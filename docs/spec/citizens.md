# SPEC-CIT — Citizens of Aden

- Status: draft (revision 2, answering review round 1; record in §12)
- Blueprint: 10 (Citizens of Aden); 01 §2.5 (alts and Discord); 02 §3 (game logic never branches on origin), §7 (the
  runner is a separate process); 03 §7 (geodata); 04 §2 (Actor Identity), §3.4 (accounting per actor class and
  principal); 12 (Aden Remembers' telemetry); 16 §8 (information design); 20 §1.
- Depends on: actor-identity.md (provisioning, origin, principals, identity versions, policy), profiles.md (the runner's
  dualbox allowance, WalkerBotProtection), aden-signal.md (synthetic candidates), black-ledger.md (houses fund bounties),
  wayfarers-memory.md (citizens keep their own store), fact-journal.md (telemetry, gaps), aden-remembers.md (`ar_*`
  families), asset-service.md (conservation), platform.md (processes, database users).
- DDL: `sql/runner/V001__cit_runner.sql`, applied to the runner's own database `lc_runner` (not a game migration).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/` unless they start with `dist/`.

## 1. Scope

The citizens are a resident population of real `Player` characters on real accounts, played by software through the real
network protocol, from a separate process (02 §7): the **citizen runner**. This spec owns the runner: its protocol
library, its perception filter and observation store, its navigation, its personas and decision policies, its principals
and their out-of-band channel, its relationship ledger, the dialogue boundary, its login admission and pacing, its economic
discipline and the governor that enforces it, the Genesis epoch and the acceptance of all of it.

It does not own, and never touches: the game server's code or tables (the runner has no access to them), how the server
tells citizens apart (actor-identity.md: provisioning, origin, policy), or any mechanic. A citizen is subject to every
rule a human is: the same packets, the same flood protection, the same bot reports, the same Writs.

**Epistemic honesty** (10 §5.1). A decision's **inputs** are only the deciding citizen's own observations (§4.2): what its
client was sent and passed its perception filter, what its principal's out-of-band channel delivered to it (each message
read becomes an `OOB` observation of that citizen), and its own inferences from those; static navigation geometry from
validated geodata is the one other input. The perception filter is applied before anything reaches a decision or the
channel. One supervisory influence exists and is not an input: the **governor** (§4.10), which applies the
humans-as-protagonists bounds (10 §9, §10) from server telemetry. It reaches a policy only as a **constraint** that removes
options (a cap below which a choice is feasible), never as a value a utility term reads; §4.5 states how that is enforced
and §10 (AT-CIT-03) how it is tested.

## 2. Data (the runner's schema)

```text
cit_principal(principal_id PK, name, budget_policy JSON, information_policy JSON, goals JSON)        -- mirrors lc_principal
cit_account(account_id PK, login UNIQUE, secret_ref, principal_id, identity_ver, created_at, retired, suspended_at NULL)
cit_character(char_id PK, account_id, name, class_id, persona, state {ACTIVE|RESTING|QUIT}, online,
              online_account AS (online ? account_id : NULL) UNIQUE)
cit_persona_state(char_id PK, goal, plan JSON, budget JSON, updated_at)
cit_schedule(char_id, weekday, start_minute, end_minute, PRIMARY KEY(char_id, weekday, start_minute))
cit_observation(obs_id PK, char_id, at, kind, subject_kind, subject_ref, payload JSON,
                provenance {SEEN|HEARD|TOLD|OOB|INFERRED}, source_ref NULL, tier {RAW|SUMMARY}, expires_at NULL)
                                                                 -- partitioned by obs_id ranges, one per month
cit_observation_source(obs_id, source_obs_id, PRIMARY KEY(obs_id, source_obs_id))
cit_relation(citizen_char_id, other_char_id, first_seen, last_seen, trades, trade_value, contracts_ok, contracts_failed,
             parties, killed_by, killed, rescued_by, debts_owed, notes_tags JSON, sentiment AS (§4.9) STORED,
             PRIMARY KEY(citizen_char_id, other_char_id))
cit_oob_message(msg_id PK, principal_id, from_char, channel, at, template_id, args JSON, text VARCHAR(500),
                observation_refs JSON)
cit_governor_cap(cap_id PK, principal_id, kind {EXTRACTION|RETUNE}, region_id NULL, family NULL, value, window_end,
                 valid_from, imported_by)
cit_decision_log(decision_id PK, char_id, at, policy, policy_ver, config_ver, inputs JSON, constraints JSON,
                 persona_state JSON, input_snapshot JSON, snapshot_sha256, choice JSON, rng_key)
cit_login_guard(guard_id PK = 1, ban_threshold, halt_at, failures, in_flight, halted_at NULL)
cit_participation_limit(kind PK {OLYMPIAD|EVENT}, max_slots)
cit_participation(principal_id, kind, slot, char_id, state {RESERVED|REGISTERED}, since,
                  PRIMARY KEY(principal_id, kind, slot), UNIQUE(char_id, kind))
```

What the schema itself enforces (triggers and checks, every one exercised by AT-CIT-00):

- **Provenance.** `SEEN` and `HEARD` carry no source; `TOLD` carries the teller's character id; `OOB` carries the message
  id, and the message must belong to the observing citizen's principal and precede the observation; `INFERRED` carries its
  primary evidence, an earlier observation of the same citizen, and further evidence goes to `cit_observation_source` under
  the same rule. Only `SEEN` and `HEARD` rows are summarised; a summary's raw rows are listed in `cit_observation_source`,
  which keeps the ids after the raw rows expire. Observations and sources are never updated.
- **The channel.** A message is written only by a member of its principal; its `observation_refs` is a non-empty list of
  distinct observations of the writer's own, none of them `OOB` (no echo), none later than the message. Messages are never
  updated, so a valid message can never be reassigned to another principal or writer.
- **Decisions.** `inputs` is a list of distinct observations of the deciding citizen at or before the decision;
  `constraints` is a list of governor caps of that citizen's principal valid at the decision. Decisions are never updated.
- **Caps.** A cap is valid no earlier than a day after the end of the telemetry window it was computed from, and never
  changes.
- **Sentiment** is a stored generated column: no writer can store another value.
- **Online.** At most one character of an account is online (`online_account` is unique); a `QUIT` character is never
  online and never leaves `QUIT`.
- **The login guard.** `failures + in_flight < ban_threshold` always; `failures` decreases only to 0, and only in the
  update that resolves the one credential in flight, or in an admin's clearing of a halt with nothing in flight (§4.7).
- **Participation.** A slot number never exceeds its kind's `max_slots`; a slot holds a character of its principal; a
  character holds one slot per kind; a slot only moves `RESERVED` → `REGISTERED` and is released by deletion.
- **The identity mirror.** An account's id and login never change, retirement is one-way, a change of principal or
  retirement carries a strictly newer `identity_ver` and the version never decreases, and the principal changes only while
  none of the account's characters is online or holds a participation slot.

