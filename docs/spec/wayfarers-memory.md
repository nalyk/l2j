# SPEC-MEM — Wayfarer's Memory

- Status: draft (revision 1)
- Blueprint: 14 (Wayfarer's Memory); 01 §2.5 (shared content is just content); 02 §1, §4; 03 §4.4 (party seams); 04 §4
  (Intel Service: registered copies), §5 (Codex: the login recap); 12 (Aden Remembers moves the world); 13 (Signal reads
  "played together before"); 15 (PACT contracts in projects); 16 §8 / 14 §8 (the Ledger boundary); 17 (publication).
- Depends on: intel-service.md (copies, `intel.read`, publication registry), codex-ui.md (pages, recap), actor-identity.md
  (activity, policy), aden-signal.md (§4.3's `collaborated` term), pact.md (contract facts), fact-journal.md (facts).
- DDL: `sql/migrations/V014__lc_mem.sql` (after Signal's V013).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Aden Remembers answers "what happened to the world"; Wayfarer's Memory answers "what happened to me, and what do I know".
It owns, per character: **observations** captured from that character's own experience, with **provenance**; their
**ageing, contradiction and summarisation**; the **login recap**; **projects**, **notes** and **reminders**; **shared
pages** and their import; the **personal history**; and its side of the **Ledger boundary**.

It does not own: the world's state (Mobius, Aden Remembers), anyone else's experience, intel (the Intel Service: Wayfarer
keeps only registered references, read through it), recipes (Mobius's `RecipeData`).

**No global wiki** (14 §4): every query answers from the asking character's own observations and the pages they
imported; nothing aggregates across characters, and there is no "best spot", "best farm" or "optimal route" query.
**Nothing is granted**: no item, no buff, no hint the character did not experience (02 §1).

## 2. Data

### 2.1 Observations

```text
lc_mem_observation(obs_id BIGINT PK auto, char_id, relation {SOLD_BY|BOUGHT_FROM|BUYS|CRAFTS|PRICED_AT_NPC|OBTAINED_FROM|
                   HUNTED|TALKED_TO|QUEST_FROM|QUEST_DONE|LEARNED_RECIPE|SAW_BOSS|HUNTED_WITH|TRADED_WITH|MET|VISITED|
                   TOLD|RUMOR|LEAD}, subject_kind {ITEM|NPC|MONSTER|PLAYER|REGION|RECIPE|QUEST|BOSS|CONTRACT},
                   subject_ref BIGINT, object_kind NULL, object_ref NULL, region_id SMALLINT NULL, value JSON,
                   provenance {KNOWN|TOLD|RUMORED|INFERRED|HISTORICAL}, state {CURRENT|STALE|CONTRADICTED},
                   source_char NULL, source_page NULL, intel_id NULL, first_at, last_at, times INT,
                   tier {RAW|SUMMARY|DURABLE}, expires_at NULL)
```

- **Provenance** (14 §3): `KNOWN` the character experienced it; `TOLD` imported from another player's shared page (with
  who and when); `RUMORED` heard from an NPC or the world (Aden Remembers' rumors, the Intel Service's rumors);
  `INFERRED` derived from the character's own observations (a project's missing items); `HISTORICAL` an expired lead or a
  fact kept only as history. A query result is always grouped by provenance: the page never merges a rumor into a fact.
- **State**: `CURRENT`; `STALE` once older than its relation's `StaleAfter` (§8); `CONTRADICTED` when a newer `KNOWN`
  observation of the same relation and subject gives another value (a price, a seller who left). Ageing is by time only:
  if Aden Remembers moved supply, old knowledge stays old (14 §3).
- **Coalescing**: an observation is keyed by (char, relation, subject, object, region, provenance); a repeat updates
  `last_at`, `times` and the value, never a new row, so 400 identical kills are one row with `times` 400 (14 §9).
- **Values** are typed per relation (a price and a count, an enchant level, a quest step), never free text, except notes
  (§2.2).

### 2.2 The rest

```text
lc_mem_project(project_id PK, char_id, target_item_id, state {OPEN|DONE|ABANDONED}, created_at)
lc_mem_project_req(project_id, item_id, qty_needed, via_recipe INT NULL, PRIMARY KEY(project_id, item_id))
lc_mem_project_contract(project_id, contract_id, PRIMARY KEY(project_id, contract_id))
lc_mem_note(note_id PK, char_id, text VARCHAR(400), pinned, remind_at NULL, created_at)
lc_mem_shared_page(page_id PK, author_char, title VARCHAR(64), created_at, observations_from, observations_to)
lc_mem_shared_fact(page_id, seq, relation, subject_kind, subject_ref, object_kind, object_ref, region_id, value, observed_at,
                   PRIMARY KEY(page_id, seq))
lc_mem_milestone(milestone_id PK, char_id, kind, ref BIGINT, region_id NULL, at, public BOOLEAN)
lc_mem_session(char_id, session_end, summary JSON, expires_at, PRIMARY KEY(char_id, session_end))
lc_mem_profile(char_id PK, history_public BOOLEAN, collaborate_with_signal BOOLEAN, raw_retention_days)
```

- A shared page holds **copies** of the author's observations, frozen when the page is made; editing the author's memory
  later does not change it.
- Notes are the only free text: bounded, stripped (codex-ui.md §4.2), private to the character, never shared unless the
  character puts the note itself on a page.

### 2.3 Tiers and retention

| Tier | What | Kept |
|---|---|---|
| `RAW` | observations as captured and coalesced | `raw_retention_days` (default `RawRetentionDays`, 30) after `last_at`, then summarised |
| `SUMMARY` | a summary row per (relation, subject, object kind, region, provenance) with counts and the first and last time ("obtained X from family Y repeatedly") | `SummaryRetentionDays` |
| `DURABLE` | pinned observations, notes, projects, milestones, imported pages | until the character deletes them or is deleted |

Sessions expire with `SessionRetentionDays`. Every expiring table is listed in `lc_expiring_table` (intel-service.md §4.5).

## 3. Capture

### 3.1 Only the character's own experience

Every capture point records what **this character was shown or did**, at the moment the server shows or does it, never
global state (14 §2). Mobius dispatches no usable event for most of them, and its asynchronous ones race the state they
describe (`mechanics/events/EventDispatcher.java:159-166`), so capture is a set of **experience seams**, small synchronous
core patches (ledgered in `CORE_PATCHES.md` when implemented), each copying primitives into the character's capture
buffer (§3.3):

| Relation | Seam (upstream point) | What is copied |
|---|---|---|
| `SOLD_BY`, `BUYS`, `CRAFTS` | `Player.doInteract`, `entity/actor/Player.java:4470-4494`, when it sends a store's list to the viewer | per listed item: item id, count, price, enchant (sell store); the store's own buy list (`getBuyList().getItems()`, not the viewer-adjusted packet, `entity/itemcontainer/PlayerInventory.java:343-376`); per recipe: its product and cost, resolved through `RecipeData.getRecipeList` (the packet carries only recipe ids, `network/serverpackets/RecipeShopSellList.java:54-59`); the store owner and region |
| `BOUGHT_FROM` | `TradeList.privateStoreBuy` / `privateStoreSell`, `network/holders/TradeList.java:791`, `:959`, after each item moves | item, count, price, the other party |
| `PRICED_AT_NPC` | `Merchant.showBuyWindow`, `entity/actor/instance/Merchant.java:87-110`, when the buy list is sent | per product: item id and the price shown, tax included (`network/serverpackets/BuyList.java:89`); the merchant and town |
| `OBTAINED_FROM` | `Player.addItem` and `addAdena`, `Player.java:3485-3581`, `:3192`, `:3223`, for the processes `LOOT`, `SWEEP` and `PICKUP` | item, count, and the monster when the reference is one (auto-loot and party loot pass it, `Player.java:4514-4527`, `entity/groups/Party.java:677-686`); a ground pickup knows no monster (Mobius clears the dropper, `entity/item/instance/Item.java:1628-1639`) and records only the region |
| `HUNTED` | the monster death, `ON_CREATURE_DEATH` on `Containers.Monsters()` (synchronous, `entity/actor/Creature.java:2626-2628`) | the monster template, region, and the killer's acting player |
| `TALKED_TO` | `NpcClick`, `dist/game/data/scripts/handlers/actions/click/NpcClick.java:121-128`, in range, before the NPC's chat or first-talk event | the NPC and region (never a listener on `Containers.Npcs()`: that would route every NPC to its first-talk event and skip default chat windows) |
| `QUEST_FROM`, `QUEST_DONE` | `QuestState.setState`, `setCond`, `exitQuest`, `mechanics/script/QuestState.java:162-184`, `:467-522`, `:680-704` | quest, step, and the NPC `player.getLastFolkNPC()` names at that moment |
| `LEARNED_RECIPE` | `Player.registerRecipeList`, `Player.java:1371`, `:1386` | the recipe |
| `SAW_BOSS` | `ON_CREATURE_SEE` for raid and grand boss ids (`Npc.addCreatureSeeId`, `entity/actor/Npc.java:1341-1347`; polled every second, `taskmanagers/CreatureSeeTaskManager.java:39`) | the boss and region |
| `HUNTED_WITH` | the party seams (03 §4.4), for parties lasting at least `HuntedWithMinutes` | the other members |
| `TRADED_WITH`, `MET` | `TradeList.doExchange`, `TradeList.java:584-628`, before the transfer; whisper delivery, `dist/game/data/scripts/handlers/chat/channels/ChatWhisper.java:122-123` | the other character |
| `VISITED` | the first entry of a region in a session (the journal's region index, fact-journal.md §2.3) | the region |

`TOLD` comes only from imported pages (§4.5); `RUMORED` only from rumors the character was told by an NPC (Aden
Remembers' crier and tavern lines, the Intel Service's `LEDGER_RUMOR` disclosures to that character); `LEAD` only from the
character's own Intel grants (§4.6). Chat is never parsed.

### 3.2 Who is captured

Any player character whose owner has not turned Wayfarer off (`lc_mem_profile`; on by default, as a personal journal is).
Offline traders and offline play capture nothing (their client is detached; `Player.java:4447` drops packets with no
client). Citizens keep their own observation store (10) and use Wayfarer only if their persona enables it.

### 3.3 The capture buffer

Each online character has a bounded buffer (`CaptureSlotsPerChar`) of primitive records, filled at the seams without a
lock or I/O. A Wayfarer writer coalesces records in memory (§2.1) and flushes each character's coalesced rows every
`FlushSeconds`, and at the **logout seam**, a synchronous hook before `storeMe()` (`network/Disconnection.java:110-120`;
Mobius's logout event is asynchronous and runs after the save, `Player.java:11766-11770`). A full buffer drops the oldest
records and counts them. A crash loses at most the last `FlushSeconds` of memories: an accepted limit for a journal, never
for money (no Wayfarer row is a fact of the world).

## 4. Operations

### 4.1 Queries

Codex → Journal: "who sold *item*", "where did I get *item*", "what does *NPC* sell", "which NPC gave *quest*", "what was I
crafting", "where did I see *boss*", "who did I hunt with". Each runs over the asking character's own observations and
imported pages only, and returns rows grouped by provenance, each with its age and state. There is no query that ranks
places, monsters or routes, no "best", and no query across characters (AT-MEM-04 is a static check of the query set).

### 4.2 The login recap

At login, if the character has one, the recap (14 §1): the open projects and what they still miss; the latest relevant
observations for those missing items (by provenance, with age); where the character was last hunting; pinned notes and due
reminders. Shown through the Codex as `TutorialShowHtml` (codex-ui.md §4.7), dismissable, and in the Journal tab. It is not
shown to characters below level 6 at login, whose tutorial quest uses that window (`dist/game/data/scripts/quests/Q00255_Tutorial/Q00255_Tutorial.java:139-150`,
`:2228`); they see it in the Journal tab. No item is generated; no optimal answer is offered.

### 4.3 Projects

A project pins a target item. Its requirements are derived from the recipes **the character knows or has observed**
(`LEARNED_RECIPE`, `CRAFTS`): Wayfarer builds, at startup, an index from product to recipes from Mobius's recipe data
(`data/xml/RecipeData.java:217`, `:244`; Mobius keeps no product index, its `getRecipeByItemId` matches the recipe scroll,
`:227-238`), and expands intermediate materials through recipes the character also knows or has observed. Quantities
held are read from the character's own inventory when the page renders. PACT contracts the character signed for a
project's items appear in it (`lc_mem_project_contract`, from PACT's facts). Reminders send a system message at
`remind_at` if the character is online, else at their next login.

### 4.4 Ageing, contradiction, summarisation

A daily job marks observations `STALE` past their relation's `StaleAfter`, `CONTRADICTED` when a newer `KNOWN` one disagrees,
summarises `RAW` rows past retention into `SUMMARY` rows and deletes them, and deletes expired summaries and sessions.
Pinned observations are `DURABLE` and never summarised away.

### 4.5 Shared pages

A character makes a page from a subset of their own observations (by relation, subject, region and time span), never from
intel (`LEAD` rows and anything carrying an `intel_id` are refused: shareable intel is the Intel Service's decision, and a
copy of intel on a page would escape its expiry). Another character imports the page in person from the author or a
link the author gave them (a Codex action): each fact becomes a `TOLD` observation with `source_char` = the author,
`source_page`, and the observation's own date; an import never becomes `KNOWN`. Shared content is just content: it can be
forwarded, which is fine (01 §2.5); a forwarded import keeps the original author.

### 4.6 The Ledger boundary

Legitimately learned Ledger intel is kept as `LEAD` rows that hold only the Intel Service item id (registered as a copy in
the writing transaction, intel-service.md §4.5) and are shown through `intel.read`, with its confidence class, provenance and
age band. When the item expires (the read returns nothing), the row becomes `HISTORICAL`: "you once had a lead on *mark*
(expired)", with the day only, no place, no confidence, no payload. A rebuild or replay reads intel only through
`intel.read`, so it can never resurrect an expired lead (14 §8, 20 §1).

### 4.7 Personal history

Milestones are promoted from the character's own observations and its own facts (PACT contracts, Ledger outcomes as the
hunter or mark they already know, sieges attended from battle facts): first visit of a region, a boss witnessed, clans
joined and left, a contract completed, a significant craft (grade B or above, or masterwork), a memorable PvP (a siege kill,
a kill of a higher-level character), a recurring companion (`HUNTED_WITH` at least `CompanionSessions` times), an economic
milestone (first million, first ten million of own Adena). They are private unless the character opts in
(`history_public`); then each public milestone is a publication row of type `wayfarer_history` (intel-service.md §4.7:
the character's name, the milestone kind, a day, public entities only; never a place finer than a territory, never another
player unless that player also opted in), after the tactical delay.

### 4.8 Signal's soft input

When both characters set `collaborate_with_signal`, Wayfarer answers Signal's `collaborated(a, b)` with whether they share a
`HUNTED_WITH` observation; otherwise it answers no (aden-signal.md §4.3). Nothing else of the memory leaves the character.

## 5. Facts

Telemetry only (fact-journal.md §2.6): `Mem.Captured` (relation, count), `Mem.BufferDropped`, `Mem.Flushed`. Outbox:
`Mem.PageShared` (page, author), `Mem.PageImported` (page, importer), `Mem.MilestoneReached` (kind, public). No fact holds an
observation's content.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| MEM-INV01 | every observation is the owner's own experience, an import, an NPC rumor told to them, or their own grant | the seams are the only writers; the static scan; AT-MEM-01 |
| MEM-INV02 | provenance is never merged in a query result, and an import is never `KNOWN` | the query layer; AT-MEM-02 |
| MEM-INV03 | no query reads another character's observations or ranks places, monsters or routes | the static scan of the query set; AT-MEM-04 |
| MEM-INV04 | no expired intel is readable, rebuilt or shared | `intel.read`; the page builder; AT-MEM-06 |
| MEM-INV05 | Wayfarer grants nothing and calls no mechanics API | static scan; AT-MEM-09 |

## 7. Failure model

| Fault | End state |
|---|---|
| crash | at most `FlushSeconds` of captures lost; nothing else |
| buffer full | oldest records dropped and counted; the character's journal is incomplete, never wrong |
| Intel Service down | `LEAD` rows show "unavailable"; nothing else changes |
| the daily job late | rows stay a day longer; queries mark them stale by time anyway |
| a seam missing (not yet patched) | its relation is absent; the rest works |

## 8. Configuration

`config/Custom/LivingChronicle/Wayfarer.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CaptureSlotsPerChar` | 64–8192 | 1024 | 1024 everywhere | AT-MEM-08 |
| `FlushSeconds` | 5–600 | 60 | 60 everywhere | AT-MEM-08 |
| `RawRetentionDays` | 1–365 | 30 | 30 everywhere | AT-MEM-05 |
| `SummaryRetentionDays` | 30–3650 | 365 | 365 everywhere | AT-MEM-05 |
| `SessionRetentionDays` | 1–90 | 14 | 14 everywhere | AT-MEM-05 |
| `StaleAfter` per relation (days) | 1–365 | prices 3, stores 3, drops 30, NPCs 90, quests 365 | as default everywhere | AT-MEM-03 |
| `HuntedWithMinutes`, `CompanionSessions` | 5–240, 2–100 | 20, 5 | as default everywhere | AT-MEM-07 |
| `MaxNoteChars`, `MaxPageFacts` | 50–2000, 10–1000 | 400, 200 | as default everywhere | AT-MEM-05 |

## 9. Interface

Codex → Journal tab and the Wayfarer's Journal item: recap, search, projects, notes and reminders, share a page, import a
page, personal history, settings (off, history public, Signal collaboration). A contextual "remember this" action on store
windows and NPC pages pins the observation it shows. Admin: `lc why mem <char> <obs>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| MEM-R01 | Observations MUST come only from the experience seams, imports, rumors told to the character and their own grants, each copying primitives at the moment of the experience. | AT-MEM-01 |
| MEM-R02 | Every result MUST keep KNOWN, TOLD, RUMORED and the internal provenances apart, and an import MUST never become KNOWN. | AT-MEM-02 |
| MEM-R03 | Observations MUST age into STALE by time and become CONTRADICTED by newer knowledge, never updated from global state. | AT-MEM-03 |
| MEM-R04 | No query MUST read another character's observations or rank places, monsters or routes. | AT-MEM-04 |
| MEM-R05 | Repetition MUST coalesce, raw rows MUST summarise and expire by tier, and expiring tables MUST be registered. | AT-MEM-05 |
| MEM-R06 | Ledger intel MUST be kept only as registered references read through the Intel Service, never shared, and become an expired lead without content when it expires. | AT-MEM-06 |
| MEM-R07 | Projects, recap and history MUST be derived from the character's own knowledge, recipes they know or saw, and their own facts; public history only by opt-in through the publication registry. | AT-MEM-07 |
| MEM-R08 | Capture MUST not block the game: bounded buffers, flushes off the game thread, at most `FlushSeconds` lost on a crash. | AT-MEM-08 |
| MEM-R09 | Wayfarer MUST grant nothing. | AT-MEM-09 |
| MEM-R10 | Signal MUST learn from Wayfarer only whether two characters who both opted in share a `HUNTED_WITH` observation. | AT-MEM-10 |

| Test | Kind | What it does |
|---|---|---|
| AT-MEM-00 | integration | the migration applies; keys and checks refuse an import as `KNOWN`, a shared fact carrying intel, a lead without its intel reference, duplicate coalescing keys, a note over its bound; expiring tables registered |
| AT-MEM-01 | integration | each seam in turn: a store opened, a store trade, an NPC buy list, auto-loot, party loot, a ground pickup (no monster), a kill, a click on an NPC in range (its default chat unchanged), a quest started, advanced and completed, a recipe learned, a boss seen, a party, a trade, a whisper: one coalesced observation of the right relation for the experiencing character only |
| AT-MEM-02 | integration | the same item known, told and rumored: three groups on the page; an imported page's facts `TOLD` with author and dates, still after forwarding |
| AT-MEM-03 | integration | prices stale after 3 days; a newer price contradicts the older; a supply change by Aden Remembers leaves old knowledge old |
| AT-MEM-04 | unit | the static scan of the query set: none reads another character's rows, none ranks or aggregates across characters; the absent queries ("best farm", "optimal route") are not reachable from any page |
| AT-MEM-05 | integration | 400 identical kills one row with `times` 400; raw rows summarised after retention and deleted; summaries and sessions expire |
| AT-MEM-06 | integration, restart | a lead read through the Intel Service; after the item expires the row is an expired lead with no place or confidence; a rebuild and a replay after expiry do not resurrect it; a page from a lead refused |
| AT-MEM-07 | integration | a project's requirements from known and observed recipes only, intermediates expanded, inventory read at render; the recap's content; milestones; public history rows only for opted-in characters and opted-in companions |
| AT-MEM-08 | sim | the 800-citizen benchmark with Wayfarer on for all: capture adds no measurable tick time, buffers never block, flushes keep up; a crash loses at most `FlushSeconds` |
| AT-MEM-09 | unit | the static scan: no Wayfarer code calls a mechanics API or grants anything |
| AT-MEM-10 | integration | `collaborated(a, b)` true only when both opted in and share a `HUNTED_WITH` row; false when either did not opt in; no other memory reaches Signal |

## 11. Upstream constraints

1. **Most experiences dispatch no event**: store views, store trades, NPC buy lists, quest progress, recipes, player trades
   (`Player.java:4470-4494`; `TradeList.java:584-628`, `:791`, `:959`; `Merchant.java:87-110`; `QuestState.java:162-704`;
   `Player.java:1371`). → experience seams.
2. **Asynchronous events race their state**: `ON_PLAYER_ITEM_ADD` carries no process or source and the stack may have
   changed (`PlayerInventory.java:464-466`); `ON_ATTACKABLE_KILL` is delayed 2.5 s (`Attackable.java:125`, `:314-330`).
   → synchronous seams, primitives copied.
3. **Listener traps**: a Global pickup listener disables per-player ones (`Item.java:278-288`); a container-wide first-talk
   listener skips default chat windows (`NpcClick.java:121-128`). → seams, never such listeners.
4. **The buy-store packet shows the viewer's adjusted items** (`PlayerInventory.java:343-376`) and the recipe-shop packet
   only recipe ids (`RecipeShopSellList.java:54-59`). → read the store's own lists and resolve recipes server-side.
5. **The logout event runs after the save** (`Disconnection.java:110-120`). → a synchronous logout seam.
6. **No product index for recipes** (`RecipeData.java:227-238`). → Wayfarer's own index at startup.
7. **The tutorial owns `TutorialShowHtml` for new characters** (`Q00255_Tutorial.java:139-150`). → no recap popup below
   level 6.

## 12. Review record

- Revision 1: draft, not yet reviewed.
