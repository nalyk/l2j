# SPEC-MEM — Wayfarer's Memory

- Status: draft (revision 6, answering five adversarial review rounds; record in §12)
- Blueprint: 14 (Wayfarer's Memory); 01 §2.5 (shared content is just content); 02 §1, §4, §6 (capture ≤ 2 µs p99); 03 §4.4
  (party seams); 04 §4 (Intel Service: registered copies), §5 (Codex: the login recap); 12 (Aden Remembers moves the
  world); 13 (Signal reads "played together before"); 15 (PACT contracts in projects); 16 §8 / 14 §8 (the Ledger boundary);
  17 (Chronicle owns website publication).
- Depends on: intel-service.md (copies, `intel.read`), codex-ui.md (pages, recap, queue), actor-identity.md (activity),
  aden-signal.md (§4.3's `collaborated` term), pact.md (contract facts), fact-journal.md (telemetry).
- DDL: `sql/migrations/V014__lc_mem.sql` (after Signal's V013).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Aden Remembers answers "what happened to the world"; Wayfarer's Memory answers "what happened to me, and what do I know".
It owns, per character: **observations** captured from that character's own experience, with **provenance**; their ageing,
contradiction and summarisation; the **login recap**; **projects**, **notes** and **reminders**; **shared pages** and their
import; the **personal history** (private); and its side of the **Ledger boundary**.

It does not own: the world's state (Mobius, Aden Remembers), anyone else's experience, intel (the Intel Service: Wayfarer
keeps registered references only), recipes (Mobius's `RecipeData`), or the website (Chronicle, 17, which may publish
milestones a character opted into; this spec only marks them).

**No global wiki** (14 §4): the query set (§4.1) is closed and answers only from the asking character's own rows; nothing
aggregates across characters; there is no ranking of places, monsters or routes. **Nothing is granted** (02 §1).

**Memory is best-effort.** It is a personal journal, not a record of the world: captures can be dropped under overload or
during a database outage (counted and alarmed), and a crash loses what was not yet committed. Nothing in the game depends
on a Wayfarer row.

## 2. Data

### 2.1 Observations

```text
lc_mem_observation(obs_id PK, char_id, relation {SOLD_BY|BOUGHT_FROM|BUYS|CRAFTS|PRICED_AT_NPC|OBTAINED_FROM|HUNTED|TALKED_TO|
                   QUEST_FROM|QUEST_DONE|QUEST_ENDED|QUEST_ABANDONED|LEARNED_RECIPE|SAW_BOSS|HUNTED_WITH|TRADED_WITH|MET|VISITED|TOLD|
                   RUMOR|LEAD}, subject_kind, subject_ref, object_kind NULL, object_ref NULL, region_id NULL,
                   value JSON, value_key VARCHAR(64), provenance {KNOWN|TOLD|RUMORED|INFERRED|HISTORICAL},
                   rumor_source {WORLD|INTEL} NULL, state {CURRENT|STALE|CONTRADICTED}, source_char NULL, source_page NULL,
                   origin_char NULL, origin_at NULL, intel_id NULL, first_at, last_at, times, tier {RAW|SUMMARY|DURABLE},
                   expires_at NULL, coalesce_key (stored, unique))
lc_mem_lead_history(char_id, mark_char, first_day DATE, PRIMARY KEY(char_id, mark_char, first_day))
lc_mem_companion(char_id, other_char, party_key VARCHAR(48), overlap_minutes INT, at, PRIMARY KEY(char_id, other_char, party_key))
```

- **Provenance** (14 §3) is never merged in a result (§4.1): `KNOWN` the character experienced it; `TOLD` imported from a
  page, with its **teller** (`source_char`, `source_page`) and its **origin** (`origin_char`, `origin_at`: who first observed
  it and when, kept through any number of forwards); `RUMORED` heard from an NPC (`rumor_source WORLD`: Aden Remembers' crier
  and tavern lines) or from the Intel Service (`INTEL`: `LEDGER_RUMOR` disclosures to this character); `INFERRED` derived from
  the character's own rows (a project's missing items); `HISTORICAL` kept only as history.
- **Coalescing**: a row's key is (char, relation, subject, object, region, provenance, teller, origin, `value_key`); the
  origin is part of it, so two forwarded reports first observed by different characters stay two rows. `value_key` is the
  part of the value that identifies a distinct observation: price and enchant for `SOLD_BY` and `BOUGHT_FROM`; the price
  alone for `BUYS`, because the enchant level a buy-store view shows is the **viewer's** own item's, substituted by
  `adjustAvailableItem` (`entity/itemcontainer/PlayerInventory.java:343-376`, via `network/holders/TradeList.java:122-130`),
  not the store's, so it is recorded in the value for the viewer's information but is no part of the offer; the
  merchant's buy-list id and the price for `PRICED_AT_NPC` (a merchant has several lists, `network/serverpackets/
  BuyList.java:34-42`, e.g. `dist/game/data/html/merchant/30001-1.htm:3-4` opens lists 3000100 and 3000101); the recipe
  id and the fee for `CRAFTS` (`network/serverpackets/RecipeShopSellList.java:57-59`);
  nothing for counts. A repeat with the same key updates `last_at` and `times`; a different price or enchant
  is a **new row**, and offers that coexist in one list (two swords of one enchant at two prices, two enchant levels) are
  rows that never contradict each other: what contradicts an offer is a later complete view of the same list without it
  (§3.1). Different tellers of the same thing stay apart.
- **Intel never coalesces**: a row with an `intel_id` (`LEAD`, and `RUMOR` from `INTEL`) is unique by its `intel_id`, holds
  **no content** of its own (value `{}`, no object, no region: what is shown is read through `intel.read`), and is registered
  as a copy of exactly that item (intel-service.md §4.5). When the item expires, the Intel Service's purge deletes the row
  with its other copies; nothing of it remains in `lc_mem_observation`.
- **Lead history** is the only trace of an expired lead: when a `LEAD` row is first stored, `lc_mem_lead_history` records
  the mark (whose identity the dossier already gave the hunter) and the day, nothing else, and no dependency. The Journal
  shows "you once had a lead on *mark* (*day*)" once the lead row is gone (14 §8).
- **Companions** are pairwise: `lc_mem_companion` records, per party incarnation (the party seams' party key, 03 §4.4), the
  minutes two characters were members **at the same time** (rows expire after `CompanionRetentionDays`, at most
  `MaxCompanionRows` per character, the oldest dropped first); a `HUNTED_WITH` observation exists for a pair once an
  incarnation's overlap reaches `HuntedWithMinutes`, and its count of sessions is the number of such incarnations.

### 2.2 The rest

```text
lc_mem_project(project_id PK, char_id, target_item_id, state {OPEN|DONE|ABANDONED}, created_at)
lc_mem_project_req(project_id, item_id, qty_needed, via_recipe NULL)
lc_mem_project_contract(project_id, contract_id)
lc_mem_note(note_id PK, char_id, text VARCHAR(400), pinned, remind_at NULL, created_at)
lc_mem_shared_page(page_id PK, author_char, title VARCHAR(64), created_at, observations_from, observations_to)
lc_mem_shared_fact(page_id, seq, relation, subject…, value, observed_at, origin_char, origin_at)   -- frozen
lc_mem_milestone(milestone_id PK, char_id, kind, ref, region_id NULL, at, publishable BOOLEAN)
lc_mem_session(char_id, session_end, summary JSON, expires_at)
lc_mem_profile(char_id PK, enabled, history_publishable, collaborate_with_signal, raw_retention_days)
```

Bounds per character (§8): `MaxPages`, `MaxPageFacts` per page, `MaxImportedFacts`, `MaxDurableRows` (pins count), `MaxNotes`,
`MaxProjects`. An action that would pass a bound is refused with the reason.

### 2.3 Tiers and retention

| Tier | What | Kept |
|---|---|---|
| `RAW` | observations as captured and coalesced | `raw_retention_days` after `last_at`, then summarised |
| `SUMMARY` | one row per (relation, subject, object, region, provenance, teller, origin, `value_key`), with counts, first and last times, and the state its raw rows had (the newest one's): the source (which monster, which seller) and the value (which price, which enchant) are kept, only repetitions and exact times are folded | `SummaryRetentionDays` |
| `DURABLE` | pinned observations, imported facts, notes, projects, milestones, lead history | until the character deletes them, within the bounds, or the character is deleted |

Intel-bearing rows are never summarised or made durable: they live exactly as long as their item. Every expiring table is
listed in `lc_expiring_table` (intel-service.md §4.5).

## 3. State machines

### 3.1 Observation

| From | Event | To |
|---|---|---|
| — | a capture, an import, a rumor told, a grant | `CURRENT`, tier `RAW` (imports: `DURABLE`) |
| `CURRENT` | its relation's `StaleAfter` passes since `last_at` | `STALE` |
| `CURRENT`, `STALE` | a later **intact** view of the same list in which this offer (its subject and `value_key`) does not appear | `CONTRADICTED` |
| `CONTRADICTED`, `STALE` | the offer appears again in a view of its list | `CURRENT` |

A **list** is a private store's sell list, its buy list or its manufacture list (store owner and relation), or one
merchant buy list (merchant and list id). A **view** is one capture of one list, and it travels as **one unit** from the
seam to the database: one buffer record holding the entry count and up to `MaxStoreItemsCaptured` entries (§3.4),
accepted or refused whole by the ring, and applied by the writer in one step (its entries coalesced and its absences
decided together), dropped whole if the backlog must drop. A view is **intact** when it was not truncated and was applied
whole; only an intact view decides absences, and an intact view with no entries (an emptied store) contradicts every
offer of its list. A truncated view makes the offers it shows current and contradicts nothing; a view that cannot be
applied whole is not applied at all (counted), so loss makes the journal incomplete, never wrong. Offers shown together in one view are all current together, so no sequence of
views of an unchanged list can flip one of them. Only the list relations (`SOLD_BY`, `BUYS`, `CRAFTS`, `PRICED_AT_NPC`)
are contradicted, and only by the character's own `KNOWN` views.
| `RAW` | `raw_retention_days` pass, not pinned, no `intel_id` | folded into its `SUMMARY` row, deleted |
| `RAW`, `SUMMARY` | pinned (within `MaxDurableRows`), no `intel_id` | `DURABLE` |
| any with `intel_id` | the item expires | deleted by the Intel Service's purge |

### 3.2 Quest observation

`QUEST_FROM` (a step given at a talk) → one of, each a separate row (§3.3): `QUEST_DONE` (a non-repeatable quest left
`COMPLETED` by its exit), `QUEST_ABANDONED` (the player aborted it through the quest window), or `QUEST_ENDED` (a repeatable
quest's exit, where Mobius does not tell a completion from a script's own cancellation: both call `exitQuest(true…)`, and a
give-up sound, if any, is played after the call, `dist/game/data/scripts/quests/Q00363_SorrowfulSoundOfFlute/Q00363_SorrowfulSoundOfFlute.java:155-156`).

### 3.3 Capture: the experience seams

Every capture records what **this character was shown or did**, at the moment it happens, never global state (14 §2). Mobius
dispatches no usable event for most of these, and its asynchronous ones race the state they describe
(`mechanics/events/EventDispatcher.java:159-166`), so capture is a set of small synchronous **experience seams** (core
patches, ledgered in `CORE_PATCHES.md` when implemented), each copying primitives into the character's capture buffer (§3.4):

| Relation | Seam | What is copied |
|---|---|---|
| `SOLD_BY`, `BUYS`, `CRAFTS` | the **serialisation** of the store list packets, `network/serverpackets/PrivateStoreListSell.java:42-61`, `PrivateStoreListBuy.java:37-58`, `RecipeShopSellList.java:54-59`, in `writeImpl`: entries are **staged** while written and published to the buffer only when the packet is **submitted**, when `_connection.write` returns true in `Client.sendPacket` (`commons/network/Client.java:204-233`; `Connection.write` returns false on a closed channel, `commons/network/Connection.java:145-149`); a packet whose serialisation throws or whose write is refused is discarded by Mobius, and so are its staged entries. Memory therefore records what the server submitted to this client's connection; a client that disconnects after submission may not have drawn it (a stated limit) | exactly the item ids, counts, prices and enchant levels written to this viewer, the store owner and region; recipe ids resolved to their products through `RecipeData.getRecipeList` (the packet has only recipe ids); capturing at the write, not at `Player.doInteract` (`entity/actor/Player.java:4470-4494`), because these packets read live lists when serialised (`PrivateStoreListSell.java:35`), after a queue (`commons/network/Client.java:85-94`) |
| `BOUGHT_FROM` | `TradeList.privateStoreBuy` / `privateStoreSell` after each item moves, `network/holders/TradeList.java:791`, `:959` | item, count, price, the other party |
| `PRICED_AT_NPC` | `BuyList.writeImpl`, `network/serverpackets/BuyList.java:55-90`, staged the same way | per product written: item id and the price written, tax included; the merchant and town |
| `OBTAINED_FROM` | auto-loot and party item loot at `Player.addItem` (`Player.java:3485-3581`) and `addAdena` (`:3192`, `:3223`) for `LOOT` and `SWEEP`, whose reference is the monster (`Player.java:4514-4527`, `entity/groups/Party.java:677-686`); **ground** pickups at `Player.doPickupItem` (`Player.java:4551`), which still holds the ground item's object id, and sets a **pickup context** for the rest of its synchronous call (the ground object id, the picker); the acquisition is recorded for the character who **receives** the item: the picker, or the party member `Party.distributeItem` chooses (`entity/groups/Party.java:639-649`: `getActualLooter`, then the recipient's `addItem` with the picker as reference), whose `addItem` seam reads the pickup context; the monster is known only from the **recipient's** own witnessed-drop map; the picker records nothing for an item another member received (Adena merging into a stack loses it later, `:4705`) | item, count; for auto-loot, the monster; for a ground pickup, the monster **only if this character saw the drop**: when an item is dropped Mobius sends each player who sees it a `DropItem` naming the dropper (`entity/item/instance/Item.java:1955`), and that send is a seam recording, in the viewer's own buffer, item object id → monster for `DropMapSeconds`; a character who arrived later received only `SpawnItem`, without a dropper (`network/serverpackets/SpawnItem.java:45`), and records no monster. Party Adena passes the initiating player (`Party.java:772`) and records no monster; herbs are consumed on pickup (`Player.java:4653-4666`) and are not obtained |
| `HUNTED` | the monster death, `ON_CREATURE_DEATH` on `Containers.Monsters()` (synchronous, `entity/actor/Creature.java:2626-2628`) | the monster template, region, the killer's acting player |
| `TALKED_TO` | `NpcClick`, `dist/game/data/scripts/handlers/actions/click/NpcClick.java:121-128`, in range, when the chat or first-talk dispatch happens (never on targeting, :66; never a listener on `Containers.Npcs()`, which would skip default chat windows) | the NPC and region |
| `QUEST_FROM` | `Quest.notifyTalk` and `Quest.notifyEvent` (`mechanics/script/Quest.java:638`, `:618`), when the quest state changes during that call | quest, step, and **the NPC that call was dispatched with**; a state change outside a talk (a kill advancing a step) records no NPC (`player.getLastFolkNPC()`, set on targeting, is never used) |
| `QUEST_DONE`, `QUEST_ENDED`, `QUEST_ABANDONED` | `QuestState.exitQuest` (`mechanics/script/QuestState.java:633-715`); `RequestQuestAbort` (`network/clientpackets/RequestQuestAbort.java:58`) marks its own call as an abort | abandoned when reached from `RequestQuestAbort`; done when the exit leaves the quest `COMPLETED`; ended otherwise (§3.2) |
| `LEARNED_RECIPE` | `Player.registerRecipeList`, `Player.java:1371`, `:1386` | the recipe |
| `SAW_BOSS` | the point Mobius sends a raid or grand boss's info **to this player**, `Npc.sendInfo(player)` (`entity/actor/Npc.java:1548`), called when the boss becomes known to the player's client (`entity/World.java:366-394`, `:600-619`) | the boss and region; the check is one template flag, so the hot path pays a branch for other NPCs |
| `HUNTED_WITH` | the party seams (03 §4.4): join and leave times per member and party incarnation | pairwise overlap into `lc_mem_companion` (§2.1) |
| `TRADED_WITH` | `TradeList.doExchange` **after success**, at `onTradeFinish(true)` (`TradeList.java:623-628`; a failed exchange, overweight or full, finishes with `false`) | the other character |
| `MET` | whisper delivery, `dist/game/data/scripts/handlers/chat/channels/ChatWhisper.java:122-123` | the other character |
| `VISITED` | the first entry of a region in a session (the journal's region index, fact-journal.md §2.3) | the region |

`TOLD` comes only from imported pages (§4.5); `RUMORED` only from rumors an NPC told the character or the Intel Service
disclosed to them; `LEAD` only from the character's own Intel grants. Chat is never parsed.

### 3.4 The capture buffer

Each online character has a bounded buffer of primitive records (`CaptureSlotsPerChar`), a multi-producer ring like the
journal's (fact-journal.md §3.2: claim, fill, publish; no lock, no I/O, no allocation), because its producers are the packet,
network, combat and event threads. A seam's work is bounded: at most `MaxStoreItemsCaptured` entries per store list, the
rest counted as truncated; a list view is one record (§3.1), its entries in the record's preallocated slots. Every seam is wrapped so that **any exception inside capture is caught, counted and dropped**:
retail processing continues exactly as it would without Wayfarer. A full buffer refuses new records (counted); records are
never overwritten. The Wayfarer writer coalesces in memory and commits every `FlushSeconds` and at the **logout seam**, a
synchronous hook before `storeMe()` (`network/Disconnection.java:110-120`; Mobius's logout event runs after the save,
`Player.java:11766-11770`). During a database outage the writer keeps coalesced rows in memory up to `WriterBacklogRows` and
drops the oldest beyond that, a list view always whole (counted, alarmed); a crash loses what was not committed.

### 3.5 Who is captured

Any player character whose profile is enabled (on by default). Offline traders and offline play capture nothing (detached
client; `Player.java:4447` sends nothing without a client). Citizens keep their own observation store (10) and use Wayfarer
only if their persona enables it.

## 4. Operations

### 4.1 The query set

Closed: each query is a named server operation over the asking character's own rows and the pages they imported, with
results grouped by provenance and each row's age and state:

| Query | Answers from |
|---|---|
| `who_sold(item)` | own `SOLD_BY`, `BOUGHT_FROM`, imported `TOLD` rows of those relations |
| `where_obtained(item)` | own `OBTAINED_FROM`, `TOLD` |
| `npc_sells(npc)` | own `PRICED_AT_NPC`, `TOLD` |
| `quest_giver(quest)` | own `QUEST_FROM` |
| `projects()` | own projects |
| `boss_seen(boss)` | own `SAW_BOSS`, `TOLD` |
| `hunted_with()` | own `HUNTED_WITH` (`KNOWN` only) |
| `rumors(subject)` | own `RUMORED` |
| `leads()` | own `LEAD` rows through `intel.read`, and the lead history |
| `history()` | own milestones |

No other query exists; none reads another character's rows; none sorts subjects by any yield, price or frequency across
characters. A request names a query id from this table and nothing else (AT-MEM-04 enumerates them).

### 4.2 The login recap

At login, if there is something to recap (14 §1): open projects and what they still miss; the latest observations for the
missing items, by provenance, with age; where the character was last hunting; pinned notes and due reminders. Through the
Codex as `TutorialShowHtml` (codex-ui.md §4.7), dismissable, and in the Journal tab; not as a popup below level 6, whose
tutorial owns that window at login (`dist/game/data/scripts/quests/Q00255_Tutorial/Q00255_Tutorial.java:139-150`, `:2228`).

### 4.3 Projects

A project pins a target item; its requirements come from recipes **the character knows or observed** (`LEARNED_RECIPE`,
`CRAFTS`), expanded through intermediate materials whose recipes the character also knows or observed. Wayfarer builds a
product-to-recipe index at startup from `RecipeData` (`data/xml/RecipeData.java:217`, `:244`; Mobius's `getRecipeByItemId`
matches the scroll, `:227-238`). Held quantities come from the character's own inventory when the page renders. PACT
contracts the character signed for a project's items appear in it. Reminders are system messages at `remind_at`, or at the
next login.

### 4.4 Ageing and summarisation

A daily job applies §3.1: stale by time, contradicted by newer known values, summarised and deleted past retention.

### 4.5 Shared pages

A character makes a page from a subset of their own rows (by relation, subject, region and span), never from a row with
an `intel_id`, never from `RUMORED` rows (a rumor is the world's or the Intel Service's to tell, not the character's to
re-certify), never lead history. Each fact on the page carries its
**origin**: for the author's own `KNOWN` rows, the author and the observation's time; for rows the author imported, their
original origin. Importing (a Codex action, in person from the author or through a link the author gave) makes `TOLD` rows
with the page's author as teller and the fact's origin preserved; an import never becomes `KNOWN`. Forwarding imported facts
on a new page keeps their origin (01 §2.5).

### 4.6 The Ledger boundary

Ledger intel is kept only as content-free `LEAD` rows referencing their item and shown through `intel.read`, with its
confidence, provenance and age band; Ledger rumors likewise as `RUMORED`/`INTEL` rows. When the item expires the Intel
Service's purge deletes the row; the lead history alone remains: "you once had a lead on *mark* (*day*)". A rebuild or replay
reads intel only through `intel.read`, and summaries, sessions, milestones and pages never take intel content, so nothing can
resurrect an expired lead (14 §8, 20 §1).

### 4.7 Personal history

Milestones are promoted from the character's own rows and facts: first visit of a region, a boss witnessed, clans joined and
left, a contract completed, a significant craft (grade B or above, or masterwork), a memorable PvP (a siege kill, a kill of a
higher-level character), a recurring companion (`CompanionSessions` incarnations), an economic milestone. They are private.
A character may mark them **publishable**; Chronicle (17) owns any website publication and its types, and may publish only
milestones marked so, of a character who set `history_publishable`, never one about a private PACT contract or a Ledger
outcome that is not already public, never naming another player who has not also set it; withdrawing the flag removes the
character's milestones from what Chronicle may publish. Until Chronicle is specified, nothing is published.

### 4.8 Signal's soft input

When both characters set `collaborate_with_signal`, Wayfarer answers Signal's `collaborated(a, b)` with whether
`lc_mem_companion` holds an incarnation of theirs with overlap ≥ `HuntedWithMinutes` (only the character's own experience;
imported rows never count); otherwise no (aden-signal.md §4.3).

### 4.9 Character deletion

The character deletion path (`network/GameClient.java:342`, `deleteCharByObjId`, which deletes a fixed list of tables and
none of Wayfarer's) gains, in the same core patch the Asset Service's deletion guard makes there (asset-service.md §11.21), a
step that deletes every `lc_mem_*` row of the character, and their shared pages; imports others made of those pages stay
theirs, with their recorded origin.

### 4.10 Production checks

A verifier samples `VerifySample` rows per cycle: no row of one character is reachable from another's queries; every
`TOLD` row has a teller and an origin; every row with an `intel_id` has its copy registration; no row's provenance is
`KNOWN` without a capture seam. A violation is an alarm and freezes Wayfarer (`lc_module_state`): captures continue into the
buffer, pages show the journal unavailable, until an admin unfreezes it.

## 5. Facts

Telemetry only (fact-journal.md §2.6): `Mem.Captured` (relation, count), `Mem.Dropped` (reason: buffer, backlog, truncated,
exception), `Mem.Flushed`. Outbox: `Mem.PageShared` (page, author), `Mem.PageImported` (page, importer),
`Mem.MilestoneReached` (kind, publishable), `Mem.Frozen`. No fact holds an observation's content.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| MEM-INV01 | every observation is the owner's own experience, an import, an NPC or Intel rumor told to them, or their own grant | the seams are the only writers; the verifier; AT-MEM-01 |
| MEM-INV02 | provenance is never merged and an import is never `KNOWN`; tellers and origins are kept | the schema; the query layer; AT-MEM-02 |
| MEM-INV03 | no query reads another character's rows or ranks across characters | the closed query set; the verifier; AT-MEM-04 |
| MEM-INV04 | no intel content outlives its item | content-free intel rows, purge by the Intel Service; AT-MEM-06 |
| MEM-INV05 | capture never changes retail processing | the seam wrapper; AT-MEM-08 |

## 7. Failure model

| Fault | End state |
|---|---|
| an exception inside a seam | caught, counted; retail unchanged |
| buffer full, backlog full | records dropped and counted, alarmed; the journal incomplete, never wrong |
| database outage | rows held in memory up to the backlog bound |
| crash | uncommitted rows lost |
| Intel Service down | `LEAD` and Intel rumor rows show "unavailable" |
| a seam not yet patched | its relation is absent; the rest works |
| a verifier violation | Wayfarer frozen until unfrozen |

## 8. Configuration

`config/Custom/LivingChronicle/Wayfarer.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CaptureSlotsPerChar`, `MaxStoreItemsCaptured` | 64–8192, 10–200 | 1024, 100 | as default everywhere | AT-MEM-08 |
| `FlushSeconds`, `WriterBacklogRows` | 5–600, 1000–1000000 | 60, 200000 | as default everywhere | AT-MEM-08 |
| `DropMapSeconds` | 60–3600 | 600 | 600 everywhere | AT-MEM-01 |
| `RawRetentionDays`, `SummaryRetentionDays`, `SessionRetentionDays`, `CompanionRetentionDays` | 1–365, 30–3650, 1–90, 7–365 | 30, 365, 14, 90 | as default everywhere | AT-MEM-05 |
| `StaleAfter` per relation (days) | 1–365 | prices and stores 3, drops 30, NPCs 90, quests 365 | as default everywhere | AT-MEM-03 |
| `HuntedWithMinutes`, `CompanionSessions` | 5–240, 2–100 | 20, 5 | as default everywhere | AT-MEM-07 |
| `MaxPages`, `MaxPageFacts`, `MaxImportedFacts`, `MaxDurableRows`, `MaxNotes`, `MaxProjects`, `MaxCompanionRows` | — | 20, 200, 2000, 1000, 200, 50, 2000 | as default everywhere | AT-MEM-05 |
| `VerifySample` | 1–1000 | 50 | 50 everywhere | AT-MEM-11 |

## 9. Interface

Codex → Journal tab and the Wayfarer's Journal item: recap, the queries of §4.1, projects, notes and reminders, share and
import pages, history (with the publishable marks), settings. A "remember this" action on store windows and NPC pages pins
the observation it shows. Admin: `lc why mem <char> <obs>`, `lc mem unfreeze`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| MEM-R01 | Observations MUST come only from the experience seams, imports, rumors told to the character and their own grants, each copying what was shown or done at that moment. | AT-MEM-01 |
| MEM-R02 | Results MUST keep provenances apart; an import MUST never become KNOWN; tellers and origins MUST survive forwarding. | AT-MEM-02 |
| MEM-R03 | Observations MUST age by time, and an offer MUST be contradicted only by a later complete own view of its list that no longer shows it, never by an offer shown with it. | AT-MEM-03 |
| MEM-R04 | Only the closed query set MUST exist, each over the asker's own rows, none ranking across characters. | AT-MEM-04 |
| MEM-R05 | Repeats MUST coalesce by their key, tiers MUST summarise and expire, durable growth MUST stay within its bounds, and a deleted character's rows MUST go. | AT-MEM-05 |
| MEM-R06 | Intel MUST be kept only as content-free registered references, never coalesced, summarised, shared or made durable, and leave only the lead history when it expires. | AT-MEM-06 |
| MEM-R07 | Projects, recap and history MUST derive from the character's own knowledge and facts; history MUST be publishable only by Chronicle under §4.7's conditions. | AT-MEM-07 |
| MEM-R08 | Capture MUST stay within 2 µs p99 per seam call, never block or change retail processing, and drop rather than wait. | AT-MEM-08 |
| MEM-R09 | Wayfarer MUST grant nothing. | AT-MEM-09 |
| MEM-R10 | Signal MUST learn only whether two characters who both opted in share a companion incarnation of their own. | AT-MEM-10 |
| MEM-R11 | A verifier MUST sample rows each cycle and freeze Wayfarer on a violation. | AT-MEM-11 |

| Test | Kind | What it does |
|---|---|---|
| AT-MEM-00 | integration | the migration applies; keys and checks refuse an import without teller or origin, an import as `KNOWN`, intel content on a row, intel on a durable or summary row, a second row for an intel item, a second row for one coalescing key, a durable row with an expiry, a shared fact without origin, a changed shared fact, a note over its bound; expiring tables registered |
| AT-MEM-01 | integration | each seam: the captured values equal the bytes submitted to the client's connection (store lists, buy list) or the change made (trade, loot, quest); a store list whose write is refused (channel closed) leaves no observation; a party pickup distributed to another member records the acquisition for the recipient only, with the monster only if the recipient saw the drop (picker and recipient with differing drop visibility); a store changed between the interaction and the write captures what was written; a ground pickup with its monster from the drop map, party Adena without one, herbs not obtained; a quest advanced by a kill records no NPC; an abort is `QUEST_ABANDONED`, a repeatable script cancellation `QUEST_ENDED`, a non-repeatable completion `QUEST_DONE`; a failed trade records nothing; a boss sent to the player recorded, one hidden from them not; a packet whose serialisation throws leaves no observation; ground loot picked up by a character who saw the drop knows its monster, one who arrived later does not |
| AT-MEM-02 | integration | the same item known, told by two tellers and rumored: four rows, three groups; a page forwarded twice keeps the original origin; one page carrying the same report first observed by two characters imports as two rows |
| AT-MEM-03 | integration | a complete view without an earlier offer makes it `CONTRADICTED`; two swords of one enchant at two prices and two enchant levels in one store stay `CURRENT` together through any number of views; a truncated view contradicts nothing; an emptied store contradicts all its offers; a two-offer view whose record is refused by a full ring, or dropped from the backlog, contradicts nothing and leaves both offers as they were; switching between a merchant's two lists (3000100, 3000101) contradicts neither; two intact views of an unchanged buy store between which only the viewer's own sword went from +3 to +4 contradict nothing; a manufacture list with a changed fee contradicts the old fee and a return to it makes it `CURRENT` again; an offer seen again is `CURRENT` again; prices stale after 3 days; a supply change by Aden Remembers leaves old knowledge old |
| AT-MEM-04 | unit, integration | the query set is exactly §4.1's; two characters with disjoint rows see only their own through every query; a forged page id or query id refused; nothing sorts across characters |
| AT-MEM-05 | integration | 400 kills one row; raw rows summarised and deleted after retention, the summary still naming each source monster and seller and keeping each price and enchant apart, pinned ones kept; companion rows expired and capped; each bound refused at its limit; a deleted character leaves no `lc_mem_*` row |
| AT-MEM-06 | integration, restart | a lead and an Intel rumor read through the Intel Service; after expiry both rows gone and the lead history alone shown; no summary, session, milestone or page holds their content; a rebuild and a replay after expiry show nothing more |
| AT-MEM-07 | integration | requirements from known and observed recipes only; recap content; milestones; a publishable mark refused for a private contract or another player's name; Signal-free history |
| AT-MEM-08 | sim, fault | the 800-citizen benchmark with Wayfarer on for all: each seam ≤ 2 µs p99, no tick time beyond the benchmark's band; exceptions injected in every seam leave retail packets and state identical to a run without Wayfarer; a database outage drops beyond the backlog, counted |
| AT-MEM-09 | unit | the static scan: no Wayfarer code calls a mechanics API or grants anything |
| AT-MEM-10 | integration | `collaborated` true only for both opted in with a qualifying own incarnation; a short overlap in a long party not counted; imported `HUNTED_WITH` never counted |
| AT-MEM-11 | integration | a planted cross-character row, a `TOLD` row without origin and an unregistered intel row: the verifier alarms and freezes Wayfarer |

## 11. Upstream constraints

1. **Most experiences dispatch no event** (store views, trades, NPC buy lists, quest progress, recipes, player trades:
   `Player.java:4470-4494`; `TradeList.java:584-628`, `:791`, `:959`; `Merchant.java:87-110`; `QuestState.java:162-704`;
   `Player.java:1371`). → experience seams.
2. **Store packets read live lists when serialised**, after a queue (`PrivateStoreListSell.java:35`, `Client.java:85-94`).
   → capture in `writeImpl`.
3. **Asynchronous events race their state** (`PlayerInventory.java:464-466`; `Attackable.java:125`, `:314-330`). → synchronous
   seams.
4. **Listener traps**: a Global pickup listener disables per-player ones (`Item.java:278-288`); a container-wide first-talk
   listener skips default chat windows (`NpcClick.java:121-128`). → seams, never such listeners.
5. **The dropper is cleared at drop** (`Item.java:1628-1639`). → Wayfarer's drop map.
6. **`lastFolkNPC` is set on targeting** (`NpcClick.java:66`) and aborts call `exitQuest(true)` like repeatable completions
   (`RequestQuestAbort.java:58`). → the talk dispatch's NPC; the abort marked at its own call.
7. **`ON_CREATURE_SEE` is the boss's observation**, asynchronous, without line of sight (`Creature.java:7465-7485`). → the
   player-side `sendInfo`.
8. **The logout event runs after the save** (`Disconnection.java:110-120`); **character deletion lists its tables**
   (`GameClient.java:342`). → a logout seam and a deletion step.
9. **No product index for recipes** (`RecipeData.java:227-238`). → Wayfarer's own.
10. **The tutorial owns `TutorialShowHtml` for new characters** (`Q00255_Tutorial.java:139-150`). → no recap popup below
    level 6.

## 12. Review record

- Round 5 (Codex), NO SIGN-OFF: one finding, a buy store's offer keyed by an enchant level the packet takes from the
  viewer's inventory. Revision 6: `BUYS` is keyed by price alone; the test changes only the viewer's item.
- Round 4 (Codex), NO SIGN-OFF: open were absences decided from a view partly lost in the ring or backlog, merchants'
  several buy lists, and manufacture fees without a value key. Revision 5: a list view is one capture unit, accepted,
  applied and dropped whole, and only an intact view decides absences (an empty one included); the buy-list id and the
  manufacture fee in the value key; tests for each.
- Round 3 (Codex), NO SIGN-OFF: open were two offers of one enchant at two prices contradicting each other, summaries
  folding distinct values, party pickups recorded for the picker rather than the recipient, and capture published before
  the packet was submitted. The rest was accepted as implementation obligations and stated limits. Revision 4:
  contradiction by a later complete view of the same list, so coexisting offers stay current together; the value key in
  the summary key; a pickup context carried to the recipient's seam, with the recipient's own drop map; publication at
  `_connection.write`'s success, and the limit stated.
- Round 2 (Codex), NO SIGN-OFF: open were forwarded reports of different first observers merging, enchant levels
  contradicting each other and no path back to current, script cancellations counted as completions, ground Adena without
  its object id, unbounded companion rows, summaries dropping the source, the drop map inventing knowledge for late
  arrivals, a provenance check allowing partly attributed `KNOWN` rows, and capture from a packet that is then discarded.
  Revision 3: the origin in the coalescing key; contradiction only by price at the same enchant, and a repeat restoring a
  row; `QUEST_ENDED` for repeatable exits Mobius cannot classify; ground pickups at `doPickupItem`; companion retention
  and cap; summaries keyed by the source; drop witnessing from the `DropItem` each viewer receives; every attribution
  field empty unless `TOLD`; staged capture published only after a packet serialises.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 17 findings: Ledger rumors outside the boundary; intel content
  allowed in rows; coalescing merging intel dependencies, tellers and prices; expired-lead conversion against the purge;
  `SAW_BOSS` from the boss's side; quest givers from targeting and aborts as completions; trades before success; party Adena
  and ground pickups mis-sourced; store capture before serialisation; companions by party lifetime; a crash-loss promise a
  flush interval cannot keep; no capture budget or containment; unbounded durable growth and no deletion; unregistered public
  history; tests without oracles. Revision 2: intel rows content-free, never coalesced, purged by the Intel Service, with a
  content-free lead history; Intel rumors as intel rows; coalescing by value key and teller, origins preserved; capture at
  serialisation and at the player-side `sendInfo`; quest NPCs from talk dispatch, aborts marked; trades after success; a
  drop map for ground loot; pairwise companion overlap; memory stated as best-effort with a backlog bound; a 2 µs seam
  budget, exception containment and no overwrite; durable bounds and a deletion step; publication moved to Chronicle; a
  closed query set; a verifier; oracles per seam.
