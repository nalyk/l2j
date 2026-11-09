# 17 — Chronicle of Aden (public history and press)

Aden Remembers decides what became history. The Chronicle makes it visible: in the world, in the press, and on the web.
It is story, never a cheat dashboard.

## 1. Rule of publication

Everything public is built from **publication DTOs** (`lc_pub_*`), filled by projectors from durable facts that are marked
`publishable`, after privacy filtering by the Intel Service. The web process and the narrator read only these tables with a
separate DB user. Sealed contract terms, live locations, raw Director values and hidden hunter identities never enter them.
Tactical delay: anything location-related is published only after it can no longer be used (default ≥24 h for battle maps,
immediately for already-public facts like a castle owner change).

## 2. In the world

- **Monuments** — after a legendary siege, Territory War or hunt, a monument NPC (existing statue/stele model via `displayId`)
  appears in the relevant town with an inscription:
  ```text
  The Siege of 14 October · Defended by NightWatch · 317 defenders, 402 attackers · Castle held after 1h 43m
  ```
- **NPCs remember** — selected town NPCs gain lines referencing real history:
  *"RedSky once ruled these lands. Their banners haven't flown here since the Winter Rebellion."*
- **Bards** *(NEW v2)* — tavern bards recite short ballads about last week's events (narrator text from facts, template
  fallback). Ask a bard about a clan and hear its saga.
- **Town criers / heralds** *(NEW v2)* — proclaim new Crown Laws, castle ownership changes, public treaties, Writs on notorious
  outlaws, caravan departures. Rate-limited, local to the town.
- **Wanted boards** *(NEW v2)* — public Writs and bounty totals (16 §2).
- **Notice boards** — public contract registrations, caravan schedules, Notarius public registry.

## 3. The Aden Gazette *(NEW v2)*

- A daily issue (in-game day boundary aligned to `ON_DAILY_RESET`), sold by Newsboy NPCs for a token price (sink) as an item
  *Aden Gazette, issue #N* that opens the issue in the Codex; the same issue is on the website.
- Sections: The Realm (ownership, laws, treaties, wars), Markets (coarse: "coal is wanted in Giran", "B-grade weapons cheap in
  Oren"; never exact prices or volumes that act as a dashboard), Roads (caravans arrived/lost), Law & Disorder (public Writs,
  bounty records, completed public executions), Arena (Olympiad heroes, coliseum champions), Obituaries of Legends
  (legendary items destroyed or lost), Letters (player-submitted, moderated, signed with character name).
- Written from facts by templates; the narrator decorates prose; facts in every article link to their history entries.
  An article never states a fact that is not in the publication DTO it was given.

## 4. Item sagas

Where deliberately enabled for provenance-worthy items (S-grade and above, legendary drops, items with notable history):
```text
Crafted by BlackMamba (Giran, 3 Mar) · First owner: Slava · Recorded PvP kills while tracked: 283
Carried at the Siege of Aden (14 Oct) · Lost to Ghost outside Oren (2 Nov)
```
Uses the item lifecycle id (04 §3.3), not the reusable object id. Common items never become telemetry objects.

## 5. Website: chronicle of Aden

Server-rendered HTML + a small amount of vanilla JS/SVG; no SPA framework; read-only publication DB user.

| View | Content |
|---|---|
| Political map | territories, castles, fortresses, clan halls, current owners, active laws, coarse regional prosperity words ("thriving", "embattled") |
| Dynasties | castle reigns over time, clan heraldry (crest), longest reigns |
| Battles | sieges, Territory Wars, fortress sieges: participants, duration, outcome |
| **Saga replays** *(NEW v2)* | animated, delayed (≥24 h) reconstruction of public battles from facts: front lines by zone, ward captures, gate breaches, kills over time; no individual live positions |
| Crafters & items | crafting dynasties, famous items and their sagas |
| Treaties & contracts | public registrations, treaty forfeits paid |
| Outlaws & hunts | notorious PKs, bounty records, legendary hunts by alias |
| Economy | eras and shifts in words and coarse trends |
| Records | firsts, longest, largest |
| Gazette archive | every issue |
| Clan pages | history, wars, holdings, members who opted into public profiles |
| Character pages | opt-in personal history (14 §7) |
| Truth Report | 11 §5 |

Testers and later the public can follow Aden without logging in. The website must be interesting to follow and useless as
live tactical radar (acceptance test: a hunter given only the website cannot locate a mark better than chance + public facts).

## 6. Narrator boundary

- Input: one publication DTO + style + language. Output: text. No tools, no state access.
- Templates render first; LLM output replaces template prose only if a fact-check pass (every named entity, number and date
  in the output must exist in the DTO) succeeds. Otherwise the template stands.
- Cached per (DTO hash, language, style). Narrator down → templates only.

## 7. Data

```text
lc_pub_history(id, kind, title, period, region, summary_facts JSON, participants JSON, published_at)
lc_pub_gazette(issue_no, day, sections JSON, published_at)
lc_pub_monument(id, town_id, npc_spawn_ref, inscription JSON, event_id)
lc_pub_item_saga(lifecycle_id, entries JSON)
lc_pub_replay(event_id, frames_ref, published_at)
lc_pub_map_state(snapshot_at, territories JSON)
```

## 8. Acceptance

`CHR-*`: no sealed/secret/live-tactical data reaches any `lc_pub_*` table (property tests over all fact types); replay delay
enforced; narrator fact-check rejects any unsupported entity/number; monuments spawn idempotently; Gazette issue per day;
radar test fails for the website; localisation EN/RO/RU.
