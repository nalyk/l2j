# Claude's position: how the FULL Living Chronicle gets built on Mobius H5 under the owner's constraints

Scope: every system and sub-feature in blueprint §31 (Retail Truth, Aden Remembers incl. Director/economy/politics/
rumors/history, Aden Signal, Wayfarer's Memory, PACT + Notarius tiers, Black Ledger incl. advanced intel features,
public Chronicle website, optional LLM narration). Nothing is cut. The only ordering allowed is hard technical
dependency (P13). Each proposition states claim -> why -> evidence -> how it is proven done.

---------------------------------------------------------------------------------------------------------------------
## P1. With ≤10 humans the blueprint is not "less fun", it is NON-FUNCTIONAL. The fix is a Synthetic Society:
##     a resident population of synthetic citizens that are real Players, not Mobius Fake Players.

Why: Signal needs compatible humans online in the same band; informants need witnesses; Aden Remembers needs
statistically meaningful pressure; mercenary PACTs need clans; Black Ledger needs targets that move, hunters,
brokers' markets; Chronicle needs history. 10 people cannot generate any of that (R1). Mobius Fake Players are NPCs
(01_VERIFIED_FACTS: FakePlayerData maps name->npcId) and cannot trade, craft, use gatekeepers, join clans or sign
contracts (R7). So the population must be made of real `Player` objects driven by software.

Claim: Synthetic Society (SS) is a first-class production subsystem of the hobby world, not a test fixture.
- Target: ~1500 synthetic accounts, 300–800 concurrently online following human-like session schedules
  (login/logout, evenings, weekends), across all races/classes/levels, organized in clans/alliances.
- They earn everything through normal gameplay at the configured rates. Zero minting. Zero GM spawning of items.
- Personas: farmer, spoiler, crafter (each recipe tier), merchant (private stores), buffer/healer for hire,
  PK/outlaw, clan leader, mercenary, hunter, informant, broker client, siege participant, manor farmer, newbie.
- Decision layer: utility AI / behaviour trees with personal goals, budgets, risk appetite and memory.
  A local LLM on the RTX 5090 is used only for (a) natural-language whispers/chat with humans and
  (b) low-frequency strategic choices of clan leaders (war, alliance, contract terms). This does NOT violate §1.3:
  synthetic citizens are players, not the engine; the engine still decides all facts.
- Out-of-band "Discord" for synthetic clans: bots in the same clan share observations through an external channel,
  so the alt/Discord premise of P5 is exercised by the population itself.

## P2. Synthetic citizens connect through the REAL network protocol (separate "citizen-runner" JVM), not in-JVM.

Why: the information-warfare modules (Black Ledger, Wayfarer, rumors, Signal privacy) are only valid if every actor
has exactly the epistemic limits of a human client. An in-JVM client that can read `World` directly would be
omniscient and silently invalidate every information mechanic. A network client only knows what server packets
told it (CharInfo, NpcInfo, MoveToLocation, ItemList, PrivateStoreMsg, SystemMessage...). It also exercises login
server, Blowfish/RSA login crypt, game XOR crypt, flood protectors and packet handlers exactly as humans do.
Feasibility: the protocol spec is literally the Mobius source: 315 client opcodes (ClientPackets 170 + Ex 145) whose
`readImpl` become client writers, 389 server packets whose `writeImpl` become client parsers. That is a mechanical,
verifiable transformation ideal for AI generation, checked by round-trip tests against the real server.
Navigation uses the same geodata files (the runner loads the GeoEngine code/data read-only).
Proof of done: N concurrent runners log in, play for 24h, zero protocol desyncs/kicks, packet-coverage report
shows every opcode needed by the personas exercised.

## P3. Actor model update: add SYNTHETIC_CITIZEN to the §7.2 taxonomy and reconcile blueprint rules.

The blueprint's exclusions (§4.16, §7.2 "Black Ledger must not pay fake-player contracts", §9.8, §25 IncludeFakePlayers,
FakePlayerPayout) were written against Mobius NPC fakes and abuse. Synthetic citizens are a declared population:
- they count for economy, Aden Remembers pressure, Signal matching, PACT and Black Ledger (as clients, hunters,
  targets, informants), and they can enter the Chronicle — always tagged actorClass=SYNTHETIC_CITIZEN in the journal;
- Mobius NPC Fake Players keep the original exclusions;
- every metric is reported split by actor class; history can be filtered "humans-only";
- anti-collusion rules apply to synthetic citizens too (a bot ring must not farm bounties any more than humans).
Testers are told the world is mostly synthetic; there is no in-world visual marker (immersion), but there is an
admin view. When the server goes live, the population becomes a configurable ecological layer (count can be lowered
gradually); nothing in the architecture assumes it disappears overnight.

## P4. Hard fork of Mobius H5 pinned at 2b0758e9 with a gated "upstream intake", and an explicit core hook seam.

