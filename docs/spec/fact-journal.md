# SPEC-FACT — Fact Journal

- Status: draft (revision 2, answering the first adversarial review; record in §12)
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

### 2.1 Two kinds of stream

A **stream** is the unit of order. Every stream is of exactly one kind, fixed by its name:

| Kind | Name | Order assigned | Writer | Delivery |
|---|---|---|---|---|
| **outbox** | `asset.custody.<id>`, `pact.<contract>`, `ledger.<contract>`, `castle.<id>`, `config`, … | in the transaction that records the change, under the stream's head row lock (§3.1) | the module's own transaction | exactly once |
| **captured** | `cap.<family>` or `cap.<family>.<key>` (e.g. `cap.kill.<region>`, `cap.pvp`, `cap.boss`) | at capture, by an atomic counter, before the fact is queued (§3.2) | the journal's single batch writer | at most once; every loss visible as a hole |

An outbox stream never holds captured facts and a captured stream never holds outbox facts, so the two writers never
compete for a stream, and order within a stream is the order of its one writer. Order *across* streams is defined in
§4.2.

### 2.2 Tables

```text
lc_fact(id BIGINT auto, occurred_at TIMESTAMP(3), stream VARCHAR(48), epoch INT, stream_seq BIGINT, type SMALLINT,
        schema_ver SMALLINT, logical_time BIGINT, actor_snapshot JSON, region_id SMALLINT, zone_id INT NULL, instance_id INT,
        sensitivity TINYINT, payload JSON, op_id BINARY(16) NULL, batch_id BIGINT NULL, PRIMARY KEY(id, occurred_at))
        PARTITION BY RANGE (UNIX_TIMESTAMP(occurred_at)), one partition per month and a catch-all
lc_stream_head(stream VARCHAR(48) PK, epoch INT, last_seq BIGINT, last_logical_time BIGINT, clean_end BOOLEAN)
lc_journal_epoch(epoch INT PK auto, started_at TIMESTAMP(3), ended_at TIMESTAMP(3) NULL, clean BOOLEAN)
lc_journal_batch(batch_id BIGINT PK, epoch INT, committed_at TIMESTAMP(3), facts INT, frontier_logical_time BIGINT)
lc_journal_gap(stream VARCHAR(48), epoch INT, from_seq BIGINT, to_seq BIGINT NULL, refused BIGINT NULL,
               reason {QUEUE_FULL|STORE_DOWN|RESTART}, acknowledged_by VARCHAR(32) NULL, acknowledged_reason VARCHAR(128) NULL,
               PRIMARY KEY(stream, epoch, from_seq))
lc_fact_expiring(fact_id BIGINT, field VARCHAR(32), value JSON, expires_at TIMESTAMP(3), PRIMARY KEY(fact_id, field))
lc_fact_precise(fact_id BIGINT PK, x INT, y INT, z INT, instance_id INT, expires_at TIMESTAMP(3))
lc_telemetry_bucket(window_start TIMESTAMP, window_len INT, region_id SMALLINT, family SMALLINT, actor_class TINYINT,
                    events BIGINT UNSIGNED, sum_a BIGINT, sum_b BIGINT, unique_actors INT UNSIGNED,
                    PRIMARY KEY(window_start, region_id, family, actor_class))
lc_projector_checkpoint(projector VARCHAR(48), stream VARCHAR(48), epoch INT, last_seq BIGINT, reducer_ver SMALLINT,
                        config_ver INT, PRIMARY KEY(projector, stream))
lc_effect_outbox(application_id BINARY(16) PK, projector VARCHAR(48), fact_id BIGINT, kind VARCHAR(32), payload JSON,
                 state {PENDING|DONE|FAILED}, attempts INT, created_at TIMESTAMP(3), done_at TIMESTAMP(3) NULL)
lc_config_version(config_ver INT PK auto, module VARCHAR(16), changed_at TIMESTAMP(3), content JSON)
lc_fact_type(type SMALLINT PK, name VARCHAR(64) UNIQUE, schema_ver SMALLINT, durability {DURABLE|TELEMETRY},
             delivery {OUTBOX|CAPTURED}, stream_family VARCHAR(32), sensitivity TINYINT, retention_days INT NULL,
             owner_module VARCHAR(16))
lc_replay_snapshot(projector VARCHAR(48), taken_at TIMESTAMP(3), checkpoint JSON, store_ref VARCHAR(128),
                   PRIMARY KEY(projector, taken_at))
```

