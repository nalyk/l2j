# 03 — Platform

## 1. Baseline (verified against upstream `2b0758e9`, 2026-11-09)

- Mobius `L2J_Mobius_CT_2.6_HighFive`: ~364k LOC core Java, ~306k LOC datapack scripts (1291 script files).
- Build: Ant, **Java 27** required (`build.xml` `checkRequirements`, `javac source/target="27"`). No test infrastructure upstream.
- DB: MySQL protocol via `mysql-connector-j` 9.5; we run MariaDB (InnoDB everywhere).
- Protocol revisions accepted: 267, 268, 271, 273 (`config/Server.ini AllowedProtocolRevisions`).
- Events: 89 `EventType`s in `mechanics/events/EventType.java`. Missing for our needs: craft, private store, P2P trade, NPC
  buy/sell, private warehouse, sweep/spoil, enchant, mail, Territory War, fortress ownership, party join/leave, Seven Signs
  period, Hellbound level, Manor results, gatekeeper teleport.
- RNG: `commons/util/Rnd.java` → `ThreadLocalRandom`, not seedable.
- Fake Players: NPC-backed (`data/xml/FakePlayerData.java`). Not used by Living Chronicle (replaced by Citizens).
- Item persistence: `Item.updateInDb` opens its own connection and logs-and-continues on failure.
- Startup cleanup: `DatabaseIdManager.java:67` deletes items whose owner is not a character or clan (except owner −1);
  used-ID extraction (lines 122–128) scans characters, items, clan_data, itemsonground, messages.
- Party loot: `Party.distributeItem` picks the looter and adds the item immediately (Party.java ~677–686).
- Visibility: `Creature.updateSeenCreatures` (Creature.java:7465–7484) is range + invisibility only, no line of sight.
- Packet encryption default `PacketEncryption = False` ("Retail: True"); game channel is stateful XOR (`network/Encryption.java`).
- Custom NPC visuals without client edits: `NpcTemplate` `displayId` + `usingServerSideName` (NpcTemplate.java:137–144,
  serialized in AbstractNpcInfo.java:106, 163–200). Custom *items* need client data entries.
- Community Board HTML: up to three 16250-character chunks, rejected at ≥48750 (`util/HtmlUtil.java:411–453`, multiedit is a
  separate path). NPC HTML truncates above 17200 (`AbstractHtmlPacket.java:73–78`).

## 2. Toolchain (pinned by version and digest in `toolchain.lock`)

Temurin JDK 27.0.1; Ant ≥ 1.10.14 + matching `ant-junitlauncher`; JUnit Platform/Jupiter; MariaDB 11.x (container image
digest); Testcontainers; acmi xdat tooling in a separate legacy JDK 8 container; l2mapconv (geodata) pinned build;
client-data `.dat` patch pipeline pinned (encrypt/decrypt + table editors). Build is reproducible from a clean checkout with
one command (`./build.sh`) inside a pinned container image.

## 3. Fork and upstream intake

- Repository layout: `upstream/` branch mirrors Mobius H5 verbatim; `main` is our fork.
- **Intake job** (scheduled agent): fetch upstream, classify commits (fix / content / refactor / build), cherry-pick or merge,
  re-apply `CORE_PATCHES.md`, run the full gate. Merge only if all pass:
  behaviour lock, Retail Truth, Living Chronicle suites, migration + recovery tests against representative persisted
  contracts and escrow, client-compatibility checks. An intake change MUST NOT regenerate the goldens it is judged by.
- Upstream moves fast (Java 25 → 27 and `model/events` → `mechanics/events` within weeks). Intake runs weekly; large
  refactors are taken as a single reviewed merge.

## 4. The core seam (everything we change inside Mobius)

Each item is a ledgered core patch with its own tests.

1. **Asset Service integration** — all item/Adena mutations that touch Living Chronicle assets go through one
   transaction-aware path (04 §3). Includes: `ItemLocation.ESCROW` with custody reference; `DatabaseIdManager` cleanup
   recognising custody; used-ID extraction reserving escrow ids; inventory restore never loading escrow rows.
