# 04 — Shared Architecture

Five mechanisms. Every module uses them; no module reimplements them.

```text
 Mobius authoritative systems ──(core seam: snapshots after validation)──► Fact Capture (bounded queue, O(1))
        │                                                                          │
        │ asset mutations                                                          ▼
        ▼                                                         ┌──────── Fact Journal ────────┐
  ASSET SERVICE ── one InnoDB tx: rows + contract state +          │ durable facts │ telemetry   │
  (escrow, ledger, outbox) ── postings + outbox facts ───────────►│ (financial,   │ buckets     │
                                                                   │  audit)       │ (aggregates)│
                                                                   └──────┬────────┴─────┬───────┘
                                                                          ▼              ▼
                                         projectors (deterministic reducers, checkpointed, idempotent)
                     ┌───────────────┬──────────────┬─────────────┬──────────────┬──────────────┐
                     ▼               ▼              ▼             ▼              ▼              ▼
              Aden Remembers     Signal        Wayfarer        PACT        Black Ledger     Chronicle
                     └───────────────┴──────► INTEL SERVICE ◄───┴──────────────┘              │
                                                     │                                        ▼
                                            CODEX UI / NPC pages                 publication tables ─► web, narrator
```

## 1. Fact Journal

### 1.1 Two kinds of facts, deliberately separate
- **Durable facts**: anything with financial, contractual, legal (Ledger), historical or audit meaning. One row per fact,
  immutable, written in the same transaction as the state change when the change goes through the Asset Service (outbox).
- **Telemetry buckets**: high-volume ecological signals (kills, yields, travel, presence) aggregated in-memory per
  `(region, window, family, actorClass)` and flushed per window. Never per-event rows.

Sizing sanity (arithmetic, not measurement): 800 actors × 1 event/s × 500 B = 69.12 M events, 34.56 GB/day raw. That is why
telemetry is bucketed and durable facts are only the meaningful ones. Storage budgets are set from measured rates.

### 1.2 Tables
```text
lc_fact(id BIGINT PK seq, stream VARCHAR, stream_seq BIGINT, type SMALLINT, schema_ver SMALLINT,
        occurred_at TIMESTAMP(3), logical_time BIGINT, actor_snapshot JSON, region_id SMALLINT, zone_id INT,
        sensitivity TINYINT, expires_at TIMESTAMP NULL, payload JSON, op_id BINARY(16) NULL)
        PARTITION BY RANGE (month)
lc_fact_precise(fact_id BIGINT PK, x INT, y INT, z INT, instance_id INT, expires_at TIMESTAMP)   -- restricted, TTL-purged
lc_telemetry_bucket(region_id, window_start, window_len, family, actor_class, kills, yield JSON, unique_actors, ...)
lc_projector_checkpoint(projector VARCHAR PK, stream VARCHAR, last_seq BIGINT, reducer_ver SMALLINT)
```
- Exact coordinates never live in `lc_fact`; they live in `lc_fact_precise` with a TTL and are readable only by the Intel
  Service and forensic tooling.
- Fact types are a sealed Java hierarchy of records; `schema_ver` bumps are additive with explicit upcasters.

### 1.3 Projectors and replay contract
- Projectors are deterministic reducers: versioned immutable facts in, derived state out, stable processing order
  (stream, stream_seq), logical clock, recorded random outcomes. Checkpointed and idempotent.
- Replay rebuilds derived state into isolated stores. Replay never re-applies actuators or transfers (each carries an
  idempotent application id). We do not promise full live-Mobius replay.
- If the durable store is unavailable: new financial operations pause; the Director freezes with explicit gap/watermark
  handling. Inputs are never silently dropped. Restore and replay are benchmarked against retained volume.

## 2. Actor Identity

Every fact carries an immutable snapshot of:

| Dimension | Values | Changes |
|---|---|---|
| `origin` (server-assigned, immutable) | `HUMAN`, `SYNTHETIC_CITIZEN`, `NPC_FAKE`, `NPC`, `SYSTEM` | never |
| `account` | authenticated account id | never per character |
| `activity` | `INTERACTIVE`, `OFFLINE_TRADE`, `OFFLINE_PLAY`(disabled), `DISCONNECTED` | recorded transitions only |
| `sim_profile` | resident / scenario-adversary / test | admin |
| `principal` | synthetic economic principal id (citizens) or account (humans) | admin |

