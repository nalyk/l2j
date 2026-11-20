# SPEC-CHR — Chronicle of Aden

- Status: draft (revision 6, answering five adversarial review rounds; record in §12)
- Blueprint: 17 (Chronicle of Aden); 01 §2.6 (deterministic engine, optional narration); 02 §7 (three processes; the
  narrator an external local service), §6 (budgets); 04 §4 (publication DTOs, a separate database user), §8 (narrator
  down); 10 §8 (Genesis: the Chronicle runs from day 0); 12 §9 (Aden Remembers decides what became history); 14 §7
  (opt-in personal history); 16 §2 (public Writs); 20 §1 (everything public reads publication DTOs only).
- Depends on: intel-service.md (the publication registry and `intel.publishable`), fact-journal.md (facts, projectors,
  effects), aden-remembers.md (history events, world facts), asset-service.md (item lifecycles, fees), pact.md,
  black-ledger.md and aden-signal.md (their publication types), wayfarers-memory.md (the publishable flag and
  milestones), actor-identity.md (the humans-only filter), codex-ui.md (pages), profiles.md (the welcome line).
- DDL: `sql/migrations/V016__lc_chronicle.sql` (after Aden Remembers' V015).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Chronicle makes public what already became history: **in the world** (monuments, NPCs that remember, bards, criers,
boards), in the **Aden Gazette**, as **item sagas**, as **saga replays**, and on the **website**. It is story, never a
dashboard: it states only what a publication row holds.

It owns: the publication row store every module writes into (§2.1); names and their tokens (§2.2); its own publication
types (§2.3) and their projector; the Gazette; monuments and the other in-world voices; item sagas; battle sampling for
replays; the narrator boundary; the website process, its database roles and its views.

It does not own: what becomes history (Aden Remembers, 12 §9), the registry's rules (the Intel Service,
intel-service.md §4.7), other modules' publication types, item lifecycles (the Asset Service), or anyone's consent
(Wayfarer's profile flag, wayfarers-memory.md §2.2).

**The rule of publication** (17 §1): everything public is a `lc_pub_row` of a registered type, written by a projector
from durable facts, **only once it is due** (its delay passed), after `intel.publishable` accepted it; the website, the
narrator and every in-world voice read only rows through the views of §2.1. Sealed terms, live locations, raw state values
of any module, hidden identities and character ids never enter what they read.

## 2. Data

### 2.1 The row store

```text
lc_pub_row(pub_row_id PK, pub_type, written_at, event_at, fields JSON, source_fact_from, source_fact_to,
           superseded_by NULL → lc_pub_row, withdrawn_at NULL)
lc_pub_visible       = current rows: neither superseded nor withdrawn                       (a view)
lc_pub_successor     = (old row, its final current row) along corrections                   (a view)
```

- **Written when due.** A module writes a row only once its delay has passed: the trigger sets `written_at` to the
  database's clock at the insert, whatever the writer supplied, and refuses the row unless that is ≥ `event_at` + its
  type's delay, `event_at` being at or after the latest `occurred_at` of **every** fact in its source range (the journal
  orders facts by delivery, not occurrence, so the newest id need not be the newest event; the range may include unrelated
  facts, which can only delay a row, never hasten it), and `event_at` ≥ the `occurred_at` of its newest source fact
  (`source_fact_to`, which must exist in `lc_fact`), so the delay is counted from the facts, not from a timestamp the
  writer chose. Chronicle's own pending publications wait in its private `lc_chr_pending` until due. A location-related
  type's delay in `lc_pub_type` follows the Intel Service's `PublicationTacticalDelayMinutes`, never below 1440 (written
  by the Intel Service at startup; an amendment, §11).
- **Frozen.** A row never changes, except once: it is **corrected** (`superseded_by`: a later, current row of the same
  type replaces it; archives follow `lc_pub_successor` to the final current row) or **withdrawn** (`withdrawn_at`: it
  disappears, with no replacement, §4.1), never both. Rows are never deleted.
- **Readers** are database roles (§4.9): `lc_chronicle_web` can read `lc_pub_visible`, `lc_pub_successor`,
  `lc_pub_name_visible`, `lc_pub_character_page` and `lc_chr_prose`; `lc_chronicle_narrator` the same and may write
  `lc_chr_prose`; neither can read `lc_pub_row`, `lc_pub_name_ref`, `lc_pub_name` or any other table.

### 2.2 Names

```text
lc_pub_name(char_id PK, name, opted_in BOOLEAN, consent_gen INT, page_key BINARY(16), changed_at)   -- private
lc_pub_name_ref(token BINARY(16) PK, pub_row_id, char_id, basis {OPTED_IN|HERO|CASTLE_LORD|PACT_PARTY|WRIT_MARK|
                LEGEND_MARK}, consent_gen INT, basis_ref BIGINT NULL)                                       -- private
lc_pub_name_visible   = (token, name) for tokens whose basis holds now                 (a view)
lc_pub_character_page = (page_key, name, token) for a character's current opted-in tokens (a view)
```

