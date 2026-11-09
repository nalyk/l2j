# 16 — The Black Ledger

> **There is a price on your head. Nobody knows where you are. Yet.**
> An assassin's primary profession is information. The kill is the final act.

Lineage II already has violence, PK consequences, geography and open-world danger. The missing gameplay is the **hunt**.

## 1. Sacred balance rule

The Black Ledger grants 0 P.Atk, 0 M.Atk, 0 speed, 0 crit, 0 blow, 0 stealth, 0 target debuff, 0 teleport-to-target,
0 combat privilege. A hunter who finds the target fights with normal Lineage II rules. Killing a white target still creates
karma. The contract pays because the hunter accepts the danger; it does not legalise murder.

## 2. Two legal faces, three institutions

| | The Writ (lawful) | The Black Contract (illegal) |
|---|---|---|
| Institution | **Magistrate / Guard Captain** in towns | **Broker / Shadow Broker** (hidden NPCs in taverns, catacombs, back alleys) |
| Targets | chaotic (karma) characters; cursed-weapon holders; enemies under a declared clan war; other server-defined lawful warrants | anyone outside protections (§10) |
| Funding | players, Crown Law Bounty Endowments, caravan guild fee pools, citizen houses | the depositor only |
| Publicity | public: **Wanted boards** in every town square *(NEW v2)*, Gazette, Codex | sealed |

> Notarius protects promises society recognises. The Magistrate administers the law. The Broker enforces promises society
> must never see.

### 2.1 Public bounties and PK legends
Writ totals on notorious chaotic players are public status: *"TOTAL PUBLIC BOUNTY: 187,000,000 Adena"*. Being notorious
becomes dangerous because people want the money.

### 2.2 Cursed weapon writs *(NEW v2)*
When `ON_CURSED_WEAPON_ACQUIRE` fires (Zariche, Akamanah), the Magistrate opens an automatic Writ on the holder, funded by any
active Crown Law Bounty Endowments and by player contributions. The holder is already hunted by retail rules; now it is a
public manhunt with a price and a story.

### 2.3 Citizen bounties *(NEW v2)*
Citizen houses fund Writs and Black Contracts from their own budgets based on their relationship ledgers (10 §7). Kill enough
of a house's people and the house buys your death — through the same rules as anyone.

## 3. The hunt

### 3.1 Dossier
An accepted contract gives identity-level facts, not live location:
```text
BLACK CONTRACT #8821 · Mark: Nalyk · Dark Elf · approx. level 56–60 · Clan: Nightfall
Value: 12,000,000 · Last confirmed activity: 2h 17m ago · Current location: UNKNOWN
```

### 3.2 Traces
Normal actions leave sparse semantic traces: gatekeeper use (`ON_PLAYER_GATEKEEPER_TELEPORT`), warehouse visits, merchant
interactions, PvP/PK, death, zone/town transitions, public siege participation, public contract activity, verified player
sightings. Traces live in the Intel Service with TTL and sensitivity; exact coordinates never leave it.

```text
black_trace(id, mark_char_id, kind, region_id, town_id NULL, coarse_dir NULL, occurred_at, expires_at, sensitivity, source_fact_id)
```
Default lifetimes *(tunable)*: HOT ≤15 min, RECENT ≤1 h, STALE ≤6 h, then expired and deleted from source, caches and projections.

### 3.3 NPC witnesses (existing NPCs gain a secondary role)
- **Gatekeepers**: "The name is familiar. He used my services earlier today." With stronger access: "He travelled toward Oren."
- **Warehouse keepers**: confirm a recent visit, never contents.
- **Merchants**: recent commercial contact; narrow, fair clues only.
- **Guards**: coarse direction/area of a recently seen character.
- **Clan hall NPCs**: membership/activity, willingness depends on ownership and politics.
- **Tavern/information NPCs**: aggregated rumors ("Hunters say Nightfall has been seen around the Ivory Tower").
Questioning requires the hunter **in person** at the NPC, costs Adena (sink), and is bound to that character.

### 3.4 No offline omniscience
When the mark logs out, fresh traces stop. The hunter learns "last activity some time ago; the trail has gone cold". Nothing more.

