# Asset Service part 2: upstream facts at HEAD e6a5fee8 (verified, read-only survey)

Every cited file is still byte-identical to upstream c9ca4c31 (none is in CORE_PATCHES.md yet). CRLF files:
GameClient.java, RequestPostAttachment.java, UseItem.java; the rest LF. Next free core patch number: CP-0008.
check-core-ledger.sh: backticked paths on the `- Files:` line itself; fails on unledgered or stale paths.

| # | Site | Current location | Spec claim |
|---|---|---|---|
| 1 | ItemLocation enum | entity/item/enums/ItemLocation.java:23-36; only valueOf at Item.java:1538 (restore catches, returns null, callers skip); switches at AbstractRefinePacket.java:362, RequestExEnchantItemAttribute.java:103 have defaults | holds; patch: add ESCROW |
| 2 | Item.updateDatabase | Item.java:1472-1512 under `_dbLock` (ReentrantLock :167, taken only there); deletes when owner 0, VOID, REFUND, or count 0 unless LEASE; updateInDb :1656 / insertIntoDb :1689 / removeFromDb :1734 (void, errors only logged) | holds (+ "unless LEASE") |
| 3 | Item._count | plain long :111; setCount :424-433 (clamps < -1 to 0); changeCount :451-474 clamps at MAX_ADENA (Adena only) and 0; unsynchronized writers: ItemContainer.java:230 (merge), :268 (addItem int), :376 (target under the SOURCE's monitor) | §11.15 incomplete: :230 missing |
| 4 | Monitors | transferItem synchronized(source) :340-398 (target looked up outside at :339); destroyItem :427-455; Inventory.dropItem :802-816, :838-858; setPaperdollItem synchronized on the inventory :1072; chain item → inventory → player (sendSkillList Player.java:10490); Player.store synchronized :7621 → item _dbLock | holds |
| 5 | Id release | ItemManager.destroyItem :166-226 releases at :177 before any row delete; deletes come later at ItemContainer.java:235, 385, 450 via removeFromDb (void); IdManager.releaseId :158 no double-release check | holds; also :371→385 and :234→235; removeFromDb must return success |
| 6 | Startup cleanup | DatabaseIdManager.java:67 (keeps owner -1), **:68 deletes owner -1 MAIL rows unless their message has senderId = -1**, :70-71 attribute/elemental orphans, no item_variables cleanup; used ids :122-129 | holds; :68 is a missing fact (service mail must be sender -1) |
| 7 | Castle treasury | Castle.java:83 plain field; addToTreasury :286-326 (recursive tax skims) → addToTreasuryNoTax :333-373 (no lock, clamp at MAX_ADENA, SQL failure leaves memory changed, returns true); callers CastleManorManager 387/492, FortUpdater 70, RequestBuyItem 282, MultiSellChoose 488, RequestBuySeed 209, CastleChamberlain 900/927 | holds |
| 8 | Variables | AbstractVariables: all ten set overloads :53-121 track under _saveLock then put outside it; remove :200-230 already atomic; StatSet.merge :82-85 bypasses tracking; ItemVariables.storeMe schedules saveNow at :135; saveNowSync :172; Item.getVariables :2235 lazy, not atomic | §11.12: all overloads + merge |
| 9 | Eligibility | Item.isAvailable :898-909 dereferences player (no clan-warehouse reuse); isShadowItem :1293; isTimeLimitedItem :1837; isHeroItem :868 | range 898-909 |
| 10 | Login/logout | Player.restore :7141, items at :7357-7360 (plain reads); GameClient.load :509-554 (Player.load at :539); CharacterSelect holds the player lock :101 → load :187 → unlock :232; Disconnection.storeAndDelete :110-134; Player.deleteMe :11766, offline at :11793, inventory deleteMe :12103 | §11.18: lock spans :101-232 |
| 11 | Mail | MailManager.sendMessage :157-177 (memory before insert); MailMessage system ctor :107-122 (sender -1, 360 h expiry); attachments restore owner = sender (-1), MAIL, loc_data = message (Mail.java:122); MessageDeletionTaskManager :70-110; RequestPostAttachment claim :191-232, offline COD :251-257 | holds |
| 12 | Deletion | GameClient.markToDeleteChar :257-318 (immediate at :293-295); deleteCharByObjId :342-507 (items :439-455, leaves item_elementals and item_variables); deferred executor CharSelectionInfo.java:266 | §11.21: name CharSelectionInfo:266 |
| 13 | Other direct Item construction | ZoneForm.java:53, ItemsOnGroundManager.java:106 (plus Loto, RaceManager, MercTicketManager) | §11.14 minor |
