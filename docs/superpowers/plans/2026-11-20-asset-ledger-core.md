# Asset Service, part 1: the ledger core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Asset Service's database half: one all-or-nothing transaction per asset operation over custodies, with
operation ids, double-entry postings, settlement keys, owner-version checks, staged facts, the freeze switch, uncertain
commit resolution and the startup custody check — everything that does not touch a live Mobius container.

**Architecture:** New code only, under `java/org/l2jmobius/gameserver/livingchronicle/asset/` and `.../journal/`; no core
patch in this part. An `AssetTx` collects legs on one JDBC connection (autocommit off) and commits them with the
`lc_asset_op` row; custody holdings are `items` rows (`owner_id = -1`, `loc = 'ESCROW'`, `loc_data = custody_id`), written
by SQL only. Connections and object ids come through two small interfaces so tests run on the Testcontainers MariaDB
without a game server.

**Tech Stack:** Java 27, plain JDBC (MariaDB Connector/J), JUnit 5, `TestDatabase` + `Migrations` (existing harness).

**Spec:** `docs/spec/asset-service.md` (reviewed rev 10), staging rules from `docs/spec/fact-journal.md` §3.1.

**Sub-project split (scope check).** The spec is four subsystems; each gets its own plan, each shippable on its own:
1. **this plan** — the ledger core (custody ↔ custody, custody → sink, admin correction, recovery checks);
2. Mobius holders — core patches §11.4–§11.6, §11.10, §11.12, §11.15, §11.16, §11.20 (`ItemLocation.ESCROW`, retired
   items, `tryDecrease`, treasury lock, variables lock), `take`/`takeAdena`/treasury debits, pending marks, refunds;
3. service mail — `lc_mail_delivery`, `MailManager` adoption, the deletion guard and login wait (§11.19, §11.21–§11.23);
4. the economy monitor — site scan (AT-ASSET-12), buckets, census, `recover()` census half.

## Global Constraints

- No framework beyond the Java standard library, Mobius commons, plain JDBC and JUnit (02 §7).
- One connection, autocommit off, one transaction per operation (§4.2 rule 1); never Mobius's write helpers (§11.1).
- `OpId` is a deterministic 128-bit id of (module, subject, action, attempt); `OpId.refundOf(opId)` is deterministic too (§2.2, §4.1).
- Quantities are exact and never clamp (§4.2 rule 4); `qty > 0` on every posting.
- Row-lock order (fact-journal.md §3.1): `characters`; `castle`; custody rows; `items` by object id; `messages`/`lc_mail_delivery`; `lc_asset_pending`; owner-module rows (table, then id); `lc_settlement_key`/`lc_asset_op`; stream heads; outbox inserts.
- Every transaction sets `innodb_lock_wait_timeout` from `LockWaitMillis`; a deadlock (SQLState `40001`, MariaDB 1213) or lock-wait timeout (1205) is retried up to `DeadlockRetries` times.
- `UncertainCommitLookupMillis` default 5000 (§8); after it the module is frozen, never aborted.
- Custody ids are `INT`s; Adena (item id 57) is one stackable row per custody (§2.3).
- New files follow `GameClock.java`'s style: tab indentation, Allman braces, `_field` names, no license header.
- Tests live under `test/java/org/l2jmobius/gameserver/livingchronicle/{asset,journal}/` and run with `./build.sh test -Dtest.only=<Class>`.

## Review Focus

1. **A retry of the same `OpId` after a commit whose result was lost** must return the committed outcome and move nothing (R02) — Task 5 test `aRetryAfterALostCommitResultMovesNothing`.
2. **Two operations racing on one custody** must serialise on the custody row; neither may see the other's half (custody row `FOR UPDATE`) — Task 6 test `twoMovesOutOfOneCustodyNeverOverdraw`.
3. **A move of more Adena than the custody holds** must abort with nothing written (no clamp) — Task 6 test `aMoveBeyondTheBalanceAbortsAndWritesNothing`.
4. **A frozen owner module** must refuse every operation on its custodies except `correct` (§4.2 rule 7) — Task 7 test `aFrozenModuleRefusesAllButCorrections`.
5. **An escrow row edited behind the service's back** (count changed in SQL) must be caught by the startup check as an INV11 violation that freezes, never repaired — Task 8 test `aTamperedEscrowRowFreezesItsCustodyAtStartup`.

