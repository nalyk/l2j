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
   Do instead: on ultra copy `build/lock-actual/<golden>` to `test/lock/<golden>`, rerun the gate, and commit only when it reproduces byte for byte; then `scp` the golden back.
4. **[2026-11-19] Ultra keeps files you `scp`'d even after a local `git stash`**
   Do instead: after stashing or reverting locally, `git checkout -- <files>` on ultra too, and run `git status --short` there before every gate run.
5. **[2026-11-19] A "failed" pipeline may only be `grep -v` finding nothing**
   Do instead: judge the gate by `BUILD SUCCESSFUL` in `build/gate.log` and by `failures="0"` in the report XML, not by the shell's exit code.

## Lock Harness Guardrails
1. **[2026-11-19] A lock case runs whole on its own stream, aligned, in a woken arena**
   Do instead: `Arena.wake()` once before a sweep; per case `Arena.align()` then `_server.withRandom(KeyedRandom.of(SEED, "<stream>", key), ...)` around setup, run and clean-up.
2. **[2026-11-19] Prove isolation, do not assume it**
   Do instead: run one case, advance the world ten minutes, draw 1,000 times on the world stream, `Arena.wake()`, run it again and assert it is equal (`GearLockTest.aCaseComesOutTheSameAfterTheWorldHasMovedOn`). To find a leak, wrap the case's generator with a `StackWalker` draw-site logger and diff the two runs.
3. **[2026-11-19] Server pools that also serve world actors stay on the world stream**
   Do instead: add a task class to `CaseStreams.ACTOR_POOLS` only after measuring that it draws nothing over an idle hour (movement, decay and idle animation do draw).
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

## Core & Datapack Guardrails
1. **[2026-11-18] Every edit to an upstream file needs a CORE_PATCHES.md entry**
   Do instead: add `CP-NNNN` (files, reason, change, invariant, proof) in the same commit; `check-core-ledger` fails the build otherwise.
2. **[2026-11-19] Upstream files mix CRLF and LF**
   Do instead: edit them byte-wise in Python, check whether `\r\n` is present first and write the same endings back.
3. **[2026-11-19] `-XX:hashCode=2` makes every identity hash equal in tests**
   Do instead: key test maps by object id (Integer), never by world objects; expect maps keyed by objects to degrade to one bucket.

## Shell & Command Reliability
1. **[2026-11-18] Python block replacements must match exactly once**
   Do instead: replace whole multi-line blocks and `assert s.count(old) == 1` before writing.

## User Directives
1. **[2026-11-18] Fix bugs and gaps found in scope at once; do not ask**
   Do instead: stop only for irreversible or destructive external actions (deleting, paying, force-pushing).
2. **[2026-11-19] Git hygiene CONTINUOUSLY, and validate it**
   Do instead: follow Execution & Validation item 1 after every green step, without being asked.
3. **[2026-11-18] `main` and history belong to the user**
   Do instead: never merge into `main`, force-push or delete branches without the user's say.
