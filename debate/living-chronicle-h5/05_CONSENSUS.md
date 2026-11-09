# CONSENSUS — Lineage II: Living Chronicle on L2J Mobius CT 2.6 High Five

Debaters: Claude (Anthropic, Opus) and Codex (OpenAI, gpt-6.1-sol, reasoning=high). Date: 2026-11-09.
Codex thread: 01a1c118-0551-7a71-b554-d54c29025e95. Transcript: rounds/round1*.{txt,json}, rounds/round2*.{txt,json}.
Base: Mobius H5 commit 2b0758e97d5a097127b7330ee59e82d4b1d69b3b. Constraints: 00_CONSTRAINTS.md (binding).

Status: SIGNED OFF by Codex in round 3 (rounds/round3.json); its 16 corrections applied verbatim.
Scope of this consensus: HOW to build and validate the complete project. It does not certify any implementation;
legal clearance, owner risk acceptance, client compatibility and capacity remain unproven until their evidence exists.
Nothing from blueprint §31 is cut or deferred. Work is ordered only by hard technical dependency (§11).

---------------------------------------------------------------------------------------------------------------------
## 1. Population: the Synthetic Society
1.1 Problem: with ≤10 humans the blueprint lacks market depth, simultaneous role availability and political scale.
    Mobius Fake Players are NPC-backed (FakePlayerData.java:37-64) and cannot do Player workflows.
1.2 A resident population of synthetic citizens that are real `Player`s on real accounts is a first-class subsystem.
1.3 Population is SPECIFIED AS DISTRIBUTIONS: level band × role × geography × session overlap × supply-chain coverage
    × competing political principals. 300–800 concurrent is a benchmark scenario, not a requirement.
1.4 Economic and political decisions of citizens use DETERMINISTIC UTILITY POLICIES. The LLM (local, RTX 5090) is
    limited to fact-constrained dialogue and narration (blueprint §1.3 holds).
1.5 Residents progress only through recorded ordinary gameplay (class changes, recipes, clans, resources). No artificial asset grants or GM-spawned items; ordinary gameplay faucets and crafting transformations remain permitted and accounted for. Efficiency is bounded by decision latency, schedules, imperfect knowledge, spending preferences —
    never by changing combat or reward formulas.
1.6 Faucets and concentration: source/sink accounting by actor class and synthetic principal; bounded aggregate
    farming duty, extraction concentration, stockpiling and scarce-content attendance via behaviour policy;
    modelled consumption, maintenance, losses and commercial risk; tests of human newcomer margins and access, human support-class demand,
    human trade opportunities and political contestability; adversarial runs where citizens try to concentrate power.

## 2. Synthetic citizen runtime (external protocol runner)
2.1 Citizens connect through the real network protocol from a separate runner JVM (no privileged server-world access: dynamic observations come from perceived protocol traffic and the authorized communication channel; validated static navigation geometry is permitted). Versioned JVM protocol library: explicit connection states, framing, encryption
    negotiation, conditional fields, retries, packet→domain mappings.
2.2 Not "315 readImpl inversions": ClientPackets/ExClientPackets contain null handlers (ClientPackets.java:76-81,
    205-206; ExClientPackets.java:164-181). Differential references: l2js-client (MIT), l2py H5 Mobius client (MIT),
    permitted captured traffic.
2.3 PERCEPTION BUDGET per citizen (attention limits, tracked-object cap, reaction latency, what it notices), applied
    BEFORE information reaches decision policies and shared clan memory.
2.4 Each citizen has its own observation store; clans have an allowlisted, logged out-of-band channel (emulates
    Discord, so §6 is exercised by the population itself).
2.5 Navigation: static geometry library from the validated geodata; doors/fences/obstacles learned from observations
    and movement failures (GeoEngine also consults DoorData/FenceData — GeoEngine.java:470-477, 695-702).
2.6 Fault matrix: fragmented/coalesced TCP frames, reconnects, malformed lengths, cipher state, packet loss on
    disconnect, stale objects, every persona's full workflow; 24h soak is one test among many.
2.7 Login admission: runner-wide queue, spacing beyond NormalConnectionTime, bounded outstanding logins, jittered
    backoff (login Server.ini:53-65, FloodProtectorListener.java:98-112). Per-category action pacing against
    FloodProtector.ini. Explicit runner dualbox policy incl. Olympiad/event limits. Protections never disabled globally.
    Mass runner crash/recovery must not block human logins.
