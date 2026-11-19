# SPEC-PACT — PACT and the Notarius

- Status: draft (revision 1)
- Blueprint: 15 (PACT and the Notarius); 03 §4.2-§4.4 (loot routing, crafting boundary, new events); 04 §3 (Asset
  Service), §5 (Codex); 12 §6.2 (Crown Law Market Charters), §8 (Caravan Escort); 17 (publication); consensus §4.
- Depends on: asset-service.md (custody, operations, settlement keys, service mail), fact-journal.md (facts, projectors,
  effects), actor-identity.md (snapshots, policy), intel-service.md (publication registry), codex-ui.md (pages, tokens).
- DDL: `sql/migrations/V011__lc_pact.sql` (after the Asset Service's V010).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

PACT enforces agreements made only of **machine-verifiable clauses** that every party signed, and leaves everything else
risky (15 §1, §7). It owns contracts, their clauses, parties and states; the evaluation of clauses from facts; settlement
through the Asset Service; the craft-for-contract and loot-charter operations; the Notarius NPC pages; the public
registry; and the objective history.

It does not own: assets and their movement (the Asset Service: every escrow, release, fee and refund is one of its
operations, and PACT never touches an item), facts (the Fact Journal), sieges, fortresses and Territory War (Mobius:
PACT reads their registrations and facts, never changes them), Black Contracts (the Black Ledger; the Notarius never
processes them, 15 §8), Caravan Escort's issuing (the Caravan Guild, 12 §8; PACT runs the contract once issued).

PACT never judges effort, quality, loyalty or intent, and never prevents an act: a Treaty prices an attack, it does not
stop one.

## 2. Data

### 2.1 Tables

```text
lc_pact_contract(contract_id BIGINT PK auto, type {CRAFTING_PACT|CRAFT_ORDER|PURCHASE_ORDER|DELIVERY_ORDER|LOAN|LOOT_CHARTER|
                 MERCENARY|CARAVAN_ESCORT|TREATY}, state {…§3.1}, version INT, visibility {PRIVATE|PARTIES|PUBLIC},
                 publishable BOOLEAN, notarius_tier {TOWN|ROYAL|CASTLE|CARAVAN}, notarius_town SMALLINT,
                 created_by INT, created_at TIMESTAMP(3), deadline TIMESTAMP(3), terms_hash BINARY(32),
                 terms JSON, value_adena BIGINT, review_reason VARCHAR(128) NULL)
lc_pact_party(contract_id, party_no TINYINT, role {CLIENT|CONTRACTOR|LENDER|BORROWER|BUYER|SELLER|DELIVERER|EMPLOYER|
              MERCENARY|SIGNATORY|LEADER|MEMBER}, char_id INT NULL, clan_id INT NULL, signed_version INT NULL,
              signed_at TIMESTAMP(3) NULL, signed_op BINARY(16) NULL, PRIMARY KEY(contract_id, party_no))
lc_pact_clause(contract_id, clause_no SMALLINT, kind {DELIVER_ITEMS|PAY_ADENA|CRAFT_ATTEMPT|DEADLINE|PARTICIPATION|
               REGISTRATION|LOOT_POLICY|COLLATERAL|REPAY|FORFEIT|CANCEL_CONDITION}, params JSON, PRIMARY KEY(contract_id, clause_no))
lc_pact_custody(contract_id, purpose {FUNDING|COLLATERAL|OUTPUT|CHARTER|FORFEIT}, party_no TINYINT NULL, custody_id INT → lc_custody,
                PRIMARY KEY(contract_id, purpose, custody_id))
lc_pact_settlement(contract_id, clause_no, unit INT, skey VARCHAR(96) → lc_settlement_key(scope 'PACT'), outcome
                   {PAID|RETURNED|FORFEITED|NONE}, op_id BINARY(16), at TIMESTAMP(3), PRIMARY KEY(contract_id, clause_no, unit))
lc_pact_craft_outcome(contract_id, attempt_no INT, recipe_id INT, crafter_char INT, success BOOLEAN, masterwork BOOLEAN,
                      product_item_id INT NULL, product_count INT, op_id BINARY(16), rolled_at TIMESTAMP(3),
                      PRIMARY KEY(contract_id, attempt_no))
lc_pact_charter_member(contract_id, char_id INT, joined_version INT, state {FROZEN_IN|LEFT|DISCONNECTED|DEAD_AT_KILL},
                       PRIMARY KEY(contract_id, char_id))
lc_pact_registry(contract_id PK, public_text_key VARCHAR(64), registered_at TIMESTAMP(3))
lc_pact_history(char_id INT PK, completed INT, cancelled INT, defaults INT, craft_attempts INT, craft_success INT,
                as_of_fact BIGINT)
```

- `version` grows by one at every change of terms or state; every Asset operation on the contract carries
  `expectVersion(PACT_CONTRACT, id, version)` (asset-service.md §4.1), so a settlement never acts on terms it did not read.
- `terms` is the canonical JSON of the clauses and parties; `terms_hash` its SHA-256. A signature is a party's
  `signed_version`: it signs exactly that version's terms (codex-ui.md §4.3), and any change of terms clears every
  signature (§3.1).
- Settlement uniqueness is the Asset Service's single mechanism: each settlement unit (a clause, a batch, a fill, an
  instalment) has the key `PACT/<contract>/<clause>/<unit>` in `lc_settlement_key`; `lc_pact_settlement` records its
  outcome (15 §4).
