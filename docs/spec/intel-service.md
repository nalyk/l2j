# SPEC-INTEL — Intel Service

- Status: draft (revision 1)
- Blueprint: 04 §4 (Intel Service), §8; 01 §2.5 (imperfect information); 02 §3 (the one mechanism for "telling a player
  something they might not be entitled to know"), §12; 03 §4.5 (`canWitness`); 16 §3 (traces, witnesses, budgets,
  confidence), §4 (counter-intelligence), §8 (information under alts and Discord); 13 §3 (Signal location ≤ REGION, short
  TTL); 14 §7 (Wayfarer keeps expired intel only as "an expired lead"); 17 (publication DTOs); consensus §6.
- DDL: `sql/migrations/V008__lc_intel.sql` (after the Fact Journal and Actor Identity).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Everything a player may learn about another actor, or about hidden world state, passes through the Intel Service
(04 §4). It owns:

- **traces**: what the world noticed about an actor (a sighting by a witness, a teleport through a gatekeeper, a
  warehouse visit), kept with a lifetime and a sensitivity, its exact evidence in the journal's precise table;
- **disclosure**: turning traces into **intel** for one audience and purpose, always coarsened, with a confidence class,
  priced and limited by budgets that cooperating accounts cannot reset;
- **`canWitness`**: the one predicate deciding whether an observer could have seen a target;
- **expiry**: every intel item, and every copy a module keeps of it, disappears when it expires;
- **publication filtering**: what may reach the `lc_pub_*` tables the website and narrator read.

It does not own: the Black Ledger's contracts, prices and ranks (they ask it for disclosures), Wayfarer's journal (it
keeps intel ids, never payloads), Signal's matching, nor the facts themselves (the Fact Journal).

## 2. Data

### 2.1 Precision and confidence

| Precision | Meaning | Disclosable |
|---|---|---|
| `EXACT_INTERNAL` | coordinates and instance | never: only inside the service and forensics |
| `ZONE` | a named zone (a hunting ground, a town district) | yes |
| `REGION` | a map region (fact-journal.md §2.3) | yes |
| `TERRITORY` | a castle's territory | yes |
| `NONE` | that something exists, nowhere | yes |

Confidence: `CONFIRMED` (direct authoritative evidence), `WITNESS` (a server-verified sighting), `INFERRED` (derived from
valid clues), `RUMOR` (unverified; false trails are labelled so), `STALE` (true once, past its fresh life) (16 §3.7).

### 2.2 Tables

```text
lc_trace(trace_id BIGINT PK auto, subject_char INT, kind {SIGHTING|GATEKEEPER|WAREHOUSE|MERCHANT|LOGIN|KILL|OTHER},
         witness_ref VARCHAR(32) NULL, zone_id INT NULL, region_id SMALLINT, territory_id SMALLINT NULL, coarse_dir TINYINT NULL,
         occurred_at TIMESTAMP(3), expires_at TIMESTAMP(3), sensitivity TINYINT, source_fact_id BIGINT)
lc_intel(intel_id BIGINT PK auto, trace_id BIGINT NULL, kind VARCHAR(32), subject_char INT, precision_level {ZONE|REGION|TERRITORY|NONE},
         confidence {CONFIRMED|WITNESS|INFERRED|RUMOR|STALE}, payload JSON, audience_kind {CHAR|CONTRACT|CLAN},
         audience_id INT, purpose VARCHAR(32), created_at TIMESTAMP(3), expires_at TIMESTAMP(3), shareable BOOLEAN,
         publishable BOOLEAN, dedup_key BINARY(16))
lc_disclosure(target_char INT, witness_ref VARCHAR(32), window_start TIMESTAMP, units INT, PRIMARY KEY(target_char, witness_ref, window_start))
lc_intel_copy(module VARCHAR(16), table_name VARCHAR(64), row_key VARCHAR(64), intel_id BIGINT, expires_at TIMESTAMP(3),
              PRIMARY KEY(module, table_name, row_key))
lc_witness_suppression(witness_ref VARCHAR(32), subject_char INT, suppressed_until TIMESTAMP(3), source_fact_id BIGINT,
                       PRIMARY KEY(witness_ref, subject_char))
```

