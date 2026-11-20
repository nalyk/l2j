# SPEC-RT — Retail Truth

- Status: draft (revision 2, answering review round 1; record in §12)
- Blueprint: 11 (Retail Truth); 02 §9 (show me the test); 03 §3 (upstream intake), §5 (the H5 content inventory);
  30 §2 (delivery: the lock in every gate); 17 §5 (the Truth Report on the website).
- Depends on: profiles.md (the `truth` profile), chronicle.md (the website that serves the report).
- DDL: none. Data: `truth/` and `test/lock/` in the repository.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31).

## 1. Scope

Two artifacts, never confused (11): **Retail Truth cases** say, with evidence, what retail High Five does and how this
server compares (correctness); the **behaviour lock** says only what this server does now, so nothing moves it silently
(stability). This spec owns the case format and its rules, the status machine and the regression contract, the lock's
change rule and `DESIGN_OVERRIDE`, the coverage plan, the H5 content inventory and the Truth Report.

It does not own the profile mechanism (profiles.md) or the website (chronicle.md §4.9).

What exists and what this spec adds (a draft specifies ahead of code, README "Status of a spec"; nothing below claims the
second column is enforced today):

| Exists | Specified here, not yet built |
|---|---|
| the lock's goldens `test/lock/**.tsv`, compared by `test/java/org/l2jmobius/testing/Golden.java`, which writes what a run observed to `build/lock-actual/` and has no switch to update a golden | the line-identity file `truth/lock-keys.csv` and `tools/truth/check-lock-changes.sh` (§4.3) |
| the lock suites `test/java/org/l2jmobius/gameserver/*LockTest.java`, booted by `TestServer`, which applies `TruthProfile` (every custom module neutral or off) and runs with keyed `Rnd` streams, the controlled clock and a per-run `TestDatabase` | the profile refusal and the suite separation of AT-RT-05 (§4.3) |
| the intake gate that may not change a golden: `tools/intake/check-intake-goldens.sh`, `tools/intake/intake.sh` | `tools/truth/check-statuses.sh` (§3, §4.3) |
| the case directory `truth/cases/` (`truth/README.md`, `RT-AI-0001.yaml`) | `truth/evidence/`, `truth/overrides/`, `truth/coverage.csv`; `CaseFormatTest` (AT-RT-00), `TruthCaseTest` (AT-RT-01), `TruthReport` (§4.6) |
| the H5 content inventory: `org.l2jmobius.testing.truth.Inventory`, `InventoryTest`, `InventoryCurrentTest` (AT-RT-06), `truth/inventory/` | — |

## 2. Data

```text
truth/cases/RT-<DOMAIN>-<NNNN>.yaml     one case (§2.1)
truth/evidence/<source>.yaml            one evidence source: class, title, locator, region, update, confidence, retrieved, sha256
truth/overrides/DO-<NNNN>.yaml          one DESIGN_OVERRIDE (§2.2)
truth/lock-keys.csv                     per golden file: its key columns, or `line` (§4.3)
truth/coverage.csv                      domain, feature, partition, case ids (§4.4)
truth/inventory/<table>.csv             the H5 content inventory (§4.5): quests, instances, bosses, systems
truth/inventory/systems.in              the hand-kept list of world systems the inventory tracks (§4.5)
test/lock/<suite>/<name>.tsv            the lock's goldens (existing)
```

### 2.1 A case

The format of 11 §1.3, with these fields and rules. Each rule is checked by `CaseFormatTest` (AT-RT-00) on every case
file at every gate.

| Field | Rule |
|---|---|
| `id` | `RT-<DOMAIN>-<NNNN>`, the file's name; `DOMAIN` one of `COMBAT`, `SKILL`, `AI`, `ECONOMY`, `SOCIAL`, `CONTENT`, `WORLD`; never reused |
| `feature`, `chronicle`, `region` | a feature key; `ct26_highfive`; the region whose service the case describes (`KR`, `NA`, `EU`, `RU`, `JP`, or `any` when the evidence shows no regional difference) |
| `client_build` | the pinned client build hash, required when any evidence is of class 1 |
| `evidence` | an append-only list of entries `{id, class, source, property, locator, confidence}`, and for a refutation `refutes: <entry id>` (§3.3) |
| `preconditions`, `actions` | what the check sets up and does |
| `expected` | a list of items `{property, value, tolerance, from, derivation}`: what retail does, **derived from the evidence, never from running the server**; `from` lists the evidence entries the value rests on, `derivation` says in words how the value follows from them; empty only while `UNKNOWN` |
| `observed` | what the server does (class 5: current behaviour only) |
| `trials` | for a probabilistic item: `{n, ci, seed}` (§4.1) |
| `check` | the executable check (a test method), required for every status but `UNKNOWN` |
| `result` | written by a person from a run of `TruthCaseTest`, required for every status but `UNKNOWN`: `{server_commit, outcome, measured}`, `outcome` as §3.1 requires for the status |
| `status` | §3 |
| `fixed` | `true` once the case has been `EXACT` or `FUNCTIONAL`; kept until a refutation (§3.3) |
| `approved_by` | the reviewer who read the evidence and confirmed that each `derivation` is what the cited sources say; required for every status but `UNKNOWN` |
| `golden_refs` | golden lines this case's behaviour appears in: `{file, key}` with every key column of the file's `truth/lock-keys.csv` entry given exactly (§4.3) |
| `inventory` | inventory rows the case judges, each `<table>:<key>` (§4.5) |
| `evidence_wanted`, `notes` | free text |

