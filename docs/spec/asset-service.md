# SPEC-ASSET — Asset Service

- Status: reviewed (revision 7; signed off by Codex, a different model family, in round 7; record in §12)
- Blueprint: 04 §3 (Asset Service), §8 (failure degradation), §9 (persistence); 02 §4 (production invariants), §6 (budgets);
  15 §3–§4 (PACT lifecycle and escrow rules); 16 §7 (zero minting); 12 §6.2 (treasury commitments), §8 (caravans);
  consensus §4.2 a–b, §4.3, §10.2–§10.3.
- DDL: `sql/migrations/V010__lc_asset.sql`. Classified sites: `docs/spec/asset-economy-sites.csv` (§2.7).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31, Mobius
  f1e2649e33cc); paths are under `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Asset Service is the one path by which Living Chronicle code moves or holds an item or Adena (02 §3). It owns:

- **custody**: every holding that is not a Mobius container (contract escrow, institutional pools, caravan manifests);
- **asset operations**: all-or-nothing transfers, each one InnoDB transaction with an operation id;
- **the ledger**: double-entry postings for every asset operation;
- **the economy monitor**: conservation of every item id and of Adena across the whole server, Mobius flows included
  (04 §3.6), against a reviewed classification of every place quantity changes (§2.7).

It does not own: contract terms and states (PACT, Black Ledger, Crown Law own theirs and answer a holding predicate,
INV05), the Fact Journal tables (it writes durable facts into them inside its transaction), town supply budgets (Aden
Remembers), Mobius's own transfers (trade, private store, mail, warehouse, NPC shops, auctions: they stay retail and are
*observed* by the monitor), and anything in combat or drop rates (02 §1).

Depends on: Fact Journal (outbox facts), Actor Identity (actor snapshots), the core patches of §11.

## 2. Data

### 2.1 Holders and directions

The design rests on one asymmetry. Taking an asset *out of* a live Mobius container is done in memory, under the item's
own locks, and written in the operation's transaction. Putting an asset *into* a live Mobius container is never done:
Mobius merges stacks, destroys emptied ones and saves them lazily, and an outside writer cannot make a credit to such a
container atomic with its own transaction (§12, rounds 2 and 3). Everything owed to a character is delivered as **system
mail**, written as rows in the operation's transaction and claimed by the character through retail mail.

| Holder | Key | Debit (asset leaves) | Credit (asset enters) |
|---|---|---|---|
| `PLAYER` | char id | from the inventory of a character in the world, in memory (§3.3) | never: system mail to the character |
| `PLAYER_WAREHOUSE` | char id | from the warehouse container of a character in the world, in memory | never: system mail |
| `CLAN_WAREHOUSE` | clan id | from the clan's warehouse container (always in memory, Clan.java:145, 177), in memory | never: system mail to the clan leader, named in the operation |
| `CASTLE_TREASURY` | castle id | `Castle._treasury` (always in memory, Castle.java:981), atomic checked decrease | atomic increase after commit (§3.3) |
| `CUSTODY` | custody id | rows `owner_id = -1`, `loc = 'ESCROW'`, `loc_data = custody id`, in the transaction | rows, in the transaction |
| `MAIL` | message id | — | rows: a new `messages` row from the system (sender -1) to the character, with the assets as its attachments (`items` rows `owner_id = -1`, `loc = 'MAIL'`, `loc_data = message id`, as Mobius stores system mail, DatabaseIdManager.java:68; Mail.java:122-126) |
| `SOURCE(code, ref)` / `SINK(code, ref)` | posting code (§2.5) | the asset enters existence | the asset leaves existence |

- Equipped items, pet, freight, refund and auction-bid holdings are never debited by an asset operation; the monitor counts
  them (§2.6).
- `owner_id = -1` is the owner Mobius's startup cleanup keeps (DatabaseIdManager.java:67) and that no restore, repair
  or delete-by-owner query selects (AdminRepairChar.java:101, GameClient.java:451 select a character's id). Blanket
  deletions by item id do reach it (LongTimeEvent.java:557, Hero.java:81): §4.4 keeps those item ids out of custody, and
  §2.7 classifies every such statement. `items.loc_data` is an `INT` (`dist/db_installer/sql/game/items.sql`), so
  custody ids are `INT`s.
- **Service mail** is a `messages` row with `senderId = -1`, `sendBySystem` nonzero (so it cannot be rejected back to a
  sender, RequestRejectPostAttachment.java:96), `reqAdena = 0` (never cash on delivery, MailMessage.java:225-227),
  `hasAttachments = 'true'` and an `expiration` beyond any server lifetime (service mail never expires, so
  MessageDeletionTaskManager.java:80-110 never touches it). Its life: *sent* (in the operation's transaction) →
  *claimed* (by retail mail, which checks room; RequestPostAttachment.java:191-232). The receiver cannot delete it while
  it has attachments (RequestDeleteReceivedPost.java:83), and a character with unclaimed service mail cannot be deleted
  (core patch §11.21). Writing service mail, and writing a pending mark (§2.4), lock the character's `characters` row
(`SELECT … FOR UPDATE`) and require it to exist and not be marked for deletion. Every deletion of a character first runs
the **deletion guard**: one transaction that locks the same row, refuses while the character has unclaimed service mail
or a pending mark, and otherwise marks it (`deletetime`); only then may Mobius delete it, at once or later. So a payout,
a debit with the refund it may need, and a deletion are serialised: a character being deleted is never debited, and is
never owed a refund that could not be mailed. A release whose receiver is gone or marked aborts
with the reason `RECEIVER_GONE`; the custody stays as it was and its owner module decides. Claiming records `claimed_at`
in `lc_mail_delivery` (core patch §11.23), right after retail clears the message's attachments. It counts toward the
receiver's inbox like any mail; it is not refused by the 240-message check,
  which belongs to sending (RequestSendPost.java:248-251), so a full inbox delays the receiver's own incoming player mail
  until they claim it: that is the policy, and the mailbox says why.

### 2.2 Tables

```text
lc_custody(custody_id INT PK auto, kind {CONTRACT|POOL|MANIFEST}, owner_module VARCHAR(16), owner_ref BIGINT,
           state {OPEN|FROZEN|CLOSED}, version INT, created_op BINARY(16), closed_op BINARY(16) NULL,
           created_at TIMESTAMP(3), closed_at TIMESTAMP(3) NULL)
