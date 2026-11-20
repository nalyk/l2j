# SPEC-PLT — Platform

- Status: draft (revision 2, answering review round 1; record in §12)
- Blueprint: 03 §1 (baseline), §2 (toolchain), §6 (client package), §7 (geodata), §9 (deployment), §10 (capacity);
  02 (engineering doctrine), §12 (security); 90 §1, §3, §4 (the coverage rows these answer).
- Depends on: profiles.md (configuration, machine files, the startup check), citizens.md (the runner, its schema and
  navigation), chronicle.md (the website, its narrator worker and their roles), retail-truth.md (the case runner and the
  lock's change rule in the gate), fact-journal.md (database users, expiring data, the maintenance job, the benchmarks),
  intel-service.md (the expiring-table registry and its purge), actor-identity.md (provisioning), asset-service.md and
  pact.md (the invariants a restore is checked against).
- DDL: none.
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); the fork's own
  build files (`build.sh`, `build-lc.xml`, `toolchain.lock`, `tools/`) are cited at this branch.
- **Exists** and **to build** are kept apart throughout: a sentence about what the repository does today cites the file
  that does it; everything else is a requirement on future work, with the change named.

## 1. Scope

What the game runs on and how it is built, deployed and measured: the baseline, the pinned toolchain and the gate, the
dependency rule, the processes, their network and their database users, backups, the client package, the geodata, the
capacity benchmarks, and where each part of the engineering doctrine is checked. It owns no module's behaviour, no table
and no game rule.

## 2. Data

```text
toolchain.lock                  exists   every downloaded build and test input, by digest (images, Ant, test-only jars)
dist/libs/*.jar                 exists   upstream's vendored runtime libraries, pinned by the commit (toolchain.lock:11)
tools/build/Dockerfile          exists   the build image, assembled from toolchain.lock by Provision.java
tools/intake/check-core-ledger.sh exists the core ledger's file-set check
deploy/compose.yaml             to build the hobby stack (§4.3)
deploy/grants/<user>.sql        to build one file per database user of §4.3, applied after the migrations
deploy/backup/                  to build the backup, retention and restore procedures (§4.7)
client/client.lock              to build base client file hashes, patch inputs, tool digests, the built archive's file hashes
client/patch/                   to build the .dat patch pipeline's inputs
geodata/geodata.lock            to build generator digest and settings, correction patches, the required-region manifest
geodata/probes.csv              to build the geometry probes and their evidenced expectations (§4.4)
bench/<scenario>.yaml           to build one capacity scenario, at least §4.5's minimum workload and bands
```

## 3. State machines

None.

## 4. Operations

### 4.1 Baseline and absent features

The game is Mobius `L2J_Mobius_CT_2.6_HighFive` (03 §1), Java 27 (upstream's `build.xml` requirement, not the Java 25 an
earlier draft named), MariaDB through `mysql-connector-j`, the protocol revisions upstream accepts
(`dist/game/config/Server.ini:72`). The original blueprint's Kamael-era scope is replaced by this baseline.

Three features the old blueprint listed do not exist in High Five and are not built (90 §1). Each is defined by a
signature, so a test can find it however it is named:

| Feature | Signature (any one is the feature) | What upstream already has, and is not it |
|---|---|---|
| VIP tier | a configuration file or key, class, enum, packet, table or account/character variable carrying a tier or level of paid or timed benefits; any reward or rate multiplier keyed by an account's tier | the premium system's header comment `Premium System (VIP)` (`dist/game/config/Custom/PremiumSystem.ini:2`, kept off by profiles.md); a comment in `dist/game/config/Rates.ini:225`; the unused `AccountVariables.deleteVipPurchases` (`gameserver/mechanics/variables/AccountVariables.java:244`) |
| Sayune for everyone | a client or server packet, handler, skill or datapack file for jump tracks (Sayune / fly-move) | nothing: no file of the fork names Sayune or a fly-move |
| Custom depositable items | a configuration key that changes what may be deposited, or an item whose `is_depositable` (`gameserver/entity/item/ItemTemplate.java:157`) differs from the upstream item data | the retail per-item `is_depositable` flag and `Item.isDepositable` (`entity/item/instance/Item.java:838`) |

### 4.2 Toolchain, build and gate

**What exists.** `./build.sh` is the one command (build.sh:1-9). It assembles a build image from `toolchain.lock`,
`tools/build/Dockerfile` and `tools/build/Provision.java`, which downloads every `file` and `jar` line of the lock and
refuses any byte whose digest differs (`Provision.java:16`, `:128`); the base JDK image is pulled by digest
(build.sh:15-20, :30). It then runs Ant's `gate` target (build-lc.xml:132: `dist`, which fingerprints the compiled classes,
then `test`, every `*Test` class in its own JVM) with the checkout mounted read-only (build.sh:45-56).

**Inputs** come in three classes, and only these:

