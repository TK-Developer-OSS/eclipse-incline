package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * ツールの引数を取り出す。型が違うときは、Claude が直せるように何が違うかを例外で伝える。
 */
final class Args {

	private Args() {
	}

	static String string(JsonObject args, String name) {
		String value = optString(args, name);
		if (value == null) {
			throw new IllegalArgumentException("Missing required argument: " + name); //$NON-NLS-1$
		}
		return value;
	}

	/** @return 指定がない、または空なら null */
	static String optString(JsonObject args, String name) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (!e.isJsonPrimitive()) {
			throw new IllegalArgumentException("Argument " + name + " must be a string"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String value = e.getAsString().trim();
		return value.isEmpty() ? null : value;
	}

	static int optInt(JsonObject args, String name, int defaultValue) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			return defaultValue;
		}
		try {
			return e.getAsInt();
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException("Argument " + name + " must be an integer"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	static boolean optBool(JsonObject args, String name, boolean defaultValue) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			return defaultValue;
		}
		if (!e.isJsonPrimitive()) {
			throw new IllegalArgumentException("Argument " + name + " must be true or false"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return e.getAsBoolean();
	}

	/**
	 * 文字列の配列。文字列 1 つだけが渡されたら 1 要素の配列として扱い、配列を文字列にしたもの ("[\"a\",\"b\"]") も受け付ける。
	 *
	 * @return 指定がなければ空
	 */
	static List<String> optStringList(JsonObject args, String name) {
		JsonElement e = args.get(name);
		List<String> list = new ArrayList<>();
		if (e == null || e.isJsonNull()) {
			return list;
		}
		if (e.isJsonPrimitive()) {
			String text = e.getAsString().trim();
			if (text.startsWith("[") && text.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
				try {
					e = new Gson().fromJson(text, JsonArray.class);
				} catch (JsonParseException ex) {
					// 配列ではなく、角括弧で囲まれたラベルだった
				}
			}
			if (e.isJsonPrimitive()) {
				list.add(e.getAsString());
				return list;
			}
		}
		if (!e.isJsonArray()) {
			throw new IllegalArgumentException("Argument " + name + " must be an array of strings"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		for (JsonElement item : e.getAsJsonArray()) {
			if (!item.isJsonPrimitive()) {
				throw new IllegalArgumentException("Argument " + name + " must be an array of strings"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			list.add(item.getAsString());
		}
		return list;
	}

	/** @return 指定がなければ null */
	static Map<String, String> optStringMap(JsonObject args, String name) {
		JsonElement e = args.get(name);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (!e.isJsonObject()) {
			throw new IllegalArgumentException("Argument " + name + " must be an object"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Map<String, String> map = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
			JsonElement value = entry.getValue();
			if (!value.isJsonPrimitive()) {
				throw new IllegalArgumentException("The values of " + name + " must be strings"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			map.put(entry.getKey(), value.getAsString());
		}
		return map;
	}
}
