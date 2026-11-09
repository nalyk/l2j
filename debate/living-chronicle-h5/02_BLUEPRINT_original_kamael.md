# LINEAGE II: LIVING CHRONICLE
## Ultra-detailed development blueprint for L2J_Mobius Classic 3.0 The Kamael

**Working product name:** *Lineage II: Living Chronicle*  
**Core world module:** *Aden Remembers*  
**Foundation:** L2J_Mobius Classic 3.0 The Kamael  
**Document purpose:** Product vision + system architecture + implementation blueprint + compatibility contract + validation plan  
**Status:** Design baseline derived from the complete design thread  
**Blueprint date:** 2026-09-30  
**Primary principle:** Preserve Lineage II. Deepen its society.

---

# 0. Executive thesis

This project must **not** become another custom Lineage II server that competes by adding more items, more NPC menus, more currencies, more rates, more convenience, more instances, or a larger collection of unrelated features.

The design target is much harder:

> **Make Lineage II more Lineage II than Lineage II, without corrupting its combat, progression, class identity, scarcity, geography, economy, politics, or social dependence.**

The core insight developed throughout the design discussion is that classic Lineage II was never primarily great because of its quest catalogue or because killing a monster produced a satisfying combat loop. Its strongest property was that **other players were the content**.

The world created conditions in which players had to:

- depend on one another;
- discover and guard information;
- fight over access rather than over abstract match scores;
- remember enemies by name;
- recognize crafters and traders;
- negotiate alliances;
- betray alliances;
- compete over castles, routes, bosses, materials, and hunting grounds;
- accept meaningful economic and PvP risk;
- build reputations that existed socially rather than as a UI number.

The project therefore adds **societal capabilities**, not replacement gameplay.

The custom systems defined by this blueprint give Aden five properties a persistent society needs:

1. **History** — *Aden Remembers*.
2. **Coordination** — *Aden Signal*.
3. **Personal continuity and knowledge** — *Wayfarer’s Memory*.
4. **Institutions and enforceable agreements** — *PACT*, embodied by the *Notarius*.
5. **Secrets, investigation, assassination and counter-intelligence** — *The Black Ledger*.

A sixth system protects the integrity of everything beneath them:

6. **Verified retail correctness** — *Retail Truth*.

The end state is not “Mobius with six custom mods”. The end state is a **persistent medieval society implemented on top of a verified Lineage II simulation**.

The product proposition is:

> **One permanent world. No seasonal amnesia. No scripted global storyline. The history is written by the inhabitants.**

Aden is not a map. It is a society.

---

# 1. Scope and hard assumptions

## 1.1 Target Mobius branch

The concrete reference branch throughout this blueprint is:

**L2J_Mobius Classic 3.0 The Kamael**.

Important clarification from the design thread:

- the current Mobius source tree contains **Classic 3.0 The Kamael**;
- this must not be confused with a separate original CT1 “The Kamael” server project;
- client compatibility is branch/protocol-specific rather than generic “any Classic client”.

The design remains portable to other Mobius branches, but implementation details, packet support, available systems, NPC IDs, datapack structure, skills, retail rules, and event availability must be revalidated per branch.

## 1.2 Technical baseline discussed

Development baseline:

- Linux x86-64;
- JDK 25 for the current branch/build baseline discussed;
- Ant-based build;
- MariaDB;
- Mobius LoginServer;
- Mobius GameServer;
- full geodata enabled for realistic testing;
- Lineage II client runs on developer workstation, not the server;
- no GPU requirement for Mobius or authoritative custom systems.

Resource baseline for development:

- **absolute practical dev minimum:** 2 vCPU / 8 GB RAM / 30 GB SSD;
- **recommended working environment:** 4 vCPU / 12–16 GB RAM / 50 GB NVMe;
- **simulation/profiling environment:** 4–8 vCPU / 16 GB+ RAM when running larger synthetic populations or headless actors.

The discussed GameServer JVM baseline was approximately:

```text
-Xms2g
-Xmx4g
-XX:+UseZGC
```

The LoginServer requires far less memory.

These are development recommendations, not production sizing.

## 1.3 Authoritative simulation rule

All game-affecting decisions are made by deterministic or explicitly randomized **Java server logic**.

An LLM may be used only as a **narrator**, never as an authority.

Allowed LLM uses:

- turn verified world facts into tavern rumors;
- summarize a historical siege;
- produce a readable Chronicle article;
- vary non-authoritative NPC wording;
- generate summaries from structured facts.

Forbidden LLM uses:

- deciding drop multipliers;
- deciding which clan deserves a benefit;
- changing world state directly;
- determining PvP outcomes;
- deciding guilt, trustworthiness, contract validity, or sanctions;
- creating authoritative player locations;
- replacing deterministic economic rules.

The core maxim is:

> **The engine decides facts. A model may narrate facts.**

---

# 2. Non-negotiable Lineage II design philosophy

Every proposed feature must pass the following tests.

## 2.1 Productive pain versus stupid pain

Lineage II contains intentional friction that creates value:

- long travel;
- dependency on support classes;
- mana management;
- gear scarcity;
- low success probabilities;
- dangerous open world movement;
- death and PK consequences;
- difficult logistics;
- slow progression;
- incomplete information;
- social dependence.

These are **productive pain** when they create decisions, relationships, risk, trade, or stories.

The project must remove **stupid pain around the game**, not the meaningful friction inside the game.

Examples of stupid pain:

- two compatible players farming separately because neither can discover the other;
- returning after three days and forgetting what you were preparing;
- handing millions in materials to a crafter because the UI offers no safe voluntary contract;
- a head hunter receiving exact GPS coordinates, reducing a hunt to travel time;
- a newcomer being irrelevant because every veteran has outgrown all low-level supply chains.

The governing sentence is:

> **Never reduce the cost of succeeding in Aden merely for convenience. Reduce the cost of being a human trying to participate in Aden.**

## 2.2 Never replace Lineage content; make it cause consequences

We do not create a parallel game beside Lineage II.

We use existing systems:

- Adena;
- existing materials;
- spoil;
- crafting;
- private stores;
- Manor;
- castles;
- clan wars;
- PvP/PK;
- raid bosses;
- classes;
- skills;
- geography;
- travel;
- towns;
- NPCs;
- warehouses;
- alliances;
- player knowledge.

Then we connect them so actions in one system create consequences in another.

## 2.3 Things this project explicitly rejects

The core design must not depend on:

- custom god weapons;
- wings as progression;
- custom combat classes;
- blanket XP catch-up;
- free endgame gear;
- global teleport convenience that nullifies geography;
- free universal buffs that nullify support classes;
- Battle Pass as the main retention mechanism;
- dozens of custom currencies;
- mandatory daily chores;
- artificial “underdog +50% damage” balancing;
- seasonal wipes as the primary freshness mechanism;
- arena/lobby replacement of open-world conflict;
- omniscient server-provided wiki data;
- GPS tracking for assassination;
- subjective automated morality scores.

## 2.4 Power must create obligations

Any system that increases power must also increase **surface area, responsibility, exposure or complexity**.

A clan that dominates territory should gain opportunities, but also create:

- more routes to protect;
- more political hostility;
- more visible assets;
- more dependencies;
- more economic consequences;
- more targets for enemies;
- greater coordination requirements.

This is preferred over synthetic catch-up buffs.

## 2.5 Information is gameplay

The server should distinguish:

- fact;
- personal observation;
- verified witness report;
- inference;
- rumor;
- stale knowledge;
- deliberate misinformation.

The server should **never expose internal simulation values as an optimal-strategy dashboard**.

Do not show:

```text
OrenResourcePressure = 82.7%
```

Show consequences:

- unusual merchant demand;
- a guard mentioning fighting;
- players carrying unexpected materials;
- a rare spawn appearing elsewhere;
- trade shifting between towns;
- an NPC rumor with uncertain provenance.

Mystery is not missing UX. It is part of the game.

---

# 3. Stock Mobius foundation: retail systems assumed to exist

The project assumes Mobius supplies the broad L2 framework required for a real server, including the major systems discussed in the thread:

- characters;
- races;
- classes;
- leveling;
- combat;
- skills;
- monsters;
- drops;
- spoil;
- equipment;
- enchantment;
- crafting;
- private stores;
- parties;
- clans;
- clan wars;
- PvP and PK;
- raids and bosses;
- summons and pets;
- Olympiad;
- castles and sieges;
- teleportation;
- warehouses;
- quests;
- instances;
- daily systems;
- script handlers;
- geodata;
- pathfinding;
- administrative infrastructure;
- game/login server architecture.

“Exists” must not be interpreted as “retail-perfect”. Retail fidelity is handled explicitly by **Retail Truth** later in this document.

---

# 4. Complete inventory of stock Mobius custom functionality discussed

This section is intentionally exhaustive. These are the stock/custom Mobius capabilities explicitly discussed in the thread. They are **not inventions of Living Chronicle**, but they affect compatibility and server policy.

The design rule is that our new societal systems must coexist with them without requiring their removal.

## 4.1 Allowed Player Races

Allows race creation to be enabled or disabled individually, including Human, Elf, Dark Elf, Orc, Dwarf and Kamael.

**Why it exists:** server operators can constrain the playable population for special rulesets.

**Living Chronicle policy:** compatible. Normally leave all branch-retail races enabled. Aden Remembers must never assume every race is enabled.

## 4.2 Auto Potions

Provides automatic CP/HP/MP potion consumption with configurable thresholds, item priorities, level requirements and Olympiad policy.

**Purpose:** convenience/automation of consumable use.

**Compatibility:** orthogonal. Our systems never rely on potion state.

## 4.3 Banking System

Provides conversion between Adena and configured banking items such as Gold Bars, with configurable ratios and commands such as deposit/withdraw.

**Purpose:** high-value currency handling on custom servers.

**Compatibility:** technically compatible, but Living Chronicle should treat Adena as the primary economic truth. If banking is enabled, PACT escrow and economic telemetry must understand the bank item so wealth cannot disappear from analysis.

## 4.4 Boss Announcements

Configurable global announcements for raid/grand boss spawn and death, with instance inclusion and exclusions.

**Compatibility:** compatible, but public announcements may reduce mystery around certain Aden Remembers situations. Operators should decide whether all boss information should be globally broadcast.

## 4.5 Cancel Return / Buff Restoration

Allows cancelled buffs to return automatically after a configurable delay, with separate policies for player, monster/raid and Olympiad contexts.

**Compatibility:** orthogonal to societal systems. Retail Truth must record whether enabled when validating combat.

## 4.6 Captcha Anti-Bot System

Triggers player verification based on kill activity, configurable thresholds, timeout/retry policy and punishments such as teleport, kick, jail or ban.

**Compatibility:** useful but insufficient as sole anti-cheat. Black Ledger and economic systems increase the value of automation, so anti-bot coverage must be stronger than this module alone.

## 4.7 Champion Monsters

Allows ordinary mobs to become enhanced champions with configurable level range, stats, aura/title, EXP/SP, drop and special reward behavior.

**Compatibility:** compatible, but if enabled Aden Remembers must distinguish champion-driven reward anomalies from regional economic changes so it does not infer false scarcity/surplus.

## 4.8 Chat Moderation

Administrative voice commands for chat bans/unbans and access-controlled moderation.

**Compatibility:** fully orthogonal.

## 4.9 Per-Class Balance Engine

Class-specific multipliers can modify PvE/PvP magic damage, physical skill damage, normal attacks, critical behavior, defence, blows, energy skills, healing, skill mastery, reuse, XP and SP.

**Compatibility:** technically compatible but philosophically sensitive.

Living Chronicle should default to **retail values** and use Retail Truth to prove them before applying discretionary balance multipliers. None of the revolutionary modules should require combat rebalancing.

## 4.10 Custom Community Board / Alt+B Services

Supports services such as custom shops/multisells, teleportation, buffs, healing, deleveling, configurable currencies/prices, peace-zone/combat/karma restrictions, available buff/teleport lists and Premium purchase options.

**Compatibility:** can host read-only interfaces for our systems, but it must not become a bypass for the world.

Recommended philosophy:

- Signal may use Community Board as one interface;
- Wayfarer’s Memory may expose a journal UI there;
- Chronicle may expose public history;
- PACT high-value signing should preserve physical institutional ceremony;
- Black Ledger should use appropriate in-world actors rather than a generic Alt+B “Assassination” button.

## 4.11 Custom Depositable Items

Allows selected normally non-depositable items, optionally including quest items, to be stored in private warehouse.

**Compatibility:** PACT escrow must operate independently of warehouse depositability rules and never assume an item is contract-safe merely because it can enter a warehouse.

## 4.12 Custom Mail Manager

External/server-side mail delivery via a custom SQL queue, useful for websites, shops, rewards and automation.

**Compatibility:** highly useful as a delivery/notification channel, but important PACT assets should settle directly through authoritative escrow rather than depend on asynchronous mail when avoidable.

## 4.13 Delevel Manager

NPC-based instant deleveling with configurable NPC, price item, amount and minimum level.

**Compatibility:** compatible. Wayfarer’s Memory records level history as historical fact but must not treat delevel as corruption.

## 4.14 Dualbox Control

Controls clients/characters per IP, including separate rules for Olympiad, events and Offline Play, Premium exceptions, whitelist/extra slots and whether offline traders count.

**Compatibility:** essential context for anti-abuse. Black Ledger, PACT and Signal must use the same identity-risk signals where appropriate without exposing real-world identifiers to players.

## 4.15 Faction System

Provides a Good-vs-Evil style server mode with bases, colors, guards, respawns, optional Nobless, chat restrictions and population balancing.

**Compatibility:** technically possible but **not recommended for the canonical Living Chronicle ruleset** because it replaces emergent clan politics with artificial fixed factions.

Aden Remembers is deliberately designed so factions emerge socially rather than being imposed by a permanent red/blue framework.

## 4.16 Fake Players

NPC-backed simulated players can chat, use shots, interact with PvP/karma rules, attack monsters/players/fakes, generate drops and pick up items.

**Compatibility:** extremely useful for development and testing.

Production rule: fake actors must never silently contaminate Aden Remembers history, economic statistics, PACT, Black Ledger bounties, or player reputation. Every event must carry an actor type and synthetic actors must be filterable.

## 4.17 Find PvP

Community Board/NPC bypass functionality that directs a player toward PvP activity.

**Compatibility:** potentially conflicts with Black Ledger philosophy if it provides too much location certainty. Keep ordinary Find PvP behavior isolated; never reuse its exact-location logic for assassination.

## 4.18 Free Mounts

Provides configurable free Strider/Wyvern functionality through custom skills.

**Compatibility:** orthogonal, although widespread free movement changes geographic friction and therefore can indirectly affect Signal travel estimates and Aden Remembers trade geography.

## 4.19 Merchant Zero Sell Price