What the schema cannot enforce, and who does: that a message's `text` says only what its `args` and references support
(the channel writer renders text only from a template over the referenced observations' payload fields, §4.6); that
`input_snapshot` equals the rows read (the policy view writes both, §4.5; AT-CIT-03 compares them while the rows are
retained).

**Database users** (platform.md §4.3). The runner's tables live in their own database, `lc_runner`; the runner's user has
exactly these grants and nothing on the game's database:

| Grant | Tables |
|---|---|
| `SELECT` | every `lc_runner` table |
| `INSERT, DELETE` (append-only; delete is retention) | `cit_observation`, `cit_observation_source`, `cit_oob_message`, `cit_decision_log` |
| `INSERT` | `cit_governor_cap` |
| `INSERT, UPDATE` | `cit_principal`, `cit_account`, `cit_character`, `cit_login_guard`, `cit_participation_limit` |
| `INSERT, UPDATE, DELETE` | `cit_persona_state`, `cit_schedule`, `cit_relation`, `cit_participation` |

The game's user has no grant on `lc_runner`. Secrets (`secret_ref`) live in the runner's secret store, never in a table.

- `cit_account` mirrors the accounts the game's admin provisioned as `SYNTHETIC_CITIZEN` (actor-identity.md §2.2,
  `lc citizen provision`), with their `identity_ver`. The runner never creates an account (the login server's
  `AutoCreateAccounts = True`, `dist/login/config/Server.ini:77`, `loginserver/controller/LoginController.java:243-267`,
  would let any login create one); it logs in only as a provisioned login, and actor-identity.md's patch refuses an
  unprovisioned one. The mirror changes only through the **identity import** (§4.6).
- Retention: raw `SEEN`/`HEARD` observations for `RawRetentionDays`, then summarised; `TOLD`, `OOB` and `INFERRED` rows
  until `expires_at` (at most `RawRetentionDays`), then deleted; channel messages and decisions for `DecisionRetentionDays`,
  then deleted. A decision replays from its own row (§4.5), so retention of observations never breaks a retained
  decision's replay.

## 3. State machines

A transition not in a table is impossible.

### 3.1 Citizen session

`n` is the citizen's consecutive-failure count of non-credential steps (reset on `IN_WORLD`). "Guard" is
`cit_login_guard` (§4.7); `B` is its `ban_threshold`, `H` its `halt_at`, `F` its `failures`, `A` its `in_flight`.

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| `OFFLINE` | schedule window opens | account not retired, not suspended, not held (§4.6); character `ACTIVE`; no other character of the account online | `LOGIN_QUEUED` | — |
| `LOGIN_QUEUED` | admission token (§4.7) | runner's login rate and `MaxOutstandingLogins` respected | `CONNECTING` | TCP connect to the login server; step deadline `LoginStepTimeoutMillis` |
| `CONNECTING` | `Init` read, `AuthGameGuard` answered | Guard not halted, `F < H` and `F + A + 1 < B` | `AUTHENTICATING` | `A := A + 1` committed **before** `RequestAuthLogin` is sent |
| `CONNECTING` | `Init` read, `AuthGameGuard` answered | otherwise | `LOGIN_QUEUED` | connection closed; no credential sent |
| `AUTHENTICATING` | `LoginFail` `REASON_USER_OR_PASS_WRONG`, or no answer before the deadline, or the connection lost | — | `SUSPENDED` | `A := A − 1`, `F := F + 1` in one update; `suspended_at` set; if `F ≥ H`: Guard `halted_at` set, alarm |
| `AUTHENTICATING` | any other answer: `LoginOk`; `AccountKicked` (the account banned); `REASON_ACCOUNT_IN_USE` or any other | — | `SERVER_SELECT`; `SUSPENDED`, alarmed; `BACKOFF` | `A := A − 1`; if this credential was **solo** (§4.7), `F := 0` in the same update; no failure counted (the password was correct) |
| `SERVER_SELECT` | `PlayOk` | — | `GAME_CONNECT` | login connection closed |
| `GAME_CONNECT` | `KeyPacket`, then `CharSelectionInfo` after `AuthLogin` | — | `CHAR_SELECT` | — |
| `CHAR_SELECT` | `CharSelected`, then the first `UserInfo` after `EnterWorld` | — | `IN_WORLD` | `online := TRUE` (refused if another character of the account is online: then `LOGOUT`); `n := 0` |
| `IN_WORLD` | persona restart | — | `CHAR_SELECT` | `online := FALSE` |
| `IN_WORLD` | schedule window closes, persona quits, identity hold (§4.6), runner shutdown | — | `LOGOUT` | `Logout` sent |
| `LOGOUT` | `LeaveWorld` or connection closed | — | `OFFLINE` | `online := FALSE` |
| any of `CONNECTING`, `SERVER_SELECT`, `GAME_CONNECT`, `CHAR_SELECT`, `IN_WORLD` | a refused step, a step deadline missed, a desync (§4.1), the server closing the connection | — | `BACKOFF` | connection closed; `online := FALSE`; `n := n + 1`; counted |
| `BACKOFF` | delay `min(BackoffMaxMillis, BackoffBaseMillis · 2^n) · U(0.5, 1.5)` elapsed | — | `LOGIN_QUEUED` | — |
| `SUSPENDED` | admin `lc citizen unsuspend` after fixing the credential | — | `OFFLINE` | `suspended_at := NULL` |

At runner start every character is `OFFLINE` and `online := FALSE`. A credential whose answer the crash lost was already
counted in `A`; at start `F := F + A`, `A := 0` (counted as failures, since the server may have counted them) in one update,
and if then `F ≥ H` the Guard halts.

### 3.2 Character life

| From | Event | To |
|---|---|---|
| `ACTIVE` | outside every schedule window | `RESTING` |
| `RESTING` | inside a schedule window | `ACTIVE` |
| `ACTIVE`, `RESTING` | the persona's quit decision (§4.5) | `QUIT` (final: never logs in again; its assets stay in the world as a human's would) |

### 3.3 Participation slot

| From | Event | Guard | To |
|---|---|---|---|
| — | the policy chooses to register (Olympiad or event) | a free slot `≤ max_slots` of the principal and kind; the character holds none | `RESERVED` (row committed before the registration request is sent) |
| `RESERVED` | the citizen observes its registration confirmed | — | `REGISTERED` |
| `RESERVED`, `REGISTERED` | the citizen observes itself not registered (refusal, unregistration, match or event end, or, at its next `IN_WORLD`, no registration shown) | — | released (row deleted) |

A crash releases nothing: rows are durable, so a restarted runner can only under-admit, never over-admit.

## 4. Operations

### 4.1 The protocol library

Versioned and explicit: connection states, conditional fields, packet → domain mappings.

- **Login** (`loginserver/network/LoginClientPackets.java:212-215`): `Init` read (`Init.java:60-77`: session id,
  revision, scrambled RSA modulus, the session Blowfish key); `AuthGameGuard` echoing the session id
  (`AuthGameGuard.java:61-95`); `RequestAuthLogin` with the credentials RSA-encrypted in the 128-byte block
  (`RequestAuthLogin.java:55-104`); `RequestServerList` and `RequestServerLogin` with the session keys
  (`RequestServerLogin.java:50-55`), the licence step when `ShowLicence` is on. Login framing: the first server packet
  XOR-passed with the static key, the rest Blowfish with checksums (`loginserver/network/LoginEncryption.java:37-55`,
  `:98-99`, `:151-160`; `commons/crypt/NewCrypt.java:90-235`).
- **Game** (`gameserver/network/ClientPackets.java`): `ProtocolVersion` with an allowed revision
  (`dist/game/config/Server.ini:72`), `KeyPacket` read (`KeyPacket.java:40-53`), `AuthLogin` with **play key 2 before play
  key 1** (`AuthLogin.java:42-46`), character select or create, `EnterWorld`; the game cipher when `PacketEncryption` is on
  (`network/Encryption.java:94-171`; it ships off, `Server.ini:39`, and the library supports both).
