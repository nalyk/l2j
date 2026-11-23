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
  longer standing in an awake region is dropped when it falls due, before anything is drawn for it, and put back as it was
  if the NPC's region woke meanwhile.
- Invariant: an NPC is on the idle-animation list exactly while it is spawned in an awake region (a race between threads
  can leave an entry until it next falls due, never longer, and nothing is drawn for it); what it does once there is
  unchanged.
- Proof: `TimerLockTest.anNpcTurnsIdlyExactlyWhileItStandsInAnAwakeRegion` (spawned into the awake arena: on the list;
  walked into a sleeping region: off; walked back: on; deleted: off, where before it stayed on);
  `WorldProofTest.cp0007AnNpcTakenOffMidTurnStaysOff` (an NPC that takes itself off from inside its own turn, the moment
  another thread could, stays off), `cp0007AGoneNpcPutOnLateDoesNotStay` (a late caller holding a deleted NPC),
  `cp0007AStaleEntryIsDroppedWhenItFallsDue` (an entry left by any race), `cp0007AnNpcWhoseRegionWokeMeanwhileStaysOn`
  (the region waking while the manager drops the entry).

## CP-0008 The escrow location

- Files: `java/org/l2jmobius/gameserver/entity/item/enums/ItemLocation.java`, `java/org/l2jmobius/gameserver/managers/DatabaseIdManager.java`
- Reason: the Asset Service keeps escrowed items as ordinary `items` rows (owner -1, `loc = 'ESCROW'`, `loc_data` = the
  custody; asset-service.md §2.3). Mobius restores a row through `ItemLocation.valueOf` and gives up with a SEVERE log on
  a location it does not know (Item.java:1538, 1546-1549), and startup cleanup deletes every row whose owner is neither a
  character nor a clan unless that owner is -1 (DatabaseIdManager.java:67): one corrupted owner id would have destroyed
  the evidence the custody check needs (§11.4, §11.5).
- Change: `ESCROW` is added at the end of `ItemLocation` (no ordinal moves; nothing uses them), and cleanup's owner
  query also keeps every `loc = 'ESCROW'` row whatever its owner. Every other row is cleaned up exactly as before.
- Invariant: an `ESCROW` row is never deleted by cleanup and never loaded by a container (their restores select by
  location); it restores as an `Item` in `ESCROW`.
- Proof: `EscrowLocationTest.anEscrowRowRestoresAsAnItemWithItsLocation`, `cleanupKeepsEscrowRowsWhateverTheirOwner`
  (and an ordinary orphan still deleted), `noContainerLoadsAnEscrowRow`.

## CP-0009 Atomic stack counts

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: `Item.changeCount` reads `_count`, computes and writes it back with no lock (Item.java:451-474), and Mobius calls
  it without the item's monitor (`ItemContainer.addItem`, :230 and :268; the destination of `transferItem`, :376, under
  the source's monitor), so two concurrent changes of one stack can lose one of them, in Mobius today. The Asset Service
  must also take part of a stack without ever racing a retail change (asset-service.md §3.3, §11.15).
- Change: `_count` is `volatile` and `changeCount` updates it by compare-and-set with exactly the clamps it had (Adena
  at `MAX_ADENA`, every count at 0); new `tryDecrease(quantity)` takes the quantity atomically only if at least one unit
  remains. No lock is added, so no lock order changes.
- Invariant: every change to a stack's count is applied exactly once; a debit never empties a stack.
- Proof: `AtomicCountTest.concurrentChangesLoseNothing` (8 threads × 20 000 increments, exact),
  `tryDecreaseRacingIncreasesLosesNothing`, `tryDecreaseLeavesAtLeastOne`, `retailClampsAreUnchanged`.

## CP-0010 Retired items

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`, `java/org/l2jmobius/gameserver/entity/itemcontainer/ItemContainer.java`, `java/org/l2jmobius/gameserver/entity/itemcontainer/Inventory.java`, `java/org/l2jmobius/gameserver/entity/actor/Player.java`, `java/org/l2jmobius/gameserver/entity/itemcontainer/PlayerInventory.java`
- Reason: when the Asset Service takes a whole object out of a live container, the item's old row still says where it
  was until the operation commits, and Mobius may still hold references to the object (a scheduled equip,
  UseItem.java:350, Player.java:2257-2267; a pending transfer; a lazy save). Any of them acting on it, or writing it,
  would recreate a second representation of an asset that now belongs to a custody (asset-service.md §3.3, §11.20).
- Change: `Item.retire()` / `isRetired()`. A retired item is never written (`updateDatabase` returns at once, under its
  write lock), and is refused by `ItemContainer.addItem(Item)`, `transferItem` (checked under the source's monitor, with
  the presence check), `destroyItem`, both `Inventory.dropItem` overloads (under the item's monitor),
  `Inventory.setPaperdollItem` and `Player.useEquippableItem`. Nothing changes for an item that is not retired.
  `PlayerInventory.transferItem` dereferenced the item `super.transferItem` returns, which upstream already returns as
  null when the item left the container meanwhile (a NullPointerException in retail on that race); it now checks for
  null, as its `destroyItem` and `dropItem` siblings already do.
- Invariant: after `retire()`, no Mobius operation moves, destroys, drops or equips the item, and no Mobius write reaches
  its row.
- Proof: `RetiredItemTest.aRetiredItemIsNotTransferred`, `aRetiredItemIsNotDestroyed`, `aRetiredItemIsNotDropped`,
  `aRetiredItemIsNotAddedToAnotherContainer`, `aRetiredItemIsNotEquipped`, `aRetiredItemIsNeverWritten`.

## CP-0011 Object ids released after their row is deleted

- Files: `java/org/l2jmobius/gameserver/managers/ItemManager.java`, `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: `ItemManager.destroyItem` released the item's object id at once (ItemManager.java:177), before its caller
  deleted the row (`ItemContainer.java:235`, `:385`, `:450`), and a failed delete is only logged (Item.java:1769): the id
  manager could hand the id to a new item while a row still held it. The Asset Service keeps escrowed and mailed rows by
  object id, so an id must stay reserved exactly while a row has it (asset-service.md §11.6, ASSET-R24, INV10).
- Change: `destroyItem` asks the item to release its id when its row is gone (`releaseObjectIdWhenRowDeleted`): at once
  if the item has no row, as before; otherwise inside `removeFromDb`, right after the `items` row's DELETE succeeded,
  under the item's write lock. A failed delete releases nothing.
