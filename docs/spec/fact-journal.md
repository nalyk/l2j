# SPEC-FACT — Fact Journal

- Status: draft (revision 9: telemetry amendments for aden-remembers.md, reviewed; revision 8 was signed off; record in §12)
- Blueprint: 04 §1 (Fact Journal), §6 (`lc why`), §7 (randomness and time), §8 (failure degradation); 02 §4, §6 (budgets:
  fact capture ≤ 2 µs p99, projector lag ≤ 5 s p99 at the 800-citizen siege benchmark), §11; 03 §4.4 (immutable
  primitive snapshots at the owning operation); consensus §4.2 e, §5.
- DDL: `sql/migrations/V005__lc_fact.sql` (before the Asset Service's, which writes into it).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/` unless they start with `dist/`.

## 1. Scope

The Fact Journal records what happened, for every Living Chronicle module (02 §3: the one mechanism for "recording that
something happened"). It owns:

- **durable facts**: immutable rows with financial, contractual, legal, historical or audit meaning;
- **telemetry**: high-volume ecological signals aggregated in memory per window and flushed as buckets, never one row per
  event (04 §1.1);
- **expiring data**: exact coordinates and any other datum that must disappear, kept beside facts, never inside them;
- **capture**: how the core seam and modules hand facts to the journal without slowing the game;
- **the projector runtime**: the contract every derived store is built on, including how a projector's decisions turn
  into effects (Asset operations, actuators) exactly once.

It does not own: what each module's facts mean (each module spec lists its facts; this spec registers them, §2.5), what a
player may learn from them (Intel Service), what is published (Chronicle), nor actor identity (Actor Identity spec).

## 2. Data

### 2.1 One log, one sequencer

All durable facts form **one log**, ordered by `lc_fact.id`, which only the journal's **writer** assigns. The writer is a
single thread with a single database session; it inserts facts in batches, each batch one transaction, in increasing id
order, so a reader that sees id *n* committed has seen every committed id below it. Ids may skip values (an aborted
insert consumes one); readers never treat a skipped id as a loss. Losses are explicit records in the log (§3.4).

Facts reach the writer two ways:

| Delivery | How | Guarantee |
|---|---|---|
| **outbox** | inserted into `lc_fact_outbox` by the module's own transaction, with the change it records (§3.1) | exactly once: it exists exactly when the change committed |
| **captured** | copied at the owning operation into a preallocated record and offered to the writer's bounded queue (§3.2) | at most once; every refusal and every loss is an explicit gap record in the log |

The writer **relays** committed outbox rows into the log (inserting them into `lc_fact` and deleting them from
`lc_fact_outbox` in the same transaction) and drains the capture queue, so the log's order is the writer's: a serial order
of everything that happened, the same live and on replay. A **stream** still names the aggregate a fact belongs to
(`asset.custody.<id>`, `pact.<contract>`, `cap.kill`, …) and an outbox stream's facts carry consecutive `stream_seq`
numbers from its head row (§3.1), so a consumer can version an aggregate; order *between* streams is simply log order.

### 2.2 Tables

```text
lc_fact(id BIGINT auto, occurred_at TIMESTAMP(3), stream VARCHAR(48), stream_seq BIGINT NULL, type SMALLINT, schema_ver SMALLINT,
        logical_time BIGINT, actor_snapshot JSON, region_id SMALLINT, zone_id INT NULL, instance_id INT, sensitivity TINYINT,
        payload JSON, op_id BINARY(16) NULL, config_ver INT, captured_at TIMESTAMP(3), source_outbox_id BIGINT NULL,
        batch_id BIGINT, PRIMARY KEY(id, occurred_at))
        PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)), one partition per month and a catch-all
lc_fact_outbox(outbox_id BIGINT PK auto, staged_at TIMESTAMP(3), stream VARCHAR(48), stream_seq BIGINT, type SMALLINT,
               schema_ver SMALLINT, logical_time BIGINT, actor_snapshot JSON, region_id SMALLINT, zone_id INT NULL,
               instance_id INT, sensitivity TINYINT, payload JSON, op_id BINARY(16) NULL, config_ver INT)
lc_stream_head(stream VARCHAR(48) PK, last_seq BIGINT)
lc_journal_epoch(epoch INT PK auto, started_at TIMESTAMP(3), ended_at TIMESTAMP(3) NULL, clean BOOLEAN)
lc_journal_batch(batch_id BIGINT PK, epoch INT, committed_at TIMESTAMP(3), first_id BIGINT NULL, last_id BIGINT NULL,
                 relayed INT, captured INT, phase BIGINT NULL, frontier_logical_time BIGINT NULL)
lc_journal_gap(fact_id BIGINT PK, epoch INT, phase BIGINT NULL, family VARCHAR(32), refused BIGINT NULL,
               from_logical_time BIGINT, to_logical_time BIGINT, reason {QUEUE_FULL|STALLED|RESTART},
               acknowledged_by VARCHAR(32) NULL, acknowledged_reason VARCHAR(128) NULL)
lc_fact_expiring(fact_id BIGINT, field VARCHAR(32), value JSON, expires_at TIMESTAMP(3), PRIMARY KEY(fact_id, field))
lc_fact_precise(fact_id BIGINT PK, x INT, y INT, z INT, instance_id INT, expires_at TIMESTAMP(3))
lc_telemetry_bucket(window_start TIMESTAMP, window_len INT, region_id SMALLINT, family SMALLINT, actor_class TINYINT,
                    events BIGINT UNSIGNED, sum_a BIGINT, sum_b BIGINT, unique_actors INT UNSIGNED, stalled BOOLEAN,
                    PRIMARY KEY(window_start, region_id, family, actor_class))
lc_projector_checkpoint(projector VARCHAR(48) PK, last_fact_id BIGINT, reducer_ver SMALLINT, config_ver INT)
lc_effect_outbox(application_id BINARY(16) PK, projector VARCHAR(48), fact_id BIGINT, effect_index SMALLINT, kind VARCHAR(32),
                 payload JSON, state {PENDING|DONE|FAILED}, attempts INT, created_at TIMESTAMP(3), done_at TIMESTAMP(3) NULL,
                 UNIQUE(projector, fact_id, effect_index))
lc_config_version(config_ver INT PK auto, module VARCHAR(16), changed_at TIMESTAMP(3), content JSON)
lc_fact_type(type SMALLINT PK, name VARCHAR(64) UNIQUE, schema_ver SMALLINT, durability {DURABLE|TELEMETRY},
             delivery {OUTBOX|CAPTURED}, family VARCHAR(32), sensitivity TINYINT, retention_days INT NULL, owner_module VARCHAR(16))
lc_replay_snapshot(projector VARCHAR(48), last_fact_id BIGINT, taken_at TIMESTAMP(3), store_ref VARCHAR(128),
                   PRIMARY KEY(projector, last_fact_id))
lc_journal_retention(family VARCHAR(32) PK, max_dropped_fact_id BIGINT)
```

- `lc_fact` is immutable: the game server's database user may `INSERT` and `SELECT` it and nothing else (§8), and nothing
  that must ever be removed is written into it (§2.4). It is partitioned by month (MariaDB allows no unique key there
  without the partition column, and no foreign keys); its order is the writer's.
- An **epoch** is one run of the writer: it begins at startup and ends cleanly only when the writer has drained the queue
  and the outbox at shutdown and recorded `clean`. A **phase** is one interval of the capture side (§3.3).
- `logical_time` is the game clock at the fact (`GameClock.millis`, `gameserver/livingchronicle/clock/GameClock.java:35`);
  it is data a reducer may compare, never the order of application.
- `config_ver` is the configuration under which the producer acted, stamped by the producer when it staged or captured
  the fact (a module's in-memory configuration version changes only after the configuration change has committed), so a
  fact is never read under a configuration it was not produced under, whatever the relay order (§4.1).
- `captured_at` is when the fact was staged or captured (wall clock); lag is measured from it (§4.6). `source_outbox_id`
  names the staged row a relayed fact came from, so no staged row can be relayed twice unnoticed (INV03).
- `lc_journal_retention` holds, per family, the highest fact id of that family ever dropped with a partition (§4.5).
- `lc_effect_outbox` turns a projector's decision into an effect exactly once (§4.4); `lc_config_version` binds
  configuration to facts (§4.1); `lc_replay_snapshot` records copies of derived state by the log position they cover
  (§4.5).

### 2.3 Region

`region_id` is a dense index of the town map region of the place: Mobius's `MapRegionData.getMapRegionLocId(x, y)`
(`gameserver/data/xml/MapRegionData.java:350`) returns that region's sparse XML `locId` (for example 2189,
`dist/game/data/mapregion/kamael_town.xml:5`), or 0 where no region matches. At startup the journal numbers every
`locId` the map region data defines, in ascending order, from 1; 0 stays "no region". The table from `locId` to index is
written as a `config` fact, so producers and projectors, live and on replay, classify a place the same way. `zone_id` is
set only by facts whose meaning is a zone, never looked up on a hot path (`ZoneManager.getZones` allocates,
`gameserver/managers/ZoneManager.java:647`). `instance_id` is `WorldObject.getInstanceId()` (`WorldObject.java:729`).

### 2.4 Expiring data

Nothing that must disappear is written into `lc_fact`. A fact that carries an exact location writes it to
`lc_fact_precise`; a fact that carries any other expiring datum (an alias's link to a character, an informant's
identity) writes it to `lc_fact_expiring`, one row per field; the fact's payload holds neither, only that they exist.
Both tables are purged at `expires_at` by deleting rows, never by rewriting a fact (INV05).

- **Backups** of the two expiring tables are kept no longer than the longest expiry they can hold (`PreciseRetentionHours`,
  `ExpiringRetentionHours`), and a restore deletes every row already past its `expires_at` before the server starts.
  Other modules' expiring tables (the Intel Service's, and any table holding intel ids) are listed with these two in one
  registry, `lc_expiring_table` (intel-service.md §4.5), and the same rule applies to every table in it; tables outside
  the registry hold no expiring data.
- **Projectors** may read expiring data only into derived state that expires with it; replay is defined over `lc_fact`
  alone, so a replay after expiry gives the derived state the module would have without the expired data.

### 2.5 Fact types

Every fact type is a Java record implementing a sealed interface, registered in `lc_fact_type` with its id, name, schema
version, durability, delivery, family, sensitivity, retention and owner module. Adding a type, or bumping its schema version, is a change to the owner's spec and to this
registry. A schema bump is additive: older versions are read through an explicit upcaster to the newest; a stored row is
never rewritten. The payload is canonical JSON (sorted keys, integers only, fractions as integers with a stated scale).

### 2.6 Telemetry

A family (kills, yield, travel, presence, deaths, teleports, …) has fixed counters per (window, region, family, actor
class): `events`, two family-defined sums and a distinct-actor sketch (HyperLogLog registers, a fixed byte array per
cell). The current window is a buffer of preallocated arrays indexed by (region index, family, actor class).

- Each family is registered with its **region dimension**: `ATOM` (the region index of §2.3, the default) or a module's
  own dense index declared with the family (Aden Remembers' subregions, aden-remembers.md §2.1), sized at startup; the
  `region_id` of its buckets is then that index. A module may register a contiguous **range** of family ids (one per
  resource family, for instance), each a family in every other respect.

## 3. Capture

### 3.1 Outbox facts

A fact whose meaning depends on a state change is staged in the transaction that makes the change: the caller locks its
stream's head row, takes the next `stream_seq` numbers, inserts the facts into `lc_fact_outbox` and advances the head,
all before its `COMMIT`. A staged fact exists exactly when its change does (INV03); the writer relays it into the log
by the relay.

**Relay.** Every `RelayMillis` (a polling cadence, not a deadline) the writer selects every committed staged row, oldest `outbox_id` first, up to
`WriterBatchMax`, with no high-water mark: a row whose transaction commits late is simply found at the next poll. Within a
stream, a later number was staged only after the earlier one committed (the head lock serialises them), so `outbox_id`
order is stream order. The selection, its contents and its batch id are kept until the batch is resolved: the batch
inserts the facts (each with its `source_outbox_id`), deletes exactly those staged rows, and inserts its
`lc_journal_batch` row, in one transaction. An uncertain commit is resolved by a locking read of the batch row before the
writer selects anything else: present means done; absent means the same selection is retried under the same batch id;
a lookup that cannot be made leaves the batch unresolved and the writer retrying it, never re-polling.

**Row-lock order.** Every Living Chronicle transaction takes its database row locks in one global order, each category
by key: `characters` rows; `castle` rows; custody rows; `items` rows and their attribute, elemental and variable rows (by
object id); `messages` and `lc_mail_delivery` rows; `lc_asset_pending` rows; owner-module rows (by table, then id);
`lc_settlement_key` and `lc_asset_op` rows; stream heads; then the inserts into `lc_fact_outbox`, which lock nothing
another transaction waits for. Every transaction sets a lock-wait timeout of `LockWaitMillis`. Lock waits arise only from contention on one row (two
operations on one character, custody or stream at once), which the Asset Service's commit budget (asset-service.md §8,
p99) absorbs; the capacity benchmark measures it with contention included (AT-FACT-02, AT-ASSET-30). A deadlock victim or a
lock-wait timeout is a definite rollback before `COMMIT`: the transaction is retried up to `DeadlockRetries` times,
holding the in-memory locks it holds, and only then fails; for an Asset operation that failure is the abort-and-refund
of asset-service.md §3.2.

### 3.2 Captured facts

A fact that records something Mobius did on its own is captured where Mobius does it (§3.5), by a core-seam call that
claims a slot in the writer's preallocated ring of fixed-size records, copies primitives into it (03 §4.4) and publishes
it, without blocking, without I/O and without allocating. Mobius's own event objects are not used: they hold live
`Player`, `Item` and `Location` references
(`gameserver/mechanics/events/holders/actor/player/inventory/OnPlayerItemTransfer.java:30-32`; `WorldObject.getLocation`
returns the live field, `WorldObject.java:748-751`), and async listeners run later on a pool with an unbounded queue
(`gameserver/mechanics/events/EventDispatcher.java:159`; `commons/threads/ThreadPool.java:86`).

- **Claim, fill, publish.** A capture claims a slot (the slot carries a generation number, advanced each time the slot is
  reused, and the phase), fills it inside a `try`, and publishes it in the `finally`: filled, or marked **void** if filling
  threw. A void slot counts as a refusal of its family, so a capture that failed half-way is a
  gap, never a silent loss, and the writer never waits on a slot that was claimed and left.
- **A full ring refuses** the capture and counts it for its family in the current phase, with the earliest and latest
  logical time refused. Mobius's file logger is the precedent for a writer behind a bounded queue whose offer never blocks
  and counts refusals (`log/handler/AsyncFileHandler.java:47, 87-89, 125-129`).
- **Bulk facts** (a siege's end with its participants, a raid boss's kill with its contributors) take one record per
  `MaxParticipantsPerFact` participants, sharing a correlation id with their parts numbered; their budget is
  `BulkCaptureMicros`, not the hot path's 2 µs.
- **Coherent locations.** Mobius's `Location` has five independently written volatile fields (`entity/Location.java:36-40`)
  written one after another (`WorldObject.java:760-762`), possibly from more than one thread. A core patch gives
  `Location` a version: a writer moves it from even to odd by compare-and-set (spinning while it is odd, so writers exclude
  one another), writes the fields, then makes it even again; capture reads the fields between two equal, even readings,
  retrying up to three times, and otherwise records the location as `torn`.

### 3.3 Phases: when every capture is accounted for

Every capture runs inside a **phase**. **Admission**: the producer reads the current phase *p*, increments its stripe of
*p*'s in-flight counter (the stripe is the thread's id modulo the stripe count: no registration, no allocation), then
reads the current phase again; if it is no longer *p*, it decrements and starts over in the new one. It then claims its
slot (or counts its refusal), records *p* in the slot, and decrements in a `finally`. **Closing**: the writer makes *p+1*
current and then watches *p*'s stripes; once they are all zero, no capture can still enter *p* (an admission that read
*p* re-reads and leaves), and every capture of *p* is published in the ring or counted as refused.

