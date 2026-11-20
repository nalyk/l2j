# Lineage II: Living Chronicle

Un server Lineage II High Five în care istoria o scriu oamenii care trăiesc în el, nu un scenariu. Construit pe L2J
Mobius.

Read in English: [README.md](README.md)

> **Stadiu (noiembrie 2026):** deocamdată nu se poate juca nimic din toate astea. Fundația e construită și designul e
> scris, dar niciunul dintre sistemele noi nu e încă în joc. Detaliile, spuse cinstit, sunt la
> [Unde am ajuns](#unde-am-ajuns).

## Ideea

Gândește-te ce te-a ținut de fapt în Lineage II. Probabil nu lista de questuri. Erau ceilalți jucători: bishopul fără de
care party-ul nu putea vâna, PK-ul al cărui nume îl ții minte și acum, clanul care a luat Giranul exact în săptămâna în care
te-ai lăsat, crafterul căruia i-ai dat 30 de milioane de adena sperând că nu dispare cu ele.

Exact partea asta se pierde prima pe serverele mici. Intră câțiva oameni, lumea pare goală, nimeni nu mai ține minte ce s-a
întâmplat luna trecută, iar serverul moare încet, fără zgomot.

Living Chronicle lasă High Five așa cum e: aceleași clase, aceeași luptă, aceleași drop-uri, aceeași hartă. Adăugăm doar ce
îi trebuie unei lumi pline de jucători ca să pară vie. Lumea ține minte ce s-a întâmplat. Te ajută să găsești oamenii de
care ai nevoie. Face ca o promisiune să poată fi impusă, pune preț pe secrete și scrie știrile.

Fiecare funcție trebuie să treacă de o singură întrebare: îi va face pe jucători să le pese ce urmează? Reacția pe care o
vrem de la cineva care aude de server e „stai, ce s-a întâmplat acolo?”.

## Cum ar trebui să se simtă jocul aici

Astea sunt momentele spre care construim. Niciunul nu atinge formulele de luptă.

- Intri prima dată și lumea are deja săptămâni de istorie. Clanuri s-au ridicat și au căzut, castele și-au schimbat
  stăpânii, o familie de crafteri din Giran ține piața de B-grade, iar pe capul unui PK roșu e o recompensă de 40 de
  milioane de adena. Nimic din toate astea nu e scenariu. S-a jucat.
- Oamenii te țin minte. Omori party-ul cuiva și clanul lui poate pune preț pe capul tău. Livrezi cinstit un contract și data
  viitoare crafterii lor te caută primul pe tine.
- Și lumea ține minte. Farmul intens schimbă ce se vinde, taxele mută comerțul dintr-un oraș în altul, asediile lasă
  monumente, iar o armă legendară poartă povestea tuturor celor care au ținut-o în mână.
- Ai un preț pe cap și nimeni nu știe unde ești. Încă. Să vânezi pe cineva prin Black Ledger înseamnă martori, registrele
  gatekeeperilor, informatori, mită și piste false. Pe hartă nu apare niciun marcaj.
- O promisiune poate deveni obligatorie. Comenzile de craft, comenzile de cumpărare, livrările, împrumuturile, regulile de
  împărțire a loot-ului și contractele de mercenari pot trece printr-un Notarius, care ține itemele și adena până se încheie
  înțelegerea. În afara unui contract poți fi trădat în continuare, și așa e corect.
- Lorzii castelelor dau legi, în limite și plătite din propria trezorerie: fonduri pentru recompense, privilegii de piață,
  amnistii, taxe. Heralzii le anunță, iar istoria le păstrează.
- Marfa circulă pe drumuri reale. Caravanele negustorilor duc stocuri reale între orașe. Poți escorta una contra plată sau
  o poți jefui.
- Aden are presă. O Gazetă zilnică, barzi care cântă despre asediul de săptămâna trecută, crainici care citesc legile noi,
  afișe de „căutat” în fiecare piață și un site cu dinastii, hărți și reluări ale bătăliilor celebre.

## Ce nu vom adăuga

Fără arme sau armuri custom, aripi, clase custom, battle pass, monede noi, sarcini zilnice, wipe-uri de sezon sau arene care
să înlocuiască lupta în lumea deschisă. Auto-play-ul, auto-poțiunile și scheme buffer-ul rămân oprite, la fel și buff-urile,
teleporturile și magazinele din community board. Toate astea iau exact motivele pentru care jucătorii au nevoie unii de
alții.

Regula după care mergem: e în regulă să fie mai ușor să participi (să-ți găsești party, să-ți amintești ce făceai acum trei
zile, să ai încredere într-un crafter pe care nu l-ai văzut niciodată). Nu e în regulă să fie mai ușor să câștigi. Dacă o
funcție dă putere pe gratis, face o clasă inutilă sau transformă drumul într-un meniu, nu intră.

## Cetățenii din Aden

Un server de test cu cel mult zece oameni nu poate umple o lume. Așa că Aden va avea cetățeni: personaje reale, pe conturi
reale, jucate de un program. Se vor conecta prin același protocol de joc pe care îl folosește clientul tău, vor vedea doar
ce ar vedea un client și vor respecta aceleași reguli ca tine. Nu sunt „fake players” din Mobius, care de fapt sunt NPC-uri
îmbrăcate ca jucători.

Înainte să intre primul om, cetățenii vor trece printr-o Epocă a Genezei: săptămâni de joc în care primele clanuri, războaie,
piețe și dușmănii se formează singure.

Despre AI avem o regulă fermă. Modelele de limbaj au voie doar să scrie text: cântecul unui bard, un articol din Gazetă, o
replică, mereu pornind de la fapte pe care serverul le-a verificat deja. Nu decid niciodată un drop, o sentință, un contract
sau unde se află cineva. Orice decizie care afectează jocul e cod Java obișnuit, pe care oricine îl poate citi și rula din
nou.

## Reguli și rate

| Setare | Valoare | De ce |
|---|---|---|
| EXP / SP | x5 | un grup mic trebuie să ajungă la conținutul de mid-game în câteva săptămâni; Vitality și Nevit rămân ca pe retail |
| Adena, drop, spoil, iteme de quest | x1 | raritatea e ce face economia să meargă |
| Raid boss și epic boss | x1, respawn ca pe retail | bossii rămân evenimente, nu loc de farm |
| Seven Signs | pornit, perioade ca pe retail | de el depind plafoanele de taxe ale castelelor și accesul în catacombe |
| Hellbound și seed-urile din Gracia | pornite, progres ca pe retail | lumea avansează odată cu jucătorii |
| Territory Wars, asediile de fortărețe și de clan hall | pornite, program ca pe retail | politica |
| Auto-play, auto-poțiuni, scheme buffer, buff-uri, teleporturi și magazine în community board | oprite | elimină nevoia de alți jucători |
| Magazine offline | pornite, doar în zonele de pace | piețele rămân pline chiar și când sunt puțini online |
| Vânzare de buff-uri între jucători | pornită | buffer-ul e o meserie |
| Limbi | engleză, română, rusă | comunitatea noastră |

Valorile astea sunt decise. Se schimbă doar dacă o simulare arată că trebuie, nu pentru că are cineva chef.

## Unde am ajuns

Nu se poate juca încă nimic nou. Iată ce există în noiembrie 2026.

Ce e gata:

- Fork-ul propriu-zis: L2J Mobius High Five, ținut la zi cu upstream-ul. O singură comandă îl construiește și rulează toate
  testele, cu fiecare unealtă fixată la o versiune exactă.
- Un „behaviour lock”: teste care înregistrează cum se comportă serverul azi (lupta, AI-ul monștrilor, drop-urile,
  spawn-urile, configurația), ca nicio schimbare să nu poată modifica pe tăcute o mecanică de retail.
- Designul și câte o specificație detaliată pentru fiecare dintre cele 15 sisteme. Șapte sunt aprobate de un al doilea AI
  care le revizuiește; restul sunt în lucru sau așteaptă să ajungem la partea lor.
- O listă cu tot conținutul High Five din datapack: 539 de questuri, 142 de zone instanță, 370 de raid și grand bossi și 37 de
  sisteme ale lumii, fiecare așteptând să fie verificat cu retail-ul. Lista a scos deja la iveală probleme: nouă zone de
  dungeon de fortăreață pe care niciun script nu le deschide și 39 de bossi pe care nicio listă de spawn, tabelă sau script
  nu îi pune în lume.
- Prima bucată din Asset Service, partea care va ține itemele și adena din contracte. E construită astfel încât un crash în
  mijlocul unei tranzacții să nu le poată dubla sau pierde, iar fiecare mișcare e trecută de două ori, ca într-un registru
  contabil adevărat.

Ce nu e gata: toate funcțiile descrise mai sus, cetățenii, pachetul de client, geodata, site-ul și serverul de test.

Ce urmează: Asset Service-ul se leagă de inventarele reale ale jucătorilor, apoi sistemele se construiesc unul câte unul.
După asta vine un test privat cu cel mult zece jucători invitați. Un server public vine mai târziu, dacă testul merge bine.

## Cum e construit

Baza e L2J Mobius, cronica CT 2.6 High Five. Fiecare modificare a unui fișier original din Mobius e trecută, cu motivul și
testul ei, în [CORE_PATCHES.md](CORE_PATCHES.md), ca să putem lua în continuare update-urile din Mobius fără să pierdem
șirul modificărilor noastre.

Retail-ul are prioritate. Behaviour lock-ul prinde schimbările nedorite, iar dacă ceva se potrivește cu serverele oficiale
se decide pe bază de dovezi: patch notes, ghiduri, trafic înregistrat. „Eu țin minte altfel” nu contează. Munca asta stă în
[truth/](truth/).

Codul e scris de AI și revizuit de AI, iar decizia e a unui om. Claude (Anthropic) scrie codul și specificațiile. Fiecare
specificație ajunge la Codex de la OpenAI, un model al altei companii, și se tot întoarce la noi până când Codex o aprobă.
Testele vin primele: fiecare test trebuie văzut picând înainte să fie scris codul care îl face să treacă. Un proprietar uman
decide ce intră în `main`.

Tehnologia e simplă intenționat: Java, MariaDB și nimic peste ce folosește deja Mobius.

## Pentru dezvoltatori

Ai nevoie doar de Docker. Build-ul rulează într-un container fixat, care își aduce singur JDK-ul, Ant-ul și baza de date de
test.

```sh
./build.sh                                  # construiește și rulează toate testele
./build.sh test -Dtest.only=AssetTxTest     # rulează o singură clasă de teste
```

Unde găsești ce (documentația tehnică e în engleză):

- [docs/blueprint/](docs/blueprint/): designul. Începe cu [00-INDEX.md](docs/blueprint/00-INDEX.md) și
  [01-VISION.md](docs/blueprint/01-VISION.md).
- [docs/spec/](docs/spec/): câte o specificație pentru fiecare sistem, cu tabelele, regulile și testele de acceptanță.
- [CORE_PATCHES.md](CORE_PATCHES.md): toate modificările făcute în fișierele Mobius.
- [truth/](truth/): cazurile Retail Truth și inventarul de conținut.
- [readme.txt](readme.txt): patch notes-urile High Five, exact cum le livrează Mobius.

## Licență și precizări

E un proiect de fani. Lineage II e marcă înregistrată NCSoft. Proiectul nu e afiliat cu NCSoft și nici susținut de
compania asta, iar repository-ul nu conține clientul jocului.

Totul aici e sub licența MIT ([LICENSE](LICENSE)): codul care vine din L2J Mobius, care era deja MIT, și codul și
documentele proprii Living Chronicle. Poți să-l citești, să înveți din el, să-i faci fork și să construiești pe el, cu
condiția să păstrezi notița de copyright.
