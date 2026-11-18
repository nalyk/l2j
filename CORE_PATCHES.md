# Core patches

Every file that exists upstream and differs in this fork is listed here, with the reason it had to change, the invariant the
change protects and the test that proves it. Upstream intake (blueprint 03 §3) replays this ledger: after merging `upstream`,
each patch must still apply, still be needed, and still pass its proof.

Rules (blueprint 02 §5):

- A core change is minimal, mechanical where possible, and written so it could be sent upstream.
- New Living Chronicle code does not belong here. It lives in files upstream does not have.
- The ledger is complete by construction: `tools/intake/check-core-ledger.sh` fails the build when the set of `Files:` below
  is not exactly the set of upstream files that differ from the `upstream` branch.

Upstream files use CRLF line endings. Patches keep them.

## CP-0001 Bindable randomness

- Files: `java/org/l2jmobius/commons/util/Rnd.java`
- Reason: all game randomness goes through `Rnd`, which drew from `ThreadLocalRandom` and could not be seeded. Behaviour-lock
  goldens and simulations need a thread to be able to draw from a deterministic stream (blueprint 03 §4.6, 04 §7).
- Change: every draw goes through one private `generator()`. In a JVM started with `-Dlc.rng.bindable=true`, a thread may
  `Rnd.bind(generator)`; otherwise, and for every unbound thread, the generator is `ThreadLocalRandom.current()` as before.
- Invariant: production behaviour is unchanged. Without the flag the constant folds to false, each method calls the same
  `ThreadLocalRandom` method it called before, and `bind` throws, so live randomness can never be made predictable.
- Proof: `RndContractTest` (range contract, unbound), `RndBindingTest` (determinism, per-thread scope, contract on a bound
  stream), `RndProductionModeTest` (binding refused in a JVM without the flag).

## CP-0002 "Now" is always read through a redirectable call

- Files: `java/org/l2jmobius/gameserver/mechanics/script/LongTimeEvent.java`, `java/org/l2jmobius/gameserver/mechanics/announce/EventAnnouncement.java`, `dist/game/data/scripts/ai/areas/Gracia/AI/Lindvior.java`
- Reason: test JVMs make Mobius read `GameClock` by redirecting its static JDK time calls as classes load (see
  `TruthAgent`), which is what lets several hundred call sites stay unedited. A constructor that captures the present
  cannot be redirected that way. These were the only ones: four `new Date()` and one `new GregorianCalendar()`.
- Change: `new Date()` becomes `new Date(System.currentTimeMillis())`, which is how the JDK defines it. The Lindvior script
  uses `Calendar.getInstance()` like every other schedule in the datapack; it only ever used the `Calendar` API.
- Invariant: production behaviour is unchanged, and no Mobius class, core or script, reads the system clock in a way the
  agent cannot redirect.
- Proof: `CoreClockCoverageTest` (every compiled core class), `GameServerBootTest.everyLoadedClassReadsTimeThroughTheGameClock`
  (every script, as loaded), `TruthAgentTest` (the redirect itself).

## CP-0003 The festival manager pauses with a sleep, not a wait

- Files: `java/org/l2jmobius/gameserver/mechanics/sevensigns/SevenSignsFestival.java`
- Reason: `FestivalManager.run()` is one long task that paces a whole festival cycle with eight `wait(millis)` calls on
  itself. Nothing ever notifies it, so each is a timer. Test JVMs redirect `Thread.sleep` to `GameScheduler.sleep`, which
  pauses a task in game time while the rest of the world keeps running; an instance `wait` cannot be redirected the same
  way, and left alone it blocks the simulated world for real minutes.
- Change: the eight `wait(x)` become `Thread.sleep(x)`. No other line changes.
- Invariant: production behaviour is unchanged where the old code worked. Two differences, both stated because both are real:
  the monitor of the manager stays held during the pause (no other code synchronizes on it, and fixed-rate runs of one task
  never overlap), and a computed pause of exactly 0 ms now continues instead of waiting forever (`wait(0)` means "no
  timeout"; that was a latent hang, reachable when a festival's elapsed time equals its configured length to the millisecond).
- Proof: `GameSchedulerTest` (a sleeping task does not stop the world and wakes in order), `CoreClockCoverageTest` (no core
  class waits on real time in a way the agent cannot redirect), `PausedWorldTest` (the world, festival manager included,
  advances through game time without blocking).