Allows items sold to NPC merchants to return zero Adena globally.

**Compatibility:** Aden Remembers economy must read actual configured sell values rather than assume retail NPC liquidation value.

## 4.20 Multilingual Server Support

Server-side language selection, allowed/default languages and in-game language switching.

**Compatibility:** strategically valuable.

Aden Signal may include language preferences. Narrative systems should keep authoritative facts language-neutral and render text through localization or optional narrative generation.

## 4.21 Nobless Master

Custom NPC can grant Nobless to qualifying players based on configurable level/item requirements and optionally reward a tiara.

**Compatibility:** orthogonal. Retail Truth environments must disable it when validating retail progression.

## 4.22 NPC Stat Multipliers

Global multipliers for normal monsters, raid bosses, guards and defenders, including HP, MP, P.Atk, M.Atk, P.Def, M.Def, aggro and clan-help range.

**Compatibility:** technically compatible, but any production setting must be recorded in world metadata. Aden Remembers should never infer world difficulty from assumptions about retail NPC stats.

## 4.23 Offline Auto Play

Allows a character to continue auto-play after logout, potentially persisting across restart, with Premium restrictions, death behavior, same-account rules and visual marking.

**Compatibility:** sensitive.

Aden Remembers must clearly distinguish **online human activity**, **offline character activity** and **synthetic fake-player activity**. Otherwise the world may react politically to humans who were asleep.

Black Ledger must not reveal offline-player information in ways that produce free kills or surveillance abuse beyond configured server policy.

## 4.24 Offline Trade / Craft

Offline stores and crafting persist after logout and may survive restarts; configurable peace-zone rules, invulnerability, expiry, disconnect behavior and persistence are available.

**Compatibility:** highly relevant to economic simulation.

Offline stores are real market supply/demand and should be included in economic observations, but must not be mistaken for active human presence by Signal or Black Ledger.

## 4.25 Online Information Command

Exposes current online population through a player command.

**Compatibility:** orthogonal. The Living Chronicle should never fake online counts with fake players.

## 4.26 In-Game Password Change

Allows players to change account password from within the game.

**Compatibility:** orthogonal security utility.

## 4.27 Premium Account System

Supports Premium subscriptions and configurable XP/SP, drop chance/quantity, spoil chance/amount, quest rewards, per-item drop multipliers and other Premium integrations.

**Compatibility:** dangerous if configured as pay-to-win, but technically independent.

Canonical Living Chronicle philosophy should avoid letting Premium distort the political/economic simulation through massive reward multipliers. If Premium affects production, those modifiers must be represented in telemetry so the world model does not misdiagnose them.

### 4.27.1 PC Cafe / PC Bang enhancements

Discussed as a substantial related feature set: timed point generation, Premium/VIP restrictions, caps, XP-based acquisition, randomization, double-point probabilities and low-XP rewards.

This is conceptually separate but may live inside the broader Premium configuration.

## 4.28 Private Store Distance Control

Enforces minimum distance between stores and nearby players/NPCs to prevent dense market carpets.

**Compatibility:** fully compatible and potentially beneficial to readable economic hubs.

## 4.29 PvP/PK Announcements

Server-wide configurable kill messages with killer/target substitution.

**Compatibility:** optional. Excessive announcements can destroy uncertainty and Black Ledger tension, so public messaging policy must be deliberate.

## 4.30 PvP/PK Item Rewards

Separate PvP and PK reward items/amounts/messages with restrictions for instances and PvP zones.

**Compatibility:** technically compatible but philosophically unnecessary for the canonical design. Black Ledger bounties must never stack into exploitable reward loops without anti-feed checks.

## 4.31 PvP Ranking Titles / Colors

Automatic ranks based on PvP count with configurable thresholds, titles and colors.

**Compatibility:** compatible, but do not confuse PvP volume with Black Ledger hunter reputation. Hunter progression is intelligence capability, not combat prestige.

## 4.32 Randomized Monster Spawns

Normal monster coordinates can be randomized within a radius, with exclusions for location-sensitive mechanics.

**Compatibility:** useful with Aden Remembers, but must be distinguished from Director-driven ecological changes. The Director changes **why/where populations matter**, while random spawn radius changes micro-positioning.

## 4.33 Sayune for Everyone

Removes normal Awakening restriction for Sayune use.

**Compatibility:** branch-specific convenience; likely irrelevant to Classic 3.0 world philosophy unless the datapack uses relevant assets.

## 4.34 Scheme Buffer

Persistent player buff schemes, configurable limits, currencies and buff costs.

**Compatibility:** technically compatible. Canonical ruleset should be careful not to erase the social role of real buffers, one of the core social dependencies Aden Signal is designed to strengthen.

## 4.35 Welcome Screen Message

Custom login screen message with configurable text and duration.

**Compatibility:** ideal place for concise world-state flavor, but never dump internal world metrics there.

## 4.36 Player-to-Player Buff Selling

Allows players to sell buffs using configured currency, price bounds, MP-cost multipliers and listing limits.

**Compatibility:** strongly aligned with social-economic philosophy because it preserves player-provided support as a profession.

## 4.37 Server-Time Display

Allows `/time` to return actual server time instead of only ordinary game-time behavior.

**Compatibility:** useful for PACT deadlines, future Signal sessions, siege coordination and Chronicle timestamps.

## 4.38 Custom Starting Location

Allows new characters to spawn at arbitrary configured coordinates rather than retail race locations.

**Compatibility:** compatible, but canonical Living Chronicle should preserve meaningful geography unless there is a deliberate world design reason.

## 4.39 Custom Starting Title

Assigns a configurable title to new characters.

**Compatibility:** cosmetic and orthogonal.

## 4.40 Transmogrification / Appearance Collection

Players acquire equipment appearances into a collection and can apply them to other items; account-wide behavior, fees, exclusions and removal are configurable.

**Compatibility:** orthogonal if it remains cosmetic. Wayfarer’s Memory may record meaningful acquisition history but should not treat visual skin as equipment power.

## 4.41 VIP Tier System

Supports client VIP interface, configurable tiers, Prime Shop-derived points and VIP-specific coin drops/ranges.

**Compatibility:** similar warning to Premium: production economics must understand any VIP-generated items or drop modifiers.

## 4.42 Walker / Bot Protection

Basic protection targeting L2Walker-style automation.

**Compatibility:** useful foundation but not sufficient for a high-value persistent server. The more valuable information, contracts and economy become, the more sophisticated automation will become.

## 4.43 Wedding / Marriage System

Engagement, marriage, wedding NPC, fees/formal-wear requirements, spouse teleport, divorce, infidelity punishment and optional same-sex marriage.

**Compatibility:** fully compatible. It is a social relation and can optionally become historical context in Wayfarer’s Memory. It should not generate world power by itself.

### 4.44 Inventory accounting note

The thread described **43 actual custom configuration modules**, while PC Cafe behavior was broken out conceptually as an additional feature family under Premium. This blueprint preserves that distinction instead of pretending there are 44 independent `.ini` modules.

---

# 5. Known Mobius completeness and retail-fidelity gaps

The server is a viable development foundation, but the design must never assume that “implemented” means “identical to NCSoft retail”. The thread identified several concrete categories of risk.

## 5.1 Explicitly missing quests

The discussed current Classic 3.0 The Kamael tree contains a `MissingQuests.txt` inventory with **23 missing quests**:

- 201
- 202
- 203
- 204
- 205
- 206
- 630 — Pirate Treasure Hunt
- 664 — Quarrels Time
- 910 — Request from the Red Libra Guild - Basic
- 911 — Intermediate
- 912 — Advanced
- 913 — Super Advanced
- 914 — Lv. 5
- 10861 — Monster Arena: The Birth of a Warrior
- 10862 — Challenge: 10 battles
- 10863 — New Challenge: 15 battles
- 10864 — Brave Warrior: 25 battles
- 10865 — Last Call: 40 battles
- 10867 — Gone Missing
- 10868 — The Dark Side of Power
- 10870 — Unfinished Device
- 10871 — Death to the Pirate King!
- 10960

A subset exists as `not_done` skeleton scripts rather than functioning content.

## 5.2 NPC AI is not automatically PTS-perfect

Mobius contains extensive AI systems, but recent corrections discussed in the thread show that subtle behavior continues to be reconstructed, including examples involving:

- Queen Ant minion spawning;
- summon death hate toward the master;
- territory-constrained wandering;
- retail-style spawn group selection;
- aggro delays;
- leash behavior;
- raid minion return behavior;
- Orfen-related aggro;
- raid minion respawn;
- `Skill0X` PTS parameter semantics.

The implication is not “Mobius AI is bad”. The implication is that **retail AI contains hidden semantics**, and correctness must be demonstrated case by case.

## 5.3 Formula and trigger edge cases

Potential divergence categories include:

- obscure skill trigger gates;
- proc probability;
- resistance interactions;
- NPC skill conditions;
- hate calculation;
- reuse conditions;
- summon interactions;
- item triggers;
- target-selection edge cases;
- exact chronological differences between retail revisions.

A feature can be visually “working” while mathematically or conditionally wrong.

## 5.4 Geodata/pathfinding

The discussed branch uses GeoEngine and runtime cell-based pathfinding, but pathfinding quality depends on both implementation and geodata quality.

Expected edge cases:

- mobs clipping or attempting wall paths;
- strange detours;
- line-of-sight oddities;
- stuck NPCs;
- geometry exploits;
- boss movement anomalies.

The custom systems must never use “path exists” as a social truth without tolerating GeoEngine failure.

## 5.5 Protocol/client lock

Classic 3.0 The Kamael expects the correct client/protocol family. A random current Classic client is not a supported drop-in replacement.

Our build/deployment documentation must version together:

- server commit;
- datapack commit;
- protocol;
- approved client/system build;
- geodata set;
- SQL schema version.

## 5.6 NCSoft operational infrastructure is not Mobius

Mobius reproduces the game server ecosystem, not the entirety of NCSoft operations.

Do not assume stock equivalents for:

- official account ecosystem;
- commercial billing platform;
- official launcher/update infrastructure;
- proprietary anti-cheat;
- proprietary fraud detection;
- NCSoft telemetry;
- customer-support tooling;
- proprietary internal GM workflows.

## 5.7 Anti-cheat is a production subsystem, not a checkbox

Stock captcha/Walker protection/dualbox checks are useful, but a serious persistent server with valuable economy and information will attract:

- packet bots;
- radar tools;
- market scraping;
- automated hunting;
- account networks;
- contract farming;
- bounty farming;
- location inference clients.

Therefore anti-abuse must be part of the architecture of each custom module.

## 5.8 Historical examples prove “data exists” != “behavior works”

The thread cited historical Kamael examples such as:

- Blessed Soulshots present but previously malfunctioning;
- Daily Mission dependencies where required mobs were not spawning correctly.

These are examples of the class of bug we must test, not necessarily assertions that those exact bugs remain current.

---

# 6. RETAIL TRUTH
## A correctness harness before custom innovation

**Mission:** establish an evidence-backed, continuously testable baseline for what Mobius does exactly, approximately, incorrectly, or not at all.

This is the safety rail for the whole product.

If a custom server becomes famous while core class mechanics are subtly wrong, the community will correctly treat every later design decision with suspicion. Therefore Retail Truth should start **before** deep custom development, even though it is not player-facing.

## 6.1 Core classification

Every tested behavior receives one of five statuses:

- **EXACT** — matches validated retail evidence within defined tolerances.
- **FUNCTIONAL** — feature works and is usable, but exact retail equivalence is not proven.
- **APPROXIMATE** — intentionally or currently differs in measurable behavior.
- **BROKEN** — implementation exists but fails expected behavior.
- **MISSING** — expected branch feature is absent.

A sixth internal state may be useful:

- **UNKNOWN** — no sufficient reference evidence exists yet.

Do not lie by upgrading UNKNOWN to EXACT merely because nobody complained.

## 6.2 Test record structure

Every Retail Truth case should contain:

```yaml
id: RT-COMBAT-0001
feature: physical_critical_damage
chronicle: classic_3_0_the_kamael
reference:
  evidence_type: retail_capture|pts_data|official_doc|trusted_archive|repro
  source: ...
preconditions:
  attacker: ...
  defender: ...
  buffs: ...
  equipment: ...
actions:
  - ...
expected:
  - ...
tolerance:
  - ...
observed:
  - ...
status: EXACT|FUNCTIONAL|APPROXIMATE|BROKEN|MISSING|UNKNOWN
server_commit: ...
datapack_commit: ...
notes: ...
```

## 6.3 Validation domains

Retail Truth must progressively cover:

### Combat

- P.Atk / M.Atk formulas;
- P.Def / M.Def;
- crit rates and crit damage;
- blow mechanics;
- shield behavior;
- cast/attack speed;
- accuracy/evasion;
- elemental/resistance logic applicable to the chronicle;
- PvP modifiers;
- death consequences.

### Skills

- learn conditions;
- costs;
- cooldown/reuse;
- targeting;
- land rate;
- triggers;
- cancellation;
- stacking;
- abnormal types;
- mastery;
- class-specific edge cases.

### NPC/AI

- aggro;
- hate transfer;
- clan help;
- leash;
- spawn makers/groups;
- minions;
- boss phases;
- skill-selection gates;
- territory behavior.

### Economy

- NPC buy/sell;
- spoil;
- drops;
- crafting;
- success/failure material behavior;
- private store behavior;
- Manor if applicable;
- taxes.

### Social/PvP systems

- clans;
- wars;
- karma/reputation;
- PK;
- siege;
- Olympiad;
- parties;
- loot rules.

### Content

- quests;
- instances;
- raids;
- bosses;
- daily systems;
- pets/summons;
- teleports;
- class progression.

### World simulation

- geodata;
- line of sight;
- pathfinding;
- spawn timing;
- zone boundaries.

## 6.4 Automation strategy

Retail Truth should use:

- JUnit-like server-side tests where possible;
- deterministic RNG seeding in test mode where safe;
- headless clients for protocol and interaction tests;
- Mobius fake players only when their behavior is equivalent enough for the scenario;
- admin instrumentation;
- database fixtures;
- packet captures where legally/technically available;
- repeated statistical trials for probabilistic mechanics.

Do not use one successful observation to validate a 30% probability.

## 6.5 Test environment isolation

Retail Truth requires a **clean retail profile**:

- custom balance multipliers off;
- champion mobs off;
- Premium reward multipliers off;
- custom auto-buffs off when they affect scenario;
- custom PvP rewards off;
- Aden Remembers consequences disabled/frozen;
- Black Ledger disabled;
- PACT disabled except when testing PACT;
- known fixed RNG seed where appropriate.

This avoids “custom server behavior” contaminating retail measurements.

## 6.6 Regression contract

No merge into production branch if it moves a validated case from:

