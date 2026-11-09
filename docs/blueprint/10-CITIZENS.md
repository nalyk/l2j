# 10 — Citizens of Aden (Synthetic Society)

## 1. Why this exists

With ≤10 humans the blueprint lacks market depth, simultaneous role availability and political scale. Signal has no one to
match, the Black Ledger has no witnesses or informants, Aden Remembers has no pressure to measure, sieges have no
attackers, the Gazette has nothing to report. Mobius Fake Players are NPCs and cannot trade, craft, use gatekeepers, join
clans or sign contracts.

The Citizens of Aden are a resident population of **real `Player` characters on real accounts**, played by software
through the real network protocol. They are a production subsystem of the world, not a test fixture.

## 2. Wow

- **Genesis Epoch** *(NEW v2)*: the server "launches" to citizens weeks before the first tester. Testers enter a world with
  real history, dynasties, markets, feuds, legends.
- **They remember you** *(NEW v2)*: every citizen keeps a relationship ledger with every character it has interacted with.
- They shout WTS/WTB in Giran, haggle in whispers, form parties at Cruma, fight over Sea of Spores, siege Aden castle, sign
  contracts at the Notarius, put bounties on PKs, sell sightings to hunters, and lose their nerve when they feel watched.

## 3. Population model

Specified as distributions, calibrated by simulation, reported in the admin view:

| Dimension | Specification |
|---|---|
| level band | spread across all bands at any time after Genesis; newcomers keep arriving (launch curve, then trickle) |
| role | fighters, mages, all support classes (buffers, healers, enchanters), spoilers, crafters per recipe tier, merchants |
| geography | weighted to retail hunting grounds per level band; towns for trade; never all in one zone |
| session overlap | human-like schedules: evening peaks, weekend peaks, sleep, missed days, quitting |
| supply chain | every crafting chain from low-grade materials to S-grade has producers and consumers |
| politics | 12–20 competing **principals** (§4) with clans, alliances, ambitions and grudges |

Benchmarks: 300–800 concurrent citizens, ~1500 accounts over time. These are capacity scenarios, not targets to hit at any cost.

## 4. Principals (Houses)

A principal is an independent synthetic economic and political actor: the "will" behind a group of citizens.
- Isolated budget, isolated information policy, own goals (e.g. *Iron Covenant* wants Oren castle; *Silver Wharf* wants
  the Giran market; *Ash Wolves* are PKers for hire).
- Principals compete. Ordinary funded transfers between principals are allowed (trade, contracts, bounties); repeat-pair and
  reciprocal-farming controls apply exactly as for humans. Malicious collusion is a separate *scenario-adversary* profile used
  only in tests.
- A principal's clans have an **out-of-band channel** (logged, allowlisted) that emulates Discord, so information design is
  exercised under the alts-and-Discord assumption (01 §2.5, 16 §8).

## 5. Runtime

```text
citizen-runner JVM (virtual threads)
  ├─ ProtocolSession     login (RSA/Blowfish), game (stateful XOR), KeyPacket negotiation, framing, reconnect
  ├─ PerceptionFilter    attention budget: tracked-object cap, reaction latency, noticing probability
  ├─ ObservationStore    what this citizen has seen/heard/been told, with time and provenance
  ├─ Navigator           static geometry from the validated geodata; doors/fences learned from observation and failure
  ├─ Persona + Policy    deterministic utility AI over a goal library; schedules; budgets; risk appetite
  ├─ Relationship ledger per other character (§7)
  └─ Dialogue            local LLM for whispers/party chat, fed ONLY from ObservationStore + persona; may say "I don't know"
```

### 5.1 Epistemic honesty
No privileged server-world access. Dynamic knowledge comes only from perceived protocol traffic and the authorised
out-of-band channel; validated static navigation geometry is permitted. The perception filter is applied **before** anything
reaches decision policies or shared clan memory, so packet-level data never becomes superhuman awareness.

### 5.2 Protocol library
- Versioned, explicit connection states, conditional fields, packet → domain mappings.
- Built from the Mobius server code (inverting the relevant `readImpl`/`writeImpl`; null-handler opcodes excluded) and checked
  differentially against independent clients (l2js-client, l2py H5 client) and captured traffic from the pinned client.
- Fault matrix: fragmented/coalesced frames, reconnects, malformed lengths, cipher state, packet loss on disconnect, stale
  objects, every persona workflow end to end.

### 5.3 Decisions
- Economic and political choices: deterministic utility policies with recorded inputs (replayable, explainable).
- The LLM never decides wars, prices, contract terms or targets. It only writes words.
- Efficiency is bounded by decision latency, schedules, imperfect knowledge and spending preferences. Never by changing
  combat or reward formulas.

### 5.4 Login admission and pacing
Runner-wide admission queue: spacing beyond `NormalConnectionTime` (700 ms), bounded outstanding logins, jittered backoff
(login `Server.ini` flood settings; `FloodProtectorListener`). Per-category action pacing against `FloodProtector.ini`.
Explicit dualbox policy for the runner IP per principal, including Olympiad/event limits. Protections are never disabled
globally. A mass runner crash and recovery must not block human logins.

