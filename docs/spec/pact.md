# SPEC-PACT — PACT and the Notarius

- Status: draft (revision 6: §4.10's completion type split for chronicle.md, under review with it; revision 5 was signed off; record in §12)
- Blueprint: 15 (PACT and the Notarius); 03 §4.2-§4.4 (loot routing, crafting boundary, new events); 04 §3 (Asset
  Service), §5 (Codex), §7 (keyed randomness); 12 §6.2 (Crown Law Market Charters), §8 (Caravan Escort); 17
  (publication); consensus §4.
- Depends on: asset-service.md (custody, operations, settlement keys, service mail), fact-journal.md (facts, projectors,
  effects), actor-identity.md (snapshots), intel-service.md (publication registry), codex-ui.md (pages, tokens, `NpcRef`).
- DDL: `sql/migrations/V011__lc_pact.sql` (after the Asset Service's V010).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

PACT enforces agreements made only of **machine-verifiable clauses** that every party signed, and leaves everything else
risky (15 §1, §7). It owns contracts, their clauses, parties and states; the evaluation of clauses; settlement through the
Asset Service; the craft-for-contract and loot-charter operations; PACT's participation measurement; the Notarius NPC
pages; the public registry; and the objective history.

It does not own: assets and their movement (the Asset Service: every escrow, release, fee and refund is one of its
operations; PACT never touches an item), facts (the Fact Journal), sieges, fortresses and Territory War (Mobius: PACT reads
their registrations and state, never changes them), Black Contracts (the Black Ledger; the Notarius never processes them,
15 §8), Caravan Escort's issuing (the Caravan Guild, 12 §8), the Market Charter fee reduction (Crown Law, 12 §6.2: it
provides a fee factor per territory, 1 until Crown Law is specified).

PACT never judges effort, quality, loyalty or intent, never prevents an act, and **changes no retail outcome where no
charter or contract applies**.

## 2. Data

### 2.1 Tables

```text
lc_pact_contract(contract_id BIGINT PK auto, type {CRAFTING_PACT|CRAFT_ORDER|PURCHASE_ORDER|DELIVERY_ORDER|LOAN|LOOT_CHARTER|
                 MERCENARY|CARAVAN_ESCORT|TREATY}, state {…§3.1}, review_from {state} NULL, version INT, terms_version INT,
                 visibility {PRIVATE|PARTIES|PUBLIC}, publishable BOOLEAN, notarius_tier {TOWN|ROYAL|CASTLE|CARAVAN},
                 notarius_town SMALLINT, territory SMALLINT NULL, created_by INT, created_at TIMESTAMP(3),
                 deadline TIMESTAMP(3), terms JSON, terms_hash BINARY(32) = SHA-256(terms), value_adena BIGINT,
                 fee_adena BIGINT, fee_payer TINYINT, review_reason VARCHAR(128) NULL)
lc_pact_party(contract_id, party_no TINYINT, role {CLIENT|CONTRACTOR|LENDER|BORROWER|BUYER|SELLER|DELIVERER|EMPLOYER|
              MERCENARY|SIGNATORY|LEADER|MEMBER}, kind {CHAR|CLAN|ALLIANCE}, char_id INT NULL, clan_id INT NULL,
              ally_id INT NULL, signed_terms_version INT NULL, signed_at TIMESTAMP(3) NULL, signed_token BINARY(16) NULL,
              PRIMARY KEY(contract_id, party_no))
lc_pact_clause(contract_id, clause_no SMALLINT, kind {DELIVER_ITEMS|PAY_ADENA|CRAFT_ATTEMPT|DEADLINE|PARTICIPATION|
               REGISTRATION|LOOT_POLICY|COLLATERAL|REPAY|FORFEIT|CANCEL_CONDITION}, params JSON, PRIMARY KEY(contract_id, clause_no))
lc_pact_custody(contract_id, purpose {FUNDING|COLLATERAL|OUTPUT|CHARTER|FORFEIT}, party_no TINYINT NULL, custody_id INT → lc_custody)
lc_pact_unit(contract_id, clause_no, unit INT, parent_unit INT NULL, state {OPEN|PAID|RETURNED|FORFEITED|NONE|SPLIT}, owed JSON,
             skey VARCHAR(96) NULL
             → lc_settlement_key, op_id BINARY(16) NULL, at TIMESTAMP(3) NULL, PRIMARY KEY(contract_id, clause_no, unit))
lc_pact_craft_intent(contract_id, attempt_no INT, recipe_id INT, crafter_char INT, secret_version INT, success_rate INT,
                     masterwork_rate_ppm INT, created_at TIMESTAMP(3), PRIMARY KEY(contract_id, attempt_no))
lc_pact_craft_outcome(contract_id, attempt_no INT → intent, success BOOLEAN, masterwork BOOLEAN, product_item_id INT NULL,
                      product_count INT, op_id BINARY(16), rolled_at TIMESTAMP(3), PRIMARY KEY(contract_id, attempt_no))
lc_pact_roll_secret(secret_version INT PK, secret BINARY(32), created_at TIMESTAMP(3))
lc_pact_charter_member(contract_id, char_id INT, state {FROZEN_IN|LEFT|DISCONNECTED}, PRIMARY KEY(contract_id, char_id))
lc_pact_charter_active(char_id INT PK, contract_id)                       -- at most one active charter per character
lc_pact_kill(kill_key VARCHAR(64) PK, contract_id, op_id BINARY(16), at TIMESTAMP(3))  -- one routing per kill
lc_pact_participation(contract_id, battle_id BIGINT, char_id INT, clan_id INT, minute TIMESTAMP, qualifying BOOLEAN,
                      PRIMARY KEY(contract_id, battle_id, char_id, minute))
lc_pact_registry(contract_id PK, public_text_key VARCHAR(64), registered_at TIMESTAMP(3))
lc_pact_history(char_id INT PK, completed INT, cancelled INT, defaults INT, craft_attempts INT, craft_success INT, as_of_fact BIGINT)
```

- **Two versions.** `version` is the row's optimistic-concurrency counter: it grows by one at every update, and every
  Asset operation carries `expectVersion(PACT_CONTRACT, id, version)` (asset-service.md §4.1). `terms_version` grows by one
  only when the terms change (an amendment), and a signature is on a `terms_version` (codex-ui.md §4.3's terms hash is
  `terms_hash` at that `terms_version`). So signing, which changes the row, does not stale other signatures; an amendment,
  which changes the terms, clears them all.
- **Frozen terms.** `terms` is the canonical JSON of type, visibility, parties, clauses and fees; the database checks
  `terms_hash = SHA2(terms)`. `terms`, `terms_version`, `visibility`, `publishable`, the party rows (but their signature
  columns) and the clause rows change only in `DRAFT`, `PROPOSED` and `PARTIALLY_SIGNED`; triggers refuse anything else.
  Clause and party rows are written from `terms` in the same transaction, and a startup check compares them.
- **Units and their owed content.** A unit is one movement-to-be: what it will move when it settles (items and Adena, by
  receiver) is its `owed`, fixed when the unit is created and never changed. A contract's custody holds exactly the sum
  of the `owed` of its `OPEN` units (§6, INV02). A staged obligation is several units, created as its stages are funded
  (a loan: principal, collateral and repayment units, §4.2). A budget drawn in parts (a Purchase Order's maximum spend) is
  a unit that is **split**: a fill settles the budget unit as `SPLIT` and creates, in the same operation, a `PAID` unit for
  the fill and an `OPEN` unit for the remainder (`parent_unit` links them), so every unit stays immutable once settled
  and the custody still equals the open units.
- **Craft intent.** An attempt's inputs are committed before it rolls (§4.3); the outcome references them; both are
  immutable.
- `lc_pact_roll_secret` is generated once at installation, never changed (a new version is added only by an audited admin
  operation, and an intent records the version it used), and readable only by the game server's database user.
- `lc_pact_history` is a projection of PACT facts (fact-journal.md §4), never written by an operation.

### 2.2 Value, fee and tier

- `value_adena` = Adena to be escrowed + Σ item quantities × the item's retail reference price
  (`entity/item/ItemTemplate.java:574`, `getReferencePrice`), computed from `terms` when proposed and part of the terms.
- `fee_adena` = max(`RegistrationFeeMinAdena`, ⌊`value_adena` × `RegistrationFeePermille` / 1000⌋) × the territory's
  Market Charter factor (1 until Crown Law provides one), + `SealingFeeAdena` if `visibility = PARTIES` (sealed terms).
  It is part of the terms, so every signer saw it. The **fee payer** is the party the terms name (the drafter by
  default); it is charged as a `FEE_NOTARIUS` sink (asset-service.md §2.5) in the operation of the last signature, which
  fails if the payer cannot pay (the contract stays `PARTIALLY_SIGNED`).
- A contract with `value_adena` ≥ `RoyalThresholdAdena`, a clan or alliance party, or of type Treaty needs a Royal
  Notarius; Mercenary contracts the Castle Notarius of the castle concerned; Caravan Escorts the Caravan Guild (15 §8).
  The tier's Notarius binds the **last signature** to its presence (`NpcRef`, codex-ui.md §2).

### 2.3 Identities

| Identity | Form | Used for |
|---|---|---|
| request | the Codex token id (codex-ui.md §4.5) | an operation a player takes: signing, funding, a fill, a handing over, a craft attempt, a cancel signature |
| effect | the journal's application id and attempt (fact-journal.md §4.4) | a settlement caused by facts: a deadline, a battle's end, a treaty violation, a repayment's evaluation |
| settlement key | `PACT/<contract>/<clause>/<unit>` | the one movement that settles a unit; written only by it |
| funding key | `PACT/<contract>/fund/<party>/<clause>` | the one movement that funds a party's obligation for a clause |
| signing key | `PACT/<contract>/sign/<terms version>` | the last signature's operation: the fee and the parchments |
| bid key | `PACT/<contract>/bid/<lot>/<bidder>/<n>` | the escrow of a charter auction bid (its *n*-th raise) |
| split key | `PACT/<contract>/<clause>/<unit>/split` | a unit settled as `SPLIT` (§2.1) |
| kill | the journal epoch (fact-journal.md §2, `lc_journal_epoch`) and a kill sequence the routing hook assigns in memory, `PACT/kill/<epoch>/<seq>` | the one routing of a kill's drops; its `OpId` is derived from it |
| roll | (contract, attempt) | the craft roll (§4.3), independent of any operation id |

An operation's `OpId` is the request's, the effect's or the kill's; what it does is made unique by the keys it writes:
every movement writes **the set of keys its effect requires, each once and atomically** (a repayment writes the
repayment's and the collateral's settlement keys; a fill writes the split key and the fill's settlement key; a routing
writes its kill key), and no key of this table is ever written by two operations. A refunded
operation (asset-service.md §3.2) leaves its key unwritten, so the next request or effect attempt, with a new `OpId`, can
write it. A kill key is unique across restarts because the epoch is (one per server start).

## 3. State machines

### 3.1 Contract

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | `draft` | the type's form valid; the drafter is a party | `DRAFT` (inserted only in this state, version 0, terms version 1) | row, clauses, parties |
| `DRAFT` | `amend` | the drafter | `DRAFT` | terms version + 1 |
| `DRAFT` | `propose` | every party named | `PROPOSED` | the parties see it; registration in the registry if not private |
| `PROPOSED`, `PARTIALLY_SIGNED` | `amend` (counter-proposal) | any party | `PROPOSED` | terms version + 1; **every signature cleared** |
| `PROPOSED`, `PARTIALLY_SIGNED` | `sign` | the token's terms version is current; the signer's authority (§4.1); the last signature at the tier's Notarius | `PARTIALLY_SIGNED`, or `SIGNED` at the last | the signature; at the last, the fee charged and the parchments issued, one operation |
| `PROPOSED`, `PARTIALLY_SIGNED` | `withdraw` | any party | `WITHDRAWN` | nothing was escrowed |
| `SIGNED` | `fund` | a funding party, its funding key unwritten | `SIGNED`, or `FUNDED` when every funding key is written | Asset `fund`; units' `owed` set |
| `SIGNED` | funding deadline (`FundingHours`) | a funding key unwritten | `EXPIRED` | every funded part returned by service mail |
| `FUNDED` | activation (immediate, or the clause's start) | — | `ACTIVE` | the type's activation effect |
| `ACTIVE` | a unit settles | §4.2 | `ACTIVE` | the unit's settlement |
| `ACTIVE` | the last unit settles | — | `COMPLETED` | custodies closed |
| `ACTIVE` | deadline | units `OPEN` | `EXPIRED` | each `OPEN` unit's expiry rule (§4.2) |
| `ACTIVE` | a cancel condition holds | — | `FAILED_BY_CONDITION` | the condition's rule |
| `SIGNED`, `FUNDED`, `ACTIVE` | `cancel` signed by every party on the current terms version | — | `MUTUALLY_CANCELLED` | every `OPEN` unit's `owed` returned to its funder |
| any but terminal | `review` (admin, audit fact) | corruption or a bug | `ADMIN_REVIEW`, `review_from` = the state left | custodies frozen (asset-service.md §4.5) |
| `ADMIN_REVIEW` | `resolve` (admin, audit fact) | invariants hold | `review_from`, or a terminal state | `review_from` cleared |

Terminal: `COMPLETED`, `EXPIRED`, `MUTUALLY_CANCELLED`, `FAILED_BY_CONDITION`, `WITHDRAWN`. Every transition is a durable
fact in the transaction of its movements (15 §3). A trigger enforces this table, including insertion only as `DRAFT` and
resolution only to `review_from` or a terminal state.

### 3.2 Unit

`OPEN` → `PAID` | `RETURNED` | `FORFEITED` | `NONE` | `SPLIT`, once: the transition writes the unit's settlement key (or,
for `SPLIT`, its split key) in the same transaction (a trigger refuses leaving `OPEN` without one and any change after).
A unit is one movement-to-be (§2.1): an attempt (Crafting Pact, Craft Order), a fill and the remaining budget (Purchase
Order), the delivery (Delivery Order), the principal, the collateral and the repayment (Loan), a routed lot (Loot
Charter), a mercenary party (Mercenary), a (violating signatory, window) pair (Treaty).

## 4. Operations

### 4.1 Common rules

- Every player action is a Codex action (codex-ui.md): a one-use token bound to the contract, its terms version and terms
  hash, and for the last signature of a tiered contract to that Notarius NPC.
- Every movement is one Asset Service operation (§2.3 identities) with the contract's `expectVersion`, the key it writes,
  the state and unit changes and their facts. PACT has no other way to move anything.
- What is owed to a character goes by service mail (asset-service.md §2.1); nothing waits in a custody for a claim.
- **Authority of collective parties**, as retail grants it: a clan signs and receives through its leader; it funds from the
  clan warehouse through its leader, or also through members with `ClanAccess.ACCESS_WAREHOUSE` only when
  `AltMembersCanWithdrawFromClanWH` is enabled (`network/clientpackets/SendWareHouseWithDrawList.java:127-140`;
  `dist/game/config/Player.ini:665`, default false). An alliance acts through the leader of its leader clan, funds from
  that clan's warehouse under the same rule, and receives through that leader. Authority is checked at the operation, from
  current state.
- Settlements caused by facts run as projector effects (fact-journal.md §4.4): applied once by their application id, and
  replay writes nothing.

### 4.2 Contract types

| Type | Funding (funding keys) | Units and settlement | Expiry, failure |
|---|---|---|---|
| Crafting Pact | client: materials for *n* attempts + fee per attempt | per attempt (§4.3): the attempt's fee to the contractor whatever the outcome; product, if any, to the client; materials consumed per retail | unattempted attempts' materials and fees returned |
| Craft Order | client: materials and fee per batch, a unit per attempt of the batch | per attempt under §4.3, as for a Crafting Pact; a batch is the funding group of its attempts, so a partial batch is the attempts made so far | unattempted attempts' units returned |
| Purchase Order | buyer: maximum spend (the budget unit) | per fill: a seller hands items to a Notarius (in person) or, if the order allows, through the Codex; in one operation the items go to the buyer, the budget unit is split (§2.1) into the fill's price × quantity `PAID` to the seller and the remainder `OPEN` | the remainder unit `RETURNED` |
| Delivery Order | client: payment | the delivery: the deliverer hands the named items at the named Notarius before the deadline; items to the receiver, payment to the deliverer | payment returned |
| Loan | lender: principal (unit *P*); borrower: collateral (unit *C*) | activation settles *P* (`PAID` to the borrower); repayment is a request that creates unit *R* (the repayment, funded from the borrower) and in the same operation settles *R* (`PAID` to the lender) and *C* (`RETURNED` to the borrower) | at the deadline, unrepaid: *C* `FORFEITED` to the lender, never more |
| Loot Charter | bidders' bids, during an auction | per routed lot (§4.5) | an unfinished auction's lot to the charter's fallback receiver |
| Mercenary | employer: payment per mercenary party | per mercenary party, on the battle's end (§4.6) | payment returned for parties that did not qualify; all of it if the battle did not happen |
| Caravan Escort | the Caravan Guild: payment | delivery + participation, on the caravan's arrival (12 §8) | per the issuing terms |
| Treaty | each signatory: its forfeit | per (violating signatory, window): at the first violation (§4.7) in the window, that signatory's forfeit goes to the victim's side; later violations in the window are facts only | at the end of the window, every **unspent** forfeit returned to its signatory |

### 4.3 Craft for contract

Retail crafting takes materials only from the customer's inventory (`managers/RecipeManager.java:608-631`), destroys them
there (:642), rolls on the packet thread (`Rnd.get(100) < successRate`, :416, rates from `dist/game/data/Recipes.xml`),
rolls masterwork (:679) and adds the product to the customer's inventory (:686). PACT needs the **crafting boundary**
core patch (03 §4.3): `RecipeItemMaker` split into *validate* (the crafter's book, :97/:140; skill level, :238; HP/MP,
:543-593), *roll* and *apply*, with the materials' source and the product's destination as parameters, sharing every
retail rule and number.

A contractual attempt, run by the crafter (who must know the recipe, as retail requires) from the contract's page:

1. **Intent**, its own committed transaction: *validate* on the crafter; the next attempt number; the recipe, the current
   secret version, the recipe's success rate and the masterwork rate in parts per million, as configured now: written to
   `lc_pact_craft_intent`. An intent is immutable and is the attempt.
2. **Roll**, a pure function of the intent: `u = HMAC-SHA256(secret[secret_version], contract ‖ attempt ‖ "success")`
   read as a 64-bit unsigned integer, success iff `u mod 100 < success_rate`; masterwork likewise with its own label and
   `mod 1 000 000 < masterwork_rate_ppm`. The secret never leaves the server; no player sees `u`; the roll does not depend
   on time, order, retries or configuration changes after the intent. (N2's `KeyedRandom` is a seeded stream for
   simulation; this roll is a keyed hash, specified here.)
3. **Apply**, one Asset `craft` operation with the request's `OpId`: materials from the `FUNDING` custody to the
   `CRAFT_INPUT` sink; on success the product from the `CRAFT_OUTPUT` source **straight to the client by service mail**
   (it never enters a custody, so its eligibility for custody does not matter); the attempt's fee to the contractor; the outcome row; the unit settled; the crafter's
   HP/MP consumed in memory as retail does (not restored on abort, as retail does not restore it on a failed craft).

If *apply* aborts, the intent stands; the next request applies the same intent, which rolls the same. An attempt is never
re-intended. Retail crafting outside contracts keeps its own RNG.

### 4.4 Clause evaluation

| Clause kind | Evaluated from |
|---|---|
| `DELIVER_ITEMS` | the handing-over operation itself (item ids and quantities exactly, object ids for unique items) |
| `PAY_ADENA`, `COLLATERAL`, `REPAY` | the unit's `owed` and the operation's postings |
| `CRAFT_ATTEMPT` | `lc_pact_craft_outcome` |
| `DEADLINE` | the server clock (the controllable clock, N2), compared with fact times |
| `REGISTRATION` | native registration, read when registration closes: castle sieges (`mechanics/siege/Siege.java:81-83`, table `siege_clans`), fortresses (`mechanics/siege/FortSiege.java:724-775`, table `fortsiege_clans`), Territory War clans and mercenaries (`managers/TerritoryWarManager.java:106-107`, `:312-334`); recorded as a fact |
| `PARTICIPATION` | `lc_pact_participation` (§4.6) |
| `LOOT_POLICY` | §4.5 |
| `FORFEIT` | §4.7 |
| `CANCEL_CONDITION` | one of the above, named |

Each kind's evaluator is a function from its named inputs to a unit result, tested against tables of inputs with expected
results (AT-PACT-04).

### 4.5 Loot Charter

**Scope.** A charter is proposed by a party leader for that party, or by a command channel leader for that channel, and
signed by every member. At activation PACT binds the charter to **that group object** (the `Party` or `CommandChannel`
instance, held by reference, never by its leader's id: retail lets a leader disband and form a new party,
`entity/groups/Party.java:109,551`, and a new party is not the signed one) and freezes its members (`lc_pact_charter_member`); a character is frozen in at most one active charter
(`lc_pact_charter_active`). A charter lists **which items it routes and how**: `RESERVED` (an item id to a named member),
`AUCTION` (an item id to a post-kill auction among members), `ROTATION` (an item id to members in the charter's order,
cycling). Only item ids that may be in a custody (asset-service.md §4.4) can be listed as `AUCTION`; the drafting form
refuses others. Everything else a kill drops, and all Adena and spoil, follows retail untouched.

**Applicability.** A kill is routed by a charter only if, at the moment Mobius decides the kill's drop owner
(`entity/actor/Attackable.java:483-497`: the top-damage party's leader, or the top damage dealer), that owner is a frozen
member of an active charter, **is in the charter's group object at that moment** (its current party, or command channel, is that very object), and, if a
command channel holds the raid's loot rights (`Attackable.java:242-264`; `managers/ItemManager.java:97-101`), the charter's
group is that command channel. Otherwise retail runs unchanged, including the competing channel's rights.

**Routing**, at the loot routing core patch (03 §4.2): a hook in `Attackable.doItemDrop` after the drop list is computed
and before any item reaches the ground or an inventory (`Attackable.java:1176-1191`). For an applicable kill, the hook
splits the list: the charter's routed items go to PACT; the rest continue to retail. PACT routes **the whole kill in one
Asset `route` operation** with the kill key (§2.3): every routed item enters from the `LOOT_DROP` source; `RESERVED` and
`ROTATION` items go to their receiver by service mail; `AUCTION` items into the charter's custody. If the operation cannot
commit (the database is down, the module is frozen), the hook returns the routed items to retail, which drops them as it
would have, and a `Pact.RoutingFailed` fact names the kill. So a kill's routed items are either all in PACT's operation or
all in retail's hands; none is lost between them while the server runs. **Stated limit**: if the JVM dies between the
kill and the routing commit, the routed items are lost with it, as retail's own drops of that kill are (ground items
survive a restart only with `SaveDroppedItem`, off by default, `config/GeneralConfig.java:252`); the kill's fact was not
written either, so nothing claims them.

When the group object dissolves (the party disbands, the channel closes, a restart: parties do not survive one), the
charter ends: open auctions resolve by the fallback rule, and the charter completes.

The auction runs at the charter NPC page with bids escrowed (funding keys per bid); the winning bid is split among the
members as the charter says; losing bids are returned.

### 4.6 Mercenary contracts: participation

**Parties.** Siege and fortress registration is by clan only (`Siege.java:904-973`, `FortSiege.java:724-775`); per-character
registration exists only in Territory War (`TerritoryWarManager.java:312-334`). A mercenary party is a clan for sieges and
fortresses, and a clan or a character for Territory War.

**Measurement**, PACT's own, not journal telemetry (whose aggregates keep no actors): while a battle named by an `ACTIVE`
Mercenary contract runs (from its start fact to its end fact; the battle id is the start fact's id), PACT samples every
60 seconds each online character of each mercenary party:

- *present*: inside the battle's zone: the castle's siege zone (`entity/zone/type/SiegeZone.java`) for a castle siege, the
  fortress's siege zone for a fortress siege, any siege zone of the territory's castle or forts during Territory War;
- *active*: within that minute, **damage was actually taken** by one of two players on different sides of the same
  running battle from the other (an attempted hit that dealt nothing does not count), or one killed the other. Mobius's
  damage events do not say this: `ON_CREATURE_DAMAGE_DEALT` is dispatched after `reduceCurrentHp` whatever it refused
  (`entity/actor/Creature.java:5358`, `:7201`; skills likewise,
  `dist/game/data/scripts/handlers/skill/effects/StaticDamage.java:54`), and the received-damage event is asynchronous
  (:7206). So PACT uses the **damage seam**, a core patch in `PlayerStatus.reduceHp`
  (`entity/actor/status/PlayerStatus.java:71`) after its refusals (offline no-damage, :80-83; invulnerability, :85-88), at
  every point where a player's CP or HP is actually lowered on an attacker's account: its own CP and HP (:224-230,
  :292), and **damage transferred** to a caster's CP from a protected player (:198-208) or a protected summon
  (`entity/actor/status/SummonStatus.java:76-115`), which reaches the caster through `reduceCp` directly and may leave no
  residual HP damage. At each, when the lowered amount is positive and the original attacker's acting player is another
  player: a synchronous primitive snapshot (attacker, target, amount, time) handed to PACT's
  in-memory minute counters, which read both players' sides (the siege's registration, attacker, defender or owner, or
  Territory War's siege side, `TerritoryWarManager.java:1226`), clans and the battle at that moment and mark the minute
  active for both;
- *member*: in the party (the clan) at the sample.

A minute qualifies when all three hold; it is a row of `lc_pact_participation`. A clan party qualifies when at least the
clause's number of members each have at least the clause's qualifying minutes; a character party when it has them.

### 4.7 Treaty

Parties are clans or alliances (`kind`, with `clan_id` or `ally_id`). A violation is a kill (`OnPlayerPvPKill`,
`entity/actor/Player.java:5308-5313`; siege-zone kills, `:5643-5654`) in which the killer's clan (or alliance) at the
moment of the kill is one signatory and the victim's another, inside the named territory
(`data/xml/MapRegionData.java:412-416`, `getAreaCastle`) and window. The kill fact carries both snapshots taken at the
kill (actor-identity.md §2.4) and the territory. The first violation of a signatory in a window settles its unit: its
forfeit to the victim's side's receiver. Nothing stops the kill.

### 4.8 The parchment

At `SIGNED` each party receives, by service mail, a **Notarized Contract** item: non-tradable, non-droppable,
non-sellable, non-depositable (`entity/item/ItemTemplate.java:152-160`), with an item handler that opens the contract's
page (as `dist/game/data/scripts/handlers/items/Bypass.java:41-52` does), issued from the `PACT_PARCHMENT` source. No
operation reads it; destroying it changes nothing.

### 4.9 Notarius NPCs

Town, Royal, Castle Notarius and Caravan Guild Master (15 §8): Codex NPC pages for the last signature of tiered contracts,
registration, handing over deliveries and purchase fills, the public registry board and charter auctions. They are not
judges, buffers, teleporters or shops, and refuse Black Contracts.

### 4.10 Visibility and publication

`PRIVATE` (only parties see it; never registered), `PARTIES` (parties public, terms sealed), `PUBLIC` (terms public);
`publishable` lets history show it after completion. Both are part of the terms, frozen at signing. PACT registers three
publication types (intel-service.md §4.7):

| Type | Fields (rule) | When |
|---|---|---|
| `pact_registered` | contract type (`TEXT_FROM_FACT`), public names of parties (`PUBLIC_ENTITY`), day (`DAY`) | a `PARTIES` or `PUBLIC` contract signed |
| `pact_completed` | contract type, public names of parties, outcome, day | a terminal state of a `PARTIES` or `PUBLIC` contract; never for `PRIVATE` |
| `pact_completed_terms` | as `pact_completed`, plus the clause kinds (`TEXT_FROM_FACT`) and the item and Adena totals (`COUNT`) | a terminal state of a `PUBLIC` and `publishable` contract, instead of `pact_completed` |

No field holds free text; clauses are typed, so there is no text in which a sealed term could hide. The rows are written
by PACT's projector from the contract's frozen visibility.

### 4.11 Objective history

`lc_pact_history` counts completed, mutually cancelled, mechanical defaults (a party's own funding or repayment missed),
craft attempts and successes, from facts. No score, no rating (15 §6).

## 5. Facts

All outbox facts, written in the transaction of their change: `Pact.Drafted`, `Pact.Proposed`, `Pact.Amended`,
`Pact.Signed` (party, terms version), `Pact.Funded` (party, clause), `Pact.Activated`, `Pact.UnitSettled` (clause, unit,
outcome), `Pact.CraftIntended`, `Pact.CraftRolled` (attempt, success, masterwork), `Pact.LootRouted` (charter, kill key,
items, receivers), `Pact.RoutingFailed` (charter, kill key), `Pact.RegistrationRecorded`, `Pact.Violation` (treaty,
killer and victim snapshots, territory), `Pact.Completed`, `Pact.Expired`, `Pact.Cancelled`, `Pact.Failed`,
`Pact.Withdrawn`, `Pact.ReviewOpened`, `Pact.ReviewResolved`. Consumed: battle start, registration-close and end facts;
player kill facts; caravan facts (12 §8).

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| PACT-INV01 | every movement of a contract is an Asset operation writing atomically the keys of §2.3 its effect requires; no key is written by two operations | the keys; AT-PACT-02 |
| PACT-INV02 | each contract custody holds exactly the sum of the `owed` of its `OPEN` units | PACT's holding predicate, checked in every operation (asset-service.md INV05) and by AT-PACT-02's reference model |
| PACT-INV03 | a contract leaves the signing states only with every party's signature on the current terms version; terms never change after | triggers; AT-PACT-00 |
| PACT-INV04 | an attempt has one intent and at most one outcome, both immutable; the outcome is the keyed function of the intent | keys and triggers; AT-PACT-03 |
| PACT-INV05 | no sealed term reaches a publication row | the registered types; AT-PACT-08 |
| PACT-INV06 | state changes follow §3.1 only | the trigger; AT-PACT-00 |
| PACT-INV07 | a kill is routed at most once, entirely or not at all; where no charter applies, retail's outcome is unchanged | the kill key; AT-PACT-05 |

## 7. Failure model

| Fault | End state |
|---|---|
| crash during any operation | the Asset Service's: committed or not; a later request or effect attempt writes the key once |
| crash between a craft's intent and its apply | the intent stands; the next request applies it with the same roll |
| database fault or frozen module during a kill's routing | routed entirely, or returned to retail with `Pact.RoutingFailed` |
| JVM crash between a kill and its routing commit | the routed items are lost, as retail's own drops of that kill are (§4.5, a stated limit) |
| a party offline at settlement | service mail waits for them |
| a receiver deleted | the release aborts with `RECEIVER_GONE` (asset-service.md §2.1); the contract goes to `ADMIN_REVIEW` |
| a clan disbanded or an alliance dissolved | payouts to the last leader if they exist; otherwise `ADMIN_REVIEW` |
| a battle cancelled | mercenary units settle `NONE`, payments returned |
| facts lagging | evaluation waits; deadlines compare fact times, never arrival times |

## 8. Configuration

`config/Custom/LivingChronicle/Pact.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `RegistrationFeePermille` | 0–100 | 5 | 5 everywhere | AT-PACT-07 |
| `RegistrationFeeMinAdena` | 0–1000000 | 1000 | 1000 everywhere | AT-PACT-07 |
| `SealingFeeAdena` | 0–10000000 | 50000 | 50000 everywhere | AT-PACT-07 |
| `RoyalThresholdAdena` | 1000000–1000000000 | 10000000 | 10000000 everywhere | AT-PACT-01 |
| `FundingHours` | 1–168 | 24 | 24 everywhere | AT-PACT-01 |

## 9. Interface

Codex → Contracts: draft per type, propose, review incoming, counter-propose, sign, fund, track, cancel by mutual
consent, objective history. Notarius pages: §4.9. Loot charter: proposed in the Codex, signed by members, shown during the
raid. Admin: `lc why pact <id>`, `lc pact review|resolve <id> <reason>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| PACT-R01 | A contract MUST move only by §3.1; signatures MUST bind a terms version and every amendment MUST clear them; terms MUST never change after signing. | AT-PACT-00, AT-PACT-01 |
| PACT-R02 | Every movement MUST be one Asset operation writing atomically the keys of §2.3 its effect requires, none written by two operations, and each custody MUST hold exactly what its open units owe, across restarts at every state and faults between statements. | AT-PACT-02 |
| PACT-R03 | A contractual craft MUST follow retail rules and rates exactly, with materials from escrow, and its outcome MUST be the keyed function of an intent committed before the roll, never rerolled. | AT-PACT-03 |
| PACT-R04 | Clauses MUST be machine-verifiable and each kind's evaluator MUST compute its unit result from its named inputs only. | AT-PACT-04 |
| PACT-R05 | A Loot Charter MUST apply only to kills its group owns at the drop owner's decision and MUST route that kill's listed items entirely or not at all; everything else MUST stay retail. | AT-PACT-05 |
| PACT-R06 | Mercenary settlement MUST use native registrations and PACT's per-character participation measurement only. | AT-PACT-06 |
| PACT-R07 | Fees MUST be the specified amounts charged to the named payer as sinks; a Treaty forfeit MUST pay once per violating signatory and window, unspent forfeits returned. | AT-PACT-07 |
| PACT-R08 | Sealed terms MUST never be published; publication rows MUST be of PACT's registered types; history MUST be objective counts only. | AT-PACT-08 |
| PACT-R09 | Nothing outside a signed clause MUST be enforced, and collective parties MUST act only with retail's authority. | AT-PACT-09 |

| Test | Kind | What it does |
|---|---|---|
| AT-PACT-00 | integration | the migration applies; triggers refuse every transition outside §3.1 and allow every one inside it (all 132 pairs of distinct states tried, each on a fresh contract), insertion in another state than `DRAFT`, resolution to other than `review_from` or terminal, leaving signing without every signature on the current terms version, any change of terms, clauses or parties after signing, a terms hash not of the terms, a unit leaving `OPEN` without its key or changing after, a second or changed intent or outcome, a second active charter of a character (with valid member rows), a kill key twice |
| AT-PACT-01 | integration | every transition of §3.1 through the operations; two parties signing in either order without re-signing; after an amendment no earlier signature counts (each is on an old terms version, and the operation clears them from the page); a token of an old terms version refused; a Royal contract's last signature away from the Notarius refused |
| AT-PACT-02 | integration, fault, restart | an automated suite of every type against an **independent reference model** that computes, from the scenario alone, each character's expected receipts and each custody's expected content: after every step, restarts at every state and faults between statements, actual equals expected per character, per item and object id, and no key is written twice |
| AT-PACT-03 | integration, sim | contractual crafts against retail: the same validation refusals and MP cost; over 10⁶ intents per test recipe, the success frequency within the 99.9 % binomial interval of the recipe's rate; an apply aborted at each step and retried, and a restart between intent and apply, give the same outcome; a configuration change after the intent does not change it |
| AT-PACT-04 | unit | each clause kind's evaluator against a table of inputs and expected results, including boundaries (a deadline at the same millisecond, a member reaching the minutes exactly); the static scan finds no evaluator reading outside its named inputs |
| AT-PACT-05 | integration | raids and kills with charters and without; the charter's leader disbanding and forming a new party (the new party's kills retail); a charter's group owning the kill routes exactly its listed items to the expected receivers (computed independently from the charter and the kill), the rest and spoil and Adena exactly as a retail run of the same kill; the owner outside the group, in another group, with a competing command channel holding loot rights, or with no charter: retail exactly; a database fault during routing returns the items to retail; a kill routed twice is refused |
| AT-PACT-06 | integration | scripted battles with mercenary clans and characters: presence, activity and membership toggled per minute, a hit just before a minute boundary and a side change just after it, hits on an invulnerable or offline-protected target (no activity), damage transferred to a caster's CP leaving no residual HP damage (activity), expected qualifying minutes computed independently; units paid or returned accordingly; a cancelled battle returns all |
| AT-PACT-07 | integration | fees: values and fees computed independently from the terms, charged to the named payer as sinks, the payer unable to pay keeps the contract partially signed; a treaty with concurrent violations inside and outside the territory and window: one payout per violating signatory and window, unspent forfeits returned, spent ones not |
| AT-PACT-08 | integration | publication rows of both types for every visibility; a sealed contract's rows carry no clause or amount; an attempt to write another type or field refused; history counts equal the facts |
| AT-PACT-09 | integration | acts outside contracts (attacking a treaty party, keeping a lent weapon outside a loan, leaving a party) stay possible; a clan member without the leader's authority cannot sign or fund, and with `ACCESS_WAREHOUSE` can fund only when `AltMembersCanWithdrawFromClanWH` is on |

## 11. Upstream constraints

1. **Crafting reads and writes only the customer's inventory** (`RecipeManager.java:608-642`, `:686`), synchronously by
   default (`:120,166`; `AltGameCreation`, `dist/game/config/Player.ini:594`). → the crafting boundary core patch; staging
   materials into the inventory is refused as a design (the customer could move them mid-craft).
2. **Drops, raid points and kill events have three different winners** (§4.5), and command-channel loot rights exist only
   with raid auto-loot off (`ItemManager.java:97-101`). → applicability decided at Mobius's own drop-owner decision, with
   the loot-rights channel respected; routing at `doItemDrop` before the hand-off.
3. **Siege and fortress registration are clan-only; Territory War stores clans and characters in one column**
   (`TerritoryWarManager.java:910-933`). → §4.6.
4. **`OnPlayerPvPKill` is asynchronous** (`Player.java:5308-5313`). → violation facts carry snapshots taken at the kill.
5. **Clan warehouse withdrawal by members is off by default** (`Player.ini:665`). → §4.1's authority.
6. **Mail expiry deletes attachments of system mail** (`taskmanagers/MessageDeletionTaskManager.java:89-101`). → PACT
   uses only service mail, which never expires (asset-service.md §2.1).

## 12. Review record

- Revision 6 (after the round-4 sign-off): `pact_completed` split into `pact_completed` and `pact_completed_terms`, so
  the publication registry itself refuses clause kinds and totals on any row but a `PUBLIC` and `publishable`
  contract's (found by Chronicle's first review); under review with chronicle.md.

- Round 4 (Codex), **SIGN-OFF WITH FIXES**: one fix, made in revision 5: the damage seam also covers damage transferred
  to a caster's CP (`PlayerStatus.java:198-208`, `SummonStatus.java:76-115`), which bypasses the CP and HP points, with a
  zero-residual case in AT-PACT-06. The Asset Service amendment (its revision 8) was reviewed in the same rounds. Remaining
  are named implementation obligations: the crafting, loot and damage seams, the operations, startup checks and the
  acceptance suites; the precommit crash loss of routed drops is a stated limit.
- Round 3 (Codex), NO SIGN-OFF: seventeen fixed or accepted; open were a one-key-per-movement rule that repayments and
  splits contradict, §3.2 not listing split and staged units, the Asset source definitions still saying "into custody",
  the damage event firing for refused damage, the failure table contradicting the crash limit, and review tested from one
  state only. Revision 4: keys as an atomic set per movement; §3.2 restated; Asset §2.5 aligned; the damage seam at the
  points `PlayerStatus.reduceHp` actually lowers CP or HP; the failure table split; review entered from and returned to
  every non-terminal state in the test.
- Round 2 (Codex), NO SIGN-OFF: thirteen of nineteen fixed, five partly, and five new: precommit loss of routed drops
  on a JVM crash; groups identified by a leader's id; asynchronous damage events; `pact_completed` for private contracts;
  immutable `owed` unable to model staged obligations and budgets; movements without a key (signing, loan principal,
  bids, kills); a kill key not durable across restarts and object reuse; custody-ineligible sourced items; and a schema
  test short of its promise. Revision 3: the precommit crash stated as a limit, equal to retail's own; charters bound to
  the group object and ended with it; activity captured synchronously at the hit; completion published only for
  non-private contracts; units created per stage and budgets split into immutable children; signing, bid, split and kill
  keys, kills keyed by the journal epoch and an in-memory sequence; crafted products and reserved or rotated drops go
  straight to mail, auctions only of custody-eligible items; the full 132-pair transition matrix in the test.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 19 findings: signing staled earlier signatures (one counter
  for row and terms); signed terms mutable through other states, the JSON or the clause and party rows; the review
  machine; operation identities conflicting with Codex tokens and journal effects, and funding sharing settlement keys;
  loot routing without a durable hand-off; charters overriding command-channel rights and applying to unrelated groups;
  policies called retail that were not; participation not measurable from journal telemetry and undefined; clan funding
  beyond retail's authority; the craft roll's inputs not persisted; alliance parties undefined; treaty units and expiry
  in conflict; the Asset amendment contradicting its own requirements; fees undefined; publication types undefined; and
  tests without independent oracles. Revision 2: a terms version apart from the row version; terms, clauses and parties
  frozen by triggers and the hash checked; `review_from`; an identity table; one routing operation per kill, keyed, with
  a fallback to retail; charters bound to their group and the loot-rights channel, routing only listed items; PACT's own
  per-minute participation table; retail's warehouse authority; committed craft intents and an HMAC roll; alliance
  parties; treaty units per signatory and window with unspent forfeits returned; ASSET-R14, R20, AT-ASSET-14 and §5
  amended; fees from reference prices with a named payer; two publication types without free text; reference-model and
  table-driven oracles.
