# SPEC-ASSET — Asset Service

- Status: draft (revision 3, answering two adversarial review rounds; record in §12)
- Blueprint: 04 §3 (Asset Service), §8 (failure degradation), §9 (persistence); 02 §4 (production invariants), §6 (budgets);
  15 §3–§4 (PACT lifecycle and escrow rules); 16 §7 (zero minting); 12 §6.2 (treasury commitments), §8 (caravans);
  consensus §4.2 a–b, §4.3, §10.2–§10.3.
- DDL: `sql/migrations/V010__lc_asset.sql`. Classified sites: `docs/spec/asset-economy-sites.csv` (§2.7).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31, Mobius
  f1e2649e33cc); paths are under `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Asset Service is the one path by which Living Chronicle code moves or holds an item or Adena (02 §3). It owns:

- **custody**: every holding that is not a Mobius container (contract escrow, institutional pools, caravan manifests,
  deliveries waiting for their beneficiary);
- **asset operations**: all-or-nothing transfers between holders, each one InnoDB transaction with an operation id;
- **the ledger**: double-entry postings for every asset operation;
- **the economy monitor**: conservation of every item id and of Adena across the whole server, Mobius flows included
  (04 §3.6), against a reviewed classification of every place quantity changes (§2.7).

It does not own: contract terms and states (PACT, Black Ledger, Crown Law own theirs and answer a holding predicate,
INV05), the Fact Journal tables (it writes durable facts into them inside its transaction), town supply budgets (Aden
Remembers), Mobius's own transfers (trade, private store, mail, warehouse, NPC shops, auctions: they stay retail and are
*observed* by the monitor), and anything in combat or drop rates (02 §1).

Depends on: Fact Journal (outbox facts), Actor Identity (actor snapshots), the core patches of §11.

## 2. Data

### 2.1 Holders

An asset is always in exactly one holding. An asset operation debits and credits only the holders below.

| Order | Holder | Key | Authoritative copy | Assets |
|---|---|---|---|---|
| 1 | `CASTLE_TREASURY` | castle id | memory: `Castle._treasury` (every castle is loaded; `getTreasury`, Castle.java:981); row `castle.treasury` is its copy | Adena |
| 2 | `CLAN_WAREHOUSE` | clan id | memory: the clan's warehouse container, restored when the clan is built (Clan.java:145, 177) and changed by Mobius without saving (CastleManorManager.java:380, ClanHallAuction.java:317) | items, Adena |
| 3 | `PLAYER` | char id | memory: the inventory of a player **in the world** (§2.4) | items, Adena |
| 4 | `PLAYER_WAREHOUSE` | char id | memory: the warehouse container of a player in the world | items, Adena |
| 5 | `CUSTODY` | custody id | the `items` rows `owner_id = -1`, `loc = 'ESCROW'`, `loc_data = custody id` | items, Adena |
| 6 | `GROUND` | — (credit only) | memory: an item placed in the world (a caravan raid's dropped share, 12 §8); until placed, the committed `lc_ground_drop` row | items, Adena |
| — | `SOURCE(code, ref)` / `SINK(code, ref)` | posting code (§2.5) | nowhere: the asset enters or leaves existence | — |

- `PLAYER` and `PLAYER_WAREHOUSE` of one character share one holder lock (L1, keyed by the character), because login and
  logout restore and tear down both together (Player.java:7358-7360).
- A character that is not in the world is never a holder: what an operation owes it goes into a `DELIVERY` custody
  (§4.3), claimed in person later. No operation writes an offline character's rows.
- Equipped items, pet, mail, freight, refund and auction-bid holdings are never debited or credited by an asset
  operation; the monitor counts them (§2.6).
- `owner_id = -1` is the owner Mobius's startup cleanup keeps (DatabaseIdManager.java:67) and that no restore, repair
  or delete-by-owner query selects (AdminRepairChar.java:101, GameClient.java:451 select a character's id). Blanket
  deletions by item id do reach it (LongTimeEvent.java:557, Hero.java:81): §4.4 keeps those item ids out of custody, and
  §2.7 classifies every such statement. `items.loc_data` is an `INT` (`dist/db_installer/sql/game/items.sql`), so
  custody ids are `INT`s.

### 2.2 Tables

```text
lc_custody(custody_id INT PK auto, kind {CONTRACT|POOL|MANIFEST|DELIVERY}, owner_module VARCHAR(16), owner_ref BIGINT,
           beneficiary_char INT NULL, origin_op BINARY(16) NULL, state {OPEN|FROZEN|CLOSED}, version INT,
           created_op BINARY(16), closed_op BINARY(16) NULL, created_at TIMESTAMP(3), closed_at TIMESTAMP(3) NULL)
lc_asset_op(op_id BINARY(16) PK, module VARCHAR(16), action VARCHAR(32), subject_ref BIGINT, committed_at TIMESTAMP(3),
            applied_at TIMESTAMP(3) NULL)
lc_ledger_posting(id BIGINT PK auto, op_id BINARY(16) → lc_asset_op, leg SMALLINT, account_kind {CASTLE_TREASURY|
                  CLAN_WAREHOUSE|PLAYER|PLAYER_WAREHOUSE|CUSTODY|GROUND|SINK|SOURCE}, account_id BIGINT, code VARCHAR(24) NULL,
                  item_id INT, object_id INT NULL, lifecycle_id BIGINT NULL, qty BIGINT > 0, direction {DEBIT|CREDIT},
                  fact_id BIGINT NULL, UNIQUE(op_id, leg))
