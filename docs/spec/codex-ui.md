# SPEC-UI — Codex of Aden (UI framework)

- Status: draft (revision 4, answering three adversarial review rounds; record in §12)
- Blueprint: 04 §5 (Codex of Aden); 02 §3 (one mechanism per concern), §12 (nothing the client sends is trusted); 13 §4
  (Signal's pages), 14 (Wayfarer's journal), 15 (PACT's ceremonies), 16 (Ledger pages), 17 (Chronicle, Gazette); 30 §2.4
  (localisation EN/RO/RU); 20 (client patch at N6).
- DDL: none. Pages, tokens and dialogs are in memory; a character's chosen locale is a Mobius player variable; what an
  action changes is stored by the module it calls.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Codex is the one way Living Chronicle shows pages and takes actions from players. It owns:

- **routing**: two bypass prefixes, `_lcb` (the book) and `_lc` (NPC pages), their grammar, and the dispatch to module page
  handlers;
- **rendering**: templates with localisation keys, bounded and paginated to fit the client's limits;
- **confirmation**: the one-use token and the native confirmation dialog through which every state-changing action passes;
- **notification**: system messages, screen messages and the login recap;
- **the rules every page obeys**: nothing hidden is shown, nothing the client sends is believed, nothing shown is cached.

It does not own what pages say or what actions do: each module spec defines its pages and actions as flows (04 §5.3); the
Codex runs them. It never changes a game mechanic.

## 2. Data

All in memory, per online player, except the locale; nothing else survives a logout or restart, and nothing needs to (§7).

```text
PageContext   { id: 64-bit random, module, page, locale, handles: Map<short, Ref> (≤ 64), npc: NpcRef or none, issued_at }
Ref           { kind, id }                    -- an identifier of a server object (a contract id, an intel id, a page number)
NpcRef        { objectId, instanceId, incarnation: int, npc: weak reference to the Npc }
ActionToken   { id: 128-bit random, char_id, module, action, terms_hash: 32 bytes, subject_ref, subject_version,
                deadline, npc: NpcRef or none, presence_range, state {ISSUED|DIALOG|USED|DEAD} }
DialogSlot    { token_id, nonce: negative int, deadline }  -- at most one per player
```

- A **handle** is a small number on a page standing for a `Ref`: an identifier only, never a view, a payload or an object
  holding data. Following a handle re-reads the object through its owning service at that moment, with that moment's
  authorisation (intel-service.md §4.1): an expired item, a revoked grant or a contract the player left yields nothing.
  A link carries handles, never ids, amounts or names, so a player can only point at what a page offered them.
- A **terms hash** is SHA-256 of the canonical text of what the player is agreeing to (the module renders it; §4.3).
- An **NpcRef** names one NPC in one life: the object, its instance and its **incarnation**. Mobius reuses object ids
  (`managers/IdManager.java:158`) and respawns a dead NPC as the same object with the same `Spawn`
  (`entity/spawns/Spawn.java:658-664`, `initializeNpc` at :399, linking the same `Spawn` at :508), and its spawn event is asynchronous
  (`entity/actor/Npc.java:1331-1334`), so no upstream value tells one life from the next. A small core patch (ledgered
  in `CORE_PATCHES.md` when implemented) adds a `volatile int` incarnation to `Npc`, advanced **when a life ends and
  before the next can be seen**: at the start of `Npc.onDecay` (`entity/actor/Npc.java:1365`) and at the start of
  `Spawn.initializeNpc` (`entity/spawns/Spawn.java:399`), before it clears the dead flag (:402) and before
  `WorldObject.spawnMe` publishes the object again (`entity/WorldObject.java:169`, `onSpawn` only at :179). So between a
  death and the next publication the old incarnation is already gone (and the dead NPC fails "not dead" anyway); the same
  coordinates in another instance, another object, or the same object in a later life do not match.
- The **locale** is the player variable `LC_LOCALE` (`EN`, `RO`, `RU`; Mobius's `PlayerVariables`), chosen in the book's
  settings, `EN` until chosen. It is independent of Mobius's multilingual support, which ships disabled and with other
  languages (`dist/game/config/Custom/MultilingualSupport.ini:7`).
- Bounds: `MaxPageContexts` per player (oldest dropped), `MaxOpenTokens` per player (oldest made `DEAD`).

## 3. State machines

### 3.1 Action token

All transitions of one player's tokens and dialog slot run on that player's **Codex queue**, a sequential executor per
player (§4.4), so checking, consuming and replacing never interleave; a token's state changes by compare-and-set besides.

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | page rendered with an action | the module offers it | `ISSUED` | the link `_lc a <token>` or `_lcb a <token>` is in the page |
| `ISSUED` | the link clicked | §4.3 checks pass | `DIALOG` | the slot set (killing the previous slot's token); the dialog shown (§4.4) |
| `ISSUED` | the link clicked | a check fails | `DEAD` | the page re-rendered with the reason |
| `DIALOG` | "yes" with the slot's nonce, before the slot's deadline | §4.3 checks pass again | `USED` | the module's operation runs (§4.5) |
| `DIALOG` | "no", the slot's deadline, a newer Codex dialog, logout | — | `DEAD` | nothing |
| `DIALOG`, `ISSUED` | the token's deadline, a newer page of the same module | — | `DEAD` | nothing |
| `USED`, `DEAD` | anything | — | unchanged | refused |

### 3.2 Dialog slot

One per player, with its own deadline (`DialogSeconds`, enforced by the server, not only by the client's countdown).
An answer whose nonce is not the slot's, or that arrives after the slot's deadline, is claimed and ignored (§4.4).

## 4. Operations

### 4.1 Opening the book

- **Alt+B** sends `RequestShowBoard`, which runs `GeneralConfig.BBS_DEFAULT` (`network/clientpackets/RequestShowBoard.java:44`,
  default `_bbshome`, `dist/game/config/General.ini:422`). The Codex registers a Community Board handler for `_lcb`, and
  the shipped configuration sets `BBSDefault = _lcb home`. Mobius's own board pages
  (`dist/game/data/scripts/handlers/bypass/communityboard/`) stay reachable from the book's last tab, **Board**.
- **The Wayfarer's Journal item** opens the same home page through an item handler.
- If `EnableCommunityBoard = False` (`General.ini:418`), the book is unavailable (the board handler refuses,
  `handler/CommunityBoardHandler.java:109`) and the item says so; NPC pages still work, because they use `_lc`, which is
  routed by the bypass handler, not the board (§4.2). Startup logs this.

Tabs: Journal (Wayfarer), Signal, Contracts (PACT), Ledger (shown only to registered hunters, clients and marks), Chronicle,
Gazette, Laws (the Crown Law of the territory the character is in), Settings (the locale), Board. Ceremonies (signing at a
Notarius, writs, Broker contracts, questioning a witness) are NPC pages, never book pages (04 §5.1).

### 4.2 Routing

- `_lcb …` (book) reaches the Codex through its Community Board handler: board prefixes are matched first
  (`network/clientpackets/RequestBypassToServer.java:131`, by `startsWith`, `handler/CommunityBoardHandler.java:65-78`).
- `_lc …` (NPC pages) reaches it through a bypass handler registered for the first word `_lc`
  (`handler/BypassHandler.java:53-61`, which matches the first word exactly). No board prefix starts `_lc ` with a
  space, so `_lc` commands never enter the board path, and `_lcb` is not the word `_lc`.

Grammar, after the prefix: `p <ctx> <handle>...` (show a page), `a <token>` (take an action), `home`, `t <tab>`. Every
argument is a decimal of bounded length; a bypass that does not parse is dropped. Text the player types (a search, a note)
comes only from an edit box, sent as the bypass's variable part; it is the only free text, bounded by `MaxInputChars`,
**stripped** of `<`, `>`, `&` and control characters (and then delivered: stripping is not refusal), and handed to the
module as data.

Every `_lc` and `_lcb` bypass is **validated by Mobius before it reaches the Codex**: neither is one of the prefixes exempt
from validation (`RequestBypassToServer.java:56-66`), so `Player.validateHtmlAction` accepts only an action that was in a
page sent to this player (`entity/actor/Player.java:11614`); for an NPC page Mobius also checks the distance to the NPC
(`RequestBypassToServer.java:113`). A variable action is accepted by its prefix (`Player.java:11589-11594`), so the
variable part is untrusted input. Then the router resolves `<ctx>` and `<token>` against the player's own maps: an id of
another player, an old page or a used token resolves to nothing. A bypass Mobius rejects never reaches the Codex and is
not counted by it; one the Codex rejects is counted (`Ui.BypassRejected`).

### 4.3 Checks before an action

At the click and again at "yes", on the player's Codex queue: the token is the player's own, in the right state, before
its deadline; the subject's current version equals `subject_version` (a contract amended since the page was shown kills
the token); the module recomputes the terms hash from current state and it equals the token's; where presence is
required, the NPC of the `NpcRef` is the same live object in the same life (`World` still maps its id to the very object the weak
reference holds, and its incarnation is the token's), not dead, in the player's instance, within `presence_range`, and `Npc.canInteract(player)` holds
(`entity/actor/Npc.java:525`, which also refuses casting, dead or sitting players and another instance); and the player can
act (not dead, not trading, not in an olympiad match, not teleporting).

These checks are a courtesy that gives the player a clear reason early. **The guarantee is in the operation's own
transaction**: the token passes `subject_version` and `terms_hash` to the module's operation, which re-checks them in the
transaction that makes the change (the Asset Service's `expectVersion`, asset-service.md §4; each module's operation the
same), so an amendment between "yes" and the commit makes the operation refuse, not apply other terms.

### 4.4 Confirmation dialog

The dialog is Mobius's native one: `ConfirmDlg(String)` (`network/serverpackets/ConfirmDlg.java:44`) shows the terms'
short form with Yes/No, with `addTime(DialogSeconds × 1000)` (the field is in milliseconds, as upstream uses it:
`dist/game/data/scripts/handlers/skill/effects/CallPc.java:95`, `…/chat/commands/voiced/Wedding.java:244`) and
`addRequesterId(nonce)`, a negative number drawn per dialog. That constructor uses system message `S1_3`, which Mobius
also uses for its own dialogs (offline trade, offline play, engagement, admin confirmations;
`network/clientpackets/DlgAnswer.java:75-187`), told apart by pending player actions, and those dialogs leave the
requester id at 0 (`dist/game/data/scripts/handlers/chat/commands/voiced/Offline.java:62`); the only other requester ids
Mobius sends are object ids, which are positive.

The Codex's `ON_PLAYER_DLG_ANSWER` listener runs first (`DlgAnswer.java:66-73`) and **claims every `S1_3` answer whose
requester id is negative**, whatever its value: a current one, a stale one, a forged one. It does nothing in the event
thread but hand the answer to the player's Codex queue, and returns termination; it contains no code that can throw
before returning (the hand-off is a non-blocking offer to a bounded queue; a full queue drops the answer), so a claimed
answer can never fall through to Mobius's handling (`EventDispatcher` would log an exception and continue,
`mechanics/events/EventDispatcher.java:327-330`). On the queue, an answer that does not match the slot's nonce or comes
after the slot's deadline is ignored. Answers with requester id 0 or positive are never claimed and reach Mobius
untouched.

**Stated limit**: Mobius shows its own dialogs without telling the Codex, and the client shows one dialog at a time. If a
Mobius dialog replaces a Codex dialog on screen, the Codex slot stays valid until its deadline; an answer carrying its
nonce can then only come from a client that kept or forged it. That answer is still the player's own explicit consent to
exactly the terms whose hash the token holds, re-checked in the operation's transaction, so it authorises nothing the
player had not been shown; the slot's short deadline bounds the window. From N6, when the client patch gives the Codex its
own system message id, the dialog uses it and the nonce remains.

### 4.5 Running the operation

A `USED` token runs its operation on the player's Codex queue with operation id = token id, expected version and terms
hash (§4.3). Outcomes:

- **refused** by the operation (version, terms, a check of its own): nothing changed; the page says why.
- **committed**: the change and its facts are durable; the page is re-rendered from current state.
- **failed before commit** (an exception, a lost connection to the database): nothing changed (the operation's own
  atomicity), except what the operation's spec says an abort leaves (an Asset Service refund mail,
  asset-service.md §7); the page re-renders from current state.
- **failed after commit** (the response could not be rendered): the change is durable; the next page shows it.

The Codex never retries an operation; a player who sees "nothing changed" takes the action again with a new token.

### 4.6 Rendering

Templates live in `dist/game/data/html/LivingChronicle/<module>/`, with text as keys in
`dist/game/data/lang/LivingChronicle/<locale>.properties` (EN, RO, RU). A key missing in the character's locale falls back
to English, key by key; a missing English key fails the build. Values are escaped when inserted.

Every view-model field has a declared maximum length after escaping (module specs declare them); a longer text value is
cut at the maximum with an ellipsis at render, **except agreement terms**: a terms field is never cut. An action is
offered only on a page that shows its whole terms text, the same canonical text whose hash the token holds; terms over
`MaxTermsChars`, or that do not fit on the page with its fixed part, are never offered (the module must split the
agreement). The build-time test checks that every page carrying an action fits its terms at `MaxTermsChars`. From the declared maxima a build-time test computes each template's largest
possible page and each list item's largest size; a template whose fixed part plus one largest item does not fit fails the
build, so no runtime item is indivisible and too large.

A book page is sent only if it has **fewer than 48 750 characters** (three parts of 16 250, strictly fewer, as Mobius
requires; at 48 750 it sends an error page instead, `util/HtmlUtil.java:430-451`), and an NPC page only if it has at most
17 200 (beyond which Mobius truncates, `network/serverpackets/AbstractHtmlPacket.java:75-78`). The Codex measures the
rendered page and paginates before handing it to Mobius, so Mobius never truncates nor replaces a page; this matters also
because `HtmlUtil.sendCBHtml` caches a page's actions before checking its length (`HtmlUtil.java:418-423`), so an oversized
page's actions would be valid without having been displayed.

### 4.7 Notifications

- **System messages**: a `SystemMessage` of `S1_3` with the Living Chronicle text, in the character's locale, until N6;
  with their own ids after it.
- **Screen messages**: `ExShowScreenMessage` for rare moments (at most one per `ScreenMessageMinSeconds` per player).
- **Login recap**: `TutorialShowHtml`, once per login, only if there is something to recap.

## 5. Facts

The Codex emits telemetry only: `Ui.PageShown` (module, page, render micros, parts), `Ui.ActionTaken` (module, action,
outcome: refused, committed, failed before or after commit), `Ui.BypassRejected` (reason: parse, unknown context, token
state, presence, version, terms, dialog nonce, dialog deadline). The operations it runs emit their own durable facts.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| UI-INV01 | no state change happens without a `USED` token, and the change's own transaction found the token's version and terms current | by construction; AT-UI-02 |
| UI-INV02 | a link carries only handles, contexts and tokens of the player's own session; a handle holds only an identifier | by the router; by the static scan; AT-UI-01, AT-UI-06 |
| UI-INV03 | no page is truncated or replaced by Mobius | by the renderer's measurement; AT-UI-04 |
| UI-INV04 | no page shows hidden state, and nothing shown outlives the request that read it: Intel, Ledger and Director data reach pages only through their services' views, read at render | by the static scan; AT-UI-06 |
| UI-INV05 | every `S1_3` answer with a negative requester id is claimed by the Codex; no other answer is | AT-UI-03 |

## 7. Failure model

| Fault | End state |
|---|---|
| player logs out or crashes during a dialog | the token dies; nothing ran |
| server crashes after "yes" | the operation committed or not (§4.5); a new session has new tokens, and the operation's id prevents a second application of the same token |
| an operation fails before or after commit, or a handler throws | §4.5's outcomes; the player sees the current state |
| a forged or replayed bypass | dropped by Mobius's validation, or by the router and counted |
| a stale or forged dialog answer | claimed and ignored; never reaches Mobius |
| a value longer than its declared maximum | cut at render; a terms text too long is never offered |
| the board disabled | no book; NPC pages work |

## 8. Configuration

`config/Custom/LivingChronicle/Codex.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `TokenSeconds` | 10–600 | 120 | 120 everywhere | AT-UI-02 |
| `DialogSeconds` | 5–120 | 30 | 30 everywhere | AT-UI-03 |
| `MaxPageContexts` | 4–64 | 16 | 16 everywhere | AT-UI-01 |
| `MaxOpenTokens` | 4–64 | 16 | 16 everywhere | AT-UI-02 |
| `MaxInputChars` | 16–512 | 128 | 128 everywhere | AT-UI-01 |
| `MaxTermsChars` | 200–4000 | 1500 | 1500 everywhere | AT-UI-04 |
| `CodexQueueDepth` | 4–256 | 32 | 32 everywhere | AT-UI-03 |
| `ScreenMessageMinSeconds` | 60–3600 | 300 | 300 everywhere | AT-UI-05 |

## 9. Interface

To modules: `CodexPage` (renders a view model, with each field's declared maximum, into a template, and declares its
handles and actions) and `CodexAction` (checks, terms text, subject version and the operation, which takes the operation
id, expected version and terms hash), registered per module. To players: §4.1's book and NPC pages. To admins:
`lc ui <char>` lists a player's contexts, tokens and slot.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| UI-R01 | Every Codex bypass MUST have been sent to the player in a page, and MUST resolve only against that player's own contexts and tokens; a handle MUST hold only an identifier; free text MUST come only from edit boxes, bounded and stripped. | AT-UI-01 |
| UI-R02 | Every state change MUST pass a one-use token bound to character, terms hash, subject version, deadline and NPC presence (object, life and instance), checked on the player's Codex queue at the click and at "yes", and run with the token's id as operation id, the operation re-checking version and terms in its own transaction. | AT-UI-02 |
| UI-R03 | Every confirmation answer with a negative requester id MUST be claimed by the Codex, without any path to Mobius's dialog handling, and acted on only if it matches the slot's nonce before the slot's deadline; no other answer MUST be claimed. | AT-UI-03 |
| UI-R04 | Pages MUST be rendered in the character's chosen locale with per-key fallback to English, with bounded fields, and paginated to fit the client's limits strictly before sending. | AT-UI-04 |
| UI-R05 | Notifications MUST use system messages, screen messages and the login recap, in the character's locale, within their rates. | AT-UI-05 |
| UI-R06 | No page MUST show hidden state; Intel, Ledger and Director data MUST reach pages only through their services' views, read at render and never kept beyond the request. | AT-UI-06 |
| UI-R07 | Mobius's own board pages MUST stay reachable when the Codex is the board's home, and NPC pages MUST work with the board disabled. | AT-UI-07 |

| Test | Kind | What it does |
|---|---|---|
| AT-UI-01 | integration | forged bypasses (never sent: no effect; another player's context or token, an id instead of a handle, an unparseable command: dropped and counted); input with markup and control characters arrives stripped, input over the bound cut; a page's own links work; the 17th page context drops the oldest |
| AT-UI-02 | integration | a token used twice (sequentially, and by two answers racing), after its deadline, after the contract was amended, with terms changed between page and "yes", **and with an amendment committed between "yes" and the operation's commit** (the operation refuses); away from the NPC, after the NPC despawned and its object id was reused, from the same coordinates in another instance; after the NPC died and respawned as the same object, and with the respawn paused between clearing the dead flag and `onSpawn` (the old answer refused); from a dead character; after a newer page of the module: refused. Faults injected before the operation, inside its commit and after it: nothing / all-or-nothing / applied once and shown on the next page |
| AT-UI-03 | integration | a Codex dialog and a pending offline-trade dialog in either order: each answer reaches only its own handler; a stale, forged or late (after the slot's deadline) negative answer is claimed and ignored, and a pending offline-trade action stays pending; the listener with a fault injected after claiming still terminates the event; a new Codex dialog kills the previous token; the dialog packet carries `DialogSeconds × 1000` |
| AT-UI-04 | unit, integration | every template in every locale renders within limits for view models at their declared maxima; pages of 48 749 and 48 750 characters: the first sent in three parts, the second paginated by the Codex before Mobius sees it; NPC pages of 17 200 and 17 201 likewise; no action cached from a page that was not sent; a character choosing RO and RU receives those texts, a key missing in RO falls back to English; a missing English key fails the build; an over-long terms text is not offered; terms that fit `MaxTermsChars` but would exceed a text field's maximum are shown whole, never cut, and the hash is of exactly what is shown |
| AT-UI-05 | integration | the packets emitted: a system message of the right id carrying the localised text; a screen message, rate-limited; the recap as `TutorialShowHtml`, once per login and only when there is something to recap |
| AT-UI-06 | unit, integration | the static scan: page handlers read Intel, Ledger and Director data only through their services' views and keep no view in a field, a handle or a cache; a planted read of a trace, a pressure value or a plan, or a planted cached view, fails it; through Codex navigation, an intel page reopened after expiry, opt-out, block or leaving the clan shows nothing |
| AT-UI-07 | integration | with `BBSDefault = _lcb home`, Alt+B opens the Codex and the Board tab opens Mobius's home board and its pages; with the board disabled, an NPC page's `_lc` action works and the book is refused |

## 11. Upstream constraints

1. **Bypass validation is per prefix.** `_bbs`, `bbs` and a few others skip `validateHtmlAction`
   (`RequestBypassToServer.java:56-66`); `_lc` and `_lcb` do not. A variable action (ending in `$`) is accepted by prefix
   (`Player.java:11589-11594`), so everything after the prefix is untrusted input (§4.2). A bypass that fails validation
   is dropped silently (`RequestBypassToServer.java:107-111`), before any handler or event.
2. **Board prefixes are dispatched before bypass handlers** (`RequestBypassToServer.java:131`) and refused when the board
   is disabled (`CommunityBoardHandler.java:109`). → the book and NPC pages use different prefixes (§4.2).
3. **Two limits for HTML**: strictly fewer than 3 × 16 250 for the board (`HtmlUtil.java:430-451`), whose actions are
   cached before the length check (`HtmlUtil.java:418-423`); 17 200 for NPC pages, truncated silently
   (`AbstractHtmlPacket.java:75-78`). → the Codex measures and paginates first (§4.6).
4. **One shared dialog message**: `ConfirmDlg(String)` uses `S1_3`, shared with Mobius's dialogs (`DlgAnswer.java:75`),
   whose requester id is 0; a listener exception does not stop the dispatch (`EventDispatcher.java:327-330`). → negative
   nonces, every negative answer claimed by a listener that cannot throw (§4.4), until the N6 client patch.
5. **Object ids are reused** (`managers/IdManager.java:158`), a respawn reuses the very object
   (`entity/spawns/Spawn.java:658-664`), the spawn event is asynchronous (`entity/actor/Npc.java:1331-1334`), the object is republished before `onSpawn` (`WorldObject.java:169-179`), and
   Mobius's bypass range check ignores instances (`util/LocationUtil.java:192`). → `NpcRef` with an incarnation advanced
   at decay and before republication (a small core patch) and `Npc.canInteract` (§4.3).
6. **Packets run on a worker pool** (`commons/network/packet/PacketExecutor.java:52`) without a per-player lock. → the
   Codex queue (§3.1).

## 12. Review record

- Round 3 (Codex), NO SIGN-OFF: one open, the incarnation advanced in `onSpawn`, after Mobius had already republished the
  object. Revision 4: it advances when a life ends (`onDecay`) and at the start of `initializeNpc`, before the dead flag is
  cleared and before publication; AT-UI-02 pauses respawn inside the publication window.
- Round 2 (Codex), NO SIGN-OFF: thirteen of fifteen fixed, one accepted as a stated limit; open were an NPC respawned as
  the same object matching its old token, and truncated fields able to hide agreement terms. Revision 3: a synchronous
  incarnation counter in `Npc.onSpawn` (a one-line core patch); terms never cut, offered only where shown whole.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 15 findings: stale or zero-requester answers falling through
  to Mobius's `S1_3` handlers; a Mobius dialog replacing a Codex one; listener exceptions failing open; no atomicity for
  tokens and slots; a race between the checks and the operation's commit; NPC presence by object id only; handles able to
  keep intel views; `_lc` registered as a board command breaking NPC pages with the board off; the dialog time in seconds
  instead of milliseconds and no server-side dialog expiry; the board limit off by one and its action cache; oversized
  runtime values; failure promises the operations cannot keep; counting bypasses Mobius drops; locale selection; and a
  notification test without packet oracles. Revision 2: every negative answer claimed by a listener that only hands off;
  the replacement case stated as a limit with its bound; a per-player Codex queue with compare-and-set; version and terms
  re-checked inside the operation's transaction; `NpcRef` with life and instance and `Npc.canInteract`; handles as
  identifiers only, re-read at render; `_lcb` for the book and `_lc` for NPC pages; milliseconds and a server deadline;
  strictly fewer than 48 750; declared field maxima, cut at render, terms bounded; §4.5's outcomes and matching fault
  oracles; counting only what reaches the Codex; the `LC_LOCALE` variable with per-key fallback; packet-level oracles.