**Evidence rules** (11 §1.2: evidence is scoped to client build, region, update and observable property):

- **E1 Support.** Every `expected` item names at least one entry in `from`; every entry it names has class 1–4 and the
  item's `property` exactly. An entry about another property supports nothing, however official its source.
- **E2 Class 5 never supports.** An entry of class 5 may stand in `evidence` (it records what the server did) and is never
  named in `from`, alone or beside a class 1–4 entry.
- **E3 Client data only for client-visible properties.** A property is named `client.<what>` when the client shows or
  computes it (cast time, reuse, MP cost, range, item and NPC stats shown, text) and `server.<what>` otherwise (rates,
  AI, transactions, timers, drops). An entry of class 1 names only a `client.` property.
- **E4 Scope.** Each entry's source file has `update` `ct26_highfive` (an evidence source about another update supports
  nothing) and a `region` equal to the case's or `any`; the entry's `class` equals its source file's `class`; the source
  file exists with `locator`, `retrieved` and `sha256` of the retrieved copy.
- **E5 Refuted entries do not support.** An entry another entry `refutes` is never named in `from`.
- **E6 Review.** A case above `UNKNOWN` has `approved_by`. The gate checks E1–E5 mechanically; whether a patch note says
  what `derivation` claims is a reader's judgement, recorded by name and published with the case text in the Truth Report.

### 2.2 A DESIGN_OVERRIDE

```yaml
id: DO-0001
domain: COMBAT
lines:                                   # each changed golden line, by its exact key, with its values before and after
  - file: test/lock/combat/monster-physical.tsv
    key: {attacker: "20120", target: "20130", from: front, attack: "1"}
    before: "20120\t20130\tfront\t1\tfalse\tfalse\tfalse\t41\t82"
    after:  "20120\t20130\tfront\t1\tfalse\tfalse\tfalse\t45\t90"
retire: []                               # golden files this override retires (a lock withdrawn), each with its reason
intended_change: what changes and why it is a design decision, not a correction
player_note: the line players read in the Truth Report's list of deviations
cases: [RT-COMBAT-0007]                  # the cases it makes deviate, with their status kept honest
approved_by: the reviewer
```

An override names golden **lines**, never a pattern, a file or a set of files (11 §2: "never blanket-approves regenerated
goldens"); §4.3 says when it authorises a change.

## 3. State machine: a case's status

`EXACT` (matches evidence within tolerance), `FUNCTIONAL` (works, equivalence unproven), `APPROXIMATE` (measurably
differs), `BROKEN` (does not work), `MISSING` (absent), `UNKNOWN` (no sufficient evidence). The order, best first:
`EXACT`, `FUNCTIONAL`, `APPROXIMATE`, `BROKEN`, `MISSING`; `UNKNOWN` is outside it.

### 3.1 The outcome each status requires

A status is a claim about a recorded run. `TruthCaseTest` runs every case's check at every gate and compares the run with
the claim. A run never sets a status.

| Status | `check` does | `result.outcome` recorded | The gate's run | A run that disagrees |
|---|---|---|---|---|
| `EXACT` | drives the feature and measures every `expected` item | `within`: every item within its `tolerance` (a probabilistic item `WITHIN`, §4.1) | must be `within` | fails the build |
| `FUNCTIONAL` | drives the feature's flow to its end; asserts every `expected` item it can (a `server.flow.` property: the quest completes, the instance closes) | `completes` | must complete with every flow item true | fails the build |
| `APPROXIMATE` | as `EXACT` | `outside`: the flow completes and at least one item is outside its tolerance (`OUTSIDE`), `measured` recorded | must complete and be outside | outside → passes; `within` → reported as a candidate for `EXACT`; does not complete → fails the build |
| `BROKEN` | as `FUNCTIONAL` | `fails`: the flow does not complete, or a non-numeric item is false; the failure message recorded | must fail | completes → reported as a candidate for a repair |
| `MISSING` | an absence probe: looks up the feature's entry (the quest class, the NPC template, the handler, the inventory's `HAND` row) | `absent` | must find it absent | present → reported as a candidate |
| `UNKNOWN` | optional | none | runs and is reported | never fails |

An `INCONCLUSIVE` probabilistic item (§4.1) fails an `EXACT` or `APPROXIMATE` case's run: its `n` must grow.

### 3.2 Transitions