No row of **any** module's type holds a character id or a name: every field naming a character holds **tokens**, one
per (row, character), random, so two rows naming the same character share nothing a reader can link. The field's rule
decides the token's basis: a `NAME_TOKEN` field's declared basis (§2.3); `LEDGER_WRIT_MARK` → `WRIT_MARK` (its
`basis_ref` the Writ); `LEDGER_LEGEND_MARK` → `LEGEND_MARK`; PACT's `party_characters` → `PACT_PARTY` (pact.md §4.10).
`PUBLIC_ENTITY` fields never hold a character. A trigger allows each basis only on its row types: `HERO` in `chr_arena`,
`CASTLE_LORD` in `chr_realm` and `chr_history`, `PACT_PARTY` in PACT's types, `WRIT_MARK` in `ledger_wanted`,
`LEGEND_MARK` in `ledger_legend`, `OPTED_IN` in Chronicle's other types. `lc_pub_name_visible` returns a token's name only
while its row is current and its basis holds:
- `OPTED_IN` while the character's flag is set **and** the token's `consent_gen` equals the character's: a withdrawal
  raises `consent_gen` (and draws a new `page_key`), so a token written before a withdrawal never resolves again, even if
  the character opts in later;
- `WRIT_MARK` while its Writ is open (black-ledger.md §4.12);
- `HERO`, `CASTLE_LORD`, `PACT_PARTY`, `LEGEND_MARK`: always, for those rows only: they name a role the game itself made
  public in that event (the period's hero, the reign's lord, a party to a contract registered as public, a legend the
  Ledger promoted), and the row is about that event.

A token that does not resolve renders as "an adventurer" in the reader's language. A character page is
`lc_pub_character_page` for one `page_key`: only current-generation opted-in tokens, so it disappears with the flag and a
new one, unlinkable to the old, begins if the character opts in again.

### 2.3 Publication types

Chronicle's types; their field rules are registered by V016 (new rules marked *, an amendment to intel-service.md
§4.7, §11):

| Type | Fields (rule) | Delay |
|---|---|---|
| `chr_realm` | kind (`TEXT_FROM_FACT`: owner change, law enacted or ended), castle, fortress, clan (`PUBLIC_ENTITY`), lord (`NAME_TOKEN`* basis `CASTLE_LORD`), day (`DAY`) | none (already public in the game) |
| `chr_history` | kind, title (`TEXT_FROM_FACT`), period (`DAY`), territory (`AREA_TERRITORY`), clans (`PUBLIC_ENTITY`), characters (`NAME_TOKEN`* basis `OPTED_IN` or `CASTLE_LORD`), counts and durations (`COUNT`) | tactical |
| `chr_monument` | history row (`PUB_REF`* to a `chr_history` row), town (`PUBLIC_ENTITY`), slot (`COUNT`) | its history row's |
| `chr_saga_entry` | item (`SAGA_ITEM`*), entry kind (`TEXT_FROM_FACT`), territory (`AREA_TERRITORY`), day (`DAY`), characters (`NAME_TOKEN`* basis `OPTED_IN`), counts (`COUNT`) | tactical |
| `chr_obituary` | item (`SAGA_ITEM`*), cause (`TEXT_FROM_FACT`), day | tactical |
| `chr_replay` | history row (`PUB_REF`*), frames (`REPLAY_FRAMES`*, §4.7) | tactical, from the battle's end fact |
| `chr_arena` | heroes (`NAME_TOKEN`* basis `HERO`), class (`TEXT_FROM_FACT`), period (`DAY`) | none |
| `chr_letter` | author (`NAME_TOKEN`* basis `OPTED_IN`), text (`MODERATED_TEXT`*), day | tactical, from submission |
| `chr_gazette` | issue (`COUNT`), day (`DAY`), sections (`PUB_REF`* list) | none (each referenced row already written, so already due) |