- **Built from the server's own code**: each packet the runner sends is the inverse of its `readImpl`, each it reads the
  inverse of its `writeImpl`. The **sendable set** is exactly what `gameserver/network/GamePacketHandler.java` dispatches:
  every `ClientPackets` entry with a supplier; `0xD0` is not a packet but the extended envelope
  (`ClientPackets.java:206`, dispatched at `GamePacketHandler.java:60-119`), through which every `ExClientPackets` entry
  with a supplier is sent; extended `0x51` is not a packet but the bookmark envelope, whose sub-ids 0–5 are sendable
  (`GamePacketHandler.java:83-112`). Never sent: the 12 other top-level entries with a null supplier
  (`ClientPackets.java:76-79`, `:81`, `:97`, `:164`, `:177`, `:184`, `:188`, `:193`, `:205`), the 30 other extended ones
  with a null supplier and the `EX_MAX` sentinel (`ExClientPackets.java:66-181`), any other bookmark sub-id, and
  `RequestActionUse` with action 65, the bot report (`RequestActionUse.java:433-437`, §4.7). The encoder refuses anything
  outside the sendable set, so the refusal is a library property, not a persona's good conduct. Checked differentially
  against independent clients and captured traffic from the pinned client.
- **Known traps**: the server ignores silently an unknown or wrong-state packet (`GamePacketHandler.java:65-81`), so the
  library asserts the state before sending and treats an expected answer missing past its deadline as a desync; an empty
  `Say2` text or unknown chat type **kicks** (`Say2.java:114-128`), over 105 characters without an item link is refused
  (`:134`), and a whisper starting with an L2Walker command is punished (`:54-91`, `:140-144`), so dialogue is checked
  before it is sent (§4.8).

### 4.2 Perception

A citizen's **perception filter** turns packets into observations under an attention budget, per persona: at most
`TrackedObjects` objects tracked (the nearest and most relevant), a reaction latency drawn per reaction
(`ReactionMillis`, a persona-specific distribution), and a noticing probability falling with distance and clutter, drawn
from the citizen's keyed stream. Only what passes becomes an observation (`SEEN` for world packets, `HEARD` for chat,
`TOLD` for what another character said to it, `OOB` for a channel message it read), with time and provenance.

A dropped packet changes nothing but a drop counter: the filter's state (tracked set, latencies, the random stream's
position) after a dropped packet equals its state before. This is what makes AT-CIT-03's paired replay an oracle.
No decision and no channel message reads a packet: policies see only the policy view (§4.5), built from the store.

Some things the server never says, and a citizen must infer them as a human would: a `Die` packet names no killer
(`gameserver/network/serverpackets/Die.java:69`), and the "killed by" system messages are never sent
(`SystemMessageId.java:7083`, `:7086`; a death is broadcast as `Die` only, `ai/AbstractAI.java:444`); so "killed by X" is
an inference from the attacks the citizen itself perceived on itself before it died, recorded as `INFERRED` with its
evidence (the perceived attacks), and may be wrong.

### 4.3 Observation store

Per citizen, `cit_observation`, with tiers like Wayfarer's (raw for `RawRetentionDays`, then summarised, §2) and the same
rule: memory is the citizen's own. A clan's or house's shared memory is what its members wrote to the out-of-band channel
and each member read (as its own `OOB` observations), never their stores or ledgers merged.

### 4.4 Navigation

Static geometry comes from **validated geodata** (03 §7): the fork ships none (`dist/game/data/geodata/` holds only a
readme; `geoengine/GeoEngine.java:68`, `:102`), so the deployment installs the pinned geodata set for both the server and
the runner, its hash recorded by both, and a mismatch stops the runner. Doors, fences and other dynamic obstacles are
learned from observation and failure (a blocked move, a closed door seen), never read from server state.

### 4.5 Personas and decisions

Personas (10 §6) each declare goals, preconditions, utility terms, budget rules, risk appetite, a schedule template, the
channel templates they write (§4.6), and their **workflows**: named sequences of client packets with the server answer
each step waits for. The workflows are listed in the **workflow manifest** `runner/personas/workflows.csv` (`persona,
workflow, step, packet, sub-id, connection state, expected answer, flood category`), which AT-CIT-01 runs row by row. The
minimum per persona:

| Persona | Workflows (client packets) |
|---|---|
| every persona | session (§3.1); move (`MoveToLocation`, `ValidatePosition`); target and attack (`Action`, `AttackRequest`, `RequestMagicSkillUse`); use item (`UseItem`); die and restart (`RequestRestartPoint`); NPC talk (`RequestBypassToServer`, `RequestLinkHtml`); buy and sell at an NPC (`RequestBuyItem`, `RequestSellItem`); warehouse (`SendWareHouseDepositList`, `SendWareHouseWithDrawList`); party (`RequestJoinParty`, `RequestAnswerJoinParty`, `RequestWithDrawalParty`); whisper and party chat (`Say2`); learn skills (`RequestAcquireSkillInfo`, `RequestAcquireSkill`) |
| farmer, spoiler, raider, newbie, returning player | hunt loop (the above), pick up (`Action` on an item), spoil and sweep (`RequestMagicSkillUse`) for the spoiler, quests (`RequestBypassToServer`, `RequestQuestList`, `RequestQuestAbort`) |
| crafter (per grade) | recipe book and craft (`RequestRecipeBookOpen`, `RequestRecipeItemMakeSelf`), manufacture shop (`RequestRecipeShopListSet`, `RequestRecipeShopMakeItem` as buyer) |
| merchant, broker client | private stores (`RequestPrivateStoreManageSell`, `SetPrivateStoreListSell`, `SetPrivateStoreMsgSell`, `RequestPrivateStoreBuy`, the buy-store counterparts `SetPrivateStoreListBuy`, `RequestPrivateStoreSell`), trade (`TradeRequest`, `AnswerTradeRequest`, `AddTradeItem`, `TradeDone`), mail (`RequestSendPost`, `RequestPostAttachment`), shout and trade chat (`Say2`) |
| buffer for hire | SellBuffs store (`RequestBypassToServer` on the SellBuffs pages), buffs (`RequestMagicSkillUse`) |
| party leader | party matching (`RequestPartyMatchConfig`, `RequestPartyMatchList`, `RequestPartyMatchDetail`), invite and oust |
| manor farmer | manor (`RequestManorList`, `RequestProcureCropList`, `RequestBuySeed`, `RequestProcureCrop` through the envelope) |
| fisherman | fishing skills (`RequestMagicSkillUse`), bait (`UseItem`), sell catch |
| clan leader, diplomat | clan (`RequestJoinPledge`, `RequestAnswerJoinPledge`, `RequestOustPledgeMember`, `RequestPledgeMemberList`), alliance (`RequestJoinAlly`, `RequestAnswerJoinAlly`, `AllyLeave`), clan war (`RequestStartPledgeWar`, `RequestReplyStartPledgeWar`, `RequestStopPledgeWar`) |
| siege commander, fortress holder, Territory War mercenary | registration (`RequestBypassToServer` at the siege and Territory Manager NPCs), siege info (`RequestAllCastleInfo`, `RequestAllFortressInfo`), combat as above |
| outlaw, caravan raider, caravan escort | PvP (`AttackRequest` with force), flag and karma recovery (quests, death), escort (party, follow by `MoveToLocation`) |
| Writ hunter, Black Contract hunter, informant | the Ledger's NPC flows (`RequestBypassToServer` at the Magistrate, Broker and Notarius, black-ledger.md, pact.md) |
| Olympiad competitor | registration and match (`RequestBypassToServer` at the Olympiad Manager), match list (`RequestOlympiadMatchList`) |
| quitter | session end, never again (§3.2) |

**Decisions** are deterministic utility policies. A policy is a pure function `decide(view, persona state, constraints,
rng) → choice` with these rules:

1. **The policy view is the only way to read.** It is built from the citizen's own observation store; it records every
   observation the policy reads, and that recorded read set, not anything the policy reports, becomes
   `cit_decision_log.inputs`; the view also writes the rows read, canonical JSON, to `input_snapshot` with its SHA-256.