The status lives in the case file; a transition is the difference between a case in the base and in the change.
`tools/truth/check-statuses.sh <base>` compares the two (with git, on the host; not inside the build container) and
`CaseFormatTest` checks the guards that need only the change. **J(s)**, "judged `s`", is: E1–E6 hold, `result.outcome` is
what §3.1 requires for `s`, and the gate's run agrees. **N**, "new knowledge", is: the change appends at least one
evidence entry and the `expected` items it changes cite it. **R**, "refuted", is §3.3. A transition not in this table is
refused.

| From | To | Guard | Effect |
|---|---|---|---|
| — (no file in the base) | `UNKNOWN` | the id is new and well formed | — |
| — | any other | as `—` → `UNKNOWN` followed in the same change by `UNKNOWN` → it | as that move |
| `UNKNOWN` | `EXACT`, `FUNCTIONAL` | J(to) | `fixed: true` |
| `UNKNOWN` | `APPROXIMATE`, `BROKEN`, `MISSING` | J(to) | — |
| `MISSING` | `BROKEN`, `APPROXIMATE` | J(to) (repair in progress) | — |
| `BROKEN` | `APPROXIMATE` | J(to) | — |
| `MISSING`, `BROKEN`, `APPROXIMATE` | `FUNCTIONAL`, `EXACT` | J(to) (repair) | `fixed: true` |
| `FUNCTIONAL` | `EXACT` | J(EXACT) and N (equivalence newly provable) | — |
| `FUNCTIONAL` | `APPROXIMATE` | J(APPROXIMATE) and N, in a change that touches nothing outside `truth/`: precise evidence shows a difference the server already had, not one a code change made | — |
| `APPROXIMATE` | `BROKEN`, `MISSING` | J(to) and `fixed` is not `true` | — |
| `BROKEN` | `MISSING` | J(MISSING) and `fixed` is not `true` | — |
| any | `UNKNOWN` | R | `expected` items resting on refuted entries removed; `result`, `approved_by` cleared; `fixed` cleared |
| any | any lower, or a move this table otherwise refuses | R, then J(to) on the remaining and new evidence, in the same change | as R, then as J(to) |
| any | the same status | when `expected`, `tolerance` or `trials` change: N, and J(status) again with a new `approved_by` | — |

Refused, unless R: `EXACT` to anything lower; `FUNCTIONAL` to `BROKEN` or `MISSING`; any case with `fixed: true` to
`BROKEN` or `MISSING` (a fixed defect reintroduced, 11 §4). Refused always: a case file in the base absent or renamed in the
change (a case is never deleted; a mistaken one is refuted to `UNKNOWN` with a note); an evidence entry of the base removed
or altered (the list is append-only); `fixed: true` cleared without R; `trials.seed` changed once set; `trials.n` lowered;
an `inventory` entry of a case above `UNKNOWN` removed, unless replaced in the same change by the entry of the row that now
holds the same content (a renamed quest, a renumbered template), said in `notes`.

"Never upgrade UNKNOWN to EXACT because nobody complained" (11 §1.1): every upward guard needs evidence, not silence.

### 3.3 Refutation

A refutation is an evidence entry appended to the case with `refutes: <entry id>`, of class 1–4, for the same `property`
as the entry it refutes, whose source satisfies E4 and which a reviewer approves (`approved_by` of the change). R holds for
a case when every entry that its current status rests on (named in `from` by an `expected` item) is refuted, or the items
resting on unrefuted entries are removed by the change. The refuted entry stays in the file: refutation supersedes, it does
not erase, so a later change sees why the case fell and the history needs no earlier base. `check-statuses.sh` therefore
compares a change with its base only: `fixed` carries what "fixed before" means.

## 4. Operations

### 4.1 Writing a case; probabilistic properties

Cases are seeded where work on the server meets a question the code cannot answer (truth/README.md), and by the coverage
plan (§4.4). Expected results come from evidence (§2.1).

A probabilistic item states the expected rate `p0` and a **tolerance** `δ` (an absolute rate, required, `0 < δ`, derived
from the evidence's precision: a guide's "about 30%" is not `±0.1%`). Trials run on the keyed stream
`(trials.seed, "truth.<case id>", trial)` under the `truth` profile, `seed` fixed once the case is judged (§3.2).

- **Power, at authoring.** `trials.n` must be at least `n_min = ⌈z² · p0 (1 − p0) / (δ/2)²⌉`, `z` the two-sided normal
  quantile for `ci` (default 0.99, `z` = 2.5758): the interval of a run at `p0` is then about `δ/2` wide each side. A case
  with a smaller `n` is refused by AT-RT-00.
- **Verdict.** With `k` successes in `n` trials, `[L, U]` the Wilson interval at `ci`: `WITHIN` when `p0 − δ ≤ L` and
  `U ≤ p0 + δ`; `OUTSIDE` when `U < p0 − δ` or `L > p0 + δ`; `INCONCLUSIVE` otherwise. `EXACT` needs `WITHIN`,
  `APPROXIMATE` needs `OUTSIDE` (§3.1).
