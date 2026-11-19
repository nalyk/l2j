# SPEC-PRO — Profiles and Mobius custom modules

- Status: draft (revision 1)
- Blueprint: 02 §8 (configuration is a liability; the canonical ruleset is the default; every key has an owner, range,
  default and test), 03 §8 (Mobius H5 custom modules: canonical policy), 10 §9 (citizens' own bounds), 11 §3 (the
  `truth` profile), 20 §1.
- Depends on: actor-identity.md (§3 policy, for the one origin-dependent rule), chronicle.md (the welcome line), every
  module spec's §8 (their per-profile values).
- DDL: none.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/` unless they start with `dist/`.

## 1. Scope

Every spec gives its keys a value per **profile** (`truth`, `dev`, `sim`, `hobby`, `live`). This spec owns how a server
knows its profile, how a profile's values reach every configuration file, Mobius's and Living Chronicle's alike, the
check that refuses to start a server whose effective values break its profile, and the canonical policy of each of
Mobius's 44 custom modules (`dist/game/config/Custom/*.ini`) per profile.

It does not own any module's own keys or their meaning (their specs do), nor the behaviour lock and Retail Truth cases
(11 §1–§2), which run in `truth`.

## 2. Data

```text
dist/game/config/**/*.ini                      the canonical files, as upstream ships them plus Living Chronicle's
dist/game/config/profiles/<profile>/**/*.ini   overlays: only the keys a profile changes, same relative paths
dist/game/data/lc/custom_policy.csv            module, file, key, truth, dev, sim, hobby, live, reason
```

- **The profile** is the system property `lc.profile` given at launch (one of the five); without it the server runs the
  canonical files (no overlay) and refuses to start Living Chronicle (its modules need a profile, §4.2).
- An **overlay** holds only keys its profile changes. The `truth` overlay is not hand-written: it is generated from the
  rule `TruthProfile` already applies to the behaviour lock's staged copy (every custom switch off except those retail
  as shipped, neutral values for the non-switch settings; `test/java/org/l2jmobius/testing/TruthProfile.java`) plus every
  Living Chronicle module's `Enabled = false`, and a test checks that the committed overlay equals the rule.
- `custom_policy.csv` is §4.3's table as data, read by the startup check.

## 3. State machines

None: the profile is fixed for a process's life.

## 4. Operations

### 4.1 Loading

Every configuration file of the game server is read through one constructor, `ConfigReader(String filePath)`
(`commons/util/ConfigReader.java:52`), which loads a `Properties`. The profile core patch adds, after that load: if
`lc.profile` is set and `config/profiles/<profile>/<the same relative path>` exists, it is loaded over the same
`Properties`, so its keys replace the canonical ones and every other key keeps its canonical value. Nothing else reads
configuration files, so no file escapes the overlay. The effective value of every key, the profile and the overlay's
hash are written as a `config` fact at startup (fact-journal.md §4.1), so replay and `lc why` see what the server ran with.

### 4.2 The startup check

Before the world loads, the profile check compares the effective values with `custom_policy.csv` and with each Living
Chronicle module's per-profile values (their specs' §8), and **refuses to start** naming every difference:

- `truth`: every Living Chronicle module disabled; every custom switch off except those retail as shipped; neutral
  values; the keyed RNG and controlled clock of the behaviour lock (11 §3).
- `dev`, `sim`, `hobby`, `live`: every key as §4.3 and the modules' specs say for that profile.

An admin who wants a different value changes the overlay (a reviewed change of this spec's table for policy keys), never
a canonical file in place; the check is what makes a hand edit visible.

### 4.3 The custom modules

Columns are the profiles; "off" means the module's switches as `TruthProfile` sets them (every `True` switch `False`).
`dev` and `sim` equal `hobby` unless stated.

| Module (file) | Keys that differ from canonical | truth | hobby | live | Reason (03 §8) |
|---|---|---|---|---|---|
| AllowedPlayerRaces | — | as shipped | as shipped | as shipped | retail: every race |
| AutoPlay, AutoPotions | all switches | off | off | off | not retail; consumables are productive pain; presence |
| Banking | `BankingEnabled` | off | off | off | Adena is the single economic truth |
| BossAnnouncements | all | off | off | off | news travels through bards, the Gazette and rumors |
| CancelReturn, DelevelManager, MerchantZeroSellPrice, NoblessMaster, OnlineInfo, Rebirth, StartingTitle | all | off | off | off | not retail, no reason to differ |
| Captcha | `EnableCaptcha` | off | off | **on, for human accounts only** | humans are few and known in hobby; citizens are a declared population (§4.4) |
| ChampionMonsters | `ChampionEnable` | off | off | off | distorts regional economics |
| ChatModeration | `ChatAdmin` | as shipped (on) | on | on | admin tool |
| ClassBalance | every multiplier | empty (neutral) | empty | empty | the behaviour lock |
| CommunityBoard | `CustomCommunityBoard` | off | off | off | the board belongs to the Codex; no buff, teleport or shop bypass of the world |
| CustomMailManager | `CustomMailManagerEnabled` | off | **off** | **off** | a deviation from 03 §8, below |
| DualboxCheck | `DualboxCheckMaxPlayersPerIP`, `DualboxCheckWhitelist` | 0 (none) | 2; the citizen runner's address whitelisted | 2; likewise | explicit, not disabled; citizens are bounded per principal by their own policy (10 §9) |
| FactionSystem, FakePlayers, FindPvP, FreeMounts, HellboundStatus | all | off | off | off | factions emerge socially; citizens replace fake players; location certainty against the Ledger; geography; Hellbound learned diegetically |
| MultilingualSupport | `MultiLangEnable`, `MultiLangAllowed`, `MultiLangDefault` | off | on, `en;ro;ru`, `en` | on, likewise | the Codex and Chronicle render EN, RO, RU; Mobius's own pages fall back to English until RO and RU packs exist (`dist/game/data/lang/` holds only `el`) |
| NpcStatMultipliers | `EnableNpcStatMultipliers` | off | off | off | the behaviour lock |
| OfflinePlay | all | off | off | off | presence must mean presence |
| OfflineTrade | `OfflineFame`, `OfflineMaxDays` (with the canonical `OfflineTradeEnable`, `OfflineCraftEnable`, `OfflineModeInPeaceZone`) | off | on in peace zones, `OfflineFame` **false**, `OfflineMaxDays` 14 | likewise | market depth, counted as supply and never presence (actor-identity.md §2.3); `OfflineFame = True` lets a detached client keep earning siege-zone fame (`gameserver/entity/actor/tasks/player/FameTask.java:48`), a reward without presence |
| PasswordChange | `AllowChangePassword` | off | on | on | account hygiene |
| PremiumSystem | `EnablePremiumSystem`, `PcCafeEnabled` | off | off | off | no paid or timed reward multipliers |
| PrivateStoreRange | the two ranges | 0, 0 (neutral) | as shipped (50, 100) | as shipped | readable markets |
| PvpAnnounce, PvpRewardItem, PvpTitleColor | all | off | off | off | uncertainty; the Ledger's reputation is information, not colours |
| RandomSpawns | `EnableRandomMonsterSpawns` | off | off | off | spawns are retail (Aden Remembers changes none, aden-remembers.md §4.3) |
| SchemeBuffer | — (its NPC 50008, `dist/game/data/stats/npcs/custom/SchemeBuffer.xml`, has no spawn) | not spawned | not spawned | not spawned | buffers are people |
| ScreenWelcomeMessage | `ScreenWelcomeMessageEnable` | off | on | on | one line of world flavour: the text is Chronicle's latest Gazette headline (§4.5) |
| SellBuffs | `SellBuffEnable` | off | on, as shipped otherwise | on | the buffer's profession |
| ServerTime | `DisplayServerTime` | as shipped (on) | on | on | deadlines, sessions, sieges |
| StartingLocation, Transmog | all | off | off | off | retail geography; gear grade is information |
| WalkerBotProtection | `L2WalkerProtection` | off | on | on | anti-bot |
| WarehouseSorting | both switches | off | on | on | comfort without balance effect |
| Wedding | `AllowWedding`, `WeddingTeleport`, `WeddingPunishInfidelity` | off | on; teleport **false**; punishment **false** | likewise | social history and no power: a teleport to one's spouse is location certainty (like FindPvP, against the Ledger), and the infidelity punishment is a penalty retail never had |

**Deviations from 03 §8**, each decided here with its reason:
- `CustomMailManager` stays off: 03 §8 meant it for website-to-game deliveries, but every item Living Chronicle gives
  must be an Asset operation at a classified site (asset-service.md §2.7), the website's database user can only read
  publication rows (chronicle.md §4.9), and no spec needs a delivery from the website. Mail Living Chronicle sends is the
  Asset Service's service mail.
- `OfflineFame`, `WeddingTeleport` and `WeddingPunishInfidelity` are off within modules 03 §8 turns on, for the reasons
  in the table.

### 4.4 Captcha for human accounts

In `live`, Mobius's captcha (`Captcha.ini`) applies to human accounts only: the citizen runner cannot answer it and the
citizens are a declared population (10). Whether an account is subject to it is a **participation** rule asked through
`Policy.allows(CAPTCHA, SUBJECT, snapshot)` (actor-identity.md §3), the only place origin is read; the captcha's own
mechanics are Mobius's, unchanged. In every other profile the captcha is off for everyone.

### 4.5 The welcome line

With `ScreenWelcomeMessageEnable` on, the text shown is not `ScreenWelcomeMessageText` but the lead headline of the
latest published Gazette issue in the player's language (chronicle.md §4.3), rendered through Chronicle's names rule; with
no issue yet, the canonical text. This is a seam in the welcome message's sender, owned by Chronicle (an amendment to
chronicle.md, §11).

## 5. Facts

`Config.Loaded` (the profile, the overlay hash, the effective values of every key; a `config` fact) at startup.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PRO-INV01 | every key's effective value is its profile's | the startup check; AT-PRO-02 |
| PRO-INV02 | the `truth` overlay equals `TruthProfile`'s rule plus every module disabled | AT-PRO-03 |

## 7. Failure model

| Fault | End state |
|---|---|
| no `lc.profile` | canonical files; Living Chronicle refuses to start |
| an unknown profile name | refuses to start |
| an effective value off its profile | refuses to start, naming every difference |
| an overlay naming a key no file has | refuses to start (a typo is not a no-op) |

## 8. Configuration

`lc.profile` (system property): `truth`, `dev`, `sim`, `hobby`, `live`; no default for Living Chronicle. Every other key
is in §4.3 or a module's spec.

## 9. Interface

None for players. Admin: `lc config <file> <key>` prints the canonical value, the overlay's and the effective one.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PRO-R01 | Every configuration file MUST be read with its profile's overlay applied, at the one reading point. | AT-PRO-01 |
| PRO-R02 | A server MUST refuse to start when any effective value differs from its profile's, or its overlay names an unknown key. | AT-PRO-02 |
| PRO-R03 | The `truth` overlay MUST equal the behaviour lock's rule with every Living Chronicle module disabled. | AT-PRO-03 |
| PRO-R04 | Each custom module MUST have, per profile, exactly §4.3's values. | AT-PRO-04 |
| PRO-R05 | In `live` the captcha MUST apply to human accounts only, decided through the policy registry. | AT-PRO-05 |
| PRO-R06 | The effective configuration MUST be recorded as a `config` fact at startup. | AT-PRO-01 |

| Test | Kind | What it does |
|---|---|---|
| AT-PRO-01 | integration | each profile: every key of every file read through `ConfigReader` equals overlay-or-canonical; the `config` fact holds them and the overlay hash |
| AT-PRO-02 | integration | a hand edit of a policy key, an overlay with an unknown key, an unknown profile, no profile with Living Chronicle: each refused at startup with the difference named |
| AT-PRO-03 | unit | the committed `truth` overlay equals `TruthProfile`'s rule over the canonical files plus every module's `Enabled = false`; LOCK-CONFIG-0001 still passes |
| AT-PRO-04 | unit | `custom_policy.csv` covers all 44 modules of `TruthProfile.MODULES` and every switch of each file; each profile's overlay yields exactly its column |
| AT-PRO-05 | integration | in `live`, a human account meets the captcha at its kill counter and a citizen account does not; in `hobby` neither does |

## 11. Upstream constraints

1. **One reading point** (`commons/util/ConfigReader.java:52`). → the overlay there.
2. **Custom NPC templates load with `CustomNpcData = True`** (`dist/game/config/General.ini:655`) **but are spawned only by
   data**: the scheme buffer has no spawn. → it stays unspawned.
3. **`OfflineFame` defaults to true** (`gameserver/config/custom/OfflineTradeConfig.java:63`). → false in every profile.

**Amendments to reviewed or drafted specs**: chronicle.md §4.3, the welcome-line seam (§4.5 here).

## 12. Review record

- Revision 1 (draft): not yet reviewed.