The new rules: `NAME_TOKEN` (a token of `lc_pub_name_ref` with a basis the field allows); `PUB_REF` (the id of a row that
exists, is current, and whose `written_at` is at or before the referencing row's); `REPLAY_FRAMES` (below);
`MODERATED_TEXT` (text approved under §4.5, with no character name in it); `REPLAY_FRAMES` holds two lists: **samples**
(minute, cell index on the battle's grid, side, count ≥ `ReplayMinCount`) and **marks** (minute, kind among `KILLS`,
`WARD`, `GATE`, `SEAL`, a reference that is a public entity: the ward's territory, the gate's door, the castle; a count
≥ 1), nothing else, so a mark is an event of a place, never of a person; `SAGA_ITEM` (an item lifecycle id of a saga,
with its item template; never an object id).

### 2.4 Chronicle's own tables

```text
lc_chr_pending(pending_id PK, pub_type, fields JSON, event_at, due_at, source_fact_from, source_fact_to)   -- private
lc_chr_day(day DATE PK, closed_at, fact_id, composed_at NULL, issue_no NULL)
lc_chr_issue(issue_no PK, day UNIQUE, composed_at)
lc_chr_issue_entry(issue_no, seq, pub_row_id UNIQUE, section)
lc_chr_reading(char_id, issue_no, paid_op, PRIMARY KEY(char_id, issue_no))
lc_chr_monument_slot(town_id, slot, x, y, z, heading)
lc_chr_monument(monument_id PK, town_id, slot, history_row_id UNIQUE, application_id UNIQUE, state {STANDING|RETIRED},
                raised_at, retired_at NULL)
lc_chr_saga(lifecycle_id PK, item_id, object_id, began_at, began_kind, ended_at NULL, end_kind NULL, pvp_kills)
lc_chr_saga_event(lifecycle_id, seq, kind, at, territory_id NULL, char_id NULL, other_char_id NULL, ref NULL)
lc_chr_battle(battle_key PK, kind, place_id, started_at, ended_at NULL, end_fact_id NULL, history_row_id NULL)
lc_chr_battle_sample(battle_key, minute, cell, side, count ≥ 2)
lc_chr_battle_mark(battle_key, minute, kind, ref, count)
lc_chr_letter(letter_id PK, author_char, text VARCHAR(600), submitted_at, state, moderator, decided_at, reason)
lc_chr_prose(dto_hash, lang, style, choices JSON, flavour TEXT NULL, checked_at)
lc_chr_crier(crier_id PK, town_id, last_said_at)
lc_chr_crier_log(town_id, kind, ref, said_at, PRIMARY KEY(town_id, kind, ref))
```

Battle samples, marks, pending rows, sagas, letters and name references are private: only the GameServer's own user
reads them.

## 3. State machines

### 3.1 Day and issue

`lc_chr_day` rows are written by Chronicle's own clock (§4.3), one per game day, in order (`CLOSED`); the day's
composition is one transaction that sets its `composed_at` and, when it has entries, its `issue_no` with the issue and its
entries (`COMPOSED`), or `issue_no` null for a day with none (`COMPOSED_EMPTY`). Either way it is never composed again, so
after a restart a closed but uncomposed day is composed, and a composed empty day is not reconsidered.

### 3.2 Monument

| From | Event | To |
|---|---|---|
| — | a qualifying `chr_history` row **is written** (so it is already due and public) and its town has a free slot or a retirable monument | `STANDING` (spawned; its own `chr_monument` row written) |
| `STANDING` | its slot is needed by a more significant event and it is the least significant, oldest monument of the town | `RETIRED` (despawned; the website keeps it) |

The NPC is a projection of the row: at startup every `STANDING` monument is spawned in its slot (fact-journal.md §4.4).

### 3.3 Saga

`TRACKED` (a lifecycle began for an eligible item, §4.6) → `ENDED` when the lifecycle ends (asset-service.md §2.2).

### 3.4 Letter

`QUEUED` → `APPROVED` or `REJECTED` (a moderator, with a reason) → `PUBLISHED` (in the first issue after its delay) →
`WITHDRAWN` (its author withdrew publication: its row's `withdrawn_at` set).

## 4. Operations

### 4.1 Writing rows, withdrawal, the humans-only view

Chronicle's projector consumes durable facts in log order (fact-journal.md §4), keeps what is not yet due in
`lc_chr_pending`, and when due writes the row through `intel.publishable`, with fresh tokens for every named character; a
refused row is counted and alarmed, never written in part. `lc why pub <type> <row>` shows its source facts.

**Withdrawal.** When Wayfarer's `history_publishable` flag is cleared (a durable fact), Chronicle in one transaction sets
`opted_in` false, raises `consent_gen`, draws a new `page_key` (so every `OPTED_IN` token of the character stops
resolving everywhere at once, archived issues included, for good) and sets `withdrawn_at` on every row that exists only
because of the character: its milestones, its letters, its saga entries. Rows that name it among others stay and render
it as "an adventurer"; tokens are per row, so nothing links those rows to each other or to the character. Opting in
again resolves only tokens written afterwards.

**The humans-only view** (actor-identity.md §3, publication domain) never filters individual events or names. It shows,
per domain and month, the number of events with at least `HumansMinPrincipals` (3) human participant principals and the
human share in five bands, only for domain-months with at least `HumansMinEvents` (5) events. **Stated limit**: monthly
aggregates can still be differenced by someone who knows most participants of a month's few events; the promise is no
per-event or per-name disclosure of origin, not immunity to inference.

### 4.2 In the world

- **NPCs remember**: listed town NPC templates add a line from the most significant current `chr_history` rows of their
  territory through the same chat-window seam Aden Remembers uses for rumors (`entity/actor/Npc.java:843`, `:879`;
  aden-remembers.md §4.8), never a container-wide first-talk listener.
- **Bards** (tavern NPCs): last week's history in their territory (template, optionally narrated, §4.8); asked about a
  clan, its `chr_history` rows (clans are public entities).
- **Criers** proclaim new and ended laws, owner changes, registered treaties (`chr_realm`, `pact_registered`) and the
  marks of Writs whose open total passes `CrierWritAdena` (`ledger_wanted`), with an `NpcSay` sent only to the players
  inside the crier's town zone (`entity/zone/type/TownZone`; Mobius's `Npc.broadcastSay`, `Npc.java:2136-2138`, sends to
  every visible player through `World.broadcastToVisiblePlayers`, `entity/World.java:2283-2311`, which crosses town
  bounds, so the seam filters the recipients by the town zone). Each crier speaks at most once per
  `CrierMinIntervalSeconds` (`lc_chr_crier.last_said_at`, durable), each (town, kind, ref) once.
- **Wanted boards** render `ledger_wanted` rows; **notice boards** render `pact_registered` rows and the Notarius public
  registry. Caravan schedules are Aden Remembers' own, at its Guild Master and boards (aden-remembers.md §4.6).

### 4.3 The Aden Gazette

- **The day.** Chronicle keeps its own day: a game day runs from `GazetteDayHour` (06:30, the hour of Mobius's daily
  reset, `managers/DailyResetManager.java:69-73`) to the next. Its clock writes `lc_chr_day` (and `Chronicle.DayClosed`)
  for each day as it ends, and at startup for every day missed since the last row, oldest first; the day's key makes a
  repeat a no-op. Mobius's own reset callback, which runs once at startup however many days were missed and whose marker
  is saved separately (`DailyResetManager.java:75-88`, `:99`, `:129-131`), is not used.
- **Composition.** Issue *N* for day *D* is composed in one transaction after `GazetteSettleMinutes` (30) past the day's
  end: its entries are **every current row of the section types that is in no issue yet and was written before the end of
  *D*** (so the backlog, rows committed after an earlier composition and letters carried over, comes first), each in its
  section, ordered by `written_at` then id; letters at most `LettersPerIssue`, by approval time, the rest waiting for the
  next issue. A day with no eligible row closes without an issue, so every issue has at least one entry. A crash before the
  commit leaves nothing composed, and the recovery composes from what is eligible then; once committed, an issue never
  changes, and an entry whose row is later corrected renders its successor.
- **Sections**: The Realm (`chr_realm`, `pact_registered`, `pact_completed`, `pact_completed_terms`), Markets (`world_market`, `signal_demand`),
  Roads (`world_caravan`), Law and Disorder (`ledger_wanted`, `ledger_legend`), Arena (`chr_arena`), Obituaries of Legends
  (`chr_obituary`), Letters (`chr_letter`), History (`chr_history`). No other type enters an issue.
- **Reading.** The issue is free on the website. In the game, a Newsboy sells a reading of an issue for
  `GazettePriceAdena` (`FEE_GAZETTE`, asset-service.md §2.5): the Codex then opens it from the Journal
  (`lc_chr_reading`). There is no newspaper item (17 §3 names one): an item would be a new asset source for a thing of no
  value, and the reading gives the same use.

### 4.4 Monuments

A `chr_history` row of kind siege, Territory War, legendary hunt or caravan disaster with significance ≥
`MonumentSignificance` raises a monument in its territory's town when the row is written: a new NPC template on an
existing statue or stele model (`displayId`), at a free slot of `lc_chr_monument_slot` (datapack positions, validated at
startup: in the town, on the geodata), with its inscription rendered from that row and resolved tokens. Raising is an
actuator effect with an application id (fact-journal.md §4.4): spawned once, re-spawned from its row after a restart. When
the town's slots are full, the least significant, oldest monument retires if the new one is more significant; otherwise the
new one is website-only. A corrected history row's monument shows its final successor's inscription; a withdrawn one retires.

### 4.5 Letters

A character whose `history_publishable` is set submits a letter in the Codex (≤ `LetterMaxChars`, `LetterFeeAdena` into
`FEE_GAZETTE`, at most `LettersPerCharWeek`). The text may name **no character**: an automatic check refuses any word that
is a character name (case-insensitive, whole words) before the letter reaches a moderator. Admins moderate it
(`lc chr letters`) against a checklist: no character named or described, no position or route, no contract terms, no
claim about hidden identities, aliases, informants or intel, no events younger than the tactical delay. An approved
letter is published after the tactical delay from submission, signed with its author's token; withdrawal sets its row's `withdrawn_at`
(§4.1).

### 4.6 Item sagas

- **Eligible**: non-stackable equipment of grade S and above (`entity/item/ItemTemplate.java:390`, `getCrystalType`) and
  the items in `data/lc/saga_items.csv` (legendary drops). Common items never become sagas.
- **Begin**: when an eligible item without a saga **enters a player's inventory or warehouse** (the container seam below),
  Chronicle calls the Asset Service's `track` (an amendment, §11) to begin a lifecycle for that object id, because Mobius
  reuses object ids; the beginning is the process of that entry (crafted, looted, bought, rewarded, exchanged).
- **The container seam** is the two `ItemContainer.addItem` overloads (`entity/itemcontainer/ItemContainer.java:221`,
  `:261`), which receive the process and reference for every item entering a container, created (`:261`, whose new item is
  given its owner at `:284`) or moved (`:221`, `:240`), recording an owner change when a saga item enters a container
  of a player other than its last owner. `Item.setOwnerId` is not the seam: its integer form carries no process
  (`entity/item/instance/Item.java:299-301`, `:336`), and Mobius also uses it for ground-loot **privileges**
  (`managers/ItemManager.java:93-108`), which are not possession.
- **Restore**: the saga flag on an item is in memory; at startup Chronicle loads the object ids of every live saga
  (`lc_chr_saga` with no end, whose lifecycle the Asset Service keeps bound to one live object, asset-service.md INV08),
  and item restoration (`Item.java:1560`, which sets the owner directly) sets the flag for those ids.
- **Events**: owner changes (the container seam); **lost on death**, at the death drop (`entity/actor/Player.java:5587`,
  in `onDieDropItem`, `:5506`), with the killer; **PvP kills while equipped**, at `Player.onKillUpdatePvPKarma`
  (`Player.java:5609`), for every flagged item in the killer's paperdoll (not only the weapon `getActiveWeaponInstance`
  returns, `:5051-5053`); **carried at a battle**, equipped by a participant sampled in a battle (§4.7); **ended**, when
  the lifecycle ends.