1. **Upstream runtime libraries**: exactly upstream's `dist/libs` at the upstream snapshot — HikariCP 7.0.2,
   mysql-connector-j 9.5.0, slf4j-api 2.0.17, slf4j-simple 2.0.17 and their source jars. Vendored and pinned by the commit,
   not by the lock (toolchain.lock:11); they change only through upstream intake (03 §3). They form the server's runtime
   classpath (`build.xml` `classpath`, from `${datapack}/libs`), and are the only libraries the runner and the web process
   may use besides the JDK (the website uses the JDK's HTTP server, chronicle.md §4.9).
2. **Test infrastructure**: the lock's `jar` lines (JUnit Jupiter and Platform 6.1.3, Testcontainers 2.0.5 and the closure
   `tools/build/relock-test-libs.sh` resolves for it: docker-java, JNA, commons-*, Jackson annotations, duct-tape and the
   annotation jars, toolchain.lock:20-41) and its images (the JDK, MariaDB, Ryuk; Maven, used only by the relock script).
   They live in `/opt/lc/test-libs` and are on the test classpath only (build-lc.xml:19, :28-33), never in `dist` or a
   runtime classpath.
3. **Anything else is a new dependency** and is forbidden (02 §7: no Spring, no ORM, no DI container, no message broker).
   A new runtime library or framework needs a revision of this section first, with the reason no JDK API serves.

**Reproducible** means: two clean builds of one commit in the pinned image print the same classes fingerprint
(build-lc.xml:35-46, `build/classes.sha256`). The packaged jars are not byte-identical and are not claimed to be:
upstream's `jar` target stamps `Build-By` and `Build-Date` into every manifest (`build.xml:85-86`, `:100-101`, `:117-118`),
and patching that is not worth a core patch. The fingerprint is the artifact oracle.

**Trust boundary of the image.** build.sh names the image after a digest of its three inputs and reuses any local image
carrying that tag (build.sh:22-31). The guarantee "only pinned inputs" therefore holds for an image build.sh assembled; a
local image retagged by hand is outside it. That substitution needs control of the Docker daemon, which is root on the
host and which the build container is given anyway (build.sh:51), so the local daemon is trusted, not verified. To build:
the **merge gate** (the run that admits a commit to `main`, and every intake run) removes the tag and assembles the image
afresh, so the path that verifies digests is the one that runs where it matters.

**The gate today** is: the core-ledger check when an `upstream` branch is present (build.sh:33-39), the class fingerprint,
and every test class: the behaviour-lock goldens (`*LockTest`, `test/lock/`), the schema tests (`*SchemaTest`), the H5
content inventory (`InventoryTest`), `SpecTraceabilityTest`, and the clock, randomness and boot tests. **To build, and join
the gate when they exist**: Retail Truth's case runner `TruthCaseTest` and `tools/truth/check-statuses.sh` /
`check-lock-changes.sh` (retail-truth.md AT-RT-01..03; none exists yet), the dependency check (AT-PLT-01), the geodata and
client checks (AT-PLT-04, -05). A spec cannot reach `implemented` while one of its tests is outside the gate.

**The core ledger.** What exists: `check-core-ledger.sh` fails unless the set of upstream files the working tree changes
equals the set of `Files:` paths in `CORE_PATCHES.md` (check-core-ledger.sh:25-33); build.sh skips it, with a warning,
when the clone has no local `upstream` branch (build.sh:34-39), and it does not read an entry's other fields. To build:
(a) a gate run with `LC_GATE=1` (the merge gate and intake set it) fails without an `upstream` branch instead of skipping;
a plain clone's `./build.sh` may still warn and skip, and its result is not a gate pass; (b) the check also fails an
entry whose `Reason:`, `Invariant:` or `Proof:` line is missing or empty, or whose `Proof:` names a test class that does
not exist under `test/java`.

### 4.3 Processes, network and database users

The hobby stack (03 §9) is one Compose file, `deploy/compose.yaml` (to build).

| Process | Network | Database user(s) |
|---|---|---|
| `mariadb` | `lc_private` only; listens on its `lc_private` address, never a wildcard | — |
| `tailscale` | the stack's tailnet node, also on `lc_private` | — |
| `login` | in `tailscale`'s network namespace: 2106 shared; 9014 (game link) on loopback only | `lc_login` |
| `game` | in `tailscale`'s network namespace: 7777 shared | `lc_game`, `lc_intel`, `lc_maint` (three pools) |
| `web` | in `tailscale`'s network namespace: 443 shared | `lc_web` (HTTP server), `lc_narrator` (narrator worker) |
| `runner` | `lc_private` only; reaches login and game through `tailscale`'s `lc_private` address | `lc_runner`: database `lc_runner` only, exactly citizens.md §2's grants (`deploy/grants/runner.sql` equals them) |
| `governor` (admin tool, run by hand) | `lc_private` only; never in the runner's container | `lc_governor`: read-only on `lc_telemetry_bucket`, `lc_journal_gap`, `clan_data`, `territories` and the Ledger and Chronicle publication views; it writes only the caps file an admin imports (citizens.md §4.10) |
| `narrator` | `lc_private` only: the local LLM (ollama on the RTX 5090), no state, no tools | none |
| `backup` | `lc_private` only | `lc_backup` |

