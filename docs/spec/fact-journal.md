# SPEC-FACT — Fact Journal

- Status: draft (revision 1)
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
- **telemetry**: high-volume ecological signals (kills, yields, travel, presence) aggregated in memory per window and
  flushed as buckets, never one row per event (04 §1.1);
- **precise locations**: the exact coordinates some facts need, kept apart, readable only by the Intel Service and
  forensics, purged when they expire;
- **capture**: how the core seam and modules hand facts to the journal without slowing the game;
- **the projector runtime**: the contract every derived store (Aden Remembers, Signal, Wayfarer, PACT history, Black
  Ledger, Chronicle) is built on: deterministic, checkpointed, idempotent, replayable.

It does not own: what each module's facts mean (each module spec lists the facts it emits and consumes; this spec only
registers them, §2.4), what a player may learn from them (Intel Service), what is published (Chronicle's `lc_pub_*`),
nor actor identity (Actor Identity spec; a fact carries its snapshot).

## 2. Data

### 2.1 Tables

```text
lc_fact(id BIGINT auto, occurred_at TIMESTAMP(3), stream VARCHAR(48), stream_seq BIGINT, type SMALLINT, schema_ver SMALLINT,
        logical_time BIGINT, actor_snapshot JSON, region_id SMALLINT, zone_id INT NULL, instance_id INT, sensitivity TINYINT,
        expires_at TIMESTAMP(3) NULL, payload JSON, op_id BINARY(16) NULL, PRIMARY KEY(id, occurred_at))
        PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)), one partition per month
lc_stream_head(stream VARCHAR(48) PK, last_seq BIGINT, last_fact_id BIGINT)
lc_fact_precise(fact_id BIGINT PK, x INT, y INT, z INT, instance_id INT, expires_at TIMESTAMP(3))
lc_journal_gap(stream_or_type VARCHAR(48), from_at TIMESTAMP(3), to_at TIMESTAMP(3), refused BIGINT, reason {QUEUE_FULL|STORE_DOWN|RESTART},
               PRIMARY KEY(stream_or_type, from_at))
lc_telemetry_bucket(window_start TIMESTAMP, window_len INT, region_id SMALLINT, family SMALLINT, actor_class TINYINT,
                    events BIGINT UNSIGNED, sum_a BIGINT, sum_b BIGINT, unique_actors INT UNSIGNED,
                    PRIMARY KEY(window_start, region_id, family, actor_class))
lc_projector_checkpoint(projector VARCHAR(48), stream VARCHAR(48), last_seq BIGINT, reducer_ver SMALLINT, PRIMARY KEY(projector, stream))
lc_fact_type(type SMALLINT PK, name VARCHAR(64) UNIQUE, schema_ver SMALLINT, durability {DURABLE|TELEMETRY}, delivery {OUTBOX|CAPTURED},
             sensitivity TINYINT, retention_days INT NULL, owner_module VARCHAR(16))
```

- `lc_fact` is partitioned by month (04 §1.2). MariaDB requires every unique key of a partitioned table to include the
  partition column, so `(stream, stream_seq)` cannot be a unique key there: uniqueness and contiguity of a stream's
  sequence come from `lc_stream_head`, whose row is locked and advanced in the same transaction that inserts the facts
  (§3.1), and are checked by every projector as it reads (INV02).
- A **stream** is the unit of order: everything whose relative order matters is in one stream. Streams are named by
  their owner: `asset.custody.<id>`, `pact.<contract>`, `ledger.<contract>`, `region.<id>`, `castle.<id>`, `system`. A
  stream has one writer at a time: the transaction that holds its head row.
- `logical_time` is the game clock at the fact (`GameClock.millis`, `gameserver/livingchronicle/clock/GameClock.java:35`),
  so that a run under a controlled clock records the time the simulation saw.
- `lc_fact_precise` holds what `lc_fact` must never hold: exact coordinates (04 §1.2). It is read only by the Intel
  Service and by forensics, under a separate database user (§8), and its rows are deleted at `expires_at`.
- `lc_journal_gap` records every capture the journal refused or lost, so a gap is never silent (consensus §5.2).
- `lc_fact_type` is the registry of every fact type every module emits (§2.4).

### 2.2 Region, zone, instance

`region_id` is the town map region of the fact's place (`MapRegionData.getMapRegion`, an O(1) lookup in a 32 × 32 grid
with 32768-unit cells, `gameserver/data/xml/MapRegionData.java:330`, :80-83, :116). Aden Remembers maps these to its own
regions in its projector. `zone_id` is set only by facts whose meaning is a zone (entering a siege zone, a castle's
territory), never looked up on a hot path (`ZoneManager.getZones` allocates and iterates, `gameserver/managers/ZoneManager.java:647`).
`instance_id` is `WorldObject.getInstanceId()` (`gameserver/entity/WorldObject.java:729`).

