package org.l2jmobius.gameserver.livingchronicle.asset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import org.l2jmobius.gameserver.livingchronicle.clock.GameClock;
import org.l2jmobius.gameserver.livingchronicle.journal.OutboxStager;

/**
 * Startup recovery (docs/spec/asset-service.md §3.2, §7), run once Mobius's managers are up and before any login: every
 * custody is checked (INV03, INV04, INV05, INV11), every pending mark is removed (INV13: an operation that committed
 * cleared its own, so one left belongs to an operation that never did), and every unclaimed delivery is checked (INV12):
 * its message must be a service mail whose attachment rows are exactly the operation's MAIL credit legs; one whose
 * attachments retail cleared is a claim whose patch step did not run, and is recorded as claimed; a missing message, one
 * whose attachment rows lack the {@code hasAttachments} flag retail shows them by, and one the mail manager lacks and
 * cannot adopt are violations; one it lacks is otherwise adopted. Recorded as {@code Asset.RecoveryCompleted}.
 */
public final class AssetRecovery
{
	/**
	 * @param custodiesChecked custodies the check read
	 * @param violations one line per violation found
	 * @param marksRemoved pending marks removed
	 * @param claimsRepaired deliveries recorded as claimed because retail had cleared their attachments
	 * @param adopted service mails the mail manager did not know
	 */
	public record Report(int custodiesChecked, List<String> violations, int marksRemoved, int claimsRepaired, int adopted)
	{
	}

	private AssetRecovery()
	{
	}