- `lc_pact_craft_outcome` is written in the transaction that consumes the materials; its rows are immutable (a trigger)
  and an attempt is never rolled twice (§4.3).
- `lc_pact_history` is a projection of PACT facts (fact-journal.md §4), never written by an operation (§6).

### 2.2 Value and tiers

`value_adena` is the contract's escrowed value at reference prices (Adena at face value; items at the Asset Service's
monitor reference, asset-service.md §2.6). A contract of `value_adena` ≥ `RoyalThresholdAdena` (default 10 000 000), or
whose parties include a clan, or that is a Treaty, needs a Royal Notarius; Mercenary contracts need the Castle Notarius of
the castle concerned; Caravan Escorts the Caravan Guild (15 §8).

## 3. State machines

### 3.1 Contract

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | `draft` | the type's form valid; the drafter is a party | `DRAFT` | row, clauses, parties |
| `DRAFT` | `amend` | the drafter | `DRAFT` | `version` + 1, terms hash recomputed |
| `DRAFT` | `propose` | every party named; the registration fee affordable | `PROPOSED` | `version` + 1; the parties see it |
| `PROPOSED`, `PARTIALLY_SIGNED` | `amend` | any party, by counter-proposal | `PROPOSED` | `version` + 1; **every signature cleared** |
| `PROPOSED`, `PARTIALLY_SIGNED` | `sign` | the token's version is current; in person where §2.2 requires; the signer's role | `PARTIALLY_SIGNED`, or `SIGNED` when the last party signs | `signed_version`; at the last signature the registration fee (`FEE_NOTARIUS`) and, if sealed, the sealing fee, charged in the same operation |
| `PROPOSED`, `PARTIALLY_SIGNED` | `withdraw` | any party | `WITHDRAWN` | nothing escrowed yet |
| `SIGNED` | `fund` | each funding party's assets, by the deadline to fund | `FUNDED` when every funding clause is escrowed | Asset `fund` into the contract's custodies |
| `SIGNED` | funding deadline | not all funded | `EXPIRED` | escrowed parts returned by service mail |
| `FUNDED` | activation (immediate, or the clause's start: a siege's registration close, a raid's start) | — | `ACTIVE` | the type's activation effect (a loan's principal mailed; a charter's members frozen) |
| `ACTIVE` | a settlement unit evaluates | §4.2 | `ACTIVE` | the unit's settlement |
| `ACTIVE` | all units settled | — | `COMPLETED` | custodies closed; parchments marked |
| `ACTIVE` | deadline | units outstanding | `EXPIRED` | each outstanding unit's expiry rule (§4.2) |
| `ACTIVE` | a cancel condition holds (a clause, from facts) | — | `FAILED_BY_CONDITION` | the condition's rule |
| `SIGNED`, `FUNDED`, `ACTIVE` | `cancel` signed by every party on the current version | — | `MUTUALLY_CANCELLED` | everything escrowed returned to its funder |
| any but terminal | `review` by an admin with an audit fact | a corruption or bug | `ADMIN_REVIEW` | the contract's custodies frozen (asset-service.md §4.5) |
| `ADMIN_REVIEW` | `resolve` by an admin | invariants hold | the state it left, or a terminal state | audit fact |

Terminal: `COMPLETED`, `EXPIRED`, `MUTUALLY_CANCELLED`, `FAILED_BY_CONDITION`, `WITHDRAWN`. Every transition is a durable
fact written in the transaction of its asset movements (15 §3); a transition with none is a transaction of its own. A
transition not in the table is impossible (a trigger refuses an update of `state` to a value the table does not allow
from the old one).

### 3.2 Settlement unit

`OPEN` → `PAID` | `RETURNED` | `FORFEITED` | `NONE`, once, by the unit's settlement key. A unit is the smallest thing that
settles on its own: a whole contract for a Delivery Order, a batch for a Craft Order, a fill for a Purchase Order, an
attempt for a Crafting Pact, a reserved item or a lot for a Loot Charter, a measured party for a Mercenary contract, a
violation for a Treaty.

## 4. Operations

### 4.1 Common rules

- Every action is a Codex action (codex-ui.md): a one-use token bound to the contract's id, version and terms hash, and,
  for signing a contract that §2.2 sends to a Notarius, to that Notarius NPC (`NpcRef`). Remote drafting, proposing,
  reviewing and signing are allowed for the rest (15 §8).
- Every movement is one Asset Service operation with `OpId.of(PACT, contract, action, unit-or-attempt)`, the contract's
  `expectVersion`, the unit's settlement key, the state change and its fact. PACT has no other way to move anything.
- What is owed to a character is delivered as service mail (asset-service.md §2.1): a full inventory never blocks a
  settlement, and nothing waits in a custody for a claim.
- A clan party acts through its leader or a member with the clan right `ClanAccess.ACCESS_WAREHOUSE` (as retail checks it,
  `network/clientpackets/SendWareHouseWithDrawList.java:129`) for funding from the clan
  warehouse and through its leader for signing; the clan's payouts go to the leader (asset-service.md §2.1).
- Clause evaluation reads only facts and native state named in the clause's kind (§4.4); it is a projector (fact-journal.md
  §4) whose settlements are effects with application ids, so an evaluation is applied once and replay writes nothing.

