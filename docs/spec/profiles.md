# SPEC-PRO — Profiles and Mobius custom modules

- Status: draft (revision 3, answering two adversarial review rounds; record in §12)
- Blueprint: 02 §8 (configuration is a liability; the canonical ruleset is the default; every key has an owner, range,
  default and test), 03 §8 (Mobius H5 custom modules: canonical policy), 10 §5.4 and §9 (citizens' own bounds), 11 §3
  (the `truth` profile), 20 §1.
- Depends on: chronicle.md (the welcome line), aden-remembers.md (its `Enabled`), every module spec's §8.
- DDL: none.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/` unless they start with `dist/`.

## 1. Scope

Every spec gives its keys a value per **profile** (`truth`, `dev`, `sim`, `hobby`, `live`). This spec owns how a server
runs a profile, how the profile's values become the files every Mobius and Living Chronicle reader actually reads, the
check that refuses a server whose files are not exactly its profile's, and the canonical policy of each of Mobius's 44
custom modules (`dist/game/config/Custom/*.ini`) per profile.

It does not own any module's own keys or their meaning (their specs do), nor the behaviour lock and Retail Truth cases
(11 §1–§2), which run in `truth`.

## 2. Data

```text
dist/game/config/**, dist/login/config/**  the canonical configuration, as upstream ships it plus Living Chronicle's
dist/game/data/scripts/**/config.xml       event configuration files (TvT, Deathmatch, the seasonal events), also canonical
dist/game/profiles/<profile>/**            overlays: for an .ini, only the keys the profile changes; any other file
                                           (XML, txt) replaced whole; same relative paths
dist/game/profiles/canonical.sha256        the hash of every canonical file
dist/game/data/lc/custom_policy.csv        every key of the 44 custom files: module, file, key, value per profile, reason
<server root>/config/                      the materialised tree the server reads (§4.1), read-only
```

- **The canonical set** is both servers' `config/` trees and every `config.xml` under the datapack's scripts (event
  schedules and settings, read by the events themselves, e.g. `dist/game/data/scripts/custom/events/TeamVsTeam/TvT.java:179`);
  a test scans the datapack and both servers' code for files they open as configuration and fails on one outside the set.
- Overlays live outside `config/`, so the configuration lock (LOCK-CONFIG-0001, `test/java/org/l2jmobius/gameserver/
  ConfigLockTest.java:36-38`, which reads every `.ini` under `config`) sees only canonical files and the materialised
  `truth` tree, as it does today.
- The `truth` overlay is not hand-written: it is what `TruthProfile` does to the staged copy
  (`test/java/org/l2jmobius/testing/TruthProfile.java`: every custom switch off except those retail as shipped, neutral
  values for the non-switch settings), and a test checks the committed overlay equals that rule.
- `custom_policy.csv` lists **every** key of the 44 files, numeric ones included (multipliers, ranges, limits), so the
  check has an expected value for each.

## 3. State machines

None: the profile and the materialised tree are fixed for a process's life.

## 4. Operations

### 4.1 Materialising the profile

Mobius reads configuration in several ways, not only through `ConfigReader` (`commons/util/ConfigReader.java:52`):
`FortSiegeManager` loads `config/FortSiege.ini` with its own `Properties` (`gameserver/managers/FortSiegeManager.java:124-140`),
XML data readers parse configuration files (`DynamicExpRateData.java:53`, `SecondaryAuthData.java:50`,
`ScriptEngine.java:72`, the datapack's `ClassMaster.java:95`), and `ServerConfig` reads `chatfilter.txt` and
`ipconfig.xml` directly (`gameserver/config/ServerConfig.java:259`, `:424`). So no reader is patched. Instead the launcher,
before the server starts, **materialises** the profile: it copies the canonical tree, applies the overlay (an `.ini`
overlay replaces whole **logical properties**, parsed as Java properties are (a value continued over several physical
lines, such as `SkillDurationList` in `dist/game/config/Player.ini:69`, is replaced whole and written as one logical
property), refusing a key the canonical file does not have; any other overlay file replaces its canonical file or, where
the canonical tree has none, creates it), then applies the **machine staging** (the database settings, `ipconfig.xml`,
which `gameserver/config/ServerConfig.java:420` looks for instead of the shipped `default-ipconfig.xml`, and the test
harness's own staging, `test/java/org/l2jmobius/testing/TestServer.java:154`), writes the result read-only, and records the
hash of every materialised file but the machine-staged ones. For `truth` the overlay step is `TruthProfile.apply` run on
the staged copy before it is made read-only, exactly as the lock runs it; Living Chronicle's own `.ini` files are in the
canonical tree, so the lock's recursive golden includes them with their `truth` values. Every reader, whatever its method, then reads the profile's values. This is what `TruthProfile.apply`
already does to the behaviour lock's staged copy; the same code produces every profile.

### 4.2 The startup check

Before the world loads, the profile check refuses to start, naming every difference, unless:

1. every canonical file's hash equals `canonical.sha256` (a hand edit of a canonical file is visible, and the canonical
   files themselves are locked by LOCK-CONFIG-0001 in CI);
2. every materialised file equals the materialisation of the canonical tree with the profile's overlay (recomputed);
3. every key of the 44 custom files has the value `custom_policy.csv` gives for the profile, and every Living Chronicle key
   the value its spec's §8 gives;
4. in `truth`, Living Chronicle starts **no module**: its bootstrap reads the profile and, for `truth`, initialises
   nothing (one gate, not a key per module); in the other profiles Aden Remembers' `Enabled` (aden-remembers.md §8) is as
   its spec says.

The profile, the overlay's hash and the materialised hashes are written as a `config` fact (fact-journal.md §4.1).
**Reloads**: Mobius re-runs `ConfigLoader.init()` from an admin command and the GUI (`dist/game/data/scripts/handlers/
chat/commands/admin/AdminReload.java:169`, `gameserver/ui/Gui.java:198`), and the same admin command reloads
`DynamicExpRates.xml` and `SecondaryAuth.xml` directly (`AdminReload.java:199`, `:463`); the profile core patch runs the
same check before each of the four and refuses the reload on any difference, so a reload rereads the same materialised
files or nothing.

### 4.3 The custom modules

"Off" means **every** switch of the module's file false (its master and any switch that works on its own, such as the
Community Board's services or `BankingAutoConvert`), and its numeric settings at their canonical values. Columns are the
profiles; `dev` and `sim` equal `hobby` unless stated; `truth` is always `TruthProfile`'s rule.

| Module (file) | truth | hobby, live | Reason (03 §8) |
|---|---|---|---|
| AllowedPlayerRaces | as shipped (every race) | as shipped | retail |
| AutoPlay, AutoPotions | off | off | not retail; consumables are productive pain; presence |
| Banking (`BankingEnabled`, `BankingAutoConvert`, the latter checked on its own by `dist/game/data/scripts/custom/BankingAutoConvert.java:41`, `:49`) | off | off | Adena is the single economic truth |
| BossAnnouncements | off | off | news travels through bards, the Gazette and rumors |
| CancelReturn, DelevelManager, MerchantZeroSellPrice, NoblessMaster, OnlineInfo, Rebirth, StartingTitle | off | off | not retail |
| Captcha | off | **off** | a deviation, below |
| ChampionMonsters | off | off | distorts regional economics |
| ChatModeration (`ChatAdmin`) | off | on | admin tool |
| ClassBalance | every multiplier empty (neutral) | empty | the behaviour lock |
| CommunityBoard (`CustomCommunityBoard` and `CommunityEnableMultisells`, `…Teleports`, `…Buffs`, `…Heal`, `…Delevel`, which ship true and register services on their own, `dist/game/data/scripts/handlers/bypass/communityboard/HomeBoard.java:79-88`, `:109-114`) | off | off | the board belongs to the Codex; no buff, teleport or shop bypass of the world |
| CustomMailManager | off | **off** | a deviation, below |
| DualboxCheck | as `TruthProfile` (0: no limit) | `DualboxCheckMaxPlayersPerIP` 2, `DualboxCheckMaxOlympiadParticipantsPerIP` 1, `DualboxCheckMaxL2EventParticipantsPerIP` 1, `DualboxCheckMaxOfflinePlayPerIP` and `…PremiumPerIP` 0, the citizen runner's address in `DualboxCheckWhitelist` as `address,RunnerAllowance` (upstream adds that number to every limit for the address, `gameserver/managers/AntiFeedManager.java:285-295`, and reads a zero as −1, `DualboxCheckConfig.java:66`; `RunnerAllowance` is the Citizens spec's maximum of citizens online, 1000 until it is specified) | explicit, not disabled; Olympiad and events limited for humans (0 would bypass the check, `gameserver/managers/OlympiadManager.java:546`); upstream keys the whitelist by address (`gameserver/config/custom/DualboxCheckConfig.java:62-77`), so the runner's per-principal limits, Olympiad and events included, are the citizens' own policy (10 §5.4, the Citizens spec) |
| FactionSystem, FakePlayers, FindPvP, FreeMounts, HellboundStatus | off | off | factions emerge socially; citizens replace fake players; location certainty against the Ledger; geography; Hellbound learned diegetically |
| MultilingualSupport | off | on, `MultiLangAllowed` `en;ro;ru`, `MultiLangDefault` `en` | the Codex and Chronicle render EN, RO, RU; Mobius's own pages fall back to English until RO and RU packs exist (`dist/game/data/lang/` holds only `el`) |
| NpcStatMultipliers | off | off | the behaviour lock |
| OfflinePlay | off | off | presence must mean presence |
| OfflineTrade | off | on in peace zones (canonical), `OfflineFame` **false**, `OfflineMaxDays` 14 | market depth, counted as supply and never presence (actor-identity.md §2.3); `OfflineFame = True` lets a detached client keep earning siege-zone fame (`gameserver/entity/actor/tasks/player/FameTask.java:48`) |
| PasswordChange (`AllowChangePassword`) | off | on | account hygiene |
| PremiumSystem (`EnablePremiumSystem`, `PcCafeEnabled` and the rest) | off | off | no paid or timed reward multipliers |
| PrivateStoreRange | 0, 0 (neutral, `TruthProfile`) | as shipped (50, 100) | readable markets |
| PvpAnnounce, PvpRewardItem, PvpTitleColor | off | off | uncertainty; the Ledger's reputation is information |
| RandomSpawns | off | off | spawns are retail (Aden Remembers changes none) |
| SchemeBuffer (no switch; its NPC 50008, `dist/game/data/stats/npcs/custom/SchemeBuffer.xml:3`, has no spawn) | not spawned | not spawned | buffers are people |
| ScreenWelcomeMessage | off | on; the text is Chronicle's (§4.5) | one line of world flavour |
| SellBuffs | off | on, otherwise as shipped | the buffer's profession |
| ServerTime (`DisplayServerTime`) | off | on | deadlines, sessions, sieges |
| StartingLocation, Transmog | off | off | retail geography; gear grade is information |
| WalkerBotProtection | off | on | anti-bot |
| WarehouseSorting | off | on | comfort without balance effect |
| Wedding | off | `AllowWedding` on, `WeddingTeleport` **false**, `WeddingPunishInfidelity` **false** | social history and no power: a teleport to one's spouse is location certainty; the infidelity punishment is a penalty retail never had |

`truth` is "off" even for modules that are on in the other profiles (ChatModeration, ServerTime): the truth profile is
`TruthProfile`'s rule with no exception but those retail as shipped, as LOCK-CONFIG-0001 requires
(`ConfigLockTest.java:97-106`).

**Deviations from 03 §8**, each decided here:
- **CustomMailManager** stays off: 03 §8 meant it for website-to-game deliveries, but every item Living Chronicle gives
  must be an Asset operation at a classified site (asset-service.md §2.7), the website's database roles can only read
  publication views (chronicle.md §2.1), and no spec needs such a delivery.
- **Captcha** stays off in `live`: 03 §8 wanted it on for humans only, but exempting citizens would make an origin rule
  decide a mechanic with consequences (a failed captcha teleports or jails), which actor-identity.md §3 forbids (its policy
  domains are participation in institutions, metrics and publication). Humans are few and known (10), and
  WalkerBotProtection stays on.
- **OfflineFame**, **WeddingTeleport** and **WeddingPunishInfidelity** are off within modules 03 §8 turns on.

### 4.4 Withdrawn

(The captcha rule of revision 1 is withdrawn, §4.3.)

### 4.5 The welcome line

With `ScreenWelcomeMessageEnable` on, the text shown at login (`gameserver/network/clientpackets/EnterWorld.java:632-634`
sends the configured text) is replaced by the **lead headline** of the latest issue: its first entry in section order
(Realm, Law and Disorder, History, Roads, Markets, Arena, Obituaries, Letters; within a section, issue order), rendered as
that entry's template title line in the player's language with names resolved through Chronicle's name view. An issue is
composed only with at least one entry (chronicle.md §4.3), so the latest issue always has a lead; with no issue yet, the
canonical text. The seam belongs to Chronicle (an amendment
to chronicle.md §4.3, §11).

## 5. Facts

`Config.Loaded` (the profile, the overlay hash, every materialised file's hash; a `config` fact) at startup and at every
accepted reload.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PRO-INV01 | every file the server reads is its profile's materialisation of the locked canonical tree | the startup and reload check; AT-PRO-02 |
| PRO-INV02 | the `truth` overlay equals `TruthProfile`'s rule, and in `truth` no Living Chronicle module starts | AT-PRO-03 |

## 7. Failure model

| Fault | End state |
|---|---|
| no `lc.profile` | **neither server starts**: upstream's own defaults are not the canonical ruleset (the Community Board's services, for one, ship on) |
| an unknown profile name | refuses to start |
| a canonical file edited by hand | refuses to start, naming it |
| an overlay key the canonical file lacks | materialisation refused (a typo is not a no-op) |
| a materialised file changed after start, then a reload | the reload refused |

## 8. Configuration

`lc.profile` (system property of the launcher and both servers): `truth`, `dev`, `sim`, `hobby`, `live`; required. Every other key is
in §4.3, `custom_policy.csv` or a module's spec.

## 9. Interface

None for players. Admin: `lc config <file> <key>` prints the canonical, overlay and effective values.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PRO-R01 | The server MUST read only its profile's materialisation of the canonical tree, whatever its reading method. | AT-PRO-01 |
| PRO-R02 | A server MUST refuse to start, or to reload, when a canonical file differs from its hash, a materialised file from its materialisation, or any key from its profile's value. | AT-PRO-02 |
| PRO-R03 | The `truth` overlay MUST equal the behaviour lock's rule, and in `truth` no Living Chronicle module MUST start. | AT-PRO-03 |
| PRO-R04 | Each custom module MUST have, per profile, exactly `custom_policy.csv`'s values for every key of its file. | AT-PRO-04 |
| PRO-R05 | withdrawn (revision 2): the human-only captcha would let origin decide a mechanic (§4.3). | — |
| PRO-R06 | The profile and the materialised hashes MUST be recorded as a `config` fact at startup and at every reload. | AT-PRO-01 |
| PRO-R07 | No custom NPC 03 §8 keeps out of the world MUST be spawned by data. | AT-PRO-05 |
| PRO-R08 | The welcome line MUST be the latest issue's headline in the player's language, or the canonical text without an issue. | AT-PRO-06 |

| Test | Kind | What it does |
|---|---|---|
| AT-PRO-01 | integration | each profile: a value changed by its overlay is the one read by `ConfigReader`, by `FortSiegeManager`'s own `Properties`, by the LoginServer's `LoginConfig`, by an event's `config.xml` (replaced whole) and by `ServerConfig`'s `chatfilter.txt`; an overlay of `SkillDurationList` replaces the whole continued value; an overlay creating a file the canonical tree lacks creates it; the `config` fact holds the hashes; the scan finds no configuration file outside the canonical set |
| AT-PRO-02 | integration | a hand edit of a canonical file, an overlay with an unknown key, an unknown profile, no profile at all (neither server starts), a materialised file changed before each of the four reloads: each refused with the difference named |
| AT-PRO-03 | unit, integration | the committed `truth` overlay equals `TruthProfile`'s rule over the canonical files; LOCK-CONFIG-0001 passes on the materialised `truth` tree; a `truth` server starts no Living Chronicle module |
| AT-PRO-04 | unit | `custom_policy.csv` covers every key of the 44 files of `TruthProfile.MODULES`; each profile's materialisation yields exactly its column, numeric keys included; "off" leaves no switch true (Community Board services, `BankingAutoConvert`) |
| AT-PRO-05 | unit | no spawn file of `dist/game/data/spawns` places NPC 50008 |
| AT-PRO-06 | integration | with no issue, the canonical text; an issue with a Law and a History entry leads with the Law entry's title line, in EN, RO and RU by the player's language; a headline naming a withdrawn character shows it unnamed |

## 11. Upstream constraints

1. **Configuration is read several ways** (`ConfigReader.java:52`; `FortSiegeManager.java:124-140`; XML readers;
   `ServerConfig.java:259`, `:424`). → materialisation, no reader patched.
2. **Configuration can be reloaded** (`AdminReload.java:169`, `Gui.java:198`). → the check before a reload.
3. **Service switches of the Community Board work on their own** (`HomeBoard.java:79-88`, `:109-114`). → "off" is every
   switch.
4. **A zero dualbox limit means none** (`OlympiadManager.java:546`); **the whitelist is by address**
   (`DualboxCheckConfig.java:62-77`). → explicit limits; citizens' per-principal policy in their spec.
5. **`OfflineFame` defaults to true** (`gameserver/config/custom/OfflineTradeConfig.java:63`). → false.

**Amendments**: chronicle.md §4.3, the welcome-line seam (§4.5); the Citizens spec (to come) owns the runner's
per-principal Olympiad and event limits.

## 12. Review record

- Round 2 (Codex), NO SIGN-OFF: event `config.xml` files and the LoginServer's config outside the materialised tree; a
  runner whitelist entry without its number; the lock harness and machine staging unplaced; two direct XML reloads; no
  rule for the welcome headline; no rule for creating a file or for continued properties; a no-profile fallback to
  upstream defaults. Revision 3: both servers' trees and the events' configs are canonical, with a scan test; the
  allowance number; staging after the overlay, `TruthProfile.apply` in the overlay step; the four reloads checked; the
  lead headline rule; logical properties and file creation; no profile, no server.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 11 findings: readers that bypass `ConfigReader` (FortSiege's
  own `Properties`, XML, txt); `truth` values contradicting `TruthProfile` and the lock; Community Board and Banking
  switches that work on their own; an `Enabled` key most modules lack; a check with no expectation for most keys; dualbox
  Olympiad and event limits left at zero; a captcha origin rule outside actor-identity.md's domains; overlays under
  `config` breaking the lock; reloads after the check; untested scheme-buffer and welcome-line promises. Revision 2: the
  profile is materialised before boot, so every reader reads it; `truth` is exactly `TruthProfile`'s rule; "off" is every
  switch; one Living Chronicle gate for `truth`; canonical hashes and a complete key table; explicit dualbox limits with the
  runner's per-principal policy in the Citizens spec; the captcha stays off; overlays outside `config`; reloads checked;
  tests for each promise.