	/**
	 * @param mailKnown whether the mail manager knows a message
	 * @param adopt makes the mail manager load a message
	 * @param census the kind of the startup census taken before (asset-service.md §2.6)
	 */
	public static Report run(Connections db, OutboxStager stager, Map<String, HoldingPolicy> owners, IntPredicate mailKnown, IntConsumer adopt, EconomyMonitor.Kind census) throws SQLException
	{
		final CustodyCheck.Report custodies = new CustodyCheck(db, stager, owners).run();
		final List<String> violations = new ArrayList<>(custodies.violations());
		int marks;
		int claims = 0;
		int adopted = 0;
		try (Connection connection = db.open())
		{
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM lc_asset_pending"))
			{
				marks = delete.executeUpdate();
			}
			final List<long[]> deliveries = new ArrayList<>();
			final List<byte[]> ops = new ArrayList<>();
			try (PreparedStatement read = connection.prepareStatement("SELECT message_id, op_id FROM lc_mail_delivery WHERE claimed_at IS NULL ORDER BY message_id");
				ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					deliveries.add(new long[]
					{
						result.getInt(1)
					});
					ops.add(result.getBytes(2));
				}
			}
			for (int i = 0; i < deliveries.size(); i++)
			{
				final int message = (int) deliveries.get(i)[0];
				final byte[] op = ops.get(i);
				if (!serviceMail(connection, message))
				{
					final String violation = "INV12: unclaimed delivery " + message + " names no service mail";
					violations.add(violation);
					Violations.record(connection, stager, custodyOf(connection, op), "INV12", violation);
					connection.setAutoCommit(true);
					continue;
				}
				final Map<Integer, Long> rows = attachments(connection, message);
				if (rows.isEmpty())
				{
					// Retail cleared the attachments: a claim whose patch step (CP-0015) did not run.
					try (PreparedStatement claim = connection.prepareStatement("UPDATE lc_mail_delivery SET claimed_at = ? WHERE message_id = ? AND claimed_at IS NULL"))
					{
						claim.setTimestamp(1, new Timestamp(GameClock.millis()));
						claim.setInt(2, message);
						claims += claim.executeUpdate();
					}
					continue;
				}
				if (!flagged(connection, message))
				{
					// Retail shows no attachments without the flag (MailMessage.getAttachments): the payout is unreachable.
					final String violation = "INV12: message " + message + " has attachment rows but hasAttachments is false";
					violations.add(violation);
					Violations.record(connection, stager, custodyOf(connection, op), "INV12", violation);
					connection.setAutoCommit(true);
					continue;
				}
				final Map<Integer, Long> legs = legs(connection, op, message);
				if (!legs.equals(rows))
				{
					final String violation = "INV12: message " + message + " has attachments " + rows + " for legs " + legs;
					violations.add(violation);
					Violations.record(connection, stager, custodyOf(connection, op), "INV12", violation);
					connection.setAutoCommit(true);
				}
				if (!mailKnown.test(message))
				{
					adopt.accept(message);
					if (mailKnown.test(message))
					{
						adopted++;
					}
					else
					{
						final String violation = "INV12: the mail manager does not know message " + message + " and could not adopt it";
						violations.add(violation);
						Violations.record(connection, stager, custodyOf(connection, op), "INV12", violation);
						connection.setAutoCommit(true);
					}
				}
			}
			connection.setAutoCommit(false);
			try
			{
				stager.stage(connection, null, List.of(new AssetFact("Asset.RecoveryCompleted", "asset.recovery", Json.object(fields(custodies.custodiesChecked(), census, marks, claims, adopted, violations.size())))));
				connection.commit();
			}
			catch (SQLException e)
			{
				connection.rollback();
				throw e;
			}
		}
		return new Report(custodies.custodiesChecked(), List.copyOf(violations), marks, claims, adopted);
	}

	private static Map<String, Object> fields(int custodies, EconomyMonitor.Kind census, int marks, int claims, int adopted, int violations)
	{
		final Map<String, Object> fields = new java.util.LinkedHashMap<>();
		fields.put("custodies_checked", custodies);
		fields.put("census", census.name());
		fields.put("marks_removed", marks);
		fields.put("claims_repaired", claims);
		fields.put("adopted", adopted);
		fields.put("violations", violations);
		return fields;
	}

	private static boolean serviceMail(Connection connection, int message) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT 1 FROM messages WHERE messageId = ? AND senderId = -1 AND sendBySystem <> 0 AND reqAdena = 0"))
		{
			read.setInt(1, message);
			try (ResultSet result = read.executeQuery())
			{
				return result.next();
			}
		}
	}

	private static boolean flagged(Connection connection, int message) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT 1 FROM messages WHERE messageId = ? AND hasAttachments = 'true'"))
		{
			read.setInt(1, message);
			try (ResultSet result = read.executeQuery())
			{
				return result.next();
			}
		}
	}

	private static Map<Integer, Long> attachments(Connection connection, int message) throws SQLException
	{
		final Map<Integer, Long> rows = new TreeMap<>();
		try (PreparedStatement read = connection.prepareStatement("SELECT item_id, SUM(count) FROM items WHERE owner_id = -1 AND loc = 'MAIL' AND loc_data = ? GROUP BY item_id"))
		{
			read.setInt(1, message);
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					rows.put(result.getInt(1), result.getLong(2));
				}
			}
		}
		return rows;
	}

	/** @return the operation's MAIL legs to {@code message}, credits less debits, per item id */
	private static Map<Integer, Long> legs(Connection connection, byte[] op, int message) throws SQLException
	{
		final Map<Integer, Long> legs = new TreeMap<>();
		try (PreparedStatement read = connection.prepareStatement("SELECT item_id, SUM(CASE WHEN direction = 'CREDIT' THEN qty ELSE -qty END) FROM lc_ledger_posting WHERE op_id = ? AND account_kind = 'MAIL' AND account_id = ? GROUP BY item_id"))
		{
			read.setBytes(1, op);
			read.setInt(2, message);
			try (ResultSet result = read.executeQuery())
			{
				while (result.next())
				{
					if (result.getLong(2) != 0)
					{
						legs.put(result.getInt(1), result.getLong(2));
					}
				}
			}
		}
		return legs;
	}

	/** @return the custody the operation paid the message from, or 0 (the ASSET module's) when it paid from none */
	private static int custodyOf(Connection connection, byte[] op) throws SQLException
	{
		try (PreparedStatement read = connection.prepareStatement("SELECT account_id FROM lc_ledger_posting WHERE op_id = ? AND account_kind = 'CUSTODY' AND direction = 'DEBIT' ORDER BY account_id LIMIT 1"))
		{
			read.setBytes(1, op);
			try (ResultSet result = read.executeQuery())
			{
				return result.next() ? result.getInt(1) : 0;
			}
		}
	}
}
