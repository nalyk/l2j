# Verified facts (checked 2026-11-09 by Claude; re-verify anything you rely on)

## Mobius source available locally (read-only, sparse checkout of H5 only)
Path: /tmp/claude-1000/-home-ubuntu-gits-l2j/602dbef7-23ab-4d0b-a677-10a4a756e60c/scratchpad/mobius-src/L2J_Mobius_CT_2.6_HighFive
Upstream commit: 2b0758e97d5a097127b7330ee59e82d4b1d69b3b (2026-11-09 11:21 +0200)
- Core Java: ~364k LOC (3194 .java incl. scripts dir counted separately); datapack scripts: 1291 .java, ~306k LOC.
- Build requires **Java 27** (build.xml checkRequirements: javaversion atleast="27", javac source/target 27).
  The blueprint (dated 2026-09-30) says JDK 25 -> upstream moved in ~6 weeks.
- Event system moved: java/org/l2jmobius/gameserver/mechanics/events/EventType.java (blueprint Appendix G links to
  the old model/events path). 89 EventTypes. Present: ATTACKABLE_KILL, CREATURE_KILLED/DEATH/TELEPORTED/ZONE_ENTER/EXIT,
  CASTLE_SIEGE_START/FINISH/OWNER_CHANGE, FORT_SIEGE_START/FINISH, CLAN_WAR_START/FINISH, PLAYER_PVP_KILL, PK/KARMA/
  PVP_CHANGED, ITEM_CREATE, PLAYER_ITEM_ADD/DESTROY/DROP/PICKUP/TRANSFER, PLAYER_CLAN_* incl CLAN_WH_*,
  PLAYER_BYPASS, NPC_MANOR_BYPASS, PLAYER_LOGIN/LOGOUT, OLYMPIAD_MATCH_RESULT, DAILY_RESET ...
  ABSENT: crafting/recipe, private store buy/sell, player-to-player trade, NPC buy/sell, private warehouse,
  spoil/sweep, Territory War, mail/post, enchant, fortress ownership change.
- Hook-point handlers exist: network/clientpackets/RequestRecipeItemMakeSelf, RequestRecipeShopMakeItem,
  RequestPrivateStoreBuy/Sell, RequestSellItem, RequestBuyItem, SendWareHouseDepositList/WithDrawList, TradeDone,
  RequestSendPost.
- Protocol: ClientPackets.java 170 opcodes + ExClientPackets.java 145; 389 server packet classes.
  AllowedProtocolRevisions = 267;268;271;273 (config/Server.ini).
- Fake Players are **NPC-based** (data/xml/FakePlayerData maps names -> npcId; isFakePlayer on WorldObject/Npc).
  They cannot trade, craft, use gatekeepers, join clans, sign contracts like a real Player.
- H5 world-state systems present: SevenSigns (29 refs), Hellbound (scripts/ai/areas/Hellbound, HellboundStatus.ini),
  SeedOfDestruction/SeedOfInfinityManager (GraciaSeeds.ini), TerritoryWarManager, FortManager/FortSiegeManager,
  CastleManorManager, CHSiegeManager/ClanHallAuctionManager, Olympiad.
- Custom configs in H5 (44): AutoPlay (EnableAutoPlay=False by default), FakePlayers, OfflinePlay, OfflineTrade,
  HellboundStatus, WarehouseSorting, Rebirth, Transmog ... ; Kamael-only (absent in H5): VipSystem, SayuneForAll,
  CustomDepositableItems. No MissingQuests.txt in H5.
- UI primitives available server-side: NpcHtmlMessage, ShowBoard (Community Board), TutorialShowHtml,
  ExShowScreenMessage, ConfirmDlg, RadarControl, ExShowTrace. RequestHardWareInfo (ex 0x96) packet exists,
  EnableHardwareInfo=False by default.
- Upstream activity: 18 commits touching H5 in the last 30 days (latest 2026-11-08).
- Default GameServer JVM: -XX:+UseZGC -Xmx4g -Xms2g (dist/game/java.cfg). DB: MySQL connector 9.5 (MariaDB OK).

## Infrastructure (server "build-host", hostname "ultra")
- AMD Ryzen 9 9950X 16C/32T, 123 GiB RAM (peak used 18 GiB over 9 days), RTX 5090 32 GB, NVMe ~363 GB free.
- Ubuntu 24.04; only Java 8 installed (Temurin 27.0.1 tarball available); no MariaDB; user has docker + sudo(password).
- Shared box: several GitLab runners (incl. dind), ollama, ik_llama, assorted services.
- Network: LAN 172.27.0.14/18 behind office NAT (public IP 212.56.197.2 shared by the whole office). Tested from an
  external VPS: TCP 2106/7777 unreachable; tcpdump on the server saw zero packets -> blocked at office router.
  ufw is inactive. Tailscale present (build-host). Cloudflare tunnel/ngrok on the box are HTTP-oriented.
- No test infrastructure in Mobius H5 (no test sources, no JUnit in build.xml).
- RNG: commons/util/Rnd.java uses ThreadLocalRandom everywhere -> not seedable; deterministic test mode needs a core patch.
- No party-related EventTypes (no ON_PLAYER_PARTY_JOIN/LEAVE).
- Custom NPCs without client changes ARE possible: NpcTemplate supports displayId (reuse an existing client model)
  and usingServerSideName (entity/actor/templates/NpcTemplate.java:66-142). Custom ITEMS still need client .dat entries.
