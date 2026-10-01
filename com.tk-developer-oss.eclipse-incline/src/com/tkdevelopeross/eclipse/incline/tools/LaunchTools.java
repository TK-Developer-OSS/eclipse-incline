package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.TextConsole;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * 実行 / デバッグの構成を作る・直す、実行する、結果 (終了コードとコンソールの出力) を受け取るツール。
 * Eclipse の起動の仕組み (起動構成) をそのまま使うので、どの言語のプラグインの構成でも同じように扱える。
 */
final class LaunchTools {

	private static final int DEFAULT_WAIT_SECONDS = 30;
	private static final int MAX_WAIT_SECONDS = 600;
	private static final int DEFAULT_OUTPUT_CHARS = 8000;
	/** 起動の準備 (ビルドなど) にかけてよい時間。 */
	private static final int LAUNCH_TIMEOUT_SECONDS = 180;
	private static final String[] MODES = { ILaunchManager.RUN_MODE, ILaunchManager.DEBUG_MODE, ILaunchManager.PROFILE_MODE };

	private LaunchTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("list_launch_configurations", //$NON-NLS-1$
				"List the run/debug configurations that exist in this Eclipse (the entries of Run > Run Configurations...), with their type and modes. " //$NON-NLS-1$
						+ "With types=true it also lists the configuration types that can be created (Java Application, C/C++ Application, PHP CLI Application, " //$NON-NLS-1$
						+ "external Program, ...), which depend on the installed plug-ins.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("filter", "Words that must all appear in the name or type (case-insensitive)") //$NON-NLS-1$ //$NON-NLS-2$
						.bool("types", "Also list the configuration types (default false)"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> list(Args.optString(args, "filter"), Args.optBool(args, "types", false)))); //$NON-NLS-1$ //$NON-NLS-2$

