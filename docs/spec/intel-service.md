# SPEC-INTEL — Intel Service

- Status: reviewed (revision 6; signed off by Codex with the Black Ledger, in its round 6; record in §12)
- Blueprint: 04 §4 (Intel Service), §8; 01 §2.5 (imperfect information); 02 §3 (the one mechanism for "telling a player
  something they might not be entitled to know"), §12; 03 §4.5 (`canWitness`); 16 §3 (traces, witnesses, budgets,
  confidence), §4 (counter-intelligence), §8 (information under alts and Discord); 13 §3 (Signal: location ≤ REGION,
  short TTL, opt-in); 14 §7 (Wayfarer keeps expired intel only as "an expired lead"); 17 (publication DTOs); consensus §6.
- DDL: `sql/migrations/V008__lc_intel.sql` (after the Fact Journal and Actor Identity).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Everything a player may learn about another actor, or about hidden world state, passes through the Intel Service
(04 §4). It owns:

- **traces**: what the world noticed about an actor, kept with a lifetime and a sensitivity, **outside the journal**: a
  trace and its exact evidence live only in the service's own expiring tables, so no immutable record of a trail exists;
- **disclosure**: turning traces into **intel** for an authorised requester and purpose, always coarsened, priced and
  limited by budgets that cooperating accounts cannot reset, bounded together so that combining answers cannot narrow a
  target below a floor;
- **`canWitness`**: the one predicate deciding whether an observer could have seen a target, NPC witness or registered
  player informant;
- **expiry**: every trace, item and copy disappears when its evidence does, in backups and restores too;
- **publication filtering**: what may reach the `lc_pub_*` tables the website and narrator read.

It does not own: the Black Ledger's contracts, prices and ranks (it asks for disclosures and is checked here),
Wayfarer's journal (it keeps registered copies), Signal's matching (it asks for coarse locations), nor facts (the Fact
Journal; the journal records that a disclosure happened, never what or where).

## 2. Data

### 2.1 Precision and confidence

| Precision | Meaning | Disclosable |
|---|---|---|
| `EXACT_INTERNAL` | coordinates and instance | never: only inside the service and forensics |
| `ZONE` | a named zone (a hunting ground, a town district) | yes |
| `REGION` | a map region (fact-journal.md §2.3) | yes |
| `TERRITORY` | a castle's territory | yes |
| `NONE` | that something exists, nowhere | yes |

Confidence: `CONFIRMED`, `WITNESS`, `INFERRED`, `RUMOR` (unverified; false trails are labelled so), `STALE` (16 §3.7).

### 2.2 Tables

```text
lc_trace(trace_id BIGINT PK auto, subject_char INT, kind {SIGHTING|GATEKEEPER|WAREHOUSE|MERCHANT|LOGIN|KILL|INFORMANT|OTHER|RUMOR|IDENTITY},
         witness_ref VARCHAR(32), zone_id INT NULL, region_id SMALLINT, territory_id SMALLINT NULL, coarse_dir TINYINT NULL,
         occurred_at TIMESTAMP(3), disclosable_at TIMESTAMP(3), expires_at TIMESTAMP(3), sensitivity TINYINT)
lc_trace_evidence(trace_id BIGINT PK, x INT, y INT, z INT, instance_id INT, observer_x INT, observer_y INT, observed_ms INT,
                  expires_at TIMESTAMP(3))
lc_intel(intel_id BIGINT PK auto, trace_id BIGINT, kind VARCHAR(32), subject_char INT, precision_level {ZONE|REGION|TERRITORY|NONE},
         confidence {…}, payload_type VARCHAR(32), payload JSON, age_band {FRESH|RECENT|OLD}, created_at TIMESTAMP(3),
         expires_at TIMESTAMP(3), shareable BOOLEAN, publishable BOOLEAN, UNIQUE(trace_id))
lc_intel_grant(intel_id BIGINT, audience_kind {CHAR|CONTRACT|CLAN}, audience_id INT, purpose VARCHAR(32), granted_at TIMESTAMP(3),
               view_precision {ZONE|REGION|TERRITORY|NONE}, expires_at TIMESTAMP(3), PRIMARY KEY(intel_id, audience_kind, audience_id, purpose))
lc_disclosure(target_char INT, witness_ref VARCHAR(32), at TIMESTAMP(3), units INT, implied_cells INT, expires_at TIMESTAMP(3),
              PRIMARY KEY(target_char, witness_ref, at))
lc_disclosure_cell(target_char INT, at TIMESTAMP(3), cell INT, expires_at TIMESTAMP(3), PRIMARY KEY(target_char, at, cell))
lc_pub_type(pub_type VARCHAR(48) PK, min_delay_minutes INT, location_related BOOLEAN)
lc_pub_field_rule(pub_type VARCHAR(48), field VARCHAR(48), rule {PUBLIC_ENTITY|AREA_TERRITORY|DAY|COUNT|TEXT_FROM_FACT}, PRIMARY KEY(pub_type, field))
lc_intel_copy(module VARCHAR(16), table_name VARCHAR(64), row_key VARCHAR(64), intel_id BIGINT, expires_at TIMESTAMP(3),
              PRIMARY KEY(module, table_name, row_key, intel_id))
lc_witness_suppression(witness_ref VARCHAR(32), subject_char INT, suppressed_until TIMESTAMP(3), source_fact_id BIGINT, PRIMARY KEY(witness_ref, subject_char))
lc_identity_evidence(trace_id BIGINT PK → lc_trace, kind {ALIAS_LINK|PATRON_CLAN|PATRON_NAME}, payload JSON, bound_seal BIGINT NULL,
                     bound_alias INT NULL, expires_at TIMESTAMP(3))
lc_signal_presence(char_id INT PK, opted_in BOOLEAN, region_id SMALLINT, expires_at TIMESTAMP(3))
lc_expiring_table(table_name VARCHAR(64) PK, expires_column VARCHAR(32), owner_module VARCHAR(16), max_life_hours INT)
```