**One phase, one batch, gaps first.** The writer commits captured facts only by closed phase: the batch for phase *p*
holds, in this order, *p*'s gap records (`Journal.Gap`: family, count, logical-time span, reason `QUEUE_FULL`), then *p*'s
facts, and records *p* and its **frontier** (the logical time at which *p* was closed). A projector therefore meets a
phase's gaps before any of its facts, and stops before acting on anything from a phase that lost something. **Latency.** The writer is one thread committing one batch at a time, so every bound counts the batches committed before
a fact's own. Let *T* be the longest batch transaction (a measured value: `WriterBatchBudgetMillis`, checked by the
benchmark, and exceeding it is itself an alarm). With the store available: a captured fact is committed within
`HeartbeatMillis` + `PhaseDrainMillis` + 3*T* (a batch already in progress when its phase closed, at most one relay batch,
and its phase's own); a staged fact with *k* rows staged before it and not yet relayed is committed within `RelayMillis`
+ (2 × ⌈(*k* + 1) / `WriterBatchMax`⌉ + 2) × *T*: the batch already in progress when it was staged (whose selection is
fixed, so it cannot include the fact) and the phase batch that may follow it, then its own and the preceding relay batches, each of which may be followed by one
phase batch, because with a backlog the writer relays batch after batch without waiting for the cadence and commits at
most one closed phase between two relay batches. A batch that fails and is retried (§3.1) adds its retries' time. With
the store down, nothing is committed and §7 applies. These bounds, not the cadences, are what lag (§4.6) and its tests
measure; a test that sees a slower batch than *T* fails on the alarm, not on the bound.

**The writer's loop** never blocks on a phase: each iteration relays the outbox if `RelayMillis` has passed, checks the
closing phase's stripes without waiting, and commits a phase once it has drained. It runs under a supervisor that
restarts it if it dies; the restarted writer first resolves any unresolved batch (§3.1).

- A phase whose stripes are not all zero after `PhaseDrainMillis` (a producer stuck, or its `finally` failed) is written
  as a `STALLED` gap (count unknown) for every family, followed by whatever of its facts the ring holds, and its counters,
  its telemetry buffer and every ring slot it claimed but did not publish are **retired for good**: never reused (a retired
  slot keeps its generation, and a late publish into it, whose generation or phase no longer matches, is ignored), so a
  producer that wakes up late writes only into storage nothing reads again; the ring is that many slots smaller until
  restart. Stripes and buffers come from fixed pools sized at startup; if a pool runs out, capture of the
  affected kind is refused (counted as refusals) until restart.

- A stripe that is not zero after `PhaseDrainMillis` (a producer stuck, or one whose `finally` itself failed) does not
  stop the writer: it writes a `STALLED` gap for the phase (count unknown), abandons that phase's counters for good, and
  goes on.
- The same phases close telemetry windows (§3.4): a window's buffer is written only after its phase drained, or written
  flagged `stalled`.
- A **crash** loses what the ring held. At startup a new epoch begins; the writer records a `RESTART` gap covering every
  captured family (`*`), from the last committed frontier of the previous epoch (count unknown), unless that epoch ended
  clean.

A module that must decide on the **absence** of a captured fact (no kill before a deadline) decides only once the log's
frontier has passed the deadline, and treats a gap record overlapping it as "unknown", failing closed. A module that
needs a fact exactly once makes it an outbox fact in its own transaction.

### 3.4 Telemetry capture

`Telemetry.add(family, regionIndex, actorClass, a, b, actorId)`: O(1), no allocation, no lock. Inside the current phase
(§3.3), the producer adds into its cell of the current window's buffer: `events`, the two sums and the distinct-actor
sketch register (an atomic max). At a window's end the writer swaps in a fresh buffer, and once the phases that could
still add to the old one have drained, writes one row per non-empty cell; a buffer whose phase stalled is written with
`stalled` set. An add is therefore counted in exactly one window, whole. The window's rows and its
`Journal.WindowWritten` fact (window start, length, stalled, row count) are written **in one transaction**, the fact as an
outbox fact of that transaction relayed like any other (§3.1, FACT-R01), so a window is never visible without its
marker nor its marker without its rows, and a crash after the commit loses neither. A projector that reads telemetry binds
each step to the log: at fact *F* it reads exactly the windows whose `WindowWritten` precedes
*F*, the same live and on replay (aden-remembers.md §4.12).

### 3.5 Where capture happens

The core seam (blueprint 03 §4.4) adds capture calls at the owning operations. The natural points, as Mobius stands:

| What | Where | Thread |
|---|---|---|
| a creature dies; an attackable's kill and its rewards | `Creature.doDie`, `gameserver/entity/actor/Creature.java:2606` (dead flag :2614-2624); `Attackable.doDie` :314, `calculateRewards` :374, `doItemDrop` :491, :496, :1095 | the scheduled pool, from the hit or skill that killed (`Creature.java:1382`, :2168) |
| damage taken | `CreatureStatus.reduceHp`, `gameserver/entity/actor/status/CreatureStatus.java:144` | scheduled pool |
| movement and region change | `Creature.updatePosition` :4312; `WorldObject.setXYZ` `WorldObject.java:760` | movement task, high-priority pool every 50 ms (players) / 100 ms (`gameserver/taskmanagers/MovementTaskManager.java:25-26`) |
| pickup, drop | `Player.doPickupItem` `Player.java:4551`; `Item.dropMe` `Item.java:1600` | AI or packet thread |
| spawn | `Npc.onSpawn` `Npc.java:1313` | respawn task (1 s) or caller |
| login, logout | `Player.onPlayerEnter` :11049; `Player.deleteMe` :11766 | packet thread; disconnection |
| teleport | `Creature.teleToLocation` :795 | caller |
| zone enter and exit | `ZoneType.revalidateInZone` `gameserver/entity/zone/ZoneType.java:404`, `removeCharacter` :442 | movement thread |

Movement, damage and region changes are telemetry only; deaths, kills and logins are telemetry, and also captured facts
where a module's spec needs them.

## 4. The projector runtime

### 4.1 Determinism

The same log, the same reducer version and the same configuration give the same derived state. Randomness a reducer
needs comes from the fact (recorded outcomes) or from a keyed stream seeded by the fact's id (04 §7); time is the fact's
`logical_time`, never the wall clock; configuration is the fact's own `config_ver`, the one it was produced under;
the reducer's version and the configuration versions it read are recorded in the checkpoint.

### 4.2 Order

A projector applies the facts it consumes in log order (`lc_fact.id`), reading only committed batches. That order is the
same live and on replay, whatever streams a projector combines; ties and frontiers do not arise.

### 4.3 Checkpoints and gaps

A projector writes its derived rows, its checkpoint (the last fact id applied, reducer and config versions) and its effect
intents (§4.4) in one transaction; a fact at or below the checkpoint is skipped. A projector **stops** at a gap record of
a family it consumes, and at an epoch boundary that did not end clean, recording `Journal.ProjectorStopped`; its owner
module's effects freeze (04 §8) until an admin acknowledges the gap with a reason (`Journal.GapAcknowledged`).

### 4.4 Effects

A projector never calls the Asset Service or an actuator directly. It writes each effect it decided into
`lc_effect_outbox` with its position (fact id, effect index) and a deterministic application id derived from
(projector, fact id, effect index), in the transaction of its checkpoint. One dispatcher per projector executes that projector's `PENDING` effects strictly in
(fact id, effect index) order; an effect that has not succeeded blocks the ones after it.

- **Asset effects** run as Asset operations with operation id `OpId.of(application id, attempt)`. A `COMMITTED` outcome
  marks the effect `DONE`; an aborted-and-refunded outcome (asset-service.md §3.2) means nothing happened, so the next
  attempt runs with the next attempt number. After `EffectAttempts` refunded attempts the effect is `FAILED`, which freezes
  the module.
- **Actuator effects** change database state only, and commit the application id with it (a unique key), so an actuator
  answers whether it already applied an effect from its own durable record, not from what is in the world now. What an
  actuator puts in the world (a monument, a caravan, a crier) is a projection of that state: its owner module spawns it
  from the rows and, after a restart, re-spawns what the rows say still exists, so "applied, and since gone" is never
  mistaken for "never applied". The dispatcher asks before executing and after a crash.
- A crash between checkpoint and execution leaves the intent `PENDING`; it is executed after restart. Replay writes no
  effect.

### 4.5 Replay and retention

**Telemetry buckets a projector reads are protected like log partitions**: maintenance deletes a family's buckets of a
window only when its `WindowWritten` fact is at or below every consuming projector's checkpoint and the newest usable
snapshot of each, and no replay lease of the family has read below it; the same exclusive lock on the family's
`lc_journal_retention` row serialises the deletion with admission (below), and a replay re-reads the watermark of the
buckets it needs at each batch. A projector's declared bucket retention (aden-remembers.md §4.12) is a minimum on top of
this, never a replacement. **A projector reads a window once**, at the step its marker precedes; whatever it needs of that
window later (a running mean, the daily values behind a 28-day median) it keeps in its own derived state, which its
snapshots carry, so a window it has passed is never needed again by it or by its replay.

Replay rebuilds an isolated store (its own schema) from a **usable** replay snapshot. **Admission** is one transaction
that reads the `lc_journal_retention` rows of the projector's families with a shared lock, chooses the newest snapshot
usable against them, and inserts its lease; maintenance's step (1) updates those same rows, so the two serialise: a
replay is admitted either before a watermark moves (and its lease is then seen by maintenance) or after (and chooses
against the new watermark). Every read batch also re-reads its families' watermarks and aborts the replay if any is
above the lease's read position, so even an admission that slipped past this protocol cannot read past a hole. A running
replay holds a **lease**
(`lc_replay_lease`: its projector, the families it reads, the id it has read up to, a heartbeat), and reads each batch
of facts in a transaction that holds its lease row with a shared lock and checks the lease is still valid. Maintenance
first locks the `lc_journal_retention` rows of every family in the partition **exclusively** (creating any missing row
first), and holds them through step (1); only then does it scan and lock the leases of those families. An admission
needs those rows shared, so while maintenance holds them no lease of those families can be inserted: every lease that
can matter either committed before the scan, and is seen by it, or is admitted after step (1) commits, against the new
watermark. Then: a live lease whose read position is
below the partition's last id **keeps the partition** (the drop waits for the next run); a lease whose heartbeat is older
than `ReplayLeaseSeconds` is invalidated in that transaction, and its replay, at its next batch, finds it invalid and
aborts and starts over from a snapshot usable then. So no replay ever reads past a hole, and none holds retention
forever. A snapshot is usable for projector *P* if its
`last_fact_id` is at or beyond the highest `max_dropped_fact_id` of every family *P* consumes, so that every fact *P* needs
after it is still in the log; the newest usable one is chosen, or the log's first fact if none of *P*'s families ever
lost a partition.
Facts are kept per type for `retention_days` (`NULL`: for ever). A monthly partition is dropped only when every fact in it
is past its type's retention, every projector's checkpoint is past its last id, and every projector reading its families
has a snapshot whose `last_fact_id` is at or beyond that last id. The drop is done in this order, so an interruption at
any point leaves replay safe: (1) in one transaction, advance `max_dropped_fact_id` of every family present in the
partition to the partition's last id; (2) only then drop the partition. Between (1) and (2) the facts are still there but
no snapshot below the watermark is used: conservative, never wrong. A snapshot is retired only when no projector can use
it any more, judged against the families its own projector consumes. The maintenance job creates partitions at least two months ahead every day and alarms if the
catch-all partition holds a row.

### 4.6 Lag

A projector's lag is the age of the oldest fact of a family it consumes that it has not yet applied, measured from the
fact's `captured_at` (when it was staged or captured), so the relay and the phase wait count; a fact still staged and
not yet relayed counts too. With nothing waiting, lag is zero. A telemetry window's delay is
measured separately, from its end to its row's commit.

## 5. Facts of the journal itself

| Fact | Delivery | Fields |
|---|---|---|
| `Journal.Gap` | written by the writer into the log | family, epoch, phase, refused (or unknown), logical-time span, reason |
| `Journal.GapAcknowledged` | outbox | gap, admin, reason |
| `Journal.ProjectorStopped` | outbox | projector, last fact applied, cause |
| `Journal.EpochStarted`, `Journal.EpochEnded` | written by the writer | epoch, clean |
| `Journal.ConfigChanged` | outbox (stream `config`) | module, config version |
| `Journal.Purged` | outbox | table, before, rows |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| FACT-INV01 | `lc_fact` holds no exact coordinate and no expiring datum: no payload field of a registered type is one | by the registry at startup (schema check of every record type) and a property test over every type |
| FACT-INV02 | the log's ids grow in commit order: no committed batch holds an id below one of an earlier committed batch | by construction (one writer, one session); by the writer at each commit (its first id exceeds the last batch's last id) |
| FACT-INV03 | a staged outbox fact exists if and only if the change it records committed; it is relayed into the log exactly once | by construction (same transaction; relay inserts and deletes in one transaction); at startup, no staged row is also in the log |
| FACT-INV04 | no projector checkpoint is ahead of the last committed fact | at every checkpoint write |
| FACT-INV05 | every expiring row is gone by `expires_at` + purge interval, including after a restore | by the purge job, which counts what it purged and alarms on what it found late |
| FACT-INV06 | every telemetry add is counted in exactly one window, or its window is flagged `stalled` | by construction (§3.3-§3.4); by a property test that compares adds with written sums |
| FACT-INV07 | every fact's type is registered with the schema version it was written with | at insert (the writer refuses an unregistered type) |
| FACT-INV08 | effects of one projector become `DONE` in (fact id, effect index) order, and no application id is executed with two payloads | by the dispatcher; at startup |
| FACT-INV09 | every capture taken before a committed frontier is in the log or covered by a gap record at or before that frontier's batch | by construction (§3.3); by the capture property test |

