# SPEC-UI — Codex of Aden (UI framework)

- Status: draft (revision 1)
- Blueprint: 04 §5 (Codex of Aden); 02 §3 (one mechanism per concern), §12 (nothing the client sends is trusted); 13 §4
  (Signal's pages), 14 (Wayfarer's journal), 15 (PACT's ceremonies), 16 (Ledger pages), 17 (Chronicle, Gazette); 30 §2.4
  (localisation EN/RO/RU); 20 (client patch at N6).
- DDL: none. Pages, tokens and dialogs are in memory; what an action changes is stored by the module it calls.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Codex is the one way Living Chronicle shows pages and takes actions from players. It owns:

- **routing**: the bypass prefix `_lc`, its grammar, and the dispatch to module page handlers;
- **rendering**: templates with localisation keys, split to fit the client's limits, for the book (Community Board) and for
  NPC pages;
- **confirmation**: the one-use token and the native confirmation dialog through which every state-changing action passes;
- **notification**: system messages, screen messages and the login recap;
- **the rules every page obeys**: nothing hidden is shown, nothing the client sends is believed.

It does not own what pages say or what actions do: each module spec defines its pages and actions as flows (04 §5.3); the
Codex runs them. It never changes a game mechanic.

## 2. Data

All in memory, per online player; nothing survives a logout or restart, and nothing needs to (§7).

```text
PageContext   { id: 64-bit random, module, page, locale, handles: Map<short, Object> (≤ 64), npc: objectId or 0, issued_at }
ActionToken   { id: 128-bit random, char_id, module, action, terms_hash: 32 bytes, subject_ref, subject_version,
                deadline, npc: objectId or 0, presence_range, state {ISSUED|DIALOG|USED|DEAD} }
DialogSlot    { token_id, nonce: negative int, shown_at }        -- at most one per player
```

- A **handle** is a small number on a page standing for a server-side object (a contract, a page of results, a mark); a
  link carries handles, never ids, amounts or names. A player can only point at what a page offered them.
- A **terms hash** is SHA-256 of the canonical text of what the player is agreeing to (the module renders it; §4.3), so
  the action executes only what was shown.
- Bounds: `MaxPageContexts` per player (oldest dropped), `MaxOpenTokens` per player (oldest made `DEAD`).

## 3. State machines

### 3.1 Action token

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | page rendered with an action | the module offers it | `ISSUED` | the link `_lc a <token>` is in the page |
| `ISSUED` | the link clicked | §4.3 checks pass | `DIALOG` | the confirmation dialog shown (§4.4) |
| `ISSUED` | the link clicked | a check fails | `DEAD` | the page re-rendered with the reason |
| `DIALOG` | "yes" answered with the slot's nonce | §4.3 checks pass again | `USED` | the module's operation runs with operation id = token id |
| `DIALOG` | "no", timeout, another dialog, logout | — | `DEAD` | nothing |
| `DIALOG`, `ISSUED` | deadline passed, subject version changed, a newer page of the same module | — | `DEAD` | nothing |
| `USED`, `DEAD` | anything | — | unchanged | refused |

A token is used at most once by this machine; the module's operation is also idempotent by its id (asset-service.md §3,
`OpId`), so a crash between "yes" and the outcome cannot apply it twice.

### 3.2 Dialog slot

One per player: showing a new Codex dialog kills the token of the previous one. An answer whose nonce is not the slot's
is ignored.

## 4. Operations

### 4.1 Opening the book

- **Alt+B** sends `RequestShowBoard`, which runs `GeneralConfig.BBS_DEFAULT` (`network/clientpackets/RequestShowBoard.java:44`,
  default `_bbshome`, `dist/game/config/General.ini:422`). The Codex registers a Community Board handler for `_lc`, and
  the shipped configuration sets `BBSDefault = _lc home`. Mobius's own board pages (`dist/game/data/scripts/handlers/bypass/communityboard/`)
  stay reachable from the Codex's last tab, **Board**, so nothing upstream is lost.
- **The Wayfarer's Journal item** opens the same home page through an item handler.
- If `EnableCommunityBoard = False` (`General.ini:418`), the book is not available and the item says so; NPC pages still
  work. Startup logs this.

Tabs: Journal (Wayfarer), Signal, Contracts (PACT), Ledger (shown only to registered hunters, clients and marks), Chronicle,
Gazette, Laws (the Crown Law of the territory the character is in), Board. Ceremonies (signing at a Notarius, writs,
Broker contracts, questioning a witness) are NPC pages, never book pages (04 §5.1).

### 4.2 Routing