- A **trace** is the service's own record of something noticed, coarse in itself (zone, region, territory, a direction
  among eight); its exact evidence is the journal's `lc_fact_precise` row of `source_fact_id`. The Black Ledger's
  `black_trace` (16 §3.2) is this table. Default lifetimes by kind and heat (16 §3.2: HOT ≤ 15 min, RECENT ≤ 1 h,
  STALE ≤ 6 h) are configuration; at `expires_at` a trace is deleted.
- An **intel item** is one disclosure to one audience: what was said, how precisely, how confidently, to whom, for what,
  until when. `dedup_key` is a hash of (subject, kind, precision, the coarse place, the window): an equivalent sighting
  disclosed again is the same item, not new information (16 §3.5).
- `lc_disclosure` counts what has been disclosed about a target, per witness and window: the budget's state. It is keyed
  by target and witness, **never by the requester**, so a second account, a second contract or a cooperating clan asking
  the same witness about the same target in the same window draws on the same, already diminished budget (16 §8).
- `lc_intel_copy` registers every row any module keeps that holds intel (a Wayfarer observation, a Ledger dossier line,
  a Signal answer): the purge job deletes those rows when the intel expires (§4.4).
- `lc_witness_suppression` records a bribed witness's silence about a subject (16 §4): it suppresses disclosure, never
  the trace or the audit.

## 3. `canWitness`

`canWitness(observer, target, at)` is true only if, at the moment of the evidence, all hold (03 §4.5; 16 §3.5):

| Condition | How |
|---|---|
| same instance | `WorldObject.getInstanceId()` of both (`entity/WorldObject.java:729`) |
| within the witness's range | distance ≤ the witness kind's range (configuration) |
| line of sight through geodata | `GeoEngine.canSeeTarget(observer, target)` (`geoengine/GeoEngine.java:424`) |
| the target visible to it | not invisible (`WorldObject.isInvisible`, :971), not hidden by a skill or GM mode |
| observed long enough, or an authoritative interaction | the target stayed eligible for at least the kind's minimum observation time, sampled; or the witness served it (a gatekeeper teleported it, a warehouse keeper opened its warehouse) |
| recent | the evidence is younger than the trace kind's fresh life |

**Sampling.** Witnesses are NPCs given a witness role (gatekeepers, warehouse keepers, merchants, guards, 16 §3.3). In an
awake region, every `SightingSampleMillis`, each witness checks the players within its range
(`World.getVisibleObjectsInRange`, `entity/World.java:695`) against the conditions; a player eligible for the minimum time
becomes a `SIGHTING` trace (one per witness, subject and window). Interactions with a witness are traces at once. The
sample is the service's only hot-ish path; its cost is bounded by the number of witnesses in awake regions and is a
measured budget (§8).

## 4. Operations

### 4.1 Interface

```java
Trace       intel.notice(TraceKind kind, WitnessRef witness, Creature subject, long factId);      // inside the service, from capture
Disclosure  intel.disclose(Audience audience, Purpose purpose, WitnessRef witness, int subjectChar,
                           Precision max, Budget budget);                                         // what a player may learn
Optional<IntelView> intel.read(long intelId, Audience reader);                                   // refuses an expired item
void        intel.registerCopy(String module, String table, String rowKey, long intelId);
PublicationDecision intel.publishable(Object dto);                                               // for lc_pub_* writers
```

No method returns a coordinate, a `Location`, a `WorldObject` or a precise row; `IntelView` holds only the item's coarse
fields. A static test enforces it over the service's public types (AT-INTEL-01).

### 4.2 Disclosure

1. The witness must hold a non-suppressed trace of the subject, fresh enough for the asked purpose.
2. The disclosed precision is the coarser of the asked maximum, the witness kind's maximum, and what the budget still
   allows: each disclosure about a target from a witness in a window costs units, and each further one in the same
   window costs more and yields coarser precision, down to `NONE` (diminishing returns, 16 §8). The units are spent in
   `lc_disclosure` in the same transaction as the item is written, under the target's row lock, so two concurrent
   requests cannot both spend the last unit.