- EXACT → anything lower;
- FUNCTIONAL → BROKEN/MISSING;
- fixed known defect → regression.

Any intentional departure requires an explicit `DESIGN_OVERRIDE` entry documenting:

- why;
- affected behavior;
- configuration switch;
- player-facing documentation.

This gives us something very rare in private-server land: **known truth instead of folklore**.

---

# 7. Shared architecture for all Living Chronicle modules

The custom architecture should be **event-driven, append-first, low-coupling and auditable**.

Do not patch every Mobius subsystem directly.

Preferred shape:

```text
Mobius authoritative systems
        │
        │ player/NPC/clan/item/siege/economy events
        ▼
Living Chronicle Event Adapter
        │
        ├──► append-only Event Journal
        │
        ├──► Aden Remembers projectors
        ├──► Wayfarer's Memory projectors
        ├──► Signal presence/intention service
        ├──► PACT clause evaluators
        ├──► Black Ledger trace engine
        └──► Retail Truth instrumentation (test environments)
```

## 7.1 Event envelope

All internal events should normalize into a shared envelope:

```json
{
  "eventId": "uuid",
  "eventType": "PLAYER_TELEPORTED",
  "occurredAt": "timestamp",
  "serverTick": 123456789,
  "actor": {
    "type": "PLAYER|FAKE_PLAYER|OFFLINE_PLAYER|NPC|SYSTEM",
    "id": 123,
    "accountScopeId": "internal-only"
  },
  "subject": {},
  "location": {
    "regionId": "OREN",
    "zoneId": 123,
    "x": 0,
    "y": 0,
    "z": 0
  },
  "context": {
    "partyId": null,
    "clanId": null,
    "instanceId": 0,
    "source": "mobius"
  },
  "payload": {}
}
```

Coordinates may be recorded server-side for authoritative processing, but most systems must **not expose them** to players.

## 7.2 Actor classification

Every event must know whether the actor is:

- interactive online human;
- offline auto-play character;
- offline store/crafter;
- fake player;
- NPC;
- system.

This single distinction prevents enormous analytical corruption.

Examples:

- Fake Player kills must not create legendary PvP history.
- Offline trader presence counts as market supply but not as “available party member”.
- Offline auto-play may affect ecological pressure but should be separately measurable from human activity.
- Black Ledger must not pay fake-player contracts.

## 7.3 Append-first philosophy

When practical, record a compact event before deriving consequences.

Benefits:

- replay;
- debugging;
- auditability;
- rebuilding derived state after algorithm changes;
- historical reconstruction;
- simulation testing.

Do not retain every high-frequency movement packet forever. Event Journal is **semantic**, not packet logging.

## 7.4 Time aggregation

High-volume events should be aggregated into buckets where exact event identity is unnecessary.

Example:

```text
region=DEATH_PASS
window=10min
mob_family=LETO_LIZARDMAN
kills=1832
unique_humans=41
offline_auto_kills=529
fake_kills=0
materials_out={...}
```

This keeps Aden Remembers cheap enough to run as a module rather than as a distributed-data vanity project.

## 7.5 Suggested packages

Illustrative structure:

```text
org.l2jmobius.gameserver.livingchronicle
├── core
│   ├── events
│   ├── identity
│   ├── time
│   ├── config
│   └── audit
├── adenremembers
├── signal
├── memory
├── pact
├── blackledger
└── telemetry
```

Retail Truth can live in test tooling rather than production packages:

```text
tools/retail-truth/
```

## 7.6 Config isolation

Each system receives its own configuration:

```text
config/Custom/AdenRemembers.ini
config/Custom/AdenSignal.ini
config/Custom/WayfarersMemory.ini
config/Custom/Pact.ini
config/Custom/BlackLedger.ini
```

A master switch should exist:

```ini
LivingChronicleEnabled = true
```

But each module must remain independently disableable for testing, rollback and compatibility.

---

# 8. ADEN REMEMBERS
## Persistent Living-World & Political-Economy Director

### 8.1 Product promise

> **Every other Lineage II server resets the world. On ours, the world remembers what you did.**

Aden Remembers turns ordinary Lineage II activity into persistent regional, political, economic and historical consequences.

It does not generate a parallel quest game. It interprets consequences of existing gameplay.

## 8.2 Core problem solved

Classic L2 content is largely solved by veteran communities:

- optimal zones are known;
- drops/spoil are known;
- boss timings are known;
- progression is mapped;
- class compositions are known;
- economy tends toward predictable end-state concentrations.

Long-running servers also develop a structural problem:

- low-level areas lose population;
- veterans concentrate at endgame;
- late joiners become socially irrelevant;
- old zones become dead geography;
- a wipe becomes the easiest way to recreate early-server chaos.

Aden Remembers aims to create **fresh context without deleting history**.

## 8.3 Regional state model

The world is divided into meaningful regions, for example:

- Gludio;
- Dion;
- Giran;
- Oren;
- Aden;
- Rune;
- Goddard;
- associated hunting areas and strategic subregions.

Each region maintains derived state such as:

- prosperity;
- supply health;
- security;
- trade activity;
- resource pressure;
- monster pressure;
- conflict intensity;
- crime/outlaw activity;
- war exhaustion;
- clan political influence;
- infrastructure health;
- NPC confidence/loyalty where appropriate;
- import/export dependency.

These are **internal model values**, not raw player-facing stats.

## 8.4 Input signals

Examples:

- monster kills by family/area;
- material generation;
- spoil generation;
- NPC sell flows;
- private store observations;
- crafting activity;
- teleport flows;
- player travel between regions;
- PvP and PK density;
- clan war activity;
- castle ownership;
- siege outcome;
- Manor activity;
- boss activity;
- deaths;
- new character activity;
- level distribution;
- warehouse/trade activity at safe aggregated level;
- contract activity;
- Signal demand patterns;
- Black Ledger intensity.

## 8.5 Resource pressure

If an area is farmed excessively, the system may gradually change its relative attractiveness.

Possible bounded outputs:

- minor redistribution of selected material availability;
- increased appearance of related monster families elsewhere;
- local scarcity signals;
- merchant demand changes;
- neighboring zone opportunity;
- NPC rumors about unusual supply.

### Hard balance rule

Aden Remembers should normally **redistribute within bounded global supply budgets**, not print arbitrary extra resources.

For each managed resource family define:

- baseline expected global supply;
- allowed regional variation;
- maximum temporal deviation;
- recovery/decay curve;
- protected retail drops that must not change;
- boss/quest exclusions.

This lets the geography breathe without turning the Director into a hidden admin changing rates.

## 8.6 Situations, not events

Traditional custom event:

```text
START → OBJECTIVE → REWARD → END
```

Aden Remembers situation:

```text
CAUSE → CONSEQUENCE → HUMAN REACTION → NEW CONSEQUENCE → HISTORY
```

There should be no giant UI saying:

> FARM EVENT ACTIVE 60 MINUTES

Instead, players infer the situation through the world.

## 8.7 Economic warfare

Castle taxes and regional behavior should affect economic geography.

Example:

1. Clan RedSky controls Aden.
2. Taxes become aggressive.
3. Commerce shifts toward Giran.
4. Aden trade activity falls.
5. Giran crafting activity rises.
6. Aden castle revenue weakens.
7. Another clan gains economic relevance without defeating RedSky in direct combat.

This creates economic warfare from ordinary player behavior.

## 8.8 NPC society reactions

Derived regional conditions can produce bounded, lore-compatible reactions such as:

- black-market/smuggling opportunities;
- merchants adjusting demand;
- guard presence changes;
- NPC caravan activity;
- local shortages;
- temporary merchant movement;
- local requests for existing low-level materials;
- bandit pressure;
- NPC militia/defense situations;
- regional rumors.

Do not create a theme-park quest chain every time a metric changes.

## 8.9 Boss ecology and pressure

Bosses remain recognizable retail entities. Do not randomly teleport iconic bosses around Aden for novelty.

Instead, surrounding world conditions may change:

- subordinate spawn pressure;
- scouting NPCs;
- environmental spread;
- local material demand;
- preparation activity;
- routes and risks around the boss region.

The boss remains Lineage II; the **context around it becomes alive**.

## 8.10 Political influence

Political influence is not “capture point = +10% damage”.

Possible contributors:

- castle ownership;
- trade volume;
- defense of regional infrastructure;
- clan presence;
- Manor participation;
- public contracts;
- successful caravans;
- local conflict;
- sustained economic activity.

Possible outputs:

- access to strategic information;
- lower/altered institutional fees where justified;
- NPC contractual opportunities;
- prestige;
- administrative options;
- vulnerability/responsibility expansion.

Avoid direct combat buffs.

## 8.11 Anti-zerg principle: complexity cost

Never solve dominant clans by giving small clans +50% damage.

Instead, empire scales its burdens:

- more routes;
- more assets;
- more simultaneous obligations;
- higher visibility;
- more opportunities for sabotage;
- greater administrative cost;
- more regions with unrest;
- increased need for allies/proxies.

A giant alliance can dominate, but domination should require **organization**, not merely population.

## 8.12 Catch-in, not catch-up

Do not give late joiners free high-grade gear merely because the server is old.

Create demand for what low-level players can genuinely produce.

Example:

- Gludio region enters a material shortage;
- demand rises for animal skins, iron ore, coal, stems or another existing low-level resource;
- veterans need supply;
- low-level/new players can participate economically;
- veteran wealth flows downward through trade instead of server-generated welfare.

The newcomer is not pitied. The newcomer is **useful**.

## 8.13 Imperfect information and rumors

Aden Remembers never exposes the full model.

Player-facing evidence channels:

- NPC dialogue;
- merchant pricing/demand;
- guard remarks;
- caravans;
- private shop behavior;
- public Chronicle;
- player observation;
- Wayfarer’s Memory;
- Signal demand patterns;
- rumors with provenance/uncertainty.

Information itself becomes valuable.

## 8.14 Historical graph

The server records durable historical facts such as:

- clan castle reigns;
- notable sieges;
- first major milestones;
- clan wars;
- politically important betrayals when mechanically observable;
- major Black Ledger hunts;
- notable crafters and item provenance where configured;
- economic eras;
- long-lived regional dominance;
- famous PK/outlaw activity;
- major public contracts.

### Important filtering

Not every event deserves immortality.

History should promote events based on thresholds such as:

- scale;
- rarity;
- duration;
- value;
- number of unique participants;
- political consequence;
- first/record status.

## 8.15 Chronicle of Aden

Public history surface may exist in-game and on the website.

Possible views:

- current political map;
- castle dynasties;
- historically important battles;
- famous crafters/items;
- public treaties;
- long-lived wars;
- regional story summaries;
- notorious outlaws;
- major assassinations;
- economic shifts.

Do not expose hidden actionable state.

The public Chronicle is **story**, not a cheat dashboard.

## 8.16 Website effect

A public Living Chronicle turns gameplay into externally watchable narrative.

People who are not logged in can follow:

- political change;
- war;
- castle history;
- public contract activity;
- major economic movement;
- legendary hunts.

This creates organic media material from player behavior instead of requiring constant GM-authored “content drops”.

## 8.17 Suggested Aden Remembers data model

Illustrative tables/entities:

```text
lc_event_journal
lc_region
lc_region_state
lc_region_metric_bucket
lc_resource_pressure
lc_trade_flow
lc_clan_influence
lc_world_situation
lc_world_situation_state
lc_rumor
lc_history_event
lc_history_participant
lc_historical_record
lc_item_provenance
```

## 8.18 Director cadence

Separate real-time reactions from strategic simulation.

Suggested layers:

- **immediate:** record semantic events;
- **1–5 min:** aggregate telemetry;
- **10–30 min:** update short-term pressure;
- **hourly:** evaluate situations;
- **daily:** decay long-term state, promote history, recalculate slow economic/political state;
- **weekly:** derive eras/records and archival summaries.

Do not recompute the entire world every game tick.

## 8.19 Safety bounds

Every dynamic actuator must have:

- minimum;
- maximum;
- rate of change;
- recovery rate;
- excluded zones/NPCs/items;
- kill switch;
- audit trail;
- reason code.

No Director output should be impossible to explain after the fact.

---

# 9. ADEN SIGNAL
## Intent Network: find the people you need without making them unnecessary

### 9.1 Problem

Lineage II deliberately creates complementary roles, but its traditional tools are poor at discovering compatible humans efficiently.

The wrong solution is to make solo play so powerful that dependency disappears.

Aden Signal solves:

> **The compatible person exists, but the game fails to introduce you.**

### 9.2 What Signal is

Signal is an **intent network**, not an automatic dungeon finder.

A player may express or opt into intents such as:

- XP;
- spoil;
- quest;
- raid;
- PvP;
- Manor;
- crafting;
- material hunting;
- support-for-hire;
- exploration;
- specific item acquisition.

A Signal includes structured dimensions:

- class/role;
- level/range;
- current region;
- preferred regions;
- objective;
- expected remaining play time;
- travel tolerance;
- language;
- desired pace;
- willingness to join an existing party;
- willingness to lead;
- schedule now/later;
- optional notes.

Example:

```text
Nalyk
Lv. 47 Shillien Elder
Intent: XP
Preferred: Cruma / nearby
Available: ~55 min
Travel tolerance: 10 min
Languages: RO / RU / EN
Pace: relaxed
Join existing party: yes
```

### 9.3 Time is a first-class compatibility dimension

Signal explicitly models expected session duration:

- 20–30 minutes;
- ~1 hour;
- 2–3 hours;
- until boss;
- evening session;
- custom end time.

This avoids a major social mismatch: players with compatible classes but incompatible commitments.

No punishment for leaving at the declared time. The purpose is **expectation alignment**, not enforcement.

### 9.4 Latent party detection

When participation is enabled, Signal may notice several nearby players with mutually compatible goals even when nobody opened Party Matching.

Example:

```text
A viable party may be formed nearby.
6 compatible players
Estimated rendezvous: 7 minutes
Common intent: XP / spoil
```

Names need not be revealed until mutual interest is expressed.

This is one of Signal’s defining features:

> Party Finder asks who advertised. Signal asks which humans currently want compatible things.

### 9.5 Future intent

Players can declare future play intent:

```text
Tomorrow 20:30–22:00
Cruma XP/spoil
```

Other players can align with the session.

At login or shortly before the time, Signal may say:

```text
5 players aligned with your planned Cruma session.
```

Still no matchmaking teleport. Players must meet inside the world.

### 9.6 No balance advantage

Signal creates:

- zero XP;
- zero Adena;
- zero items;
- zero buffs;
- zero teleportation;
- zero combat modifiers.

It changes only the probability that compatible humans discover one another.

### 9.7 Support-class protection

Signal must never solve “we cannot find a buffer” by supplying an NPC equivalent automatically.

Its philosophy is:

