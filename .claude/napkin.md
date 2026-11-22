# Napkin Runbook

## Curation Rules
- Re-prioritize on every read.
- Keep recurring, high-value notes only.
- Max 10 items per category.
- Each item includes date + "Do instead".

## Execution & Validation (Highest Priority)
1. **[2026-11-19] A test gate in an `&&` chain must fail on failure**
   `ssh ... grep -o "BUILD [A-Z]*"` exits 0 on "BUILD FAILED" too, so a chain went on to commit and push a red tree.
   Do instead: gate with `ssh ... './build.sh test ... > log; grep -q "BUILD SUCCESSFUL" log'` and run the traceability test after every spec edit, not only some.
2. **[2026-11-19] Git hygiene is continuous and validated, every green step**
   Do instead: before committing, `md5sum` every changed file locally and on ultra and diff them (commit only what the green gate ran); after committing, clean ultra (`git checkout -- . && git clean -fdq test`), push to origin and ultra, then check that local, `origin/n2-foundation` and ultra `HEAD` are the same commit and that ultra's `git status --short` is empty.
3. **[2026-11-18] Builds and tests run on ultra, never on WSL**
   Do instead: `scp` the changed files to `nalyk@build-host:gits/l2j/...`, run `./build.sh` (full gate) or `./build.sh test -Dtest.only=<Class>Test` there, write output to `build/gate.log` and read `build/test-reports/TEST-*.xml` for failures.
4. **[2026-11-18] A golden is recorded by copying, and trusted only after a second run**
   Do instead: on ultra copy `build/lock-actual/<golden>` to `test/lock/<golden>`, rerun the classes whose goldens changed (`./build.sh test -Dtest.only=<Class>Test`), commit only when they reproduce byte for byte; then `scp` the goldens back.
5. **[2026-11-21] A gate is 25 minutes: run it detached on ultra, never as a live ssh from WSL**
   Do instead: `ssh -f ultra 'setsid nohup bash -c "./build.sh > /tmp/gateN.log 2>&1; echo $? > /tmp/gateN.done" >/dev/null 2>&1 </dev/null &'`,
   then a background `until ssh ... test -f /tmp/gateN.done; do sleep 60; done` poll; Claude Code's memory reaper killed a
   live-ssh gate once, and without `-f` the launching ssh stays attached.
6. **[2026-11-19] A "failed" pipeline may only be `grep -v` finding nothing**
   Do instead: judge the gate by `BUILD SUCCESSFUL` in `build/gate.log` and by `failures="0"` in the report XML, not by the shell's exit code.

7. **[2026-11-19] A proof is trusted only after its fix is reverted and it fails**
   Do instead: on ultra, revert each fix (sed on the core line, or the test wrapper), drop any reverted file from CORE_PATCHES.md `Files:` there (or `check-core-ledger` stops the build before tests run), run the class, see every proof fail with its own message, then `scp` the local files back and md5-check them.

8. **[2026-11-19] Intakes run in a separate worktree on ultra, and move `upstream` for every worktree**
   Do instead: `git worktree add --detach ~/gits/l2j-intake n2-foundation`, run `tools/intake/intake.sh <sha> n2-foundation` there in the background, then fetch `upstream` and `intake/<sha12>` back, push both to origin (the mirror push is a fast-forward), and `git worktree remove` it. Ledger checks compare with `git merge-base HEAD upstream`, never the tip.
9. **[2026-11-19] A reverted fix that would deadlock is not run**
   Do instead: when a fix prevents a deadlock (a lock taken from inside the simulation), prove it by the test alone; a hung build container outlives `timeout`.
10. **[2026-11-20] Parallel spec revisions: agents own files, never commit; one batch gate**
   Do instead: one background agent per review, each told exactly which files it may touch and given its own
   `git worktree add --detach ~/gits/l2j-<x> n1-specs` on ultra; collect their cross-spec amendments, apply them, run
   `tools/spec/traceability.py` (row status is derived from spec status lines), one full gate, then commit the batch.

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
1. **[2026-11-20] Asset tests need tradeable items and a character in the World**
   Do instead: use the Short Sword (id 1), not the Squire's Sword (2369, a starter item with is_tradable=false, which §4.4
   refuses), and call `World.addObject(player)` after `TestPlayers.create` for anything that looks players up by id.
