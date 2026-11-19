# SPEC-CHR — Chronicle of Aden

- Status: draft (revision 1)
- Blueprint: 17 (Chronicle of Aden); 01 §2.6 (deterministic engine, optional narration); 02 §7 (three processes; the
  narrator an external local service), §6 (budgets); 04 §4 (publication DTOs, a separate database user), §8 (narrator
  down); 10 §8 (Genesis: the Chronicle runs from day 0); 12 §9 (Aden Remembers decides what became history); 14 §7
  (opt-in personal history); 16 §2 (public Writs); 20 §1 (everything public reads publication DTOs only).
- Depends on: intel-service.md (the publication registry and `intel.publishable`), fact-journal.md (facts, projectors,
  effects), aden-remembers.md (history events, world facts), asset-service.md (item lifecycles, fees), pact.md and
  black-ledger.md (their publication types), wayfarers-memory.md (the publishable flag and milestones), actor-identity.md
  (the humans-only filter), codex-ui.md (pages).
- DDL: `sql/migrations/V016__lc_chronicle.sql` (after Aden Remembers' V015).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Chronicle makes public what already became history: **in the world** (monuments, NPCs that remember, bards, criers,
boards), in the **Aden Gazette**, as **item sagas**, as **saga replays**, and on the **website**. It is story, never a
dashboard: it states only what a publication row holds, after that row's delay.

It owns: its publication types (§2.2) and their projector; the Gazette; monuments and the other in-world voices; item
sagas; battle sampling for replays; the narrator boundary; the website process and its views.

It does not own: what becomes history (Aden Remembers, 12 §9), the registry and its field rules (the Intel Service,
intel-service.md §4.7), other modules' publication types (PACT's, the Ledger's, Signal's, Aden Remembers'), item
lifecycles (the Asset Service), or anyone's consent (Wayfarer's profile flag, wayfarers-memory.md §2.2).

