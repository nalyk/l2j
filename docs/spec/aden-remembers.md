# SPEC-AR — Aden Remembers

- Status: draft (revision 1)
- Blueprint: 12 (Aden Remembers); 01 §2.5 (rumors are content); 02 §4, §6 (budgets: hot-path seams ≤ 2 µs p99, never per
  tick); 04 §1 (Fact Journal, telemetry), §3 (Asset Service), §6 (`lc why`), §7 (keyed randomness), §8 (degradation); 10 §8
  (newcomer catch-in); 13 §4.8 (Signal demand); 15 (PACT contracts as the channel for orders and escorts); 16 (the Black
  Ledger's limits on external money); 17 (Chronicle publishes history); 90 §2 (the Director rows of the traceability).
- Depends on: fact-journal.md (facts, telemetry, the projector runtime, effects), asset-service.md (custody, operations,
  the economy monitor), pact.md (guild orders and escorts), black-ledger.md (Bounty Endowments, Amnesty), intel-service.md
  (publication registry, field rules), codex-ui.md (the Laws tab, NPC pages), actor-identity.md (principals for distinct
  counts), aden-signal.md (§4.8's daily demand cells).
- DDL: `sql/migrations/V015__lc_world.sql` (after Wayfarer's V014).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Aden Remembers turns what players actually do into bounded, explainable, persistent consequences in the world: regional
state, **situations**, a small set of **actuators**, **Crown Law**, the **Merchant Guild** and its **caravans**,
**influence**, and the **historical graph**. It interprets play; it generates no quests and no banners.

It owns: the region model (§2.1); the regional state reducers (§4.2); situations (§3.1, §4.4); the actuators and their
bounds (§4.3); Crown Law (§4.5); the Merchant Guild's warehouses, orders and caravans (§4.6); influence and the
complexity cost of holdings (§4.7); world rumors and criers (§4.8); history promotion (§4.9).

It does not own, and never changes (12 §4): combat, experience, drop **rates** as configured (`Rates.ini`), enchant and
craft chances, boss and raid stats, spawns and locations, Seven Signs, Hellbound level and trust, seed states, sieges,
anything a player earned in a native system, or retail NPC prices (no `Product` price changes, `mechanics/buylist/
Product.java:38`). It does not own contracts (PACT, the Ledger), holdings (the Asset Service), publication (Chronicle and
the Intel Service's registry), or anyone's identity.

**Nothing is created** (16 §7): every item the Guild sells, moves or drops was sold to it by a player; every Adena it pays
was diverted from a retail sink or received from a player (§4.6). **Nothing is hidden behind an API** (12 §7): no player-
or website-facing interface returns a state value; the world shows its state only through the channels of §4.3.

## 2. Data

### 2.1 Regions

```text
lc_region(region_id SMALLINT PK, kind {TERRITORY|SUBREGION}, name_key, castle_id NULL, parent_id NULL)
lc_region_atom(atom SMALLINT PK, territory_id)                         -- atom = the journal's region index
lc_subregion_spawn(spawn_file VARCHAR(128) PK, subregion_id)
```

- **Atoms** are the journal's dense map-region indices (fact-journal.md §2.3); every fact and telemetry cell already
  carries one. Each atom belongs to exactly one **territory**: the castle named by its map region's `castle` attribute
  (`data/xml/MapRegionData.java:412`, `getAreaCastle`; `dist/game/data/mapregion/*.xml`), giving the nine H5 territories,
  plus **Hellbound** and **Gracia** for the atoms of those map regions, which name no castle. The table is generated at
  startup and written as a `config` fact, like the journal's own table.
- **Subregions** are the strategic hunting grounds (Sea of Spores, Cruma Tower, Dragon Valley, Forge of the Gods, Den of
  Evil, Primeval Isle, …): sets of **spawn files**. The datapack already organises spawns by hunting ground
  (`dist/game/data/spawns/Giran/BrekasStronghold.xml`), and a spawned monster's file is
  `SpawnData.getSpawnFile(npc.getSpawn().getNpcSpawnTemplateId())` (`data/xml/SpawnData.java:1031`). At startup each spawn
  template id is resolved once to its subregion (or none), into an `int[]`; no hot path does a zone or file lookup
  (`ZoneManager.getSpawnTerritories` is a linear scan, `managers/ZoneManager.java:758`). A monster outside every
  subregion counts in its atom's territory.
- `lc_subregion_spawn` is data (`data/lc/subregions.csv`, validated at startup: every file exists, no file in two
  subregions, each subregion inside one territory).

### 2.2 State, families, actuators

```text
lc_region_state(region_id, state_key, value INT, window_end TIMESTAMP(3), reducer_ver, PRIMARY KEY(region_id, state_key))
lc_resource_family(family_id PK, name_key, catch_in BOOLEAN, max_variation_permille, max_rate_permille_day, recovery_days)
lc_resource_item(item_id PK, family_id)
lc_resource_protected(item_id PK, reason)
lc_actuator(application_id BINARY(16) PK, kind, region_id, target INT, value_permille INT, min_permille, max_permille,
            reason_code, situation_id NULL, fact_id BIGINT, applied_at, valid_until NULL)
lc_actuator_switch(kind PK, enabled, changed_by, changed_at)
```

- `lc_region_state` is derived (the projector's), rebuildable by replay (fact-journal.md §4.5). Keys are fixed (§4.2) and
  values are integers with a stated scale; no other module reads it.
- A **resource family** groups items of one kind of supply (coal and charcoal; animal skins; iron ore; stems; threads;
  low-grade crystals; …, `data/lc/resource_families.csv`). An item belongs to at most one family. **Protected** items never
  belong to one and are refused by a trigger: Adena, seal stones, herbs, quest items, event items, every item any raid or
  grand boss drops, and every item whose base chance at any monster is ≥ `100 / (1 + max_variation)` (§4.3.1).
- `lc_actuator` is the append-only history of every actuator value; the value in force for (kind, region, target) is the
  newest row. Its application id is the journal effect's (fact-journal.md §4.4), so an actuator answers "already applied"
  from this table. A row out of its own bounds is refused by a check.

### 2.3 Situations

```text
lc_situation(situation_id PK, kind, region_id, state {EMERGING|ACTIVE|RECEDING|CLOSED}, opened_fact_id, opened_at,
             cause JSON, params JSON, next_eval_at, closed_at NULL)
lc_situation_transition(situation_id, seq, from_state, to_state, at, reason_code, input_fact_from, input_fact_to)
```

One open situation per (kind, region) (a unique key on the open ones). Transitions are append-only.

### 2.4 Crown Law

```text
lc_law(law_id PK, castle_id, kind {BOUNTY_ENDOWMENT|MARKET_CHARTER|CARAVAN_PROTECTION|AMNESTY|GATEKEEPER_LEVY|OPEN_ROADS},
       state {ENACTED|REPEALED|EXPIRED|EXHAUSTED}, reign_key VARCHAR(48), enacted_by INT, enacted_at, proclaimed_at,
       params JSON, custody_id NULL, ended_at NULL, end_reason NULL)
```

- `reign_key` identifies a reign: the castle, its owning clan and the fact that began the ownership (§4.1). A law belongs
  to the reign that enacted it and ends with it.
- One enacted law per (castle, kind) (a key), so at most six per castle; `MaxLawsPerCastle` may allow fewer, checked at
  `enact`.
- Funded laws hold their money in a custody (`CROWN`, law id; asset-service.md §2.2), committed from the treasury
  (`commit`, asset-service.md §4.3) and returned at the end (`return`).

### 2.5 The Merchant Guild and caravans

```text
lc_guild_town(town_id PK, territory_id, warehouse_custody_id, pool_custody_id)
lc_guild_price(town_id, item_id, buy_price BIGINT, sell_price BIGINT, application_id, PRIMARY KEY(town_id, item_id))
lc_guild_order(order_id PK, town_id, item_id, qty, unit_price, contract_id, situation_id NULL, state {POSTED|FILLED|EXPIRED})
lc_caravan_route(route_name VARCHAR(64) PK, origin_town, dest_town, nodes INT, scheduled_minutes INT, fort_ids JSON)
lc_caravan(caravan_id PK, route_name, manifest_custody_id, state {SCHEDULED|EN_ROUTE|ARRIVED|RAIDED|RECALLED|SETTLED},
           depart_at, last_node INT, arrived_at NULL, outcome JSON NULL, escort_contract_id NULL)
lc_caravan_escort(caravan_id, char_id, contract_id, joined_at, presence_seconds)
```

Each town of the Guild (one per territory, its main town) has a **warehouse** custody holding real goods and a **pool**
custody holding Adena (asset-service.md custody kind `POOL`). A trigger keeps every town's buy price for an item below
every town's sell price for it (§4.6, no arbitrage loop drains a pool).

### 2.6 Influence, rumors, history

```text
lc_influence(territory_id, clan_id, value INT, window_end, PRIMARY KEY(territory_id, clan_id))      -- derived
lc_world_rumor(rumor_id PK, territory_id, kind, template_key, params JSON, from_at, until_at, situation_id NULL)
lc_history_event(event_id PK, kind, title_key, started_at, ended_at NULL, region_id NULL, significance INT, facts JSON,
                 publishable BOOLEAN)
lc_history_participant(event_id, char_id NULL, clan_id NULL, role, origin {HUMAN|SYNTHETIC})
lc_historical_record(record_kind PK, holder_kind, holder_ref, value BIGINT, set_at, event_id)
```

`lc_world_rumor.params` holds only values allowed by the rumor field rules (§4.8), checked by the writer and by the
verifier. History rows are durable; participants keep origin for history's humans-only filter (actor-identity.md §3).

## 3. State machines

### 3.1 Situation

| From | Event | To | Effects |
|---|---|---|---|
| — | its kind's cause predicate holds over its window (§4.4) | `EMERGING` | evidence channels that need no actuator (rumors after their delay) |
| `EMERGING` | the predicate holds for its confirmation windows | `ACTIVE` | its actuator targets (§4.4), each applied at its rate |
| `EMERGING` | the predicate fails | `CLOSED` | nothing ever applied |
| `ACTIVE` | the predicate fails for its release windows | `RECEDING` | targets back toward neutral at the recovery rate |
| `RECEDING` | the predicate holds again | `ACTIVE` | as above |
| `RECEDING` | every actuator it drove is neutral | `CLOSED` | history promotion considered (§4.9) |
| any open | the module is frozen or disabled (§4.10) | `CLOSED` | every actuator neutral at once |

Each transition is one row of `lc_situation_transition` with the facts it read (an id range), and the reason code.

### 3.2 Actuator value

A value moves from its current value toward its **target** (the sum of the open situations' asks for it, clamped to its
bounds and, for budgeted kinds, projected onto the budget, §4.3) by at most its `max_rate` per day, evaluated at each
cadence step that owns it (§4.1). Neutral is 1000 permille. A value with no open situation asking for it recovers toward
neutral over its `recovery_days`. A kill switch (`lc_actuator_switch`) or a freeze sets it to neutral immediately: going
back to retail is never rate-limited.

### 3.3 Law

| From | Event | To | Effects |
|---|---|---|---|
| — | `enact` at the Chamberlain (§4.5) | `ENACTED` | treasury commit (funded kinds); proclamation (crier, Codex Laws tab, history) |
| `ENACTED` | `repeal` by the lord | `REPEALED` | custody remainder returned to the treasury; proclamation |
| `ENACTED` | the reign ends (§4.1) | `EXPIRED` | as repeal, to the treasury of the castle, whoever owns it now |
| `ENACTED` | a funded law's custody falls below its minimum payout | `EXHAUSTED` | remainder returned; proclamation |

### 3.4 Caravan

| From | Event | To | Effects |
|---|---|---|---|
| — | dispatch (§4.6) | `SCHEDULED` | manifest custody filled from the origin warehouse (Asset transfer); schedule posted on the origin notice board; escort contract issued |
| `SCHEDULED` | `depart_at` | `EN_ROUTE` | the caravan spawned at the origin node and started on its route |
| `EN_ROUTE` | the route's last node reached | `ARRIVED` | manifest → destination warehouse (Asset transfer); escort settled by PACT |
| `EN_ROUTE` | the Caravan Master killed | `RAIDED` | `raid` (asset-service.md §4.3): share dropped once, after commit; the rest lost to the road |
| `EN_ROUTE` | not arrived by `depart_at` + `CaravanTimeoutFactor` × scheduled time, or the module disabled | `RECALLED` | manifest → origin warehouse (Asset transfer); caravan despawned |
| `ARRIVED`, `RAIDED`, `RECALLED` | escort and history settled | `SETTLED` | — |

The caravan in the world is a projection of its row (fact-journal.md §4.4): after a restart an `EN_ROUTE` caravan is
re-spawned at `last_node` and resumes its route.

## 4. Operations

### 4.1 Inputs and cadence

Aden Remembers is one projector (fact-journal.md §4) over telemetry windows and durable facts, deterministic: the same
log, reducer version and configuration give the same state, situations and actuator values; randomness (caravan
departure jitter, rumor choice) comes from keyed streams seeded by the fact id (04 §7).

**Telemetry families** it reads (fact-journal.md §2.6; each a counter per (window, atom, family, actor class), with a
distinct-principal sketch): `kills` (monster template family, at `ON_CREATURE_DEATH` on `Containers.Monsters()`,
synchronous, `entity/actor/Creature.java:2626-2628`), `yield` and `spoil` (per resource family, at the drop seams of
§4.3.1, counting items dropped and the expected count at neutral), `npc_buy` and `npc_sell` (`network/clientpackets/
RequestBuyItem.java:243`, `RequestSellItem.java:203`), `store_trade` (`network/holders/TradeList.java:637`, `:859`),
`craft` (`managers/RecipeManager.java:686` in `RecipeItemMaker.rewardPlayer`), `teleport` (`entity/teleporter/
TeleportHolder.java:221`), `pvp` (`entity/actor/Player.java:5609`, `onKillUpdatePvPKarma`), `deaths`, `new_chars`
(`ON_PLAYER_CREATE`, synchronous, `network/clientpackets/CharacterCreate.java:327-329`), and a `level` snapshot of online
characters per atom each bucket. None of these points dispatches a usable event except the two noted, so each is a
telemetry seam (a core patch, `CORE_PATCHES.md`), copying primitives into the journal's preallocated window (≤ 2 µs).

**Durable facts** it reads, each produced at a synchronous seam at the authoritative point, because Mobius dispatches
nothing there or only asynchronously: castle owner set and removed (`mechanics/siege/Castle.java:506`, `:571`; the only
event, `ON_CASTLE_SIEGE_OWNER_CHANGE`, fires in one branch of `midVictory`, `Siege.java:469-471`), fortress owner set and
removed (`mechanics/siege/Fort.java:380`, `:467`), siege start and end (`Siege.java:481`, `:251`; `FortSiege.java:308`,
`:251`), Territory War start and end (`managers/TerritoryWarManager.java:942`, `:1043`) and ward capture (`:376`), Seven
Signs period change and seal owners (`mechanics/sevensigns/SevenSigns.java:1508`, `:1273`), tax change (`Castle.java:605`),
Hellbound level and trust (`dist/game/data/scripts/ai/areas/Hellbound/HellboundEngine.java:174`, `:342`), seed states
(`managers/SeedOfDestructionManager.java:201`, `managers/SeedOfInfinityManager.java:87`, `dist/game/data/scripts/ai/areas/
Gracia/AI/SeedOfAnnihilation/SeedOfAnnihilation.java:302-324`), Manor settlement (`managers/CastleManorManager.java:346-424`),
clan war start and end (after the clan sets change, `data/sql/ClanTable.java:400`, `:443`), and the facts of PACT, the
Ledger (counts only, never a contract's parties), Signal's daily demand cells (aden-signal.md §4.8) and the Guild (§4.6).

A **reign** begins at a castle owner fact and ends at the next owner fact of that castle (set or removed); its key is
(castle, clan, beginning fact id).

**Cadence**, on the facts' logical time, never per tick (12 §3): buckets of `BucketMinutes` (5); short-term pressure each
30 minutes; situations hourly; decay, slow economy, politics and history daily; eras and records weekly. A step runs at
the first fact whose logical time passes its boundary, so live and replay agree.

### 4.2 Regional state

Per region (territory and subregion), integers per mille or per day, each an exponentially weighted average of a telemetry
ratio with a fixed half-life (`data/lc/region_state.csv` gives the formula, scale and half-life of every key):
`prosperity`, `supply_health` per resource family, `security` (inverse PvP and PK density), `trade_activity`,
`resource_pressure` per family (yield taken against the family's baseline), `monster_pressure`, `conflict` (sieges, wars,
Territory Wars), `outlaw` (PK density, Ledger contract counts), `war_exhaustion`, `npc_confidence`, `import_dependency`
(Guild arrivals against local supply). Distinct counts are of **principals** (actor-identity.md §2.2), so one account's
characters count once, and synthetic actors count apart (they never move an actuator alone, §4.4). Baselines are trailing
28-day medians, so a whole server's growth or decline moves nothing.

### 4.3 Actuators

Every actuator has a kind, bounds, a rate, a recovery, exclusions, a kill switch, a reason code and an application id,
and every value is explained by `lc why` (§9). The **perception channel** is how a player can notice it.

#### 4.3.1 Yield share (`YIELD`, per subregion and resource family)

A factor *f* ∈ [1000 − V, 1000 + V] per mille (V = the family's `max_variation_permille`, default 50) multiplying the
**final** chance of an item of that family dropping from a monster of that subregion:

- at the group roll, on `groupItemChance` after it is computed (`entity/actor/templates/NpcTemplate.java:824`); at the
  ungrouped roll (`:1183`); at the spoil roll (`:1264`). Never by folding into `rateChance`: in the group path any
  `rateChance ≠ 1` switches Mobius to its "custom rates" mode, which rolls a group's items independently (`:815-822`), a
  change of behaviour, not of odds;
- never for a protected item (§2.2); never for a raid or grand boss or a minion of one; amounts, rates, champion and
  premium multipliers untouched.

**Budget** (the global supply budget, 12 §4): per family and day, Σ_r w_r·f_r = Σ_r w_r, where w_r is subregion r's
expected yield of the family at neutral over the trailing 7 days (from the `yield` telemetry). Targets are projected onto
that plane and clamped to the bounds by a fixed, deterministic iteration; the expected server-wide supply of every family
is therefore unchanged, only where it is found shifts. Because no family item has a base chance at which the upper bound
could reach 100 % (§2.2), no factor is clipped and the budget is exact in expectation.

Hot path: one array read `factor[subregion][family]` from an immutable snapshot swapped atomically at each cadence step;
item → family is a precomputed `int[]`. Perception: yields noticed by hunters, Guild prices, rumors.

#### 4.3.2 Spawn tempo (`TEMPO`, per allowlisted spawn)

A factor *t* ∈ [800, 1250] per mille on the respawn delay Mobius draws when a monster of an **allowlisted** retail spawn
dies (`entity/spawns/Spawn.java:265`, the delay given to `RespawnTaskManager`). The allowlist (`data/lc/spawn_context.csv`)
holds ordinary hunting-ground spawns near boss lairs and contested areas, never a boss, raid, minion bound to a leader,
quest NPC or a spawn with `selection` groups. **Budget**: per territory, the expected respawn rate over its allowlisted
spawns, Σ n_s / (delay_s·t_s), equals its value at neutral: denser in one place means sparser elsewhere in the same
territory. Perception: monsters seen thicker or thinner, guards' remarks.

Mobius has no runtime API to enable or disable a named spawn group (`entity/spawns/SpawnGroup.java:224`, `setActive`,
only stops replacements), and Aden Remembers needs none: it never adds or removes a retail spawn.

#### 4.3.3 Limited merchant stock (`STOCK`, per town and product)

For buy-list products with a limited count (`dist/game/data/buylists/*.xml`, the `count` attribute; 2 072 entries, mostly
clan-hall scrolls, strider food and enchant scrolls), the restock delay may be scaled by [700, 1500] per mille, through a
factor read where a sale schedules the restock (`mechanics/buylist/Product.java:107`); the maximum count and price never
change. No budget: a longer delay sells less, a shorter one never below 700 ‰ of retail. Perception: "sold out" more or
less often.

#### 4.3.4 Guild prices and orders (`GUILD`, per town and item)

The Guild's own buy and sell prices for family items (§4.6), within [`GuildBuyMinPermille`, `GuildBuyMaxPermille`] of the
retail NPC buy-back (reference price ÷ 2, `network/clientpackets/RequestSellItem.java:165`, `:183`) and
[`GuildSellMinPermille`, `GuildSellMaxPermille`] of the reference price, at most `GuildPriceRatePermilleDay` per day, and
always buy < sell across all towns (§2.5). Orders only post when the pool funds them. Perception: posted prices and orders
at the Guild NPC, "we're buying coal".

#### 4.3.5 Caravans, rumors, fees

Caravan dispatch and routes (§4.6), rumors and criers (§4.8), and institutional fees only through Crown Law (§4.5). Each
has its own bounds there.

### 4.4 Situations

A situation is `CAUSE → CONSEQUENCE → HUMAN REACTION → NEW CONSEQUENCE → HISTORY` (12 §5): a cause predicate over regional
state, confirmation and release windows, the actuator targets it asks for (each a delta, combined additively with other
situations' and clamped, §3.2), and the evidence it produces. **A situation never acts on synthetic activity alone**: its
predicate must hold over human principals' telemetry too (actor-identity.md §3), so citizens can make the world believable
but never steer it. `data/lc/situations.csv` holds every kind's parameters; adding a kind is a spec change.

| Kind | Cause (over its windows) | Consequences | Rows of the matrix |
|---|---|---|---|
| `OVERHARVEST` | a subregion's `resource_pressure` for a family above `OverharvestPermille` of baseline | that family's yield factor down there, up in the territory's other subregions (inside the budget); Guild buy price for it up in neighbouring towns; rumors | resource pressure, dynamic opportunity, migration |
| `SHORTAGE` | a town's craft demand for a family (crafts using it, Signal `CRAFTING`/`MATERIALS` cells) above its local supply (Guild warehouse and store trade) | Guild buy orders for the family's **catch-in** items (low-grade inputs low levels farm); caravans toward the town; rumors | shortages, newcomer catch-in, opportunity |
| `TRADE_SHIFT` | a castle's tax raised, followed by its towns' `npc_buy`+`store_trade` falling against neighbours | rumors and criers on prices; Guild routes favouring the neighbours; history if sustained | economic warfare, smuggling |
| `OUTLAW_PRESSURE` | `outlaw` above threshold in a territory | guard remarks; caravan escort premiums up; Guild caravans avoid it unless Open Roads | banditry |
| `BOSS_ECOLOGY` | sustained kills around a raid or grand boss lair (by spawn file) | tempo up for the lair's allowlisted spawns, down elsewhere in the territory; Guild demand for preparation consumables; scouts' rumors | boss ecology, opportunity |
| `WAR_EXHAUSTION` | `conflict` sustained in a territory | Guild sell prices up and orders down there; deserted-market rumors; mercenary demand noted in the Gazette | rebellion (dialogue only), NPC societal reaction |
| `UNREST` | high tax, low `npc_confidence`, sustained war exhaustion | NPC dialogue and rumors of discontent, criers questioning the lord; nothing else (never combat, never a spawn) | rebellion, corruption (bribe volume, black-ledger.md §4.7, read as a count) |
| `FRONTIER` | Hellbound level or a seed state changed, or a seed's attack cycle open | Guild orders for expedition supplies; rumors inviting lawful participation; never touching level, trust or state | Hellbound and Gracia |

### 4.5 Crown Law

The lord of a castle (the leader of its owning clan) enacts, repeals and reads laws at the castle's **Chamberlain**, in a
new `crown_law` action of `dist/game/data/scripts/ai/others/CastleChamberlain/CastleChamberlain.java` guarded like
`manage_tax` (`:797`, `isOwner` at `:472`, plus "is the clan leader"), reached through the Chamberlain's page by the
script bypass (`dist/game/data/scripts/handlers/bypass/npc/ScriptLink.java:86`, which requires interaction range). Each
action is a Codex confirmation (codex-ui.md §4.4). Laws are public: proclaimed by the territory's criers (§4.8), listed in
the Codex Laws tab, recorded in history (§4.9). A law never changes combat, rates or PK mechanics.

| Law | Effect | Funding and bounds |
|---|---|---|
| Bounty Endowment | tops up `CURSED_WEAPON` Writs on cursed-weapon holders in the territory, and **only those** (black-ledger.md §4.3: external money funds no other Writ) | committed treasury Adena in custody; `EndowmentCapAdena` per weapon life as the Ledger defines it |
| Market Charter | Notarius fees in the territory's towns × `CharterFactorPermille` (the Market Charter factor of pact.md §2.2) | the custody pays the difference into the `FEE_NOTARIUS` sink, so the sink sees the full retail fee |
| Caravan Protection | escort payments for Guild caravans entering the territory × (1 + bonus) | committed custody |
| Amnesty | the territory's Magistrates refuse new `KARMA` Writs on a mark with no PK fact since the proclamation (a predicate the Ledger asks at `open`, black-ledger.md §4.3) | no money; costs `AmnestyInfluenceCost` influence; at most once per reign |
| Gatekeeper Levy | a fee on teleports from the territory's gatekeepers, `LevyPermille` of the retail fee, charged with it (`entity/teleporter/TeleportHolder.java:221`) and credited to the treasury (`Castle.addToTreasuryNoTax`, `mechanics/siege/Castle.java:333`) | levy % + castle tax % ≤ the Seven Signs cap (`managers/CastleManager.java:216-245`: 5, 25 or 15) at every tax or seal change; above it the law is suspended until it fits |
| Open Roads | Guild caravans route through the territory when an alternative exists; road wardens (guard NPCs of an existing template) walk its caravan routes | committed custody pays `WardenWageAdena` per warden-hour into the `CROWN_UPKEEP` sink |

**Upkeep scales with holdings** (12 §6.3): every funded law of a clan holding *k* castles costs × (1 + (k − 1) ×
`HoldingUpkeepPermille`/1000), and Amnesty's influence cost likewise.

**Tax integrity**: the levy's cap is only meaningful if the castle tax is within [0, cap], and Mobius accepts a negative
tax from a crafted bypass (`CastleChamberlain.java:827-847` checks only the upper limit; `Castle.setTaxPercent`,
`Castle.java:605-621`, checks nothing). The Crown Law core patch clamps `setTaxPercent` to [0, the current cap], which
retail's own UI never exceeds; this is ledgered as a retail bug fix.

### 4.6 The Merchant Guild and caravans

**Buying.** At a town's Guild Master (a new NPC on an existing model), a player sells family items at the town's Guild buy
price, paid from the pool: an Asset exchange (player items → warehouse custody, pool Adena → player). The Guild also posts
**orders** as PACT delivery contracts it issues (pact.md, issuer `MERCHANT_GUILD`), escrowed from the pool at posting, for
the catch-in items of a `SHORTAGE` (§4.4): a newcomer's spoils become something a town needs (12 §5.2).

**Selling.** Players buy warehouse goods at the town's Guild sell price: an Asset exchange (player Adena → pool, warehouse
items → player). Crafters in shortage towns are the buyers caravans exist for.

**Funding.** A pool receives (1) what players pay it, and (2) a **diverted share** of the retail Adena sinks of its
territory: `DivertPermille` (default 100, at most 250) of every teleport fee paid at the territory's gatekeepers and of
every Notarius fee paid in its towns goes into the pool instead of being destroyed (asset-service.md, a `divert` leg: the
player pays exactly what retail charges). These are the only Adena Aden Remembers ever puts in players' hands, and they
are bounded by what retail would have destroyed. A pool is never negative; an order or a purchase it cannot fund is
refused. Because every buy price is below every sell price for the same item, no loop through the Guild gains Adena.

**Caravans** move warehouse goods between towns (12 §8). A caravan is dispatched when a destination's `supply_health` for
a family is low and an origin's warehouse holds it, at most `CaravansPerTownDay`, with the manifest capped by
`ManifestMaxValueAdena` (at reference prices). Its route is a datapack route in `dist/game/data/Routes.xml`
(`lc_caravan_*`, validated at startup against `lc_caravan_route`), because Mobius has no runtime route API
(`managers/WalkingManager.java:73`, `:212`); the caravan (a Caravan Master and pack animals: new NPC templates on existing
client models via `displayId`, with no drops, experience or SP, so killing them yields only the raid share) is spawned at
`depart_at` and walked with `WalkingManager.startMoving` (`:279`); node arrivals update `last_node` (`ON_NPC_MOVE_NODE_ARRIVED`,
asynchronous, `WalkingManager.java:447-449`, fine for a position). The schedule is posted on the origin's notice board
("departs at 20:00") `CaravanNoticeMinutes` ahead.

- **Escort**: the Guild issues a Caravan Escort contract (pact.md §4.2, issuer the Caravan Guild), paid from the pool (and
  Caravan Protection's custody), settled on arrival by participation (`lc_caravan_escort.presence_seconds`).
- **Raid**: killing the Caravan Master ends the caravan: `raid` drops `RaidSharePermille` (default 300) of the manifest
  once, after commit, at the Master's position (`lc_raid_drop`, asset-service.md ASSET-R25), and the rest is lost to the
  road (`ROAD_LOSS` sink). Killing NPCs gives no karma in Mobius; raiders who kill escorts or bystanders get karma and
  flags by the normal rules, and the raid itself becomes history, `outlaw` pressure and rumors. No Writ is posted on
  raiders: the Ledger takes external money only for cursed weapons (black-ledger.md §4.3), and a raid names no one.
- **Arrival** raises the destination's supply; a lost caravan leaves its shortage. Fortresses on a route (`fort_ids`) give
  their owning clan the route's schedule in the Codex (visibility only; no fortress guards join).

### 4.7 Influence and the complexity cost

Influence per (territory, clan), derived daily (`lc_influence`): castle and fortress ownership, Territory War results,
defended caravans (members' escort presence), Manor participation, public PACT contracts, regional presence (distinct
member principals). Outputs: political capital for Crown Law (Amnesty), the order of Guild escort offers among equal
applicants (never exclusion), prestige in history, and **exposure**: the dominant clan of a territory is named in its
rumors (a public fact). Never a combat or rate effect. Holdings raise burdens (§4.5's upkeep; more caravans to defend
through owned territories; unrest weighs more under higher taxes), so domination requires organisation, not population.

### 4.8 Rumors and criers

- **Rumor NPCs** (tavern keepers, guards, travellers: a listed set of templates) add a rumor line to their chat window,
  through a seam in `Npc.showChatWindow` (`entity/actor/Npc.java:843`, `:879`) for those templates only, never a
  container-wide first-talk listener (which would skip every NPC's default window, `dist/game/data/scripts/handlers/
  actions/click/NpcClick.java:121-128`).
- **Criers** proclaim laws, caravan departures and notable outcomes with `Npc.broadcastSay` (`Npc.java:2136`) in their town.
- A rumor is a template with fields of these kinds only: a territory or town, a subregion, a resource family, a situation
  kind, a band word (few, some, many; rising, falling), a public entity (a castle, the owning clan, a law). Never a
  character, never a number, never a position, never anything from intel. A rumor about activity in a place starts
  `RumorDelayMinutes` (default 360) after its situation's evidence and lives at most `RumorLifeHours`. Wayfarer records a
  heard rumor as `RUMORED`/`WORLD` (wayfarers-memory.md §2.1).

### 4.9 History

Durable history is promoted daily or weekly by thresholds on scale, rarity, duration, value, unique principals, political
consequence and firsts or records: reigns, sieges, Territory Wars, fortress holdings, clan wars, mechanically observable
betrayals (leaving an alliance mid-war; breaking a public PACT contract), major hunts, notable crafts (public PACT),
economic eras (situations of a week or more), long dominance, laws, caravan disasters. Participants are recorded with
their roles and origin. `publishable` marks what Chronicle (17) may publish, under the Intel Service's field rules and
tactical delay (intel-service.md §4.7); this spec publishes nothing itself.

### 4.10 Freeze and disable

- **Kill switch** per actuator kind: its values go to neutral in the next snapshot (at most one cadence step, the hot-path
  snapshot swapped at once).
- **Freeze** (`lc_module_state` `WORLD`): on a verifier violation (§4.11) or a projector stop (fact-journal.md §4.3); every
  actuator neutral, situations closed, no caravan dispatched, Guild trading paused (warehouses and pools kept), laws kept
  (they are custody, owned by their own rules); until an admin unfreezes with a reason.
- **Disable** (`Enabled = false`): as freeze, plus every `EN_ROUTE` caravan `RECALLED`. The world is then retail: no
  factor, no tempo, no stock factor, no Guild NPC trade (12 §10 `AR-DISABLE`).

### 4.11 Production checks

A verifier runs each daily step: every family's budget equation holds within rounding; no value outside its bounds or
faster than its rate; no factor on a protected item; every pool and warehouse equals its Asset custody; every rumor's
params pass the field rules; Guild buy < sell across towns; no situation moved an actuator without human principals in its
evidence. A violation is an alarm and a freeze.

## 5. Facts

Durable, emitted: `World.SituationChanged` (situation, from, to, reason), `World.ActuatorSet` (kind, region, target,
value, application id), `World.LawEnacted`, `World.LawEnded` (reason), `World.GuildTraded` (town, item, qty, price, side),
`World.OrderPosted`, `World.CaravanScheduled`, `World.CaravanDeparted`, `World.CaravanArrived`, `World.CaravanRaided`,
`World.CaravanRecalled`, `World.HistoryPromoted`, `World.Frozen`. Consumed: the telemetry families and durable facts of
§4.1; Asset, PACT and Ledger facts as counts. Captured at the seams of §4.1: `World.CastleOwnerChanged`,
`World.FortOwnerChanged`, `World.SiegeStarted`, `World.SiegeEnded`, `World.TerritoryWarStarted`,
`World.TerritoryWarEnded`, `World.WardCaptured`, `World.SevenSignsPeriod`, `World.TaxChanged`, `World.HellboundChanged`,
`World.SeedChanged`, `World.ManorSettled`, `World.ClanWarChanged`.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| AR-INV01 | every family's expected server-wide yield equals its neutral value (budget plane) | the projection; the verifier daily; AT-AR-01 |
| AR-INV02 | every actuator value within its bounds and rate; protected items never factored | checks on `lc_actuator`; the trigger on families; the verifier; AT-AR-02 |
| AR-INV03 | nothing outside §1's list is changed | the actuator set is closed; the static scan; AT-AR-03 |
| AR-INV04 | nothing is created: Guild goods came from players, Guild Adena from players or diverted sinks | Asset operations only; the economy monitor; AT-AR-05 |
| AR-INV05 | live and replay give the same state, situations and values | the projector runtime; AT-AR-06 |
| AR-INV06 | no interface returns a state value; rumors only carry rule-allowed fields | the interface list; the verifier; AT-AR-08 |
| AR-INV07 | laws within funding, caps, expiry and the Ledger's limits | keys, triggers, the levy check at every tax change; AT-AR-07 |
| AR-INV08 | disabled, the world is retail | AT-AR-09 |

## 7. Failure model

| Fault | End state |
|---|---|
| projector stopped at a gap | frozen (§4.10) until acknowledged |
| an exception in a hot-path seam | caught, counted; the retail roll or delay unchanged |
| crash between an effect's intent and its application | applied once after restart (application id, fact-journal.md §4.4) |
| restart with caravans en route | re-spawned at `last_node`; recalled past the timeout |
| Asset Service frozen | Guild trading, dispatch and law funding refused; actuators keep their values |
| a pool exhausted | orders and purchases refused; caravans not dispatched without escort funding |
| a tax or seal change over the levy cap | the levy suspended until it fits |
| a verifier violation | frozen |

## 8. Configuration

`config/Custom/LivingChronicle/AdenRemembers.ini` and the `data/lc/*.csv` tables. Every value change is a `config` fact
(fact-journal.md §4.1).

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `Enabled` | bool | true | false / true / true / true / true | AT-AR-09 |
| `BucketMinutes` | 1–15 | 5 | as default everywhere | AT-AR-06 |
| family `max_variation_permille`, `max_rate_permille_day`, `recovery_days` | 0–100, 1–50, 1–60 | 50, 10, 14 | 0 in truth, as default elsewhere | AT-AR-01, AT-AR-02 |
| tempo bounds | 800–1000, 1000–1250 | 800, 1250 | 1000/1000 in truth | AT-AR-02 |
| stock delay bounds | 700–1000, 1000–1500 | 700, 1500 | 1000/1000 in truth | AT-AR-02 |
| `GuildBuyMinPermille`, `GuildBuyMaxPermille` (of buy-back), `GuildSellMinPermille`, `GuildSellMaxPermille` (of reference) | 1000–1500, 1000–2000, 500–1000, 500–1500 | 1000, 1400, 800, 1200 | as default everywhere | AT-AR-05 |
| `GuildPriceRatePermilleDay` | 1–100 | 30 | as default everywhere | AT-AR-02 |
| `DivertPermille` | 0–250 | 100 | 0 in truth | AT-AR-05 |
| `CaravansPerTownDay`, `ManifestMaxValueAdena`, `RaidSharePermille`, `CaravanTimeoutFactor`, `CaravanNoticeMinutes` | 0–12, 0–1e9, 0–500, 2–5, 30–1440 | 2, 20000000, 300, 3, 120 | 0 caravans in truth | AT-AR-04 |
| `MaxLawsPerCastle`, `CharterFactorPermille`, `LevyPermille`, `WardenWageAdena`, `HoldingUpkeepPermille`, `AmnestyInfluenceCost` | 0–6, 500–1000, 0–500, 0–100000, 0–1000, 0–1e6 | 3, 800, 100, 2000, 250, 1000 | 0 laws in truth | AT-AR-07 |
| `RumorDelayMinutes`, `RumorLifeHours` | 60–2880, 1–168 | 360, 48 | as default everywhere | AT-AR-08 |
| situation parameters (`data/lc/situations.csv`) | per kind | per kind | as default everywhere | AT-AR-10 |

## 9. Interface

Guild Master (per main town): sell to the Guild, buy from the Guild, orders, caravan schedule and escort sign-up.
Chamberlain: Crown Law (enact, repeal, list). Criers, rumor NPCs, notice boards. Codex: the Laws tab, Gazette entries
(Chronicle). Admin: `lc why actuator <kind> <region> <target>` (the value, its rows, the situations and facts behind it),
`lc why situation <id>`, `lc world switch <kind> on|off`, `lc world unfreeze <reason>`. Nothing else shows any state.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| AR-R01 | The yield factor MUST keep every family's expected server-wide supply equal to neutral and MUST apply only to the final chance of unprotected family items at the three drop rolls. | AT-AR-01 |
| AR-R02 | Every actuator value MUST stay within its bounds, move at most at its rate, recover when unasked, and go neutral at once on a kill switch, freeze or disable. | AT-AR-02 |
| AR-R03 | Aden Remembers MUST NOT change anything outside its actuator set (§1). | AT-AR-03 |
| AR-R04 | Caravans MUST conserve their manifest: arrival, raid share plus road loss, or recall account for all of it, a raid share dropped at most once. | AT-AR-04 |
| AR-R05 | The Guild MUST create nothing: its goods come from players, its Adena from players or diverted sink shares, its pools never negative, its buy prices below its sell prices everywhere. | AT-AR-05 |
| AR-R06 | State, situations and actuator values MUST be identical live and on replay. | AT-AR-06 |
| AR-R07 | Every Crown Law MUST respect its funding, the levy cap, its reign, the Ledger's limits, proclamation and history. | AT-AR-07 |
| AR-R08 | No interface MUST return a state value, and rumors MUST carry only rule-allowed fields after their delay. | AT-AR-08 |
| AR-R09 | Disabled, Aden Remembers MUST leave retail behaviour. | AT-AR-09 |
| AR-R10 | Situations MUST follow their machine, MUST NOT act on synthetic activity alone, and each MUST produce its declared consequences. | AT-AR-10 |
| AR-R11 | Every actuator value MUST be explainable by `lc why` from its rows and facts. | AT-AR-11 |
| AR-R12 | A verifier MUST check budgets, bounds, custody equality, rumor fields and the human-evidence rule daily and freeze on a violation. | AT-AR-12 |
| AR-R13 | Hot-path seams MUST stay within 2 µs p99 and contain their own exceptions. | AT-AR-13 |

| Test | Kind | What it does |
|---|---|---|
| AT-AR-00 | integration | the migration applies; keys, checks and triggers refuse a protected item in a family, an actuator row outside its bounds, a second open situation of one kind and region, a second enacted law of one kind per castle, an ended law changed, a Guild buy price at or above any town's sell price, a changed actuator or transition row, a rumor beyond its life |
| AT-AR-01 | sim | `AR-OVERHARVEST`: a synthetic overfarm of a subregion with human participation: factors shift inside bounds, the family's expected and measured server-wide supply within the declared band of neutral, recovery after pressure stops; group drops keep their one-per-group behaviour |
| AT-AR-02 | unit, integration | property test over random situations: every value within bounds and rate, recovery toward neutral, kill switch and freeze neutral within one step |
| AT-AR-03 | unit | the static scan: no Aden Remembers code writes rates, skills, stats, spawns, prices or Hellbound or seed state; the actuator set is exactly §4.3's |
| AT-AR-04 | integration, restart, fault | `AR-CARAVAN-*`: arrival, raid (share dropped once, after commit, under a crash at each step), recall on timeout and disable; a restart en route resumes at the last node; escort settlement by presence |
| AT-AR-05 | integration, sim | the economy monitor over a week of Guild trading: no source; pools equal diverted shares plus receipts minus payments; refused trades when a pool is short; no profitable loop through any two towns |
| AT-AR-06 | integration | replay from a snapshot gives the same `lc_region_state`, situations and actuator rows as live, under two reducer runs |
| AT-AR-07 | integration | `AR-LAW-*`: each law enacted, funded, proclaimed, listed, recorded; repealed and expired on an owner change with the remainder returned; the levy suspended over the Seven Signs cap; negative tax refused; an endowment refused for a non-cursed-weapon Writ; Amnesty's predicate at the Magistrate; upkeep scaled by holdings |
| AT-AR-08 | integration, sim | `AR-PERCEPTION`: client-knowledge observer citizens detect each actuator within its declared window; every Codex, website and NPC response scanned: no state value; rumors carry only allowed fields and start after their delay |
| AT-AR-09 | integration | `AR-DISABLE`: disabled mid-situation, drop rolls, respawn delays, stock and NPC pages equal a server without Aden Remembers |
| AT-AR-10 | sim | `AR-CATCHIN`, `AR-TRADESHIFT`: a mature world with new humans gets Guild orders they can serve within the window; a tax raise moves trade to neighbours within the window; a synthetic-only overfarm moves nothing |
| AT-AR-11 | integration | for random actuator rows, `lc why` returns the situations, facts and windows that produced them, and recomputing from those gives the value |
| AT-AR-12 | integration | a planted budget breach, an out-of-bounds row, a custody mismatch and a rumor with a forbidden field: alarm and freeze |
| AT-AR-13 | sim, fault | the 800-citizen benchmark: each seam ≤ 2 µs p99; an exception injected in each seam leaves the retail outcome |

## 11. Upstream constraints

1. **No events** for drops, NPC buy and sell, store trade, crafting, teleports, owner changes, Territory War, Seven Signs,
   Hellbound, seeds or Manor (§4.1). → telemetry and fact seams.
2. **Group drop custom-rates mode** (`NpcTemplate.java:815-822`). → the factor on the final chance only.
3. **No runtime spawn-group or route API** (`SpawnGroup.java:224`; `WalkingManager.java:73`, `:212`). → respawn delay at
   `Spawn.java:265`; datapack routes.
4. **No product price setter; buy-back hard-coded** (`Product.java:38`; `RequestSellItem.java:165`, `:183`). → no retail
   price actuator; the Guild's own prices.
5. **Limited merchant stock is not materials** (`dist/game/data/buylists/*.xml`). → the Guild's warehouses of real goods.
6. **Teleport fees are a pure sink, without castle tax** (`TeleportHolder.java:221`). → the divert seam and the levy.
7. **Negative tax accepted** (`CastleChamberlain.java:827-847`, `Castle.java:605-621`). → clamped (§4.5).
8. **Killing NPCs gives no karma.** → raids are history and pressure, not Writs.
9. **Container-wide first-talk listeners skip default windows** (`NpcClick.java:121-128`). → the rumor seam per template.

**Amendments to reviewed specs** (made with this spec's review): asset-service.md: the `TOWN_SUPPLY` source and sink are
withdrawn (caravans move custody to custody), the exchange and `divert` operations, the `CROWN_UPKEEP` sink, the Guild
sites; pact.md: the `MERCHANT_GUILD` issuer for orders, and escort payment from Guild pools; black-ledger.md: the Amnesty
predicate at `open`.

## 12. Review record

- Revision 1 (draft): not yet reviewed.