2.8 PacketEncryption=True (retail). Specify and test the ACTUAL wire behaviour (stateful XOR game channel, initial
    unencrypted KeyPacket, directional rolling state — Encryption.java:26-35, 92-144; KeyPacket.java:40-52;
    ProtocolVersion.java:54-71); unencrypted path also tested. The cipher is not authentication/integrity.

## 3. Actor identity model
3.1 Separate dimensions: immutable server-assigned origin; authenticated account; activity mode (changes only via recorded
    transitions: interactive / offline trade / offline play / disconnected); simulation profile; synthetic economic
    principal. Persisted across reconnects and journal replay.
3.2 Competing synthetic principals with isolated budgets and information policies; ordinary funded transfers between
    them are allowed; repeat-pair and reciprocal-farming controls apply to everyone. Malicious collusion scenarios are
    separate from resident policies.
3.3 Mobius NPC Fake Players keep the blueprint's original exclusions.
3.4 Signal reports human / synthetic / mixed outcomes separately; humans choose whether synthetic candidates are offered.
    No overhead in-world marker; testers are told the world is mostly synthetic; admin view shows everything.
3.5 Each event carries an immutable snapshot of these dimensions, including origin=SYNTHETIC_CITIZEN where applicable. Resident citizens participate in economy, Aden Remembers, opted-in Signal matching, PACT, Black Ledger and Chronicle history under the agreed policies; NPC Fake Players retain their exclusions. Metrics are reported separately by actor class, and history supports a humans-only filter.

## 4. Fork, core seam and financial integrity
4.1 Hard fork pinned at 2b0758e9; `upstream` mirror branch; AI intake job classifies/applies upstream commits; merge
    only if behaviour-lock + Retail Truth + migration/recovery (both sides, with representative persisted contracts and
    escrow assets) + client-compatibility checks pass. An intake change must never approve itself by regenerating
    its own goldens. CORE_PATCHES.md ledger lists every core change.
4.2 The core seam is NOT one-line dispatches. It includes:
    a) transaction-aware ASSET SERVICE: one connection for all affected core rows, metadata, contract state, ledger
       postings, contract version, settlement uniqueness key and outbox facts in one InnoDB transaction; documented lock order; optimistic versions; operation IDs;
       on uncertain commit resolve by operation ID before retry; it owns EVERY competing mutation of affected assets
       (incl. ordinary core saves) so no independent writer overwrites committed state;
    b) ESCROW ItemLocation with custody reference; canonical escrow items stay in `items`; DatabaseIdManager cleanup
       (DatabaseIdManager.java:67) recognises custody; used-ID extraction (lines 122-128) reserves them; IDs never
       released during custody transfer; ordinary inventory restore never loads escrow rows; contract/event IDs are
       independent; item provenance uses a lifecycle ID (object IDs can be reused);
    c) pre-distribution LOOT ROUTING for signed charters before Party.distributeItem assigns loot (Party.java:639-686),
       with frozen participants and rules for join/leave/death/disconnect/spoil/command channel; Reserved and auctioned assets are routed atomically to charter escrow before ordinary inventory assignment.
    d) authoritative CRAFTING OPERATION boundary sharing retail validation/outcome code (RecipeManager.java:388-429)
       with escrow-backed sources/destinations; persisted attempt outcome (no reroll on recovery);
    e) immutable primitive event snapshots captured at the owning operation (existing item events are async with
       mutable Item refs — PlayerInventory.java:539-556); post-commit cache and client publication;
    f) new EventTypes for: craft, private store, P2P trade, NPC buy/sell, private warehouse, sweep/spoil, enchant,
       mail, Territory War, fortress ownership, party join/leave, Seven Signs period, Hellbound level, Manor results,
       gatekeeper teleport (NPC + destination);
    g) seedable/keyed RNG streams and controllable clocks/schedulers for test profiles.
4.3 After restart, committed operations are recovered before affected inventory actions/reconnects are accepted;
    overflow deliveries stay in durable escrow. LazyItemsUpdate=False is an asserted invariant (General.ini:189-197). Reconcile affected in-memory inventories from committed rows before releasing mutation locks or accepting affected reconnect actions.
