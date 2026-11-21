package org.l2jmobius.testing.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.l2jmobius.testing.economy.SiteScan.Kind;
import org.l2jmobius.testing.economy.SiteScan.Site;

/**
 * The site scan of AT-ASSET-12 (docs/spec/asset-service.md §2.7) on known code: each kind of site found with its file and
 * member, a lambda's call attributed to its enclosing member, repeated calls one site, other calls none; SQL counts when it
 * writes a census table (items, itemsonground, item_auction_bid, auction_bid, a castle's treasury), and only then.
 */
class SiteScanTest
{
	@Test
	void findsEveryKindOfSiteWithItsFileAndMember() throws Exception
	{
		final Path root = Path.of(EconomyFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		final Set<Site> sites = SiteScan.scanClasses(root, "test/java/", name -> name.contains("EconomyFixture"));
		final String file = "test/java/org/l2jmobius/testing/economy/EconomyFixture.java";
		assertEquals(Set.of(new Site(file, "mints", Kind.NEW_ITEM), new Site(file, "creates", Kind.CREATE_ITEM), new Site(file, "destroys", Kind.DESTROY_ITEM), new Site(file, "grows", Kind.CHANGE_COUNT), new Site(file, "sets", Kind.SET_COUNT), new Site(file, "purges", Kind.CENSUS_SQL), new Site(file, "inserts", Kind.CENSUS_SQL), new Site(file, "treasury", Kind.CENSUS_SQL), new Site(file, "bids", Kind.CENSUS_SQL), new Site(file, "ground", Kind.CENSUS_SQL)), sites);
	}
}
