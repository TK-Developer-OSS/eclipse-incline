package com.tkdevelopeross.eclipse.incline.claude;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.tkdevelopeross.eclipse.incline.Activator;
import com.tkdevelopeross.eclipse.incline.backend.ChatBackend;
import com.tkdevelopeross.eclipse.incline.backend.ChatListener;
import com.tkdevelopeross.eclipse.incline.backend.FileChange;
import com.tkdevelopeross.eclipse.incline.backend.PermissionRequest;
import com.tkdevelopeross.eclipse.incline.mcp.McpServer;

/**
 * claude CLI を子プロセスで起動し、stream-json で会話する。
 * 許可要求は --permission-prompt-tool stdio により control_request (can_use_tool) として届き、
 * control_response で答える。
 * MCP サーバも同じ制御チャネルで公開する。起動直後に initialize で sdkMcpServers にサーバ名を伝えると、
 * claude が MCP の JSON-RPC を control_request (mcp_message) で送ってくるので、返事を mcp_response に入れて返す。
 */
public class ClaudeCliBackend implements ChatBackend {

	private static final int STDERR_TAIL_LINES = 20;

	private final String executable;
	private final File workingDirectory;
	private final McpServer mcpServer;
	private final String resumeSessionId;
	private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
	private final Object writeLock = new Object();
	private final Deque<String> stderrTail = new ArrayDeque<>();

	private volatile ChatListener listener;
	private volatile Process process;
	private volatile boolean stopping;
	private volatile ExecutorService mcpExecutor;
	private Writer stdin;

	/** @param mcpServer claude に公開する MCP サーバ。公開しないなら null */
	public ClaudeCliBackend(String executable, File workingDirectory, McpServer mcpServer) {
		this(executable, workingDirectory, mcpServer, null);
	}

	/**
	 * @param resumeSessionId 続きから始める会話のセッション ID (その会話を始めたときと同じ作業ディレクトリで起動すること)。新しく始めるなら null
	 */
	public ClaudeCliBackend(String executable, File workingDirectory, McpServer mcpServer, String resumeSessionId) {
		this.executable = executable;
		this.workingDirectory = workingDirectory;
		this.mcpServer = mcpServer;
		this.resumeSessionId = resumeSessionId;
	}

	@Override
	public void setListener(ChatListener listener) {
		this.listener = listener;
	}