3. If an item with the same `dedup_key` already exists and has not expired, the audience gets that item, at no new cost
   and with no new information.
4. The item's confidence is the trace's (`WITNESS` for a sighting), lowered to `STALE` past its fresh life; a deliberate
   false trail is `RUMOR`.
5. A disclosure is an outbox fact (`Intel.Disclosed`) in the same transaction: who learned what about whom, for which
   contract, at which precision. It records the disclosure, never the exact evidence.

### 4.3 What may be shared

Content is assumed shareable: anything disclosed may be relayed instantly to an enemy (16 §8). The service protects
actions (who may ask, in person, at what price, how often), not content; `shareable` only marks whether the Codex lets
the player pass it on in-game.

### 4.4 Expiry

At `expires_at` the purge job deletes the trace, the intel item and every registered copy (`lc_intel_copy`), and the
journal deletes the precise and expiring rows behind it (fact-journal.md §2.4). A module that keeps intel in a table not
registered as a copy fails the static check (AT-INTEL-06). A rebuild or replay never resurrects expired intel: projectors
read intel through `intel.read`, which refuses expired items, so a rebuild after expiry derives "an expired lead" at most
(14 §7).

### 4.5 Publication

Writers of `lc_pub_*` tables pass every row through `intel.publishable`: a row may name an actor or a place only if the
fact it comes from is public by nature (a castle changing owner, a siege, a hero) or marked `publishable` after its delay,
and never at a precision finer than its publication rule. The website and narrator read the `lc_pub_*` tables with a
database user that can read nothing else (04 §4).

## 5. Facts

