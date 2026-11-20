# SPEC-PLT — Platform

- Status: draft (revision 1)
- Blueprint: 03 §1 (baseline), §2 (toolchain), §6 (client package), §7 (geodata), §9 (deployment), §10 (capacity);
  02 (engineering doctrine), §12 (security); 90 §1, §3, §4 (the coverage rows these answer).
- Depends on: profiles.md (configuration), citizens.md (the runner), chronicle.md (the website and narrator roles),
  retail-truth.md (the lock in every gate), fact-journal.md (the benchmarks).
- DDL: none.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31).

## 1. Scope

What the game runs on and how it is built, deployed and measured: the baseline, the pinned toolchain, the processes and
their database users, the client package, the geodata, the capacity benchmarks, and the parts of the engineering doctrine
that a test can check. It does not own any module's behaviour.

## 2. Data

```text
toolchain.lock                 every external byte the build and tests consume, by digest (exists)
tools/build/Dockerfile          the pinned build image (exists)
deploy/compose.yaml             the hobby stack (§4.3)
deploy/grants/*.sql             one file per database user (§4.3)
client/client.lock              the pinned client build: file hashes
client/patch/                   the .dat patch pipeline's inputs and its pinned tools' digests
geodata/geodata.lock            generator, its digest and settings, correction patches, every region file's hash
bench/<scenario>.yaml           a capacity scenario and its acceptance bands (§4.5)
```

## 3. State machines

None.

## 4. Operations

### 4.1 Baseline

The game is Mobius `L2J_Mobius_CT_2.6_HighFive` (03 §1), Java 27 (upstream's `build.xml` requirement, not the Java 25 an
earlier draft named), MariaDB through `mysql-connector-j`, the protocol revisions upstream accepts
(`dist/game/config/Server.ini:72`). The original blueprint's Kamael-era scope is replaced by this baseline, and three
features it listed do not exist in High Five and are therefore not built: a VIP tier system, Sayune for everyone, custom
depositable items (no configuration or code for them exists in the fork; the PremiumSystem file's "VIP" comment names
the premium system, which profiles.md keeps off).

### 4.2 Toolchain and build

Every external input is pinned by digest in `toolchain.lock` (the JDK and MariaDB images, Ant, the test libraries), and
the build runs with one command, `./build.sh`, inside the pinned image (`tools/build/Dockerfile`), from a clean checkout,
with nothing resolved at build time. The gate it runs is the whole suite: the behaviour lock and the Retail Truth checks
(retail-truth.md), every schema test, the traceability test. No framework beyond the Java standard library, Mobius
commons, plain JDBC and JUnit (02 §7): a test scans the build's dependencies and fails on any other.

### 4.3 Processes, network and database users

The hobby stack (03 §9), one Compose file:

| Process | Database user and what it may read | Network |
|---|---|---|
| `mariadb` | — | private |
| `login` | the login user: the login tables | 2106 shared, 9014 private |
| `game` | the game user: the game and Living Chronicle tables | 7777 shared |
| `runner` | the runner user: the runner's own schema only (citizens.md §2) | private, to login and game |
| `narrator` | `lc_chronicle_narrator` (chronicle.md §2.1) | private |
| `web` | `lc_chronicle_web` (chronicle.md §2.1) | 443 shared |
| `tailscale` | — | the stack's node |

Each user's grants are a file of `deploy/grants/`, tested by connecting as that user (the Chronicle and runner schema tests
already do for theirs). Testers reach the node by a Tailscale node share to at most ten accounts, granted only
`tcp:2106`, `tcp:7777`, `tcp:443`, tested from a real sharee; `ipconfig.xml` advertises the tailnet address to testers and
the private one to the runner (a machine file, profiles.md §4.1). Backups: a nightly MariaDB snapshot with binlogs, a
snapshot before every migration, and a monthly restore drill that checks escrow and contract consistency (the Asset
Service's and PACT's invariants) on the restored copy.

### 4.4 Client package and geodata

- **Client**: one pinned H5 client build, every file's hash in `client/client.lock`; testers receive one archive, the client
  and its `system/` patch. New items (parchments, the Torn Black Seal, the Wayfarer's Journal, the Wanted Poster) come from
  the pinned `.dat` patch pipeline; new NPCs reuse client models through `displayId` and server-side names, with no client
  NPC edits. The client runs on the tester's workstation; nothing authoritative depends on it (02 §12: never trust the
  client; the legacy cipher is not authentication).
- **Geodata**: generated from the pinned client with the pinned generator (03 §7), every input and output hashed in
  `geodata/geodata.lock`; no third-party pack. The server refuses to start if a required region is left a `NullRegion`
  (`gameserver/geoengine/GeoEngine.java:89-92` sets every region to one before loading); the runner loads the same files and
  checks the same hashes (citizens.md §4.4).

### 4.5 Capacity

