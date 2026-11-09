# 12 — Aden Remembers (World Director)

> **Every other Lineage II server resets the world. On ours, the world remembers what you did.**

Aden Remembers turns ordinary activity into persistent regional, economic, political and historical consequences.
It interprets gameplay; it does not generate a quest game.

## 1. Regions

Regions follow H5 territories and their hunting grounds: Gludio, Dion, Giran, Oren, Aden, Innadril, Goddard, Rune,
Schuttgart, plus Hellbound, Gracia, and strategic subregions (e.g. Sea of Spores, Cruma Tower, Dragon Valley, Forge of the
Gods, Den of Evil, Primeval Isle). Each region keeps internal state — never shown as numbers:
prosperity, supply health per resource family, security, trade activity, resource pressure, monster pressure, conflict
intensity, outlaw activity, war exhaustion, clan influence, infrastructure, NPC confidence, import/export dependency.

```text
lc_region(id, name, territory_castle_id, parent_id)
lc_region_state(region_id, key, value, updated_at, reducer_ver)         -- derived, rebuildable
lc_resource_family(id, items JSON, protected_items JSON, budget_baseline, max_regional_variation, max_rate, recovery_curve)
lc_actuator(id, kind, region_id, target, value, min, max, max_rate, recovery, excluded JSON, reason_code, applied_at, op_id)
```

## 2. Inputs

Telemetry buckets (kills by family/region/actor class, yields, spoil, NPC buy/sell flows, private-store transactions, crafting,
teleport flows, travel, PvP/PK density, deaths, new-character activity, level distribution) and durable facts (castle/fortress
ownership, siege and Territory War outcomes, ward captures, Seven Signs periods, Hellbound level, seed states, Manor results,
tax changes, contracts, Ledger intensity, Signal demand, caravan outcomes, Crown Law).

## 3. Cadence

immediate: capture · 1–5 min: buckets · 10–30 min: short-term pressure · hourly: situations · daily: decay, history promotion,
slow economy/politics · weekly: eras and records. Never per tick.

## 4. Actuators and safety bounds

Every actuator has min, max, rate of change, recovery, exclusions, kill switch, audit, reason code. No output that cannot be
explained by `lc why`.

| Actuator | What changes | Perception channel |
|---|---|---|
| resource redistribution | bounded shift of a resource family's yield share between regions inside a **global supply budget** (default ±5 % of family supply per region *(tunable)*; protected retail drops and boss/quest items excluded) | merchants' demand, rumors, citizens and humans noticing yields |
| spawn context | which retail spawn groups are active/denser in a region (from retail data only) | monsters seen elsewhere, guards talking |
| merchant demand/stock | NPC buy lists and stock for existing items, within bounds | prices, "we're buying coal" notices |
| caravans | caravan dispatch, routes, cargo (§8) | convoys on roads |
| rumors | rumor NPCs and Gazette notes about real situations | tavern talk |
| institutional fees | Notarius/Broker fees in a territory, within bounds, when Crown Law or politics justifies | posted fee tables |
| situations | named situation chains (§5) | all of the above |

Aden Remembers never changes combat, rates, enchant/craft chances, boss stats or locations, or anything earned in native systems.

## 5. Situations (not events)

`CAUSE → CONSEQUENCE → HUMAN REACTION → NEW CONSEQUENCE → HISTORY`. No "FARM EVENT ACTIVE 60 MIN" banners.

```text
lc_situation(id, kind, region_id, state, started_at, cause_facts JSON, params JSON, next_eval_at)
lc_situation_transition(situation_id, from_state, to_state, at, reason_code, input_facts JSON)
```

### 5.1 Overharvest
Sustained extraction above budget → yield share shifts to neighbours, merchants elsewhere start buying, rumors appear after
thresholds, recovery when pressure stops.

### 5.2 Shortage and catch-in
A town's craft demand outruns supply of low-grade inputs (coal, animal skin, iron ore, stems, thread) → NPC demand and caravan
contracts for exactly the things low-level characters can farm. The newcomer is not pitied; the newcomer is useful.

### 5.3 Trade shift (economic warfare)
High castle tax (within the Seven Signs cap) → trade flow measurably moves to neighbouring towns → the taxing castle's income
falls → rivals gain relevance without a siege.

### 5.4 Outlaw pressure
PK density and Ledger intensity in a region → guard remarks, caravan risk premiums, Magistrate writs funded under Crown Law,
citizens travelling in groups.

### 5.5 Boss ecology
Activity around a grand boss or raid region (e.g. Sea of Spores / Orfen) → retail minion and subordinate spawn groups denser,
scouts and rumors, material demand for preparation, route value. Bosses themselves never move or change.

### 5.6 War exhaustion
Long wars in a territory → reduced trade, higher prices, deserted markets, mercenary demand.

### 5.7 Hellbound and Gracia
The Director observes Hellbound level and seed states and creates situations that invite lawful participation (e.g. supply
demand for Hellbound expeditions). It never sets level or trust.