**Network.** No service has a `ports:` entry: nothing of the stack is published on the host's LAN or public interfaces.
The three shared listeners are reachable only through the tailnet node. Testers reach it by a Tailscale node share to at
most ten accounts, and the tailnet policy grants `autogroup:shared` that node on `tcp:2106`, `tcp:7777`, `tcp:443` only;
no policy line grants anyone 3306 or 9014. `ipconfig.xml` advertises the tailnet address to testers and the
`lc_private` one to the runner (a machine file, profiles.md §2, §4.1). The `narrator` service is called only by the web's
narrator worker (chronicle.md §4.8) and the runner's dialogue (citizens.md §4.8), with a DTO; it has no database user.

**Database users.** Login and game share Mobius's one schema (both `Database.ini` files name `l2jmobiush5`); the Living
Chronicle tables live beside the stock ones and the runner's in its own schema (citizens.md §2). Every user below has
exactly the privileges listed and those the spec that owns a table gives that user; a table no spec grants to a user is
refused to it, and a table whose owning spec names no user is the game server's (`lc_game`). No running process holds
`lc_owner`'s or root's credentials.

| User | Used by | Privileges |
|---|---|---|
| `lc_login` | `login`; upstream's `AccountManager` run by the operator | `SELECT, INSERT, UPDATE, DELETE` on the stock login tables (`dist/db_installer/sql/login/`: `accounts`, `account_data`, `accounts_ipauth`, `gameservers`); nothing else |
| `lc_game` | `game`: Mobius and every Living Chronicle module, except the two pools below | `SELECT, INSERT, UPDATE, DELETE` on the stock game tables (`dist/db_installer/sql/game/`); `SELECT, INSERT` on `accounts` only of the login tables (actor-identity.md §2.2: `lc citizen provision` creates the login); on `lc_` tables what their specs give the game server, in particular `lc_fact` `INSERT, SELECT` only, `lc_fact_outbox` `INSERT, SELECT, DELETE`, `lc_fact_precise` and `lc_fact_expiring` `INSERT` only (fact-journal.md §2.2, §8); no `DROP`, `TRUNCATE`, `ALTER`, `CREATE`, `TRIGGER`, `GRANT` on anything (actor-identity.md §2.2); nothing of the runner's schema |
| `lc_intel` | `game`, the Intel Service's own pool, and forensics (`lc why`) through it | `SELECT` on `lc_fact_precise` and `lc_fact_expiring` (fact-journal.md §8, FACT-R07); the Intel tables as intel-service.md gives them |
| `lc_maint` | `game`, the pool of the journal's maintenance job (fact-journal.md §4.5) and the Intel purge (intel-service.md §4.5) | `SELECT, DELETE` on every table listed in `lc_expiring_table` (the only user that deletes expiring rows, fact-journal.md §8); `ALTER, DROP` on `lc_fact` only (MariaDB requires `DROP` to drop a partition); what fact-journal.md §4.5 needs to retire snapshots (`lc_replay_snapshot`) |
| `lc_runner` | `runner` | what citizens.md §2 gives it on its own schema; nothing of Mobius's schema (citizens.md §2, AT-CIT-04) |
| `lc_governor` | the `governor` admin tool, run by hand (citizens.md §4.10) | `SELECT` on `lc_telemetry_bucket`, `lc_journal_gap`, `clan_data`, `territories` and the Ledger and Chronicle publication views; nothing that writes |
| `lc_web` | `web`, its HTTP server | the role `lc_chronicle_web` and nothing else (chronicle.md §2.1, §4.9) |
| `lc_narrator` | `web`, its narrator worker — not the LLM service | the role `lc_chronicle_narrator` and nothing else: chronicle.md §2.1's reads and `INSERT` on `lc_chr_prose` |
| `lc_backup` | `backup` | `SELECT, LOCK TABLES, SHOW VIEW, RELOAD, PROCESS, BINLOG MONITOR` (a `mariadb-backup` full backup); nothing that writes |
| `lc_owner` | no process: the operator runs migrations, `deploy/grants/`, `DatabaseInstaller` and restores with it | all privileges on both schemas with `GRANT OPTION` (V016 creates the Chronicle roles, chronicle.md §4.9) |

The three pools of `game` share one JVM, so the split guards against a module's bug reading or deleting what it must not,
not against a compromised game process; the network and process boundaries guard the runner and the web (02 §7).

