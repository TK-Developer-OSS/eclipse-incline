package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * デバッグのツール: ブレークポイント、止まっている場所と変数の読み取り、ステップ実行。
 * Eclipse の標準のデバッグモデル (スレッド・スタックフレーム・変数) を使うので、このモデルに沿ったデバッガ (Java、PHP など) なら共通で動く。
 * 標準のモデルを使わないデバッガ (C/C++ の GDB 連携) では、ステップ実行は Eclipse のコマンドで行い、
 * 状態は Debug ビューと Variables ビューを read_view で読む。
 */
final class DebugTools {

	private static final int DEFAULT_FRAMES = 10;
	private static final int MAX_VARIABLES = 60;
	private static final int MAX_VALUE_CHARS = 300;
	private static final int DEFAULT_STEP_WAIT_SECONDS = 10;
	private static final int BREAKPOINT_WAIT_MILLIS = 4000;
	private static final String DEBUG_VIEW = "org.eclipse.debug.ui.DebugView"; //$NON-NLS-1$
	private static final String VARIABLES_VIEW = "org.eclipse.debug.ui.VariableView"; //$NON-NLS-1$
	private static final long VIEW_FILL_MILLIS = 900;
	private static final String GENERIC_HINT = "This debugger does not report through Eclipse's standard debug model; " //$NON-NLS-1$
			+ "call get_debug_state to see where it stopped."; //$NON-NLS-1$

	private static final String RESUME = "resume"; //$NON-NLS-1$
	private static final String SUSPEND = "suspend"; //$NON-NLS-1$
	private static final String STEP_OVER = "step_over"; //$NON-NLS-1$
	private static final String STEP_INTO = "step_into"; //$NON-NLS-1$
	private static final String STEP_RETURN = "step_return"; //$NON-NLS-1$

	private DebugTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("set_breakpoint", //$NON-NLS-1$
				"Set a line breakpoint in a source file of the workspace, the same way double-clicking the editor's left margin does. " //$NON-NLS-1$
						+ "It works for every language whose editor supports breakpoints. The file is opened in an editor.", //$NON-NLS-1$
				ToolEffect.NAVIGATE,
				new Schema().string("path", "The source file: absolute file system path or workspace path") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("line", "1-based line number").required("path", "line"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				args -> setBreakpoint(Args.string(args, "path"), Args.optInt(args, "line", 0)))); //$NON-NLS-1$ //$NON-NLS-2$