### 4.2 Contract types

| Type | Funding | Units and settlement | Expiry and failure |
|---|---|---|---|
| Crafting Pact | client: materials for *n* attempts + fee; contractor: none | per attempt (§4.3): fee to the contractor whatever the outcome; product, if any, to the client; materials consumed per retail | unattempted attempts' materials and fees returned to the client |
| Craft Order | client: materials for *b* batches + fee per batch | per batch, crafted under §4.3; partial batches settle as they are made (15 §2) | unfilled batches returned |
| Purchase Order | buyer: maximum spend in Adena | per fill: a seller hands items at any Notarius (or remotely, if allowed by the order), the items go to the buyer and the price per unit to the seller in **one** operation; the buyer's custody shrinks by exactly the paid amount; no Adena is created | the remaining Adena returned |
| Delivery Order | client: payment | one unit: the deliverer hands the items at the named Notarius before the deadline; items to the receiver, payment to the deliverer. PACT verifies the handing over, not the journey | payment returned |
| Loan | lender: principal; borrower: collateral | activation mails the principal to the borrower; repayment (borrower funds the repayment amount) returns the collateral to the borrower and pays the lender | at the deadline, unrepaid: the collateral to the lender (forfeit), mechanically; never more than the collateral |
| Loot Charter | none, or the reserved items' bidders' bids | per drop routed (§4.5) | an unfinished auction's lot goes by the charter's fallback rule |
| Mercenary | employer: payment | per mercenary party (a clan or a character), on the siege's, fortress siege's or Territory War's end fact: paid if its registration and measured participation clauses held (§4.6) | payment returned for parties that did not qualify |
| Caravan Escort | the Caravan Guild: payment | delivery + participation clauses, on the caravan's arrival fact (12 §8) | per the issuing terms |
| Treaty | each signatory: its forfeit | per violation: a kill by a member of one signatory of a member of another inside the named territory and window (§4.7) pays that violator's forfeit to the victim's side, once per signatory per window | at the end of the window, each forfeit returned |