2. **[2026-11-18] Every edit to an upstream file needs a CORE_PATCHES.md entry**
   Do instead: add `CP-NNNN` (files, reason, change, invariant, proof) in the same commit; `check-core-ledger` fails the
   build otherwise. Put every file of an entry on its one `- Files:` line (the check reads only that line);
   for a revert proof that restores a file to upstream, drop it from a temporary ledger on ultra too, or the stale check
   stops the build before any test runs.
3. **[2026-11-19] Upstream files mix CRLF and LF**
   Do instead: edit them byte-wise in Python, check whether `\r\n` is present first and write the same endings back.
4. **[2026-11-19] `-XX:hashCode=2` makes every identity hash equal in tests**
   Do instead: key test maps by object id (Integer), never by world objects; expect maps keyed by objects to degrade to one bucket.
5. **[2026-11-19] Instance calls can be rerouted in test JVMs only through `ActorDispatch`**
   Do instead: use `java.lang.classfile` to swap `invokevirtual X.m(args)R` for `invokestatic W.m(X,args)R` (same stack effect) at listed sites; `MethodRedirector` handles static calls only and never touches instructions.

6. **[2026-11-22] Test a reviewer's database-limit claim before fixing it**
   A review said a counted deletion over 65,535 rows fails on the placeholder limit; a 70,000-row test passed with and
   without `useServerPrepStmts=true` (Connector/J prepares client-side, falling back when the server refuses).
   Do instead: write the failing test first; if it passes on the unfixed code, drop the fix and record the claim refuted.

7. **[2026-11-19] Cross-row trigger rules in migrations: no locking read of the trigger's own table**
   MariaDB raises error 1442 on `FOR UPDATE` of the table that fired the trigger.
   Do instead: turn the rule into a check of NEW against a row of another table read `FOR UPDATE` (e.g. a per-item band), never an isolation guard (see the `mariadb-trigger-enforced-immutability` skill, §6-7).

## Shell & Command Reliability
1. **[2026-11-19] `git checkout -- .` on ultra leaves scp'd NEW files untracked, and push (updateInstead) then fails**
   Do instead: after committing new files, `git clean`-free fix: on ultra `rm` exactly the files listed by `git status --short` as `??` (after md5 matched), then push.
2. **[2026-11-19] Codex verdict grep matches the echoed prompt**
   When Codex fails (usage limit), its output echoes the prompt, which contains "NO SIGN-OFF", so a plain grep reports a fake verdict.
   Do instead: first check the output for "hit your usage limit", then read the verdict only after the last `^codex$` line.
3. **[2026-11-18] Python block replacements must match exactly once**
   Do instead: replace whole multi-line blocks and `assert s.count(old) == 1` before writing.
4. **[2026-11-19] A file list passed to `ssh "..."` must be one line**
   Do instead: build it with `git status --short | awk '{print $2}' | tr '\n' ' '`; a newline inside the remote command runs each path as a command.
   `$(ls a/*)` has the same newline problem: use `files=$(echo a/* b/*)`, and scp every edited file (specs and plans too)
   before the gate, or the md5 check fails at commit.
5. **[2026-11-19] Never wait with `pgrep -f <pattern>` from a shell whose own command line contains the pattern**
   Do instead: start the jobs with `&`, collect `$!`, and `wait` on those pids; a `while pgrep -f "codex exec …"` loop matches its own wrapper shell and never ends.
6. **[2026-11-19] `git rev-parse --short` takes one revision**
   Do instead: loop `for r in a b; do git rev-parse --short $r; done`; with two it fails and stops an `&&` chain silently.

7. **[2026-11-20] An unquoted heredoc runs every backtick in it**
   `python3 - <<EOF` with a backticked `git log -p` in a string ran it and broke the script.
   Do instead: always `<<'EOF'`; pass values in through `export VAR=...` and `os.environ`.

## User Directives
1. **[2026-11-18] Fix bugs and gaps found in scope at once; do not ask**
   Do instead: stop only for irreversible or destructive external actions (deleting, paying, force-pushing).
2. **[2026-11-19] Git hygiene CONTINUOUSLY, and validate it**
   Do instead: follow Execution & Validation item 1 after every green step, without being asked.
3. **[2026-11-18] `main` and history belong to the user**
   Do instead: merge into `main` only on the user's say (given 2026-11-19 for N2, and again for the review answers + first intake; 2026-11-20 for N1); merge with `--no-ff -F <file>` (merge does not read `-F -`), and check `git rev-parse main^{tree}` equals the gated tree before pushing; never force-push or delete branches without it.
