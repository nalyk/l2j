# Lineage II: Living Chronicle

A Lineage II High Five server where the history is written by the people who live in it, not by a script. Built on
L2J Mobius.

Citește în română: [README.ro.md](README.ro.md)

> **Status (November 2026):** you can't play any of this yet. The foundation is built and the design is written down,
> but none of the new systems is in the game. The section [Where it stands](#where-it-stands) has the honest details.

## The idea

Think about what made Lineage II stick with you. It probably wasn't the quest list. It was the other players: the
bishop your party couldn't hunt without, the PK whose name you still remember, the clan that took Giran the week you
quit, the crafter you handed 30 million adena to while hoping for the best.

Small servers lose exactly that part first. A handful of people log in, the world feels empty, nobody remembers what
happened last month, and the server quietly dies.

Living Chronicle keeps High Five as it is: same classes, same combat, same drops, same map. What we add is what a world
full of players needs to feel alive. It remembers what happened. It helps you find the people you need. It lets a
promise be enforced, gives secrets a price, and writes the news down.

Every feature has to answer one question: will this make players care what happens next? The reaction we want from
someone who hears about this server is "wait, what happened there?"

## What playing here should feel like

These are the moments we're building toward. None of them touches combat formulas.

- You log in for the first time and the world already has weeks of history. Clans have risen and fallen, castles have
  changed hands, a crafter family in Giran runs the B-grade market and a red PK has a 40 million adena bounty on his
  head. None of it was scripted. It was played.
- People remember you. Wipe out someone's party and their clan may put a price on your head. Deliver honestly on a
  contract and their crafters will call you first next time.
- The world remembers too. Heavy farming changes what's for sale, taxes push trade from one town to another, sieges
  leave monuments, and a legendary weapon carries the story of everyone who held it.
- There's a price on your head and nobody knows where you are. Yet. Hunting someone through the Black Ledger means
  witnesses, gatekeeper records, informants, bribes and false trails. There is no map marker.
- A promise can be made binding. Craft orders, purchase orders, deliveries, loans, loot rules and mercenary contracts
  can go through a Notarius, who holds the items and adena until the deal is done. Outside a contract you can still be
  betrayed, and that's how it should be.
- Castle lords make laws, within limits and paid out of their own treasury: bounties, market charters, amnesties, levies.
  Heralds proclaim them and history keeps them.
- Goods travel on real roads. Merchant caravans carry real stock between towns. You can escort one for pay, or rob it.
- Aden has a press. A daily Gazette, bards singing about last week's siege, town criers reading out new laws, wanted
  posters in every square, and a website with dynasties, maps and replays of famous battles.

## What we won't add

No custom weapons or armor, wings, custom classes, battle pass, new currencies, daily chores, seasonal wipes, or arenas
that replace fighting in the open world. Auto-play, auto-potions and scheme buffers stay off, and so do buffs,
teleports and shops in the community board. All of those take away the reasons players need each other.

The rule we follow: it's fine to make it easier to take part (finding a party, remembering what you were doing three days
ago, trusting a crafter you've never met). It's not fine to make it easier to win. If a feature hands out power for free,
makes a class unnecessary or turns travel into a menu, it doesn't go in.

## The citizens of Aden

A test server with at most ten people can't fill a world. So Aden will get citizens: real characters on real accounts,
played by a program. They'll log in through the same game protocol your client uses, see only what a game client would
see, and follow the same rules you do. They aren't the "fake players" Mobius ships, which are NPCs dressed up as
players.

Before the first human logs in, the citizens will live through a Genesis Epoch: weeks of play in which the first clans,
wars, markets and grudges form on their own.

There's one hard rule about AI. Language models may only write words: a bard's song, a Gazette article, a line of dialogue,
always from facts the server has already checked. They never decide a drop, a verdict, a contract or anyone's location.
Every decision that affects the game is plain Java code that anyone can read and replay.

## Rules and rates

| Setting | Value | Why |
|---|---|---|
| EXP / SP | x5 | a small group has to reach mid-game content in weeks; Vitality and Nevit stay retail |
| Adena, drop, spoil, quest items | x1 | scarcity is what makes the economy work |
| Raid and epic bosses | x1, retail respawn | bosses stay events, not farms |
| Seven Signs | on, retail periods | castle tax limits and catacomb access depend on it |
| Hellbound and the Gracia seeds | on, retail progression | the world moves forward with the players |
| Territory Wars, fortress and clan hall sieges | on, retail schedule | the politics |
| Auto-play, auto-potions, scheme buffer, community board buffs, teleports and shops | off | they remove the need for other players |
| Offline shops | on, peace zones only | markets stay full even when few people are online |
| Selling buffs to other players | on | being a buffer is a job |
| Languages | English, Romanian, Russian | our community |

These values are decided. They change only if simulation shows they should, not because someone feels like it.

## Where it stands

Nothing new is playable yet. Here is what exists in November 2026.

Done:

- The fork itself: L2J Mobius High Five, kept in step with upstream. One command builds it and runs every test, with every
  tool pinned to an exact version.
- A behaviour lock: tests that record how the server behaves today (combat, monster AI, drops, spawns, configuration), so
  no change can quietly alter a retail mechanic.
- The design, and a detailed spec for each of the 15 systems. Seven of them are signed off by a second AI reviewer; the
  rest are being reworked or wait until their part gets built.
- A list of all High Five content in the datapack: 539 quests, 142 instance zones, 370 raid and grand bosses and 37 world
  systems, each waiting to be checked against retail. The list has already turned up problems: nine fortress dungeon
  zones that no script ever opens, and 39 bosses that no spawn list, table or script puts into the world.
- The first piece of the Asset Service, the part that will hold contract items and adena. It's built so that a crash in
  the middle of a deal can't duplicate or lose them, and every movement is booked twice, like in a real ledger.

Not done: every feature described above, the citizens, the client package, geodata, the website and the test server.

What's next: the Asset Service gets connected to real player inventories, then the systems get built one at a time. After
that comes a private test with up to ten invited players. A public server comes later, if the test goes well.

## How it's built

The base is L2J Mobius, chronicle CT 2.6 High Five. Every change to an original Mobius file is listed with its reason
and its test in [CORE_PATCHES.md](CORE_PATCHES.md), so we can keep taking Mobius updates without losing track of ours.

Retail comes first. The behaviour lock catches unwanted changes, and whether something matches the official servers is
decided by evidence: patch notes, guides, recorded traffic. "I remember it differently" doesn't count. That work lives in
[truth/](truth/).

The code is written by AI and reviewed by AI, and a person decides. Claude (Anthropic) writes the code and the specs.
Each spec goes to OpenAI's Codex, a model from another company, and goes back and forth until Codex signs it off. Tests
come first: every test has to be seen failing before the code that makes it pass gets written. A human owner decides
what goes into `main`.

The technology is plain on purpose: Java, MariaDB, and nothing beyond what Mobius already uses.

## For developers

All you need is Docker. The build runs in a pinned container that brings its own JDK, Ant and test database.

```sh
./build.sh                                  # build and run the whole test suite
./build.sh test -Dtest.only=AssetTxTest     # run a single test class
```

Where things are:

- [docs/blueprint/](docs/blueprint/): the design. Start with [00-INDEX.md](docs/blueprint/00-INDEX.md) and
  [01-VISION.md](docs/blueprint/01-VISION.md).
- [docs/spec/](docs/spec/): one spec per system, with its tables, rules and acceptance tests.
- [CORE_PATCHES.md](CORE_PATCHES.md): every change made to Mobius's own files.
- [truth/](truth/): Retail Truth cases and the content inventory.
- [readme.txt](readme.txt): the High Five patch notes, exactly as Mobius ships them.

## License and disclaimer

This is a fan project. Lineage II is a trademark of NCSoft. The project isn't affiliated with or endorsed by NCSoft, and
this repository contains no game client.

Everything here is under the MIT license ([LICENSE](LICENSE)): the code that comes from L2J Mobius, which was already
MIT, and Living Chronicle's own code and documents. You can read it, learn from it, fork it and build on it, as long as
you keep the copyright notice.
