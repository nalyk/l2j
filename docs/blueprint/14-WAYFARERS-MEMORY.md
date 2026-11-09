# 14 — Wayfarer's Memory

> The game remembers what your character actually knew and was doing.

Aden Remembers answers "what happened to the world?". Wayfarer's Memory answers "what happened to me, and what do I know?".
Wrong solution: telling the player the globally optimal next step.

## 1. Login recap

```text
Last journey
You were preparing a Blue Wolf Heavy Set.
Missing: 18 Crafted Leather · 7 Mold Hardener · BW Helmet recipe
You last observed Mold Hardener sold by IronDwarf for 78,000 Adena (3 days ago).
You were hunting near the Cemetery before returning to Giran.
Note: "Ask Kira about BW helmet recipe."
```
No item generated, no optimal answer. Shown via `TutorialShowHtml` on login; dismissable; also in the Journal tab.

## 2. Personal knowledge graph

Entities: NPCs, monsters, regions, materials, recipes, equipment, raid bosses, shops, players, clans, projects, quests,
contracts, rumors, notable deaths/battles.
Relations: `observed ITEM sold_by PLAYER at TOWN at TIME for PRICE`, `obtained ITEM from MONSTER at REGION`,
`was_told FACT by PLAYER`, `met PLAYER at REGION`, `needs ITEM for PROJECT`, `witnessed BOSS at REGION`,
`signed CONTRACT with PLAYER`, `hunted_with PLAYER`.

Everything is captured from the character's own experience (its own packets' server-side equivalents: what it saw in a
store window, what it looted, who it partied with) — never from global state.

## 3. Provenance (the defining rule)

`KNOWN` (personally witnessed/experienced) · `TOLD` (shared by another player, with who/when) · `RUMORED` (NPC/world rumor) ·
internal: `INFERRED`, `HISTORICAL`, `STALE`, `CONTRADICTED`. The UI never merges rumor into fact. Observations are time-stamped
and age into STALE; if Aden Remembers moved supply, old knowledge stays old.

```text
Mold Hardener
  Known:   Timak Orc Archer, 6 days ago
  Told by Vasya: Giant's Cave, 2 days ago
  Rumor:   merchants report increased supply near Oren, today
```

## 4. No global wiki

Forbidden: `.bestfarm`, "best spot now", "optimal route". Allowed: "where did I last get SOP?", "who did I see selling Mold
Hardener?", "which NPC gave that quest?", "what was I crafting?". (Players will use external wikis — that is fine; the
server does not become one.)

## 5. Projects and notes

Players pin projects (target item → requirements auto-derived from recipes they know or have seen), add notes, set reminders
("check IronDwarf's store"). Contracts from PACT appear in the project they serve.

## 6. Shareable journal pages

A player may share a subset of their knowledge (e.g. "mobs I personally used for D-grade spoil") as a page another player can
import as `TOLD` with provenance "Shared by VeteranName, observations X–Y". Mentorship becomes easy without replacing discovery.
Shared content is just content: it can be forwarded, which is fine (01 §2.5).

## 7. Personal history

A private autobiography promoted from meaningful facts: places first visited, bosses witnessed, old clans, enemies, important
contracts, significant crafted items, memorable PvP, recurring companions, sieges attended, economic milestones.
Optionally public on the website if the player opts in.

## 8. Ledger boundary

Legitimately learned Black Ledger intel may be remembered with its confidence class, provenance and expiry. The journal
never turns a stale trail into permanent tracking: when the Intel Service expires an intel item, the journal keeps only
"you once had a lead (expired)".

## 9. Retention

Short-lived observations → summarised (400 identical kills become "obtained X from family Y repeatedly") → durable notes,
milestones and pinned facts. Default raw retention 30 days *(tunable)*.

## 10. Data

```text
mem_entity(id, kind, ref_id, label_key)
mem_observation(id, char_id, entity_id, relation, other_entity_id, value JSON, provenance, source_char_id NULL, observed_at, expires_at, tier)
mem_project(id, char_id, target_item_id, state, created_at)
mem_project_req(project_id, item_id, qty_needed, qty_have_snapshot, source_hint_obs_id NULL)
mem_note(id, char_id, text, pinned, remind_at NULL)
mem_shared_page(id, author_char_id, title, created_at)  /  mem_shared_fact(page_id, observation_id)
mem_session_summary(char_id, session_end, summary JSON)
mem_milestone(char_id, kind, event_ref, at, public BOOL)
```

## 11. UI (Codex → Journal tab; Wayfarer's Journal item)

Flows: recap · search ("who sold…", "where did I get…") · projects · notes/reminders · share page · import page ·
personal history. Contextual "remember this" action on store windows and NPC dialogues.

## 12. Acceptance

`MEM-*`: accurate personal context after days away; KNOWN/TOLD/RUMORED never merged; stale handling; no global-optimum query
exists; shared pages keep provenance; repetitive noise summarised; expired Ledger intel never resurrected by rebuild.