### 2.3 Telemetry

A telemetry family (kills, yield, travel, presence, deaths, teleports, …) is a fixed set of counters per
(window, region, family, actor class): `events`, two family-defined sums (`sum_a`, `sum_b`: e.g. kills and experience; drops
and Adena) and an approximate count of distinct actors. The current window lives in preallocated arrays indexed by
(region, family, actor class), updated with atomic adds; at the window's end the writer swaps them for a fresh set and
writes one row per non-empty cell. Telemetry is never replayed exactly: it is the ecology's input, not a record.

### 2.4 Fact types

Every fact type is a Java record implementing a sealed interface, registered in `lc_fact_type` with its id, name, schema
version, durability, delivery, sensitivity, retention and owner module. Adding a type, or bumping its schema version, is a
change to the owner's spec and to this registry. A schema bump is additive: older versions are read through an explicit
upcaster to the newest, and the stored row is never rewritten. The payload is canonical JSON (sorted keys, no floats:
quantities are integers, fractions are integers with a stated scale), so that equal facts are equal bytes.

## 3. Capture

### 3.1 Outbox facts

A fact whose meaning depends on a state change (an escrow funded, a contract signed, a payout) is written in the
transaction that makes the change: the caller locks its stream's head row (`SELECT … FOR UPDATE` on `lc_stream_head`),
takes the next sequence numbers, inserts the facts and advances the head, all before its `COMMIT`. Outbox facts are
exactly-once: they exist exactly when their change does. The Asset Service writes its facts this way (asset-service.md
§5).

### 3.2 Captured facts