- **Why an absent mechanic cannot pass.** For `p0` 0.001 and `δ` 0.0005, `n_min` is 106 053. A server that never triggers
  the mechanic gives `k` 0 and `[0, 0.0000626]`: `U < p0 − δ`, `OUTSIDE`. The interval-membership test of revision 1
  accepted `0/2 000` (its interval `[0, 0.0033]` holds 0.001); here `n` 2 000 is refused before any run, and a rate of
  `2·p0` gives `[0.00168, 0.00238]`, `OUTSIDE`. A sample at `p0 + δ` gives `[0.00122, 0.00184]`, `INCONCLUSIVE`.

A deterministic item is `within` when its measured value is within `tolerance` of `value` (equal when `tolerance` is
absent).

### 4.2 Running cases

`TruthCaseTest` runs every case's `check` in the `truth` profile and compares the run with the case's claim as §3.1 says. It
writes each case's run (outcome, measured values, verdicts) to `build/truth-actual/<id>.yaml`, from which a person copies a
`result` when judging; the suite cannot write a case (the checkout is read-only, as for goldens). Its candidates (a run
better than the claim) are listed in the same directory and in the build log.

### 4.3 The regression contract and the lock

- **Status regression** (AT-RT-02): `tools/truth/check-statuses.sh <base>` refuses every move §3.2 refuses.
- **Line identity.** `truth/lock-keys.csv` names, for every file under `test/lock`, its key: the header columns that
  identify a line (`attacker,target,from,attack` for `combat/monster-physical.tsv`), or `line` for a file without a header
  or a unique key (the boot log, the world summaries). The check refuses a golden without an entry, an entry without a
  golden, and a keyed file in which two data lines share a key. Comment lines (`#`) are identified by their text.
- **A golden's change** is the difference between the base's and the change's file: for a keyed file, each key whose line
  was added, removed or changed, with its line before and after; for a `line` file, the multiset difference of its lines
  (a repeated log line counts each time), each line removed or added.
- **Lock changes** (AT-RT-03): `tools/truth/check-lock-changes.sh <base>` fails unless every changed, added or removed line
  is authorised by one of:
  - a **correction**: a case changed in the same change whose `golden_refs` name the file and the line's exact key, whose
    status in the change is `EXACT` or `FUNCTIONAL` and in the base was lower or rested on other `expected` values (N),
    and whose check passes in this gate's run. The line then moves to behaviour a case judged against independent
    evidence; a judgement of `UNKNOWN`, `BROKEN` or `MISSING`, or evidence added to a case left `UNKNOWN`, authorises
    nothing;
  - an **override added in the same change** (its file absent in the base) whose `lines` give the file, the exact key
    (every key column; for a `line` file, no key) and the line's `before` and `after` exactly. An override in the base
    authorises nothing: an override authorises the change that adds it, once; an override file of the base edited is
    refused.

  An override line that is not an actual change is refused (no authorisation in advance). Every line an override changes
  is written out with both values, so a regenerated file passes only when each of its changed lines was stated and
  reviewed; that is not a blanket approval, and the next regeneration needs its own. A new golden file (a new lock) needs
  its `lock-keys.csv` entry and authorises nothing else; removing a golden file needs an override added in the change that
  names it in `retire` with a reason; a renamed golden is a retirement and a new lock. A passing lock proves stability only,
  never correctness.
- **Intake** (existing): an intake branch may change no golden (`check-intake-goldens.sh`), add no override and change no
  `lock-keys.csv` entry; a lock it fails is answered on the base by a correction or an override, never by recording the new
  behaviour.
- **The truth boundary** (AT-RT-05). The lock and `TruthCaseTest` boot through `TestServer`, which refuses to start unless
  the profile is `truth` (profiles.md); in a truth run, a draw of `Rnd` outside a keyed stream fails, the clock is the
  controlled one, the database is the run's own `TestDatabase`, and no Living Chronicle module is enabled. Living
  Chronicle's suites declare their profile (`dev` or `sim`) and run in their own JVM forks; a class that declares another
  profile found among the truth classes fails the build.

### 4.4 Coverage

`truth/coverage.csv` lists, per domain of 11 §1.4 (combat, skills, NPC and AI, economy, social and PvP, content, world),
its features and their equivalence partitions, boundaries and known interactions, with the case ids that cover each
row. A row without a case is reported, not a failure; the counts are part of the Truth Report (§4.6). The lock's suites
cover the class × skill × gear matrix through their goldens, recorded in the same file by suite.

### 4.5 The H5 content inventory