---

### Task 1: `OpId`

**Files:**
- Create: `java/org/l2jmobius/gameserver/livingchronicle/asset/OpId.java`
- Test: `test/java/org/l2jmobius/gameserver/livingchronicle/asset/OpIdTest.java`

**Interfaces:**
- Produces: `public record OpId(byte[] bytes)` (16 bytes, defensive copies, value `equals`/`hashCode`);
  `static OpId of(String module, long subject, String action, int attempt)`; `OpId refund()` (= `refundOf(this)`);
  `static OpId refundOf(OpId op)`; `String hex()`.

- [ ] **Step 1: Write the failing tests** — `sameInputsSameId` (two `of("PACT", 7, "FUND", 0)` equal); `anyInputChangesTheId` (vary each of the four, all distinct); `fieldsCannotRunTogether` (`of("PA","1"…)` vs `of("P","A1"…)`-style ambiguity: module `"AB"`/action `"C"` ≠ module `"A"`/action `"BC"`); `refundIsDeterministicAndNeverTheOriginalOrAnotherRefund` (`refundOf(x).equals(refundOf(x))`, `≠ x`, `refundOf(refundOf(x)) ≠ refundOf(x)`); `bytesAreSixteenAndCopied`.
- [ ] **Step 2: Run** `./build.sh test -Dtest.only=OpIdTest` — expect compile failure (no `OpId`).
- [ ] **Step 3: Implement.** First 16 bytes of SHA-256 over a length-prefixed encoding (`DataOutputStream.writeUTF` of module and action, `writeLong` subject, `writeInt` attempt, with a leading domain tag byte `0x01`); `refundOf` hashes tag `0x02` plus the original 16 bytes.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `Asset: OpId, deterministic operation ids`.

### Task 2: Staged durable facts (the journal's outbox half)

**Files:**
- Create: `java/org/l2jmobius/gameserver/livingchronicle/journal/DurableFact.java` (interface: `String typeName()`, `int schemaVersion()`, `String stream()`, `int sensitivity()`, `String payloadJson()`)
- Create: `java/org/l2jmobius/gameserver/livingchronicle/journal/OutboxStager.java`
- Create: `sql/migrations/V017__lc_asset_fact_types.sql` — `INSERT INTO lc_fact_type` one row per §5 `Asset.*` type: ids 1000–1024 (25 types) in §5's table order, `schema_ver 1`, `DURABLE`, `OUTBOX`, family `asset`, sensitivity 1, retention NULL, owner `ASSET`.
- Test: `test/java/org/l2jmobius/gameserver/livingchronicle/journal/OutboxStagerTest.java`

**Interfaces:**
- Produces: `final class OutboxStager` with `OutboxStager(Clock wall, LongSupplier logicalTime, IntSupplier configVersion)`; `List<Long> stage(Connection c, OpId op, List<DurableFact> facts)` — returns the `outbox_id`s; type id looked up by name from `lc_fact_type` (cached), unknown name → `IllegalStateException`. `region_id = 0`, `instance_id = 0`, `actor_snapshot = NULL` (the Asset Service stages system facts; actor snapshots arrive with Actor Identity).