| Fact | Delivery | Fields |
|---|---|---|
| `Intel.TraceNoticed` | captured (`intel.trace`) | trace id, kind, subject snapshot, witness, coarse place (the exact place in the journal's precise table) |
| `Intel.Disclosed` | outbox | intel id, audience, purpose, subject, precision, confidence, units spent |
| `Intel.Suppressed` | outbox | witness, subject, until, bribe fact |
| `Intel.Expired` | outbox | intel ids and traces purged, copies deleted |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| INTEL-INV01 | no coordinate, `Location` or world object leaves the service | by the static test over its public types; by the database grants (no other user reads the precise table) |
| INTEL-INV02 | disclosures about a target from a witness in a window never exceed its budget, whoever asks | by the row lock and a check before commit; at every purge pass |
| INTEL-INV03 | no item, trace or registered copy outlives its `expires_at` + purge interval | by the purge job, which alarms on what it found late |
| INTEL-INV04 | every trace of kind `SIGHTING` was eligible under `canWitness` when noticed | by construction; by the witness property tests |
| INTEL-INV05 | no `lc_pub_*` row carries an actor or place the publication rules forbid | by the publication property tests over every fact type |

## 7. Failure model

| Fault | End state |
|---|---|
| service down | nothing is disclosed: questioning and trace purchases are refused; play continues (04 §8) |
| purge late | an alarm; the items are refused by `intel.read` past expiry even before they are deleted |
| journal gap in traces | disclosures from the gap's window are `NONE` or refused, never invented |

## 8. Configuration

`config/Custom/LivingChronicle/Intel.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `SightingSampleMillis` | 250–10000 | 2000 | 2000 / 2000 / 1000 / 2000 / 2000 | AT-INTEL-04 |
| `SightingBudgetMicros` | 10–100000 | 2000 | 2000 everywhere | AT-INTEL-09 |
| `DisclosureWindowMinutes` | 1–1440 | 60 | 60 everywhere | AT-INTEL-02 |
| `TraceLifeHot/Recent/StaleMinutes` | 1–1440 | 15 / 60 / 360 | as default everywhere | AT-INTEL-03 |
| `SignalLocationTtlMinutes` | 1–60 | 10 | 10 everywhere | AT-INTEL-03 |

Witness kinds (range, minimum observation, maximum precision, unit costs) are tables in the same file.

## 9. Interface

Players reach the service only through the modules' pages (Ledger dossiers, witness questioning at NPCs, Signal answers,
Wayfarer observations), which show intel with its precision, confidence and age, never more. Admin: `lc why <intel>`
prints the trace, its exact evidence (forensics only), the budget spent and the requester.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| INTEL-R01 | No coordinate, location or world object MUST leave the service; coarsening MUST happen inside it. | AT-INTEL-01 |
| INTEL-R02 | Disclosure about a target from a witness in a window MUST be budgeted with diminishing returns that no other account, contract or clan resets. | AT-INTEL-02 |
| INTEL-R03 | Traces, items and every registered copy MUST be deleted at expiry; nothing expired MUST be readable or rebuilt. | AT-INTEL-03 |
| INTEL-R04 | A sighting MUST be a trace only if `canWitness` held: same instance, range, line of sight, visibility, minimum observation or interaction, recency. | AT-INTEL-04 |
| INTEL-R05 | An equivalent sighting disclosed again MUST yield the same item at no new cost. | AT-INTEL-05 |
| INTEL-R06 | A module MUST keep intel only in tables registered as copies. | AT-INTEL-06 |
| INTEL-R07 | Nothing published MUST name an actor or place beyond its publication rule. | AT-INTEL-07 |
| INTEL-R08 | A suppressed witness MUST disclose nothing about its subject until the suppression ends, and the trace and audit MUST remain. | AT-INTEL-08 |
| INTEL-R09 | Witness sampling MUST stay within `SightingBudgetMicros` per sample at the 800-citizen siege benchmark. | AT-INTEL-09 |
| INTEL-R10 | Every disclosure MUST be recorded as an outbox fact with its audience, purpose and precision, never its exact evidence. | AT-INTEL-10 |

| Test | Kind | What it does |
|---|---|---|
| AT-INTEL-00 | integration | the migration applies; keys and checks reject what this spec forbids |
| AT-INTEL-01 | unit | the static scan of the service's public types finds no coordinate, `Location` or world object; the game server's user cannot read the precise table |
| AT-INTEL-02 | integration | several accounts, contracts and clans asking about one target from one witness in one window: units spent once, precision falls, never past the budget, concurrently too |
| AT-INTEL-03 | integration | items, traces and registered copies gone at expiry; a rebuild and a replay after expiry give "expired lead" at most |
| AT-INTEL-04 | integration | sightings across walls, floors, invisibility, hide, teleport, login, doors, summons, instances and the minimum time: a trace exactly when eligible (16 `BL-*` edge cases) |
| AT-INTEL-05 | integration | the same sighting asked twice, by two audiences: one item, one cost |
| AT-INTEL-06 | unit | a module table holding an intel id or payload without a copy registration fails the scan |
| AT-INTEL-07 | integration | property test over every fact type: no `lc_pub_*` row breaks its publication rule; a hunter given only the website locates a mark no better than chance plus public facts (17) |
| AT-INTEL-08 | integration | a bribed witness discloses nothing about its subject until the end of the suppression; the trace and the audit fact remain |
| AT-INTEL-09 | sim | 800-citizen siege benchmark: witness sampling within budget |
| AT-INTEL-10 | integration | every disclosure writes its `Intel.Disclosed` fact in its transaction, without exact evidence |

## 11. Upstream constraints

1. **Line of sight and visibility exist in Mobius**: `GeoEngine.canSeeTarget` (`geoengine/GeoEngine.java:424, 435, 452`),
   `WorldObject.isInvisible` / `isVisibleFor` (`entity/WorldObject.java:971, 1004`), range queries
   (`World.getVisibleObjectsInRange`, `entity/World.java:695, 796`). → `canWitness` composes them (§3); its line of sight
   is only as good as the geodata (N7).
2. **NPCs have no notion of having seen anyone.** → witness sampling is new, bounded and measured (§3).

## 12. Review record

None yet.