- A **trace** is coarse in itself; its exact evidence is `lc_trace_evidence`, deleted with it. **No trace, and nothing
  derived from one, is written to the journal**: the journal's `Intel.Disclosed` fact records an intel id, a requester and
  a purpose, never a subject's place, witness or time beyond the disclosure's own.
- An **intel item** is the one coarse statement ever made from a trace (`UNIQUE(trace_id)`): the first disclosure fixes its
  precision; any later request about the same trace, from any audience, for any purpose, receives a **grant** of that
  same item (`lc_intel_grant`), never a refinement. A grant has its own view: the coarser of the item's precision and the
  purpose's ceiling, and its own expiry: the earlier of the item's and the purpose's lifetime (§4.4). So a Signal grant of
  an item first made at `ZONE` for the Ledger shows `REGION` and lives for Signal's TTL; nothing is ever shown finer than
  the item. Only new evidence (a new trace) can say more.
- `lc_disclosure` is the budget's ledger, keyed by target and witness, never by requester, and `lc_disclosure_cell` the
  cells (§4.3) each positive answer implied. Both rows **expire with the window** (`expires_at` = `at` +
  `DisclosureWindowMinutes`), are in the expiring registry, and are purged like traces, so the ledger never outlives the
  window it bounds and keeps no trail.
- `lc_intel_copy` registers every row any module keeps that depends on intel, one row per dependency. A module stores
  **intel ids, never payloads**: what it shows is fetched through `intel.read` at the time of showing (§4.1), so a copy is
  only a reference, and expiry or a revoked grant takes effect at once whatever the purge's delay.
- **Rows are immutable** on every Intel table holding traces, evidence, items, grants, copies or the ledger: they are
  inserted and deleted, never updated (database triggers), so no expiry, parent, precision or view can change after the
  insert checks. Evidence expires exactly with its trace (they are one datum stored in two places).
- `lc_pub_type` and `lc_pub_field_rule` are the publication registry (§4.7).
- `lc_expiring_table` lists every table in the database that holds expiring data (this service's, the journal's
  `lc_fact_precise` and `lc_fact_expiring`, and every module table that registers copies), with its expiry column and the
  longest life its rows may have. The purge job, the backup policy and the restore procedure all work from this list
  (§4.5).
