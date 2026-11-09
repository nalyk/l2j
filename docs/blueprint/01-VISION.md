# 01 — Vision

## 1. Thesis

Classic Lineage II was never great because of its quest list. It was great because **other players were the content**:
you depended on them, feared them, remembered them by name, traded with them, betrayed them, and fought them over castles,
bosses, spots and routes.

Living Chronicle keeps Lineage II intact and adds the five things a persistent society needs, plus one that keeps us honest:

| Capability | System |
|---|---|
| History | **Aden Remembers** + **Chronicle of Aden** |
| Coordination | **Aden Signal** |
| Personal continuity and knowledge | **Wayfarer's Memory** |
| Institutions and enforceable promises | **PACT** and the **Notarius** |
| Secrets, investigation, assassination | **The Black Ledger** |
| Verified foundation | **Retail Truth** |

And one thing that makes it possible with a handful of humans: **the Citizens of Aden**.

> **One permanent world. No seasons. No scripted global storyline. The history is written by the inhabitants.**
> **Aden is not a map. It is a society.**

## 2. Design law

Every feature passes all of these or it does not ship.

### 2.1 Productive pain vs stupid pain
Keep the friction that creates decisions, relationships, risk, trade or stories: long travel, support-class dependency,
mana, gear scarcity, low success rates, dangerous open world, PK consequences, slow progression, incomplete information.
Remove the friction around being a human: not finding compatible people, forgetting what you were doing after three days,
handing millions to a crafter on blind trust, a newcomer being useless on an old server.

> Never reduce the cost of succeeding in Aden for convenience. Reduce the cost of being a human trying to participate in Aden.

### 2.2 Consequences, not replacement content
We connect existing systems (Adena, materials, spoil, crafting, stores, Manor, castles, fortresses, Territory Wars, clan wars,
PvP/PK, raids, Seven Signs, Hellbound, travel, NPCs) so actions in one create consequences in another.
We do not build a parallel game beside Lineage II.

### 2.3 Rejected outright
Custom god weapons; wings; custom combat classes; blanket XP catch-up; free endgame gear; teleport convenience that kills
geography; free universal buffs; Battle Pass; new currencies; daily chores; underdog damage buffs; seasonal wipes;
arena/lobby replacement of open-world conflict; omniscient in-game wiki; GPS for assassination; automated morality scores;
transmogrification that hides gear (gear grade is information in L2); any LLM with authority over game state.

### 2.4 Power creates obligations
Anything that increases power increases surface area, exposure, responsibility or complexity. A clan that rules a territory
gets opportunities *and* routes to protect, laws to enforce, a treasury to defend, enemies who know where it lives.

### 2.5 Information is gameplay
The server distinguishes fact, personal observation, verified witness, inference, rumor, stale knowledge and deliberate
misinformation, and never exposes internal model values. Mystery is not missing UX. It is the game.

### 2.6 The engine decides facts; a model may narrate facts
All game-affecting decisions are deterministic or explicitly randomized Java logic. Language models write tavern ballads,
Gazette articles and dialogue from verified facts. They never decide drops, guilt, contract validity, locations or outcomes.

### 2.7 The "do not accidentally ruin this" checklist
Before any new idea: does it create power from nowhere? make a class unnecessary? invalidate geography? reveal what players
should discover? add a currency? turn open-world conflict into a lobby? replace a human with an NPC convenience? reward
clicking the feature? make an old L2 system irrelevant? Can it be disabled without hurting core Mobius? Can every authoritative
decision be explained? Can alts or citizens farm it? Does it create a story, a relationship or a decision?
Would it still feel like Lineage II with the custom UI hidden? Mostly "yes" to the first nine = rejected.

## 3. The wow pillars

These are what a player tells a friend about. Each is backed by a module; none touches combat math.

1. **The world was alive before you arrived.** *(NEW v2, Genesis Epoch — 10-CITIZENS)* When the first tester logs in, Aden
   has weeks of real history: clans rose and fell, castles changed hands, a crafter dynasty in Giran owns the B-grade market,
   a red PK has a 40M bounty, the Gazette archive has 50 issues. Nothing scripted — all of it was played.
2. **The people remember you.** *(NEW v2)* Citizens keep a relationship ledger. Kill a citizen's party and their clan may put
   a price on your head. Deliver honestly on a contract and their crafters give you first call. Rescue a newbie and they
   remember the name.
3. **The world remembers what you did.** Farming pressure shifts supply, taxes move trade between towns, sieges leave
   monuments, legendary weapons carry their saga, NPCs mention the clan that once ruled here.
4. **There is a price on your head. Nobody knows where you are. Yet.** Black Ledger hunts are information warfare:
   witnesses, gatekeeper records, informants, bribes, false trails, calling cards, aliases, reverse investigation.
5. **Promises can be made binding.** PACT turns crafting, purchase orders, deliveries, loans, loot charters and mercenary
   contracts into escrowed, machine-verified agreements — and leaves betrayal possible everywhere else.
6. **Lords make law.** *(NEW v2, Crown Law)* Castle lords enact bounded, treasury-funded laws in their territory: bounty
   endowments, market charters, amnesties, levies. Laws are proclaimed by heralds and recorded in history.
7. **Goods move physically.** *(NEW v2, Caravans)* Merchant caravans carry real stock between towns along real roads.
   They can be escorted under contract or robbed by outlaws.
8. **Aden has a press.** *(NEW v2, Gazette, bards, criers)* A daily Gazette, tavern bards singing about last week's siege,
   town criers reading new laws, wanted boards in every square, and a public website with dynasties, maps and saga replays.

## 4. Canonical ruleset (decided; tunable only through simulation evidence)

| Setting | Value | Why |
|---|---|---|
| EXP / SP | ×5 | 10 humans must reach mid-game content in weeks; Vitality and Nevit stay retail |
| Adena, drop, spoil, quest items | ×1 | scarcity is the economy; crafters, contracts and caravans need it |
| Raid / epic drops | ×1, retail respawn | bosses stay events, not farms |
| Seven Signs | ON, retail periods | drives castle tax caps and Necropolis/Catacomb access |
| Hellbound, Gracia seeds | ON, retail progression | world-state machines Aden Remembers observes |
| Territory Wars, fortress, clan hall sieges | ON, retail schedule | the political spine |
| Auto-play, auto-potions, scheme buffer, CB buffs/teleports/shops | OFF | they delete social dependency |
| Offline trade | ON, peace zones only | deep markets with few humans; counted as supply, never as presence |
| Sell buffs (player-to-player) | ON | buffers as a profession |
| Multilingual | ON: EN, RO, RU | the community |
| Packet encryption | ON (retail) | fidelity; the runner implements the real wire format |

Full per-config policy for all 44 Mobius H5 custom modules: 03-PLATFORM §8.

## 5. Final statement

The project does not ask "how many features keep players?". It asks
**"how can the actions of players produce enough consequences that they care what happens next?"**

The intended reaction is not "nice custom server". It is **"wait — what happened on that server?"**