		server.register(new EclipseTool("remove_breakpoint", //$NON-NLS-1$
				"Remove the line breakpoint at a file and line.", ToolEffect.NAVIGATE, //$NON-NLS-1$
				new Schema().string("path", "The source file: absolute file system path or workspace path") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("line", "1-based line number").required("path", "line"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				args -> {
					IFile file = file(Args.string(args, "path")); //$NON-NLS-1$
					int line = Args.optInt(args, "line", 0); //$NON-NLS-1$
					List<IBreakpoint> found = breakpointsAt(file, line);
					if (found.isEmpty()) {
						return "There is no breakpoint at " + file.getFullPath() + " line " + line + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					}
					for (IBreakpoint breakpoint : found) {
						breakpoint.delete();
					}
					return "Removed the breakpoint at " + file.getFullPath() + " line " + line + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}));

		server.register(new EclipseTool("list_breakpoints", //$NON-NLS-1$
				"List all breakpoints (file, line, enabled or not).", ToolEffect.READ, new Schema(), //$NON-NLS-1$
				args -> listBreakpoints()));

		server.register(new EclipseTool("get_debug_state", //$NON-NLS-1$
				"Show where the debugged programs are: each debug session, its suspended threads with their call stack, and the variables of the top frame. " //$NON-NLS-1$
						+ "Call it after launch in debug mode or after debug_step.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().integer("frames", "How many stack frames to show per suspended thread (default " + DEFAULT_FRAMES + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.bool("variables", "Show the variables of the top frame (default true)"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> debugState(Args.optInt(args, "frames", DEFAULT_FRAMES), Args.optBool(args, "variables", true)))); //$NON-NLS-1$ //$NON-NLS-2$

		server.register(new EclipseTool("get_variable", //$NON-NLS-1$
				"Look inside a variable of a suspended thread: its value and its fields or elements one level down. " //$NON-NLS-1$
						+ "Give the path from a variable of the stack frame, e.g. [\"this\", \"items\", \"[0]\"].", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().stringList("path", "Variable name followed by field / element names") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("frame", "Stack frame index, 0 = top (default 0)") //$NON-NLS-1$ //$NON-NLS-2$
						.string("thread", "Thread name. Default: the first suspended thread").required("path"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> variable(Args.optStringList(args, "path"), Args.optInt(args, "frame", 0), //$NON-NLS-1$ //$NON-NLS-2$
						Args.optString(args, "thread")))); //$NON-NLS-1$

		server.register(new EclipseTool("debug_step", //$NON-NLS-1$
				"Control a debug session: resume, suspend, or step over / into / out of (step_return) the current line. " //$NON-NLS-1$
						+ "It waits until the thread stops again and returns the new location.", //$NON-NLS-1$
				ToolEffect.NAVIGATE,
				new Schema().choice("action", "What to do", RESUME, SUSPEND, STEP_OVER, STEP_INTO, STEP_RETURN) //$NON-NLS-1$ //$NON-NLS-2$
						.string("thread", "Thread name. Default: the first suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("waitSeconds", "How long to wait for the thread to stop again (default " + DEFAULT_STEP_WAIT_SECONDS + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.required("action"), //$NON-NLS-1$
				args -> step(Args.string(args, "action"), Args.optString(args, "thread"), //$NON-NLS-1$ //$NON-NLS-2$
						Args.optInt(args, "waitSeconds", DEFAULT_STEP_WAIT_SECONDS)))); //$NON-NLS-1$
	}

	// ---- ブレークポイント ----

	private static IFile file(String path) {
		IResource resource = Workspaces.resolve(path);
		if (!(resource instanceof IFile)) {
			throw new IllegalArgumentException(path + " is not a file of the workspace."); //$NON-NLS-1$
		}
		return (IFile) resource;
	}

	private static List<IBreakpoint> breakpointsAt(IFile file, int line) {
		List<IBreakpoint> found = new ArrayList<>();
		for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
			IMarker marker = breakpoint.getMarker();
			if (marker != null && marker.exists() && file.equals(marker.getResource())
					&& marker.getAttribute(IMarker.LINE_NUMBER, -1) == line) {
				found.add(breakpoint);
			}
		}
		return found;
	}

	private static String setBreakpoint(String path, int line) throws Exception {
		IFile file = file(path);
		if (line < 1) {
			throw new IllegalArgumentException("line must be 1 or greater."); //$NON-NLS-1$
		}
		if (!breakpointsAt(file, line).isEmpty()) {
			return "There already is a breakpoint at " + file.getFullPath() + " line " + line + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		// ブレークポイントの作り方は言語ごとに違うので、その言語のエディタに「この行で切り替えて」と頼む
		Ui.call(() -> {
			IEditorPart editor = IDE.openEditor(Ui.page(), file, false);
			ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
			IDocument document = textEditor == null || textEditor.getDocumentProvider() == null ? null
					: textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
			if (document == null) {
				throw new IllegalStateException("The editor for " + file.getName() + " is not a text editor, so a line breakpoint cannot be set."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (line > document.getNumberOfLines()) {
				throw new IllegalArgumentException(file.getName() + " has only " + document.getNumberOfLines() + " lines."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			IRegion region = document.getLineInformation(line - 1);
			ITextSelection selection = new TextSelection(document, region.getOffset(), 0);
			IToggleBreakpointsTarget target = DebugUITools.getToggleBreakpointsTargetManager()
					.getToggleBreakpointsTarget(editor, selection);
			if (target == null || !target.canToggleLineBreakpoints(editor, selection)) {
				throw new IllegalStateException("The editor for " + file.getName() + " does not support line breakpoints."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			target.toggleLineBreakpoints(editor, selection);
			return null;
		});
		// 作成はバックグラウンドで行われることがあるので、できるのを待つ
		long deadline = System.currentTimeMillis() + BREAKPOINT_WAIT_MILLIS;
		while (System.currentTimeMillis() < deadline) {
			if (!breakpointsAt(file, line).isEmpty()) {
				return "Set a breakpoint at " + file.getFullPath() + " line " + line + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			Thread.sleep(150);
		}
		// デバッガが、実行できる行に合わせて位置をずらすことがある
		StringBuilder others = new StringBuilder();
		for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
			IMarker marker = breakpoint.getMarker();
			if (marker != null && marker.exists() && file.equals(marker.getResource())) {
				others.append(others.length() > 0 ? ", " : "").append(marker.getAttribute(IMarker.LINE_NUMBER, -1)); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		throw new IllegalStateException("No breakpoint appeared at line " + line + " of " + file.getName() //$NON-NLS-1$ //$NON-NLS-2$
				+ " (the line may not be executable). Breakpoints in this file are now at lines: " //$NON-NLS-1$
				+ (others.length() == 0 ? "none" : others) + "."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String listBreakpoints() throws CoreException {
		IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
		if (breakpoints.length == 0) {
			return "There are no breakpoints."; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		for (IBreakpoint breakpoint : breakpoints) {
			IMarker marker = breakpoint.getMarker();
			if (marker == null || !marker.exists()) {
				continue;
			}
			int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
			sb.append(Workspaces.location(marker.getResource()));
			if (line > 0) {
				sb.append(" line ").append(line); //$NON-NLS-1$
			}
			String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
			if (!message.isEmpty()) {
				sb.append(" (").append(message).append(')'); //$NON-NLS-1$
			}
			sb.append(breakpoint.isEnabled() ? "" : " [disabled]").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return sb.toString();
	}

	// ---- 状態 ----

	/**
	 * @param threadName 探すスレッドの名前。null なら最初に見つかった、止まっているスレッド
	 * @return 標準のデバッグモデルで見つかる、止まっているスレッド。なければ null
	 */
	static IThread suspendedThread(ILaunch launch, String threadName) {
		for (IDebugTarget target : launch.getDebugTargets()) {
			if (target.isTerminated() || target.isDisconnected()) {
				continue;
			}
			try {
				for (IThread thread : target.getThreads()) {
					if (thread.isSuspended() && (threadName == null || threadName.equals(thread.getName()))) {
						return thread;
					}
				}
			} catch (DebugException e) {
				// スレッドを答えられない状態 (起動中・終了中)
			}
		}
		return null;
	}

	/** @return ブレークポイントなどで止まっていて、スタックを読める状態のスレッドがあれば true */
	static boolean isStopped(ILaunch launch) {
		IThread thread = suspendedThread(launch, null);
		try {
			return thread != null && thread.hasStackFrames();
		} catch (DebugException e) {
			return false;
		}
	}

	/** @return デバッグモードで起動して、まだ終わっていないものがあれば true */
	private static boolean hasLiveDebugSession() {
		for (ILaunch launch : LaunchTools.manager().getLaunches()) {
			if (!launch.isTerminated() && ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())) {
				for (IDebugTarget target : launch.getDebugTargets()) {
					if (!target.isTerminated() && !target.isDisconnected()) {
						return true;
					}
				}
				if (launch.getDebugTargets().length == 0) {
					return true;
				}
			}
		}
		return false;
	}

	private static IThread suspendedThread(String threadName) {
		ILaunch[] launches = LaunchTools.manager().getLaunches();
		for (int i = launches.length - 1; i >= 0; i--) {
			IThread thread = launches[i].isTerminated() ? null : suspendedThread(launches[i], threadName);
			if (thread != null) {
				return thread;
			}
		}
		return null;
	}

	/** "thread "main" at Main.main(String[]) line: 6 (C:\...\Main.java)" のような 1 行。 */
	static String describeLocation(IThread thread) {
		IDebugModelPresentation presentation = DebugUITools.newDebugModelPresentation();
		try {
			// 止まった直後はスタックがまだ用意されていないことがあるので、少しだけ待つ
			IStackFrame[] stack = thread.getStackFrames();
			for (int i = 0; i < 15 && stack.length == 0 && thread.isSuspended(); i++) {
				Thread.sleep(100);
				stack = thread.getStackFrames();
			}
			return "thread \"" + thread.getName() + "\"" //$NON-NLS-1$ //$NON-NLS-2$
					+ (stack.length == 0 ? "" : " at " + describe(stack[0], presentation)); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (DebugException e) {
			return "a thread (its location is not available: " + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "a thread"; //$NON-NLS-1$
		} finally {
			presentation.dispose();
		}
	}

	/** Debug ビューと同じ書き方のラベル (言語ごとのデバッガが作る)。作れなければ fallback。 */
	private static String label(IDebugModelPresentation presentation, Object element, String fallback) {
		try {
			String text = presentation.getText(element);
			if (text != null && !text.trim().isEmpty()) {
				return text.replace('\n', ' ').replace('\r', ' ').trim();
			}
		} catch (RuntimeException e) {
			// ラベルを作れないデバッガでは、モデルの値をそのまま使う
		}
		return fallback;
	}

	private static String describe(IStackFrame frame, IDebugModelPresentation presentation) throws DebugException {
		int line = frame.getLineNumber();
		StringBuilder sb = new StringBuilder(
				label(presentation, frame, frame.getName() + (line > 0 ? " line: " + line : ""))); //$NON-NLS-1$ //$NON-NLS-2$
		// ソースの場所が分かれば添える (Claude がそのファイルを読めるように)
		ILaunch launch = frame.getLaunch();
		Object source = launch.getSourceLocator() == null ? null : launch.getSourceLocator().getSourceElement(frame);
		IResource resource = Adapters.adapt(source, IResource.class);
		if (resource != null) {
			sb.append(" (").append(Workspaces.location(resource)).append(')'); //$NON-NLS-1$
		}
		return sb.toString();
	}

	private static String debugState(int frames, boolean withVariables) throws DebugException {
		StringBuilder sb = new StringBuilder();
		boolean anySession = false;
		boolean variablesShown = false;
		IDebugModelPresentation presentation = DebugUITools.newDebugModelPresentation();
		try {
			for (ILaunch launch : LaunchTools.manager().getLaunches()) {
				if (launch.isTerminated() || launch.getDebugTargets().length == 0) {
					continue;
				}
				anySession = true;
				sb.append("Debug session \"").append(LaunchTools.launchName(launch)).append("\":\n"); //$NON-NLS-1$ //$NON-NLS-2$
				for (IDebugTarget target : launch.getDebugTargets()) {
					IThread[] threads = target.getThreads();
					if (threads.length == 0) {
						sb.append("  ").append(target.getName()).append(": no threads reported. ").append(GENERIC_HINT) //$NON-NLS-1$ //$NON-NLS-2$
								.append('\n');
					}
					int running = 0;
					for (IThread thread : threads) {
						if (!thread.isSuspended()) {
							running++;
							continue;
						}
						sb.append("  Thread \"").append(thread.getName()).append("\" is suspended:\n"); //$NON-NLS-1$ //$NON-NLS-2$
						IStackFrame[] stack = thread.getStackFrames();
						for (int i = 0; i < stack.length && i < frames; i++) {
							sb.append("    #").append(i).append(' ').append(describe(stack[i], presentation)).append('\n'); //$NON-NLS-1$
						}
						if (stack.length > frames) {
							sb.append("    ... ").append(stack.length - frames).append(" more frames\n"); //$NON-NLS-1$ //$NON-NLS-2$
						}
						if (withVariables && !variablesShown && stack.length > 0) {
							variablesShown = true;
							sb.append("    Variables of frame #0:\n"); //$NON-NLS-1$
							appendVariables(sb, stack[0].getVariables(), "      ", presentation); //$NON-NLS-1$
						}
					}
					if (running > 0) {
						sb.append("  ").append(running).append(running == threads.length ? " threads, all running.\n" //$NON-NLS-1$ //$NON-NLS-2$
								: running == 1 ? " other thread is running.\n" : " other threads are running.\n"); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			}
		} finally {
			presentation.dispose();
		}
		if (!anySession) {
			for (ILaunch launch : LaunchTools.manager().getLaunches()) {
				if (!launch.isTerminated() && ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())) {
					return "Debug session \"" + LaunchTools.launchName(launch) + "\" (this debugger reports its state only through Eclipse's views):\n" //$NON-NLS-1$ //$NON-NLS-2$
							+ readDebugViews();
				}
			}
			return "No debug session is running. Start one with launch in debug mode."; //$NON-NLS-1$
		}
		return sb.toString();
	}

	/**
	 * 標準のデバッグモデルを使わないデバッガ (C/C++ の GDB 連携) の状態を、Debug ビューと Variables ビューから読む。
	 * ビューは表に出ている間しか中身を更新しないので、順に表に出して、埋まるのを少し待つ。
	 */
	private static String readDebugViews() {
		StringBuilder sb = new StringBuilder();
		for (String viewId : new String[] { DEBUG_VIEW, VARIABLES_VIEW }) {
			try {
				Ui.call(() -> Ui.page().showView(viewId, null, IWorkbenchPage.VIEW_VISIBLE));
				Thread.sleep(VIEW_FILL_MILLIS);
				sb.append(Ui.call(() -> {
					UiTarget target = UiTarget.resolve(viewId);
					return target.title() + "\n" + WidgetReader.read(target.root(), MAX_VARIABLES * 2); //$NON-NLS-1$
				}));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				sb.append("(could not read ").append(viewId).append(": ").append(e.getMessage()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return sb.toString();
	}

	private static void appendVariables(StringBuilder sb, IVariable[] variables, String indent,
			IDebugModelPresentation presentation) throws DebugException {
		if (variables.length == 0) {
			sb.append(indent).append("(none)\n"); //$NON-NLS-1$
		}
		for (int i = 0; i < variables.length; i++) {
			if (i == MAX_VARIABLES) {
				sb.append(indent).append("... ").append(variables.length - i).append(" more\n"); //$NON-NLS-1$ //$NON-NLS-2$
				break;
			}
			sb.append(indent).append(describe(variables[i], presentation)).append('\n');
		}
	}

	private static String describe(IVariable variable, IDebugModelPresentation presentation) throws DebugException {
		IValue value = variable.getValue();
		String raw = value == null || value.getValueString() == null ? "" : value.getValueString(); //$NON-NLS-1$
		String text = label(presentation, variable, variable.getName() + " = " + raw); //$NON-NLS-1$
		if (text.length() > MAX_VALUE_CHARS) {
			text = text.substring(0, MAX_VALUE_CHARS) + "..."; //$NON-NLS-1$
		}
		String type;
		try {
			type = variable.getReferenceTypeName();
		} catch (DebugException e) {
			type = null;
		}
		return text + (type == null || type.isEmpty() ? "" : " (" + type + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ (value != null && value.hasVariables() ? " {...}" : ""); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String variable(List<String> path, int frameIndex, String threadName) throws DebugException {
		if (path.isEmpty()) {
			throw new IllegalArgumentException("path must name a variable, e.g. [\"this\", \"items\"]."); //$NON-NLS-1$
		}
		IThread thread = suspendedThread(threadName);
		if (thread == null) {
			throw new IllegalStateException("No thread is suspended" + (threadName == null ? "" : " with the name " + threadName) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ ". Variables can only be read while a thread is stopped (get_debug_state shows the threads)."); //$NON-NLS-1$
		}
		IStackFrame[] stack = thread.getStackFrames();
		if (frameIndex < 0 || frameIndex >= stack.length) {
			throw new IllegalArgumentException("The thread has " + stack.length + " stack frames; frame " + frameIndex + " does not exist."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		IVariable[] level = stack[frameIndex].getVariables();
		IVariable found = null;
		for (String name : path) {
			found = null;
			for (IVariable candidate : level) {
				if (candidate.getName().equals(name)) {
					found = candidate;
					break;
				}
			}
			if (found == null) {
				StringBuilder names = new StringBuilder();
				for (int i = 0; i < level.length && i < MAX_VARIABLES; i++) {
					names.append(i > 0 ? ", " : "").append(level[i].getName()); //$NON-NLS-1$ //$NON-NLS-2$
				}
				throw new IllegalArgumentException("There is no \"" + name + "\" here. Available: " + names + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			IValue value = found.getValue();
			level = value == null ? new IVariable[0] : value.getVariables();
		}
		IDebugModelPresentation presentation = DebugUITools.newDebugModelPresentation();
		try {
			StringBuilder sb = new StringBuilder(describe(found, presentation)).append('\n');
			if (level.length > 0) {
				appendVariables(sb, level, "  ", presentation); //$NON-NLS-1$
			}
			return sb.toString();
		} finally {
			presentation.dispose();
		}
	}

	// ---- ステップ実行 ----

	private static String step(String action, String threadName, int waitSeconds) throws Exception {
		if (!hasLiveDebugSession()) {
			throw new IllegalStateException("No debug session is running. Start one with launch in debug mode."); //$NON-NLS-1$
		}
		IThread thread = suspendedThread(threadName);
		if (SUSPEND.equals(action)) {
			return suspend();
		}
		if (thread == null) {
			// 標準のモデルで止まっているスレッドが見つからないデバッガでは、Debug ビューで選ばれているものに対してコマンドを送る
			return stepByCommand(action);
		}
		// 次に止まる (または終わる) のを、デバッグのイベントで待つ
		CountDownLatch stopped = new CountDownLatch(1);
		IDebugTarget target = thread.getDebugTarget();
		IDebugEventSetListener listener = events -> {
			for (DebugEvent event : events) {
				boolean suspended = event.getKind() == DebugEvent.SUSPEND && event.getSource() instanceof IThread
						&& ((IThread) event.getSource()).getDebugTarget() == target;
				boolean ended = event.getKind() == DebugEvent.TERMINATE && event.getSource() == target;
				if (suspended || ended) {
					stopped.countDown();
				}
			}
		};
		DebugPlugin.getDefault().addDebugEventListener(listener);
		try {
			switch (action) {
			case RESUME:
				thread.resume();
				break;
			case STEP_OVER:
				requireStep(thread.canStepOver(), action);
				thread.stepOver();
				break;
			case STEP_INTO:
				requireStep(thread.canStepInto(), action);
				thread.stepInto();
				break;
			case STEP_RETURN:
				requireStep(thread.canStepReturn(), action);
				thread.stepReturn();
				break;
			default:
				throw new IllegalArgumentException("Unknown action \"" + action //$NON-NLS-1$
						+ "\". Use resume, suspend, step_over, step_into or step_return."); //$NON-NLS-1$
			}
			stopped.await(waitSeconds, TimeUnit.SECONDS);
		} finally {
			DebugPlugin.getDefault().removeDebugEventListener(listener);
		}
		ILaunch launch = target.getLaunch();
		if (launch.isTerminated() || target.isTerminated() || target.isDisconnected()) {
			// プログラムが終わると、まずデバッグの接続が切れ、少し遅れてプロセスが終わる
			LaunchTools.waitFor(launch, 5, false);
			return "The program ran to its end (" + LaunchTools.state(launch) //$NON-NLS-1$
					+ "). get_console_output returns what it printed."; //$NON-NLS-1$
		}
		IThread now = suspendedThread(launch, null);
		if (now == null) {
			return "The program is running (no thread has stopped within " + waitSeconds //$NON-NLS-1$
					+ " seconds). Call get_debug_state later, or debug_step with action suspend."; //$NON-NLS-1$
		}
		return "Stopped: " + describeLocation(now) + "."; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static void requireStep(boolean possible, String action) {
		if (!possible) {
			throw new IllegalStateException("The thread cannot " + action.replace('_', ' ') + " in its current state."); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static String suspend() throws DebugException {
		ILaunch[] launches = LaunchTools.manager().getLaunches();
		for (int i = launches.length - 1; i >= 0; i--) {
			for (IDebugTarget target : launches[i].getDebugTargets()) {
				if (!target.isTerminated() && target.canSuspend()) {
					target.suspend();
					return "Asked \"" + LaunchTools.launchName(launches[i]) + "\" to suspend. Call get_debug_state to see where it stopped."; //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		return stepByCommand(SUSPEND);
	}

	/** Eclipse のデバッグのコマンド (Run メニューの Resume / Step Over など) を、Debug ビューで選ばれている対象に送る。 */
	private static String stepByCommand(String action) {
		String commandId;
		switch (action) {
		case RESUME:
			commandId = "org.eclipse.debug.ui.commands.Resume"; //$NON-NLS-1$
			break;
		case SUSPEND:
			commandId = "org.eclipse.debug.ui.commands.Suspend"; //$NON-NLS-1$
			break;
		case STEP_OVER:
			commandId = "org.eclipse.debug.ui.commands.StepOver"; //$NON-NLS-1$
			break;
		case STEP_INTO:
			commandId = "org.eclipse.debug.ui.commands.StepInto"; //$NON-NLS-1$
			break;
		case STEP_RETURN:
			commandId = "org.eclipse.debug.ui.commands.StepReturn"; //$NON-NLS-1$
			break;
		default:
			throw new IllegalArgumentException("Unknown action \"" + action //$NON-NLS-1$
					+ "\". Use resume, suspend, step_over, step_into or step_return."); //$NON-NLS-1$
		}
		try {
			String sent = Ui.call(() -> {
				IWorkbenchWindow window = Ui.window();
				Command command = window.getService(ICommandService.class).getCommand(commandId);
				// コマンドは Debug ビューで選ばれているものに働くので、Debug ビューをアクティブにしてから送る
				Ui.page().showView(DEBUG_VIEW);
				if (!command.isDefined() || !command.isEnabled()) {
					String dialog = UiTarget.openDialogTitle();
					throw new IllegalStateException("Cannot " + action.replace('_', ' ') + " right now" //$NON-NLS-1$ //$NON-NLS-2$
							+ (dialog != null ? ": the dialog \"" + dialog + "\" is open, and Eclipse disables debug commands while a dialog is open. Answer it first." //$NON-NLS-1$ //$NON-NLS-2$
									: ": no thread is suspended, or nothing is selected in the Debug view (select the thread or stack frame with select_item).")); //$NON-NLS-1$
				}
				window.getService(IHandlerService.class).executeCommand(new ParameterizedCommand(command, null), null);
				return "Sent " + action.replace('_', ' ') + " to the debug session selected in the Debug view."; //$NON-NLS-1$ //$NON-NLS-2$
			});
			// このデバッガは止まったことを標準の仕組みで知らせないので、ビューを読んで今の状態を返す
			// (続けてステップを送っても、前のステップが終わってから届くようにもなる)
			return sent + " State now:\n" + readDebugViews(); //$NON-NLS-1$
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("The command could not be run: " + e.getMessage(), e); //$NON-NLS-1$
		}
	}
}
