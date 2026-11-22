/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.entity.residences;

import static org.l2jmobius.gameserver.entity.itemcontainer.Inventory.ADENA_ID;
import static org.l2jmobius.gameserver.entity.itemcontainer.Inventory.MAX_ADENA;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.sql.ClanHallTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.livingchronicle.asset.Census;
import org.l2jmobius.gameserver.livingchronicle.asset.EconomyCounter;
import org.l2jmobius.gameserver.managers.ClanHallAuctionManager;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.network.SystemMessageId;

public class ClanHallAuction
{
	protected static final Logger LOGGER = Logger.getLogger(ClanHallAuction.class.getName());
	private int _id = 0;
	private long _endDate;
	private int _highestBidderId = 0;
	private String _highestBidderName = "";
	private long _highestBidderMaxBid = 0;
	private int _itemId = 0;
	private String _itemName = "";
	private int _itemObjectId = 0;
	private final long _itemQuantity = 0;
	private String _itemType = "";
	/** Serialises every sale's confirmation (CP-0029). */
	private static final Object CONFIRMATIONS = new Object();
	/** Set once the sale is deleted, ended or cancelled (CP-0031). */
	private volatile boolean _over;
	/** The sale's scheduled end (CP-0031), cancelled with it. */
	private volatile ScheduledFuture<?> _endTask;
	
	private int _sellerId = 0;
	private String _sellerClanName = "";
	private String _sellerName = "";
	private long _currentBid = 0;
	private long _startingBid = 0;
	
	private final Map<Integer, Bidder> _bidders = new ConcurrentHashMap<>();
	
	private static final String[] ItemTypeName =
	{
		"ClanHall"
	};
	
	public static class Bidder
	{
		private final String _name; // TODO replace with objId
		private final String _clanName;
		private long _bid;
		private final Calendar _timeBid;
		
		public Bidder(String name, String clanName, long bid, long timeBid)
		{
			_name = name;
			_clanName = clanName;
			_bid = bid;
			_timeBid = Calendar.getInstance();
			_timeBid.setTimeInMillis(timeBid);
		}
		
		public String getName()
		{
			return _name;
		}
		
		public String getClanName()
		{
			return _clanName;
		}
		
		public long getBid()
		{
			return _bid;
		}
		
		public Calendar getTimeBid()
		{
			return _timeBid;
		}
		
		public void setTimeBid(long timeBid)
		{
			_timeBid.setTimeInMillis(timeBid);
		}
		
		public void setBid(long bid)
		{
			_bid = bid;
		}
	}
	
