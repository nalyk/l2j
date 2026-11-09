# LINEAGE II: LIVING CHRONICLE — Blueprint v2 (High Five)

**Platform:** L2J Mobius CT 2.6 High Five, fork of upstream commit `2b0758e97d5a097127b7330ee59e82d4b1d69b3b`
**Status:** canonical design baseline, 2026-11-09. Supersedes the Kamael blueprint (`debate/living-chronicle-h5/02_BLUEPRINT_original_kamael.md`).
**Inputs:** the original blueprint (philosophy kept), the Claude × Codex consensus (`debate/living-chronicle-h5/05_CONSENSUS.md`, signed off),
source verification against the pinned Mobius H5 tree.
**Owner constraints:** full-featured, nothing deferred, all code written by AI, ≤10 human testers during the hobby period, live later.
**Two bars:** the features must make people say *"wait, what happened on that server?"*; the engineering must survive a Linus Torvalds review.

## Reading order

| File | What it is |
|---|---|
| [01-VISION.md](01-VISION.md) | Thesis, design law, the "wow" pillars, the canonical ruleset |
| [02-ENGINEERING-DOCTRINE.md](02-ENGINEERING-DOCTRINE.md) | How we write code. Non-negotiable. Read before touching anything |
| [03-PLATFORM.md](03-PLATFORM.md) | H5 baseline, fork, core seam, native H5 systems, client package, geodata, deployment, capacity |
| [04-ARCHITECTURE.md](04-ARCHITECTURE.md) | The five shared mechanisms every module uses: Fact Journal, Actor Identity, Asset Service, Intel Service, Codex UI |
| [10-CITIZENS.md](10-CITIZENS.md) | Synthetic Society: the inhabitants of Aden, the Genesis Epoch |
| [11-RETAIL-TRUTH.md](11-RETAIL-TRUTH.md) | Behaviour lock + evidence-backed correctness |
| [12-ADEN-REMEMBERS.md](12-ADEN-REMEMBERS.md) | World Director, regions, situations, caravans, Crown Law, native world-state |
| [13-ADEN-SIGNAL.md](13-ADEN-SIGNAL.md) | Intent network: find the people you need |
| [14-WAYFARERS-MEMORY.md](14-WAYFARERS-MEMORY.md) | Personal knowledge graph and journal |
| [15-PACT.md](15-PACT.md) | Contracts, escrow, Notarius institutions |
| [16-BLACK-LEDGER.md](16-BLACK-LEDGER.md) | Writs, black contracts, investigation, counter-intelligence |
| [17-CHRONICLE.md](17-CHRONICLE.md) | Public history: Gazette, bards, monuments, item sagas, website, saga replays |
| [20-INTEGRATION.md](20-INTEGRATION.md) | How systems compose; an end-to-end H5 story |
| [30-DELIVERY.md](30-DELIVERY.md) | Dependency DAG, proof obligations, risk register |
| [90-COVERAGE.md](90-COVERAGE.md) | Every v1 §31 feature → its v2 home (nothing dropped) |

## What changed from v1, and why (analysis summary)

1. **Chronicle: Kamael → High Five.** H5 is not a smaller target, it is a richer one: 9 castles with Seven Signs–bound tax caps
   (`CastleManager.validateTaxes`: Dusk 5%, none 15%, Dawn 25%), 21 fortresses with castle contracts, Territory Wars with a native
   mercenary registry, 6 conquerable clan halls, Hellbound, Gracia seeds, cursed weapons, mail with cash-on-delivery,
   Olympiad, grand bosses. v1 ignored all of it. v2 wires every module into these systems instead of inventing parallel ones.
2. **≤10 humans cannot carry a society.** v2 adds the **Citizens of Aden**: a resident population of real `Player`
   characters driven by an external protocol runner, with the same epistemic limits as a human client. They are not Mobius
   Fake Players (those are NPCs: `FakePlayerData` maps names to npc ids). The world is born in a **Genesis Epoch** and lived
   in for weeks before the first tester logs in.
3. **"Don't touch the core" was false.** Mobius has no events for crafting, trade, private stores, NPC buy/sell, warehouse,
   spoil, Territory War, party; item persistence swallows SQL errors (`Item.updateInDb`); startup cleanup would delete escrowed
   items (`DatabaseIdManager`). v2 defines a small, explicit, ledgered **core seam** with a transactional Asset Service.
4. **Information design assumes alts, Discord and wikis.** Every clue must stay fair when relayed instantly to an enemy.
   The server protects *actions*, not *content*.
5. **UI is server-driven by design**: the **Codex of Aden**, one Community Board application, plus institutional NPC pages.
   No required interaction depends on client interface hacking.
6. **New wow features** (marked `NEW v2` in the module files): Genesis Epoch, citizens who remember you, Crown Law,
   physical caravans, the Aden Gazette, bards and town criers, wanted boards, saga replays, cursed-weapon writs,
   the `lc why` forensic tool.
7. **Engineering doctrine**: one way to do each thing, data structures first, invariants checked in production, bisectable
   patches, no frameworks we don't need. See 02.

## Conventions used in every module file

- `MUST` / `MUST NOT` are requirements with an acceptance test. `SHOULD` is a default that simulation may tune.
- Tables are written as `table(column type, ...)`; full DDL lives in `sql/migrations/` once N1 is implemented.
- Every number marked *(tunable)* has a config key and is calibrated by Synthetic Society simulation, never by taste.
- `file.java:line` references are to the pinned upstream commit.
