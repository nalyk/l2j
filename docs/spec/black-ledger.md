# SPEC-BL — The Black Ledger

- Status: draft (revision 1)
- Blueprint: 16 (the Black Ledger); 03 §4.4 (`ON_CURSED_WEAPON_ACQUIRE`), §4.5 (`canWitness`); 04 §3 (Asset Service), §4
  (Intel Service), §5 (Codex), §7 (keyed randomness); 10 §7 (citizen houses); 12 §6.2 (Crown Law Bounty Endowments);
  17 (publication); consensus §6.
- Depends on: asset-service.md (custody, fees, service mail), intel-service.md (traces, witnesses, disclosure, budgets,
  suppression, rumors, identity evidence: its revision 6 adds the last three for this spec), actor-identity.md (snapshots,
  principals, policy), fact-journal.md (facts, effects), codex-ui.md (pages, tokens, `NpcRef`), pact.md (shared escrow
  primitives only: the Ledger and PACT share the Asset Service, never institutions, 20 §1).
- DDL: `sql/migrations/V012__lc_ledger.sql` (after PACT's V011).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Black Ledger turns finding a person into play: bounties on a character, paid to whoever kills them under the
contract's conditions, with the hunt carried by priced, bounded information. It owns:

- **contracts**: lawful **Writs** (Magistrate) and sealed **Black Contracts** (Broker), their funding, acceptance,
  conditions and settlement;
- **hunters**: registration, ranks, aliases and calling cards;
- the Ledger's **pages and prices** for questioning witnesses, buying informants' sightings, bribing witnesses, planting
  false trails, buying alias links, and the reverse investigation after a failed attempt;
- **counter-intelligence** signals to the hunted;
- **anti-abuse** and **anti-harassment** rules, and payout holds;
- the Ledger's **publication** (wanted boards, legends).

It does not own: information itself (the Intel Service owns traces, witnesses, budgets, expiry and every disclosure; the
Ledger asks and pays), assets (the Asset Service), combat (Mobius: **the Ledger grants nothing in combat**, 16 §1),
karma (retail's, unchanged), PACT's institutions (the Notarius never handles Black Contracts).

### 1.1 The sacred balance rule

0 P.Atk, 0 M.Atk, 0 speed, 0 crit, 0 blow, 0 stealth, 0 debuff, 0 teleport-to-target, 0 combat privilege (16 §1). A hunter
fights under normal rules; killing a white mark creates karma exactly as retail does (`entity/actor/Player.java:5687-5698`,
`:5726-5745`); hitting a mark with karma never flags the hunter, as retail already decides (`Player.java:5798-5837`). Living
Chronicle code calls no mechanics API (actor-identity.md §3), and AT-BL-09 checks the Ledger's.

## 2. Data

### 2.1 Tables

```text
lc_ledger_contract(contract_id BIGINT PK auto, kind {WRIT|BLACK}, cause {KARMA|CURSED_WEAPON|CLAN_WAR|ADMIN|OPEN},
                   state {§3.1}, version INT, mark_char INT, client_char INT NULL, created_at, deadline,
                   principal BIGINT, intel_budget BIGINT, broker_fee BIGINT, conditions JSON, terms_hash BINARY(32),
                   origin_npc VARCHAR(32), origin_town SMALLINT, sealed_identity BOOLEAN, min_rank TINYINT, max_hunters TINYINT,
                   kills_required TINYINT, kills_done TINYINT)
lc_ledger_funding(contract_id, funding_no INT, source {PLAYER|CROWN_LAW|GUILD|HOUSE}, source_id BIGINT, amount BIGINT,
                  op_id BINARY(16), at, PRIMARY KEY(contract_id, funding_no))
lc_ledger_custody(contract_id, purpose {PRINCIPAL|INTEL}, custody_id INT → lc_custody, PRIMARY KEY(contract_id, purpose))
lc_ledger_hunter(char_id PK, rank {LISTENER|TRACKER|STALKER|HEAD_HUNTER|GHOST}, registered_at, completed INT, failed INT,
                 abandoned INT, sightings_verified INT, rank_version INT)
lc_ledger_alias(alias_id INT PK auto, name VARCHAR(24) UNIQUE, owner_char INT UNIQUE, card {FEATHER|COIN|CARD|SYMBOL},
                created_at, exposed_at NULL)
lc_ledger_acceptance(contract_id, hunter_char, accepted_at, card_mode {AT_KILL|DELAYED|NONE}, state {ACTIVE|WON|FAILED|
                     ABANDONED|EXPIRED|VOID}, PRIMARY KEY(contract_id, hunter_char))
lc_ledger_kill(kill_key VARCHAR(64), contract_id, hunter_char, mark_char, at, region_id, territory SMALLINT NULL,
               context JSON, outcome {QUALIFIED|EXCLUDED}, reason VARCHAR(48) NULL, claim_deadline NULL, claimed_op NULL,
               PRIMARY KEY(kill_key, contract_id))                  -- one kill may concern a Writ and a Black Contract
lc_ledger_hold(contract_id PK, reason VARCHAR(48), evidence JSON, opened_at, review_until, state {HELD|RELEASED|RETURNED},
               resolved_by VARCHAR(32) NULL, resolved_at NULL, expires_at)
lc_ledger_seal(lifecycle_id BIGINT PK → lc_item_lifecycle, contract_id, hunter_char, mark_char, value_band TINYINT,
               origin_town SMALLINT, issued_op BINARY(16))
lc_ledger_reverse_case(case_id BIGINT PK auto, lifecycle_id UNIQUE → lc_ledger_seal, holder_char, step TINYINT, opened_at,
                       expires_at)
lc_ledger_offer(offer_id BIGINT PK auto, informant_char, mark_char, trace_id BIGINT, price BIGINT, state {OFFERED|SOLD|
                HELD|PAID|EXPIRED}, buyer_contract NULL, expires_at)
lc_ledger_inquiry(mark_char, window_start TIMESTAMP, inquiries INT, warned BOOLEAN, expires_at, PRIMARY KEY(mark_char, window_start))
lc_ledger_pair(client_char, mark_char, window_start, contracts INT, expires_at, PRIMARY KEY(client_char, mark_char, window_start))
lc_ledger_mark_cooldown(mark_char PK, until)
```

- `client_char` (the depositor of a Black Contract) and `lc_ledger_alias.owner_char` are the Ledger's two secrets. They are
  retained as financial audit (04 §4: "non-sensitive financial audit is retained separately from expiring intel"), read
  only by the Ledger's settlement and evidence code (a static scan, AT-BL-10), never published, and disclosed only through
  the Intel Service under §4.9 and §4.10.
- `lc_ledger_inquiry`, `lc_ledger_pair`, `lc_ledger_offer`, `lc_ledger_reverse_case` and `lc_ledger_hold` (whose evidence
  holds the salted IP hashes of §4.11) expire (`expires_at`) and are listed in `lc_expiring_table` (intel-service.md
  §4.5), so they leave no trail of who asked about whom; a hold's expiry is set after its resolution.
- `lc_ledger_kill.kill_key` is `BL/kill/<journal epoch>/<victim object id>/<victim death count>`, the death count a
  per-character counter the kill hook increments; Mobius can dispatch a player's death event twice
  (`entity/actor/Playable.java:100-115` fires it before its `isDead` guard), and the key makes the second one a no-op.

### 2.2 Custody

A Black Contract has two custodies: `PRINCIPAL` (the bounty) and `INTEL` (the intelligence budget, 16 §3.6, spent on the
hunters' questioning and purchases for this contract, transparent to the depositor). A Writ has `PRINCIPAL` only, funded by
any number of contributions (16 §2: players, Crown Law Bounty Endowments by the Asset Service's treasury `commit`, guild fee
pools, citizen houses as any principal). Every movement is one Asset operation with `OpId`s and keys as PACT's (pact.md §2.3):
request, effect or kill identities; funding keys `BL/<contract>/fund/<n>`; settlement keys `BL/<contract>/<kill or unit>`.

## 3. State machines

### 3.1 Contract

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | `open` (Black, at a Broker in person) | §4.2 eligibility; principal ≥ `MinContractAdena` | `OPEN` | one operation: principal and intel budget into custody, broker fee (`FEE_BROKER`) and, if bought, sealed identity (`FEE_BROKER`) charged |
| — | `open` (Writ, at a Magistrate, or automatically, §4.3) | the mark is lawful game (§4.3) | `OPEN` | first contribution, if any |
| `OPEN` | `contribute` (Writ) | — | `OPEN` | funds added |
| `OPEN` | `cancel` (Black, by the depositor) | no acceptance yet | `CANCELLED` | principal and intel returned; fees not |
| `OPEN` | a qualifying kill (§4.5) | `kills_done + 1 < kills_required` | `OPEN` | `kills_done` + 1; the multi-kill cooldown starts |
| `OPEN` | a qualifying kill | the last required kill | `CLAIMABLE` (Black) or `SETTLING` (Writ) | the kill recorded |
| `CLAIMABLE` | `claim` by the killer in person at a Broker before `claim_deadline` | — | `SETTLING` | — |
| `CLAIMABLE` | claim deadline passes | — | `OPEN` if before the deadline, else `EXPIRED` | the kill lapses unclaimed |
| `SETTLING` | anti-abuse (§4.11) | no signal | `SETTLED` | payout to the hunter, unspent intel to the depositor |
| `SETTLING` | anti-abuse | a hold signal | `HELD` | `lc_ledger_hold` opened |
| `SETTLING` | anti-abuse | a block signal (§4.11) | `OPEN` or `EXPIRED` | the kill `EXCLUDED`; nothing paid |
| `HELD` | admin `release`, or `review_until` passes | — | `SETTLED` | payout |
| `HELD` | admin `return` with reason | — | `RETURNED` | everything back to depositor or funders |
| `OPEN` | deadline | — | `EXPIRED` | principal and unspent intel returned (Writ: each contribution to its source) |
| `OPEN` (Writ) | the lawful basis ends (§4.3) | — | `LAPSED` | contributions returned |

Terminal: `SETTLED`, `RETURNED`, `EXPIRED`, `CANCELLED`, `LAPSED`. Every transition is a durable fact in the transaction of
its movements; a trigger enforces the table.

### 3.2 Acceptance

`ACTIVE` → `WON` (its kill settled) | `FAILED` (killed by the mark while active, §4.7) | `ABANDONED` (by the hunter;
counts against rank) | `EXPIRED` (the contract ended otherwise) | `VOID` (the contract was returned or blocked).

## 4. Operations

### 4.1 Hunters

A character registers as a hunter in person at a Magistrate or a Broker (`LISTENER`). Ranks are information access only
(16 §3.8):

| Rank | Promotion (all, *tunable*) | Unlocks |
|---|---|---|
| `LISTENER` | registration | tavern rumors (§4.6), Writs |
| `TRACKER` | 3 contracts won | witness questioning up to `REGION`; Black Contracts with `min_rank` ≤ Tracker |
| `STALKER` | 10 won, at most as many failed as won, 5 sightings verified | questioning up to `ZONE`; correlation page (the hunter's own granted items side by side) |
| `HEAD_HUNTER` | 25 won | contracts with principal ≥ `HighValueAdena` |
| `GHOST` | 50 won, 20 sightings verified | questioning of traces in the `OLD` age band (the others see `FRESH` and `RECENT` only) |

Every unlock is a narrowing of what the Intel Service may already disclose (its ceilings and budgets are unchanged); none
touches combat. A rank is recomputed from the counters at each settlement; it never falls.

### 4.2 Black Contracts

Opened by a depositor in person at a Broker: the mark, principal, intel budget, deadline (`ContractMinHours`–`MaxHours`),
conditions (§4.5), `min_rank`, `max_hunters`, `kills_required` (1, or up to `MaxKills` with `MultiKillCooldownHours`
between kills), and optionally **sealed identity** (`SealedIdentityAdena`, a sink) which blocks the last two reverse steps
(§4.9). Refused when (anti-harassment, 16 §10):

- the mark is below `NewbieLevel` (default 40), or is not a player (an NPC fake never is a mark, actor-identity.md §3), or is
  in `lc_ledger_mark_cooldown`;
- the depositor and the mark share an account or a principal (actor-identity.md §2.2);
- the depositor already opened *k* contracts on this mark in the `PairWindowDays` window and the principal is below
  `MinContractAdena` × 2^k (diminishing eligibility), or *k* = `MaxPairContracts`;
- a contract by the mark on the depositor was opened in the last `ReciprocalHours` (immediate reciprocal contracts).

A hunter accepts in person at a Broker if registered, of `min_rank` or above, not the depositor, sharing no account or
principal with depositor or mark, within `LevelGapMax` levels above the mark, and while fewer than `max_hunters` are
`ACTIVE`; they choose their calling-card mode (§4.8). Acceptance gives the **dossier** (§4.4).

### 4.3 Writs

A Writ is lawful: its mark must be lawful game at the moment of opening and of each qualifying kill:

| Cause | Lawful while | Opened |
|---|---|---|
| `KARMA` | the mark's karma > 0 | by anyone at a Magistrate |
| `CURSED_WEAPON` | the mark holds a cursed weapon (`managers/CursedWeaponsManager.java:349`, `checkOwnsWeaponId`) | **automatically** when one is acquired, by the `ON_CURSED_WEAPON_ACQUIRE` seam (03 §4.4) at `CursedWeapon.activate` (`entity/actor/holders/player/CursedWeapon.java:369-393`): Mobius dispatches no event there; funded by the active Crown Law Bounty Endowments and by contributions |
| `CLAN_WAR` | the hunter's clan and the mark's clan are at **mutual** war: each `isAtWarWith` the other (`entity/clan/Clan.java:1643`), neither member an academy member, as retail's PvP credit requires (`Player.java:5672`); `Player.isAtWarWith` is one-sided (:15074-15086) and is not used | by a clan leader of a clan at war with the mark's |

When the cause ends (karma reaches 0; the cursed weapon leaves the mark by drop or expiry, `CursedWeapon.java:458`, :95-185;
the war ends, `data/sql/ClanTable.java:443-479`), the Writ **lapses** and its contributions return to their sources, unless a
kill was already recorded (its settlement proceeds). Any registered hunter may take a Writ (no exclusivity, no rank
minimum). Writs are public: the wanted boards (§4.12) show each mark's total.

### 4.4 Dossier

Identity-level facts only (16 §3.1): name, race, level band (the mark's level rounded down to 5 and up to 5 more), clan,
the contract's value and deadline, and **last confirmed activity** as the Intel Service's age band of the newest item the
hunter's grants hold about the mark (`FRESH`/`RECENT`/`OLD`/none). Current location: always `UNKNOWN`. When the mark is
offline, no new traces exist (intel-service.md §3), so the trail goes cold by itself (16 §3.4).

### 4.5 Kills and conditions

**Capture.** The kill hook is a listener on `ON_CREATURE_DEATH` for players, dispatched synchronously before the victim's
death is processed (`entity/actor/Playable.java:100-107`). It resolves the killer's acting player (`asPlayer()`, which
for summons, pets, traps, decoys and effect points is their owner, `entity/actor/Summon.java:884`,
`instance/Trap.java:229`, `instance/Decoy.java:220`, `instance/EffectPoint.java:52`; a damage-over-time kill's killer is its
caster, `dist/game/data/scripts/handlers/skill/effects/DamOverTime.java:83`), takes both snapshots (actor-identity.md §2.4)
and the context, and for each contract and acceptance the victim and killer match, stages a `Ledger.Kill` outbox fact
keyed by the kill key. The asynchronous `OnPlayerPvPKill` (`Player.java:5308-5313`) is not used.

**Excluded contexts** (the kill is recorded `EXCLUDED` with the reason, and settles nothing): the killer is not an `ACTIVE`
acceptor of the contract (Black) or not a registered hunter (Writ); the victim is not `INTERACTIVE` (actor-identity.md
§2.3: no offline farming); the killer holds a cursed weapon (`Player.isCursedWeaponEquipped`, :12861); either is in a PvP
zone or a PvP instance (`entity/actor/Creature.java:428-435`: arenas, the Coliseum and event instances); either is on or
registered for an event (`Player.java:14844-14872`); Kratei's Cube or the Underground Coliseum (`Player.java:5279-5306`).
Olympiad and duel deaths never reach this hook: retail ends them before `doDie` (`entity/actor/status/PlayerStatus.java:270-284`,
`:300-306`).

**Conditions** (all machine-verifiable, 16 §5), read from the kill's snapshots and context: before the deadline;
outside a siege zone (`isInsideZone(SIEGE)`, set only during an active siege, `entity/zone/type/SiegeZone.java:173-179`);
while the mark is in a named clan; in a named territory (`data/xml/MapRegionData.java:412-416`, `getAreaCastle`) — the
"public execution"; for a multi-kill contract, at least `MultiKillCooldownHours` after the previous qualifying kill.

**Settlement.** A Writ settles at once: the whole principal to the killer by service mail. A Black Contract becomes
`CLAIMABLE`: the killer claims in person at any Broker before `claim_deadline` (`ClaimHours`), which is the **delayed
Broker delivery** (16 §6); the principal goes to the hunter by service mail and unspent intel to the depositor. After a
successful execution the mark enters `lc_ledger_mark_cooldown` for `MarkCooldownHours`.

### 4.6 Questioning, sightings, rumors

All disclosures are the Intel Service's (intel-service.md §4.2), with these purposes and prices:

| Page | Where | Purpose | Paid from | Price (sink unless said) |
|---|---|---|---|---|
| question a witness | the witness NPC, in person | `LEDGER_QUESTIONING` (its ceiling: the hunter's rank's, §4.1) | the contract's `INTEL` custody, else the hunter | `QuestionAdena` × the disclosure's units, `FEE_QUESTIONING` |
| tavern rumors | an information NPC, in person | `LEDGER_RUMOR` (`TERRITORY`, confidence `RUMOR`) | the hunter | `RumorAdena`, `FEE_QUESTIONING` |
| buy a sighting | a Broker, in person | `LEDGER_INFORMANT` | `INTEL` custody | the informant's price, **transferred** to the informant by service mail (held under §4.11 if linkage) |

**Clan-hall stewards** (16 §3.3) answer only about the membership and activity (age band) of the clan that owns their hall
and its members, never about places; they refuse a hunter whose target is a member of the hall's owning clan unless the
hunter's clan is at mutual war with it, and they are never bribable (§4.7). Willingness beyond that follows Crown Law
politics once Crown Law is specified (12 §6), a factor of 1 until then.

**Informants** (16 §3.5): a character who sighted a mark (the Intel Service's observation, intel-service.md §3.1-§3.2:
`canWitness` held for the consecutive samples) submits the sighting in person at a Broker with an asking price; the Intel
Service records it as an `INFORMANT` trace only if the informant shares no account or principal with the mark (intel-service.md
§3.2). The offer is listed to hunters with active acceptances on that mark; equivalent sightings are deduplicated by the Intel
Service (one item per trace, grants for the rest). An informant whose sighting is bought gains `sightings_verified` if they
are also a hunter.

### 4.7 Counter-intelligence

- **Intuition** (16 §4): `lc_ledger_inquiry` counts the Ledger's own disclosure requests about a mark per window
  (`InquiryWindowMinutes`). When a window's count reaches `IntuitionThreshold`, with the keyed probability
  `IntuitionChance` (`rng("ledger.intuition", mark, window)`, 04 §7), the mark, if `INTERACTIVE`, receives once per window
  `ExShowScreenMessage` "You feel watched." (`network/serverpackets/ExShowScreenMessage.java:67`). Never a proximity alarm,
  never a count.
- **Clan intelligence**: the clan owning a castle sees on its Codex page, per day, the number of Ledger questionings
  performed at witnesses in its territory (a count, a day, no names).
- **Bribing a witness**: the mark, in person at a bribable witness (the witness kinds table, intel-service.md §8: gatekeepers,
  warehouse keepers, merchants and tavern NPCs are bribable; guards, Magistrates and clan-hall stewards never), pays
  `BribeBaseAdena` × the witness's sensitivity × (1 + the mark's PK count / 10), `FEE_BRIBE`; the Intel Service suppresses
  that witness for that subject for `BribeMinutes` (≤ `BribeMaxMinutes`) (`intel.suppress`, intel-service.md revision 6).
  The trace and the audit remain (INTEL-R08).
- **False trails**: the mark pays a Broker `FalseTrailAdena` (`FEE_BROKER`) to plant a rumor that they were seen in a
  territory of their choosing; the Intel Service records it as a `RUMOR` trace (`intel.plantRumor`), disclosed only at
  `RUMOR` confidence through tavern rumors, among the real ones, never as `WITNESS` or `CONFIRMED`, never as history.
- **Trail manipulation** by movement needs no mechanism: the witnesses see what the mark does (16 §4).

### 4.8 Aliases and calling cards

A hunter may register one **nom de guerre** at a Broker (`AliasAdena`, `FEE_ALIAS`; a unique name; a card kind). The alias is
never a visible name in the world: Mobius's visible-name override is lost on transformation
(`entity/actor/transform/Transform.java:222-230`) and many channels send the real name anyway (chat, title, announcements),
so the alias lives only in Ledger pages, calling cards and history. Ordinary combat shows the attacker's real character, as
retail does (`network/serverpackets/CharInfo.java:137`): anonymity is the confidentiality of Ledger records, not invisibility
(consensus §6.5).

Calling-card modes, chosen at acceptance: `AT_KILL` — at the qualifying kill the victim receives "A black feather was left
beside your body." naming the alias (the victim, who saw their killer, can now link alias and character; §4.10);
`DELAYED` — the Broker delivers the card to the victim when the kill is claimed, without the claim's time or place; `NONE`.

### 4.9 Target victory and the reverse investigation

When a mark kills a hunter who holds an `ACTIVE` acceptance on a **Black Contract** on that mark (any non-excluded context,
§4.5), the acceptance `FAILED` and the mark receives by service mail a **Torn Black Seal**: a non-tradable, valueless item
issued from the `LEDGER_SEAL` source (asset-service.md revision 9), one per (contract, hunter). Its evidence is the row
`lc_ledger_seal`, keyed by the item's Asset lifecycle id, never by item variables: Mobius deletes an item's variables when it
drops and does not restore them on pickup (`entity/item/instance/Item.java:1763`; `entity/item/inventory/Inventory.java:795-814`).

The seal's holder opens a **reverse case** in person at a Magistrate, within `ReverseCaseHours` of the seal's issue; each
step is an Intel Service identity disclosure (purpose `LEDGER_REVERSE`, precision `NONE`) of evidence the Ledger recorded
when the seal was issued (`intel.recordIdentity`):

| Step | Reveals | Price |
|---|---|---|
| 1 | the contract's value band and origin town (the Broker network) | free |
| 2 | the patron's clan, or "no clan" — "obscured" if sealed identity was bought | `ReverseStep2Adena`, `FEE_QUESTIONING` |
| 3 | the patron's name — "obscured" if sealed identity was bought | `ReverseStep3Adena`, `FEE_QUESTIONING` |

The failed hunter is not a step: the mark fought them and saw them (CharInfo). Consequences of a discovered patron are the
players' (16 §5): the Ledger does nothing more.

### 4.10 The alias intelligence market

The only certified alias link is the one an `AT_KILL` calling card creates: at that kill the Ledger records, through the Intel
Service, identity evidence `ALIAS_LINK` (alias → killer) whose witness is the victim. The victim may sell it in person at a
Broker for `AliasLinkAdena` paid by each buyer (a transfer to the victim, held under §4.11 if linkage); buyers receive the
link at `CONFIRMED` confidence. Its first sale sets the alias's `exposed_at`. Gossip is never certified: nothing else creates
`ALIAS_LINK` evidence. An exposed hunter is an ordinary possible mark.

### 4.11 Anti-abuse

At settlement (and at each informant or alias payment), the payout is checked against signals (16 §8-§9; consensus §6.3):

| Signal | Effect |
|---|---|
| hunter and mark, or hunter and depositor, share an account or a principal | **block**: the kill `EXCLUDED`, nothing paid |
| the mark is an NPC fake or a `TEST` profile actor (actor-identity.md §3 policy) | block |
| hunter and mark in the same party or clan at the kill, or within `LinkWindowHours` before it | hold |
| a player-to-player trade between hunter and mark, or hunter and depositor, within `LinkWindowHours` (`ON_PLAYER_TRADE_COMPLETE`, 03 §4.4) | hold |
| the same client IP at the kill or the contract's opening (a salted hash of `GameClient.getIp`, `network/GameClient.java:188`, kept with the hold's evidence and expiring with it) | hold |
| the hunter–mark pair settled `PairSettleMax` times in `PairWindowDays` | hold |
| `AntiFeedManager.check(hunter, mark)` false, when that retail feature is enabled (`managers/AntiFeedManager.java:68-117`; IP-only and off by default, `dist/game/config/PVP.ini:35-50`) | hold |

A **hold** keeps the principal in custody (`HELD`): the hunter and depositor are told the payout is under review. An admin
releases or returns it with a reason; with no decision by `review_until` (`HoldReviewHours`), it is **released** — the
explicit false-positive rule: a hold delays, it does not confiscate. Nothing is ever refunded by minting (16 §7): returns go
from the custody to its funders. The published guarantee is bounded disclosure and economically unattractive farming, not
the identification of who controls an account (consensus §6.3).

### 4.12 Publication and history

Publication types registered in the publication registry (intel-service.md §4.7):

| Type | Fields | Delay |
|---|---|---|
| `ledger_wanted` | mark (`PUBLIC_ENTITY`), total open Writ value (`COUNT`), cause, day | none: Writs are public (16 §2.1), and the row has no place |
| `ledger_legend` | mark, alias (never the hunter's character unless exposed), value band, outcome, territory (`AREA_TERRITORY`), day | `PublicationTacticalDelayMinutes` (location-related) |

A Black Contract is promoted to `ledger_legend` only after it is terminal and only if its principal ≥ `LegendValueAdena` or
a hunter failed on it; the depositor, the conditions and the informants are never published (16 §11). Wanted boards are NPC
pages in town squares reading `ledger_wanted`.

## 5. Facts

Outbox facts in the transaction of their change: `Ledger.ContractOpened` (kind, cause, mark; for Black, no client field),
`Ledger.Contributed`, `Ledger.Accepted`, `Ledger.Abandoned`, `Ledger.Kill` (kill key, contract, hunter, mark, outcome,
reason, context), `Ledger.Claimed`, `Ledger.Settled`, `Ledger.Held`, `Ledger.HoldResolved`, `Ledger.Expired`,
`Ledger.Lapsed`, `Ledger.Cancelled`, `Ledger.SealIssued`, `Ledger.ReverseStep`, `Ledger.AliasRegistered`,
`Ledger.AliasExposed`, `Ledger.RankChanged`, `Ledger.Bribe`, `Ledger.FalseTrail`, `Ledger.Intuition`. The client of a
Black Contract and an alias's owner appear in no fact; the settlement's postings name the receivers, and ledger postings are
financial audit (asset-service.md). Consumed: player deaths (the kill hook), `ON_CURSED_WEAPON_ACQUIRE`, clan war start and
end (`ClanTable.java:405-408`, `:448-451`), karma changes (`Player.java:2065-2071`), trades.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| BL-INV01 | the Ledger changes no combat value and calls no mechanics API | the static scan; AT-BL-09 |
| BL-INV02 | no coordinate, location or evidence reaches a Ledger page or table | the Intel Service's types; AT-BL-10 |
| BL-INV03 | a kill settles at most once (the kill key and the settlement key) and only if qualified | keys; AT-BL-04 |
| BL-INV04 | Ledger money only moves between custodies, holders and sinks; nothing is minted | the Asset Service's postings; AT-BL-11 |
| BL-INV05 | a depositor's identity and an alias's owner are disclosed only by §4.9 and §4.10 | the static scan of readers; AT-BL-10 |
| BL-INV06 | every expiring Ledger row is gone by its `expires_at` + purge interval | the expiring registry; AT-BL-10 |

## 7. Failure model

| Fault | End state |
|---|---|
| death event dispatched twice | the second kill fact is refused by its key |
| crash during settlement, a claim or a payment | the Asset Service's: committed or not; the next request or effect writes the key once |
| the mark logs out | no new traces; the dossier's activity ages; the contract runs to its deadline |
| a hunter deleted while `ACTIVE` | acceptance `VOID`; a claimable kill of theirs lapses unclaimed |
| the depositor deleted | returns go to the deletion guard's rule (asset-service.md §2.1): the release aborts with `RECEIVER_GONE` and the contract goes to admin review |
| Intel Service down | no questioning, no sightings, no rumors; contracts and kills continue |

## 8. Configuration

`config/Custom/LivingChronicle/Ledger.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `MinContractAdena` | 10000–100000000 | 1000000 | as default everywhere | AT-BL-02 |
| `NewbieLevel` | 1–85 | 40 | 40 everywhere | AT-BL-02 |
| `LevelGapMax` | 5–85 | 20 | 20 everywhere | AT-BL-02 |
| `PairWindowDays`, `MaxPairContracts`, `ReciprocalHours` | 1–90, 1–10, 1–168 | 7, 3, 24 | as default everywhere | AT-BL-02 |
| `ContractMinHours`, `ContractMaxHours` | 1–720 | 6, 168 | as default everywhere | AT-BL-01 |
| `ClaimHours` | 1–168 | 24 | 24 everywhere | AT-BL-04 |
| `MarkCooldownHours` | 0–720 | 48 | 48 everywhere | AT-BL-02 |
| `MaxKills`, `MultiKillCooldownHours` | 1–5, 1–168 | 3, 24 | as default everywhere | AT-BL-04 |
| `SealedIdentityAdena`, `AliasAdena`, `AliasLinkAdena`, `FalseTrailAdena`, `RumorAdena`, `QuestionAdena` | 0–100000000 | 5000000, 1000000, 2000000, 500000, 20000, 50000 | as default everywhere | AT-BL-11 |
| `BribeBaseAdena`, `BribeMinutes`, `BribeMaxMinutes` | — | 200000, 30, 120 | as default everywhere | AT-BL-07 |
| `ReverseCaseHours`, `ReverseStep2Adena`, `ReverseStep3Adena` | — | 24, 1000000, 10000000 | as default everywhere | AT-BL-08 |
| `InquiryWindowMinutes`, `IntuitionThreshold`, `IntuitionChance` | 10–1440, 1–50, 0–1 | 60, 3, 0.5 | as default everywhere | AT-BL-07 |
| `LinkWindowHours`, `PairSettleMax`, `HoldReviewHours` | — | 72, 3, 72 | as default everywhere | AT-BL-06 |
| `HighValueAdena`, `LegendValueAdena` | — | 50000000, 50000000 | as default everywhere | AT-BL-12 |

## 9. Interface

Magistrate pages: register, open and fund Writs, take a Writ, wanted board, reverse case. Broker pages: register, alias,
open a Black Contract (with sealed identity), accept, claim, informant offers, alias links, false trails. Witness NPC
pages: questioning (and, for the mark, bribing). Codex → Ledger tab, shown only to registered hunters, depositors, Writ marks
and seal holders: dossiers, granted intel with confidence and age band, offers, the reverse case file, rank. Admin:
`lc why ledger <id>`, `lc ledger hold release|return <id> <reason>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| BL-R01 | Contracts MUST move only by §3.1, every transition a durable fact in the transaction of its movements. | AT-BL-00, AT-BL-01 |
| BL-R02 | A Black Contract MUST be refused for protected marks, linked depositors, repeated pairs below the diminishing threshold and reciprocal contracts; a hunter MUST be refused outside rank, level gap, linkage and capacity limits. | AT-BL-02 |
| BL-R03 | A Writ MUST exist only while its lawful basis holds, open automatically on cursed-weapon acquisition, and lapse with its basis, returning contributions. | AT-BL-03 |
| BL-R04 | A kill MUST be captured synchronously with snapshots, excluded in the listed contexts, qualify only under its conditions, and settle at most once. | AT-BL-04 |
| BL-R05 | Every disclosure MUST go through the Intel Service with the Ledger's purposes, ranks and prices; informant sightings MUST be server-verified. | AT-BL-05 |
| BL-R06 | Payouts MUST be blocked or held on the listed signals, holds MUST release by default after review, and nothing MUST be refunded by minting. | AT-BL-06 |
| BL-R07 | Intuition, bribes and false trails MUST work as specified, bribes and rumors through the Intel Service, rumors always `RUMOR`. | AT-BL-07 |
| BL-R08 | A failed hunter's death MUST give the mark one seal, and the reverse case MUST reveal exactly its steps, honouring sealed identity. | AT-BL-08 |
| BL-R09 | The Ledger MUST grant nothing in combat. | AT-BL-09 |
| BL-R10 | No coordinate MUST reach the Ledger; depositors and alias owners MUST be disclosed only by the reverse case and certified alias links; expiring rows MUST expire. | AT-BL-10 |
| BL-R11 | Money MUST move only through the Asset Service as transfers and sinks, with zero minting. | AT-BL-11 |
| BL-R12 | Publication MUST be only of the Ledger's registered types, never naming depositors, conditions or informants. | AT-BL-12 |
| BL-R13 | Hunts MUST succeed through evidence, with target survival and hunt duration inside declared bands, under full out-of-band sharing. | AT-BL-13 |

| Test | Kind | What it does |
|---|---|---|
| AT-BL-00 | integration | the migration applies; triggers refuse transitions outside §3.1 (all pairs), a second kill fact per key, a second seal per (contract, hunter), a reverse case skipping a step, a second alias per character or name |
| AT-BL-01 | integration | every transition of §3.1 through the operations, with their movements and facts |
| AT-BL-02 | integration | each refusal of §4.2 at its boundary (level 39/40, gap 20/21, the k-th pair contract at 2^k, reciprocal at 23/25 hours, cooldown) |
| AT-BL-03 | integration | Writs per cause: opened, a kill while lawful settles, the basis ending lapses and returns each contribution to its source; a cursed-weapon acquisition opens a Writ, its drop or expiry lapses it |
| AT-BL-04 | integration | kills by the hunter, their summon, trap and damage over time credit the hunter; each excluded context (PvP zone, event instance, event registration, cursed weapon, offline mark, Kratei's Cube) records `EXCLUDED`; each condition at its boundary; the death event delivered twice settles once; claim before and after `ClaimHours` |
| AT-BL-05 | integration | questioning at each rank sees at most its ceiling; prices drawn from the intel custody then the hunter; a sighting with an informant sharing the mark's account refused; equivalent sightings bought by two hunters: one item, two grants |
| AT-BL-06 | integration | each signal of §4.11 blocks or holds as listed; a hold released by an admin, returned by an admin, and released by timeout; no source posting on any path |
| AT-BL-07 | integration | intuition at the threshold with the keyed chance, once per window, never to an offline mark; a bribed witness discloses nothing about the mark until the suppression ends; lawful witnesses refuse bribes; a planted rumor appears only as `RUMOR` among real rumors |
| AT-BL-08 | integration | a hunter killed by the mark: one seal by mail; a reverse case reveals steps 1-3 in order, "obscured" with sealed identity; after `ReverseCaseHours` nothing |
| AT-BL-09 | unit | the static scan: no Ledger code calls a mechanics API or reads a combat stat; a planted call fails it |
| AT-BL-10 | unit, integration | the static scan of readers of `client_char` and `owner_char`; no Ledger table has a coordinate column; expiring rows gone after purge, also in a restored backup |
| AT-BL-11 | integration | every Ledger flow: postings balance; sinks at their codes; no source except `LEDGER_SEAL` (valueless) |
| AT-BL-12 | integration | publication rows of both types; a Black Contract's legend without depositor, conditions or informants; an attempt to publish another field refused |
| AT-BL-13 | sim | the full-sharing citizen population (10 §), hunters and marks as citizens with relayed intel: target survival and hunt duration inside the declared bands of the Truth Report |

## 11. Upstream constraints

1. **Player death fires the synchronous death event before the dead check** (`Playable.java:100-115`), naming the raw
   killer (`OnCreatureDeath.java:32-43`). → the kill hook resolves `asPlayer()` and keys kills (§2.1, §4.5).
2. **Olympiad and duel deaths never reach `doDie`** (`PlayerStatus.java:270-284`, `:300-306`). → no exclusion needed there.
3. **Cursed weapon acquisition dispatches nothing** (`CursedWeapon.java:369-393`). → the `ON_CURSED_WEAPON_ACQUIRE` seam.
4. **Clan war is one-sided per clan** (`Clan.java:146-147`, `:1638-1648`). → mutual war tested both ways (§4.3).
5. **AntiFeed is IP-only and off by default** (`AntiFeedManager.java:68-117`, `PVP.ini:35-50`). → a signal among the Ledger's
   own (§4.11).
6. **No newbie PK protection exists** (`dist/game/config/Player.ini:777,781` are spawn and teleport protection only). → the
   Ledger's `NewbieLevel`.
7. **Item variables do not survive a drop** (`Item.java:1763`, `Inventory.java:795-814`). → seal evidence in a Ledger
   table keyed by the Asset lifecycle id (§4.9).
8. **Visible-name overrides leak and are reset by transformations** (`Transform.java:222-230`). → aliases never in the world
   (§4.8).

## 12. Review record

- Revision 1: draft, not yet reviewed.
