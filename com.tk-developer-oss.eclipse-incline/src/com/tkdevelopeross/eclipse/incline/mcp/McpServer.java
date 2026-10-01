package com.tkdevelopeross.eclipse.incline.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.Activator;

/**
 * Eclipse を操作するツールを Claude に公開する MCP サーバ。
 * 通信路には依存しない。JSON-RPC のメッセージを受け取って返事を返すだけで、どう運ぶかは呼ぶ側が決める
 * (今は claude の制御チャネル。ClaudeCliBackend を参照)。
 * ツールの登録は、最初のメッセージを処理する前に済ませること。
 */
public class McpServer {

	private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18"; //$NON-NLS-1$
	private static final int METHOD_NOT_FOUND = -32601;
	private static final int INVALID_PARAMS = -32602;
	private static final int INTERNAL_ERROR = -32603;

	private final String name;
	private final String version;
	private final String instructions;
	private final Map<String, McpTool> tools = new LinkedHashMap<>();

	/**
	 * @param name         サーバ名。Claude から見たツール名は mcp__&lt;name&gt;__&lt;ツール名&gt; になる
	 * @param instructions このサーバの使い方。Claude のシステムプロンプトに入る
	 */
	public McpServer(String name, String version, String instructions) {
		this.name = name;
		this.version = version;
		this.instructions = instructions;
	}

	public String getName() {
		return name;
	}

	public void register(McpTool tool) {
		tools.put(tool.getName(), tool);
	}

	/**
	 * @param input claude が渡そうとしている引数
	 * @return claude が許可を求めてきたツール (mcp__&lt;name&gt;__&lt;ツール名&gt;) が、確認なしで実行してよいものなら true
	 */
	public boolean isAutoAllowed(String qualifiedToolName, JsonObject input) {
		String prefix = "mcp__" + name + "__"; //$NON-NLS-1$ //$NON-NLS-2$
		if (qualifiedToolName == null || !qualifiedToolName.startsWith(prefix)) {
			return false;
		}
		McpTool tool = tools.get(qualifiedToolName.substring(prefix.length()));
		return tool != null && tool.getEffect(input != null ? input : new JsonObject()) != ToolEffect.CHANGE;
	}

	/**
	 * JSON-RPC のメッセージを 1 つ処理する。ツールの実行が終わるまで戻らない。
	 *
	 * @return 返事。通知 (id のないメッセージ) には null
	 */
	public JsonObject handle(JsonObject message) {
		String method = string(message, "method"); //$NON-NLS-1$
		JsonElement id = message.get("id"); //$NON-NLS-1$
		if (method == null || id == null || id.isJsonNull()) {
			return null;
		}
		JsonObject params = object(message, "params"); //$NON-NLS-1$
		try {
			switch (method) {
			case "initialize": //$NON-NLS-1$
				return result(id, initialize(params));
			case "ping": //$NON-NLS-1$
				return result(id, new JsonObject());
			case "tools/list": //$NON-NLS-1$
				return result(id, listTools());
			case "tools/call": //$NON-NLS-1$
				return result(id, callTool(params));
			default:
				return error(id, METHOD_NOT_FOUND, "Method not found: " + method); //$NON-NLS-1$
			}
		} catch (IllegalArgumentException e) {
			return error(id, INVALID_PARAMS, e.getMessage());
		} catch (RuntimeException e) {
			Activator.logError("MCP: " + method + " の処理に失敗しました", e); //$NON-NLS-1$
			return error(id, INTERNAL_ERROR, e.toString());
		}
	}

	private JsonObject initialize(JsonObject params) {
		// ツールしか公開しないので、クライアントが求める版にそのまま合わせる
		String requested = string(params, "protocolVersion"); //$NON-NLS-1$
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject()); //$NON-NLS-1$
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", name); //$NON-NLS-1$
		serverInfo.addProperty("version", version); //$NON-NLS-1$

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", requested != null ? requested : DEFAULT_PROTOCOL_VERSION); //$NON-NLS-1$
		result.add("capabilities", capabilities); //$NON-NLS-1$
		result.add("serverInfo", serverInfo); //$NON-NLS-1$
		if (instructions != null) {
			result.addProperty("instructions", instructions); //$NON-NLS-1$
		}
		return result;
	}

	private JsonObject listTools() {
		JsonArray list = new JsonArray();
		for (McpTool tool : tools.values()) {
			JsonObject annotations = new JsonObject();
			annotations.addProperty("readOnlyHint", tool.getEffect(null) == ToolEffect.READ); //$NON-NLS-1$
			JsonObject entry = new JsonObject();
			entry.addProperty("name", tool.getName()); //$NON-NLS-1$
			entry.addProperty("description", tool.getDescription()); //$NON-NLS-1$
			entry.add("inputSchema", tool.getInputSchema()); //$NON-NLS-1$
			entry.add("annotations", annotations); //$NON-NLS-1$
			list.add(entry);
		}
		JsonObject result = new JsonObject();
		result.add("tools", list); //$NON-NLS-1$
		return result;
	}

	private JsonObject callTool(JsonObject params) {
		String toolName = string(params, "name"); //$NON-NLS-1$
		McpTool tool = toolName == null ? null : tools.get(toolName);
		if (tool == null) {
			throw new IllegalArgumentException("Unknown tool: " + toolName); //$NON-NLS-1$
		}
		JsonObject arguments = object(params, "arguments"); //$NON-NLS-1$
		String text;
		boolean failed = false;
		try {
			text = tool.call(arguments != null ? arguments : new JsonObject());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			text = "The tool call was cancelled."; //$NON-NLS-1$
			failed = true;
		} catch (Exception | LinkageError e) {
			// ツールの失敗はプロトコルのエラーにせず、結果として Claude に伝える (Claude が原因を読んでやり直せるように)
			if (!(e instanceof IllegalArgumentException || e instanceof IllegalStateException)) {
				Activator.logError("MCP: ツール " + toolName + " が失敗しました", e); //$NON-NLS-1$ //$NON-NLS-2$
			}
			text = describe(e);
			failed = true;
		}
		JsonObject content = new JsonObject();
		content.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
		content.addProperty("text", text != null ? text : ""); //$NON-NLS-1$ //$NON-NLS-2$
		JsonArray contents = new JsonArray();
		contents.add(content);
		JsonObject result = new JsonObject();
		result.add("content", contents); //$NON-NLS-1$
		result.addProperty("isError", failed); //$NON-NLS-1$
		return result;
	}

	private static String describe(Throwable e) {
		// ツールが自分で投げた説明はそのまま伝える。それ以外は例外の種類も付ける
		String text = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : null;
		if (text == null) {
			text = e.toString();
		}
		Throwable cause = e.getCause();
		if (cause != null && cause != e) {
			text += " (caused by: " + cause + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return text;
	}

	private static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
		response.add("id", id); //$NON-NLS-1$
		response.add("result", result); //$NON-NLS-1$
		return response;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code); //$NON-NLS-1$
		error.addProperty("message", message); //$NON-NLS-1$
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
		response.add("id", id); //$NON-NLS-1$
		response.add("error", error); //$NON-NLS-1$
		return response;
	}

	private static String string(JsonObject obj, String key) {
		if (obj == null) {
			return null;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static JsonObject object(JsonObject obj, String key) {
		if (obj == null) {
			return null;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
