# SPEC-BL — The Black Ledger

- Status: reviewed (revision 7; its Amnesty amendment signed off by Codex in amendment round 1; record in §12)
- Blueprint: 16 (the Black Ledger); 03 §4.4 (`ON_CURSED_WEAPON_ACQUIRE`), §4.5 (`canWitness`); 04 §3 (Asset Service), §4
  (Intel Service), §5 (Codex), §7 (keyed randomness); 10 §7 (citizen houses); 12 §6.2 (Crown Law Bounty Endowments);
  17 (publication); consensus §6.
- Depends on: asset-service.md (custody, fees, service mail; its revision 9 adds `LEDGER_SEAL` and `FEE_BRIBE`),
  intel-service.md (traces, witnesses, disclosure, budgets; its revision 6 adds suppression, rumors and identity evidence),
  fact-journal.md (captured and outbox facts, gaps, expiring data, projectors), actor-identity.md (snapshots, principals,
  policy), codex-ui.md (pages, tokens, `NpcRef`), pact.md (identities and keys, §2.3; the Ledger and PACT share the Asset
  Service, never institutions, 20 §1).
- DDL: `sql/migrations/V012__lc_ledger.sql` (after PACT's V011).
- Upstream facts cited as `file:line` are at the fork's current upstream snapshot (`upstream` c9ca4c31); paths are under
  `java/org/l2jmobius/gameserver/` unless they start with `dist/`.

## 1. Scope

The Black Ledger turns finding a person into play: bounties on a character, paid to whoever kills them under the
contract's conditions, with the hunt carried by priced, bounded information. It owns:

- **contracts**: lawful **Writs** (Magistrate) and sealed **Black Contracts** (Broker), their funding, acceptance,
  conditions and settlement;
- **hunters**: registration, ranks, aliases and calling cards;
- **deaths and decisions**: the evidence of a mark's death and each contract's decision about it;
- **payments**: every payout, with its own anti-abuse check and hold;
- the Ledger's **pages and prices** for questioning, sightings, bribes, false trails, alias links and the reverse
  investigation;
- **counter-intelligence** signals, **anti-harassment** and **anti-abuse** rules;
- the Ledger's **publication** (wanted boards, legends).

It does not own: information (the Intel Service owns traces, witnesses, budgets, expiry and every disclosure; the Ledger
asks and pays), assets (the Asset Service), combat (Mobius: **the Ledger grants nothing in combat**, 16 §1), karma
(retail's, unchanged), PACT's institutions (the Notarius never handles Black Contracts).

### 1.1 The sacred balance rule

0 P.Atk, 0 M.Atk, 0 speed, 0 crit, 0 blow, 0 stealth, 0 debuff, 0 teleport-to-target, 0 combat privilege (16 §1). A hunter
fights under normal rules; killing a white mark creates karma as retail does (`entity/actor/Player.java:5687-5698`,
`:5726-5745`); hitting a mark with karma never flags the hunter, as retail decides (`Player.java:5798-5837`). Living
Chronicle code calls no mechanics API (actor-identity.md §3); AT-BL-09 checks the Ledger's.

### 1.2 Confidentiality

Anonymity is the **confidentiality of Ledger records** (consensus §6.5), guaranteed **against players**: no page, fact
payload, publication row or disclosure reveals a depositor, an alias's owner or an informant except by §4.9 and §4.10. It
is not a guarantee against the server's operators: the Asset Service's postings are permanent financial audit
(asset-service.md §2.2) and name the holders money moved between, readable only by admins and forensics, never by game
code outside the Asset Service, never published. The Ledger's own identity mappings expire as soon as their use ends (§2.2).

## 2. Data

### 2.1 Tables

```text
lc_ledger_contract(contract_id BIGINT PK auto, kind {WRIT|BLACK}, cause {KARMA|CURSED_WEAPON|CLAN_WAR|OPEN}, state {§3.1},
                   version INT, mark_char INT, issuer_clan INT NULL, target_clan INT NULL, created_at, deadline,
                   player_principal BIGINT, external_principal BIGINT, principal = their sum (stored), intel_budget BIGINT,
                   max_sighting_price BIGINT, broker_fee BIGINT, conditions JSON,
                   terms_hash BINARY(32), origin_npc VARCHAR(32), origin_town SMALLINT, sealed_identity BOOLEAN,
                   min_rank TINYINT, max_hunters TINYINT, kills_required TINYINT, kills_done TINYINT,
                   settling_death VARCHAR(64) NULL)        -- the death whose settlement is in progress (§3.1)
lc_ledger_patron(contract_id PK, client_char INT, expires_at)          -- the depositor of a Black Contract (§2.2)
lc_ledger_funding(contract_id, funding_no INT, source {PLAYER|CROWN_LAW|GUILD|HOUSE}, amount BIGINT, op_id, at,
                  PRIMARY KEY(contract_id, funding_no))
lc_ledger_funder(contract_id, funding_no, source_id BIGINT, expires_at)  -- who contributed (§2.2)
lc_ledger_custody(contract_id, purpose {PRINCIPAL|INTEL}, custody_id INT → lc_custody)
lc_ledger_hunter(char_id PK, rank {LISTENER|TRACKER|STALKER|HEAD_HUNTER|GHOST}, registered_at, completed, failed, abandoned,
                 sightings_verified)
lc_ledger_alias(alias_id PK, name UNIQUE, owner_char INT NULL UNIQUE, card {FEATHER|COIN|CARD|SYMBOL}, created_at,
                exposed_at NULL, retired_at NULL)
lc_ledger_acceptance(contract_id, hunter_char, accepted_at, card_mode {AT_KILL|DELAYED|NONE}, state {ACTIVE|WON|FAILED|
                     ABANDONED|EXPIRED|VOID}, PRIMARY KEY(contract_id, hunter_char))
lc_ledger_death(death_key VARCHAR(64) PK, fact_id BIGINT, victim_char INT, killer_char INT NULL, killer_kind {SELF|SUMMON|
                TRAP|DOT|OTHER}, at, region_id, territory SMALLINT NULL, context JSON)   -- immutable evidence
lc_ledger_decision(death_key, contract_id, outcome {QUALIFIED|EXCLUDED}, reason VARCHAR(48) NULL, decided_at,
                   PRIMARY KEY(death_key, contract_id))                                       -- immutable
lc_ledger_count(contract_id, death_key, ordinal INT, counted_at, voided_at NULL, PRIMARY KEY(contract_id, death_key),
                UNIQUE(contract_id, ordinal))                                                     -- counted kills
lc_ledger_claim(contract_id, death_key, claim_deadline, claimed_at NULL, claim_op NULL, PRIMARY KEY(contract_id, death_key))
lc_ledger_payment(payment_id BIGINT PK auto, kind {BOUNTY|SIGHTING|ALIAS_LINK}, contract_id NULL, death_key NULL,
                  funding {PLAYER|EXTERNAL}, source_custody INT NULL, amount BIGINT, state {PENDING|HELD|PAID|BLOCKED|RETURNED},
                  reason VARCHAR(48) NULL, review_until NULL, resolved_by NULL, op_id NULL)   -- durable: no identity
lc_ledger_payment_party(payment_id PK → lc_ledger_payment, beneficiary_char INT, evidence JSON NULL, expires_at NULL)
                                                                                         -- who is paid, and why held
lc_ledger_weapon_life(weapon_life VARCHAR(48) PK, item_id INT, dropped_at, ended_at NULL, external_paid BIGINT)
lc_ledger_seal(lifecycle_id PK → lc_item_lifecycle, contract_id, hunter_char, mark_char, value_band, origin_town, issued_op,
               UNIQUE(contract_id, hunter_char))
lc_ledger_reverse_case(case_id PK, lifecycle_id UNIQUE, holder_char, step TINYINT, opened_at, expires_at)
lc_ledger_offer(offer_id PK, informant_char, mark_char, trace_id → lc_trace ON DELETE CASCADE, price, state {OFFERED|SOLD|
                EXPIRED}, payment_id NULL, expires_at ≤ the trace's)
lc_ledger_inquiry(mark_char, window_start, inquiries, warned, expires_at)
lc_ledger_pair(client_char, mark_char, window_start, contracts, expires_at)
lc_ledger_mark_cooldown(mark_char PK, cooldown_until)
lc_ledger_endowment_use(mark_char, window_start, paid BIGINT, expires_at)
```

### 2.2 What is kept and what expires

| Datum | Where | Kept |
|---|---|---|
| a Black Contract's depositor in the Asset Service's facts (`Asset.EscrowFunded`, `Asset.EscrowReleased` of its custodies) | `lc_fact_expiring`, because the Ledger opens its custodies as **confidential** (asset-service.md §5, revision 9) | as `lc_ledger_patron` |
| a Black Contract's depositor | `lc_ledger_patron` | until the contract is terminal, its payments resolved, and its seals' reverse cases expired (`ReverseCaseHours` after the last seal); then deleted |
| a Writ contribution's source | `lc_ledger_funder` | until the Writ is terminal and its returns made; then deleted |
| an alias's owner | `lc_ledger_alias.owner_char` | while the alias is active; retiring it (the owner's choice, or `AliasIdleDays` without a contract) sets it to `NULL` and keeps the name reserved. **Stated limit**: restoring a database backup returns the server to the backup's moment, and a retirement made after it is undone with everything else; the restore procedure lists the aliases retired since the backup's time (from the journal's `Ledger.AliasRetired` facts, if the journal survives) so an admin can re-apply them, and otherwise the owner may retire again |
| a payment's beneficiary and linkage evidence | `lc_ledger_payment_party` | until the payment is resolved **and** its contract is terminal, + `PaymentAuditDays` (set by the resolving operation; the schema refuses an expiry set before both, or less than a day ahead, the configuration's minimum, and any deletion before the expiry). Every payment has its party row before it can resolve, so a bounty's killer-bound recipient is never optional; the hashed IPs of §4.11 only in `evidence`, gone with it. The payment row itself (kind, contract, death, funding, amount, state, operation) is durable and names nobody, so a leg's identity and outcome outlive its evidence |
| inquiries, pairs, offers, reverse cases, endowment use | their tables | their windows |
| who acted in a secret operation (opening a Black Contract, registering or retiring an alias, submitting a sighting, buying a link, bribing, planting a false trail), and the holders of its Asset operations' facts (the Ledger runs these as **confidential operations**, asset-service.md §5, revision 9: fee charges included) | the fact's `actor` field in `lc_fact_expiring` (fact-journal.md §2.4), with the life of the mapping above; the fact's own `actor_snapshot` is the institution's (`SYSTEM`, the Broker) | as above |

Every row with an `expires_at` is listed in `lc_expiring_table` (intel-service.md §4.5): purged, kept out of old backups,
deleted on restore. Facts never name a depositor, an alias owner or an informant in their payload (FACT-INV01).

### 2.3 Custody and identities

A Black Contract has two custodies: `PRINCIPAL` (the bounty) and `INTEL` (the intelligence budget, transparent to the
depositor, 16 §3.6). A Writ has `PRINCIPAL` only, funded by contributions: players, Crown Law Bounty Endowments (the Asset
Service's treasury `commit`), guild fee pools, citizen houses. Identities and keys follow PACT's (pact.md §2.3): request,
effect or kill identities; funding keys `BL/<contract>/fund/<n>`; settlement keys `BL/<payment>`; a death key (§4.5).

## 3. State machines

### 3.1 Contract

| From | Event | Guard | To | Effect |
|---|---|---|---|---|
| — | `open` (Black, at a Broker in person) | §4.2 | `OPEN` | principal and intel budget into custody; broker fee and, if bought, sealed identity charged (`FEE_BROKER`); `lc_ledger_patron` |
| — | `open` (Writ, at a Magistrate, or automatically, §4.3) | the basis holds | `OPEN` | the basis recorded; first contribution, if any |
| `OPEN` | `contribute` (Writ) | the basis holds | `OPEN` | funds added; `WritFeePermille` charged (`FEE_BROKER`) |
| `OPEN` | `cancel` (Black, by the depositor) | no acceptance yet | `CANCELLED` | principal and intel returned; fees not |
| `OPEN` | a `QUALIFIED` decision (§4.5) | not the last required kill | `OPEN` | a count row for that death; `kills_done` + 1; nothing paid yet |
| `OPEN` | a `QUALIFIED` decision | the last required kill | `CLAIMABLE` (Black) or `SETTLING` (Writ) | its count row; `kills_done` + 1; `settling_death` = that death; a claim row (Black) or the bounty legs (Writ) |
| `CLAIMABLE` | `claim`, the killer in person at a Broker, before `claim_deadline` | — | `SETTLING` | the bounty legs |
| `CLAIMABLE` | the claim deadline passes | — | `OPEN` if before the contract's deadline, else `EXPIRED` | the death's count row voided; `kills_done` − 1; `settling_death` cleared |
| `SETTLING` | every bounty leg of `settling_death` resolved, at least one `PAID` | — | `SETTLED` | unspent intel to the depositor; a `RETURNED` leg's share back to its funders; the mark's cooldown (§4.2) |
| `SETTLING` | every bounty leg of `settling_death` `BLOCKED` or `RETURNED` | — | `OPEN` if before the deadline and principal remains, else `EXPIRED` | the death's count row voided; `kills_done` − 1 (so a replacement kill can qualify); `settling_death` cleared; a `RETURNED` leg's share back to its funders and its principal share set to 0 (a `BLOCKED` leg's money stays); the death and its decision stay |
| `OPEN` | the deadline, decided only once the journal's frontier has passed it with no overlapping gap of the death family (fact-journal.md §3.3) | — | `EXPIRED` | principal and unspent intel returned (Writ: each contribution to its source) |
| `OPEN` | the deadline with an overlapping gap | — | `REVIEW` | an admin decides from the gap and Mobius's own counters |
| `REVIEW` | admin | — | `OPEN`, `EXPIRED` or `SETTLING` | audit fact |
| `OPEN` (Writ) | the basis ends (§4.3), decided like a deadline: only once the journal's frontier has passed the moment it was found false, with no overlapping gap of the death family and every captured death of the mark up to then decided | no qualifying death | `LAPSED` | contributions returned |

Terminal: `SETTLED`, `EXPIRED`, `CANCELLED`, `LAPSED`. Every transition is a durable fact in the transaction of its
movements. A trigger enforces the table **and its row guards**:

- **Counted kills** are rows of `lc_ledger_count`, one per (contract, death), numbered by `ordinal` in the order they are
  counted (each the previous maximum + 1), made only for a `QUALIFIED` decision while the contract is `OPEN`, never
  deleted, only ever voided; `kills_done` always equals the contract's unvoided count rows, so no update can count a kill
  without consuming a decision, and a voided death is never counted again.
- **Settlements are about `settling_death`**: entering `CLAIMABLE` or `SETTLING` from `OPEN` (or `REVIEW`) sets it to **the
  last counted death** (the unvoided count row of the highest ordinal: the final kill, never an earlier one) and needs
  `kills_done` = `kills_required`; `SETTLING` from `CLAIMABLE` needs that
  death's claim claimed; leaving for `OPEN` or `EXPIRED` needs its count row voided first.
- **Bounty legs** exist only for `settling_death`, are created only while the contract is `SETTLING`, are unique per
  funding kind, each for exactly its funding share's amount and paying exactly the settling death's killer (its party
  row), and form a complete set: a `PLAYER` leg while `player_principal` > 0 and an `EXTERNAL` leg while
  `external_principal` > 0. `SETTLED` needs the set complete, all resolved, one `PAID`; leaving `SETTLING` for `OPEN` needs
  every leg `BLOCKED` or `RETURNED` and some principal left, with each returned leg's principal share set to 0.
- `CANCELLED` needs no acceptance; `LAPSED` only for Writs; `CLAIMABLE` only for Black Contracts; principal shares grow
  only while `OPEN` and shrink only as above.

### 3.2 Acceptance

`ACTIVE` → `WON` | `FAILED` (killed by the mark while active, §4.9) | `ABANDONED` | `EXPIRED` | `VOID`, once.

### 3.3 Payment

`PENDING` → `PAID` (no signal) | `BLOCKED` (a block signal; the money stays in or returns to its custody) | `HELD` (a hold
signal); `HELD` → `PAID` (admin release, or the review window passes for a `PLAYER`-funded payment) | `RETURNED` (admin
return, or the review window passes for an `EXTERNAL`-funded payment, §4.11). Each payment is checked and resolved on its
own: a held sighting purchase does not hold the contract. A bounty is **one leg per funding kind present** (`PLAYER`:
`player_principal`; `EXTERNAL`: `external_principal`), each resolved independently (§3.1 settles when all are).

## 4. Operations

### 4.1 Hunters and ranks

A character registers as a hunter in person at a Magistrate or a Broker (`LISTENER`). Ranks are information access only
(16 §3.8):

| Rank | Promotion (all, *tunable*) | Unlocks |
|---|---|---|
| `LISTENER` | registration | tavern rumors, Writs |
| `TRACKER` | 3 won | witness questioning up to `REGION`; Black Contracts with `min_rank` ≤ Tracker |
| `STALKER` | 10 won, failed ≤ won, 5 sightings verified | questioning up to `ZONE`; the correlation page (the hunter's own granted items side by side) |
| `HEAD_HUNTER` | 25 won | contracts with principal ≥ `HighValueAdena` |
| `GHOST` | 50 won, 20 sightings verified | questioning of traces in the `OLD` age band (others see `FRESH` and `RECENT` only) |

A won contract counts only if its bounty was `PAID`. Every unlock narrows what the Intel Service may already disclose; none
touches combat. A rank never falls.

### 4.2 Black Contracts

Opened by a depositor in person at a Broker: the mark, principal, intel budget, **maximum sighting price** (a cap on any
one informant purchase, ≤ the intel budget), deadline, conditions (§4.5), `min_rank`, `max_hunters`, `kills_required`, and
optionally sealed identity (`SealedIdentityAdena`), which obscures the last two reverse steps (§4.9). Refused when:

- the mark is below `NewbieLevel` (default 40; retail's own newbie protection is narrower: Protection Blessing stops
  karma players attacking a target 10+ levels lower, `ai/PlayableAI.java:53-66`), is not a player, or is in cooldown;
- the depositor and the mark share an account or a principal;
- the mark already has `MaxOpenOnMark` open Black Contracts, from any depositors;
- the depositor already opened *k* contracts on this mark in `PairWindowDays` and the principal is below
  `MinContractAdena` × 2^k, or *k* = `MaxPairContracts`;
- a contract by the mark on the depositor was opened in the last `ReciprocalHours`.

A hunter accepts in person at a Broker if registered, of `min_rank` or above, neither the depositor nor the mark, sharing
no account or principal with either, at most `LevelGapMax` levels above the mark, while fewer than `max_hunters` are
`ACTIVE`; they choose a calling-card mode (§4.8). Acceptance gives the dossier (§4.4).

**Mark cooldown.** After any `QUALIFIED` decision on a mark (any contract, Writ or Black, intermediate kill or final), the
mark is in cooldown for `MarkCooldownHours`: a death during it is decided `EXCLUDED` (`MARK_COOLDOWN`) for every contract.
Decisions are made in log order (fact-journal.md §4.2), so of two deaths the earlier decides first.
`MultiKillCooldownHours` is at least `MarkCooldownHours` (configuration ranges).

### 4.3 Writs

| Cause | Basis recorded at opening | Lawful at a death while |
|---|---|---|
| `KARMA` | the mark's karma > 0 | the death snapshot shows karma > 0 |
| `CURSED_WEAPON` | the mark holds one (`managers/CursedWeaponsManager.java:349`) | the snapshot shows the mark holding it |
| `CLAN_WAR` | `issuer_clan`, `target_clan` (the mark's), mutual war: each `isAtWarWith` the other (`entity/clan/Clan.java:1643`) | the snapshot shows the killer in `issuer_clan`, the mark in `target_clan`, both directions at war, neither in the academy |

**Amnesty** (aden-remembers.md §4.5): at a `KARMA` opening in a territory whose castle has an enacted Amnesty, the
Magistrate asks Aden Remembers' predicate, which compares the occurrence time of the mark's latest PK with the
proclamation's once the journal's frontier has passed the opening time, and fails closed ("no amnesty") on lag, a gap or a
stopped projector; "amnesty" refuses the opening with that reason. No other cause is affected.

A Writ is opened by anyone at a Magistrate (`KARMA`), by a clan leader of the issuing clan (`CLAN_WAR`), or **automatically**
when a cursed weapon is acquired, through the `ON_CURSED_WEAPON_ACQUIRE` seam at `CursedWeapon.activate`
(`entity/actor/holders/player/CursedWeapon.java:369-393`; Mobius dispatches no event there). Lawfulness is read at the
death, synchronously, from the death snapshot (§4.5), never from asynchronous war or karma events (war events are
dispatched before the clan sets change, `data/sql/ClanTable.java:405-408`, `:448-451`). The basis **ends** when the Ledger's
periodic check, or a karma, war or cursed-weapon event followed by a re-read of the state, finds it false; the Writ then
lapses, unless a qualifying death awaits payment.

**Writ farming bounds.** A Writ is paid only under the same mark protections as a Black Contract (newbie level, level gap,
cooldown). **External money** (Crown Law Bounty Endowments, guild fee pools) funds only `CURSED_WEAPON` Writs: a cursed
weapon cannot be manufactured (it drops from a monster's death at 1 in 100 000 and its holder is whoever picks it up,
`dist/game/data/CursedWeapons.xml:3-16`, `entity/actor/Attackable.java:1169`), so no ring can create a mark that external
money will pay for at will. That does not stop a ring member who happens to pick a weapon up from dying on purpose to an
unlinked friend, nor a weapon dropped at its holder's death (`CursedWeapon.java:458`) from being picked up by another
member: the guarantee for external money is therefore a **bounded subsidy**, not a loss: external payouts are capped at
`EndowmentCapAdena` **per weapon life** (`lc_ledger_weapon_life`: one random drop, from `CursedWeapon.checkDrop`,
`CursedWeapon.java:353`, to the weapon's end, `:95-185`, across every pickup and death drop in between, :369, :458) and per
mark per `PairWindowDays` (`lc_ledger_endowment_use`); a held external payment is returned by default (§4.11); and hunter
and holder sharing an account or a principal is a block. The endowments' total exposure is therefore at most the number of
random drops (1 in 100 000 monster deaths per weapon, `dist/game/data/CursedWeapons.xml:3-16`) times the cap, which the
endowment's owner chooses knowing it. `KARMA` and `CLAN_WAR` Writs are
funded by players and citizen houses only (a house is an economic actor spending its own budget, 10 §7, exactly as a
player does). On those, a ring's own contributions come back to it minus `WritFeePermille` and karma's retail costs: farming
moves money between colluders and burns the fee. A ring that manufactures a real killer and collects honest players'
contributions to a Writ on them has created a real killer with retail's consequences; the Ledger pays for that death as for
any. AT-BL-14 shows a ring's net is never positive on its own money, strictly negative with a positive fee, and its take
from endowments never above the caps.

### 4.4 Dossier

Identity-level facts only (16 §3.1): name, race, level band (a 5-level band), clan, the contract's value and deadline, and
the age band of the newest intel the hunter's grants hold about the mark (`FRESH`/`RECENT`/`OLD`/none). Location: always
`UNKNOWN`. When the mark is offline, no traces are made (intel-service.md §3), so the trail goes cold by itself (16 §3.4).

### 4.5 Deaths and decisions

**Capture**, at the **death seam**, a core patch in `Playable.doDie` (`entity/actor/Playable.java:110-119`) right after the
synchronized block that makes the alive→dead transition (`setDead(true)`, under `synchronized (this)`): only a death that
happened gets there, once, whatever the death event before it did (`Playable.java:100-107` dispatches before the dead
check and may run twice). The seam increments the victim's death counter (in memory, and its value is part of the key, so
it is assigned once per real death) and makes a **captured fact** `Ledger.Death` (fact-journal.md §3.2: primitives into the
capture ring, no lock, no I/O, no allocation): death key `BL/death/<journal epoch>/<victim object id>/<death counter>`,
victim and killer snapshots, the killer's kind, and the context bits read at that moment:

- the acting player behind the killer: `asPlayer()`, the owner for summons, pets, traps, decoys and effect points
  (`entity/actor/Summon.java:884`, `instance/Trap.java:229`, `instance/Decoy.java:220`, `instance/EffectPoint.java:52`);
  for damage over time its caster (`dist/game/data/scripts/handlers/skill/effects/DamOverTime.java:83`);
- PvP zone or PvP instance (`entity/actor/Creature.java:428-435`), siege zone, Olympiad mode (`Player.java:10280`), duel
  (`:10286`; suicide skill effects call `doDie` directly even there,
  `dist/game/data/scripts/handlers/skill/effects/PhysicalDamage.java:112`), event registration or participation
  (`Player.java:14844-14872`), Kratei's Cube or the Underground Coliseum (`Player.java:5279-5306`), the killer holding a
  cursed weapon (`:12861`), the victim's activity (actor-identity.md §2.3), the territory
  (`data/xml/MapRegionData.java:412-416`);
- the Writ bases: the victim's karma and cursed weapon; both clans' mutual war;
- the retail anti-feed answer `AntiFeedManager.check(killer, victim)` (`managers/AntiFeedManager.java:68-117`: IP equality,
  and a death within its interval), read **here**, before Mobius records this death's time (`Player.java:5490`), so the
  interval is about the victim's previous death.

A ring full drops the capture and the journal records a `QUEUE_FULL` gap of the death family (fact-journal.md §3.3); §3.1's
deadline rule fails closed on it, and a hunter whose kill fell into a gap can file a claim that goes to admin review with
the gap and Mobius's own PvP and PK counters as evidence.

**Evidence and decisions.** The Ledger's projector writes the death as immutable evidence (`lc_ledger_death`, keyed by the
death key: a second capture of the same death is a no-op) and, in the same transaction, one immutable decision per contract
naming the victim as mark (`lc_ledger_decision`): `QUALIFIED`, or `EXCLUDED` with the first failing reason of: the killer
not an `ACTIVE` acceptor (Black) or not a registered hunter (Writ); a PvP zone or instance, Olympiad, duel, event, Kratei's
Cube or Coliseum; the killer holding a cursed weapon; the victim not `INTERACTIVE` (no offline farming); the mark in
cooldown; the Writ's basis false at the death; a condition false (16 §5: before the deadline; outside a siege zone; the
mark in a named clan; in a named territory, the "public execution"; for a multi-kill contract, after
`MultiKillCooldownHours`). Anti-abuse is **not** a decision: it is the payment's (§4.11), so evidence and decisions never
change.

**Claims and payment.** Only the decision on the **last required kill** starts a settlement (§3.1); earlier
qualifying kills of a multi-kill contract only count. A Writ's last kill creates its bounty payments at once. A Black
Contract's creates a claim; the killer claims in person at any Broker before `claim_deadline` (`ClaimHours`), the **delayed
Broker delivery** (16 §6), which creates the payments. A paid bounty goes to the hunter by service mail; unspent intel to the depositor.

### 4.6 Questioning, rumors, sightings

All disclosures are the Intel Service's (intel-service.md §4.2, §4.8):

| Page | Where | Purpose | Paid from | Price |
|---|---|---|---|---|
| question a witness | the witness NPC, in person | `LEDGER_QUESTIONING` (ceiling: the hunter's rank's) | the contract's `INTEL` custody, else the hunter | `QuestionAdena` × units, `FEE_QUESTIONING` sink |
| tavern rumors | an information NPC, in person | `LEDGER_RUMOR` | the hunter | `RumorAdena`, `FEE_QUESTIONING` sink |
| buy a sighting | a Broker, in person | `LEDGER_INFORMANT` | the contract's `INTEL` custody | the offer's price ≤ the contract's `max_sighting_price`: a `SIGHTING` payment to the informant |

**Clan-hall stewards** (16 §3.3) answer only about the membership and activity (age band) of the clan owning their hall,
never about places; they refuse a hunter whose mark belongs to that clan unless the hunter's clan is at mutual war with it;
they are never bribable. Crown Law politics may scale willingness once specified (a factor of 1 until then).

**Informants** (16 §3.5): a character who sighted a mark (the Intel Service's observation, `canWitness` held for the
consecutive samples) submits it in person at a Broker with an asking price; the Intel Service records it as an `INFORMANT`
trace only if the informant shares no account or principal with the mark. The offer (`lc_ledger_offer`) dies with its
trace. It can be bought only for a contract on that mark, by an `ACTIVE` acceptor, at a price ≤ the contract's cap; the
**payment** is checked (§4.11) with the informant as beneficiary against the buying hunter, the depositor and the mark, so
a hunter's own account cannot sell to them, and a linked one is held. Equivalent sightings are deduplicated by the Intel
Service (one item per trace).

### 4.7 Counter-intelligence

- **Intuition**: `lc_ledger_inquiry` counts the Ledger's disclosure requests about a mark per `InquiryWindowMinutes`. When a
  window's count reaches `IntuitionThreshold`, with the keyed probability `IntuitionChance` (`rng("ledger.intuition", mark,
  window)`), an `INTERACTIVE` mark receives once per window `ExShowScreenMessage` "You feel watched."
  (`network/serverpackets/ExShowScreenMessage.java:67`). Never a proximity alarm, never a count.
- **Clan intelligence**: a castle-owning clan sees per day the number of Ledger questionings at witnesses in its territory.
- **Bribes**: the mark, in person at a bribable witness, pays `BribeBaseAdena` × the witness's sensitivity × (1 + PK count / 10)
  (`FEE_BRIBE`); the Intel Service suppresses that witness for that subject (`intel.suppress`, intel-service.md §4.8).
- **False trails**: the mark pays a Broker `FalseTrailAdena` (`FEE_BROKER`) to plant a rumor in a territory of their choosing
  (`intel.plantRumor`): a `RUMOR` trace, disclosed only as a rumor, alike to the real rumors.
- **Trail manipulation** needs no mechanism: witnesses see what the mark does.

### 4.8 Aliases and calling cards

A hunter may register one nom de guerre at a Broker (`AliasAdena`, `FEE_ALIAS`). The alias never enters the world: Mobius's
visible-name override is reset by transformations (`entity/actor/transform/Transform.java:222-230`) and many channels send
the real name anyway, so the alias lives only in Ledger pages, calling cards and history. Ordinary combat shows the
attacker as Mobius shows them (`network/serverpackets/CharInfo.java:137`, the visible name).

Calling-card modes, chosen at acceptance: `AT_KILL` — at a qualifying death the victim is told "A black feather was left
beside your body." with the alias; `DELAYED` — the Broker delivers the card when the kill is claimed, without its time or
place; `NONE`.

### 4.9 Target victory and the reverse investigation

When a mark kills a hunter who holds an `ACTIVE` acceptance on a **Black Contract** on that mark (a death decided under
§4.5's context exclusions, with the hunter as victim and the mark as killer), the acceptance is `FAILED` and the mark
receives by service mail a **Torn Black Seal**: non-tradable, valueless, issued from `LEDGER_SEAL` (asset-service.md
revision 9), one per (contract, hunter). Its evidence is `lc_ledger_seal`, keyed by the item's Asset lifecycle id, never
item variables (Mobius deletes them on drop, `entity/item/instance/Item.java:1763`, `entity/item/inventory/Inventory.java:795-814`).
At issue the Ledger records, through the Intel Service, the seal's identity evidence (`PATRON_CLAN`, `PATRON_NAME`, or
`OBSCURED` under sealed identity), bound to that seal (intel-service.md §4.8).

The seal's holder opens a reverse case in person at a Magistrate within `ReverseCaseHours`; each step is a `LEDGER_REVERSE`
disclosure:

| Step | Reveals | Price |
|---|---|---|
| 1 | value band and origin town (the Broker network) | free |
| 2 | the patron's clan, "no clan", or "obscured" | `ReverseStep2Adena`, `FEE_QUESTIONING` |
| 3 | the patron's name, or "obscured" | `ReverseStep3Adena`, `FEE_QUESTIONING` |

A case starts at step 0 and moves one step at a time; the failed hunter is no step (the mark fought them).

### 4.10 The alias intelligence market

The only certified alias link is a **direct-kill certificate**: when a hunter chose `AT_KILL`, the
server itself certifies to the victim, at the qualifying death, that the alias is the character who killed them (whatever
the victim did or did not see: an `AT_KILL` card is a deliberate choice to burn secrecy towards the victim, 16 §6). The
Ledger records `ALIAS_LINK` identity evidence (alias → killer), the victim its witness, and the victim's Ledger page shows
it. The victim may sell it at a Broker in person (`AliasLinkAdena` per buyer, an
`ALIAS_LINK` payment to the victim, checked under §4.11 against the buyer and the alias's owner); buyers receive it at
`CONFIRMED`. Its first sale sets `exposed_at`. Nothing else creates `ALIAS_LINK` evidence; gossip is never certified.

### 4.11 Payments and anti-abuse

Every payment is checked when it is created, against its beneficiary and its counterparties (for a bounty: hunter, mark,
depositor or funders; for a sighting: informant, buying hunter, depositor, mark; for an alias link: seller, buyer, alias
owner):

| Signal | Effect |
|---|---|
| beneficiary and a counterparty share an account or a principal | **block** |
| the mark or a counterparty is an NPC fake or a `TEST` profile actor (actor-identity.md §3) | block |
| the pair (beneficiary, counterparty) was paid by the Ledger `PairSettleMax` times in `PairWindowDays` | block |
| same party or clan at the death or within `LinkWindowHours` before it | hold |
| a player-to-player trade between them within `LinkWindowHours` (`ON_PLAYER_TRADE_COMPLETE`, 03 §4.4) | hold |
| the same client IP at the death or at the contract's opening (a salted hash of `GameClient.getIp`, `network/GameClient.java:188`, kept only in the payment's evidence) | hold |
| retail anti-feed answered false at the death (§4.5; it is off by default, `dist/game/config/PVP.ini:35-50`) | hold |

A **block** pays nothing: the money stays in, or returns to, its custody, and the contract continues (§3.1). A **hold**
keeps it in custody; beneficiary and counterparties are told a payment is under review. An admin releases or returns it
with a reason. With no decision by `review_until` (`HoldReviewHours`): a **player-funded** payment is released (a hold
delays, it does not confiscate), an **externally funded** share is returned to its funders. Nothing is ever refunded by
minting. The published guarantee is bounded disclosure and economically unattractive farming, not identification of who
controls an account (consensus §6.3).

### 4.12 Publication and history

Two publication types, with field rules the publication registry gains for them (intel-service.md §4.7, revision 6):

| Type | Fields (rule) | Delay |
|---|---|---|
| `ledger_wanted` | mark (`LEDGER_WRIT_MARK`: a character only while an open Writ names them), open Writ total (`COUNT`), cause, day | none: Writs are public (16 §2.1) and the row has no place |
| `ledger_legend` | mark (`LEDGER_LEGEND_MARK`: the mark of a terminal Black Contract promoted below), alias (`LEDGER_ALIAS`), value band, outcome, territory (`AREA_TERRITORY`), day | `PublicationTacticalDelayMinutes` |

A Black Contract is promoted only after it is terminal and only if its principal ≥ `LegendValueAdena` or a hunter failed
on it. An alias's owner, a depositor, conditions and informants are **never** published, exposed or not.

## 5. Facts

Captured: `Ledger.Death` (§4.5). Outbox, in the transaction of their change: `Ledger.ContractOpened` (kind, cause, mark;
actor `SYSTEM` with the depositor in `lc_fact_expiring`, §2.2), `Ledger.Contributed`, `Ledger.Accepted`, `Ledger.Abandoned`,
`Ledger.Decided` (death key, contract, outcome, reason), `Ledger.Claimed`, `Ledger.PaymentCreated`, `Ledger.PaymentResolved`
(state, reason), `Ledger.Settled`, `Ledger.Expired`, `Ledger.Lapsed`, `Ledger.Cancelled`, `Ledger.Review`,
`Ledger.SealIssued`, `Ledger.ReverseStep`, `Ledger.AliasRegistered`, `Ledger.AliasRetired`, `Ledger.AliasExposed` (alias
only), `Ledger.RankChanged`, `Ledger.Bribe`, `Ledger.FalseTrail`, `Ledger.Intuition`. Consumed: deaths,
`ON_CURSED_WEAPON_ACQUIRE`, karma and war changes (as triggers for re-reading state), trades.

## 6. Invariants

| Id | Invariant | Checked |
|---|---|---|
| BL-INV01 | the Ledger changes no combat value and calls no mechanics API | static scan; AT-BL-09 |
| BL-INV02 | no coordinate or evidence reaches a Ledger page or table | Intel's types; AT-BL-10 |
| BL-INV03 | a death is evidence once; each contract decides it once; a payment resolves once | keys and triggers; AT-BL-00, AT-BL-04 |
| BL-INV04 | Ledger money moves only between custodies, holders and sinks | Asset postings; AT-BL-11 |
| BL-INV05 | depositors, alias owners and informants reach players only by §4.9 and §4.10, and their mappings are gone by §2.2's times | static scan; expiry; AT-BL-10 |

## 7. Failure model

| Fault | End state |
|---|---|
| a death event dispatched twice, or a death captured twice | one evidence row, one decision per contract |
| a death dropped by a full ring | a gap; deadlines fail closed to `REVIEW`; the hunter's claim goes to admin review |
| crash during a payment, claim or settlement | the Asset Service's; keys written once |
| the mark logs out | no new traces; the contract runs to its deadline |
| a hunter deleted while `ACTIVE` | acceptance `VOID`; their claimable kill lapses |
| a depositor or funder deleted | returns abort with `RECEIVER_GONE` (asset-service.md §2.1); the contract goes to `REVIEW` |
| Intel Service down | no questioning, sightings or rumors; contracts and deaths continue |

## 8. Configuration

`config/Custom/LivingChronicle/Ledger.ini`. Nothing here changes a game mechanic.

| Key | Range | Default | truth / dev / sim / hobby / live | Test |
|---|---|---|---|---|
| `MinContractAdena` | 10000–100000000 | 1000000 | as default everywhere | AT-BL-02 |
| `NewbieLevel` | 1–85 | 40 | 40 everywhere | AT-BL-02 |
| `LevelGapMax` | 5–85 | 20 | 20 everywhere | AT-BL-02 |
| `MaxOpenOnMark`, `PairWindowDays`, `MaxPairContracts`, `ReciprocalHours` | 1–20, 1–90, 1–10, 1–168 | 5, 7, 3, 24 | as default everywhere | AT-BL-02 |
| `ContractMinHours`, `ContractMaxHours` | 1–720 | 6, 168 | as default everywhere | AT-BL-01 |
| `ClaimHours` | 1–168 | 24 | 24 everywhere | AT-BL-04 |
| `MarkCooldownHours`, `MultiKillCooldownHours` | 0–720, ≥ `MarkCooldownHours` | 48, 48 | as default everywhere | AT-BL-02 |
| `MaxKills` | 1–5 | 3 | 3 everywhere | AT-BL-04 |
| `WritFeePermille`, `EndowmentCapAdena` | 0–200, 0–1000000000 | 20, 50000000 | as default everywhere | AT-BL-14 |
| `SealedIdentityAdena`, `AliasAdena`, `AliasLinkAdena`, `FalseTrailAdena`, `RumorAdena`, `QuestionAdena` | 0–100000000 | 5000000, 1000000, 2000000, 500000, 20000, 50000 | as default everywhere | AT-BL-11 |
| `BribeBaseAdena` | 0–100000000 | 200000 | as default everywhere | AT-BL-07 |
| `ReverseCaseHours`, `ReverseStep2Adena`, `ReverseStep3Adena` | 1–168, 0–1000000000, 0–1000000000 | 24, 1000000, 10000000 | as default everywhere | AT-BL-08 |
| `InquiryWindowMinutes`, `IntuitionThreshold`, `IntuitionChance` | 10–1440, 1–50, 0–1 | 60, 3, 0.5 | as default everywhere | AT-BL-07 |
| `LinkWindowHours`, `PairSettleMax`, `HoldReviewHours`, `PaymentAuditDays`, `AliasIdleDays` | 1–720, 1–20, 1–720, 1–365, 1–365 | 72, 3, 72, 30, 90 | as default everywhere | AT-BL-06 |
| `HighValueAdena`, `LegendValueAdena` | 1000000–10000000000 | 50000000, 50000000 | as default everywhere | AT-BL-12 |

## 9. Interface

Magistrate pages: register, open and fund Writs, take a Writ, wanted board, reverse case. Broker pages: register, alias
(register, retire), open a Black Contract, accept, claim, sighting offers, alias links, false trails. Witness NPC pages:
questioning, and bribing for a mark. Codex → Ledger tab (registered hunters, depositors, Writ marks, seal holders): dossiers,
granted intel with confidence and age band, offers, payments under review, the reverse case file, rank. Admin:
`lc why ledger <id>`, `lc ledger payment release|return <id> <reason>`, `lc ledger review <contract> <decision> <reason>`.

## 10. Requirements

| Id | Requirement | Proven by |
|---|---|---|
| BL-R01 | Contracts MUST move only by §3.1 with its row guards, every transition a durable fact in the transaction of its movements; deadlines MUST fail closed on overlapping death gaps. | AT-BL-00, AT-BL-01 |
| BL-R02 | A Black Contract MUST be refused for protected or saturated marks, linked depositors, repeated pairs below the diminishing threshold and reciprocal contracts; a hunter MUST be refused outside rank, level gap, linkage and capacity; a mark in cooldown MUST qualify for no contract. | AT-BL-02 |
| BL-R03 | A Writ MUST record its basis, qualify a death only while the basis held at it (read synchronously), open automatically on cursed-weapon acquisition, and lapse when its basis ends. | AT-BL-03 |
| BL-R04 | A death MUST be captured once at the alive→dead transition without blocking, recorded as immutable evidence, and decided once per contract, excluded in the listed contexts and conditions. | AT-BL-04 |
| BL-R05 | Every disclosure MUST go through the Intel Service with the Ledger's purposes, ranks, prices and caps; sightings MUST be server-verified and paid only within the contract's cap. | AT-BL-05 |
| BL-R06 | Every payment MUST be checked against its beneficiary and counterparties, blocked or held per §4.11, resolved once, released or returned by default by its funding, and never refunded by minting. | AT-BL-06 |
| BL-R07 | Intuition, bribes and false trails MUST work as specified, through the Intel Service. | AT-BL-07 |
| BL-R08 | A failed hunter's death MUST give the mark one seal, and the reverse case MUST reveal exactly its steps in order, honouring sealed identity. | AT-BL-08 |
| BL-R09 | The Ledger MUST grant nothing in combat. | AT-BL-09 |
| BL-R10 | No coordinate MUST reach the Ledger; depositors, alias owners and informants MUST reach players only by the reverse case and certified alias links, and their mappings MUST expire as specified. | AT-BL-10 |
| BL-R11 | Money MUST move only through the Asset Service as transfers and sinks, with zero minting. | AT-BL-11 |
| BL-R12 | Publication MUST be only of the Ledger's two types with their field rules, never naming alias owners, depositors, conditions or informants. | AT-BL-12 |
| BL-R13 | Hunts MUST succeed through evidence, with target survival and hunt duration inside declared bands, under full out-of-band sharing. | AT-BL-13 |
| BL-R14 | A ring killing a mark it manufactured MUST never gain on its own money (strictly lose with a positive Writ fee), and MUST take from external money at most the per-weapon-life and per-mark caps. | AT-BL-14 |

| Test | Kind | What it does |
|---|---|---|
| AT-BL-00 | integration | the migration applies; triggers refuse every transition outside §3.1 for each kind, and each guarded transition without its row (a decision, a claim, a paid payment, no acceptance), a decision or evidence changed, a payment resolved twice, a seal twice or for a non-failed hunter, a reverse case inserted past step 0 or skipping a step, a second alias per character or name, a falling rank |
| AT-BL-01 | integration | every transition through the operations, with movements and facts; a deadline with and without an overlapping gap |
| AT-BL-02 | integration | each refusal at its boundary (level 39/40, gap 20/21, the open-contract cap, the k-th pair contract at 2^k, reciprocal at 23/25 hours); deaths during a mark's cooldown excluded for every contract; two deaths decided in log order |
| AT-BL-03 | integration | Writs per cause: a death while the basis held settles, a death after it ended does not; a war ending between the death and the event; a cursed-weapon acquisition opens a Writ, its drop or expiry lapses it |
| AT-BL-04 | integration, fault | a blocked final bounty reopening the contract with `kills_done` − 1 and a replacement kill settling; a lapse waiting for a queued lawful death; the death event fired twice and the seam reached once; a death refused by retail (an already-dead victim) captures nothing; kills by summon, trap, decoy, DoT and self; each excluded context including a suicide skill in a duel and Olympiad; each condition at its boundary; a full ring: a gap, no decision, a claim to review |
| AT-BL-05 | integration | questioning per rank within its ceiling; prices from the intel custody then the hunter; a sighting price above the cap refused; an informant sharing the buyer's account blocked, linked held; equivalent sightings: one item, two grants |
| AT-BL-06 | integration | each signal per payment kind; a mixed bounty as two legs, the player one released and the external one returned at timeout, the contract settling and the external share back to its funders; an all-external bounty returned, the contract reopening with its external share at 0, or expiring when nothing is left; a held sighting not holding the contract; release by admin, return by admin, timeout release (player-funded) and timeout return (external); no source posting on any path |
| AT-BL-07 | integration | intuition at the threshold with the keyed chance, once per window, never offline; a bribed witness disclosing nothing until the suppression ends; lawful witnesses refusing bribes; a planted rumor only as a rumor |
| AT-BL-08 | integration | a hunter killed by the mark: one seal; steps 1-3 in order, "obscured" with sealed identity; after `ReverseCaseHours` nothing |
| AT-BL-09 | unit | the static scan: no Ledger code calls a mechanics API or reads a combat stat |
| AT-BL-10 | unit, integration | the static scan of readers of the patron, funder and alias-owner columns; no Ledger table has a coordinate; each mapping deleted at its §2.2 time, also from a restored backup; secret operations' facts carry `SYSTEM` with the actor only in `lc_fact_expiring` |
| AT-BL-11 | integration | every flow: postings balance; sinks at their codes; no source except `LEDGER_SEAL` |
| AT-BL-12 | integration | rows of both types; an exposed alias's owner never published; a Writ mark not publishable once no open Writ names them; another field refused |
| AT-BL-13 | sim | the full-sharing citizen population: survival and hunt duration inside the Truth Report's declared bands |
| AT-BL-14 | sim | rings of 2 to 20 linked and unlinked accounts manufacturing karma and clan-war marks and killing them for Writs funded by the ring: the ring's net Adena never positive, strictly negative when `WritFeePermille` > 0; external money refused on any Writ but `CURSED_WEAPON`; a ring passing one cursed weapon between members by deaths and pickups: its endowment take never above the per-weapon-life and per-window caps |

## 11. Upstream constraints

1. **The death event fires before the dead check** (`Playable.java:100-107`, check at :110-119) and names the raw killer
   (`OnCreatureDeath.java:32-43`). → the death seam after the transition; `asPlayer()` resolution.
2. **Olympiad and duel damage normally avoid `doDie`** (`PlayerStatus.java:270-284`, `:300-306`), but suicide skill effects
   reach it (`PhysicalDamage.java:112`). → explicit context exclusions.
3. **Cursed weapon acquisition dispatches nothing** (`CursedWeapon.java:369-393`). → the `ON_CURSED_WEAPON_ACQUIRE` seam.
4. **Clan war is one-sided per clan, and war events precede the change** (`Clan.java:146-147`, `:1638-1648`;
   `ClanTable.java:405-408`). → mutual war read at the death; lapse by re-reading state.
5. **AntiFeed compares IPs and the last death time, off by default** (`AntiFeedManager.java:68-117`, `PVP.ini:35-50`), and
   `setLastDeathTime` runs during `doDie` (`Player.java:5490`). → read at the seam, before it; one signal among the Ledger's.
6. **Retail newbie protection is Protection Blessing only** (`PlayableAI.java:53-66`). → the Ledger's `NewbieLevel`.
7. **Item variables do not survive a drop** (`Item.java:1763`, `Inventory.java:795-814`). → seal evidence in a Ledger table.
8. **Visible names are overridden by transformations and leak elsewhere** (`Transform.java:222-230`; `CharInfo.java:137`).
   → aliases never in the world; an alias link is the server's direct-kill certificate (§4.10), not a claim of what the
   victim saw.

## 12. Review record

- Amendment round 1 (Codex): revision 7 **SIGN-OFF**.
- Revision 7 (after the revision-6 sign-off): the Amnesty predicate at a `KARMA` opening (§4.3), accepted in
  aden-remembers.md's review; to be reviewed on its own.

- Round 6 (Codex), **SIGN-OFF**. The bounded subsidy and the operator-audit limit are stated limits. Remaining are named
  implementation obligations: the weapon-life identity and atomic cap accounting, the death and cursed-weapon seams,
  confidential-fact production, the Intel authorisation rules, the seal item's restrictions, purge and restore jobs, and
  the runtime and simulation acceptance tests.
- Round 5 (Codex), NO SIGN-OFF: two schema gaps, a payment resolving without its party row and a party expiry able to
  skip the audit interval. Revision 6: resolution requires the party; expiry at least a day ahead (the configured
  `PaymentAuditDays` applied by the operation) and no deletion before it.
- Round 4 (Codex), NO SIGN-OFF: open were the subsidy bound counted per activation (one drop can activate many times), the
  final kill not bound to the settlement, payment expiry able to erase a leg's identity before its settlement ends, and
  legs not bound to their amount and recipient. Revision 5: the cap per weapon life and BL-R14 scoped to what is
  guaranteed; count ordinals and settlement on the last counted death; durable, identity-free payment rows with the
  beneficiary and evidence in an expiring party table that cannot expire before the contract ends; each leg bound to its
  share's amount and the settling death's killer.
- Round 3 (Codex), NO SIGN-OFF: open were cursed-weapon Writs still farmable by picking the weapon up, counts and
  attempts not bound to consumed decisions (and blocked payments' expiry erasing the memory), incomplete or late bounty
  legs, returned external money leaving the principal unchanged, alias fees outside the confidential rule, alias
  retirement undone by a restore, and §11 still stating the old alias rule. Revision 4: the cursed-weapon guarantee stated
  as a bounded subsidy with per-activation caps; durable count rows consumed and voided, never reused; complete, unique
  legs created only while settling; principal split into player and external shares, a returned share zeroed; confidential
  operations (asset-service.md) covering every secret Ledger operation; the restore limit stated with its remedy; §11
  aligned.
- Round 2 (Codex), NO SIGN-OFF: most fixed; open were Writ farming by three unlinked accounts, the Asset Service's and
  the Intel Service's own facts still naming depositors and bribers permanently, guards satisfied by stale decisions,
  mixed-funded payments with one state, reopening after a blocked final kill with no kill-count path, a Writ lapsing
  before a queued lawful death is decided, and an alias certificate claiming observation. Revision 3: external money only
  for cursed-weapon Writs, which cannot be manufactured; confidential custodies and suppression facts keep identities in
  the journal's expiring data (asset-service.md, intel-service.md); `settling_death` with guards about it and `kills_done`
  decremented on a block; one payment per funding kind; lapse decided like a deadline; a direct-kill certificate.
- Round 1 (Codex, a different model family), NO SIGN-OFF, 18 findings: a reserved column name; a death key that did not
  deduplicate; capture at a pre-death notification; SQL in the combat thread against the journal's capture contract; a
  block that had to rewrite immutable evidence; informant purchases able to drain a budget to a hunter's alt; holds keyed by
  contract; Writ farming released by default; cooldown checked only at opening; rumors and identity evidence not fitting
  Intel's items, budgets and storage; secrecy promises against permanent records; unregistered expiring data;
  guard-free transitions in the schema; clan-war Writs without a recorded basis; contradictory publication policy; and four
  inaccurate upstream claims. Revision 2: `cooldown_until`; the death seam after the alive→dead transition with a captured
  fact and a gap rule; immutable evidence and decisions apart from payments; payments with their own checks, holds and
  defaults by funding; sighting price caps and buyer–seller checks; Writ bounds with an economic test; cooldown at decision
  time across contracts; a confidentiality statement and expiring mappings, secret actors in `lc_fact_expiring`; guarded
  transitions; recorded Writ bases; publication field rules; corrected upstream claims; Intel revision 6 reworked (§12 there).
