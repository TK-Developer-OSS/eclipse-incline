package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * Eclipse のコマンド (メニュー・ツールバー・キー操作の実体) を探すツールと実行するツール。
 * どのプラグインの機能でも、コマンドとして登録されていればここから動かせる。
 */
final class CommandTools {

	private static final int DEFAULT_LIMIT = 60;

	private CommandTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("list_commands", //$NON-NLS-1$
				"Search the commands this Eclipse provides. A command is the action behind a menu item, toolbar button or key binding, " //$NON-NLS-1$
						+ "contributed by any installed plug-in (save, build, format, refactor, run, debug, Git, ...). " //$NON-NLS-1$
						+ "Returns 'id - Name [Category]' with the parameter ids, and whether the command is disabled in the current context.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("filter", "Words that must all appear in the id, name, category or description (case-insensitive), e.g. 'save', 'toggle breakpoint', 'git commit'") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("limit", "Maximum number of commands to return (default " + DEFAULT_LIMIT + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.required("filter"), //$NON-NLS-1$
				args -> {
					String filter = Args.string(args, "filter"); //$NON-NLS-1$
					int limit = Args.optInt(args, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
					return Ui.call(() -> listCommands(filter, limit));
				}));

		server.register(new EclipseTool("execute_command", //$NON-NLS-1$
				"Run an Eclipse command, exactly as if the user had chosen it from a menu or pressed its key binding. " //$NON-NLS-1$
						+ "Commands act on the active part and its selection, so pass 'part' to activate the editor or view the command should act on. " //$NON-NLS-1$
						+ "Find the id and parameter ids with list_commands. The user is asked to approve each call.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				new Schema().string("commandId", "Command id, e.g. org.eclipse.ui.file.saveAll") //$NON-NLS-1$ //$NON-NLS-2$
						.stringMap("parameters", "Command parameters as { parameterId: value }") //$NON-NLS-1$ //$NON-NLS-2$
						.string("part", "What to activate before running: 'editor' for the front editor, or a view id") //$NON-NLS-1$ //$NON-NLS-2$
						.required("commandId"), //$NON-NLS-1$
				args -> {
					String id = Args.string(args, "commandId"); //$NON-NLS-1$
					Map<String, String> parameters = Args.optStringMap(args, "parameters"); //$NON-NLS-1$
					String part = Args.optString(args, "part"); //$NON-NLS-1$
					// ダイアログを開くコマンドは、ダイアログが閉じるまで戻ってこない。perform がそのことを Claude に伝える
					return UiTarget.perform(null, () -> execute(id, parameters, part));
				}));
	}

	private static String listCommands(String filter, int limit) {
		String[] terms = filter.toLowerCase(Locale.ROOT).split("\\s+"); //$NON-NLS-1$
		ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
		List<String> lines = new ArrayList<>();
		for (Command command : service.getDefinedCommands()) {
			try {
				String id = command.getId();
				String name = command.getName();
				String category = command.getCategory().getName();
				String description = command.getDescription();
				String haystack = (id + ' ' + name + ' ' + category + ' ' + (description == null ? "" : description)) //$NON-NLS-1$
						.toLowerCase(Locale.ROOT);
				if (!ViewTools.containsAll(haystack, terms)) {
					continue;
				}
				StringBuilder sb = new StringBuilder(id).append(" - ").append(name).append(" [").append(category) //$NON-NLS-1$ //$NON-NLS-2$
						.append(']');
				IParameter[] parameters = command.getParameters();
				if (parameters != null && parameters.length > 0) {
					sb.append(" parameters: "); //$NON-NLS-1$
					for (int i = 0; i < parameters.length; i++) {
						sb.append(i > 0 ? ", " : "").append(parameters[i].getId()) //$NON-NLS-1$ //$NON-NLS-2$
								.append(parameters[i].isOptional() ? " (optional)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
				if (!command.isEnabled()) {
					sb.append(" (disabled in the current context)"); //$NON-NLS-1$
				}
				lines.add(sb.toString());
			} catch (NotDefinedException e) {
				// 定義が外れたコマンドは出さない
			}
		}
		if (lines.isEmpty()) {
			return "No command matches \"" + filter + "\". Try a shorter or different word."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		Collections.sort(lines);
		StringBuilder sb = new StringBuilder();
		sb.append(lines.size()).append(lines.size() == 1 ? " command matches" : " commands match"); //$NON-NLS-1$ //$NON-NLS-2$
		if (lines.size() > limit) {
			sb.append(" (showing the first ").append(limit).append("; narrow the filter or raise limit)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append(":\n"); //$NON-NLS-1$
		for (int i = 0; i < lines.size() && i < limit; i++) {
			sb.append(lines.get(i)).append('\n');
		}
		return sb.toString();
	}

	private static String execute(String id, Map<String, String> parameters, String partId) throws Exception {
		IWorkbenchWindow window = Ui.window();
		if (partId != null) {
			activate(partId);
		}
		Command command = window.getService(ICommandService.class).getCommand(id);
		if (!command.isDefined()) {
			throw new IllegalArgumentException("Unknown command id: " + id + ". Call list_commands to find the id."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(command, parameters);
		if (parameterized == null) {
			throw new IllegalArgumentException(
					"The parameters do not match the command " + id + ". list_commands shows its parameter ids."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			Object result = window.getService(IHandlerService.class).executeCommand(parameterized, null);
			return "Executed " + command.getName() + " (" + id + ")" + (result == null ? "." : ". It returned: " + result); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		} catch (NotEnabledException | NotHandledException e) {
			throw new IllegalStateException("The command " + id + " is not available in the current context (active part: " //$NON-NLS-1$ //$NON-NLS-2$
					+ activePartName() + "). Activate the editor or view it applies to with the 'part' argument, " //$NON-NLS-1$
					+ "and make sure the right thing is selected there."); //$NON-NLS-1$
		}
	}

	private static void activate(String partId) throws Exception {
		IWorkbenchPage page = Ui.page();
		if ("editor".equals(partId)) { //$NON-NLS-1$
			IEditorPart editor = page.getActiveEditor();
			if (editor == null) {
				throw new IllegalStateException("No editor is open."); //$NON-NLS-1$
			}
			page.activate(editor);
		} else {
			page.showView(partId);
		}
	}

	private static String activePartName() {
		IWorkbenchPage page = Ui.window().getActivePage();
		IWorkbenchPart part = page == null ? null : page.getActivePart();
		return part == null ? "none" : part.getTitle(); //$NON-NLS-1$
	}
}