> **Do not make support classes unnecessary. Make support players discoverable.**

This is especially important if stock Scheme Buffer or custom Community Board buffs are enabled. Server policy should avoid configuring those systems so generously that Signal becomes irrelevant.

### 9.8 Privacy and opt-in

Recommended design:

- explicit player opt-in to Signal presence;
- fine-grained visibility;
- never expose exact coordinates;
- region-level location sufficient for discovery;
- offline traders excluded;
- fake players excluded unless running a special test profile;
- offline auto-play excluded from human matching;
- block/ignore rules respected.

### 9.9 Signal interfaces

Possible surfaces:

- `/signal` command;
- Community Board tab;
- existing Party Matching augmentation;
- small system-message notifications;
- optional planner for future intents.

Do not require a special NPC for ordinary use; the value is low-friction coordination.

### 9.10 Suggested data model

```text
signal_profile
signal_intent
signal_intent_role
signal_schedule
signal_match_candidate
signal_mutual_interest
signal_session
signal_block
```

Most current intents can be memory/cache-first and persisted only where needed for future sessions/history.

### 9.11 Matching score

The matching algorithm may score:

- objective compatibility;
- level compatibility;
- role complementarity;
- travel cost;
- remaining time overlap;
- language overlap;
- pace;
- social exclusions;
- party capacity.

Do not optimize solely for mathematically perfect meta compositions. The system should introduce viable humans, not turn the server into a raid optimizer.

### 9.12 Success criteria

Measure:

- time from intent to first compatible contact;
- party formation conversion;
- average travel-to-party time;
- low-level party formation on mature server;
- support-class match rate;
- repeated social pairings;
- percentage of Signal groups that continue voluntarily after first session.

Never reward usage directly with XP/items. Usage must be valuable because the humans are valuable.

---

# 10. WAYFARER'S MEMORY
## Private character memory and personal knowledge graph

### 10.1 Problem

Lineage II asks humans to remember long-running, fragmented progression:

- what they were farming;
- which recipe they needed;
- who sold an item;
- which NPC mattered;
- where they last saw a resource;
- what a friend told them;
- which project they abandoned three days ago.

The wrong solution is to tell them the globally optimal next step.

Wayfarer’s Memory solves:

> **The game remembers what your character actually knew and was doing.**

### 10.2 Core distinction

Aden Remembers answers:

> What happened to the world?

Wayfarer’s Memory answers:

> What happened to me, and what do I know?

### 10.3 Session continuity

Example return summary:

```text
Last journey

You were preparing a Blue Wolf Heavy Set.
Missing:
- 18 Crafted Leather
- 7 Mold Hardener
- BW Helmet recipe

You last observed Mold Hardener sold by IronDwarf for 78,000 Adena.
IronDwarf is currently offline.

You were hunting near Cemetery before returning to Giran.

Personal note:
"Ask Kira about BW helmet recipe."
```

No item is generated. No optimal answer is provided. The player simply regains context.

### 10.4 Personal knowledge graph

Potential remembered entity types:

- NPCs;
- monsters;
- regions;
- materials;
- recipes;
- equipment;
- raid bosses;
- shops;
- players;
- clans;
- personal projects;
- quests;
- contracts;
- rumors;
- notable deaths/battles.

Possible relationships:

```text
PLAYER observed ITEM sold_by PLAYER at TOWN at TIME for PRICE
PLAYER obtained ITEM from MONSTER at REGION
PLAYER was_told FACT by PLAYER
PLAYER met PLAYER at REGION
PLAYER needs ITEM for PROJECT
PLAYER witnessed BOSS at REGION
PLAYER signed CONTRACT with PLAYER
```

### 10.5 Knowledge provenance

This is a defining design rule.

Every piece of actionable knowledge has provenance such as:

#### KNOWN
Personally witnessed or mechanically experienced.

#### TOLD
Shared by another player.

#### RUMORED
Received from an NPC/world rumor source.

Additional internal labels may include:

- inferred;
- historical;
- stale;
- contradicted.

The UI should never silently merge rumor into fact.

### 10.6 Memory can become stale

A personal observation is time-stamped.

Example:

```text
Mold Hardener
Personally observed:
- Timak Orc Archer, 6 days ago
Told by Vasya:
- Giant's Cave, 2 days ago
Rumor:
- merchants report increased supply near Oren, today
```

If Aden Remembers changed regional conditions, old knowledge remains old knowledge.

This preserves mystery.

### 10.7 No global omniscient wiki

Forbidden queries:

```text
.bestfarm
where is the best SOP right now?
what is the optimal XP route?
```

Allowed questions are grounded in the character’s actual knowledge:

```text
Where did I last obtain SOP?
Who did I see selling Mold Hardener?
Which NPC did I talk to for that quest?
What was I crafting last session?
```

### 10.8 Shareable journal pages

Players may voluntarily share a subset of their knowledge.

Example:

> “Here are the mobs I personally used for D-grade spoil.”

A shared page retains provenance:

```text
Shared by: VeteranName
Based on personal observations between dates X–Y
```

This makes mentorship mechanically easy without replacing discovery.

### 10.9 Personal history

Over years, a character may accumulate a private autobiography:

- places visited;
- bosses witnessed;
- old clans;
- old enemies;
- important contracts;
- significant crafted items;
- memorable PvP;
- friends encountered repeatedly;
- historical sieges attended;
- personal economic milestones.

Not every event appears in the UI. Promote meaningful summaries.

This gives persistent characters emotional weight that a level/gear snapshot cannot provide.

### 10.10 Interfaces

Potential interfaces:

- `/memory` or `/journal`;
- Community Board journal;
- physical “Wayfarer’s Journal” item that opens UI;
- login recap;
- contextual “remember” action;
- personal notes.

### 10.11 Suggested data model

```text
memory_entity
memory_observation
memory_relation
memory_project
memory_project_requirement
memory_note
memory_shared_page
memory_shared_fact
memory_session_summary
memory_personal_milestone
```

### 10.12 Retention policy

Do not persist meaningless noise forever.

Use tiers:

- short-lived observations;
- summarized observations;
- durable user notes;
- durable milestones;
- user-pinned facts.

Example: 400 identical mob kills become “obtained X from mob family Y repeatedly”, not 400 UI entries.

### 10.13 Relationship with Black Ledger

Wayfarer’s Memory must never leak privileged Black Ledger intelligence automatically.

If the player legitimately learns a fact through Black Ledger, the journal may remember:

- the fact;
- its provenance;
- expiry/staleness.

It must not turn a stale hunt into permanent GPS.

---

# 11. PACT
## Voluntary enforceable contracts between players

### 11.1 Problem

Lineage II relies heavily on trust for transactions whose intended terms are mechanically understandable:

- crafting with expensive materials;
- purchasing large quantities;
- paying mercenaries;
- distributing raid loot;
- loans;
- delivery arrangements;
- service payments.

The wrong solution is to make social betrayal impossible.

PACT distinguishes:

- **mechanically explicit agreements that players want enforced**;
- **human loyalty and politics that must remain risky**.

### 11.2 Prime rule

> **Freedom is the default. Contract is optional certainty.**

If players transact manually, ordinary risk remains.

PACT protects only an agreement that all required parties explicitly sign.

### 11.3 Machine-verifiable clauses only

PACT may enforce:

- item IDs/quantities;
- Adena amounts;
- item transfer;
- crafting attempt;
- resulting item ownership;
- deadline;
- siege registration;
- measurable siege participation;
- party membership;
- boss kill participation;
- delivery of specified items;
- contract cancellation conditions;
- payment release.

PACT must not judge:

- whether somebody “tried hard enough”;
- whether a healer healed well;
- whether a clan was loyal;
- whether somebody was rude;
- whether an alliance morally betrayed another;
- subjective quality of service.

### 11.4 Crafting Pact

Example:

```text
Client: Nalyk
Crafter: Blacksmith
Recipe: Doom Plate Armor
Materials: supplied by client
Craft fee: 400,000 Adena
Attempts: 1
Result owner: Nalyk
Deadline: 24h
Normal retail craft chance: unchanged
```

Assets enter escrow only after terms are agreed according to contract lifecycle.

If normal craft fails:

- materials are consumed according to normal rules;
- no item is magically created;
- agreed fee settlement follows contract terms;
- contract records “attempt completed; outcome unsuccessful”.

PACT guarantees **behavior**, not success.

### 11.5 Party / Raid Loot Charter

Before a raid or party, members can explicitly agree to one of several machine-supported loot policies:

- normal random;
- finders keepers;
- leader distribution;
- specific item reservation;
- post-kill auction;
- configured deterministic split.

Once the relevant activity begins, the charter cannot be silently replaced.

Players remain free to refuse to sign and operate normally.

### 11.6 Purchase contract

Example:

```text
BUY 4,000 Coal
Price: 210 Adena each
Max spend escrowed
Expiry: Friday 22:00
Partial fulfillment: yes
```

Sellers can fulfil some or all of the order.

No Adena is created. Buyer deposits the funds.

### 11.7 Craft order

Example:

```text
Need 20 Crafted Leather
Materials provided: yes
Fee: 120,000 Adena
Partial batches: allowed
```

### 11.8 Delivery order

Example:

```text
Deliver 250 Iron Ore
Payment: X
Deadline: Y
```

The system verifies possession/transfer, not the roleplay quality of the journey.

### 11.9 Loan agreement

Possible scope:

- item loan;
- Adena loan;
- collateral;
- expiry;
- voluntary return terms.

This requires careful design because forced removal from a player’s possession can interact badly with trade, destruction, enchantment or account states.

Recommended first release: support **escrow-backed/collateralized** loans only. Do not invent supernatural debt collection until lifecycle rules are proven.

### 11.10 Mercenary contract

Clan A hires Clan B to defend Giran.

Example clauses:

```text
Employer: Clan A
Mercenary: Clan B
Reward: 40,000,000 Adena
Requirement:
- registered defender
- at least 12 qualifying members
- at least 45 minutes measurable participation
Settlement: after siege completion
```

This can create a genuine mercenary profession without combat buffs.

### 11.11 Public versus sealed contracts

Contracts can have visibility policies:

- private;
- parties visible, terms sealed;
- fully public;
- historically publishable after completion.

A public political contract can itself become information:

> Clan A and Clan B registered an agreement.

Even if terms are sealed, society may speculate.

### 11.12 Objective reputation only

PACT must never implement Yelp stars.

Allowed objective history:

```text
143 contracts completed
3 mutually cancelled
0 mechanical defaults
87 contracted craft attempts
12 success / 75 normal failure
```

Forbidden:

```text
Trustworthiness: 87.4%
★★★★★ nice guy
```

The server records facts, not virtue.

### 11.13 What PACT must never protect

These remain socially risky unless explicitly contracted:

- verbal alliance promises;
- secret farming-location disclosure;
- clan treasury access;
- manual weapon lending;
- political loyalty;
- information leaks;
- promises not to attack.

This is essential. Lineage needs betrayal to remain possible.

---

# 12. NOTARIUS
## The in-world institution that gives PACT a soul

### 12.1 Why an NPC is important

PACT could technically be a menu, but a **Notarius** makes it belong to Aden.

The NPC transforms a backend escrow engine into an institution players recognize, visit and talk about.

The Notarius is:

- notary;
- escrow registrar;
- contract archive interface;
- settlement authority for machine-verifiable agreements.

The Notarius is **not**:

- a judge of morality;
- an AI courtroom;
- a buffer;
- a teleport NPC;
- a generic shop.

### 12.2 Notarius tiers

#### Town Notarius

Handles:

- crafting pacts;
- purchases;
- deliveries;
- ordinary loans;
- party/loot charters where ceremony is wanted.

#### Royal Notarius

Located in major centers such as Aden/Giran/Rune.

Handles:

- high-value agreements;
- clan agreements;
- large commercial contracts;
- public/sealed political instruments.

#### Castle Notarius

Handles:

- mercenary agreements;
- siege-related contracts;
- clan-level obligations;
- castle-related public records.

### 12.3 Physical contract item

PACT can represent a signed agreement with a non-tradable parchment-style item:

```text
Notarized Contract #18427
```

Double-click opens authoritative terms.

Possible visual types:

- Crafting Agreement;
- Purchase Order;
- Loan Agreement;
- Raid Charter;
- Mercenary Contract.

These are representations, not currencies.

### 12.4 Remote drafting, physical high-value signing

To avoid pointless inconvenience:

- ordinary contracts may be drafted remotely;
- counterpart receives a proposal;
- high-value or clan contracts may require final physical confirmation at a Notarius;
- thresholds are configurable.

The physical ceremony gives important agreements weight.

### 12.5 Public registration

For selected public contracts, the Notarius may announce a restrained fact:

```text
A commercial agreement between BlackForge and Nightfall has been registered.
```

Terms may remain sealed.

This creates political information without exposing private contents.

### 12.6 Aden Remembers integration

Historically significant public contracts may later appear in the Chronicle:

```text
The Treaty of Giran
Registered between Eclipse and Vanguard
Date: ...
Outcome: ...
```

The server can remember the agreement because it was a real mechanical act, not because an AI inferred political motives.

### 12.7 Contract lifecycle

Recommended state machine:

```text
DRAFT
  ↓
PROPOSED
  ↓
PARTIALLY_SIGNED
  ↓
FUNDED / ASSETS_ESCROWED
  ↓
ACTIVE
  ├──► COMPLETED
  ├──► EXPIRED
  ├──► MUTUALLY_CANCELLED
  ├──► FAILED_BY_CONDITION
  └──► ADMIN_REVIEW (only exceptional corruption/bug path)
```

### 12.8 Escrow rules

Escrow must be authoritative and transactional.

Requirements:

- atomic DB transaction where possible;
- no duplicate settlement;
- idempotent completion handler;
- item object IDs tracked when uniqueness matters;
- stack quantities tracked exactly;
- no asset exists simultaneously in player inventory and escrow;
- settlement survives restart;
- audit ledger immutable except corrective admin operation with reason.

### 12.9 Suggested PACT data model

```text
pact_contract
pact_party
pact_signature
pact_clause
pact_asset
pact_escrow_item
pact_escrow_currency
pact_event
pact_settlement
pact_public_registry
pact_objective_history
```

### 12.10 PACT API concept

```java
interface PactClauseEvaluator {
    ClauseResult evaluate(PactContext context);
}

interface PactSettlementAction {
    void settle(PactContext context);
}
```

Each contract type composes known clauses rather than embedding giant `if` chains.

---

# 13. THE BLACK LEDGER
## Assassination, head-hunting, investigation and counter-intelligence

### 13.1 Product promise

> **There is a price on your head. Nobody knows where you are. Yet.**

The Black Ledger must not be a “bounty board with coordinates”.

The revolutionary idea is:

> **An assassin’s primary profession is information. The kill is the final act.**

