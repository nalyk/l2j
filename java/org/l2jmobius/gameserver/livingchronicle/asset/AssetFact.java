package org.l2jmobius.gameserver.livingchronicle.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import org.l2jmobius.gameserver.livingchronicle.journal.DurableFact;

/**
 * An Asset Service fact (docs/spec/asset-service.md §5), registered by V017 as {@code Asset.<name>}, schema version 1,
 * sensitivity 1, in the stream of the custody it concerns.
 */
record AssetFact(String typeName, String stream, String payloadJson) implements DurableFact
{
	/** A fact of the economy monitor (§2.6), in the stream of the whole economy rather than of one custody. */
	static AssetFact economy(String name, Object... fields)
	{
		final Map<String, Object> payload = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2)
		{
			payload.put((String) fields[i], fields[i + 1]);
		}
		return new AssetFact("Asset." + name, "asset.economy", Json.object(payload));
	}

	/** A lifecycle's fact (§5, §11.35), in the stream of lifecycles: it may concern no custody at all. */
	static AssetFact lifecycle(String name, Object... fields)
	{
		final Map<String, Object> payload = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2)
		{
			payload.put((String) fields[i], fields[i + 1]);
		}
		return new AssetFact("Asset." + name, "asset.lifecycle", Json.object(payload));
	}

	static AssetFact of(String name, int custody, Object... fields)
	{
		final Map<String, Object> payload = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2)
		{
			payload.put((String) fields[i], fields[i + 1]);
		}
		return new AssetFact("Asset." + name, "asset.custody." + custody, Json.object(payload));
	}

	@Override
	public int schemaVersion()
	{
		return 1;
	}

	@Override
	public int sensitivity()
	{
		return 1;
	}
}