- Invariant: an object id is in use while any `items` row has it.
- Proof: `IdReleaseTest.aDestroyedItemsIdIsReservedUntilItsRowIsDeleted`, `aFailedDeleteKeepsTheIdReserved` (the delete
  refused by a trigger: row kept, id kept), `anItemWithoutARowIsReleasedAtOnce` (retail's immediate release).

## CP-0012 The castle treasury

- Files: `java/org/l2jmobius/gameserver/mechanics/siege/Castle.java`
- Reason: `addToTreasuryNoTax` read, changed and wrote `_treasury` with no lock (Castle.java:333-373), so tax income
  racing a chamberlain's withdrawal could lose one of them or overdraw the treasury, and the row was written from
  whatever `_treasury` held at that moment. The Asset Service debits and credits treasuries for Crown Law and must
  serialise with Mobius on them (asset-service.md §3.3, §11.10).
- Change: `_treasury` is `volatile` and changed by compare-and-set, with retail's refusal of an overdraw (`false`) and its
  clamp at `MAX_ADENA` unchanged; the row is written under a new `treasuryLock()` from the value in memory once the lock
  is held, so the last writer writes the last value. `addToTreasury` (the tax skims) is unchanged and funnels into it.
- Invariant: every treasury change is applied exactly once, the treasury is never below 0, and after the last writer
  releases the lock the row equals memory.
- Proof: `TreasuryTest.concurrentIncomeAndWithdrawalsLoseNothingAndTheRowEqualsMemory` (upstream lost a withdrawal:
  1 006 002 for 1 006 000), `anOverdrawIsRefusedUnderRace`, `retailClampIsUnchangedAndTheLockExists`.

## CP-0013 Item variables changed in one step

- Files: `java/org/l2jmobius/gameserver/mechanics/variables/AbstractVariables.java`, `java/org/l2jmobius/gameserver/mechanics/variables/ItemVariables.java`
- Reason: every `set` overload tracked the change under the save lock and then set the value after releasing it
  (AbstractVariables.java:53-121), so a save, or the Asset Service capturing an item's variables, could see the change
  tracked but the value not yet set and lose it; `merge` bypassed tracking entirely (StatSet.java:82-85); and an item's
  scheduled save (ItemVariables.java:135) could write its variables after the Asset Service had taken the item
  (asset-service.md §3.3, §11.12).
- Change: all ten `set` overloads, `set(name, value, markAsChanged)` and `merge` track and set in one step under the save
  lock; new `retire()` / `isRetired()`: a retired object refuses `set`, `remove` and `merge`, and `ItemVariables` never
  saves it. Nothing changes for variables that are not retired.
- Invariant: a capture under the save lock sees either neither or both of a change's tracking and value; a retired
  object's rows are written by nothing but the Asset Service's transaction.
- Proof: `ItemVariablesTest.aCaptureNeverSeesATrackedChangeWithoutItsValue` (upstream: `true=ABSENT`, a tracked change
  without its value), `aRetiredVariablesObjectRefusesWritesAndNeverSaves`.

## CP-0014 Service mail adopted, and never expiring

- Files: `java/org/l2jmobius/gameserver/managers/MailManager.java`, `java/org/l2jmobius/gameserver/taskmanagers/MessageDeletionTaskManager.java`
- Reason: the Asset Service writes service mail in its own transaction, so `MailManager` (which only knows messages it
  loaded at boot or sent itself, MailManager.java:53-75, :157-177) must adopt a committed message; and service mail must
  never expire, because expiry of sender -1 mail with the receiver offline destroys the attachments
  (MessageDeletionTaskManager.java:84-104) and the client cannot show an expiry after 2038 (asset-service.md §2.1,
  §11.19).
- Change: `MailManager.adopt(messageId)` reads the committed row as `load()` does, keeps it if absent (idempotent), tells
  an online receiver and registers it with the deletion task; `MessageDeletionTaskManager.add` ignores the service-mail
  expiration (`ServiceMail.EXPIRATION`, 2038-01-19), so neither adoption nor the boot load ever schedules it. Every other
  message is scheduled as before.
- Invariant: a committed service mail is in `MailManager` once, and is never deleted by expiry.
- Proof: `ServiceMailAdoptionTest.aCommittedMessageIsAdoptedOnce`, `aServiceMailIsNeverScheduledForExpiry`,
  `retailMailStillExpires`.

## CP-0015 Claims of service mail recorded

- Files: `java/org/l2jmobius/gameserver/network/holders/MailMessage.java`
- Reason: retail's claim (RequestPostAttachment.java:191-232) moves a mail's attachments into the receiver's inventory and
  clears them through `MailMessage.removeAttachments`, telling nobody; the Asset Service must know a service mail was
  claimed (its delivery's `claimed_at`, asset-service.md §2.1, §11.23, INV12), and a character's deletion is refused
  while one is unclaimed.
- Change: when `removeAttachments` clears a sender -1 message's attachments, it calls
  `ServiceMail.attachmentsCleared(messageId)`, which sets `claimed_at` on the message's delivery if it has one and does
  nothing when Living Chronicle is not running (the `truth` profile). Retail's claim is otherwise unchanged.
- Invariant: a service mail whose attachments retail has cleared has `claimed_at` set (or the census sets it).
- Proof: `ServiceMailClaimTest.claimingTheAttachmentsSetsClaimedAt` (the claim as RequestPostAttachment performs it),
  `anotherSystemMailHasNoDeliveryAndNothingChanges`.

## CP-0016 The deletion guard

- Files: `java/org/l2jmobius/gameserver/network/GameClient.java`, `java/org/l2jmobius/gameserver/network/clientpackets/CharacterDelete.java`
- Reason: deleting a character removes its own items (GameClient.java:439-455) and leaves owner -1 rows, so service mail
  owed to a deleted character could never be claimed, and a character whose items an operation has taken but not yet
  committed could be deleted mid-operation (asset-service.md §2.1, §11.21, ASSET-R27).
- Change: `markToDeleteChar` marks the character through `DeletionGuard.markForDeletion`, which in one transaction locks
  the characters row, refuses while it has unclaimed service mail or a pending mark, and otherwise sets `deletetime`;
  with `DeleteDays = 0` the immediate deletion follows the guard's mark. A refusal answers 3, which `CharacterDelete`
  sends as `CharDeleteFail(REASON_DELETION_FAILED)`. When Living Chronicle is not running the guard marks exactly as
  retail did. The deletion time is now computed in `long`: upstream's `DELETE_DAYS * 86400000` overflowed `int` for 25
  days or more.
- Invariant: a character marked or deleted has no unclaimed service mail and no pending mark; service mail is never
  written to a marked character (the release refuses it under the same row lock).
- Proof: `DeletionGuardTest.aCharacterWithUnclaimedServiceMailIsNotMarked` (and marked once the mail is claimed),
  `aCharacterWithAPendingMarkIsNotMarked`, `aMarkedCharacterReceivesNoServiceMail`; on upstream both refusals failed.

## CP-0017 The taking seams

- Files: `java/org/l2jmobius/gameserver/entity/itemcontainer/ItemContainer.java`, `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`, `java/org/l2jmobius/gameserver/mechanics/siege/Castle.java`
- Reason: the Asset Service takes items out of live containers under Mobius's own locks (asset-service.md §3.3), but
  Mobius keeps what it needs private: a container's `removeItem` is protected (its overrides unequip and clear the Adena
  caches, Inventory.java:891, PlayerInventory.java:770), an item's write lock (`_dbLock`, Item.java:167) is private, and a
  treasury change always writes its row (Castle.java:333-373), where the service writes the row in its own transaction.
- Change: `ItemContainer.removeForAsset(item)` (the virtual `removeItem`, then `refreshWeight`); `Item.awaitWrites()`
  (take and release the write lock) and `Item.writeLock()`; `Castle.tryDebitTreasury(amount)` and
  `creditTreasuryInMemory(amount)`, compare-and-set only, a credit past `MAX_ADENA` refused. Nothing calls them but the
  Asset Service; Mobius's behaviour is unchanged.
- Invariant: the service takes items and treasury amounts exactly as Mobius's own code would, without new locks.
- Proof: `TakingSeamsTest.anItemTakenForAnAssetLeavesItsContainerAsRetailRemovalDoes`,
  `awaitingWritesWaitsForAWriteInProgress`, `aTreasuryChangesInMemoryOnlyAndRefusesAnOverdrawOrAPassOverTheCap`.

## CP-0018 The login wait

- Files: `java/org/l2jmobius/gameserver/network/GameClient.java`
- Reason: a login restores a character's items from rows with plain reads that do not wait for an uncommitted change
  (Player.java:7357-7360); while an asset operation has taken an item in memory and not yet committed or refunded, the
  item's row still says where it was, so a login in between would restore a second, live `Item` (asset-service.md §2.4,
  §11.22, ASSET-R26).