lc_settlement_key(scope VARCHAR(16), skey VARCHAR(96), op_id BINARY(16) → lc_asset_op, PRIMARY KEY(scope, skey))
lc_item_lifecycle(lifecycle_id BIGINT PK auto, object_id INT, item_id INT, parent_lifecycle_id BIGINT NULL, began_op BINARY(16) NULL,
                  began_at TIMESTAMP(3), ended_at TIMESTAMP(3) NULL, end_reason {DESTROYED|MERGED|SPLIT_REMAINDER_GONE} NULL,
                  merged_into BIGINT NULL, UNIQUE(object_id while ended_at IS NULL))
lc_economy_bucket(window_start TIMESTAMP, item_id INT, site VARCHAR(48), class {SOURCE|SINK|MOVE_OUT|MOVE_IN},
                  qty BIGINT UNSIGNED, events INT UNSIGNED, PRIMARY KEY(window_start, item_id, site, class))
lc_economy_census(census_id BIGINT, taken_at TIMESTAMP(3), kind {SHUTDOWN|STARTUP|REBASE}, item_id INT, holding VARCHAR(16),
                  qty BIGINT UNSIGNED, PRIMARY KEY(census_id, item_id, holding))
lc_economy_counter(item_id INT PK, existence BIGINT, as_of_census BIGINT)
lc_module_state(module VARCHAR(16) PK, state {RUNNING|FROZEN}, reason VARCHAR(64), since TIMESTAMP(3), evidence_fact_id BIGINT NULL)
lc_ground_drop(op_id BINARY(16), leg SMALLINT, x INT, y INT, z INT, instance_id INT, placed_at TIMESTAMP(3) NULL,
               PRIMARY KEY(op_id, leg) → lc_ledger_posting)
