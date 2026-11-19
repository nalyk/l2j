# Core patches

Every file that exists upstream and differs in this fork is listed here, with the reason it had to change, the invariant the
change protects and the test that proves it. Upstream intake (blueprint 03 §3, `tools/intake/intake.sh`) replays this ledger:
after merging `upstream`, each patch must still apply, still be needed, and still pass its proof.

Rules (blueprint 02 §5):

- A core change is minimal, mechanical where possible, and written so it could be sent upstream.
- New Living Chronicle code does not belong here. It lives in files upstream does not have.
- The ledger is complete by construction: `tools/intake/check-core-ledger.sh` fails the build when the set of `Files:` below
  is not exactly the set of upstream files that differ from the `upstream` branch.

Upstream files keep the line endings they have upstream, CRLF in some and LF in others. Patches keep them.

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

## CP-0004 The bot report reset is scheduled for tomorrow, not for now

- Files: `java/org/l2jmobius/gameserver/data/BotReportTable.java`
- Reason: the daily reset of bot report points is computed as "today at BotReportPointsResetHour" by setting only the hour
  and minute of a calendar for the present, which keeps the present's seconds and milliseconds. When the reset task runs at
  that minute and schedules the next one, "today at 00:00" is the present itself, which is not before the present, so the
  next reset is due at once. The task reschedules itself with no delay until the clock leaves the reset minute: in production
  it spins a pool thread and resets every reporter's points thousands of times each night; in a paused world, where time
  does not move by itself, it never ends. The AI lock is the first case to run the world through a midnight and found it.
- Change: the reset point is the start of its minute (seconds and milliseconds 0), both where the next reset is scheduled and
  where the last one is computed at load, and the next reset is the first one strictly after the present.
- Invariant: points still reset once a day at the configured hour and minute, now exactly once.
- Proof: `AiLockTest.theWorldRanThroughMidnightWithoutAFailedTask` (its sweep runs the world through 00:00; before the
  patch the scheduler stopped there with the reset task run a million times without game time moving).

## CP-0005 A spawn's random heading is kept, and a territory's random point stays in the territory

- Files: `java/org/l2jmobius/gameserver/entity/spawns/Spawn.java`
- Reason: `Spawn` stores -1 as its heading to mean "a random heading each time": the datapack's spawns without a heading
  (10,077 of them), every territory spawn, and every NPC the random spawn system has moved. `Location.setHeading` clamps a
  heading into its range, so the -1 is stored as 0 and the branch that turns an NPC a random way can never run: all of those
  NPCs face due east, which also decides who stands behind them in a fight. The random spawn system's own check for -1 never
  sees one either, so it adds its offset to a territory's random point too and puts NPCs up to 150 outside their territory.
  The respawn lock (LOCK-TIMER-0002) recorded 1,679 territory spawns bringing NPCs back outside; with the patch, none.
- Change: `Spawn` remembers that -1 was asked for and turns those NPCs a random way, and the random spawn system leaves
  territory spawns alone. The system's check for -1 stays as it is, never true, so spawns on a fixed spot are moved by it
  at every respawn exactly as before.
- Invariant: where an NPC on a fixed spot appears is unchanged; an NPC of a territory appears inside it; an NPC whose
  spawn asks for no particular heading faces a random way. That way is drawn, so the boot draws more than before and every
  random choice after it (which slot of a spawn group fills, for one) falls differently: the world goldens moved, the
  isolated case goldens (skills, auto-attacks) did not.
- Proof: `TimerLockTest.territoryNpcsComeBackFacingAnyWay` (before the patch every one faced due east);
  `WorldProofTest.cp0005RandomSpawnsMoveFixedSpotsAndLeaveTerritoriesAlone`, with the random spawn system on as upstream
  ships it (the truth profile switches it off): a territory no wider than the system's offset keeps all 50 of its NPCs
  inside, NPCs on a fixed spot are still moved within the range and face any way.

## CP-0006 A creature's place in its spawn territory is judged at its own height

- Files: `java/org/l2jmobius/gameserver/entity/zone/type/NpcSpawnTerritory.java`, `java/org/l2jmobius/gameserver/ai/AttackableAI.java`
- Reason: a spawn territory may ban only a band of heights over part of its area: in the Swamp of Screams (rune12_2216_03)
  NPCs must not be placed under the swamp, below -2969, but may stand on it. `getRandomPoint` judges a ban at the point's
  own height and places NPCs above the band. The AI asks `isInsideZone(x, y)`, which judges a ban at the territory's lowest
  height, so it turns such an NPC out of the territory that placed it there: an idle one is woken as having strayed, and
  one with nothing to fight is sent home from a place it was spawned at.
- Change: `NpcSpawnTerritory.holds(x, y, z)` tests the polygon on the XY plane, ignoring the territory's own height band as
  the flat test does, and the bans at the creature's height, as `getRandomPoint` does. The AI's three territory tests (waking,
  going home, straying) use it. `isInsideZone(x, y)` is left as it was for any other caller.
- Invariant: an NPC a territory placed is inside that territory for its AI; outside a height-banded ban nothing changes.
- Proof: `NpcSpawnTerritoryTest` (the swamp territory and its ban: a Splinter Stakato where the respawn lock saw one come
  back holds the territory, the flat test turned it out); through the AI itself, each of its three tests on an NPC over a
  height-banded ban, `WorldProofTest.cp0006AnNpcOverABanIsNotWokenAsStrayed`, `cp0006AnNpcOverABanIsNotSentHome` and
  `cp0006AFightingNpcOverABanHasNotStrayed`, each failing with its call site reverted.

## CP-0007 An NPC turns idly exactly while it stands in an awake region

- Files: `java/org/l2jmobius/gameserver/entity/actor/Npc.java`, `java/org/l2jmobius/gameserver/taskmanagers/RandomAnimationTaskManager.java`
- Reason: the idle-animation list (`RandomAnimationTaskManager`) is filled only when a region wakes, with the NPCs it holds
  then, and emptied only when it falls asleep. An NPC that spawns into a region already awake, or walks into one, never
  turns idly there; one deleted while its region is awake stays on the list for good, and the list keeps drawing for it
  each time it falls due. In a town that never sleeps, every NPC a script or event ever spawned and removed piles up.
- Change: `Npc` puts itself on the list when it is placed in or walks into an awake region, takes itself off when it walks
  into a sleeping one or leaves the world, and when it decays. Regions waking and sleeping work as before. On a server's
  threads these can race, so the list heals itself: the manager reschedules an NPC's turn only if its entry is still the
  one it read; a caller putting on an NPC that has left the world meanwhile takes it off again; and an entry for an NPC no
  longer standing in an awake region is dropped when it falls due, before anything is drawn for it.
- Invariant: an NPC is on the idle-animation list exactly while it is spawned in an awake region (a race between threads
  can leave an entry until it next falls due, never longer, and nothing is drawn for it); what it does once there is
  unchanged.
- Proof: `TimerLockTest.anNpcTurnsIdlyExactlyWhileItStandsInAnAwakeRegion` (spawned into the awake arena: on the list;
  walked into a sleeping region: off; walked back: on; deleted: off, where before it stayed on);
  `WorldProofTest.cp0007AnNpcTakenOffMidTurnStaysOff` (an NPC that takes itself off from inside its own turn, the moment
  another thread could, stays off), `cp0007AGoneNpcPutOnLateDoesNotStay` (a late caller holding a deleted NPC),
  `cp0007AStaleEntryIsDroppedWhenItFallsDue` (an entry left by any race).
