# SPEC-AR — Aden Remembers

- Status: draft (revision 7: §11.1 signed off in Chronicle's round 6; the store atoms' actor class, from the Citizens review, under review; revision 6 was signed off; record in §12)
- Blueprint: 12 (Aden Remembers); 01 §2.5 (rumors are content); 02 §3 (game logic never branches on origin), §4, §6
  (budgets: hot-path seams ≤ 2 µs p99, never per tick); 03 §4.2 (the loot routing patch); 04 §1 (Fact Journal,
  telemetry), §3 (Asset Service), §6 (`lc why`), §7 (keyed randomness), §8 (degradation); 10 §8 (Genesis: Aden Remembers
  runs from day 0 on citizens' activity), §9 (citizens bounded by their own policy); 13 §4.8 (Signal demand); 15 (PACT
  contracts for orders and escorts); 16 (the Black Ledger's limits on external money); 17 (Chronicle publishes); 90 §2.
- Depends on: fact-journal.md (facts, telemetry, the projector runtime, effects), asset-service.md (custody, operations,
  the economy monitor), pact.md (the loot routing patch, guild orders, escorts), black-ledger.md (Bounty Endowments,
  Amnesty), intel-service.md (publication registry), codex-ui.md (the Laws tab, NPC pages), actor-identity.md (principals),
  aden-signal.md (§4.8's demand cells), chronicle.md (proclamations, boards, history publication).
- DDL: `sql/migrations/V015__lc_world.sql` (after Wayfarer's V014).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Aden Remembers turns what is actually done in the world into bounded, explainable, persistent consequences: regional
state, **situations**, a closed set of **actuators**, **Crown Law**, the **Merchant Guild** and its **caravans**,
**influence**, **rumors** and the **historical graph**. It interprets play; it generates no quests and no banners.

It owns: the region model (§2.1); the regional state reducers (§4.2); situations (§3.1, §4.4); the actuators (§4.3);
Crown Law (§4.5); the Guild's warehouses, prices, orders and caravans (§4.6); influence and the cost of holdings (§4.7);
world rumors and its publication types (§4.8); history promotion (§4.9).

**It changes no mechanic** (12 §4; actor-identity.md §3): no drop or spoil **probability**, rate, experience, combat,
enchant or craft chance, boss or raid stat, spawn, respawn time, location, Seven Signs, Hellbound level or trust, seed
state, siege, or retail NPC price (`mechanics/buylist/Product.java:38` has no setter, and a retail price is a rate). It
calls none of the methods actor-identity.md §3 lists. What it moves are **where** goods go after retail decided them, its
own Guild's prices, the restock delay of limited merchant stock, its caravans, its rumors, and fees that Crown Law
enacts.

**Nothing is created** (16 §7): every item the Guild holds was rolled by a retail drop or sold to it by a player; every
Adena it pays came from players or from a recorded share of retail sinks (§4.6).

**It treats every actor alike** (02 §3): citizens' activity moves the world exactly as humans' does, as Genesis requires
(10 §8); citizens are bounded by their own policy (10 §9: extraction concentration, farming duty), not by this module.

**Nothing is behind an API** (12 §7): no player- or website-facing interface returns a state value; the world shows its
state only through the channels of §4.3 and §4.8.

## 2. Data

### 2.1 Regions

```text
lc_region(region_id SMALLINT PK, kind {TERRITORY|SUBREGION}, name_key, castle_id NULL, parent_id NULL)
lc_region_atom(atom SMALLINT PK, territory_id)                         -- atom = the journal's region index
lc_subregion_spawn(spawn_file VARCHAR(128) PK, subregion_id)
lc_world_town(town_id PK, territory_id, neighbour_ids JSON)
```

- **Atoms** are the journal's dense map-region indices (fact-journal.md §2.3). Each belongs to exactly one **territory**:
  the castle named by its map region's `castle` attribute (`data/xml/MapRegionData.java:412`, `getAreaCastle`;
  `dist/game/data/mapregion/*.xml`), giving the nine H5 territories, plus **Hellbound** and **Gracia** for the atoms of
  those map regions, which name no castle. The table is generated at startup and written as a `config` fact.
- **Subregions** are the strategic hunting grounds (Sea of Spores, Cruma Tower, Dragon Valley, Forge of the Gods, Den of
  Evil, Primeval Isle, …), each a set of **spawn files**: the datapack already organises spawns by hunting ground
  (`dist/game/data/spawns/Giran/BrekasStronghold.xml`), and a spawned monster's file is
  `SpawnData.getSpawnFile(npc.getSpawn().getNpcSpawnTemplateId())` (`data/xml/SpawnData.java:1031`). At startup every spawn
  template id is resolved once to its subregion index (or 0) into an `int[]`, so no hot path does a zone or file lookup
  (`managers/ZoneManager.java:758` is a linear scan). `data/lc/subregions.csv` is validated at startup: every file exists,
  none is in two subregions, each subregion lies in one territory.
- **Towns** are the Guild's towns (one per territory, its main town) and their neighbours (adjacent territories' towns).

### 2.2 State, families, actuators

```text
lc_region_state(region_id, state_key, value INT, window_end, reducer_ver, PRIMARY KEY(region_id, state_key))
lc_resource_family(family_id PK, name_key, catch_in, max_relocate_permille, max_rate_permille_day, recovery_days)
lc_resource_item(item_id PK, family_id)
lc_resource_protected(item_id PK, reason)
lc_actuator_target(kind, region_id, target INT, value_permille, decided_fact_id, PRIMARY KEY(kind, region_id, target))
lc_actuator(application_id BINARY(16) PK, kind, region_id, target, value_permille, min_permille, max_permille,
            reason_code, situation_id NULL, fact_id, applied_at)
lc_actuator_switch(kind PK, enabled, changed_by, changed_at)
```

- `lc_region_state` and `lc_actuator_target` are **derived** (the projector's decisions), rebuilt identically by replay
  (§4.12). `lc_actuator` is the **applied** history, written by the effects that carry the decisions (fact-journal.md
  §4.4), append-only, its application id the effect's; the value in force is the newest row.
- A **resource family** groups items of one kind of supply (coal and charcoal, animal skins, iron ore, stems, threads,
  low-grade crystals, …; `data/lc/resource_families.csv`). An item belongs to at most one family. **Protected** items never
  belong to one (triggers on both tables, both directions): Adena, seal stones, herbs, quest items, event items, and every
  item any raid or grand boss drops.

### 2.3 Situations

```text
lc_situation(situation_id PK, kind, region_id, state {EMERGING|ACTIVE|RECEDING|CLOSED}, opened_fact_id, opened_at,
             cause JSON, params JSON, next_eval_at, closed_at NULL)
lc_situation_transition(situation_id, seq, from_state NULL, to_state, at, reason_code, input_fact_from, input_fact_to)
```

One open situation per (kind, region). The transition rows are the record and the authority: a situation's state changes
only together with the transition row that moves it (a trigger checks the newest row), and a transition row follows the
machine of §3.1 (a trigger). Both are append-only.

### 2.4 Crown Law, influence, the PK record

```text
lc_law(law_id PK, castle_id, kind {BOUNTY_ENDOWMENT|MARKET_CHARTER|CARAVAN_PROTECTION|AMNESTY|GATEKEEPER_LEVY|OPEN_ROADS},
       state {ENACTED|REPEALED|EXPIRED|EXHAUSTED}, reign_key, enacted_by, enacted_at, proclaimed_fact_id, proclaimed_at, params JSON,
       custody_id NULL, ended_at NULL, end_reason NULL)
lc_influence(territory_id, clan_id, value INT, window_end, PRIMARY KEY(territory_id, clan_id))       -- derived
lc_influence_spend(spend_id PK, territory_id, clan_id, reign_key, amount INT, law_id, fact_id)        -- durable
lc_world_pk(char_id PK, last_pk_fact_id BIGINT, last_pk_at)                                         -- derived
```

- A **reign** is (castle, owning clan, the owner fact that began it); a law belongs to the reign that enacted it.
- One enacted law per (castle, kind) (so at most six per castle; `MaxLawsPerCastle` may allow fewer); one Amnesty per
  reign in any state; ended laws are never changed or deleted (triggers).
- **Influence** is derived daily; what has been **spent** is durable (`lc_influence_spend`, written with the law it paid
  for), so the available influence of a clan in a territory is its derived value minus its spends of the current reign,
  the same live and on replay.
- `lc_world_pk` holds, per character, the latest **occurrence time** of its applied PK facts (`last_pk_at`, the maximum
  `occurred_at`, fact-journal.md §2.2) and that fact's id, for Amnesty; a law's `proclaimed_at` is its proclamation
  fact's occurrence time. Fact ids are delivery order, not time (an outbox fact can be relayed before an earlier
  capture's phase commits, fact-journal.md §3.3), so Amnesty compares times.

### 2.5 The Merchant Guild and caravans

```text
lc_guild_town(town_id PK, territory_id UNIQUE, warehouse_custody_id, pool_custody_id)
lc_guild_band(item_id PK, buy_ceiling BIGINT, sell_floor BIGINT, application_id)                   -- buy_ceiling < sell_floor
lc_guild_price(town_id, item_id, buy_price, sell_price, application_id, PRIMARY KEY(town_id, item_id))
lc_guild_order(order_id PK, town_id, item_id, qty, unit_price, contract_id UNIQUE, situation_id NULL,
               state {POSTED|FILLED|EXPIRED})
lc_guild_stock(town_id, item_id, qty BIGINT, fact_id, PRIMARY KEY(town_id, item_id))               -- derived
lc_caravan_route(route_name PK, origin_town, dest_town, nodes, scheduled_minutes, fort_ids JSON)
lc_caravan(caravan_id PK, route_name, manifest_custody_id UNIQUE, state {SCHEDULED|EN_ROUTE|ARRIVED|RAIDED|RECALLED|SETTLED},
           depart_at, last_node, arrived_at NULL, outcome JSON NULL, escort_contract_id NULL)
lc_caravan_escort(caravan_id, char_id, clan_id NULL, contract_id, joined_at, presence_seconds)
```

- Each Guild town has a **warehouse** custody (real goods) and a **pool** custody (Adena) (asset-service.md §2.2, `POOL`).
- **No loop gains Adena through the Guild**: each family item has a **band** (`lc_guild_band`) with its buy ceiling
  strictly below its sell floor, and every town's buy price and every open order's unit price for the item is at most the
  ceiling, every town's sell price at least the floor. So every buy is below every sell without comparing rows of one
  table with each other. Triggers check each price or order row against its item's band, read with a locking read of
  `lc_guild_band` (a different table, so always the latest committed row whatever the writer's isolation or snapshot),
  which also serialises them with band changes; a band change is checked against the item's prices and open orders with
  locking reads of those tables, so it never leaves one outside. A band is never deleted, every price and order row has a
  foreign key to its item's band, and a band's insert is checked against the item's rows like a change, so no
  delete-and-recreate can narrow one past existing rows. No check reads another row of its own table, so no
  isolation level and no ordering of writers can break the rule. The only writer is Aden Remembers' effect dispatcher
  (fact-journal.md §4.4); the triggers hold the rule for any writer.
- `lc_guild_stock` is the projector's view of each warehouse, built from the Guild's facts (§5), so decisions read a
  history, not a live custody; the verifier compares it with the custody (§4.11).

### 2.6 Rumors and history

```text
lc_world_rumor(rumor_id PK, territory_id, kind, template_key, params JSON, from_at, until_at, situation_id NULL)
lc_history_event(event_id PK, kind, title_key, started_at, ended_at NULL, region_id NULL, significance, facts JSON,
                 publishable, humans INT, synthetic INT)
lc_history_participant(event_id, seq, char_id NULL, clan_id NULL, role, origin {HUMAN|SYNTHETIC})
lc_historical_record(record_kind PK, holder_kind, holder_ref, value, set_at, event_id)
```

`lc_world_rumor.params` holds only rule-allowed values (§4.8). A history event carries per-event human and synthetic
participant counts for history's humans-only filter (actor-identity.md §3, publication domain); participants keep origin
for that filter's counts and are never published with it (chronicle.md §4.1).

## 3. State machines

### 3.1 Situation

| From | Event | To | Effects |
|---|---|---|---|
| — | its cause holds at a step (§4.4) | `EMERGING` | rumors after their delay (§4.8) |
| `EMERGING` | the cause holds for its confirmation steps | `ACTIVE` | its actuator asks (§4.4) |
| `EMERGING` | the cause fails | `CLOSED` | nothing was asked |
| `ACTIVE` | the cause fails for its release steps | `RECEDING` | asks withdrawn; values recover (§3.2) |
| `RECEDING` | the cause holds again | `ACTIVE` | asks restored |
| `RECEDING` | every value it asked for is neutral | `CLOSED` | history considered (§4.9) |
| `EMERGING`, `ACTIVE`, `RECEDING` | freeze or disable (§4.10), reason `FROZEN` or `DISABLED` | `CLOSED` | every value neutral at once |

`ACTIVE → CLOSED` exists only with reason `FROZEN` or `DISABLED`.

### 3.2 Actuator value

Each actuator value is independent: its **target** is the sum of the open situations' asks for it, clamped to its bounds;
the applied value moves toward the target by at most `max_rate` per day at each daily step, and with no ask recovers to
neutral over `recovery_days`. No value is coupled to another by a budget, so bounds and rates are always satisfiable. A
kill switch, a freeze or a disable sets it to neutral at once: going back to retail is never rate-limited.

### 3.3 Law

| From | Event | To | Effects |
|---|---|---|---|
| — | `enact` at the Chamberlain (§4.5) | `ENACTED` | treasury commit (funded kinds); influence spend (Amnesty); proclamation (chronicle.md §4.2), the Codex Laws tab, history |
| `ENACTED` | `repeal` by the lord | `REPEALED` | custody remainder returned to the treasury; proclamation |
| `ENACTED` | the reign ends (an owner fact of the castle) | `EXPIRED` | remainder returned to the castle's treasury, whoever owns it now |
| `ENACTED` | a funded law's custody below its minimum payout | `EXHAUSTED` | remainder returned; proclamation |

While the module is frozen or disabled, enacted laws are **suspended**: no levy is charged, the charter factor is 1,
endowments and protections pay nothing, wardens are despawned; custodies are kept. Suspension is a condition read at each
use (§4.5), not a state.

### 3.4 Caravan

| From | Event | To | Effects (one transaction with the Asset operation, settlement key `CARAVAN/<id>/<step>`) |
|---|---|---|---|
| — | dispatch (§4.6) | `SCHEDULED` | warehouse → manifest (Asset transfer); escort contract issued with its payment escrowed from the pool |
| `SCHEDULED` | `depart_at`, module running | `EN_ROUTE` | spawned at the origin node, walked from node 0 |
| `SCHEDULED` | disable, or the origin's pool cannot fund the escort | `RECALLED` | manifest → origin warehouse; escort escrow refunded |
| `EN_ROUTE` | the last node reached (the arrival seam, §4.6) | `ARRIVED` | manifest → destination warehouse; escort settled by PACT |
| `EN_ROUTE` | the Caravan Master killed | `RAIDED` | `raid`: share dropped once after commit, the rest lost to the road; escort escrow refunded |
| `EN_ROUTE` | not arrived by `depart_at` + `CaravanTimeoutFactor` × scheduled (time parked excluded), or disable | `RECALLED` | manifest → origin warehouse; escort escrow refunded; despawned |
| `ARRIVED`, `RAIDED`, `RECALLED` | its history settled | `SETTLED` | — |

**Freeze parks caravans.** While the module is frozen the Asset Service commits no operation on its custodies except
corrective admin operations (asset-service.md §4.2, rule 7), so a freeze moves no manifest: `SCHEDULED` caravans do not
depart, `EN_ROUTE` caravans are despawned where they are with `last_node` kept, and the timeout clock stops (the parked
time is recorded in `outcome`); at the unfreeze they depart or re-spawn at `last_node` and go on. A disable, by contrast,
happens while the module still runs: its recalls are ordinary operations, run before the module stops acting.

**Terminal resolution**: arrival, the Master's death and the timeout can race; each tries one transaction that changes
the caravan from `EN_ROUTE` (a conditional update) and runs its Asset operation under the key `CARAVAN/<id>/terminal`.
Exactly one commits; the others find the key taken and do nothing. The caravan in the world is a projection of its row:
after a restart an `EN_ROUTE` caravan is re-spawned at `last_node` and resumes from it (§4.6).

## 4. Operations

### 4.1 Inputs

Aden Remembers is one projector (fact-journal.md §4) over durable facts and telemetry windows.

**Telemetry** (fact-journal.md §2.6, amended, §11): families with a declared **region dimension**, atom or subregion; the
subregion of a monster is the `int[]` of §2.1, read at the seam. Families: `ar_kills` (subregion; `ON_CREATURE_DEATH` on
`Containers.Monsters()`, synchronous, `entity/actor/Creature.java:2626-2628`), `ar_yield_<family>` (subregion; one
journal family id per resource family, in a range registered to Aden Remembers; counted at the loot routing patch: items
rolled, and `sum_a` items relocated), `ar_npc_trade` (atom; `network/clientpackets/RequestBuyItem.java:243`,
`RequestSellItem.java:203`; `sum_a` Adena paid, `sum_b` received), `ar_store_trade` (atom; `network/holders/
TradeList.java:637`, `:859`; Adena), `ar_store_units_<family>` (atom; at the same two points, `sum_a` units of the family's
items that changed hands; for both store atoms the actor class is the **seller's**), `ar_craft_<family>` (atom; materials of the family consumed, at their destruction in
`RecipeItemMaker.listItems(true)`, `managers/RecipeManager.java:642`, called at `:404` **before** the success roll at
`:416`, so failed attempts count; and a contractual craft's `CRAFT_INPUT` legs from the Asset Service's facts, pact.md
§4.3), `ar_teleport_fee` (atom; `entity/teleporter/TeleportHolder.java:221`, `sum_a` Adena
destroyed), `ar_pvp` (atom; `Player.java:5609`), `ar_deaths`, `ar_new_chars` (`network/clientpackets/CharacterCreate.java:
327-329`, synchronous), and an `ar_levels` snapshot of online characters per atom each bucket. Each is a telemetry seam (a
core patch, `CORE_PATCHES.md`, ≤ 2 µs).

**Durable facts**, each produced at a synchronous seam at the authoritative point because Mobius dispatches nothing
there, or only asynchronously: castle owner set and removed (`mechanics/siege/Castle.java:506`, `:571`; the only event
fires in one branch of `midVictory`, `Siege.java:469-471`), fortress owner set and removed (`mechanics/siege/Fort.java:380`,
`:467`), siege start and end (`Siege.java:481`, `:251`; `FortSiege.java:308`, `:251`), Territory War start and end
(`managers/TerritoryWarManager.java:942`, `:1043`) and ward capture (`:376`), Seven Signs period and seal owners
(`mechanics/sevensigns/SevenSigns.java:1508`, `:1273`), tax change (`Castle.java:605`), Hellbound level and trust
(`dist/game/data/scripts/ai/areas/Hellbound/HellboundEngine.java:174`, `:342`), seed states (`managers/
SeedOfDestructionManager.java:201`, `managers/SeedOfInfinityManager.java:87`, `dist/game/data/scripts/ai/areas/Gracia/AI/
SeedOfAnnihilation/SeedOfAnnihilation.java:302-324`), Manor settlement (`managers/CastleManorManager.java:346-424`), clan war
start and end (after the clan sets change, `data/sql/ClanTable.java:400`, `:443`), and **innocent kills**
(`World.InnocentKilled`: killer, territory; at `Player.onKillUpdatePvPKarma`, `Player.java:5609`, when it raises the
killer's karma), and **clan presence** (`World.ClanPresence`: day, territory, clan, distinct member principals), written
once a day from a presence sampler that every `PresenceSampleMinutes` (15) adds each online clan member's principal to an
in-memory sketch per (territory of the member's atom, clan): off the hot path, and durable as a fact so replay has it.
Also consumed: the Guild's and caravans' own facts, Asset, PACT and Ledger facts as counts (never a
contract's parties), and Signal's daily demand cells (aden-signal.md §4.8).

### 4.2 Regional state

Per region, each key an exponentially weighted average updated at its step, as integers (‰ of the key's trailing 28-day
median baseline unless stated), so a whole server's growth or decline moves nothing:

| Key | Region | Source | Step, half-life |
|---|---|---|---|
| `resource_pressure_<family>` | subregion | `ar_yield_<family>` items rolled | hourly, 6 h |
| `supply_health_<family>` | town | `lc_guild_stock` + store trade of the family, against `ar_craft_<family>` consumption | daily, 3 d |
| `craft_demand_<family>` | town | `ar_craft_<family>` + Signal `CRAFTING`/`MATERIALS` cells of the territory | daily, 3 d |
| `trade_activity` | town | `ar_npc_trade` + `ar_store_trade` Adena | daily, 3 d |
| `outlaw` | territory | `ar_pvp` innocent kills + Ledger contract counts | daily, 3 d |
| `conflict` | territory | sieges, Territory War battles, ward captures (count over 14 days, absolute) | daily |
| `kills_lair_<boss>` | subregion | `ar_kills` in the lair's spawn files | hourly, 12 h |
| `prosperity`, `security`, `npc_confidence` | territory | trade activity; inverse outlaw; inverse conflict and tax | daily, 7 d |

Distinct counts are of principals (actor-identity.md §2.2), so one account's characters count once.

**Arithmetic.** A daily value *x* of a key is its source's sum for the day. Its **baseline** is the median of its last 28
daily values, once at least 7 exist; before that (Genesis, a new region, a new family), or while the median is 0, the key
is neutral (1000) and no situation can open on it (**cold start**). A key's value is round(1000 × EWMA(*x*) ÷ baseline),
the EWMA with the half-life in the table, evaluated at its step. Two keys are computed differently:

- `supply_health_<f>` (town) is **days of cover** in ‰: 1000 × (Guild stock of the family's items in `lc_guild_stock` +
  the 7-day mean of `ar_store_units_<family>` in the town's atoms per day) ÷ max(1, the 7-day mean of `ar_craft_<family>`
  units in the town's atoms per day × the Signal factor), where the Signal factor is 1.10, 1.25 or 1.50 when
  the territory's latest `CRAFTING` or `MATERIALS` demand cell is 5-9, 10-19 or 20+ (aden-signal.md §4.8), and 1 without
  one. `SHORTAGE` reads it directly (§4.4); `craft_demand_<f>` is the denominator's daily units.
- `conflict` is the absolute count of the territory's sieges, Territory War battles and ward captures in the last 14 days.

**Influence** of a clan in a territory is the EWMA (half-life 14 days) of daily points, each from a durable input: 100
for owning its castle and 25 per fortress in it (owner facts); 20 per ward captured there (`World.WardCaptured`, with the
capturer's clan); 1 per hour of members' escort presence on caravans through it (`lc_caravan_escort`, whose `clan_id`
is the member's clan at joining); 10 per day the castle it owns has a Manor procure or seed list set (`World.ManorSettled`);
5 per public PACT contract with the clan as a party completed there (PACT's completion facts); and 1 per distinct member
principal of the day's `World.ClanPresence` there.

**History significance** of a candidate event is: distinct participant principals + 2 × duration in hours + value in
millions of Adena (manifests, contract totals, treasuries moved) + 100 if it is a first or a record + 200 if it changed an
owner or a law; it is promoted when ≥ `HistorySignificance`.

### 4.3 Actuators

Every actuator has a kind, bounds, a rate, a recovery, exclusions, a kill switch, a reason code and an application id;
every applied value is explained by `lc why` (§9).

#### 4.3.1 Drop relocation (`RELOCATE`, per subregion and resource family)

A share *r* ∈ [0, R] per mille (R = the family's `max_relocate_permille`, default 50, ceiling 100). At the **loot routing
patch** PACT's Loot Charter uses (03 §4.2; pact.md §4.5): in `Attackable.doItemDrop`, after the death-drop list and the
spoil list are computed and before any item reaches the ground or an inventory (`entity/actor/Attackable.java:1173`,
`:1176-1191`), and after a Loot Charter has taken its items, each **family item** of the kill from a monster of that
subregion is selected if an independent keyed roll (04 §7, seeded by the kill key) falls below *r*. The selected items go,
with exactly the counts retail gave them, into the warehouse of the destination town decided for (subregion, family),
through **one Asset operation per kill**, under the kill key, source `RELOCATED_DROP` (an amendment like `LOOT_DROP`,
asset-service.md §2.5), run exactly as the Loot Charter runs its `route` (pact.md §4.5): **synchronously on the thread
that is dropping the kill's loot**, inside the hook, before the hook returns. Its outcome decides the items: `COMMITTED`
— relocated; aborted (anything short of commit, asset-service.md §3.2) — the hook hands them back to retail, which drops
them as it would have; `UNCERTAIN` — the Asset Service resolves it on that thread before returning (§3.2: a locking read,
never a timeout abort), so the hook always ends knowing which. Until then the items belong to the hook's local list and
nowhere else. **Stated limit**, the Loot Charter's: if the JVM dies between the kill and the commit, the selected items
are lost with it, as retail's own ground drops of that kill are without `SaveDroppedItem`; no fact claims them. At most
`RelocationOpsPerSecond` operations run server-wide; beyond it a kill's selection is skipped (its items go to retail,
counted), so relocation never queues on the kill path.

- **The retail roll is untouched**, so every item's probability, every group's one-per-group behaviour, protected items
  and Adena are exactly retail; only the destination of a rolled family item changes.
- **Supply is conserved exactly** while the server runs: every family item retail rolls is dropped, auto-looted or
  relocated, never two of them (the stated limit above is the only exception). No budget is needed and none can be broken
  by rates, champions, premium bonuses or player bonuses.
- Excluded: protected items (§2.2); kills of raid and grand bosses and of their minions; kills outside every subregion.
- Hot path: one array read `share[subregion][family]` from an immutable snapshot swapped at each step, the item → family
  `int[]`, and a keyed roll only for family items of a subregion whose share is above zero: the 2 µs budget covers this
  selection only. The Asset operation runs only for kills with a selected item, rate-capped, and is held to the Asset
  Service's commit budget (asset-service.md ASSET-R21, `CommitBudgetMillis` at p99).
- Perception: hunters there find fewer of the family; the destination's Guild offers them; Guild prices; rumors.

#### 4.3.2 Limited merchant stock (`STOCK`, per town and product)

For buy-list products with a limited count (`dist/game/data/buylists/*.xml`, the `count` attribute: 2 072 entries,
mostly clan-hall scrolls, strider food and enchant scrolls), the restock delay may be scaled by [700, 1500] per mille,
through a factor read where a sale schedules the restock (`mechanics/buylist/Product.java:107`); the count, the price and
the item never change. A restock already scheduled keeps its time, so after a disable each product is retail **from its
next restock** (a stated limit; AT-AR-09 checks exactly this).
Perception: "sold out" more or less often.

#### 4.3.3 Guild prices (`GUILD_BUY`, `GUILD_SELL`, per town and item)

The Guild's own buy and sell prices for family items (§4.6), within [`GuildBuyMinPermille`, `GuildBuyMaxPermille`] of the
retail buy-back (reference price ÷ 2, `network/clientpackets/RequestSellItem.java:165`, `:183`) and
[`GuildSellMinPermille`, `GuildSellMaxPermille`] of the reference price, moving at most `GuildPriceRatePermilleDay` per
day, always inside the item's band (§2.5). The band is decided with the prices at each daily step: the ceiling is the
highest buy or order price it will allow and the floor the lowest sell price, a band change committed before the prices
that need it when it widens and after them when it narrows. Perception: posted prices and orders at the Guild Master.

Mobius has no runtime spawn-group API (`entity/spawns/SpawnGroup.java:224`) and Aden Remembers needs none: it changes no
spawn and no respawn time, so "denser monsters near a boss" (12 §4) is not an actuator. A denser spawn changes experience
and Adena per hour, which are rates; boss ecology shows through demand, caravans and rumors instead (§4.4).

### 4.4 Situations

`CAUSE → CONSEQUENCE → HUMAN REACTION → NEW CONSEQUENCE → HISTORY` (12 §5). Steps are counted in the cadence of the cause's
keys (§4.2). Every kind's parameters are here; changing one is a spec change.

| Kind | Region | Cause | Confirm / release | Asks |
|---|---|---|---|---|
| `OVERHARVEST` | subregion | `resource_pressure_<f>` ≥ 1500 (cold start: never) | 6 / 12 hourly steps (release below 1200) | `RELOCATE` share = min(R, (pressure − 1000) ÷ 10) ‰, destination the neighbouring Guild town with the lowest `supply_health_<f>`; that town's Guild buy price for the family + 100 ‰ |
| `SHORTAGE` | town | `supply_health_<f>` < 500 (less than half a day of cover) while `craft_demand_<f>` ≥ `MinDemandUnits` | 2 / 2 daily steps (release at ≥ 1500) | Guild orders for the family's catch-in items (quantity: the demand gap, capped by the pool), caravans from neighbours whose stock of the family is ≥ 2 days of their own demand |
| `TRADE_SHIFT` | territory | its castle's tax raised by ≥ 5 points, then its town's `trade_activity` ≤ 850 while a neighbour's ≥ 1100 | 3 / 3 daily steps (release within 950–1050) | rumors and proclamations on prices; Guild sell prices − 50 ‰ in the gaining neighbours |
| `OUTLAW_PRESSURE` | territory | `outlaw` ≥ 2000 | 2 / 3 daily | escort payment + 250 ‰ for caravans through it; caravans route around it unless Open Roads; rumors |
| `BOSS_ECOLOGY` | subregion with a raid or grand boss lair | `kills_lair_<boss>` ≥ 2000 | 2 / 2 daily | Guild orders for preparation consumables (`data/lc/preparation_items.csv`) in the territory's town; caravans of them; scouts' rumors |
| `WAR_EXHAUSTION` | territory | `conflict` ≥ 3 | 1 / 7 daily | Guild sell prices + 100 ‰ and orders − 500 ‰ there; deserted-market rumors |
| `UNREST` | territory | tax ≥ 15 for 14 days and `WAR_EXHAUSTION` active | 1 / 3 daily | rumors of discontent only (never combat, never a spawn) |
| `FRONTIER` | Hellbound or Gracia | a Hellbound level or seed state fact | at once / after 7 days | Guild orders for expedition supplies; rumors inviting lawful participation |

The blueprint rows of NPC reactions map onto these: shortages (`SHORTAGE`), migration (`OVERHARVEST`), opportunity
(`SHORTAGE`, `BOSS_ECOLOGY`, `FRONTIER`), banditry (`OUTLAW_PRESSURE`, raids), smuggling (`TRADE_SHIFT`), corruption and
rebellion (`UNREST`, dialogue only).

### 4.5 Crown Law

The lord of a castle (the leader of its owning clan) enacts, repeals and reads laws at the castle's **Chamberlain**, in a
new `crown_law` action of `dist/game/data/scripts/ai/others/CastleChamberlain/CastleChamberlain.java` guarded like
`manage_tax` (`:797`, `isOwner` at `:472`, plus "is the clan leader"), reached by the script bypass
(`dist/game/data/scripts/handlers/bypass/npc/ScriptLink.java:86`, which requires interaction range), each action a Codex
confirmation (codex-ui.md §4.4). Laws are public: proclaimed by Chronicle's criers (chronicle.md §4.2), listed in the
Codex Laws tab, recorded in history. A law never changes combat, rates or PK mechanics.

| Law | Effect | Funding and bounds |
|---|---|---|
| Bounty Endowment | tops up `CURSED_WEAPON` Writs on cursed-weapon holders in the territory, and only those (black-ledger.md §4.3) | committed treasury Adena in custody; `EndowmentCapAdena` per weapon life as the Ledger defines it |
| Market Charter | a rebate of (1 − `CharterFactorPermille`) of the territory's Notarius fees, paid to the payer after each signing (pact.md §2.2) | the payer pays the full fee into `FEE_NOTARIUS`; the custody pays the rebate; exhausted → the law ends |
| Caravan Protection | escort payments for Guild caravans through the territory × (1 + `ProtectionBonusPermille`) | committed custody |
| Amnesty | the territory's Magistrates refuse a new `KARMA` Writ on a mark with no PK since the law's proclamation (a predicate the Ledger asks at `open`, black-ledger.md §4.3), decided on the **absence** of a captured fact as the journal requires (fact-journal.md §3.3): at an `open` at time *T* the predicate waits, at most `AmnestyBarrierMillis`, until Aden Remembers' checkpoint has passed a committed phase whose frontier is ≥ *T*; then it answers "amnesty" only if `lc_world_pk.last_pk_at` (or no row) is before the law's `proclaimed_at` **and** no gap record of the PK family overlaps the span from the proclamation to *T*; otherwise (not reached in time, a gap, the projector stopped) it answers "no amnesty" and the Writ opens: failing closed means a mark is never shielded on missing evidence | no money; `AmnestyInfluenceCost` influence spent (§2.4); once per reign |
| Gatekeeper Levy | `LevyPermille` of the retail fee added to teleports from the territory's gatekeepers, credited to the treasury (`mechanics/siege/Castle.java:333`, `addToTreasuryNoTax`) | charged only if, **at the charge**, the law is enacted, the module running, and castle tax % + levy % ≤ the current Seven Signs cap (`managers/CastleManager.java:216-245`: 5, 25 or 15), all read synchronously in the teleport seam; the levy is destroyed with the fee and the treasury credited after, so a crash between can only lose it |
| Open Roads | Guild caravans route through the territory when an alternative exists; road wardens (an existing guard template) walk its caravan routes | committed custody pays `WardenWageAdena` per warden-hour into the `CROWN_UPKEEP` sink |

**Holdings** (12 §6.3): retail lets a clan own one castle (`mechanics/siege/Siege.java:1164`, `:1386`), so empires are
alliances. A clan's **holding weight** is the castles (1 each) and fortresses (`FortWeightPermille`, default 250) owned by
its alliance's clans; every funded law costs × (1 + (weight − 1) × `HoldingUpkeepPermille` ÷ 1000), and Amnesty's
influence cost likewise.

**Tax integrity**: the levy's cap is meaningful only if the castle tax is within [0, cap], and Mobius accepts a negative
tax from a crafted bypass (`CastleChamberlain.java:827-847` checks only the upper limit; `Castle.setTaxPercent`,
`Castle.java:605-621`, checks nothing). The Crown Law core patch clamps `setTaxPercent` to [0, the current cap], which
retail's own UI never leaves; it is ledgered as a retail bug fix.

### 4.6 The Merchant Guild and caravans

**Selling to the Guild** (at a town's Guild Master, a new NPC on an existing model): a player sells family items at the
town's buy price, in one Asset `exchange` (player items → warehouse, pool Adena → player; an amendment, §11); refused when
the pool cannot pay. **Orders** are PACT Purchase Orders whose buyer is the `MERCHANT_GUILD` institution (pact.md §4.1a), filled into the
warehouse custody, with the maximum spend moved from the pool when issued and the remainder returned to it on expiry,
for the catch-in items of a `SHORTAGE` or the supplies of `BOSS_ECOLOGY` and `FRONTIER`: a newcomer's spoils become what a
town needs (12 §5.2).

**Buying from the Guild**: players buy warehouse goods at the town's sell price, in one Asset `exchange` (player Adena →
pool, warehouse items → player).

**Funding**: a pool receives what players pay it, and each day a **recycled share** of its territory's retail teleport
fees: `RecyclePermille` (default 100, ceiling 250) of the Adena the `ar_teleport_fee` telemetry recorded as destroyed the
previous day (`SINK_RECYCLE`, a source bounded by a recorded sink, an amendment). Telemetry can only under-record (a
dropped add is lost, never doubled, fact-journal.md §3.4), so the recycle never exceeds what retail destroyed. These are the
only Adena Aden Remembers puts in players' hands. A pool is never negative.

**Caravans** move warehouse goods (12 §8). A caravan is dispatched for a `SHORTAGE` (or from a full warehouse toward a low
one) at most `CaravansPerTownDay` per origin, its manifest capped at `ManifestMaxValueAdena` at reference prices. Its
route is a datapack route in `dist/game/data/Routes.xml` (`lc_caravan_*`, validated at startup against
`lc_caravan_route`), since Mobius has no runtime route API (`managers/WalkingManager.java:73`, `:212`). The Caravan Master
and pack animals are new NPC templates on existing client models (`displayId`), with no drops, experience or SP.

- **Walking and resuming**: Mobius's `startMoving` (`WalkingManager.java:279`) builds a `WalkInfo` whose cursor starts at
  the first node (`entity/actor/holders/npc/WalkInfo.java:74`), and its node-arrival event is asynchronous, carries no node
  index and is sent before the arrival check completes (`WalkingManager.java:447-449`). The caravan core patch adds
  `startMoving(npc, route, fromNode)` and a synchronous **arrival seam** after the arrival check, passing the node index:
  it advances `last_node` (monotonic, a trigger) and, at the route's last node, starts the arrival transaction (§3.4).
  After a restart an `EN_ROUTE` caravan is spawned at `last_node` and walked from it.
- **Escort**: a Caravan Escort contract (pact.md §4.2, issuer the Caravan Guild), escrowed from the pool (plus Caravan
  Protection's custody) at dispatch, settled on arrival by participation, refunded to the pool on a raid or a recall.
  **Participation**: while the caravan is `EN_ROUTE`, every `EscortSampleSeconds` (30) each registered escort (enrolled at the Guild Master, pact.md §4.2) who is alive,
  online and within `EscortRadius` (1500) of the Caravan Master is credited the interval (`presence_seconds`); at arrival
  the escrow is split among escorts whose presence is at least `EscortMinSharePermille` (300) of the travel time, in
  proportion to their presence, the remainder (all of it if nobody qualifies) refunded to the pool. PACT settles the
  contract with these shares (pact.md §4.2's Caravan Escort: "delivery + participation").
- **Raid**: killing the Caravan Master is the `RAIDED` transition (§3.4): `raid` drops `RaidSharePermille` (default 300) of
  the manifest once, after commit, at the Master's position (a ground drop, asset-service.md §2.5 and ASSET-R25), the rest lost to
  the road (`ROAD_LOSS`). Killing NPCs gives no karma in Mobius; raiders who kill escorts or bystanders get karma and flags
  by the normal rules, and the raid becomes history, `outlaw` pressure and rumors. No Writ is posted on raiders: the
  Ledger takes external money only for cursed weapons (black-ledger.md §4.3), and a raid names no one.
- **Arrival** raises the destination's stock; a lost caravan leaves its shortage. Fortresses on a route (`fort_ids`) give
  their owning clan the schedule in the Codex (visibility only). Schedules are public (Chronicle's notice boards,
  chronicle.md §4.2) from `CaravanNoticeMinutes` before departure.

### 4.7 Influence and the cost of holdings

Influence per (territory, clan), derived daily: castle and fortress ownership, Territory War results, members' escort
presence, Manor participation, public PACT contracts, regional presence (distinct member principals). It buys Amnesty
(§4.5), orders equal applicants for Guild escorts (never excluding anyone), and adds prestige in history; and it
**exposes**: the clan with the most influence in a territory is named in its rumors (a public fact). Never a combat or rate
effect. Holdings raise burdens (§4.5's upkeep; more caravans through owned territories to protect; `UNREST` under high
tax), so domination requires organisation, not population.

### 4.8 Rumors and publication

- **Rumor NPCs** (tavern keepers, guards, travellers: listed templates) add a rumor line to their chat window through a
  seam in `Npc.showChatWindow` (`entity/actor/Npc.java:843`, `:879`) for those templates only, shared with Chronicle's
  remembering NPCs (chronicle.md §4.2), never a container-wide first-talk listener (which would skip every NPC's default
  window, `dist/game/data/scripts/handlers/actions/click/NpcClick.java:121-128`).
- A rumor is a template with fields of these kinds only: a territory or town, a subregion, a resource family, a situation
  kind, a band word (few, some, many; rising, falling), a public entity (a castle, the most influential clan, a law). Never
  a character, a number, a position or anything from intel. A rumor about a situation starts `RumorDelayMinutes` (default
  360) after the situation became `EMERGING` and lives at most `RumorLifeHours`. Wayfarer records a heard rumor as
  `RUMORED`/`WORLD` (wayfarers-memory.md §2.1).
- **Publication types** registered by Aden Remembers (intel-service.md §4.7), written by its projector:

| Type | Fields (rule) | Delay |
|---|---|---|
| `world_market` | resource family (`TEXT_FROM_FACT`), territory (`AREA_TERRITORY`), direction word (`TEXT_FROM_FACT`: wanted, plentiful), day (`DAY`) | tactical |
| `world_caravan` | origin and destination towns (`PUBLIC_ENTITY`), outcome (`TEXT_FROM_FACT`), day | tactical |
| `world_map_words` | territory, prosperity word and conflict word (`TEXT_FROM_FACT`, one of five bands), week (`DAY`) | tactical |

These and the rumors are the module's only public outputs besides the Guild's posted prices and orders, Crown Law and
caravans in the world. The promise is no direct state API, not immunity to inference (12 §7).

### 4.9 History

Durable history is promoted daily or weekly by thresholds (`HistorySignificance`: participants, duration, value, firsts
and records, political consequence): reigns, sieges, Territory Wars, fortress holdings, clan wars, mechanically observable
betrayals (leaving an alliance mid-war; breaking a public PACT contract), major hunts, economic eras (a situation `ACTIVE`
seven days or more), long dominance, laws, caravan disasters. `publishable` marks what Chronicle may publish (chronicle.md
§2.2, under the Intel Service's field rules and tactical delay); Aden Remembers publishes no history itself.

### 4.10 Freeze and disable

- **Kill switch** per kind: its values neutral in the next snapshot.
- **Freeze** (`lc_module_state` `WORLD`): on a verifier violation (§4.11) or a projector stop (fact-journal.md §4.3);
  every value neutral, open situations `CLOSED` (`FROZEN`), relocation off, Guild trading and dispatch paused, caravans
  parked (§3.4), laws suspended (§3.3); no Asset operation on its custodies is attempted; until an admin unfreezes with a
  reason.
- **Disable** (`Enabled = false`), while the module runs: first every caravan is recalled (ordinary operations), then
  everything a freeze stops is stopped, with reason `DISABLED`. The world is then retail: drops land where retail puts
  them, each limited product restocks retail from its next restock, no Guild trade, no caravan, no levy, a charter factor
  of 1, no endowment, no wardens, no rumor lines (12 §10 `AR-DISABLE`).

### 4.11 Production checks

A verifier runs at each daily step: every applied value within bounds and rate; no relocation of a protected item (each
`World.Relocated` fact's items against `lc_resource_protected`); **per kill**, every `World.Relocated` fact matches one
committed `RELOCATED_DROP` operation under its kill key with the same items and counts, and no such operation lacks its
fact; every pool and warehouse equals `lc_guild_stock` **at one boundary**: the projection as of its checkpoint fact *F*
against the custody's postings whose operations' facts are at or below *F* (the Asset Service writes an operation's facts
in its commit, so the two agree exactly at *F*); the no-loop rule over prices and open orders; every rumor's params pass
the field rules; no recycle above the previous day's recorded sink share. A violation is an alarm and a freeze. **Honest
limit**: the `ar_yield` sums are aggregates; they can show a day's totals agree, not that each kill's retail delivery was
exact. Per-kill exactness of the split (relocated or retail, never both, never neither) is proven by AT-AR-01 over the
hook, not observed in production.

### 4.12 Determinism

- **Inputs**: durable facts in log order, and telemetry windows bound to the log: the telemetry writer writes a
  `Journal.WindowWritten` fact after a window's rows commit (an amendment, §11), and a step at fact *F* reads exactly the
  windows whose `WindowWritten` precedes *F*. Warehouses are read from `lc_guild_stock` (built from facts), never from a
  live custody. Randomness is keyed by fact ids (04 §7).
- **Outputs compared live and on replay**: `lc_region_state`, `lc_situation`, `lc_situation_transition`, `lc_influence`,
  `lc_world_pk`, `lc_guild_stock`, `lc_actuator_target` and the effect intents. The applied history (`lc_actuator`) and the
  world projections come from effects, which replay does not run (fact-journal.md §4.4).
- Each window is read once, at its step (fact-journal.md §4.5); the daily values behind every 28-day median and 7-day
  mean are kept in derived state (`lc_region_state` history), carried by the projector's snapshots, so no step reads an
  old window again. Buckets are still kept `TelemetryRetentionDays` (≥ 35) for `lc why`.

## 5. Facts

Emitted, durable: `World.SituationChanged`, `World.ActuatorDecided` (kind, region, target, value), `World.LawEnacted`,
`World.LawEnded`, `World.InfluenceSpent`, `World.GuildTraded` (town, item, qty, price, side), `World.OrderPosted`,
`World.OrderSettled`, `World.Relocated` (kill key, destination, items), `World.Recycled` (pool, amount, day),
`World.CaravanScheduled`, `World.CaravanDeparted`, `World.CaravanNode` (caravan, node), `World.CaravanArrived`,
`World.CaravanRaided`, `World.CaravanRecalled`, `World.HistoryPromoted`, `World.Frozen`. Captured at the seams of §4.1:
`World.CastleOwnerChanged`, `World.FortOwnerChanged`, `World.SiegeStarted`, `World.SiegeEnded`,
`World.TerritoryWarStarted`, `World.TerritoryWarEnded`, `World.WardCaptured`, `World.SevenSignsPeriod`,
`World.TaxChanged`, `World.HellboundChanged`, `World.SeedChanged`, `World.ManorSettled`, `World.ClanWarChanged`,
`World.InnocentKilled`, `World.ClanPresence`. Consumed: §4.1.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| AR-INV01 | every family item a retail roll made exists, dropped or relocated; no probability changed | the routing patch; the verifier; AT-AR-01 |
| AR-INV02 | every applied value within its bounds and rate; protected items never relocated | checks on `lc_actuator`; family triggers; the verifier; AT-AR-02 |
| AR-INV03 | nothing outside §1's list is changed | the closed actuator set; actor-identity.md's static scan; AT-AR-03 |
| AR-INV04 | the Guild creates nothing: goods from retail rolls or players, Adena from players or a recorded sink share | Asset operations only; the economy monitor; AT-AR-05 |
| AR-INV05 | live and replay give the same decisions | §4.12; AT-AR-06 |
| AR-INV06 | no interface returns a state value; rumors and rows carry only rule-allowed fields | the interface list; the verifier; AT-AR-08 |
| AR-INV07 | laws within funding, caps, reigns and the Ledger's limits | keys, triggers, charge-time checks; AT-AR-07 |
| AR-INV08 | disabled, the world is retail (limited stock from each product's next restock) | AT-AR-09 |
| AR-INV09 | a caravan's manifest has exactly one terminal disposition | the terminal key; AT-AR-04 |

## 7. Failure model

| Fault | End state |
|---|---|
| projector stopped at a gap | frozen (§4.10) until acknowledged |
| an exception in a hot-path seam | caught, counted; the retail outcome unchanged |
| a relocation operation fails | the items go to retail |
| crash between an effect's intent and its application | applied once after restart (fact-journal.md §4.4) |
| crash between a levy's charge and the treasury credit | the levy is lost (a sink), never minted |
| restart with caravans en route | re-spawned at `last_node`, walked from it; recalled past the timeout |
| freeze with caravans out | parked where they are, no manifest moved; resumed at the unfreeze |
| JVM death between a kill and its relocation commit | the selected items are lost with the kill (the Loot Charter's limit) |
| Amnesty asked while the projector lags or a PK gap overlaps | "no amnesty"; the Writ opens |
| arrival, raid and timeout at once | one terminal transaction commits |
| Asset Service frozen | Guild trading, dispatch, relocation and law funding refused (items to retail); values kept |
| a pool exhausted | purchases and orders refused; no dispatch without escort funding |
| tax or seal change over the levy cap | no levy charged from the next teleport |
| a verifier violation | frozen |

## 8. Configuration

`config/Custom/LivingChronicle/AdenRemembers.ini`, the `data/lc/*.csv` tables named above. Every change is a `config`
fact (fact-journal.md §4.1).

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `Enabled` | bool | true | false / true / true / true / true | AT-AR-09 |
| `BucketMinutes`, `TelemetryRetentionDays` | 1–15, 35–400 | 5, 60 | as default everywhere | AT-AR-06 |
| family `max_relocate_permille`, `max_rate_permille_day`, `recovery_days` | 0–100, 1–50, 1–60 | 50, 10, 14 | as default everywhere (Enabled false in truth) | AT-AR-01, AT-AR-02 |
| stock delay bounds | 700–1000, 1000–1500 | 700, 1500 | as default everywhere | AT-AR-02 |
| `GuildBuyMinPermille`, `GuildBuyMaxPermille` (of buy-back), `GuildSellMinPermille`, `GuildSellMaxPermille` (of reference), `GuildPriceRatePermilleDay` | 1000–1500, 1000–2000, 500–1000, 500–1500, 1–100 | 1000, 1400, 800, 1200, 30 | as default everywhere | AT-AR-05 |
| `RecyclePermille` | 0–250 | 100 | as default everywhere | AT-AR-05 |
| `CaravansPerTownDay`, `ManifestMaxValueAdena`, `RaidSharePermille`, `CaravanTimeoutFactor`, `CaravanNoticeMinutes` | 0–12, 0–1e9, 0–500, 2–5, 30–1440 | 2, 20000000, 300, 3, 120 | as default everywhere | AT-AR-04 |
| `MaxLawsPerCastle`, `CharterFactorPermille`, `ProtectionBonusPermille`, `LevyPermille`, `WardenWageAdena`, `HoldingUpkeepPermille`, `FortWeightPermille`, `AmnestyInfluenceCost` | 0–6, 500–1000, 0–1000, 0–500, 0–100000, 0–1000, 0–1000, 0–1e6 | 3, 800, 250, 100, 2000, 250, 250, 1000 | as default everywhere | AT-AR-07 |
| `RumorDelayMinutes`, `RumorLifeHours`, `HistorySignificance` | 60–2880, 1–168, 1–1000 | 360, 48, 500 | as default everywhere | AT-AR-08 |
| `RelocationOpsPerSecond`, `MinDemandUnits`, `AmnestyBarrierMillis` | 1–1000, 1–10000, 100–60000 | 50, 20, 5000 | as default everywhere | AT-AR-01, AT-AR-10, AT-AR-07 |
| `EscortSampleSeconds`, `EscortRadius`, `EscortMinSharePermille` | 10–120, 300–5000, 0–1000 | 30, 1500, 300 | as default everywhere | AT-AR-04 |
| `PresenceSampleMinutes` | 5–60 | 15 | as default everywhere | AT-AR-06 |

## 9. Interface

Guild Master (each Guild town): sell, buy, orders, caravan schedule, escort sign-up. Chamberlain: Crown Law (enact,
repeal, list). Rumor NPCs. Codex: the Laws tab. Admin: `lc why actuator <kind> <region> <target>` (the value, its rows,
the situations and facts behind it), `lc why situation <id>`, `lc world switch <kind> on|off`, `lc world unfreeze <reason>`.
Nothing else shows any state.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| AR-R01 | Relocation MUST change only the destination of retail-rolled, unprotected family items, never any probability, and every rolled item MUST exist afterwards. | AT-AR-01 |
| AR-R02 | Every actuator value MUST stay within its bounds, move at most at its rate, recover when unasked, and go neutral at once on a kill switch, freeze or disable. | AT-AR-02 |
| AR-R03 | Aden Remembers MUST NOT change anything outside its actuator set (§1). | AT-AR-03 |
| AR-R04 | A caravan's manifest MUST have exactly one terminal disposition (arrival, raid share plus road loss, or recall), a raid share dropped at most once, after a restart resumed from its last node. | AT-AR-04 |
| AR-R05 | The Guild MUST create nothing; its pools MUST never be negative, its recycle MUST never exceed the recorded sink share, and no price or order MUST allow a profitable loop. | AT-AR-05 |
| AR-R06 | Decisions MUST be identical live and on replay over the inputs of §4.12. | AT-AR-06 |
| AR-R07 | Every Crown Law MUST respect its funding, the levy cap at each charge, its reign, the Ledger's limits, Amnesty's predicate and spend, proclamation and history. | AT-AR-07 |
| AR-R08 | No interface MUST return a state value; rumors and publication rows MUST carry only rule-allowed fields after their delay. | AT-AR-08 |
| AR-R09 | Disabled, Aden Remembers MUST leave retail behaviour, each limited product from its next restock. | AT-AR-09 |
| AR-R10 | Situations MUST follow their machine with the parameters of §4.4, and each MUST produce its declared asks. | AT-AR-10 |
| AR-R11 | Every applied value MUST be explainable by `lc why` from its rows and facts. | AT-AR-11 |
| AR-R12 | A verifier MUST run the checks of §4.11 daily and freeze on a violation. | AT-AR-12 |
| AR-R13 | Hot-path seams MUST stay within 2 µs p99 and contain their own exceptions. | AT-AR-13 |

| Test | Kind | What it does |
|---|---|---|
| AT-AR-00 | integration | the migration applies; triggers and keys refuse: a transition row after the first with no predecessor state; a protected item in a family (insert or update, both tables); an actuator row outside its bounds or its kind's ceiling, changed or deleted; a second open situation of a kind and region; a situation inserted other than `EMERGING`; a state change without its newest transition row; a transition outside the machine; `ACTIVE → CLOSED` without `FROZEN`/`DISABLED`; a second enacted law of a kind per castle; a second Amnesty in a reign; an ended law changed or deleted; a band whose ceiling is not below its floor; a buy or order price above its band's ceiling, a sell price below its floor; a band narrowed past an existing price or open order, deleted, or deleted-and-recreated narrower; a price written against a band another transaction changed after the writer's snapshot (the locking read sees the change); a caravan transition outside its machine or a node going back; a rumor beyond its life |
| AT-AR-01 | integration, sim | `AR-OVERHARVEST`: 10⁶ kills of a drop list with groups, protected items, Adena and champions, with relocation at its ceiling and off: every item's frequency (relocated counted as dropped) equal between the two within a chi-square test at p ≥ 0.001; per kill, rolled family items = dropped + relocated exactly, and none both; an aborted operation returns the items to retail; an uncertain commit resolved either way leaves each item in exactly one place; past `RelocationOpsPerSecond` selections are skipped to retail; the overharvest situation opens at the declared threshold and windows and recovers after pressure stops |
| AT-AR-02 | unit, integration | property test over random situations: every value within bounds and rate, recovery to neutral, kill switch and freeze neutral at once |
| AT-AR-03 | unit | actor-identity.md's static scan passes over Aden Remembers; no code writes rates, skills, stats, spawns, respawn times, prices or Hellbound or seed state; the actuator set is exactly §4.3's |
| AT-AR-04 | integration, restart, fault | `AR-CARAVAN-*`: arrival, raid (the share dropped once, after commit, under a crash at each step), recall on timeout and disable (also from `SCHEDULED`); a freeze parks caravans with no Asset operation and the unfreeze resumes them with the parked time excluded from the timeout; arrival, raid and timeout raced: one disposition; a restart en route resumes at `last_node`; escorts paid in proportion to sampled presence above the minimum share, the rest refunded |
| AT-AR-05 | integration, sim | the economy monitor over a week of Guild trading: no source except `RELOCATED_DROP` equal to rolled items and `SINK_RECYCLE` ≤ the recorded share; pools equal recycles plus receipts minus payments; refused trades when a pool is short; no profitable sequence through any towns and orders |
| AT-AR-06 | integration | a replay from a snapshot reproduces §4.12's outputs exactly, with telemetry windows written late and effects dispatched slowly in the live run |
| AT-AR-07 | integration | `AR-LAW-*`: each law enacted, funded, proclaimed, listed, recorded; repealed and expired on an owner change with the remainder returned; a teleport charged without levy when tax + levy exceeds the cap; negative tax clamped; an endowment refused for a non-cursed Writ; Amnesty refuses a Writ for a mark with no PK since, including a mark whose PK occurred before the proclamation but whose fact was delivered after it (a larger fact id), allows one after a new PK even when the PK's fact is still in the writer's ring at `open` (the barrier waits for it), allows one when a PK gap overlaps the span or the barrier times out, spends influence once, refused a second time in the reign; upkeep by alliance holding weight |
| AT-AR-08 | integration, sim | `AR-PERCEPTION`: observer citizens with client knowledge only detect each actuator within its window (relocation 48 h, stock 72 h, Guild prices at once, caravans at departure, laws at proclamation); every Codex, website and NPC response scanned: no state value; rumors carry only allowed fields and start after their delay |
| AT-AR-09 | integration | `AR-DISABLE`: disabled mid-situation with laws enacted and caravans scheduled and en route: every caravan recalled; drops, teleport fees, Notarius fees and NPC pages equal a server without Aden Remembers; each limited product's restock retail from its next one, a pending scaled one kept |
| AT-AR-10 | sim | each of the eight kinds, driven by synthetic inputs: opens at its threshold after its confirmation steps, makes its asks, recedes and closes after its release steps; `AR-CATCHIN`: a mature world with new characters gets orders for catch-in items within 2 days of a shortage; `AR-TRADESHIFT`: a tax raise moves trade to neighbours within 3 days |
| AT-AR-11 | integration | for random applied rows, `lc why` returns the situations, facts and windows behind them, and recomputing from those gives the value |
| AT-AR-12 | integration | a planted out-of-bounds row, a relocated protected item, a `World.Relocated` fact without its operation, a custody mismatch at the boundary, a looping price, an over-share recycle and a rumor with a forbidden field: alarm and freeze; a trade committed but not yet projected raises no alarm |
| AT-AR-13 | sim, fault | the 800-citizen benchmark: the relocation lookup and selection and each telemetry seam ≤ 2 µs p99; the relocation's Asset operation, run only for kills with a selected item and rate-capped, is measured against the Asset Service's commit budget (ASSET-R21), not the 2 µs one; an exception injected in each seam leaves the retail outcome |

## 11. Upstream constraints

1. **No events** for drops, NPC buy and sell, store trade, crafting, teleports, Territory War, Seven Signs, Hellbound,
   seeds or Manor, and no complete owner-change feed: only one owner event, `ON_CASTLE_SIEGE_OWNER_CHANGE`, dispatched asynchronously at a castle siege's mid-victory (`mechanics/events/EventType.java:128`, `mechanics/siege/Siege.java:469`); and `ON_CASTLE_SIEGE_FINISH` (`Siege.java:339`), also asynchronous, whose payload is the live siege and castle, mutable state rather than a frozen ownership outcome; nothing for fortresses, clan halls, admin changes or a castle lost with its clan, so no durable ownership feed (§4.1). → telemetry and fact seams.
2. **Group drops switch to custom-rates mode when the rate is not 1** (`entity/actor/templates/NpcTemplate.java:815-822`),
   and later items' odds depend on earlier thresholds. → no probability changes; relocation after the roll.
3. **No runtime spawn-group or route API; routes restart from node 0** (`SpawnGroup.java:224`; `WalkingManager.java:73`,
   `:212`, `:279`; `WalkInfo.java:74`). → no spawn actuator; datapack routes and a cursor and arrival seam.
4. **No product price setter; buy-back hard-coded** (`Product.java:38`; `RequestSellItem.java:165`, `:183`). → the Guild's
   own prices.
5. **Limited merchant stock is not materials** (`dist/game/data/buylists/*.xml`). → the Guild's warehouses of real goods.
6. **Teleport fees are a pure sink, without castle tax** (`TeleportHolder.java:221`). → the recycle and the levy.
7. **Negative tax accepted** (`CastleChamberlain.java:827-847`, `Castle.java:605-621`). → clamped (§4.5).
8. **One castle per clan** (`Siege.java:1164`, `:1386`). → holdings by alliance.
9. **Killing NPCs gives no karma.** → raids are history and pressure, not Writs.
10. **Container-wide first-talk listeners skip default windows** (`NpcClick.java:121-128`). → the per-template chat seam.

**Amendments to reviewed specs**, accepted in this spec's review and applied in fact-journal.md revision 9,
asset-service.md revision 10, pact.md revision 6 and black-ledger.md revision 7 (each under its own review):
- fact-journal.md §2.6: a telemetry family declares its region dimension (atom or subregion) and families may be
  registered in a module's id range; §3.4: the writer writes `Journal.WindowWritten` after a window's rows commit.
- asset-service.md: the `TOWN_SUPPLY` source and sink withdrawn (caravans move custody to custody); the `exchange`
  operation; the `RELOCATED_DROP` and `SINK_RECYCLE` sources and the `CROWN_UPKEEP` sink, with their sites.
- pact.md: the `MERCHANT_GUILD` issuer with a custody as receiver and refund on expiry; Caravan Escort escrowed from a
  Guild pool and refunded on raid or recall; the Market Charter as a rebate after signing (§4.5).
- black-ledger.md §4.3: the Amnesty predicate at `open`, comparing PK occurrence time with the proclamation's, with its
  frontier barrier and its fail-closed "no amnesty".
- asset-service.md §4.2 rule 7 is kept as it is: a freeze parks caravans and moves no custody (§3.4).
- actor-identity.md is unchanged: Aden Remembers calls nothing on its list, and relocation runs at the loot routing patch
  after the retail calculation, as the Loot Charter does.

## 12. Review record

- Revision 7 (amendment, under review): §11.1 said upstream has no owner-change event; it has one,
  `ON_CASTLE_SIEGE_OWNER_CHANGE`, dispatched only at a castle siege's mid-victory (Siege.java:469), so the claim now names
  it and the gaps the capture seams fill (Chronicle review round 5, finding 36). No rule changed. Also: the store atoms'
  actor class is the seller's (Citizens review round 1 (citizens.md revision 2)). Signed off with Chronicle round 6,
  after naming `ON_CASTLE_SIEGE_FINISH` and its limit.
- After the sign-off, the amendment reviews of the dependent specs changed four details here (the fourth: escorts are
  "registered", not "signed", matching PACT's participants), each to match what those
  specs now say: Guild Orders are PACT Purchase Orders of an institutional buyer; each telemetry window is read once
  and the baselines' daily values kept in derived state; the Market Charter is a rebate after signing.
- Round 5 (Codex), **SIGN-OFF WITH FIXES**: Amnesty compared fact ids, which are delivery order; revision 6 compares
  the PK's occurrence time with the proclamation's, keeping the frontier barrier, with the reordered-delivery case in
  AT-AR-07. Remaining are named implementation obligations (the seams, the relocation handoff, recycling, the band
  triggers, caravan arbitration, the cursor seam) and stated limits. §11's amendments to fact-journal.md, asset-service.md,
  pact.md and black-ledger.md were judged sufficient and consistent; they are applied in those specs' next revisions,
  each to be reviewed there.
- Round 4 (Codex), NO SIGN-OFF: a band could be deleted and recreated narrower, past existing rows; the relocation
  operation's budget named one PACT does not define. Revision 5: bands are never deleted, prices and orders reference
  their band by foreign key, a band's insert is checked like a change; the budget is ASSET-R21's.
- Round 3 (Codex), NO SIGN-OFF: open were an isolation guard that reads the session default, not the active
  transaction's level; formula operands the declared inputs could not supply (store units per family, clan presence);
  craft consumption counted only on success; a latency oracle describing a removed queue. Revision 4: a per-item band
  (buy ceiling below sell floor) that each price and order checks with a locking read of another table, so no row is ever
  compared with its own table and isolation no longer matters; `ar_store_units_<family>`, a daily durable clan-presence
  fact and escorts' clan at joining; consumption at material destruction, before the roll, and contractual crafts'
  inputs; the 2 µs budget limited to selection.
- Round 2 (Codex), NO SIGN-OFF: twelve of twenty fixed; open were validation reads under an old snapshot, Amnesty
  deciding on a PK fact not yet projected, relocation's handoff (queued items, uncertain commits, the crash limit),
  arithmetic for supply, cold starts, influence, significance and escort participation, a NULL path through the
  transition trigger, a verifier claiming per-kill evidence from aggregates and comparing custody without a common
  boundary, the disable promise against kept restock deadlines, and a freeze-time recall the Asset Service's freeze rule
  forbids. Revision 3: locking validation reads; Amnesty's frontier barrier, failing closed; relocation synchronous on the
  dropping thread like the Loot Charter, uncertain commits resolved there, the crash limit adopted, a rate cap; the
  formulas, cold start and escort sampling in §4.2 and §4.6; the trigger fails on NULL; per-kill fact-to-operation checks
  and a checkpoint boundary, with the aggregate limit stated; disable scoped to the next restock; freeze parks caravans.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 20 findings: a group-drop factor that shifts later items' odds;
  clipping at high final chances; a budget over historical weights that conserves no current supply; budget and rate
  limits that can be infeasible together; a spawn tempo that raises a family's supply; telemetry with no subregion or
  resource-family dimension; no binding of telemetry windows to the log and actuator rows that replay cannot write; the
  reviewed ban on calls into drop calculation left standing; a synthetic-exclusion rule that cannot be evaluated (and,
  found while answering, contradicts Genesis, 10 §8, and actor-identity.md §3); orders outside the no-loop rule;
  unserialised price triggers; no atomic caravan or order settlement; charter subsidy and fee diversion contradicting each
  other; Amnesty without a durable PK record or influence ledger; a disable that leaves laws and schedules acting; levy
  suspension outside any machine; a holdings multiplier that retail's one-castle rule makes inert; a walking API that
  cannot resume; migration gaps; tests without oracles. Revision 2: probabilities untouched, relocation after the retail
  roll at the loot routing patch (exact conservation, no budget); no spawn actuator; independent values; telemetry
  amendments with a window fact and declared region dimensions; decisions vs applied history; every actor alike; orders
  under the no-loop rule and a locked, single-writer price path; terminal keys and Asset `exchange`; Notarius fees not
  diverted, a recycled share of teleport fees instead; a durable PK record, influence spends and one Amnesty per reign;
  suspension at every use and a complete disable; the levy checked at each charge; holdings by alliance weight; a cursor
  and arrival seam; triggers for every machine; parameters, windows and statistical oracles in the spec.
