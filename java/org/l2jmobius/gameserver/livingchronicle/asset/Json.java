package org.l2jmobius.gameserver.livingchronicle.asset;

import java.util.List;
import java.util.Map;

/**
 * The few JSON values fact payloads need: objects with string, number, boolean and nested values, in insertion order.
 */
final class Json
{
	private Json()
	{
	}

	static String object(Map<String, ?> fields)
	{
		final StringBuilder out = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> field : fields.entrySet())
		{
			if (!first)
			{
				out.append(',');
			}
			first = false;
			string(out, field.getKey());
			out.append(':');
			value(out, field.getValue());
		}
		return out.append('}').toString();
	}

	private static void value(StringBuilder out, Object value)
	{
		if (value == null)
		{
			out.append("null");
		}
		else if ((value instanceof Number) || (value instanceof Boolean))
		{
			out.append(value);
		}
		else if (value instanceof Map<?, ?> map)
		{
			@SuppressWarnings("unchecked")
			final Map<String, ?> object = (Map<String, ?>) map;
			out.append(object(object));
		}
		else if (value instanceof List<?> list)
		{
			out.append('[');
			for (int i = 0; i < list.size(); i++)
			{
				if (i > 0)
				{
					out.append(',');
				}
				value(out, list.get(i));
			}
			out.append(']');
		}
		else
		{
			string(out, value.toString());
		}
	}

	private static void string(StringBuilder out, String text)
	{
		out.append('"');
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
			}
		}
		out.append('"');
	}
}