### 4.3 Craft for contract

Retail crafting takes materials only from the customer's inventory (`managers/RecipeManager.java:608-631`, `listItems`),
destroys them there (:642), rolls success on the packet thread (`Rnd.get(100) < successRate`, :416, from
`dist/game/data/Recipes.xml`) and adds the product to the customer's inventory (:686). PACT needs the **crafting boundary**
core patch (03 §4.3): `RecipeItemMaker` split into *validate* (the crafter's book, :97/:140; the skill level, :238; HP/MP,
:543-593), *roll* and *apply*, with the materials' source and the product's destination as parameters, sharing every
retail rule and number.

A contractual attempt is one Asset operation, run by the crafter (who must know the recipe, as retail requires) from the
contract's page:

1. *validate* on the crafter, retail rules unchanged; the materials are the contract's `FUNDING` custody, and the attempt
   number is the next one not in `lc_pact_craft_outcome`.
2. *roll* with the **keyed RNG** (04 §7, 02 §3; N2): `success = H(secret, contract, attempt, "success") mod 100 < successRate`,
   `masterwork` likewise with retail's rate (:679). The roll is a pure function of the attempt, so an attempt that aborts
   and is retried rolls the same outcome: **no reroll** is possible by crashing, disconnecting or retrying; the
   distribution over attempts is retail's (uniform, the same rates). Retail's own crafting keeps its own RNG.
3. *apply* in the transaction: the materials leave the custody to the `CRAFT_INPUT` sink; on success the product enters
   the contract's `OUTPUT` custody from the `CRAFT_OUTPUT` source and is released to the client by service mail; the fee
   to the contractor; the crafter's HP/MP consumed in memory as retail does; the outcome row and its fact.

The crafter's MP is not an asset; if the transaction aborts after MP was spent, the MP is not restored, as retail does
not restore it on a failed craft. AT-PACT-03 compares outcome frequencies against retail rates and replays attempts after
faults to prove the roll unchanged.

### 4.4 Clause evaluation

| Clause kind | Evaluated from |
|---|---|
| `DELIVER_ITEMS` | the handing-over operation itself (exact item ids and quantities, object ids for unique items) |
| `PAY_ADENA`, `COLLATERAL`, `REPAY` | the custody's postings |
| `CRAFT_ATTEMPT` | `lc_pact_craft_outcome` |
| `DEADLINE` | the server clock (the controllable clock, N2) |
| `REGISTRATION` | native registration at the clause's moment: castle siege attackers and defenders (`mechanics/siege/Siege.java:81-83`, table `siege_clans`), fortress attackers (`mechanics/siege/FortSiege.java:724-775`, table `fortsiege_clans`), Territory War clans and mercenaries (`managers/TerritoryWarManager.java:106-107`, `:312-334`); captured as a fact when registration closes |
| `PARTICIPATION` | §4.6 |
| `LOOT_POLICY` | §4.5 |
| `FORFEIT` | §4.7 |
| `CANCEL_CONDITION` | one of the above, named |

### 4.5 Loot Charter

A party leader (or a command channel leader) proposes a charter in the Codex; members sign with the native dialog
(15 §10). At activation the members are frozen (`lc_pact_charter_member`): joining later never gives a share; leaving,
disconnecting or dying keeps the member in with the state the charter's rules give it (each rule says which states share).

Routing needs the **loot routing** core patch (03 §4.2), a hook at the one place a kill's drops are decided: before
`Attackable.doItemDrop` hands drops to the ground or to auto-loot (`entity/actor/Attackable.java:483-497`, `:1184-1191`)
and before a party's looter is chosen (`entity/groups/Party.java:207-241`, `:639-686`, `:724-775`). For a kill whose
**attribution** (below) falls to an active charter, the hook gives the drop list to PACT instead, and nothing reaches the
ground or an inventory:

- items the charter routes by retail rules (normal random, finders keepers) are released to the chosen member by service
  mail, chosen by the keyed RNG over the members whose state shares;
- reserved items go to the reserving member; items for a post-kill auction go to the charter's custody, auctioned at the
  charter NPC page with bids escrowed, the winning bid split per the charter; a deterministic split by count goes by the
  charter's order;
- each routed drop is one Asset operation: the items enter from the `LOOT_DROP` source into the `CHARTER` custody and leave
  by service mail or stay for the auction, in the same transaction.

Spoil is the spoiler's (`dist/game/data/scripts/handlers/skill/effects/Sweeper.java:58-79`) unless the charter says
otherwise; Adena follows the charter's split, or retail's even split (`Party.java:724-775`).

**Attribution.** Mobius gives a kill's drops to the top-damage party's leader or the top damage dealer
(`Attackable.java:483-497`), raid points to the last hitter's party (`entity/actor/instance/RaidBoss.java:73-98`), and
command-channel loot rights only gate pickup and vanish with auto-loot of raids (`managers/ItemManager.java:97-101`). A
charter applies to a kill when Mobius's own drop owner (the character `doItemDrop` would receive, before the hook) is a
frozen member; nothing else changes who wins a kill.

### 4.6 Mercenary contracts: participation

Siege and fortress registration is by clan only (`Siege.java:904-973`, `FortSiege.java:724-775`); per-character
registration exists only in Territory War (`TerritoryWarManager.java:312-334`, stored with clan ids in one column,
`:910-933`). So a mercenary party is a clan for sieges and fortresses, and a clan or a character for Territory War; the
clause names it.

Measured participation of a character: the minutes in which it was inside the battle's zone while the battle ran
(`entity/zone/type/SiegeZone.java:190,274` mark `isInSiege`; Territory War's siege side, `TerritoryWarManager.java:1226`)
**and** had combat activity (dealt or took damage from an enemy of the battle, or killed one) in that minute, sampled
per minute into a telemetry family of the journal. A clan party qualifies when at least the clause's number of its members
each reach the clause's minutes. Nothing about the quality of their fighting is judged (15 §1).

### 4.7 Treaty

A violation is a kill (`OnPlayerPvPKill`, `entity/actor/Player.java:5308-5313`, for any player kill; siege-zone kills,
`:5643-5654`, too) in which the killer's clan (or alliance, if the treaty names alliances) at the moment of the kill is
one signatory and the victim's another, inside the named territory (`data/xml/MapRegionData.java:412-416`,
`getAreaCastle`, the broad territory) and window. The fact carries both snapshots (actor-identity.md §2.4) and the area.
The violator's side's forfeit pays the victim's side once per window; later kills are facts and history, not more
payments. Nothing stops the kill.

### 4.8 The parchment

At `SIGNED` each party receives, by service mail, a **Notarized Contract** item: non-tradable, non-droppable,
non-sellable, non-depositable (`entity/item/ItemTemplate.java:152-160`), with an item handler that opens the contract's
authoritative page (as `dist/game/data/scripts/handlers/items/Bypass.java:41-52` opens a page), created from the
`PACT_PARCHMENT` source. It is a representation, never a currency: no operation reads it, and destroying it changes
nothing.

### 4.9 Notarius NPCs

Town, Royal, Castle Notarius and Caravan Guild Master (15 §8): Codex NPC pages for in-person signing, registration,
sealed instruments, handing over deliveries and purchase fills, the public registry board and the charter auction. They
are not judges, buffers, teleporters or shops, and refuse Black Contracts.

### 4.10 Visibility and publication

`PRIVATE` (only parties see it), `PARTIES` (parties are public, terms sealed), `PUBLIC` (terms public); `publishable`
lets history show it after completion. Registration of a non-private contract writes `lc_pact_registry` with a restrained
text key ("A commercial agreement between … has been registered", 15 §5). Sealed terms never reach a publication row:
PACT's publication types are registered in the publication registry (intel-service.md §4.7) without any terms field
unless the contract is `PUBLIC` and `publishable`.

