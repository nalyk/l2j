# Asset Service, part 2: the core patches — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Mobius's item code safe to take assets from: an `ESCROW` location that restore and startup cleanup leave
alone, counts that never lose an update, items that can be retired, object ids released only after their row is gone,
an atomic castle treasury, and item variables that change in one step. Each patch is a retail-preserving fix with its
own proof. Nothing here moves an asset yet: part 3 does that.

**Architecture:** One core patch per task, each the smallest change to the upstream file, ledgered in `CORE_PATCHES.md`
(CP-0008 onward, format of the existing entries; `tools/intake/check-core-ledger.sh` checks the file list). Tests boot a
real server through `TestServer.boot()` and create characters through `TestPlayers.create`.

**Spec:** `docs/spec/asset-service.md` §2.3, §3.3, §11 (reviewed, revision 11). Verified current sites:
`docs/superpowers/notes/2026-11-20-asset-upstream-facts.md` (cited below as "facts #n").

## Global Constraints

- Upstream files keep their line endings (facts header: LF here; CRLF files are part 3's).
- Every patch keeps retail behaviour for every existing caller: the behaviour lock (the `*LockTest` classes) stays green.
- A patch's proof is trusted only after the fix is reverted on the build server and the proof fails (napkin rule).
- `CORE_PATCHES.md` entry: Files (backticked, on the `- Files:` line), Reason, Change, Invariant, Proof.

## Review Focus

1. An `ESCROW` row must survive `DatabaseCleanUp = True` even if its owner id is not -1 (a corrupted row is evidence,
   reported by `CustodyCheck`, never deleted). Task 1.
2. Two threads changing one stack's count must never lose an update (today `ItemContainer.java:230/268/376` race). Task 2.
3. A retired item must be refused by every container operation and never written, including a scheduled equip
   (`Player.useEquippableItem`). Task 3.
4. An object id must stay reserved while its row exists, even if the row delete fails. Task 4.
5. Concurrent treasury changes (tax income racing a chamberlain withdrawal) must not lose Adena or go below 0. Task 5.

---

### Task 1: CP-0008 the `ESCROW` location

**Files:** `entity/item/enums/ItemLocation.java` (add `ESCROW`), `managers/DatabaseIdManager.java:67` (keep every
`loc = 'ESCROW'` row whatever its owner). Test: `test/java/org/l2jmobius/gameserver/livingchronicle/asset/EscrowLocationTest.java`.

- [ ] Failing tests: `anEscrowRowRestoresAsAnItemWithItsLocation` (`Item.restoreFromDb` on an `ESCROW` row gives
  `ItemLocation.ESCROW`, not null); `cleanupKeepsEscrowRowsWhateverTheirOwner` (a row `owner_id = 999999999`,
  `loc = 'ESCROW'` survives `DatabaseIdManager.cleanDatabase()` with clean-up on); `noContainerLoadsAnEscrowRow`
  (a character's inventory, warehouse and freight restore skip an `ESCROW` row with its id as owner).
- [ ] Run (compile RED for `ESCROW`), patch, GREEN, revert-proof on ultra, ledger entry, commit.

### Task 2: CP-0009 atomic counts and `tryDecrease`

**Files:** `entity/item/instance/Item.java` (`_count` updated by compare-and-set in `setCount` and `changeCount`, clamps
unchanged; new `boolean tryDecrease(long n)` that takes `n` only if at least one unit remains). Test: `AtomicCountTest`.

- [ ] Failing tests: `concurrentChangesLoseNothing` (8 threads × 10 000 `changeCount(+1)` on one stack: exact total);
  `tryDecreaseLeavesAtLeastOne` (count 10: `tryDecrease(9)` true → 1; `tryDecrease(1)` false, count unchanged);
  `clampsAreRetail` (Adena at `MAX_ADENA - 1` + 5 → `MAX_ADENA`; 3 − 5 → 0).
- [ ] RED, patch, GREEN, revert-proof, ledger, commit.

### Task 3: CP-0010 retired items

**Files:** `Item.java` (`retire()`, `isRetired()`; `updateDatabase` returns at once for a retired item),
`ItemContainer.java` (`addItem(Item)`, `transferItem`, `destroyItem` refuse a retired item), `Inventory.java`
(`dropItem` both overloads, `setPaperdollItem`), `Player.java` (`useEquippableItem` refuses). Test: `RetiredItemTest`.

- [ ] Failing tests: one per operation, each asserting the item stays where it was and nothing is written; plus
  `aRetiredItemIsNeverWritten` (`updateDatabase(true)` after `retire()` leaves the row as it was).
- [ ] RED, patch, GREEN, revert-proof (each refusal), ledger, commit.

### Task 4: CP-0011 ids released after the row is gone

**Files:** `Item.java` (`removeFromDb` returns whether the delete succeeded; `updateDatabase` passes it on),
`ItemManager.java:177` (no release here), `ItemContainer.java:235, 385, 450` (release after a successful delete).
Test: `IdReleaseTest`.

- [ ] Failing tests: `aDestroyedItemsIdIsReservedUntilItsRowIsDeleted`; `aFailedDeleteKeepsTheIdReserved` (the delete
  made to fail by a trigger the test installs on `items`).
- [ ] RED, patch, GREEN, revert-proof, ledger, commit.

### Task 5: CP-0012 the castle treasury

**Files:** `mechanics/siege/Castle.java` (`_treasury` changed by compare-and-set in `addToTreasuryNoTax`, clamps and the
`false` return for an overdraw unchanged; the row written under a `ReentrantLock treasuryLock()` the Asset Service will
take). Test: `TreasuryTest`.

- [ ] Failing tests: `concurrentIncomeAndWithdrawalsLoseNothing`; `anOverdrawIsRefusedUnderRace`;
  `theRowEqualsMemoryAfterEveryChange`.
- [ ] RED, patch, GREEN, revert-proof, ledger, commit.

### Task 6: CP-0013 item variables in one step

**Files:** `mechanics/variables/AbstractVariables.java` (each `set` overload tracks and sets under `_saveLock`;
`merge` tracked; `retire()` makes `set`/`remove` refuse and `saveNowSync` do nothing), `ItemVariables.java`
(scheduled save does nothing once retired). Test: `ItemVariablesTest`.

- [ ] Failing tests: `aCaptureNeverSeesATrackedChangeWithoutItsValue` (a capture under the save lock racing `set`);
  `aRetiredVariablesObjectRefusesWritesAndSaves`.
- [ ] RED, patch, GREEN, revert-proof, ledger, commit; full gate; merge to `main` after the gate.