### 3.5 Player informants
Anyone who legitimately sighted the mark can sell a **verified sighting** to the Broker network:
```text
Verified player sighting · Region: Oren territory · Age: ~23 minutes
```
Eligibility (core `canWitness`): same instance, bounded distance, geodata line of sight, visibility state, minimum
observation duration or authoritative interaction, recency. The server snapshots private evidence; the disclosed clue stays
coarse. Equivalent sightings deduplicated across cooperating hunters and contracts. A low-level character can hold
information worth millions. New profession: **Informant**.

### 3.6 Intelligence budget
```text
Principal 20,000,000 · Intel budget 3,000,000 · Broker fee 500,000 · Potential payout 19,500,000
```
All player-funded, transparent to the depositor, not to the mark. Disputed informant proceeds are frozen before payment.

### 3.7 Confidence classes
`CONFIRMED` (direct authoritative evidence) · `WITNESS` (server-verified sighting) · `INFERRED` (derived from valid clues) ·
`RUMOR` (possibly manipulated) · `STALE`. Wayfarer's Memory keeps the class.

### 3.8 Hunter progression is information progression
**Listener** (rumor access) → **Tracker** (better witness questioning) → **Stalker** (correlates clues) → **Head Hunter**
(high-value contracts) → **Ghost** (older/sealed traces within strict bounds). Unlocks improve time-window precision,
witness query quality, evidence sources, correlation, eligibility, alias tools. Never combat stats. Promotion by completed
contracts, survival and verified intel quality *(tunable thresholds)*.

## 4. Counter-intelligence

- **Intuition**: repeated inquiries about you eventually produce *"You feel watched."* (`ExShowScreenMessage`), probabilistic
  and thresholded, never a proximity alarm.
- A friendly NPC mentions a stranger asking questions; clan intelligence sees information purchases in territory it controls.
- **Trail manipulation through movement**: Giran → gatekeeper → Oren leaves a different trail from Giran → gatekeeper →
  Hunters Village → on foot. Geography is counter-intelligence.
- **Bribing witnesses**: some witness classes accept time-bounded suppression; cost scales with sensitivity and notoriety;
  lawful officials may refuse; politics (Crown Law, territory owner) shifts willingness; the hunter may notice an evasive
  witness without learning why; suppression never erases audit.
- **False trails**: a controlled misinformation service, always labelled RUMOR, plausible, never fabricating authoritative
  history.

## 5. Contract conditions and endings

Machine-verifiable only: kill once; before deadline; outside siege; while in a specified clan; in a named territory
(*public execution*: political message, extra difficulty). Multi-kill contracts only with cooldowns and target protections.

