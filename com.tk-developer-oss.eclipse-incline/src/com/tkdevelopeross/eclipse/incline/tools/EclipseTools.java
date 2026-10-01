package com.tkdevelopeross.eclipse.incline.tools;

import com.tkdevelopeross.eclipse.incline.Activator;
import com.tkdevelopeross.eclipse.incline.mcp.McpServer;

/**
 * Eclipse を見て操作するツール一式。Claude がファイルの読み書きだけでは届かない
 * Eclipse 自身の状態 (パースペクティブ・ビュー・エディタ・問題・コマンド) に触れられるようにする。
 */
public final class EclipseTools {

	public static final String SERVER_NAME = "eclipse"; //$NON-NLS-1$

	private static final String INSTRUCTIONS = "This server connects you to the user's running Eclipse IDE, so you can see and operate Eclipse itself " //$NON-NLS-1$
			+ "instead of working only from the files on disk.\n" //$NON-NLS-1$
			+ "- get_workbench_state: what the user is looking at (perspective, editors, views, selections). Call it first when a request depends on the IDE's state.\n" //$NON-NLS-1$
			+ "- read_view: what any open view currently displays (Problems, Console, Debug, Variables, Search, Git Staging, Outline, ...).\n" //$NON-NLS-1$
			+ "- list_perspectives / open_perspective, list_views / show_view, open_file: move around the workbench and show things to the user.\n" //$NON-NLS-1$
			+ "- get_problems: the errors and warnings Eclipse's builders report. Check it after you change code.\n" //$NON-NLS-1$
			+ "- refresh_workspace: after files change outside Eclipse (for example through Bash). Edit and Write are refreshed automatically.\n" //$NON-NLS-1$
			+ "- list_commands / execute_command: run anything Eclipse offers through a menu, toolbar or key binding " //$NON-NLS-1$
			+ "(save, build, format, refactor, run, debug, team operations, ...). Commands act on the active part and its selection.\n" //$NON-NLS-1$
			+ "- select_item / click / set_text / context_menu: operate what is inside a view or dialog the way the user would " //$NON-NLS-1$
			+ "(select, expand or double-click tree and table items, press buttons and toolbar buttons, fill fields, use right-click menus).\n" //$NON-NLS-1$
			+ "- main_menu / view_menu: browse and use Eclipse's menu bar and a view's drop-down menu, when you do not know a command id.\n" //$NON-NLS-1$
			+ "- read_dialog: read the dialog or wizard that is open. When an action opens a dialog, its result says so; " //$NON-NLS-1$
			+ "read it, then answer it with click / set_text / select_item using target 'dialog'.\n" //$NON-NLS-1$
			+ "- import_project: add a directory to the workspace as a project.\n" //$NON-NLS-1$
			+ "- list_launch_configurations / get_launch_configuration / set_launch_configuration / launch: create, change and run " //$NON-NLS-1$
			+ "Eclipse run/debug configurations. launch waits for the program and returns its exit code and console output; " //$NON-NLS-1$
			+ "get_launches, get_console_output and terminate_launch follow up on what is running.\n" //$NON-NLS-1$
			+ "- set_breakpoint / remove_breakpoint / list_breakpoints, get_debug_state, get_variable, debug_step: debug a program " //$NON-NLS-1$
			+ "(launch it in debug mode, then inspect the stack and variables and step).\n" //$NON-NLS-1$
			+ "- send_to_terminal: type into the terminal of Eclipse's Terminal view (local shell, SSH, serial) and get its output; " //$NON-NLS-1$
			+ "read_view reads the terminal without typing."; //$NON-NLS-1$

	private EclipseTools() {
	}

	public static McpServer createServer() {
		Activator activator = Activator.getDefault();
		String version = activator == null ? "0" : activator.getBundle().getVersion().toString(); //$NON-NLS-1$
		McpServer server = new McpServer(SERVER_NAME, version, INSTRUCTIONS);
		WorkbenchTools.register(server);
		ViewTools.register(server);
		WorkspaceTools.register(server);
		CommandTools.register(server);
		InteractionTools.register(server);
		LaunchTools.register(server);
		DebugTools.register(server);
		TerminalTools.register(server);
		return server;
	}

	/** claude が自分のツール (Edit / Write) でディスク上のファイルを書き換えたときに、ワークスペースに反映する。 */
	public static void refreshChangedFile(String osPath) {
		Workspaces.refreshChangedFile(osPath);
	}
}