		server.register(new EclipseTool("get_launch_configuration", //$NON-NLS-1$
				"Show one run/debug configuration: its type and all its attributes (project, main class or program, arguments, working directory, environment, ...). " //$NON-NLS-1$
						+ "Read an existing configuration of the same type to learn the attribute names before creating a new one.", //$NON-NLS-1$
				ToolEffect.READ, new Schema().string("name", "Configuration name").required("name"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> describe(require(Args.string(args, "name"))))); //$NON-NLS-1$

		server.register(new EclipseTool("set_launch_configuration", //$NON-NLS-1$
				"Create a run/debug configuration, or change attributes of an existing one. Only the attributes you pass are changed. " //$NON-NLS-1$
						+ "Attribute names depend on the type; examples: Java Application uses org.eclipse.jdt.launching.PROJECT_ATTR, .MAIN_TYPE, .PROGRAM_ARGUMENTS, .VM_ARGUMENTS, .WORKING_DIRECTORY; " //$NON-NLS-1$
						+ "external Program (org.eclipse.ui.externaltools.ProgramLaunchConfigurationType) uses org.eclipse.ui.externaltools.ATTR_LOCATION, .ATTR_TOOL_ARGUMENTS, .ATTR_WORKING_DIRECTORY; " //$NON-NLS-1$
						+ "environment variables go in org.eclipse.debug.core.environmentVariables (an object); " //$NON-NLS-1$
						+ "if the console output is garbled, set org.eclipse.debug.ui.ATTR_CONSOLE_ENCODING (e.g. MS932 for Windows console programs on Japanese Windows). " //$NON-NLS-1$
						+ "The user is asked to approve.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				new Schema().string("name", "Configuration name") //$NON-NLS-1$ //$NON-NLS-2$
						.string("type", "Configuration type id, required when creating (see list_launch_configurations with types=true)") //$NON-NLS-1$ //$NON-NLS-2$
						.object("attributes", "Attributes to set: strings, booleans, integers, arrays of strings, or objects with string values. null removes an attribute") //$NON-NLS-1$ //$NON-NLS-2$
						.required("name"), //$NON-NLS-1$
				args -> set(Args.string(args, "name"), Args.optString(args, "type"), //$NON-NLS-1$ //$NON-NLS-2$
						args.has("attributes") && args.get("attributes").isJsonObject() ? args.getAsJsonObject("attributes") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
								: new JsonObject())));

		server.register(new EclipseTool("launch", //$NON-NLS-1$
				"Run or debug a configuration, as Run > Run / Debug does (the workspace is built first). " //$NON-NLS-1$
						+ "By default it waits until the program ends and returns the exit code and the console output. " //$NON-NLS-1$
						+ "In debug mode it returns as soon as a thread stops at a breakpoint; continue with get_debug_state and debug_step. " //$NON-NLS-1$
						+ "If the program is still running when the wait ends, read more later with get_console_output. The user is asked to approve.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				new Schema().string("name", "Configuration name") //$NON-NLS-1$ //$NON-NLS-2$
						.choice("mode", "run (default), debug or profile", MODES) //$NON-NLS-1$ //$NON-NLS-2$
						.integer("waitSeconds", "How long to wait for the program to end or stop at a breakpoint (default " //$NON-NLS-1$ //$NON-NLS-2$
								+ DEFAULT_WAIT_SECONDS + ", 0 = return immediately)") //$NON-NLS-1$
						.integer("maxOutputChars", "How much console output to return (default " + DEFAULT_OUTPUT_CHARS + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.required("name"), //$NON-NLS-1$
				args -> launch(Args.string(args, "name"), optMode(args), //$NON-NLS-1$
						Math.min(Args.optInt(args, "waitSeconds", DEFAULT_WAIT_SECONDS), MAX_WAIT_SECONDS), //$NON-NLS-1$
						Args.optInt(args, "maxOutputChars", DEFAULT_OUTPUT_CHARS)))); //$NON-NLS-1$

		server.register(new EclipseTool("get_launches", //$NON-NLS-1$
				"List what has been launched in this Eclipse session (the entries of the Debug view): running or terminated, exit codes, " //$NON-NLS-1$
						+ "and for debug sessions whether threads are suspended and where.", //$NON-NLS-1$
				ToolEffect.READ, new Schema(), args -> launches()));

		server.register(new EclipseTool("get_console_output", //$NON-NLS-1$
				"Return the console output (stdout and stderr) of a launched program, and whether it is still running.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("name", "Configuration name of the launch. Default: the most recent launch") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("maxOutputChars", "How much output to return (default " + DEFAULT_OUTPUT_CHARS + "; the beginning and the end are kept)"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> {
					ILaunch launch = findLaunch(Args.optString(args, "name")); //$NON-NLS-1$
					return report(launch, Args.optInt(args, "maxOutputChars", DEFAULT_OUTPUT_CHARS)); //$NON-NLS-1$
				}));

		server.register(new EclipseTool("terminate_launch", //$NON-NLS-1$
				"Stop a running program or debug session. The user is asked to approve.", //$NON-NLS-1$
				ToolEffect.CHANGE, new Schema().string("name", "Configuration name of the launch. Default: the most recent launch"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					ILaunch launch = findLaunch(Args.optString(args, "name")); //$NON-NLS-1$
					if (launch.isTerminated()) {
						return launchName(launch) + " has already ended."; //$NON-NLS-1$
					}
					launch.terminate();
					waitFor(launch, 5, false);
					return launchName(launch) + (launch.isTerminated() ? " was terminated." : " was asked to terminate but is still running."); //$NON-NLS-1$ //$NON-NLS-2$
				}));
	}

	static ILaunchManager manager() {
		return DebugPlugin.getDefault().getLaunchManager();
	}

	private static String optMode(JsonObject args) {
		String mode = Args.optString(args, "mode"); //$NON-NLS-1$
		return mode == null ? ILaunchManager.RUN_MODE : mode;
	}

	// ---- 構成 ----

	private static String list(String filter, boolean withTypes) throws CoreException {
		String[] terms = filter == null ? new String[0] : filter.toLowerCase(Locale.ROOT).split("\\s+"); //$NON-NLS-1$
		List<String> lines = new ArrayList<>();
		for (ILaunchConfiguration config : manager().getLaunchConfigurations()) {
			ILaunchConfigurationType type = config.getType();
			String line = config.getName() + "  [" + type.getName() + ": " + type.getIdentifier() + "]  modes: " + modes(type); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (ViewTools.containsAll(line.toLowerCase(Locale.ROOT), terms)) {
				lines.add(line);
			}
		}
		Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
		StringBuilder sb = new StringBuilder();
		sb.append(lines.size()).append(lines.size() == 1 ? " launch configuration" : " launch configurations") //$NON-NLS-1$ //$NON-NLS-2$
				.append(filter == null ? "" : " matching \"" + filter + "\"").append(lines.isEmpty() ? ".\n" : ":\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		for (String line : lines) {
			sb.append("  ").append(line).append('\n'); //$NON-NLS-1$
		}
		if (withTypes) {
			List<String> types = new ArrayList<>();
			for (ILaunchConfigurationType type : manager().getLaunchConfigurationTypes()) {
				String line = type.getIdentifier() + " - " + type.getName() + " (" + modes(type) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				if (type.isPublic() && ViewTools.containsAll(line.toLowerCase(Locale.ROOT), terms)) {
					types.add(line);
				}
			}
			Collections.sort(types, String.CASE_INSENSITIVE_ORDER);
			sb.append("Configuration types that can be created (id - name):\n"); //$NON-NLS-1$
			for (String line : types) {
				sb.append("  ").append(line).append('\n'); //$NON-NLS-1$
			}
		} else if (lines.isEmpty()) {
			sb.append("Pass types=true to see which kinds of configuration can be created with set_launch_configuration.\n"); //$NON-NLS-1$
		}
		return sb.toString();
	}