### 4.4 Client package and geodata

**Client** (nothing exists yet; all to build):

- `client/client.lock` holds every file hash of one pinned H5 base client, the digests of the patch inputs and of the
  pinned patch tools (encrypt/decrypt, table editors; acmi's legacy JDK 8 container only if an `interface.xdat` edit is
  proven necessary, 03 §6), and every file hash of the built archive. One command builds the archive (the client and its
  `system/` patch) from those pinned inputs only, and a rebuild reproduces the recorded hashes; if a pinned tool's
  encryption is not deterministic, the lock records the hashes of the decrypted tables instead and the rebuild is compared
  on those.
- The patch adds every new item (parchments, the Torn Black Seal, calling-card tokens, the Wayfarer's Journal, the Aden
  Gazette issue, the Wanted Poster: 03 §6, and any item a spec adds later) with its name, description, icon, system
  messages, NpcStrings and Codex button strings in EN, RO and RU. New NPCs reuse client models through `displayId` and
  server-side names; no client NPC file (`npcgrp`, `npcname-*`) differs from the base.
- Validation in the pinned client (03 §6): layouts, item use, Cyrillic and Romanian text, Community Board size bounds.
- The client runs on the tester's workstation; nothing authoritative depends on it (02 §12: never trust the client; the
  legacy cipher is not authentication).

**Geodata.** What exists: the fork ships none (`dist/game/data/geodata/` holds a readme), and upstream's loader accepts
whatever it finds: every region starts as `NullRegion` (`gameserver/geoengine/GeoEngine.java:92`), a region file that
does not exist is skipped (`:103`), one that fails to load is logged and skipped (`:113`), and with no region loaded
pathfinding is switched off (`:128-131`). Nothing refuses to start. To build:

- `geodata/geodata.lock`: the pinned l2mapconv build's digest, its settings, the correction patches, the `client.lock` it
  was generated from, and the **required-region manifest**: every region file the generator produces from the pinned
  client, by name and SHA-256. "Required" means "in the manifest"; no third-party pack.
- **A core patch to `GeoEngine.java`** (ledgered in `CORE_PATCHES.md`): when the geodata directory holds a manifest, the
  server refuses to start, naming the region, if a listed region is missing, fails to load or hashes differently, or if a
  region file not in the manifest is present. Without a manifest, upstream's behaviour is unchanged (the `truth` and `dev`
  profiles run without geodata today). The `sim`, `hobby` and `live` profiles require the manifest (a profiles.md startup
  check, §8). The runner checks the same manifest and stops on any difference (citizens.md §4.4).
- **Geometry probes** (03 §7), `geodata/probes.csv`: each probe a query (path between two points, line of sight between
  two points, height at a point from a given floor) with its expected outcome, run against the loaded `GeoEngine`. Each
  expectation comes from evidence: an observation recorded in the pinned client during the client validation session, or
  a retail structure's evident property (a closed castle gate blocks passage); never from the geodata under test. Minimum
  content: every gate of the 9 castles and 21 fortresses closed (path and line of sight blocked) and open (path found);
  the 9 castles' walls blocking passage outside the gates; at least 10 cross-region routes on foot; 5 multilevel buildings
  (height resolved to the floor the query starts from); 5 staircases (path from lower to upper floor); 3 water crossings
  with the generator's settled outcome; 10 line-of-sight pairs across walls and terrain; 3 instance zones (path inside).

### 4.5 Capacity

Measured on the deployment host (`build-host`, 03 §9), never assumed (03 §10). §4.5's tables are the authority:
`bench/<scenario>.yaml` reproduces each scenario at no less than its minimum workload and with no looser band. A file may be
heavier or tighter; raising a band, or lowering a minimum, is a revision of this spec with the measurement that justifies
it (02 §9: bands are declared before the run). The bands are first values chosen before any measurement; they may only be
tightened without a revision.

Every scenario: 10 minutes of warm-up, then at least 60 minutes measured (the siege: its whole length, at least 60
minutes); the `game` and `mariadb` CPU sets of 03 §9; and the **background load** for the whole run: the host's CI runners
at their hard CPU caps running the full gate in a loop, and the `narrator` LLM serving the web's narrator worker and the
citizens' dialogue at its maximum concurrency. A run whose background load was not measured present (CI at ≥ 90 % of its
caps, LLM requests in flight at its concurrency, each for ≥ 95 % of the measured time) does not count.