Grammar, by the first word after `_lc`: `p <ctx> <handle>...` (show a page), `a <token>` (take an action), `home`, and
`t <tab>`. Every argument is a decimal of bounded length; a bypass that does not parse is dropped and counted. Text the
player types (a search, a note) comes only from an edit box, sent as the bypass's variable part; it is the only free
text, bounded by `MaxInputChars`, stripped of `<`, `>`, `&` and control characters, and handed to the module as data.

Every `_lc` bypass is **validated by Mobius before it reaches the Codex**: `_lc` is not one of the prefixes exempt from
validation (`network/clientpackets/RequestBypassToServer.java:56-66`), so `Player.validateHtmlAction` accepts only an
action that was in a page sent to this player (`entity/actor/Player.java:11614`), and for an NPC page also the player's
distance to that NPC (`RequestBypassToServer.java:113`). The Codex sends book pages through `HtmlUtil.sendCBHtml`
(`util/HtmlUtil.java:411`), which rebuilds that cache. Then the router resolves `<ctx>` and `<token>` against the player's
own maps: an id of another player, an old page or a used token resolves to nothing.

### 4.3 Checks before an action

At the click and again at "yes": the token is the player's own, in the right state, before its deadline; the subject's
current version equals `subject_version` (a contract amended since the page was shown kills the token: the player must
see the new terms); the module recomputes the terms hash from current state and it equals the token's; the player is
within `presence_range` of `npc` where the action requires presence (the NPC must still exist and be the same object);
and the player can act (not dead, not in a state Mobius forbids for dialogs: trading, in an olympiad match, teleporting).

### 4.4 Confirmation dialog

The dialog is Mobius's native one: `ConfirmDlg(String)` (`network/serverpackets/ConfirmDlg.java:44`) shows the terms'
short form with Yes/No, with `addTime(DialogSeconds)` and `addRequesterId(nonce)`. That constructor uses system message
`S1_3`, which Mobius also uses for its own dialogs (offline trade, offline play, engagement, admin confirmations;
`network/clientpackets/DlgAnswer.java:75-187`), told apart by pending player actions. The Codex therefore answers through
the `ON_PLAYER_DLG_ANSWER` listener, which runs first (`DlgAnswer.java:66-73`): it claims an answer only if the message is
`S1_3` and the requester id is **negative and equals the slot's nonce**; Mobius sends only object ids, which are positive,
as requester ids (`dist/game/data/scripts/handlers/skill/effects/CallPc.java:96`). A claimed answer terminates the event,
so Mobius's own handling never sees it; any other answer passes through untouched. From N6, when the client patch adds
its own system message id, the dialog uses it and the nonce remains.

### 4.5 Rendering

Templates live in `dist/game/data/html/LivingChronicle/<module>/`, with text as keys in
`dist/game/data/lang/LivingChronicle/<locale>.properties` (EN, RO, RU; the character's locale, English when a key is
missing, and a missing English key fails the build). Values are escaped when inserted. A book page is at most three parts
of 16250 characters, the client's limit that Mobius splits by (`util/HtmlUtil.java:430-451`); an NPC page at most 17200,
beyond which Mobius truncates (`network/serverpackets/AbstractHtmlPacket.java:75-78`). The Codex measures the rendered
page and **paginates before sending**, never letting Mobius truncate: a list is cut into pages that fit, each with its
own context.

### 4.6 Notifications

- System messages with the Living Chronicle text as a parameter of `S1_3` until N6; with their own ids after it.
- `ExShowScreenMessage` for rare moments (at most one per `ScreenMessageMinSeconds` per player).
- `TutorialShowHtml` for the login recap, once per login, only if there is something to recap.

## 5. Facts

The Codex emits telemetry only: `Ui.PageShown` (module, page, render micros, parts), `Ui.ActionTaken` (module, action,
outcome), `Ui.BypassRejected` (reason: parse, unknown context, token state, presence, version, terms). The operations it
runs emit their own durable facts.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| UI-INV01 | no state change happens without a `USED` token whose terms hash matched current state at "yes" | by construction; AT-UI-02 |
| UI-INV02 | a link carries only handles, contexts and tokens of the player's own session | by the router; AT-UI-01 |
| UI-INV03 | no page is truncated by Mobius | by the renderer's measurement; AT-UI-04 |
| UI-INV04 | no page shows hidden state: a page renders only what its module's view model offers, and view models of Intel, Ledger and Director data come from their services' coarse views | by the static scan; AT-UI-06 |
| UI-INV05 | a Codex dialog answer never reaches Mobius's own dialog handling, and no other answer reaches the Codex | AT-UI-03 |

## 7. Failure model

| Fault | End state |
|---|---|
| player logs out or crashes during a dialog | the token dies; nothing ran, or the operation ran once under its id |
| server crashes after "yes" | the module's operation committed or not; a retry after restart is impossible (new session, new token), and the operation's id prevents a second application |
| a module's handler throws | the player sees a generic error page; `Ui.BypassRejected`; the module's state is unchanged |
| a forged or replayed bypass | dropped by Mobius's validation or by the router; counted |
| a page too large | paginated; if one item alone is too large, the build-time template test fails |

