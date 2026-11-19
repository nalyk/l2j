# SPEC-RT — Retail Truth

- Status: draft (revision 1)
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

It builds on what exists: the lock's goldens (`test/lock/**.tsv`, checked by `test/java/org/l2jmobius/testing/
Golden.java`, which already tells a failing diff that "a golden changes only with an evidence-backed Retail Truth
correction or a scoped DESIGN_OVERRIDE"), the lock suites (`test/java/org/l2jmobius/gameserver/*LockTest.java`), the
intake gate that may not change a golden (`tools/intake/check-intake-goldens.sh`, `tools/intake/intake.sh`), the case
directory (`truth/README.md`, the first case `truth/cases/RT-AI-0001.yaml`) and the `truth` profile (profiles.md).

It does not own the profile mechanism (profiles.md) or the website (chronicle.md §4.9).

## 2. Data

```text
truth/cases/RT-<DOMAIN>-<NNNN>.yaml     one case (§2.1)
truth/overrides/DO-<NNNN>.yaml          one DESIGN_OVERRIDE (§2.2)
truth/coverage.csv                      domain, feature, partition, case ids (§4.5)
truth/inventory/<kind>.csv              the H5 content inventory (§4.6): quests, instances, bosses, systems
truth/evidence/<source>.yaml            evidence sources: class, title, locator, confidence, retrieved, hash
test/lock/<suite>/<name>.tsv            the lock's goldens (existing)
```

### 2.1 A case

The format of 11 §1.3, with these rules (checked by AT-RT-00):

| Field | Rule |
|---|---|
| `id` | `RT-<DOMAIN>-<NNNN>`, the file's name; `DOMAIN` one of `COMBAT`, `SKILL`, `AI`, `ECONOMY`, `SOCIAL`, `CONTENT`, `WORLD`; never reused |
| `feature`, `chronicle` | a feature key; `ct26_highfive` |
| `client_build` | the pinned client build hash, required when any evidence is of class 1 |
| `evidence` | a list of `{class, source, property, confidence}`; `source` names a `truth/evidence` file; `class` 1–5 as 11 §1.2 |
| `preconditions`, `actions` | what the check sets up and does |
| `expected` | what retail does, **derived independently of the code under test**: from the evidence, never from running the server; empty only while `UNKNOWN` |
| `observed` | what the server does (class 5: current behaviour only) |
| `tolerance`, `trials` | required for a numeric or probabilistic property: trials `n` and confidence `ci` |
| `check` | the executable check (a test method), required for every status but `UNKNOWN` and `MISSING` |
| `status` | §3 |
| `server_commit` | the commit the status was last judged at |
| `golden_refs` | the goldens (file and key columns) whose lines this case's behaviour appears in, if any |
| `evidence_wanted`, `notes` | free text |

Evidence of class 5 (Mobius behaviour) never supports a status above `UNKNOWN`; class 1 (client data) supports only
client-visible values (11 §1.2: never drop rates, land rates, AI or transaction semantics).

### 2.2 A DESIGN_OVERRIDE

```yaml
id: DO-0001
domain: COMBAT
goldens: [{file: test/lock/combat/monster-physical.tsv, key: {monster: "20120"}}]   # never a whole file
intended_change: what changes and why it is a design decision, not a correction
player_note: the line players read in the Truth Report's list of deviations
cases: [RT-COMBAT-0007]          # the cases it makes deviate, with their status kept honest
approved_by: the reviewer
```

An override names golden **lines** by file and key columns; a pattern matching a whole file or several files is refused
(11 §2: "never blanket-approves regenerated goldens").

## 3. State machine: a case's status

`EXACT` (matches evidence within tolerance), `FUNCTIONAL` (works, equivalence unproven), `APPROXIMATE` (measurably
differs), `BROKEN`, `MISSING`, `UNKNOWN` (no sufficient evidence).

| From | To | Guard |
|---|---|---|
| — | `UNKNOWN` | any new case |
| `UNKNOWN` | `EXACT`, `FUNCTIONAL`, `APPROXIMATE`, `BROKEN`, `MISSING` | evidence of class 1–4 attached; `expected` derived from it; for `EXACT` and `APPROXIMATE` the check run within tolerance (or outside it) at the stated trials |
| any | `UNKNOWN` | only when its evidence is refuted (a new evidence entry saying so) |
| per the regression contract (§4.3) | — | no merge lowers `EXACT`, moves `FUNCTIONAL` to `BROKEN` or `MISSING`, or makes a case fixed before (`EXACT` or `FUNCTIONAL` in any earlier commit of the base) `BROKEN` again, unless the same commit refutes its evidence |

"Never upgrade UNKNOWN to EXACT because nobody complained" (11 §1.1): the guard needs evidence, not silence.

## 4. Operations

### 4.1 Writing a case

Cases are seeded where work on the server meets a question the code cannot answer (truth/README.md), and by the
coverage plan (§4.5). Expected results come from evidence: client data extracted with its file, row and the client build's
hash; patch notes and official guides; community sources with a confidence; captured traffic. A probabilistic property
is judged by repeated trials under the `truth` profile's keyed randomness: the case passes when the expected value lies in
the Wilson interval of the observed rate at `ci` (default 0.99), with `n` chosen in the case (default 2 000).

### 4.2 Running cases

`TruthCaseTest` runs every case's `check` in the `truth` profile and compares with `expected` and `tolerance`. A case's
status is never set by a run: a run that disagrees with an `EXACT` or `FUNCTIONAL` case fails the build (a regression); a
run that agrees with a lower case's expected is reported as a candidate for a reviewed status change.

### 4.3 The regression contract and the lock

- **Status regression** (AT-RT-02): `tools/truth/check-statuses.sh <base>` compares every case's status with the base and
  with every earlier base commit, and fails on any move §3 forbids.
- **Lock changes** (AT-RT-03): `tools/truth/check-lock-changes.sh <base>` fails when a golden line differs from the base
  unless the same commit contains either a case change whose `golden_refs` cover the line and whose evidence or status
  changed (a correction), or an override whose `goldens` cover it; overrides covering whole files are refused. A passing
  lock proves stability only, never correctness.
- **Intake** (existing): an intake branch may not change a golden at all (`check-intake-goldens.sh`); a lock it fails is
  answered on the base by a correction or an override, never by recording the new behaviour.
- The lock runs in the `truth` profile (profiles.md: every custom module neutral or off, no Living Chronicle module,
  keyed randomness, the controlled clock, an isolated database), and Living Chronicle's suites never run in it.

### 4.4 Coverage

`truth/coverage.csv` lists, per domain of 11 §1.4 (combat, skills, NPC and AI, economy, social and PvP, content, world),
its features and their equivalence partitions, boundaries and known interactions, with the case ids that cover each
row. A row without a case is reported, not a failure; the report's counts are part of the Truth Report (§4.6). The lock's
suites cover the class × skill × gear matrix through their goldens, recorded in the same file by suite.

### 4.5 The H5 content inventory

`tools/truth/inventory.py` generates `truth/inventory/quests.csv`, `instances.csv`, `bosses.csv` and `systems.csv` from the
datapack: every quest script with its id and name (`dist/game/data/scripts/quests/`), every instance
(`dist/game/data/instances/`), every raid and grand boss template, and the world systems listed by hand in
`truth/inventory/systems.in` (Seven Signs, the Festival of Darkness, Hellbound, the Gracia seeds, sieges, fortress sieges,
Territory Wars, Olympiad, the Manor, clan halls, fishing, the Underground Coliseum, Krateï's Cube, and the rest). Each row
has a status (§3's six) and the case ids behind it; generated rows start `UNKNOWN` unless a case judges them. A quest or
system known to retail and absent from the datapack is a `MISSING` row added by hand with its evidence. Known incomplete
native paths, such as the Seed of Destruction defense state and the Festival's gaps (11 §1.5), are rows with their
status: implementation work, listed, never hidden as residual risk.

### 4.6 The Truth Report

`tools/truth/report.py` builds the report from the case files, the overrides, the coverage and the inventory, at the
website's build: per domain, the count of each status; the list of known deviations (every `APPROXIMATE`, `BROKEN` and
`MISSING` case, every override with its `player_note`), each with how it is known (its evidence classes). It is a static
page of public repository data (no game data, no player), served by the Chronicle website (chronicle.md §4.9), in EN, RO
and RU templates with the case text as written.

## 5. Facts

None: cases, overrides and the inventory are repository data, judged in CI.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| RT-INV01 | every case is well formed and its status supported by its evidence | AT-RT-00 |
| RT-INV02 | no merge regresses a status | AT-RT-02 |
| RT-INV03 | every changed golden line is covered by a correction or a scoped override | AT-RT-03 |
| RT-INV04 | the lock and the cases run only in `truth` | AT-RT-05 |

## 7. Failure model

| Fault | End state |
|---|---|
| a lock diff without a correction or override | the build fails, the observed behaviour written beside the golden (existing) |
| an `EXACT` case's check failing | the build fails |
| an override covering a whole file | refused |
| evidence source missing | the case refused |

## 8. Configuration

None at runtime. Case defaults: `trials.n` 2 000, `trials.ci` 0.99, both overridable per case.

## 9. Interface

The Truth Report page on the website. Developers: `./build.sh` runs the lock and the cases; `tools/truth/*.sh` the checks.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| RT-R01 | Every case MUST follow §2.1, its status supported by evidence of class 1–4 and its expected derived from it. | AT-RT-00 |
| RT-R02 | A case's executable check MUST run in `truth`, and a disagreement with an `EXACT` or `FUNCTIONAL` case MUST fail the build. | AT-RT-01 |
| RT-R03 | No merge MUST lower `EXACT`, move `FUNCTIONAL` to `BROKEN` or `MISSING`, or break a fixed case, unless its evidence is refuted in the same commit. | AT-RT-02 |
| RT-R04 | A golden line MUST change only with a correction or a scoped override in the same commit, and an intake MUST change none. | AT-RT-03 |
| RT-R05 | A probabilistic case MUST be judged by its trials and confidence interval. | AT-RT-04 |
| RT-R06 | The lock and the cases MUST run only in the `truth` profile. | AT-RT-05 |
| RT-R07 | The inventory MUST list every quest, instance and boss of the datapack and the listed world systems, each with a status. | AT-RT-06 |
| RT-R08 | The Truth Report MUST count every case by domain and status and list every deviation and override with its evidence classes. | AT-RT-07 |

| Test | Kind | What it does |
|---|---|---|
| AT-RT-00 | unit | every case file parses and satisfies §2.1's rules; a case `EXACT` on class-5 evidence, one with an empty `expected` above `UNKNOWN`, one naming a missing evidence source, are refused (fixtures) |
| AT-RT-01 | integration | `TruthCaseTest` runs every check in `truth`; a fixture `EXACT` case whose behaviour is changed fails the build |
| AT-RT-02 | unit | `check-statuses.sh` on fixture histories: each forbidden move refused, a refutation in the same commit accepted |
| AT-RT-03 | unit | `check-lock-changes.sh` on fixture commits: a golden line changed alone refused; with a covering correction or a scoped override accepted; a whole-file override refused; an intake branch changing any golden refused (`check-intake-goldens.sh`) |
| AT-RT-04 | unit | the Wilson interval judgement on known samples: a true rate inside passes, outside fails, at `ci` 0.99 |
| AT-RT-05 | integration | the lock and the cases started in another profile refuse to run |
| AT-RT-06 | unit | the inventory has one row per quest script, instance and boss of the datapack and per line of `systems.in` |
| AT-RT-07 | unit | the report's counts equal the cases'; every non-`EXACT`, non-`UNKNOWN` case and every override appears in its list |

## 11. Upstream constraints

1. **Mobius behaviour is not evidence of correctness** (11 §1.2). → class 5 never supports a status.
2. **Client data establishes client-visible values only** (11 §1.2). → class 1's scope.
3. **Upstream changes arrive by intake** (03 §3). → the intake gate may not change goldens.

## 12. Review record

- Revision 1 (draft): not yet reviewed.