| Scenario | Minimum workload |
|---|---|
| dispersed farming | 800 citizens over ≥ 20 hunting grounds in ≥ 5 regions, fighting, looting, moving and resting continuously |
| dense private-store markets | ≥ 300 private stores (sell, buy, manufacture) in one town and ≥ 300 citizens browsing and buying, ≥ 5 purchases per second |
| craft and trade bursts | every 5 minutes, 60 s of ≥ 50 crafts and ≥ 50 trades (P2P and store) per second, through the Asset Service where it applies, PACT's craft for contract included |
| instance transitions | ≥ 30 parties of 2–9 entering or leaving instances per minute |
| **800-citizen castle siege** | one castle siege, two sides of 800 citizens in all: real attacks and skills, deaths and resurrections; movement over the castle's walls, gates and stairs; visibility fan-out, each participant seeing ≥ 100 others on average; ≥ 50 live PACT mercenary contracts settling on siege facts; the Chronicle recording |

| Metric (server side) | Definition | Band, every scenario |
|---|---|---|
| scheduler lateness | actual minus planned start of each task on Mobius's scheduled pools | p99 ≤ 50 ms, max ≤ 500 ms |
| packet-queue age | receipt to start of execution of a client packet | p99 ≤ 50 ms |
| combat acknowledgement | receipt of an attack or skill request to the first packet it causes | p99 ≤ 100 ms |
| pathfinding time | one path request | p99 ≤ 10 ms, max ≤ 100 ms |
| database commit | a Living Chronicle transaction's commit (the Asset Service's included) | p99 ≤ 15 ms (02 §6) |
| fact capture | fact-journal.md FACT-R03 | p99 ≤ 2 µs (02 §6) |
| journal lag | projector lag, fact-journal.md §4.6 | p99 ≤ 5 s (02 §6, FACT-R14) |
| Codex render | one page | p99 ≤ 20 ms (02 §6) |
| resident memory | RSS of `game`, `runner`, `mariadb` | ≤ 80 % of the container's limit; growth over the measured period ≤ 2 % |
| mapped memory | `game`'s mapped files (GeoEngine maps regions) | ≤ the manifest's total size + 10 % |
| disk growth | data volume growth rate | 90 days at that rate fit in the free space at start |
| GC | pauses of the `game` JVM | max ≤ 100 ms; total ≤ 2 % of wall time |

The modules' own seams (Wayfarer, Aden Remembers, Chronicle) are measured in these scenarios against their specs' budgets.
A failing benchmark is fixed by removing the bottleneck or adding resources, never by cutting a feature.

**No GPU for anything authoritative.** Only `narrator` is given a GPU (a Compose device reservation); `game`, `login`,
`runner`, `web`, `mariadb` have none. Without it the narrator's choices fall back to the templates' first alternatives
(chronicle.md §4.8) and citizens' dialogue to template lines (citizens.md §4.8); nothing else changes.

### 4.6 The doctrine, and where each part is checked

02 is a review standard; only some of it is machine-checkable. Each section, with what checks it today and what is to
build. "Review" means the different-family reviewer (02 §10), not a test.

| 02 | Checked today | To build | Not machine-checked |
|---|---|---|---|
| §1 do not break the game | behaviour-lock goldens (`*LockTest`, `test/lock/`, `Golden.java`) | the Retail Truth case runner and lock-change check (retail-truth.md) | — |
| §2 data structures first | `SpecTraceabilityTest`: every MUST names defined tests and is reached from the blueprint | the same test fails a spec with status `implemented` unless each of its AT ids is named in the Javadoc of a test class (the convention, e.g. `ChronicleSchemaTest.java:20`) | that the spec changed before the code: review |
| §3 one mechanism | each module's static scans (e.g. AT-SIG-09, AT-MEM-09) | per module spec | — |
| §4 production invariants | — | each module's invariants (§6 of each spec) | — |
| §5 patches | the ledger's file set (`check-core-ledger.sh`), present `upstream` only | §4.2 (a), (b) | one logical change per commit, bisectability of each commit: review |
| §6 performance | — | §4.5; fact-journal.md AT-FACT-03, -14; the budgets of asset-service.md and codex-ui.md | — |
| §7 no frameworks | — | AT-PLT-01's dependency check (§4.2) | — |
| §8 configuration | — | profiles.md's startup check | — |
| §9 show me the test | `SpecTraceabilityTest` | — | independence of an oracle: review, against each AT's stated source (invariant, evidence, pre-declared band) |
| §10 review | each spec's §12 | — | the review itself |
| §11 observability | — | each module's reason codes and `lc why` | — |
| §12 security | the Chronicle reader roles (`ChronicleSchemaTest`), the runner's user (`RunnerSchemaTest`) | §4.3 here (AT-PLT-02, -03); module specs | — |

**Absent NCSoft infrastructure** (02 §12; 16 §8–§9): there is no anti-cheat client (the login's `AuthGameGuard` step only
compares a session id, `loginserver/network/clientpackets/AuthGameGuard.java:58-100`), no official account system, billing or
moderation team. So every authoritative fact (location, kills, contract completion, inventory, witness encounters,
participation, clan membership) comes from server state; admin actions on contracts, bounties, world state, history,
escrow and traces produce audit facts with before and after and a reason (the Asset Service's, PACT's and the Ledger's
admin operations); moderation is the admin tooling plus the Chronicle's letter queue.

