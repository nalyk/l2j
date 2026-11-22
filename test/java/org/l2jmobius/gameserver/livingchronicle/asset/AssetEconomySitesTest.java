package org.l2jmobius.gameserver.livingchronicle.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.economy.SiteScan;
import org.l2jmobius.testing.economy.SiteScan.Site;
import org.l2jmobius.testing.truth.Inventory;

/**
 * AT-ASSET-12, its static part (docs/spec/asset-service.md §2.7, ASSET-R12): every site of the compiled core and the
 * datapack's scripts is classified in {@code docs/spec/asset-economy-sites.csv}, no row is {@code PENDING}, no row names a
 * site that no longer exists, and the ledger rows (file {@code ledger}, member the posting code) are exactly the SOURCE
 * and SINK posting codes of §2.5 in each kind, every {@link SinkCode} among them.
 * The scan's sites are written to {@code build/economy-sites-actual.txt}.
 */
class AssetEconomySitesTest
{
	private static Set<String> _scanned;
	private static List<Map<String, String>> _table;

	@BeforeAll
	static void scan() throws Exception
	{
		final Path repository = Path.of(System.getProperty("lc.docs.dir")).getParent();
		final Path build = Path.of(System.getProperty("lc.classes.dir")).getParent();
		final Set<Site> sites = SiteScan.scanAll(Path.of(System.getProperty("lc.classes.dir")), repository.resolve("dist/game/data/scripts"), build.resolve("economy-scripts"));
		Files.write(build.resolve("economy-sites-actual.txt"), sites.stream().map(s -> s.file() + "," + s.member() + "," + s.kind()).toList());
		_scanned = sites.stream().map(s -> s.file() + "#" + s.member()).collect(Collectors.toCollection(TreeSet::new));
		_table = Inventory.read(repository.resolve("docs/spec/asset-economy-sites.csv"));
	}

	private static final String LEDGER = "ledger";

	/** The rows naming a member of the repository: every row but the ledger's. */
	private static Set<String> classified()
	{
		return _table.stream().filter(r -> !LEDGER.equals(r.get("file"))).map(r -> r.get("file") + "#" + r.get("member")).collect(Collectors.toCollection(TreeSet::new));
	}

	@Test
	void everySiteIsClassified()
	{
		final Set<String> unclassified = new TreeSet<>(_scanned);
		unclassified.removeAll(classified());
		assertEquals(Set.of(), unclassified, unclassified.size() + " sites the table does not classify (build/economy-sites-actual.txt lists every site)");
	}

	@Test
	void noRowIsPending()
	{
		assertEquals(List.of(), _table.stream().filter(r -> "PENDING".equals(r.get("class"))).map(r -> r.get("site")).toList());
	}

	@Test
	void noRowIsStale()
	{
		final Set<String> stale = classified();
		stale.removeAll(_scanned);
		assertEquals(Set.of(), stale, "rows naming a site that no longer exists");
	}

	@Test
	void everyRowHasAKnownClass()
	{
		final Set<String> classes = Set.of("SOURCE", "SINK", "MOVE", "RESTORE", "PERSIST", "COUNTER", "PENDING");
		assertEquals(List.of(), _table.stream().filter(r -> !classes.contains(r.get("class"))).map(r -> r.get("site") + "=" + r.get("class")).toList());
	}

	/** The {@code SOURCE} and {@code SINK} posting codes asset-service.md §2.5 permits, each with its kinds, as "CODE KIND". */
	private static Set<String> postingCodes() throws Exception
	{
		final String spec = Files.readString(Path.of(System.getProperty("lc.docs.dir")).resolve("spec/asset-service.md"));
		final String table = spec.substring(spec.indexOf("### 2.5 Posting codes"), spec.indexOf("### 2.6"));
		final Set<String> codes = new TreeSet<>();
		for (String line : table.split("\n"))
		{
			final String[] cells = line.split("\\|");
			if (!line.startsWith("| `") || (cells.length < 3))
			{
				continue;
			}
			final java.util.regex.Matcher code = java.util.regex.Pattern.compile("`([A-Z_]+)`").matcher(cells[1]);
			while (code.find())
			{
				for (String kind : List.of("SOURCE", "SINK"))
				{
					if (cells[2].contains(kind))
					{
						codes.add(code.group(1) + " " + kind);
					}
				}
			}
		}
		return codes;
	}

	@Test
	void theLedgerRowsAreExactlyThePostingCodesInEachKind() throws Exception
	{
		final Set<String> ledger = _table.stream().filter(r -> LEDGER.equals(r.get("file"))).map(r -> r.get("member") + " " + r.get("class")).collect(Collectors.toCollection(TreeSet::new));
		assertEquals(postingCodes(), ledger);
	}

	@Test
	void everySinkCodeIsASinkPostingCode() throws Exception
	{
		final Set<String> codes = postingCodes();
		assertEquals(List.of(), java.util.Arrays.stream(SinkCode.values()).map(c -> c.name() + " SINK").filter(c -> !codes.contains(c)).toList());
	}

	@Test
	void everyLedgerRowIsASourceOrASink()
	{
		assertEquals(List.of(), _table.stream().filter(r -> LEDGER.equals(r.get("file")) && !Set.of("SOURCE", "SINK").contains(r.get("class"))).map(r -> r.get("site")).toList());
	}
}