Policy tables (not mechanics) decide participation: e.g. `NPC_FAKE` never pays or receives Ledger contracts; offline traders
count as market supply, never as presence; Signal reports human/synthetic/mixed separately. History supports a humans-only filter.

## 3. Asset Service

The single path for moving or holding Living Chronicle assets (escrow, settlements, bounties, fees, caravan cargo, crown
treasury commitments).

### 3.1 API (shape, not final names)
```java
try (AssetTx tx = assets.begin(OpId.of(contractId, "settle", attempt))) {
    tx.lockHolders(Holder.player(a), Holder.player(b), Holder.escrow(custody));   // global lock order: by holder key
    tx.moveItem(objectId, Holder.escrow(custody), ItemLocation.ESCROW);
    tx.moveAdena(Holder.player(a), Holder.escrow(custody), amount);
    tx.contractVersion(contractId, expectedVersion);                              // optimistic check
    tx.settlementKey(contractId, clauseId);                                       // unique; second attempt = no-op
    tx.fact(new EscrowFunded(...));                                               // outbox, same transaction
    tx.commit();          // one connection, one InnoDB transaction
}                         // after commit: reconcile in-memory inventories, then publish to caches and clients
```
### 3.2 Rules
- One connection and one transaction for: core item rows, item metadata, contract state + version, ledger postings,
  settlement uniqueness key, outbox facts.
- The Asset Service owns every competing mutation of an asset it holds, including ordinary core saves, so no independent
  writer can overwrite committed state.
- Global lock order by holder key; optimistic versions on contracts; operation ids on everything.
- Uncertain commit (connection lost after commit sent) is resolved by looking up the operation id before any retry.
- After commit: affected in-memory inventories are reconciled from committed rows **before** mutation locks are released or
  affected reconnect actions are accepted. Publication to clients happens only after commit.
- Restart: committed operations are recovered before any affected inventory action is accepted. Overflow deliveries
  (full inventory) stay in durable escrow with a claim path.
- `LazyItemsUpdate = False` is an asserted invariant at startup.

### 3.3 Escrow custody
- Escrowed items stay canonical rows in `items` with `loc = ESCROW` and a custody reference
  (`lc_custody(custody_id PK, kind, contract_id, created_at, state)`).
- `DatabaseIdManager` cleanup recognises custody owners; used-ID extraction reserves them; ids are never released during a
  custody transfer. Inventory restore never loads `ESCROW` rows.
- Item provenance uses a lifecycle id (`lc_item_lifecycle`) because game object ids can be reused after destruction.

### 3.4 Ledger
```text
lc_ledger_posting(id, op_id, account_kind, account_id, asset_kind {ADENA|ITEM}, item_id, object_id NULL, qty, direction, fact_id)
```
Double entry per asset kind. Sources (drops, quests, NPC sales), sinks (fees, broker cut, bribes) and transforms (craft) are
explicit posting kinds, so conservation is checkable per item id and per currency.

### 3.5 Fault model (tested)
Kill/abort before and after every SQL mutation, commit, cache application and client acknowledgement; ambiguous commit;
DB restart mid-transaction; GameServer restart in every contract state; full inventory on settlement.

### 3.6 Production invariants (continuous)
Per item id and per currency: Σ sources − Σ sinks ± transforms = Σ holdings (inventory, warehouse, clan WH, mail, escrow,
ground). Every object id in exactly one location. Every `ESCROW` row has a live custody. Every custody has a contract in a
state that allows holding. Violation → freeze the owning module, alarm, keep evidence.

## 4. Intel Service

Everything a player may *learn* about another actor or about hidden world state passes through here.

```text
Intel(kind, subject, precision {EXACT_INTERNAL|ZONE|REGION|TERRITORY|NONE}, confidence {CONFIRMED|WITNESS|INFERRED|RUMOR|STALE},
      provenance, audience, purpose, created_at, expires_at, shareable BOOL, publishable BOOL)
```
- Callers never get raw coordinates. The service computes the coarsened answer from authoritative state; there is no
  "get coordinates then round them in the UI" path.