- **Publication**: each event becomes a `chr_saga_entry` row when due, with territory and day only and opted-in tokens. An
  ended saga of a legendary item becomes a `chr_obituary`.

### 4.7 Battles and saga replays

During a castle siege, fortress siege or Territory War battle (their start and end facts, aden-remembers.md §4.1), a task
samples every `ReplaySampleSeconds` (60) the players in the battle's zone (`entity/zone/ZoneType.java:505`,
`getPlayersInside`; the castle's `Castle.getZone`, `mechanics/siege/Castle.java:396`), off any hot path, into cells of a
`ReplayCellSize` grid over the zone, per side. A cell with fewer than `ReplayMinCount` (3) of a side in a minute is not
kept (the table refuses fewer than 2). Kills, ward captures, gate breaches (`entity/actor/instance/Door.java:629`) and seal
engravings are marks per minute. The `chr_replay` row's `event_at` is the battle's **end** fact; its frames are the samples
and marks in `REPLAY_FRAMES`' schema. A battle with no end fact (a crash) is closed by its place's next fact.

### 4.8 The narrator boundary

The narrator is an external local service (02 §7), called asynchronously by the website process with one publication DTO,
a style and a language; it has no tools and no state. It **states no fact**:

1. **Templates state every fact.** Each type's template is a fixed sequence of sentences over the row's fields.
2. **Choices.** A template sentence offers closed lists of alternatives (verbs, connectives, epithets) for its slots, each
   alternative written by us and true for every row the template serves ("held", "kept", "defended" for a successful
   defence). A template may also offer **flavour sentences**, also written by us and true for any row it serves (weather,
   song, the mood of a town square: never a claim about what anyone did). The narrator returns only the indices it
   chose; anything else is discarded.