- Payloads are typed: `payload_type` names a registered record whose fields are only coarse values (area ids at the
  item's precision, a direction among eight, an age band); the service refuses a payload with any other field (§4.2).

## 3. `canWitness`

`canWitness(observer, target)` is true only if all hold (03 §4.5; 16 §3.5):

| Condition | How |
|---|---|
| same instance | `WorldObject.getInstanceId()` of both (`entity/WorldObject.java:729`) |
| within the observer kind's range | distance ≤ the kind's range (configuration) |
| geodata present along the line | `GeoEngine.hasGeo` (`geoengine/GeoEngine.java:874`) for both ends and every cell crossed; **missing geodata fails closed**, because Mobius's own line-of-sight succeeds where geodata is absent (`GeoEngine.java:525-528`) |
| line of sight | `GeoEngine.canSeeTarget(observer, target)` (`geoengine/GeoEngine.java:424`) |
| the target visible | not invisible (`WorldObject.isInvisible`, :971), not hidden by a skill or GM mode |
| observed long enough, or an authoritative interaction | §3.1 |

### 3.1 Observation

Each (observer, target) pair under observation has a record: `eligible_since` and the last sample at which it was
eligible. At each sample: eligible → keep `eligible_since` (or set it); not eligible, or a sample missed for the pair
(overload, despawn, teleport, instance change, a visibility change) → **reset**. A sighting is noticed when the pair has
been eligible at `MinConsecutiveSamples` consecutive samples spanning at least the kind's minimum observation time. An
**interaction** (a gatekeeper teleporting the target, a warehouse keeper opening its warehouse, a merchant trading with
it) is a trace at once, with the target's place **before** the interaction (the departure, not the destination).
Observation state never invents eligibility: every failure resets.

### 3.2 Sampling

Witnesses are NPCs given a witness role (16 §3.3) and **registered informants**: player characters who hold an informant
registration with a Broker (16 §3.5), observed with the same predicate and the same state machine. Every
`SightingSampleMillis` a sampling pass runs over the witnesses of awake regions, **round-robin**, iterating each witness's
region's visible objects and its neighbours' (`WorldRegion`'s visible-object sets, `entity/WorldRegion.java:41`), without
building lists. The whole pass has the budget `SightingPassMicros`; witnesses not reached in a pass are first in the next,
and their pairs count as missed (reset): overload yields fewer sightings, never false ones. An informant's sightings are
evidence only if the informant and the target do not share an account or a principal (actor-identity.md §2.2), and only
when submitted in person to the Broker; the Black Ledger's linkage rules (16 §8) may refuse more.

## 4. Operations

### 4.1 Interface

```java
Disclosure  intel.disclose(Requester requester, Purpose purpose, WitnessRef witness, int subjectChar, Precision asked);
Optional<IntelView> intel.read(long intelId, Requester reader);
void        intel.suppress(Connection tx, WitnessRef witness, int subjectChar, Instant until, long bribeFactId);
long        intel.plantRumor(Connection tx, int subjectChar, int territoryId, Duration life, long payerFactId);
long        intel.recordIdentity(Connection tx, int subjectChar, IdentityKind kind, WitnessRef witness, IdentityPayload payload, Duration life);
void        intel.registerCopies(Connection tx, String module, String table, String rowKey, long... intelIds);
PublicationDecision intel.publishable(PubRow row);
```

`Requester` is built by the service from the authenticated session (the character, its account), never from module
arguments. No method returns a coordinate, a `Location`, a `WorldObject` or evidence; `IntelView` holds only the grant's
view of the item's typed coarse fields.

`intel.read` is authorised **at every read**, not only at disclosure: it returns a view only if the item and a grant are
unexpired and the reader holds that grant now: directly (a `CHAR` grant to the reader), or through a contract or clan the
reader belongs to at the moment of reading (membership resolved again, never cached), and only if the grant's purpose
rule still allows the reader (for Signal: §4.4's conditions checked again, so an opt-out, a block or a visibility change
takes effect at the next read). Otherwise it returns nothing, the same nothing as for an unknown id.

### 4.2 Disclosure

A disclosure succeeds only if, checked by the service itself:

1. **Authorisation**: the purpose's rule allows the requester (Ledger: a registered hunter, client or mark of a live
   contract naming this subject, asked in person: the requester's character within the witness NPC's interaction range at
   the time of asking; Signal: §4.4; Wayfarer: only items already granted to that character).
2. **Evidence**: the witness holds a non-suppressed trace of the subject, past its `disclosable_at` (§4.6).
3. **Budget**: the units already spent in the rolling window for (subject, witness), plus this disclosure's cost, do not
   exceed `DisclosureBudgetUnits`, and the combined bound holds (§4.3). The units are spent in `lc_disclosure`, under the subject's row lock, in the same transaction as the
   grant, so concurrent requests cannot both spend the last units.
4. **Precision**: the coarser of the asked precision, the purpose's ceiling, the witness kind's ceiling and what the budget
   still allows (each further disclosure about a subject from a witness in the window costs more units and yields coarser
   precision, the cost and the ladder being configuration tables with known values, §8).

Then: if the trace already has an item, the requester receives a grant of it (no cost, no new information); otherwise the
item is created at that precision with a typed payload and expiry (§4.5) and granted. The `Intel.Disclosed` outbox fact is
written in the same transaction.

### 4.3 The combined bound

Answers combine, and an answer says more than the area it names: a positive answer from a fixed witness also says the
subject was within that witness's range, and a direction says which side. So the service computes each positive answer's
**implied area**: the named area (at the answer's precision) ∩ the witness's observation range (centred where the
witness stood when it observed, recorded in the evidence: a fixed NPC's spawn point, a moving NPC's or an informant's
own position, with its kind's range) ∩ the direction's sector if one is given, as a set of geodata blocks (the "cells", 2048 game units square).
For each subject it keeps, over the rolling window, the implied cells of every positive answer to anyone, for any
purpose, from any witness; a new positive answer is given only if the intersection of its implied cells with all of
those is at least `MinCombinedCells` cells. Otherwise it is coarsened, its direction dropped first; and if even a bare
"seen" from that witness would imply too little, the witness answers exactly as it does when it has nothing: same words,
same price, no units spent. So cooperating accounts, contracts and clans asking several witnesses cannot together pin a
subject below the floor within a window, and a refusal is indistinguishable from an absence. The guarantee is about this
service's answers; what players learn by other means (seeing the subject themselves) is outside it.

### 4.4 Purpose ceilings

| Purpose | Ceiling | Lifetime | Other rules |
|---|---|---|---|
| Ledger questioning (`LEDGER_QUESTIONING`) | the witness kind's, and the hunter's rank's (black-ledger.md §4.1) | the trace's | requester in person; an active acceptance or a Writ naming the subject |
| Ledger rumors (`LEDGER_RUMOR`) | `TERRITORY`, confidence always `RUMOR` | the trace's | requester in person at an information NPC; any registered hunter; real traces of the subject and planted `RUMOR` traces alike |
| Ledger sightings (`LEDGER_INFORMANT`) | the informant witness kind's | the trace's | requester in person at a Broker; an active acceptance naming the subject; only `INFORMANT` traces |
| Ledger identity (`LEDGER_IDENTITY`, `LEDGER_REVERSE`) | `NONE` | the evidence's | §4.8 |
| Signal location | `REGION` | `SignalLocationTtlMinutes` | only characters who opted in (`lc_signal_presence`), are `INTERACTIVE` (actor-identity.md §2.3), have not blocked the requester, and whose Signal visibility (13 §4: everyone, alliance, clan, nobody) includes the requester; offline actors never; checked at disclosure and again at every read |
| Wayfarer recall | the character's existing grant's view | the existing grant's | creates no grant: Wayfarer reads through the character's existing grant, so its view, expiry and every revocation (an opt-out, a block, a membership change) apply unchanged |
| publication | §4.7 | — | — |

### 4.5 Expiry

- An item and the evidence expire **exactly** with their trace, so an item is never purged while its trace lives and a
  trace's one item can never be replaced by a new one; shorter lives belong to grants. Grants, copies and the ledger
  never outlive what they depend on: a grant's ≤ its item's, a copy's ≤ the earliest of its items'. A grant does not extend an item's life; a new
  disclosure of an old trace gets the old item and its old expiry. The database enforces this at insert, and refuses any
  later change of an expiry (§2.2).
- At `expires_at` the purge job deletes traces with their evidence, items with their grants, and every row of every
  registered copy (all its dependencies), in one transaction per batch; `intel.read` refuses an expired item even before
  it is deleted. Copies are registered in the same transaction that writes the referring row (`registerCopies` takes the
  module's transaction). No module keeps an intel payload in memory beyond the request that read it.
- **Backups and restores** work from `lc_expiring_table`: a backup of those tables is kept no longer than their
  `max_life_hours`; a restore deletes every expired row of every listed table before the server starts. A module table
  holding intel that is not listed fails the static check (AT-INTEL-06).
- A rebuild or replay reads intel only through `intel.read`, so after expiry it derives "an expired lead" at most
  (14 §7).

### 4.6 Timing

Presence of a recent trace is itself information, by design: that is what a hunter pays a witness for. What the service
bounds is its resolution in time. **Every change of an answer that comes from the evidence is on the quantum grid**
(authorisation changes, §4.1, take effect at once and reveal only what the reader did themselves or what the subject
chose: a block, an opt-out, leaving a clan): a trace becomes
disclosable at `disclosable_at`, the next multiple of `DisclosureQuantumMinutes` after it occurred; its age band changes
and it expires only at multiples of the quantum too (`expires_at` and the band boundaries are computed from
`disclosable_at` and are whole quanta), and items, grants and copies expire at their trace's grid points or earlier grid
points. So watching a witness's answers, appearing, aging or vanishing, reveals a visit to within a quantum, never to the
moment.

### 4.7 Publication

`lc_pub_*` rows are of registered types only (`lc_pub_type`); each field of each type has a rule (`lc_pub_field_rule`: a
public entity, such as a castle, a clan, a hero; an area at `TERRITORY` or coarser; a day; a count; text taken from a
fact). An unknown type or field is refused (fail closed). Every type has a minimum delay before a row may be written
about an event; a **location-related** type (a battle map, a saga replay, a raid, anything naming an area and a time)
has at least `PublicationTacticalDelayMinutes` (default 1440, 17 §1's "≥24 h"), and the registry refuses a lower one. Sealed contract terms, hidden identities (aliases, informants, marks, depositors) and anything
derived from intel are never publishable, except through three field rules that check their condition when the row is
written: `LEDGER_WRIT_MARK` (a character, only while an open Writ names them: Writs are public by design, 16 §2),
`LEDGER_LEGEND_MARK` (the mark of a terminal Black Contract the Ledger promoted, black-ledger.md §4.12) and `LEDGER_ALIAS`
(an alias, never its owner). A depositor, an informant or an alias's owner is never publishable, exposed or not. The website and narrator read the `lc_pub_*` tables with a database user that
can read nothing else (04 §4).

### 4.8 The Black Ledger's operations

- **Suppression** (`suppress`): a bribe paid by the subject at a witness. Allowed only for witness kinds marked bribable in
  the witness kinds table (gatekeepers, warehouse keepers, merchants, information NPCs; never guards, Magistrates or
  clan-hall stewards), for at most the kind's maximum; it writes `lc_witness_suppression` and `Intel.Suppressed`.
  Suppression begins and ends **on the quantum grid** (§4.6: at the next multiple of `DisclosureQuantumMinutes`), like
  every other change of an answer that is not a requester's own authorisation; a suppressed witness answers exactly as
  one with nothing (INTEL-R08); the trace and its audit stay. `lc_witness_suppression` is an expiring table (its
  `suppressed_until`), listed in `lc_expiring_table`.
- **Rumors** are their own traces, never views of other ones. The **rumor network** of a territory is a witness
  (`rumor:<territory>`) with no position: its traces are `RUMOR` traces whose only place is the territory. Two kinds of
  rumor trace exist: **heard** ones, which the network derives from real traces of a subject once they are
  `RumorDelayMinutes` old (one per subject, territory and age band; the real trace and its items are untouched, and nothing
  of its witness, region or direction passes), and **planted** ones (`plantRumor`, paid by the subject at a Broker for a
  territory of their choosing). Both are identical in every field a reader can see, have confidence `RUMOR` (a trigger
  refuses any other for a `RUMOR` trace's item), live at most to the `RECENT` band's end, and are disclosed only under
  `LEDGER_RUMOR`. They are budgeted like any witness's traces, per (subject, rumor network), and enter the combined bound
  (§4.3) with the whole territory as their implied area, so they never narrow anything below a territory. No other
  purpose discloses a `RUMOR` trace, and no `RUMOR` trace becomes `WITNESS`, `CONFIRMED` or history.
- **Identity evidence** (`recordIdentity`): an `IDENTITY` trace with its row in `lc_identity_evidence` (`kind`
  `ALIAS_LINK`, `PATRON_CLAN` or `PATRON_NAME`; a typed `payload`: the alias and the character, or the clan or character
  or `OBSCURED`; the seal or alias it is **bound** to; `expires_at` equal to its trace's). Recorded only by the Black
  Ledger at its certified events (black-ledger.md §4.9, §4.10). Its items have precision `NONE` (a trigger), it has no
  implied area and is outside the combined bound, and it is not budgeted per witness; it is disclosed only by its own
  rules, which replace §4.2's authorisation for it: `LEDGER_IDENTITY` (an `ALIAS_LINK`, to any character but the alias's
  owner, at a Broker in person, each disclosure paid) and `LEDGER_REVERSE` (`PATRON_*`, only to the current holder of the
  bound seal, at a Magistrate in person, in the reverse case's step order). No contract need be live.

## 5. Facts

| Fact | Delivery | Fields |
|---|---|---|
| `Intel.Disclosed` | outbox | intel id, requester (char), purpose, precision, units spent, granted (true for a grant of an existing item) |
| `Intel.Suppressed` | outbox | until (on the grid); the witness, the subject and the bribe fact only as expiring data (`lc_fact_expiring`, with the suppression's life) |
| `Intel.Purged` | outbox | counts of traces, items, grants and copies deleted |
| `Intel.RumorPlanted` | outbox | trace, territory, life, payer fact (the subject only inside the expiring trace) |
| `Intel.IdentityRecorded` | outbox | trace, kind, witness kind (never the payload) |

No fact names a subject's place, a witness's place or a trace's time.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| INTEL-INV01 | no coordinate, `Location`, world object or evidence leaves the service; no payload field outside its registered type | by the static test over public types; by the payload validator at write; by the database grants |
| INTEL-INV02 | per (subject, witness, rolling window) units never exceed the budget; per subject and window the combined area never falls below the floor, whoever asks | by the row lock and checks before commit; at every purge pass |
| INTEL-INV03 | no trace, evidence, item, grant or registered copy outlives its `expires_at` + purge interval; no item outlives its trace, no copy its items | by the purge job, which alarms on what it found late; by checks at write |
| INTEL-INV04 | every `SIGHTING` and `INFORMANT` trace was eligible under §3 with geodata present | by construction; by the witness property tests |
| INTEL-INV05 | no `lc_pub_*` row breaks its type's field rules | by the publication validator at write; by property tests |
| INTEL-INV06 | no trace, evidence or subject place is in the journal | by the fact type registry (no intel fact has such a field) |

## 7. Failure model

| Fault | End state |
|---|---|
| service down | nothing is disclosed; questioning and trace purchases are refused; play continues (04 §8) |
| purge late | an alarm; expired items are refused by `intel.read` before they are deleted |
| sampling overloaded | witnesses deferred round-robin, their pairs reset: fewer sightings, never false ones |
| geodata missing | no sighting there |
| restore from backup | expired rows of every listed table deleted before the server starts |

## 8. Configuration

`config/Custom/LivingChronicle/Intel.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `DisclosureBudgetUnits` | 1–100 | 14 | 14 everywhere | AT-INTEL-02 |
| `MinCombinedCells` | 1–400 | 9 | 9 everywhere | AT-INTEL-12 |
| `PublicationTacticalDelayMinutes` | 1440–10080 | 1440 | 1440 everywhere | AT-INTEL-07 |
| `SightingSampleMillis` | 250–10000 | 2000 | 2000 / 2000 / 1000 / 2000 / 2000 | AT-INTEL-04 |
| `MinConsecutiveSamples` | 1–20 | 3 | 3 everywhere | AT-INTEL-04 |
| `SightingPassMicros` | 100–100000 | 5000 | 5000 everywhere | AT-INTEL-09 |
| `DisclosureWindowMinutes` | 1–1440 | 60 | 60 everywhere | AT-INTEL-02 |
| `DisclosureQuantumMinutes` | 1–60 | 5 | 5 everywhere | AT-INTEL-11 |
| `TraceLifeHot/Recent/StaleMinutes` | 1–1440 | 15 / 60 / 360 | as default everywhere | AT-INTEL-03 |
| `SignalLocationTtlMinutes` | 1–60 | 10 | 10 everywhere | AT-INTEL-13 |
| `RumorDelayMinutes` | 5–360 | 30 | 30 everywhere | AT-INTEL-15 |

Tables in the same file, with their values reviewed like the spec: witness kinds (range, minimum observation, ceiling, bribable, maximum suppression);
the unit cost of the *n*-th disclosure about a subject from a witness in a window (default 1, 2, 4, 8, …) and the
precision each cumulative cost allows (default: up to 2 units `ZONE`, up to 6 `REGION`, up to 14 `TERRITORY`, then `NONE`).

## 9. Interface

Players reach the service only through the modules' pages (Ledger dossiers, witness questioning at NPCs, Signal answers,
Wayfarer observations), which show intel with its precision, confidence and age band, never more. Admin: `lc why <intel>`
prints the trace, its evidence (forensics only), the budget and area state, and the grants.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| INTEL-R01 | No coordinate, location, world object or evidence MUST leave the service, and no payload MUST hold a field outside its registered coarse type. | AT-INTEL-01 |
| INTEL-R02 | Disclosure about a subject from a witness MUST be budgeted over a rolling window, up to `DisclosureBudgetUnits`, with diminishing returns that no account, contract or clan resets, and spent atomically; the ledger MUST expire with the window. | AT-INTEL-02 |
| INTEL-R03 | Traces, evidence, items, grants and copies MUST be deleted at expiry, in backups and restores too; no item MUST outlive its trace nor copy its items; nothing expired MUST be readable or rebuilt. | AT-INTEL-03 |
| INTEL-R04 | A sighting MUST be a trace only if §3 held, with geodata present, for the minimum consecutive samples; any failure MUST reset the observation. | AT-INTEL-04 |
| INTEL-R05 | A trace MUST yield at most one item; any later request about it MUST receive a grant of that item, viewed no finer than the item or the purpose allows and living no longer than either, never a refinement. | AT-INTEL-05 |
| INTEL-R06 | Every module table referring to intel MUST hold ids only, read through `intel.read`, register its copies with all their dependencies in the writing transaction, and be listed as expiring. | AT-INTEL-06 |
| INTEL-R07 | Nothing published MUST break its type's field rules or come sooner than its type's delay; unknown types MUST be refused; location-related types MUST wait at least `PublicationTacticalDelayMinutes`. | AT-INTEL-07 |
| INTEL-R08 | A suppressed witness MUST disclose nothing about its subject until the suppression ends, and the trace and audit MUST remain. | AT-INTEL-08 |
| INTEL-R09 | A sampling pass MUST stay within `SightingPassMicros`, deferring witnesses round-robin and resetting their pairs. | AT-INTEL-09 |
| INTEL-R10 | Every disclosure MUST be recorded as an outbox fact without the subject's place, the witness's place or the trace's time; no trace MUST be journaled. | AT-INTEL-10 |
| INTEL-R11 | A disclosure MUST be authorised by the service from the authenticated requester, in person where its purpose requires, and every read MUST be authorised again against current grants, memberships and purpose rules. | AT-INTEL-14 |
| INTEL-R12 | The implied areas of positive answers about a subject in a window MUST NOT intersect below `MinCombinedCells`, and a refusal MUST be indistinguishable from an absence. | AT-INTEL-12 |
| INTEL-R13 | Signal locations MUST be at most `REGION`, live `SignalLocationTtlMinutes`, and cover only opted-in, interactive, non-blocking characters whose visibility includes the requester, checked at every read. | AT-INTEL-13 |
| INTEL-R14 | Every evidence-driven change of an answer (appearance, age band, expiry) MUST happen on the quantum grid. | AT-INTEL-11 |
| INTEL-R15 | Suppression MUST apply only to bribable witness kinds within their maximum, on the quantum grid; rumors MUST be their own traces (heard or planted, identical to a reader), disclosed only under `LEDGER_RUMOR`, always at `RUMOR` confidence, budgeted and bounded at territory size, never changing a real trace's items. | AT-INTEL-15 |
| INTEL-R16 | Identity evidence MUST be recorded only by the Black Ledger at its certified events, stored with its kind, payload and binding, expire with its trace, carry precision `NONE`, and be disclosed only under `LEDGER_IDENTITY` and `LEDGER_REVERSE` by their own rules. | AT-INTEL-16 |

| Test | Kind | What it does |
|---|---|---|
| AT-INTEL-00 | integration | the migration applies; keys, checks and triggers reject what this spec forbids: no exact precision value; one item per trace; evidence, items, grants and copies outliving their parents at insert; any change of an expiry; a location-related publication type with less than the tactical delay |
| AT-INTEL-01 | unit, integration | the static scan of the service's public types finds no coordinate, `Location`, world object or evidence; payloads with a planted coordinate, instance, precise time or finer area id under an allowed precision are refused; the game server's user cannot read the evidence table |
| AT-INTEL-02 | integration | several accounts, contracts and clans asking about one subject from one witness, concurrently and across window boundaries: units charged by the configured table (1, 2, 4, 8), precision by the configured ladder, cumulative 1, 3, 7, and the fourth charged disclosure in a window refused (7 + 8 > 14), a grant of an existing item costing nothing; after the window the ledger rows are gone |
| AT-INTEL-03 | integration, restart | items, grants, evidence, traces and every copy (multi-dependency rows included) gone at expiry; an item made just before its trace expires dies with it; a restore of an old backup deletes expired rows before start; a rebuild and a replay after expiry give "expired lead" at most |
| AT-INTEL-04 | integration | sightings across walls, floors, invisibility, hide, teleport, login, doors, summons, instances, missing and partly loaded geodata, and a target leaving and returning between samples: a trace exactly when §3 held for the consecutive samples |
| AT-INTEL-05 | integration | one trace asked by two audiences at two precisions: one item, at the first precision, two grants, one cost; a Ledger item at `ZONE` granted to Signal is viewed at `REGION` and expires at Signal's TTL |
| AT-INTEL-06 | unit | a module table holding an intel payload, or an intel id without registration or not listed as expiring, and module code reading intel other than through `intel.read`, fail the scan of migrations and code |
| AT-INTEL-07 | integration | property test over every publication type: no row breaks its field rules or its delay; an unknown type is refused; a location-related type registered below the tactical delay is refused; two hunters with the same private knowledge (everything one account could legitimately learn), one also given the website and history filtered to humans, locate a mark equally often within the statistical margin: the publication adds nothing (17) |
| AT-INTEL-08 | integration | a bribed witness discloses nothing about its subject until the suppression ends; the trace and audit remain |
| AT-INTEL-09 | sim | 800-citizen siege benchmark: every sampling pass within `SightingPassMicros`; under overload, deferred witnesses served next and no false sighting |
| AT-INTEL-10 | integration | every disclosure writes its fact in its transaction; no intel fact type has a place, witness place or trace time field; no trace is journaled |
| AT-INTEL-11 | integration | a visit just after a quantum boundary is disclosable only at the next; a client polling every second sees the answer appear, change band and vanish only at grid points |
| AT-INTEL-12 | integration | cooperating requesters asking several witnesses (ranges overlapping, directions given) about one subject: the intersection of the implied cells of their answers never falls below the floor within a window; a refused answer is byte-equal and price-equal to an answer about no trace |
| AT-INTEL-13 | integration | Signal: a non-opted-in, offline, detached, blocking or not-visible-to-the-requester character is never located; an opted-in one only at `REGION`, for the TTL; an opt-out or block after a grant hides it at the next read |
| AT-INTEL-14 | integration | a disclosure asked remotely, for another contract, with a forged budget or audience, or by a non-party: refused; a reader who left the clan or contract of a grant reads nothing |
| AT-INTEL-15 | integration | a bribe at a guard refused, at a gatekeeper beyond the kind's maximum capped; suppression starting and ending on the grid; a real trace disclosed as `WITNESS` before and after its heard rumor, in both orders, unchanged; heard and planted rumors indistinguishable in fields, never above `RUMOR`, never under another purpose; rumors in the combined bound at territory size |
| AT-INTEL-16 | integration, unit | identity evidence recorded by another module refused (static scan and a database grant); an `ALIAS_LINK` never to its owner, only at a Broker in person; `PATRON_*` only to the bound seal's holder, in step order, after its contract ended; no identity item carries an area; identity evidence gone with its trace |

## 11. Upstream constraints

1. **Line of sight succeeds where there is no geodata** (`geoengine/GeoEngine.java:525-528`: the same-cell fast path
   returns true when a cell has no geodata; traversal skips checks in cells without it). → `canWitness` checks coverage
   with `GeoEngine.hasGeo` (:874) and fails closed (§3).
2. **Visibility and ranges exist in Mobius**: `WorldObject.isInvisible` / `isVisibleFor` (`entity/WorldObject.java:971,
   1004`); `World.getVisibleObjectsInRange` allocates a list (`entity/World.java:695`), so sampling iterates the regions'
   own visible-object sets instead (`entity/WorldRegion.java:41`).
3. **NPCs have no notion of having seen anyone.** → observation is new, bounded and measured (§3).

## 12. Review record

- Revision 6 signed off with black-ledger.md in the Ledger's round 6.
- Revision 6 (after the round-5 sign-off): the Black Ledger (black-ledger.md) needs suppression, rumors and identity
  evidence as Intel operations, two trace kinds, four purposes and publication field rules; they go to review with the
  Ledger. Its round 1 found rumors fixed by a trace's first item, rumors and identity evidence outside the budget and
  bound rules, identity payloads without storage, suppression unregistered and off the grid, and contradictory publication
  exceptions; revised: rumors as their own heard or planted traces, `lc_identity_evidence` with bindings and its own
  authorisation, suppression on the grid and registered, three conditional field rules.
- Round 5 (Codex), **SIGN-OFF WITH FIXES**: one test fix, made (the exact-precision assertion now uses an otherwise
  valid row, so only the precision can fail it). Remaining are named implementation obligations: authorisation,
  grant-preserving Wayfarer reads, payload validation, the copy scan, purge and restore, publication validation and the
  sampling benchmark, each with its acceptance test.
- Round 4 (Codex), NO SIGN-OFF: one open, an item expiring before its trace could be purged and replaced by a finer one.
  Revision 5: an item expires exactly with its trace (trigger); shorter lives are the grants'.
- Round 3 (Codex), NO SIGN-OFF: open were Wayfarer recall able to create a grant wider than the one it came from,
  reparenting and re-precisioning rows after the insert checks, items outliving evidence that expired earlier than its
  trace, implied areas centred on spawn points for moving witnesses, a timing promise at odds with immediate revocation,
  and the budget test's arithmetic. Revision 4: Wayfarer reads through the existing grant; Intel rows never updated;
  evidence expires with its trace; the observer's position recorded and used; the timing promise limited to
  evidence-driven changes; the test asserting refusal of the fourth charged disclosure.
- Round 2 (Codex), NO SIGN-OFF: most of round 1 fixed or partly; open were a disclosure ledger that kept the trail,
  grants that had to break Signal's limits, a combined bound blind to witness range and direction, expiry undoing the
  time quantum, reads and copies not authorised or expiry-checked at read, Signal visibility, expiries changeable after
  insert, a budget without a capacity, a publication oracle with the wrong baseline, and no tactical delay. Revision 3:
  the ledger expires with its window and is registered; grants have their own view and expiry, never finer or longer than
  item and purpose; implied areas in cells, with refusals indistinguishable from absence; every answer change on the
  grid; reads authorised every time, modules keep ids only; Signal visibility; immutable expiries and evidence bounded by
  its trace; `DisclosureBudgetUnits`; the oracle compares hunters with equal private knowledge; a publication registry
  with a tactical-delay floor.
- Round 1 (Codex, different model family), NO SIGN-OFF, 17 findings: traces journaled as immutable trails; no
  authorisation; deduplication against audience isolation and across precisions and windows; no combined bound; timing
  of answers; Signal's restrictions missing; lifetimes not bounded by evidence; backups of the new tables; copies with one
  dependency and readable caches; no executable publication policy; payloads unconstrained; missing geodata passing;
  sampling without a state machine, bounds or fairness; no informant path; tests without oracles. Revision 2: traces and
  evidence never journaled; authorisation by the service from the authenticated requester; one item per trace and grants;
  rolling windows; a combined-area floor; quantised availability and age bands; purpose ceilings; lifetimes ordered by
  evidence; an expiring-table registry driving purge, backup and restore; multi-dependency copies registered in the
  writing transaction and no cached payloads; typed payloads and typed publication rows, failing closed; geodata coverage
  failing closed; an observation state machine and a bounded round-robin sampling pass; registered informants; and
  acceptance cases with configured oracles.