```

- `lc_custody.owner_module/owner_ref` name what the custody is for: (`PACT`, contract id), (`LEDGER`, contract id),
  (`CROWN`, law id), (`CARAVAN`, caravan id). A `DELIVERY` custody carries the owner of the operation that deferred it,
  its `origin_op`, and its `beneficiary_char`.
- An `lc_asset_op` row exists exactly when its operation committed: it is written inside the operation's transaction.
  `applied_at` records that the committed state is in memory (§3.2).
- `lc_settlement_key` is the single settlement-uniqueness mechanism for every module; PACT's `pact_settlement` and Black
  Ledger's payouts reference it.
- `lc_item_lifecycle` gives an item a provenance id from its first asset operation on, because Mobius reuses object ids
  after destruction (IdManager.java:158-177). It ends when the object leaves existence or merges into another stack
  (both are classified sites, §2.7); a split of a tracked stack starts a child lifecycle (`parent_lifecycle_id`).
- `lc_economy_*` hold the monitor's state (§2.6): buckets are telemetry, never per-event rows (04 §1.1).
- `lc_module_state` is the freeze switch every Living Chronicle module honours (04 §8); the Asset Service is its first user.
- `lc_ground_drop` records where a committed `GROUND` leg is to be placed; `placed_at` is set once it is in the world, so
  a drop that committed but was not placed (thread death, crash) is placed by the applier or by startup recovery.

### 2.3 The `items` row of an escrowed asset

`owner_id = -1`, `loc = 'ESCROW'`, `loc_data = custody_id`, every other column as it was in memory at commit. The item's
`item_attributes`, `item_elementals` and `item_variables` rows stay keyed by its object id and are written from memory in
the same transaction (§4.2 rule 8). Adena in a custody is one stackable row per custody (item id 57, Inventory.java:75).

### 2.4 Authority, presence and handoff

- For every Mobius holder an operation touches, memory is authoritative and the database is a lagging copy (§2.1).
- An operation never writes an absolute value over a Mobius holder's row. It writes **relative deltas**
  (`count = count - n`, a whole object's `owner_id/loc/loc_data` by object id, `treasury = treasury + n`) to rows that it
  first made current under a fence (§3.3), and applies the same deltas to memory. A delta composes with Mobius's own lazy
  absolute writes: whatever Mobius later writes is memory, and memory already contains the delta.
- A player is **in the world** from the moment login, holding L1, has restored inventory, warehouse and freight
  (Player.java:7358-7360), until logout takes L1. Logout takes L1 at the start of the whole disconnection sequence,
  before the first save (`Disconnection.storeAndDelete`, Disconnection.java:116), and holds it through `deleteMe`, the
  final saves and the removal of the containers. An operation checks presence under L1: a character not in the world is not
  a holder, and what the operation owes it goes into a `DELIVERY` custody.
- Custody rows have no in-memory `Item` outside an operation.

### 2.5 Posting codes

`SOURCE` and `SINK` postings carry a code from this table and, where it names one, a reference; adding one is a spec
change.

| Code | Kind | Reference | Meaning |
|---|---|---|---|
| `FEE_NOTARIUS`, `FEE_BROKER`, `FEE_QUESTIONING`, `FEE_ALIAS`, `FEE_GAZETTE` | SINK | — | institutional fees (15 §6, 16 §7, 17 §2) |
| `TOWN_SUPPLY` | SOURCE | origin town | goods leaving a town's supply budget into a caravan manifest (12 §8) |
| `TOWN_SUPPLY` | SINK | destination town | a manifest's goods arriving into a town's supply budget |
| `ROAD_LOSS` | SINK | caravan | the part of a raided manifest that is neither dropped nor delivered |
| `ADMIN_CORRECTION` | SOURCE / SINK | audit fact | a corrective admin operation (§4.5) |

Living Chronicle mints nothing for players: a `TOWN_SUPPLY` source is matched by the supply budget it draws down (owned
by Aden Remembers), and every other source is an audited correction.

### 2.6 The economy monitor

Conservation across the whole server, with authorisation coming from the site table (§2.7), not from a process name. What
it guarantees, and what it only watches, are stated separately.

**Guaranteed (exact):**

1. Every operation's postings balance (INV01) and every custody holds exactly what its postings say (INV11), always,
   including across crashes.
2. Across a clean restart, every item id is conserved exactly: the census taken at the shutdown boundary equals the census
   taken at the next startup, and the existence counters carried between them equal both (INV06).

**Watched (alarm, not proof):**

3. Every place that changes an item's existence or quantity is a classified site, and each change is counted under its
   site (§2.7). A per-window imbalance between a move site's outgoing and incoming quantity beyond `MoveToleranceQty`, for
   two consecutive windows, raises `Asset.MoveImbalance`. Retail transfers span calls, threads and persistence steps (mail
   cash on delivery, RequestPostAttachment.java:183-256; auction refunds; freight; offline shops), so an exact live check
   would need an identity on every retail transfer; this is a deliberate limit, not a guarantee, and offsetting errors of
   equal size in one window can hide each other.

**The shutdown boundary.** After Mobius's shutdown has disconnected every player and stopped its task pools
(Shutdown.java:380, 403), the service writes every authoritative container that Mobius does not (a clan warehouse whose
leader is offline is skipped by `ItemContainer.updateDatabase`, ItemContainer.java:579: the service writes each clan
warehouse's items itself), checks every write succeeded, posts each memory-only holding about to be discarded as a sink
(ground items when `SaveDroppedItem = False`, General.ini:217; with it on they are counted from `itemsonground` instead),
and only then takes the `SHUTDOWN` census from the database and persists the counters. Refund lists are already
destroyed at logout (Player.java:12131, PlayerRefund.java:85) through a classified sink site, so they are never counted.

**At startup**, before any login, the database is counted again (`STARTUP`) and compared with the persisted counters.
After a crash there is no boundary: the census is a `REBASE`, recorded with its per-item difference in
`Asset.MonitorRebased`, and never reported as conservation holding; INV11 still holds exactly.

**Holdings counted**: inventory and paperdoll, warehouse, clan warehouse, pet and pet equipment, mail attachments,
freight, custody rows, castle treasuries, ground (`itemsonground`, when saved), and the open bid commitments: item-auction
bids whose `playerBid > 0` in auctions not yet finished (ItemAuctionBid.java:51-58, ItemAuction.java:473-477), and
clan-hall bids (`auction_bid.maxBid`, ClanHallAuction.java:326). Each bid transition is its own site in §2.7: placing a bid
moves Adena into the bid holding; cancelling an item-auction bid moves it back; cancelling a clan-hall bid moves 90 % back
and sinks 10 % (ClanHallAuction.java:292-317); winning an NPC's auction sinks the bid; winning a player-owned hall moves
the bid to the seller (ClanHallAuction.java:470-473), with any clamp a sink.

### 2.7 The site table

`docs/spec/asset-economy-sites.csv`: one row per site, `site, file, member, class, holding_from, holding_to, reason`. It is
reviewed like the spec. A static test (AT-ASSET-12) scans the compiled core and every datapack script for each call that
can change an item's existence or quantity (`ItemManager.createItem`, `ItemManager.destroyItem`, `Item.changeCount`,
`Item.setCount`, the `Item` constructors) and for every SQL string that deletes or updates `items`, and fails on any
site the table does not classify, and on any table row that no longer matches a site. Known sites that must be in it from
the start: direct item construction for lottery and race tickets (`dist/game/data/scripts/handlers/bypass/npc/Loto.java:242`,
RaceManager.java:171) and mercenary tickets (MercTicketManager.java:195), restores (`RESTORE`), and the blanket SQL
deletions of event and hero items (LongTimeEvent.java:557, Hero.java:81: `SINK`). A constructor followed by
`setCount` on the fresh object, before it enters any holding, is one site (the quantity first exists there); a
reconstruction from rows is `RESTORE`.

## 3. State machines

### 3.1 Custody

| From | To | Guard | Effect |
|---|---|---|---|
| — | `OPEN` | an asset operation opens it | `Asset.CustodyOpened` |
| `OPEN` | `OPEN` | an operation funds, releases, drops or loses part of it | version + 1 |
| `OPEN` | `CLOSED` | it holds no `ESCROW` row | `Asset.CustodyClosed`; terminal |
| `OPEN` | `FROZEN` | an invariant over it is violated | `Asset.CustodyFrozen`; owning module frozen |
| `FROZEN` | `FROZEN` | a corrective admin operation (§4.5) | version + 1, `Asset.AdminCorrection` |
| `FROZEN` | `OPEN` | admin unfreezes, with reason, every invariant over it holding again | `Asset.AdminCorrection` |
| `FROZEN` | `CLOSED` | admin, empty | `Asset.AdminCorrection` |

A `CLOSED` custody never reopens; a new holding is a new custody.

### 3.2 Asset operation

Every leg is a **debit** (an asset leaves a holder) or a **credit** (an asset enters one). Debits are applied to memory
before the transaction, credits after it, so that nothing is spendable before it is committed and nothing is spent
twice while a transaction is open.

| From | To | When |
|---|---|---|
| — | `BEGUN` | `begin(opId)`; if `lc_asset_op` has `opId`, the call returns the earlier outcome and does nothing (its credit legs, if not yet applied in memory, are the applier's, §3.4) |
| `BEGUN` | `LOCKED` | the holder locks (L1) of every holder named are taken in holder order |
| `LOCKED` | `FENCED` | every touched Mobius item and castle is fenced and flushed (§3.3) |
| `FENCED` | `DEBITED` | every debit is applied to memory atomically and checked (an insufficient or no-longer-eligible asset aborts the operation), and recorded in the operation's in-memory undo list |
| `DEBITED` | `COMMITTING` | the statements (relative deltas, custody rows, postings, settlement key, owner state and version, facts, `lc_asset_op`) run on one connection, autocommit off; `COMMIT` is sent |
| `COMMITTING` | `COMMITTED` | the commit returned |
| `COMMITTING` | `ABORTED` | an error before `COMMIT` was sent: rollback, then the undo list is applied to memory |
| `COMMITTING` | `UNCERTAIN` | the connection failed after `COMMIT` was sent |
| `UNCERTAIN` | `COMMITTED` / `ABORTED` | a **locking** read of `opId` in `lc_asset_op` on a new connection returns (it waits while the original transaction is unresolved): present means committed, absent means aborted |
| `UNCERTAIN` | `UNCERTAIN` | the read times out or fails: retried; L1 and the fences stay; after `UncertainCommitLookupMillis` the module is frozen (§6); never aborted on a timeout |
| `COMMITTED` | `APPLIED` | every credit is applied to memory (and every ground leg placed, `placed_at` set), each leg marked applied in memory as it is applied; `applied_at` written |
| `APPLIED`, `ABORTED` | `DONE` | fences lifted and fenced items written (§3.3), L1 released, then clients and caches told |

While an operation is `UNCERTAIN`, gameplay goes on: the fenced items stay in memory and in use, only their row writes wait.
A crash in any state leaves the rows as the last committed transaction made them, and Mobius rebuilds memory from rows.

### 3.3 Fences

A **fence** marks a Mobius item, a castle treasury or an item's variables as owned by one asset operation. Core patches
(§11) make the three Mobius writers (`Item.updateDatabase`, Item.java:1481; the castle treasury write, Castle.java:361;
the variables save, ItemVariables.java:172) skip a fenced object for any thread but the fence's owner, leaving it dirty.

- To fence: set the fence, then take and release the object's write lock once (`_dbLock`, the treasury's write path, the
  variables save lock), so that a write already in progress has finished and every later one sees the fence; then flush
  the object (write its current memory) as the fence owner, so its row exists and is current.
- To lift: clear the fence and write the object once more, as Mobius would have.
- A fence is never held while waiting for another fence or for a Mobius lock: fences are taken in object-id order after L1,
  and taking one never blocks on anything but the momentary write lock.

### 3.4 The applier

One supervisor thread of the service. If the thread running an operation dies after `COMMITTED`, the applier finishes the
operation from the rows: it applies the credit legs not yet marked applied (the marks are in memory, set atomically with
each leg), places unplaced ground drops, writes `applied_at`, lifts the fences. If it dies before `COMMIT` was sent, the
applier rolls the undo list back into memory. Nothing in the applier needs to own another thread's lock: the operation's
only locks are its L1 permits and its fences, and both belong to the operation, not to a thread: L1 is a binary permit per
holder (a semaphore, released by whoever finishes the operation), and a fence is a record naming the operation.

## 4. Operations

### 4.1 Interface

```java
try (AssetTx tx = assets.begin(OpId.of(module, subject, action, attempt))) {
    tx.lock(Holder.player(a), Holder.custody(c));               // all holders, before any read
    tx.moveItem(objectId, qty, Holder.player(a), Holder.custody(c));
    tx.moveAdena(amount, Holder.player(a), Holder.custody(c));
    tx.sink(amount, Holder.player(a), Sink.FEE_NOTARIUS);
    tx.expectVersion(Table.PACT_CONTRACT, contractId, version);  // optimistic check on the owner's row
    tx.settlementKey("PACT", contractId + "/" + clause);        // unique; a second attempt aborts as already settled
    tx.fact(new EscrowFunded(...));                              // durable fact, same transaction
    tx.commit();
}
```

`OpId` is a deterministic 128-bit id of (module, subject, action, attempt): retrying the same logical operation produces
the same id, so a retry after an uncertain commit can never apply twice.

### 4.2 Rules

1. One connection, autocommit off, one transaction for: the relative deltas on `items` rows and their attribute,
   elemental and variable rows, on `castle.treasury`, custody rows, the owner module's state and version, ledger
   postings, the settlement key, ground drops, durable facts and the `lc_asset_op` row.
2. **Locks.** An operation takes the holder locks L1 of every holder it names, in holder order (§2.1) and then by key,
   before anything else, and holds no other lock across a step. L1 ranks above every Mobius lock: Mobius code takes L1
   only at the very start of login and logout, holding nothing. Everything else an operation does in memory goes through
   Mobius's own methods, which take Mobius's locks as they always do, or through the fences (§3.3), which never wait.
   AT-ASSET-05 checks with a lock-order witness that no path waits for L1 while holding a Mobius lock.
3. A debit of a whole object removes it from its container under the item's own monitor, after checking under that
   monitor that the object is still in that holder and still eligible; a debit from a stack uses the atomic checked
   decrease (§11.15). A credit to a stack goes to a row made current under its fence; a credit of a new object inserts
   its row in the transaction and adds it to memory after commit.
4. A stack is moved whole or split, never both in one leg; quantities are exact; a move never clamps (§4.3 `release`).
5. The owner module's holding predicate is checked for every custody touched (INV05).
6. Every operation's postings balance per item id (INV01), and per custody they match the custody's rows (INV11), before
   `COMMIT` is sent.
7. While an owner module is frozen, no operation on its custodies commits except corrective admin operations (§4.5).
8. An item entering a custody has its variables written from memory in the transaction under the variables' fence, with
   setting and change-tracking made one step by a core patch (§11.12); its variables object is then retired, so a late
   writer holding it is refused and logged, not lost silently. An item leaving a custody gets a variables object loaded
   from the committed rows.
9. An item entering a custody leaves memory entirely: removed from its container without releasing its object id
   (§11.6), its `Item` discarded once the operation is `DONE` (INV07).

### 4.3 Operations offered

| Operation | Effect | Facts |
|---|---|---|
| `open(kind, owner, beneficiary?)` | new `OPEN` custody | `Asset.CustodyOpened` |
| `fund(custody, holder, assets)` | holder → custody | `Asset.EscrowFunded` |
| `release(custody, holder, assets)` | custody → holder; what does not fit (slots, weight, `MAX_ADENA`) or is owed to a character not in the world → a `DELIVERY` custody | `Asset.EscrowReleased`, `Asset.DeliveryDeferred` |
| `charge(holder, amount, sinkCode)` | holder → sink | `Asset.FeeCharged` |
| `supply(manifest, town, assets)` | `TOWN_SUPPLY` source (origin) → manifest, within the town's budget | `Asset.SupplyEntered` |
| `arrive(manifest, town)` | manifest → `TOWN_SUPPLY` sink (destination) | `Asset.SupplyArrived` |
| `drop(custody, assets, place)` | custody → ground (a raid's dropped share) | `Asset.AssetDropped` |
| `lose(custody, assets, ROAD_LOSS)` | custody → sink | `Asset.AssetLost` |
| `close(custody)` | `OPEN` → `CLOSED`, empty only | `Asset.CustodyClosed` |
| `claim(delivery, player)` | in person: all of a delivery → the beneficiary, only if it all fits | `Asset.DeliveryClaimed` |
| `correct(...)`, `unfreeze(module)` | admin (§4.5) | `Asset.AdminCorrection` |
| `recover()` | startup, before any login (§7) | `Asset.RecoveryCompleted` |

### 4.4 Eligibility

An item may enter a custody only if, for the holder it leaves:

- Mobius's own availability check passes with Adena allowed and non-tradeable items refused:
  `Item.isAvailable(player, true, false)` (Item.java:898-911): not equipped, not a quest item, not the control item of the
  summoned pet, not an enchant item in use, not consumed by a skill being cast, tradeable;
- it is not time-limited or a shadow item (Item.java:1837, 1293);
- its item id is not one a classified blanket deletion targets (§2.7: event items, hero items);
- for a clan warehouse, the same checks without the player-bound ones.

### 4.5 Corrective admin operations

`correct` moves assets out of or into a custody, or between holders, to repair a violation. It requires the violation's
fact id and a reason, posts `ADMIN_CORRECTION` legs, is allowed on a frozen custody and a frozen module, and writes
`Asset.AdminCorrection` with before and after. `unfreeze(module)` returns a module to `RUNNING` only when every
invariant over its custodies holds.

## 5. Facts

All durable (04 §1.1), written in the operation's transaction, `op_id` set.

| Fact | Fields |
|---|---|
| `Asset.CustodyOpened` | custody, kind, owner module/ref, beneficiary, origin op |
| `Asset.EscrowFunded`, `Asset.EscrowReleased` | custody, holder, legs (item id, object id, lifecycle id, qty) |
| `Asset.DeliveryDeferred`, `Asset.DeliveryClaimed` | delivery custody, beneficiary, legs, reason (no room, not in the world) |
| `Asset.FeeCharged` | holder, sink code, amount |
| `Asset.SupplyEntered`, `Asset.SupplyArrived`, `Asset.AssetDropped`, `Asset.AssetLost` | custody, code, reference, legs |
| `Asset.CustodyClosed`, `Asset.CustodyFrozen` | custody, reason |
| `Asset.AdminCorrection` | admin, violation fact, reason, before, after |
| `Asset.InvariantViolated` | invariant id, evidence |
| `Asset.QuarantineStuck` | holder, operation, since |
| `Asset.MonitorRebased` | census id, per-item difference from the last persisted counters |
| `Asset.RecoveryCompleted` | operations found committed-unapplied, custodies checked, census kind |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| ASSET-INV01 | every operation's postings balance per item id: Σ DEBIT = Σ CREDIT | before `COMMIT` (in code) and by query over each new `op_id` |
| ASSET-INV02 | every object id is in exactly one holding: one `items` row, or one ground item, never both, never two | on every leg; full scan at every census |
| ASSET-INV03 | every `loc = 'ESCROW'` row has `owner_id = -1` and a custody in `OPEN` or `FROZEN` | per operation for the rows it touches; full scan at every census and at startup |
| ASSET-INV04 | a `CLOSED` custody holds no row; a custody's version only grows | per operation |
| ASSET-INV05 | every custody that holds a row is allowed to hold by its owner module (`allowsHolding(owner_ref)`) | per operation; full pass at every census |
| ASSET-INV06 | across a clean restart, the startup census equals the shutdown census and the persisted counters per item id (§2.6) | at startup |
| ASSET-INV07 | no in-memory `Item` exists for an `ESCROW` row outside an operation | per operation; at every census |
| ASSET-INV08 | at most one live lifecycle per object id; a live lifecycle's object is in exactly one holding | by the unique key; at every census |
| ASSET-INV09 | `LazyItemsUpdate = False` (GeneralConfig.java:80, General.ini:193) | at startup; the module refuses to start otherwise |
| ASSET-INV10 | an object id is never released while a row with that id exists | by the core patch (§11.6) on every destroy; at every census |
| ASSET-INV11 | per custody and item id: Σ CREDIT − Σ DEBIT of the custody's postings = Σ `count` of its `ESCROW` rows | per operation for the custodies it touches; full pass at every census and at startup, including after a crash |

A violation writes `Asset.InvariantViolated`, freezes the owning module through `lc_module_state` (the Asset Service
itself when the custody's owner cannot be told), freezes the custody, and keeps the evidence (04 §8, 02 §4). Repair is a
corrective admin operation (§4.5), never automatic.

## 7. Failure model

Every row has a fault test that kills or aborts at the named point and checks the end state (AT-ASSET-20…27). The
points are: before each statement, after each statement, `COMMIT` sent, after `COMMIT` returned, after each holder's
memory is applied, after `applied_at`, after each cache update, after each client packet.

| Fault | End state |
|---|---|
| before `COMMIT` is sent | rolled back; memory and rows as before; retry with the same `OpId` succeeds once |
| connection lost after `COMMIT` sent | `UNCERTAIN`: resolved by the locking read, never by a timeout (§3.2) |
| after `COMMIT`, before or while credits are applied, before `applied_at` | the applier applies the remaining credit legs and places unplaced drops (§3.4) |
| before `COMMIT` is sent, after debits were applied to memory | the undo list is applied to memory; nothing committed |
| after `APPLIED`, during cache updates or client packets | committed and applied; caches and clients brought up to date by the applier's re-publication; nothing re-moved |
| database restarted mid-transaction | rolled back by InnoDB; as the first row |
| GameServer killed in any custody state | at startup, before any login: committed operations are complete in rows; committed ground drops with no `placed_at` are placed; INV03, INV05 and INV11 are checked over every custody; the census is `STARTUP` after a clean shutdown and `REBASE` after a crash (§2.6) |
| full inventory, weight limit, `MAX_ADENA`, beneficiary not in the world | the rest goes to a `DELIVERY` custody, claimable in person |
| owner module frozen | its custodies stay as they are; only corrective operations commit; gameplay goes on (04 §8) |

## 8. Configuration

`config/Custom/LivingChronicle/Asset.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CommitBudgetMillis` | 1–1000 | 15 | 15 everywhere | AT-ASSET-30 |
| `UncertainCommitLookupMillis` | 100–60000 | 5000 | 5000 everywhere | AT-ASSET-21 |
| `MoveToleranceQty` | 0–10^9 | 0 | 0 everywhere | AT-ASSET-12 |
| `BucketWindowMinutes` | 1–1440 | 60 | 60 / 5 / 5 / 60 / 60 | AT-ASSET-12 |

`LazyItemsUpdate = False` is asserted, not configured (INV09).

## 9. Interface

- Codex → Contracts → *Deliveries*: lists the character's `DELIVERY` custodies with their contents and the operation that
  deferred each; *Claim* requires the character in person at a Notarius NPC, asks `ConfirmDlg`, carries a one-use token
  bound to character, custody and custody version (04 §5.2), and runs `claim`, which succeeds only if everything fits.
- Admin: `lc invariants asset`, `lc why <op>` (04 §6), `lc asset correct`, `lc asset unfreeze`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| ASSET-R01 | An asset operation MUST commit all its rows, postings, settlement key, owner state, facts and its `lc_asset_op` row in one transaction, or none of them. | AT-ASSET-01, AT-ASSET-20 |
| ASSET-R02 | Retrying an operation with the same `OpId` MUST have no effect once it committed. | AT-ASSET-02 |
| ASSET-R03 | A second settlement with the same (scope, key) MUST abort without moving anything. | AT-ASSET-00, AT-ASSET-03 |
| ASSET-R04 | An operation whose expected owner version differs MUST abort without moving anything. | AT-ASSET-04 |
| ASSET-R05 | An operation MUST take only its L1 permits and fences (§4.2); no path, asset or Mobius, may deadlock against it. | AT-ASSET-05 |
| ASSET-R06 | An operation MUST change Mobius holders' rows only by relative deltas on rows made current under a fence, so that no Mobius write, before or after, can lose or repeat its effect. | AT-ASSET-06 |
| ASSET-R07 | A non-stackable escrowed item MUST keep its object id, enchant, augmentation, elementals and variables (including unsaved changes) through fund and release; a moved part of a stack MUST keep its item id and get a child lifecycle. | AT-ASSET-07 |
| ASSET-R08 | No inventory, warehouse, pet, mail or freight restore MUST load an `ESCROW` row; startup cleanup and every classified SQL site MUST leave it. | AT-ASSET-00, AT-ASSET-08, AT-ASSET-26 |
| ASSET-R09 | An escrowed item's object id MUST stay reserved while its row exists, at runtime and across restarts. | AT-ASSET-00, AT-ASSET-09 |
| ASSET-R10 | A release that does not fit, or is owed to a character not in the world, MUST put the rest into a `DELIVERY` custody, never clamp, drop or write offline rows. | AT-ASSET-10 |
| ASSET-R11 | A claim MUST move all of a delivery or nothing, in person. | AT-ASSET-11 |
| ASSET-R12 | Every site that changes an item's existence or quantity, and every SQL statement deleting or updating `items`, MUST be classified; a clean restart MUST conserve every item id exactly; a move imbalance beyond tolerance for two windows MUST raise an alarm. | AT-ASSET-12 |
| ASSET-R13 | Clamps at `MAX_ADENA` and at 0, in stacks and in castle treasuries, MUST be classified sinks. | AT-ASSET-13 |
| ASSET-R14 | Living Chronicle MUST NOT create an asset except `TOWN_SUPPLY` within budget and audited `ADMIN_CORRECTION`. | AT-ASSET-14 |
| ASSET-R15 | A violated invariant MUST freeze the owning module and the custody, write evidence, and change nothing else; only corrective operations may then commit. | AT-ASSET-15 |
| ASSET-R16 | Nothing MUST be shown to a client, nor a lock released, before the operation is `APPLIED`, `ABORTED` or handed to the applier. | AT-ASSET-16 |
| ASSET-R17 | An ambiguous commit MUST be resolved by a locking `OpId` read, and MUST NOT be aborted on a timeout. | AT-ASSET-21 |
| ASSET-R18 | After a GameServer restart, no login MUST be accepted before recovery completes; INV11 MUST hold for every custody. | AT-ASSET-25 |
| ASSET-R19 | The module MUST refuse to start unless `LazyItemsUpdate = False`. | AT-ASSET-17 |
| ASSET-R20 | An item MUST enter a custody only if it is eligible (§4.4). | AT-ASSET-18 |
| ASSET-R21 | An operation MUST commit within `CommitBudgetMillis` at p99 under the capacity benchmark. | AT-ASSET-30 |
| ASSET-R22 | Debits MUST reach memory before the transaction and credits only after it commits; a dead operation MUST be finished by the applier from the rows (credits) or its undo list (debits), never twice. | AT-ASSET-22 |
| ASSET-R23 | Logout and login MUST take the player's holder lock first; an operation MUST NOT treat a player as a holder unless in the world under that lock. | AT-ASSET-24 |
| ASSET-R24 | Mobius MUST release an object id only after its row is deleted. | AT-ASSET-09 |
| ASSET-R25 | A committed ground drop MUST be placed exactly once, by the operation, the applier or startup recovery. | AT-ASSET-27 |

| Test | Kind | What it does |
|---|---|---|
| AT-ASSET-00 | integration | every migration applies in order on the stock schema; keys and checks reject what this spec forbids; an escrow row with a live custody passes upstream's own cleanup and used-id queries |
| AT-ASSET-01 | integration | fund, release, charge, supply, arrive, drop, lose: rows, postings, facts and op row appear together |
| AT-ASSET-02 | integration | same `OpId` twice, applied and unapplied: no second effect |
| AT-ASSET-03 | integration | two settlements with one key: the second aborts, nothing moved |
| AT-ASSET-04 | integration | stale owner version: aborts, nothing moved |
| AT-ASSET-05 | integration | random concurrent operations interleaved with `Player.store`, autosave, logout, login, `transferItem`, trade, private stores, mail, warehouses, castle tax, pet actions, enchanting, under a lock-order witness: nothing waits for L1 while holding a Mobius lock, no deadlock |
| AT-ASSET-06 | integration | operations racing every Mobius writer of the same items, then a restart: rows equal committed state |
| AT-ASSET-07 | integration | an enchanted, augmented, elemental item with variables changed but not yet saved, and with a variables write racing the fund, round-trips through escrow; a late writer on the retired variables is refused; a split stack gets a child lifecycle |
| AT-ASSET-08 | restart | escrow rows survive cleanup and every classified SQL site, and no container loads them at login |
| AT-ASSET-09 | integration, restart | the allocator never hands out an escrowed object id, at runtime and after restart; a failed row delete keeps the id reserved |
| AT-ASSET-10 | integration | release into a full, overweight, `MAX_ADENA` inventory, or to a character not in the world, defers exactly the rest |
| AT-ASSET-11 | integration | claim with room for part: nothing moves; with room for all, in person: everything moves |
| AT-ASSET-12 | unit, integration | the static site scan fails on an unclassified site and on a stale table row; a dupe injected into a retail transfer path raises the move alarm within two windows; a clean restart conserves every item id; a crash produces a rebase, never a pass |
| AT-ASSET-13 | unit | gains over `MAX_ADENA` and losses below 0, in stacks and castle treasuries, are counted as clamp sinks |
| AT-ASSET-14 | integration | every operation kind, randomised: no SOURCE leg except `TOWN_SUPPLY` within budget and audited corrections |
| AT-ASSET-15 | integration | a forged `ESCROW` row under a closed custody: module and custody frozen, evidence written, rows untouched; only a correction commits |
| AT-ASSET-16 | integration | client packets, cache updates and L1 releases observed only after `APPLIED` or `ABORTED`, or after the applier finished |
| AT-ASSET-17 | unit | startup with `LazyItemsUpdate = True` refused |
| AT-ASSET-18 | unit | each ineligible kind of item (§4.4) refused |
| AT-ASSET-20 | fault | kill at every point of §7 for every operation kind, recover, retry |
| AT-ASSET-21 | fault | connection cut after `COMMIT` sent, both outcomes; lookup timing out keeps L1 and the fences, freezes the module, and never aborts; play on the fenced items continues |
| AT-ASSET-22 | fault | the operation's thread dies at each step; the applier finishes or undoes it; no leg applied twice; gameplay on the touched items continues meanwhile |
| AT-ASSET-23 | fault | database restarted mid-transaction |
| AT-ASSET-24 | integration | operations on inventory or warehouse racing logout and login of their character: none reads a stale copy; payouts during logout are deferred |
| AT-ASSET-25 | restart | GameServer killed in every custody state; recovery before login; INV11 exact |
| AT-ASSET-26 | restart | `DatabaseCleanUp = True` with escrow rows of every kind |
| AT-ASSET-27 | fault, restart | a drop dies after commit, before placing, with and without a restart: placed once |
| AT-ASSET-30 | sim | capacity benchmark: commit p99 within budget |

## 11. Upstream constraints (for N3 design and N4 implementation)

Facts about Mobius the design must answer; each is the reason for a rule above or a core patch.

1. **No transactions exist.** No core code changes autocommit or commits (only comments mention it,
   OfflineTraderTable.java:170); HikariCP keeps autocommit on (DatabaseFactory.java:70-99). The Asset Service brings the
   first multi-statement transactions, on its own connections, never through Mobius's write helpers.
2. **Item writes are lazy and lossy.** `Item.updateDatabase` opens a connection per call and only logs SQL errors
   (Item.java:1680, 1725, 1769); count changes reach the database at `Player.store` (Player.java:7647-7649). → §2.4, §4.2.
3. **Mobius deletes some rows on write**: location `VOID`/`REFUND`, owner 0, count 0 (Item.java:1489, 1500). An escrow
   row is never written through `updateDatabase`.
4. **Unknown locations are skipped with a SEVERE log** (`ItemLocation.valueOf`, Item.java:1538, 1546-1549): `ESCROW`
   is added to `ItemLocation` (core patch).
5. **Cleanup** keeps owner -1 (DatabaseIdManager.java:67) and counts every `items.object_id` as used (122-129); a core
   patch makes it name `ESCROW` explicitly (report a row whose custody is not live; never delete it), so a later upstream
   change to rule 67 cannot silently destroy escrow.
6. **An object id is released before its row is deleted** (`ItemManager.destroyItem`, ItemManager.java:177, before
   ItemContainer.java:449-450 deletes it, with errors only logged, Item.java:1769). Core patch: release after a
   successful delete (ASSET-R24).
7. **Quantities are clamped silently** at `MAX_ADENA` and 0 (Item.java:458-469) and in the castle treasury
   (Castle.java:333-357). → clamp sinks (ASSET-R13).
8. **Retail transfers are not atomic.** `TradeList.doExchange` moves both sides with no rollback (TradeList.java:606-607);
   private stores lock only the seller (637, 859); containers have no lock (ItemContainer.java:50). They stay retail
   (02 §1); the move balance makes their failures visible.
9. **Offline Adena is written by inserting a new row** (cash on delivery to an offline sender,
   RequestPostAttachment.java:253-257); the Asset Service never writes offline rows (§2.1).
10. **The castle treasury is changed without a lock** (`addToTreasuryNoTax` reads, changes and writes `_treasury`
    unsynchronised, Castle.java:333-367) and its SQL failures leave memory changed. Core patch: treasury changes are
    atomic (a checked compare-and-set on `_treasury`), and the write honours the fence (§3.3).
11. **Blanket deletions by item id** reach every owner, -1 included (LongTimeEvent.java:557; Hero.java:81 at 874). → §4.4
    eligibility and §2.7 classification.
12. **Item variables are saved later, on their own lock and connection** (ItemVariables.java:135, 172), and `set`
    tracks the change and sets the value as two steps (AbstractVariables.java:117-121), so a capture between them reads the
    old value. Core patch: tracking and setting are one step under the save lock; the save honours the fence; a retired
    variables object refuses writes. → §4.2 rule 8.
13. **Logout marks the player offline before the final save** (Player.java:11793, ItemContainer.java:550). → §2.4 handoff.
14. **Quantity is created outside `ItemManager.createItem`**: lottery, race and mercenary tickets are constructed
    directly (Loto.java:242; RaceManager.java:171 in `onBypassFeedback`; MercTicketManager.java:335 in `addTicket`, while
    :195 in `load` rebuilds saved tickets). → §2.7.
15. **Counts change without a lock** (`Item.changeCount`, Item.java:451, read-modify-write of `_count`; called without the
    item's monitor by `ItemContainer.addItem`, :268, and for the destination of `transferItem`, :376), so two concurrent
    changes can lose one, in Mobius today. Core patch: `changeCount` and `setCount` update `_count` atomically
    (compare-and-set, no monitor, so no new lock order), and a checked `tryDecrease(n)` serves debits (§4.2 rule 3).
16. **Mobius's own locks do not form an order an outsider can join**: `transferItem` holds the source item's monitor
    while the inventory sends the skill list under the player's monitor (Inventory.java:881, 907; Player.java:10490),
    enchanting likewise (RequestEnchantItem.java:176, 266); and those locks are thread-owned, so no supervisor could take
    them over. → the operation takes only L1 and fences (§3.3, §4.2 rule 2).
17. **Fences**: `Item.updateDatabase`, the castle treasury write and the variables save skip a fenced object for any
    thread but the fence's owner (core patch, §3.3).
18. **Login and logout take the character's L1 first** (core patch at `Disconnection.storeAndDelete`, Disconnection.java:116,
    and before the restores at Player.java:7358). → §2.4.

## 12. Review record

- Round 2 (Codex), NO SIGN-OFF: 9 of 20 fixed; new findings showed that joining Mobius's own locks cannot work (inverted
  orders, unlocked count changes, thread-owned locks) and that quarantine could itself lose unsaved memory. Revision 3
  replaces both with fences and relative deltas (§2.4, §3.2-§3.4, §4.2), debits before and credits after commit, an
  applier that needs no thread's lock, one L1 per character for inventory and warehouse, logout locked from its first save,
  durable ground drops (`lc_ground_drop`), the two upstream races as core patches (§11.12, §11.15), an honest scope for
  the live move check with the shutdown boundary made exact (§2.6), the bid transitions as sites, the site table's wrong
  members fixed, and the two DDL checks that SQL's NULL let through.
- Round 1 (Codex, different model family), NO SIGN-OFF, 20 findings. Revision 2 answers them: authority of castle
  treasuries and clan warehouses (§2.1, §11.10); blanket deletions (§4.4, §2.7, §11.11); the lock hierarchy including
  Mobius's own locks (§4.2); the logout handoff (§2.4); site-classified conservation in place of process-classified
  (§2.6, §2.7); unhooked creation paths (§2.7, §11.14); census only at quiescence, crash as an explicit rebase, exact
  custody conservation (§2.6, INV11); ambiguous commits by a locking read (§3.2); the applier (§3.4);
  core id-reuse ordering (ASSET-R24); lifecycle ends, splits and merges (§2.2); variables (§4.2 rule 8); pet control
  items and the other availability rules (§4.4); caravan arrive and drop operations (§4.3); corrections on frozen
  modules (§4.5); the delivery's origin operation (§2.2); the missing acceptance cases (§10).
