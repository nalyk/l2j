# SPEC-CIT — Citizens of Aden

- Status: draft (revision 1)
- Blueprint: 10 (Citizens of Aden); 01 §2.5 (alts and Discord); 02 §3 (game logic never branches on origin), §7 (the
  runner is a separate process); 03 §7 (geodata); 04 §2 (Actor Identity); 16 §8 (information design); 20 §1.
- Depends on: actor-identity.md (provisioning, origin, principals, policy), profiles.md (the runner's dualbox allowance),
  aden-signal.md (synthetic candidates), black-ledger.md (houses fund bounties), wayfarers-memory.md (citizens keep their
  own store), fact-journal.md (the benchmarks).
- DDL: `sql/runner/V001__cit_runner.sql` (the runner's own schema; not a game migration).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/` unless they start with `dist/`.

## 1. Scope

The citizens are a resident population of real `Player` characters on real accounts, played by software through the real
network protocol, from a separate process (02 §7): the **citizen runner**. This spec owns the runner: its protocol
library, its perception filter and observation store, its navigation, its personas and decision policies, its principals
and their out-of-band channel, its relationship ledger, the dialogue boundary, its login admission and pacing, its economic
discipline, the Genesis epoch and the acceptance of all of it.

It does not own, and never touches: the game server's code or tables (the runner has no access to them), how the server
tells citizens apart (actor-identity.md: provisioning, origin, policy), or any mechanic. A citizen is subject to every
rule a human is: the same packets, the same flood protection, the same bot reports, the same Writs.

**Epistemic honesty** (10 §5.1): a citizen knows only what its client was sent and what its principal's out-of-band
channel told it; static navigation geometry from validated geodata is the one other input. The perception filter is
applied before anything reaches a decision or shared memory.

## 2. Data (the runner's schema)

```text
cit_principal(principal_id PK, name, budget_policy JSON, information_policy JSON, goals JSON)       -- mirrors lc_principal
cit_account(account_id PK, login, secret_ref, principal_id, created_at, retired)
cit_character(char_id PK, account_id, name, class_id, persona, state {ACTIVE|RESTING|QUIT})
cit_persona_state(char_id PK, goal, plan JSON, budget JSON, updated_at)
cit_schedule(char_id, weekday, start_minute, end_minute, PRIMARY KEY(char_id, weekday, start_minute))
cit_observation(obs_id PK, char_id, at, kind, subject_kind, subject_ref, payload JSON, provenance {SEEN|HEARD|TOLD|OOB},
                source_ref NULL, tier {RAW|SUMMARY}, expires_at NULL)                                -- partitioned by month
cit_relation(citizen_char_id, other_char_id, first_seen, last_seen, trades, trade_value, contracts_ok, contracts_failed,
             parties, killed_by, killed, rescued_by, debts_owed, notes_tags JSON, sentiment SMALLINT,
             PRIMARY KEY(citizen_char_id, other_char_id))
cit_oob_message(msg_id PK, principal_id, from_char, channel, at, text VARCHAR(500), observation_refs JSON)
cit_decision_log(decision_id PK, char_id, at, policy, policy_ver, inputs JSON, choice JSON, rng_key)
```

- The runner's database user can read and write only this schema; the game's user cannot read it (10 §11). Secrets
  (`secret_ref`) live in the runner's secret store, never in a table.
- `cit_account` mirrors the accounts the game's admin provisioned as `SYNTHETIC_CITIZEN` (actor-identity.md §2.2,
  `lc citizen provision`): the runner never creates an account (the login server's `AutoCreateAccounts = True`,
  `dist/login/config/Server.ini:77`, `loginserver/LoginController.java:243-267`, would let any login create one; the
  runner logs in only as a provisioned login, and actor-identity.md's patch refuses an unprovisioned one).
- `cit_decision_log.inputs` lists observation ids, never a raw packet; the epistemic audit (§4.9) walks them.

## 3. State machines

### 3.1 Citizen session

`OFFLINE` → `LOGIN_QUEUED` (admission, §4.7) → `LOGIN` (login protocol) → `GAME_CONNECT` → `CHAR_SELECT` → `IN_WORLD`
→ (`RESTART` → `CHAR_SELECT`) → `LOGOUT` → `OFFLINE`; any state → `BACKOFF` on a refused or failed step (jittered,
bounded) → `LOGIN_QUEUED`; `SUSPENDED` after a credential failure (§4.7), until an admin clears it.

### 3.2 Character life

`ACTIVE` (following its schedule) ⇄ `RESTING` (outside it); `QUIT` (persona decided to quit: never logs in again; its
assets stay in the world as a human's would).

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
  inverse of its `writeImpl`; opcodes with no handler (13 of 170 and 32 of 145 extended, `ClientPackets.java`,
  `ExClientPackets.java`) are never sent. Checked differentially against independent clients and captured traffic from
  the pinned client.
- **Known traps**: the server ignores silently an unknown or wrong-state packet (`GamePacketHandler.java:65-81`), so the
  library asserts the state before sending; an empty `Say2` text or unknown chat type **kicks** (`Say2.java:114-128`) and
  over 105 characters is refused (`:133`), so dialogue is bounded before it is sent.

### 4.2 Perception

A citizen's **perception filter** turns packets into observations under an attention budget, per persona: at most
`TrackedObjects` objects tracked (the nearest and most relevant), a reaction latency drawn per reaction
(`ReactionMillis`, a persona-specific distribution), and a noticing probability falling with distance and clutter. Only
what passes becomes an observation (`SEEN` for world packets, `HEARD` for chat, `TOLD` for what another character said to
it, `OOB` for its principal's channel), with time and provenance. Packets that never pass are dropped; no decision and no
shared memory reads a packet.

Some things the server never says, and a citizen must infer them as a human would: a `Die` packet names no killer
(`gameserver/network/serverpackets/Die.java:69`), and the "killed by" system messages are never sent
(`SystemMessageId.java:7083`, `:7086`; a death is broadcast as `Die` only, `ai/AbstractAI.java:444`); so "killed by X" is
an inference from the attacks the citizen itself perceived on itself before it died, recorded as `INFERRED` with its
evidence, and may be wrong.

### 4.3 Observation store

Per citizen, `cit_observation`, with tiers like Wayfarer's (raw for `RawRetentionDays`, then summarised) and the same
rule: memory is the citizen's own. A clan's shared memory is what its members wrote to the out-of-band channel, never their
stores merged.

### 4.4 Navigation

Static geometry comes from **validated geodata** (03 §7): the fork ships none (`dist/game/data/geodata/` holds only a
readme; `geoengine/GeoEngine.java:68`, `:102`), so the deployment installs the pinned geodata set for both the server and
the runner, its hash recorded by both, and a mismatch stops the runner. Doors, fences and other dynamic obstacles are
learned from observation and failure (a blocked move, a closed door seen), never read from server state.

### 4.5 Personas and decisions

Personas (10 §6: farmer, spoiler, crafter by grade, merchant, buffer for hire through SellBuffs, party leader, raider,
manor farmer, fisherman, clan leader, diplomat, siege commander, Territory War mercenary, fortress holder, outlaw, Writ
hunter, Black Contract hunter, informant, broker client, caravan escort, caravan raider, Olympiad competitor, newbie,
returning player, quitter) each declare goals, preconditions, utility terms, budget rules, risk appetite, a schedule
template, what they write to the out-of-band channel, and their complete protocol workflows.

**Decisions** are deterministic utility policies over observations, persona state and a keyed random stream
(`cit_decision_log.rng_key`), logged with their inputs and choice, so every decision replays and explains (10 §5.3).
Efficiency is bounded by decision latency, schedules, imperfect knowledge and spending preferences, never by any
mechanic.

### 4.6 Principals and the out-of-band channel

A **principal** (a house) is the will behind a group of citizens (10 §4): its own budget, its information policy, its
goals; principals compete, transfer funds only through ordinary trades, contracts and bounties, and are subject to the
same repeat-pair and reciprocal controls as humans (black-ledger.md, pact.md). A house funds Writs and bounties from its
own budget (black-ledger.md §4.3). Its clans share an **out-of-band channel** (`cit_oob_message`), logged and allowlisted
(only members of the principal; only text derived from their own observations, with the observation ids), emulating a
Discord so information design is exercised under the alts-and-Discord assumption (01 §2.5, 16 §8). The
`SCENARIO_ADVERSARY` profile (actor-identity.md §2.2) is the only one that colludes across principals, and only in tests.

### 4.7 Login admission and pacing

- **Admission**: one runner-wide queue spaces logins by more than `NormalConnectionTime` (700 ms,
  `dist/login/config/Server.ini:53-65`), keeps at most `MaxOutstandingLogins` in flight, and backs off with jitter.
  The login server's own per-address flood settings protect only its game-server link (`loginserver/util/
  FloodProtectorListener.java:100`, used by `GameServerListener.java:45`), not client logins, so the runner's queue is the
  only thing that keeps a mass reconnect from crowding humans out: it takes at most `RunnerLoginShare` of login slots per
  second.
- **The shared ban**: five failed logins from one address ban it for 900 s (`loginserver/LoginController.java:194-196`),
  and every citizen shares the runner's address. So a citizen whose login fails once is `SUSPENDED` (never retried), and
  the runner stops all logins after `MaxFailuresBeforeHalt` (2) failures in the window, below the server's 5, and alarms.
- **Pacing**: each action category is paced above its `FloodProtector.ini` interval (`dist/game/config/FloodProtector.ini:15-117`,
  enforced by `util/FloodProtectorAction.java:89-200`), so no citizen is ever throttled or punished; chat is paced by the
  persona even where the server has no limit (shout and trade, `ChatShout.java:92-97`).
- **Dualbox**: the runner's address carries the allowance profiles.md sets (whose whitelist needs the dualbox core patch,
  profiles.md §4.3: upstream's never matches, `config/custom/DualboxCheckConfig.java:77` against
  `managers/AntiFeedManager.java:168-170`); within it, the per-principal limits are the runner's: at most
  `OlympiadPerPrincipal` citizens of a principal registered for one Olympiad cycle, `EventPerPrincipal` for one event, and
  never two characters of one account online at once.
- **Bot reports** (`dist/game/config/General.ini:732`, `data/BotReportTable.java:217`): citizens never report anyone; a
  human may report a citizen, and its punishments apply to it as to anyone.
- A mass runner crash restarts through the same queue, so it cannot block human logins.

### 4.8 Dialogue

A local LLM writes whispers and party chat, fed **only** from the citizen's observation store and persona; it may say "I
don't know". It decides nothing: prices, terms, targets, wars and every other choice come from the policies (§4.5), and the
LLM's text is checked before sending: no number but those the policy supplied, no character name the citizen has not
observed, within `Say2`'s length and never empty (§4.1). If the check fails or the LLM is down, the citizen says a
template line or nothing.

### 4.9 Relationship ledger

`cit_relation`, per other character the citizen interacted with (10 §7), fed only from its own observations: trades and
their value (its own trade windows), contracts kept and failed (the contract pages it was shown), parties, kills it
perceived (§4.2's inference), rescues, debts. **Sentiment** is a deterministic function of the counters (no LLM), and it
changes policy: prices offered, willingness to party or trade, which crafter gets first call, which PK gets a bounty, whom
a clan recruits. A principal aggregates its members' ledgers into house grudges and alliances; a human who repeatedly
kills a house's members becomes its bounty target through the ordinary Black Ledger. Humans see it only as behaviour and
dialogue.

### 4.10 Economic discipline and humans as protagonists

- No grants: citizens earn only through ordinary play (drops, quests, NPC sales, crafting), counted per actor class and
  principal by the Asset Service's monitor (asset-service.md §2.6).
- **Bounds by policy**, never by stats: an aggregate farming duty per principal (`FarmingHoursPerPrincipalDay`); an
  extraction share per (region, resource family) of at most `ExtractionSharePermille` of that cell's telemetry (Aden
  Remembers' `ar_yield`, read by the runner through the admin metrics feed, aggregate only); stockpile limits per
  principal; a scarce-content attendance share (raids, epic bosses) of at most `ScarceSharePermille`.
- **Dominance monitors**: per market and contest (item market share, castle holdings, Olympiad heroes, raid kills), the
  citizens' share against the humans'; above `DominanceThresholdPermille` for `DominanceDays`, the principal's policy is
  retuned (an admin-reviewed change), never the humans'.
- Modelled consumption and loss: shots, potions, repairs, PK drops, scams outside contracts, bankruptcy, quitting.

### 4.11 Genesis

Day 0: an empty world, retail spawns, the canonical rules. Citizens arrive on a launch curve (a first wave, then
decaying daily arrivals), choose races and classes by distribution, start where retail starts them, and play in real time
(Mobius timers are not accelerated; 10 §8). Aden Remembers, the Chronicle and the Gazette run from day 0. Testers arrive
when the **exit criteria** are measured true: at least 5 castles changed hands at least once; at least one Territory War
fought; markets for every grade below S; at least 3 crafter specialisations led by different principals; at least 10
completed Black Ledger hunts; at least 30 Gazette issues; no conservation invariant ever violated.

## 5. Facts

The runner writes none to the game: everything it does reaches the server as protocol. Its own log is
`cit_decision_log` and `cit_oob_message`.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| CIT-INV01 | every decision input traces to an observation the citizen perceived or to its principal's channel | the epistemic audit; AT-CIT-03 |
| CIT-INV02 | the runner has no access to game tables or server memory | its database user's grants; the process boundary; AT-CIT-04 |
| CIT-INV03 | no citizen is ever flood-throttled, kicked or banned by pacing | runner metrics; AT-CIT-02 |
| CIT-INV04 | the policy bounds of §4.10 hold per principal | daily checks; AT-CIT-06 |

## 7. Failure model

| Fault | End state |
|---|---|
| a credential refused | that citizen `SUSPENDED`; two in a window halt all logins, alarmed, before the server's ban |
| the runner crashes | every citizen offline; restart through the admission queue |
| a desync or unexpected packet | the session closed and requeued; counted; the soak test's zero-desync target |
| the LLM down | template lines or silence |
| geodata hash mismatch | the runner does not start |
| the out-of-band channel down | principals' clans act on their own memory |

## 8. Configuration

`runner.ini` (the runner's), per profile; only `sim`, `hobby` and `live` run citizens.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `CitizensMaxOnline` | 0–2000 | 800 | 0 / 20 / 800 / 800 / 800 | AT-CIT-07 |
| `MaxOutstandingLogins`, `RunnerLoginShare`, `MaxFailuresBeforeHalt` | 1–50, 1–100 %, 1–4 | 4, 50 %, 2 | as default where citizens run | AT-CIT-02 |
| `TrackedObjects`, `ReactionMillis` (per persona) | 5–200, 100–5000 | 40, persona table | as default | AT-CIT-03 |
| `OlympiadPerPrincipal`, `EventPerPrincipal` | 0–50, 0–50 | 3, 5 | as default | AT-CIT-02 |
| `FarmingHoursPerPrincipalDay`, `ExtractionSharePermille`, `ScarceSharePermille` | 1–500, 50–1000, 50–1000 | 120, 300, 400 | as default | AT-CIT-06 |
| `DominanceThresholdPermille`, `DominanceDays` | 300–1000, 1–30 | 600, 7 | as default | AT-CIT-06 |
| `RawRetentionDays` | 1–90 | 14 | as default | AT-CIT-05 |

## 9. Interface

None for players beyond the citizens themselves. Admin: the runner's view (population by persona, principal and band;
dominance monitors; `lc why citizen <char> <decision>` printing a decision's inputs and choice); `lc citizen provision`,
`retire` (actor-identity.md).

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| CIT-R01 | Every opcode a persona uses MUST round-trip against the real server, and the library MUST survive the fault matrix. | AT-CIT-01 |
| CIT-R02 | Admission and pacing MUST keep every citizen below every flood limit and the login ban, and a mass reconnect MUST NOT block human logins. | AT-CIT-02 |
| CIT-R03 | No decision input MUST come from anything but the citizen's perceived observations, its principal's channel and validated geodata. | AT-CIT-03 |
| CIT-R04 | The runner MUST have no access to game tables or server memory. | AT-CIT-04 |
| CIT-R05 | Observation stores, relationship ledgers and sentiment MUST derive only from the citizen's own observations, sentiment deterministically. | AT-CIT-05 |
| CIT-R06 | The policy bounds and dominance monitors MUST hold per principal, and conservation per item and principal. | AT-CIT-06 |
| CIT-R07 | Genesis MUST reach its exit criteria without an invariant violation. | AT-CIT-07 |
| CIT-R08 | The LLM MUST only write words, checked before sending, and decide nothing. | AT-CIT-08 |
| CIT-R09 | Citizens MUST be judged believable and not dominant by recorded tester sessions. | AT-CIT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-CIT-00 | integration | the runner migration applies to its own schema; keys refuse a second relation row per pair, an observation without provenance, an out-of-band message without observation references; the runner's user is refused every game table |
| AT-CIT-01 | integration, fault | `CIT-PROTO-*`: every persona workflow end to end against the real server in both cipher modes; the fault matrix (fragmented and coalesced frames, reconnects, malformed lengths, cipher state, loss on disconnect, stale objects); a 24 h soak with zero desyncs and kicks |
| AT-CIT-02 | integration, sim | 800 citizens logging in after a mass crash: no flood refusal, no ban, human logins admitted throughout; a refused credential suspends one citizen and two halt the queue before the server's fifth; per-principal Olympiad and event limits |
| AT-CIT-03 | sim | `CIT-EPISTEMIC-*`: every `cit_decision_log` input resolves to an observation the citizen's own packets produced after the filter, or to an out-of-band message of its principal; a planted decision reading a packet the filter dropped is caught |
| AT-CIT-04 | integration | the runner's database user is refused every game table; the runner process holds no game-server handle |
| AT-CIT-05 | unit, sim | relation counters equal the citizen's own observations; sentiment equal for equal counters; a killer inferred only from perceived attacks |
| AT-CIT-06 | sim | `CIT-ECON-*`: per-item conservation by principal; farming, extraction, stockpile and attendance bounds hold; newcomer margins, support demand, trade opportunity and contestability tests pass; a planted dominance triggers retuning |
| AT-CIT-07 | sim | `CIT-GENESIS-*`: a Genesis run reaches every exit criterion with no invariant violation |
| AT-CIT-08 | unit | the dialogue check refuses an unsupplied number, an unobserved name, an empty or over-long line; an LLM outage yields templates |
| AT-CIT-09 | human | `CIT-HUMAN-*`: recorded tester sessions rate citizens believable and not dominant |

## 11. Upstream constraints

1. **No killer in `Die`, no killer message sent** (`Die.java:69`; `SystemMessageId.java:7083`, `:7086`). → inference.
2. **Five failed logins ban the address** (`LoginController.java:194-196`). → suspend on the first failure, halt at two.
3. **Client logins have no per-address flood limit** (`FloodProtectorListener.java:100` guards only port 9014). → the
   runner's own admission.
4. **The dualbox whitelist never matches** (`DualboxCheckConfig.java:77`; `AntiFeedManager.java:168-170`). → profiles.md's
   core patch.
5. **No geodata shipped** (`dist/game/data/geodata/`). → a pinned, validated set for both.
6. **Empty or unknown chat kicks** (`Say2.java:114-128`). → dialogue bounded before sending.
7. **`AutoCreateAccounts` is on** (`dist/login/config/Server.ini:77`). → the runner logs in only as provisioned logins.

## 12. Review record

- Revision 1 (draft): not yet reviewed.