## 6. Personas (goal libraries)

Farmer · spoiler · crafter (per grade) · private-store merchant · buffer/healer for hire (uses SellBuffs) · party leader ·
raider · manor farmer · fisherman · clan leader · alliance diplomat · siege commander · Territory War mercenary · fortress
holder · PK/outlaw · bounty hunter (Writ) · black-contract hunter · informant · broker client · caravan escort · caravan
raider · Olympiad competitor · newbie · returning player · quitter.

Each persona specifies: goals, preconditions, utility terms, budget rules, risk appetite, schedule template, what it
writes to the out-of-band channel, and its complete protocol workflows (tested).

## 7. Relationship ledger *(NEW v2)*

```text
cit_relation(citizen_char_id, other_char_id, first_seen, last_seen, trades, trade_value, contracts_ok, contracts_failed,
             parties, killed_by, killed, rescued_by, debts_owed, notes_tags JSON, sentiment SMALLINT)
```
- Fed only from the citizen's own observations (it knows it was killed by "Nalyk" because `Die`/`SystemMessage` said so;
  it does not know who funded the hunt).
- Sentiment is a deterministic function of the counters (no LLM). It changes policy: prices offered, willingness to party or
  trade, which crafter gets first call, which PK gets a bounty, which clan it recruits.
- Principals aggregate members' ledgers into house-level grudges and alliances. A human who repeatedly kills a house's
  members becomes that house's bounty target through the normal Black Ledger.
- Visible to humans only through behaviour and dialogue ("You again. Last time you took my spoil.").

## 8. Genesis Epoch *(NEW v2)*

1. Day 0: empty world, retail spawn, canonical ruleset. Citizens register accounts on a launch curve (big first wave,
   decaying daily arrivals), pick races/classes by distribution, start where retail starts them.
2. Days 1–N: they level, form clans, find spots, fight over them, start markets, craft, build alliances, register for
   Territory Wars and sieges as soon as clan requirements are met, take castles, set taxes, enact Crown Law, post bounties.
   Aden Remembers, the Chronicle and the Gazette run from day 0.
3. Testers arrive when the world meets the **Genesis exit criteria** (measured, not scheduled): at least 5 castles have changed
   hands at least once; at least one Territory War fought; markets exist for every grade below S; at least 3 crafter
   specialisations dominated by different principals; at least 10 completed Black Ledger hunts; at least 30 Gazette issues;
   no conservation invariant ever violated.
4. Testers start as newcomers in a mature world: the **catch-in** design (12 §5.2) is tested for real.

Genesis runs in real time (Mobius timers are not accelerated). The Director may run accelerated only in isolated `sim`
profile instances using recorded inputs, never in the hobby world.

## 9. Economic discipline

- No artificial asset grants or GM-spawned items; ordinary gameplay faucets (drops, quests, NPC sales) and crafting
  transformations remain permitted and accounted for (04 §3.4 per actor class and per principal).
- Bounded by policy, not stats: aggregate farming duty per principal, extraction concentration per region/resource,
  stockpile limits, scarce-content attendance (raid/epic participation share).
- Modelled consumption, maintenance, losses and commercial risk: citizens spend on shots, potions, repairs (crystallise/
  re-craft), lose items to PK drops, overpay, get scammed outside contracts, go broke and quit.
- Tests: human newcomer margins and access (can a new human earn and sell?), human support-class demand (are human buffers
  wanted?), human trade opportunities, political contestability (can 10 humans + allies take a castle?), and adversarial
  runs where citizens try to concentrate economic or political power.

## 10. Humans stay protagonists

- Citizens are never the best at anything by design-rule; they are as good as their decision latency, knowledge and
  schedules allow. If metrics show citizen dominance over humans in any market or contest, policy is retuned.
- Testers know the world is mostly synthetic. There is no overhead marker in-world (immersion); the admin view shows origin.
- Signal lets humans choose whether synthetic candidates are offered to them.

## 11. Data (runner side)

The runner has its own database (separate MariaDB schema and user, **no access to game tables**):
`cit_account`, `cit_character`, `cit_principal`, `cit_persona_state`, `cit_observation` (partitioned, retention by tier),
`cit_relation`, `cit_oob_message`, `cit_schedule`, `cit_decision_log` (inputs → choice, for `lc why`).

## 12. Acceptance

- `CIT-PROTO-*`: every opcode used by any persona round-trips against the real server; fault matrix passes; 24 h soak with
  zero desyncs/kicks.
- `CIT-EPISTEMIC-*`: no decision input traces to information the citizen could not have perceived (audited from
  `cit_decision_log`).
- `CIT-ECON-*`: per-item conservation by principal; concentration bounds hold; newcomer/support/trade/contestability tests pass.
- `CIT-GENESIS-*`: exit criteria reached without invariant violation.
- `CIT-HUMAN-*`: tester sessions rate the citizens as believable and not dominant (human judgement, recorded).
