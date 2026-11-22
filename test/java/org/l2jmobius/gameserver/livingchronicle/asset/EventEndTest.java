package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.l2jmobius.gameserver.livingchronicle.asset.AssetFixtures.SWORD;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Pet;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.mechanics.script.LongTimeEvent;
import org.l2jmobius.gameserver.network.enums.MessageSenderType;
import org.l2jmobius.gameserver.network.holders.MailMessage;
import org.l2jmobius.testing.TestPlayers;
import org.l2jmobius.testing.TestServer;

/**
 * An ended event destroys its items wherever they are loaded (docs/spec/asset-service.md §11.30, CP-0030): upstream
 * destroyed them from online inventories and deleted the rest by SQL, so a copy loaded elsewhere (a clan warehouse, an
 * online player's warehouse, a loaded mail attachment) outlived the event and stayed usable until the restart, and so
 * did both copies of a non-stackable item of which one was worn (the inventory asked its unequipped copies for both).
 */
class EventEndTest
{
	private static final int MEDAL = 6393;
	private static final int WOLF = 12077;
	private static final int WOLF_COLLAR = 2375;
	private static final AtomicInteger NAMES = new AtomicInteger();

	@BeforeAll
	static void boot() throws Exception
	{
		TestServer.boot();
		org.l2jmobius.testing.Migrations.ensure();
		EconomyCounter.activate(Map.of());
	}

	@AfterAll
	static void stop()
	{
		EconomyCounter.deactivate();
	}

	private static Player online()
	{
		final Player player = TestPlayers.create(PlayerClass.FIGHTER, 40);
		if (World.getPlayer(player.getObjectId()) == null)
		{
			World.addObject(player);
		}
		return player;
	}