3. **Cache.** The choices are stored per (DTO hash, language, style) in `lc_chr_prose`; names resolve at render. Narrator
   down or slow (`NarratorTimeoutSeconds`): the templates' first alternatives, no flavour (04 §8).

So narrated text is always text we wrote, chosen; it can state nothing the template does not.

### 4.9 The website

A separate process (02 §7): the Java standard library's HTTP server, server-rendered HTML, a little plain JavaScript and
SVG, virtual threads. It connects as a user holding the role `lc_chronicle_web`; its narrator worker as one holding
`lc_chronicle_narrator`; both roles are created by V016 with exactly §2.1's grants. The views of 17 §5: political map
(owners, active laws, Aden Remembers' `world_map_words`), dynasties, battles, saga replays, crafters and items, treaties and
contracts, outlaws and hunts, economy (eras and shifts in words), records, the Gazette archive, clan pages, opt-in
character pages, and the Truth Report (11 §5, specified with Retail Truth). Chronicle's own templates and pages exist in
EN, RO and RU; a missing translation is a test failure, not a fallback.

## 5. Facts

**Publication rows are outputs, not derived state.** Each `lc_pub_row`, issue, monument row and name reference is written
once, as an **effect** of the projector's decision (fact-journal.md §4.4), and never regenerated. Its retry receipt is a
unique key on the row, checked in the effect's transaction: Chronicle's publication rows and monuments store the
application id from (projector, fact, effect index) (`uk_pub_row_application`, `uk_chr_monument_application`; Chronicle
always sets it, the column is nullable only for other modules' rows); an issue is found by its day (`uk_chr_issue_day`,
written with the day's `composed_at` in one transaction); a name reference by (row, character) (`uk_pub_name_ref_row`).
Replay writes no effect, so a replay rebuilds Chronicle's private derived state (pending rows, sagas, battles, days) and
its effect intents, and compares those; the public record keeps its original ids and times. **Letters are inputs, not
derived state**: an `lc_chr_letter` row is the authoritative record of a submission (author, text, submission time,
immutable by trigger, kept for the character's life and withdrawn with it, §3.4) and its decision is a moderator's input,
recorded on the row and in `Chronicle.LetterDecided`; a replay reads the retained letter rows, as it reads any input, and
neither rebuilds nor compares them. The facts below carry what the private state needs, and the decisions that depend on what
was committed when (a composition's membership, a battle's samples) are recorded in them rather than recomputed:

| Fact | Payload |
|---|---|
| `Chronicle.DayClosed` | day, closed time |
| `Chronicle.IssueComposed` | day, issue number or none (an empty day), every entry: row id, section, sequence |
| `Chronicle.MonumentRaised`, `…Retired` | monument, town, slot, history row, application id |
| `Chronicle.SagaBegan` | lifecycle, item, object id, kind, holder character |
| `Chronicle.SagaEvent` | lifecycle, sequence, kind, time, territory, character, other character, reference |
| `Chronicle.SagaEnded` | lifecycle, kind |
| `Chronicle.BattleOpened`, `…Closed` | battle key, kind, place, time, end fact |
| `Chronicle.BattleSample` | battle key, minute, every kept cell (cell, side, count) and mark (kind, reference, count): one per minute, sensitivity private |
| `Chronicle.LetterDecided` | letter, decision, moderator, reason |
| `Chronicle.Withdrawn` | character, consent generation after, rows withdrawn |
| `Chronicle.Refused` | type, reason |

None holds a name or a position finer than a battle cell; character ids appear only in private facts.
Consumed: Aden Remembers' history, owner and law facts; PACT and Ledger facts; Olympiad hero facts
(`mechanics/olympiad/Hero.java:607`); Wayfarer's flag and milestone facts; Asset lifecycle facts.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| CHR-INV01 | readers see only current rows of registered types through the views, never ids or private tables | the roles' grants; AT-CHR-01 |
| CHR-INV02 | no row is written before its delay counted from its source facts | the trigger; AT-CHR-02 |
| CHR-INV03 | a name resolves only on a basis that holds now | the name view; AT-CHR-03 |
| CHR-INV04 | narrated text states no fact beyond its template | the choice protocol and flavour check; AT-CHR-04 |
| CHR-INV05 | one issue per day, fixed at composition, each row in at most one issue | keys; AT-CHR-05 |
| CHR-INV06 | each monument raised once, from a written row | application ids; AT-CHR-06 |

## 7. Failure model

| Fault | End state |
|---|---|
| narrator down, slow or returning anything but choices and clean flavour | templates' first alternatives |
| website process down | nothing public lost; the game unaffected |
| downtime over several days | each missed day closed at startup, its issue composed |
| crash during composition | nothing composed; recomposed at recovery |
| crash after a monument row, before its spawn | spawned at restart |
| a battle without an end fact | closed by its place's next fact |
| `intel.publishable` refuses a row | not written; counted and alarmed |
| withdrawal | tokens stop resolving at once, for good; the character's own rows withdrawn |

## 8. Configuration

`config/Custom/LivingChronicle/Chronicle.ini`.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `GazetteDayHour`, `GazetteSettleMinutes` | 0–23.5, 5–180 | 6.5, 30 | as default everywhere | AT-CHR-05 |
| `GazettePriceAdena`, `LetterFeeAdena` | 0–100000, 0–1000000 | 100, 1000 | as default everywhere | AT-CHR-05 |
| `LetterMaxChars`, `LettersPerIssue`, `LettersPerCharWeek` | 100–600, 0–20, 0–7 | 600, 5, 1 | as default everywhere | AT-CHR-09 |
| `MonumentSignificance`, `MonumentSlotsPerTown` | 1–1000, 0–8 | 500, 4 | as default everywhere | AT-CHR-06 |
| `CrierMinIntervalSeconds`, `CrierWritAdena` | 60–3600, 0–1e9 | 300, 10000000 | as default everywhere | AT-CHR-07 |
| `ReplaySampleSeconds`, `ReplayCellSize`, `ReplayMinCount` | 30–300, 200–2000, 2–10 | 60, 600, 3 | as default everywhere | AT-CHR-02 |
| `HumansMinPrincipals`, `HumansMinEvents` | 2–10, 3–50 | 3, 5 | as default everywhere | AT-CHR-03 |
| `NarratorEnabled`, `NarratorTimeoutSeconds` | bool, 1–120 | false, 20 | false / true / false / true / true | AT-CHR-04 |

## 9. Interface

In the world: monuments, NPC lines, bards, criers, wanted and notice boards, Newsboys. Codex: the Gazette, letters.
Website: §4.9. Admin: `lc why pub <type> <row>`, `lc chr letters` (moderation), `lc chr day <date>` (closes a day that
was missed, idempotent).

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| CHR-R01 | Every public output MUST be rendered only from current rows through the views, by roles that can read nothing else. | AT-CHR-01 |
| CHR-R02 | No row MUST be written before its type's delay counted from its newest source fact, and battle samples MUST stay private. | AT-CHR-02 |
| CHR-R03 | A character MUST be named only through a token whose basis holds at render; withdrawal MUST unname them everywhere at once, for good, and withdraw their own rows. | AT-CHR-03 |
| CHR-R04 | Narrated text MUST consist only of our template alternatives and flavour sentences, chosen. | AT-CHR-04 |
| CHR-R05 | Every game day MUST close once, its issue composed once from its span's rows, each row in at most one issue. | AT-CHR-05 |
| CHR-R06 | A monument MUST be raised at most once, only for a written row, and re-spawned from its row. | AT-CHR-06 |
| CHR-R07 | Criers MUST be heard only inside their town, at most once per interval, each proclamation once. | AT-CHR-07 |
| CHR-R08 | Sagas MUST be keyed by item lifecycles, begun at a player container entry, kept across restarts, and record owner changes, death losses and PvP kills with any equipped saga item. | AT-CHR-08 |
| CHR-R09 | Letters MUST name no character, be published only after moderation and the delay, and disappear on withdrawal. | AT-CHR-09 |
| CHR-R10 | Replays MUST show no cell below the minimum count and no individual position. | AT-CHR-02 |
| CHR-R11 | The website MUST give a hunter no advantage in locating a mark over one with the same private knowledge. | AT-CHR-10 |
| CHR-R12 | The humans-only view MUST disclose no event's or character's origin. | AT-CHR-03 |
| CHR-R13 | Every Chronicle page and template MUST exist in EN, RO and RU. | AT-CHR-11 |

| Test | Kind | What it does |
|---|---|---|
| AT-CHR-00 | integration | the migration applies; the registry holds exactly §2.3's types, fields, rules and delays and those the other modules' specs list (a table in the test, compared row for row); triggers and keys refuse a row before its delay (a supplied future `written_at` is overridden by the clock), one whose `event_at` precedes its newest source fact or whose source fact is missing, a changed or deleted row, a correction by an earlier, other-type or non-current row, a second correction, a withdrawal of a corrected row, a basis on the wrong row type, a second day or issue for a day, a row in two issues, a second monument for a history row, application or standing slot, an event on an ended saga, a battle sample below two, a letter over its length or outside its machine; the views hide corrected and withdrawn rows and unresolved names; the successor view leads through two corrections to the final row |
| AT-CHR-01 | integration | connected as the web and the narrator role (and the narrator writing prose): `lc_pub_row`, `lc_pub_name_ref`, `lc_pub_name`, pending rows, samples and sagas are refused; the views read; a property test over every fact type finds no sealed term, live location, state value, hidden identity, intel content or character id in any view |
| AT-CHR-02 | integration | a location-related row refused before its delay from its source fact; raising the tactical delay raises the database floor; a replay dated from its battle's end; cells below the minimum absent |
| AT-CHR-03 | integration | each basis resolves only while it holds and only on its row types; a withdrawal unnames the character in an archived issue at once and withdraws its milestones, letters and saga entries; opting in again resolves no old token and gives a new, unlinked page; two rows naming one character share no token; for one event with a human and a citizen swapped in a role, every per-event and per-name response is identical, and the humans-only view changes at most a monthly aggregate |
| AT-CHR-04 | unit | the narrator protocol: only valid choice indices are kept, any free text discarded, in EN, RO and RU; every alternative and flavour sentence of every template is checked by a test that it holds for every row its template serves (a reversed relationship has no alternative); narrator down gives the first alternatives |
| AT-CHR-05 | integration, restart | downtime over three days closes three days at startup in order; a restart after an empty day's composition does not reconsider it, and a row committed afterwards goes to the next issue; a replay from facts and the retained letter rows rebuilds the same private state (days, sagas, battles, pending rows) and the same effect intents, and writes no publication row; each effect retried after its commit finds its receipt (a Chronicle row by application id, an issue by day, a name reference by row and character) and writes nothing; a row committed after composition goes to the next issue; a crash before the composition commit recomposes; letters past the cap carry over; a reading is a `FEE_GAZETTE` sink and opens its issue |
| AT-CHR-06 | integration, restart, fault | a monument raised once under a crash at each step, only after its row is written; re-spawned after a restart; retirement order; a withdrawn history row's monument retires |
| AT-CHR-07 | integration | a player just outside the town zone, though visible to the crier, does not hear it; one proclamation per interval per crier across a restart; each once |
| AT-CHR-08 | integration, restart | a saga through craft, trade, private store, warehouse, mail, death drop, PvP kills with a saga weapon and with saga armor, a siege and crystallisation; a ground-loot privilege records no owner; a restart keeps the flag of a live saga item; a reused object id starts nothing for the old saga |
| AT-CHR-09 | integration | a letter naming a character is refused before moderation; non-opted authors refused; published after the delay; a withdrawal after approval removes it |
| AT-CHR-10 | sim | the radar test (as AT-INTEL-07's baseline): 2 000 trials of two hunter groups with the same private knowledge, one also reading the website, each to locate a mark within 300 units in 30 minutes: success rates differ by less than 2 percentage points at 95 % confidence |
| AT-CHR-11 | integration | every Chronicle template and page renders in EN, RO and RU with no missing key |

## 11. Upstream constraints

1. **No complete owner-change feed, and no Territory War or Seven Signs events**: only one owner event, `ON_CASTLE_SIEGE_OWNER_CHANGE`, dispatched asynchronously at a castle siege's mid-victory (`mechanics/events/EventType.java:128`, `mechanics/siege/Siege.java:469`); nothing for fortresses, clan halls, admin changes, a castle lost with its clan, or the siege's final outcome, so no durable ownership feed (aden-remembers.md §4.1). → Aden Remembers' seams feed history.
2. **The daily reset runs once at startup whatever was missed, its marker saved apart** (`DailyResetManager.java:75-88`,
   `:99`, `:129-131`). → Chronicle's own day clock with catch-up.
3. **`Item.setOwnerId(int)` carries no process and also marks ground-loot privileges** (`Item.java:299-301`, `:336`;
   `ItemManager.java:93-108`). → the container seam.
4. **`broadcastSay` crosses town bounds** (`Npc.java:2136-2138`; `World.java:2283-2311`). → recipients filtered by zone.
5. **Container-wide first-talk listeners skip default chat windows** (`dist/game/data/scripts/handlers/actions/click/NpcClick.java:121-128`). → the shared per-template chat seam.

**Amendments to reviewed specs**, made with this spec's review:
- intel-service.md §4.7: the `NAME_TOKEN`, `PUB_REF`, `REPLAY_FRAMES`, `MODERATED_TEXT` and `SAGA_ITEM` rules; the
  row store and the "written when due" trigger as the registry's enforcement; the tactical delay written into
  `lc_pub_type` at startup; and its §2 listing of the rule enum, which omits the three `LEDGER_*` rules its §4.7 and DDL
  hold.
- asset-service.md: `track(objectId)`: in one transaction, if the object has no live lifecycle, insert one
  (`began_op` null, kind `TRACKED`) and emit `Asset.LifecycleTracked`; idempotent; ended by the existing end sites;
  runs under the object's stack lock like any operation that reads an object (§3.3).
- pact.md §4.10: `pact_completed` split into `pact_completed` (type, parties, outcome, day) and `pact_completed_terms`
  (with clause kinds and totals, written only for `PUBLIC` and `publishable` contracts), so the registry itself refuses
  terms on any other row; and `parties` split into `party_clans` (`PUBLIC_ENTITY`) and `party_characters` (`NAME_TOKEN`
  basis `PACT_PARTY`) (V011).
- black-ledger.md needs no change: its mark fields keep their rules; that the stored value is a token of the matching
  basis is the row store's rule (§2.2).
- wayfarers-memory.md: the flag's set and clear as durable facts (`Mem.PublicationFlag`).
- the registry rows of PACT's, the Ledger's and Signal's types in their own migrations (V011, V012, V013), as data.

## 12. Review record

- Round 5 (Codex), NO SIGN-OFF: 28 earlier findings fixed; open: private letters could not be replayed (no fact carries a
  submission; findings 16, 27, 28, 31, 33); §5's receipt wording exceeded the schema (issues and name references have no
  application id; 34); no reversed-order test of the delay trigger (35); the claim of no owner-change event was too broad
  (36). Revision 6: letters are authoritative input rows, read by replay, never rebuilt; each effect's receipt is the
  unique key it really has (application id, day, row and character) and AT-CHR-05 retries each; ChronicleSchemaTest
  inserts a current lower-id fact then an old higher-id one; §11.1 names the one upstream owner event and its gaps
  (with the same correction in aden-remembers.md §11.1).
- Round 4 (Codex), NO SIGN-OFF: replay could not rebuild publication rows (their ids and times are commit-dependent); the
  delay counted from the newest id, not the newest occurrence. Revision 5: publication rows are effects with application
  ids, never regenerated, and replay rebuilds private state and intents only; the delay counts from every source fact.
- Round 3 (Codex), NO SIGN-OFF: commit-dependent decisions without replayable facts; an empty day without a durable
  composed state; stale supersession wording. Revision 4: complete fact payloads, membership and samples recorded in
  their facts; `composed_at` and `issue_no` on the day, set atomically; the wording follows `withdrawn_at`.
- Round 2 (Codex), NO SIGN-OFF: flavour paragraphs could still state invented facts; re-enabling consent revived old
  tokens; role bases unconditioned and pages unidentifiable; a withdrawal needed a visible replacement; frames could not
  carry marks; a future `written_at` published at once; a non-current successor accepted and only one hop followed;
  carry-over outside the day-span rule; `pact_completed_terms` outside the Gazette; other modules' character fields not
  tokens; tests missing for these. Revision 3: authored flavour sentences, chosen; a consent generation and page key;
  bases per row type; `withdrawn_at` without a successor; samples and place marks in frames; the clock sets `written_at`;
  current successors and a view to the final row; eligibility of the whole backlog, an issue only with an entry; the terms
  type in The Realm; tokens for every character field, PACT's parties split; the tests.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 16 findings: a lexical narrator check that lets relationships
  be invented; letters carrying names past withdrawal; a humans-only filter revealing origin in small events; withdrawal
  leaving stable ids attributable; reader grants that include delayed rows and every name; reference and replay fields
  without a contract; a delay counted from a writer's timestamp, and rows written before their delay; monuments raised
  before publication; supersession without a valid successor; an ownership seam with no process that also sees loot
  privileges; sagas missing armor and restarts; Mobius's reset callback as the day; unstable composition; criers heard
  beyond town; amendments that left the Asset Service's closed operations and PACT's conditional fields unreconciled;
  tests without oracles. Revision 2: the narrator chooses among our alternatives and adds name-free flavour; letters name
  no one; the humans-only view is monthly aggregates over minimum cohorts, its limit stated; per-row tokens and a name view;
  roles granted views only; typed reference and frame rules; rows written only when due, counted from their source facts;
  monuments from written rows; supersession by a current row of the same type with a reason; the container seam and
  restore; every equipped saga item; Chronicle's own day clock with catch-up; composition by day span; criers filtered
  by town zone; a reading instead of a newspaper item, `track` with a contract, `pact_completed_terms`; oracles in the
  tests.