Lineage II already contains the violence, PK consequences, geography and open-world danger required for assassination. The missing gameplay is the **hunt**.

### 13.2 Sacred balance rule

Black Ledger provides:

- **0 P.Atk**;
- **0 M.Atk**;
- **0 attack speed**;
- **0 movement speed**;
- **0 crit**;
- **0 blow chance**;
- **0 stealth buff**;
- **0 target debuff**;
- **0 assassination teleport**;
- **0 combat privilege**.

A hunter who reaches the target fights using normal Lineage II mechanics.

If the target is lawful/white and ordinary PK rules say the kill creates karma/reputation consequences, those consequences remain.

The contract pays because the assassin accepts the danger and consequences. It does not legalize murder magically.

### 13.3 Two legal faces

#### The Writ

A lawful head-hunting instrument.

Potential targets include cases where the existing game rules already make hunting legally/socially recognized, for example:

- qualifying chaotic/red characters;
- appropriate clan-war enemies;
- other server-defined lawful warrant conditions that preserve the branch’s PK philosophy.

Institution:

- Magistrate;
- Guard Captain;
- lawful bounty office.

#### The Black Contract

Murder-for-hire outside lawful recognition.

Institution:

- The Broker;
- Shadow Broker;
- underground network.

Notarius must **not** process Black Contracts.

The institutional symmetry is important:

> **Notarius protects promises society recognizes.**

> **Magistrate administers the law.**

> **The Broker enforces promises society must never see.**

### 13.4 Initial dossier

An accepted contract provides identity-level facts but not live coordinates.

Example:

```text
BLACK CONTRACT #8821
Mark: Nalyk
Race: Dark Elf
Approximate level: 56–60
Clan: Nightfall
Contract value: 12,000,000 Adena
Last confirmed activity: 2h 17m ago
Current location: UNKNOWN
```

Depending on anonymity rules, some identity fields may be reduced.

### 13.5 Traces

Players naturally leave sparse semantic traces through normal actions.

Potential trace-producing events:

- Gatekeeper use;
- selected NPC interactions;
- warehouse visits;
- merchant interactions;
- PvP/PK events;
- death;
- regional mob activity at safe aggregation level;
- town/zone transitions;
- public siege participation;
- public contract activity;
- player witness encounters.

A trace is not necessarily visible to every hunter.

### 13.6 NPC witnesses

Existing NPCs become potential information sources.

#### Gatekeepers

May know whether a target used their service recently and, at higher information access, a destination category/town.

Example:

```text
"The name is familiar. He used my services earlier today."
```

With stronger verified access:

```text
"He travelled toward Oren."
```

Never provide current target coordinates.

#### Warehouse Keepers

May confirm a recent visit.

They never reveal private warehouse contents.

#### Merchants

May remember recent commercial interaction.

They need not reveal purchase details unless the design explicitly allows narrow, fair clues.

#### Guards

May remember recently observed characters in a coarse direction/area.

#### Clan Hall NPCs

May know membership/activity, but their willingness to disclose should reflect ownership, politics or configured privacy.

#### Tavern / information NPCs

May aggregate rumors such as:

```text
"Hunters say Nightfall members have been seen around Ivory Tower."
```

### 13.7 Information decay

Trace age matters.

Suggested semantic stages:

- **CONFIRMED / HOT**;
- **RECENT**;
- **STALE**;
- expired.

Exact timing depends on trace type.

Example:

```text
Target used Oren Gatekeeper 14 minutes ago
```

is valuable.

The same fact three days later is almost useless and should normally disappear from active investigation.

### 13.8 No offline omniscience

Do not generate “target is offline at coordinates X”.

When the player logs out, fresh movement traces stop.

The hunter may know:

- last activity was some time ago;
- current trail has gone cold.

That is enough.

### 13.9 Player informants

Any ordinary player may become economically relevant by possessing information.

If a player legitimately encountered the mark recently, they may offer a **verified sighting** through the Broker/information network.

The server verifies that an encounter actually occurred within the allowed semantics.

The informant can be paid from a **player-funded intelligence budget** attached to the contract.

Returned information remains coarse:

```text
Verified player sighting
Region: Oren territory
Age: approximately 23 minutes
```

No coordinates.

This creates a new profession:

> **Informant.**

A low-level player can possess information valuable to high-level politics.

### 13.10 Information budget

A Black Contract can separate:

- assassination reward;
- intelligence budget;
- broker fees;
- anonymity fee;
- optional calling-card/identity services.

All money is player-funded.

Example:

```text
Contract principal: 20,000,000
Intel budget: 3,000,000
Broker fee: 500,000
Potential assassin payout: 19,500,000
```

Exact accounting must be transparent to the depositor, though not necessarily to the mark.

### 13.11 Counter-intelligence

The target is also a player in this system.

Possible warning mechanisms:

- repeated inquiries eventually create an intuition message;
- friendly NPC mentions a stranger asking questions;
- clan intelligence observes information purchases in controlled territory;
- a failed assassin carries evidence.

Example:

```text
You feel watched.
```

The message must be probabilistic/thresholded enough to create tension without becoming a perfect proximity alarm.

### 13.12 Trail manipulation through ordinary movement

Because Gatekeeper use leaves traces, the target can choose different logistics:

```text
Giran → Gatekeeper → Oren
```

creates a different trail from:

```text
Giran → Gatekeeper → Hunters Village → leave manually on foot
```

This turns geography into counter-intelligence.

### 13.13 Bribing witnesses

Some NPC witness classes may be bribable to suppress or degrade disclosure.

Rules:

- not every NPC accepts;
- suppression is time-bounded;
- suppression cannot erase server audit history;
- political control may influence willingness;
- cost scales with information sensitivity and target notoriety;
- the hunter may detect that a witness is evasive without learning why.

A lawful official might reject a bribe. A corrupt merchant may happily forget.

### 13.14 False trails

Experienced outlaws/targets may purchase a controlled misinformation service.

Important distinction:

- server must label it **RUMOR**, not fact;
- false trail must be plausible;
- it must not fabricate impossible server events into authoritative history.

Example:

```text
Unverified rumor:
Nalyk may have been seen travelling toward Rune.
```

This creates human interpretation rather than a puzzle with a deterministic solution.

### 13.15 Intelligence confidence classes

Recommended player-visible classes:

#### CONFIRMED
Direct authoritative evidence that the relevant event occurred.

#### WITNESS
Server-verified player encounter.

#### INFERRED
Derived from several valid clues.

#### RUMOR
Possibly true, possibly manipulated.

#### STALE
Previously meaningful but now too old for confidence.

Wayfarer’s Memory may remember these with provenance but must retain the confidence class.

### 13.16 Hunter progression is information progression

Proposed career ladder:

1. **Listener** — basic rumor access.
2. **Tracker** — improved witness interrogation.
3. **Stalker** — can correlate multiple clues better.
4. **Head Hunter** — access to high-value warrants/contracts.
5. **Ghost** — can investigate older/sealed traces within strict bounds.

Unlocks improve:

- precision of time windows;
- quality of witness queries;
- number/type of evidence sources;
- ability to correlate clues;
- contract eligibility;
- alias/calling-card tools.

They do **not** improve combat stats.

### 13.17 Contract conditions

Only machine-verifiable conditions are permitted.

Examples:

- kill target once;
- kill target before deadline;
- kill target outside siege;
- kill target while target belongs to specified clan;
- achieve N successful contract kills over defined period;
- kill within a specified territory for a “public execution” statement.

Be extremely cautious with multi-kill contracts because they can encourage harassment/farming. Apply cooldowns, target protections and abuse rules.

### 13.18 Public execution

A contract may require the death to occur in a named broad territory.

Purpose is political messaging, not bonus damage.

Example:

> Target must die in Giran territory.

This can create risk because the hunter must solve both finding and location conditions.

### 13.19 Target victory and reverse investigation

If the mark kills the assassin, the hunt should produce consequences.

Possible evidence drop:

```text
Torn Black Seal
Contract value band: 25–50m
Origin: Giran network
Sponsor identity: sealed
```

The target does not automatically receive the client’s name.

They may begin a **reverse investigation**:

```text
Victim
  ↓
failed assassin
  ↓
contract trace
  ↓
broker network
  ↓
informants / financial clues
  ↓
possible patron
```

If the patron is eventually discovered through valid mechanics, the result can trigger real social consequences and possibly clan war.

### 13.20 Calling cards

After a successful contract, a hunter may leave a non-power cosmetic/signature mark:

- black feather;
- broken silver coin;
- blood-red card;
- selected symbol.

Target sees something like:

```text
A black feather was left beside your body.
```

No stat effect.

The community may learn to recognize the signature.

### 13.21 Nom de guerre

A hunter may establish a persistent Black Ledger alias:

```text
Black Feather
```

The system may reveal the alias to:

- contract client;
- victim through calling card;
- hunter network;
- Chronicle for legendary cases.

It does not automatically reveal:

```text
Black Feather = CharacterName
```

Other players can investigate and potentially discover the mapping.

This creates an assassin identity separate from ordinary public character reputation.

### 13.22 Selling assassin identity intelligence

If another player legitimately obtains evidence linking an alias to a character, that information can become valuable.

The Broker can accept a verified intelligence offer with a confidence rating derived from server evidence.

Never certify random gossip as truth.

Once identity leaks, the hunter becomes a target of counter-hunting and politics.

### 13.23 PK legends and public bounties

For notorious chaotic players, lawful/public bounty totals can become a status object:

```text
TOTAL PUBLIC BOUNTY: 187,000,000 Adena
```

This should be entirely funded by players or configured lawful sources that do not inflate currency.

Being notorious becomes dangerous because humans want the reward.

### 13.24 Economic conservation and Adena sink

The Black Ledger must mint **zero Adena**.

Example:

```text
Player deposits: 10,000,000
Hunter receives on success: 9,500,000
Broker fee destroyed: 500,000
New Adena created: 0
```

Other potential sinks:

- witness bribes;
- broker registration;
- sealed identity;
- intelligence purchases;
- counter-intelligence;
- alias services.

The more murderous the society becomes, the more Adena can be removed from circulation.

### 13.25 Anti-abuse

No reward or heavily restricted reward for suspicious relationships such as:

- same account;
- same known machine/HWID signal where legally/technically available;
- suspicious same-IP patterns;
- current party;
- immediate reciprocal contracts;
- repeated pair farming;
- fake players;
- controlled test actors;
- event/Olympiad contexts;
- newbie/protected characters below policy thresholds;
- obvious level/power trivialization.

Use multi-signal risk scoring internally, but never expose real-world identity data to players.

### 13.26 Anti-harassment design

Black Ledger must remain dangerous without becoming a tool for infinite targeted griefing.

Safeguards may include:

- minimum meaningful contract value;
- contract cooldown after successful execution;
- diminishing eligibility for repeated same-target contracts;
- limits for extreme level gaps;
- newbie protection;
- no permanent GPS;
- no offline target farming;
- server policy on repeated multi-kill contracts;
- explicit logging/admin audit for abuse investigation.

Do not make high-level political figures immune merely because they dislike being hunted. Protection exists for abuse, not importance.

### 13.27 Aden Remembers integration

Most contracts are transient crime/commerce and never enter permanent history.

Historically significant hunts may be promoted based on:

- exceptional bounty value;
- number of failed hunters;
- political significance;
- target notoriety;
- duration of hunt;
- clan-war consequence;
- record status.

Example Chronicle entry:

```text
The Giran Knives
Seven contracts were placed on Nightfall leadership.
Four hunters failed.
Two contracts were abandoned.
Ghost completed the final 84,000,000 Adena contract outside Oren.
```

The historical record states observable facts, not invented motives.

### 13.28 Suggested data model

```text
black_contract
black_contract_condition
black_contract_funding
black_warrant
black_hunter_profile
black_hunter_alias
black_calling_card
black_trace
black_trace_access
black_npc_witness_state
black_intel_offer
black_intel_purchase
black_counterintel_action
black_reverse_investigation
black_broker_fee
black_abuse_signal
```

### 13.29 Trace retention architecture

Do not store a permanent raw surveillance history for every player.

Recommended lifecycle:

1. semantic event occurs;
2. only eligible trace types create short-lived trace records;
3. trace has TTL and sensitivity class;
4. expired operational traces are deleted/aggregated;
5. only historically significant public facts survive via Aden Remembers.

This serves gameplay design and keeps storage/privacy semantics sane.

### 13.30 Black Ledger user journey

A full hunt should be capable of looking like:

```text
Accept contract
   ↓
Read sparse dossier
   ↓
Choose first likely information source
   ↓
Travel to witness
   ↓
Buy/bribe/investigate clue
   ↓
Determine trail direction
   ↓
Purchase player sighting or infer movement
   ↓
Target receives possible counter-intel warning
   ↓
Target changes travel pattern
   ↓
Trail goes cold / hunter reacquires
   ↓
Hunter physically finds target
   ↓
Normal L2 combat
   ├── hunter wins → settlement / signature / consequences
   └── target wins → evidence / reverse hunt / consequences
```

If this loop is successful, forty minutes with almost no combat can still feel like continuous PvP because **both humans are making adversarial decisions**.

---

# 14. How the systems fit together

The systems are deliberately complementary rather than overlapping.

```text
                         RETAIL TRUTH
                   verifies the foundation
                              │
                              ▼
                    NORMAL MOBIUS / L2
                              │
            ┌─────────────────┼──────────────────┐
            │                 │                  │
            ▼                 ▼                  ▼
     ADEN REMEMBERS      ADEN SIGNAL      WAYFARER'S MEMORY
      world memory       coordination       personal memory
            │                 │                  │
            └────────────┬────┴──────────────┬───┘
                         │                   │
                         ▼                   ▼
                       PACT            BLACK LEDGER
                    institutions       secrets / hunts
                         │                   │
                         └──────────┬────────┘
                                    ▼
                         NORMAL L2 CONSEQUENCES
                                    │
                                    ▼
                              ADEN REMEMBERS
```

## 14.1 Aden Remembers + Signal

Aden Remembers may observe aggregate demand for certain roles/activities, but must not manipulate Signal matches to push players into Director goals without disclosure.

Signal can reveal social pressure indirectly:

> Many players are currently seeking spoilers around Oren.

This is still player-generated demand.

## 14.2 Aden Remembers + Wayfarer’s Memory

World changes can make personal knowledge stale.

Wayfarer remembers what the player knew; Aden Remembers changes what is currently true.

That tension creates exploration.

## 14.3 Aden Remembers + PACT

Major public contracts, mercenary agreements and treaties can become historical facts.

PACT gives Aden Remembers high-confidence structured evidence instead of asking an AI to infer politics from chat.

## 14.4 Aden Remembers + Black Ledger

Large assassination campaigns, notorious public bounties and politically consequential murders may become history.

