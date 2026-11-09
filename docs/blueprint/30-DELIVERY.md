# 30 — Delivery

Everything is built. Order is technical dependency only. Bracketed dependencies are required input artifacts, not
whole-node completion barriers; independent subtasks run concurrently (many agents in parallel).

## 1. Dependency DAG

| Node | Deliverable | Needs |
|---|---|---|
| **N1** | per-module specs from this blueprint (DDL, state machines, fact types, invariants, config keys, Codex page/action contracts, acceptance tests); H5 content inventory; traceability matrix | — |
| **N2** | fork, `upstream` mirror, reproducible build (`toolchain.lock`, container), JUnit/Testcontainers harness, keyed RNG + controllable clock, behaviour-lock baseline | — |
| **N3** | Asset Service design: lock order, escrow `ItemLocation`, custody, `DatabaseIdManager` integration, ledger postings, outbox, recovery protocol | N1 (Asset spec) |
| **N4** | core seam implementation (03 §4): Asset Service, escrow, loot routing, crafting boundary, new EventTypes, `canWitness`, pacing hooks, `_lc` router; `CORE_PATCHES.md` | N2, N3 |
| **N5** | Fact Journal, projector runtime, Actor Identity, Intel Service, audit/reason codes, `lc why`, config isolation | N2, N4 |
| **N6** | client provenance: pinned client, `.dat`/resource patch pipeline, legacy XDAT environment, client ZIP | — |
| **N7** | geodata: local generation from the pinned client, pinned l2mapconv, hashes, settings, correction patches; full coverage; routing/LoS/door/water/siege/instance tests | N6 |
| **N8** | Codex of Aden framework + every page/action flow from module specs | N5, N6 |
| **N9** | protocol library, citizen runner, navigator, login admission | N2, N7 |
| **N10** | Citizens: personas, principals, perception filter, observation store, relationship ledger, out-of-band channel, schedules, dialogue | N5, N8, N9 |
| **N11a** | PACT + all Notarius tiers + Treaty + Caravan Escort | N4, N5, N8 |
| **N11b** | Black Ledger complete (§1–§14 of 16) | N4, N5, N8 |
| **N11c** | Aden Signal | N5, N8 |
| **N11d** | Wayfarer's Memory | N5, N8 |
| **N11e** | Aden Remembers: regions, actuators, situations, Crown Law, caravans, history graph | N4, N5, N8 |
| **N11f** | native H5 completion: SoD defense state, Seven Signs Festival gaps, inventory findings | N2 |
| **N11g** | Chronicle: in-world surfaces, Gazette, bards, criers, monuments, item sagas, website, saga replays, narrator | N5, N11e |
| **N11h** | Retail Truth case suites + Truth Report | N2 |
| **N12** | cross-module integrations (20 §1), Genesis Epoch run, full-world soak, capacity benchmarks, tester sessions | N10, N11* |
| **N13** | deployment stack on build-host: Compose, Tailscale node + grants, backups + restore drills, metrics, CPU sets/caps | N2 (parallel) |
| **N14** | live topology specification (public ingress, DDoS protection, signed updater activation) | N13 contracts |

Validation of every N11 module runs with N10 citizens; modules can be implemented before N10 completes.

## 2. Definition of done (per node)

1. Spec in N1 updated first (data structures first).
2. Code + tests merged in bisectable commits; reviewer from a different model family signed off adversarially.
3. All MUSTs mapped to passing named tests in the traceability matrix.
4. Invariants running in production mode with alarms.
5. Performance budgets (02 §6) measured.
6. `lc why` explains every decision the node can make.
7. Where machines cannot judge (fun, clarity, tension, fairness, believability): a recorded tester session.

## 3. Traceability matrix

`docs/blueprint/traceability.csv` (generated in N1): every §31 sub-feature (see 90-COVERAGE), retained appendix behaviour,
and cross-module behaviour → spec section, authoritative subsystem, schema objects, Codex flow, hooks, tests, acceptance
evidence, status. Coverage is per sub-feature, never per module.

## 4. Proof obligations (global)

- Conservation per item id and per currency against ledger postings; unique ownership; escrow custody (04 §3.6).
- Fault injection before/after each SQL mutation, commit, cache application, acknowledgement; ambiguous commits; DB restart;
  GameServer restart in every contract state; restart tests for escrow cleanup/ID reservation, expired-intel rebuilds,
  persisted craft outcomes.
- Behaviour lock green; Retail Truth regression contract held.
- Intel: no raw coordinates outside the Intel Service; expiry through caches/projections/backups; publication DTO property
  tests; website radar test.
- Citizens: epistemic audit; economic bounds; Genesis exit criteria.
- Capacity: the 03 §10 matrix under CI + LLM load.

## 5. Risk register

Format per entry: invariant · trigger · detection metric · remedy · recovery · acceptance evidence.

| Risk | Invariant at stake | Detection | Remedy |
|---|---|---|---|
| asset dupe/loss in settlement | conservation, unique ownership | continuous invariant checks, ledger diff | Asset Service fault suite; freeze module on violation |
| escrow destroyed or id reused at restart | custody integrity | startup custody audit | escrow `ItemLocation` + `DatabaseIdManager` integration tests |
| upstream intake breaks the seam | behaviour lock, seam tests | intake gate | ledgered patches; take large refactors as single reviewed merges |
| correlated test-oracle errors (AI writes code and tests) | correctness | evidence-based Retail Truth, invariants, independent expectations | second-model review; evidence classes; simulation acceptance bands declared before runs |
| citizens dominate humans | human protagonism | per-market/contest shares by origin | policy retune; efficiency bounds |
| citizens feel fake | believability | tester sessions | persona and dialogue work; perception budget tuning |
| intel leakage (website, narrator, Wayfarer, joins) | privacy | publication property tests, radar test | Intel Service as single path; allowlisted DTOs |
| Director imperceptible or opaque | perception, explainability | observer-citizen tests, tester comprehension | channel design; `lc why`; criers and Gazette |
| journal/storage growth | durability, latency | storage budget alarms, projector lag | bucketing, partitions, retention |
| shared-host interference (CI, LLM) | latency budgets | scheduler lateness, queue age | CPU sets, CI caps, pacing |
| geodata coverage gaps | witness/LoS correctness, navigation | NullRegion startup check, route/LoS tests | regenerate/correct from pinned client |
| client patch drift | UI and items render | pinned hashes, layout tests | single pinned client build |
| endpoint visibility to testers | — (accepted property of Tailscale peer connectivity) | — | live ingress (N14) for public audiences |

Known implementation gaps (SoD defense, Festival, inventory findings) are work items in N11f, not risks.