A violation stops the projector or freezes the module, alarms and keeps the evidence; it never rewrites a fact.

## 7. Failure model

| Fault | End state |
|---|---|
| ring full | the capture is refused and counted; at the phase's close a `QUEUE_FULL` gap record enters the log; the game is not slowed |
| store down | staging fails with its transaction (the module's operation aborts; financial operations pause, 04 §8); the writer retries its batch; the ring fills and refusals become gap records when the store returns |
| deadlock or lock-wait timeout between Living Chronicle transactions | prevented by the row-lock order; a victim is retried, then fails (§3.1) |
| relay or batch commit uncertain | resolved by a locking read (staged rows gone; batch row present) before anything else: never written twice |
| a producer stuck inside a phase | a `STALLED` gap after `PhaseDrainMillis`; the writer goes on |
| JVM killed | staged outbox facts survive and are relayed after restart; the ring's captures are lost; a `RESTART` gap enters the log; projectors stop at it until acknowledged |
| projector reducer throws | that projector stops; gameplay and other projectors go on |
| crash between a projector's checkpoint and an effect's execution | the effect is `PENDING`, executed after restart once, its idempotence asked first |
| replay | an isolated store is rebuilt from a usable snapshot; no effect is written |
| restore from backup | expiring rows past their expiry are deleted before the server starts |

## 8. Configuration and access

`config/Custom/LivingChronicle/Journal.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CaptureRingSlots` | 1024–1048576 | 65536 | 65536 everywhere | AT-FACT-04 |
| `WriterBatchMax` | 1–2000 | 500 | 500 everywhere | AT-FACT-05 |
| `RelayMillis` | 10–1000 | 50 | 50 everywhere | AT-FACT-01 |
| `WriterBatchBudgetMillis` | 10–5000 | 100 | 100 everywhere | AT-FACT-01 |
| `ReplayLeaseSeconds` | 10–3600 | 120 | 120 everywhere | AT-FACT-17 |
| `HeartbeatMillis` | 100–10000 | 1000 | 1000 everywhere | AT-FACT-15 |
| `PhaseDrainMillis` | 10–10000 | 500 | 500 everywhere | AT-FACT-06 |
| `LockWaitMillis` | 50–10000 | 200 | 200 everywhere | AT-FACT-02 |
| `DeadlockRetries` | 0–10 | 3 | 3 everywhere | AT-FACT-02 |
| `TelemetryWindowSeconds` | 10–3600 | 60 | 60 / 60 / 10 / 60 / 60 | AT-FACT-06 |
| `MaxParticipantsPerFact` | 10–1000 | 200 | 200 everywhere | AT-FACT-03 |
| `BulkCaptureMicros` | 10–10000 | 100 | 100 everywhere | AT-FACT-03 |
| `PreciseRetentionHours` | 1–720 | 72 | 72 everywhere | AT-FACT-08 |
| `ExpiringRetentionHours` | 1–8760 | 720 | 720 everywhere | AT-FACT-08 |
| `PurgeIntervalMinutes` | 1–1440 | 15 | 15 everywhere | AT-FACT-08 |
| `EffectAttempts` | 1–100 | 10 | 10 everywhere | AT-FACT-16 |

Database users: the game server may `INSERT` and `SELECT` `lc_fact` (no `UPDATE`, no `DELETE`), may insert and the writer
may delete `lc_fact_outbox`, and may read neither expiring table; the Intel Service and forensics read the expiring tables;
the maintenance job alone deletes expiring rows, drops partitions and retires snapshots; publication reads only `lc_pub_*`
(04 §4).

## 9. Interface

None for players. Admin: `lc why <fact>` (04 §6) prints a fact with its log neighbours, its producing operation and the
projectors that applied it; `lc journal gaps`; `lc journal ack <gap> <reason>`; `lc journal effects`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| FACT-R01 | A durable fact recording a state change MUST be staged in the same transaction as the change and relayed into the log exactly once. | AT-FACT-01 |
| FACT-R02 | The log MUST have one order, assigned by one writer, the same live and on replay. | AT-FACT-02, AT-FACT-15 |
| FACT-R03 | A hot-path capture MUST take at most 2 µs at p99, never block, never do I/O and never allocate; a bulk fact MUST stay within `BulkCaptureMicros` with at most `MaxParticipantsPerFact` participants per fact. | AT-FACT-03 |
| FACT-R04 | A capture the journal cannot take, or that fails after claiming its slot, MUST be counted and become a gap record committed before any fact of its phase. | AT-FACT-04, AT-FACT-06 |
| FACT-R05 | A batch MUST commit once, with the selection and id it was given until resolved; a crash MUST leave a `RESTART` gap in the log; a dead writer MUST be restarted and resolve its batch first. | AT-FACT-05, AT-FACT-18 |
| FACT-R06 | Telemetry MUST be aggregated in memory, each add counted in exactly one window (or the window flagged stalled), written once per window and cell. | AT-FACT-06 |
| FACT-R07 | Exact coordinates and expiring data MUST NOT be stored in `lc_fact`, and MUST be readable only by the Intel Service and forensics. | AT-FACT-07 |
| FACT-R08 | Expiring rows MUST be deleted at expiry, in backups by bounded backup life and at restore. | AT-FACT-08 |
| FACT-R09 | A projector MUST be deterministic, apply facts in log order, and checkpoint in the transaction of its derived writes and effect intents. | AT-FACT-09 |
| FACT-R10 | A projector MUST stop at a gap record of a family it consumes and at an unclean epoch boundary, and freeze its module's effects until acknowledged. | AT-FACT-10 |
| FACT-R11 | Replay MUST rebuild an isolated store from a usable snapshot and MUST NOT write any effect. | AT-FACT-11 |
| FACT-R12 | Every fact type MUST be registered and versioned; stored facts MUST never be updated or deleted except by dropping a partition under §4.5. | AT-FACT-12 |
| FACT-R13 | Capture MUST copy primitives at the owning operation, read locations coherently or flag them torn, and hold no reference to a live game object. | AT-FACT-03, AT-FACT-13 |
| FACT-R14 | Projector lag, as §4.6 defines it, MUST stay within 5 s at p99 under the 800-citizen siege benchmark. | AT-FACT-14 |
| FACT-R15 | A decision on the absence of a captured fact MUST wait for the frontier to pass it and fail closed on an overlapping gap. | AT-FACT-15 |
| FACT-R16 | Every effect a projector decides MUST be executed exactly once, in order, through the effect outbox, with actuators idempotent by application id. | AT-FACT-16 |
| FACT-R17 | Living Chronicle transactions MUST take database row locks in the global order of §3.1, with a bounded lock wait. | AT-FACT-02 |

| Test | Kind | What it does |
|---|---|---|
| AT-FACT-00 | integration | the journal's migration applies; monthly partitions start exactly at midnight UTC on the first of each month, contiguous, ending in a catch-all; keys and checks reject what this spec forbids |
| AT-FACT-01 | integration, fault | a staged fact and its change commit together or not at all, under faults at each statement; relaying, with its commit cut both ways, a lower `outbox_id` committing late, and a relay backlog, and a row staged just after a relay selected its batch, puts each staged fact into the log exactly once, in stream order, within the latency bound of §3.3 for its backlog |
| AT-FACT-02 | integration | Asset operations over several characters, custodies, items, mail and streams, concurrently, with outbox writers to shared streams: no deadlock outside the retry path, no wait past `LockWaitMillis`; the log's ids grow in commit order |
| AT-FACT-03 | unit | hot-path capture benchmark at full load, phase admission and telemetry included: p99 within 2 µs, zero allocation (first use of every family and every thread included), never blocks; bulk facts within budget and chunked at the cap; locations read during movement, teleport and two concurrent writers are coherent or flagged torn |
| AT-FACT-04 | integration | a full ring: refused captures counted per family, and the gap record is in the log at or before the batch whose frontier passes them; game threads unaffected |
| AT-FACT-05 | fault, restart | the writer killed mid-batch, a batch commit cut after it was sent (committed and not), the JVM killed with a full ring: no batch twice; a `RESTART` gap |
| AT-FACT-06 | unit | captures and telemetry under concurrent producers across many phase changes and ring wraps, with a producer admitted just as its phase closes, one throwing between claim and publish, and one stuck after claiming a slot then resuming after its phase was written: every capture in the log or a gap of its phase; every add in exactly one window or the window flagged stalled; a retired buffer never read again; the writer never blocks the relay |
| AT-FACT-07 | integration | every registered type has no coordinate or expiring field; the game server's user cannot read the expiring tables |
| AT-FACT-08 | integration | expiring rows deleted on time; a restore deletes rows already expired before the server starts; backups of the expiring tables older than their life are refused |
| AT-FACT-09 | integration | a projector run twice, and with a crash between derived write and checkpoint: identical derived state and intents |
| AT-FACT-10 | integration | a gap record and an unclean epoch boundary each stop a projector and freeze its effects; acknowledgement resumes it |
| AT-FACT-11 | integration | replay into an isolated schema after partitions were dropped under §4.5: equal derived state from the newest usable snapshot; a snapshot below its families' highest dropped id refused; a projector of other families keeps its snapshots; maintenance interrupted between watermark and drop leaves replay correct; no effect written |
| AT-FACT-12 | unit, integration | an upcaster chain reads every stored version; an unregistered type is refused; the game server's user cannot update or delete `lc_fact` |
| AT-FACT-13 | unit | every capture record holds only primitives and immutable snapshots (reflection over the sealed hierarchy) |
| AT-FACT-14 | sim | 800-citizen siege benchmark: lag p99 within 5 s; a caught-up projector reports zero |
| AT-FACT-15 | integration | a projector combining captured and outbox facts, live and replayed: the same order; a phase's gap applied before its facts; an absence decided before the frontier passed is refused; a gap overlapping it fails closed; a configuration change relayed after a fact produced under it: the fact read under its own version |
| AT-FACT-16 | fault, restart | crash between checkpoint and execution, an Asset effect refunded twice then committed, an actuator crashing after acting before `DONE`, a world object it spawned gone before `DONE` is recorded, two projectors deciding effect 0 of one fact, effects out of order on restart: each executed once, in order; `FAILED` freezes the module |
| AT-FACT-18 | fault | the writer thread killed while the JVM lives, with a batch unresolved: the supervisor restarts it, the batch is resolved first, the relay resumes |
| AT-FACT-17 | integration | the maintenance job: partitions two months ahead after a run; a catch-all row alarms; a partition with an unretired dependency is kept; a replay paused mid-way keeps the partition it still needs; a replay paused between choosing its snapshot and registering its lease while maintenance runs: either maintenance sees the lease or the replay chooses against the new watermark; a replay whose lease went stale finds it invalid at its next batch and restarts, never reading past a dropped partition |

## 11. Upstream constraints

1. **Event payloads are live** (`gameserver/mechanics/events/holders/actor/player/inventory/OnPlayerItemTransfer.java:30-32`,
   `gameserver/mechanics/events/holders/actor/npc/attackable/OnAttackableKill.java:30-32`; item events dispatched async
   from `PlayerInventory.java:464-466, 518-520, 554-556, 600-602, 709-711, 742-744`; a kill event is delivered after a
   delay, `Attackable.java:328`). → capture copies primitives (§3.2).
2. **Async notification uses an unbounded queue** (`commons/threads/ThreadPool.java:86`). → the journal's own bounded
   queue and writer (§3.2).
3. **No transactions anywhere in Mobius**; HikariCP pool of 100 (`dist/game/config/Database.ini:21`); batches exist
   (`Player.java:7763-7766`). → the writer batches inside one transaction per batch.
4. **No partitioning in the stock schema**; MariaDB 11.8.9 (toolchain.lock). → `lc_fact` is the first partitioned table,
   kept ahead by the maintenance job (§4.5).
5. **Mobius's durable logs are files** (`dist/game/log.cfg:15-34`): nothing records game events in the database.
6. **Hot paths run on several pools** (packets, `commons/network/packet/PacketExecutor.java:52`; hits and casts on the
   scheduled pool; movement on the high-priority pool). → capture is lock-free (§3.2, §3.3).
7. **Locations tear**: five independently written volatile fields (`entity/Location.java:36-40`), written one after
   another (`WorldObject.java:760-762`), possibly by more than one thread. Core patch: a version on `Location` that a
   writer takes from even to odd by compare-and-set, so writers exclude one another and readers see whole writes (§3.2).
8. **Map regions are sparse `locId`s** (`MapRegionData.java:350`; e.g. 2189). → a dense index fixed at startup and
   recorded (§2.3).

## 12. Review record

- Amendment round 2 (Codex), NO SIGN-OFF: a replay could pass a window's marker yet need the window later for a baseline.
  Fixed: a projector reads a window once and keeps what it needs later in its derived state and snapshots.
- Amendment round 1 (Codex) on revision 9, NO SIGN-OFF: the window marker could be lost in a crash after the rows
  committed, and telemetry buckets had no retention protection. Fixed: rows and marker in one transaction (the marker an
  outbox fact); buckets protected like partitions by checkpoints, snapshots and leases.
- Revision 9 (after the revision-8 sign-off): telemetry families declare their region dimension and may be registered as
  a module's id range; the writer records `Journal.WindowWritten` after a window's rows commit. Accepted in
  aden-remembers.md's review; this revision is to be reviewed on its own.

- Round 8 (Codex), **SIGN-OFF**. Remaining are named implementation obligations: allocation behaviour, memory ordering,
  slot retirement, actuator reconstruction, supervisor recovery and measured latency, each with its acceptance test.
- Round 7 (Codex), NO SIGN-OFF: one open, the lease scan ran before maintenance held the retention rows, so a lease
  inserted during the scan escaped it. Revision 8: maintenance takes the retention rows exclusively first and holds them
  through the watermark update, then scans the leases.
- Round 6 (Codex), NO SIGN-OFF: two open. The latency bound missed a batch already in progress, whose selection could
  not include a fact staged during it; and replay admission was not coordinated with the watermark. Revision 7: the
  in-progress batch and its following phase batch counted; admission serialised with maintenance through the retention
  rows, and every read batch re-checks the watermarks.
- Round 5 (Codex), NO SIGN-OFF: two open. The backlog bound ignored the batches committed before a fact's own; and a
  replay already running could resume past a partition dropped under it. Revision 6: latency counted in batches of a
  measured longest transaction, relay and phase batches interleaved; replay leases checked under a shared lock at each
  read batch, keeping partitions they still need, and invalidated only when stale, which aborts their replay.
- Round 4 (Codex), NO SIGN-OFF: most of round 3 fixed; open were a global deletion watermark retiring an unrelated
  projector's snapshot, a watermark advanced after the drop (an interruption leaving deleted history behind it), cadences
  presented as deadlines, and ring slots of a stalled producer. Revision 5: watermarks per family and usability per
  projector; watermark before drop; latency bounds stated with their conditions, separate from cadences; claimed slots of
  a stalled phase retired with generation checks.
- Round 3 (Codex), NO SIGN-OFF: confirmed the single log (no inversion of capture numbers, outbox frontiers gone,
  timestamps as data), effect order and refunded attempts, the row-lock order, the location version; found phase
  admission racing a closing phase, a capture failing after its slot claim, abandoned buffers written late, gaps
  committed after facts of their own phase, unspecified relay polling and uncertain-relay recovery, configuration bound
  by log position rather than by producer, lag blind to the relay, no supervisor for the writer, world actuators judged by
  presence, snapshots "usable" across deleted history, and application ids colliding across projectors. Revision 4:
  admission with a re-check, claim-fill-publish with void slots, retired storage, one batch per closed phase with its
  gaps first, a specified relay (no watermark, oldest first, selection kept until resolved), `config_ver` stamped by the
  producer, lag from `captured_at`, a supervised non-blocking writer, actuators as database state with world objects as
  projections, usability against the highest dropped id, and the projector in every application id.
- Round 2 (Codex), NO SIGN-OFF: 3 round-one findings fixed, most others partly; 14 new, showing that numbering captures
  per stream cannot be made sound (a number taken then lost to an exception; numbers offered out of order; frontiers that
  cannot be computed across in-flight captures; outbox frontier registration too late; timestamp order against sequence
  order), plus effect ordering and refunded attempts, actuator idempotence, a stranded telemetry counter, an incomplete
  row-lock order, replay snapshots chosen by age rather than coverage, and a location version that did not exclude
  writers. Revision 3 replaces streams-with-frontiers by **one log with one sequencer**: outbox facts staged in the
  module's transaction and relayed by the writer, captures drained by the same writer, so log order is the only order;
  **capture phases** (striped in-flight counters closed by the writer) make every capture accounted for at each frontier
  and close telemetry windows, with a bounded drain and a `STALLED` gap; effects dispatched per projector in order, with
  a new attempt id after a refunded Asset attempt and actuators idempotent by application id; a complete row-lock order
  with lock-wait budget and retries; snapshots chosen by the log position they cover; and a location version that
  writers take by compare-and-set.

- Round 1 (Codex, different model family), NO SIGN-OFF, 16 findings. Revision 2: two kinds of stream that never mix
  (outbox, ordered under the head lock; captured, numbered at capture), so the batch writer takes no outbox head and a
  refused capture is a hole exactly where it happened; a global row-lock order with heads last and deadlock retries;
  batch identity and a locking read for uncertain batch commits; epochs with clean ends, heartbeats and frontiers; bulk
  facts chunked with their own budget; coherent locations through a version counter (core patch); telemetry adds
  counted in exactly one window by in-flight counters; `region_id` as a dense index of `locId`; expiring data only in
  side tables, with bounded backup life and purge at restore, and `lc_fact` insert-only for the game server; partition
  retention gated on checkpoints and replay snapshots; a total cross-stream order behind frontiers, with configuration
  versions bound at each fact; an effect outbox for exactly-once effects; lag measured from commit, zero when caught up;
  the acceptance cases the review found missing.