Aden Remembers can also influence witness behavior through regional politics, but must not directly reveal targets.

## 14.5 Signal + Wayfarer’s Memory

Memory can note recurring successful collaborators:

> You previously hunted with X three times.

Signal may optionally use explicit player preference/history as a soft compatibility signal, never as an opaque social ranking.

## 14.6 Signal + PACT

Once players discover each other, PACT can formalize a service relationship:

- crafter found through Signal → Crafting Pact;
- mercenary group found through Signal → Mercenary Pact;
- supplier found through Signal → Purchase Contract.

Signal introduces. PACT commits.

## 14.7 PACT + Black Ledger

They share contract concepts but **must not share institutions**.

- lawful/ordinary contracts → Notarius;
- lawful warrants → Magistrate;
- murder-for-hire → Broker.

Black Ledger may reuse hardened escrow primitives internally, but public domain models remain distinct.

## 14.8 Wayfarer’s Memory + Black Ledger

The journal may remember legitimate clues, but every clue keeps provenance and staleness.

A hunt ending does not create a permanent tracking database.

---

# 15. Compatibility contract: do not break existing Mobius mods

The user requirement throughout the design was that revolutionary systems should **not require disabling existing mods** and should avoid unbalancing the game.

Therefore:

## 15.1 Integration strategy

Prefer:

- event listeners;
- adapters;
- data projectors;
- script hooks;
- isolated NPC scripts;
- new DB tables;
- existing handlers where appropriate.

Avoid:

- invasive edits to combat formulas;
- replacing inventory primitives;
- replacing clan system;
- replacing geodata;
- rewriting private stores;
- monkey-patching every NPC class.

## 15.2 Explicit compatibility metadata

Every event consumed by Living Chronicle should include relevant modifier context when it can distort interpretation:

- Premium reward multiplier;
- VIP status;
- Champion monster flag;
- fake-player actor flag;
- offline-play flag;
- custom NPC-stat profile;
- custom class-balance profile;
- event/instance context;
- PvP reward eligibility.

This lets the societal systems interpret activity correctly without policing how the operator configures Mobius.

## 15.3 No hidden stat modification

Aden Remembers, Signal, Memory, PACT and Black Ledger must not silently edit:

- class stats;
- skill formulas;
- damage;
- defense;
- success rates;
- enchant rates;
- craft rates;
- player speed;
- cooldowns.

When a world situation changes spawn/resource geography, the actuator is explicit, bounded and logged.

---

# 16. Economy design rules

## 16.1 Prefer existing Adena and items

The project rejects currency explosion.

New systems use:

- Adena;
- existing materials;
- existing crafted items;
- escrow;
- fees.

Avoid introducing:

- Assassin Coin;
- Contract Token;
- Reputation Crystal;
- Chronicle Point;
- Signal Credit;
- arbitrary custom reward currencies.

## 16.2 Conservation

PACT transfers assets.

Black Ledger transfers and sinks assets.

Signal creates no assets.

Wayfarer’s Memory creates no assets.

Aden Remembers normally redistributes opportunity within bounded supply constraints rather than creating unlimited global output.

## 16.3 Currency sinks

Good sinks that fit the fiction:

- Notarius registration fees;
- high-value sealing fees;
- Broker fees;
- intelligence purchases;
- witness bribes;
- counter-intelligence;
- optional transmog/custom services already present;
- institutional archive/certification fees where useful.

Fees should exist for economic function, not as pointless taxation.

## 16.4 Newcomer economy

A mature server should periodically create legitimate demand for low/mid-level resources through regional conditions.

This is a market integration mechanism, not a catch-up reward.

---

# 17. Information model

Information is one of the project’s primary resources, so it needs formal semantics.

## 17.1 Fact classes

Suggested universal classes:

- `AUTHORITATIVE_FACT` — server-verifiable and still relevant;
- `PERSONAL_OBSERVATION` — player actually experienced it;
- `VERIFIED_WITNESS` — another player legitimately observed it;
- `INFERENCE` — algorithmic conclusion from valid facts;
- `RUMOR` — unverified/possibly manipulated;
- `HISTORICAL_FACT` — true past event, may no longer reflect present state;
- `STALE` — previously useful but aged;
- `FALSE_RUMOR` — internally known misinformation, still shown as RUMOR to players.

## 17.2 Provenance

Every player-facing intelligence object should be able to answer internally:

- where did this come from?;
- when?;
- how precise?;
- how long is it valid?;
- who is authorized to see it?;
- may it be shared?;
- may it enter permanent history?

## 17.3 Never expose the omniscient backend

The backend may know exact coordinates to run the server.

The user-facing intelligence system often should not.

This distinction must be architectural, not a UI afterthought.

---

# 18. NPC institutional layer

The new systems should make existing Aden feel richer rather than populate every town with neon custom NPCs.

## 18.1 New signature NPC roles

### Notarius

Legal/private contracts and escrow.

### Royal Notarius

High-value and clan instruments.

### Castle Notarius

Siege/mercenary/clan agreements.

### Magistrate / Guard Captain

Lawful warrants and public outlaw bounties.

### Broker / Shadow Broker

Illegal assassination contracts, informants and underworld intelligence.

## 18.2 Existing NPCs gain context-sensitive secondary roles

Without replacing their ordinary functions:

- Gatekeeper → possible Black Ledger witness;
- Warehouse Keeper → possible recent-visit witness;
- merchant → economic observation / witness;
- guard → regional security rumor / lawful witness;
- clan hall NPC → politically constrained information;
- town information NPC → aggregated rumor;
- ordinary merchants → visible consequences of Aden Remembers demand.

The objective is that a 20-year-old NPC suddenly matters again.

---

# 19. Development and simulation strategy

## 19.1 Development environment

Recommended starting VM/server:

```text
Ubuntu Linux
4 vCPU
12–16 GB RAM
50 GB NVMe
JDK 25
Ant
MariaDB
Mobius LoginServer
Mobius GameServer
Full geodata
```

Developer workstation:

- IDE or CLI coding harness;
- Git;
- SSH;
- Lineage II client;
- DB client;
- log/metrics viewer.

No desktop environment is required on the server.

## 19.2 Separate profiles

Maintain at least these configuration profiles:

### `retail-truth`

- custom dynamic modules disabled;
- retail-like stock config;
- deterministic/testing instrumentation enabled.

### `living-dev`

- all Living Chronicle systems enabled as needed;
- debug/audit verbosity increased;
- shortened TTLs where useful;
- synthetic population allowed.

### `load-sim`

- headless/fake actors enabled;
- production-like telemetry aggregation;
- accelerated scenario generation;
- no irreversible external actions.

### `staging`

- production-like config;
- sanitized/test accounts;
- migration rehearsal.

### `production`

- minimal debug overhead;
- full audit;
- stable schema;
- no developer cheats.

## 19.3 Headless and synthetic actors

Testing systems like Aden Remembers manually with one developer is absurdly slow.

We need synthetic actors capable of generating realistic semantic behaviors:

- login/logout;
- travel;
- Gatekeeper use;
- mob farming;
- party formation;
- trade;
- stores;
- crafting;
- PvP;
- PK;
- clan joining;
- clan war;
- siege participation;
- contract proposal/settlement;
- Black Ledger hunting patterns.

Potential sources:

- Mobius Fake Players for server-side simulation;
- headless protocol clients for end-to-end behavior;
- scripted scenario drivers.

Every synthetic actor must be tagged so production analytics can exclude or separately count it.

## 19.4 Scenario library

Create reusable simulations such as:

### `regional_overfarm`

100 actors farm one region heavily for 6 simulated hours.

Expected:

- resource pressure rises within bounds;
- no global supply explosion;
- rumors appear only after thresholds;
- recovery occurs after pressure stops.

### `zerg_empire`

One alliance dominates multiple regions/castles.

Expected:

- influence rises;
- responsibilities/vulnerabilities scale;
- no direct “underdog damage buff” appears;
- smaller actors gain asymmetric opportunity, not free power.

### `mature_server_newcomer`

Existing high-level economy + 50 new low-level characters.

Expected:

- some low-level resource demand can integrate them;
- no free catch-up gear;
- Signal discovers compatible low-level/support players.

### `crafting_pact_success_failure`

Large matrix of successful/failed normal craft attempts.

Expected:

- asset conservation;
- normal craft probability untouched;
- fee settlement exactly per terms;
- restart-safe escrow.

### `black_ledger_hunt`

Hunter, target, informants and false trails.

Expected:

- no live coordinates leak;
- trace TTL respected;
- false trail labelled rumor;
- hunter can succeed through evidence;
- target counter-intel works;
- no combat modifier.

### `assassination_abuse_ring`

Multiple controlled accounts repeatedly attempt to farm bounty.

Expected:

- payout blocked/flagged according to risk rules;
- no duplicate settlement;
- audit contains reason.

### `notarius_restart`

Server restarts at every contract lifecycle state.

Expected:

- no lost items;
- no duplicated items;
- no double payout;
- contract resumes in deterministic state.

## 19.5 Time acceleration

For long-lived simulations, provide development-only time scaling at the **simulation scheduler** level, not by globally corrupting Mobius game time.

Example:

- one strategic Director “day” can be represented by compressed aggregated input fixtures;
- do not accelerate every core retail timer unless the test requires it.

This keeps test results interpretable.

---

# 20. Observability and auditability

A revolutionary system that cannot explain itself will become a conspiracy generator within a week.

## 20.1 Every consequential decision needs a reason code

Examples:

```text
AR_RESOURCE_SHIFT
reason=OVERHARVEST_THRESHOLD
region=DEATH_PASS
resource=ENRIA_FAMILY
input_window=24h
change=-4.0%
cap=-5.0%
```

```text
BLACK_TRACE_DISCLOSED
source=GATEKEEPER
confidence=CONFIRMED
precision=REGION
age=17m
hunter_id=...
contract_id=...
```

```text
PACT_SETTLEMENT
contract=18427
clause=CRAFT_ATTEMPT_COMPLETED
outcome=FAIL_NORMAL_RETAIL
fee_released=400000
```

## 20.2 Metrics

### Mobius/core

- tick latency where available;
- JVM heap;
- GC pause;
- DB connection pool;
- query latency;
- active players;
- offline players/stores;
- fake actors.

### Aden Remembers

- event ingestion rate;
- bucket lag;
- Director evaluation duration;
- number of active situations;
- actuator changes by type;
- resource supply deviation;
- region state change rate.

### Signal

- active intents;
- candidate matches;
- mutual accepts;
- party formation rate;
- median time-to-contact;
- support-role unmet demand.

### Wayfarer

- observations retained;
- summaries produced;
- journal usage;
- shared pages;
- stale-fact cleanup.

### PACT

- proposed/signed/active/completed contracts;
- assets in escrow;
- settlement failures;
- idempotency retries;
- contract types;
- cancellation/default rates.

### Black Ledger

- active warrants/contracts;
- trace generation/disclosure;
- intelligence purchases;
- successful hunts;
- target kills of hunters;
- suspected abuse blocks;
- Adena sunk.

## 20.3 Admin forensic views

Administrators need a privileged view showing **why**, not merely what.

Examples:

- reconstruct one PACT settlement;
- see every state transition of a contract;
- replay Black Ledger trace access;
- explain why a rumor was generated;
- see Director inputs for a regional shift;
- identify fake/offline actor contamination;
- compare world state before/after configuration deployment.

Never expose these forensic details to ordinary players.

---

# 21. Persistence and database strategy

## 21.1 Keep Mobius tables authoritative for Mobius concepts

Do not duplicate player inventory, clan membership, castle owner or character level into a second source of truth unless it is a historical snapshot.

Living Chronicle tables store:

- events;
- derived societal state;
- contracts;
- memory;
- traces;
- historical records.

They reference core Mobius IDs.

## 21.2 Schema migrations

Use explicit numbered migrations for all custom tables.

Example:

```text
V001__living_chronicle_core.sql
V002__aden_remembers.sql
V003__signal.sql
V004__wayfarers_memory.sql
V005__pact.sql
V006__black_ledger.sql
```

Do not rely on hand-editing production SQL.

## 21.3 Idempotency keys

Critical operations require idempotency:

- contract settlement;
- escrow return;
- bounty payout;
- Broker fee destruction;
- Chronicle history promotion;
- synthetic aggregate processing.

## 21.4 Backups

Development and production need:

- automated DB snapshots;
- pre-migration snapshot;
- tested restore process;
- event-journal retention strategy;
- contract/escrow consistency verification after restore.

A backup that has never been restored is a motivational artifact, not a backup.

---

# 22. Security and abuse model

## 22.1 Threat classes

Plan for:

- packet manipulation;
- botting;
- radar/location extraction;
- multi-account farms;
- same-household false positives;
- contract laundering;
- bounty collusion;
- fake informant rings;
- PACT duplication exploits;
- SQL injection/admin interface abuse;
- social engineering;
- compromised accounts;
- malicious clan leaders;
- replay/double-settlement bugs.

## 22.2 Principle of least trust

Never trust client claims for:

- location;
- kill success;
- contract completion;
- inventory presence;
- witness encounter;
- participation time;
- clan membership.

All verification comes from authoritative server state/events.

## 22.3 Black Ledger privacy/fairness

The server internally knows exact position because it must simulate the game.

That does not give the hunter a right to exact position.

Trace APIs must return transformed/coarsened information intentionally.

Do not implement “get player coordinates then round them in the UI”. Build an information-access service that never exposes raw coordinates to Black Ledger callers.

## 22.4 PACT security

Escrow operations are financial operations.

Requirements:

- DB transaction boundaries;
- row locking/version checks;
- no settlement based solely on client packet;
- duplicate request protection;
- immutable audit event;
- explicit failure recovery;
- safe handling of full inventory/warehouse;
- safe account logout/restart handling.

## 22.5 Admin power

Any admin override of:

- contract;
- bounty;
- world state;
- history;
- escrow;
- trace;

must produce an audit entry with:

- admin ID;
- timestamp;
- before/after;
- reason.

A server built around trust cannot have invisible GM reality editing.

---

# 23. Performance design

The systems are conceptually large but should be computationally modest.

## 23.1 Never simulate society every tick

Aden Remembers is an **event aggregation system**, not an agent-based simulation of every citizen every 50 ms.

Use:

- counters;
- rolling windows;
- scheduled projectors;
- bounded state machines;
- coarse regional models.

## 23.2 Database write control

Do not write every movement event.

Write semantic events and aggregate hot streams.

Examples worth recording:

- Gatekeeper usage;
- region transition at meaningful granularity;
- kill bucket counts;
- contract transitions;
- siege outcome;
- meaningful item flow aggregates.

Examples usually not worth recording individually:

- every coordinate update;
- every auto-attack swing for world history;
- every minor NPC proximity tick.

## 23.3 Caches