- Change: `GameClient.load` waits, before `Player.load`, while `lc_asset_pending` marks the character
  (`PendingMarks.awaitNone`: a plain read polled every 50 ms, taking no lock); if a mark outlives four times
  `UncertainCommitLookupMillis`, the login is refused and the character not loaded. When Living Chronicle is not
  running nothing waits.
- Invariant: no character is loaded while an operation that took its items is pending.
- Proof: `LoginWaitTest.aLoginWaitsWhileAnOperationOfTheCharacterIsPending` (upstream loaded it at once),
  `aLoginIsRefusedIfTheMarkOutlivesTheBound` (upstream loaded it), `anUnmarkedLoginDoesNotWait`,
  `markingCommitsOnItsOwnConnection`.

## CP-0019 A row the Asset Service wrote

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: when the Asset Service takes part of a stack that Mobius has never saved, its transaction must insert the
  stack's row from memory (else a crash would leave the taken part in escrow and no debited stack in the database:
  Adena from nowhere, asset-service.md §3.3 step 2); Mobius then must know the row exists, or its next write would
  insert a second one (Item.java `insertIntoDb`, `_existsInDb`, private).
- Change: `Item.markRowWritten()` sets `_existsInDb`; the service calls it after its commit, holding the stack's write
  lock. Nothing else calls it.
- Invariant: after the service has written a stack's row, Mobius updates that row and never inserts another.
- Proof: `StackDebitTest.aStackNeverSavedGetsItsRowInTheOperationAndMobiusKnowsIt`.

## CP-0020 Clan warehouse events for operations that happen

- Files: `java/org/l2jmobius/gameserver/entity/itemcontainer/ClanWarehouse.java`
- Reason: `ClanWarehouse.addItem`, `destroyItem` and `transferItem` notified scripts before calling `super`, so an
  operation `super` refused (now including any on an item the Asset Service retired, CP-0010) still fired its event, and
  `transferItem` dereferenced `getItemByObjectId(objectId)` unchecked: a NullPointerException in retail when the item was
  already gone (asset-service.md §3.3, the part 2 obligation).
- Change: each calls `super` first and notifies only when the operation happened, with the same event and arguments;
  `transferItem` checks for a missing item. Retail operations that succeed fire exactly the events they fired before.
- Invariant: a clan warehouse event means its operation happened.
- Proof: `ClanAndTreasuryDebitTest.clanWarehouseEventsFireOnlyForOperationsThatHappen` (a refused destroy fires nothing; a
  missing object's transfer returns null).

## CP-0021 Existence counters: the item hooks

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: asset-service.md §2.6 (revision 13) compares the census across a clean restart with existence counters kept
  from the changes themselves (INV06), and Mobius has no hook that counts an item's quantity entering or leaving the
  holdings the census counts.
- Change: `setCount`, `changeCount` and `setItemLocation` settle the object's counted quantity (its count while its
  location is one the census counts, otherwise nothing) under a leaf lock of `EconomyCounter`, never an item's or a
  container's monitor; `changeCount` records what its clamps took or refused apart from the counts; `restoreFromDb` marks
  a restored object counted at its count without posting; the Asset Service's `tryDecrease` (CP-0009) and `retire`
  (CP-0010) hand their quantity to a custody under the same lock, posting only what the object was not counted with. Until
  `EconomyCounter.activate`, every hook returns at once, so `truth` runs as upstream.
- Invariant: when the server is quiet, the counters equal the census of the counted holdings plus what the service and
  the SQL sites posted.
- Proof: `ExistenceCounterTest` (creation, merge, destroy, drop and pickup, moves between counted holdings, a sale to the
  buyback list, a restore then a change, the service's takes, a clamp, a count and a location changing together and a
  stack decrease racing a Mobius change, each exact; an inactive counter posts nothing).

## CP-0022 Existence counters: the treasury, saved-ground and bid hooks

