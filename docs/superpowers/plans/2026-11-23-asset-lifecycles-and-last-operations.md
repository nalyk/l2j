# Asset Service, part 7: lifecycles and the last four operations — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** item lifecycles for non-stackable items, written by every operation and ended before their object's id can be
reused, and the four operations still unbuilt: `track`, `issue`, `raid`, `recycle`.

**Architecture:** while Living Chronicle runs, a non-stackable item's object id is never released at runtime (CP-0034,
at CP-0011's call in `ItemManager.destroyItem`), so no id a lifecycle names is ever reused; each destruction and each
`track` capture is offered, without a lock but the queue's, to one bounded queue drained in order by the lifecycle
writer (`LifecycleWriter`, new, `livingchronicle.asset`), which keeps the run's destroyed ids, checks a capture in
memory with no row lock held, and decides in a transaction that locks the object's lifecycle rows. A startup pass in `DatabaseIdManager` ends `VANISHED` every live lifecycle whose object is in no row, writes the
run's `lc_asset_run` row and reserves the live lifecycles' ids. `AssetTx` names a lifecycle on every non-stackable
whole-object leg, begins one where an item has none or is created, ends one where a sink takes a custody row, and gains
`issue`, `raid` and `recycle`. `RecordedSinks` and a permille supplier bound `recycle`; N5 and N11e install them.

**Tech Stack:** Java 27, JUnit 5, MariaDB (the test server's).

**Spec:** `docs/spec/asset-service.md` revision 16 (with fact-journal.md §3.1's row-lock order and aden-remembers.md
§4.6's raid share, amended with it): §2.2, §4.3 (`recycle`, `track`, `raid`, `issue`), §5, ASSET-INV08,
ASSET-R07, ASSET-R25, ASSET-R29, AT-ASSET-07, AT-ASSET-27, AT-ASSET-31, §11.35.

**Out of scope:** the telemetry reader (N5); the consumers (Chronicle's container seam and sagas N11g, PACT's parchment
N11a, the Ledger's seal N11b, Aden Remembers' pools and caravans N11e); the representation items' templates (N6's
client patch adds them; tests pick an existing template that passes the representation test).

## Global Constraints

- Every core change is ledgered in `CORE_PATCHES.md` with a test that fails on upstream; CRLF/LF kept as upstream has it.
- `truth` boots exactly as upstream: every id released as upstream, the startup pass does nothing, `PausedWorldTest`
  unchanged.
- Lifecycles only for non-stackable items; a stackable leg names none; a stackable id is released as upstream.
- No hot path does I/O or takes a lock but the queue's: a destruction and a capture are field checks and one offer.
- The writer never holds a row lock while taking an item's monitor; it locks only the object's `lc_item_lifecycle` rows.
- A raid's share drops only after a known commit, once, never by recovery.
- `recycle` refuses with no reader or supplier, nothing recorded, permille 0 or above 250, above the active share, for a
  day not over, twice per (territory, day).

## Review Focus

1. A capture queued, then the item destroyed before the writer reaches it, its row deleted or not: begun and then
   ended `DESTROYED`, never left live. Task 2.
2. A capture checked live, then `fund` and a corrective sink before the writer's transaction: nothing written. Task 2.
3. A retail transfer writing the item's row under its monitor while the writer works on it: no deadlock (the writer
   holds no row lock when it takes the monitor). Task 2.
4. A used-id scan failing at a start: no id a live lifecycle names is handed out. Task 2.
5. A raid whose commit is `UNCERTAIN` and resolved as not committed: nothing dropped, the manifest whole. Task 4.

---

### Task 1: V019 and the lifecycle table

**Files:** Create `sql/migrations/V019__lc_item_lifecycle.sql`; Modify
`test/java/org/l2jmobius/gameserver/livingchronicle/asset/AssetSchemaTest.java`.

- [x] Test first: `lc_asset_run` exists; `MERGED` refused; a live lifecycle refuses `ended_op` and `ended_run`;
  `DESTROYED` with both and `VANISHED` accepted; one live row per object. Red on V010's table.
- [x] V019: drop the merge and parent columns and constraints; `end_reason ENUM('DESTROYED','VANISHED')`; add `ended_op`,
  `ended_run` (null while live); create `lc_asset_run`. Register `Asset.LifecycleEnded` (1025) with Task 2.
- [x] Green (`AssetSchemaTest`, `LedgerSchemaTest`); commit.

### Task 2: CP-0034 and the lifecycle writer

