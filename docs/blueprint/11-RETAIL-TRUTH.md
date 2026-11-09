# 11 — Retail Truth

Mission: know, with evidence, what our server does exactly, approximately, wrongly or not at all — and make sure no change
(ours, upstream's, an agent's) moves it silently.

Two independent artifacts. Never confuse them.

## 1. Retail Truth cases (correctness)

### 1.1 Status
`EXACT` (matches evidence within tolerance) · `FUNCTIONAL` (works, equivalence unproven) · `APPROXIMATE` (measurably differs)
· `BROKEN` · `MISSING` · `UNKNOWN` (no sufficient evidence). Never upgrade UNKNOWN to EXACT because nobody complained.

### 1.2 Evidence classes (scoped to client build, region, update and observable property)
1. Official H5 client data files (skillgrp, skillname, weapongrp/armorgrp/etcitemgrp, itemname, npcgrp, npcname, systemmsg)
   — authoritative only for client-visible values (cast time, reuse, MP cost, range, item stats shown).
2. Archived official patch notes and official guides.
3. Community archives, videos, wikis, with a confidence level per source.
4. Captured traffic from retail-era recordings where available.
5. Mobius behaviour — never evidence of correctness, only of current behaviour.

Client data does not establish drop rates, land rates, AI or transaction semantics. Probabilistic mechanics require
repeated trials with confidence intervals. Expected results are derived independently of the code under test.

### 1.3 Case format
```yaml
id: RT-COMBAT-0001
feature: physical_critical_damage
chronicle: ct26_highfive
client_build: <pinned hash>
evidence: [{class: client_data, source: skillgrp.dat#..., property: ...}]
preconditions: {attacker: ..., defender: ..., buffs: ..., gear: ...}
actions: [...]
expected: [...]          # derived independently
tolerance: {...}
trials: {n: 2000, ci: 0.99}
status: EXACT|FUNCTIONAL|APPROXIMATE|BROKEN|MISSING|UNKNOWN
server_commit: ...
notes: ...
```

### 1.4 Coverage
Every class and feature through equivalence partitions, boundaries, known interactions and targeted combinatorial cases:
combat (P.Atk/M.Atk/defence, crit, blow, shield, speeds, accuracy/evasion, attributes, PvP modifiers, death penalties),
skills (learning, costs, reuse, targeting, land rate, triggers, cancellation, stacking/abnormal types, mastery),
NPC/AI (aggro, hate transfer, clan help, leash, spawn groups, minions, boss phases, skill gates), economy (NPC buy/sell, spoil,
drops, crafting incl. material behaviour on failure, private stores, Manor, taxes), social/PvP (clans, wars, karma, PK, sieges,
fortresses, Territory Wars, Olympiad, parties, loot), content (quests, instances, raids, grand bosses, Seven Signs, Hellbound,
Gracia seeds, pets/summons, teleports, class progression, subclasses), world (geodata, LoS, pathfinding, spawn timing, zones).

### 1.5 H5 content inventory
Every quest, instance, boss and world system gets a status. Known incomplete native paths are implementation work, not
residual risk: Seed of Destruction defense state, Seven Signs Festival gaps, and whatever the inventory finds.

## 2. Behaviour lock (stability)

- Golden suites capture current behaviour of formulas, skills, AI decisions, drops and timers across the class × skill × gear
  matrix, using keyed RNG streams and a controlled clock in the `truth` profile.
- A passing lock proves stability only, never correctness.
- Any diff fails CI unless accompanied by either an evidence-backed Retail Truth correction or a scoped `DESIGN_OVERRIDE`
  (domain, intended change, player-facing note). A `DESIGN_OVERRIDE` never blanket-approves regenerated goldens.
- The lock runs in the upstream intake gate; intake cannot regenerate the goldens it is judged by.

## 3. Clean profile

`truth` profile: all Living Chronicle modules disabled or frozen, all custom Mobius modules at neutral/off, keyed RNG,
controlled clock, isolated DB. Living Chronicle suites run in `dev`/`sim`, never contaminating truth measurements.

## 4. Regression contract

No merge moves a case from EXACT to anything lower, from FUNCTIONAL to BROKEN/MISSING, or reintroduces a fixed defect.

## 5. Player-facing honesty *(NEW v2)*

A public **Truth Report** on the Chronicle website: per domain, counts of EXACT/FUNCTIONAL/APPROXIMATE/BROKEN/MISSING/
UNKNOWN and the list of known deviations. Private-server land runs on folklore; we publish what we know and how we know it.

## 6. Acceptance
`RT-*` cases with evidence; `LOCK-*` goldens; intake gate green; Truth Report generated from the case database.