2. **Loot routing** — a pre-distribution hook in `Party.distributeItem` / `distributeAdena` consulted before any looter is
   chosen, so signed Loot Charters route reserved and auctioned items atomically to charter escrow.
3. **Crafting operation boundary** — `RecipeManager` craft split into validate → roll → apply, sharing retail code, so PACT
   can supply escrowed materials, route output to escrow, and persist the rolled outcome (no reroll on recovery).
4. **New EventTypes** (immutable primitive snapshots captured at the authoritative point, after validation):
   `ON_PLAYER_CRAFT`, `ON_PLAYER_PRIVATE_STORE_TRADE`, `ON_PLAYER_TRADE_COMPLETE`, `ON_PLAYER_NPC_BUY`, `ON_PLAYER_NPC_SELL`,
   `ON_PLAYER_WAREHOUSE_DEPOSIT`, `ON_PLAYER_WAREHOUSE_WITHDRAW`, `ON_PLAYER_SWEEP`, `ON_PLAYER_ENCHANT`, `ON_PLAYER_MAIL_SEND`,
   `ON_PLAYER_MAIL_RECEIVE`, `ON_PLAYER_PARTY_JOIN`, `ON_PLAYER_PARTY_LEAVE`, `ON_PLAYER_GATEKEEPER_TELEPORT` (npc, destination,
   fee), `ON_TERRITORY_WAR_START/FINISH`, `ON_TERRITORY_WARD_CAPTURE`, `ON_FORT_OWNER_CHANGE`, `ON_FORT_CONTRACT_CHANGE`,
   `ON_SEVEN_SIGNS_PERIOD_CHANGE`, `ON_HELLBOUND_LEVEL_CHANGE`, `ON_MANOR_PERIOD_RESULT`, `ON_CASTLE_TAX_CHANGE`,
   `ON_CURSED_WEAPON_ACQUIRE/DROP`, `ON_RAID_BOSS_KILL` (participants snapshot), `ON_PLAYER_HUNTING_SAMPLE`
   (aggregation-friendly kill sample: region, mob family, drop/spoil yield).
5. **Line-of-sight sighting predicate** — a server-side `canWitness(observer, target)` using geodata LoS, instance, distance,
   visibility and minimum observation time; used by the Intel Service, never by combat.
6. **Keyed RNG streams + controllable clock** for test/sim profiles; production behaviour unchanged.
7. **Packet pacing hooks** — per-client pacing metrics so the runner and humans can be measured against flood protectors.
8. **Codex bypass router** — one registered Community Board prefix (`_lc`) dispatching to the Codex framework.

Nothing else in core changes. Living Chronicle code lives in `org.l2jmobius.gameserver.livingchronicle.*`, datapack scripts
under `data/scripts/livingchronicle/`, schema under `sql/migrations/`.

## 5. Native H5 systems inventory and authority table

The Director and the modules may **observe** all of these and may **act** only through the listed lawful inputs.
They never overwrite earned ownership, scores, periods or results.

