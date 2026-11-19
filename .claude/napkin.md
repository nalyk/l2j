# Napkin Runbook

## Curation Rules
- Re-prioritize on every read.
- Keep recurring, high-value notes only.
- Max 10 items per category.
- Each item includes date + "Do instead".

## Execution & Validation (Highest Priority)
1. **[2026-11-19] Git hygiene is continuous and validated, every green step**
   Do instead: before committing, `md5sum` every changed file locally and on ultra and diff them (commit only what the green gate ran); after committing, clean ultra (`git checkout -- . && git clean -fdq test`), push to origin and ultra, then check that local, `origin/n2-foundation` and ultra `HEAD` are the same commit and that ultra's `git status --short` is empty.
2. **[2026-11-18] Builds and tests run on ultra, never on WSL**
   Do instead: `scp` the changed files to `nalyk@build-host:gits/l2j/...`, run `./build.sh` (full gate) or `./build.sh test -Dtest.only=<Class>Test` there, write output to `build/gate.log` and read `build/test-reports/TEST-*.xml` for failures.
3. **[2026-11-18] A golden is recorded by copying, and trusted only after a second run**
   Do instead: on ultra copy `build/lock-actual/<golden>` to `test/lock/<golden>`, rerun the classes whose goldens changed (`./build.sh test -Dtest.only=<Class>Test`), commit only when they reproduce byte for byte; then `scp` the goldens back.
4. **[2026-11-19] A gate is 8 minutes: run it in the background and prepare the next step meanwhile**
   Do instead: start the gate with `run_in_background`, write the next patch as a script in the scratchpad (dry-run it on copies), apply it only after the current step is committed.
5. **[2026-11-19] Ultra keeps files you `scp`'d even after a local `git stash`**
   Do instead: after stashing or reverting locally, `git checkout -- <files>` on ultra too, and run `git status --short` there before every gate run.
6. **[2026-11-19] A "failed" pipeline may only be `grep -v` finding nothing**
   Do instead: judge the gate by `BUILD SUCCESSFUL` in `build/gate.log` and by `failures="0"` in the report XML, not by the shell's exit code.

7. **[2026-11-19] A proof is trusted only after its fix is reverted and it fails**
   Do instead: on ultra, revert each fix (sed on the core line, or the test wrapper), drop any reverted file from CORE_PATCHES.md `Files:` there (or `check-core-ledger` stops the build before tests run), run the class, see every proof fail with its own message, then `scp` the local files back and md5-check them.

## Lock Harness Guardrails
1. **[2026-11-19] A lock case runs whole on its own stream, aligned, in a woken arena**
   Do instead: `Arena.wake()` once before a sweep; per case `Arena.align()` then `_server.withRandom(KeyedRandom.of(SEED, "<stream>", key), ...)` around setup, run and clean-up.
2. **[2026-11-19] Prove isolation, do not assume it**
   Do instead: run one case, advance the world ten minutes, draw 1,000 times on the world stream, `Arena.wake()`, run it again and assert it is equal (`GearLockTest.aCaseComesOutTheSameAfterTheWorldHasMovedOn`). To find a leak, wrap the case's generator with a `StackWalker` draw-site logger and diff the two runs.
3. **[2026-11-19] A server loop serving world and case actors alike is dispatched per actor**
   Do instead: list its per-actor calls in `TruthAgent.DISPATCH` and add same-named receiver-first statics to `ActorStreams` (movement and thinking are done). A loop goes in `CaseStreams.ACTOR_POOLS` (case-wide) only if it draws nothing over an idle hour; every other task manager runs on the world stream.
4. **[2026-11-18] Reading an NPC's AI must not create one**
   Do instead: use `npc.hasAI() ? npc.getAI().getIntention() : null`; `getAI()` builds an AI and draws (`rollGlobalAggro`).
5. **[2026-11-18] Stop conditions must count only the case's own objects**
   Do instead: key results by object id and test membership in the set of ids the case made; global spawn listeners also see minions and replacements.
6. **[2026-11-18] Silent outcomes: 0-damage hits and raid curse notify nothing**
   Do instead: count swings via `ON_CREATURE_ATTACK`; give raid bosses an opponent at their own level (more than 8 above gets petrified).
7. **[2026-11-18] Both sides of a case must be spawned**
   Do instead: spawn target and actor in the `Arena` before `setTarget`/`useMagic`; `Player.setTarget` drops unspawned targets.
8. **[2026-11-19] The random spawn system rewrites a Spawn's own coordinates**
   Do instead: use `spawn.getSpawnLocation()` for the datapack spot, never `spawn.getX()`.
9. **[2026-11-19] Territory containment is judged at the creature's height**
   Do instead: use `NpcSpawnTerritory.holds(x, y, z)` (CP-0006); `isInsideZone(x, y)` tests bans at the territory's lowest Z and turns out legal points above a banned band.
10. **[2026-11-19] Locks run in the truth profile: every custom switch off**
   Do instead: add a new `config/Custom/*.ini` to `TruthProfile.MODULES`; its `= true` lines are turned off by rule (only AllowedPlayerRaces kept), numeric neutral values go in `NEUTRAL_VALUES`. To test a custom path (random spawns), set the config static in a `try/finally`, together with any list its loader fills only when switched on (`MOBS_LIST_NOT_RANDOM`).

## Core & Datapack Guardrails
1. **[2026-11-18] Every edit to an upstream file needs a CORE_PATCHES.md entry**
   Do instead: add `CP-NNNN` (files, reason, change, invariant, proof) in the same commit; `check-core-ledger` fails the build otherwise.
2. **[2026-11-19] Upstream files mix CRLF and LF**
   Do instead: edit them byte-wise in Python, check whether `\r\n` is present first and write the same endings back.
3. **[2026-11-19] `-XX:hashCode=2` makes every identity hash equal in tests**
   Do instead: key test maps by object id (Integer), never by world objects; expect maps keyed by objects to degrade to one bucket.
4. **[2026-11-19] Instance calls can be rerouted in test JVMs only through `ActorDispatch`**
   Do instead: use `java.lang.classfile` to swap `invokevirtual X.m(args)R` for `invokestatic W.m(X,args)R` (same stack effect) at listed sites; `MethodRedirector` handles static calls only and never touches instructions.

## Shell & Command Reliability
1. **[2026-11-18] Python block replacements must match exactly once**
   Do instead: replace whole multi-line blocks and `assert s.count(old) == 1` before writing.
2. **[2026-11-19] A file list passed to `ssh "..."` must be one line**
   Do instead: build it with `git status --short | awk '{print $2}' | tr '\n' ' '`; a newline inside the remote command runs each path as a command.

## User Directives
1. **[2026-11-18] Fix bugs and gaps found in scope at once; do not ask**
   Do instead: stop only for irreversible or destructive external actions (deleting, paying, force-pushing).
2. **[2026-11-19] Git hygiene CONTINUOUSLY, and validate it**
   Do instead: follow Execution & Validation item 1 after every green step, without being asked.
3. **[2026-11-18] `main` and history belong to the user**
   Do instead: merge into `main` only on the user's say (given 2026-11-19 for N2, after its fixes); never force-push or delete branches without it.
