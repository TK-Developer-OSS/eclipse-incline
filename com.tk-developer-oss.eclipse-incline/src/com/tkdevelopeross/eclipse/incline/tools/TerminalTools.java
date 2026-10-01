package com.tkdevelopeross.eclipse.incline.tools;

import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * Terminal ビュー (ローカルのシェル、SSH、Telnet、シリアル) に文字を送るツール。読むのは read_view でできる。
 * ターミナルのプラグイン (TM Terminal) は入っていないこともあるので、クラスを直接は参照せず、
 * 画面の部品が持つメソッド (pasteString / sendKey) を名前で呼ぶ。
 */
final class TerminalTools {

	private static final String TERMINAL_VIEW = "org.eclipse.tm.terminal.view.ui.TerminalsView"; //$NON-NLS-1$
	private static final int DEFAULT_WAIT_SECONDS = 3;
	private static final int MAX_WAIT_SECONDS = 120;
	/** 出力がこの時間増えなければ、落ち着いたとみなす。 */
	private static final long QUIET_MILLIS = 700;
	private static final int RETURNED_CHARS = 4000;
	private static final long OPEN_WAIT_MILLIS = 15000;

	private TerminalTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("send_to_terminal", //$NON-NLS-1$
				"Type text into the terminal shown in Eclipse's Terminal view (a local shell, SSH, Telnet or a serial line) and return what the terminal shows afterwards. " //$NON-NLS-1$
						+ "Enter is pressed after the text unless enter is false. The text goes to the tab that is selected in the view. " //$NON-NLS-1$
						+ "Open a terminal first if none is open (the command org.eclipse.tm.terminal.connector.local.command.launch opens a local shell; " //$NON-NLS-1$
						+ "read_view on " + TERMINAL_VIEW + " reads it without typing). The user is asked to approve each call.", //$NON-NLS-1$ //$NON-NLS-2$
				ToolEffect.CHANGE,
				new Schema().string("text", "The text to type, for example a shell command") //$NON-NLS-1$ //$NON-NLS-2$
						.bool("enter", "Press Enter after the text (default true)") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("waitSeconds", "How long to wait for the output to settle before returning it (default " //$NON-NLS-1$ //$NON-NLS-2$
								+ DEFAULT_WAIT_SECONDS + ")") //$NON-NLS-1$
						.string("viewId", "The terminal view, if several are open (default " + TERMINAL_VIEW + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.required("text"), //$NON-NLS-1$
				args -> {
					if (!args.has("text") || !args.get("text").isJsonPrimitive()) { //$NON-NLS-1$ //$NON-NLS-2$
						throw new IllegalArgumentException("Missing required argument: text"); //$NON-NLS-1$
					}
					String text = args.get("text").getAsString(); //$NON-NLS-1$
					boolean enter = Args.optBool(args, "enter", true); //$NON-NLS-1$
					int waitSeconds = Math.min(Args.optInt(args, "waitSeconds", DEFAULT_WAIT_SECONDS), MAX_WAIT_SECONDS); //$NON-NLS-1$
					String viewId = Args.optString(args, "viewId"); //$NON-NLS-1$
					return send(viewId == null ? TERMINAL_VIEW : viewId, text, enter, waitSeconds);
				}));
	}

	private static String send(String viewId, String text, boolean enter, int waitSeconds) throws Exception {
		// ターミナルは開いてから使えるようになるまで少しかかるので、出てくるのを待つ
		long ready = System.currentTimeMillis() + OPEN_WAIT_MILLIS;
		while (System.currentTimeMillis() < ready) {
			boolean open = Ui.call(() -> {
				Control root = UiTarget.resolve(viewId).root();
				return findTerminal(root) != null && selectedTerminal(root) != null;
			});
			if (open) {
				break;
			}
			Thread.sleep(300);
		}
		String before = Ui.call(() -> {
			Control root = UiTarget.resolve(viewId).root();
			Control canvas = findTerminal(root);
			Object terminal = selectedTerminal(root);
			if (canvas == null || terminal == null) {
				throw new IllegalStateException("No terminal is open in the view " + viewId //$NON-NLS-1$
						+ ". Open one first, e.g. with execute_command org.eclipse.tm.terminal.connector.local.command.launch."); //$NON-NLS-1$
			}
			String shown = WidgetReader.terminalText(canvas);
			if (!text.isEmpty()) {
				Object accepted = terminal.getClass().getMethod("pasteString", String.class).invoke(terminal, text); //$NON-NLS-1$
				if (Boolean.FALSE.equals(accepted)) {
					throw new IllegalStateException("The terminal did not accept the text. It may be disconnected."); //$NON-NLS-1$
				}
			}
			if (enter) {
				terminal.getClass().getMethod("sendKey", char.class).invoke(terminal, '\r'); //$NON-NLS-1$
			}
			return shown == null ? "" : shown; //$NON-NLS-1$
		});

		// 出力が増えなくなるまで待つ
		long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
		String last = before;
		long lastChange = System.currentTimeMillis();
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
			String now = Ui.call(() -> {
				Control canvas = findTerminal(UiTarget.resolve(viewId).root());
				String shown = canvas == null ? null : WidgetReader.terminalText(canvas);
				return shown == null ? "" : shown; //$NON-NLS-1$
			});
			if (!now.equals(last)) {
				last = now;
				lastChange = System.currentTimeMillis();
			} else if (!last.equals(before) && System.currentTimeMillis() - lastChange > QUIET_MILLIS && endsWithPrompt(last)) {
				// 出力が止まっただけでは、コンパイルのように黙って動いている最中かもしれない。プロンプトが戻るまで待つ
				break;
			}
		}
		// 送る前から出ていた分は省いて、新しく出た分を返す。打ち込んだ行はプロンプトの続きに出るので、その行の頭から
		int common = 0;
		while (common < before.length() && common < last.length() && before.charAt(common) == last.charAt(common)) {
			common++;
		}
		int lineStart = last.lastIndexOf('\n', Math.max(0, Math.min(common, last.length()) - 1)) + 1;
		String added = last.substring(Math.min(lineStart, last.length()));
		boolean cut = added.length() > RETURNED_CHARS;
		if (cut) {
			added = added.substring(added.length() - RETURNED_CHARS);
		}
		if (added.trim().isEmpty()) {
			return "Sent. The terminal has shown nothing new within " + waitSeconds //$NON-NLS-1$
					+ " seconds (read_view on the terminal view shows its whole content)."; //$NON-NLS-1$
		}
		return "Sent. New terminal output" + (cut ? " (the end of it)" : "") + ":\n" + added; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** @return 最後の行が、シェルの入力待ち (C:\dir&gt; や user@host:~$ など) に見えれば true */
	private static boolean endsWithPrompt(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		char last = trimmed.charAt(trimmed.length() - 1);
		return last == '>' || last == '$' || last == '#' || last == '%';
	}

	/** @return 見えているターミナルの画面。なければ null */
	private static Control findTerminal(Control control) {
		if (WidgetReader.isTerminal(control)) {
			return control;
		}
		if (control instanceof Composite) {
			for (Control child : ((Composite) control).getChildren()) {
				if (child.getVisible()) {
					Control found = findTerminal(child);
					if (found != null) {
						return found;
					}
				}
			}
		}
		return null;
	}

	/** @return 選ばれているタブのターミナル (文字を送るメソッドを持つオブジェクト)。なければ null */
	private static Object selectedTerminal(Control control) {
		if (control instanceof CTabFolder) {
			CTabItem selected = ((CTabFolder) control).getSelection();
			Object data = selected == null ? null : selected.getData();
			if (data != null && hasMethod(data, "pasteString", String.class)) { //$NON-NLS-1$
				return data;
			}
		}
		if (control instanceof Composite) {
			for (Control child : ((Composite) control).getChildren()) {
				Object found = selectedTerminal(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static boolean hasMethod(Object object, String name, Class<?>... parameters) {
		try {
			object.getClass().getMethod(name, parameters);
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}
}
