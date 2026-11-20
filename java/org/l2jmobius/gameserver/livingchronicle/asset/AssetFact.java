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