Measured on the deployment host, never assumed (03 §10), each scenario a file with its acceptance bands: dispersed
farming, dense private-store markets, craft and trade bursts, instance transitions, and the **800-citizen castle siege**,
all under simultaneous CI and LLM load. Measured: scheduler lateness, packet-queue age, combat acknowledgement latency,
pathfinding time, database commit latency, journal lag, resident and mapped memory, disk growth, GC. The modules' budgets
(fact capture 2 µs p99, projector lag 5 s p99, the Asset Service's commit budget, the seams of Wayfarer, Aden Remembers
and Chronicle) are checked in these scenarios. A failing benchmark is fixed by removing the bottleneck or adding
resources, never by cutting a feature.

**No GPU for anything authoritative**: the game, login, runner (but its dialogue) and web need none. The narrator and the
citizens' dialogue use the host's GPU and degrade to templates without it (chronicle.md §4.8, citizens.md §4.8).

### 4.6 Doctrine and security, as checks

- A behaviour change starts with a spec change (02 §2): the traceability test (every requirement reached from the
  blueprint, every test named) and the spec status rule (docs/spec/README.md).
- Every core edit is ledgered (`CORE_PATCHES.md`, checked by `tools/intake/check-core-ledger.sh`).
- Every configuration key has an owner, range, default and test (02 §8; profiles.md's startup check).
- **Absent NCSoft infrastructure** (02 §12; 16 §8–§9): there is no anti-cheat client (the login's `AuthGameGuard` step is a
  formality, `loginserver/network/clientpackets/AuthGameGuard.java:61-95`), no official account system, billing or
  moderation team. So every authoritative fact (location, kills, contract completion, inventory, witness encounters,
  participation, clan membership) comes from server state, admin actions on contracts, bounties, world state, history,
  escrow and traces produce audit facts with before and after and a reason (the Asset Service's, PACT's and the Ledger's
  admin operations), and moderation is the admin tooling plus the Chronicle's letter queue.

## 5. Facts

None of its own.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PLT-INV01 | the build consumes only pinned inputs | AT-PLT-01 |
| PLT-INV02 | each process's database user reads only what §4.3 grants | AT-PLT-02 |
| PLT-INV03 | no required geodata region is missing | AT-PLT-04 |

## 7. Failure model

| Fault | End state |
|---|---|
| a download's digest differs | the build fails |
| a required geodata region missing, or the runner's hashes differ | the server, or the runner, does not start |
| GPU absent | templates instead of narration and dialogue |
| a restore drill finds an escrow or contract inconsistency | an alarm; the backup is not trusted |

## 8. Configuration

The Compose file and the locks; no runtime keys of its own.

## 9. Interface

None.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PLT-R01 | The build MUST run with one command from a clean checkout inside the pinned image, consuming only inputs pinned by digest, and use no framework outside 02 §7's list. | AT-PLT-01 |
| PLT-R02 | Each process MUST connect as its own database user, granted only §4.3's access. | AT-PLT-02 |
| PLT-R03 | Testers MUST reach only the three shared ports. | AT-PLT-03 |
| PLT-R04 | The server and the runner MUST start only with every required geodata region present and matching its pinned hash. | AT-PLT-04 |
| PLT-R05 | The client package MUST be the pinned build plus the pinned patch, with no client NPC edits. | AT-PLT-05 |
| PLT-R06 | Every capacity scenario MUST stay within its acceptance bands under simultaneous CI and LLM load. | AT-PLT-06 |
| PLT-R07 | Nothing authoritative MUST need a GPU. | AT-PLT-07 |
| PLT-R08 | Backups MUST be restorable, checked monthly against the escrow and contract invariants. | AT-PLT-08 |
| PLT-R09 | The features absent from High Five MUST NOT be built: no VIP tier, Sayune for everyone or custom depositable items. | AT-PLT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-PLT-01 | integration | a clean checkout builds with `./build.sh` in the pinned image with the network limited to the locked URLs; a changed digest fails; the dependency scan finds only allowed libraries |
| AT-PLT-02 | integration | connected as each user of `deploy/grants/`: every table outside its grant refused |
| AT-PLT-03 | human | from a real Tailscale sharee: 2106, 7777 and 443 open, every other port closed |
| AT-PLT-04 | integration | a missing required region stops the server; a runner with a differing hash does not start |
| AT-PLT-05 | unit | the client archive's hashes equal `client.lock`; no NPC client file is patched |
| AT-PLT-06 | sim | each `bench/` scenario within its bands, the 800-citizen siege included |
| AT-PLT-07 | integration | the stack without a GPU: every module runs; narration and dialogue fall back to templates |
| AT-PLT-08 | integration | a restore of a nightly snapshot passes the Asset Service's and PACT's invariant checks |
| AT-PLT-09 | unit | no configuration key, class or datapack file of a VIP tier, Sayune for everyone or custom depositable items exists |

## 11. Upstream constraints

1. **Java 27 is upstream's requirement** (`build.xml`). → the pinned JDK 27 image.
2. **No geodata ships, and missing regions silently become `NullRegion`** (`GeoEngine.java:89-92`). → generated, hashed,
   and a refusal to start.
3. **`AuthGameGuard` is a formality** (`AuthGameGuard.java:61-95`). → nothing trusts the client.

## 12. Review record

- Revision 1 (draft): not yet reviewed.