A fact that records something Mobius did on its own (a castle changing owner, a siege ending, a player killing another)
is captured where Mobius does it, by a core-seam call that copies primitives (ids, levels, counts, coordinates, the actor
snapshot's fields) into a preallocated record and offers it to the journal's bounded queue, without blocking, without I/O
and without allocating on the hot path (02 §6; 03 §4.4). Mobius's own event objects are not used for capture: they hold
live, mutable `Player`, `Item` and `Location` references (`gameserver/mechanics/events/holders/actor/player/inventory/OnPlayerItemTransfer.java:30-32`;
`WorldObject.getLocation` returns the live field, `gameserver/entity/WorldObject.java:748-751`), and async listeners run
later on a pool with an unbounded queue (`EventDispatcher.notifyEventAsync`, `gameserver/mechanics/events/EventDispatcher.java:159`;
`commons/threads/ThreadPool.java:86`), so what they show is not what happened.

- **One writer thread** drains the queue in batches, one transaction per batch, taking each stream's head row as §3.1
  does. Mobius's file logger is the precedent: a daemon writer behind a bounded queue whose `offer` never blocks and
  counts what it refuses (`log/handler/AsyncFileHandler.java:47, 87-89, 125-129`).
- **A full queue refuses the capture** and counts it per fact type; the writer records the refusals as an
  `lc_journal_gap` row (`QUEUE_FULL`) for the window in which they happened. **A store that is down** makes the writer
  retry with backoff while the queue fills; what is then refused is recorded as `STORE_DOWN` when the store returns.
- **Captured facts are at most once across a crash**: what was in the queue when the JVM died is gone. At startup the
  journal records a `RESTART` gap for every captured stream from its last written fact to the restart. A module that needs
  a fact exactly once must make it an outbox fact in its own transaction (§3.1); the module spec says which it uses.

### 3.3 Telemetry capture

`Telemetry.add(family, region, actorClass, a, b, actorId)`: three array index computations and atomic adds into the current
window, no queue, no allocation. The distinct-actor count uses a fixed-size per-cell sketch.

### 3.4 Where capture happens

The core seam (blueprint 03 §4.4) adds capture calls at the owning operations. The natural points, and the thread each
runs on, as Mobius stands:

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

Movement, damage and region changes are telemetry only; deaths, kills and logins are telemetry, and also captured
durable facts where a module's spec needs them (a PvP kill for the Black Ledger, a boss kill for the Chronicle).

## 4. The projector runtime

A **projector** turns facts into one module's derived state. Its contract:

1. **Deterministic**: the same facts in the same order, with the same reducer version and configuration version, give the
   same derived state. Randomness a reducer needs comes from the fact (recorded outcomes) or from a keyed stream
   (04 §7) seeded by the fact's id; time is the fact's `logical_time`, never the wall clock.
2. **Ordered per stream**: facts of one stream are applied in `stream_seq` order; a projector that combines streams says
   how (a watermark on `logical_time`), and is deterministic given the facts it saw.
3. **Checkpointed and idempotent**: a projector writes its derived rows and its `lc_projector_checkpoint` row in one
   transaction; applying a fact at or below the checkpoint does nothing.
4. **Gap-aware**: a projector whose input stream has a gap (`lc_journal_gap`, or a sequence that jumps, INV02) stops at
   the gap's start, records its watermark, and its owner module's actuators freeze (04 §8: the Director freezes; Black
   Ledger pays nothing) until an admin acknowledges the gap with a reason.
5. **Replayable**: replay applies the stored facts to an isolated store (its own schema), from zero or from a snapshot,
   and never re-applies an actuator or a transfer: every actuator and transfer a projector causes carries an idempotent
   application id (04 §1.3). Live Mobius state is not replayed (consensus §5.3).
6. **Measured**: lag (now − `logical_time` of the last applied fact) is a metric with the budget of 02 §6.

## 5. Facts of the journal itself

| Fact | Delivery | Fields |
|---|---|---|
| `Journal.Gap` | outbox (the writer's own) | stream or type, from, to, refused count, reason |
| `Journal.GapAcknowledged` | outbox | gap, admin, reason |
| `Journal.ProjectorStopped` | outbox | projector, stream, watermark, cause |
| `Journal.Purged` | outbox | table, before, rows (precise rows and expired payload fields) |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| FACT-INV01 | `lc_fact` never holds exact coordinates: no payload field of a registered type is a coordinate | by the type registry at startup (schema check of every record) and a property test over every type |
| FACT-INV02 | per stream, `stream_seq` runs 1, 2, 3, … with no repeat; a missing number is covered by an `lc_journal_gap` | by every projector as it reads; by a full pass at startup |
| FACT-INV03 | an outbox fact exists if and only if the change it records committed (same transaction) | by construction; by the owner module's invariants (e.g. ASSET-INV01) |
| FACT-INV04 | no projector checkpoint is ahead of its stream's head | at every checkpoint write |
| FACT-INV05 | every `lc_fact_precise` row and every expiring payload field is gone by its `expires_at` + purge interval | by the purge job, which counts what it purged and alarms on what it found late |
| FACT-INV06 | a telemetry window is written once | by its primary key |
| FACT-INV07 | every fact's type is registered with the schema version it was written with | at insert (the writer refuses an unregistered type) |

A violation writes `Journal.Gap` or stops the projector (§4), alarms, and keeps the evidence; it never rewrites a fact.

## 7. Failure model

| Fault | End state |
|---|---|
| queue full | the capture is refused and counted; a `QUEUE_FULL` gap is recorded; the game is not slowed |
| store down | outbox writes fail with their transaction (the module's operation aborts: financial operations pause, 04 §8); the writer retries; refusals become a `STORE_DOWN` gap |
| writer thread dies | restarted by its supervisor; what it held in its batch is re-offered (the batch is committed or not, as one transaction) |
| JVM killed | outbox facts as committed; captured facts in the queue are lost and recorded as a `RESTART` gap at startup |
| projector reducer throws | that projector stops with its watermark (`Journal.ProjectorStopped`); gameplay and other projectors go on (04 §8) |
| replay | an isolated store is rebuilt; no actuator or transfer is re-applied |

## 8. Configuration and access

`config/Custom/LivingChronicle/Journal.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CaptureQueueCapacity` | 1024–1048576 | 65536 | 65536 everywhere | AT-FACT-04 |
| `WriterBatchMax` | 1–10000 | 500 | 500 everywhere | AT-FACT-05 |
| `TelemetryWindowSeconds` | 10–3600 | 60 | 60 / 60 / 10 / 60 / 60 | AT-FACT-06 |
| `PreciseRetentionHours` | 1–720 | 72 | 72 everywhere | AT-FACT-08 |
| `PurgeIntervalMinutes` | 1–1440 | 15 | 15 everywhere | AT-FACT-08 |

Database users: the game server writes `lc_fact*` and reads all but `lc_fact_precise`; the Intel Service and forensics
read `lc_fact_precise`; publication reads only `lc_pub_*` (04 §4).

## 9. Interface

None for players. Admin: `lc why <fact>` (04 §6) prints a fact with its stream neighbours, its producing operation, and
the projectors that applied it; `lc journal gaps` lists unacknowledged gaps; `lc journal ack <gap> <reason>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| FACT-R01 | A durable fact recording a state change MUST be written in the same transaction as the change. | AT-FACT-01 |
| FACT-R02 | Within a stream, sequence numbers MUST be contiguous and unique, and every missing number MUST be covered by a recorded gap. | AT-FACT-02 |
| FACT-R03 | A capture call MUST take at most 2 µs at p99, never block, never do I/O and never allocate on the hot path. | AT-FACT-03 |
| FACT-R04 | A capture the journal cannot take MUST be counted and recorded as a gap, never dropped silently. | AT-FACT-04 |
| FACT-R05 | Captured facts MUST be written in batches, each one transaction; a crash MUST leave every captured stream with a recorded `RESTART` gap. | AT-FACT-05 |
| FACT-R06 | Telemetry MUST be aggregated in memory per window and written once per window and cell, never one row per event. | AT-FACT-06 |
| FACT-R07 | Exact coordinates MUST NOT be stored in `lc_fact`, and MUST be readable only by the Intel Service and forensics. | AT-FACT-07 |
| FACT-R08 | Precise rows and expiring payload fields MUST be purged at expiry, backups and replays included. | AT-FACT-08 |
| FACT-R09 | A projector MUST be deterministic, checkpointed in the transaction of its derived writes, and idempotent. | AT-FACT-09 |
| FACT-R10 | A projector MUST stop at a gap in its input and freeze its module's actuators until the gap is acknowledged. | AT-FACT-10 |
| FACT-R11 | Replay MUST rebuild an isolated store and MUST NOT re-apply any actuator or transfer. | AT-FACT-11 |
| FACT-R12 | Every fact type MUST be registered, versioned, and read through upcasters; stored facts MUST never be rewritten. | AT-FACT-12 |
| FACT-R13 | Capture MUST copy primitives at the owning operation; no fact MUST hold a reference to a live game object. | AT-FACT-03, AT-FACT-13 |
| FACT-R14 | Projector lag MUST stay within 5 s at p99 under the 800-citizen siege benchmark. | AT-FACT-14 |

| Test | Kind | What it does |
|---|---|---|
| AT-FACT-00 | integration | the journal's migration applies; partitions exist for the current and next months; keys and checks reject what this spec forbids |
| AT-FACT-01 | integration, fault | an outbox fact and its change commit together or not at all, under faults at each statement |
| AT-FACT-02 | integration | concurrent writers to one stream: contiguous sequences; a forced jump is detected by a projector and the startup pass |
| AT-FACT-03 | unit | capture benchmark at full load: p99 within 2 µs, zero allocation (allocation counter), never blocks |
| AT-FACT-04 | integration | a full queue: refused captures counted and recorded as a `QUEUE_FULL` gap; game threads unaffected |
| AT-FACT-05 | fault, restart | writer killed mid-batch and JVM killed with a full queue: batches all or nothing; `RESTART` gaps recorded |
| AT-FACT-06 | unit | telemetry over many windows: one row per non-empty cell and window; sums exact |
| AT-FACT-07 | integration | every registered type has no coordinate field; the game server's database user cannot read `lc_fact_precise` |
| AT-FACT-08 | integration | precise rows and expiring fields purged on time; a restored backup and a replay purge them too |
| AT-FACT-09 | integration | a projector run twice, and run with a crash between derived write and checkpoint: identical derived state |
| AT-FACT-10 | integration | a gap in a projector's input: it stops at the gap, its module's actuators freeze, resume after acknowledgement |
| AT-FACT-11 | integration | replay into an isolated schema: equal derived state; no actuator or transfer applied |
| AT-FACT-12 | unit | an upcaster chain reads every stored version; an unregistered type is refused |
| AT-FACT-13 | unit | every capture record holds only primitives and immutable snapshots (reflection over the sealed hierarchy) |
| AT-FACT-14 | sim | 800-citizen siege benchmark: projector lag p99 within 5 s |

## 11. Upstream constraints

1. **Event payloads are live** (`gameserver/mechanics/events/holders/actor/player/inventory/OnPlayerItemTransfer.java:30-32`,
   `gameserver/mechanics/events/holders/actor/npc/attackable/OnAttackableKill.java:30-32`; item events dispatched
   async from `PlayerInventory.java:464-466, 518-520, 554-556, 600-602, 709-711, 742-744`; a kill event is delivered after
   a delay, `Attackable.java:328`). → capture copies primitives (§3.2).
2. **Async notification uses an unbounded queue** (`ThreadPool.execute`, `commons/threads/ThreadPool.java:86`). → the
   journal has its own bounded queue and writer (§3.2).
3. **No transactions anywhere in Mobius** (no `setAutoCommit`, `commit` or `rollback`); HikariCP pool of 100
   (`dist/game/config/Database.ini:21`); batches exist (`Player.java:7763-7766`), without `rewriteBatchedStatements`. → the
   writer batches inside one transaction per batch.
4. **No partitioning anywhere in the stock schema**; MariaDB 11.8.9 (toolchain.lock). → `lc_fact` is the first
   partitioned table; partitions are added ahead by the purge job.
5. **Mobius's durable logs are files** (`dist/game/log.cfg:15-34`; item log `ItemManager.java:125-189`, off by default):
   nothing in Mobius records game events in the database. → the journal is new, not a replacement.
6. **Hot paths run on several pools** (packets, `PacketExecutor.java:52`; hits and casts on the scheduled pool; movement
   on the high-priority pool). → capture is thread-safe and lock-free (§3.2, §3.3).

## 12. Review record

None yet.
