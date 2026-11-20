package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.holders.CharacterInfoHolder;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * CP-0016 (docs/spec/asset-service.md §2.1, §11.21, ASSET-R27): a character with unclaimed service mail or a pending mark
 * is not marked for deletion, by the client's own deletion request ({@code GameClient.markToDeleteChar}), and a
 * character marked for deletion receives no service mail; the two serialise on the character's row. AT-ASSET-29.
 */
class DeletionGuardTest
{
	private static AssetFixtures _f;

	@BeforeAll
	static void boot() throws Exception
	{
		final TestServer server = TestServer.boot();
		_f = new AssetFixtures(server.database(), () -> IdManager.getInstance().getNextId(), Map.of("TEST", (connection, ref) -> true), id -> MailManager.getInstance().adopt(id));
	}

	/** The deletion request a client sends for its only character slot (CharacterDelete → markToDeleteChar). */
	private static byte requestDeletion(Player character) throws Exception
	{
		final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		final Object unsafe = unsafeField.get(null);
		// A client without a network connection: only its character slots are used here.
		final GameClient client = (GameClient) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, GameClient.class);
		client.setCharSelection(List.of(new CharacterInfoHolder(character.getObjectId(), character.getName())));
		return client.markToDeleteChar(0);
	}

	private static long deleteTime(Player character) throws Exception
	{
		return _f.number("SELECT deletetime FROM characters WHERE charId = " + character.getObjectId());
	}

	private static int releaseTo(Player receiver) throws Exception
	{
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 10, false);
		final OpId op = _f.op("RELEASE");
		try (AssetTx tx = _f.service.begin(op, "TEST", "RELEASE", custody))
		{
			tx.release(custody, receiver.getObjectId(), List.of(new Asset(ADENA, 10, null)), MailReason.PAYOUT, "Notarius", "Paid.");
			tx.commit();
		}
		return (int) _f.number("SELECT message_id FROM lc_mail_delivery WHERE op_id = UNHEX('" + op.hex() + "')");
	}

	@Test
	void aCharacterWithUnclaimedServiceMailIsNotMarked() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		final int message = releaseTo(character);
		assertEquals(3, requestDeletion(character), "refused: answered with CharDeleteFail(REASON_DELETION_FAILED)");
		assertEquals(0, deleteTime(character));
		_f.execute("UPDATE lc_mail_delivery SET claimed_at = NOW(3) WHERE message_id = " + message);
		assertEquals(0, requestDeletion(character), "once claimed, deletion goes ahead as in retail");
		assertEquals(1, _f.number("SELECT COUNT(*) FROM characters WHERE charId = " + character.getObjectId() + " AND deletetime > 0"));
	}

	@Test
	void aCharacterWithAPendingMarkIsNotMarked() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		_f.execute("INSERT INTO lc_asset_pending (char_id, op_id, since) VALUES (" + character.getObjectId() + ", UNHEX('" + _f.op("TAKE").hex() + "'), NOW(3))");
		assertEquals(3, requestDeletion(character));
		assertEquals(0, deleteTime(character));
	}

	@Test
	void aMarkedCharacterReceivesNoServiceMail() throws Exception
	{
		final Player character = TestPlayers.create(PlayerClass.FIGHTER, 20);
		assertEquals(0, requestDeletion(character));
		final int custody = _f.open("TEST");
		_f.seed(custody, ADENA, 10, false);
		try (AssetTx tx = _f.service.begin(_f.op("RELEASE"), "TEST", "RELEASE", custody))
		{
			tx.release(custody, character.getObjectId(), List.of(new Asset(ADENA, 10, null)), MailReason.PAYOUT, "Notarius", "Paid.");
			assertEquals(AssetAbort.Reason.RECEIVER_GONE, assertThrows(AssetAbort.class, tx::commit).reason());
		}
	}
}
