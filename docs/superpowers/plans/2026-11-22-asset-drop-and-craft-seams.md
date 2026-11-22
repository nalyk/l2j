# Asset Service, part 6: the drop and craft seams — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the two core seams of blueprint 03 §4.2-§4.3 and the three Asset operations behind them: a kill's drops can be
routed (`route`, Loot Charter) or relocated (`relocate`, Aden Remembers) before retail drops them, and a craft can be
validated, rolled and applied apart (`craft`, PACT), each exactly as retail computes it.

**Architecture:** `DropSeam` (new package `livingchronicle.drop`) is called by one core patch in
`Attackable.doItemDrop` with the computed death-drop list; installed routers, in order, each take positions of the
list after committing their own Asset operation, and the rest goes to retail. `RecipeManager.Craft` (core patch) holds
the retail rules `RecipeItemMaker` already applies, made callable: validation, the two rates, the materials, the
product, the stat use; retail's maker is rewritten to call them and keeps its own `Rnd` draws. `AssetTx` gains
`route`, `relocate`, `craft` and `ownerRow`.

**Tech Stack:** Java 27, JUnit 5, MariaDB (the test server's).

**Spec:** `docs/spec/asset-service.md` (revision 15 after Task 1) §2.5 (`LOOT_DROP`, `RELOCATED_DROP`, `CRAFT_INPUT`,
`CRAFT_OUTPUT`), §4.2, §4.3 (`route`, `relocate`, `craft`), §4.4; `docs/spec/pact.md` §2.3 (kill key), §4.3, §4.5;
`docs/spec/aden-remembers.md` §4.3.1; blueprint 03 §4.2-§4.3.

**Out of scope:** `recycle`, `raid`, `issue`, `track` (part 7); the consumers (PACT's charter and contracts, N11a; Aden
Remembers' relocation, N11e); the window buckets (N5, asset-service.md §2.6 "Watched").

## Global Constraints

- Every core change is ledgered in `CORE_PATCHES.md` with a test that fails on upstream; CRLF/LF kept as upstream has it.
- `truth` installs no router and calls no new operation: its behaviour locks (`test/lock/**`) stay byte-identical.
- With no router installed the seam returns the same collection it was given, after one volatile read (02 §6 hot path).
- A router runs synchronously on the dropping thread and returns only positions whose operation committed; an
  `UNCERTAIN` commit is resolved on that thread before it returns (asset-service.md §3.2); anything else gives retail
  the items back.
- The kill key is `kill/<epoch>/<seq>`: `epoch` is given at install (the journal's, fact-journal.md §2, once N5 exists),
  `seq` counts kills from 1 in memory; each consumer derives its own `OpId` from it (PACT `PACT/kill/<epoch>/<seq>`).
- Source legs post to the existence counters as the ledger does today (asset-service.md §2.6); nothing posts twice.
- Every credit to a character is service mail written in the operation (ASSET-R10), never a live container.
- Retail crafting keeps `Rnd.get(100) < successRate` and `Rnd.get(100) < rarity × CraftMasterworkChanceRate` in its
  order of draws; the keyed roll's masterwork rate in parts per million is `min(100, ceil(rarity × rate)) × 10 000`, the
  probability retail's draw has.

## Review Focus

1. A router that throws after its operation committed: the items must not also reach retail (a dupe). Task 2.
2. A kill whose drop list holds one item id twice (two groups rolled it): positions, not ids, decide what was taken. Task 2.
3. An auctioned drop whose template is not custody-eligible (a quest item, an event item): `route` refuses the whole
   operation before writing anything. Task 3.
4. A recipe whose rare product exists but `CraftMasterwork` is off and the rare id differs: masterwork rate 0, as retail.
   Task 5.
5. A retail craft after the refactor draws the same random numbers in the same order and gives the same result as
   upstream for the same seed. Task 5.

---

### Task 1: Spec revision 15

**Files:**
- Modify: `docs/spec/asset-service.md` (§4.2, §4.3, §4.4, §11, §12, status line), `docs/spec/asset-economy-sites.csv` (no
  new site: the seam and the operations write through the ledger)

- [x] **Step 1:** §4.4: an item a source leg creates straight into a custody (`route`'s auctioned drops, `relocate`) passes
  the same checks on its template: tradeable, not a quest item, not time-limited or shadow, its id not blanket-deleted;
  an operation with one that fails is refused whole.
- [x] **Step 2:** §4.2: the owner module may write rows of its own tables (`lc_<module>_…`) in the operation's
  transaction through `ownerRow`, never another module's or Mobius's.
- [x] **Step 3:** §11.33: the drop seam's contract (Global Constraints above) and where it is: the player branch of
  `doItemDrop` only (Attackable.java:1176-1204); the fake-player branch (`FakePlayerCanDropItems`, off by default)
  follows retail untouched; spoil is never offered. §11.34: the craft rules made callable, retail's draws unchanged.
- [x] **Step 4:** status line `draft (revision 15, under review; revision 14 …)`; §12 entry; run
  `python3 tools/spec/traceability.py`, then `SpecTraceabilityTest` and `AssetEconomySitesTest` green.
- [x] **Step 5:** commit `Asset rev 15: the drop and craft seams`.

### Task 2: The drop seam (CP-0032)

**Files:**
- Create: `java/org/l2jmobius/gameserver/livingchronicle/drop/DropSeam.java`, `DropRouter.java`, `Kill.java`
- Modify: `java/org/l2jmobius/gameserver/entity/actor/Attackable.java` (after `calculateDrops(DropType.DROP, …)` in the
  player branch), `CORE_PATCHES.md`
- Test: `test/java/org/l2jmobius/gameserver/livingchronicle/drop/DropSeamTest.java`

**Interfaces:**
- Produces: `record Kill(int epoch, long seq, Attackable npc, Player owner) { String key(); }` (`"kill/" + epoch + "/" + seq`);
  `interface DropRouter { Set<Integer> route(Kill kill, List<ItemHolder> offered) throws Exception; }` (positions taken,
  each committed); `DropSeam.install(int epoch, List<DropRouter> routers)`, `DropSeam.uninstall()`,
  `static Collection<ItemHolder> DropSeam.route(Attackable npc, Player owner, Collection<ItemHolder> drops)`.

- [ ] **Step 1: Write the failing tests** in `DropSeamTest` (kill a level-1 monster with a bound `KeyedRandom` as
  `GearLockTest` does; compare ground items by id and count):
  - `withNoRouterTheKillDropsExactlyAsRetail`: same seed, same ground items as a run before install; and
    `DropSeam.route(npc, p, list) == list` (same instance).
  - `aRouterTakesItsPositionsAndRetailDropsTheRest`: router takes position 0; the ground lacks exactly that holder.
  - `routersRunInOrderOnWhatIsLeft`: the second router is offered the list without the first one's positions.
  - `aRouterThatThrowsTakesNothing`: all items on the ground; the exception logged.
  - `aPositionOutOfRangeIsIgnoredAndTheValidOnesStayTaken`: `{0, 99}` → position 0 taken, logged SEVERE.
  - `oneItemIdTwiceIsTwoPositions`: list `[(57,10), (57,10)]`, router takes `{1}` → one Adena drop of 10 remains.
  - `killsAreNumberedFromOne`: two kills → keys `kill/7/1`, `kill/7/2` after `install(7, …)`.
- [ ] **Step 2:** run `t4.sh DropSeamTest` → RED (classes missing).
- [ ] **Step 3: Implement** `DropSeam` (a volatile immutable router list and an `AtomicLong`; `route` copies the
  collection to a list only when routers are installed; a router's exception or a position outside the list it was
  offered is logged and ignored, positions are into the list that router was offered); the core patch is one line,
  `deathItems = DropSeam.route(this, player, deathItems); // CP-0032`, null-safe.
- [ ] **Step 4:** `t4.sh DropSeamTest` GREEN; `PausedWorldTest` and `GearLockTest` GREEN (truth unchanged).
- [ ] **Step 5:** CP-0032 in `CORE_PATCHES.md` (Files, Reason: 03 §4.2, pact.md §4.5, aden-remembers.md §4.3.1;
  Invariant: a kill's drop is dropped, auto-looted or taken by one committed operation, never two; Proof); commit
  `Core: CP-0032 the drop seam`.

### Task 3: `route` and template eligibility

**Files:**
- Modify: `java/org/l2jmobius/gameserver/livingchronicle/asset/AssetTx.java`, `Eligibility.java`, `AssetFact` users
- Test: `test/java/org/l2jmobius/gameserver/livingchronicle/asset/RouteTest.java`

**Interfaces:**
- Produces: `record RoutedDrop(int itemId, long count, int receiverCharId)` (in `asset`; `receiverCharId` 0 = the
  custody); `void AssetTx.route(int custody, List<RoutedDrop> drops)`; `static String Eligibility.templateRefusal(ItemTemplate t)`.

- [ ] **Step 1: Write the failing tests** (`AssetFixtures` as `ReleaseTest` uses):
  - `routedDropsReachTheirReceiversByServiceMailAndTheAuctionedOnesTheCustody`: two receivers and one auctioned drop →
    one service mail per receiver with exactly its drops (INV12 holds), one `ESCROW` row in the custody; postings
    `LOOT_DROP` SOURCE legs equal to the drops; counters up by the drops; fact `Asset.LootRouted` with custody and legs.
  - `anIneligibleAuctionedDropRefusesTheWholeRoute`: a quest item with receiver 0 → `AssetAbort` (reason `INELIGIBLE`),
    no row, no mail, no posting, counters unchanged.
  - `aSecondRouteOfOneKillAbortsAsSettled`: same settlement key `("PACT", "kill/7/1")` twice → the second aborts.
- [ ] **Step 2:** RED. **Step 3:** implement `route` by composing the existing service-mail writer `release` uses and the
  custody row writer `correct` uses for SOURCE legs; code `LOOT_DROP`, reference = custody. **Step 4:** GREEN; the whole
  `asset` test package GREEN. **Step 5:** commit `Asset: route`.

### Task 4: `relocate`

**Files:** Modify `AssetTx.java`; Test `test/java/org/l2jmobius/gameserver/livingchronicle/asset/RelocateTest.java`

**Interfaces:**
- Produces: `record Relocation(int itemId, long count, int custody)`; `void AssetTx.relocate(List<Relocation> drops)`.

- [ ] **Step 1: Write the failing tests:** `eachDropGoesToItsOwnWarehouseCustody` (two `POOL` custodies, three drops →
  rows in each, `RELOCATED_DROP` SOURCE legs, fact `Asset.DropRelocated`); `anIneligibleDropRefusesTheWholeRelocation`;
  `aFrozenOwnerRefusesIt` (asset-service.md §4.2 rule 5).
- [ ] **Step 2:** RED. **Step 3:** implement as `route` without mail. **Step 4:** GREEN. **Step 5:** commit `Asset: relocate`.

### Task 5: The crafting boundary (CP-0033)

**Files:**
- Modify: `java/org/l2jmobius/gameserver/managers/RecipeManager.java`, `CORE_PATCHES.md`
- Test: `test/java/org/l2jmobius/gameserver/livingchronicle/asset/CraftBoundaryTest.java`

**Interfaces:**
- Produces (nested `public static final class Craft` in `RecipeManager`):
  `static SystemMessageId refusal(Player crafter, RecipeList recipe)` (null when retail would start the craft: alive,
  not in a transaction, crafting enabled, the recipe in the crafter's book, `recipe.getLevel() <= ` skill level, HP and
  MP as `calculateStatUse(false, false)` checks); `static int successRate(RecipeList)`;
  `static int masterworkRatePpm(RecipeList)` (0 unless `rareItemId != -1 && (rareItemId == itemId || CraftMasterwork)`);
  `static List<ItemHolder> materials(RecipeList)` (quantities > 0, recipe order); `static ItemHolder product(RecipeList, boolean masterwork)`;
  `static void consumeStats(Player crafter, RecipeList recipe)` (what `calculateStatUse(false, true)` takes).

- [ ] **Step 1: Write the characterization test first, against upstream:** `retailCraftIsUnchanged` crafts 200 times
  with a bound `KeyedRandom` stream (recipe 1: Wooden Arrow, and one recipe with a rare product) recording success,
  product id and count, materials left and MP; store the sequence as expected values in the test. GREEN on upstream.
- [ ] **Step 2: Write the failing tests:** `theRatesAreRetails` (`successRate` = `getSuccessRate()`; `masterworkRatePpm`
  = `min(100, ceil(rarity × rate)) × 10 000` when eligible, 0 for Review Focus 4); `materialsAndProductAreRetails`;
  `refusalMatchesWhatRetailRefuses` (unknown recipe, low skill, too little MP, dead). RED (no `Craft`).
- [ ] **Step 3: Implement** `Craft` and rewrite `RecipeItemMaker` to call it (`listItems`, `rewardPlayer`,
  `calculateStatUse` share the rules; the two `Rnd` draws stay where they are).
- [ ] **Step 4:** `CraftBoundaryTest` GREEN, `retailCraftIsUnchanged` still GREEN (Review Focus 5); behaviour locks GREEN.
- [ ] **Step 5:** CP-0033 ledgered (revert proof: the new tests fail with `Craft` removed); commit
  `Core: CP-0033 the crafting boundary`.

### Task 6: `craft` and owner rows

**Files:** Modify `AssetTx.java`; Test `test/java/org/l2jmobius/gameserver/livingchronicle/asset/CraftTest.java`

**Interfaces:**
- Consumes: `RecipeManager.Craft.materials`, `product` (Task 5).
- Produces: `void AssetTx.craft(int custody, List<Asset> materials, int receiverCharId, ItemHolder product, String subject, String content)`
  (`product` null on failure); `void AssetTx.ownerRow(String table, Map<String, Object> columns)` (refused unless
  `table` starts with `lc_` + the operation's module in lower case + `_`).

- [ ] **Step 1: Write the failing tests:** `aSuccessfulCraftSinksTheMaterialsAndMailsTheProduct` (custody rows gone,
  `CRAFT_INPUT` SINK and `CRAFT_OUTPUT` SOURCE legs, one service mail, fact `Asset.Crafted`, counters move by the
  product less the materials); `aFailedCraftOnlySinksTheMaterials`; `theOutcomeRowIsWrittenInTheOperation` (an
  `lc_pact_craft_outcome` row via `ownerRow`, absent after an abort); `anotherModulesTableIsRefused` (`ownerRow("lc_asset_op", …)`
  from module `PACT` → `IllegalArgumentException`).
- [ ] **Step 2:** RED. **Step 3:** implement (`craft` = the custody sink `sink` writes, with `CRAFT_INPUT`, then the
  `route` mail path with `CRAFT_OUTPUT`; `ownerRow` queues an INSERT executed in the commit with the other statements).
  **Step 4:** GREEN; full gate `./build.sh` GREEN. **Step 5:** commit `Asset: craft and owner rows`.

### Task 7: Review

- [ ] **Step 1:** Codex amendment round on revision 15 and CP-0032/0033 (prompt as rounds 19-20: spec, table, ledger,
  code, tests); fix each finding red-first, gate, commit, next round until SIGN-OFF.
- [ ] **Step 2:** record the sign-off in the status line and §12; traceability regenerated; commit
  `Asset rev 15 signed off`; ask the user before merging into `main`.