**Files:** Create `java/.../livingchronicle/asset/LifecycleWriter.java`; Modify `Item.java`
(`releaseObjectIdWhenRowDeleted`), `DatabaseIdManager.java` (the startup pass after the cleanup queries; the live
lifecycles' ids added to the used ids), `IdManager.java` if the used-id loop needs it, `AssetTx.java` (`track` as the
capture), `LivingChronicle.java` (start the writer), a migration registering `Asset.LifecycleEnded`; `CORE_PATCHES.md`;
Test `test/.../asset/LifecycleTest.java`.

**Interfaces:** `LifecycleWriter.capture(Item, ItemProcessType, Object reference) -> boolean` (O(1), false refused);
`LifecycleWriter.destroyed(Item)` (O(1) offer); `LifecycleWriter.startup(Connection) -> int[]` (the live lifecycles' ids
to reserve; throws on failure); `LifecycleWriter.isRunning()`.

- [x] Tests, each red first: a destroyed non-stackable keeps its id (a stackable's is released); a tracked item
  destroyed ends `DESTROYED` with its fact; a capture after its object's destruction writes nothing; a capture before it
  is begun and then ended, its row deleted or not; a capture checked live then funded and sunk writes nothing; a fund
  racing the writer binds; a new unsaved item, a dropped item, an unloaded item are begun; an SQL-purged one is begun and
  ended `VANISHED` at the next start; the startup pass ends `VANISHED` (previous run) and
  reserves live ids even with the used-id scan failing; `truth` releases every id and the pass does nothing.
- [x] Implement; revert proofs; ledger CP-0034; commit. (`LifecycleTest`, 12; V020 registers `Asset.LifecycleEnded`.)

### Task 3: legs name lifecycles

**Files:** Modify `AssetTx.java` (leg building, commit's posting insert, created rows in `attachCreated` / `give`, sink of
custody rows, after-commit marks); Test `FundWholeObjectTest` (AT-ASSET-07) or a new `LifecycleLegTest`.

- [ ] Tests: a fund of a non-stackable item begins one lifecycle (`began_op` the fund) named by both legs; release names
  it; the claimed item carries it; a second fund of the same object reuses it; a stack part names none; `route`/`craft`
  create non-stackables each with its own lifecycle; `CRAFT_INPUT` of an escrowed object ends it `DESTROYED` with
  `ended_op`.
- [ ] Implement: lifecycle rows are inserted in the transaction before the postings; marks after commit only.
- [ ] Commit.

### Task 4: `raid`

**Files:** Modify `AssetTx.java`; Test `RaidTest.java` (AT-ASSET-27).

- [ ] Tests: share and rest exactly the contents, else refused; at 300‰, 100 coal in two rows, four swords of one item id
  and one of another: one new object of 30 coal and the lowest-id sword of the four (id, enchant, lifecycle kept) at the
  Master's place after commit, 70 coal, three swords and the single one (rounded to 0) sunk, lifecycles ended, every row
  deleted; pickup saves a new row, census equals counters;
  `UNCERTAIN` resolved as not committed drops nothing; a crash after commit before the drop: nothing dropped, the
  lifecycle `VANISHED` at the next start.
- [ ] Implement; commit.

### Task 5: `issue`

**Files:** Modify `AssetTx.java`; Test `IssueTest.java`.

- [ ] Tests: two parchments in one operation (keys per recipient); a second issue for one key refused; refused per
  clause (stackable, tradable, droppable, sellable, depositable, priced, crystallizable, extractable, skills, a handler
  but `Bypass`); issued item in service mail with a lifecycle, the fact carrying it.
- [ ] Implement; commit.

### Task 6: `recycle`

**Files:** Create `livingchronicle/asset/RecordedSinks.java`; Modify `AssetTx.java`, `AssetService.java` (install);
Test `RecycleTest.java`.

- [ ] Tests: refused with no reader or supplier, nothing recorded, permille 0 or 251, today or later, above the active
  share (101 of 1000 at 100‰), a non-`POOL` custody, twice for one (territory, day) by one pool or two; credited once
  within the share with `Asset.SinkRecycled` naming the bound.
- [ ] Implement; commit.

### Task 7: INV08 at the census, ledger, traceability, gate

- [ ] Track wired: `AssetTx`/`AssetService.track` is the capture (Task 2); Fund/release/route/craft legs name lifecycles
  (Task 3).
- [ ] Census: a live lifecycle whose object id is held (row or loaded) by another item id is a violation; a stackable's
  live lifecycle is a violation. Test red first.
- [ ] `CORE_PATCHES.md`, sites table, traceability, AT names; full gate; Codex review rounds to sign-off.