2. **No path to packets or telemetry.** Policy classes are compiled in a module whose compile-time dependencies exclude
   the protocol library, the perception filter's internals and the governor; a static test (AT-CIT-03) scans the
   policy module's class files and fails on any reference to those packages.
3. **Constraints only mask.** The governor's caps reach a policy as an opaque feasibility predicate over choices (`allows
   (choice)`); the policy cannot read a cap's value, and the cap ids that masked at least one option are logged in
   `constraints`. A cap may only remove choices; it never adds one or reorders the rest.
4. **Replay.** A decision logs `policy_ver`, `config_ver` (the version of `runner.ini` and the persona table),
   `persona_state` as read, the snapshot and `rng_key`; replaying `decide` on those alone reproduces `choice`.

Efficiency is bounded by decision latency, schedules, imperfect knowledge and spending preferences, never by any
mechanic.

### 4.6 Principals, the out-of-band channel and the identity import

A **principal** (a house) is the will behind a group of citizens (10 §4): its own budget, its information policy, its
goals; principals compete, transfer funds only through ordinary trades, contracts and bounties, and are subject to the
same repeat-pair and reciprocal controls as humans (black-ledger.md, pact.md). A house funds Writs and bounties from its
own budget (black-ledger.md §4.3). The `SCENARIO_ADVERSARY` profile (actor-identity.md §2.2) is the only one that colludes
across principals, and only in tests.

A house is not a mind: its decisions are taken by one of its citizens, the **house steward** (the clan leader persona of
its main clan, or a diplomat when it has none), from that citizen's own observations, which include the channel messages
it read.

**The out-of-band channel** (`cit_oob_message`) emulates a Discord, so information design is exercised under the
alts-and-Discord assumption (01 §2.5, 16 §8):

- **Writing**: only a member of the principal; only from a persona's channel template (`template_id`), whose arguments
  (`args`) are fields of the referenced observations' payloads, the text rendered from the template and arguments alone;
  at least one reference, all the writer's own, none read from the channel (§2).
- **Bounds**: at most `OobMessagesPerHour` messages per member; text at most 500 characters; delivery to each other
  member online at a delay drawn per message and recipient from `OobDelaySeconds` (a human relaying news is not instant);
  a member logging in reads at most the last `OobHistoryMessages` of its principal, then new ones as delivered.
- **Reading**: each message delivered to a member passes that member's perception filter like chat (it may be missed when
  its attention budget is spent) and, if it passes, becomes that member's `OOB` observation; only then can a decision of
  that member use it.

**House grudges and alliances** (10 §7) are formed only through the channel: when a member's relation row crosses a
persona threshold (for example `killed_by ≥ 2` for the same character), it writes a `GRUDGE` message from its own
`INFERRED` and `SEEN` observations; the steward's grudge and alliance policies read the `GRUDGE` and `ALLY` messages it
received as `OOB` observations, never another member's store or ledger. A human who repeatedly kills a house's members
becomes the steward's bounty target only through those messages, then through the ordinary Black Ledger.

**The identity import.** The runner never reads `lc_account`. An admin exports the citizen identities from the game
(`lc citizen export`, actor-identity.md §9: account id, login, principal, retired, `identity_ver` per account) and imports
the file with the runner's admin command `runner identity import`. Per account: a row with a version not newer than the
mirror's is ignored; a new account is inserted; a newer version is applied, except that a principal change is applied only
while the account has no character online and no participation slot. Otherwise the account is **held**: its characters
are logged out (`IN_WORLD` → `LOGOUT`), not admitted again, their slots released as §3.3 observes, and the import retried
by the admin. An account in the mirror whose version is newer than the export's, or absent from the export, is an alarm and
is held. The database refuses a principal change that breaks these rules (§2).

### 4.7 Login admission and pacing

**Admission.** One runner-wide queue admits at most `RunnerLoginsPerSecond` new connections per second, spaced by more
than `NormalConnectionTime` (700 ms, `dist/login/config/Server.ini:59`), with at most `MaxOutstandingLogins` sessions
between `CONNECTING` and `CHAR_SELECT` and every step bounded by `LoginStepTimeoutMillis`. The login server's per-address
flood settings protect only its game-server link (`loginserver/util/FloodProtectorListener.java:85-110`, used by
`loginserver/GameServerListener.java:45`); client logins go through `commons/network/ConnectionManager.java:73-84`
(`loginserver/LoginServer.java:241`), a fixed thread pool shared by every client with no reservation for humans. The
runner therefore cannot reserve capacity for humans; it bounds its own load (a known, small absolute rate) and the
acceptance test measures what humans experience under it (AT-CIT-02's human probe), against a baseline without the
runner.

**The shared login ban.** Upstream counts failed logins per address with no time window: every
`REASON_USER_OR_PASS_WRONG` (`loginserver/network/clientpackets/RequestAuthLogin.java:115-121`) is a call to
`recordFailedLoginAttemp` (`loginserver/controller/LoginController.java:174-200`, from `:233` and `:246`), the fifth
(`LoginTryBeforeBan`, `dist/login/config/Server.ini:41`) bans the address for 900 s (`:194-196`) and resets the count; any
correct password resets it (`:237`), whatever answer follows (`RequestAuthLogin.java:123-170`). The count lives in memory
(`LoginController.java:80`): a login server restart clears it, a runner restart does not. Every citizen shares the runner's
address. So the runner keeps a conservative mirror of that count, `cit_login_guard`:

- `F` counts the runner's credential failures since the last **solo success**; a credential is **solo** when, from the
  commit of its `A := A + 1` until its answer, no other credential of the runner was in flight. Only a solo credential
  answered with anything but `REASON_USER_OR_PASS_WRONG` proves the server's count is 0 (the server processed it alone,
  and its correct password reset the count), so only it resets `F` (§3.1).
- `A` counts credentials sent and not yet answered; each is committed before it is sent, and one without an answer
  (deadline, lost connection, runner crash) counts as a failure.
- A credential is sent only if `F < MaxFailuresBeforeHalt` (`H`) and `F + A + 1 < LoginTryBeforeBan` (`B`, read from the
  login server's shipped configuration at deployment and stored in the row). Since every failure the server counts is one
  of the runner's counted in `F` or still in `A`, the server's count never exceeds `F + A < B`: the runner can never
  trigger the ban, whatever answers are pending when a failure arrives. The database refuses a row breaking
  `F + A < B` (§2).
- At `F ≥ H` the runner **halts** all logins and alarms; credentials already in flight resolve, and `F` may grow up to
  `B − 1` but no further. An admin clears the halt in one of two ways: a **probe**, one solo login of a known-good account
  (`runner login-probe`, allowed while halted when `F + 1 < B`), whose success resets `F` through the solo rule; or, when
  the probe does not fit, after restarting the login server (which clears its in-memory count), `runner login-guard clear`,
  which sets `F := 0` and clears the halt with nothing in flight.

The citizen whose credential failed is `SUSPENDED` and never retried until an admin fixes it (§3.1).

**Pacing.** Flood protection is per client connection and per category (`gameserver/util/FloodProtectors.java:71-145`,
fifteen categories), enforced at the server's receipt tick (`gameserver/util/FloodProtectorAction.java:97-98`, `:196`;
intervals in 100 ms ticks, `dist/game/config/FloodProtector.ini:5`); several packets share a category (purchases, trades,
warehouse, mail attachments and others use `canPerformTransaction`, 22 call sites, e.g.
`gameserver/network/clientpackets/RequestBuyItem.java:90`). Shipped punishment limits are all 0 (refusal only, no
punishment; `FloodProtector.ini:17` and every other `PunishmentLimit`), and flood logging is off. The rules:

- The **category map**: every packet the encoder can send is mapped to the flood category its server handler checks, or
  to none; the map is generated from the server's code (each `getFloodProtectors().can…()` call site, 46 in `java/` and
  `dist/`, with its enclosing packet or chat handler) and a static test fails when the code and the map disagree.
- **Receipt-safe spacing**: per citizen and category, the next packet is sent no earlier than the server's answer to the
  previous one plus `(interval + PacingMarginTicks) × 100 ms`. The answer is sent after the server processed the previous
  packet, and the next one cannot reach the server before it is sent, so the server sees the two at least
  `interval + PacingMarginTicks − 1` ticks apart even when the network delivers the second packet late or together with
  others (the −1 is the tick in which the first was processed). A category packet with no answer (none in the shipped
  set; the manifest lists the answer of every step) is spaced from its send by `interval + PacingMarginTicks + 1` ticks.
- **Chat**: in the shipped `GlobalChat = ON` and `TradeChat = ON` (`dist/game/config/General.ini:321`, `:326`) shout and
  trade chat have no server limit (`dist/game/data/scripts/handlers/chat/channels/ChatShout.java:64-85`); only in
  `GLOBAL` mode does the global-chat protector apply (`ChatShout.java:92-97`, `ChatTrade.java:94`). Personas pace chat in
  every mode at `ChatSecondsPerMessage` per channel, and never chat below `MinimumChatLevel`, while chat-banned or jailed.
- A refusal (the server's own messages, e.g. "You are buying too fast.", `RequestBuyItem.java:92`) is counted per category;
  the acceptance target is zero, measured on the server side too (AT-CIT-02).

**Dualbox and participation.** The runner's address carries the allowance profiles.md sets (whose whitelist needs the
dualbox core patch, profiles.md §4.3: upstream's never matches, `gameserver/config/custom/DualboxCheckConfig.java:77`
against `gameserver/managers/AntiFeedManager.java:168-170`); upstream counts addresses, not principals, so the
per-principal limits are the runner's. They mirror upstream's per-address check, which counts **concurrent**
registrations (added on registration, `gameserver/mechanics/olympiad/OlympiadManager.java:546`, removed on unregistration
or game end, `:323`, `:350`, `:398`, `:412`, `AbstractOlympiadGame.java:418`, and cleared with the registrations, `OlympiadManager.java:108`): at most
`OlympiadPerPrincipal` citizens of a principal registered for or in an Olympiad match at once, and `EventPerPrincipal`
for an event, held as `cit_participation` slots (§3.3), reserved in the database before the registration is sent. Never
two characters of one account online at once (§2).

**Bot reports and punishments** (`dist/game/config/General.ini:732`, `gameserver/data/BotReportTable.java:217-374`):
citizens never report anyone (the encoder refuses action 65, §4.1); a human may report a citizen, and every punishment
applies to it as to anyone. A citizen observes its punishments from what the server tells it (jail teleport and messages,
chat-ban messages, the bot-report restrictions' system messages) and its persona complies: a jailed citizen stays and
waits; a chat-banned one sends no chat; a restricted one does not attempt the restricted action until the punishment's
end is observed.

**A mass runner crash** restarts every citizen through the same queue (§3.1), at the same bounded rate.

### 4.8 Dialogue

A local LLM writes whispers and party chat, fed **only** from the citizen's observation store and persona; it may say "I
don't know". It decides nothing:

- The policy emits a **dialogue act**: a type (`GREET`, `SMALL_TALK`, `DONT_KNOW`, `DECLINE`, `OFFER`, `COUNTER`, `ACCEPT`,
  `REQUEST`, `WARN`, `THANK`) and its slots, each a value the policy chose: item (id), quantity, price, direction
  (buy or sell), target character, place, time. Acts without slots are the only ones the LLM may word freely.
- The LLM returns text with one placeholder per slot (`{item}`, `{qty}`, `{price}`, `{dir}`, `{who}`, `{where}`,
  `{when}`); the runner substitutes each from the act, with the item's client name, the number, a fixed phrase for the
  direction ("buying" or "selling") and so on. The LLM never writes a slot's value.
- **The check**, on the LLM's text before substitution, refuses: a missing or extra placeholder; any digit or number
  word; any item, skill, NPC, place or character name from the client's name lists or the citizen's store; any word of the
  closed **commitment lexicon** (buy, sell, WTB, WTS, pay, give, owe, promise, deliver, meet, attack, kill, war, contract,
  bounty and their forms, in EN, RO, RU); a text whose first word, in any chat type and case-insensitively, starts with any
  L2Walker command (`Say2.java:54-91`, 35 commands; upstream checks whispers only and case-sensitively, `:140-144`, so the
  rule is stricter); and after substitution: an empty text, more than 105 characters (`Say2.java:134`), a character
  outside the client's chat character set.
- If the check fails, the LLM is down or slow (`DialogueTimeoutMillis`), the citizen says the act's template line, or
  nothing for acts without one.

So prices, terms, targets, directions, commitments and every other choice come only from the policy's act. A stated limit:
a closed lexicon cannot rule out every paraphrase of a commitment; what it does rule out is any new item, number, name or
direction, and AT-CIT-08's adversarial corpus measures the rest.

### 4.9 Relationship ledger

`cit_relation`, per other character the citizen interacted with (10 §7), fed only from its own observations. Each counter
moves on exactly one kind of observation:

| Counter | +1 (or + value) on |
|---|---|
| `trades`, `trade_value` | a trade with the other completed in its own trade window (`TradeDone` followed by the success system message); value: both sides at the citizen's own price book at that moment (its policy's reference prices), in Adena |
| `contracts_ok`, `contracts_failed` | a PACT contract page shown to it, naming it and the other as parties, settled or breached by the other |
| `parties` | the other joining a party it is in, or it joining the other's (one per party lifetime) |
| `rescued_by` | the other's heal or resurrection skill (`MagicSkillUse` it saw) targeting it while its HP was below 30 % or it was dead |
| `killed_by` | its own `INFERRED` killer (§4.2) is the other |
| `killed` | the other's `Die` seen within 5 s of its own last attack on the other, with no other attacker perceived on the other in that time |
| `debts_owed` | Adena the other owes it under a contract page it was shown, overdue; reduced when paid |

**Sentiment** is a deterministic function of the counters, computed by the database (§2):
`sentiment = clamp(−1000, 1000, 30·trades + 60·contracts_ok − 150·contracts_failed + 20·parties + 200·rescued_by −
250·killed_by − 50·killed − min(300, debts_owed div 10 000))`. It changes policy: prices offered, willingness to party or
trade, which crafter gets first call, which PK the member reports to the house (§4.6), whom a clan recruits. Humans see it
only as behaviour and dialogue.

### 4.10 Economic discipline and humans as protagonists

**No grants.** Citizens earn only through ordinary play (drops, quests, NPC sales, crafting): the runner can only send
client packets, and no admin command gives a citizen an item (an operator rule; asset-service.md's site table classifies
admin creation sites, and the Genesis audit, §4.11, reports any posting to a citizen holder at one).

**The governor.** An admin tool outside the runner (platform.md: it reads the telemetry the runner cannot) computes, once
per server day, the caps of this section from telemetry windows that ended at least a day earlier, and the admin imports
them into `cit_governor_cap` with the runner's admin command. A cap only lowers what a principal may do; missing or
incomplete telemetry (a bucket marked `stalled`, or a window overlapping an `lc_journal_gap`, fact-journal.md §2)
never lowers or raises a cap: the previous one stays. Each rule below states its measurement, window, attribution and
missing-data behaviour.

| Bound | Measured as | Window | Attribution | Enforcement |
|---|---|---|---|---|
| **Farming duty** | minutes a principal's characters spend `IN_WORLD` with a farming goal (farmer, spoiler, raider, fisherman, manor farmer goals) | server day (00:00–24:00 server time) | the runner's own session records (its own state, not world knowledge) | a citizen starts a farming goal only after reserving 15-minute blocks in the principal's daily budget `FarmingHoursPerPrincipalDay`; a running goal ends at its reservation's end |
| **Extraction share** | per (subregion, resource family): the citizens' share of `ar_yield_<family>` items rolled, citizen actor class over all actor classes (aden-remembers.md §4, fact-journal.md §2.6); each principal's part of the citizens' share is its share of the items its own citizens observed themselves obtaining there (their own `InventoryUpdate` and system messages) | the last 7 complete server days | telemetry for the citizen total, the runner's own observations for the split | above `ExtractionSharePermille`, the governor issues an `EXTRACTION` cap for that principal and cell: its farming minutes there per day, lowered by the excess fraction; the cap masks farming choices in that cell; a cell with no rolled items has no cap change |
| **Stockpile** | value of what a principal's characters hold at **reference price** (the item template's `price`, the value an NPC shop uses), Adena at face value: inventories (always known to their owner), personal warehouses as last opened, clan warehouses of clans the principal leads as last opened, items in their own stores, and mail and freight they sent and have not seen received or returned | continuous | each item counts to the principal of the character holding it; a transfer between principals moves it between their totals with the trade or mail the runner observed | above `StockpileValuePerPrincipal`, buying and crafting for stock are masked (selling, consuming and spending stay open); and at most `StockpileScarceUnits` units of any one item of grade S or above per principal |
| **Scarce attendance** | per raid or epic boss engagement: a principal's characters present ÷ all player characters each of them perceived present (union, by object id) when the boss was engaged | per engagement, reported over 7 days | the runner's own perception | a principal's citizens above `ScarceSharePermille` of the perceived attendance leave the engagement (the excess, latest arrivals first); no other player perceived means no human is shut out: no limit applies |
| **Dominance** | per **market** (resource family: units sold through private stores, `ar_store_units_<family>`, seller's actor class) and per **contest** (castles held, by owner clan's principal or human; Olympiad heroes; raid and epic boss kills, by last-hit side): the citizens' share against the humans' | rolling `DominanceDays` measured days | telemetry and public state for the citizen total; the runner's own sales, holdings, heroes and kills for the split per principal | a market or contest is measured on a day only when its telemetry is complete and humans took part (human volume > 0); a day otherwise neither breaks nor extends a streak, and a day with citizens only is reported as uncontested. Above `DominanceThresholdPermille` for `DominanceDays` measured days, the governor issues a `RETUNE` cap for the principal with the largest share: an admin-reviewed policy change (a lower price aggressiveness, fewer attempts at that contest), never a change to the humans' rules |

**Modelled consumption and loss**: shots, potions, repairs, PK drops, scams outside contracts, bankruptcy, quitting.

**Conservation** is the Asset Service's, not the runner's: it guarantees exact postings and custody always (ASSET-INV01,
INV11) and detects exactly any difference across a clean restart (INV06), while live transfers are only watched and a
crash forces a `REBASE` that is never reported as conservation holding (asset-service.md §2.6). The runner adds no
per-principal conservation ledger and claims none; per-principal accounting here is the stockpile measurement above,
which is an observation-based estimate, not a conservation proof.

### 4.11 Genesis

Day 0: an empty world, retail spawns, the canonical rules. Citizens arrive on a launch curve (a first wave, then
decaying daily arrivals), choose races and classes by distribution, start where retail starts them, and play in real time
(Mobius timers are not accelerated; 10 §8). Aden Remembers, the Chronicle and the Gazette run from day 0.

The **Genesis audit** is an admin tool (not the runner) that reads the game's tables and journal read-only, snapshots them
hourly, and measures the **exit criteria**; testers arrive when every one is true:

| Criterion | Measured as |
|---|---|
| at least 5 castles changed hands | castles whose owning clan (`clan_data.hasCastle`) differs between two hourly snapshots at least once, the first capture from the NPC included |
| at least one Territory War fought | a Territory War period after which at least one territory's `territories.ownedWardIds` differs from its value at the period's start |
| markets for every grade below S | for each of no-grade, D, C, B and A: at least one private-store sale of a weapon or armour of that grade on at least 7 of the last 14 server days |
| at least 3 crafter specialisations led by different principals | a specialisation is (grade, weapons, armour, jewellery or consumables); its leader is the principal whose citizens crafted the most units of it in the last 14 days (the runner's own craft records); at least 3 specialisations with at least 20 units each, led by 3 distinct principals |
| at least 10 completed Black Ledger hunts | settled Writs and Black Contracts (black-ledger.md) |
| at least 30 Gazette issues | published issues (chronicle.md) |
| conservation | across all of Genesis: no ASSET-INV01 or INV11 violation; every restart's census either `STARTUP` with no `Asset.ConservationBroken`, or a `REBASE` (a crash) whose per-item difference an admin reviewed and recorded; and since the last `REBASE`, at least one clean restart whose census is equal |

Missing evidence never counts as met: an hour without a snapshot, or a window overlapping a journal gap, leaves the
criteria that need it unmet until later evidence covers them.

## 5. Facts

The runner writes none to the game: everything it does reaches the server as protocol. Its own log is
`cit_decision_log`, `cit_oob_message` and `cit_governor_cap`.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| CIT-INV01 | every decision input is an earlier observation of the deciding citizen, and every constraint a valid cap of its principal | the triggers, at insert (§2); AT-CIT-00, AT-CIT-03 |
| CIT-INV02 | the runner has no access to game tables or server memory | its database user's grants; the process boundary; AT-CIT-04 |
| CIT-INV03 | `failures + in_flight < ban_threshold` | the check constraint (§2); AT-CIT-00, AT-CIT-02 |
| CIT-INV04 | the policy bounds of §4.10 hold per principal | the governor's daily run and the runner's reservations; AT-CIT-06 |

## 7. Failure model

| Fault | End state |
|---|---|
| a credential refused | that citizen `SUSPENDED`; at `MaxFailuresBeforeHalt` all logins halt, alarmed; the server's count stays below its ban (§4.7) |
| a credential unanswered (timeout, lost connection) | counted as a failure, as above |
| the runner crashes | every citizen offline; at start, credentials in flight counted as failures; participation slots kept; restart through the admission queue |
| the login server restarts | its count clears; the runner's `F` stays (conservative) until a solo success |
| a desync or unexpected packet | the session to `BACKOFF` and requeued; counted; the soak test's zero-desync target |
| a flood refusal | counted per category; the next send waits a full spacing again |
| the LLM down or slow | template lines or silence |
| geodata hash mismatch | the runner does not start |
| the out-of-band channel down | members act on their own memory; messages are not queued for later |
| an identity import with an older, missing or conflicting version | ignored, or the account held and alarmed (§4.6) |
| governor telemetry missing or stalled | caps unchanged |

## 8. Configuration

`runner.ini` (the runner's), per profile; only `sim`, `hobby` and `live` run citizens.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CitizensMaxOnline` | 0–2000 | 800 | 0 / 20 / 800 / 800 / 800 | AT-CIT-07 |
| `RunnerLoginsPerSecond` | 0.1–1.4 | 1 | as default where citizens run | AT-CIT-02 |
| `MaxOutstandingLogins` | 1–50 | 4 | as default | AT-CIT-02 |
| `MaxFailuresBeforeHalt` | 1 to `LoginTryBeforeBan` − 2 | 2 | as default | AT-CIT-02 |
| `LoginStepTimeoutMillis`, `BackoffBaseMillis`, `BackoffMaxMillis` | 1000–60000, 500–10000, 10000–600000 | 10000, 2000, 300000 | as default | AT-CIT-02 |
| `PacingMarginTicks`, `ChatSecondsPerMessage` | 1–10, 2–120 | 2, 8 | as default | AT-CIT-02 |
| `TrackedObjects`, `ReactionMillis` (per persona) | 5–200, 100–5000 | 40, persona table | as default | AT-CIT-03 |
| `OlympiadPerPrincipal`, `EventPerPrincipal` | 0–50, 0–50 | 3, 5 | as default | AT-CIT-02 |
| `OobMessagesPerHour`, `OobDelaySeconds`, `OobHistoryMessages` | 1–60, 5–3600 (a range), 0–500 | 6, 30–300, 50 | as default | AT-CIT-03 |
| `DialogueTimeoutMillis` | 200–10000 | 2000 | as default | AT-CIT-08 |
| `FarmingHoursPerPrincipalDay`, `ExtractionSharePermille`, `ScarceSharePermille` | 1–500, 50–1000, 50–1000 | 120, 300, 400 | as default | AT-CIT-06 |
| `StockpileValuePerPrincipal`, `StockpileScarceUnits` | 10⁷–10¹¹ Adena, 0–1000 | 2·10⁹, 20 | as default | AT-CIT-06 |
| `DominanceThresholdPermille`, `DominanceDays` | 300–1000, 1–30 | 600, 7 | as default | AT-CIT-06 |
| `RawRetentionDays`, `DecisionRetentionDays` | 1–90, 7–365 | 14, 90 | as default | AT-CIT-05 |