	private static void endEvent(int... itemIds) throws Exception
	{
		final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		final Object unsafe = unsafeField.get(null);
		final LongTimeEvent event = (LongTimeEvent) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, LongTimeEvent.class);
		final Field items = LongTimeEvent.class.getDeclaredField("_destroyItemsOnEnd");
		items.setAccessible(true);
		final List<Integer> list = new ArrayList<>();
		for (int itemId : itemIds)
		{
			list.add(itemId);
		}
		items.set(event, list);
		final Method destroy = LongTimeEvent.class.getDeclaredMethod("destroyItemsOnEnd");
		destroy.setAccessible(true);
		destroy.invoke(event);
	}

	private static long held(ItemContainer container, int itemId)
	{
		if (container == null)
		{
			return 0; // a message whose attachments were removed has none
		}
		long count = 0;
		for (Item item : container.getAllItemsByItemId(itemId))
		{
			count += item.getCount();
		}
		return count;
	}

	private static long counted(int itemId)
	{
		return EconomyCounter.snapshot().getOrDefault(itemId, 0L);
	}

	@Test
	void aLoadedClanWarehouseLosesTheEventsItems() throws Exception
	{
		final Clan clan = ClanTable.getInstance().createClan(online(), "EventEnd" + NAMES.incrementAndGet());
		assertTrue(clan != null, "the test clan is created");
		clan.getWarehouse().addItem(ItemProcessType.NONE, MEDAL, 5, null, null);
		final long before = counted(MEDAL);
		endEvent(MEDAL);
		assertEquals(0, held(clan.getWarehouse(), MEDAL), "upstream left the loaded copy usable until the restart");
		assertEquals(before - 5, counted(MEDAL), "its quantity left existence once");
	}

	@Test
	void everyCopyOfANonStackableEventItemGoesFromAnOnlineInventory() throws Exception
	{
		final Player player = online();
		player.getInventory().addItem(ItemProcessType.NONE, SWORD, 1, player, null);
		player.getInventory().addItem(ItemProcessType.NONE, SWORD, 1, player, null);
		final long before = counted(SWORD);
		endEvent(SWORD);
		assertEquals(0, held(player.getInventory(), SWORD), "every copy, as upstream already did for an inventory");
		assertEquals(before - 2, counted(SWORD));
	}

	@Test
	void anEquippedCopyBesideAnotherGoesToo() throws Exception
	{
		final Player player = online();
		final Item worn = player.getInventory().addItem(ItemProcessType.NONE, SWORD, 1, player, null);
		player.getInventory().addItem(ItemProcessType.NONE, SWORD, 1, player, null);
		player.getInventory().equipItem(worn);
		assertTrue(worn.isEquipped(), "the test sword is worn");
		final long before = counted(SWORD);
		endEvent(SWORD);
		assertEquals(0, held(player.getInventory(), SWORD), "upstream asked the unequipped copies for both and destroyed none");
		assertEquals(before - 2, counted(SWORD));
	}

	@Test
	void anOnlinePlayersWarehouseLosesTheEventsItems() throws Exception
	{
		final Player player = online();
		player.getWarehouse().addItem(ItemProcessType.NONE, MEDAL, 3, player, null);
		final long before = counted(MEDAL);
		endEvent(MEDAL);
		assertEquals(0, held(player.getWarehouse(), MEDAL));
		assertEquals(before - 3, counted(MEDAL));
	}

	@Test
	void aSummonedPetLosesTheEventsItems() throws Exception
	{
		final Player owner = online();
		final Item collar = owner.getInventory().addItem(ItemProcessType.NONE, WOLF_COLLAR, 1, owner, null);
		final Pet wolf = Pet.spawnPet(NpcData.getInstance().getTemplate(WOLF), owner, collar);
		assertTrue(wolf != null, "the test wolf is summoned");
		owner.setPet(wolf);
		try
		{
			wolf.getInventory().addItem(ItemProcessType.NONE, MEDAL, 6, owner, null);
			final long before = counted(MEDAL);
			endEvent(MEDAL);
			assertEquals(0, held(wolf.getInventory(), MEDAL));
			assertEquals(before - 6, counted(MEDAL));
		}
		finally
		{
			owner.setPet(null);
			World.removePet(owner.getObjectId());
		}
	}

	@Test
	void anItemOnTheGroundDecays() throws Exception
	{
		final Player player = online();
		final Item medal = ItemManager.createItem(ItemProcessType.NONE, MEDAL, 2, player, null);
		medal.dropMe(player, player.getX(), player.getY(), player.getZ());
		assertTrue(medal.isSpawned(), "the test medal lies on the ground");
		final long before = counted(MEDAL);
		endEvent(MEDAL);
		assertTrue(!medal.isSpawned(), "upstream left it on the ground until it decayed");
		assertEquals(before, counted(MEDAL), "the unsaved ground is no counted holding");
	}

	@Test
	void aBuybackListLosesTheEventsItems() throws Exception
	{
		final boolean allowed = GeneralConfig.ALLOW_REFUND;
		GeneralConfig.ALLOW_REFUND = true;
		try
		{
			final Player player = online();
			player.getRefund().addItem(ItemProcessType.NONE, MEDAL, 2, player, null);
			endEvent(MEDAL);
			assertEquals(0, held(player.getRefund(), MEDAL), "RequestRefundItem would have bought it back after the event");
		}
		finally
		{
			GeneralConfig.ALLOW_REFUND = allowed;
		}
	}

	private static MailMessage cashOnDelivery(Player receiver, int medals) throws Exception
	{
		final MailMessage mail = new MailMessage(online().getObjectId(), receiver.getObjectId(), true, "For sale", "Medals.", 1_000);
		mail.createAttachments().addItem(ItemProcessType.NONE, MEDAL, medals, null, null);
		MailManager.getInstance().sendMessage(mail);
		return mail;
	}

	@Test
	void aLoadedCashOnDeliveryMailEmptiedByTheEventHasNothingToClaim() throws Exception
	{
		final MailMessage mail = cashOnDelivery(online(), 3);
		endEvent(MEDAL);
		assertTrue(!mail.hasAttachments(), "retail's claim would charge 1 000 Adena for an empty delivery");
	}

	@Test
	void anUnloadedCashOnDeliveryMailEmptiedByTheEventHasNothingToClaim() throws Exception
	{
		final MailMessage mail = cashOnDelivery(online(), 3);
		final Method unload = MailMessage.class.getDeclaredMethod("unloadAttachments");
		unload.setAccessible(true);
		unload.invoke(mail);
		assertTrue(mail.getLoadedAttachments() == null, "the attachments are unloaded");
		endEvent(MEDAL);
		assertTrue(!mail.hasAttachments(), "its rows were deleted by the purge");
	}

	@Test
	void aMailKeepingOtherAttachmentsKeepsThem() throws Exception
	{
		final Player receiver = online();
		final MailMessage mail = cashOnDelivery(receiver, 3);
		mail.getAttachments().addItem(ItemProcessType.NONE, SWORD, 1, null, null);
		endEvent(MEDAL);
		assertTrue(mail.hasAttachments());
		assertEquals(1, held(mail.getAttachments(), SWORD));
	}

	@Test
	void aLoadedMailAttachmentLosesTheEventsItems() throws Exception
	{
		final Player receiver = online();
		final MailMessage mail = new MailMessage(receiver.getObjectId(), "Event", "A gift.", MessageSenderType.NEWS);
		mail.createAttachments().addItem(ItemProcessType.NONE, MEDAL, 4, null, null);
		MailManager.getInstance().sendMessage(mail);
		final long before = counted(MEDAL);
		endEvent(MEDAL);
		assertEquals(0, held(mail.getAttachments(), MEDAL), "the claim would have moved the loaded copy into the inventory");
		assertEquals(before - 4, counted(MEDAL));
	}
}