| System | Code | Authoritative owner | Living Chronicle may act via |
|---|---|---|---|
| Castles (9), taxes, treasury | `mechanics/siege/Castle.java`, `CastleManager` | siege results; lord sets tax within Seven Signs cap (`validateTaxes`: Dusk 5 %, none 15 %, Dawn 25 %) | Crown Law spends treasury the lord commits (12 §6.2); never changes tax caps |
| Castle sieges | `SiegeManager` | siege engine | PACT mercenary contracts settle on facts; Chronicle records |
| Fortresses (21), castle contracts | `Fort.java`, `FortManager`, `FortSiegeManager` | fort siege + contract rules | observe contract/ownership; caravans route through owned forts |
| Territory Wars, wards, mercenary registry | `TerritoryWarManager` (`_registeredMercenaries`) | TW engine | PACT mercenary contracts reference TW registration; Chronicle records ward captures |
| Conquerable clan halls (6) | `data/scripts/conquerablehalls/*`, `CHSiegeManager` | hall siege scripts | observe; Chronicle |
| Auctionable clan halls | `ClanHallAuctionManager` | auction | observe |
| Seven Signs, seals, festival | `mechanics/sevensigns/*` | period engine (`SevenSigns.java:1514–1616`) | observe; Festival gaps implemented as H5 content (`SevenSignsFestival.java:62–68`) |
| Manor | `CastleManorManager` | Manor periods | Director reads crop/seed results; lords' Manor choices shape regional supply |
| Hellbound | `ai/areas/Hellbound/HellboundEngine.java` | trust/level engine | Director creates situations where players can earn trust; never sets level |
| Seed of Destruction / Infinity | `SeedOfDestructionManager`, `SeedOfInfinityManager` | seed state engines | observe; SoD defense state implemented as H5 content (`SeedOfDestructionManager.java:110–132`) |
| Grand bosses | `ai/bosses/*` (Antharas, Valakas, Baium, Beleth, Core, Orfen, QueenAnt, Frintezza, Sailren, …) | boss AI, `GrandBossManager` | boss ecology context (12 §5.5); never relocation or stat change |
| Raid bosses | `RaidBossSpawnManager`, `RaidBossPointsManager` | respawn engine | observe; Signal raid intents; Loot Charters |
| Olympiad / Heroes | Olympiad engine | match results | Chronicle records heroes and records |
| Cursed weapons (Zariche, Akamanah) | `CursedWeaponsManager`, `data/CursedWeapons.xml` | cursed weapon engine | automatic public Writ eligibility (16 §2.2) |
| Mail, cash-on-delivery | `MailManager`, `network/holders/MailMessage.getReqAdena`, `RequestPostAttachment` | mail engine | PACT may settle *through* the Asset Service; COD mail stays a separate retail feature |
| Item auction | `ItemAuctionManager` | auction | observe prices |
| Vitality, Nevit | `VitalityTask`, `entity/actor/holders/player/Nevit.java` | retail | none |
| Clan academy, reputation, clan skills | clan engine | retail | Chronicle; Signal (academy mentorship intents) |
| Mini-games (Underground Coliseum, Krateis Cube, Fishing Championship, Monster Race, Lottery) | `managers/games/*` | retail | Chronicle records champions |

**H5 content inventory (N1):** every quest, instance, boss and system above gets a status from Retail Truth
(EXACT/FUNCTIONAL/APPROXIMATE/BROKEN/MISSING/UNKNOWN). Known incomplete paths (SoD defense, Festival) are implemented.

## 6. Client package

