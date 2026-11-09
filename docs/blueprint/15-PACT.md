# 15 — PACT and the Notarius

> **Freedom is the default. Contract is optional certainty.**

Lineage II runs transactions on trust: crafting with expensive materials, bulk purchases, mercenaries, raid loot, loans,
deliveries. PACT enforces mechanically explicit agreements that every party signs, and leaves everything else risky.
Betrayal must stay possible.

## 1. Machine-verifiable clauses only

May enforce: item ids/quantities, Adena amounts, transfers, crafting attempts, result ownership, deadlines, siege/fortress/
Territory War registration, measurable participation (presence in zone with combat activity), party membership, boss-kill
participation, deliveries, cancellation conditions, payment release.
Must not judge: effort, healing quality, loyalty, rudeness, moral betrayal, subjective service quality.

Clauses are a sealed type hierarchy; contract types compose clauses:
```java
sealed interface Clause permits DeliverItems, PayAdena, CraftAttempt, Deadline, Participation, Registration, LootPolicy, ... {}
interface ClauseEvaluator<C extends Clause> { ClauseResult evaluate(C clause, ContractFacts facts); }
interface Settlement { void settle(AssetTx tx, Contract c, ClauseResults r); }   // runs only inside the Asset Service
```

## 2. Contract types

| Type | Example | Notes |
|---|---|---|
| **Crafting Pact** | client supplies Doom Plate materials; fee 400k; 1 attempt; result owner client; 24 h | materials escrowed; craft runs through the core crafting boundary using escrowed inputs; output routed to client escrow; outcome persisted; failure consumes materials per retail rules; PACT guarantees behaviour, not success |
| **Craft Order** | "need 20 Crafted Leather, materials provided, 120k, partial batches" | partial fulfilment settles per batch |
| **Purchase Order** | "BUY 4,000 Coal @ 210, max spend escrowed, expires Fri 22:00, partial yes" | sellers fill any part; no Adena created |
| **Delivery Order** | "deliver 250 Iron Ore to Giran Notarius by Y for X" | verifies possession/transfer, not the journey |
| **Loan** | Adena/item loan with collateral, expiry, return terms | collateralised/escrow-backed only; collateral releases or forfeits mechanically; no "supernatural debt collection" |
| **Loot Charter** | before a raid: normal random / finders keepers / leader distribution / item reservation / post-kill auction / deterministic split | enforced by the core loot-routing hook before `Party.distributeItem` picks a looter; frozen participants; explicit rules for join/leave/death/disconnect/spoil/command channel; reserved and auctioned items go atomically to charter escrow; post-kill auction runs at the charter NPC page with bids escrowed |
| **Mercenary Contract** | Clan A hires Clan B to defend Giran: ≥12 members registered as defenders, ≥45 min measured participation, 40M on siege completion | references native registrations (castle siege defenders, Territory War mercenary registry `TerritoryWarManager._registeredMercenaries`, fortress sieges); participation from facts |
| **Caravan Escort** *(NEW v2)* | issued by the Caravan Guild (12 §8) | delivery + participation clauses |
| **Treaty** *(NEW v2)* | public instrument between clans/alliances: non-aggression in a territory for N days with escrowed forfeits | the only enforceable part is the forfeit: if a party's members kill the other's members in the named territory inside the window (facts), the forfeit pays out. The treaty cannot stop anyone from attacking — betrayal stays possible, it just has a price |

## 3. Lifecycle

```text
DRAFT → PROPOSED → PARTIALLY_SIGNED → FUNDED (assets escrowed) → ACTIVE
   ACTIVE → COMPLETED | EXPIRED | MUTUALLY_CANCELLED | FAILED_BY_CONDITION | ADMIN_REVIEW (corruption/bug path only)
```
Every transition is a durable fact written in the same transaction as its asset movements. `ADMIN_REVIEW` requires an audit
fact with reason.

## 4. Escrow rules

Atomic (Asset Service, 04 §3); no duplicate settlement (settlement uniqueness key per contract/clause); idempotent completion;
object ids tracked for unique items; exact stack quantities; no asset simultaneously in inventory and escrow; settlement
survives restart; full-inventory settlements stay claimable in escrow; immutable audit except corrective admin operations
with reason.

## 5. Visibility

Private · parties visible, terms sealed · fully public · publishable to history after completion. Public registration produces
restrained facts: *"A commercial agreement between BlackForge and Nightfall has been registered."* Sealed terms never reach
publication DTOs.

## 6. Objective reputation only

```text
143 contracts completed · 3 mutually cancelled · 0 mechanical defaults · 87 contracted craft attempts (12 success / 75 normal failure)
```
Never stars, never "trustworthiness 87 %". The server records facts, not virtue.

## 7. What PACT never protects

Verbal alliance promises, secret spot disclosure, clan treasury access, manual weapon lending, political loyalty, information
leaks, promises not to attack (except a Treaty's escrowed forfeit). Lineage needs betrayal.

## 8. The Notarius institutions

| NPC | Where | Handles |
|---|---|---|
| **Town Notarius** | every town | crafting pacts, craft/purchase/delivery orders, loans, loot charters |
| **Royal Notarius** | Aden, Giran, Rune | high-value agreements (≥10M *(tunable)*), clan agreements, treaties, public/sealed instruments |
| **Castle Notarius** | each castle | mercenary contracts, siege and fortress contracts, castle records, Crown Law treasury commitments |
| **Caravan Guild Master** *(NEW v2)* | trade towns | escort contracts |

- Remote drafting for ordinary contracts (Codex → Contracts); high-value and clan contracts require final signing in person at
  the right Notarius (proximity-bound confirmation token).
- Signed contracts are represented by a non-tradable **Notarized Contract** parchment item (type-specific icons via the `.dat`
  patch). Using it opens the authoritative terms page. It is a representation, never a currency.
- Fees: registration fee (0.5 % min 1,000 Adena *(tunable)*), sealing fee for sealed terms; fees are sinks. Crown Law Market
  Charters can reduce them in a territory.
- The Notarius is not a judge, a buffer, a teleporter or a shop. It never processes Black Contracts.

## 9. Data

```text
pact_contract(id, type, state, version, visibility, created_by, created_at, deadline, terms_hash, terms JSON, notarius_tier, publishable)
pact_party(contract_id, role, char_id, clan_id, signed_at, signature_token_id)
pact_clause(contract_id, clause_no, kind, params JSON)
pact_custody(contract_id, custody_id)                    -- links to lc_custody
pact_settlement(contract_id, clause_no, settlement_key UNIQUE, op_id, outcome, at)
pact_craft_outcome(contract_id, attempt_no, recipe_id, success, rolled_at, op_id)   -- persisted; no reroll
pact_registry(contract_id, public_text_key, registered_at)
pact_history(char_id, completed, cancelled, defaults, craft_attempts, craft_success)  -- derived
```

## 10. UI flows

Codex → Contracts: draft (per type form), propose, review incoming, sign (ConfirmDlg + token), fund, track, claim overflow,
cancel by mutual consent, objective history. Notarius NPC pages: in-person signing, registration, sealed instruments,
public registry board. Loot Charter: party leader proposes in Codex; members confirm via `ConfirmDlg`; charter shown on raid.

## 11. Acceptance

`PACT-*`: no dupes, no silent loss across large automated settlement suites and restart-at-every-state tests (incl. faults
between individual SQL mutations); conditions machine-verifiable; craft success/failure exactly retail; persisted craft
outcomes never rerolled; loot charter enforced before looter selection; mercenary settlement from native registrations and
participation facts; treaty forfeits pay only on facts; objective history only; betrayal outside contracts remains possible.