lc_asset_op(op_id BINARY(16) PK, module VARCHAR(16), action VARCHAR(32), subject_ref BIGINT, committed_at TIMESTAMP(3))
lc_ledger_posting(id BIGINT PK auto, op_id BINARY(16) → lc_asset_op, leg SMALLINT, account_kind {CASTLE_TREASURY|
                  CLAN_WAREHOUSE|PLAYER|PLAYER_WAREHOUSE|CUSTODY|MAIL|SINK|SOURCE}, account_id BIGINT, code VARCHAR(24) NULL,
                  item_id INT, object_id INT NULL, lifecycle_id BIGINT NULL, qty BIGINT > 0, direction {DEBIT|CREDIT},
                  fact_id BIGINT NULL, UNIQUE(op_id, leg))
lc_mail_delivery(message_id INT PK, op_id BINARY(16) → lc_asset_op, receiver_char INT, reason {PAYOUT|REFUND|RETURN|CORRECTION},
                 claimed_at TIMESTAMP(3) NULL)
lc_asset_pending(char_id INT, op_id BINARY(16), since TIMESTAMP(3), PRIMARY KEY(char_id, op_id))
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
```

- `lc_custody.owner_module/owner_ref` name what the custody is for: (`PACT`, contract id), (`LEDGER`, contract id),
  (`CROWN`, law id), (`CARAVAN`, caravan id).
- An `lc_asset_op` row exists exactly when its operation committed: it is written inside the operation's transaction.
- `lc_mail_delivery` names every service mail an operation wrote, so a delivery is traceable from the operation and the
  mail; the mail itself is Mobius's (`messages` and its attachment rows). There is no foreign key to `messages`: retail
  deletes a claimed message, and INV12 checks the link instead.
- `lc_asset_pending` marks a character whose items an operation has taken in memory but not yet committed or refunded
  (§3.3). It is written on its own, committed, before the first debit, and deleted by the operation's transaction or by
  its refund's. Login waits while a mark exists (§2.4).
- A refund is an operation of its own (`action = 'REFUND'`), with the deterministic id `OpId.refundOf(opId)`, so its
  `lc_asset_op` row, its deliveries and the resolution of its own uncertain commit work as for any operation.
- `lc_settlement_key` is the single settlement-uniqueness mechanism for every module; PACT's `pact_settlement` and Black
  Ledger's payouts reference it.
- `lc_item_lifecycle` gives an item a provenance id from its first asset operation on, because Mobius reuses object ids
  after destruction (IdManager.java:158-177). It ends when the object leaves existence or merges into another stack
  (both are classified sites, §2.7); a split of a tracked stack starts a child lifecycle (`parent_lifecycle_id`).
- `lc_economy_*` hold the monitor's state (§2.6): buckets are telemetry, never per-event rows (04 §1.1).
- `lc_module_state` is the freeze switch every Living Chronicle module honours (04 §8); the Asset Service is its first user.

### 2.3 The rows of an escrowed or mailed asset

`owner_id = -1`; `loc = 'ESCROW'` with `loc_data = custody_id`, or `loc = 'MAIL'` with `loc_data = message id`; every
other column as it was in memory when the operation took it. Its `item_attributes`, `item_elementals` and
`item_variables` rows stay keyed by its object id and are written from memory in the same transaction (§3.3). Adena in a
custody is one stackable row per custody (item id 57, Inventory.java:75).

### 2.4 Authority

- Memory is authoritative for every Mobius container and for castle treasuries, and the database is a lagging copy;
  rows are authoritative for custodies and for service mail until it is claimed.
- An operation writes a Mobius item's row only while holding that item's write lock (`_dbLock`, Item.java:167), and
  writes exactly what Mobius would write for its current memory, the debit already applied, including deleting the row of
  a stack Mobius has emptied meanwhile (§3.3). Mobius's own writers take the same lock and write memory too, so no write,
  before or after, can carry a state from before the debit.
- **Second representations.** A whole object taken from a container is retired, but its row still says where it was
  until the operation commits; a login in between would restore a second, live `Item` from that row
  (Player.java:7358; ItemContainer.java:594). Hence the pending mark: committed before the first debit, it makes the
  character's login wait (a database check at the start of the restore, core patch §11.22, taking no lock) until the
  operation's transaction or its refund's has replaced the row. A logout needs no exclusion: it saves only what is in
  its containers, and a stack it saves waits on the stack's write lock, which the operation holds until it is done
  (§3.3).

### 2.5 Posting codes

`SOURCE` and `SINK` postings carry a code from this table and, where it names one, a reference; adding one is a spec
change.

| Code | Kind | Reference | Meaning |
|---|---|---|---|
| `FEE_NOTARIUS`, `FEE_BROKER`, `FEE_QUESTIONING`, `FEE_ALIAS`, `FEE_GAZETTE` | SINK | — | institutional fees (15 §6, 16 §7, 17 §2) |
| `TOWN_SUPPLY` | SOURCE | origin town | goods leaving a town's supply budget into a caravan manifest (12 §8) |
| `TOWN_SUPPLY` | SINK | destination town | a manifest's goods arriving into a town's supply budget |
| `RAID_SHARE` | SINK | caravan | the share of a raided manifest that the raid drops: it leaves the custody here and enters the world again through the classified site `lc_raid_drop` (§2.7) when it is dropped |
| `ROAD_LOSS` | SINK | caravan | the rest of a raided manifest |
| `ADMIN_CORRECTION` | SOURCE / SINK | audit fact | a corrective admin operation (§4.5) |

Living Chronicle mints nothing for players: a `TOWN_SUPPLY` source is matched by the supply budget it draws down (owned
by Aden Remembers), and every other source is an audited correction.

### 2.6 The economy monitor

Conservation across the whole server, with authorisation coming from the site table (§2.7), not from a process name. What
it guarantees, and what it only watches, are stated separately.

**Guaranteed (exact):**

1. Every operation's postings balance (INV01) and every custody holds exactly what its postings say (INV11), always,
   including across crashes.
2. Across a clean restart, every item id is either conserved exactly or its difference is reported exactly: the census
   taken at the shutdown boundary, the census taken at the next startup and the existence counters carried between them
   are compared per item id (INV06). What is guaranteed is the detection, not that Mobius loses nothing: Mobius
   disconnects players before stopping its task pools (Shutdown.java:380, 403), so a task can change a container after
   its final save, and that change is lost and reported.

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

**At startup**, before any login, the database is counted again (`STARTUP`) and compared with the persisted counters. A
difference is a conservation failure somewhere in Mobius or here (for instance a background task changing a player's
container after its final save, which Mobius's shutdown order allows, Shutdown.java:380-403; the census reports such a
loss, it does not hide it): `Asset.ConservationBroken` with the per-item
difference and an admin alarm; it freezes nothing by itself (Mobius's own losses are not a Living Chronicle module's), and
a custody involved is caught by INV11, which does freeze.
After a crash there is no boundary: the census is a `REBASE`, recorded with its per-item difference in
`Asset.MonitorRebased`, and never reported as conservation holding; INV11 still holds exactly.

**Holdings counted**: inventory and paperdoll, warehouse, clan warehouse, pet and pet equipment, mail attachments,
freight, custody rows, castle treasuries, ground (`itemsonground`, when saved), and the open bid commitments: item-auction
bids whose `playerBid > 0`, except the winning bid of a finished auction (a losing bid stays refundable after the
auction finishes, ItemAuction.java:428-477; the winning one was sunk, ItemAuctionBid.java:51-58), and
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
reconstruction from rows is `RESTORE`. A site that computes several quantities (a returned part, a penalty, a clamp) counts
each under its own class where it computes it. A row whose class is not yet decided is `PENDING`, and AT-ASSET-12 fails on
any `PENDING` row: the table is complete only when the scan and the review agree. The table in this commit is the seed
(the sites the reviews named); completing it is the exit criterion of AT-ASSET-12 in N4, not a claim of this spec.

## 3. State machines

### 3.1 Custody

| From | To | Guard | Effect |
|---|---|---|---|
| — | `OPEN` | an asset operation opens it | `Asset.CustodyOpened` |
| `OPEN` | `OPEN` | an operation funds, releases or loses part of it | version + 1 |
| `OPEN` | `CLOSED` | it holds no `ESCROW` row | `Asset.CustodyClosed`; terminal |
| `OPEN` | `FROZEN` | an invariant over it is violated | `Asset.CustodyFrozen`; owning module frozen |
| `FROZEN` | `FROZEN` | a corrective admin operation (§4.5) | version + 1, `Asset.AdminCorrection` |
| `FROZEN` | `OPEN` | admin unfreezes, with reason, every invariant over it holding again | `Asset.AdminCorrection` |
| `FROZEN` | `CLOSED` | admin, empty | `Asset.AdminCorrection` |

A `CLOSED` custody never reopens; a new holding is a new custody. Custody rows are changed only inside operations, under
`SELECT … FOR UPDATE` of the custody row, so two operations on one custody serialise in the database.

### 3.2 Asset operation

| From | To | When |
|---|---|---|
| — | `BEGUN` | `begin(opId)`; if `lc_asset_op` has `opId` or its refund `OpId.refundOf(opId)`, the call returns that earlier outcome (committed, or aborted and refunded) and does nothing: an attempt that was refunded is final, and a retry is a new attempt with a new id |
| `BEGUN` | `MARKED` | if anything will be taken from a character, the pending mark is committed (§2.4) |
| `MARKED` | `TAKEN` | every debit from a Mobius holder is taken in memory as §3.3 orders it, the stack write locks held from then on; a debit that is no longer possible (gone, moved, insufficient, ineligible) ends the operation as `ABORTED` |
| `TAKEN` | `COMMITTING` | the statements (the touched Mobius rows written from memory, custody and mail rows, postings, settlement key, owner state and version, facts, deleting the pending mark, `lc_asset_op`) run on one connection, autocommit off; `COMMIT` is sent |
| `COMMITTING` | `COMMITTED` | the commit returned |
| `COMMITTING` | `ABORTED` | an error before `COMMIT` was sent: rollback |
| `COMMITTING` | `UNCERTAIN` | the connection failed after `COMMIT` was sent |
| `UNCERTAIN` | `COMMITTED` / `ABORTED` | a **locking** read of `opId` in `lc_asset_op` on a new connection returns (it waits while the original transaction is unresolved): present means committed, absent means aborted |
| `UNCERTAIN` | `UNCERTAIN` | the read times out or fails: retried, the locks still held; after `UncertainCommitLookupMillis` the module is frozen; never aborted on a timeout |
| `ABORTED` | `REFUNDED` | the refund operation `OpId.refundOf(opId)` commits, under the locks still held: each whole object taken has its row (still where it was) moved to service mail to the holder it came from; each stack part taken is written as a new row in service mail, and the stack's row is written from memory; a treasury debit is written back as the row's memory value plus the amount, memory itself untouched until the refund has committed; the pending mark is deleted. An uncertain refund commit is resolved like any other; a refund is retried until it commits, and nothing it does to memory happens before its commit |
| `COMMITTED`, `REFUNDED` | `DONE` | castle treasury credits (and a refunded treasury debit) applied to memory, once, under the treasury write lock; stack write locks released; retired items discarded; `MailManager` adopts each new message (idempotent by message id, retried until it holds); clients and caches told |

Everything happens on the thread that began the operation, inside one `try`/`finally`; resolution and refund run before
any lock is released. Only the death of the JVM escapes the `finally`; then memory is gone and the rows are the truth:
either the operation (or its refund) committed, or nothing taken was ever written away from its holder, because the stack
write locks kept every Mobius write of a debited state out, and retired whole objects are written by nothing. The pending
mark of an operation that never committed is removed at startup recovery, before any login. Mail adoption that failed is
also repaired at every census by comparing unclaimed deliveries with `MailManager`.

### 3.3 Taking an asset from a Mobius holder

Two phases, so that the service never waits for a Mobius lock while holding one Mobius waits for:

1. **Whole objects first** (a non-stackable item, or a whole stack), one at a time, holding nothing else: take the item's
   monitor (`synchronized (item)`, as `transferItem`, `destroyItem` and `dropItem` take it, ItemContainer.java:340, 427;
   Inventory.java:802, 838), then the container's own monitor (`setPaperdollItem` is synchronized on the inventory,
   Inventory.java:1072, and Mobius reaches it holding the item's monitor when destroying an equipped item, so item then
   container is Mobius's order); mark the item retired first, then check it is still in that holder's live container, not
   equipped, and eligible (§4.4), undoing the mark and aborting if not; a scheduled equip (UseItem.java:350,
   Player.java:2257-2267) that reaches `setPaperdollItem` afterwards refuses the retired item (§11.20); remove it from the
   container (this may send the owner's client a new skill list under the player's monitor, Inventory.java:902-907, as
   Mobius itself does, and nothing else of the service's is held); mark it **retired** (core patch §11.20: a retired item
   is never written by Mobius and every Mobius operation on it refuses); under its variables lock capture its variables and
   retire them (setters and removals take the same lock, §11.12); release the monitor. Then take and release the item's
   `_dbLock` once, holding nothing else, so that a Mobius write of it already in progress has finished; every later one is
   a no-op. The transaction writes its row (update, or insert if it never existed) as `ESCROW` or mail, with its metadata
   rows, from the captured state.
2. **Then parts of stacks**: take the monitors of all the stacks in object-id order, then their `_dbLock`s in object-id
   order, and hold all of them until `DONE`; never call a container method while holding them. Under its monitor a stack's
   decrease cannot fall between a retail transfer's reading of the count and its acting on it (`transferItem` decides and
   acts under the source's monitor, ItemContainer.java:340-385). The atomic checked decrease (§11.15) takes the quantity,
   leaving at least one unit. The transaction writes the stack's row from memory as Mobius would at that moment: its count, or its
   deletion if Mobius has emptied and destroyed the stack meanwhile (a retail transfer can, ItemContainer.java:365-385;
   Mobius's own deletion waits on the held write lock and then finds nothing to delete). The part taken becomes a new row
   under a new object id. The service never marks a stack as saved: the decrease left it dirty, so Mobius writes it again
   from memory later; if the transaction inserted a row that did not exist, the stack is marked as existing, under its write
   lock, after commit.
3. **Castle treasury**: under the treasury write lock (§11.10), the atomic checked decrease; the transaction writes
   `treasury` from memory. A credit is written in the transaction as memory plus the amount, and added to memory after
   commit, still under the write lock.

Every write checks its affected-row count against what it meant to touch; a mismatch aborts the operation (and refunds).

## 4. Operations

### 4.1 Interface

```java
try (AssetTx tx = assets.begin(OpId.of(module, subject, action, attempt))) {
    tx.take(Holder.player(a), objectId);                        // whole object, from the live container
    tx.takeAdena(Holder.player(a), amount);                     // part of the Adena stack
    tx.toCustody(c);                                            // what was taken goes into custody c
    tx.sink(amount, Sink.FEE_NOTARIUS);
    tx.expectVersion(Table.PACT_CONTRACT, contractId, version);  // optimistic check on the owner's row
    tx.settlementKey("PACT", contractId + "/" + clause);        // unique; a second attempt aborts as already settled
    tx.mail(character, custody, objectIds, Reason.PAYOUT);      // from a custody to a character, as system mail
    tx.fact(new EscrowFunded(...));                              // durable fact, same transaction
    tx.commit();
}
```

`OpId` is a deterministic 128-bit id of (module, subject, action, attempt): retrying the same logical operation produces
the same id, so a retry after an uncertain commit can never apply twice.

### 4.2 Rules

1. One connection, autocommit off, one transaction for: the touched Mobius rows and their attribute, elemental and
   variable rows, `castle.treasury`, custody rows, mail rows, the owner module's state and version, ledger postings, the
   settlement key, durable facts and the `lc_asset_op` row.
2. Locks: as §3.3 orders them, and database row locks inside the transaction. A whole object's monitors are held alone
   and briefly; stack monitors, stack write locks and the treasury write lock are held from the debit to `DONE`, all taken
   before the first stack debit, and while holding them the service waits for nothing Mobius holds (AT-ASSET-05 checks it with a lock-order witness, against `Player.store`, which
   holds the player's monitor and then takes item write locks, Player.java:7621-7647, and against retail transfers, which
   hold the source's monitor and then write the destination, ItemContainer.java:340-388).
3. Assets are never credited to a live Mobius container (§2.1); payouts, releases, refunds and corrections to characters
   are system mail.
4. Quantities are exact and never clamp: a treasury credit that would pass `MAX_ADENA` aborts the operation.
5. The owner module's holding predicate is checked for every custody touched (INV05).
6. Every operation's postings balance per item id (INV01), and per custody they match the custody's rows (INV11), before
   `COMMIT` is sent.
7. While an owner module is frozen, no operation on its custodies commits except corrective admin operations (§4.5).
8. A retired item leaves memory for good: after `DONE` it is discarded, its object id stays reserved by its row (§11.6).
9. A debit is visible to its owner at once; nothing credited is visible to anyone before `COMMITTED`.

### 4.3 Operations offered

| Operation | Effect | Facts |
|---|---|---|
| `open(kind, owner)` | new `OPEN` custody | `Asset.CustodyOpened` |
| `fund(custody, holder, assets)` | holder → custody | `Asset.EscrowFunded` |
| `release(custody, character, assets)` | custody → system mail to the character | `Asset.EscrowReleased` |
| `charge(holder, amount, sinkCode)` | holder → sink | `Asset.FeeCharged` |
| `commit(treasury, custody, amount)` / `return(custody, treasury, amount)` | castle treasury ↔ custody (Crown Law, 12 §6.2) | `Asset.TreasuryCommitted`, `Asset.TreasuryReturned` |
| `supply(manifest, town, assets)` | `TOWN_SUPPLY` source (origin) → manifest, within the town's budget | `Asset.SupplyEntered` |
| `arrive(manifest, town)` | manifest → `TOWN_SUPPLY` sink (destination) | `Asset.SupplyArrived` |
| `raid(manifest, share, rest)` | manifest → `RAID_SHARE` sink (dropped after commit, at most once, through `lc_raid_drop`) and → `ROAD_LOSS` sink | `Asset.ManifestRaided` |
| `close(custody)` | `OPEN` → `CLOSED`, empty only | `Asset.CustodyClosed` |
| `correct(...)`, `unfreeze(module)` | admin (§4.5) | `Asset.AdminCorrection` |
| `recover()` | startup, before any login (§7) | `Asset.RecoveryCompleted` |

A raided share is dropped after commit; if the JVM dies first, it is not dropped: the sink stands, and the share is lost
to the road, as the rest is. The drop is at most once by construction, never repeated by recovery.

### 4.4 Eligibility

An item may enter a custody only if, for the holder it leaves:

- Mobius's own availability check passes with Adena allowed and non-tradeable items refused:
  `Item.isAvailable(player, true, false)` (Item.java:898-911): not equipped, not a quest item, not the control item of the
  summoned pet, not an enchant item in use, not consumed by a skill being cast, tradeable;
- it is not time-limited or a shadow item (Item.java:1837, 1293);
- its item id is not one a classified blanket deletion targets (§2.7: event items, hero items);
- for a clan warehouse, the same checks without the player-bound ones;
- a partial debit leaves at least one unit in the stack: taking a whole stack is a whole-object debit (§3.3).

### 4.5 Corrective admin operations

`correct` moves assets out of or into a custody, or to a character by system mail, to repair a violation. It requires the violation's
fact id and a reason, posts `ADMIN_CORRECTION` legs, is allowed on a frozen custody and a frozen module, and writes
`Asset.AdminCorrection` with before and after. `unfreeze(module)` returns a module to `RUNNING` only when every
invariant over its custodies holds.

## 5. Facts

All durable (04 §1.1), written in the operation's transaction, `op_id` set.

| Fact | Fields |
|---|---|
| `Asset.CustodyOpened` | custody, kind, owner module/ref |
| `Asset.EscrowFunded`, `Asset.EscrowReleased` | custody, holder or receiver and mail, legs (item id, object id, lifecycle id, qty) |
| `Asset.FeeCharged` | holder, sink code, amount |
| `Asset.TreasuryCommitted`, `Asset.TreasuryReturned` | castle, custody, amount |
| `Asset.SupplyEntered`, `Asset.SupplyArrived`, `Asset.ManifestRaided` | custody, code, reference, legs |
| `Asset.Refunded` | aborted op, receiver and mail, legs (written by the refund transaction) |
| `Asset.CustodyClosed`, `Asset.CustodyFrozen` | custody, reason |
| `Asset.AdminCorrection` | admin, violation fact, reason, before, after |
| `Asset.InvariantViolated` | invariant id, evidence |
| `Asset.ConservationBroken`, `Asset.MonitorRebased` | census id, per-item difference |
| `Asset.MoveImbalance` | window, item id, site, quantity |
| `Asset.RecoveryCompleted` | custodies checked, census kind |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| ASSET-INV01 | every operation's postings balance per item id: Σ DEBIT = Σ CREDIT | before `COMMIT` (in code) and by query over each new `op_id` |
| ASSET-INV02 | every object id is in exactly one holding: one `items` row or one ground item, never both, never two | on every leg; full scan at every census |
| ASSET-INV03 | every `loc = 'ESCROW'` row has `owner_id = -1` and a custody in `OPEN` or `FROZEN` | per operation for the rows it touches; full scan at every census and at startup |
| ASSET-INV04 | a `CLOSED` custody holds no row; a custody's version only grows | per operation |
| ASSET-INV05 | every custody that holds a row is allowed to hold by its owner module (`allowsHolding(owner_ref)`) | per operation; full pass at every census |
| ASSET-INV06 | across a clean restart, the startup census equals the shutdown census and the persisted counters per item id (§2.6) | at startup |
| ASSET-INV07 | no in-memory `Item` exists for an `ESCROW` row, and no retired `Item` is in any container | per operation; at every census |
| ASSET-INV08 | at most one live lifecycle per object id; a live lifecycle's object is in exactly one holding | by the unique key; at every census |
| ASSET-INV09 | `LazyItemsUpdate = False` (GeneralConfig.java:80, General.ini:193) | at startup; the module refuses to start otherwise |
| ASSET-INV10 | an object id is never released while a row with that id exists | by the core patch (§11.6) on every destroy; at every census |
| ASSET-INV11 | per custody and item id: Σ CREDIT − Σ DEBIT of the custody's postings = Σ `count` of its `ESCROW` rows | per operation for the custodies it touches; full pass at every census and at startup, including after a crash |
| ASSET-INV12 | every `lc_mail_delivery` with `claimed_at` unset names an existing service mail (§2.1: `senderId = -1`, `sendBySystem` nonzero, `reqAdena = 0`) whose attachment rows are exactly the operation's `MAIL` credit legs to that message, and `MailManager` knows it; a missing message for an unclaimed delivery is a violation, not a claim | per operation; at every census (which also adopts a message `MailManager` lacks, and sets `claimed_at` for a message whose attachments retail cleared, RequestPostAttachment.java:232) |
| ASSET-INV13 | a pending mark exists only while its operation is between `MARKED` and `DONE` | at startup (none may survive recovery); at every census |

A violation of INV01-INV05, INV07, INV08, INV10-INV13 writes `Asset.InvariantViolated`, freezes the owning module through
`lc_module_state` (the Asset Service itself when the custody's owner cannot be told), freezes the custody, and keeps the
evidence (04 §8, 02 §4). Repair is a corrective admin operation (§4.5), never automatic. INV06 raises
`Asset.ConservationBroken` and alarms (§2.6).

## 7. Failure model

Faults are injected as exceptions at each point below (the in-process path through §3.2's `finally`) and as JVM kills at
each point (the restart path): before each statement, after each statement, `COMMIT` sent, after `COMMIT` returned,
during the refund, after each memory step of `DONE`, after each client packet.

| Fault | End state |
|---|---|
| exception before `COMMIT` is sent | rolled back; the refund operation commits: whole objects' rows to service mail, stack parts as new mail rows with the stacks written from memory; the pending mark gone |
| connection lost after `COMMIT` sent | `UNCERTAIN`: resolved by the locking read, never by a timeout; then as committed or aborted |
| exception during the refund | retried while the locks are held; an uncertain refund commit is resolved by its own `OpId`; the module freezes after `UncertainCommitLookupMillis`; nothing is released unrefunded |
| JVM killed before `COMMIT` | nothing taken was written away (stack write locks kept Mobius's writes out; retired objects are written by nothing); after restart the assets are in their holders' rows as last saved; startup removes the pending mark |
| JVM killed after the main `COMMIT` failed and before the refund committed | as the row above: the rows were never changed by the operation |
| login while an operation of the character is pending | waits for the mark to go; restores the rows the operation or its refund left |
| `MailManager` adoption fails after commit | retried; repaired at the next census otherwise; the mail is in the rows and appears at the latest after a restart |
| JVM killed after `COMMIT` | the rows hold the committed operation; retired items and taken stack parts are in custody or mail; treasury credits are in the row |
| database restarted mid-transaction | rolled back by InnoDB; as the first row |
| GameServer restarted in any custody state | at startup, before any login: INV03, INV05, INV11 and INV12 are checked over every custody and delivery; the census is `STARTUP` after a clean shutdown and `REBASE` after a crash (§2.6) |
| receiver offline, inventory full, inbox full | the mail waits; retail mail checks room when it is claimed; a full inbox delays the receiver's own incoming player mail |
| receiver's deletion requested | refused while service mail is unclaimed (§11.21) |
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

- What an operation owes a character arrives as system mail: the retail mailbox shows it, with a subject naming the
  institution and the contract; claiming it is retail.
- Admin: `lc invariants asset`, `lc why <op>` (04 §6), `lc asset correct`, `lc asset unfreeze`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| ASSET-R01 | An asset operation MUST commit all its rows, postings, settlement key, owner state, facts and its `lc_asset_op` row in one transaction, or none of them. | AT-ASSET-01, AT-ASSET-20 |
| ASSET-R02 | Retrying an operation with the same `OpId` MUST have no effect once it committed. | AT-ASSET-02 |
| ASSET-R03 | A second settlement with the same (scope, key) MUST abort without moving anything. | AT-ASSET-00, AT-ASSET-03 |
| ASSET-R04 | An operation whose expected owner version differs MUST abort without moving anything. | AT-ASSET-04 |
| ASSET-R05 | An operation MUST take only the locks of §4.2, in Mobius's own order; no path, asset or Mobius, may deadlock against it. | AT-ASSET-05 |
| ASSET-R06 | An operation MUST write a Mobius item's row only under the item's write lock, from memory with the debit applied, so that no Mobius write before or after can lose or repeat it. | AT-ASSET-06 |
| ASSET-R07 | A non-stackable escrowed item MUST keep its object id, enchant, augmentation, elementals and variables (including changes not yet saved) through fund and release; a part of a stack MUST keep its item id and get a child lifecycle. | AT-ASSET-07 |
| ASSET-R08 | No inventory, warehouse, pet, mail or freight restore MUST load an `ESCROW` row; startup cleanup and every classified SQL site MUST leave it. | AT-ASSET-00, AT-ASSET-08, AT-ASSET-26 |
| ASSET-R09 | An escrowed item's object id MUST stay reserved while its row exists, at runtime and across restarts. | AT-ASSET-00, AT-ASSET-09 |
| ASSET-R10 | Every credit to a character MUST be system mail written in the operation's transaction; no asset MUST be credited to a live Mobius container. | AT-ASSET-10 |
| ASSET-R11 | Service mail MUST be built as §2.1 says (not rejectable, not cash on delivery, never expiring) and MUST keep its attachments until claimed; the claim itself is retail and has retail's guarantees, no more. | AT-ASSET-11 |
| ASSET-R12 | Every site that changes an item's existence or quantity, and every SQL statement deleting or updating `items`, MUST be classified; a clean restart MUST either conserve every item id exactly or report the exact per-item difference; a move imbalance beyond tolerance for two windows MUST raise an alarm. | AT-ASSET-12 |
| ASSET-R13 | Clamps at `MAX_ADENA` and at 0, in stacks and in castle treasuries, MUST be classified sinks. | AT-ASSET-13 |
| ASSET-R14 | Living Chronicle MUST NOT create an asset except `TOWN_SUPPLY` within budget and audited `ADMIN_CORRECTION`. | AT-ASSET-14 |
| ASSET-R15 | A violated invariant MUST freeze the owning module and the custody, write evidence, and change nothing else; only corrective operations may then commit. | AT-ASSET-15 |
| ASSET-R16 | Nothing credited MUST be visible to anyone before the operation committed. | AT-ASSET-16 |
| ASSET-R17 | An ambiguous commit MUST be resolved by a locking `OpId` read, and MUST NOT be aborted on a timeout. | AT-ASSET-21 |
| ASSET-R18 | After a GameServer restart, no login MUST be accepted before recovery completes; INV11 and INV12 MUST hold for every custody and delivery. | AT-ASSET-25 |
| ASSET-R19 | The module MUST refuse to start unless `LazyItemsUpdate = False`. | AT-ASSET-17 |
| ASSET-R20 | An item MUST enter a custody only if it is eligible (§4.4). | AT-ASSET-18 |
| ASSET-R21 | An operation MUST commit within `CommitBudgetMillis` at p99 under the capacity benchmark. | AT-ASSET-30 |
| ASSET-R22 | An aborted operation MUST be followed, before any lock is released, by a committed refund operation that returns every debited asset to its holder by service mail (or to its treasury) and writes the touched stacks from memory. | AT-ASSET-22 |
| ASSET-R23 | A retired item MUST never be written by Mobius nor accepted by any Mobius operation. | AT-ASSET-24 |
| ASSET-R24 | Mobius MUST release an object id only after its row is deleted. | AT-ASSET-09 |
| ASSET-R25 | A raided share MUST be dropped at most once, after commit. | AT-ASSET-27 |
| ASSET-R26 | A character's login MUST wait while an operation that took its items is pending. | AT-ASSET-28 |
| ASSET-R27 | A character MUST NOT be marked for deletion, or deleted at once, while it has unclaimed service mail or a pending mark; neither service mail nor a pending mark MUST be written for a character that is gone or marked; all are serialised on the character's row. | AT-ASSET-29 |
| ASSET-R28 | An attempt that was refunded MUST NOT run again under its id. | AT-ASSET-02 |

| Test | Kind | What it does |
|---|---|---|
| AT-ASSET-00 | integration | every migration applies in order on the stock schema; keys and checks reject what this spec forbids; an escrow row with a live custody passes upstream's own cleanup and used-id queries |
| AT-ASSET-01 | integration | every operation kind: rows, postings, mail, facts and op row appear together |
| AT-ASSET-02 | integration | same `OpId` twice after a commit, and after an abort and refund: no second effect, no second debit |
| AT-ASSET-03 | integration | two settlements with one key: the second aborts, nothing moved |
| AT-ASSET-04 | integration | stale owner version: aborts, refunded, nothing else moved |
| AT-ASSET-05 | integration | random concurrent operations over several items, interleaved with `Player.store`, autosave, logout (both paths), login, `transferItem` in both directions between the same stacks, trade, private stores, mail, warehouses, castle tax, pet actions, enchanting and items with template skills, under a lock-order witness: no inversion, no deadlock |
| AT-ASSET-06 | integration, restart | operations racing every Mobius writer of the same items (including one emptying or merging the debited stack, and retail additions to it after the debit), then a JVM kill at each point and a clean logout: rows hold no asset twice, lose none, and later additions are saved |
| AT-ASSET-07 | integration | an enchanted, augmented, elemental item with variables changed but not yet saved, and a variables write racing the fund, round-trips through escrow and mail; a stack part gets a child lifecycle |
| AT-ASSET-08 | restart | escrow and service mail rows survive cleanup and every classified SQL site; no container loads an escrow row |
| AT-ASSET-09 | integration, restart | the allocator never hands out an escrowed or mailed object id, at runtime and after restart; a failed row delete keeps the id reserved |
| AT-ASSET-10 | integration | releases, refunds and corrections arrive only as system mail; the live containers are untouched |
| AT-ASSET-11 | integration | service mail as written: rejecting it, deleting it with attachments, waiting past every expiry run, an offline receiver, a full inventory and a full inbox: its attachments survive until claimed; startup cleanup keeps it (DatabaseIdManager.java:68) |
| AT-ASSET-12 | unit, integration | the static site scan fails on an unclassified site, a `PENDING` row and a stale row; a dupe injected into a retail transfer raises the move alarm within two windows; a clean restart conserves every item id; a crash produces a rebase, never a pass |
| AT-ASSET-13 | unit | gains over `MAX_ADENA` and losses below 0, in stacks and castle treasuries, are counted as clamp sinks |
| AT-ASSET-14 | integration | every operation kind, randomised: no SOURCE leg except `TOWN_SUPPLY` within budget and audited corrections |
| AT-ASSET-15 | integration | a forged `ESCROW` row under a closed custody: module and custody frozen, evidence written, rows untouched; only a correction commits |
| AT-ASSET-16 | integration | no mail, client packet or cache update shows a credit before `COMMITTED` |
| AT-ASSET-17 | unit | startup with `LazyItemsUpdate = True` refused |
| AT-ASSET-18 | unit | each ineligible kind of item (§4.4) refused |
| AT-ASSET-20 | fault | exception and JVM kill at every point of §7 for every operation kind |
| AT-ASSET-21 | fault | connection cut after `COMMIT` sent, both outcomes; lookup timing out keeps the locks, freezes the module, never aborts |
| AT-ASSET-22 | fault | every abort cause, with the holder's stack emptied or merged meanwhile, and the refund's own commit cut, failed and killed, treasury debits included: the debited assets come back exactly once, the stacks' and treasury's rows match memory |
| AT-ASSET-24 | unit, integration | every Mobius item operation and writer refuses a retired item; a scheduled equip racing a whole-object fund never installs it |
| AT-ASSET-25 | restart | GameServer killed in every custody state; recovery before login; INV11 and INV12 exact |
| AT-ASSET-26 | restart | `DatabaseCleanUp = True` with escrow and service mail rows of every kind |
| AT-ASSET-27 | fault, restart | a raid dies after commit, before or after the drop, with and without a restart: dropped at most once |
| AT-ASSET-28 | integration | logout and re-login racing a whole-object fund, both outcomes: no second live `Item`, the row restored is the committed one |
| AT-ASSET-29 | integration | both deletion paths (marking, and immediate with `DeleteDays = 0`): refused with unclaimed service mail or a pending mark, allowed after; a payout, and a debit that aborts and must be refunded, racing a deletion: either the operation and its refund complete first and the deletion is refused, or the deletion wins and the operation aborts with `RECEIVER_GONE` before taking anything |
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
    atomic (a checked compare-and-set on `_treasury`), and its row is written under a treasury write lock that the
    service takes too (§3.3).
11. **Blanket deletions by item id** reach every owner, -1 included (LongTimeEvent.java:557; Hero.java:81 at 874). → §4.4
    eligibility and §2.7 classification.
12. **Item variables are saved later, on their own lock and connection** (ItemVariables.java:135, 172), and `set`
    tracks the change and sets the value as two steps (AbstractVariables.java:117-121), so a capture between them reads the
    old value. Core patch: tracking, setting and removing (AbstractVariables.java:117-121, 201-228) are one step under the
    save lock; a retired variables object refuses writes and its scheduled save does nothing. → §3.3.
13. **Logout marks the player offline before the final save** (Player.java:11793, ItemContainer.java:550). → §2.4 handoff.
14. **Quantity is created outside `ItemManager.createItem`**: lottery, race and mercenary tickets are constructed
    directly (Loto.java:242; RaceManager.java:171 in `onBypassFeedback`; MercTicketManager.java:335 in `addTicket`, while
    :195 in `load` rebuilds saved tickets). → §2.7.
15. **Counts change without a lock** (`Item.changeCount`, Item.java:451, read-modify-write of `_count`; called without the
    item's monitor by `ItemContainer.addItem`, :268, and for the destination of `transferItem`, :376), so two concurrent
    changes can lose one, in Mobius today. Core patch: `changeCount` and `setCount` update `_count` atomically
    (compare-and-set, no monitor, so no new lock order), and a checked `tryDecrease(n)` serves debits (§4.2 rule 3).
16. **Mobius's own locks take an item's monitor before anything else on that item** and may then take the player's
    monitor (`transferItem` holds the source item's monitor while the inventory sends the skill list, Inventory.java:881,
    907; Player.java:10490; enchanting likewise, RequestEnchantItem.java:176, 266), and they are thread-owned. → the
    service takes item locks in that same order, on its own thread, and holds no lock of its own that Mobius waits for
    (§3.3, §4.2 rule 2).
17. **Crediting a live container cannot be made atomic from outside**: a stack can be merged away or destroyed between
    any two steps (`transferItem` empties and destroys the source, ItemContainer.java:365-383; ItemManager.java:164-175),
    and the merge of a persisted item deletes its row before the merged stack is saved. → credits are system mail
    (§2.1).
18. **Login holds a lock when it reaches the character** (`CharacterSelect.runImpl` holds the client's player lock while
    loading, CharacterSelect.java:105-124), and teardown has two paths (`Disconnection.storeAndDelete`,
    Disconnection.java:116; `GameClient.load`, GameClient.java:531-533). → the service takes no lock at login or
    logout: login only waits on the pending mark, a database check (§11.22), and logout serialises with a debit on the
    stack write locks (§2.4).
19. **Mail.** Expiry of sender -1 mail with the receiver offline destroys the attachments
    (MessageDeletionTaskManager.java:84-104): an upstream defect that service mail avoids by never expiring (§2.1), and
    recorded here for upstream. `MailManager.sendMessage` inserts on its own connection (MailManager.java:157): core patch
    so that `MailManager` adopts a message the service already committed, idempotently by message id.
20. **Retired items**: `Item.updateDatabase` does nothing for a retired item, and Mobius's item operations (container add,
    transfer, destroy, drop, equip, use) refuse one (core patch). → §3.3.
21. **Character deletion** removes the character's own items (GameClient.java:439-451) and leaves owner -1 rows, so
    service mail to a deleted character could never be claimed. A deletion either marks the character (`deletetime`)
    for later, or, with `DeleteDays = 0`, deletes it at once (GameClient.java:293) on other connections. Core patch: both
    paths first run the deletion guard (§2.1), which locks the row, refuses while unclaimed service mail or a pending mark
    exists, and marks the character, committed before any deleting statement; service mail and pending marks are written
    under the same row lock. → ASSET-R27.
23. **Claims of service mail** clear the message's attachments (RequestPostAttachment.java:191-232) and say nothing to the
    service. Core patch: after clearing a service message's attachments, the claim sets `claimed_at` on its delivery; the
    census repairs a claim whose patch step did not run.
22. **Login** restores a character's items from rows (Player.java:7358-7360) with plain reads that do not wait for an
    uncommitted change. Core patch: the restore waits while `lc_asset_pending` marks the character. → ASSET-R26.

## 12. Review record

- Round 7 (Codex): **SIGN-OFF.** The deletion guard covers both deletion paths (`deletetime` and `DeleteDays = 0`) with
  the mark committed before any deleting statement; pending marks protect a mandatory refund; the traceability test
  checks that a row's tests cover its requirements' and that its blueprint documents exist. Remaining, as named N4
  obligations and not spec defects: the complete site table and its `PENDING` rows (AT-ASSET-12), and implementing the
  fault and INV12 checks.
- Round 6 (Codex), NO SIGN-OFF: confirmed the stack-monitor rule (no cycle found between item monitors, write locks and the
  inventory monitor in the paths examined), retire-first under item then container, refund finality and treasury
  compensation after commit, the narrowed restart guarantee, and the claim record; accepted the seed table, `PENDING`
  rows and fault coverage as named N4 obligations. Found that immediate deletion (`DeleteDays = 0`) bypassed the guard
  and that deletion did not protect a pending refund. Revision 7: one deletion guard before both deletion paths,
  refusing while service mail or a pending mark exists; pending marks written under the same row lock; and the
  traceability test made to check that a row's tests cover its requirements' and that its blueprint references exist.
- Round 5 (Codex), NO SIGN-OFF: confirmed the lock cycles gone (no further cycle found in store, autosave, transfer,
  warehouse, mail, pet, clan warehouse or shutdown paths), the pending mark, refunds as operations, the mail flags and
  lifecycle; found that a stack decrease without the monitor can fall between a retail transfer's decision and its
  action (100 becomes 120), that a scheduled equip can install a whole object between removal and retirement, that an
  attempt refunded could run again under its id, that a treasury refund touched memory before its commit, that
  deletion and payouts were not serialised, and that INV12 held vacuously for a missing message. Revision 6: stack
  decreases under all their monitors (taken in object-id order before their write locks); whole objects retired first,
  under the item's and then the container's monitor; refunded attempts final; refund memory after commit; deletion and
  payouts serialised on the character's row; deliveries record their claim; the clean-restart guarantee stated as exact
  conservation or an exact report.
- Round 4 (Codex), NO SIGN-OFF: confirmed mail credits, retirement, variables, at-most-once drops and thread-local
  resolution; found two lock cycles (a stack write lock held into `removeItem`; one item's write lock held while waiting
  for another's monitor), that marking a stack saved after commit could hide a later retail addition, that a logout and
  re-login could restore a second live copy of a whole object pending escrow, that a refund was neither durable nor an
  operation with its own identity, and that service mail needed explicit flags, a lifecycle without expiry, a rule for
  deleted receivers, an adoption that cannot be lost and an honest claim boundary. Revision 5: two phases of taking
  (whole objects alone under their monitors, then stack write locks that never wait for a monitor, §3.3); stacks never
  marked saved; the pending mark and the login wait (§2.4, §11.22); refunds as committed operations of their own
  (§3.2); service mail fully specified (§2.1, INV12, §11.19, §11.21); and the seed site table stated as a seed.
- Round 3 (Codex), NO SIGN-OFF: confirmed the applier and quarantine fixes, the scope of the move check, and the DDL;
  found that credits into live stacks and fenced rows can still be lost or doubled when Mobius merges, empties or deletes
  a stack in between (#33-#37), that an undo into a live stack can fail (#36), that login reaches L1 holding a lock and
  teardown has a second path (#40, #41), that ground placement has no exactly-once acknowledgement (#38), and that
  variables capture and retirement were two steps (#42). Revision 4 removes credits to live containers altogether
  (system mail, §2.1), and with them fences, L1, the presence handoff, the applier and `DELIVERY` custodies; takes
  debits under the item's own locks in Mobius's order with the rows written from memory in the transaction (§3.3);
  refunds aborts by mail (§3.2); makes a raided share a sink plus an at-most-once drop (§2.5, §4.3); counts losing
  item-auction bids until refunded (§2.6); and fixes the system-mail expiry loss upstream (§11.19).
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