## 9. Interface

None for players beyond the citizens themselves. Admin: the runner's view (population by persona, principal and band;
the login guard; flood refusals per category; governor caps; dominance monitors; `lc why citizen <char> <decision>`
printing a decision's inputs, constraints, snapshot and choice, and replaying it); `lc citizen provision`, `retire`,
`export` (actor-identity.md); the runner's `runner identity import`, `runner governor import`, `runner unsuspend`,
`runner login-probe`, `runner login-guard clear`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| CIT-R01 | Every workflow of the manifest MUST round-trip against the real server in both cipher modes, the encoder MUST refuse everything outside the sendable set (the bot report included), the library MUST survive the fault matrix, and a punished citizen MUST comply with its punishment. | AT-CIT-01 |
| CIT-R02 | Admission and pacing MUST keep every citizen below every flood limit at the server and the address below the login ban whatever answers are pending, crashes included; a mass reconnect MUST keep human logins within the probe's band; per-principal participation MUST never exceed its limit, crashes included. | AT-CIT-02 |
| CIT-R03 | No decision input MUST come from anything but the citizen's own observations (its channel reads included) and validated geodata; governor caps MUST only mask choices; every decision MUST replay from its log row. | AT-CIT-03 |
| CIT-R04 | The runner MUST have no access to game tables or server memory, and the game none to the runner's. | AT-CIT-04 |
| CIT-R05 | Observation stores, channel messages, relationship ledgers and sentiment MUST derive only from the citizen's own observations, by the rules of §4.6 and §4.9. | AT-CIT-05 |
| CIT-R06 | The policy bounds and dominance monitors of §4.10 MUST hold per principal, measured as §4.10 states, and the humans-as-protagonists tests MUST pass their bands. | AT-CIT-06 |
| CIT-R07 | Genesis MUST reach its exit criteria, measured as §4.11 states. | AT-CIT-07 |
| CIT-R08 | The LLM MUST only word dialogue acts the policy chose, checked before sending, and MUST decide nothing. | AT-CIT-08 |
| CIT-R09 | Citizens MUST be judged believable and not dominant by recorded tester sessions, by §10's rubric. | AT-CIT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-CIT-00 | integration | the runner migration applies to its own database `lc_runner`, and no runner table exists in the game's; every rule of §2's list is refused when broken (one case each, see `RunnerSchemaTest`); the runner's user, with exactly §2's grants, is refused `SELECT` and `INSERT` on every table of the game's database and `UPDATE` on every append-only table; the game's user, with all of the game's database, is refused every runner table |
| AT-CIT-01 | integration, fault | `CIT-PROTO-*`: every manifest row run end to end against the real server in both cipher modes, each step's expected answer observed; every persona of §4.5 has its workflows in the manifest; the encoder refuses each null-supplier opcode, a bookmark sub-id outside 0–5 and action 65, and sends each sendable one; the fault matrix (fragmented and coalesced frames, reconnects, malformed lengths, cipher state, loss on disconnect, stale objects); a 24 h soak with zero desyncs and kicks; a citizen jailed, chat-banned and bot-report-restricted by an admin sends no chat while banned, stays jailed, and attempts no restricted action until the end is observed |
| AT-CIT-02 | integration, sim | 800 citizens logging in after a mass crash, with flood logging on for every category (`FloodProtector*LogFlooding = True`, logging only; no punishment limit changed): zero flood log lines on the server and zero refusals counted by the runner; a human probe, 10 human-origin accounts from another address logging in at random times throughout, every login reaching the character list, each within the baseline's p99 (the same probe without the runner, same host) plus 1 s; the login guard: a stub login server answering `REASON_USER_OR_PASS_WRONG` to chosen credentials with 4 in flight shows the server-side count never reaching 5, the halt at 2, a crash with credentials in flight counting them at restart, a non-solo success not resetting and a solo one resetting; participation: concurrent registration attempts never exceed `OlympiadPerPrincipal` or `EventPerPrincipal`, and a runner killed between reservation and registration restarts without admitting another |
| AT-CIT-03 | sim, unit | `CIT-EPISTEMIC-*`: (a) the static scan of the policy module finds no reference to the protocol, perception internals or governor packages; (b) a recorded sim run's packet capture replayed offline twice through filter and policies with a virtual clock, the second time with injected packets the filter drops (objects beyond the tracked cap and range, chat outside attention): both decision logs are byte-identical; a planted policy reading a dropped packet through a test hook makes them differ; (c) every decision of the run replays from its row to its choice, and its snapshot equals the stored rows while retained; (d) the run replayed with every non-binding cap removed gives identical decisions, and with a binding cap gives only choices the uncapped run's ranking allowed; (e) channel delivery respects rate, delay, history and the perception filter |
| AT-CIT-04 | integration | the runner's user's `SHOW GRANTS` equals §2 exactly; from inside the runner's container, a TCP connection to every port of the game and login containers but 7777 and 2106 is refused; the runner's environment and files contain neither the game's nor the login server's database credential |
| AT-CIT-05 | unit, sim | each counter of §4.9 moves exactly on its observation kind and on nothing else (one fixture per row, plus a fixture of the same event seen by another citizen only, which moves nothing); sentiment equals the formula for the counters; a killer inferred only from perceived attacks; a channel message whose text uses a value absent from its referenced observations is refused by the writer; a steward's bounty decision has only `OOB` and its own observations as inputs |
| AT-CIT-06 | sim | `CIT-ECON-*`, each with its band: farming minutes per principal-day ≤ the reservation; a planted principal over-farming one cell gets an `EXTRACTION` cap the next day and its minutes there fall; stockpile above the limit masks buying; scarce attendance above the share makes the excess leave; a planted dominance of 8 measured days triggers one `RETUNE`, a day with missing telemetry neither breaks nor extends the streak; **newcomer margin**: a scripted human newcomer (levels 1–20, retail newbie spots, selling drops) earns ≥ 80 % of the Adena per hour it earns in a run without citizens on the same seed; **support demand**: a scripted human buffer's sales ≥ the median citizen buffer's over the same hours; **trade opportunity**: a scripted human store at the citizens' median price sells within ±30 % of the median comparable citizen store; **contestability**: 10 scripted human level-80 characters allied with one citizen principal take a castle from another in at least 1 of 3 seeded sieges |
| AT-CIT-07 | sim | `CIT-GENESIS-*`: a Genesis run measured by the Genesis audit reaches every exit criterion of §4.11; a planted gap in the snapshots leaves a criterion unmet until covered |
| AT-CIT-08 | unit | the check refuses an extra or missing placeholder, a digit or number word, a name from the lists, a commitment-lexicon word in each language, a text starting with any of the 35 L2Walker commands in any case, an empty or over-long line; slot values appear only by substitution; an adversarial corpus of 500 LLM outputs per act type yields no sent line whose meaning (judged by the corpus's labels) differs from its act; an LLM outage or timeout yields templates |
| AT-CIT-09 | human | `CIT-HUMAN-*`: at least 5 testers, at least 3 recorded sessions of at least 1 h each; after each, two 5-point items ("the characters I met behaved like players", "citizens beat me unfairly"); and once, a blind origin guess over 20 recorded interactions (10 human, 10 citizen); pass: median believability ≥ 4, median unfairness ≤ 2, origin guessed right ≤ 70 % |

## 11. Upstream constraints

1. **No killer in `Die`, no killer message sent** (`Die.java:69`; `SystemMessageId.java:7083`, `:7086`). → inference.
2. **Failed logins are counted per address with no window, five ban it, a correct password resets the count**
   (`loginserver/controller/LoginController.java:174-200`, `:237`). → the runner's conservative guard, halt at two.
3. **Client logins have no per-address flood limit and no reserved capacity** (`FloodProtectorListener.java:85-110` guards
   only the game-server link; `ConnectionManager.java:73-84`). → the runner's own bounded admission, measured by a human
   probe.
4. **The dualbox whitelist never matches** (`DualboxCheckConfig.java:77`; `AntiFeedManager.java:168-170`), and it counts
   addresses, not principals. → profiles.md's core patch; per-principal slots in the runner.
5. **No geodata shipped** (`dist/game/data/geodata/`). → a pinned, validated set for both.
6. **Empty or unknown chat kicks, long chat is refused, a whisper starting with a walker command is punished**
   (`Say2.java:114-128`, `:134`, `:140-144`). → dialogue checked before sending.
7. **`AutoCreateAccounts` is on** (`dist/login/config/Server.ini:77`). → the runner logs in only as provisioned logins.
8. **Flood protection is judged at receipt, in ticks, per shared category** (`FloodProtectorAction.java:97-98`, `:196`).
   → spacing from the server's answer, a generated category map.
9. **`0xD0` and extended `0x51` are envelopes with null suppliers** (`ClientPackets.java:206`,
   `ExClientPackets.java:118`, `GamePacketHandler.java:60-119`). → the sendable set is what the handler dispatches.

## 12. Review record

- Round 1 (Codex, a different model family), NO SIGN-OFF, 15 findings (12 HIGH, 3 MEDIUM). Revision 2:
  1. *Telemetry fed decisions*: fixed. Extraction no longer reads `ar_yield` in the runner; a governor outside the
     runner computes caps from telemetry lagged a day, imported as `cit_governor_cap`; a cap only masks choices, through
     an opaque predicate, logged as `constraints`, never an input (§1, §4.5 rule 3, §4.10); AT-CIT-03 (a), (d).
  2. *House decisions bypassed the channel*: fixed. A house decides through its steward citizen, from the `GRUDGE` and
     `ALLY` messages it read as `OOB` observations; ledgers are never merged (§4.3, §4.6); AT-CIT-05.
  3. *Fabricated or cross-citizen provenance*: fixed in the schema: triggers check every inference source, channel read,
     message reference, decision input and constraint for ownership, existence and chronology; messages, observations,
     decisions and caps are never updated, and the runner's user has no update grant on them (§2); message text comes only
     from templates over referenced payload fields (§4.6). AT-CIT-00 has a case for each.
  4. *No oracle for unlogged influence*: fixed. Inputs are the policy view's recorded read set, not the policy's report;
     a static scan forbids packet and telemetry types in policies; a dropped packet provably leaves the filter's state
     unchanged; offline paired replay with injected dropped traffic must give byte-identical logs (§4.2, §4.5; AT-CIT-03).
  5. *Retention destroyed replay evidence*: fixed. `obs_id` alone is the key (partitioned by id ranges); decisions carry
     their input snapshot, persona state and configuration version; summaries keep their sources' ids (§2, §4.5 rule 4).
  6. *Null-handler exclusion removed dispatchers*: fixed. The sendable set is what `GamePacketHandler` dispatches, with
     `0xD0` and extended `0x51` as envelopes and the counts corrected to 12 and 30 plus `EX_MAX` (§4.1, §11.9).
  7. *Halt-at-two did not prevent the ban*: fixed. Upstream has no window and resets on a correct password (verified,
     `LoginController.java:174-200`, `:237`); the runner keeps `F + A < B` with credentials committed before sending,
     unanswered ones counted as failures, crash recovery, and resets only by a solo success; the database enforces the
     bound (§3.1, §4.7); AT-CIT-02 with a stub login server.
  8. *No capacity model for human logins*: fixed by changing the claim, with the reviewer's evidence accepted
     (`ConnectionManager.java:73-84` has no reservation and none can be added without a core patch): the runner bounds its
     own rate absolutely, and AT-CIT-02's human probe measures human logins against a baseline band (§4.7).
  9. *Participation limits without recovery or identity sync*: fixed. Durable slots reserved before registering, released
     only on observation, mirroring upstream's concurrent count; the identity mirror carries `identity_ver`, changes only
     by import, and holds accounts that cannot change safely (§3.3, §4.6, §4.7; schema triggers).
  10. *Send pacing did not guarantee receipt spacing*: fixed. Spacing starts from the server's answer, a generated
      category map covers shared categories, flood logging is turned on in the test as the server-side oracle, and the
      shout citation is stated with its mode (§4.7; AT-CIT-02).
  11. *Bounds lacked measurements*: fixed. Each bound has its measurement, window, attribution, enforcement and
      missing-data rule; telemetry gives only citizen totals, the runner's own observations the split per principal
      (§4.10).
  12. *Per-principal conservation exceeded the dependency*: fixed by withdrawing the claim. Conservation is the Asset
      Service's as it states it; the Genesis criterion is restated as its exact guarantees plus reviewed rebases (§4.10,
      §4.11; CIT-R06, CIT-R07 reworded).
  13. *The isolation test proved neither schema separation nor mutual isolation*: fixed. AT-CIT-00 now applies the
      migration to `lc_runner`, checks no runner table is in the game's database, applies §2's exact grants, and tests
      every game table for reads and writes and every runner table from the game's user; AT-CIT-04 has concrete grant,
      network and credential oracles.
  14. *The dialogue check did not stop the LLM deciding*: fixed. The policy chooses a dialogue act; the LLM writes only
      placeholders for its slots, which the runner fills; a commitment lexicon and the full L2Walker list (stricter than
      upstream's whisper-only check) are refused; the residual paraphrase risk is stated and measured (§4.8; AT-CIT-08).
  15. *Tests without oracles*: fixed. A workflow manifest for AT-CIT-01; exact counter rules and the sentiment formula
      for AT-CIT-05; scenarios and bands for AT-CIT-06; measurable criteria and a missing-evidence rule for AT-CIT-07; a
      rubric and thresholds for AT-CIT-09; bot-report refusal and punishment compliance in AT-CIT-01; channel rate, delay,
      history and fan-out bounds (§4.6).
  Also corrected: the login controller's path (`loginserver/controller/`), the failure-path citations, and the
  `NormalConnectionTime` line (`Server.ini:59`).
- Revision 1 (draft): not reviewed.