## 8. Configuration

`config/Custom/LivingChronicle/Codex.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `TokenSeconds` | 10–600 | 120 | 120 everywhere | AT-UI-02 |
| `DialogSeconds` | 5–120 | 30 | 30 everywhere | AT-UI-03 |
| `MaxPageContexts` | 4–64 | 16 | 16 everywhere | AT-UI-01 |
| `MaxOpenTokens` | 4–64 | 16 | 16 everywhere | AT-UI-02 |
| `MaxInputChars` | 16–512 | 128 | 128 everywhere | AT-UI-01 |
| `ScreenMessageMinSeconds` | 60–3600 | 300 | 300 everywhere | AT-UI-05 |

## 9. Interface

To modules: `CodexPage` (renders a view model into a template and declares its handles and actions) and `CodexAction`
(checks, terms text, subject version and the operation to run), registered per module. To players: §4.1's book and NPC
pages. To admins: `lc ui <char>` lists a player's contexts and tokens.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| UI-R01 | Every `_lc` bypass MUST have been sent to the player in a page, and MUST resolve only against that player's own contexts and tokens; free text MUST come only from edit boxes, bounded and stripped. | AT-UI-01 |
| UI-R02 | Every state change MUST pass a one-use token bound to character, terms hash, subject version, deadline and presence, checked at the click and at "yes", and run the operation with the token's id as its operation id. | AT-UI-02 |
| UI-R03 | The confirmation dialog MUST be claimed only by its negative nonce and MUST never interfere with Mobius's own dialogs. | AT-UI-03 |
| UI-R04 | Pages MUST be localised (EN/RO/RU) and paginated to fit the client's limits before sending. | AT-UI-04 |
| UI-R05 | Notifications MUST use system messages, screen messages and the login recap within their rates. | AT-UI-05 |
| UI-R06 | No page MUST show hidden state; Intel, Ledger and Director data MUST reach pages only through their services' coarse views. | AT-UI-06 |
| UI-R07 | Mobius's own board pages MUST stay reachable when the Codex is the board's home. | AT-UI-07 |

| Test | Kind | What it does |
|---|---|---|
| AT-UI-01 | integration | forged bypasses (never sent, another player's context or token, an id instead of a handle, oversized or markup-laden input, an unparseable command) are dropped and counted; a page's own links work; the 17th page context drops the oldest |
| AT-UI-02 | integration | a token used twice, after its deadline, after the contract was amended, with terms changed between page and "yes", away from the NPC, from a dead character, or after a newer page of the module: refused; a crash after "yes" and a reconnect: the operation applied once |
| AT-UI-03 | integration | a Codex dialog and a pending offline-trade dialog in either order: each answer reaches only its own handler; a "yes" with a stale or positive requester id is ignored; a new dialog kills the previous token |
| AT-UI-04 | unit, integration | every template in every locale renders within limits for maximal view models; a list longer than one page is paginated without Mobius truncating; a missing English key fails the build |
| AT-UI-05 | integration | screen messages rate-limited; the recap shown once per login and only when there is something to recap |
| AT-UI-06 | unit | the static scan: page handlers read Intel, Ledger and Director data only through their coarse views; a planted read of a trace, a pressure value or a plan fails it |
| AT-UI-07 | integration | with `BBSDefault = _lc home`, Alt+B opens the Codex and the Board tab opens Mobius's home board and its pages |

## 11. Upstream constraints

1. **Bypass validation is per prefix.** `_bbs`, `bbs` and a few others skip `validateHtmlAction`
   (`RequestBypassToServer.java:56-66`); a Codex link must therefore not start with any of them, and `_lc` does not.
   A variable action (ending in `$`) is accepted by prefix (`Player.java:11589-11594`), so everything after the prefix is
   untrusted input (§4.2).
2. **Two limits for HTML**: 3 × 16250 for the board (`HtmlUtil.java:430-451`), 17200 for NPC pages, truncated silently
   (`AbstractHtmlPacket.java:75-78`). → the Codex paginates first (§4.5).
3. **One shared dialog message**: `ConfirmDlg(String)` uses `S1_3`, shared with Mobius's dialogs (`DlgAnswer.java:75`).
   → negative nonces through the dialog listener (§4.4), until the N6 client patch gives the Codex its own id.
4. **The board home is configuration** (`BBSDefault`), and `CommunityBoardHandler` matches registered prefixes
   (`handler/CommunityBoardHandler.java:65-78`), so the Codex adds a handler and changes a configuration value; no core
   patch is needed for routing.

## 12. Review record

- Revision 1: draft, not yet reviewed.