### 4.7 Backups and restore

All to build, in `deploy/backup/`, run by `backup` as `lc_backup` and, for restores, by the operator as `lc_owner`.

- **Taken**: a nightly `mariadb-backup` full snapshot; binary logging on, each closed binlog copied off the data volume;
  a snapshot before every migration (the migration procedure refuses to run without one newer than the last commit).
- **Kept** within the expiring-data rule (fact-journal.md §2.4, intel-service.md §4.5): a snapshot is stored in two parts,
  the tables listed in `lc_expiring_table` and the rest; each listed table's part is deleted once older than its
  `max_life_hours`; binlogs, which hold those tables' writes, are deleted once older than the shortest `max_life_hours`
  in the registry. The non-expiring part follows the operator's retention. So point-in-time recovery reaches back to the
  later of the newest snapshot and now minus the shortest life (with the defaults, 72 h: any point since the last nightly).
- **Restored**: the snapshot, then its binlogs up to the chosen point; then, before any server starts, every row past its
  `expires_at` in every listed table is deleted (the maintenance purge, as `lc_maint`); a listed table whose part is past
  its life is restored empty. Then the Asset Service's census and PACT's custody check run on the copy (ASSET-INV02,
  -INV03, -INV05; PACT-INV02), and a failure is an alarm and an untrusted backup.
- **Drilled monthly**: the newest snapshot restored to a scratch database with its binlogs replayed to the drill's start,
  which must recover the newest `lc_fact` id committed before the drill began; then every check above.

## 5. Facts

None of its own.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PLT-INV01 | the build image holds only pinned inputs; the server's runtime classpath only §4.2's upstream libraries | at assembly by `Provision.java`; AT-PLT-01 |
| PLT-INV02 | each process's database user holds exactly §4.3's privileges | AT-PLT-02; the monthly drill compares the live grants with `deploy/grants/` |
| PLT-INV03 | the loaded geodata is exactly the manifest's regions with their hashes, in `sim`, `hobby` and `live` | at every start by the `GeoEngine` patch and the runner; AT-PLT-04 |
| PLT-INV04 | no backup holds expiring data past its life | the retention job, every hour, alarming on any file it finds late; AT-PLT-08 |

## 7. Failure model

| Fault | End state |
|---|---|
| a download's digest differs | the image is not assembled; the build fails |
| a local image retagged by hand | outside the guarantee (trusted daemon, §4.2); the merge gate reassembles and is unaffected |
| a merge-gate run without an `upstream` branch, or an incomplete ledger entry | the gate fails |
| a listed geodata region missing, unreadable, modified, or an unlisted one present | the server, or the runner, does not start, naming the region |
| the geodata manifest absent in `sim`, `hobby` or `live` | the profile's startup check refuses to start |
| GPU absent or the `narrator` service down | template text instead of narration and dialogue; everything else unchanged |
| a binlog lost | recovery only to the last snapshot or the binlog before the gap; an alarm |
| a restore with rows past expiry | deleted before any server starts |
| a restore drill finds an escrow or contract inconsistency | an alarm; the backup is not trusted |

## 8. Configuration

No runtime keys of its own. Its inputs are the files of §2. One check belongs to profiles.md's startup check: in `sim`,
`hobby` and `live` the geodata directory holds `geodata.lock`'s manifest (§4.4).

## 9. Interface