Suitable cache candidates:

- active Signal intents;
- active Black Ledger traces;
- current regional state;
- NPC witness short memory;
- contract read models.

Authoritative assets/settlements remain DB-backed.

## 23.4 Failure degradation

If Aden Remembers projector fails:

- normal Mobius gameplay must continue;
- Director freezes safely;
- queued events can catch up.

If Signal fails:

- ordinary party formation still works.

If Wayfarer fails:

- ordinary gameplay still works.

If Black Ledger fails:

- normal PvP/PK still works;
- no contract payout until consistency restored.

If PACT settlement fails:

- assets remain safely escrowed;
- contract enters recoverable pending state;
- never “best effort” transfer assets twice.

The custom layer must not become a single point of failure for basic L2.

---

# 24. Player UX principles

## 24.1 Use diegetic interfaces where ceremony matters

Physical NPC/in-world interaction is valuable for:

- Notarius high-value contracts;
- Magistrate warrants;
- Broker assassinations;
- witness questioning;
- public political registration.

## 24.2 Use low-friction UI where bureaucracy has no value

Direct UI is better for:

- Signal intent updates;
- Wayfarer journal search;
- reviewing your own active contracts;
- checking already acquired intelligence.

Do not make the player run to Giran every time they want to remember their shopping list.

## 24.3 Avoid UI omniscience

Never use UI to expose:

- hidden resource-pressure percentages;
- exact opponent locations;
- secret clan influence formulas;
- exact target path predictions;
- Director future decisions.

## 24.4 Language

All structured system facts should use localization keys.

Narrative text can be localized/templates first, with optional LLM narration later.

The stock Multilingual Support module can be leveraged where appropriate.

---

# 25. Configuration blueprint

Illustrative configuration, not final syntax.

## 25.1 `AdenRemembers.ini`

```ini
Enabled = true
EventJournalEnabled = true
RegionAggregationMinutes = 10
DirectorEvaluationMinutes = 30
HistoryPromotionDaily = true
ResourcePressureEnabled = true
ResourcePressureMaxDeviationPercent = 5
TradeFlowEnabled = true
PoliticalInfluenceEnabled = true
NpcRumorsEnabled = true
PublicChronicleEnabled = true
LlmNarrationEnabled = false
```

## 25.2 `AdenSignal.ini`

```ini
Enabled = true
RequireOptIn = true
LatentPartySuggestions = true
FutureIntentEnabled = true
ExposeExactCoordinates = false
IncludeOfflinePlay = false
IncludeFakePlayers = false
MaxActiveIntentsPerPlayer = 3
```

## 25.3 `WayfarersMemory.ini`

```ini
Enabled = true
LoginRecapEnabled = true
PersonalNotesEnabled = true
KnowledgeSharingEnabled = true
RememberMarketObservations = true
ObservationRetentionDays = 30
AutoSummarizeRepeatedObservations = true
GlobalWikiMode = false
```

## 25.4 `Pact.ini`

```ini
Enabled = true
CraftingPacts = true
PurchaseOrders = true
DeliveryOrders = true
LootCharters = true
MercenaryContracts = true
Loans = false
RequireNotariusForHighValue = true
HighValueThresholdAdena = 10000000
PublicRegistryEnabled = true
```

## 25.5 `BlackLedger.ini`

```ini
Enabled = true
LawfulWarrants = true
BlackContracts = true
ExactLocationDisclosure = false
PlayerInformants = true
FalseRumors = true
CounterIntelligence = true
HunterAliases = true
CallingCards = true
ReverseInvestigation = true
MinContractValue = 100000
BrokerFeePercent = 5
NewbieProtectionLevel = <branch-policy>
SamePartyPayout = false
FakePlayerPayout = false
```

All values require real balancing after simulation.

---

# 26. Implementation roadmap

The roadmap deliberately puts correctness and observability before spectacle.

## Phase 0 — Freeze the reference foundation

Deliverables:

- exact Mobius branch/commit pinned;
- known compatible client/system pinned;
- geodata version pinned;
- JDK/build documented;
- reproducible clean installation;
- DB migration mechanism;
- Git branching policy;
- staging/development profiles.

Exit condition:

> another developer can build and run the exact same server from documentation.

## Phase 1 — Retail Truth MVP

Deliverables:

- test catalogue format;
- initial automated combat/skill tests;
- missing-quest inventory;
- AI correctness cases for known risky bosses;
- clean test profile;
- status dashboard/report.

Exit condition:

> we can prove at least a meaningful core subset rather than saying “looks retail”.

## Phase 2 — Living Chronicle Event Core

Deliverables:

- event adapter;
- actor classification;
- semantic event journal;
- rolling metric buckets;
- audit/reason-code framework;
- fake/offline actor separation.

Exit condition:

> server produces trustworthy structured history without changing gameplay.

## Phase 3 — Aden Remembers vertical slice

Start with **one region pair and one material family**, not the whole world.

Example:

- Oren + neighboring region;
- one farmed material family;
- one pressure metric;
- one merchant/rumor output;
- one reversible bounded spawn/resource response;
- history capture.

Exit condition:

> synthetic overfarm creates understandable, bounded, reversible world consequences and no global economy inflation.

## Phase 4 — Aden Signal

This was identified in the thread as the first player-facing module to prioritize after Aden Remembers because it solves the most primitive modern social failure without changing balance.

Deliverables:

- player opt-in;
- intent model;
- time availability;
- role/objective matching;
- latent party suggestion;
- future intents;
- privacy/block rules.

Exit condition:

> testers form viable parties faster without teleport/reward assistance.

## Phase 5 — Wayfarer’s Memory

Deliverables:

- personal observation graph;
- login recap;
- projects/notes;
- known/told/rumored provenance;
- stale knowledge;
- shareable journal pages.

Exit condition:

> a player can leave for days, return, and reconstruct what they were doing without the system telling them the globally optimal path.

## Phase 6 — PACT core + Notarius

Deliverables:

- contract lifecycle;
- signatures;
- escrow;
- Crafting Pact;
- Purchase Order;
- Delivery Order;
- Notarius NPC;
- parchment representation;
- objective contract history;
- restart/crash tests.

Exit condition:

> no asset duplication/loss across large automated settlement and restart test suites.

## Phase 7 — PACT social expansion

Deliverables:

- Loot Charter;
- public/sealed registry;
- Royal Notarius;
- Castle Notarius;
- Mercenary Contract;
- Chronicle integration.

Exit condition:

> a real siege mercenary agreement can be signed, verified and settled without modifying siege combat rules.

## Phase 8 — Black Ledger MVP

Deliverables:

- Magistrate warrants;
- Broker black contracts;
- funding/escrow reuse where safe;
- Gatekeeper/merchant/guard/warehouse traces;
- trace TTL;
- information confidence;
- successful-kill settlement;
- ordinary PK consequence preservation.

Exit condition:

> hunter can find target through clues without any exact-location API and combat is unchanged.

## Phase 9 — Black Ledger advanced

Deliverables:

- player informants;
- intelligence budgets;
- counter-intelligence;
- witness bribery;
- false rumors;
- hunter progression;
- calling cards;
- nom de guerre;
- identity intelligence;
- reverse investigation;
- legendary-hunt history;
- deep anti-farm.

Exit condition:

> multi-hour simulated hunts produce meaningful information warfare and resist obvious farming loops.

## Phase 10 — Full Aden Remembers expansion

Expand gradually across:

- additional regions;
- multiple resource families;
- trade flows;
- political influence;
- NPC demand;
- caravans;
- boss ecology;
- catch-in mechanics;
- public Chronicle;
- economic warfare.

Every actuator needs its own simulation and rollback.

## Phase 11 — Public Living Chronicle

Deliverables:

- public website/API read model;
- castle dynasties;
- historical battles;
- public contracts;
- famous hunts;
- regional narrative;
- records;
- strict secret-state filtering.

Exit condition:

> the website is interesting to follow but cannot be used as a live tactical radar.

---

# 27. Acceptance criteria by revolutionary module

## 27.1 Aden Remembers is acceptable when

- the world changes from real player behavior;
- changes are bounded and reversible;
- no raw hidden metrics are exposed;
- latecomers gain economic relevance without free gear;
- dominant clans face complexity rather than synthetic debuffs;
- resource changes do not inflate total supply uncontrollably;
- every consequence has an audit reason;
- disabling the module restores stable Mobius behavior.

## 27.2 Aden Signal is acceptable when

- players find compatible humans significantly faster;
- no teleport is granted;
- no reward is generated;
- support classes become easier to find, not unnecessary;
- exact location is not exposed;
- future sessions work;
- opt-out works cleanly.

## 27.3 Wayfarer’s Memory is acceptable when

- it accurately remembers personal context;
- it distinguishes known/told/rumor;
- it tolerates stale facts;
- it never becomes a global optimal-strategy wiki;
- sharing knowledge preserves provenance;
- old repetitive noise is summarized.

## 27.4 PACT is acceptable when

- assets never duplicate;
- assets never silently disappear;
- contract conditions are machine-verifiable;
- normal craft success/failure remains normal;
- manual/social betrayal remains possible outside contracts;
- objective history replaces subjective ratings;
- server restart at any state does not corrupt settlement.

## 27.5 Black Ledger is acceptable when

- hunters receive trails, not GPS;
- normal combat remains unchanged;
- lawful/illegal consequences remain distinct;
- player informants can matter;
- targets can counter-play;
- false information is possible but labelled as uncertain;
- repeated bounty farming is economically unattractive/blocked;
- failed hunters can create reverse-investigation gameplay;
- identities/calling cards create social mythology rather than stats.

## 27.6 Retail Truth is acceptable when

- claims can be traced to evidence;
- exactness is not assumed;
- regression is automated for core mechanics;
- known missing/broken behavior is visible;
- custom modules can be disabled for clean validation.

---

# 28. Product positioning

Do not market this as:

> “Classic 3.0 Kamael x3, unique features, custom events, balanced classes, active GM.”

That sentence has been murdered by repetition.

Market the actual idea.

## 28.1 World proposition

> **One permanent world. No seasons. No wipes as a content strategy. No scripted global storyline. Resources move. Economies rise and weaken. Clans build power. Trade routes emerge. Wars reshape regions. NPCs remember consequences. New players enter history instead of starting outside it.**

## 28.2 Aden Remembers proposition

> **Every other Lineage II server resets the world. On ours, the world remembers what you did.**

## 28.3 Black Ledger proposition

> **There is a price on your head. Nobody knows where you are. Yet.**

Supporting line:

> **No GPS. No PvP buffs. No arenas. Follow witnesses. Buy information. Bribe officials. Plant false trails. Hire informants. Learn routines. Wait for your moment.**

## 28.4 Societal proposition

> **Aden is not a map. It is a society.**

This is the umbrella concept.

---

# 29. Example emergent story: how all systems create one history

This example demonstrates why the modules are designed as interacting systems rather than separate minigames.

## Day 1–7: economic pressure

Players heavily farm a particular high-value material around Oren.

Aden Remembers observes:

- unusual kill concentration;
- rising material output;
- reduced geographic diversity;
- high trade flow into Giran.

Resource pressure increases within bounded limits.

NPC rumors begin mentioning unusual hunting activity.

## Day 8: discovery

A small clan notices a neighboring area becoming more attractive.

Wayfarer’s Memory records individual members’ observations, but this information is not globally published.

They begin farming quietly.

## Day 10: information leakage

Other traders notice the clan selling unusually large quantities.

Players begin asking around.

Aden Signal shows elevated demand for spoilers and support players around that region, because humans are seeking them.

No system explicitly announces “new best farm zone”.

## Day 12: conflict

A rival CP follows the supply chain and finds the spot.

PvP begins.

Regional conflict rises.

## Day 14: institution

The smaller clan needs protection.

Through Signal they find a mercenary clan.

They visit a Notarius and sign a PACT:

- fixed payment;
- defined defense window;
- machine-verifiable participation.

The contract is public but terms are sealed.

Other clans now know *something* is happening.

## Day 15: assassination

A rival secretly places a Black Contract on the supplier clan’s leader.

The hunter receives no location.

They investigate Gatekeepers and buy a verified player sighting.

The target’s friendly NPC later warns that strangers have asked questions.

The target changes travel routes.

The hunt lasts two days.

## Day 17: failed kill

The assassin attacks and dies.

The target finds a Torn Black Seal and begins reverse investigation.

## Day 20: political exposure

Enough evidence emerges socially that the supplier clan believes a larger alliance funded the attempt.

No AI declares guilt.

The players choose to declare clan war.

## Week 4: regional shift

Trade has moved, conflict has increased, mercenary payments circulate, Broker fees have removed Adena, and a previously irrelevant region has become strategically important.

## Month 3: history

The conflict becomes sufficiently large that Aden Remembers promotes it into the Chronicle.

The historical record includes:

- contract dates;
- siege participation;
- public clan-war facts;
- major assassination attempt;
- regional economic shift;
- final outcome.

It does not invent private motives.

That story was not a scripted event.

It emerged from:

```text
economy
+ geography
+ information
+ human greed
+ contracts
+ violence
+ memory
```

That is the intended product.

---

# 30. “Do not accidentally ruin this” checklist

Before approving any new custom idea, ask:

1. Does it create power from nowhere?
2. Does it make a class unnecessary?
3. Does it invalidate geography?
4. Does it reveal information players should discover?
5. Does it create a new currency unnecessarily?
6. Does it turn open-world conflict into an instance/lobby?
7. Does it replace humans with an NPC convenience?
8. Does it reward activity merely for clicking the new feature?
9. Does it make old Lineage systems irrelevant?
10. Can it be disabled without damaging core Mobius?
11. Can we explain every authoritative decision?
12. Can players abuse it through alts/fake players?
13. Does it create a story, relationship or meaningful decision?
14. Would this still feel like Lineage II if the custom UI were hidden?

If answers 1–9 are mostly “yes”, reject it.

---

# 31. Complete feature coverage audit of this design thread

This section exists specifically to ensure no discussed feature was silently omitted.

## 31.1 Stock Mobius custom features captured

- Allowed Player Races
- Auto Potions
- Banking System
- Boss Announcements
- Cancel Return / Buff Restoration
- Captcha Anti-Bot
- Champion Monsters
- Chat Moderation
- Per-Class Balance Engine
- Custom Community Board / Alt+B Services
- Custom Depositable Items
- Custom Mail Manager
- Delevel Manager
- Dualbox Control
- Faction System
- Fake Players
- Find PvP
- Free Mounts
- Merchant Zero Sell Price
- Multilingual Support
- Nobless Master
- NPC Stat Multipliers
- Offline Auto Play
- Offline Trade / Craft
- Online Information
- In-Game Password Change
- Premium Account System
- PC Cafe / PC Bang enhancements
- Private Store Distance Control
- PvP/PK Announcements
- PvP/PK Item Rewards
- PvP Ranking Titles / Colors
- Randomized Monster Spawns
- Sayune for Everyone
- Scheme Buffer
- Welcome Screen Message
- Player-to-Player Buff Selling
- Server-Time Display
- Custom Starting Location
- Custom Starting Title
- Transmogrification / Appearance Collection
- VIP Tier System
- Walker / Bot Protection
- Wedding / Marriage System