4.4 All Living Chronicle logic lives in org.l2jmobius.gameserver.livingchronicle + datapack scripts + migrated schema.

## 5. Event journal, replay, storage
5.1 Durable financial/audit facts (transactional outbox, immutable IDs) are separated from aggregated regional
    telemetry (bounded windows). Illustrative arithmetic: 800 actors × 1 event/s × 500 B = 69.12 M events / 34.56 GB per
    day raw — so storage budgets come from measured rates.
5.2 Versioned schemas and reducers, partitioned retention, batched projector writes, idempotent checkpointed consumers,
    bounded queues. Financial operations pause if their durable store is unavailable; Director freezes with explicit
    gap/watermark handling; inputs are never silently dropped.
5.3 Replay contract: versioned immutable facts → isolated derived stores with logical clock, per-stream sequence,
    config version and recorded random outcomes; actuator decisions carry idempotent application IDs; replay never
    reissues transfers. No promise of full live-Mobius replay. Concurrency tested separately (stress/linearizability).
5.4 Reducers use a specified stable processing order. Restoration and replay are benchmarked against the retained data volume.

## 6. Information design (alts, Discord, wikis assumed)
6.1 Every clue must stay balanced when relayed instantly to an enemy alliance. Learned CONTENT is shareable;
    server ACTIONS are protected (capabilities, contract authorization, presence, exact expiry).
6.2 Disclosure priced/limited per contract, target, witness, window, with diminishing returns that do NOT reset when
    another cooperating account/contract asks for the same clue; equivalent sightings deduplicated.
6.3 Disputed informant proceeds frozen before payment; behavioural linkage with explicit false-positive handling;
    never refund by minting. Published guarantee: bounded disclosure and economically unattractive farming —
    not controller identification (RequestHardWareInfo is client-supplied and spoofable; stock H5 transmission unproven).
6.4 Verified sighting eligibility: same instance, bounded distance, geodata LoS, visibility state, minimum observation
    duration or authoritative interaction, recency; server-side evidence snapshot + dedup; tests for walls, floors,
    invisibility, teleport, login, doors, summons, colluding witnesses (Creature.java:7465-7484 lacks LoS). The disclosed clue remains coarse even when its private eligibility evidence is precise.
6.5 Calling cards/aliases: anonymity = confidentiality of Ledger records and registrations; ordinary combat observation
    can expose an alias (CharInfo/Attack packets); placing a signature is a deliberate choice that can burn secrecy;
    attribution-neutral/delayed Broker delivery option with evidence rules. Identity-intelligence market and reverse
    investigation kept in full.
6.6 One INFORMATION-ACCESS SERVICE: typed precision, audience, purpose, expiry, shareability, publication eligibility.
    Website and narrator consume allowlisted publication DTOs with separate DB credentials. Expiry applies to source
    payloads, caches, projections, backups and rebuilds. Tests for cross-module inference and replay-after-expiry. Retain non-sensitive financial audit separately from expiring intelligence payloads. Cross-module inference and replay-after-expiry tests explicitly include sealed contracts and actor-filtered history.

## 7. Aden Remembers and native H5 world-state
7.1 ±5% (§25.1) is resource-pressure only; every actuator has its own bounds (§8.19), supply budgets retained.
7.2 AUTHORITY TABLE per native system (Seven Signs, castles/taxes, Manor, Hellbound, SoD/SoI, Territory War,
    fortresses, clan hall sieges, Olympiad): state owner, allowed commands, legal preconditions, transitions,
    persistence, emitted facts. The Director never overwrites earned ownership/scores/periods; it acts only through
    each system's lawful inputs (e.g. situations where players can earn Hellbound trust) plus complementary NPC and
    economic consequences.
7.3 Incomplete native H5 paths are implemented as content obligations with evidence-backed specs: SoD defense state
    (SeedOfDestructionManager.java:110-132), Seven Signs Festival gaps (SevenSignsFestival.java:62-68), and any
    others found by the H5 content inventory.
7.4 Perception: detectability tested mechanically (client-knowledge observer citizens), comprehension tested with
    humans; promise is "no direct hidden-state API", not resistance to statistical inference.

