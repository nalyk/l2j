package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.ADENA;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * Startup recovery (docs/spec/asset-service.md §3.2, §7, INV03/04/05/11/12/13), run once Mobius's managers are up and
 * before any login: the custody check, every pending mark removed, every unclaimed delivery checked (a claim retail made
 * without the patch step repaired, a missing message a violation, a message the mail manager lacks adopted), recorded as
 * {@code Asset.RecoveryCompleted}.
 */
class AssetRecoveryTest
{
	private static final Map<String, HoldingPolicy> OWNERS = Map.of("TEST", (connection, ref) -> true, "BROKEN", (connection, ref) -> true, "BOOT", (connection, ref) -> true, "STRAY", (connection, ref) -> true, "NOADOPT", (connection, ref) -> true, "NOFLAG", (connection, ref) -> true);
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), OWNERS, id -> MailManager.getInstance().adopt(id));
	}

	private static AssetRecovery.Report recover() throws Exception
	{
		return AssetRecovery.run(_f.database::connect, _f.service.stager(), OWNERS, id -> MailManager.getInstance().getMessage(id) != null, id -> MailManager.getInstance().adopt(id), EconomyMonitor.Kind.STARTUP);
	}

	/** Releases {@code qty} Adena from a fresh custody of {@code owner} to a new character. @return the message id */
	private static int delivery(String owner, long qty) throws Exception
	{
		final int custody = _f.open(owner);
		_f.seed(custody, ADENA, qty, false);
		final Player receiver = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final OpId op = _f.op("PAY");
		try (AssetTx tx = _f.service.begin(op, owner, "PAY", custody))
		{
			tx.release(custody, receiver.getObjectId(), List.of(new Asset(ADENA, qty, null)), MailReason.PAYOUT, "Test", "A test payout.");
			assertEquals(Outcome.COMMITTED, tx.commit());
		}
		return (int) _f.number("SELECT message_id FROM lc_mail_delivery WHERE op_id = UNHEX('" + op.hex() + "')");
	}

	@Test
	void aPendingMarkACrashLeftIsRemoved() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		_f.execute("INSERT INTO lc_asset_pending (char_id, op_id, since) VALUES (" + character.getObjectId() + ", UNHEX('" + _f.op("CRASHED").hex() + "'), NOW(3))");
		assertTrue(PendingMarks.isMarked(character.getObjectId()));
		assertTrue(recover().marksRemoved() >= 1);
		assertFalse(PendingMarks.isMarked(character.getObjectId()), "no mark survives recovery (INV13)");
	}

	@Test
	void aClaimRetailMadeWithoutThePatchStepIsRepaired() throws Exception
	{
		final int message = delivery("TEST", 50);
		_f.execute("DELETE FROM items WHERE loc = 'MAIL' AND loc_data = " + message); // retail cleared the attachments
		assertTrue(recover().claimsRepaired() >= 1);
		assertEquals(1, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE message_id = " + message + " AND claimed_at IS NOT NULL"));
	}

	@Test
	void anUnclaimedDeliveryWithoutItsMessageIsAViolation() throws Exception
	{
		final int message = delivery("BROKEN", 30);
		_f.execute("DELETE FROM messages WHERE messageId = " + message);
		final AssetRecovery.Report report = recover();
		assertTrue(report.violations().stream().anyMatch(v -> v.contains("INV12") && v.contains(String.valueOf(message))), report.violations().toString());
		assertTrue(_f.frozen("BROKEN"), "a missing message is a violation, never a claim");
		assertEquals(0, _f.number("SELECT COUNT(*) FROM lc_mail_delivery WHERE message_id = " + message + " AND claimed_at IS NOT NULL"));
	}

	@Test
	void aServiceMailTheMailManagerLacksIsAdopted() throws Exception
	{
		final int message = delivery("TEST", 20);
		final Field messages = MailManager.class.getDeclaredField("_messages");
		messages.setAccessible(true);
		((Map<?, ?>) messages.get(MailManager.getInstance())).remove(message);
		assertTrue(recover().adopted() >= 1);
		assertNotNull(MailManager.getInstance().getMessage(message));
	}

	@Test
	void aMailTheManagerCannotAdoptIsAViolation() throws Exception
	{
		final int message = delivery("NOADOPT", 15);
		final AssetRecovery.Report report = AssetRecovery.run(_f.database::connect, _f.service.stager(), OWNERS, id -> id != message && (MailManager.getInstance().getMessage(id) != null), id ->
		{
			// the production adopter logs a failed read and returns
		}, EconomyMonitor.Kind.STARTUP);
		assertTrue(report.violations().stream().anyMatch(v -> v.contains("could not adopt") && v.contains(String.valueOf(message))), report.violations().toString());
	}

	@Test
	void attachmentRowsWithoutTheFlagRetailShowsThemByAreAViolation() throws Exception
	{
		final int message = delivery("NOFLAG", 25);
		_f.execute("UPDATE messages SET hasAttachments = 'false' WHERE messageId = " + message);
		final AssetRecovery.Report report = recover();
		assertTrue(report.violations().stream().anyMatch(v -> v.contains("hasAttachments") && v.contains(String.valueOf(message))), report.violations().toString());
	}

	@Test
	void theBootstrapRecoversWithTheModulesRegisteredPolicies() throws Exception
	{
		final int registered = _f.open("BOOT");
		_f.seed(registered, ADENA, 10, false);
		final int stray = _f.open("STRAY");
		_f.seed(stray, ADENA, 10, false);
		for (String module : List.of("TEST", "BROKEN", "BOOT", "NOADOPT", "NOFLAG"))
		{
			org.l2jmobius.gameserver.livingchronicle.LivingChronicle.owner(module, OWNERS.get(module));
		}
		System.setProperty(org.l2jmobius.gameserver.livingchronicle.LivingChronicle.PROFILE, "dev");
		try
		{
			org.l2jmobius.gameserver.livingchronicle.LivingChronicle.beforeLogin();
		}
		finally
		{
			System.clearProperty(org.l2jmobius.gameserver.livingchronicle.LivingChronicle.PROFILE);
		}
		assertEquals("OPEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + registered), "its module registered the policy that let it hold");
		assertEquals("FROZEN", _f.text("SELECT state FROM lc_custody WHERE custody_id = " + stray), "no module registered a policy for it (INV05)");
	}

	@Test
	void recoveryIsRecorded() throws Exception
	{
		final long before = _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.RecoveryCompleted'");
		final AssetRecovery.Report report = recover();
		assertTrue(report.custodiesChecked() >= 0);
		final StringBuilder rows = new StringBuilder();
		try (java.sql.Connection connection = _f.database.connect();
			java.sql.ResultSet result = connection.createStatement().executeQuery("SELECT o.outbox_id, o.stream, o.stream_seq, o.payload FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.RecoveryCompleted'"))
		{
			while (result.next())
			{
				rows.append(result.getLong(1)).append(' ').append(result.getString(2)).append(' ').append(result.getLong(3)).append(' ').append(result.getString(4)).append("; ");
			}
		}
		assertEquals(before + 1, _f.number("SELECT COUNT(*) FROM lc_fact_outbox o JOIN lc_fact_type t ON t.type = o.type WHERE t.name = 'Asset.RecoveryCompleted'"), rows.toString());
	}
}
