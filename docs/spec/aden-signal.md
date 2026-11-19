# SPEC-SIG — Aden Signal

- Status: draft (revision 5, answering four adversarial review rounds; record in §12)
- Blueprint: 13 (Aden Signal); 02 §1 (we do not break the game), §4 (production checks), §9 (tests decide); 03 §4.4
  (`ON_PLAYER_PARTY_JOIN`, `ON_PLAYER_PARTY_LEAVE`); 04 §4 (Intel Service), §5 (Codex), §7 (keyed randomness); 10
  (citizens as candidates); 12 (Aden Remembers consumes demand); 14 (Wayfarer's collaborators); 17 (Gazette); 20 §1
  (Signal introduces, PACT commits); consensus §3 (actor classes reported apart).
- Depends on: intel-service.md (Signal presence and location, §4.4 and §4.9 of its revision 7), actor-identity.md
  (snapshots, activity, policy domains, packet equality), codex-ui.md (pages, tokens, dialogs, queue), fact-journal.md
  (facts, telemetry), pact.md (drafts).
- DDL: `sql/migrations/V013__lc_signal.sql` (after the Ledger's V012). Reference data: `docs/spec/signal-class-roles.csv`.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Party Finder asks who advertised; Signal asks which people currently want compatible things, and introduces them. It owns
profiles (opt-in, visibility), **intents** (now) and **schedules** (later), **matching**, **interest** and the
**reveal**, **latent party** prompts, **future-session** alignment, **demand** aggregates and **sessions**.

It does not own: parties (Mobius: Signal never creates, joins or moves anyone), location (the Intel Service: Signal learns
a candidate's actual region only through it, and only after mutual interest), retail party matching
(`entity/groups/matching/PartyMatchRoomList.java`, `PartyMatchWaitingList.java`, unchanged, the baseline), contracts
(PACT commits what Signal introduces).

### 1.1 Zero balance effect

Signal grants 0 XP, 0 Adena, 0 items, 0 buffs, 0 teleportation, 0 combat modifiers, never rewards its own use, and never
fills a missing role with an NPC (13 §2-§3). Living Chronicle code calls no mechanics API (actor-identity.md §3).

### 1.2 What Signal protects, and what it does not

- **Location** is protected: before mutual interest nothing Signal shows depends on where a candidate actually is. An
  intent's places are the regions its owner **declares** (where they want to play); travel is computed from the viewer to
  those declared regions. A candidate's actual region comes only from the Intel Service, at `REGION`, after mutual
  interest. The latent prompt (§4.6) is the one exception, and it concerns only consenting characters in the viewer's own
  region.
- **Identity** before mutual interest is protected **only as far as an advertisement can be**: the anonymised fields
  (§4.4) are what a player chose to publish, as on retail's party matching board, which shows names. A determined
  observer of a sparse population may guess who is behind them; Signal coarsens the fields and rate-limits views, but
  does not promise unlinkability. Visibility `NOBODY` is the protection for a player who wants none.
- **Harassment** is bounded: per-pair cooldowns, per-recipient caps and anonymous blocking (§4.5).

## 2. Data

### 2.1 Tables and memory

```text
lc_signal_profile(char_id PK, opted_in, visibility {EVERYONE|ALLIANCE|CLAN|NOBODY}, latent, languages SET, pace,
                  allow_synthetic, synthetic_changed_at, standing_objectives SET, updated_at)
lc_signal_schedule(schedule_id PK, char_id, objective, role_sought SET, region_pref JSON, languages SET, start_at, end_at,
                   expires_at = end_at)
lc_signal_block(char_id, blocked_char, anonymous BOOLEAN, at, PRIMARY KEY(char_id, blocked_char))
lc_signal_session(session_id PK, introduction_id UNIQUE, formed_at, cohort {HUMAN|SYNTHETIC|MIXED}, objective,
                  introduced_by {INTENT|LATENT|SCHEDULE}, continued_after_first BOOLEAN NULL, ended_at NULL, expires_at)
lc_signal_session_member(session_id, char_id, origin, PRIMARY KEY(session_id, char_id))
lc_signal_demand(day DATE, territory_id, objective, intents_band {5-9|10-19|20+}, PRIMARY KEY(day, territory_id, objective))
lc_signal_audit_sample(sample_id PK, at, class_a, class_b, score, components JSON, expires_at)
```

In memory, per online character, on the character's Codex queue (codex-ui.md §3.1): the **intent** with its **version**
(any edit, and any change of its owner's visibility, clan, alliance or synthetic preference, makes a new version); its
**candidates** (recomputed every `MatchSeconds`); **introductions** (§3.2), each with an id and the two intent versions it
binds; **latent groups** (§4.6); and the **interest ledger** of cooldowns and caps (§4.5). In-memory state is published
by the queue that changes it: an operation either publishes its new state or leaves the old one, and durable parts
(profile, schedule, block) are committed **before** the memory publication, so a failure never leaves memory ahead of the
database. **An introduction has its own lock**: every transition of it, from either character's queue, takes that lock
and, under it, checks that the introduction is not dropped and that both bound intent versions are still the current
ones; an intent edit, under each of its introductions' locks in turn, drops them. So a transition on B's queue can never
publish a mutual introduction for a version A has just replaced.

### 2.2 Reference data

- **Class roles**: `docs/spec/signal-class-roles.csv` maps every `PlayerClass` (`entity/actor/enums/player/PlayerClass.java`,
  all 103 classes) to the roles it fills: `TANK`, `HEALER`, `BUFFER`, `NUKER`, `ARCHER`, `DAGGER`, `SUMMONER`, `SPOILER`,
  `CRAFTER`, `SUPPORT`, `DAMAGE` (a class may fill several). Reviewed like the spec; a test fails on a class missing from it.
- **Travel minutes** between map regions (the journal's region index, fact-journal.md §2.3) are computed at startup, the
  same way on every server, as a shortest path in a graph whose nodes are regions: a region's **centre** is the mean of the
  centres of its map cells (Mobius's map-region data, `data/xml/MapRegionData.java:91`, keeps cells and respawns, not a
  centre, so Signal computes it); a **walking edge** joins two regions whose cells touch, costing the centre distance at
  120 units per second; a **gatekeeper edge** costs one minute and joins the region of each gatekeeper to the region of
  each destination of its **unrestricted** teleport lists (`dist/game/data/teleporters/`; a list with conditions, such as
  noblesse or item lists, is not an edge). Minutes are rounded up; between a viewer and an intent with several declared
  regions, travel is the minimum over them. Regions without map cells (such as the Coliseum's,
  `dist/game/data/mapregion/colosseum.xml:5`) have no centre: they are not in the graph and cannot be declared.

## 3. State machines

### 3.1 Intent

| From | Event | To |
|---|---|---|
| — | set (Codex form or `.signal`) by an opted-in character | `ACTIVE` version 1 |
| `ACTIVE` vN | edit; or a change of the owner's visibility, clan, alliance or synthetic preference | `ACTIVE` vN+1; every introduction binding vN is dropped |
| `ACTIVE` | cancel, `until`, logout, opt-out, the owner no longer `INTERACTIVE` | gone; its introductions dropped |
| `ACTIVE` | the owner joins a party | `ACTIVE`, now speaking for the party: others are offered to it only while it has room and its leader allows joining |

### 3.2 Introduction (id, intent A vX, intent B vY)

| From | Event | To | Effect |
|---|---|---|---|
| — | B is among A's top `ShowCandidates` and visible to A (§4.2) | `SHOWN` | A sees B anonymised (§4.4); the view's fields are the token's terms (codex-ui.md §4.3) |
| `SHOWN` | A expresses interest, on the Codex queue, with the token of that view | `A_INTERESTED` | B is told someone compatible is interested, anonymised; the interest ledger is charged (§4.5) |
| `A_INTERESTED` | B expresses interest in A (B's view of A, its own token) | `MUTUAL` | both see names, notes and, through the Intel Service, regions (§4.4); Intel is told the introduction is mutual until `MutualMinutes` |
| `SHOWN`, `A_INTERESTED` | `InterestMinutes` pass | `DROPPED` | the pair's cooldown starts (§4.5) |
| `MUTUAL` | `MutualMinutes` pass (at least `SessionWindowMinutes`, by their ranges) | `DROPPED` | Intel told (`signalUnmutual`) |
| any | a bound version changes, either intent is gone, a block either way | `DROPPED` | the pair's cooldown starts; if it was mutual, Intel told (`signalUnmutual`) at once |
| `MUTUAL` | the two are in one party within `SessionWindowMinutes`, having **not** been in one party at `MUTUAL` | session | `lc_signal_session` keyed by the introduction id |

Every transition re-checks §4.2 on the queue; one that fails drops the introduction.

## 4. Operations

### 4.1 Opt-in, opt-out and visibility

Nothing about a character enters Signal before opt-in. **Opt-out takes effect in its own operation**: the intent,
introductions and latent memberships are dropped, and the Intel Service's Signal presence for the character is deleted
(`intel.signalPresence(subject, none)`, intel-service.md §4.9), before the operation returns; no page is rendered from a
dropped intent, because pages re-check opt-in when they render. Match cycles are not needed for it, so an overloaded cycle
never delays an opt-out.

Visibility says who may be shown the character as a candidate: everyone, their alliance, their clan, or nobody. A
character with `NOBODY` may still look and express interest; **the recipient of that interest is then included in their
visibility for that introduction only**, so the recipient can answer and, at mutual interest, both see each other (this is
Signal's definition of "visibility includes the requester", which the Intel Service checks, intel-service.md §4.4). No
penalty attaches to `NOBODY`; a character who is a public Writ mark or holds a Torn Black Seal is offered "hide from
Signal" on their Ledger page (13 §4).

### 4.2 Candidates

B is a candidate for A only if all hold, checked at every transition: both opted in and `INTERACTIVE` (never an offline
trader, offline play, or an `NPC_FAKE`: a `PARTICIPATION` policy rule, actor-identity.md §3); B's visibility includes A;
neither blocked the other in Mobius (`entity/actor/holders/player/BlockList.java:151`, both directions) or in Signal; no
active cooldown for the pair (§4.5); B is a citizen only if A allows synthetic candidates; level ranges, objectives and time
windows intersect; the travel minutes from A's own current region to B's **declared** regions are within A's tolerance,
and from B's **declared** regions to A's declared regions within B's (B's actual whereabouts are never an input: moving B
changes nothing A can see or be offered); and B's party has room (9, `network/clientpackets/RequestJoinParty.java:217`) and allows joining.

**The synthetic preference** changes at most once per `SyntheticToggleDays` (a new intent version), and is a viewer's own
filter: packets about a character never depend on its origin (actor-identity.md AT-ACTOR-09, whose candidate-list
comparison is between viewers with the default preference). A viewer who disallows citizens sees lists without them by
construction; the rate limit keeps comparisons of the two settings days apart, with different intents.

### 4.3 Score

All terms in [0, 1], from the two intents and nothing else (no Director or Aden Remembers goal, no hidden ranking, 20 §1):

| Term | Definition |
|---|---|
| `objective` | 1 same objective; 0.5 a compatible pair (XP–SPOIL, RAID–SUPPORT_FOR_HIRE, CRAFTING–MATERIALS, CARAVAN_ESCORT–TW_MERCENARY); else 0 |
| `level` | max(0, 1 − |mid_A − mid_B| / 10), mid = the middle of the level range |
| `complement` | if A seeks roles: |roles A seeks ∩ roles B's class fills| / |roles A seeks|; else 0.5 |
| `travel` | 1 − min(1, travel minutes A→B's declared regions / A's tolerance) |
| `overlap` | min(overlap minutes, 60) / 60 |
| `languages` | 1 if they share one, else 0 |
| `pace` | 1 equal, 0.5 adjacent, 0 opposite |
| `collaborated` | 1 if both opted into Wayfarer's memory and played a session together before (14); shown as "you have played together before" after mutual interest, never hidden |

`score = Σ w_term × term`. A's candidates are ordered by score, ties by `rng("signal.tie", A, B, cycle)` (04 §7); a
party-to-be is built greedily: start from A, add the best remaining candidate whose score against A is at least
`MinScore`, until 9 or none. AT-SIG-03 checks this against a table of hand-computed cases.

### 4.4 What is disclosed when

| Stage | A learns about B |
|---|---|
| shown | objective; roles B's class fills (not the class); level band of 10 levels; time overlap in 30-minute steps; travel minutes to B's **declared** regions in `TravelBucketMinutes` steps; languages |
| interested | that someone compatible is interested (B sees A as above) |
| mutual | name, notes, the "played together before" marker, and B's actual region from the Intel Service (`intel.signalRegion(A, B, introduction)`, `REGION`, `SignalLocationTtlMinutes`, only while the introduction is mutual and the Intel Service's Signal rules hold) |

Pace is used in the score but never shown before mutual interest. A viewer sees at most `ShowCandidates` and may refresh
their view at most once per `MatchSeconds`.

### 4.5 Interest limits and anonymous blocking

The interest ledger (memory, per character pair and per recipient) enforces: after an introduction between A and B is
dropped or refused, A cannot express interest in B again for `PairCooldownMinutes` (keyed by the characters, so a new
intent does not reset it); a recipient receives at most `IncomingPerHour` interest notices, the rest silently not sent;
and an interest notice carries a **"block this sender"** action that writes `lc_signal_block` with `anonymous = true`: the
recipient never learns who it was, and the pair is excluded both ways from then on.

### 4.6 Latent party prompts

Only characters who enabled `latent` are counted, and only in the viewer's own region (actual, as each one's own Signal
presence). When at least `LatentMinParty` latent-enabled, compatible characters are in one region, Signal freezes a
**latent group** (an id, its member set, `LatentGroupMinutes` of life) and sends each member the Codex dialog: "A viable
party may be formed in your region. *k* compatible players. Common intent: *objectives*." where *k* is a band (3-5, 6+).
A member's "yes" is bound to the group id. Once at least two members said yes and are still eligible (§4.2, re-checked),
those who said yes become mutual among themselves (one introduction per pair, §3.2); a member's later block or opt-out
removes them from the group. At most one prompt per member per `LatentPromptMinutes`. What a prompt discloses is that a
band of consenting characters is in the region the recipient is in: what walking around would show.

### 4.7 Future sessions

A schedule persists until its end. At login and `SchedulePromptMinutes` before its start, its owner is told how many other
schedules align with it as a band (none, 1-4, 5+): compatible under §4.2 with declared regions and times, overlap ≥
`ScheduleMinOverlap`. At the start, aligned schedules become intents for those online, and they meet through §3.2
(mutual interest is still required).

### 4.8 Demand

Once a day, the day's intents are counted by (territory of their declared regions, objective) in **distinct principals**
(actor-identity.md §2.2, so several characters of one account count once). A cell is kept only at 5 principals or more,
as a band (5-9, 10-19, 20+), without role, cohort or time of day. Aden Remembers reads these daily cells (never finer), and
the Gazette publishes them as `signal_demand` (intel-service.md §4.7) after `PublicationTacticalDelayMinutes`: "many are
seeking spoilers around Oren". What a cell discloses is that at least five principals wanted something in a territory on a
day; controlled contributors can learn that at least one other did.

### 4.9 Sessions

A session is recorded when a `MUTUAL` introduction's two characters are in one party within `SessionWindowMinutes` and were
not in one party when it became mutual, observed through the `ON_PLAYER_PARTY_JOIN` and `ON_PLAYER_PARTY_LEAVE` seams (03
§4.4; Mobius dispatches no party events, `entity/groups/Party.java:301`, `:422`, `:551`). One session per introduction
(its id is unique). Members are rows with their origin; the cohort is `HUMAN`, `SYNTHETIC` or `MIXED` (a `METRICS` rule).
The session ends when fewer than two of its members remain in one party; `continued_after_first` is set `TRUE` when two still are
after `ContinueMinutes`, and `FALSE` when the session ends before then; a restart before either leaves it `NULL`
(unknown), and unknown sessions are excluded from conversion metrics.

### 4.10 Signal introduces, PACT commits

After mutual interest the page offers the matching PACT draft (20 §1): crafter and client → Crafting Pact; mercenary and
employer → Mercenary Contract; supplier and buyer → Purchase Order; pre-filled only with what both declared. The draft is
PACT's (pact.md §3.1); no Signal state depends on it.

### 4.11 Production checks

Every `MatchSeconds` a verifier re-checks a sample of `VerifySample` shown candidates against §4.2, inspects the rendered
pages of a sample of pre-mutual views for any field outside §4.4's shown row (a name, a note, pace, an actual region),
recounts the distinct principals of a sample of the day's demand cells from the day's intents, and checks every opted-out
character's absence from intents and Intel presence; a violation is an alarm and freezes Signal (`lc_module_state`,
asset-service.md §2.2): pages show Signal unavailable, nothing is shown or disclosed until an admin unfreezes it.

## 5. Facts

Telemetry (fact-journal.md §2.6): `Signal.IntentSet` (objective, territory of declared regions, cohort), `Signal.Shown`,
`Signal.Interest`, `Signal.Mutual`, `Signal.LatentPrompt` (answer). Outbox: `Signal.SessionFormed` (session, cohort,
objective, introduced by), `Signal.SessionEnded` (continued), `Signal.OptOut`, `Signal.Demand` (the day's kept cells),
`Signal.Frozen`. No fact names an actual region, or a candidate before mutual interest.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| SIG-INV01 | Signal grants nothing and calls no mechanics API | static scan; AT-SIG-09 |
| SIG-INV02 | no actual region before mutual interest except §4.6's same-region band; no name before mutual interest; never a coordinate | the disclosure table; the verifier (§4.11); AT-SIG-04 |
| SIG-INV03 | no shown candidate violates §4.2 | the verifier, sampling each cycle; AT-SIG-02 |
| SIG-INV04 | an opt-out leaves nothing when its operation returns | the operation; the verifier; AT-SIG-05 |
| SIG-INV05 | no demand cell below 5 principals | the writer's count, recounted by the verifier (§4.11); AT-SIG-07 (the schema only keeps the bands) |

## 7. Failure model

| Fault | End state |
|---|---|
| restart | intents, introductions, groups and the interest ledger are gone (memory-first); profiles, schedules, blocks and sessions remain; pending continuation checks become unknown |
| an operation fails after its durable part | memory not published; the durable part is the truth, and the next render reads it |
| Intel Service down | no region at mutual interest ("region unavailable"); matching continues on declared regions |
| the party seams unavailable | no sessions recorded; introductions still work |
| matching overload | the cycle is skipped and counted; opt-outs are unaffected (§4.1) |
| a verifier violation | Signal frozen until an admin unfreezes it |

## 8. Configuration

`config/Custom/LivingChronicle/Signal.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `MatchSeconds` | 5–120 | 20 | 20 / 20 / 5 / 20 / 20 | AT-SIG-05 |
| `ShowCandidates`, `MinScore` | 1–50, 0–20 | 10, 4 | as default everywhere | AT-SIG-03 |
| `InterestMinutes`, `MutualMinutes` | 1–60, 30–240 (≥ `SessionWindowMinutes`) | 10, 60 | as default everywhere | AT-SIG-04 |
| `TravelBucketMinutes` | 1–30 | 5 | 5 everywhere | AT-SIG-04 |
| `PairCooldownMinutes`, `IncomingPerHour` | 10–10080, 1–100 | 1440, 10 | as default everywhere | AT-SIG-12 |
| `SyntheticToggleDays` | 1–90 | 7 | 7 everywhere | AT-SIG-02 |
| `LatentMinParty`, `LatentGroupMinutes`, `LatentPromptMinutes` | 3–9, 1–30, 10–1440 | 3, 5, 60 | as default everywhere | AT-SIG-06 |
| `SchedulePromptMinutes`, `ScheduleMinOverlap` | 5–240, 10–240 | 30, 30 | as default everywhere | AT-SIG-06 |
| `SessionWindowMinutes`, `ContinueMinutes`, `SessionRetentionDays` | 5–30, 10–240, 1–90 | 30, 60, 30 | as default everywhere | AT-SIG-08 |
| `MaxNotesChars` | 0–200 | 120 | 120 everywhere | AT-SIG-04 |
| `VerifySample` | 1–1000 | 50 | 50 everywhere | AT-SIG-13 |
| weights `w_objective` … `w_collaborated` | 0–10 | 3, 2, 3, 2, 2, 2, 1, 1 | as default everywhere | AT-SIG-03 |

## 9. Interface

Codex → Signal tab, and the voiced command `.signal`, which opens it (Mobius dispatches `.`-prefixed chat to voiced
handlers, `dist/game/data/scripts/handlers/chat/channels/ChatGeneral.java:48-64`; a client `/signal` is never sent): set
intent, view aligned people, express interest, reveal on mutual, schedule, visibility, block (including an anonymous
sender). Notifications as system messages; the latent prompt as the Codex dialog. Admin: `lc why signal <char>`,
`lc signal unfreeze`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| SIG-R01 | Nothing about a character MUST enter Signal before opt-in, and opt-out MUST remove it within its own operation. | AT-SIG-05 |
| SIG-R02 | Candidates MUST satisfy §4.2 at every transition, and the synthetic preference MUST be a rate-limited viewer filter. | AT-SIG-02 |
| SIG-R03 | Scores MUST follow §4.3 exactly, with no other input. | AT-SIG-03 |
| SIG-R04 | Before mutual interest only §4.4's fields MUST be shown, travel only to declared regions; names, notes and actual regions MUST come only at mutual interest, regions only through the Intel Service. | AT-SIG-04 |
| SIG-R05 | Latent prompts MUST count only consenting characters in the viewer's region, in bands, bound to a frozen group; schedule alignment MUST disclose only bands. | AT-SIG-06 |
| SIG-R06 | Demand MUST be daily cells of at least five principals, banded, by territory and objective only, published after the tactical delay. | AT-SIG-07 |
| SIG-R07 | Sessions MUST be one per introduction, from party seams, excluding parties already shared, reported per cohort, with unknown continuations excluded. | AT-SIG-08 |
| SIG-R08 | Signal MUST grant nothing, reward nothing, and never fill a role with an NPC. | AT-SIG-09 |
| SIG-R09 | Signal MUST meet the acceptance band of AT-SIG-10 over retail party matching alone, per cohort. | AT-SIG-10 |
| SIG-R10 | After mutual interest Signal MUST offer only the matching PACT draft, pre-filled with what both declared. | AT-SIG-11 |
| SIG-R11 | Interest MUST be bound to intent versions, limited per pair and per recipient, and blockable anonymously. | AT-SIG-12 |
| SIG-R12 | A verifier MUST re-check shown candidates and opt-outs every cycle and freeze Signal on a violation. | AT-SIG-13 |

| Test | Kind | What it does |
|---|---|---|
| AT-SIG-00 | integration | the migration applies; keys and checks refuse latent without opt-in, a schedule ending before it starts or expiring elsewhere, a self block, a session member twice, a second session for an introduction, a demand band other than the three, a second cell per day, territory and objective |
| AT-SIG-01 | integration | profile, intent and schedule flows through the Codex; `.signal` opens the tab |
| AT-SIG-02 | integration | each exclusion of §4.2 at its boundary; a synthetic toggle refused within `SyntheticToggleDays`; with the default preference, candidate lists equal for paired human and citizen runs |
| AT-SIG-03 | unit | the table of hand-computed scores (each term at 0, between and 1) and greedy parties; keyed ties; every `PlayerClass` in the role table; the static scan finds no other score input |
| AT-SIG-04 | integration | before mutual interest no name, note, pace or actual region in any page or packet; moving the candidate without changing their declared regions changes nothing shown; at mutual interest the region from the Intel Service, refused after `MutualMinutes` or when its rules fail |
| AT-SIG-05 | integration, fault | opt-out under an overloaded cycle: nothing left when the operation returns; a fault after the durable part leaves memory unpublished |
| AT-SIG-06 | integration | latent groups only of latent-enabled characters in the region, banded counts, yes bound to the group, a member who blocks removed before reveal; schedule bands at login and before start |
| AT-SIG-07 | integration | four controlled principals and none other: no cell; alts of one account counted once; cells banded, daily, after the delay |
| AT-SIG-08 | integration, restart | sessions once per introduction; a party already shared at mutual interest is no session; continued at `ContinueMinutes`; a restart before it gives unknown, excluded from metrics |
| AT-SIG-09 | unit | the static scan: no mechanics API, grant, reward or NPC spawn in Signal code |
| AT-SIG-10 | sim, human | characters assigned by `rng("signal.ab", char)` to treatment (Signal and retail matching) or control (retail matching only), at least 2 000 **looking periods** per cohort and arm. A looking period is defined the same way in both arms: it starts when the character registers in retail party matching (`PartyMatchWaitingList` or a party room) or sets a Signal intent, and ends when that registration or intent ends. Both arms are measured with the same instrumentation: the party seams and a **party invitation seam** at both points Mobius sends an invitation (`network/clientpackets/RequestJoinParty.java:234`, `:259`), so declined invitations count. **Time to first contact** is from the start to the first party invitation the character sends or receives during the period (censored at its end); **conversion** is the share of periods in which the character is in a party of at least two within 60 minutes; a period is **missing a healer or a buffer** when the character's party (or the character alone) contains no class filling that role by the class-role table, judged identically in both arms, and for those periods the time until a character filling it joins; acceptance: treatment's median time to first contact at least 20 % lower and conversion at least 10 points higher, each with a 95 % bootstrap interval excluding no effect, in the human and the mixed cohorts; and the time to a party containing a healer or a buffer lower under treatment, same bound |
| AT-SIG-11 | integration | the offered draft per pairing, pre-filled only with declared fields; Signal unchanged by its fate |
| AT-SIG-12 | integration | an edit, a visibility, clan or synthetic change between interest and answer drops the introduction; re-interest within the pair cooldown refused across new intents; the per-recipient cap; an anonymous block excludes the pair both ways without naming the sender |
| AT-SIG-13 | integration | a planted ineligible candidate and a planted stale presence: the verifier alarms and freezes Signal; nothing shown until unfreeze |

## 11. Upstream constraints

1. **Mobius dispatches no party join, leave or invitation event** (`Party.java:301`, `:422`, `:551`;
   `RequestJoinParty.java:234`, `:259`). → the party seams (03 §4.4) and an invitation seam, for the A/B measurement.
2. **Retail party matching exists** (`PartyMatchRoomList`, `PartyMatchWaitingList`). → unchanged; the control arm.
3. **A party holds 9** (`RequestJoinParty.java:217`). → the capacity constraint.
4. **Blocks are per character, either way** (`BlockList.java:151`). → checked both ways.
5. **Slash commands are client-side; `.`-commands reach the server** (`ChatGeneral.java:48-64`). → `.signal`.
6. **Classes carry no roles** (`PlayerClass.java` has ids, races, mage flags and parents). → `signal-class-roles.csv`.

## 12. Review record

- Round 4 (Codex), NO SIGN-OFF: one open, Actor Identity's AT-ACTOR-09 still requiring every candidate list equal.
  Revision 5: actor-identity.md (its revision 6) states the viewer's own synthetic preference as the one exception.
- Round 3 (Codex), NO SIGN-OFF: open were invitations invisible to the party seams and no equivalent healer/buffer
  measurement in the control arm, regions without cells in the travel graph, and Signal regions entering the combined
  bound in one order only. Revision 4: an invitation seam and "missing a healer or a buffer" judged from party composition
  in both arms; cell-less regions excluded and undeclarable; Signal regions as consented disclosures outside the combined
  bound, with INTEL-R12 narrowed to non-consented answers (intel-service.md).
- Round 2 (Codex), NO SIGN-OFF: open were eligibility still reading the candidate's actual region, transitions on two
  queues without a shared check, no revocation of Intel's mutual registration, early-ended sessions left unknown, an A/B
  control without equivalent events, travel computation underspecified, a schema test claiming the writer's threshold, a
  verifier blind to rendered payloads, an interest timer cutting mutual introductions short, and Intel's old Signal-grant
  wording. Revision 3: declared regions both ways; a lock per introduction with version checks under it;
  `signalUnmutual` and Signal regions reconciled with the combined bound and the grid (intel-service.md); `FALSE` on early
  end; looking periods measured alike in both arms from retail matching registration or an intent; region centres, walking
  and unrestricted gatekeeper edges, minimum over declared regions; AT-SIG-00 and INV05 restated; the verifier inspecting
  rendered views and recounting demand; separate timers for pending and mutual introductions.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 16 findings: travel buckets and tolerance filters as a location
  oracle; anonymous fields and exact counts identifying people; the synthetic filter distinguishing citizens; demand cells
  open to controlled contributors; consent not bound to intent versions or latent groups; recyclable interest for
  harassment; `NOBODY` unable to complete mutual interest; no Intel lifecycle for presence and region; memory-first
  atomicity; sessions without identity or recovery; no falsifiable metric; scores without defined functions or tables;
  weak schema checks; no production checks. Revision 2: travel only to declared regions and latent prompts limited to
  consenting characters in the viewer's own region; identity protection stated as an advertisement's, with coarser
  fields; the synthetic preference rate-limited as a viewer filter; demand as daily banded cells of five principals; intent
  versions, frozen latent groups and per-pair cooldowns with anonymous blocking; `NOBODY` defined to include its own
  recipients; Intel presence and region operations (intel-service.md revision 7); durable-before-memory publication and
  opt-out in its own operation; sessions keyed by introduction, members as rows, unknown continuations excluded; an A/B
  acceptance band; defined score terms, a class-role table and a travel computation; schema checks for members and keys;
  a verifier that freezes Signal.