## 8. Client and UI
8.1 Living Chronicle client package: pinned client hashes; a separately pinned and validated client-data/resource patch pipeline for the required item .dat entries, system messages, NpcStrings and icons. Acmi XDAT tooling handles interface.xdat layout editing in a separate pinned legacy environment; it is not the asserted .dat/resource editor. Custom NPCs reuse models via displayId/usingServerSideName (NpcTemplate.java:137-144; AbstractNpcInfo.java:106,163-200).
8.2 Every §31 interaction is fully achievable with ONE server-driven UI framework: paginated Community Board views
    (HtmlUtil.java:411-453: ordinary non-multiedit HTML uses up to three 16250-Java-character chunks and rejects length ≥48750; multiedit uses a separate path), institutional NPC pages (NPC HTML truncates >17200 —
    AbstractHtmlPacket.java:73-78), one-use confirmations bound to character + terms hash + contract version +
    deadline + required NPC proximity where the interaction requires physical presence (DlgAnswer.java:50-73), ExShowScreenMessage/system messages, TutorialShowHtml recap.
    Each interaction is specified as a concrete page/action flow (search, notes, inbox, partial orders, loans,
    auctions, reverse investigation...). Validate layouts, item use, Cyrillic/localised text and size bounds in the pinned client.
8.3 Every required gameplay interaction has a complete server-driven flow. If human UX tests establish that a native widget is necessary to satisfy an acceptance requirement, that widget and its pinned, proven toolchain become required acceptance dependencies. Use a licensed compiler pipeline or an implemented H5 bytecode/package toolchain, with pinned input/compiler/dependency manifests, a controlled build environment, a no-op semantic round trip and a changed-window runtime test. An enhancement is not treated as validated merely because it is offered.
8.4 Hobby client distribution uses a ZIP package; a signed updater is a required client-package deliverable before public activation.

## 9. Retail Truth and behaviour lock
9.1 Two independent artifacts: behaviour-regression baseline (stability only) and evidence-backed Retail Truth cases.
9.2 Evidence scoped to client build/region/update/observable property; client data proves client-visible values only,
    not drop/land rates, AI or transactions. Expected results derived independently of the code under test.
    Statistical trials with confidence intervals for probabilities; unsupported exactness stays UNKNOWN.
9.3 Coverage of every class/feature via equivalence partitions, boundaries, known interactions, targeted combinatorics.
9.4 DESIGN_OVERRIDE is scoped to a domain and intended change; never blanket-approves changed goldens.

## 10. Proof system (AI writes all code; "done" = machine-verified + human-judged where machines cannot)
10.1 Pinned Java 27, Ant ≥1.10.14 + matching ant-junitlauncher, JUnit Platform/Jupiter, MariaDB, Testcontainers —
     exact versions and digests recorded; singleton-heavy integration tests in forked JVMs.
10.2 Conservation per item/currency against authorised source/sink/transform ledger entries + unique ownership +
     metadata preservation.
10.3 Fault injection before/after each SQL mutation, commit, cache application, acknowledgement; ambiguous commits;
     DB restarts. Restart tests explicitly cover escrow cleanup/ID reservation, expired-intelligence rebuilds,
     persisted craft outcomes.
10.4 Traceability matrix: every §31 sub-feature, retained blueprint appendix behavior and §14 cross-module behavior → H5 spec, authoritative subsystem, schema, UI flow, hooks, tests and acceptance evidence. Per-module specs contain DDL, state machines, event lists, invariants, configuration keys, UI pages/action contracts and acceptance tests. Coverage is per sub-feature, not per module.
10.5 Second-model adversarial review of every module diff; AI-generated tests and a second model are NOT treated as
     independent correctness oracles — independent expectations (§9.2) and invariants are.
10.6 Humans (≤10) judge fun, clarity, tension, fairness perception, social credibility.