**Hunter wins** → settlement through the Asset Service; optional calling card; consequences (karma stays).
**Target wins** → evidence drop *Torn Black Seal* (value band, origin network, sponsor sealed) → **reverse investigation**:
failed assassin → contract trace → broker network → informants/financial clues → possible patron. If the patron is discovered
through valid mechanics, the consequences are social and political (clan war is the players' choice).

## 6. Identity: calling cards, aliases, mythology

- **Calling cards** (black feather, broken silver coin, blood-red card, chosen symbol): *"A black feather was left beside
  your body."* No stat effect.
- **Nom de guerre**: a persistent alias ("Black Feather") revealed to the client, the victim via calling card, the hunter
  network, and the Chronicle for legendary cases. The alias→character mapping is confidential in Ledger records only.
  Ordinary combat (CharInfo/Attack packets show the attacker) can expose it; placing a card at the kill is a deliberate
  choice that can burn secrecy; **delayed Broker delivery** lets a hunter claim the kill later with evidence rules
  connecting claim and attack.
- **Identity intelligence market**: evidence linking alias to character can be sold to the Broker with a server-derived
  confidence; random gossip is never certified. Once exposed, the hunter becomes a target.
- Murder acquires a market price: the server never computes a "correct bounty"; human willingness to fund and accept is the
  price discovery. *Profession test:* someone who knows how to find people who do not want to be found.

## 7. Economy

Zero minting. Depositor pays; hunter receives payout; broker fee, bribes, registrations, sealed identity, intel purchases,
counter-intelligence and alias services are sinks or transfers. The more murderous the society, the more Adena leaves.

## 8. Information under alts and Discord

Designed so every clue stays balanced when relayed instantly to an enemy alliance:
- server **actions** are protected (contract authorisation, in-person questioning, character-bound capabilities, exact expiry);
  learned **content** is assumed shareable;
- disclosure is priced and limited per contract, target, witness and window with diminishing returns that do not reset for a
  cooperating account;
- linkage (same account, behaviour, party/trade graph, IP) blocks or freezes payouts with explicit false-positive handling;
  never refund by minting;
- published guarantee: bounded disclosure and economically unattractive farming — not identification of who controls an
  account.

## 9. Anti-abuse

No or restricted reward for: same account; suspicious linkage; current party; immediate reciprocal contracts; repeated pair
farming (uses Mobius `AntiFeedManager` signals plus our linkage graph); NPC fakes; test actors; event/Olympiad contexts;
newbie-protected characters; trivial level gaps.

## 10. Anti-harassment

Minimum meaningful contract value; cooldown after a successful execution; diminishing eligibility for repeated same-target
contracts; extreme level-gap limits; newbie protection (below level 40 *(tunable)*); no permanent tracking; no offline farming;
full admin audit. High-level political figures are not immune: protection exists for abuse, not importance.

## 11. History

Most contracts are transient. Promoted to the Chronicle by value, failed hunters, political significance, notoriety, duration,
clan-war consequences, records:
```text
The Giran Knives — Seven contracts on Nightfall leadership. Four hunters failed. Two abandoned.
Ghost completed the final 84,000,000 contract outside Oren.
```
Observable facts only, never invented motives. Sealed terms never published.

## 12. Data

```text
black_contract(id, kind {WRIT|BLACK}, state, mark_char_id, client_ref, value, intel_budget, broker_fee, conditions JSON, custody_id, created_at, deadline)
black_writ_funding(writ_id, source_kind {PLAYER|CROWN_LAW|GUILD|HOUSE}, source_id, amount, custody_id)
black_hunter(char_id, rank, alias_id NULL, completed, failed, intel_quality)
black_alias(id, name, owner_char_id, exposed_at NULL)          -- owner visible only to Ledger internals and forensics
black_calling_card(alias_id, kind)
black_trace(...) ; black_trace_access(trace_id, hunter_char_id, contract_id, disclosed_precision, at)
black_witness_state(npc_id, mark_char_id, suppressed_until, bribe_fact_id)
black_intel_offer(id, informant_char_id, mark_char_id, evidence_ref, coarse_payload, state, price)
black_counterintel(char_id, kind, at)
black_reverse_case(id, victim_char_id, seal_item_lifecycle_id, steps JSON, state)
black_abuse_signal(subject, kind, score, at)
```

## 13. UI

Wanted boards (NPC pages in town squares). Magistrate and Broker NPC pages for creating, funding, accepting contracts and
questioning. Codex → Ledger tab (only for registered hunters, clients and marks): dossier, acquired intel with confidence and
age, informant offers, reverse investigation case file. Calling-card choice on settlement.

## 14. Journey (target experience)

Accept contract → read dossier → choose first witness → travel → buy/bribe/question → trail direction → buy sighting or infer →
target gets a warning → target changes route → trail cold → reacquire → physical encounter → normal L2 combat →
settlement and card, or Torn Black Seal and reverse hunt. Forty minutes with almost no combat can feel like continuous PvP
because both sides make adversarial decisions.

## 15. Acceptance

`BL-*`: no coordinate leaves the Intel Service; trace TTL respected through caches and rebuilds; sighting eligibility edge
cases (walls, floors, invisibility, teleport, login, doors, summons, colluding witnesses); false trails labelled RUMOR; hunts
succeed through evidence in simulation with target survival and hunt-duration inside declared bands under a full
out-of-band-sharing citizen population; zero combat modifiers; abuse rings blocked/frozen with reasons; zero minting.
