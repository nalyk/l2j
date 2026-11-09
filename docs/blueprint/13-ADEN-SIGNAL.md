# 13 — Aden Signal

> Party Finder asks who advertised. Signal asks which people currently want compatible things.

Problem: the compatible person exists, but the game fails to introduce you. Wrong solution: make solo play strong enough that
nobody needs anyone.

## 1. What a Signal is

An opt-in, structured **intent**: objective (XP, spoil, quest, raid, PvP, Manor, crafting, material hunting, support-for-hire,
exploration, item acquisition, caravan escort, Territory War mercenary), class/role, level range, current region, preferred
regions, remaining play time, travel tolerance, languages, pace, join-existing-party, willing-to-lead, now/later, notes.

```text
Nalyk · Lv. 47 Shillien Elder · Intent: XP · Preferred: Cruma / nearby · Available: ~55 min · Travel: 10 min
Languages: RO/RU/EN · Pace: relaxed · Join existing party: yes
```

## 2. Features

- **Time is first-class**: 20–30 min, ~1 h, 2–3 h, until boss, evening, custom end. No punishment for leaving at the declared
  time; the goal is expectation alignment.
- **Latent party detection**: with participation enabled, Signal notices nearby compatible people who never opened party
  matching: *"A viable party may be formed nearby. 6 compatible players. Estimated rendezvous: 7 minutes. Common intent: XP / spoil."*
  Names revealed only after mutual interest.
- **Future intent**: *"Tomorrow 20:30–22:00, Cruma XP/spoil"* → at login or before the time: *"5 players aligned with your
  planned Cruma session."* Players still meet inside the world.
- **Demand signals**: aggregated, coarse, player-generated demand ("many are seeking spoilers around Oren") feeds Aden
  Remembers and the Gazette, never exact positions.
- **Support-class protection**: Signal never fills a missing buffer with an NPC. Do not make support classes unnecessary;
  make support players discoverable.
- **Synthetic candidates**: humans choose whether citizens are offered to them (default: offered, labelled nowhere in-world).
  Outcomes are reported human / synthetic / mixed separately.

## 3. Zero balance effect

Signal grants zero XP, Adena, items, buffs, teleportation, combat modifiers. It changes only the probability that compatible
people meet. Never reward Signal usage itself.

## 4. Privacy

Explicit opt-in; fine-grained visibility (clan only / alliance / everyone / nobody); region-level location at most, never
coordinates; offline traders, offline play and NPC fakes excluded; block/ignore respected; Black Ledger marks may hide from
Signal entirely without penalty. Location in Signal goes through the Intel Service with `precision ≤ REGION` and a short TTL.

## 5. Matching

Score = objective compatibility + level compatibility + role complementarity − travel cost + remaining-time overlap +
language overlap + pace match − social exclusions, constrained by party capacity. Do not optimise for meta compositions;
introduce viable humans. Soft input: repeated successful collaborators (from Wayfarer, opt-in).

## 6. Data

```text
signal_profile(char_id, opt_in, visibility, languages, pace, allow_synthetic, updated_at)
signal_intent(id, char_id, objective, role, level_min, level_max, region_pref JSON, until_ts, travel_tol, lead, join_existing, notes, state)
signal_schedule(id, char_id, objective, region_pref, start_ts, end_ts)
signal_candidate(intent_a, intent_b, score, computed_at)          -- in memory; persisted only for audit sampling
signal_mutual_interest(intent_a, intent_b, a_ok, b_ok, at)
signal_session(id, intents JSON, formed_at, party_id, continued_after_first BOOL)
signal_block(char_id, blocked_char_id)
```
Current intents are memory-first; schedules and session history persist.

## 7. UI (Codex → Signal tab, `/signal`)

Flows: set intent (form) · view aligned people (anonymised until mutual) · express interest · reveal on mutual ·
schedule future session · manage visibility · block. Notifications via system messages; the latent party prompt via
`ConfirmDlg`.

## 8. Acceptance

`SIG-*`: time-to-first-contact and party conversion improve versus party matching alone (human and mixed cohorts measured
separately); no teleport, no reward; support classes found faster, not replaced; no coordinate ever leaves the Intel Service;
opt-out removes presence within one evaluation cycle; future sessions notify correctly.