## 11. Dependency DAG (all nodes built; independent nodes run concurrently)
  N1  H5 blueprint rewrite + per-module specs + H5 content inventory + traceability matrix
  N2  fork, reproducible build (pinned toolchain), test harness, keyed RNG/clocks, behaviour-lock baseline   [N1]
  N3  asset-transaction design (lock order, escrow location, ID integration, outbox)                       [N1]
  N4  core seam implementation (asset service, escrow, loot routing, crafting boundary, events)             [N2,N3]
  N5  event journal, actor identity, audit/reason codes, information-access service, config isolation       [N2,N4]
  N6  client/tool provenance: pinned client, separate validated .dat/resource patch pipeline and legacy XDAT environment [N1]
  N7  geodata: local generation from the exact pinned client used by testers, with pinned l2mapconv, generator/dependency hashes, recorded settings and correction patches; no third-party geodata pack downloads; full required-region coverage failing on NullRegion fallback; cross-region routes, multilevel floors, stairs, doors, water, siege structures, LoS and instance tests [N6]
  N8  UI framework + all interaction flows/action schemas                                                  [N5,N6]
  N9  protocol library + runner + navigation                                                               [N2,N7]
  N10 synthetic society (personas, principals, perception budgets, schedules, out-of-band channel)          [N5,N8,N9]
  N11 modules: PACT+Notarius (all tiers), Black Ledger (all of §13), Signal, Wayfarer, Aden Remembers (all of §8 +
      authority-table actuators), native H5 completion (SoD defense, Festival, …), Chronicle + website + narrator,
      Retail Truth suites                                                                                  [N4,N5,N8; validated with N10]
  N12 cross-module integrations (§14), full-world soak, capacity benchmarks, tester sessions                 [N11]
  N13 deployment: Docker stack on build-host, isolated netns + persistent Tailscale node + node-share grants,
      backups/restore drills, metrics, CPU sets/caps                                                       [from N2, parallel]
  N14 public (live) topology specification with its own activation criteria                                [N13]

Bracketed dependencies identify required input artifacts, not blanket whole-node completion barriers. Fork/build/harness work and client/tool provenance may proceed without waiting for the entire N1 rewrite. Per-feature implementation requires its applicable specification. N14 topology specification may run in parallel with N13 implementation once its deployment contracts are known. Other independent subtasks likewise proceed concurrently.

## 12. Infrastructure and topology (hobby)
12.1 Stack on build-host: MariaDB, LoginServer, GameServer, citizen runner, local LLM, website — Docker, isolated
     network namespace, persistent Tailscale node. Node shared to ≤10 individual tester accounts (not tailnet members);
     grants: autogroup:shared → that node's IP on tcp:2106, tcp:7777, tcp:443 only; overlapping allow-all rules removed;
     9014, MariaDB, runner controls, metrics, LLM private. Allowed/denied port tests from a real sharee; advertised
     addresses verified for testers and private runners. No office router change.
12.2 Testers can learn the office public endpoint through Tailscale peer connectivity: recorded as a risk for the
     owner to accept explicitly; no concealment gateway for the hobby. Public live ingress (external, DDoS-protected)
     is a separately specified deployment with activation criteria.
12.3 Capacity is unproven until measured: dispersed farming, dense stores, craft/trade bursts, instance transitions and
     an 800-citizen siege with real combat/movement/visibility fanout/contract processing, under simultaneous CI + LLM
     load. Measure scheduler lateness, packet-queue age (PacketExecutor queue is unbounded), combat ack latency,
     pathfinding, DB commit latency, journal lag, RSS, mapped memory (GeoEngine mmaps regions), disk growth, GC.
     Dedicated CPU sets for game + DB, hard caps on CI runners, explicit pool sizing, bounded custom queues, packet
     pacing. Fix bottlenecks or add resources; never cut features to pass. CPU-set/cap allocation and selection/validation of the pinned H5 client build are engineering implementation tasks; this consensus does not add owner-only approval gates for them.

## 13. Risk register (format: invariant, trigger, detection metric, remedy, recovery, acceptance evidence)
Must include at least: legal/rights for ALL NCSoft-derived artefacts (client, .dat edits, datapack, geodata) as ONE
project-wide register with artefact-specific provenance, applicable terms, distribution, open questions — maintained
during hobby work, decided by the owner before going live (owner acceptance does not create rights; local generation
proves reproducibility, not clearance); correlated test-oracle errors; synthetic economic dominance; compiler/tool
provenance; geodata coverage; privacy leakage; shared-host interference; endpoint exposure to testers; upstream intake
conflicts in the seam. Known implementation gaps are tracked as work, not relabelled as residual risk.

## 14. Owner decisions required (engineering consensus cannot make these)
O1 Accept/decline endpoint exposure to ≤10 invited testers (12.2).
O2 Legal/rights position for NCSoft-derived artefacts during hobby and before live (13).
