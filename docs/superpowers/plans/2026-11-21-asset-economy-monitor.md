# Asset Service, part 5: the economy monitor's guarantees — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** asset-service.md revision 13, §2.6 "Guaranteed" and §2.7: every site classified and held to the table by a
static scan (AT-ASSET-12); existence counters kept exactly from the changes themselves; conservation compared across a
clean restart (INV06) and reported as a rebase after a crash; Living Chronicle booting and stopping with the server.

**Architecture:** `SiteScan` (test side) reads the compiled core and the datapack scripts compiled as Mobius compiles them,
with the JDK's `java.lang.classfile` API. `EconomyCounter` (production, static, inactive until a profile starts Living
Chronicle) holds per item id the quantity in the counted holdings; Mobius's mutators post to it through core patches,
each a scanned and classified site. `Census` counts the same holdings from the database; `AssetRecovery` and
`LivingChronicle` run them at startup and at the shutdown boundary.

**Tech Stack:** Java 27, JUnit 5, MariaDB (the test server's), `java.lang.classfile`.

**Spec:** `docs/spec/asset-service.md` revision 13 §2.6, §2.7, §3.2, §6 (INV06, INV12, INV13), §11.24-26;
`docs/spec/asset-economy-sites.csv`; `docs/spec/profiles.md` §4.2 rule 4.

**Out of scope (part 6):** the per-site window buckets and `Asset.MoveImbalance` (§2.6 "Watched").

## Global Constraints

- Counted locations: every `ItemLocation` but `VOID`, `REFUND`, `LEASE`; the ground list only when `SaveDroppedItem`.
- Census holdings: `items` in counted locations, `itemsonground` when saved, `castle.treasury` (item 57),
  `item_auction_bid.playerBid > 0` less each finished auction's highest bid, `auction_bid.maxBid` (item 57).
- Restored state never counts; the service's own takes and returns post nothing; the ledger posts SOURCE/SINK legs.
- Hooks do nothing until `EconomyCounter.activate`; in `truth` it is never called.
- Every instant is `GameClock`'s; every core patch is ledgered in CORE_PATCHES.md with a revert proof.

## Review Focus

1. A count and a location changing at once on one item, from two threads: the counter settles exactly. Task 3.
2. A restored object later changed: only the change counts, never the restored quantity. Task 3.
3. The service's stack decrease and retire followed by a Mobius change on the same object. Task 3.
4. A crash (no shutdown census) is a rebase, never a pass. Task 6.
5. `truth` boots and stops exactly as upstream; no hook posts. Tasks 3, 8.

---

### Task 1: `SiteScan` and AT-ASSET-12 (done)

`test/java/org/l2jmobius/testing/economy/SiteScan.java`, `SiteScanTest` (fixture `EconomyFixture`),
`test/.../asset/AssetEconomySitesTest.java`: unclassified, stale, `PENDING`, unknown class, ledger rows, every `SinkCode`
with a ledger row. Kinds: `CREATE_ITEM`, `DESTROY_ITEM`, `CHANGE_COUNT`, `SET_COUNT`, `NEW_ITEM`, `CENSUS_SQL`; Task 3 adds
`COUNTER` (a call of `EconomyCounter`).

### Task 2: revision 13 and the complete table (done; Codex round amd10)

### Task 3: `EconomyCounter` and the item hooks (CP-0021; done)

**Files:** create `livingchronicle/asset/EconomyCounter.java`; modify `entity/item/instance/Item.java`,
`SiteScan.java` (kind `COUNTER`), the table; test `test/.../asset/ExistenceCounterTest.java`.

**Interfaces (produces):** `EconomyCounter.activate(Map<Integer, Long> base)`, `deactivate()`, `isActive()`,
`post(int itemId, long delta)`, `snapshot() → Map<Integer, Long>`; `Item.settleCounted()` (after `setCount`,
`changeCount`, `setItemLocation`), `Item.markCounted()` (restore), `Item.tryDecrease` and `retire` adjusting the posted
quantity silently. Per-item posted quantity under a striped leaf lock (never the item's monitor).

- [ ] Failing tests: created into an inventory +n; merged into a stack 0; destroyed −n; dropped −n, picked up +n
  (`SaveDroppedItem` off); inventory to warehouse 0; sold to the refund list −n; restored 0, then +1 counts 1; the
  service's stack decrease and retire post nothing, a later Mobius change counts only itself; two threads changing count
  and location settle exactly; inactive posts nothing.
- [ ] Implement; table rows for the new `COUNTER` sites; AT-ASSET-12 green; revert proof; commit.

### Task 4: treasury, ground and bid hooks (CP-0022), the re-bid (CP-0023), the saved ground (CP-0024; done)

**Files:** `mechanics/siege/Castle.java`, `managers/ItemsOnGroundManager.java`, `entity/item/auction/ItemAuction.java`,
`ItemAuctionInstance.java`, `entity/residences/ClanHallAuction.java`; tests `HoldingHooksTest`, `ClanHallRebidTest`.

- [ ] Failing tests: tax in and withdrawal out counted, a clamp counted as applied; a saved ground item entering and
  leaving; an item-auction bid placed, raised, cancelled, won; a clan-hall bid placed, raised, cancelled, returned, won;
  a clan re-bidding after being outbid pays the difference and loses nothing (RED on upstream).
- [ ] Implement; rows; AT-ASSET-12 green; revert proofs; commit.

### Task 5: SQL sites counted on commit (CP-0025; done)

**Files:** `livingchronicle/asset/Census.java` (`count(Connection, Holding, String where, Object... params)`), each SQL
`SOURCE`/`SINK` site of the table; test `SqlSinkTest`.

- [ ] Failing tests: character deletion, hero items, event items, an offline cursed weapon, an offline circlet, the
  admin repair, the startup cleanup, an expired item auction: each posts exactly what its statement removed.

### Task 6: the census, INV06 and the ledger's postings (done; V010's tables, no new migration)

**Files:** `sql/migrations/V018__lc_economy_census.sql` (`lc_economy_census`, `lc_economy_counter`), `Census.java`
(`take(kind)`), `AssetTx` (posts SOURCE/SINK legs at commit); test `CensusTest`.

- [ ] Failing tests: every holding counted; a clean restart with nothing changed compares equal; a row changed between
  shutdown and startup is reported per item id (`Asset.ConservationBroken`); a change lost after its final save is
  reported; a startup without a shutdown census is a rebase (`Asset.MonitorRebased`); a fee leg posts −n.

### Task 7: `AssetRecovery` (done)

**Files:** `livingchronicle/asset/AssetRecovery.java`: custody check (INV03/04/05/11), every pending mark removed (INV13),
deliveries checked (INV12), then the startup census. Test `AssetRecoveryTest`.

### Task 8: the boot and shutdown seams (CP-0027; done)

**Files:** `livingchronicle/LivingChronicle.java` (`boot()`, `shutdownBoundary()`), `GameServer.java` (right after the
database opens), `Shutdown.java` (after `saveData`, before the database closes). Test `LivingChronicleBootTest`.

- [ ] Failing tests: `truth` starts nothing and activates no counter; `dev` runs recovery and the census;
  `shutdownBoundary` writes every clan warehouse and the shutdown census.
- [ ] Independent code review of parts 4-5; full gate; merge to `main`.

## Outcome

Codex rounds amd10 and amd11 reshaped Tasks 3-6: SQL sites count before and after in their own transaction and resolve
a lost acknowledgement; bids are counted where their rows are written; the saved ground settles with the item; a
loaded copy of a row a counted statement deleted stops counting; CP-0026 refunds a clan-hall bid only once its row is
gone. The full gate runs the 74 classes.

Rounds amd12 to amd16 all attacked one place: a counted SQL deletion racing Mobius's own handling of the same item in
memory. Loaded copies are held under their stripes, and a row whose copy was counted out in memory is marked
(`EconomyCounter.countedOut`) until a write leaves its row holding nothing counted; the first INSERT marks it too
(`Item._inserting`). A commit whose outcome cannot be read leaves the counters uncertain, and no shutdown census follows.
CP-0028 makes the boundary wait for the task pools. One case stays unsettled, and §2.6 names it: Mobius restoring a
container while such a deletion runs builds a copy of a row that no longer exists, and the restart census reports it.
The full gate runs 75 classes.