Why: R6 is real — crafting, private store, P2P trade, NPC buy/sell, private warehouse, sweep/spoil, enchant,
mail, Territory War, fortress owner change, party join/leave, Seven Signs period, Hellbound level, Manor results,
gatekeeper teleport (with NPC + destination) have no EventType (01_VERIFIED_FACTS). Upstream moves fast (18 H5
commits/30 days, Java 25->27, package move model/events -> mechanics/events). Tracking HEAD continuously would
make every Living Chronicle change a moving target; ignoring upstream loses AI/quest fixes.
Design:
- `upstream` branch mirrors Mobius H5; `main` is the fork. An AI "intake" job periodically diffs upstream, classifies
  commits (fix / content / refactor / build), applies them, and merges only if the full proof suite (P10) is green.
- Core changes are confined to: (a) new EventTypes + one-line dispatch at the authoritative point (inside the
  handler AFTER the transaction is validated, never in client-trust paths), (b) seedable RNG (Rnd uses
  ThreadLocalRandom -> add a test-profile seeded provider), (c) test harness hooks. All Living Chronicle logic lives
  in `org.l2jmobius.gameserver.livingchronicle` + datapack scripts + new SQL schema.
- Every core patch is listed in a CORE_PATCHES.md ledger so intake can re-apply/verify them mechanically.

## P5. Information design assumes free alts, perfect external comms (Discord) and public wikis — and still works.

Value of in-game intelligence is built on properties outside chat cannot forge:
- Certification: only the server can certify (Broker-verified sighting, Notarius registry, CONFIRMED trace).
  A Discord screenshot is RUMOR by construction; a certified dossier entry is not transferable.
- Freshness: operational traces have minute-scale TTLs (§13.7); leaks age faster than they can be exploited.
- Presence-gated access: witness interrogation requires the hunter character physically at the NPC; the answer is
  bound to that character (Wayfarer provenance) and costs Adena (sink, §16.3), so alt-spam is expensive.
- Account-scoped identity: all characters of an account share one Black Ledger identity; informant/hunter/client
  linkage (same account, same IP/HW signals, party history, trade graph) blocks payouts (§13.25) — informants paid
  from the client-funded intel budget can at worst drain the client's own money, which is detectable and refundable.
- Alt spies inside clans remain legitimate L2 gameplay; the design does not try to prevent them.
Proof: SS scenario where every synthetic clan shares everything out-of-band; Black Ledger hunts must still show
counterplay (target survival rate band, median hunt duration band) and zero certified-intel forgeries.

## P6. Aden Remembers must be perceptible, and it must drive H5's native world-state systems, not only invent new ones.

- Replace the global ±5% cap (§25.1) with per-actuator bounds, each with declared perception channels
  (merchant stock/prices, NPC dialogue, rumor NPCs, caravans, spawn-group shifts, monuments, Chronicle).
- Actuators include the H5 retail world-state machines already in the code: Hellbound level, Seven Signs,
  Seed of Destruction/Infinity states, Manor seed/crop prices, castle taxes/treasury, Territory Wars, fortress
  contracts, clan hall sieges. Observing/driving these is cheaper and more "Lineage" than parallel inventions.
- Perceptibility is a test, not an opinion: an observer synthetic citizen with only client knowledge must detect a
  consequence through client-visible signals within a declared window, and must NOT be able to recover the raw
  Director value (anti-dashboard). Same for "admin nerf" suspicion: every actuator change has a public-facing
  diegetic cause (rumor/notice) and an audit reason code.

## P7. Client: a Living Chronicle client package is mandatory; UI is server-driven by default with targeted
##     interface modding only where server-driven primitives are proven insufficient.

- Mandatory patched .dat files: new items (contract parchments, Torn Black Seal, calling cards, journal), custom
  system messages/NpcStrings, item icons. Custom NPCs can reuse client models via displayId/usingServerSideName
  (NpcTemplate.java:66-142), so no NPC client edits are required.
- UI layer (one framework, used by all modules): Community Board pages (Chronicle, Journal, contracts, dossiers,
  Signal board), NpcHtmlMessage for institutional ceremony (Notarius/Magistrate/Broker), ConfirmDlg for signatures
  and escrow confirmations, ExShowScreenMessage + system messages for notifications ("You feel watched",
  "5 players aligned with your session"), TutorialShowHtml for login recap. This is L2's native server-extension
  mechanism, not a workaround.
- Interface.u modding (native windows/HUD widgets) is used for exactly the cases the above cannot express (candidates:
  persistent Signal status widget, notification inbox), conditional on a verified, reproducible toolchain.
  Codex: please research whether such a toolchain exists for H5 and challenge this split.
- With ≤10 testers distribution is a zip; a signed updater is part of the package before live.

## P8. Network topology for the hobby: no router forwarding, no public exposure.