## 6. Politics and Crown Law *(NEW v2)*

### 6.1 Influence
Derived from castle and fortress ownership, Territory War results, trade volume, defence of caravans, Manor participation,
public contracts, regional presence. Outputs: information access, fee schedules where Crown Law sets them, prestige, NPC
contract opportunities — and exposure. Never combat buffs.

### 6.2 Crown Law
A castle lord (clan leader of the owning clan) may enact up to N laws *(tunable, default 3)* for the castle's territory at the
Chamberlain. Laws are funded by **treasury commitments** moved into escrow through the Asset Service. Nothing is minted.

| Law | Effect | Funding |
|---|---|---|
| Bounty Endowment | adds a treasury-funded top-up to lawful Writs on chaotic characters in the territory | escrowed treasury pool, drains per payout |
| Market Charter | reduces Notarius fees in the territory's towns (bounded) | treasury pays the difference into the sink |
| Caravan Protection | pays escort bonuses for caravans entering the territory | escrowed pool |
| Amnesty | Magistrate stops issuing new Writs for karma earned before the proclamation date in the territory | no funding; costs political capital (influence) |
| Levy on Gatekeepers | adds a gatekeeper fee within the tax cap budget | income to treasury |
| Open Roads | caravans route preferentially through the territory | treasury-funded road wardens (guards) |

Rules: laws are public, proclaimed by heralds and town criers, listed in the Codex **Laws** tab, recorded in history; they
expire when ownership changes; they can be repealed; total levy plus castle tax never exceeds the Seven Signs cap; a law
never changes combat, rates or PK mechanics themselves.

### 6.3 Anti-zerg: complexity cost
An empire's burdens scale with its holdings: more routes and caravans to defend, more laws to fund, more visible treasury,
more territories with unrest, more rivals targeting it. Domination requires organisation, not population.

## 7. Imperfect information

Evidence channels only: NPC dialogue, merchant prices/demand, guard remarks, caravans, private-store behaviour, rumors with
provenance, the Gazette, the Chronicle, personal observation, Wayfarer's Memory, Signal demand patterns. Never a dashboard.
Detectability is tested mechanically (client-knowledge observer citizens must notice within a declared window);
comprehension is tested with humans. The promise is "no direct hidden-state API", not immunity to statistical inference.

## 8. Caravans *(NEW v2)*

Goods move physically.

- A caravan is an NPC convoy (Caravan Master + pack animals using existing client models via `displayId`) dispatched when
  trade flow between two towns is imbalanced. Its **manifest** is real merchant stock moved from the origin town's supply
  budget to the destination's; nothing is created.
- It travels retail roads at walking pace with a schedule visible at the origin town's notice board ("departs at 20:00").
- **Escort**: anyone can take a Caravan Escort contract (PACT delivery contract issued by the Caravan Guild, funded by the
  merchant fee and Crown Law Caravan Protection). Settlement on arrival, measured by participation.
- **Raid**: killing caravan guards and the master drops a bounded share of the manifest (the rest is "lost to the road",
  a sink). Raiding is an outlaw act: karma rules apply normally; the merchant guild posts a Writ funded from its fee pool.
- Arrival raises destination supply; a lost caravan creates shortage there. The Gazette reports notable raids.
- Fortress ownership on the route changes caravan escorts (fortress guards join) and gives the owning clan visibility.

```text
lc_caravan(id, origin_town, dest_town, route_id, manifest_custody_id, state, depart_at, arrived_at, outcome)
lc_caravan_escort(caravan_id, char_id, contract_id, joined_at, presence_seconds)
```

## 9. Historical graph

Durable history facts are promoted by thresholds (scale, rarity, duration, value, unique participants, political consequence,
first/record): castle reigns, sieges, Territory Wars, fortress holdings, clan wars, alliance betrayals that are mechanically
observable (leaving an alliance mid-war, breaking a public contract), major hunts, notable crafters and item provenance,
economic eras, long regional dominance, famous outlaws, public treaties, caravan disasters, laws.

```text
lc_history_event(id, kind, title_key, started_at, ended_at, region_id, significance, facts JSON, publishable)
lc_history_participant(event_id, char_id, clan_id, role, origin)
lc_historical_record(record_kind, holder, value, set_at, event_id)
```

## 10. Acceptance

- `AR-OVERHARVEST`: synthetic overfarm produces bounded, reversible consequences; global supply within budget.
- `AR-CATCHIN`: mature world + new humans → measurable low-level demand they can serve.
- `AR-TRADESHIFT`: tax change → trade flow shift within declared window.
- `AR-LAW-*`: every Crown Law respects funding, caps, expiry, proclamation, history.
- `AR-CARAVAN-*`: manifest conservation; escort settlement; raid drop bounds; shortage/arrival effects.
- `AR-PERCEPTION`: observer citizens detect each actuator; no hidden value recoverable through any API.
- `AR-DISABLE`: disabling the module restores stable retail behaviour.
- Every actuator change explainable with `lc why`.