**The rule of publication** (17 §1): everything public is a `lc_pub_*` row of a registered type, written by a projector
from durable facts after `intel.publishable` accepted it (intel-service.md §4.7); the website, the narrator and every
in-world voice read only those rows. Sealed terms, live locations, raw state values of any module and hidden identities
never enter them. A location-related type waits at least the tactical delay (24 h); a fact that is already public in the
game (a castle's owner, a Writ) may be published at once.

## 2. Data

### 2.1 The row store and names

```text
lc_pub_row(pub_row_id PK, pub_type, written_at, event_at, publish_at, fields JSON, source_fact_from, source_fact_to,
           superseded_by NULL)
lc_pub_visible = the rows with publish_at ≤ now and not superseded (a view)
```

Every publication row of every module's registered type is a `lc_pub_row`, written only after `intel.publishable`
accepted it (§4.1). A trigger refuses a `publish_at` earlier than `event_at` plus its type's delay, and every change but a
single supersession by a later row; rows are never deleted. The website and the narrator are granted `lc_pub_visible`, not
the table, so a row is unreadable to them before its time whatever their code does. The registry rows for every type
(this spec's, and PACT's, the Ledger's, Signal's and Aden Remembers', each in its own migration as its spec states) are
data in `lc_pub_type` and `lc_pub_field_rule`.

```text
lc_pub_name(char_id PK, name VARCHAR(35), opted_in BOOLEAN, changed_at)
```

Publication rows never hold a character's name: they hold the character id and the **basis** on which it may be named
(§4.1), and the web renderer and the in-world voices resolve it through `lc_pub_name` when they render. `lc_pub_name`
mirrors the character's current name and their Wayfarer `history_publishable` flag (wayfarers-memory.md §2.2), written by
Chronicle at every change. Withdrawing the flag therefore unnames the character everywhere at the next render, archived
issues included, without rewriting a row.

### 2.2 Publication types

Rows of these types are written by Chronicle's projector through `intel.publishable` (intel-service.md §4.7); their field
rules are registered by this spec's migration.

| Type | Fields (rule) | Delay | From |
|---|---|---|---|
| `chr_realm` | kind (`TEXT_FROM_FACT`: owner change, law enacted or ended, treaty registered, hero named), castle, fortress, clan (`PUBLIC_ENTITY`), character (`CHARACTER_PUBLIC`), day (`DAY`) | none (already public in the game) | Aden Remembers' owner and law facts; `pact_registered` treaty rows; heroes |
| `chr_history` | kind, title (`TEXT_FROM_FACT`), period (`DAY`), territory (`AREA_TERRITORY`), clans (`PUBLIC_ENTITY`), characters (`CHARACTER_PUBLIC`), counts and durations (`COUNT`) | tactical (≥ 24 h) | `lc_history_event` rows marked publishable (aden-remembers.md §4.9), and Wayfarer milestones marked publishable |
| `chr_monument` | history row, town (`PUBLIC_ENTITY`), slot (`COUNT`) | its history row's | §4.4 |
| `chr_saga_entry` | item (`SAGA_ITEM`), entry kind (`TEXT_FROM_FACT`), territory (`AREA_TERRITORY`), day (`DAY`), characters (`CHARACTER_PUBLIC`), counts (`COUNT`) | tactical | §4.6 |
| `chr_obituary` | item (`SAGA_ITEM`), cause (`TEXT_FROM_FACT`), day | tactical | a saga's end |
| `chr_replay` | history row, frames (`COUNT` cells, §4.7) | tactical, after the battle's end | §4.7 |
| `chr_arena` | heroes (`CHARACTER_PUBLIC`, basis hero), class, day | none | Olympiad hero facts (`mechanics/olympiad/Hero.java:607`, `computeNewHeroes`) |
| `chr_letter` | author (`CHARACTER_PUBLIC`, basis opted in), text (`MODERATED_TEXT`), day | tactical, from submission | §4.5 |
| `chr_gazette` | issue (`COUNT`), day (`DAY`), sections (references to rows of the types above and of other modules') | none (each referenced row already past its own delay) | §4.3 |

Three field rules are new (an amendment to intel-service.md §4.7, §11 below):

- `CHARACTER_PUBLIC`: a character id with its basis, valid when the row is written and again when it is rendered:
  `OPTED_IN` (the character's `history_publishable`, read from `lc_pub_name` at render), `HERO` (in a `chr_arena` row,
  the hero of that period), `CASTLE_LORD` (in a `chr_realm` or `chr_history` row about the reign in which they led the
  owning clan). Anything else renders as "an adventurer" in the reader's language. A row naming a character on any other
  basis is refused.
- `SAGA_ITEM`: an item lifecycle id (asset-service.md §2.2) of a saga (§4.6), with the item's template; never an object id.
- `MODERATED_TEXT`: text that passed moderation (§4.5), with its moderator and time.

### 2.3 Chronicle's own tables

```text
lc_chr_issue(issue_no PK, day DATE UNIQUE, closed_fact_id, composed_at)
lc_chr_issue_entry(issue_no, seq, pub_type, pub_row_id, section, PRIMARY KEY(issue_no, seq), UNIQUE(pub_type, pub_row_id))
lc_chr_monument_slot(town_id, slot, x, y, z, heading, PRIMARY KEY(town_id, slot))
lc_chr_monument(monument_id PK, town_id, slot, history_row_id UNIQUE, application_id UNIQUE, state {STANDING|RETIRED},
                raised_at, retired_at NULL)
lc_chr_saga(lifecycle_id PK, item_id, began_at, began_kind, ended_at NULL, end_kind NULL, pvp_kills INT)
lc_chr_saga_event(lifecycle_id, seq, kind, at, territory_id NULL, char_id NULL, other_char_id NULL, ref BIGINT NULL,
                  PRIMARY KEY(lifecycle_id, seq))
lc_chr_battle(battle_key PK, kind {CASTLE_SIEGE|FORT_SIEGE|TERRITORY_WAR}, place_id, started_at, ended_at NULL,
              history_row_id NULL)
lc_chr_battle_sample(battle_key, minute SMALLINT, cell SMALLINT, side {ATTACKER|DEFENDER}, count SMALLINT,
                     PRIMARY KEY(battle_key, minute, cell, side))
lc_chr_battle_mark(battle_key, minute, kind {KILLS|WARD|GATE|SEAL}, ref INT NULL, count INT,
                   PRIMARY KEY(battle_key, minute, kind, ref))
lc_chr_letter(letter_id PK, author_char, text VARCHAR(600), submitted_at, state {QUEUED|APPROVED|REJECTED|PUBLISHED},
              moderator NULL, decided_at NULL, reason NULL)
lc_chr_prose(dto_hash BINARY(32), lang {EN|RO|RU}, style, text TEXT, checked_at, PRIMARY KEY(dto_hash, lang, style))
lc_chr_crier_log(town_id, kind, ref BIGINT, said_at, PRIMARY KEY(town_id, kind, ref))
```

- Issues: one per day; a publication row enters at most one issue (the unique key), and only once it is published (a
  trigger compares its `publish_at` with the issue's composition time).
- Battle samples and marks are **not** publication rows: only Chronicle's projector reads them, and they become a
  `chr_replay` row's frames only after the tactical delay from the battle's end. Before that they are as sensitive as a
  live map, and no database user but the GameServer's can read them.
- Prose holds narrated text with **placeholders**, never resolved names (§4.8).

## 3. State machines

### 3.1 Gazette issue

`OPEN` (the day's rows accumulate) → `COMPOSED` at the day's close (§4.3) → `PUBLISHED` (written as `chr_gazette`). An issue
is composed once; a late row goes into the next issue.

### 3.2 Monument

| From | Event | To |
|---|---|---|
| — | a qualifying `chr_history` row is written (§4.4) and its town has a free slot or a retirable monument | `STANDING` (spawned) |
| `STANDING` | its slot is needed by a more significant event and it is the least significant, oldest monument of the town | `RETIRED` (despawned; the website keeps it) |

The NPC is a projection of the row: at startup every `STANDING` monument is spawned in its slot (fact-journal.md §4.4).

### 3.3 Saga

`TRACKED` (a lifecycle began for an eligible item, §4.6) → `ENDED` when the lifecycle ends (destroyed, crystallised,
merged: asset-service.md §2.2). Events append while tracked.

### 3.4 Letter

`QUEUED` → `APPROVED` or `REJECTED` (a moderator, with a reason) → `PUBLISHED` (in the first issue after its delay).

### 3.5 Battle

`OPEN` at a siege or Territory War start fact → `CLOSED` at its end fact → its replay published when the history row is
past its delay. A battle that never closes (a crash) is closed by the next start or end fact of its place.

## 4. Operations

### 4.1 Writing publication rows

Chronicle's projector consumes durable facts in log order (fact-journal.md §4) and writes rows of its types through
`intel.publishable`; a refused row is counted and alarmed, never written in part. Every row carries the fact ids it was
made from, so `lc why pub <type> <row>` shows its sources. Rows are append-only; a correction is a new row superseding the
old one (the old one stays, marked superseded).

**Humans-only filter** (actor-identity.md §3): a history row carries, per role, the count of human and of synthetic
participants (policy domain `publication`), never per-character origin, so the website's "humans only" view shows counts
and the events humans took part in, and nobody learns which named character is synthetic.

### 4.2 In the world

- **NPCs remember**: listed town NPC templates add a line from the most significant `chr_history` rows of their territory
  ("RedSky once ruled these lands…") through the same chat-window seam Aden Remembers uses for rumors
  (`entity/actor/Npc.java:843`, `:879`; aden-remembers.md §4.8), never a container-wide first-talk listener.
- **Bards** (tavern NPCs): a ballad of last week's history in their territory (narrated, §4.8, or the template); a bard
  asked about a clan answers with that clan's `chr_history` rows (clans are public entities).
- **Criers and heralds**: town criers proclaim, with `Npc.broadcastSay` (`entity/actor/Npc.java:2136`) in their town only,
  new and ended laws, owner changes, registered treaties, caravan departures (public by design, aden-remembers.md §4.6),
  and marks of Writs whose open total passes `CrierWritAdena` (`ledger_wanted` rows). At most one proclamation per crier per
  `CrierMinIntervalSeconds`, each (town, kind, ref) once (`lc_chr_crier_log`). Aden Remembers' rumors are its own (§4.8
  there); proclamations are Chronicle's.
- **Wanted boards** render `ledger_wanted` rows; **notice boards** render `pact_registered` rows, caravan schedules and the
  Notarius public registry. Boards are Codex NPC pages (codex-ui.md).

### 4.3 The Aden Gazette

- **The day**: Mobius's daily reset (`managers/DailyResetManager.java:114-116`, a synchronous `ON_DAILY_RESET` at 06:30
  server time, `:69-73`, and at startup when a reset was missed, `:75`) is a seam writing `Chronicle.DayClosed`; issue *N*
  is composed from every row whose publish time is ≤ that fact's logical time and that is in no issue yet.
- **Sections** and their sources: The Realm (`chr_realm`, `pact_registered` treaties, `pact_completed` treaties), Markets
  (Aden Remembers' `world_market` rows: a resource family, a territory, a direction word; never a price or a volume),
  Roads (`world_caravan` rows: arrived, lost), Law and Disorder (`ledger_wanted`, `ledger_legend`), Arena (`chr_arena`),
  Obituaries of Legends (`chr_obituary`), Letters (`chr_letter`, at most `LettersPerIssue`), and History (`chr_history`).
  An article renders exactly its row: templates per type and language (EN, RO, RU), optionally narrated (§4.8), every
  fact linked to its row's page.
- **The paper**: Newsboy NPCs sell the issue for `GazettePriceAdena` (the `FEE_GAZETTE` sink, asset-service.md §2.5) as a
  non-tradable, valueless item *Aden Gazette, issue #N* (`GAZETTE_ISSUE` source, an amendment, §11) that opens the issue in
  the Codex. The same issue is free on the website.

### 4.4 Monuments

A `chr_history` row of kind siege, Territory War, legendary hunt or caravan disaster with significance ≥
`MonumentSignificance` raises a monument in the town of its territory: a new NPC template on an existing statue or stele
model (`displayId`), at a free slot of `lc_chr_monument_slot` (datapack positions, validated at startup: in the town, on
the geodata), with the inscription rendered from its row ("The Siege of 14 October · Defended by NightWatch · 317
defenders, 402 attackers · Castle held after 1h 43m"). Raising is an actuator effect with an application id (fact-journal.md
§4.4): spawned once, re-spawned from its row after a restart, never twice. When the town's slots are full, the least
significant and oldest monument retires if the new one is more significant; otherwise the new one is website-only.

### 4.5 Letters

A character whose `history_publishable` is set submits a letter in the Codex (≤ `LetterMaxChars`, `LetterFeeAdena` into
`FEE_GAZETTE`, at most `LettersPerCharWeek`). Admins moderate it (`lc chr letters`): a letter naming a character that
`CHARACTER_PUBLIC` would not allow, giving a position, or describing events younger than the tactical delay is rejected
with that reason. Approved letters are published after the tactical delay from submission, signed with the author's name.

### 4.6 Item sagas

- **Eligible**: non-stackable equipment of grade S and above (`entity/item/ItemTemplate.java:390`, `getCrystalType`) and
  the items listed in `data/lc/saga_items.csv` (legendary drops). Common items never become sagas.
- **Begin**: the first time an eligible item without a saga reaches a player (the ownership seam below), the Chronicle asks
  the Asset Service to begin a lifecycle for it (`track`, an amendment, §11), because Mobius reuses object ids
  (asset-service.md §2.2); the saga's beginning is the process that brought it (crafted, looted from a monster, bought,
  rewarded, exchanged).
- **Events**, all from synchronous seams with primitives copied: **ownership**, at the one point every owner change goes
  through, `Item.setOwnerId` (`entity/item/instance/Item.java:299`, `:336`), recording the process and the new owner for
  items flagged as sagas (a boolean on the item, so others pay one branch); **lost on death**, at the death drop
  (`entity/actor/Player.java:5587`, in `onDieDropItem`, `:5506`), with the killer; **PvP kills while equipped**, at
  `Player.onKillUpdatePvPKarma` (`Player.java:5609`), from `getActiveWeaponInstance` (`:5051`); **carried at a battle**,
  equipped by a participant sampled in a battle (§4.7); **ended**, when the lifecycle ends.
- **Publication**: each event becomes a `chr_saga_entry` row after the tactical delay, with territory and day only;
  characters named only under `CHARACTER_PUBLIC`. An ended saga of a legendary item becomes a `chr_obituary`.

### 4.7 Battles and saga replays

During a castle siege, fortress siege or Territory War battle (their start and end facts, aden-remembers.md §4.1), a task
samples every `ReplaySampleSeconds` (60) the players in the battle's zone (`entity/zone/ZoneType.java:505`,
`getPlayersInside`; the castle's `Castle.getZone`, `mechanics/siege/Castle.java:396`), off any hot path, and adds them to
cells of a `ReplayCellSize` grid over the zone, per side (by siege registration or Territory War territory). A cell with
fewer than `ReplayMinCount` (3) of a side in a minute is not kept, so no frame shows one person. Kills, ward captures,
gate breaches (`entity/actor/instance/Door.java:629`, `doDie`, for siege doors) and seal engravings are marks per minute.
After the battle's end and the tactical delay, the samples become a `chr_replay` row: an animation of fronts by cell and
marks over time, never an individual position.

### 4.8 The narrator boundary

The narrator is an external local service (02 §7), called asynchronously by the Chronicle web process with one
publication DTO, a style and a language; it has no tools and no state.

1. **Templates render first**, always; every page, in-world line and Gazette article has template text.
2. **Masking**: before the call, every entity, number, date and place of the DTO is replaced by a typed placeholder
   (`⟦E1⟧`, `⟦N1⟧`, `⟦D1⟧`, `⟦P1⟧`). The narrator never sees a real name, so it cannot repeat one in a way the check misses.
3. **Fact check**: the output is accepted only if every placeholder in it is one of the DTO's, it contains no digit, no
   number word of its language (`data/lc/narrator/numbers_<lang>.txt`), no URL, and every other word is in the language's
   lexicon (`data/lc/narrator/lexicon_<lang>.txt`), so no invented name passes; and its length is within bounds.
   Otherwise the template stands.
4. **Cache**: accepted prose is stored with placeholders in `lc_chr_prose` per (DTO hash, language, style); names resolve
   at render (§2.1). Narrator down or slow (`NarratorTimeoutSeconds`): templates only (04 §8).

### 4.9 The website

A separate process (02 §7): the Java standard library's HTTP server, server-rendered HTML, a small amount of plain
JavaScript and SVG, virtual threads. It connects with a database user that can read the `lc_pub_*` tables, `lc_pub_name`,
`lc_chr_prose` and nothing else; the narrator worker in the same process uses a second user that can also write
`lc_chr_prose`. The views of 17 §5: political map (owners now, active laws, Aden Remembers' weekly words per territory, its
`world_map_words` type), dynasties, battles, saga replays, crafters and items, treaties and contracts, outlaws and hunts,
economy (eras and shifts in words), records, the Gazette archive, clan pages, opt-in character pages, and the Truth Report
(11 §5, specified with Retail Truth). It renders in EN, RO and RU and shows nothing that is not in a row.

## 5. Facts

Emitted: `Chronicle.DayClosed`, `Chronicle.IssueComposed`, `Chronicle.MonumentRaised`, `Chronicle.MonumentRetired`,
`Chronicle.SagaBegan`, `Chronicle.SagaEvent`, `Chronicle.SagaEnded`, `Chronicle.BattleOpened`, `Chronicle.BattleClosed`,
`Chronicle.LetterDecided`, `Chronicle.Refused` (a row refused by `intel.publishable`). Telemetry: `Chronicle.BattleSample`
(not a fact: rows in `lc_chr_battle_sample`). Consumed: Aden Remembers' history, owner, law and caravan facts; PACT and
Ledger publication rows; Olympiad hero facts; Wayfarer's flag and milestones; Asset lifecycle facts.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| CHR-INV01 | every public output is rendered from `lc_pub_*` rows that `intel.publishable` accepted | the web user's grants; the in-world voices' sources; AT-CHR-01 |
| CHR-INV02 | no location-related row is visible before its delay; battle samples never public before their replay | the registry; the grants; AT-CHR-02 |
| CHR-INV03 | a character is named only on a valid basis, at write and at render | `CHARACTER_PUBLIC`; AT-CHR-03 |
| CHR-INV04 | narrated text states nothing that is not in its DTO | the fact check; AT-CHR-04 |
| CHR-INV05 | one issue per day, each row in at most one issue | keys; AT-CHR-05 |
| CHR-INV06 | each monument raised once, re-spawned from its row | application ids; AT-CHR-06 |

## 7. Failure model

| Fault | End state |
|---|---|
| narrator down, slow or failing the check | templates |
| website process down | nothing public lost; the game unaffected |
| crash during composition | the issue composed at the next start from the same day fact |
| crash after a monument row, before its spawn | spawned at restart from the row |
| a battle without an end fact | closed by the place's next fact |
| `intel.publishable` refuses a row | not written; counted and alarmed |
| a character withdraws publication | unnamed at the next render everywhere |

## 8. Configuration

`config/Custom/LivingChronicle/Chronicle.ini`.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `GazettePriceAdena`, `LetterFeeAdena` | 0–100000, 0–1000000 | 100, 1000 | as default everywhere | AT-CHR-05 |
| `LetterMaxChars`, `LettersPerIssue`, `LettersPerCharWeek` | 100–600, 0–20, 0–7 | 600, 5, 1 | as default everywhere | AT-CHR-09 |
| `MonumentSignificance`, `MonumentSlotsPerTown` | 1–1000, 0–8 | 500, 4 | as default everywhere | AT-CHR-06 |
| `CrierMinIntervalSeconds`, `CrierWritAdena` | 60–3600, 0–1e9 | 300, 10000000 | as default everywhere | AT-CHR-07 |
| `ReplaySampleSeconds`, `ReplayCellSize`, `ReplayMinCount` | 30–300, 200–2000, 2–10 | 60, 600, 3 | as default everywhere | AT-CHR-02 |
| `NarratorEnabled`, `NarratorTimeoutSeconds` | bool, 1–120 | false, 20 | false / true / false / true / true | AT-CHR-04 |

The tactical delay is the Intel Service's `PublicationTacticalDelayMinutes` (≥ 1440).

## 9. Interface

In the world: monuments, NPC lines, bards, criers, wanted and notice boards, Newsboys. Codex: the Gazette (from the item or
the Journal), letters. Website: §4.9. Admin: `lc why pub <type> <row>`, `lc chr letters` (moderation), `lc chr reissue
<day>` (recompose an issue that crashed, idempotent).

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| CHR-R01 | Every public output MUST be rendered only from `lc_pub_*` rows accepted by `intel.publishable`, read with a database user that can read nothing else. | AT-CHR-01 |
| CHR-R02 | No location-related row MUST be visible before the tactical delay, and battle samples MUST stay private until their replay. | AT-CHR-02 |
| CHR-R03 | A character MUST be named only on a valid basis, checked at write and at render; withdrawal MUST unname them everywhere. | AT-CHR-03 |
| CHR-R04 | Narrated text MUST replace a template only after the fact check accepts it. | AT-CHR-04 |
| CHR-R05 | There MUST be one Gazette issue per day, composed once, each row in at most one issue. | AT-CHR-05 |
| CHR-R06 | A monument MUST be raised at most once per history row and re-spawned from its row. | AT-CHR-06 |
| CHR-R07 | Criers MUST speak only in their town, rate-limited, each proclamation once. | AT-CHR-07 |
| CHR-R08 | Sagas MUST be keyed by item lifecycles, only for eligible items, and record every owner change, death loss and PvP kill while tracked. | AT-CHR-08 |
| CHR-R09 | Letters MUST be published only after moderation and the tactical delay, by opted-in authors. | AT-CHR-09 |
| CHR-R10 | Replays MUST show no cell below the minimum count and no individual position. | AT-CHR-02 |
| CHR-R11 | The website MUST be useless as a live radar. | AT-CHR-10 |
| CHR-R12 | The humans-only filter MUST reveal no character's origin. | AT-CHR-03 |
| CHR-R13 | Every page MUST render in EN, RO and RU. | AT-CHR-11 |

| Test | Kind | What it does |
|---|---|---|
| AT-CHR-00 | integration | the migration applies; the registry holds every type of every module with its field rules; triggers and keys refuse a row published sooner than its type's delay, a changed, deleted or twice-superseded row, a second issue for a day, a row in two issues or in an issue before its publish time, a second monument for a history row, an application id or a standing slot, a retired monument changed, an event on an ended saga, a battle sample below two, a letter over its length or outside its machine; the visible view hides unpublished and superseded rows |
| AT-CHR-01 | integration, sim | property test over every fact type: no sealed term, live location, state value, hidden identity or intel content reaches any `lc_pub_*` row; the web and narrator users' grants read `lc_pub_visible`, `lc_pub_name` and `lc_chr_prose` and nothing else; every website response and in-world line is reproducible from rows |
| AT-CHR-02 | integration | a location-related row invisible before its delay; battle samples unreadable by the web user; replays drop cells below the minimum; a replay before the delay refused |
| AT-CHR-03 | integration | a character named on each basis and refused on others; withdrawal unnames them in a rendered archived issue; the humans-only view's responses are identical for a human and a citizen in the same role |
| AT-CHR-04 | unit | the fact check rejects an invented name, a number word, a digit, an unknown placeholder, a URL, in EN, RO and RU; accepts placeholder-only prose; narrator down gives templates |
| AT-CHR-05 | integration, restart | one issue per day across a restart at the reset; a late row goes to the next issue; the paper item opens its issue; the fee is a sink |
| AT-CHR-06 | integration, restart, fault | a monument raised once under a crash at each step; re-spawned after a restart; retirement order |
| AT-CHR-07 | integration | criers in their town only, at most one proclamation per interval, each once |
| AT-CHR-08 | integration | a saga through craft, trade, private store, mail, death drop, PvP kills, a siege and crystallisation; an object id reused afterwards starts no saga entry for the old one |
| AT-CHR-09 | integration | letters: refused for non-opted authors, rejected for a forbidden name or a recent position, published after the delay |
| AT-CHR-10 | sim | the radar test: a hunter given only the website locates a mark no better than chance plus public facts |
| AT-CHR-11 | integration | every view and template renders in EN, RO and RU |

## 11. Upstream constraints

1. **No owner-change, Territory War or Seven Signs events** (aden-remembers.md §4.1). → Aden Remembers' seams feed history.
2. **The daily reset is synchronous and clock-driven** (`DailyResetManager.java:114-116`). → the issue day.
3. **Ownership changes pass through `Item.setOwnerId`** (`Item.java:299`, `:336`), and Mobius reuses object ids. → the
   saga seam there, keyed by Asset lifecycles.
4. **Container-wide first-talk listeners skip default chat windows** (`dist/game/data/scripts/handlers/actions/click/NpcClick.java:121-128`). → the shared per-template chat seam.

**Amendments to reviewed specs** (made with this spec's review): the registry rows of PACT's, the Ledger's and Signal's
publication types are added to their migrations (V011, V012, V013) exactly as their specs list them (data only); intel-service.md §4.7: the `CHARACTER_PUBLIC`,
`SAGA_ITEM` and `MODERATED_TEXT` field rules (and its §2 listing of the rule enum, which omits the three `LEDGER_*` rules
its §4.7 and its DDL hold); asset-service.md: the `track` operation and the `GAZETTE_ISSUE` source; aden-remembers.md: the
`world_market`, `world_caravan` and `world_map_words` publication types, and criers moved here.

## 12. Review record

- Revision 1 (draft): not yet reviewed.