## 31.2 Revolutionary modules captured

- Aden Remembers
- Living World Director
- World Event Journal
- Regional state engine
- Political/economic graph
- resource pressure
- dynamic regional opportunity
- economic warfare
- NPC societal reaction
- boss ecology/context
- anti-zerg complexity cost
- newcomer catch-in economy
- imperfect information
- NPC rumors
- public Living Chronicle
- persistent historical graph
- item/crafter provenance concept
- deterministic engine + optional LLM narration

- Aden Signal
- intent broadcasting
- objective/role/time/language/travel/pace matching
- latent party formation
- future intent sessions
- no balance rewards
- support-class preservation

- Wayfarer’s Memory
- session recap
- personal knowledge graph
- known/told/rumored provenance
- stale knowledge
- personal projects/notes
- market observations
- shareable journal pages
- personal history
- no global omniscient wiki

- PACT
- voluntary contracts
- machine-verifiable clauses
- crafting pact
- loot charter
- purchase contracts
- craft orders
- delivery orders
- loan concept
- mercenary contracts
- public/sealed contracts
- objective contract history
- optional certainty / default freedom

- Notarius
- Town Notarius
- Royal Notarius
- Castle Notarius
- parchment contract item
- remote drafting + physical high-value signing
- public registration
- escrow
- contract lifecycle

- Black Ledger
- lawful Writ
- Magistrate / Guard Captain
- illegal Black Contract
- Broker / Shadow Broker
- sparse dossiers
- semantic traces
- Gatekeeper witnesses
- Warehouse witnesses
- merchant witnesses
- guard witnesses
- clan-hall information constraints
- tavern/information rumors
- trace decay
- no offline GPS
- player informants
- player-funded intelligence budgets
- counter-intelligence
- “you feel watched” tension
- trail manipulation through travel
- witness bribery
- false trails
- CONFIRMED / WITNESS / INFERRED / RUMOR / STALE intelligence
- Hunter ranks: Listener / Tracker / Stalker / Head Hunter / Ghost
- information progression only
- conditional contracts
- public execution condition
- target victory
- Torn Black Seal
- reverse hunt toward patron
- calling cards
- nom de guerre
- alias identity intelligence market
- public bounty for notorious outlaws
- zero-money-minting economy
- Broker/Adena sinks
- anti-farm
- anti-harassment controls
- historical legendary hunts

- Retail Truth
- EXACT / FUNCTIONAL / APPROXIMATE / BROKEN / MISSING / UNKNOWN
- evidence-based cases
- clean retail profile
- regression gates
- automated/headless tests
- AI/combat/economy/world validation

## 31.3 Technical/development topics captured

- Classic 3.0 The Kamael scope clarification
- Java 25 baseline discussed
- Ant
- MariaDB
- LoginServer/GameServer
- geodata
- development RAM/CPU/storage recommendations
- no GPU requirement
- separate client workstation
- synthetic/headless actors
- fake-player actor classification
- known missing quests
- NPC AI fidelity risk
- formula/trigger edge cases
- geodata/pathfinding risk
- protocol/client compatibility
- absent NCSoft operational infrastructure
- stock anti-cheat limitations
- observability
- reason codes
- persistence
- migrations
- audit
- safe failure modes
- performance aggregation

Nothing in the design thread should be treated as “implicitly included” if it is absent from the sections above; this audit is the explicit completeness checklist.

---

# 32. Recommended first implementation slice

Do **not** begin by implementing every idea simultaneously.

The smartest first vertical slice is:

1. pin Mobius + client + geodata;
2. create Retail Truth skeleton;
3. implement common Living Chronicle event envelope;
4. classify online/offline/fake actors;
5. build append-only semantic journal;
6. choose one region and one resource family;
7. calculate resource pressure from real/synthetic activity;
8. create one bounded reversible consequence;
9. surface the consequence only through one NPC rumor and one economic observable;
10. record it in Wayfarer’s Memory as RUMOR for one test player;
11. verify no raw Director metric leaked;
12. replay the journal and reproduce the same derived state.

If this works, the core architectural hypothesis is proven:

> **ordinary Lineage activity can generate persistent, understandable consequences without modifying combat.**

Only then scale outward.

---

# 33. Final design statement

The project should not ask:

> “How can we add enough features that players stay?”

It should ask:

> **“How can the actions of players produce enough consequences that they care what happens next?”**

That is the difference between content consumption and a persistent world.

The complete design can be reduced to six promises:

### 1. Retail Truth
**The foundation should be known, tested and honest about its inaccuracies.**

### 2. Aden Remembers
**The world has consequences and history.**

### 3. Aden Signal
**People who need one another can actually find one another.**

### 4. Wayfarer’s Memory
**A character retains the continuity of their journey without receiving omniscience.**

### 5. PACT / Notarius
**People can voluntarily make enforceable agreements without eliminating political betrayal.**

### 6. The Black Ledger
**Violence becomes a profession of information, preparation, fear and consequence rather than another PvP counter.**

Together they create a server in which:

- resources matter because access changes;
- geography matters because travel leaves consequences;
- support players matter because they remain necessary;
- crafters matter because contracts and reputation make commerce durable;
- newcomers matter because mature economies need them;
- clans matter because power creates political and logistical responsibility;
- assassins matter because knowing where someone might be is valuable;
- ordinary players matter because they can become suppliers, witnesses, informants, mercenaries or historians;
- history matters because the server refuses to forget everything at the next wipe.

The intended reaction is not:

> “Nice custom server.”

It is:

> **“Wait. What happened on that server?”**

Because once people ask about events rather than features, the world has started living.

---

# Appendix A — Source/reference notes carried from the design thread

The thread referenced the following categories of primary/current material while developing the design:

- L2J_Mobius official site and forum;
- L2J_Mobius GitLab repository, especially `L2J_Mobius_Classic_3.0_TheKamael`;
- current branch custom configuration under `dist/game/config/Custom`;
- Mobius `EventType` and `EventDispatcher` source;
- branch build/JVM configuration;
- missing quest inventory and `not_done` quest scripts;
- recent Mobius commit history for AI/spawn correctness;
- Lineage II historical/retail philosophy material;
- contemporary L2 community discussions about grouping, mature-server onboarding and old-L2 social design;
- examples from sandbox MMO bounty systems used only as design references, not as mechanics to copy.

Before implementation, every current Mobius-specific technical fact should be revalidated against the pinned commit. The blueprint intentionally distinguishes durable design principles from moving upstream implementation details.

---

# Appendix B — Naming glossary

**Living Chronicle**  
Umbrella product/server philosophy.

**Retail Truth**  
Correctness and regression framework for the underlying Mobius implementation.

**Aden Remembers**  
Persistent world-memory, political-economy and living-world Director.

**Aden Signal**  
Human intent and coordination network.

**Wayfarer’s Memory**  
Private character memory / personal knowledge graph.

**PACT**  
Voluntary machine-enforceable player contract framework.

**Notarius**  
In-world legal institution/UI for PACT.

**Magistrate**  
Lawful warrant/head-hunting institution.

**The Broker / Shadow Broker**  
Illegal Black Ledger contract and intelligence institution.

**The Black Ledger**  
Assassination, bounty hunting, investigation, informant and counter-intelligence system.

**Chronicle of Aden**  
Player-facing historical record generated from verified world history.

**Situation**  
A persistent cause/consequence chain produced by world state, deliberately distinct from a scripted timed event.

**Catch-in**  
Integrating late/new players into mature economic/social systems by making their legitimate activity useful, rather than granting artificial catch-up power.

---

# Appendix C — One-sentence architecture

> **Use a verified Mobius core to emit semantic events; turn those events into world memory, personal memory, human discovery, enforceable institutions and imperfect intelligence; let normal Lineage II combat/economy resolve the consequences; then remember only what became history.**


---

# Appendix D — Platform selection rationale from the full thread

The broader research discussion considered several Java Lineage II server families before settling on Mobius as the development foundation.

## D.1 Why L2J_Mobius

Mobius was selected as the preferred **development platform** because the discussion found it to combine:

- active ongoing maintenance;
- modern Java baseline;
- broad chronicle coverage across the Mobius ecosystem;
- source availability suitable for deep modification;
- extensive datapack/script infrastructure;
- event architecture useful for orthogonal modules;
- an existing Classic 3.0 The Kamael branch;
- enough stock custom infrastructure that we do not need to rebuild ordinary private-server conveniences before working on the genuinely novel systems.

The key reason is not a claim that Mobius is perfect retail. It is that Mobius is the strongest **modifiable engineering substrate** for the proposed work.

## D.2 MMORE / L2-Scripts context

The thread identified commercial/private MMORE/L2-Scripts builds as potentially interesting when the only objective is maximum current Essence/Classic retail completeness and budget is secondary.

They were **not** selected as the blueprint foundation because the project here requires:

- deep source-level experimentation;
- architectural control;
- new systemic modules;
- test harnesses;
- event instrumentation;
- freedom to evolve the server as a research/development platform.

Any vendor claim of “100% retail” must be independently validated rather than accepted as architecture truth.

## D.3 aCis context

aCis remains a serious option for a project whose core target is highly focused **Interlude** behavior, but this blueprint is intentionally centered on **Classic 3.0 The Kamael** and a broader modern Mobius development base.

## D.4 Legacy L2JServer/forks

The thread explicitly rejected beginning this new project on old/legacy L2JServer merely for brand familiarity. The goal is an actively evolving codebase, not archaeological loyalty.

---

# Appendix E — Explicit Aden Remembers details preserved from ideation

This appendix captures specific examples from the ideation phase so they do not disappear behind the generalized architecture.

## E.1 Dominance benefits discussed

A politically dominant clan may earn bounded forms of influence such as:

- improved Manor efficiency where appropriate;
- preferential access to selected NPC contracts;
- reduced institutional/transaction costs where justified;
- access to better strategic information;
- regional prestige;
- additional castle administrative options.

These are examples, not a mandatory launch bundle. Every one requires simulation and a non-combat balance review.

## E.2 Dominance obligations discussed

A ruling clan may need to protect or maintain:

- trade caravans;
- resource routes;
- warehouses/logistical assets;
- subordinate settlements or regional interests;
- Manor production;
- NPC loyalty/administrative stability.

This is the intended anti-zerg mechanism: **large power creates a large attack surface**.

## E.3 NPC societal reactions discussed explicitly

Potential situation outputs include:

- smuggling;
- corruption;
- rebellion;
- banditry;
- shortages;
- merchant/NPC migration;
- new economic opportunity.

These should emerge only when supported by world-state causes and should remain bounded.

## E.4 Sea of Spores / Orfen example

A representative boss-context scenario from ideation:

1. players heavily exploit/kill around Sea of Spores;
2. regional pressure shifts;
3. Orfen-related influence/context intensifies;
4. scouts or related NPC behavior appears;
5. spore/monster pressure changes in the surrounding area;
6. certain materials become more valuable;
7. nearby NPCs develop supply needs;
8. travel/access routes become strategically relevant;
9. players react economically and militarily.

The system does **not** randomly relocate Orfen simply to create novelty.

## E.5 In-world historical memory

History should appear inside Aden, not only on a website.

Examples discussed:

### Historical monument

```text
The Siege of 14 October
Defended by NightWatch
317 defenders
402 attackers
Castle held after 1h 43m
```

### NPC historical reference

```text
"RedSky once ruled these lands. Their banners haven't flown here since the Winter Rebellion."
```

### Item biography

Where technically reliable and deliberately enabled, an important item may carry historical metadata such as:

```text
Crafted by: BlackMamba
First owner: Slava
Recorded PvP kills while tracked: 283
```

This must not turn every common item into an infinitely growing telemetry object. Promote only meaningful/provenance-worthy items.

## E.6 Public Chronicle views explicitly discussed

The external/in-game Chronicle can eventually expose sanitized views such as:

- current political map;
- trade flows;
- known conflicts;
- castle dynasties;
- most notorious PKs/outlaws;
- largest historical battles;
- clan relationships based on mechanically known facts;
- crafting dynasties;
- famous weapons/items;
- regional prosperity expressed safely;
- recent rumors;
- major public contracts;
- legendary hunts.

The website must never reveal hidden tactical state, exact target location or raw Director values.

---

# Appendix F — Black Ledger principles preserved from ideation

## F.1 Murder acquires a market price

The Black Ledger should allow a real human market price for killing a specific person to emerge.

A badly geared isolated target may attract only small contracts.

A wealthy, mobile, protected clan leader may require a dramatically larger reward before competent hunters consider the risk worthwhile.

The server should not calculate a synthetic “correct bounty price” and force it. Human willingness to fund and accept contracts is itself the price discovery mechanism.

## F.2 Hunter identity as mythology

The point of calling cards and aliases is not cosmetic collection progression. It is social recognition.

The desired community reaction is:

> “Black Feather got him.”

A persistent alias becomes valuable precisely because the mapping to the underlying character can remain unknown, be suspected, leaked, sold and eventually exposed.

## F.3 The profession definition

The Black Ledger should create an occupation that does not exist in ordinary L2:

> **someone who knows how to find people who do not want to be found.**

That sentence is the design test for every feature added to the module.

---

# Appendix G — Primary Mobius reference locations used during the thread

These links are recorded for implementation revalidation. Pin exact commits before coding because upstream changes.

- Official Mobius site: <https://l2jmobius.org/>
- Mobius GitLab project: <https://gitlab.com/MobiusDevelopment/L2J_Mobius>
- Classic 3.0 The Kamael tree: <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/tree/master/L2J_Mobius_Classic_3.0_TheKamael>
- Custom configuration directory (branch): <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/tree/master/L2J_Mobius_Classic_3.0_TheKamael/dist/game/config/Custom>
- EventType source location (branch): <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/blob/master/L2J_Mobius_Classic_3.0_TheKamael/java/org/l2jmobius/gameserver/model/events/EventType.java>
- EventDispatcher source location (branch): <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/blob/master/L2J_Mobius_Classic_3.0_TheKamael/java/org/l2jmobius/gameserver/model/events/EventDispatcher.java>
- Missing quest inventory: <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/blob/master/L2J_Mobius_Classic_3.0_TheKamael/dist/game/data/scripts/quests/MissingQuests.txt>
- Branch commit history: <https://gitlab.com/MobiusDevelopment/L2J_Mobius/-/commits/master/L2J_Mobius_Classic_3.0_TheKamael>

