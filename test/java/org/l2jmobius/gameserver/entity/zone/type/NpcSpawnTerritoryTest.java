package org.l2jmobius.gameserver.entity.zone.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.l2jmobius.gameserver.entity.zone.form.ZoneNPoly;

/**
 * Who stands in a spawn territory whose ban covers only a band of heights (CORE_PATCHES.md CP-0006).
 * <p>
 * The territory is the datapack's rune12_2216_03 in the Swamp of Screams, a stretch of swamp between -3196 and -2696 with a ban from -3199 to -2969 over part of it: below that height, under the swamp, NPCs must not be placed; above it they may, and the territory's own random points put Splinter Stakatos there. The point used is where the respawn lock saw one come back.
 * </p>
 */
class NpcSpawnTerritoryTest
{
	private static final int STAKATO_X = 73113;
	private static final int STAKATO_Y = -51585;
	private static final int STAKATO_Z = -2946;

	private static NpcSpawnTerritory swamp()
	{
		final NpcSpawnTerritory territory = new NpcSpawnTerritory("rune12_2216_03", new ZoneNPoly( //
			new int[]
			{
				70813,
				72984,
				75871,
				75303,
				75345,
				74532,
				71804
			}, new int[]
			{
				-52549,
				-54916,
				-52724,
				-51564,
				-50872,
				-49507,
				-51028
			}, -3196, -2696));
		territory.addBannedTerritory(new ZoneNPoly( //
			new int[]
			{
				72922,
				73524,
				74166,
				74539,
				74508,
				74117,
				73706,
				73279,
				72954
			}, new int[]
			{
				-51634,
				-52106,
				-52169,
				-51791,
				-50996,
				-50536,
				-50429,
				-50643,
				-51017
			}, -3199, -2969));
		return territory;
	}

	@Test
	void aCreatureAboveABannedBandHoldsTheTerritory()
	{
		assertTrue(swamp().holds(STAKATO_X, STAKATO_Y, STAKATO_Z));
	}

	@Test
	void aCreatureInsideABannedBandDoesNot()
	{
		assertFalse(swamp().holds(STAKATO_X, STAKATO_Y, -3100));
	}

	@Test
	void theTerritorysOwnHeightBandDoesNotDecide()
	{
		// Clear of the ban, far above and below the territory's band: still in it, as the XY test has always held.
		assertTrue(swamp().holds(72000, -53000, -2000));
		assertTrue(swamp().holds(72000, -53000, -4000));
	}

	@Test
	void outsideThePolygonIsOutside()
	{
		assertFalse(swamp().holds(70000, -50000, STAKATO_Z));
	}

	/**
	 * The test the AI used to ask, which judges the ban at the territory's lowest height and so turns the Splinter Stakato out of a territory that placed it there.
	 */
	@Test
	void theFlatTestTurnsThatCreatureOut()
	{
		assertFalse(swamp().isInsideZone(STAKATO_X, STAKATO_Y));
	}
}
