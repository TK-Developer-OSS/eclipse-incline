package com.tkdevelopeross.eclipse.incline.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * ツールの引数の JSON Schema を組み立てる。
 */
final class Schema {

	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	Schema string(String name, String description) {
		properties.add(name, property("string", description)); //$NON-NLS-1$
		return this;
	}

	Schema integer(String name, String description) {
		properties.add(name, property("integer", description)); //$NON-NLS-1$
		return this;
	}

	Schema bool(String name, String description) {
		properties.add(name, property("boolean", description)); //$NON-NLS-1$
		return this;
	}

	/** 中身を決めないオブジェクト (値の型がまちまちな属性の集まりなど)。 */
	Schema object(String name, String description) {
		properties.add(name, property("object", description)); //$NON-NLS-1$
		return this;
	}

	/** 値が文字列のオブジェクト (コマンドのパラメータなど)。 */
	Schema stringMap(String name, String description) {
		JsonObject values = new JsonObject();
		values.addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
		JsonObject property = property("object", description); //$NON-NLS-1$
		property.add("additionalProperties", values); //$NON-NLS-1$
		properties.add(name, property);
		return this;
	}

	/** 文字列の配列 (ツリーの項目までのラベルの列など)。 */
	Schema stringList(String name, String description) {
		JsonObject items = new JsonObject();
		items.addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
		JsonObject property = property("array", description); //$NON-NLS-1$
		property.add("items", items); //$NON-NLS-1$
		properties.add(name, property);
		return this;
	}

	/** 決まった値のどれか。 */
	Schema choice(String name, String description, String... values) {
		JsonArray choices = new JsonArray();
		for (String value : values) {
			choices.add(value);
		}
		JsonObject property = property("string", description); //$NON-NLS-1$
		property.add("enum", choices); //$NON-NLS-1$
		properties.add(name, property);
		return this;
	}

	Schema required(String... names) {
		for (String name : names) {
			required.add(name);
		}
		return this;
	}

	JsonObject build() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
		schema.add("properties", properties); //$NON-NLS-1$
		if (required.size() > 0) {
			schema.add("required", required); //$NON-NLS-1$
		}
		return schema;
	}

	private static JsonObject property(String type, String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", type); //$NON-NLS-1$
		property.addProperty("description", description); //$NON-NLS-1$
		return property;
	}
}