	@Override
	public synchronized void start() throws IOException {
		if (isRunning()) {
			return;
		}
		List<String> command = new ArrayList<>();
		command.add(executable);
		command.add("-p"); //$NON-NLS-1$
		command.add("--input-format"); //$NON-NLS-1$
		command.add("stream-json"); //$NON-NLS-1$
		command.add("--output-format"); //$NON-NLS-1$
		command.add("stream-json"); //$NON-NLS-1$
		command.add("--verbose"); //$NON-NLS-1$
		command.add("--permission-prompt-tool"); //$NON-NLS-1$
		command.add("stdio"); //$NON-NLS-1$
		// 応答の文章を、できた分から stream_event で受け取る
		command.add("--include-partial-messages"); //$NON-NLS-1$
		if (resumeSessionId != null) {
			command.add("--resume"); //$NON-NLS-1$
			command.add(resumeSessionId);
		}

		ProcessBuilder pb = new ProcessBuilder(command);
		if (workingDirectory != null) {
			pb.directory(workingDirectory);
		}
		stopping = false;
		final Process p = pb.start();
		process = p;
		synchronized (writeLock) {
			stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
		}
		final Thread stderrThread = startThread("claude-stderr", () -> readStderr(p)); //$NON-NLS-1$
		startThread("claude-stdout", () -> readStdout(p, stderrThread)); //$NON-NLS-1$

		if (mcpServer != null) {
			// ツールの実行は UI スレッドを待つことがあるので、出力を読むスレッドを止めないように別スレッドで行う
			mcpExecutor = Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "claude-mcp"); //$NON-NLS-1$
				t.setDaemon(true);
				return t;
			});
			JsonArray servers = new JsonArray();
			servers.add(mcpServer.getName());
			JsonObject request = new JsonObject();
			request.addProperty("subtype", "initialize"); //$NON-NLS-1$ //$NON-NLS-2$
			request.add("sdkMcpServers", servers); //$NON-NLS-1$
			writeControlRequest(request);
		}
	}

	@Override
	public boolean isRunning() {
		Process p = process;
		return p != null && p.isAlive();
	}

	@Override
	public void sendUserMessage(String text) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
		content.addProperty("text", text); //$NON-NLS-1$
		JsonArray contents = new JsonArray();
		contents.add(content);

		JsonObject message = new JsonObject();
		message.addProperty("role", "user"); //$NON-NLS-1$ //$NON-NLS-2$
		message.add("content", contents); //$NON-NLS-1$

		JsonObject msg = new JsonObject();
		msg.addProperty("type", "user"); //$NON-NLS-1$ //$NON-NLS-2$
		msg.add("message", message); //$NON-NLS-1$
		msg.add("parent_tool_use_id", JsonNull.INSTANCE); //$NON-NLS-1$
		msg.addProperty("session_id", ""); //$NON-NLS-1$ //$NON-NLS-2$
		writeLine(msg);
	}

	@Override
	public void interrupt() {
		JsonObject request = new JsonObject();
		request.addProperty("subtype", "interrupt"); //$NON-NLS-1$ //$NON-NLS-2$
		writeControlRequest(request);
	}

	private void writeControlRequest(JsonObject request) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "control_request"); //$NON-NLS-1$ //$NON-NLS-2$
		msg.addProperty("request_id", UUID.randomUUID().toString()); //$NON-NLS-1$
		msg.add("request", request); //$NON-NLS-1$
		writeLine(msg);
	}

	@Override
	public void stop() {
		Process p = process;
		if (p == null) {
			return;
		}
		stopping = true;
		ExecutorService executor = mcpExecutor;
		if (executor != null) {
			executor.shutdownNow();
		}
		synchronized (writeLock) {
			try {
				if (stdin != null) {
					stdin.close();
				}
			} catch (IOException e) {
				// 終了させるので無視
			}
			stdin = null;
		}
		p.destroy();
		try {
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
		}
	}

	private Thread startThread(String name, Runnable r) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.start();
		return t;
	}

	private void readStdout(Process p, Thread stderrThread) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonObject msg;
				try {
					msg = gson.fromJson(line, JsonObject.class);
				} catch (JsonParseException e) {
					Activator.logInfo("claude: JSON ではない出力: " + line); //$NON-NLS-1$
					continue;
				}
				if (msg == null) {
					continue;
				}
				try {
					dispatch(msg);
				} catch (RuntimeException e) {
					Activator.logError("claude の出力の処理に失敗: " + line, e); //$NON-NLS-1$
				}
			}
		} catch (IOException e) {
			if (!stopping) {
				Activator.logError("claude の出力の読み込みに失敗", e); //$NON-NLS-1$
			}
		}

		int exitCode;
		try {
			exitCode = p.waitFor();
			stderrThread.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			exitCode = -1;
		}
		ChatListener l = listener;
		if (l != null) {
			l.onTerminated(exitCode, getStderrTail(), stopping);
		}
	}

	private void readStderr(Process p) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (stderrTail) {
					stderrTail.addLast(line);
					while (stderrTail.size() > STDERR_TAIL_LINES) {
						stderrTail.removeFirst();
					}
				}
			}
		} catch (IOException e) {
			// プロセス終了時に閉じられる
		}
	}

	private String getStderrTail() {
		synchronized (stderrTail) {
			return String.join("\n", stderrTail); //$NON-NLS-1$
		}
	}

	private void dispatch(JsonObject msg) {
		ChatListener l = listener;
		if (l == null) {
			return;
		}
		String type = getString(msg, "type"); //$NON-NLS-1$
		if (type == null) {
			return;
		}
		switch (type) {
		case "system": //$NON-NLS-1$
			if ("init".equals(getString(msg, "subtype"))) { //$NON-NLS-1$ //$NON-NLS-2$
				l.onSessionStarted(getString(msg, "session_id"), getString(msg, "model"), getString(msg, "cwd")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			break;
		case "assistant": //$NON-NLS-1$
			if (isSubagentMessage(msg)) {
				break;
			}
			for (JsonObject block : contentBlocks(msg)) {
				String blockType = getString(block, "type"); //$NON-NLS-1$
				if ("text".equals(blockType)) { //$NON-NLS-1$
					String text = getString(block, "text"); //$NON-NLS-1$
					if (text != null && !text.isEmpty()) {
						l.onAssistantText(text);
					}
				} else if ("tool_use".equals(blockType)) { //$NON-NLS-1$
					l.onToolUse(getString(block, "id"), getString(block, "name"), getObject(block, "input")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
			break;
		case "user": //$NON-NLS-1$
			if (!isSubagentMessage(msg)) {
				handleToolResults(l, msg);
			}
			break;
		case "result": //$NON-NLS-1$
			boolean isError = getBoolean(msg, "is_error"); //$NON-NLS-1$
			l.onTurnFinished(getString(msg, "subtype"), isError, isError ? getString(msg, "result") : null); //$NON-NLS-1$ //$NON-NLS-2$
			break;
		case "control_request": //$NON-NLS-1$
			handleControlRequest(l, msg);
			break;
		case "stream_event": //$NON-NLS-1$
			if (!isSubagentMessage(msg)) {
				JsonObject event = getObject(msg, "event"); //$NON-NLS-1$
				JsonObject delta = getObject(event, "delta"); //$NON-NLS-1$
				if ("content_block_delta".equals(getString(event, "type")) //$NON-NLS-1$ //$NON-NLS-2$
						&& "text_delta".equals(getString(delta, "type"))) { //$NON-NLS-1$ //$NON-NLS-2$
					String text = getString(delta, "text"); //$NON-NLS-1$
					if (text != null && !text.isEmpty()) {
						l.onAssistantTextDelta(text);
					}
				}
			}
			break;
		default:
			// control_response / rate_limit_event などは今は使わない
			break;
		}
	}

	private void handleToolResults(ChatListener l, JsonObject msg) {
		// tool_use_result はメッセージに 1 つなので、最初の tool_result にだけ付ける。エラーのときは文字列なので null になる
		FileChange change = fileChange(getObject(msg, "tool_use_result")); //$NON-NLS-1$
		for (JsonObject block : contentBlocks(msg)) {
			if ("tool_result".equals(getString(block, "type"))) { //$NON-NLS-1$ //$NON-NLS-2$
				boolean isError = getBoolean(block, "is_error"); //$NON-NLS-1$
				l.onToolResult(getString(block, "tool_use_id"), toolResultText(block.get("content")), isError, //$NON-NLS-1$ //$NON-NLS-2$
						isError ? null : change);
				change = null;
			}
		}
	}

	/** Edit / Write の結果に入っている structuredPatch を取り出す。ファイルを変更した結果でなければ null。 */
	private static FileChange fileChange(JsonObject result) {
		String filePath = getString(result, "filePath"); //$NON-NLS-1$
		JsonElement patch = result == null ? null : result.get("structuredPatch"); //$NON-NLS-1$
		if (filePath == null || patch == null || !patch.isJsonArray()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (JsonElement h : patch.getAsJsonArray()) {
			if (!h.isJsonObject()) {
				continue;
			}
			JsonObject hunk = h.getAsJsonObject();
			lines.add("@@ -" + getInt(hunk, "oldStart") + "," + getInt(hunk, "oldLines") + " +" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					+ getInt(hunk, "newStart") + "," + getInt(hunk, "newLines") + " @@"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			JsonElement hunkLines = hunk.get("lines"); //$NON-NLS-1$
			if (hunkLines == null || !hunkLines.isJsonArray()) {
				continue;
			}
			for (JsonElement e : hunkLines.getAsJsonArray()) {
				// "\ No newline at end of file" は出さない
				if (e.isJsonPrimitive() && !e.getAsString().startsWith("\\")) { //$NON-NLS-1$
					lines.add(e.getAsString());
				}
			}
		}
		boolean created = "create".equals(getString(result, "type")); //$NON-NLS-1$ //$NON-NLS-2$
		String content = getString(result, "content"); //$NON-NLS-1$
		if (created && content != null && !content.isEmpty()) {
			// 新規作成は structuredPatch が空なので、内容をすべて追加行にする
			String[] contentLines = content.split("\r?\n", -1); //$NON-NLS-1$
			int n = contentLines.length;
			if (contentLines[n - 1].isEmpty()) {
				n--;
			}
			for (int i = 0; i < n; i++) {
				lines.add("+" + contentLines[i]); //$NON-NLS-1$
			}
		}
		return new FileChange(filePath, created, lines);
	}

	private void handleControlRequest(ChatListener l, JsonObject msg) {
		final String requestId = getString(msg, "request_id"); //$NON-NLS-1$
		JsonObject request = getObject(msg, "request"); //$NON-NLS-1$
		String subtype = getString(request, "subtype"); //$NON-NLS-1$
		if ("mcp_message".equals(subtype)) { //$NON-NLS-1$
			handleMcpMessage(requestId, getString(request, "server_name"), getObject(request, "message")); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		if (!"can_use_tool".equals(subtype)) { //$NON-NLS-1$
			sendControlError(requestId, "unsupported control request: " + subtype); //$NON-NLS-1$
			return;
		}
		final JsonObject input = getObject(request, "input"); //$NON-NLS-1$
		PermissionRequest pr = new PermissionRequest(getString(request, "tool_name"), //$NON-NLS-1$
				getString(request, "description"), getString(request, "tool_use_id"), input, //$NON-NLS-1$ //$NON-NLS-2$
				(allow, denyMessage) -> sendPermissionResponse(requestId, input, allow, denyMessage));
		l.onPermissionRequest(pr);
	}

	/** claude から MCP サーバ宛ての JSON-RPC メッセージ。返事は mcp_response に入れて返す。 */
	private void handleMcpMessage(String requestId, String serverName, JsonObject message) {
		ExecutorService executor = mcpExecutor;
		if (mcpServer == null || executor == null || message == null || !mcpServer.getName().equals(serverName)) {
			sendControlError(requestId, "unknown MCP server: " + serverName); //$NON-NLS-1$
			return;
		}
		try {
			executor.execute(() -> {
				JsonObject reply;
				try {
					reply = mcpServer.handle(message);
				} catch (Error e) {
					// 返事をしないと claude が待ち続ける
					sendControlError(requestId, e.toString());
					throw e;
				}
				if (reply == null) {
					// 通知には返事がないが、制御チャネルの側は何か返す必要がある (claude の SDK と同じ形)
					reply = new JsonObject();
					reply.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
					reply.add("result", new JsonObject()); //$NON-NLS-1$
					reply.addProperty("id", 0); //$NON-NLS-1$
				}
				JsonObject payload = new JsonObject();
				payload.add("mcp_response", reply); //$NON-NLS-1$
				sendControlSuccess(requestId, payload);
			});
		} catch (RejectedExecutionException e) {
			// 停止中
		}
	}

	private void sendPermissionResponse(String requestId, JsonObject input, boolean allow, String denyMessage) {
		JsonObject decision = new JsonObject();
		if (allow) {
			decision.addProperty("behavior", "allow"); //$NON-NLS-1$ //$NON-NLS-2$
			decision.add("updatedInput", input); //$NON-NLS-1$
		} else {
			decision.addProperty("behavior", "deny"); //$NON-NLS-1$ //$NON-NLS-2$
			decision.addProperty("message", denyMessage != null ? denyMessage : "Denied by user."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sendControlSuccess(requestId, decision);
	}

	private void sendControlSuccess(String requestId, JsonObject payload) {
		JsonObject response = new JsonObject();
		response.addProperty("subtype", "success"); //$NON-NLS-1$ //$NON-NLS-2$
		response.addProperty("request_id", requestId); //$NON-NLS-1$
		response.add("response", payload); //$NON-NLS-1$
		writeControlResponse(response);
	}

	private void sendControlError(String requestId, String error) {
		JsonObject response = new JsonObject();
		response.addProperty("subtype", "error"); //$NON-NLS-1$ //$NON-NLS-2$
		response.addProperty("request_id", requestId); //$NON-NLS-1$
		response.addProperty("error", error); //$NON-NLS-1$
		writeControlResponse(response);
	}

	private void writeControlResponse(JsonObject response) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "control_response"); //$NON-NLS-1$ //$NON-NLS-2$
		msg.add("response", response); //$NON-NLS-1$
		writeLine(msg);
	}

	private void writeLine(JsonObject msg) {
		synchronized (writeLock) {
			if (stdin == null || !isRunning()) {
				reportError("claude が起動していません"); //$NON-NLS-1$
				return;
			}
			try {
				stdin.write(gson.toJson(msg));
				stdin.write('\n');
				stdin.flush();
			} catch (IOException e) {
				Activator.logError("claude への書き込みに失敗", e); //$NON-NLS-1$
				reportError("claude への書き込みに失敗しました: " + e.getMessage()); //$NON-NLS-1$
			}
		}
	}

	private void reportError(String message) {
		ChatListener l = listener;
		if (l != null) {
			l.onError(message);
		}
	}

	private static boolean isSubagentMessage(JsonObject msg) {
		JsonElement e = msg.get("parent_tool_use_id"); //$NON-NLS-1$
		return e != null && !e.isJsonNull();
	}

	private static List<JsonObject> contentBlocks(JsonObject msg) {
		List<JsonObject> blocks = new ArrayList<>();
		JsonObject message = getObject(msg, "message"); //$NON-NLS-1$
		if (message == null) {
			return blocks;
		}
		JsonElement content = message.get("content"); //$NON-NLS-1$
		if (content != null && content.isJsonArray()) {
			for (JsonElement e : content.getAsJsonArray()) {
				if (e.isJsonObject()) {
					blocks.add(e.getAsJsonObject());
				}
			}
		}
		return blocks;
	}

	/** tool_result の content は文字列か、{type:text, text} の配列。 */
	private static String toolResultText(JsonElement content) {
		if (content == null || content.isJsonNull()) {
			return ""; //$NON-NLS-1$
		}
		if (content.isJsonPrimitive()) {
			return content.getAsString();
		}
		if (content.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonElement e : content.getAsJsonArray()) {
				if (e.isJsonObject()) {
					String text = getString(e.getAsJsonObject(), "text"); //$NON-NLS-1$
					if (text != null) {
						if (sb.length() > 0) {
							sb.append('\n');
						}
						sb.append(text);
					}
				}
			}
			return sb.toString();
		}
		return content.toString();
	}

	static String getString(JsonObject obj, String key) {
		if (obj == null) {
			return null;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static boolean getBoolean(JsonObject obj, String key) {
		if (obj == null) {
			return false;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	static int getInt(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
	}

	static JsonObject getObject(JsonObject obj, String key) {
		if (obj == null) {
			return null;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