	/** Task Sheduler for endAuction */
	public class AutoEndTask implements Runnable
	{
		@Override
		public void run()
		{
			try
			{
				endAuction();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.SEVERE, "", e);
			}
		}
	}
	
	/**
	 * Constructor
	 * @param auctionId
	 */
	public ClanHallAuction(int auctionId)
	{
		_id = auctionId;
		load();
		startAutoTask();
	}
	
	public ClanHallAuction(int itemId, Clan clan, long delay, long bid, String name)
	{
		_id = itemId;
		_endDate = System.currentTimeMillis() + delay;
		_itemId = itemId;
		_itemName = name;
		_itemType = "ClanHall";
		_sellerId = clan.getLeaderId();
		_sellerName = clan.getLeaderName();
		_sellerClanName = clan.getName();
		_startingBid = bid;
	}
	
	/** Load auctions */
	private void load()
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("Select * from auction where id = ?"))
		{
			ps.setInt(1, _id);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					_currentBid = rs.getLong("currentBid");
					_endDate = rs.getLong("endDate");
					_itemId = rs.getInt("itemId");
					_itemName = rs.getString("itemName");
					_itemObjectId = rs.getInt("itemObjectId");
					_itemType = rs.getString("itemType");
					_sellerId = rs.getInt("sellerId");
					_sellerClanName = rs.getString("sellerClanName");
					_sellerName = rs.getString("sellerName");
					_startingBid = rs.getLong("startingBid");
				}
			}
			
			loadBid();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Exception: Auction.load(): " + e.getMessage(), e);
		}
	}
	
	/** Load bidders **/
	private void loadBid()
	{
		_highestBidderId = 0;
		_highestBidderName = "";
		_highestBidderMaxBid = 0;
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT bidderId, bidderName, maxBid, clan_name, time_bid FROM auction_bid WHERE auctionId = ? ORDER BY maxBid DESC"))
		{
			ps.setInt(1, _id);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					if (rs.isFirst())
					{
						_highestBidderId = rs.getInt("bidderId");
						_highestBidderName = rs.getString("bidderName");
						_highestBidderMaxBid = rs.getLong("maxBid");
					}
					
					_bidders.put(rs.getInt("bidderId"), new Bidder(rs.getString("bidderName"), rs.getString("clan_name"), rs.getLong("maxBid"), rs.getLong("time_bid")));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Exception: Auction.loadBid(): " + e.getMessage(), e);
		}
	}
	
	/** Task Manage */
	private void startAutoTask()
	{
		final long currentTime = System.currentTimeMillis();
		long taskDelay = 0;
		if (_endDate <= currentTime)
		{
			_endDate = currentTime + (7 * 24 * 3600000);
			saveAuctionDate();
		}
		else
		{
			taskDelay = _endDate - currentTime;
		}
		
		_endTask = ThreadPool.schedule(new AutoEndTask(), taskDelay); // CP-0031: kept, so a deletion cancels it
	}
	
	public static String getItemTypeName(AuctionItemType value)
	{
		return ItemTypeName[value.ordinal()];
	}
	
	/** Save Auction Data End */
	private void saveAuctionDate()
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("Update auction set endDate = ? where id = ?"))
		{
			ps.setLong(1, _endDate);
			ps.setInt(2, _id);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Exception: saveAuctionDate(): " + e.getMessage(), e);
		}
	}
	
	/**
	 * Set a bid
	 * @param bidder
	 * @param bid
	 */
	public synchronized void setBid(Player bidder, long bid)
	{
		if (_over) // CP-0031: a bid that waited on this monitor while the sale closed takes nothing
		{
			bidder.sendMessage("Invalid auction");
			return;
		}
		
		// CP-0023: a clan pays the difference over its own standing bid, as upstream charged only the highest bidder; a clan
		// outbid and bidding again paid its whole new bid and lost the standing one, which its new bid overwrites.
		final Bidder standing = bidder.getClan() == null ? null : _bidders.get(bidder.getClanId());
		final long requiredAdena = standing == null ? bid : bid - standing.getBid();
		if ((((_highestBidderId > 0) && (bid > _highestBidderMaxBid)) || ((_highestBidderId == 0) && (bid >= _startingBid))) && takeItem(bidder, requiredAdena))
		{
			updateInDB(bidder, bid);
			bidder.getClan().setAuctionBiddedAt(_id, true);
			return;
		}
		
		if ((bid < _startingBid) || (bid <= _highestBidderMaxBid))
		{
			bidder.sendPacket(SystemMessageId.YOUR_BID_PRICE_MUST_BE_HIGHER_THAN_THE_MINIMUM_PRICE_CURRENTLY_BEING_BID);
		}
	}
	
	/**
	 * Returns the item to the clan warehouse.
	 * @param clanName the clan name
	 * @param quantity the Adena value
	 * @param penalty if {@code true} fees are applied
	 */
	private void returnItem(String clanName, long quantity, boolean penalty)
	{
		long amount = quantity;
		if (penalty)
		{
			amount *= 0.9; // take 10% tax fee if needed
		}
		
		final Clan clan = ClanTable.getInstance().getClanByName(clanName);
		if (clan == null)
		{
			LOGGER.warning("Clan " + clanName + " doesn't exist!");
			return;
		}
		
		final ItemContainer cwh = clan.getWarehouse();
		if (cwh == null)
		{
			LOGGER.warning("There has been a problem with " + clanName + "'s clan warehouse!");
			return;
		}
		
		// avoid overflow on return
		final long limit = MAX_ADENA - cwh.getAdena();
		EconomyCounter.postClamp(ADENA_ID, Math.max(0, amount - limit)); // the part past MAX_ADENA, a sink (CP-0022)
		amount = Math.min(amount, limit);
		cwh.addItem(ItemProcessType.REFUND, ADENA_ID, amount, null, null);
	}
	
	/**
	 * Take Item in WHC
	 * @param bidder
	 * @param quantity
	 * @return
	 */
	private boolean takeItem(Player bidder, long quantity)
	{
		if ((bidder.getClan() != null) && (bidder.getClan().getWarehouse().getAdena() >= quantity))
		{
			bidder.getClan().getWarehouse().destroyItemByItemId(ItemProcessType.BUY, ADENA_ID, quantity, bidder, bidder);
			return true;
		}
		
		bidder.sendPacket(SystemMessageId.THERE_IS_NOT_ENOUGH_ADENA_IN_THE_CLAN_HALL_WAREHOUSE);
		return false;
	}
	
	/**
	 * Update auction in DB
	 * @param bidder
	 * @param bid
	 */
	private void updateInDB(Player bidder, long bid)
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			if (_bidders.get(bidder.getClanId()) != null)
			{
				try (PreparedStatement ps = con.prepareStatement("UPDATE auction_bid SET bidderId=?, bidderName=?, maxBid=?, time_bid=? WHERE auctionId=? AND bidderId=?"))
				{
					ps.setInt(1, bidder.getClanId());
					ps.setString(2, bidder.getClan().getLeaderName());
					ps.setLong(3, bid);
					ps.setLong(4, System.currentTimeMillis());
					ps.setInt(5, _id);
					ps.setInt(6, bidder.getClanId());
					Census.counting(con, Census.Holding.CLAN_HALL_BIDS, "auctionId = ?", new Object[]
					{
						_id
					}, ps::executeUpdate); // the bid holding is counted where its rows are written (CP-0022)
				}
			}
			else
			{
				try (PreparedStatement ps = con.prepareStatement("INSERT INTO auction_bid (id, auctionId, bidderId, bidderName, maxBid, clan_name, time_bid) VALUES (?, ?, ?, ?, ?, ?, ?)"))
				{
					ps.setInt(1, IdManager.getInstance().getNextId());
					ps.setInt(2, _id);
					ps.setInt(3, bidder.getClanId());
					ps.setString(4, bidder.getName());
					ps.setLong(5, bid);
					ps.setString(6, bidder.getClan().getName());
					ps.setLong(7, System.currentTimeMillis());
					Census.counting(con, Census.Holding.CLAN_HALL_BIDS, "auctionId = ?", new Object[]
					{
						_id
					}, ps::executeUpdate); // the bid holding is counted where its rows are written (CP-0022)
				}
				
				if (World.getPlayer(_highestBidderName) != null)
				{
					World.getPlayer(_highestBidderName).sendMessage("You have been out bidded");
				}
			}
			
			final long currentTime = System.currentTimeMillis();
			_highestBidderId = bidder.getClanId();
			_highestBidderMaxBid = bid;
			_highestBidderName = bidder.getClan().getLeaderName();
			if (_bidders.get(_highestBidderId) == null)
			{
				_bidders.put(_highestBidderId, new Bidder(_highestBidderName, bidder.getClan().getName(), bid, currentTime));
			}
			else
			{
				_bidders.get(_highestBidderId).setBid(bid);
				_bidders.get(_highestBidderId).setTimeBid(currentTime);
			}
			
			bidder.sendPacket(SystemMessageId.YOUR_BID_HAS_BEEN_SUCCESSFULLY_PLACED);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Exception: Auction.updateInDB(Player bidder, int bid): " + e.getMessage(), e);
		}
	}
	
	/** Remove bids */
	private void removeBids()
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM auction_bid WHERE auctionId=?"))
		{
			ps.setInt(1, _id);
			Census.counting(con, Census.Holding.CLAN_HALL_BIDS, "auctionId = ?", new Object[]
			{
				_id
			}, ps::executeUpdate); // (CP-0022)
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Exception: Auction.deleteFromDB(): " + e.getMessage(), e);
			return; // CP-0026: bids whose rows stay are not refunded, or a later removal would refund them again
		}
		
		for (Bidder b : _bidders.values())
		{
			if (ClanTable.getInstance().getClanByName(b.getClanName()).getHideoutId() == 0)
			{
				returnItem(b.getClanName(), b.getBid(), true); // 10 % tax
			}
			else if (World.getPlayer(b.getName()) != null)
			{
				World.getPlayer(b.getName()).sendMessage("Congratulation you have won ClanHall!");
			}
			
			ClanTable.getInstance().getClanByName(b.getClanName()).setAuctionBiddedAt(0, true);
		}
		
		_bidders.clear();
	}
	
	/** Remove auctions */
	public void deleteAuctionFromDB()
	{
		synchronized (CONFIRMATIONS) // CP-0031: the row, then the listing, so a confirmation never sees one without the other
		{
			_over = true;
			if (_endTask != null)
			{
				_endTask.cancel(false);
			}
			
			try (Connection con = DatabaseFactory.getConnection();
				PreparedStatement ps = con.prepareStatement("DELETE FROM auction WHERE itemId=?"))
			{
				ps.setInt(1, _itemId);
				ps.execute();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.SEVERE, "Exception: Auction.deleteFromDB(): " + e.getMessage(), e);
			}
			
			ClanHallAuctionManager.getInstance().getAuctions().remove(this);
		}
	}
	
	/** End of auction */
	public synchronized void endAuction()
	{
		if (_over) // CP-0031: a deleted sale (ended or cancelled) is not ended again by a timer that outlived it
		{
			return;
		}
		
		if (ClanHallTable.getInstance().loaded())
		{
			if ((_highestBidderId == 0) && (_sellerId == 0))
			{
				startAutoTask();
				return;
			}
			
			if ((_highestBidderId == 0) && (_sellerId > 0))
			{
				/**
				 * If seller haven't sell ClanHall, auction removed, THIS MUST BE CONFIRMED
				 */
				deleteAuctionFromDB(); // CP-0031: and its row, which upstream left to block the hall's next sale
				return;
			}
			
			if (_sellerId > 0)
			{
				returnItem(_sellerClanName, _highestBidderMaxBid, true);
				returnItem(_sellerClanName, ClanHallTable.getInstance().getAuctionableHallById(_itemId).getLease(), false);
			}
			
			deleteAuctionFromDB();
			final Clan clan = ClanTable.getInstance().getClanByName(_bidders.get(_highestBidderId).getClanName());
			_bidders.remove(_highestBidderId);
			clan.setAuctionBiddedAt(0, true);
			removeBids();
			ClanHallTable.getInstance().setOwner(_itemId, clan);
		}
		else
		{
			/** Task waiting ClanHallManager is loaded every 3s */
			_endTask = ThreadPool.schedule(new AutoEndTask(), 3000);
		}
	}
	
	/**
	 * Cancel bid
	 * @param bidder
	 */
	public synchronized void cancelBid(int bidder)
	{
		if (_over) // CP-0031: the sale's deletion already returned every bid
		{
			return;
		}
		
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("DELETE FROM auction_bid WHERE auctionId=? AND bidderId=?"))
		{
			ps.setInt(1, _id);
			ps.setInt(2, bidder);
			Census.counting(con, Census.Holding.CLAN_HALL_BIDS, "auctionId = ?", new Object[]
			{
				_id
			}, ps::executeUpdate); // (CP-0022)
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Exception: Auction.cancelBid(String bidder): " + e.getMessage(), e);
			return; // CP-0026: a bid whose row stays is not refunded
		}
		
		returnItem(_bidders.get(bidder).getClanName(), _bidders.get(bidder).getBid(), true);
		ClanTable.getInstance().getClanByName(_bidders.get(bidder).getClanName()).setAuctionBiddedAt(0, true);
		_bidders.clear();
		loadBid();
	}
	
	/** Cancel auction */
	public synchronized void cancelAuction()
	{
		if (_over) // CP-0031
		{
			return;
		}
		
		deleteAuctionFromDB();
		removeBids();
	}
	
	/** Confirm an auction */
	public boolean confirmAuction()
	{
		// CP-0029: the deposit, the hall's lease, is taken from the seller's clan warehouse when the hall is put up, as
		// retail's sale page says (AgitSale1.htm); upstream never took it and paid it back at the end (endAuction). Each
		// auctioneer keeps its own pending sales, so two members can confirm two sales of one hall: one lock for every
		// confirmation and every deletion, a hall listed or still with a row is not put up again, and a sale is listed
		// only once its row is written (CP-0031).
		synchronized (CONFIRMATIONS)
		{
			if ((ClanHallAuctionManager.getInstance().getAuction(_id) != null) || hasRow())
			{
				return false;
			}
			
			final Clan seller = ClanTable.getInstance().getClanByName(_sellerClanName);
			final long deposit = ClanHallTable.getInstance().getAuctionableHallById(_itemId).getLease();
			if ((seller == null) || ((deposit > 0) && (seller.getWarehouse().destroyItemByItemId(ItemProcessType.FEE, ADENA_ID, deposit, null, null) == null)))
			{
				return false;
			}
			
			if (!insertRow()) // a lost acknowledgement may have written it: the row decides, and only a row read
			{
				final Boolean row = resolveRow();
				if (row == null)
				{
					// Unknown: not listed, and the deposit kept, since a refund of a written row would pay it twice; a
					// restart loads the row with its deposit paid, or finds none and the deposit is lost, as logged here.
					LOGGER.severe("Clan hall sale " + _id + " of clan " + _sellerClanName + ": its row could not be resolved; deposit " + deposit + " kept, sale not listed.");
					return false;
				}
				
				if (!row)
				{
					if (deposit > 0)
					{
						seller.getWarehouse().addItem(ItemProcessType.REFUND, ADENA_ID, deposit, null, null); // nothing was put up
					}
					
					return false;
				}
			}
			
			ClanHallAuctionManager.getInstance().getAuctions().add(this);
		}
		
		startAutoTask(); // CP-0031: upstream scheduled a sale's end only when it loaded the sale at startup
		return true;
	}
	
	/** @return whether the sale's row was written (CP-0031); upstream logged a failure and listed the sale anyway */
	private boolean insertRow()
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("INSERT INTO auction (id, sellerId, sellerName, sellerClanName, itemType, itemId, itemObjectId, itemName, itemQuantity, startingBid, currentBid, endDate) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"))
		{
			ps.setInt(1, _id);
			ps.setInt(2, _sellerId);
			ps.setString(3, _sellerName);
			ps.setString(4, _sellerClanName);
			ps.setString(5, _itemType);
			ps.setInt(6, _itemId);
			ps.setInt(7, _itemObjectId);
			ps.setString(8, _itemName);
			ps.setLong(9, _itemQuantity);
			ps.setLong(10, _startingBid);
			ps.setLong(11, _currentBid);
			ps.setLong(12, _endDate);
			ps.execute();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "Exception: Auction.load(): " + e.getMessage(), e);
			return false;
		}
	}
	
	/** @return whether this hall's sale still has its row (CP-0031), read under the confirmation lock */
	private boolean hasRow()
	{
		return rowState() != Boolean.FALSE;
	}
	
	/** @return the row's state after a failed insert, read up to five times a second apart; null if never readable */
	private Boolean resolveRow()
	{
		for (int attempt = 0; attempt < 5; attempt++)
		{
			final Boolean row = rowState();
			if (row != null)
			{
				return row;
			}
			
			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return null;
			}
		}
		
		return null;
	}
	
	/** @return whether this hall's sale has its row: true, false, or null when it cannot be read (CP-0031) */
	protected Boolean rowState()
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT 1 FROM auction WHERE id = ?"))
		{
			ps.setInt(1, _id);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "Exception: Auction.rowState(): " + e.getMessage(), e);
			return null;
		}
	}
	
	/**
	 * Get var auction
	 * @return
	 */
	public int getId()
	{
		return _id;
	}
	
	public long getCurrentBid()
	{
		return _currentBid;
	}
	
	public long getEndDate()
	{
		return _endDate;
	}
	
	public int getHighestBidderId()
	{
		return _highestBidderId;
	}
	
	public String getHighestBidderName()
	{
		return _highestBidderName;
	}
	
	public long getHighestBidderMaxBid()
	{
		return _highestBidderMaxBid;
	}
	
	public int getItemId()
	{
		return _itemId;
	}
	
	public String getItemName()
	{
		return _itemName;
	}
	
	public int getItemObjectId()
	{
		return _itemObjectId;
	}
	
	public long getItemQuantity()
	{
		return _itemQuantity;
	}
	
	public String getItemType()
	{
		return _itemType;
	}
	
	public int getSellerId()
	{
		return _sellerId;
	}
	
	public String getSellerName()
	{
		return _sellerName;
	}
	
	public String getSellerClanName()
	{
		return _sellerClanName;
	}
	
	public long getStartingBid()
	{
		return _startingBid;
	}
	
	public Map<Integer, Bidder> getBidders()
	{
		return _bidders;
	}
}
