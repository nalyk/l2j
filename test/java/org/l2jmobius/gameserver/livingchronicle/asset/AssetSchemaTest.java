package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.managers.DatabaseIdManager;
import org.l2jmobius.testing.Migrations;
import org.l2jmobius.testing.TestDatabase;

/**
 * AT-ASSET-00 (docs/spec/asset-service.md): every migration applies in order on the stock schema, and its keys and checks reject what the spec forbids. Also the part of AT-ASSET-08/09 that needs no server: an escrow row (spec §2.3) passes upstream's own startup cleanup and keeps its object id reserved.
 */
class AssetSchemaTest
{
	private static final String OP = "UNHEX('00112233445566778899AABBCCDDEEFF')";
	private static final String OTHER_OP = "UNHEX('FFEEDDCCBBAA99887766554433221100')";
	/** The operation of the service-mail test; never OTHER_OP, which the constraint test needs absent. */
	private static final String MAIL_OP = "UNHEX('0F0E0D0C0B0A09080706050403020100')";

	private static TestDatabase _database;

	@BeforeAll
	static void migrate() throws Exception
	{
		_database = TestDatabase.start();
		try (Connection connection = _database.connect())
		{
			Migrations.applyAll(connection);
		}
	}

	@Test
	void everyTableOfTheSpecExists() throws SQLException
	{
		final Set<String> tables = new HashSet<>();
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement();
			ResultSet result = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + TestDatabase.SCHEMA + "' AND table_name LIKE 'lc\\_%'"))
		{
			while (result.next())
			{
				tables.add(result.getString(1));
			}
		}
		final Set<String> spec = Set.of("lc_custody", "lc_asset_op", "lc_ledger_posting", "lc_settlement_key", "lc_item_lifecycle", "lc_economy_bucket", "lc_economy_census", "lc_economy_counter", "lc_module_state", "lc_mail_delivery", "lc_asset_pending");
		assertTrue(tables.containsAll(spec), "missing: " + spec.stream().filter(t -> !tables.contains(t)).toList());
	}

	@Test
	void theKeysAndChecksRejectWhatTheSpecForbids() throws SQLException
	{
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (" + OP + ", 'PACT', 'fund', 1, NOW(3))");

			// A posting moves a positive quantity, of an operation that committed (ASSET-INV01's ground rules).
			assertThrows(SQLException.class, () -> statement.execute(posting(OP, 0, 0)));
			assertThrows(SQLException.class, () -> statement.execute(posting(OTHER_OP, 0, 5)));
			statement.execute(posting(OP, 0, 5));
			assertThrows(SQLException.class, () -> statement.execute(posting(OP, 0, 5)), "two postings with one leg number");
			// A source or sink posting names its code (spec §2.5); a holder's does not.
			assertThrows(SQLException.class, () -> statement.execute(sink(OP, 1, "NULL")));
			statement.execute(sink(OP, 1, "'FEE_NOTARIUS'"));

			// A settlement key is used once (ASSET-R03).
			statement.execute("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES ('PACT', '7/1', " + OP + ")");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_settlement_key (scope, skey, op_id) VALUES ('PACT', '7/1', " + OP + ")"));

			// A closed custody says which operation closed it, and when; an open one says neither. Deliveries are mail, not custodies.
			assertThrows(SQLException.class, () -> statement.execute(custody("CONTRACT", "CLOSED", "NULL")), "closed by no operation");
			assertThrows(SQLException.class, () -> statement.execute(custody("DELIVERY", "OPEN", "NULL")), "a delivery custody");
			assertThrows(SQLException.class, () -> statement.execute(custody("CONTRACT", "OPEN", OP)), "an open custody with a closing operation");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_custody (kind, owner_module, owner_ref, state, created_op, closed_op, created_at, closed_at) VALUES ('CONTRACT', 'PACT', 7, 'CLOSED', " + OP + ", " + OP + ", NOW(3), NULL)"), "closed with no time");
			statement.execute(custody("CONTRACT", "CLOSED", OP));

			// At most one live lifecycle per object id (ASSET-INV08); an ended one, with its reason, makes room for the next.
			statement.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at) VALUES (268500001, 57, NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at) VALUES (268500001, 57, NOW(3))"));
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_item_lifecycle SET ended_at = NOW(3) WHERE object_id = 268500001"), "an end without its reason");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_item_lifecycle SET ended_at = NOW(3), end_reason = 'MERGED' WHERE object_id = 268500001"), "a merge without the stack it went into");
			assertThrows(SQLException.class, () -> statement.execute("UPDATE lc_item_lifecycle SET merged_into = lifecycle_id WHERE object_id = 268500001"), "a live lifecycle already merged into another");
			statement.execute("UPDATE lc_item_lifecycle SET ended_at = NOW(3), end_reason = 'DESTROYED' WHERE object_id = 268500001");
			statement.execute("INSERT INTO lc_item_lifecycle (object_id, item_id, began_at) VALUES (268500001, 57, NOW(3))");

			// A mail delivery belongs to an operation that committed, and names one message once. (That the message exists while the
			// delivery is unclaimed is INV12, checked by the service and the census, not by a key: retail deletes claimed messages.)
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mail_delivery (message_id, op_id, receiver_char, reason, claimed_at) VALUES (268700001, " + OTHER_OP + ", 268477000, 'PAYOUT', NOW(3))"));
			statement.execute("INSERT INTO lc_mail_delivery (message_id, op_id, receiver_char, reason, claimed_at) VALUES (268700001, " + OP + ", 268477000, 'PAYOUT', NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_mail_delivery (message_id, op_id, receiver_char, reason) VALUES (268700001, " + OP + ", 268477000, 'REFUND')"));

			// One pending mark per character and operation.
			statement.execute("INSERT INTO lc_asset_pending (char_id, op_id, since) VALUES (268477000, " + OP + ", NOW(3))");
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_asset_pending (char_id, op_id, since) VALUES (268477000, " + OP + ", NOW(3))"));

			// A frozen module says why.
			assertThrows(SQLException.class, () -> statement.execute("INSERT INTO lc_module_state (module, state, since) VALUES ('PACT', 'FROZEN', NOW(3))"));
			statement.execute("INSERT INTO lc_module_state (module, state, reason, since) VALUES ('PACT', 'FROZEN', 'ASSET-INV03', NOW(3))");
		}
	}

	/**
	 * Service mail (spec §2.1) as the service writes it: a system message from sender -1 that cannot be rejected, is not cash on delivery and never expires, with an attachment row owned by -1 in MAIL. Upstream's own startup cleanup keeps both (DatabaseIdManager.java:68 keeps -1 MAIL rows of a sender -1 message), and the attachment's object id stays reserved.
	 */
	@Test
	void serviceMailPassesUpstreamCleanupAndKeepsItsIdReserved() throws Exception
	{
		final int message = 268800001;
		final int attachment = 268800002;
		final int strayMail = 268800003;
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute("INSERT INTO messages (messageId, senderId, receiverId, subject, content, expiration, reqAdena, hasAttachments, sendBySystem) VALUES (" + message + ", -1, 268477000, 'Notarius', 'Contract 7', 253402300799000, 0, 'true', 1)");
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use) VALUES (-1, " + attachment + ", 57, 1000, 0, 'MAIL', " + message + ", 0)");
			// A -1 MAIL row of no system message is what upstream deletes: proof the rule ran.
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use) VALUES (-1, " + strayMail + ", 57, 1, 0, 'MAIL', 999999, 0)");
			statement.execute("INSERT INTO lc_asset_op (op_id, module, action, subject_ref, committed_at) VALUES (" + MAIL_OP + ", 'PACT', 'release', 7, NOW(3))");
			statement.execute("INSERT INTO lc_mail_delivery (message_id, op_id, receiver_char, reason) VALUES (" + message + ", " + MAIL_OP + ", 268477000, 'PAYOUT')");

			for (String cleanup : queries("CLEANUP_QUERIES"))
			{
				statement.execute(cleanup);
			}

			assertEquals(1, count(statement, "SELECT COUNT(*) FROM messages WHERE messageId = " + message + " AND senderId = -1 AND sendBySystem <> 0 AND reqAdena = 0 AND hasAttachments = 'true'"));
			assertEquals(1, count(statement, "SELECT COUNT(*) FROM items WHERE object_id = " + attachment + " AND owner_id = -1 AND loc = 'MAIL' AND loc_data = " + message));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM items WHERE object_id = " + strayMail), "the cleanup did not run: the stray -1 MAIL row is still there");

			final Set<Integer> used = new HashSet<>();
			for (String extract : queries("EXTRACT_USED_OBJECT_ID_QUERIES"))
			{
				try (ResultSet result = statement.executeQuery(extract))
				{
					while (result.next())
					{
						used.add(result.getInt(1));
					}
				}
			}
			assertTrue(used.contains(attachment) && used.contains(message), "the mail's ids are not reserved");
		}
	}

	/**
	 * An escrow row is owner -1, location ESCROW, its live custody in loc_data. Upstream's startup cleanup, run as written, keeps it and its augmentation, deletes a real orphan beside it, and counts its object id as used.
	 */
	@Test
	void anEscrowRowPassesUpstreamCleanupAndKeepsItsIdReserved() throws Exception
	{
		final int escrowed = 268600001;
		final int orphan = 268600002;
		try (Connection connection = _database.connect();
			Statement statement = connection.createStatement())
		{
			statement.execute(custody("CONTRACT", "OPEN", "NULL"), Statement.RETURN_GENERATED_KEYS);
			final long custody;
			try (ResultSet key = statement.getGeneratedKeys())
			{
				key.next();
				custody = key.getLong(1);
			}
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use) VALUES (-1, " + escrowed + ", 6656, 1, 7, 'ESCROW', " + custody + ", 0)");
			statement.execute("INSERT INTO item_attributes (itemId, augAttributes) VALUES (" + escrowed + ", 12345)");
			statement.execute("INSERT INTO items (owner_id, object_id, item_id, count, enchant_level, loc, loc_data, time_of_use) VALUES (999999, " + orphan + ", 57, 100, 0, 'INVENTORY', 0, 0)");

			for (String cleanup : queries("CLEANUP_QUERIES"))
			{
				statement.execute(cleanup);
			}

			assertEquals(1, count(statement, "SELECT COUNT(*) FROM items WHERE object_id = " + escrowed + " AND loc = 'ESCROW' AND enchant_level = 7"));
			assertEquals(1, count(statement, "SELECT COUNT(*) FROM item_attributes WHERE itemId = " + escrowed));
			assertEquals(0, count(statement, "SELECT COUNT(*) FROM items WHERE object_id = " + orphan), "the cleanup did not run: the orphan beside the escrow row is still there");

			final Set<Integer> used = new HashSet<>();
			for (String extract : queries("EXTRACT_USED_OBJECT_ID_QUERIES"))
			{
				try (ResultSet result = statement.executeQuery(extract))
				{
					while (result.next())
					{
						used.add(result.getInt(1));
					}
				}
			}
			assertTrue(used.contains(escrowed), "the escrowed object id is not reserved");
		}
	}

	private static String posting(String op, int leg, long qty)
	{
		return "INSERT INTO lc_ledger_posting (op_id, leg, account_kind, account_id, item_id, qty, direction) VALUES (" + op + ", " + leg + ", 'PLAYER', 268477000, 57, " + qty + ", 'DEBIT')";
	}

	private static String sink(String op, int leg, String code)
	{
		return "INSERT INTO lc_ledger_posting (op_id, leg, account_kind, account_id, code, item_id, qty, direction) VALUES (" + op + ", " + leg + ", 'SINK', 0, " + code + ", 57, 5, 'CREDIT')";
	}

	private static String custody(String kind, String state, String closedOp)
	{
		final String closedAt = "NULL".equals(closedOp) ? "NULL" : "NOW(3)";
		return "INSERT INTO lc_custody (kind, owner_module, owner_ref, state, created_op, closed_op, created_at, closed_at) VALUES ('" + kind + "', 'PACT', 7, '" + state + "', " + OP + ", " + closedOp + ", NOW(3), " + closedAt + ")";
	}

	private static long count(Statement statement, String sql) throws SQLException
	{
		try (ResultSet result = statement.executeQuery(sql))
		{
			result.next();
			return result.getLong(1);
		}
	}

	/**
	 * Upstream's own statements, read from the class, so that a change to them upstream is tested rather than a copy.
	 */
	private static String[] queries(String field) throws ReflectiveOperationException
	{
		final Field queries = DatabaseIdManager.class.getDeclaredField(field);
		queries.setAccessible(true);
		return (String[]) queries.get(null);
	}
}