- Pinned H5 client build (hashes recorded). Testers get one ZIP: client + `system/` patch. Signed updater before live.
- `.dat` patch pipeline (pinned, validated): new items (Notarized Contract parchments by type, Torn Black Seal, calling-card
  tokens, Wayfarer's Journal, Aden Gazette issue, Wanted Poster), their icons, system messages, NpcStrings, Codex button strings.
- New NPCs (Notarius tiers, Magistrate, Broker, Herald, Bard, Town Crier, Caravan Master, Newsboy) reuse client models via
  `displayId` + server-side names; no client NPC edits.
- `interface.xdat` edits (acmi tooling, legacy container) only if a UX test proves a native widget is required (04 §5.3).
- Validation in the pinned client: layouts, item use, Cyrillic/Romanian text, CB size bounds.

## 7. Geodata

Generated locally from the exact pinned client with pinned l2mapconv; generator and dependency hashes, settings and correction
patches recorded; no third-party geodata packs. Acceptance: full required-region coverage (startup fails if any required
region falls back to `NullRegion`), plus tests for cross-region routes, multilevel floors, stairs, doors, water, siege
structures, line of sight and instances. The same validated static dataset is used by the citizen runner for navigation.

## 8. Mobius H5 custom modules: canonical policy

| Config | Policy | Reason |
|---|---|---|
| AllowedPlayerRaces | all races | retail |
| AutoPlay | OFF | deletes social dependency |
| AutoPotions | OFF | not retail H5; consumables management is productive pain |
| Banking | OFF | Adena is the single economic truth |
| BossAnnouncements | OFF | boss news travels through bards, Gazette and rumors |
| CancelReturn | OFF | not retail |
| Captcha | OFF in hobby; ON for humans in live | humans are ≤10 and known; citizens are a declared population |
| ChampionMonsters | OFF | distorts regional economics |
| ChatModeration | ON | admin tool |
| ClassBalance | neutral (all 1.0) | behaviour lock |
| CommunityBoard (custom services) | OFF | CB belongs to the Codex; no buff/teleport/shop bypass of the world |
| CustomMailManager | ON, internal only | website → game deliveries of Chronicle items (e.g. monument plaques) |
| DelevelManager | OFF | |
| DualboxCheck | humans: 2 per IP; runner IP: per-principal policy | explicit, not disabled |
| FactionSystem | OFF | factions emerge socially |
| FakePlayers | OFF | replaced by Citizens |
| FindPvP | OFF | location certainty conflicts with Black Ledger |
| FreeMounts | OFF | geography |
| HellboundStatus | OFF | Hellbound progress is learned diegetically |
| MerchantZeroSellPrice | OFF | |
| MultilingualSupport | ON (EN, RO, RU) | |
| NoblessMaster | OFF | |
| NpcStatMultipliers | neutral | behaviour lock |
| OfflinePlay | OFF | presence must mean presence |
| OfflineTrade | ON, peace zones | market depth; counted as supply, never presence |
| OnlineInfo | OFF | |
| PasswordChange | ON | |
| PremiumSystem | OFF (incl. its PC Cafe / PC Bang point settings) | no paid or timed reward multipliers in the world model |
| PrivateStoreRange | ON | readable markets |
| PvpAnnounce | OFF | uncertainty |
| PvpRewardItem | OFF | |
| PvpTitleColor | OFF | Black Ledger reputation is information, not colours |
| RandomSpawns | OFF | the Director owns spawn-context changes |
| Rebirth | OFF | |
| SchemeBuffer | OFF | buffers are people |
| ScreenWelcomeMessage | ON | one line of world flavour from the Gazette |
| SellBuffs | ON | buffer profession |
| ServerTime | ON | deadlines, sessions, sieges |
| StartingLocation | OFF | retail geography |
| StartingTitle | OFF | |
| Transmog | OFF | gear grade is information |
| WalkerBotProtection | ON | |
| WarehouseSorting | ON | QoL with no balance effect |
| Wedding | ON | social history; no power |

## 9. Deployment (hobby)

Docker Compose stack on `build-host` (Ryzen 9 9950X 16C/32T, 123 GiB, RTX 5090 32 GB, NVMe):

| Container | Role | Network |
|---|---|---|
| `mariadb` | game DB + LC schema | private |
| `login` | LoginServer | 2106 shared, 9014 private |
| `game` | GameServer + Living Chronicle | 7777 shared |
| `runner` | Citizen runner | private, talks to login/game |
| `narrator` | local LLM (GPU) behind a fact-DTO API | private |
| `web` | Chronicle website (read-only publication DB user) | 443 shared |
| `tailscale` | persistent node for the stack's network namespace | — |

- Testers: the stack's Tailscale node is shared to ≤10 individual accounts (node share, not tailnet membership).
  Grants: `autogroup:shared` → that node on `tcp:2106, tcp:7777, tcp:443` only. Allowed/denied ports tested from a real sharee.
  `ipconfig.xml` advertises the tailnet address to testers and the private address to the runner. No office router change.
- Live ingress (later, different audience): external public ingress with DDoS protection, specified in N14 with its own
  activation criteria. Nothing else in the stack changes.
- Backups: nightly MariaDB snapshot + binlog, pre-migration snapshot, monthly restore drill with escrow/contract consistency
  verification.
- Host isolation: CPU sets reserved for `game` + `mariadb`; hard CPU caps on the host's CI runners; explicit Mobius pool sizing.

## 10. Capacity (measured, never assumed)

Benchmarks (all with simultaneous CI + LLM load on the host): dispersed farming; dense private-store markets; craft/trade
bursts; instance transitions; **800-citizen castle siege** with real combat, movement, visibility fan-out and live contract
processing. Measured: scheduler lateness, packet-queue age, combat acknowledgement latency, pathfinding time, DB commit
latency, journal lag, RSS, mapped memory (GeoEngine mmaps regions), disk growth, GC. A failing benchmark is fixed by removing
the bottleneck or adding resources, never by cutting a feature.
