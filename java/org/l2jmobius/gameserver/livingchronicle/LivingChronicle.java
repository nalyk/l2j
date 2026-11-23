package org.l2jmobius.gameserver.livingchronicle;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.livingchronicle.asset.AssetRecovery;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyCounter;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyMonitor;
import org.l2jmobius.gameserver.livingchronicle.asset.HoldingPolicy;
import org.l2jmobius.gameserver.livingchronicle.asset.LifecycleWriter;
import org.l2jmobius.gameserver.livingchronicle.asset.ServiceMail;
import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;

/**
 * Living Chronicle in the game server's life (docs/spec/asset-service.md §2.6, §3.2, §11.25; profiles.md §4.2 rule 4),
 * called by three core seams (CP-0027): right after the database opens, the startup census and the existence counters
 * ({@link #atDatabase}); once Mobius's managers are up and before any login, recovery ({@link #beforeLogin}); at the
 * shutdown boundary, after Mobius's last save and before the database closes, every clan warehouse written and the
 * shutdown census ({@link #atShutdown}). In {@code truth} each returns at once, so the server runs as upstream.
 */
public final class LivingChronicle
{
	/** The profile's system property (profiles.md §8). */
	public static final String PROFILE = "lc.profile";

	public enum Profile
	{
		TRUTH,
		DEV,
		SIM,
		HOBBY,
		LIVE
	}

	private static final Logger LOGGER = Logger.getLogger(LivingChronicle.class.getName());
	private static volatile EconomyMonitor.Startup _startup;
	/** Each module's holding policy, registered as the module starts, before {@link #beforeLogin} checks its custodies. */
	private static final Map<String, HoldingPolicy> OWNERS = new ConcurrentHashMap<>();

	private LivingChronicle()
	{
	}

	/**
	 * @return the profile; with no {@code lc.profile} yet, {@code truth}: the profile check that refuses to start without one
	 *         (profiles.md §4.2, §7) is its own node
	 * @throws IllegalStateException for an unknown profile name, which refuses to start (profiles.md §7)
	 */
	public static Profile profile()
	{
		final String name = System.getProperty(PROFILE);
		if (name == null)
		{
			return Profile.TRUTH;
		}
		try
		{
			return Profile.valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalStateException("unknown " + PROFILE + ": " + name + " (truth, dev, sim, hobby, live)");
		}
	}

	/**
	 * Registers the holding policy of a module's custodies (asset-service.md INV05). A module registers before
	 * {@link #beforeLogin}; recovery finds a populated custody of a module that registered none in violation of INV05.
	 */
	public static void owner(String module, HoldingPolicy policy)
	{
		OWNERS.put(module, policy);
	}

	/** @return what the last {@link #atDatabase} found, or null before it ran */
	public static EconomyMonitor.Startup startup()
	{
		return _startup;
	}

	/** Seam A, right after the database opens and before any other startup step: the census, compared, and the counters. */
	public static void atDatabase()
	{
		if (profile() == Profile.TRUTH)
		{
			return;
		}
		try (Connection connection = DatabaseFactory.getConnection())
		{
			_startup = EconomyMonitor.atStartup(connection, stager());
			LOGGER.info("Living Chronicle: " + _startup.kind() + " census " + _startup.censusId() + (_startup.differences().isEmpty() ? "" : ", differences " + _startup.differences()));
		}
		catch (SQLException e)
		{
			throw new IllegalStateException("Living Chronicle could not take the startup census (are the migrations applied?)", e);
		}
	}

	/**
	 * CP-0034, from {@code DatabaseIdManager.cleanDatabase} after its cleanup and before the id set is built: the lifecycles'
	 * startup pass and the writer of this run (asset-service.md §11.35). In {@code truth} it returns at once.
	 */
	public static void lifecyclesAtStartup()
	{
		if (profile() == Profile.TRUTH)
		{
			return;
		}
		LifecycleWriter.atStartup(DatabaseFactory::getConnection, () -> IdManager.getInstance().getNextId(), stager());
	}

	/** Seam B, once Mobius's managers are up and before any login: recovery (asset-service.md §3.2, §7). */
	public static void beforeLogin()
	{
		if (profile() == Profile.TRUTH)
		{
			return;
		}
		ServiceMail.activate();
		try
		{
			final AssetRecovery.Report report = AssetRecovery.run(DatabaseFactory::getConnection, stager(), Map.copyOf(OWNERS), id -> MailManager.getInstance().getMessage(id) != null, id -> MailManager.getInstance().adopt(id), _startup == null ? EconomyMonitor.Kind.REBASE : _startup.kind());
			LOGGER.info("Living Chronicle: recovered, " + report.custodiesChecked() + " custodies checked, " + report.marksRemoved() + " pending marks removed, " + report.claimsRepaired() + " claims repaired, " + report.adopted() + " mails adopted" + (report.violations().isEmpty() ? "" : ", violations " + report.violations()));
		}
		catch (SQLException e)
		{
			throw new IllegalStateException("Living Chronicle could not recover; no login may be accepted", e);
		}
	}

	/** How long the boundary waits for Mobius's task pools to terminate (asset-service.md §2.6). */
	static final long QUIET_MILLIS = 30_000;

	/**
	 * Seam C, the shutdown boundary (asset-service.md §2.6): once Mobius's task pools have terminated, every clan warehouse
	 * written, then the shutdown census. A pool still running after {@link #QUIET_MILLIS} means no shutdown census.
	 */
	public static void atShutdown()
	{
		atShutdown(() -> ThreadPool.awaitTermination(QUIET_MILLIS));
	}

	/** The boundary with {@code quiet} deciding whether nothing else can change a holding any more. */
	static void atShutdown(java.util.function.BooleanSupplier quiet)
	{
		if ((profile() == Profile.TRUTH) || !EconomyCounter.isActive())
		{
			return;
		}
		try
		{
			boundary(quiet);
		}
		catch (RuntimeException e)
		{
			// Mobius closes and backs up the database after the boundary: a fault here must not stop that (no census follows)
			LOGGER.log(java.util.logging.Level.SEVERE, "Living Chronicle: the shutdown boundary failed; no shutdown census, so the next start is a rebase", e);
		}
	}

	private static void boundary(java.util.function.BooleanSupplier quiet)
	{
		if (!quiet.getAsBoolean())
		{
			LOGGER.severe("Living Chronicle: Mobius's task pools are still running at the shutdown boundary; no shutdown census, so the next start is a rebase");
			return;
		}
		final List<ItemContainer> warehouses = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			warehouses.add(clan.getWarehouse());
		}
		try
		{
			if (!EconomyMonitor.atBoundary(warehouses))
			{
				LOGGER.severe("Living Chronicle: a clan warehouse could not be written at the shutdown boundary; no shutdown census, so the next start is a rebase");
			}
		}
		catch (SQLException e)
		{
			LOGGER.severe("Living Chronicle: the shutdown census failed (" + e.getMessage() + "); the next start is a rebase");
		}
	}

	private static OutboxStager stager()
	{
		return new OutboxStager(new GameClockClock(), () -> 0L, () -> 1);
	}

	/** The game clock as a {@link Clock}, so facts are stamped with game time (CoreClockCoverageTest). */
	private static final class GameClockClock extends Clock
	{
		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return Instant.ofEpochMilli(GameClock.millis());
		}
	}
}