- `lc_fact` is immutable: the game server's database user may `INSERT` and `SELECT` it and nothing else (§8); nothing
  that must ever be removed is written into it (§2.4). It is partitioned by month; MariaDB allows no unique key on a
  partitioned table without the partition column and no foreign keys at all, so uniqueness of `(stream, epoch,
  stream_seq)` comes from how sequences are assigned (§3.1, §3.2) and from batch identity (`lc_journal_batch`), and is
  checked by every projector as it reads (INV02).
- An **epoch** is one run of the capture side: it begins at startup (`lc_journal_epoch`) and ends cleanly only when the
  writer has drained the queue at shutdown and recorded `clean`. Captured sequences restart at 1 in each epoch. Outbox
  streams use epoch 0 forever.
- `logical_time` is the game clock at the fact (`GameClock.millis`, `gameserver/livingchronicle/clock/GameClock.java:35`).
- `lc_effect_outbox` is how a projector's decision becomes an effect exactly once (§4.4).
- `lc_config_version` records every configuration a reducer may read; a configuration change is an outbox fact on the
  `config` stream, so a replay reads the configuration that was in force at each fact (§4.1).

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
  Backups of the rest of the database contain no expiring data, so nothing else needs shredding.
- **Projectors** may read expiring data only into derived state that expires with it; replay is defined over `lc_fact`
  alone, so a replay after expiry gives the derived state the module would have without the expired data.

### 2.5 Fact types

Every fact type is a Java record implementing a sealed interface, registered in `lc_fact_type` with its id, name, schema
version, durability, delivery, stream family, sensitivity, retention and owner module; a captured type names the
stream family its facts go to. Adding a type, or bumping its schema version, is a change to the owner's spec and to this
registry. A schema bump is additive: older versions are read through an explicit upcaster to the newest; a stored row is
never rewritten. The payload is canonical JSON (sorted keys, integers only, fractions as integers with a stated scale).

### 2.6 Telemetry

A family (kills, yield, travel, presence, deaths, teleports, …) has fixed counters per (window, region, family, actor
class): `events`, two family-defined sums and a distinct-actor sketch (HyperLogLog registers, a fixed byte array per
cell). The current window is a buffer of preallocated arrays indexed by (region index, family, actor class).

## 3. Capture

### 3.1 Outbox facts

A fact whose meaning depends on a state change is written in the transaction that makes the change: the caller locks
its stream's head row, takes the next numbers, inserts the facts and advances the head before its `COMMIT`. Outbox facts
are exactly once: they exist exactly when their change does (INV03).

**Row-lock order.** Every Living Chronicle transaction takes its database row locks in one global order: `characters`
rows (by id), custody rows (by id), owner-module rows (by table, then id), stream heads (by stream name), always last.
Within that order there is no cycle. A transaction that InnoDB still picks as a deadlock victim got a definite rollback
before `COMMIT`: it retries its statements up to `DeadlockRetries` times, holding the in-memory locks it already holds,
and only then aborts (for an Asset operation: aborts and refunds, asset-service.md §3.2).

### 3.2 Captured facts

A fact that records something Mobius did on its own is captured where Mobius does it, by a core-seam call that copies
primitives into a fixed-size record (03 §4.4), **numbers it in its stream** with an atomic counter (the stream's next
sequence in the current epoch), and offers it to the journal's bounded queue without blocking. Mobius's own event
objects are not used: they hold live `Player`, `Item` and `Location` references
(`gameserver/mechanics/events/holders/actor/player/inventory/OnPlayerItemTransfer.java:30-32`; `WorldObject.getLocation`
returns the live field, `WorldObject.java:748-751`), and async listeners run later on a pool with an unbounded queue
(`gameserver/mechanics/events/EventDispatcher.java:159`; `commons/threads/ThreadPool.java:86`).