	private static String modes(ILaunchConfigurationType type) {
		List<String> modes = new ArrayList<>();
		for (String mode : MODES) {
			if (type.supportsMode(mode)) {
				modes.add(mode);
			}
		}
		return String.join(", ", modes); //$NON-NLS-1$
	}

	static ILaunchConfiguration find(String name) throws CoreException {
		for (ILaunchConfiguration config : manager().getLaunchConfigurations()) {
			if (config.getName().equals(name)) {
				return config;
			}
		}
		return null;
	}

	private static ILaunchConfiguration require(String name) throws CoreException {
		ILaunchConfiguration config = find(name);
		if (config == null) {
			throw new IllegalArgumentException("There is no launch configuration named \"" + name //$NON-NLS-1$
					+ "\". Call list_launch_configurations to see the existing ones."); //$NON-NLS-1$
		}
		return config;
	}

	private static String describe(ILaunchConfiguration config) throws CoreException {
		ILaunchConfigurationType type = config.getType();
		StringBuilder sb = new StringBuilder();
		sb.append("Name: ").append(config.getName()).append('\n'); //$NON-NLS-1$
		sb.append("Type: ").append(type.getName()).append(" (").append(type.getIdentifier()).append("), modes: ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append(modes(type)).append('\n');
		sb.append("Stored: ").append(config.getFile() != null ? "as the workspace file " + config.getFile().getFullPath() //$NON-NLS-1$ //$NON-NLS-2$
				: "in the workspace metadata (not a project file)").append('\n'); //$NON-NLS-1$
		Map<String, Object> attributes = new TreeMap<>(config.getAttributes());
		sb.append("Attributes:").append(attributes.isEmpty() ? " (none)\n" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
			sb.append("  ").append(attribute.getKey()).append(" = ").append(attribute.getValue()).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return sb.toString();
	}

	private static String set(String name, String typeId, JsonObject attributes) throws CoreException {
		ILaunchConfiguration existing = find(name);
		ILaunchConfigurationWorkingCopy copy;
		if (existing != null) {
			if (typeId != null && !existing.getType().getIdentifier().equals(typeId)) {
				throw new IllegalArgumentException("\"" + name + "\" already exists with the type " //$NON-NLS-1$ //$NON-NLS-2$
						+ existing.getType().getIdentifier() + ". Use another name for a configuration of a different type."); //$NON-NLS-1$
			}
			copy = existing.getWorkingCopy();
		} else {
			if (typeId == null) {
				throw new IllegalArgumentException("\"" + name //$NON-NLS-1$
						+ "\" does not exist yet. To create it, pass its type (list_launch_configurations with types=true lists the type ids)."); //$NON-NLS-1$
			}
			ILaunchConfigurationType type = manager().getLaunchConfigurationType(typeId);
			if (type == null) {
				throw new IllegalArgumentException("Unknown configuration type: " + typeId //$NON-NLS-1$
						+ ". list_launch_configurations with types=true lists the type ids."); //$NON-NLS-1$
			}
			copy = type.newInstance(null, name);
		}
		for (Map.Entry<String, JsonElement> attribute : attributes.entrySet()) {
			setAttribute(copy, attribute.getKey(), attribute.getValue());
		}
		ILaunchConfiguration saved = copy.doSave();
		return (existing == null ? "Created" : "Updated") + " the launch configuration.\n" + describe(saved); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** JSON の値の型に合わせて属性を入れる (文字列 / 真偽 / 整数 / 文字列のリスト / 文字列のマップ)。 */
	private static void setAttribute(ILaunchConfigurationWorkingCopy copy, String key, JsonElement value) {
		if (value.isJsonNull()) {
			copy.removeAttribute(key);
		} else if (value.isJsonArray()) {
			List<String> list = new ArrayList<>();
			for (JsonElement item : value.getAsJsonArray()) {
				list.add(item.getAsString());
			}
			copy.setAttribute(key, list);
		} else if (value.isJsonObject()) {
			Map<String, String> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				map.put(entry.getKey(), entry.getValue().getAsString());
			}
			copy.setAttribute(key, map);
		} else if (value.getAsJsonPrimitive().isBoolean()) {
			copy.setAttribute(key, value.getAsBoolean());
		} else if (value.getAsJsonPrimitive().isNumber() && value.getAsString().matches("-?\\d+")) { //$NON-NLS-1$
			copy.setAttribute(key, value.getAsInt());
		} else {
			copy.setAttribute(key, value.getAsString());
		}
	}

	// ---- 実行 ----

	private static String launch(String name, String mode, int waitSeconds, int maxChars) throws Exception {
		ILaunchConfiguration config = require(name);
		if (!config.supportsMode(mode)) {
			throw new IllegalArgumentException("\"" + name + "\" (" + config.getType().getName() + ") cannot be launched in " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ mode + " mode. Its modes: " + modes(config.getType()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		int unsaved = Ui.call(() -> Ui.page().getDirtyEditors().length);

		// 起動の準備 (ビルド、確認のダイアログ) は時間がかかったり止まったりするので、別スレッドで行って様子を見る
		CompletableFuture<ILaunch> launching = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				launching.complete(DebugUITools.buildAndLaunch(config, mode, new NullProgressMonitor()));
			} catch (Throwable t) {
				launching.completeExceptionally(t);
			}
		}, "incline-launch"); //$NON-NLS-1$
		thread.setDaemon(true);
		thread.start();

		ILaunch launch = null;
		long start = System.currentTimeMillis();
		while (launch == null) {
			try {
				launch = launching.get(500, TimeUnit.MILLISECONDS);
			} catch (ExecutionException e) {
				throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
			} catch (TimeoutException e) {
				long waited = System.currentTimeMillis() - start;
				String dialog = waited > 2000 ? UiTarget.probeDialogTitle() : null;
				if (dialog != null) {
					return "The launch is waiting for the dialog \"" + dialog //$NON-NLS-1$
							+ "\". Use read_dialog to see it, and click with target 'dialog' to answer it. Then check get_launches."; //$NON-NLS-1$
				}
				if (waited > LAUNCH_TIMEOUT_SECONDS * 1000L) {
					throw new TimeoutException("The launch did not start within " + LAUNCH_TIMEOUT_SECONDS //$NON-NLS-1$
							+ " seconds (the build before launching may still be running)."); //$NON-NLS-1$
				}
			}
		}
		waitFor(launch, waitSeconds, true);
		StringBuilder sb = new StringBuilder();
		sb.append("Launched \"").append(name).append("\" in ").append(mode).append(" mode.\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (unsaved > 0) {
			sb.append("Note: ").append(unsaved) //$NON-NLS-1$
					.append(unsaved == 1 ? " editor has" : " editors have") //$NON-NLS-1$ //$NON-NLS-2$
					.append(" unsaved changes, which the launched program does not include.\n"); //$NON-NLS-1$
		}
		sb.append(report(launch, maxChars));
		return sb.toString();
	}

	/**
	 * 終わるまで待つ。
	 *
	 * @param stopOnSuspend スレッドが (ブレークポイントなどで) 止まったら、そこで待つのをやめる
	 */
	static void waitFor(ILaunch launch, int seconds, boolean stopOnSuspend) throws InterruptedException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (!launch.isTerminated() && System.currentTimeMillis() < deadline) {
			// 起動の直後は、デバッガが準備のためにスレッドを一瞬止める。スタックがあって、少し後でも止まったままのものだけを「止まった」とみなす
			if (stopOnSuspend && DebugTools.isStopped(launch)) {
				Thread.sleep(300);
				if (DebugTools.isStopped(launch)) {
					return;
				}
			}
			Thread.sleep(200);
		}
	}

	static ILaunch findLaunch(String name) {
		ILaunch[] launches = manager().getLaunches();
		for (int i = launches.length - 1; i >= 0; i--) {
			if (name == null || name.equals(launchName(launches[i]))) {
				return launches[i];
			}
		}
		throw new IllegalStateException(name == null ? "Nothing has been launched in this Eclipse session yet." //$NON-NLS-1$
				: "There is no launch of \"" + name + "\". Call get_launches to see the launches."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String launchName(ILaunch launch) {
		ILaunchConfiguration config = launch.getLaunchConfiguration();
		return config == null ? "(unnamed launch)" : config.getName(); //$NON-NLS-1$
	}

	private static String launches() {
		ILaunch[] launches = manager().getLaunches();
		if (launches.length == 0) {
			return "Nothing has been launched in this Eclipse session yet."; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		for (ILaunch launch : launches) {
			sb.append('"').append(launchName(launch)).append("\" [").append(launch.getLaunchMode()).append("]: ") //$NON-NLS-1$ //$NON-NLS-2$
					.append(state(launch)).append('\n');
		}
		return sb.toString();
	}

	/** 「終了 (終了コード)」「実行中」「main が Main.main の 6 行目で停止中」のような 1 行。 */
	static String state(ILaunch launch) {
		if (launch.isTerminated()) {
			List<String> codes = new ArrayList<>();
			for (IProcess process : launch.getProcesses()) {
				try {
					codes.add(String.valueOf(process.getExitValue()));
				} catch (DebugException e) {
					// 終了コードを持たないプロセス
				}
			}
			return "terminated" + (codes.isEmpty() ? "" : ", exit code " + String.join(", ", codes)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		IThread suspended = DebugTools.isStopped(launch) ? DebugTools.suspendedThread(launch, null) : null;
		if (suspended != null) {
			return "suspended: " + DebugTools.describeLocation(suspended); //$NON-NLS-1$
		}
		boolean processAlive = false;
		for (IProcess process : launch.getProcesses()) {
			processAlive |= !process.isTerminated();
		}
		if (ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()) && launch.getDebugTargets().length == 0) {
			// 標準のデバッグモデルを使わないデバッガ (C/C++ の GDB 連携) は、止まっているかどうかをここでは判定できない
			return "debugging (call get_debug_state to see whether it is suspended and where)"; //$NON-NLS-1$
		}
		// デバッグの接続だけが先に切れて、プロセスの終了を待っている状態
		return processAlive || launch.getProcesses().length == 0 ? "running" : "ending"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String report(ILaunch launch, int maxChars) throws InterruptedException {
		StringBuilder sb = new StringBuilder();
		sb.append("State: ").append(state(launch)).append('\n'); //$NON-NLS-1$
		IProcess[] processes = launch.getProcesses();
		if (processes.length == 0) {
			sb.append("(This launch has no process with console output.)\n"); //$NON-NLS-1$
		}
		for (IProcess process : processes) {
			String output = consoleText(process);
			sb.append("--- console output of ").append(process.getLabel()); //$NON-NLS-1$
			if (output.isEmpty()) {
				sb.append(": (none) ---\n"); //$NON-NLS-1$
			} else {
				sb.append(" (").append(output.length()).append(" chars) ---\n").append(Ui.clip(output, maxChars)); //$NON-NLS-1$ //$NON-NLS-2$
				if (!output.endsWith("\n")) { //$NON-NLS-1$
					sb.append('\n');
				}
				sb.append("--- end of output ---\n"); //$NON-NLS-1$
			}
		}
		return sb.toString();
	}

	/**
	 * プロセスのコンソールに出ている文字 (標準出力と標準エラー)。
	 * コンソールは出力を少し遅れて表示するので、増えなくなるまで短く待つ。
	 */
	private static String consoleText(IProcess process) throws InterruptedException {
		IConsole console = DebugUITools.getConsole(process);
		if (!(console instanceof TextConsole)) {
			// コンソールがまだ無い (または文字のコンソールではない) ときは、プロセスが溜めている出力を使う
			IStreamsProxy streams = process.getStreamsProxy();
			if (streams == null) {
				return ""; //$NON-NLS-1$
			}
			String out = streams.getOutputStreamMonitor() == null ? "" : streams.getOutputStreamMonitor().getContents(); //$NON-NLS-1$
			String err = streams.getErrorStreamMonitor() == null ? "" : streams.getErrorStreamMonitor().getContents(); //$NON-NLS-1$
			return out + err;
		}
		TextConsole text = (TextConsole) console;
		int length = text.getDocument().getLength();
		for (int i = 0; i < 10; i++) {
			Thread.sleep(150);
			int now = text.getDocument().getLength();
			if (now == length && i > 0) {
				break;
			}
			length = now;
		}
		return text.getDocument().get();
	}
}