None.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PLT-R01 | The build MUST run with one command from a clean checkout inside an image assembled from `toolchain.lock`, consuming only inputs pinned there or vendored by upstream; two clean builds of one commit MUST print the same classes fingerprint; the runtime classpath MUST hold only §4.2's upstream libraries and test libraries only the test classpath; the merge gate MUST fail without the upstream ledger check or on an incomplete ledger entry. | AT-PLT-01 |
| PLT-R02 | Each process MUST connect as its own database user of §4.3 and each user MUST hold exactly §4.3's privileges: every one listed, no other. | AT-PLT-02 |
| PLT-R03 | Every stack listener MUST be unreachable from the host's LAN and public interfaces, and testers MUST reach only ports 2106, 7777 and 443, through the node share. | AT-PLT-03 |
| PLT-R04 | In `sim`, `hobby` and `live` the server and the runner MUST start only with exactly the manifest's geodata regions, each matching its hash, and that geodata MUST pass every geometry probe of §4.4. | AT-PLT-04 |
| PLT-R05 | The client archive MUST be rebuilt identically from the pinned base and patch inputs, MUST add every new item with its name, icon and strings in EN, RO and RU, MUST change no client NPC file, and MUST pass the validation session of §4.4. | AT-PLT-05 |
| PLT-R06 | Every capacity scenario of §4.5, at no less than its minimum workload and under its background load, MUST stay within every band. | AT-PLT-06 |
| PLT-R07 | Every authoritative operation MUST succeed with the same outcome with no GPU in the stack and the `narrator` service down, and only `narrator` MUST be given a GPU. | AT-PLT-07 |
| PLT-R08 | Backups MUST recover to any point within §4.7's window, MUST hold no expiring data past its life, and a restore MUST delete expired rows before any server starts and pass the escrow and contract checks, drilled monthly. | AT-PLT-08 |
| PLT-R09 | The fork MUST NOT add a VIP tier, Sayune for everyone or custom depositable items, as §4.1's signatures define them. | AT-PLT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-PLT-01 | integration | with the `lc-build` image removed, a fresh clone builds with `./build.sh` and the network limited to the lock's URLs and the image registry; a lock line with one changed digest fails assembly; two fresh clones of one commit print equal `classes.sha256`; the dependency check fails when a jar is added to `dist/libs` or anywhere else in the repository outside upstream's set, when a test library reaches the runtime classpath, and when a compiled server, runner or web class references a package outside `java.*`, `javax.*`, `jdk.*`, `org.l2jmobius.*`, `com.zaxxer.hikari`, `com.mysql`, `org.slf4j` (`jdeps` over the compiled classes); with `LC_GATE=1`, a clone without `upstream` fails, and a fixture ledger with an entry missing `Invariant:` or naming a nonexistent proof class fails |
| AT-PLT-02 | integration | every migration and every `deploy/grants/` file applied to MariaDB: each user's privileges, read from `information_schema` and `SHOW GRANTS`, equal the set this test derives from §4.3 and the owning specs (an account with no grants fails, as does one with an extra privilege); connected as each user, one probe statement per granted table and privilege succeeds (inside a rolled-back transaction) and every table and privilege outside the grant is refused, covering every table in `information_schema.TABLES`; each process's configuration names its own user, and none names `lc_owner` or root |
| AT-PLT-03 | integration, human | static: `deploy/compose.yaml` publishes no port, puts `login`, `game` and `web` in `tailscale`'s namespace and the rest on `lc_private` only, and gives `mariadb` a non-wildcard bind address; in the running stack, each namespace's listeners (`ss -ltn`) are exactly §4.3's, and from another LAN host and from the host's own LAN address no port of the stack accepts a connection; human, recorded: from a real sharee 2106, 7777 and 443 open and 3306, 9014 and a sample of other ports closed; from a tailnet account without the share, nothing open |
| AT-PLT-04 | integration | with the manifest staged in `hobby`: a listed region missing, unreadable, changed by one byte, and an unlisted region added each stop the server and the runner, naming the region; the exact manifest starts both; without the manifest in `hobby` the startup check refuses; every probe of `geodata/probes.csv` gives its expected outcome, and the file holds at least §4.4's minimum per category |
| AT-PLT-05 | unit, human | a rebuild from the pinned inputs reproduces `client.lock`'s archive hashes; every new item id the server's data adds has a name, description and icon entry whose texture exists in the client's packages; no NPC table differs from the base; every added string decodes in EN, RO and RU with Cyrillic and Romanian diacritics and no replacement character; human, recorded in the pinned client: each new item seen and used, RO and RU texts rendered, a Community Board page of three full chunks rendered, layouts intact at 1024×768 and 1920×1080 |
| AT-PLT-06 | sim | each `bench/` file is at least its §4.5 row and no looser than §4.5's bands, else the test fails; each scenario runs on the host with its background load measured present and stays within every band, the 800-citizen siege included |
| AT-PLT-07 | integration | static: only `narrator` has a GPU reservation; the stack run with no GPU runtime and `narrator` stopped, then with them, through one seeded script: login, enter the world, kill and loot, a P2P trade, an Asset escrow funded and released, a PACT contract signed and settled, facts relayed and projected, a Chronicle row published and served by `web`; both runs end with the same inventories, Adena, custodies and contract states; without the GPU the page text is the templates' first alternatives and citizens' lines are templates |
| AT-PLT-08 | integration | fixture contracts, escrow and rows of every table in `lc_expiring_table`, some expiring between snapshot and restore; a snapshot, then more commits: a restore with binlog replay recovers the newest committed `lc_fact` id and nothing after the chosen point; no row past its `expires_at` exists when the game server starts; the census and PACT check pass, and a seeded escrow row without a custody makes them alarm; after the clock passes each life, the expiring parts and binlogs are gone and a restore asked from them is refused |
| AT-PLT-09 | unit | the fork's delta against the upstream snapshot (files added or changed) holds no §4.1 signature; upstream's own matches are exactly §4.1's list (a new one from intake fails, for review); every upstream item's `is_depositable` equals the upstream value, and an added item's equals its spec's; no profile enables `PremiumSystem`; the handled client opcodes are upstream's plus those the specs add |

## 11. Upstream constraints