- Files: `java/org/l2jmobius/gameserver/mechanics/siege/Castle.java`, `java/org/l2jmobius/gameserver/managers/ItemsOnGroundManager.java`, `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`, `java/org/l2jmobius/gameserver/entity/item/auction/ItemAuction.java`, `java/org/l2jmobius/gameserver/entity/residences/ClanHallAuction.java`
- Reason: a castle treasury, the saved ground list and the open bids of both auctions are census holdings
  (asset-service.md §2.6) that no item call changes, so their changes need hooks of their own (§11.24).
- Change: `Castle.addToTreasuryNoTax` posts the change it applied and records its clamp apart; `ItemsOnGroundManager.save`,
  `removeObject` and `load` change the saved list and the item's membership together under the item's leaf lock
  (`Item.setOnSavedGround`, `restoreOnSavedGround`), so the item settles its counted quantity as for a location (CP-0021); the bids are counted where their rows are written:
  `ItemAuction.updatePlayerBidInternal` and `storeMe` (an auction's state says when its winning bid was paid) and
  `ClanHallAuction.updateInDB`, `cancelBid` and `removeBids` run their statements through `Census.counting`, so a write
  that fails counts nothing; `ClanHallAuction.returnItem` records the part of a credit past MAX_ADENA it clamps away.
  No retail behaviour changes; with the counters inactive every hook returns at once. Codex round amd11 found the first
  design (posts beside the in-memory changes) losing a drop that raced a pickup and posting a bid change whose write
  failed.
- Invariant: a move between one of these holdings and an inventory or warehouse nets to nothing in the counters.
- Proof: `HoldingHooksTest` (tax in, a withdrawal out, the service's treasury commitment and return, a clamp; a drop to
  and a pickup from the saved ground, and an entry racing a removal and a destruction, red on the first design at
  iteration 33; an item-auction bid placed, raised, cancelled and won; a clan-hall bid placed and cancelled; an ended
  clan-hall auction paying the seller's share and the lease and refunding a loser; a credit past the cap recorded as
  clamped, red without the record).

## CP-0023 A clan-hall re-bid pays the difference over the clan's standing bid

- Files: `java/org/l2jmobius/gameserver/entity/residences/ClanHallAuction.java`
- Reason: `setBid` subtracted a standing bid only for the current highest bidder (ClanHallAuction.java:272), and
  `updateInDB` replaces a clan's standing bid with its new one (344-354, 386-390): a clan outbid and bidding again paid
  its whole new bid and lost the standing one (asset-service.md §11.26, confirmed by Codex round amd10).
- Change: the Adena taken is the new bid less the clan's own standing bid, if it has one. For the highest bidder raising
  its own bid this is what upstream charged.
- Invariant: what a clan has paid into an auction equals its standing bid.
- Proof: `ClanHallRebidTest.aClanOutbidPaysOnlyTheDifferenceOverItsStandingBid` (red on upstream: 60 000 paid for a
  40 000 bid).

## CP-0024 The saved ground: no placed tickets, whole counts

- Files: `java/org/l2jmobius/gameserver/managers/ItemsOnGroundManager.java`, `java/org/l2jmobius/gameserver/managers/MercTicketManager.java`
- Reason: with `SaveDroppedItem`, every ground item but a cursed weapon was saved (ItemsOnGroundManager.java:216-219),
  placed mercenary tickets included, which `ItemsOnGroundManager.load` and `MercTicketManager.load` both rebuilt at
  startup, a duplicate per restart; and the load read a stack's count with `getInt` (110-112), so a stack past 2^31
  came back as a single unit (asset-service.md §11.27).
- Change: `save` keeps placed tickets and cursed weapons out of the ground list (their managers own them; the cursed
  weapons were already never written); the ticket check is the static `MercTicketManager.ticketCastleId`, since that
  manager drops its restored tickets while it is still being built; the load skips a ticket row saved before this patch
  and deletes it, counted out of the saved ground; the load reads the count as a `long`.
- Invariant: a placed ticket exists once after a restart; a saved stack comes back whole.
- Proof: `SavedGroundTest` (a ticket is not saved and posts nothing; a ticket row saved before is not restored and goes,
  counted; a saved stack of 3 000 000 000 Adena, restored as 1 by upstream, comes back whole and posts nothing).

## CP-0025 Existence counters: SQL sites counted in their own transaction

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`, `java/org/l2jmobius/gameserver/mechanics/script/LongTimeEvent.java`, `java/org/l2jmobius/gameserver/mechanics/olympiad/Hero.java`, `java/org/l2jmobius/gameserver/entity/actor/holders/player/CursedWeapon.java`, `java/org/l2jmobius/gameserver/managers/CursedWeaponsManager.java`, `java/org/l2jmobius/gameserver/managers/CastleManager.java`, `java/org/l2jmobius/gameserver/network/GameClient.java`, `java/org/l2jmobius/gameserver/managers/DatabaseIdManager.java`, `java/org/l2jmobius/gameserver/managers/ItemAuctionManager.java`, `java/org/l2jmobius/gameserver/entity/item/auction/ItemAuctionInstance.java`, `dist/game/data/scripts/handlers/chat/commands/admin/AdminRepairChar.java`
- Reason: these statements write census rows directly (an ended event's and a new hero period's items, an offline
  owner's cursed weapon and circlet, a deleted character's items, the startup cleanup, a deleted or expired item auction's
  bids, the admin repair), so no item hook sees them (asset-service.md §2.6, §11.24; Codex round amd10 found a count
  taken before the statement posting a deletion that then fails).
- Change: each runs through `Census.counting`: in a transaction of its own, the rows it writes are locked and counted
  before and after it, and the difference is posted only when it commits; a statement that fails rolls back and posts
  nothing, and the connection is handed back in autocommit; a connection already in a transaction is refused; the
  transaction writes its own row to `lc_economy_statement` (V018), which decides a commit whose acknowledgement is lost,
  read under its lock on a fresh connection (Codex amd12: quantities could be changed meanwhile by another writer); the
  loaded copies of the rows it locks are held under their leaf locks from the locking read to the posting, a copy of a
  deleted row counting out what its copy counted, never the row's stale quantity, and then told to stop counting
  (`Item.forgetCounted`, `countedQuantity`, `isForgotten`); a deleted row whose object memory had counted out before
  its row was written counts nothing more (`Item.settleCounted` marks it as it settles to nothing with a row;
  `updateInDb`, `insertIntoDb` and `removeFromDb` report what they wrote through `EconomyCounter.rowWritten`; Codex amd13: retail destruction counts out and leaves the world before deleting its row); a
  commit that fails is rolled back before it is resolved, never committed by a later autocommit reset; the counted-out
  mark is read before the commit, under the row lock Mobius's own deletion waits behind, and a committed statement is
  posted even when the connection fails afterwards (Codex amd14); a row write clears the mark only when what it wrote
  holds nothing counted, and a destruction during an object's first insert leaves it (`Item._inserting`, Codex
  amd15-16), a failed insert too, and the object stays one whose row may exist until an insert of it succeeds, so a
  later destruction leaves the mark as well, since a lost acknowledgement may have left its row (Codex amd17-18; the
  orphan row upstream then never deletes is reported by the restart census); a commit whose
  outcome cannot be read makes the counters uncertain, so the boundary takes no census; any commit that ended hands
  the connection back in autocommit (Codex amd17). A
  restore racing a deletion of the same row (upstream's ghost race) is not settled: the restart census reports the ghost. An auction's row and its bids are
  deleted in one counted transaction, as its winning bid stops being taken off when the auction's row goes. With the
  counters inactive each statement runs as upstream.
- Invariant: across each of these statements the existence counters move exactly as the census does.
- Proof: `SqlSitesTest` (each of the ten sites, the counters' change equal to the census's); `CensusTest` (a failed
  statement posts nothing and rolls back; a connection in a transaction is refused and its work kept apart; a lost
  acknowledgement resolved both ways, and decided by the statement's own row while another deletion of the same rows
  commits meanwhile; a loaded copy of a deleted row stops counting, -60 for a 30-unit copy before; a copy destroyed while
  its row's deletion runs waits and counts nothing; a copy that counted itself out before its row went is not counted
  twice; the last three red on the previous code; a partly consumed copy's row counted as memory holds it, an item
  destroyed and gone from the world before its row went not counted twice, a commit failing with its transaction open
  committing nothing: these three red on round 12's code; a resolved commit handing back an autocommit connection, red
  on round 16's code); `ExistenceCounterTest.aFailedInsertKeepsTheMarkItsRowMayNeed`
  (red on round 16's code) and `aDestructionAfterAFailedInsertLeavesItsMark` (red on round 17's code).

## CP-0026 A clan-hall bid is refunded only once its row is gone

- Files: `java/org/l2jmobius/gameserver/entity/residences/ClanHallAuction.java`
- Reason: `cancelBid` and `removeBids` logged a failed `DELETE` of the bid rows and went on to refund them
  (ClanHallAuction.java:494-512, 403-431): the bid stayed and was paid back, again at its next removal
  (asset-service.md §11.28, Codex round amd11).
- Change: each returns after a failed deletion, refunding nothing.
- Invariant: a clan-hall bid is refunded at most once, and only once its row is gone.
- Proof: `HoldingHooksTest.aClanHallBidWhoseRowCannotBeDeletedIsNotRefunded` (a trigger refuses the deletion; upstream
  refunded the bid and the counters moved by -2 000; now nothing moves).

## CP-0027 Living Chronicle's seams in boot and shutdown

- Files: `java/org/l2jmobius/gameserver/GameServer.java`, `java/org/l2jmobius/gameserver/Shutdown.java`
- Reason: Mobius has no module seams in its boot or shutdown (asset-service.md §11.25): the startup census must precede
  every other startup step (Mobius's own cleanup is a counted site), recovery must finish before any character loads
  (offline traders and players are restored before the login listener opens), and the shutdown boundary must follow
  Mobius's last save and precede the database closing.
- Change: `GameServer` calls `LivingChronicle.atDatabase()` right after `DatabaseFactory.init()` (before
  `ThreadPool.init`: it uses the database only) and `LivingChronicle.beforeLogin()` once the mail managers are up and
  before the `ON_SERVER_START` listeners are told (a datapack script could otherwise act before recovery), the shutdown
  hook is registered and offline traders and players are restored, so a recovery that fails stops the start;
  `Shutdown` calls `LivingChronicle.atShutdown()` after `saveData()` and before `DatabaseFactory.close()`; a fault inside
  the boundary is logged and takes no census, never stopping Mobius's database close and backup. In `truth` (and while
  no `lc.profile` is set, until the profile check exists) each returns at once.
- Invariant: in a profile that starts Living Chronicle, no character loads before recovery, and every census follows or
  precedes exactly the Mobius steps §2.6 names.
- Proof: `LivingChronicleBootTest` (truth starts nothing; an unknown profile refuses; dev counts, recovers and keeps the
  boundary, a clan warehouse change in memory written there and conserved across the restart; a boundary whose write
  fails takes no shutdown census, so the next start is a rebase; a fault in the boundary does not escape; uncertain
  counters still write the clan warehouses, taking no census); `SeamsTest` reads the compiled order (the census right
  after `DatabaseFactory.init`, recovery before the server-start listeners, the shutdown hook and the offline traders,
  the boundary right after
  `saveData` and before `DatabaseFactory.close`; 3/3 fail with CP-0027 reverted); every server-booting test boots
  through the seams in truth.

## CP-0028 The task pools can be waited for

- Files: `java/org/l2jmobius/commons/threads/ThreadPool.java`
- Reason: `ThreadPool.shutdown()` only calls `shutdownNow()` (ThreadPool.java:224), which interrupts and returns: a task
  already running can still change a holding after the shutdown boundary's census (asset-service.md §2.6, Codex round
  amd15).
- Change: `awaitTermination(millis)` waits until every pool has terminated and says whether all did; `shutdown()` is
  unchanged.
- Invariant: the shutdown census is taken only once no task pool runs.
- Proof: `SeamsTest.theBoundaryWaitsForTheTaskPoolsMobiusStopped` (the boundary follows `ThreadPool.shutdown` and calls
  `awaitTermination`); `LivingChronicleBootTest.aBoundaryWhileMobiusTasksStillRunTakesNoShutdownCensus`.

## CP-0029 A clan hall put up for sale pays its deposit

- Files: `java/org/l2jmobius/gameserver/entity/residences/ClanHallAuction.java`, `java/org/l2jmobius/gameserver/entity/actor/instance/Auctioneer.java`
- Reason: retail's sale page (`data/html/auction/AgitSale1.htm`) says the deposit, the hall's lease, "is taken out of the
  clan warehouse at the moment the clan hall is put up for auction and is returned if the auction is completed
  normally", and is lost on a cancel or an auction without bids. Upstream's `confirmAuction` took nothing, and
  `endAuction` still paid the lease back (ClanHallAuction.java:473): every completed sale created the lease in Adena
  from nothing (asset-service.md §11.29).
- Change: `confirmAuction` destroys the deposit from the seller's clan warehouse before it registers the sale, and
  returns what it did (`Confirmation`: confirmed, not enough Adena, already for sale, not written, unresolved); the
  Auctioneer keeps the pending sale and says which: retail's not-enough-Adena message, "Invalid auction" when nothing was
  taken, and for an unresolved write that the deposit is held until the next start checks the row. The destruction is retail's own (`destroyItemByItemId`, as the
  bids' `takeItem` pays): a clan holding exactly the lease pays its whole stack, which shares upstream's race of a
  whole-stack destruction with an addition into the stack (asset-service.md §11.32). Every auctioneer keeps its own
  pending sales (Auctioneer.java:57), so two members at two auctioneers can confirm two sales of one hall: one lock
  serialises every confirmation and every deletion of a sale (CP-0031), and a hall listed, or whose row still exists,
  is refused before anything is taken. `endAuction`, `cancelAuction` and the auction without bids already return the
  deposit only when the auction completes normally.
- Invariant: what a seller is paid back as its deposit, it paid, and exactly the deposit; a completed sale creates no Adena.
- Proof: `ClanHallDepositTest` (red on upstream: the deposit is not taken, a clan that cannot pay registers its sale, a
  completed sale leaves the seller 500 000 Adena richer than its 90 %, a cancel keeps nothing; red without the lock
  and checks: a second confirmation paid a second deposit and listed the hall twice, a hall whose row remained was put
  up again; `aClanHoldingExactlyTheDepositPaysIt` guards the page's rule). An auction registered before this patch paid no
  deposit and is still paid one at its end: that sale is a source, which the counters post exactly.

## CP-0030 An ended event's items go wherever they are loaded

- Files: `java/org/l2jmobius/gameserver/mechanics/script/LongTimeEvent.java`, `java/org/l2jmobius/gameserver/network/holders/MailMessage.java`, `java/org/l2jmobius/gameserver/managers/MailManager.java`, `java/org/l2jmobius/gameserver/entity/World.java`
- Reason: `destroyItemsOnEnd` destroyed an ended event's items from online inventories (LongTimeEvent.java:551) and
  deleted every other row of the item id by SQL (557); a copy loaded elsewhere kept living in memory: a clan warehouse
  (always loaded), an online player's warehouse, freight or buyback list, a pet (summoned, or dead and kept loaded for an
  owner who logged out, Pet.java:1157-1165, so a returning owner finds it, Player.java:7410), a loaded mail attachment,
  the ground. It stayed usable until the restart, and a claim, a withdrawal or a buyback carried it into an inventory
  (asset-service.md §11.30). Online inventories were not swept whole either: of a non-stackable item the destruction
  asks for every copy, worn ones included (Player.java:3726), from the unequipped copies alone (PlayerInventory.java:657),
  so a copy worn beside another left both. And a message whose attachments the event emptied, loaded or by the SQL
  deletion, still said it had some, so retail's claim charged its cash on delivery for an empty container
  (RequestPostAttachment.java:118-186).
- Change: before the deletion, every copy of the item id in those containers, and every copy an online inventory still
  holds after upstream's destruction, is destroyed through its container, and a copy on the ground decays as the
  auto-destroy task decays it; a message left without attachments, by the sweep or by the deletion, has them removed
  (its row's `hasAttachments` set false whether or not the mail manager is loaded, a loaded message told through
  `removeAttachments`; the deletion reads which messages it empties inside its own counted work, under the rows'
  locks when the counters run); every pet `World` keeps is swept, through `World.getPets`. `MailMessage.getLoadedAttachments` reads a message's attachments without loading them, and
  `MailManager.isLoaded` whether the mail manager exists without creating it: an event that ended while the server was
  down is swept during script loading (LongTimeEvent.java:128), before `MailManager` has its place in the boot order
  (GameServer.java:424), and a server with `AllowMail` off never loads it, so the sweep reads mail only once it is
  loaded and no attachment can be loaded before that.
- Invariant: after an event ends, no copy of its items is left in a container Mobius has loaded, but for one moved
  concurrently from a container not yet swept into one already swept; no message claims attachments it lost to it.
- Proof: `EventEndTest` (red with the sweep's call removed: the clan warehouse, the player warehouse, a summoned pet and
  a loaded mail attachment kept their copies, an inventory kept both copies of a sword when one was worn, a medal on the
  ground stayed, a dead pet of a logged-out owner kept its copy; red without the buyback sweep and the mail emptying: the buyback list kept its copy, a loaded and an
  unloaded cash-on-delivery mail kept their attachment flag; two unworn copies and a mail keeping its other
  attachments pass on upstream and guard it), each moving the counters by the destroyed quantity once; truth's boot
  report is unchanged (`PausedWorldTest`: with the mail manager read through `getInstance`, its load line moved ahead
  of the event's).

## CP-0031 A player's clan-hall sale ends on its date and leaves no row

- Files: `java/org/l2jmobius/gameserver/entity/residences/ClanHallAuction.java`, `java/org/l2jmobius/gameserver/managers/ClanHallAuctionManager.java`
- Reason: a sale put up by a player is never scheduled to end (`startAutoTask` runs only for a sale loaded at startup,
  ClanHallAuction.java:142-146; the constructor of a new sale, 149, and `confirmAuction`, 522, do not call it), so it
  ends only after a restart. And a player's sale that ends without bids is dropped from memory only ("THIS MUST BE
  CONFIRMED", ClanHallAuction.java:460-467): its `auction` row stays, so the hall's next sale fails its `INSERT` on the
  primary key (logged, 545) and lives in memory only; at a restart the stale row comes back and ends again. With CP-0029
  that next sale has paid a deposit (asset-service.md §11.31).
- Change: `confirmAuction` writes the sale's row before it lists the sale, under the confirmations' lock. A failed write
  is resolved by reading the row on a fresh connection (a lost acknowledgement may have written it), up to five times
  a second apart: present, the sale is listed; absent, it is refused and the deposit given back; never readable, it is
  refused and the deposit kept, logged (a refund of a written row would pay the deposit twice; a restart loads the row
  with its deposit paid, or finds none). It then schedules the sale's end, and keeps the task: deleting a sale
  (`deleteAuctionFromDB`, which now deletes the row before the listing, under the same lock) marks it over and cancels
  its task; `endAuction`, `cancelAuction`, `setBid` and `cancelBid` run under the auction's monitor and do nothing for a
  sale already over, so a timer that outlives a cancel neither pays the cancelled seller nor deletes the hall's next
  sale (both delete by hall), and a bid that waited on the monitor while the sale closed takes nothing. With sales
  closing on timer threads while game threads read the listing, the manager's `ArrayList` (read without a lock, so an
  iteration threw `ConcurrentModificationException`) is a `CopyOnWriteArrayList`, and `getAuction` reads one snapshot
  rather than an index another thread may shift. A sale closes
  only once its bids are gone: a cancel and an end delete the bid rows first, and while that fails (CP-0026 refunds
  nothing then) the sale stays open, nothing is paid and the hall changes no owner; `cancelAuction` returns whether it
  cancelled, and the Auctioneer tells the seller either way (upstream always said "Your auction has been canceled"), so
  a cancel is asked again; an end is retried a minute later. The branch without bids deletes the auction as a cancelled one is.
- Invariant: a sale ends on its date, once; a hall's sale is listed exactly when its row exists, but after a failed
  `DELETE`, which the next confirmation then refuses.
- Proof: `ClanHallDepositTest`: `aConfirmedSaleEndsOnItsDate` (red on upstream: still listed after its end date),
  `aSaleWithoutBidsKeepsItsDepositAndFreesTheHallForItsNextSale` (red on upstream: the row stayed and the next sale's
  end date was never stored), `aCancelledSaleIsNotEndedByItsTimer` (red with the timer alone: the cancelled seller was
  paid 527 000), `aCancelledSalesTimerLeavesTheNextSaleAlone` (red: the next sale's row was deleted),
  `aSaleWhoseRowCannotBeWrittenIsRefusedAndRefunded` (red on upstream: listed, the deposit kept),
  `aSaleWhoseRowCannotBeResolvedIsNotListedAndKeepsItsDeposit` (red when an unreadable row counted as written: listed),
  `aBidAfterTheSaleClosedTakesNothing` (red on upstream: 30 000 taken into a deleted auction, its bid row stranded),
  `readingTheSalesWhileOneCloses` (red on the ArrayList: ConcurrentModificationException, found by a gate in which a
  timer closed a sale while the test read the listing),
  `aCancelWhoseBidsCannotBeDeletedCanBeRetried` and `anEndWhoseBidsCannotBeDeletedIsRetried` (red when the sale closed
  before its bids: the bid stranded, the sale closed for good), `theAuctioneerSaysWhenACancelFailed` (through the
  Auctioneer's bypass, its messages recorded: red on upstream's message, the sale still open).

## CP-0032 The drop seam

- Files: `java/org/l2jmobius/gameserver/entity/actor/Attackable.java`, `java/org/l2jmobius/gameserver/entity/actor/Npc.java`, `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: the Loot Charter's routing (pact.md §4.5) and Aden Remembers' relocation (aden-remembers.md §4.3.1) must take
  a kill's rolled drops before retail drops or auto-loots them, but `doItemDrop` computes the death-drop list and hands
  it out in one loop (Attackable.java:1176-1204), with nothing in between (blueprint 03 §4.2; asset-service.md §11.33).
- Change: in the branch whose drop owner is a player, the computed list goes through `DropSeam.route` before the loop.
  With no router installed (always in `truth`) it returns the very list after one volatile read; installed routers run
  in order on the dropping thread, each taking positions its own committed operation took, and retail drops the rest
  unchanged. A router that throws may have committed first, so the seam asks it what its operation committed, read
  from durable state (`DropRouter.committed`): that stays taken and the rest goes on to the next router and retail.
  A router that cannot tell yet (the store unreadable) has what it was offered held by the seam, with the kill's
  place, and asked again every ten seconds; once it answers, what it did not commit drops at that place as retail
  drops it, once. A kill takes one of 10 000 slots before any router runs and keeps it while held (02 §6), so a kill
  whose outcome becomes unknown always has room to be held; with no slot free no router runs and retail drops the
  kill. A JVM death loses the kills held, as it loses a kill's drops before any commit (pact.md §4.5). The held drop is
  Mobius's own: `Npc.dropItemAt` is `dropItem`'s body at a given place and instance, validated by
  `Item.dropMeFrom` from that place (`dropMe` is `dropMeFrom` from the dropper's own), and `dropItem` calls it with the
  NPC's own (its draws unchanged), so the drop stays a scanned, classified site and lands where the kill was. A held
  kill whose instance is gone when it resolves drops nothing: retail destroys an instance's ground items with it, and
  an item cannot enter an instance that no longer exists, so it would otherwise fall into the world outside. Whenever
  the instance went, the item's own instance after `dropMeFrom` assigns it decides: one that did not enter a non-zero
  instance is not published, and `dropItemAt` destroys it at once (it was never in a holding). That guard is the held
  kill's alone (`requireInstance`, which only the seam sets): retail's own drops, `dropItem`, `dropMe` and a player's,
  publish as upstream does.
  A position outside its list, or null, is ignored. Every epoch keeps its own kill counter for the JVM's life, so no
  key is given twice. The fake-player branch and the spoil list are not offered.
- Invariant: every item a kill rolls is dropped, auto-looted or taken by exactly one committed operation; with no router
  the kill is retail's.
- Proof: `DropSeamTest` (red with the seam ignoring its routers: a router's positions dropped anyway, the second router
  offered everything, kills unnumbered; red as first written, Codex rounds amd26-27: a throwing router's items dropped,
  then all withheld though it committed nothing or only part, then withheld for good while its outcome was unreadable
  or once 10 000 waited (red without the slots);
  a null position losing the rest of the kill, an epoch reinstalled after another reusing `kill/73/1`); a held kill
  drops at its place after its monster moved (the test world has no geodata and no live instance, so that the drop is
  validated from the kill's place and falls in its instance is by construction: `dropMe` is `dropMeFrom` with the
  dropper's own place and instance); a held kill whose instance is gone drops nothing (red without the check: its
  drops fell into instance 0), and `aDropThatCannotEnterItsInstanceIsNotPublished` (red without the check after the
  assignment, Codex round amd31), and `aRetailDropIntoAVanishedInstanceIsPublishedAsUpstreamDoes` (red with that
  guard on every drop, Codex round amd32); `DropLockTest`,
  `PausedWorldTest` and the other behaviour locks unchanged.

## CP-0033 The crafting boundary

- Files: `java/org/l2jmobius/gameserver/managers/RecipeManager.java`
- Reason: PACT's contractual craft (pact.md §4.3) validates, rolls with its own keyed draw and applies through the Asset
  Service, but every retail rule of a craft lives inside one runnable, `RecipeItemMaker` (RecipeManager.java:171-782):
  validation, the success and masterwork draws (:416, :679), the materials (:608-660) and the product (:669-686)
  (blueprint 03 §4.3; asset-service.md §11.34).
- Change: `RecipeManager.Craft` holds them as functions: `refusal` (what `requestMakeItem` and the maker's constructor
  refuse), `successRate`, `masterworkApplies`, `masterworkRatePpm` (min(100, ceil(rarity × CraftMasterworkChanceRate)) ×
  10 000, the probability retail's draw has), `materials`, `product`, `creationPasses` and `consumeStats` (what a craft's
  passes take: with `AltGameCreation` its materials grabbed skill level × GIM at a time, each pass its integer share),
  and `refusal` includes retail's one-craft-at-a-time guard with `AltGameCreation`.
  The maker takes its success rate, its masterwork rule and product, its materials and its stat cost from them; its two
  `Rnd` draws stay where they are, in the same order. `refusal` mirrors the maker's checks, which also send their
  packets and abort; its test pins it to them.
- Invariant: a retail craft draws the same numbers in the same order and makes the same thing as upstream.
- Proof: `CraftBoundaryTest.retailCraftIsUnchanged` (110 crafts recorded on upstream's RecipeManager, each with its
  stream's next draw, so a draw added or removed shows: red with the zero-probability masterwork draw removed);
  `withAltGameCreationTheCallableRulesAreRetailsToo` (red as first written: 174 MP for retail's 165, no busy guard); `theRatesAreRetails`, `materialsAndProductAreRetails`, `refusalMatchesWhatRetailRefuses`,
  `consumeStatsTakesWhatRetailTakes` (red without `Craft`: they do not compile).

## CP-0034 Lifecycles: no runtime reuse of a non-stackable's id

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`,
  `java/org/l2jmobius/gameserver/managers/DatabaseIdManager.java`
- Reason: a lifecycle names a non-stackable item by its object id (asset-service.md §2.2, §11.35), and Mobius gives a
  released id to the next item created (IdManager.java:158-177). A lifecycle cannot be ordered in memory against its
  object's destruction without a lock on hot paths, which 02 §6 forbids; and upstream's used-id scan logs a failed query
  and returns what it has (DatabaseIdManager.java:263-293).
- Change: `Item.releaseObjectIdWhenRowDeleted`, which `ItemManager.destroyItem` calls under the item's monitor right
  after count 0 and the removal from the world (its only caller, CP-0011), asks `LifecycleWriter.keepsDestroyed` first:
  while Living Chronicle runs, a non-stackable's id is kept until the next start (neither released nor left for
  `removeFromDb`) and its destruction offered to the lifecycle writer's bounded queue, with no lookup and no lock but the
  queue's. `DatabaseIdManager.getUsedIds` begins with `LivingChronicle.lifecyclesAtStartup` (after the cleanup,
  whether or not `DatabaseCleanUp` let it run, and before the id set is built; in `truth` it returns at once): the startup pass ends `VANISHED`, in the previous run, every live lifecycle whose object is in no row, writes the
  run's `lc_asset_run` row and starts the run's writer; a failure stops the start. `getUsedIds` adds the live
  lifecycles' ids the pass found, so none is handed out whatever the row scan found.
- Invariant: while Living Chronicle runs, no id a live lifecycle names is ever handed out; a stackable's id, and every
  id in `truth`, is released exactly as upstream.
- Proof: `LifecycleTest.aDestroyedNonStackablesIdIsKeptWhileLivingChronicleRuns`,
  `aTrackedItemThatIsDestroyedEndsDestroyedWithItsFact`, `aCaptureOfferedBeforeItsObjectsDestructionIsBegunThenEndedRowOrNoRow`,
  `aCaptureIsRefusedForAStackableARetiredOrADestroyedItemAndOnAFullQueue` (red with the `Item` hook off: the id
  released, no end, five tests); `theUsedIdsHoldEveryIdALiveLifecycleNamesWhateverTheRowScanFound` and
  `theUsedIdScanRunsThePassWhileLivingChronicleRunsWhateverTheCleanupAndNothingInTruth` (red with the two
  `DatabaseIdManager` lines off; Codex round 43 found the pass at the cleanup's end skipped with `DatabaseCleanUp` off); with the `truth` gate off the shared `truth` boot stops ("the server must not start"); `IdReleaseTest`
  (CP-0011's `truth` behaviour) and `PausedWorldTest` unchanged.

## CP-0035 A raided share's object rebuilt from its row

- Files: `java/org/l2jmobius/gameserver/entity/item/instance/Item.java`
- Reason: a raid's share falls on the ground once its commit is known, keeping each non-stackable object's id, enchant,
  augmentation, elementals and lifecycle (asset-service.md §4.3 `raid`, §11.36); its custody rows were deleted in the
  raid's transaction (INV07: no `Item` exists for an `ESCROW` row). Mobius builds an item only from its row
  (`restoreFromDb`, which marks it counted, CP-0021, and reloads attributes from rows) or as new, and
  `setAugmentation` writes its row at once and notifies scripts.
- Change: `Item.rebuilt(objectId, itemId, count, enchant, type1, type2, mana, time, augmentation, elementals)` builds the
  object in memory only: no row, not counted, `VOID`, owner 0, its augmentation and elementals in its fields, which
  its first save writes (`insertIntoDb`). Nothing is written, nothing counted; nothing else changes.
- Invariant: a rebuilt object enters the counted holdings only as any drop does (a pickup, or the saved ground); `truth`
  never calls it.
- Proof: `RaidTest.aRaidDropsItsShareOnceAfterTheCommitAndLosesTheRestToTheRoad` (red with the enchant not set: 0 for 3;
  red with the after-commit drop off: the sword not on the ground; its pickup saves it as a new row with its enchant);
  the site table classifies `Item.rebuilt` (`item_rebuilt`, SOURCE to GROUND; `AssetEconomySitesTest` red without it).
