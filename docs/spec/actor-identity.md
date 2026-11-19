# SPEC-ACTOR — Actor Identity

- Status: draft (revision 2, answering the first adversarial review; record in §12)
- Blueprint: 04 §2 (Actor Identity); 02 §3 (game logic never branches on "is this a citizen"), §12 (never trust the client);
  10 (Citizens of Aden); 13 §… (Signal reports human / synthetic / mixed); 17 (history's humans-only filter); consensus §3.
- DDL: `sql/migrations/V007__lc_actor.sql` (after the Fact Journal, before the Asset Service).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

Every fact carries an immutable snapshot of who acted (04 §2). This spec owns what that snapshot holds, where each
dimension comes from, how origin is assigned and kept immutable, and the one mechanism by which a module may treat actors
differently: policy tables. It does not own what a module's policy says (each module spec lists its rules), the citizen
runner (N9/N10), nor authentication (the LoginServer, unchanged).

## 2. Data

### 2.1 Dimensions

| Dimension | Values | Source | Changes |
|---|---|---|---|
| `origin` | `HUMAN`, `SYNTHETIC_CITIZEN`, `NPC_FAKE`, `NPC`, `SYSTEM` | players: `lc_account.origin`; NPCs: `Npc.isFakePlayer()` (`entity/actor/Npc.java:2048`, from the template) gives `NPC_FAKE`, any other NPC `NPC`; the server acting on its own: `SYSTEM` | never |
| `account_id` | int | `lc_character`: the character's binding to its account, made when the character is first seen and never changed (a character's `account_name`, `dist/db_installer/sql/game/characters.sql:2`, must keep naming that account's login) | never for a character |
| `char_id` / `npc_id` | int | the character's object id / the NPC's template id | never |
| `activity` | `INTERACTIVE`, `OFFLINE_TRADE`, `OFFLINE_PLAY`, `DISCONNECTED` | derived (§2.3) | by recorded transitions only (`Actor.ActivityChanged`) |
| `sim_profile` | `RESIDENT`, `SCENARIO_ADVERSARY`, `TEST`; none for humans | `lc_account.sim_profile` | by an admin, with an audit fact |
| `principal_id` | int | `lc_account.principal_id`: a synthetic economic principal (a citizen household) for citizens; the account's own principal for humans | by an admin, with an audit fact |
| `class_id`, `level`, `clan_id`, `ally_id` | ints | the character at the moment of the fact | as the game changes them |

### 2.2 Tables

```text
lc_account(account_id INT PK auto, login VARCHAR(45) UNIQUE, origin {HUMAN|SYNTHETIC_CITIZEN}, sim_profile {RESIDENT|
           SCENARIO_ADVERSARY|TEST} NULL, principal_id INT, created_at TIMESTAMP(3), provisioned_by VARCHAR(32),
           retired BOOLEAN)
lc_character(char_id INT PK, account_id INT, bound_at TIMESTAMP(3))
lc_identity_audit(audit_id BIGINT PK auto, account_id INT, field {SIM_PROFILE|PRINCIPAL}, before_value VARCHAR(32) NULL,
                  after_value VARCHAR(32), admin VARCHAR(32), reason VARCHAR(128), at TIMESTAMP(3))
lc_principal(principal_id INT PK auto, kind {HUMAN_ACCOUNT|CITIZEN_HOUSEHOLD}, name VARCHAR(64), created_at TIMESTAMP(3))
lc_actor_policy(module VARCHAR(16), rule VARCHAR(48), domain {PARTICIPATION|METRICS|PUBLICATION},
                origin {HUMAN|SYNTHETIC_CITIZEN|NPC_FAKE|NPC|SYSTEM},
                activity {INTERACTIVE|OFFLINE_TRADE|OFFLINE_PLAY|DISCONNECTED|ANY}, allowed BOOLEAN, config_ver INT,
                PRIMARY KEY(module, rule, origin, activity))
```

- **Origin is assigned by the server and never changes.** A citizen's account is provisioned by the admin tool (`lc citizen
  provision`), which creates the login and its `lc_account` row with `SYNTHETIC_CITIZEN` before the account's first login.
  Any account seen without a row gets one with `HUMAN` at the **authenticated** boundary: when the LoginServer's
  `PlayerAuthResponse` for it arrives at the GameServer (`gameserver/LoginServerThread.java:311-313`), never earlier (the
  client's own `AuthLogin` names an account before it is authenticated, `network/clientpackets/AuthLogin.java:65`).
  `lc_account` lives in the GameServer's schema; the LoginServer, unchanged, never reads it.
- **Database triggers** refuse any `UPDATE` that changes `account_id`, `login` or `origin`; any `UPDATE` of `sim_profile`
  or `principal_id` unless the session carries the id of an `lc_identity_audit` row written in the same transaction (the
  service's admin operation sets it; a SQL tool cannot without writing the audit); any `DELETE` of an account; any change
  of a character's binding; a human account whose principal is not its own `HUMAN_ACCOUNT` principal, or a citizen whose
  principal is not a `CITIZEN_HOUSEHOLD` (INV01, INV02). Nothing the client sends reaches these tables (02 §12).
- **A login is never reused.** Mobius's account tool deletes logins directly (`tools/AccountManager.java:1080, 1469`);
  a core patch makes it refuse a login that has an `lc_account` row unless the row is `retired` by `lc citizen retire`,
  and a retired login is refused at the authenticated boundary for ever. So a citizen's login can never become a human's,
  nor the reverse.
- **Startup checks** that the triggers exist and are the migration's own (their definitions in `information_schema`
  compared with the expected text); otherwise the module refuses to start (§7).
- `sim_profile` is set only for citizens; a human has none (INV02).
- `lc_actor_policy` is configuration data: a change is a new `config_ver` (fact-journal.md §2.2, §4.1), so a replay reads
  the policy that was in force.

### 2.3 Activity

Derived from Mobius's own state of an online character (`entity/actor/Player.java`):

| Activity | When |
|---|---|
| `INTERACTIVE` | `isInOfflineMode()` is false: a client attached and not detached (:8003) |
| `OFFLINE_PLAY` | `isInOfflineMode()` (no client, or a detached one, as offline modes detach it: `OfflineTraderTable.java:559`, `Player.java:7968`) and `isOfflinePlay()` (:7976); disabled in every profile but kept as a value |
| `OFFLINE_TRADE` | `isInOfflineMode()`, not offline play, and in a private store (`isInStoreMode()`, :1335) |
| `DISCONNECTED` | `isInOfflineMode()` otherwise (the character lingers in the world after its connection went) |

Rows are tried in this order; the first that holds is the activity.

A snapshot's activity is derived from this live state at the owning operation, so it is never stale and never depends on a
transition fact having been written. Changes are also captured as `Actor.ActivityChanged` (entering the world, a client
detaching, a store opening or closing, logout), at most once like every captured fact (fact-journal.md §3.2); a consumer
may count them, never reconstruct activity from them alone.

### 2.4 The snapshot

The snapshot is ten primitives: origin, account id, char id or npc id, activity, sim profile, principal id, class id,
level, clan id, ally id. On a hot path it is never an object: `snapshot(actor, slot)` writes the ten fields straight into
the capture's ring slot (fact-journal.md §3.2); off the hot path (an outbox fact) a record may be built. The cached parts
(origin, account id, sim profile, principal id) are loaded when a `Player` is loaded, by whatever path
(`Player.load`, `entity/actor/Player.java:1211`: a login, an offline trader restored at startup,
`OfflineTraderTable.java:232`, an offline player restored), into a primitive map keyed by object id; a character whose
binding or account row is missing is not loaded. An admin change of sim profile or principal updates the cached parts
of an online character after its transaction commits, so every later fact names the new values. In
`lc_fact.actor_snapshot` it is canonical JSON with fixed keys.

## 3. Policy, the only place actors are told apart

Game logic never branches on origin (02 §3). Policy rules are of three domains only: **participation** (who may be a party
to an institution), **metrics** (who counts in a measure) and **publication** (what history may show); there is no
mechanics domain, and the registry refuses a rule outside the three. A module that must treat actors differently (an `NPC_FAKE` never pays or
receives a Ledger contract; an offline trader counts as market supply, never as presence; Signal reports human, synthetic
and mixed separately; history can filter to humans only) asks one function: `Policy.allows(module, rule, snapshot)`,
which reads `lc_actor_policy` (the most specific row: exact activity before `ANY`; no row means not allowed). Only the
policy package reads a snapshot's origin or activity, `lc_account.origin` or `sim_profile`. A static test scans every
Living Chronicle class and every datapack script and fails on any other read of them, in code or in SQL strings, and on any
call to `Policy.allows` from Mobius's combat, skill, drop, loot, craft or movement code (AT-ACTOR-05).

**Origin is never shown in the world.** No packet a player receives about another character differs because of its
origin: no overhead marker (10 §6), no label in Signal's candidates (13 §2); only aggregate reports and history's
humans-only filter use it (AT-ACTOR-09).

## 4. Operations

| Operation | Effect | Facts |
|---|---|---|
| `provision(login, profile, principal)` | admin: a citizen account row, before its first login | `Actor.CitizenProvisioned` |
| `ensure(login)` | at login: the account's row, `HUMAN` if absent | `Actor.AccountSeen` (first time only) |
| `setProfile(account, profile)`, `setPrincipal(account, principal)` | admin, citizens only, with reason | `Actor.ProfileChanged`, `Actor.PrincipalChanged` |
| `snapshot(actor)` | the record of §2.4 | — |
| `allows(module, rule, snapshot)` | the policy answer | — |

## 5. Facts

| Fact | Delivery | Fields |
|---|---|---|
| `Actor.CitizenProvisioned` | outbox | account, profile, principal, admin |
| `Actor.AccountSeen` | outbox | account, origin |
| `Actor.ActivityChanged` | captured (`cap.activity`) | snapshot, from, to |
| `Actor.ProfileChanged`, `Actor.PrincipalChanged` | outbox | account, before, after, admin, reason |
| `Actor.PolicyChanged` | outbox (`config`) | module, rules, config version |

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| ACTOR-INV01 | an account's id, login and origin never change, its row is never deleted, a character's binding never changes, and a profile or principal changes only with its audit row | by the database triggers, verified present and unaltered at startup |
| ACTOR-INV02 | a human has no sim profile and its own human principal; a citizen has a profile and a household principal | by a check constraint and the triggers |
| ACTOR-INV03 | every loaded character has its binding and its account row, and its `account_name` still names the bound login | when a `Player` is loaded (refused otherwise) |
| ACTOR-INV04 | outside the policy package no Living Chronicle code reads a snapshot's origin or activity | by the static test (AT-ACTOR-05) |
| ACTOR-INV05 | a snapshot holds only primitives | by reflection (AT-ACTOR-06) |

## 7. Failure model

| Fault | End state |
|---|---|
| database down at login | the login fails as Mobius's own login does without its database; no character enters without its account row |
| an attempt to change an origin, a binding, an id, or a profile without its audit (admin error, SQL) | refused by the trigger; alarm |
| triggers missing or altered (a restore, a failover, a hand edit) | the module refuses to start until they are the migration's own again |
| a login deleted and recreated outside the service | refused by the patched account tool; a retired login refused at login |
| a character's `account_name` changed outside the service | the character is not loaded; alarm |
| a policy row missing | not allowed: the module's rule fails closed |

## 8. Configuration

None of its own: policy rows are module configuration, versioned (§2.2). Nothing here changes a game mechanic.

## 9. Interface

None for players. Admin: `lc citizen provision <login> <profile> <principal>`, `lc actor <char>` (prints the snapshot and
the account row), `lc policy <module>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| ACTOR-R01 | Origin MUST be assigned by the server, before or at an account's first login, and MUST never change. | AT-ACTOR-00, AT-ACTOR-01 |
| ACTOR-R02 | Nothing the client sends MUST be able to set or change origin, account, sim profile or principal. | AT-ACTOR-02 |
| ACTOR-R03 | Every fact MUST carry the snapshot of its actor as it was at the owning operation. | AT-ACTOR-03 |
| ACTOR-R04 | A snapshot's activity MUST be derived from live state by the ordered rules of §2.3, detached clients included. | AT-ACTOR-04 |
| ACTOR-R05 | Living Chronicle code MUST NOT branch on origin or activity except through `Policy.allows`. | AT-ACTOR-05 |
| ACTOR-R06 | A snapshot MUST be built without allocation or database access. | AT-ACTOR-06 |
| ACTOR-R07 | A missing policy row MUST mean not allowed. | AT-ACTOR-07 |
| ACTOR-R08 | Profile and principal changes MUST be admin-only, for citizens only, with an audit row in their transaction, and MUST reach an online character's later snapshots. | AT-ACTOR-00, AT-ACTOR-08 |
| ACTOR-R09 | Origin MUST NOT be shown in the world: packets about a character MUST NOT differ by origin. | AT-ACTOR-09 |
| ACTOR-R10 | A character's account binding and a login MUST never change or be reused; identity MUST be initialised on every path that loads a character. | AT-ACTOR-00, AT-ACTOR-10 |

| Test | Kind | What it does |
|---|---|---|
| AT-ACTOR-00 | integration | the migration applies; the triggers refuse changing an id, origin, login or binding, deleting an account, a profile change without its audit row, and a principal of the wrong kind; startup refuses missing or altered triggers |
| AT-ACTOR-01 | integration | an authenticated first login creates a `HUMAN` row; an `AuthLogin` never authenticated creates none; a provisioned citizen logs in as `SYNTHETIC_CITIZEN` |
| AT-ACTOR-02 | integration | every client packet that names a character or account, fuzzed: no column of §2.2 changes |
| AT-ACTOR-03 | integration | facts captured at each owning operation carry the snapshot of that moment (class, level and clan changed right after) |
| AT-ACTOR-04 | integration | a client attached, detached for offline trade, detached for offline play, detached with neither, and gone: each snapshot's activity by the ordered rules; a lost transition fact changes no snapshot |
| AT-ACTOR-05 | unit | the static scan of every Living Chronicle class and datapack script finds no read of origin, activity or sim profile outside the policy package, in code or SQL, and no `Policy.allows` in combat, skill, drop, loot, craft or movement code; planted reads and calls fail it; a rule of an unknown domain is refused |
| AT-ACTOR-06 | unit | writing a snapshot into a ring slot allocates nothing (allocation counter) and touches no connection |
| AT-ACTOR-07 | unit | a rule with no row is not allowed; the exact-activity row wins over `ANY` |
| AT-ACTOR-08 | integration | profile and principal changes refused for humans and for non-admins; each accepted one writes its audit row and fact; an online citizen's next fact names the new values |
| AT-ACTOR-09 | integration | a human and a citizen in identical state: every packet a third player receives about each is byte-for-byte equal but for their ids and names |
| AT-ACTOR-10 | integration, restart | an offline trader restored at startup has its identity; a character whose `account_name` was changed is not loaded; the account tool refuses deleting a bound login; a retired login is refused |

## 11. Upstream constraints

1. **Accounts live in the login schema** keyed by a 45-character login (`accounts.login`); characters name theirs by
   `account_name`. → an int `account_id` for snapshots, assigned at first sight (§2.2).
2. **Fake players are NPCs** (`entity/WorldObject.java:418`, `entity/actor/Npc.java:167`, :2048): they cannot be accounts,
   trade or sign. → `NPC_FAKE` from the template.
3. **Offline modes are flags on a live `Player`** (`isInOfflineMode`, `isInStoreMode`, `isOfflinePlay`, Player.java:8003,
   :1335, :7976), and `isInOfflineMode` is also true for a detached client. → activity is derived by ordered rules (§2.3).
4. **Authentication completes at the LoginServer's response** (`LoginServerThread.java:311-313`), after the client's
   `AuthLogin` named the account (`AuthLogin.java:65`). → identity is created only at the response.
5. **Characters load by one path whatever brought them** (`Player.load`, `Player.java:1211`; offline traders restored
   at startup, `OfflineTraderTable.java:232`). → identity is initialised there.
6. **Mobius's account tool deletes logins directly** (`tools/AccountManager.java:1080, 1469`). Core patch: it refuses a
   login bound to an `lc_account` row unless retired.

## 12. Review record

- Round 1 (Codex), NO SIGN-OFF, 13 findings: a snapshot record that allocates; a mutable account id; no persistent
  character binding; logins deletable and recreatable by Mobius's account tool; `ensure` placed before authentication
  and across schemas; detached clients outside every activity rule; offline traders restored without identity; stale
  cached profile and principal; direct SQL bypassing audit and principal kinds; a policy rule usable for mechanics; no
  rule against showing origin in the world; activity resting on lossy transition facts; no check that the triggers
  survive a restore. Revision 2 answers each (§2.1-§2.4, §3, §6-§7, §10-§11).