### 4.11 Objective history

`lc_pact_history` counts completed, mutually cancelled, mechanical defaults (a party's own funding or repayment missed),
craft attempts and successes, from facts. No score, no rating, no derived virtue (15 §6).

## 5. Facts

All outbox facts, written in the transaction of their change: `Pact.Drafted`, `Pact.Proposed`, `Pact.Amended`,
`Pact.Signed` (party, version), `Pact.Funded`, `Pact.Activated`, `Pact.UnitSettled` (clause, unit, outcome),
`Pact.CraftRolled` (attempt, recipe, success, masterwork), `Pact.LootRouted` (charter, kill, items, receivers),
`Pact.Completed`, `Pact.Expired`, `Pact.Cancelled`, `Pact.Failed`, `Pact.Withdrawn`, `Pact.ReviewOpened`,
`Pact.ReviewResolved`, `Pact.Registered`. Consumed: siege, fortress and Territory War start, registration-close and end
facts; participation telemetry; player kill facts; raid kill facts; caravan facts (12 §8).

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PACT-INV01 | every asset movement of a contract is an Asset operation with its settlement key; no unit settles twice | asset-service.md INV; the unique key |
| PACT-INV02 | a contract's custodies hold exactly what its funded and unsettled units owe | the holding predicate (asset-service.md INV05) |
| PACT-INV03 | every party's signature is on the current version when the contract leaves the signing states | by the transition guard; a trigger |
| PACT-INV04 | a craft attempt has exactly one outcome row, never changed, and its roll is the keyed function of the attempt | the key and the trigger; AT-PACT-03 |
| PACT-INV05 | no sealed term reaches a publication row | the publication registry |
| PACT-INV06 | state changes follow §3.1 only | the transition trigger |
| PACT-INV07 | a routed drop reaches exactly the receivers the charter's rule chose, or the charter's custody | AT-PACT-05 |

## 7. Failure model

| Fault | End state |
|---|---|
| crash at any point of any operation | the Asset Service's: committed or not; a retry has the same operation id and settles nothing twice; a craft retry rolls the same outcome |
| a party offline at settlement | service mail waits for them |
| a receiver deleted | the release aborts with `RECEIVER_GONE` (asset-service.md §2.1); the contract goes to `ADMIN_REVIEW` |
| a clan disbanded with a contract | its payouts to the last leader; if none, `ADMIN_REVIEW` |
| the siege or Territory War cancelled | the mercenary units settle `NONE`, payment returned |
| loot routing hook unavailable (module frozen) | drops follow retail; charters in force are flagged and their kills recorded for admin review |
| facts lagging | evaluation waits; deadlines are evaluated on facts up to the deadline, with the lag shown |

## 8. Configuration

`config/Custom/LivingChronicle/Pact.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `RegistrationFeePermille` | 0–100 | 5 | 5 everywhere | AT-PACT-07 |
| `RegistrationFeeMinAdena` | 0–1000000 | 1000 | 1000 everywhere | AT-PACT-07 |
| `SealingFeeAdena` | 0–10000000 | 50000 | 50000 everywhere | AT-PACT-07 |
| `RoyalThresholdAdena` | 1000000–1000000000 | 10000000 | 10000000 everywhere | AT-PACT-01 |
| `FundingHours` | 1–168 | 24 | 24 everywhere | AT-PACT-01 |
| `ParticipationSampleSeconds` | 30–300 | 60 | 60 everywhere | AT-PACT-06 |

## 9. Interface

Codex → Contracts: draft per type, propose, review incoming, counter-propose, sign, fund, track, cancel by mutual
consent, objective history. Notarius pages: §4.9. Loot charter: proposed in the Codex, signed by members, shown during the
raid. Admin: `lc why pact <id>`, `lc pact review|resolve <id> <reason>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PACT-R01 | A contract MUST move only by §3.1, every transition a durable fact in the transaction of its movements, signatures valid only on the current version. | AT-PACT-01 |
| PACT-R02 | Every asset movement MUST be one Asset operation with its settlement key; no settlement MUST happen twice and nothing MUST be lost, across restarts at every state and faults between statements. | AT-PACT-02 |
| PACT-R03 | A contractual craft MUST follow retail rules and rates exactly, with materials from escrow and output to escrow, and its outcome MUST be a keyed function of the attempt, persisted and never rerolled. | AT-PACT-03 |
| PACT-R04 | Clauses MUST be machine-verifiable and evaluated only from facts and native state named by their kind. | AT-PACT-04 |
| PACT-R05 | A Loot Charter MUST route a kill's drops before any looter is chosen, to exactly the receivers its rules choose, with frozen members. | AT-PACT-05 |
| PACT-R06 | Mercenary settlement MUST use native registrations and measured participation only. | AT-PACT-06 |
| PACT-R07 | Fees MUST be sinks at the configured rates; a Treaty forfeit MUST pay only on violation facts, once per window. | AT-PACT-07 |
| PACT-R08 | Sealed terms MUST never be published; history MUST be objective counts only. | AT-PACT-08 |
| PACT-R09 | Nothing outside a signed clause MUST be enforced; betrayal outside contracts MUST stay possible. | AT-PACT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-PACT-00 | integration | the migration applies; keys, checks and triggers refuse an impossible transition, a second outcome for an attempt, a changed outcome, a settlement unit settled twice |
| AT-PACT-01 | integration | every transition of §3.1 and every refused one; an amendment clears signatures; a token of an old version refused; a Royal contract signed remotely refused |
| AT-PACT-02 | integration, fault, restart | a large automated suite of every type, with restarts at every state and faults between statements: per item id, the sum over holders unchanged except by the posted sources and sinks; no unit settled twice |
| AT-PACT-03 | integration, sim | contractual crafts against retail: the same validation refusals, rates within the statistical margin over 10⁵ keyed attempts, the same MP cost; an attempt retried after a fault at each step rolls the same outcome |
| AT-PACT-04 | unit | every clause kind's evaluator reads only its named sources (static scan); clauses outside the sealed hierarchy refused |
| AT-PACT-05 | integration | raids with each loot policy, members joining late, leaving, dying, disconnecting, auto-loot of raids on and off, command channels: every drop to the chosen receivers or the custody, none on the ground, none to a non-member; Mobius's own drop owner not a member: retail unchanged |
| AT-PACT-06 | integration | siege, fortress and Territory War contracts against native registrations and participation telemetry: qualifying parties paid, others returned, a cancelled battle returns all |
| AT-PACT-07 | integration | fees charged at the configured rates as sinks; a treaty with kills inside and outside the territory and window: one payout per window, forfeits returned at its end |
| AT-PACT-08 | integration | publication rows for every visibility; sealed terms absent; history counts equal the facts |
| AT-PACT-09 | integration | acts outside contracts (attacking a treaty party, keeping a lent weapon outside a loan, leaving a party) are possible and change nothing but facts and forfeits |

## 11. Upstream constraints

1. **Crafting reads and writes only the customer's inventory** (`RecipeManager.java:608-642`, `:686`), synchronously
   by default (`:120,166`; `AltGameCreation`, `dist/game/config/Player.ini:594`). → the crafting boundary core patch;
   staging materials into the inventory is refused as a design (the customer could move them mid-craft).
2. **Drops have three different winners** (§4.5) and command-channel rights gate only pickup
   (`ItemManager.java:97-101`). → the loot routing hook before `doItemDrop`'s hand-off, attribution by Mobius's own drop
   owner.
3. **Siege and fortress registration are clan-only; Territory War stores clans and characters in one column**
   (`TerritoryWarManager.java:910-933`). → §4.6.
4. **`OnPlayerPvPKill` is asynchronous** (`Player.java:5308-5313`). → the kill fact carries snapshots taken at the kill.
5. **Mail expiry deletes attachments of system mail** (`taskmanagers/MessageDeletionTaskManager.java:89-101`). → PACT
   uses only service mail, which never expires (asset-service.md §2.1).

## 12. Review record

- Revision 1: draft, not yet reviewed.
