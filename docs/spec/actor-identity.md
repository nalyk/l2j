# SPEC-ACTOR — Actor Identity

- Status: draft (revision 1)
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
| `account_id` | int | `lc_account`, keyed by the login (`accounts.login`, `dist/db_installer/sql/login/accounts.sql`; a character's `account_name`, `dist/db_installer/sql/game/characters.sql:2`) | never for a character |
| `char_id` / `npc_id` | int | the character's object id / the NPC's template id | never |
| `activity` | `INTERACTIVE`, `OFFLINE_TRADE`, `OFFLINE_PLAY`, `DISCONNECTED` | derived (§2.3) | by recorded transitions only (`Actor.ActivityChanged`) |
| `sim_profile` | `RESIDENT`, `SCENARIO_ADVERSARY`, `TEST`; none for humans | `lc_account.sim_profile` | by an admin, with an audit fact |
| `principal_id` | int | `lc_account.principal_id`: a synthetic economic principal (a citizen household) for citizens; the account's own principal for humans | by an admin, with an audit fact |
| `class_id`, `level`, `clan_id`, `ally_id` | ints | the character at the moment of the fact | as the game changes them |

### 2.2 Tables

```text
lc_account(account_id INT PK auto, login VARCHAR(45) UNIQUE, origin {HUMAN|SYNTHETIC_CITIZEN}, sim_profile {RESIDENT|
           SCENARIO_ADVERSARY|TEST} NULL, principal_id INT, created_at TIMESTAMP(3), provisioned_by VARCHAR(32))
lc_principal(principal_id INT PK auto, kind {HUMAN_ACCOUNT|CITIZEN_HOUSEHOLD}, name VARCHAR(64), created_at TIMESTAMP(3))
lc_actor_policy(module VARCHAR(16), rule VARCHAR(48), origin {HUMAN|SYNTHETIC_CITIZEN|NPC_FAKE|NPC|SYSTEM},
                activity {INTERACTIVE|OFFLINE_TRADE|OFFLINE_PLAY|DISCONNECTED|ANY}, allowed BOOLEAN, config_ver INT,
                PRIMARY KEY(module, rule, origin, activity))
```

- **Origin is assigned by the server and never changes.** A citizen's account is provisioned by the admin tool (`lc citizen
  provision`), which inserts its `lc_account` row with `SYNTHETIC_CITIZEN` before the account's first login. Any account
  seen at login without a row gets one with `HUMAN`, in the login's own transaction. A database trigger refuses any
  `UPDATE` that changes `origin` or `login`, and any `DELETE` (INV01). Nothing the client sends reaches these columns
  (02 §12).
- `sim_profile` is set only for citizens; a human has none (INV02).
- `lc_actor_policy` is configuration data: a change is a new `config_ver` (fact-journal.md §2.2, §4.1), so a replay reads
  the policy that was in force.

### 2.3 Activity

Derived from Mobius's own state of an online character (`entity/actor/Player.java`):

| Activity | When |
|---|---|
| `INTERACTIVE` | the character has a client (`isInOfflineMode()` is false, :8003) |
| `OFFLINE_TRADE` | no client, and in a private store (`isInStoreMode()`, :1335) |
| `OFFLINE_PLAY` | no client, and offline play (`isOfflinePlay()`, :7976); disabled in every profile but kept as a value so a fact can say so |
| `DISCONNECTED` | no client otherwise (the character lingers in the world after its connection went) |

Each change is captured as `Actor.ActivityChanged` at the points that change it: entering the world (`Player.onPlayerEnter`,
:11049), a client detaching, a store opening or closing, logout (`Player.deleteMe`, :11766).

### 2.4 The snapshot

`ActorSnapshot` is a record of primitives only: origin, account id, char id or npc id, activity, sim profile, principal id,
class id, level, clan id, ally id. It is built at the owning operation from fields the service caches on the actor when it
enters the world (origin, account id, sim profile, principal id are looked up once at login and kept in a primitive map
keyed by object id), so building it allocates nothing and reads nothing from the database (fact-journal.md §3.2). In
`lc_fact.actor_snapshot` it is canonical JSON with fixed keys.

## 3. Policy, the only place actors are told apart

Game logic never branches on origin (02 §3). A module that must treat actors differently (an `NPC_FAKE` never pays or
receives a Ledger contract; an offline trader counts as market supply, never as presence; Signal reports human, synthetic
and mixed separately; history can filter to humans only) asks one function: `Policy.allows(module, rule, snapshot)`,
which reads `lc_actor_policy` (the most specific row: exact activity before `ANY`; no row means not allowed). Only the
policy package reads a snapshot's origin or activity. A static test scans every Living Chronicle class and fails on any
other read of them (AT-ACTOR-05).

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
| ACTOR-INV01 | an account's origin and login never change and its row is never deleted | by the database trigger; at startup a digest of (login, origin) is compared with the last recorded one |
| ACTOR-INV02 | a human has no sim profile; a citizen has one | by a check constraint |
| ACTOR-INV03 | every character's account has a row before the character enters the world | at login (refused otherwise) |
| ACTOR-INV04 | outside the policy package no Living Chronicle code reads a snapshot's origin or activity | by the static test (AT-ACTOR-05) |
| ACTOR-INV05 | a snapshot holds only primitives | by reflection (AT-ACTOR-06) |

## 7. Failure model

| Fault | End state |
|---|---|
| database down at login | the login fails as Mobius's own login does without its database; no character enters without its account row |
| an attempt to change an origin (admin error, SQL) | refused by the trigger; alarm |
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
| ACTOR-R04 | Activity MUST follow §2.3 and change only by recorded transitions. | AT-ACTOR-04 |
| ACTOR-R05 | Living Chronicle code MUST NOT branch on origin or activity except through `Policy.allows`. | AT-ACTOR-05 |
| ACTOR-R06 | A snapshot MUST be built without allocation or database access. | AT-ACTOR-06 |
| ACTOR-R07 | A missing policy row MUST mean not allowed. | AT-ACTOR-07 |
| ACTOR-R08 | Profile and principal changes MUST be admin-only, for citizens only, with an audit fact. | AT-ACTOR-00, AT-ACTOR-08 |

| Test | Kind | What it does |
|---|---|---|
| AT-ACTOR-00 | integration | the migration applies; the trigger refuses changing an origin or login and deleting a row; a human with a sim profile is refused |
| AT-ACTOR-01 | integration | a first login creates a `HUMAN` row in its transaction; a provisioned citizen logs in as `SYNTHETIC_CITIZEN` |
| AT-ACTOR-02 | integration | every client packet that names a character or account, fuzzed: no column of §2.2 changes |
| AT-ACTOR-03 | integration | facts captured at each owning operation carry the snapshot of that moment (class, level and clan changed right after) |
| AT-ACTOR-04 | integration | entering, detaching, opening and closing a store, offline play and logout give exactly the transitions of §2.3 |
| AT-ACTOR-05 | unit | the static scan of every Living Chronicle class finds no read of origin or activity outside the policy package; a planted read fails it |
| AT-ACTOR-06 | unit | snapshot building allocates nothing (allocation counter) and touches no connection; the record holds only primitives |
| AT-ACTOR-07 | unit | a rule with no row is not allowed; the exact-activity row wins over `ANY` |
| AT-ACTOR-08 | integration | profile and principal changes refused for humans and for non-admins; each accepted one writes its audit fact |

## 11. Upstream constraints

1. **Accounts live in the login schema** keyed by a 45-character login (`accounts.login`); characters name theirs by
   `account_name`. → an int `account_id` for snapshots, assigned at first sight (§2.2).
2. **Fake players are NPCs** (`entity/WorldObject.java:418`, `entity/actor/Npc.java:167`, :2048): they cannot be accounts,
   trade or sign. → `NPC_FAKE` from the template.
3. **Offline modes are flags on a live `Player`** (`isInOfflineMode`, `isInStoreMode`, `isOfflinePlay`, Player.java:8003,
   :1335, :7976). → activity is derived, and its changes captured (§2.3).

## 12. Review record

None yet.
