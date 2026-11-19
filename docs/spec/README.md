# Module specifications (N1)

The blueprint (`docs/blueprint/`) says what Living Chronicle is and why. A spec here says exactly what one module stores,
which states it moves through, what it promises, and which test proves each promise. Code is written against a spec, never
ahead of it (blueprint 02 §2, 30 §2.1): a change that adds behaviour first changes the spec.

## Files

| Spec | Module | Blueprint sources |
|---|---|---|
| [fact-journal.md](fact-journal.md) | Fact Journal: durable facts, capture, telemetry, precise locations, projector runtime | 04 §1, §6-§8, 02 §6, 03 §4.4, consensus §5 |
| [actor-identity.md](actor-identity.md) | Actor Identity: origin, account, activity, snapshot, policy tables | 04 §2, 02 §3, §12, 10, consensus §3 |
| [intel-service.md](intel-service.md) | Intel Service: traces, disclosure, budgets, `canWitness`, expiry, publication filtering | 04 §4, 03 §4.5, 16 §3-§4 §8, 13, 14, 17 |
| [asset-service.md](asset-service.md) | Asset Service: holders, custody, ledger, transactions, recovery, economy monitor; its site table [asset-economy-sites.csv](asset-economy-sites.csv) | 04 §3, 15 §4, 16 §7, 12 §6.2 §8, consensus §4 |
| [codex-ui.md](codex-ui.md) | Codex of Aden: routing, rendering, one-use action tokens, confirmation dialogs, notifications | 04 §5, 02 §3 §12, 13, 14, 15, 16, 17 |
| [pact.md](pact.md) | PACT and the Notarius: contracts, clauses, settlement, craft for contract, loot charters, mercenary contracts, treaties | 15, 03 §4.2-§4.4, 04 §3 §5, 12 §6.2 §8, 17 |
| [black-ledger.md](black-ledger.md) | The Black Ledger: Writs, Black Contracts, hunters, aliases, kills and claims, counter-intelligence, reverse investigation, anti-abuse | 16, 03 §4.4-§4.5, 04 §3-§5 §7, 12 §6.2, 17, consensus §6 |
| [aden-signal.md](aden-signal.md) | Aden Signal: profiles, intents, schedules, matching, mutual interest, latent parties, demand, sessions | 13, 03 §4.4, 04 §4-§5, 10, 12, 14, 20 §1 |
| [wayfarers-memory.md](wayfarers-memory.md) | Wayfarer's Memory: own-experience observations with provenance, recap, projects, notes, shared pages, personal history, the Ledger boundary | 14, 01 §2.5, 03 §4.4, 04 §4-§5, 12, 13, 16 §8 |
| [aden-remembers.md](aden-remembers.md) | Aden Remembers: regions, regional state, situations, bounded actuators, Crown Law, the Merchant Guild and caravans, influence, rumors, history | 12, 04 §1 §3 §6-§8, 10 §8, 13, 15, 16, 17, 90 §2 |
| [chronicle.md](chronicle.md) | Chronicle of Aden: the publication row store, names, the Gazette, monuments and in-world voices, item sagas, battle replays, the narrator boundary, the website | 17, 01 §2.6, 02 §7, 04 §4 §8, 10 §8, 12 §9, 14 §7, 16 §2, 20 §1 |
| [profiles.md](profiles.md) | Profiles and Mobius custom modules: the profile, configuration overlays at the one reading point, the startup check, each custom module's policy per profile | 02 §8, 03 §8, 10 §9, 11 §3, 20 §1 |
| [retail-truth.md](retail-truth.md) | Retail Truth: the case format and status machine, the regression contract, the lock's change rule and DESIGN_OVERRIDE, coverage, the H5 content inventory, the Truth Report | 11, 02 §9, 03 §3 §5, 17 §5, 30 §2 |
| [citizens.md](citizens.md) | Citizens of Aden: the runner, its protocol library, perception, observation store, navigation, personas and policies, principals and their channel, the relationship ledger, dialogue, admission and pacing, economic discipline, Genesis | 10, 01 §2.5, 02 §3 §7, 03 §7, 04 §2, 16 §8, 20 §1 |
| [platform.md](platform.md) | Platform: baseline, pinned toolchain and build, processes and database users, network, client package, geodata, capacity, the doctrine as checks | 03 §1 §2 §6 §7 §9 §10, 02, 90 §1 §3 §4 |

One file per module. Its DDL lives in `sql/migrations/V###__lc_<module>.sql`, numbered in the order modules depend on each
other; a spec names its tables, the migration defines them, and a test applies every migration to MariaDB.

## Sections, in this order

1. **Scope**: what the module owns, what it explicitly does not, and its dependencies.
2. **Data**: every table (purpose, columns, keys, who writes it) and every in-memory structure that is authoritative.
3. **State machines**: states, transitions, guards and effects, as tables. A transition not in the table is impossible.
4. **Operations**: each entry point with its preconditions, effects, failure behaviour and the facts it emits.
5. **Facts**: every fact type consumed or emitted, with its fields and durability (durable fact or telemetry).
6. **Invariants**: each with how it is checked in production (incremental, cost, frequency) and what a violation does.
7. **Failure model**: every fault the module must survive, and the state it leaves.
8. **Configuration**: key, valid range, default, value per profile (`truth`, `dev`, `sim`, `hobby`, `live`), covering test.
9. **Interface**: Codex pages and NPC flows (04 §5); "none" when the module has no player-facing surface.
10. **Requirements**: every MUST, each with the acceptance test that proves it.

## Identifiers

- Requirement `<MOD>-R<nn>`: one MUST, worded so a test can fail it.
- Invariant `<MOD>-INV<nn>`: a property that holds in every committed state, checked in production (02 §4).
- Acceptance test `AT-<MOD>-<nn>`: names the test that proves one or more requirements or invariants. Its kind is one of
  `unit`, `integration` (real MariaDB, Testcontainers), `fault` (kill or abort at a named point, then recover), `restart`
  (GameServer restarted in a given state), `sim` (Synthetic Society run with a pre-declared acceptance band), `human`
  (recorded tester session, only where machines cannot judge).
- Fact type `<Module>.<Name>`, e.g. `Asset.EscrowFunded`.

Identifiers are never reused. A withdrawn requirement keeps its number with the status `withdrawn` and the reason.

`docs/blueprint/traceability.csv` (30 §3) maps every blueprint sub-feature to the requirements, tables, flows, hooks and
acceptance tests that realise it. Coverage is per sub-feature, never per module.

## Status of a spec

`draft` (being written) → `reviewed` (a reviewer from a different model family found no defect left; the review is
recorded at the end of the spec) → `implemented` (every AT exists and passes). A spec changes status only with the commit
that makes the change true.
