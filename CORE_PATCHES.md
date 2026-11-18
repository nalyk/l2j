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
