# 02 — Engineering Doctrine

All code is written by AI agents. That makes discipline more important, not less: an agent produces plausible code at
unlimited speed, and plausible-but-wrong code is the most expensive thing a game server can ship (item dupes, lost escrow,
silent formula drift). This doctrine is the review standard. A change that violates it is rejected regardless of how much
work went into it.

## 1. We do not break the game

Linus's first rule is "we don't break userspace". Ours: **we don't break Lineage II.**

- Retail-visible behaviour (formulas, skills, AI, drops, timers) and the client wire protocol are our ABI.
- The behaviour-lock suite (11-RETAIL-TRUTH §2) fails any change to them. The only way through is a scoped
  `DESIGN_OVERRIDE` or an evidence-backed Retail Truth correction. "It seemed better" is not a reason.
- Living Chronicle modules never modify class stats, skill formulas, damage, defence, success rates, enchant/craft rates,
  movement speed or cooldowns. Not even temporarily, not even "bounded".

## 2. Data structures first

> Bad programmers worry about the code. Good programmers worry about data structures and their relationships.

Every module is specified as: tables, state machines, invariants, the facts it consumes and emits. Code is the boring
consequence. A pull request that adds behaviour without first adding or changing the data definition in the module spec is
incomplete. If the data model is ugly, the code will be uglier; fix the model.

## 3. Good taste: one way, no special cases

There is exactly one mechanism for each concern:

| Concern | The one mechanism |
|---|---|
| moving or holding any asset | Asset Service (04 §3) |
| recording that something happened | Fact Journal (04 §1) |
| telling a player something they might not be entitled to know | Intel Service (04 §4) |
| custom UI | Codex of Aden framework (04 §5) |
| who an actor is | Actor Identity (04 §2) |
| randomness in Living Chronicle code | keyed RNG streams (04 §7) |
| configuration | Mobius config loader, one `.ini` per module |

Game logic MUST NOT branch on "is this a citizen". Actor class changes *policy tables* (who may be matched, what counts in
which metric), never mechanics. `if (actor.isSynthetic())` inside combat, loot or contract code is a bug.

## 4. Correctness is checked in production, not only in tests

- Conservation, unique ownership and escrow custody invariants (04 §3.6) run continuously as cheap incremental checks with
  counters and alarms, not just in CI.
- Living Chronicle code never swallows an exception. Upstream Mobius does (`Item.updateInDb` logs and continues);
  anything that goes through the Asset Service cannot. Failure is loud, contained to the operation, and leaves state
  recoverable.
- When an invariant breaks: freeze the affected module (not the server), page the admin, keep evidence.

## 5. Patches

- Every commit builds, passes the suite and is bisectable. No "fix previous commit" chains in `main`.
- One logical change per commit. The message says *why*; the diff says *what*.
- Core Mobius changes are minimal, mechanical where possible, and recorded in `CORE_PATCHES.md` with: file, reason,
  invariant protected, test that proves it. Upstream intake (03 §3) replays this ledger.
- Generic hooks (new `EventType`s, the escrow `ItemLocation`) are written so they could be sent upstream to Mobius.
  A smaller fork delta is a feature.

## 6. Performance is a requirement with numbers

- Hot paths (packet handlers, combat, movement, visibility) gain at most an O(1) fact capture: copy primitives into a
  preallocated record and hand it to a bounded queue. No I/O, no locks beyond the queue, no allocation-heavy work.
- Budgets (MUST, measured by microbenchmarks and the capacity suite): fact capture ≤ 2 µs p99; projector lag ≤ 5 s p99 at
  the 800-citizen siege benchmark; Codex page render ≤ 20 ms p99; Asset Service commit ≤ 15 ms p99.
- Every unbounded queue in Living Chronicle code is a bug. Upstream's unbounded `PacketExecutor` queue is measured and
  bounded by packet pacing (03 §4.7, 10 §5.4).

## 7. No frameworks we don't need

Java 27 standard library, Mobius commons, plain JDBC, JUnit. No Spring, no ORM, no DI container, no message broker, no
microservices. Three processes exist because they must:

1. **GameServer** (+ LoginServer, unchanged topology) — everything authoritative lives here.
2. **Citizen runner** — separate because citizens must not read server memory (epistemic honesty).
3. **Chronicle web** — separate because the public must never touch the game database credentials.

The narrator LLM is an external local service (ollama on the RTX 5090) called asynchronously with a strict input DTO.

Use modern Java where it removes code: records for facts and DTOs, sealed interfaces for fact and clause types, pattern
matching `switch`, virtual threads in the runner and web process (not inside Mobius' own pools).

## 8. Configuration is a liability

The canonical ruleset is the default. A config key exists only if a profile (`truth`, `dev`, `sim`, `hobby`, `live`) needs a
different value or simulation must tune it. Every key has: owner module, valid range, default, the test that covers it.
No key that silently changes game mechanics.

## 9. Talk is cheap. Show me the test.

- Every MUST in a module spec maps to a named acceptance test in the traceability matrix (30-DELIVERY §3).
- Benchmarks beat opinions. A performance or balance argument without a measurement is not an argument.
- AI-written tests and a second reviewing model are not independent oracles. Independent expectations come from invariants,
  evidence (11-RETAIL-TRUTH) and simulation metrics with pre-declared acceptance bands.

## 10. Review and ownership

- Each module has a spec owner (an agent role with the module spec as its contract) and a reviewer from a different model
  family. Reviews are adversarial: find the dupe, the race, the leak.
- The product owner is the BDFL for design. Engineering disputes are settled by tests and measurements.

## 11. Logging and observability

Structured, rate-limited, with reason codes. Every consequential decision (Director actuator, intel disclosure, settlement)
carries a reason code and is explainable with `lc why <id>` (04 §6). If an admin cannot explain why the world changed, the
change is a defect.

## 12. Security

Never trust the client: location, kill success, contract completion, inventory, witness encounters, participation time,
clan membership all come from authoritative server state. The legacy game cipher is not authentication. Admin actions on
contracts, bounties, world state, history, escrow and traces produce audit facts with before/after and reason.
