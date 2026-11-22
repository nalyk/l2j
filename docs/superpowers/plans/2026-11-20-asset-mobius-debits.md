# Asset Service, part 4: debits from live Mobius holders — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operations that take items and Adena out of live characters' inventories and warehouses, clan warehouses and
castle treasuries, exactly as asset-service.md §3.3 orders the locks: whole objects one at a time, then stack parts
holding their monitors and write locks to `DONE`; the pending mark that makes a login wait; and the refund, by service
mail, of everything an aborted operation took.

**Architecture:** Debits are steps of `AssetTx` that run before the transaction opens (memory first, under Mobius's
own locks, in Mobius's lock order), then write their rows inside it. A `Taken` record per debit holds what the refund
needs. Core seams added only where Mobius hides what the service must do: a public way to take an item out of a
container, the item's write lock, and memory-only treasury changes.

**Spec:** `docs/spec/asset-service.md` revision 12, §2.1 (holders), §2.4 (authority, pending marks), §3.2 (`MARKED`,
`TAKEN`, `ABORTED → REFUNDED`, `DONE`), §3.3, §4.2, §4.3 (`fund`, `charge`, `commit`/`return`, `exchange`), §4.4,
§11.15-§11.18, §11.20, §11.22. Upstream facts: `docs/superpowers/notes/2026-11-20-asset-upstream-facts.md`.

## Global Constraints

- Lock order (§3.3, facts #4): a whole object: the item's monitor, then the container's; never another service lock
  while waiting for one of Mobius's. Stack parts: all stack monitors in object-id order, then all their `_dbLock`s in
  object-id order, held to `DONE`; no container method is called while holding them.
- The pending mark is committed on its own before the first debit and removed by the operation's or the refund's
  transaction (§2.4); a login waits while one exists (§11.22).
- A refund is the operation `OpId.refundOf(op)`, committed before any lock is released (§3.2).
- Every debit checks eligibility (§4.4) before anything is written.

## Review Focus

1. A character logging out, logging in, or `Player.store` running while one of its items is being funded: no second
   live `Item`, the row restored is the committed one. Tasks 2 and 3.
2. A retail transfer emptying or merging the stack being debited. Task 5.
3. An abort after the debit (stale version, settlement key taken, connection lost before COMMIT): everything taken comes
   back by service mail, exactly once, and the pending mark goes. Task 4.
4. An equipped, quest, time-limited, shadow or pet-control item, or one an enchant is using: refused before anything
   moves. Task 3.
5. A clan warehouse whose leader is offline. Task 6.

---

### Task 1: CP-0017 the taking seams

**Files:** `ItemContainer.java` (`public boolean removeForAsset(Item)`: `removeItem` through its overrides, then
`refreshWeight`), `Item.java` (`awaitWrites()`: take and release the write lock; `writeLock()` for stack debits),
`Castle.java` (`tryDebitTreasury(long)`, `creditTreasuryInMemory(long)`: CAS only, the row left to the caller holding
`treasuryLock()`). Test: `TakingSeamsTest`.

### Task 2: pending marks and CP-0018 the login wait

**Files:** `livingchronicle/asset/PendingMarks.java`, `GameClient.java` (`load` waits while marked, polling, and
refuses after `UncertainCommitLookupMillis` × 4 with the character not loaded). Test: `LoginWaitTest`.

### Task 3: `fund` a whole object from a character's inventory or warehouse

**Files:** `Holder.java` (`player(charId)`, `playerWarehouse(charId)`), `AssetTx.take(Holder, objectId)`,
`AssetTx.toCustody(int)`, `Eligibility.java` (§4.4). Test: `FundWholeObjectTest` (the item leaves memory, its row becomes
`ESCROW` with its metadata rows, it is retired; a re-login does not load it; each ineligible kind refused).

### Task 4: refunds

**Files:** `AssetTx` (on abort after `TAKEN`: the refund operation writes service mail to each holder of what was taken,
removes the pending mark, and is retried until it commits). Test: `RefundTest` (stale version, settlement key taken,
connection lost before COMMIT).

### Task 5: stack parts and `charge`

**Files:** `AssetTx.takeAmount(Holder, itemId, qty)`, `AssetTx.charge(Holder, long, SinkCode)` (the fee codes join
`SinkCode`). Test: `StackDebitTest` (Adena from a stack racing retail transfers and `Player.store`; the stack's row
written from memory; an emptied-meanwhile stack's row deleted).

### Task 6: clan warehouses and castle treasuries

**Files:** `Holder.clanWarehouse(clanId)`, treasury `commit`/`return`, `ClanWarehouse.java` (the part 2 obligation:
notify scripts only for an operation that happens; null-safe transfer). Tests: `ClanWarehouseDebitTest`, `TreasuryOperationsTest`.

### Task 7: `exchange`

**Files:** `AssetTx.exchange(...)`. Test: `ExchangeTest`. Then the full gate and a merge to `main`.