The inventory is generated by `org.l2jmobius.testing.truth.Inventory` (test-side tooling beside `TruthProfile`, so the
gate's JDK container runs it): `truth/inventory/quests.csv`, `instances.csv`, `bosses.csv` and `systems.csv`. Each row has
the generator's columns, among them `finding` (what the generator saw), `source` (`GENERATED` or `HAND`), the **derived**
columns `status` and `cases`, and `notes`, written by people.

**What is scanned.** Java sources are read without their comments (`//` and `/* */`, a marker inside a string, character
or text-block literal opening nothing); XML files without `<!-- -->` comments; SQL files without `--` and `/* */` comments.

- **Quests**: every quest class `Q<nnnnn>_<Name>.java` anywhere under `dist/game/data/scripts/quests/` (its own directory,
  or a shared one such as `TerritoryWarScripts/` and `Dummy/`), its id and name; a class under `Dummy/` has the finding
  "placeholder quest".
- **Instances**: every instance template (`dist/game/data/instances/**.xml`, its `id`), with the sources that name it, in
  the datapack's scripts or the core: an `int` or `int[]` constant whose name is a template or instance id
  (`TEMPLATE_ID`, `INSTANCE_IDS`, `TEMPLATE_ID_<suffix>`, and the like) assigned that number or an array (`{..}` or
  `new int[] {..}`) holding it; in a source that creates instances, a put into a map whose name names a dungeon, an
  instance or a template, `.put(<npc>, <id>)` or `.put(<npc>, new <Name>Dungeon(<id>`; or a literal
  `createDynamicInstance(<id>`. A number is a whole token: `101_000`, `101.5` and `0x65` are not 101. Other names that
  merely contain the words (a singleton `INSTANCE`, a level bound `INSTANCE_LEVEL_MIN`, a penalty), a price map's put, and
  anything in a comment are not references. A template no source names has the finding "no source names this template".
- **Bosses**: every `RaidBoss` and `GrandBoss` NPC template (`dist/game/data/stats/npcs/*.xml`), with where it is placed:
  a spawn file; a row of `raidboss_spawnlist` or `grandboss_data` (`dist/db_installer/sql/game/`), a row being a tuple that
  opens after `VALUES` or after the comma ending the previous tuple, on any line; the Dimensional Rift
  (`dist/game/data/DimensionalRift.xml`, its `mobId`); or a script naming its id as a whole number outside a comment and
  outside a `new <..>Location(..)` argument list (a negative number, a decimal and a longer token are not it). A script is
  a lead, not proof of a spawn: a coordinate written elsewhere than a location constructor can still read as an id, a
  limit the independent reading of AT-RT-06 bounds for the tables, spawns and the Rift but not for scripts. A boss placed
  nowhere has the finding "placed by no spawn, table or script".
- **Systems**: one row per line of `systems.in`, its note as the finding. It must hold at least the systems 11 §1.5 and
  this section name, by key: `seven_signs`, `seven_signs_festival`, `hellbound`, `seed_of_destruction`,
  `seed_of_infinity`, `seed_of_annihilation`, `castle_siege`, `fortress_siege`, `territory_war`, `olympiad`, `manor`,
  `clan_hall_auction`, `clan_hall_siege`, `fishing`, `underground_coliseum`, `kratei_cube`, `lottery`,
  `castle_tax_treasury`, `raid_boss_respawn`, `clan_reputation_skills`; it may hold more.

**Judgement comes from cases.** A row's `cases` are the cases whose `inventory` names it, in id order, and its `status` is
the most severe of their statuses in the order `MISSING`, `BROKEN`, `APPROXIMATE`, `UNKNOWN`, `FUNCTIONAL`, `EXACT` (a row
is not `FUNCTIONAL` while one of its cases is `UNKNOWN`), or `UNKNOWN` when no case names it. Neither is ever read from the
committed CSV: a status or case reference written by hand into a CSV is replaced by the regeneration, and AT-RT-06 fails
until the CSV agrees.

**Regeneration** (`Inventory.merge`) refreshes the generator's columns, keeps `notes`, derives `status` and `cases`, adds
new `GENERATED` rows, keeps `HAND` rows, puts every table in key order (numeric keys by number, others by text), and
refuses: a key held twice; a committed status outside §3's six or a source other than `GENERATED` and `HAND`; a case whose
`id` is not its file's name, whose status is not one of the six, or whose `inventory` names a table other than the four; a
case naming a row that is in neither the regeneration nor the committed `HAND` rows; a `HAND` row no case names, or one its
cases do not judge `MISSING`; a `HAND` row whose key the datapack now holds (the content is no longer missing).

A quest or system known to retail and absent from the datapack is a `HAND` row, judged `MISSING` by its cases.

**Why a judged row cannot be lost without a trace.** The gate runs in a container with the checkout mounted read-only and
no repository history (a worktree's `.git` points outside the mount), so it cannot compare with the base. It does not need
to: a judged row is anchored in its case file, not in the CSV. Deleting the content and its CSV row together, replacing a
CSV status with `UNKNOWN`, or deleting a `HAND` row leaves the case naming a row that is gone or a status the CSV does not
show, and the gate fails. Removing that link means changing the case: deleting it, lowering it or dropping its `inventory`
entry, each of which `check-statuses.sh` (with history, §3.2) refuses unless refuted or replaced.

AT-RT-06 fails whenever the committed files differ from a regeneration, so an intake that adds or removes content cannot
pass unseen; the regeneration is then written to `build/inventory-actual/` for review, and committing it is the reviewed
change. Known incomplete native paths, such as the Seed of Destruction defense state and the Festival's gaps (11 §1.5), are
rows with their findings and, once a case judges them, its status: implementation work, listed, never hidden as residual
risk; the Truth Report shows every finding (§4.6).

### 4.6 The Truth Report

`org.l2jmobius.testing.truth.TruthReport` builds the report from the case files, the overrides, the coverage and the
inventory, at the website's build. It shows, and AT-RT-07 checks each against the data:

1. **Cases**: per domain, the count of cases in each of the six statuses, zero counts shown.
2. **Known deviations**: every `APPROXIMATE`, `BROKEN` and `MISSING` case (never an `EXACT`, `FUNCTIONAL` or `UNKNOWN`
   one), each with its feature, status, evidence classes, and for `APPROXIMATE` the expected and measured values; and
   every override with its `player_note`, its cases and the number of golden lines it changed.
3. **Coverage**: per domain, the count of coverage rows and of rows without a case.
4. **Inventory**: per table, the count of rows in each status; and every row with a non-empty finding, with its finding
   and status (placeholder quests, templates no source names, bosses placed nowhere, systems with known incomplete paths).
5. The commit the report was built from.

It is a static page of public repository data (no game data, no player), served by the Chronicle website (chronicle.md
§4.9), in EN, RO and RU templates with the case text as written.

## 5. Facts

None: cases, overrides and the inventory are repository data, judged in CI.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| RT-INV01 | every case is well formed, and each `expected` item rests on unrefuted class 1–4 evidence of its property, update and region (E1–E5) | AT-RT-00, every gate |
| RT-INV02 | no change makes a move §3.2 refuses; no case file is deleted | AT-RT-02, every merge |
| RT-INV03 | every changed golden line is authorised by a correction or by an override added in the same change, line by line | AT-RT-03, every merge |
| RT-INV04 | the lock and the cases run only in `truth` | AT-RT-05, every gate |
| RT-INV05 | every inventory row's status and cases are those of the cases that name it, and every row a case names exists | AT-RT-06, every gate |

## 7. Failure model

| Fault | End state |
|---|---|
| a lock diff without a correction or override | the build fails, the observed behaviour written beside the golden (existing) |
| a run that disagrees with an `EXACT` or `FUNCTIONAL` case, or finds an `APPROXIMATE` one no longer completing | the build fails |
| a probabilistic item `INCONCLUSIVE` on an `EXACT` or `APPROXIMATE` case | the build fails; `n` must grow |
| an override naming a pattern, a file, or a line not changed | refused |
| evidence source missing, of another update or region, or of another class than its entry says | the case refused |
| a case file deleted | `check-statuses.sh` fails |
| a CSV status or case reference not given by a case | AT-RT-06 fails; the regeneration in `build/inventory-actual/` |
| a judged inventory row's content gone | the regeneration refused, naming the case |

## 8. Configuration

None at runtime. Case default: `trials.ci` 0.99, overridable per case. `trials.n` has no default: at least `n_min`
(§4.1).

## 9. Interface

The Truth Report page on the website. Developers: `./build.sh` runs the lock, the inventory and (once built) the cases;
`tools/truth/*.sh` (once built) the history checks, on the host.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| RT-R01 | Every case MUST follow §2.1, and every `expected` item of a case above `UNKNOWN` MUST rest on unrefuted evidence of class 1–4 of its property, update and region, class 1 only for a `client.` property, with a recorded derivation and reviewer. | AT-RT-00 |
| RT-R02 | A case's check MUST run in `truth` at every gate, and a run MUST meet the outcome §3.1 requires of the case's status, a run worse than an `EXACT`, `FUNCTIONAL` or `APPROXIMATE` claim failing the build. | AT-RT-01 |
| RT-R03 | A change MUST make only the moves §3.2 lists, with their guards: no lowering of `EXACT`, no move of `FUNCTIONAL` or of a `fixed` case to `BROKEN` or `MISSING` without a refutation (§3.3), and no deleted case or removed evidence entry. | AT-RT-02 |
| RT-R04 | A golden line MUST change only when a correction (a case judged `EXACT` or `FUNCTIONAL` on new evidence, naming the line's exact key) or an override added in the same change (naming the line's exact key, before and after) authorises it, and an intake MUST change none. | AT-RT-03 |
| RT-R05 | A probabilistic item MUST have `n` of at least `n_min` for its tolerance and be judged `WITHIN`, `OUTSIDE` or `INCONCLUSIVE` by its Wilson interval against `[p0 − δ, p0 + δ]`. | AT-RT-04 |
| RT-R06 | The lock and the cases MUST run only in the `truth` profile, and Living Chronicle's suites never in it. | AT-RT-05 |
| RT-R07 | The inventory MUST list every quest, instance and boss of the datapack and the listed world systems, each row's status and cases derived from the cases that name it. | AT-RT-06 |
| RT-R08 | The Truth Report MUST show every count, deviation, override, coverage gap and inventory finding of §4.6. | AT-RT-07 |

| Test | Kind | What it does |
|---|---|---|
| AT-RT-00 | unit | `CaseFormatTest`: every case file parses and satisfies §2.1; fixtures refused, each failing one rule: an `EXACT` case whose only evidence is class 5; an item naming a class 2 and a class 5 entry (laundering); an item naming an official entry about another property; a class 1 entry for a `server.` property; a source of another update; a source of another region; an entry whose class differs from its source's; a refuted entry named in `from`; an empty `expected` above `UNKNOWN`; a missing source; no `approved_by` above `UNKNOWN`; a judged status without `result`; a probabilistic item with `n` below `n_min`; and a well-formed fixture accepted |
| AT-RT-01 | integration | `TruthCaseTest` runs every check in `truth`; fixture cases whose server behaviour is changed: an `EXACT` measured outside fails the build, a `FUNCTIONAL` flow broken fails it, an `APPROXIMATE` flow broken fails it, an `APPROXIMATE` now within is reported as a candidate, a `BROKEN` now completing is reported, a `MISSING` now present is reported, an `UNKNOWN` never fails; and a status never changes in a case file |
| AT-RT-02 | unit | `check-statuses.sh` on fixture histories of two commits each: every row of §3.2 accepted with its guard and refused without it (`FUNCTIONAL` → `APPROXIMATE` with a code file changed refused); each refused move refused; a refutation with its entry accepted, without one refused; after an `EXACT` → `UNKNOWN` refutation, a later unchanged commit accepted; a `fixed` case moved to `BROKEN` without R refused; `fixed` cleared without R refused; a deleted case, a renamed case, a removed or altered evidence entry, a changed `seed`, a lowered `n`, an `inventory` entry dropped without replacement, each refused |
| AT-RT-03 | unit | `check-lock-changes.sh` on fixture commits: a golden line changed alone refused; covered by a correction accepted; by a case judged `BROKEN`, or `UNKNOWN` with evidence added, refused; by an override added in the change with exact key, before and after accepted; by an override of the base refused; an override with a wrong `before`, a partial key, a pattern, or an unchanged line refused; a `line` file with a repeated line, one copy removed, needing that removal stated; a new golden without its `lock-keys.csv` entry refused, with it accepted; a golden removed without `retire` refused; a keyed golden with a duplicate key refused; an intake branch changing a golden refused (`check-intake-goldens.sh`) |
| AT-RT-04 | unit | the verdict on known samples, `ci` 0.99: `p0` 0.001, `δ` 0.0005: `n` 2 000 refused (`n_min` 106 053); `0/106 053` `OUTSIDE` (an absent mechanic fails); `106/106 053` `WITHIN`; `212/106 053` `OUTSIDE`; `159/106 053` `INCONCLUSIVE`; a deterministic item within and outside its tolerance |
| AT-RT-05 | integration | the lock and `TruthCaseTest` started in another profile refuse to run; in a truth run, an unkeyed `Rnd` draw fails, the clock is controlled, the database is the run's own, every Living Chronicle module is off; a fixture class declaring `dev` among the truth classes fails the build |
| AT-RT-06 | unit | on fixture datapacks: each way of naming a template found (constant, array, `new int[]` array, map put with a bare id or a dungeon holder, literal, a constant after a string holding a comment marker), and none of the false ones (a singleton, a level bound, a penalty, a line comment, a block comment, a price map's put, `104_000`, a comparison, an unrelated holder); quests in shared directories; bosses' placements, with a commented-out table row, a negative and a positive coordinate in a location, a script comment, a commented spawn and a commented Rift room not counted, two rows inline on one line and the Rift counted; status and cases derived from case files, a CSV status and a case reference no case gives replaced, the most severe status of several cases; a case naming a gone row refused (content and row deleted together, the row deleted first, an `UNKNOWN` case); `HAND` rows kept in key order, textual keys too, refused when no case names them, when their cases are not `MISSING`, when deleted while a case names them; a case with a wrong id, an unknown status or an unknown table refused; a duplicate key, an unknown status or source refused; on the real datapack: the committed files equal a regeneration with the real cases; their ids equal the quest classes, template ids, raid and grand boss ids and `systems.in` lines, counted independently, with no duplicate; the systems §4.5 names all listed; every boss's table, spawn and Rift placement confirmed both ways by an independent reading (a DOM parser for XML, line by line for SQL); every instance reference's source holding the id outside a comment; known facts pinned (template 22 named by Q00511; template 2 not by `DefaultPageHandler`; templates 101–109 named by no source; 64, 88, 111, 127, 146 by their scripts; boss 25073 placed nowhere; 25333 by the Rift; 25143 and 25188 by the raid table; 25557, 25569 and 29177 by scripts) |
| AT-RT-07 | unit | on fixture data and on the repository: the report's case counts per domain and status equal the cases'; its deviation list is exactly the `APPROXIMATE`, `BROKEN` and `MISSING` cases plus every override; its coverage counts equal `coverage.csv`'s; its inventory counts equal the CSVs', and every row with a finding appears with it (the Festival and Seed of Destruction rows among them) |

## 11. Upstream constraints

1. **Mobius behaviour is not evidence of correctness** (11 §1.2). → class 5 never supports a status (E2).
2. **Client data establishes client-visible values only** (11 §1.2). → class 1 only for `client.` properties (E3).
3. **Upstream changes arrive by intake** (03 §3). → the intake gate may not change goldens, overrides or line keys.
4. **The gate container has no history** (`build.sh`: the checkout mounted read-only, `build/` the only writable path). →
   history checks run on the host (`tools/truth/*.sh`); the gate's own checks anchor judgements in case files (§4.5).

## 12. Review record

- Round 1 (Codex, a different model family), NO SIGN-OFF, 15 findings. Revision 2:
  1. Evidence support asserted without an oracle: **fixed**. Evidence entries carry ids; each `expected` item names its
     entries and a derivation; rules E1–E6 (property match, no class 5 support, class 1 only for `client.` properties,
     update and region scope from the source file, no refuted entry, a named reviewer), with a fixture for each in AT-RT-00.
     The gate cannot read a patch note: that part is a recorded human judgement, stated as such.
  2. Statuses without an outcome oracle: **fixed**. §3.1 gives each status its check, recorded outcome, the gate's run and
     what a disagreement does; `MISSING` needs an absence probe; AT-RT-01 covers each.
  3. No repairs in the machine: **fixed**. §3.2 lists every allowed move with guard and effect, repairs included.
  4. Refutation against "every earlier base commit", and no deletion rule: **fixed**. A refutation is an appended entry
     (§3.3); a persistent `fixed` marker replaces the scan of history, so a later unchanged commit passes; deleting or
     renaming a case, removing an evidence entry, changing `seed` or lowering `n` are refused; AT-RT-02 lists the histories.
  5. A correction need not correct: **fixed**. Only a case judged `EXACT` or `FUNCTIONAL` on new evidence, naming the
     line's exact key and passing in the same gate, authorises a golden line.
  6. Golden-line identity and change scope: **fixed**. `truth/lock-keys.csv` keys every golden (or `line` for a multiset
     of lines); additions, removals, new and retired files have rules; an override lists each line's key, before and after,
     authorises only the change that adds it, and may not list unchanged lines.
  7. Wilson membership passes an absent mechanic: **fixed**. A required tolerance, `n_min` from it, and a three-way
     `WITHIN` / `OUTSIDE` / `INCONCLUSIVE` verdict against `[p0 − δ, p0 + δ]`; the worked example and AT-RT-04 include `0/n`.
  8. Inventory judgements fabricated independently of cases: **fixed** in code. `status` and `cases` are derived from the
     case files' `inventory` entries, never kept from the CSV. Failed first: `statusAndCasesComeFromTheCaseFilesNeverFromTheCsv`.
  9. Judged-row preservation lost when the CSV is edited: **fixed** in code. The gate has no history, so the anchor is the
     case file: a case naming a gone row fails the regeneration, and the case itself is protected by `check-statuses.sh`.
     Failed first: `aCaseNamingARowThatIsGoneIsRefused`, `handRowsAreMissingByTheirCasesAndCannotBeDropped`.
  10. Instance matching: **fixed** in code. Comments stripped literal-aware; whole-number tokens; `new int[]` arrays; puts
      only into dungeon, instance or template maps. Failed first: `anInstanceIsNamedByAConstantAnArrayAMapPutOrALiteral`,
      `namesThatMerelyContainTheWordsAreNotReferences`. No real reference changed.
  11. Boss placement: **fixed** in code. Location arguments and comments ignored in scripts, XML comments in spawns and the
      Rift, table rows inline. Failed first: `bossesAreRaidAndGrandBossesWithTheirLivePlacements`. Real change: boss 25512
      (Gigantic Chaos Golem) gains `grandboss_data`, its inline row at `grandboss_data.sql:42`, found by the new independent
      reading before the fix.
  12. Independent counts do not check references or systems: **fixed** for what an independent reading can decide. The
      required systems are pinned in the test from §4.5 (shown failing with `lottery` dropped from both files); boss
      placements by table, spawn and Rift are confirmed both ways by a DOM and line reader; every instance reference's
      source must hold the id outside a comment; the reviewer's spot checks are pinned. Script leads remain leads, stated.
  13. The Truth Report can omit inventory problems; its oracle contradicts its contract: **fixed**. §4.6 lists five parts,
      inventory findings included; deviations are `APPROXIMATE`, `BROKEN`, `MISSING` (no `FUNCTIONAL`) in both §4.6 and
      AT-RT-07.
  14. The execution boundary exceeds the harness and AT-RT-05: **fixed in part, pushed back in part**. Pushed back: a draft
      specifies ahead of code (README, "Status of a spec"), so `TruthCaseTest`, `TruthReport` and `tools/truth/` not existing
      yet is not a defect; §1 now separates what exists from what is specified. Fixed: AT-RT-05 checks each property of the
      boundary and the suite separation, not only the profile refusal.
  15. `HAND` rows with textual keys out of order: **fixed** in code. Every table sorted by key, numeric or textual; the
      committed `systems.csv` is now in key order. Failed first: `textualKeysAreKeptInKeyOrderToo`.
- Revision 1 (draft): not yet reviewed.