- **A refused capture leaves a hole.** Its number was taken; the fact was not queued; the counter of refusals for the
  stream goes up. A projector reading the stream sees the missing number at the exact place the loss happened and stops
  there (§4.3), whether or not the gap row has been written yet. The writer records the hole as an `lc_journal_gap` row
  (`QUEUE_FULL`, or `STORE_DOWN` when the store was down) as soon as it can.
- **One writer thread** drains the queue in batches. Each batch is one transaction that inserts its facts, advances the
  heads of its streams to their highest numbers, and inserts its `lc_journal_batch` row, whose id is
  (epoch, writer's own counter). If the commit is uncertain, the writer reads that batch id with a locking read before
  doing anything else: present means committed and the batch is dropped from memory; absent means the batch is retried as
  it was, same id. A batch is never re-offered with new numbers. Mobius's file logger is the precedent for a daemon writer
  behind a bounded queue whose `offer` never blocks and counts refusals (`log/handler/AsyncFileHandler.java:47, 87-89,
  125-129`).
- **Heartbeats.** Every `HeartbeatMillis` the writer commits a batch row even when it has no facts, carrying the
  **frontier**: the logical time up to which every capture taken has been written or recorded as a hole. Projectors use it
  to advance past an idle stream (§4.2).
- **A crash loses what was queued.** Its numbers are gone with it. At startup a new epoch begins; every captured stream
  whose head says its last epoch did not end cleanly gets a `RESTART` gap from its last committed number to the open end
  (`to_seq` and `refused` unknown, `NULL`). A stream whose first fact of that epoch was never committed has no head, and
  the epoch is then marked not clean as a whole: a projector meeting an epoch boundary without a clean end stops (§4.3),
  so "no activity" and "lost activity" are never confused.
- **Bulk facts** (a siege's end with its participants, a raid boss's kill with its contributors) are not hot-path facts:
  their snapshot is O(n) in participants, n is capped at `MaxParticipantsPerFact` per fact, and a larger set is written
  as several facts sharing a correlation id with their parts numbered. Their budget is `BulkCaptureMicros` per fact, not
  the hot path's 2 µs.
- **Coherent locations.** Mobius's `Location` has five independently written volatile fields (`entity/Location.java:36-40`)
  and `setXYZ` writes them one after another (`WorldObject.java:760-762`), so reading another actor's place while it
  moves or teleports can mix two moments. A core patch gives `Location` a version counter, made odd during a write and
  even after it; capture reads the fields between two equal, even readings of the version, retrying up to three times,
  and otherwise records the location as `torn` (a flag, not a guess).

### 3.3 Telemetry capture

`Telemetry.add(family, regionIndex, actorClass, a, b, actorId)`: O(1), no allocation, no lock.

- The producer reads the current buffer, increments the buffer's in-flight counter (striped by thread), re-reads the
  current buffer, and if it changed meanwhile, decrements and starts again; then adds `events`, the two sums and the
  sketch register (an atomic max) into its cell, and decrements the in-flight counter.
- At the window's end the writer swaps in a fresh buffer, waits until every stripe of the old buffer's in-flight counter is
  zero, then writes one row per non-empty cell and returns the buffer, cleared, to the pool. An add is therefore counted in
  exactly one window, whole: never lost, never split, never counted twice.

### 3.4 Where capture happens

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

The same facts, the same reducer version and the same configuration give the same derived state. Randomness a reducer
needs comes from the fact (recorded outcomes) or from a keyed stream seeded by the fact's id (04 §7); time is the fact's
`logical_time`, never the wall clock; configuration is the `config_ver` in force at the fact (the latest `config` fact
before it), recorded in the checkpoint.

### 4.2 Order

Within a stream, facts apply in (epoch, `stream_seq`) order. A projector that combines streams applies facts in the total
order (`logical_time`, stream name, epoch, `stream_seq`), and applies a fact at time *T* only when every input stream's
**frontier** has reached *T*:

- a captured stream's frontier is the writer's last committed heartbeat frontier (§3.2);
- an outbox stream's frontier is the oldest `logical_time` any open transaction may still write to it: the service that
  owns the stream registers a transaction's start time when it locks the head and removes it at commit or rollback, so
  the frontier is the earliest registered start, or the current time when none is open. An outbox fact therefore always
  carries a `logical_time` at or after the frontier its stream had when its transaction began.

A fact can then never arrive behind the frontier, and a live run and a replay apply the same facts in the same order.

### 4.3 Checkpoints and gaps

A projector writes its derived rows, its `lc_projector_checkpoint` rows (epoch, last number, reducer version, config
version) and its effect intents (§4.4) in one transaction; a fact at or below the checkpoint is skipped. It **stops** at
a missing number in a stream, at a `lc_journal_gap` row, and at an epoch boundary without a clean end, and records
`Journal.ProjectorStopped` with its watermark; its owner module's actuators and effects freeze (04 §8) until an admin
acknowledges the gap with a reason (`Journal.GapAcknowledged`), after which it continues past it.

### 4.4 Effects

A projector never calls the Asset Service or an actuator directly. It writes the effect it decided as a row in
`lc_effect_outbox` with a deterministic application id (from the projector, the deciding fact and the effect's index), in
the transaction of its checkpoint. A dispatcher executes `PENDING` effects in order, each as an Asset operation (or
actuator call) whose operation id is the application id, so executing one twice has no second effect
(asset-service.md §3.2), and marks it `DONE`. A crash between checkpoint and execution leaves the intent `PENDING`; it is
executed after restart. A failing effect is retried up to `EffectAttempts` and then marked `FAILED`, which freezes the
module. Replay writes no effect.

### 4.5 Replay and retention

Replay rebuilds an isolated store (its own schema) from the oldest **replay snapshot** of the projector
(`lc_replay_snapshot`: a copy of derived state with the checkpoint it corresponds to), or from the first fact if none has
been taken. Facts are kept per type for `retention_days` (`NULL`: for ever). A monthly partition is dropped only when every
fact in it is past its type's retention, every projector's checkpoint is past every number in it, and every projector
reading its streams has a replay snapshot newer than it. A maintenance job creates partitions at least two months ahead
every day and alarms if the catch-all partition holds a row.

### 4.6 Lag

A projector's lag is the age of the oldest fact committed to one of its input streams and not yet applied, measured
from that fact's commit; with nothing waiting, lag is zero. Telemetry buckets are measured separately: a window's
delay is the time from its end to its row's commit.

## 5. Facts of the journal itself

| Fact | Delivery | Fields |
|---|---|---|
| `Journal.Gap` | outbox (stream `journal`) | stream, epoch, from, to, refused, reason |
| `Journal.GapAcknowledged` | outbox | gap, admin, reason |
| `Journal.ProjectorStopped` | outbox | projector, stream, watermark, cause |
| `Journal.EpochStarted`, `Journal.EpochEnded` | outbox | epoch, clean |
| `Journal.ConfigChanged` | outbox (stream `config`) | module, config version |
| `Journal.Purged` | outbox | table, before, rows |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| FACT-INV01 | `lc_fact` holds no exact coordinate and no expiring datum: no payload field of a registered type is one | by the registry at startup (schema check of every record type) and a property test over every type |
| FACT-INV02 | per stream and epoch, `stream_seq` is unique; every missing number is a recorded hole or lies after the last committed number of an epoch that did not end cleanly | by every projector as it reads; by a full pass at startup |
| FACT-INV03 | an outbox fact exists if and only if the change it records committed | by construction (same transaction); by the owner module's invariants |
| FACT-INV04 | no projector checkpoint is ahead of its stream's head | at every checkpoint write |
| FACT-INV05 | every expiring row is gone by `expires_at` + purge interval, including after a restore | by the purge job, which counts what it purged and alarms on what it found late |
| FACT-INV06 | every telemetry add is counted in exactly one window | by construction (§3.3); by a property test that compares adds with written sums |
| FACT-INV07 | every fact's type is registered with the schema version it was written with | at insert (the writer refuses an unregistered type) |
| FACT-INV08 | every `PENDING` effect becomes `DONE` or `FAILED`; no application id is executed with two different payloads | by the dispatcher; at startup |
| FACT-INV09 | no batch id is committed twice | by its primary key |

A violation stops the projector or freezes the module, alarms and keeps the evidence; it never rewrites a fact.

## 7. Failure model

| Fault | End state |
|---|---|
| queue full | the capture is refused and counted; its number is a hole; a `QUEUE_FULL` gap row follows; the game is not slowed |
| store down | outbox writes fail with their transaction (the module's operation aborts; financial operations pause, 04 §8); the writer retries its batch with the same id; refusals meanwhile are holes and a `STORE_DOWN` gap |
| deadlock between Living Chronicle transactions | prevented by the row-lock order; a victim retries, then aborts (§3.1) |
| batch commit uncertain | resolved by a locking read of its batch id: never written twice, never written with new numbers |
| writer thread dies | restarted; its batch is resolved as above |
| JVM killed | outbox facts as committed; captured facts in the queue lost; the epoch is not clean; projectors stop at the boundary until acknowledged |
| projector reducer throws | that projector stops with its watermark; gameplay and other projectors go on |
| crash between a projector's checkpoint and an effect's execution | the effect is `PENDING` and runs after restart, once |
| replay | an isolated store is rebuilt; no effect is written |
| restore from backup | expiring rows past their expiry are deleted before the server starts |

## 8. Configuration and access

`config/Custom/LivingChronicle/Journal.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CaptureQueueCapacity` | 1024–1048576 | 65536 | 65536 everywhere | AT-FACT-04 |
| `WriterBatchMax` | 1–2000 | 500 | 500 everywhere | AT-FACT-05 |
| `WriterBatchMillisMax` | 1–1000 | 50 | 50 everywhere | AT-FACT-05 |
| `HeartbeatMillis` | 100–10000 | 1000 | 1000 everywhere | AT-FACT-15 |
| `DeadlockRetries` | 0–10 | 3 | 3 everywhere | AT-FACT-02 |
| `TelemetryWindowSeconds` | 10–3600 | 60 | 60 / 60 / 10 / 60 / 60 | AT-FACT-06 |
| `MaxParticipantsPerFact` | 10–1000 | 200 | 200 everywhere | AT-FACT-03 |
| `BulkCaptureMicros` | 10–10000 | 100 | 100 everywhere | AT-FACT-03 |
| `PreciseRetentionHours` | 1–720 | 72 | 72 everywhere | AT-FACT-08 |
| `ExpiringRetentionHours` | 1–8760 | 720 | 720 everywhere | AT-FACT-08 |
| `PurgeIntervalMinutes` | 1–1440 | 15 | 15 everywhere | AT-FACT-08 |
| `EffectAttempts` | 1–100 | 10 | 10 everywhere | AT-FACT-16 |

Database users: the game server may `INSERT` and `SELECT` `lc_fact` (no `UPDATE`, no `DELETE`) and may read neither expiring
table; the Intel Service and forensics read the expiring tables; the maintenance job alone deletes expiring rows and
drops partitions; publication reads only `lc_pub_*` (04 §4).

## 9. Interface

None for players. Admin: `lc why <fact>` (04 §6) prints a fact with its stream neighbours, its producing operation and the
projectors that applied it; `lc journal gaps`; `lc journal ack <gap> <reason>`; `lc journal effects`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| FACT-R01 | A durable fact recording a state change MUST be written in the same transaction as the change. | AT-FACT-01 |
| FACT-R02 | Within a stream and epoch, numbers MUST be unique, and every missing number MUST be a visible hole at the place of the loss. | AT-FACT-02 |
| FACT-R03 | A hot-path capture MUST take at most 2 µs at p99, never block, never do I/O and never allocate; a bulk fact MUST stay within `BulkCaptureMicros` with at most `MaxParticipantsPerFact` participants per fact. | AT-FACT-03 |
| FACT-R04 | A capture the journal cannot take MUST leave a hole and a recorded gap, never vanish silently. | AT-FACT-04 |
| FACT-R05 | A batch MUST commit once with the numbers it was given, even across an uncertain commit; a crash MUST leave every affected epoch marked not clean. | AT-FACT-05 |
| FACT-R06 | Telemetry MUST be aggregated in memory, each add counted in exactly one window, written once per window and cell. | AT-FACT-06 |
| FACT-R07 | Exact coordinates and expiring data MUST NOT be stored in `lc_fact`, and MUST be readable only by the Intel Service and forensics. | AT-FACT-07 |
| FACT-R08 | Expiring rows MUST be deleted at expiry, in backups by bounded backup life and at restore. | AT-FACT-08 |
| FACT-R09 | A projector MUST be deterministic and checkpointed in the transaction of its derived writes and effect intents. | AT-FACT-09 |
| FACT-R10 | A projector MUST stop at a hole, a gap or an unclean epoch boundary, and freeze its module's effects until acknowledged. | AT-FACT-10 |
| FACT-R11 | Replay MUST rebuild an isolated store and MUST NOT write any effect. | AT-FACT-11 |
| FACT-R12 | Every fact type MUST be registered and versioned; stored facts MUST never be updated or deleted except by dropping a partition under §4.5. | AT-FACT-12 |
| FACT-R13 | Capture MUST copy primitives at the owning operation, read locations coherently or flag them torn, and hold no reference to a live game object. | AT-FACT-03, AT-FACT-13 |
| FACT-R14 | Projector lag, as §4.6 defines it, MUST stay within 5 s at p99 under the 800-citizen siege benchmark. | AT-FACT-14 |
| FACT-R15 | A projector combining streams MUST apply facts in the total order of §4.2, never past a stream's frontier. | AT-FACT-15 |
| FACT-R16 | Every effect a projector decides MUST be executed exactly once, through the effect outbox. | AT-FACT-16 |
| FACT-R17 | Living Chronicle transactions MUST take database row locks in the global order of §3.1. | AT-FACT-02 |

| Test | Kind | What it does |
|---|---|---|
| AT-FACT-00 | integration | the journal's migration applies; monthly partitions have exact month boundaries, contiguous, ending in a catch-all; keys and checks reject what this spec forbids |
| AT-FACT-01 | integration, fault | an outbox fact and its change commit together or not at all, under faults at each statement |
| AT-FACT-02 | integration | concurrent outbox writers to one stream and Asset operations over several streams, custodies and characters: unique numbers, no deadlock outside the retry path; a forced hole is found by a projector and the startup pass |
| AT-FACT-03 | unit | hot-path capture benchmark at full load: p99 within 2 µs, zero allocation, never blocks; bulk facts within budget and chunked at the cap; locations read during movement and teleport are coherent or flagged torn |
| AT-FACT-04 | integration | a full queue: refused captures leave holes at the right numbers and become a `QUEUE_FULL` gap; game threads unaffected |
| AT-FACT-05 | fault, restart | writer killed mid-batch, commit cut after it was sent (committed and not), JVM killed with a full queue: no batch twice, none renumbered; epochs not clean |
| AT-FACT-06 | unit | telemetry under concurrent adds across many window swaps: every add in exactly one window; sums exact |
| AT-FACT-07 | integration | every registered type has no coordinate or expiring field; the game server's user cannot read the expiring tables |
| AT-FACT-08 | integration | expiring rows deleted on time; a restore deletes rows already expired before the server starts; backups of the expiring tables older than their life are refused |
| AT-FACT-09 | integration | a projector run twice, and with a crash between derived write and checkpoint: identical derived state and intents |
| AT-FACT-10 | integration | a hole, a gap row and an unclean epoch boundary each stop a projector and freeze its effects; acknowledgement resumes it |
| AT-FACT-11 | integration | replay into an isolated schema: equal derived state; no effect written |
| AT-FACT-12 | unit, integration | an upcaster chain reads every stored version; an unregistered type is refused; the game server's user cannot update or delete `lc_fact` |
| AT-FACT-13 | unit | every capture record holds only primitives and immutable snapshots (reflection over the sealed hierarchy) |
| AT-FACT-14 | sim | 800-citizen siege benchmark: lag p99 within 5 s; a caught-up projector reports zero |
| AT-FACT-15 | integration | a projector over a captured and an outbox stream, live and replayed, with idle streams and long outbox transactions: the same order both times, nothing applied past a frontier |
| AT-FACT-16 | fault, restart | crash between checkpoint and execution, and an effect that fails: executed once after restart; `FAILED` freezes the module |

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
   another (`WorldObject.java:760-762`). Core patch: a version counter on `Location`, odd during a write (§3.2).
8. **Map regions are sparse `locId`s** (`MapRegionData.java:350`; e.g. 2189). → a dense index fixed at startup and
   recorded (§2.3).

## 12. Review record

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
