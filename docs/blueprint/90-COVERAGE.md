# 90 — Coverage: v1 §31 → v2

Nothing from v1 is dropped. Kamael-only items are marked as absent from the H5 codebase.

## 1. Stock Mobius custom features (v1 §31.1)

Policies for every H5 custom config: 03-PLATFORM §8.

| v1 item | v2 |
|---|---|
| Allowed Player Races, Auto Potions, Banking, Boss Announcements, Cancel Return, Captcha, Champion Monsters, Chat Moderation, Per-Class Balance, Custom Community Board, Custom Mail Manager, Delevel Manager, Dualbox Control, Faction System, Fake Players, Find PvP, Free Mounts, Merchant Zero Sell Price, Multilingual, Nobless Master, NPC Stat Multipliers, Offline Auto Play, Offline Trade/Craft, Online Info, Password Change, Premium, Private Store Distance, PvP/PK Announcements, PvP/PK Rewards, PvP Titles/Colors, Randomized Spawns, Scheme Buffer, Welcome Message, Sell Buffs, Server Time, Starting Location, Starting Title, Transmog, Walker/Bot Protection, Wedding | 03 §8 (each with policy and reason) |
| PC Cafe / PC Bang | H5 has `PcCafePointsManager`, configured in `PremiumSystem.ini`; OFF — 03 §8 (PremiumSystem row) |
| VIP Tier System, Sayune for Everyone, Custom Depositable Items | not present in H5 (Kamael-only configs) |
| Inventory accounting note (43 + PC Cafe) | superseded: H5 has 44 custom configs, enumerated in 03 §8 |

## 2. Revolutionary modules (v1 §31.2)

| v1 item | v2 |
|---|---|
| Aden Remembers, Living World Director, World Event Journal | 12; 04 §1 (Fact Journal) |
| regional state engine, political/economic graph | 12 §1, §6 |
| resource pressure, dynamic regional opportunity | 12 §4, §5.1 |
| economic warfare | 12 §5.3 |
| NPC societal reaction | 12 §4, §5 |
| boss ecology/context (incl. Sea of Spores / Orfen) | 12 §5.5 |
| anti-zerg complexity cost; dominance benefits and obligations (App. E.1–E.2) | 12 §6.1, §6.3 |
| newcomer catch-in | 12 §5.2; 10 §8 |
| imperfect information, NPC rumors | 12 §7; 01 §2.5 |
| public Living Chronicle, persistent historical graph | 17; 12 §9 |
| item/crafter provenance (item biography, App. E.5) | 17 §4 |
| historical monument, NPC historical reference (App. E.5) | 17 §2 |
| public Chronicle views (App. E.6) | 17 §5 |
| deterministic engine + optional LLM narration | 01 §2.6; 17 §6 |
| NPC reactions: smuggling, corruption, rebellion, banditry, shortages, migration, opportunity (App. E.3) | 12 §5 (situations), §8 (caravans/banditry) |
| Aden Signal: intent broadcasting; objective/role/time/language/travel/pace matching; latent party; future sessions; no rewards; support-class preservation | 13 §1–§5 |
| Wayfarer's Memory: recap; knowledge graph; known/told/rumored; stale; projects/notes; market observations; shareable pages; personal history; no global wiki | 14 §1–§9 |
| PACT: voluntary contracts; machine-verifiable clauses; crafting pact; loot charter; purchase; craft order; delivery; loans; mercenary; public/sealed; objective history; default freedom | 15 §1–§7 |
| Notarius: Town / Royal / Castle; parchment item; remote drafting + physical high-value signing; public registration; escrow; lifecycle | 15 §3, §4, §8 |
| Black Ledger: Writ; Magistrate/Guard Captain; Black Contract; Broker/Shadow Broker; dossiers; traces; gatekeeper/warehouse/merchant/guard witnesses; clan-hall constraints; tavern rumors; decay; no offline GPS; informants; intel budgets; counter-intelligence; "you feel watched"; trail manipulation; bribery; false trails; confidence classes; hunter ranks; information-only progression; conditional contracts; public execution; target victory; Torn Black Seal; reverse hunt; calling cards; nom de guerre; alias intel market; public bounties; zero minting; sinks; anti-farm; anti-harassment; legendary hunts | 16 §1–§14 |
| murder market price, hunter mythology, profession definition (App. F) | 16 §6 |
| Retail Truth: statuses; evidence cases; clean profile; regression gates; automated tests; AI/combat/economy/world validation | 11 |

## 3. Technical topics (v1 §31.3)

| v1 item | v2 |
|---|---|
| Kamael scope clarification | replaced by H5 baseline: 03 §1 |
| Java 25 | Java 27 (upstream requirement): 03 §1–§2 |
| Ant, MariaDB, LoginServer/GameServer | 03 §2, §9 |
| geodata | 03 §7 |
| dev RAM/CPU/storage | replaced by measured capacity on build-host: 03 §9–§10 |
| no GPU requirement | authoritative systems need none; the narrator uses the RTX 5090 (17 §6) |
| client on workstation | 03 §6 (client package) |
| synthetic/headless actors, fake-player classification | 10; 04 §2 |
| missing quests | H5 content inventory: 03 §5, 11 §1.5 |
| NPC AI fidelity, formula/trigger edge cases | 11 §1.4 |
| geodata/pathfinding risk | 03 §7; 30 §5 |
| protocol/client compatibility | 03 §1, §6; 10 §5.2 |
| absent NCSoft operational infrastructure, anti-cheat limits | 02 §12; 16 §8–§9 |
| observability, reason codes, audit | 02 §11; 04 §6 |
| persistence, migrations | 04 §9 |
| safe failure modes | 04 §8 |
| performance aggregation | 02 §6; 04 §1 |

## 4. New in v2 (not in v1)

Citizens of Aden and the Genesis Epoch (10); relationship ledger (10 §7); Crown Law (12 §6.2); caravans (12 §8);
Caravan Escort and Treaty contracts (15 §2); cursed-weapon writs, citizen bounties, wanted boards (16 §2); Aden Gazette,
bards, town criers (17 §2–§3); saga replays (17 §5); public Truth Report (11 §5); `lc why` forensics (04 §6);
Codex of Aden UI (04 §5); engineering doctrine (02).

New-in-v2 items were designed after the Claude × Codex consensus and have not been through adversarial debate.
They follow the consensus mechanisms (Asset Service, Intel Service, Fact Journal, authority table) and are subject to the
same proof obligations.
