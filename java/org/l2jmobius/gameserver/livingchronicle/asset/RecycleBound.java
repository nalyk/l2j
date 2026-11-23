/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingchronicle.asset;

import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.function.IntSupplier;

/**
 * What bounds {@code recycle} (docs/spec/asset-service.md §4.3, §2.5 {@code SINK_RECYCLE}; aden-remembers.md §4.6): the
 * Adena a territory's retail teleport fees destroyed on a day, as the journal's telemetry recorded it (installed by the
 * Fact Journal's telemetry, N5), and the active {@code RecyclePermille} (installed by Aden Remembers). Until both are
 * installed, {@code recycle} refuses.
 */
public final class RecycleBound
{
	/** The recorded teleport-fee sink of a territory and day; empty when nothing is recorded. */
	@FunctionalInterface
	public interface RecordedSinks
	{
		OptionalLong teleportFeesDestroyed(int territory, LocalDate day);
	}

	/** {@code RecyclePermille}'s ceiling (aden-remembers.md §4.6, §8): a larger value is refused, never clamped. */
	public static final int CEILING_PERMILLE = 250;

	private static volatile RecordedSinks _reader;
	private static volatile IntSupplier _permille;

	private RecycleBound()
	{
	}

	/** The Fact Journal's telemetry installs its reader; null removes it. */
	public static void install(RecordedSinks reader)
	{
		_reader = reader;
	}

	/** Aden Remembers installs its active {@code RecyclePermille}; null removes it. */
	public static void installPermille(IntSupplier permille)
	{
		_permille = permille;
	}

	static RecordedSinks reader()
	{
		return _reader;
	}

	static IntSupplier permille()
	{
		return _permille;
	}
}