- Disclosure budgets per (contract, target, witness, window) with diminishing returns that do not reset when another
  cooperating account or contract asks; equivalent sightings are deduplicated.
- Sighting eligibility uses the core `canWitness` predicate (03 §4.5); the disclosed clue stays coarse even when the private
  eligibility evidence is precise.
- Expiry applies to source payloads, caches, projections, backups and rebuilds. Non-sensitive financial audit is retained
  separately from expiring intel payloads.
- Publication (website, narrator, Gazette) consumes **allowlisted publication DTOs** from `lc_pub_*` tables, read with a
  separate DB user that cannot read anything else. Tests cover cross-module inference, sealed contracts, actor-filtered
  history and replay-after-expiry.

## 5. Codex of Aden (UI framework)

### 5.1 What players see
A leather-bound book opened with Alt+B (Community Board) or by using the *Wayfarer's Journal* item. Tabs:
**Journal** (Wayfarer), **Signal**, **Contracts** (PACT), **Ledger** (only for registered hunters / clients / marks),
**Chronicle**, **Gazette**, **Laws** (Crown Law of the current territory). Institutional ceremony (Notarius signing,
Magistrate writs, Broker contracts, witness questioning) happens on NPC pages, not in the book.

### 5.2 Mechanics
- One bypass prefix `_lc` → router → module page handlers. Pages are server-rendered from templates with localisation keys
  (EN/RO/RU); pagination respects the 3×16250 CB limit and the 17200 NPC HTML limit.
- Every state-changing action uses a **one-use confirmation token** bound to: character, terms hash, contract/version,
  deadline, and required NPC proximity where the interaction requires physical presence. Native `ConfirmDlg` + `DlgAnswer`
  for signatures.
- Notifications: system messages (custom ids in the `.dat` patch), `ExShowScreenMessage` for rare dramatic moments
  ("You feel watched."), `TutorialShowHtml` for the login recap.

### 5.3 Rules
- Every §31 interaction is specified as a concrete page/action flow in its module spec.
- No page reveals hidden state (pressure values, exact locations, Director plans).
- Native interface widgets are added only if human UX tests establish a need; then the widget and its proven toolchain
  (pinned manifests, no-op round trip, changed-window runtime test) become required dependencies.

## 6. Forensics: `lc why`

Admin CLI and in-game admin command. `lc why <fact|actuator|disclosure|settlement|rumor|history-entry>` prints the causal
chain: input facts, reducer version, thresholds crossed, bounds applied, reason code, actor snapshots. Also: `lc replay`,
`lc invariants`, `lc diff-config`. Every admin override writes an audit fact with before/after and reason.

## 7. Randomness and time

Living Chronicle code draws randomness only from keyed RNG streams (`rng(stream, key)`), and in production records any
random outcome that affects a durable fact. Test/sim profiles inject seeded streams and a controllable clock/scheduler.
Mobius combat RNG is untouched in production.

## 8. Failure degradation

| Failure | Behaviour |
|---|---|
| projector error | that projector stops with watermark; gameplay continues; catch-up later |
| Aden Remembers down | Director freezes; no actuator changes; world stays retail |
| Signal / Wayfarer down | ordinary party matching and play continue |
| Black Ledger down | PvP/PK continue; no payouts until consistency is verified |
| PACT settlement failure | assets stay in escrow; contract enters recoverable pending state; never best-effort double transfer |
| narrator down | templates render without LLM decoration |
| invariant violation | owning module frozen, alarm, evidence kept |

The custom layer is never a single point of failure for basic Lineage II.

## 9. Persistence and migrations

Mobius tables stay authoritative for Mobius concepts (inventory, clans, castles, levels). LC tables store facts, derived state,
contracts, memory, intel, history, referencing Mobius ids. Numbered migrations `V###__name.sql`, applied by the build; never
hand-edited in production. Pre-migration snapshot, migration tested in both directions against representative escrow data.

## 10. Configuration

`config/LivingChronicle.ini` (master switch + profile) and one file per module under `config/Custom/LivingChronicle/`.
Every module independently disableable; disabling restores stable retail behaviour and keeps data.
