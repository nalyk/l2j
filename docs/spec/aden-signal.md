# SPEC-SIG — Aden Signal

- Status: draft (revision 1)
- Blueprint: 13 (Aden Signal); 02 §1 (we do not break the game); 03 §4.4 (`ON_PLAYER_PARTY_JOIN`, `ON_PLAYER_PARTY_LEAVE`);
  04 §4 (Intel Service), §5 (Codex); 10 (citizens as candidates); 12 (Aden Remembers consumes demand); 14 (Wayfarer's
  collaborators); 17 (Gazette); 20 §1 (Signal introduces, PACT commits); consensus §3 (actor classes reported apart).
- Depends on: intel-service.md (Signal location purpose, §4.4; `lc_signal_presence`), actor-identity.md (snapshots,
  activity, policy domains), codex-ui.md (pages, tokens, dialogs), fact-journal.md (facts, telemetry, projectors).
- DDL: `sql/migrations/V013__lc_signal.sql` (after the Ledger's V012).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Party Finder asks who advertised; Signal asks which people currently want compatible things, and introduces them. It
owns: profiles (opt-in and visibility), **intents** (now) and **schedules** (later), **matching**, **mutual interest** and
the reveal, **latent party detection**, **future-session alignment**, **demand aggregates**, and the record of the
**sessions** that followed an introduction.

It does not own: parties (Mobius: Signal never creates, joins or moves anyone; people invite each other through retail),
location (the Intel Service: Signal sees a candidate's region only through its Signal purpose, intel-service.md §4.4),
retail party matching (`entity/groups/matching/PartyMatchRoomList.java`, `PartyMatchWaitingList.java`, unchanged and the
baseline Signal is measured against), contracts (PACT commits what Signal introduces, 20 §1).

### 1.1 Zero balance effect

Signal grants 0 XP, 0 Adena, 0 items, 0 buffs, 0 teleportation, 0 combat modifiers, and never rewards its own use (13 §3).
It changes only the probability that compatible people meet. Living Chronicle code calls no mechanics API
(actor-identity.md §3); AT-SIG-09 checks Signal's. Support classes are never replaced: Signal never fills a missing role
with an NPC (13 §2); it makes support players discoverable.

## 2. Data

### 2.1 Tables and memory

```text
lc_signal_profile(char_id PK, opted_in BOOLEAN, visibility {EVERYONE|ALLIANCE|CLAN|NOBODY}, latent BOOLEAN, languages SET,
                  pace {RELAXED|STEADY|FAST}, allow_synthetic BOOLEAN, standing_objectives SET, updated_at)
lc_signal_schedule(schedule_id PK auto, char_id, objective, role_sought SET, region_pref JSON, start_at, end_at,
                   languages SET, expires_at = end_at)
lc_signal_block(char_id, blocked_char, at, PRIMARY KEY(char_id, blocked_char))
lc_signal_session(session_id PK auto, formed_at, members JSON, cohort {HUMAN|SYNTHETIC|MIXED}, objective, introduced_by
                  {INTENT|LATENT|SCHEDULE}, continued_after_first BOOLEAN NULL, ended_at NULL, expires_at)
lc_signal_demand(window_start, territory_id, objective, role_sought, intents INT, cohort {HUMAN|SYNTHETIC|MIXED},
                 PRIMARY KEY(window_start, territory_id, objective, role_sought, cohort))
lc_signal_audit_sample(sample_id PK auto, at, intent_a_class, intent_b_class, score, components JSON, expires_at)
```

In memory, per online character (13 §6: current intents are memory-first): the **intent** (objective, role, level
range, preferred regions, `until`, travel tolerance, languages, pace, join-existing, willing-to-lead, now or later, notes),
its **candidates** (other intents with their scores, recomputed every `MatchSeconds`), and **interest** marks
(who expressed interest in whom). They vanish at logout, at `until`, or at opt-out; nothing is lost that a player did
not choose to drop.

- `members` of a session are character ids with their origin class (for cohorts) and nothing else; sessions, schedules
  and audit samples expire (`expires_at`, registered in `lc_expiring_table`, intel-service.md §4.5), so the record of who
  played with whom lives `SessionRetentionDays` (Wayfarer keeps its own opted-in memory, 14).
- `notes` are free text a player typed: bounded by `MaxNotesChars`, stripped (codex-ui.md §4.2), shown only after mutual
  interest, never stored.
- `lc_signal_demand` is an aggregate (telemetry-like): counts only, by territory, never a character.

### 2.2 Reference tables

Configuration tables, reviewed like the spec (`config/Custom/LivingChronicle/Signal.ini` and its data files): the **class
roles** (each `PlayerClass`, `entity/actor/enums/player/PlayerClass.java`, mapped to the roles it can fill: tank, healer,
buffer, nuker, archer, dagger, summoner, spoiler, crafter, support; a class may fill several), the **region travel table**
(estimated minutes between map regions, by gatekeeper and on foot, from the journal's region index, fact-journal.md §2.3),
and the **score weights** (§4.3).

## 3. State machines

### 3.1 Intent

| From | Event | To |
|---|---|---|
| — | set (Codex form or `.signal`) by an opted-in character | `ACTIVE` |
| `ACTIVE` | edit | `ACTIVE` (candidates recomputed) |
| `ACTIVE` | `until` passes, logout, opt-out, the character becomes non-`INTERACTIVE` (actor-identity.md §2.3) | gone |
| `ACTIVE` | cancel | gone |
| `ACTIVE` | the character joins a party | `ACTIVE`, now speaking for the party: others are offered to it only while it has room and its leader allows joining |

### 3.2 Introduction between two intents A and B

| From | Event | To | Effect |
|---|---|---|---|
| — | B is among A's top `ShowCandidates` and A may see B (§4.2) | `SHOWN` | A sees B **anonymised**: role, level band, objective, time overlap, travel minutes, languages, pace |
| `SHOWN` | A expresses interest (a Codex action, codex-ui.md §4.4) | `A_INTERESTED` | B is told someone compatible is interested, anonymised |
| `A_INTERESTED` | B expresses interest in A | `MUTUAL` | both see each other's name, notes and region (the region through the Intel Service's Signal purpose) |
| any | either intent gone, a block, `InterestMinutes` pass | gone | nothing kept |
| `MUTUAL` | they are in one party within `SessionWindowMinutes` (`ON_PLAYER_PARTY_JOIN`) | session | `lc_signal_session` |

## 4. Operations

### 4.1 Opt-in and visibility

Nothing about a character enters Signal until they opt in (`lc_signal_profile.opted_in`). Visibility says who may see them
as a candidate: everyone, their alliance, their clan, or nobody (a character can then still look, and be told of mutual
interest only by people they themselves showed interest in). Opting out removes the intent, the candidates and the Intel
Service's Signal presence (`lc_signal_presence`) within one match cycle (`MatchSeconds`). Hiding costs nothing: there is no
penalty for visibility `NOBODY`, and a character who is a mark (a public Writ, or holding a Torn Black Seal) is offered
"hide from Signal" on their Ledger page (13 §4).

### 4.2 Who may be a candidate for whom

B is a candidate for A only if all hold: both opted in, both `INTERACTIVE` (no offline traders or offline play, and never
an `NPC_FAKE`: actor-identity.md §2.1, §3, a `PARTICIPATION` policy rule); B's visibility includes A; neither has blocked
the other in Mobius (`entity/actor/holders/player/BlockList.java:151`, `isBlocked`, both directions) or in Signal
(`lc_signal_block`); B is a citizen only if A allows synthetic candidates (`allow_synthetic`, default yes); their level
ranges, objectives and time windows intersect; the travel minutes between their regions are within both tolerances;
and B's party (if any) has room (a party holds 9, `network/clientpackets/RequestJoinParty.java:217`) and B said
join-existing. Citizens and humans are shown alike: nothing in a candidate view says which is which (13 §2).

### 4.3 Score

`score = w_obj·objective + w_lvl·level + w_role·complement − w_travel·travel + w_time·overlap + w_lang·languages +
w_pace·pace + w_known·collaborated`, each term in [0, 1] from the two intents (role complementarity: how many of the roles
the party-to-be lacks the candidate's class can fill, by the class roles table; never a meta composition, 13 §5;
`collaborated`: 1 when both opted into Wayfarer's memory and played a session together before, 14). Ties broken by the keyed
RNG (`rng("signal.tie", a, b, cycle)`, 04 §7), so no one is always first. A party-to-be is filled greedily up to its
capacity. The score has **no other input**: no Director or Aden Remembers goal, no hidden ranking, no engagement target (20
§1: Remembers reads Signal's demand, never steers its matches). The `collaborated` term is never hidden: after mutual
interest the page says "you have played together before" when it applied, and only both players' Wayfarer opt-in turns
it on. AT-SIG-03 checks the formula against hand-computed cases.

### 4.4 What is disclosed when

| Stage | A learns about B |
|---|---|
| shown | role, level band (5 levels), objective, time overlap in minutes, travel minutes from A's region (one number), languages, pace |
| interested | that someone compatible is interested (B's view of A, anonymised as above) |
| mutual | name, notes, region (Intel Service, `REGION`, `SignalLocationTtlMinutes`, only while the Intel Service's Signal rules hold, intel-service.md §4.4) |

Before mutual interest Signal discloses no name and no region; travel minutes are rounded to `TravelBucketMinutes`, so a
shown candidate cannot be located. No coordinate ever leaves the Intel Service.

### 4.5 Latent party detection

For opted-in characters with `latent` enabled and no active intent, Signal forms an implicit intent from their standing
objectives and their current activity class (hunting, idle in town; from their own session, never another's) and looks
for a viable party-to-be: at least `LatentMinParty` compatible characters within `LatentTravelMinutes`. When one exists,
each member receives the Codex dialog (codex-ui.md §4.4): "A viable party may be formed nearby. *n* compatible players.
Estimated rendezvous: *m* minutes. Common intent: *objectives*." At most once per `LatentPromptMinutes` per character.
Those who answer yes see each other as `MUTUAL` once at least two said yes; names are revealed only among them.

### 4.6 Future sessions

A schedule ("tomorrow 20:30–22:00, Cruma, XP/spoil") is persisted. At login, and `SchedulePromptMinutes` before its start,
its owner is told how many other schedules align with it (overlap ≥ `ScheduleMinOverlap`, compatible as §4.2): "5 players
aligned with your planned Cruma session" — a count only. At the start, the aligned schedules become intents for those online,
and they meet as in §3.2. Schedules expire at their end.

### 4.7 Demand

Every `DemandWindowMinutes` the active intents are counted by (territory, objective, role sought, cohort). A cell is
published (to Aden Remembers as a fact, and to the Gazette's `signal_demand` publication type, intel-service.md §4.7) only
when it counts at least `DemandMinIntents` distinct characters, and only at territory precision, after
`PublicationTacticalDelayMinutes` for the Gazette: "many are seeking spoilers around Oren".

### 4.8 Sessions and outcomes

A session is recorded when characters who reached `MUTUAL` (or answered a latent prompt, or aligned schedules) are in one
party within `SessionWindowMinutes`, observed through the `ON_PLAYER_PARTY_JOIN` and `ON_PLAYER_PARTY_LEAVE` seams (03 §4.4;
Mobius dispatches no party events today, `entity/groups/Party.java:301`, `:422`, `:551`). `continued_after_first` is set
when the party still holds two of them `ContinueMinutes` later. Cohorts are `HUMAN`, `SYNTHETIC` or `MIXED` by the members'
origins (a `METRICS` policy rule, actor-identity.md §3), and every metric is reported per cohort (consensus §3).

### 4.9 Signal introduces, PACT commits

After mutual interest, the page offers the matching PACT draft for the intent (20 §1): a crafter and a client → a Crafting
Pact draft; a mercenary intent → a Mercenary Contract draft; a supplier and a buyer → a Purchase Order draft, each
pre-filled only with what both declared (objective, item if named, time). Signal does nothing more: the draft is PACT's,
signed or not by PACT's rules (pact.md §3.1), and no Signal state depends on it.

## 5. Facts

Telemetry (fact-journal.md §2.6): `Signal.IntentSet` (objective, role, territory, cohort), `Signal.Shown`,
`Signal.Interest`, `Signal.Mutual`, `Signal.LatentPrompt` (answer). Outbox: `Signal.SessionFormed` (session, cohort,
objective, introduced by), `Signal.SessionEnded` (continued), `Signal.OptOut`, `Signal.Demand` (the published cells).
No fact names a character's region or a candidate before mutual interest.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| SIG-INV01 | Signal grants nothing and calls no mechanics API | static scan; AT-SIG-09 |
| SIG-INV02 | no name or region of a candidate is disclosed before mutual interest; no coordinate ever | the disclosure table; AT-SIG-04 |
| SIG-INV03 | no candidate violates §4.2 | the filter; AT-SIG-02 |
| SIG-INV04 | an opt-out leaves no intent, candidate or presence after one cycle | AT-SIG-05 |
| SIG-INV05 | no published demand cell counts fewer than `DemandMinIntents` characters | AT-SIG-07 |

## 7. Failure model

| Fault | End state |
|---|---|
| restart | intents and interests are gone (memory-first); schedules and sessions remain; players set intents again |
| Intel Service down | no region at mutual interest ("region unavailable"); matching by declared regions only |
| the party seams unavailable | no sessions recorded; introductions still work |
| matching overload | the cycle is skipped and counted; never a partial candidate list |

## 8. Configuration

`config/Custom/LivingChronicle/Signal.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `MatchSeconds` | 5–120 | 20 | 20 / 20 / 5 / 20 / 20 | AT-SIG-05 |
| `ShowCandidates` | 1–50 | 10 | 10 everywhere | AT-SIG-03 |
| `InterestMinutes` | 1–60 | 10 | 10 everywhere | AT-SIG-04 |
| `TravelBucketMinutes` | 1–30 | 5 | 5 everywhere | AT-SIG-04 |
| `LatentMinParty`, `LatentTravelMinutes`, `LatentPromptMinutes` | 2–9, 1–60, 10–1440 | 3, 10, 60 | as default everywhere | AT-SIG-06 |
| `SchedulePromptMinutes`, `ScheduleMinOverlap` | 5–240, 10–240 | 30, 30 | as default everywhere | AT-SIG-06 |
| `DemandWindowMinutes`, `DemandMinIntents` | 10–1440, 3–100 | 60, 5 | as default everywhere | AT-SIG-07 |
| `SessionWindowMinutes`, `ContinueMinutes`, `SessionRetentionDays` | 5–120, 10–240, 1–90 | 30, 60, 30 | as default everywhere | AT-SIG-08 |
| `MaxNotesChars` | 0–200 | 120 | 120 everywhere | AT-SIG-04 |
| score weights `w_*` | 0–10 | 3, 2, 3, 2, 2, 2, 1, 1 | as default everywhere | AT-SIG-03 |

## 9. Interface

Codex → Signal tab, and the voiced command `.signal`, which opens it (Mobius dispatches `.`-prefixed chat to voiced command
handlers, `dist/game/data/scripts/handlers/chat/channels/ChatGeneral.java:48-64`; a client `/signal` is never sent): set
intent, view aligned people (anonymised), express interest, reveal on mutual, schedule a future session, manage visibility,
block. Notifications as system messages; the latent prompt as the Codex dialog. Admin: `lc why signal <char>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| SIG-R01 | Nothing about a character MUST enter Signal before opt-in, and opt-out MUST remove it within one cycle. | AT-SIG-05 |
| SIG-R02 | Candidates MUST satisfy §4.2: opted in, interactive, never an NPC fake, visible, unblocked both ways, synthetic only if allowed, compatible, within travel and capacity. | AT-SIG-02 |
| SIG-R03 | Scores MUST follow §4.3 with configured weights and keyed tie-breaking, without meta optimisation. | AT-SIG-03 |
| SIG-R04 | Names, notes and regions MUST be disclosed only on mutual interest, regions only through the Intel Service; nothing before but §4.4's anonymised fields. | AT-SIG-04 |
| SIG-R05 | Latent prompts and future-session alignment MUST disclose only counts, travel and common intent until mutual interest. | AT-SIG-06 |
| SIG-R06 | Demand MUST be published only in cells of at least `DemandMinIntents` characters, at territory precision, after the tactical delay. | AT-SIG-07 |
| SIG-R07 | Sessions MUST be recorded from party seams and reported per cohort, and expire. | AT-SIG-08 |
| SIG-R08 | Signal MUST grant nothing, reward nothing, and never fill a role with an NPC. | AT-SIG-09 |
| SIG-R10 | After mutual interest Signal MUST offer only the matching PACT draft, pre-filled with what both declared, and take no part in it. | AT-SIG-11 |
| SIG-R09 | Introductions through Signal MUST improve time-to-first-contact and party conversion over retail party matching alone, measured per cohort, with support classes found faster. | AT-SIG-10 |

| Test | Kind | What it does |
|---|---|---|
| AT-SIG-00 | integration | the migration applies; keys and checks refuse what this spec forbids (a schedule ending before it starts; a demand cell below the threshold; expiring rows registered) |
| AT-SIG-01 | integration | profile, intent and schedule flows through the Codex; `.signal` opens the tab |
| AT-SIG-02 | integration | each exclusion of §4.2 at its boundary: not opted in, offline trader, offline play, NPC fake, visibility clan/alliance/nobody, Mobius block either way, Signal block, synthetic disallowed, level ranges just apart, travel over tolerance, a full party |
| AT-SIG-03 | unit | hand-computed scores for a table of intent pairs; ties broken by the keyed RNG; a party-to-be filled greedily to 9; no class composition favoured beyond the formula; the static scan finds no input to the score outside §4.3 (no Director or Remembers read) |
| AT-SIG-04 | integration | before mutual interest no name, note or region in any page or packet; travel rounded; at mutual interest the region from the Intel Service at `REGION`, refused when its Signal rules fail |
| AT-SIG-05 | integration | opt-out: after one cycle no intent, candidate or `lc_signal_presence` row |
| AT-SIG-06 | integration | latent prompts at most once per window, with counts only; names only among those who said yes; schedule alignment counts at login and before start |
| AT-SIG-07 | integration | demand cells below the threshold never published; published ones at territory precision after the delay |
| AT-SIG-08 | integration | sessions from party joins within the window, continued flag at `ContinueMinutes`, cohorts by origin, gone after retention |
| AT-SIG-09 | unit | the static scan: no Signal code calls a mechanics API, grants an item, Adena, XP or buff, or spawns an NPC |
| AT-SIG-11 | integration | a crafter and a client, a mercenary and an employer, a supplier and a buyer at mutual interest: the offered draft of the right PACT type, pre-filled only with declared fields; Signal unchanged whether it is signed or not |
| AT-SIG-10 | sim, human | A/B runs with citizen populations and tester sessions: Signal plus retail matching against retail matching alone; time-to-first-contact and party conversion per cohort, and time for parties missing a healer or buffer to find one |

## 11. Upstream constraints

1. **Mobius dispatches no party join or leave event** (`Party.java:301`, `:422`, `:551`). → the party seams (03 §4.4).
2. **Retail party matching exists** (`PartyMatchRoomList`, `PartyMatchWaitingList`). → left unchanged; Signal's baseline.
3. **A party holds 9** (`RequestJoinParty.java:217`). → the capacity constraint.
4. **Blocks are per character, either way** (`BlockList.java:151`). → checked both ways.
5. **Slash commands are client-side; `.`-commands reach the server** (`ChatGeneral.java:48-64`). → `.signal`.

## 12. Review record

- Revision 1: draft, not yet reviewed.
