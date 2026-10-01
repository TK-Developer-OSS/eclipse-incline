package com.tkdevelopeross.eclipse.incline.tools;

import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.mcp.McpTool;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * 名前・説明・引数の定義と、処理 (ラムダ) を組にしたツール。
 */
final class EclipseTool implements McpTool {

	interface Handler {
		String call(JsonObject arguments) throws Exception;
	}

	/** 引数によって影響が変わるツール用。 */
	interface EffectResolver {
		ToolEffect resolve(JsonObject arguments);
	}

	private final String name;
	private final String description;
	private final ToolEffect effect;
	private final EffectResolver effectResolver;
	private final JsonObject inputSchema;
	private final Handler handler;

	EclipseTool(String name, String description, ToolEffect effect, Schema schema, Handler handler) {
		this(name, description, effect, null, schema, handler);
	}

	/**
	 * @param worstEffect    影響が一番大きい場合。引数を解釈できないときもこれにする
	 * @param effectResolver 引数から実際の影響を決める
	 */
	EclipseTool(String name, String description, ToolEffect worstEffect, EffectResolver effectResolver, Schema schema,
			Handler handler) {
		this.name = name;
		this.description = description;
		this.effect = worstEffect;
		this.effectResolver = effectResolver;
		this.inputSchema = schema.build();
		this.handler = handler;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public JsonObject getInputSchema() {
		return inputSchema;
	}

	@Override
	public ToolEffect getEffect(JsonObject arguments) {
		if (arguments == null || effectResolver == null) {
			return effect;
		}
		try {
			return effectResolver.resolve(arguments);
		} catch (RuntimeException e) {
			return effect;
		}
	}

	@Override
	public String call(JsonObject arguments) throws Exception {
		String result = handler.call(arguments);
		// 画面をふさぐダイアログが開いていたら、どのツールの結果にも添える (Claude が気づかないまま先へ進まないように)
		if (result != null && !"read_dialog".equals(name)) { //$NON-NLS-1$
			String dialog = UiTarget.probeDialogTitle();
			if (dialog != null && !result.contains("dialog \"" + dialog + "\"")) { //$NON-NLS-1$ //$NON-NLS-2$
				result += (result.endsWith("\n") ? "" : "\n") + "[A dialog is open in Eclipse: \"" + dialog //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ "\". read_dialog shows it; answer it with click / set_text using target 'dialog'.]"; //$NON-NLS-1$
			}
		}
		return result;
	}
}