- [ ] **Step 1: Failing tests** (real DB, `Migrations.applyAll`): `stagesInOneTransactionWithTheCaller` (stage, rollback → no outbox row, head unchanged); `consecutiveNumbersPerStream` (two stages in two transactions on `asset.custody.5` → seqs 1,2; another stream starts at 1); `headIsLockedSoConcurrentStagersSerialise` (second connection's stage blocks until the first commits; seqs distinct); `anUnregisteredTypeIsRefused`; `everyAssetFactTypeOfTheSpecIsRegistered` (names of §5 present in `lc_fact_type`).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Per stream in sorted stream order: `INSERT IGNORE INTO lc_stream_head (stream, last_seq) VALUES (?, 0)`, `SELECT last_seq … FOR UPDATE`, insert facts, `UPDATE lc_stream_head SET last_seq = ?`. Streams sorted so two stagers lock heads in one order.
- [ ] **Step 4: Run** — PASS. **Step 5: Commit** `Journal: stage durable facts in the caller's transaction`.

### Task 3: Connections, object ids, configuration

**Files:**
- Create: `.../asset/AssetConfig.java` — `record AssetConfig(int commitBudgetMillis, int uncertainCommitLookupMillis, int lockWaitMillis, int deadlockRetries)` with `static AssetConfig defaults()` = (15, 5000, 200, 3); constructor rejects values outside the ranges of asset-service.md §8 and fact-journal.md §8 (`LockWaitMillis` 50–10000, `DeadlockRetries` as fact-journal.md §8 pins it) (`IllegalArgumentException`).
- Create: `.../asset/Connections.java` — `@FunctionalInterface interface Connections { Connection open() throws SQLException; }`
- Create: `.../asset/ObjectIds.java` — `interface ObjectIds { int next(); }`; production binding `IdManager.getInstance()::getNextId` is wired in part 2.
- Test: `.../asset/AssetConfigTest.java` — `defaultsAreTheSpecs`, `outOfRangeIsRefused` (0 and 1001 for commit budget; 99 and 60001 for lookup).

- [ ] Steps: failing test → run (FAIL) → implement → run (PASS) → commit `Asset: configuration and seams`.

### Task 4: Custodies and the freeze switch

**Files:**
- Create: `.../asset/Custody.java` — `record Custody(int id, Kind kind, String ownerModule, long ownerRef, State state, int version)` with nested enums `Kind {CONTRACT, POOL, MANIFEST}`, `State {OPEN, FROZEN, CLOSED}`.
- Create: `.../asset/HoldingPolicy.java` — `interface HoldingPolicy { boolean allowsHolding(Connection c, long ownerRef) throws SQLException; }`
- Create: `.../asset/ModuleState.java` — `static boolean isFrozen(Connection c, String module)`, `static void freeze(Connection c, String module, String reason, Long evidenceFactId)` (upsert `FROZEN`), `static void unfreeze(...)`.
- Test: `.../asset/ModuleStateTest.java` — `aModuleWithNoRowRuns`, `freezeAndUnfreeze`, `aFreezeNeedsAReason` (empty reason → `IllegalArgumentException`).

- [ ] Steps: failing test → FAIL → implement → PASS → commit `Asset: custody record, holding policy, module freeze`.

### Task 5: `AssetService.begin` and `AssetTx` — the transaction engine

**Files:**
- Create: `.../asset/AssetService.java`, `.../asset/AssetTx.java`, `.../asset/Leg.java`, `.../asset/Outcome.java`, `.../asset/AssetAbort.java`
- Test: `.../asset/AssetTxTest.java`

**Interfaces:**
- Consumes: `OpId`, `OutboxStager`, `AssetConfig`, `Connections`, `ObjectIds`, `HoldingPolicy`, `ModuleState`.
- Produces:
  - `AssetService(Connections db, ObjectIds ids, OutboxStager stager, AssetConfig config, Map<String, HoldingPolicy> owners)`
  - `AssetTx begin(OpId op, String module, String action, long subjectRef)` — if `lc_asset_op` holds `op` → a tx whose `commit()` returns `Outcome.ALREADY_COMMITTED` and does nothing; if it holds `op.refund()` → `Outcome.ALREADY_REFUNDED`.
  - `enum Outcome { COMMITTED, ALREADY_COMMITTED, ALREADY_REFUNDED }`
  - `final class AssetAbort extends Exception` with `enum Reason { INSUFFICIENT, STALE_VERSION, ALREADY_SETTLED, CUSTODY_NOT_OPEN, NOT_ALLOWED_TO_HOLD, MODULE_FROZEN, UNBALANCED, NOT_COMMITTED, CONTENTION, ERROR }` and `Reason reason()`; `final class UncertainCommitException extends RuntimeException` (the module is frozen; never an abort).
  - **Execution model (decided in Task 5):** nothing touches the database before `commit()`; operations are declared as steps, and `commit()` runs them all on one connection after locking every custody they name in id order, so a deadlock victim or lock-wait timeout replays the whole transaction (up to `DeadlockRetries`).
  - `AssetTx implements AutoCloseable`: `expectVersion(String table, String idColumn, long id, int version)`, `settlementKey(String scope, String key)`, `fact(DurableFact f)`, `Outcome commit() throws AssetAbort`; `close()` rolls back anything uncommitted.
  - `record Leg(int leg, AccountKind kind, long accountId, String code, int itemId, Integer objectId, Long lifecycleId, long qty, Direction direction)` with nested enums matching the DDL.
- Behaviour pinned by the spec: statements in the row-lock order; before `COMMIT` check INV01 per item id over the collected legs (`UNBALANCED` otherwise — a bug, not a user error); `lc_asset_op` inserted last before the stream heads; a duplicate `lc_settlement_key` (SQLState `23000`) → `ALREADY_SETTLED`; `expectVersion` does `SELECT version … FOR UPDATE` then `UPDATE … SET version = version + 1`.
- Uncertain commit: on `SQLException` thrown by `commit()` itself, open a new connection and `SELECT op_id FROM lc_asset_op WHERE op_id = ? FOR UPDATE` (lock wait `UncertainCommitLookupMillis`): present → `COMMITTED`; absent → `AssetAbort`; lookup failing past the budget → `ModuleState.freeze(module, "UNCERTAIN_COMMIT", null)` and rethrow.

- [ ] **Step 1: Failing tests** (fault injection through a `Connections` wrapper whose `Connection` proxy can throw on `commit()` after delegating, or before): `anEmptyOperationCommitsItsOpRow`; `aRetryOfACommittedOpIdReturnsAlreadyCommittedAndWritesNothing`; `aRetryAfterALostCommitResultMovesNothing` (proxy commits then throws → outcome `COMMITTED` via lookup; retry → `ALREADY_COMMITTED`); `aCommitThatNeverHappenedAbortsAfterTheLookup` (proxy throws before delegating → `AssetAbort`, no `lc_asset_op` row); `aSecondSettlementOfOneKeyAbortsAsAlreadySettled`; `aStaleVersionAborts` (owner table `lc_test_owner(id, version)` created by the test); `factsAreStagedWithTheOperation` (outbox row carries the op id; absent after an abort).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS. **Step 5: Commit** `Asset: the operation transaction, idempotent and uncertainty-safe`.

### Task 6: Custody operations — `open`, `move`, `close`

**Files:**
- Modify: `.../asset/AssetTx.java` (add the operations), Create: `.../asset/Asset.java` — `record Asset(int itemId, long qty, Integer objectId)` (`objectId` set = whole object, null = an amount of a stackable item)
- Create: `.../asset/facts/AssetFacts.java` — records implementing `DurableFact` for `CustodyOpened`, `CustodyMoved`, `CustodyClosed`, `CustodyFrozen`, `AdminCorrection`, `InvariantViolated`, `RecoveryCompleted` (payload JSON built with a small escaping writer in the same file; stream `asset.custody.<id>`)
- Test: `.../asset/CustodyOperationsTest.java`

**Interfaces:**
- Produces: `int AssetService.open(OpId op, Custody.Kind kind, String ownerModule, long ownerRef)` — an operation of its own (a custody must exist before other operations name it); idempotent: a retry returns the custody whose `created_op` is `op`. On `AssetTx`: `void move(int from, int to, List<Asset> assets)`; `void close(int custody)`.
- Rules pinned: custody rows locked `FOR UPDATE` in custody-id order; both must be `OPEN` (`CUSTODY_NOT_OPEN`), neither owner frozen (`MODULE_FROZEN`), destination owner's `allowsHolding` true (`NOT_ALLOWED_TO_HOLD`); a whole object moves by `UPDATE items SET loc_data = ? WHERE object_id = ? AND owner_id = -1 AND loc = 'ESCROW' AND loc_data = ?` (affected rows must be 1); an amount of a stackable item decreases the source's row (`INSUFFICIENT` if it would go below 0; a row reaching 0 is deleted) and increases the destination's row of that item id, inserting one under `ids.next()` if absent; each custody's version + 1; postings DEBIT `CUSTODY from` / CREDIT `CUSTODY to`; `close` refuses a custody holding any `ESCROW` row; INV11 checked for every touched custody before `COMMIT` (Σ postings = Σ rows; `UNBALANCED` + freeze if not).

- [ ] **Step 1: Failing tests:** `openWritesAnOpenCustodyAndItsFact`; `moveOfAWholeObjectChangesOnlyItsCustody`; `moveOfAdenaSplitsAndMergesRows`; `aMoveBeyondTheBalanceAbortsAndWritesNothing`; `twoMovesOutOfOneCustodyNeverOverdraw` (two threads, each moving 60 of 100 Adena: exactly one commits); `closeRefusesANonEmptyCustodyAndClosesAnEmptyOne`; `aClosedCustodyAcceptsNothing`; `postingsBalanceAndMatchRows` (query INV01 and INV11 after a run of random moves).
- [ ] Steps 2–5: FAIL → implement → PASS → commit `Asset: open, move and close custodies`.

### Task 7: Sinks from custody and corrective operations

**Files:** Modify `AssetTx.java`; test `.../asset/SinkAndCorrectionTest.java`.

**Interfaces:**
- Produces: `void sink(int custody, List<Asset> assets, SinkCode code, long reference)` with `enum SinkCode { CROWN_UPKEEP, ROAD_LOSS, CRAFT_INPUT }` (custody-sourced sinks of §2.5; the others need Mobius holders, part 2); `void correct(int custody, List<Asset> in, List<Asset> out, long violationFactId, String reason, int adminCharId)` posting `ADMIN_CORRECTION` legs, allowed on `FROZEN` custodies and frozen modules; `AssetService.unfreeze(String module)` — only if INV03, INV05 and INV11 hold over the module's custodies (else `IllegalStateException` naming the custody).
- Rules: `sink` deletes or decreases rows and posts DEBIT custody / CREDIT `SINK(code)`; while a module is frozen nothing but `correct` commits (§4.2 rule 7).

- [ ] **Step 1: Failing tests:** `aSinkRemovesTheAssetAndPostsItsCode`; `aFrozenModuleRefusesAllButCorrections`; `aCorrectionCreatesIntoACustodyWithAnAuditFact` (source leg `ADMIN_CORRECTION`, fact carries admin, violation, reason, before/after counts); `unfreezeRefusesWhileAnInvariantIsBroken`.
- [ ] Steps 2–5 → commit `Asset: custody sinks and corrective operations`.

### Task 8: The startup custody check (`recover`, ledger half)

**Files:** Create `.../asset/CustodyCheck.java`; test `.../asset/CustodyCheckTest.java`.

**Interfaces:**
- Produces: `CustodyCheck(Connections db, OutboxStager stager, Map<String, HoldingPolicy> owners)`; `Report run()` with `record Report(int custodiesChecked, List<String> violations)`. Checks INV03 (every `ESCROW` row: owner -1, custody `OPEN`/`FROZEN`), INV04 (a `CLOSED` custody holds no row), INV05, INV11 over every custody; each violation stages `Asset.InvariantViolated`, sets the custody `FROZEN` (`Asset.CustodyFrozen`) and freezes its owner module (the `ASSET` module when the owner is unknown); stages `Asset.RecoveryCompleted`. It never changes an `items` row.

- [ ] **Step 1: Failing tests:** `aCleanDatabasePasses`; `aTamperedEscrowRowFreezesItsCustodyAtStartup` (SQL `UPDATE items SET count = count + 1` → INV11 violation, custody `FROZEN`, module `FROZEN`, row untouched); `anEscrowRowUnderAClosedCustodyIsAViolation`; `anOwnerThatNoLongerAllowsHoldingIsAViolation`; `anUnknownOwnerFreezesTheAssetModule`.
- [ ] Steps 2–5 → commit `Asset: startup custody check`.

### Task 9: Spec bookkeeping

**Files:** Modify `docs/spec/asset-service.md` §12 (a line: "N4 part 1 implemented: AT-ASSET-01/02/03/04/15 partly, 21 for custody operations"), `CORE_PATCHES.md` untouched (no core file changed — verify with `tools/intake/check-core-ledger.sh`).

- [ ] Run the whole gate `./build.sh` → `BUILD SUCCESSFUL`; commit `Asset: part 1 recorded`.