Game stack runs in Docker on build-host (MariaDB, login, game, citizen-runner, LLM, website). A Tailscale sidecar
gives the game stack its OWN tailnet node; that node (not the whole host) is shared with the ≤10 testers, ACL'd to
TCP 2106/7777 (+ website). ipconfig.xml announces the tailnet address. This removes the legal/DDoS exposure of the
office IP (R9) and the router dependency. Going live later = swap this ingress for public hosting with DDoS
protection; nothing else changes.

## P9. Retail Truth for H5 = evidence hierarchy + behaviour lock.

- Evidence classes: (1) official H5 client data files (skillgrp, itemname/weapongrp/armorgrp/etcitemgrp, npcgrp,
  skillname, systemmsg) — authoritative for client-visible values; (2) archived official patch notes;
  (3) community archives/videos/wikis with confidence levels; (4) Mobius behaviour as FUNCTIONAL baseline.
  Leaked NCSoft server (PTS) files are NOT ingested (licence/legal), only cited where publicly documented.
- Behaviour lock (the critical part for AI-written code): golden tests capture current Mobius formulas/AI/drops across
  the class×skill×gear matrix with seeded RNG; any change to them fails CI unless accompanied by a DESIGN_OVERRIDE
  or an evidence-backed Retail Truth correction. This mechanically enforces §15.3 "no hidden stat modification".

## P10. Because AI writes all code, the proof system IS the backbone. "Done" = machine-verified, not reviewed-by-eye.

- Build: Java 27, Ant kept (upstream compatibility) + JUnit 5 via junitlauncher; Testcontainers/Docker MariaDB.
- Layers: unit/property tests; invariant tests (asset conservation: sum of Adena+item counts across inventory,
  warehouse, escrow, mail before/after every PACT/Black Ledger operation; no object ID in two places);
  crash injection (kill -9 the GameServer at every contract lifecycle state, restart, assert invariants + idempotent
  settlement); journal replay determinism (§32.12); SS soak scenarios (§19.4 extended) with metric acceptance bands;
  protocol round-trip tests; Retail Truth golden suite.
- Specs are the source of truth: a rewritten H5 blueprint (P12) split into per-module specs with DDL, state machines,
  event lists, invariants, config keys, UI pages and acceptance tests. Agents implement against specs;
  a second model reviews every module diff adversarially.
- Humans (≤10) are reserved for what machines cannot judge: fun, clarity, tension, fairness perception.

## P11. Runtime on build-host is sufficient for the whole thing, with isolation.

GameServer heap 12–16 GB (ZGC) for ~800 concurrent incl. SS; citizen-runner 4–8 GB; MariaDB 4–8 GB; LLM on GPU
(32 GB VRAM); website small. Total < 40 GB RAM of 123 GiB; CPU 16C/32T. Cgroup CPU weights so CI runners on the box
cannot starve the game tick. Daily DB snapshots + restore drill (§21.4).

## P12. The blueprint must be rewritten for H5 before implementation specs: chronicle-specific sections (§1, §4, §5,
##     App. D/G), new SS/actor/client/topology sections, and H5 systems integration (TW, fortresses, clan hall sieges,
##     Hellbound, Seven Signs, SoD/SoI, Manor, Olympiad) mapped into every module. Kamael leftovers (VIP, Sayune,
##     CustomDepositableItems, MissingQuests list) removed.

## P13. Work order = dependency DAG, all nodes built, many in parallel:
  D1 fork+build+test harness+seeded RNG+behaviour-lock baseline
  D2 hook seam (new EventTypes) + event journal + actor classes + audit/reason codes + config isolation   [needs D1]
  D3 protocol client library + world model + geodata navigation                                         [needs D1]
  D4 client package (.dat) + server-driven UI framework                                                  [needs D1]
  D5 Synthetic Society behaviours/personas/schedules/out-of-band channel                                  [needs D2,D3]
  D6 modules: PACT+Notarius, Black Ledger (all of §13), Signal, Wayfarer, Aden Remembers (all of §8 + H5 actuators),
     Chronicle + website + LLM narrator, Retail Truth suites                                             [need D2,D4; validated with D5]
  D7 cross-module integrations §14 + full-world soak + tester sessions                                    [needs D6]
  D8 deployment stack on build-host (Docker, Tailscale sidecar, backups, metrics)                        [parallel from D1]
No node is optional; no feature is moved to "later".

## P14. Known residual risks that remain even with all of the above (stated so we don't pretend):
- Game-feel of synthetic citizens: if they are dumb, the society feels fake; if too efficient, they dominate humans.
  Needs explicit efficiency caps and human-perception tests.
- Legal: a private, non-public hobby server with ≤10 people is low exposure but not zero (NCSoft IP); going live needs
  a separate legal/hosting decision.
- Upstream intake can still produce hard conflicts in the hook seam after big refactors; the ledger + proof suite
  makes them visible, not free.