1. **Java 27 is upstream's requirement** (`build.xml` `checkRequirements`). → the pinned JDK 27 image.
2. **Upstream's jars stamp their build date and user** (`build.xml:85-86`, `:100-101`, `:117-118`). → reproducibility is
   the classes fingerprint, not the jars.
3. **No geodata ships, and the loader accepts whatever it finds** (`GeoEngine.java:92`, `:103`, `:113`, `:128-131`). → a
   manifest, a core patch that refuses on any difference, and probes.
4. **`AuthGameGuard` only compares a session id** (`AuthGameGuard.java:58-100`). → nothing trusts the client.
5. **Login and game share one schema with one root user by default** (`dist/login/config/Database.ini:11-14`,
   `dist/game/config/Database.ini:11-14`). → one user per process and pool, granted table by table.

## 12. Review record

- Round 2 (Codex), NO SIGN-OFF; **parked**: the report is kept verbatim in `docs/spec/reviews/platform-round-2.md` and is
  answered when N13 (deployment stack) is implemented, against the code it will have. Revising further ahead of that work would grow
  the spec without testing it.
- Revision 2 addition (from the Citizens review, round 1): the `governor` admin tool and its read-only `lc_governor`
  user; the runner's user confined to its own database.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 14 findings. Revision 2 answers each:
  1. Geodata refusal contradicted by `GeoEngine.java` — fixed: §4.4 now states upstream's behaviour (`:92`, `:103`, `:113`,
     `:128-131`) and makes the refusal a core patch to build, against a required-region manifest.
  2. AT-PLT-04 too weak, "required" undefined, no geometry tests — fixed: required = in the manifest; the test covers
     missing, unreadable, one-byte-modified and unlisted regions for both processes, and runs `geodata/probes.csv` with
     minimum counts per 03 §7 category and evidence-sourced expectations.
  3. Database grants unspecified, identities missing, AT-PLT-02 passable by an empty account — fixed: §4.3 lists every
     user (`lc_intel` and `lc_maint` for fact-journal.md §8's expiring readers and maintenance authority), their
     privileges and the default-deny rule; AT-PLT-02 compares the exact privilege set and probes every granted pair.
  4. Narrator role on the LLM service — fixed: `lc_narrator` belongs to the web's narrator worker (chronicle.md §4.9); the
     `narrator` service has no database user.
  5. The allowlist rejects HikariCP, SLF4J, Testcontainers; no scanner exists — fixed: §4.2's three classes name the
     upstream runtime jars and the test closure; the check is marked to build, with its oracle in AT-PLT-01.
  6. Jars not reproducible — fixed: reproducibility is bounded to the classes fingerprint (build-lc.xml:35-46), the jar
     stamps cited, and AT-PLT-01 compares two clean builds.
  7. Cached image bypasses digest checks — fixed in the spec's claim, pushed back on the threat: retagging needs the Docker
     daemon, which is root and is already mounted into the build (build.sh:51), so verification against it would prove
     nothing; the trust boundary is stated, and the merge gate and AT-PLT-01 assemble the image afresh.
  8. Capacity without numbers — fixed: §4.5 gives each scenario a minimum workload (the siege with 03 §10's combat,
     movement, fan-out and live contracts), duration, warm-up, a measured background load, and a band for every metric,
     with the rule that files may only be heavier or tighter.
  9. Network acceptance does not prove privacy — fixed: no published ports, namespaces and bind addresses specified;
     AT-PLT-03 adds a static check, a listener check and LAN-side scans to the sharee session.
  10. Client hashes do not prove a working client — fixed: rebuild from pinned inputs, per-item table and icon checks,
      trilingual decoding, and a recorded validation session in AT-PLT-05.
  11. Gate and doctrine claims exceed what exists — fixed: §4.2 separates today's gate from what is to build (the Retail
      Truth runner and scripts do not exist), and §4.6 maps every 02 section to an existing check, one to build, or review.
      The matrix row `TR-engineering-doctrine` still cites PLT-R01 alone; its hand-written columns are outside this
      revision (it should cite the module specs §4.6 names).
  12. Ledger skipped in an ordinary clone and checks only filenames — fixed: §4.2 requires the gate to fail without
      `upstream` under `LC_GATE=1` and the check to validate each entry's fields and proof class; AT-PLT-01 covers both.
  13. Restore omits expiry and binlog recovery — fixed: §4.7 splits snapshots by retention class, bounds binlogs by the
      shortest life, deletes expired rows before start, and AT-PLT-08 checks point-in-time recovery, expiry and that the
      invariant check can fail.
  14. GPU and absent-feature tests without oracles — fixed: AT-PLT-07 runs one seeded script with and without the GPU and
      compares authoritative outcomes; AT-PLT-09 uses §4.1's signatures on the fork's delta, an exact list of upstream's
      own matches, and data and opcode comparisons rather than words.
