package com.tkdevelopeross.eclipse.incline.ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.DialogSettings;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tkdevelopeross.eclipse.incline.Activator;
import com.tkdevelopeross.eclipse.incline.backend.ChatBackend;
import com.tkdevelopeross.eclipse.incline.backend.ChatListener;
import com.tkdevelopeross.eclipse.incline.backend.FileChange;
import com.tkdevelopeross.eclipse.incline.backend.PermissionRequest;
import com.tkdevelopeross.eclipse.incline.claude.ClaudeCliBackend;
import com.tkdevelopeross.eclipse.incline.claude.ClaudeCliLocator;
import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.tools.EclipseTools;

/**
 * Claude とのチャットビュー。最初の送信時に claude を起動し、「新しいセッション」まで同じプロセスで会話を続ける。
 */
public class ChatView extends ViewPart {

	public static final String ID = Activator.CHAT_VIEW_ID;

	private static final int TOOL_SUMMARY_MAX = 200;
	private static final int TOOL_RESULT_MAX_LINES = 3;
	private static final int PATCH_MAX_LINES = 40;
	private static final String SETTING_SESSION_ID = "lastSessionId"; //$NON-NLS-1$
	private static final String SETTING_SESSION_CWD = "lastSessionCwd"; //$NON-NLS-1$
	private static final String[] SUMMARY_KEYS = { "file_path", "command", "pattern", "path", "url", "query", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			"notebook_path", "description", "prompt", "viewId", "commandId", "perspectiveId", "filter" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

	private StyledText transcript;
	private Text input;
	private Button sendButton;
	private Button stopButton;
	private Label statusLabel;

	/** Eclipse を操作するツールを Claude に公開する MCP サーバ。セッションをまたいで使い回す。 */
	private final McpServer mcpServer = EclipseTools.createServer();
	private ChatBackend backend;
	private boolean busy;
	private final Set<String> alwaysAllowedTools = new HashSet<>();
	private final Deque<PermissionRequest> pendingPermissions = new ArrayDeque<>();
	private boolean permissionDialogOpen;

	/** 少しずつ届いている応答の文章が、会話欄のどこからどこまでか。届いていないときは -1。 */
	private int streamStart = -1;
	private int streamEnd = -1;
	private int deltaCount;

	/** 最後に使った会話 (Eclipse を閉じても覚えておく) と、次の送信で続きから始める会話。 */
	private String lastSessionId;
	private String lastSessionCwd;
	private String resumeSessionId;
	private Action resumeAction;

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		parent.setLayout(layout);

		transcript = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
		transcript.setMargins(6, 6, 6, 6);
		transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		Composite bottom = new Composite(parent, SWT.NONE);
		GridLayout bottomLayout = new GridLayout(2, false);
		bottomLayout.marginWidth = 4;
		bottomLayout.marginHeight = 4;
		bottom.setLayout(bottomLayout);
		bottom.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		input = new Text(bottom, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
		inputData.heightHint = input.getLineHeight() * 4;
		input.setLayoutData(inputData);
		input.setMessage("メッセージを入力 (Enter で送信、Alt+Enter で改行)");
		input.addListener(SWT.KeyDown, e -> {
			if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
				e.doit = false;
				if ((e.stateMask & SWT.ALT) != 0) {
					input.insert(input.getLineDelimiter());
				} else {
					send();
				}
			}
		});

		Composite buttons = new Composite(bottom, SWT.NONE);
		GridLayout buttonsLayout = new GridLayout(1, true);
		buttonsLayout.marginWidth = 0;
		buttonsLayout.marginHeight = 0;
		buttons.setLayout(buttonsLayout);
		buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

		sendButton = new Button(buttons, SWT.PUSH);
		sendButton.setText("送信");
		sendButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		sendButton.addListener(SWT.Selection, e -> send());

		stopButton = new Button(buttons, SWT.PUSH);
		stopButton.setText("停止");
		stopButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		stopButton.addListener(SWT.Selection, e -> interrupt());

		statusLabel = new Label(parent, SWT.NONE);
		GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		statusData.horizontalIndent = 4;
		statusLabel.setLayoutData(statusData);

		createActions();
		setStatus("未接続 (送信すると claude を起動します)");
		updateButtons();
	}

	private void createActions() {
		Action newSession = new Action("新しいセッション") {
			@Override
			public void run() {
				newSession();
			}
		};
		newSession.setToolTipText("claude を終了して新しい会話を始める");
		newSession.setImageDescriptor(
				PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_ETOOL_CLEAR));
		resumeAction = new Action("前回の会話を再開") {
			@Override
			public void run() {
				resumeLastSession();
			}
		};
		resumeAction.setToolTipText("最後に使った会話の続きから始める (これまでのやり取りは表示されないが、Claude は覚えている)");
		IDialogSettings settings = settings();
		lastSessionId = settings.get(SETTING_SESSION_ID);
		lastSessionCwd = settings.get(SETTING_SESSION_CWD);
		resumeAction.setEnabled(lastSessionId != null);

		IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
		toolBar.add(resumeAction);
		toolBar.add(newSession);
	}

	private static IDialogSettings settings() {
		return DialogSettings.getOrCreateSection(Activator.getDefault().getDialogSettings(), "ChatView"); //$NON-NLS-1$
	}

	@Override
	public void setFocus() {
		input.setFocus();
	}

	@Override
	public void dispose() {
		denyAllPending();
		stopBackend();
		super.dispose();
	}

	// ---- 外から使う入口 (UI スレッドで呼ぶこと) ----

	/** 入力欄に text を入れて送信したのと同じことをする。 */
	public void submitPrompt(String text) {
		input.setText(text);
		send();
	}

	/** @return Claude の応答を待っている間は true */
	public boolean isBusy() {
		return busy;
	}

	/** @return 会話欄に出ている文字 */
	public String getTranscript() {
		return transcript.getText();
	}

	/** @return 応答の文章が、少しずつ届いた回数の合計 */
	public int getDeltaCount() {
		return deltaCount;
	}

	/** claude を終了して、会話欄を空にする。 */
	public void startNewSession() {
		newSession();
	}

	/** 次の送信を、最後に使った会話の続きにする。覚えている会話がなければ何もしない。 */
	public void resumeLastSession() {
		if (lastSessionId == null) {
			return;
		}
		String id = lastSessionId;
		newSession();
		resumeSessionId = id;
		appendInfo("次の送信から、前回の会話 (session " + id + ") の続きになります。\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "これまでのやり取りはここには表示されませんが、Claude は覚えています。");
	}

	// ---- 操作 ----

	private void send() {
		String text = input.getText().trim();
		if (text.isEmpty() || busy) {
			return;
		}
		// claude の起動には時間がかかるので、その前にボタンを押せなくして、二重に送られないようにする
		busy = true;
		updateButtons();
		if (backend == null || !backend.isRunning()) {
			setStatus("claude を起動しています…");
			input.getShell().update();
			if (!startBackend()) {
				busy = false;
				setStatus("未接続 (送信すると claude を起動します)");
				updateButtons();
				return;
			}
		}
		appendEntry("あなた", text, color(SWT.COLOR_DARK_BLUE)); //$NON-NLS-1$
		input.setText(""); //$NON-NLS-1$
		setStatus("応答待ち…");
		backend.sendUserMessage(text);
	}

	private void interrupt() {
		if (backend != null && busy) {
			backend.interrupt();
			setStatus("中断しています…");
		}
	}

	private void newSession() {
		denyAllPending();
		stopBackend();
		alwaysAllowedTools.clear();
		busy = false;
		resumeSessionId = null;
		streamStart = -1;
		transcript.setText(""); //$NON-NLS-1$
		setStatus("未接続 (送信すると claude を起動します)");
		updateButtons();
	}

	private boolean startBackend() {
		stopBackend();
		String exe = ClaudeCliLocator.locate();
		// 会話の続きは、その会話を始めたときと同じ作業ディレクトリでないと claude が見つけられない
		boolean resuming = resumeSessionId != null && lastSessionCwd != null;
		File cwd = resuming ? new File(lastSessionCwd) : resolveWorkingDirectory();
		ClaudeCliBackend b = new ClaudeCliBackend(exe, cwd, mcpServer, resuming ? resumeSessionId : null);
		resumeSessionId = null;
		b.setListener(new UiListener(b));
		try {
			b.start();
		} catch (IOException e) {
			appendError("claude を起動できませんでした: " + exe + "\n" + e.getMessage()); //$NON-NLS-1$
			return false;
		}
		backend = b;
		appendInfo("claude を起動しました (" + exe + ")\n作業ディレクトリ: " + cwd); //$NON-NLS-1$
		return true;
	}

	private void stopBackend() {
		ChatBackend b = backend;
		backend = null;
		if (b != null) {
			b.stop();
		}
	}

	/** アクティブなエディタのプロジェクト → 開いているプロジェクトが 1 つならそれ → ワークスペースのルート。 */
	private File resolveWorkingDirectory() {
		IEditorPart editor = getSite().getPage().getActiveEditor();
		if (editor != null) {
			IResource resource = editor.getEditorInput().getAdapter(IResource.class);
			if (resource != null && resource.getProject() != null) {
				IPath location = resource.getProject().getLocation();
				if (location != null) {
					return location.toFile();
				}
			}
		}
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		List<IProject> open = new ArrayList<>();
		for (IProject p : root.getProjects()) {
			if (p.isOpen() && p.getLocation() != null) {
				open.add(p);
			}
		}
		if (open.size() == 1) {
			return open.get(0).getLocation().toFile();
		}
		return root.getLocation().toFile();
	}

	// ---- 許可ダイアログ ----

	private void handlePermissionRequest(PermissionRequest request) {
		// Eclipse を読むだけ・表示を切り替えるだけのツールは、確認なしで通す
		if (mcpServer.isAutoAllowed(request.getToolName(), request.getInput())
				|| alwaysAllowedTools.contains(request.getToolName())) {
			request.allow();
			return;
		}
		pendingPermissions.addLast(request);
		showNextPermission();
	}

	/** ダイアログは 1 つずつ出す (並列のツール呼び出しで複数届くことがある)。 */
	private void showNextPermission() {
		if (permissionDialogOpen || transcript.isDisposed()) {
			return;
		}
		PermissionRequest request = pendingPermissions.pollFirst();
		if (request == null) {
			return;
		}
		if (request.isAnswered()) {
			showNextPermission();
			return;
		}
		if (alwaysAllowedTools.contains(request.getToolName())) {
			request.allow();
			showNextPermission();
			return;
		}
		permissionDialogOpen = true;
		try {
			setStatus("許可待ち: " + request.getToolName());
			PermissionDialog dialog = new PermissionDialog(getSite().getShell(), request);
			if (dialog.open() == Window.OK) {
				if (dialog.isAlwaysAllow()) {
					alwaysAllowedTools.add(request.getToolName());
				}
				request.allow();
			} else {
				request.deny("The user denied this tool use."); //$NON-NLS-1$
			}
		} catch (RuntimeException e) {
			// 答えないと claude が待ち続けるので、ダイアログを出せなかったら拒否する
			Activator.logError("許可ダイアログを表示できませんでした", e);
			request.deny("The permission dialog could not be shown."); //$NON-NLS-1$
		} finally {
			permissionDialogOpen = false;
		}
		if (!transcript.isDisposed()) {
			setStatus(busy ? "応答待ち…" : "待機中");
			showNextPermission();
		}
	}

	private void denyAllPending() {
		PermissionRequest r;
		while ((r = pendingPermissions.pollFirst()) != null) {
			r.deny("Session closed."); //$NON-NLS-1$
		}
	}

	// ---- 表示 ----

	private void appendEntry(String label, String body, Color labelColor) {
		if (transcript.getCharCount() > 0) {
			append("\n", null, SWT.NORMAL); //$NON-NLS-1$
		}
		append(label + "\n", labelColor, SWT.BOLD); //$NON-NLS-1$
		append(body + "\n", null, SWT.NORMAL); //$NON-NLS-1$
	}

	/** 応答の文章の、新しく届いた分を足す。 */
	private void appendDelta(String delta) {
		deltaCount++;
		if (streamStart < 0) {
			if (transcript.getCharCount() > 0) {
				append("\n", null, SWT.NORMAL); //$NON-NLS-1$
			}
			append("Claude\n", color(SWT.COLOR_DARK_GREEN), SWT.BOLD); //$NON-NLS-1$
			streamStart = transcript.getCharCount();
		}
		append(delta, null, SWT.NORMAL);
		streamEnd = transcript.getCharCount();
	}

	/** 応答の文章の全文が届いた。少しずつ出していた分があれば、全文に置き換える。 */
	private void finishAssistantText(String text) {
		if (streamStart >= 0 && streamEnd == transcript.getCharCount()) {
			transcript.replaceTextRange(streamStart, streamEnd - streamStart, text + "\n"); //$NON-NLS-1$
			transcript.setTopIndex(transcript.getLineCount() - 1);
		} else {
			appendEntry("Claude", text, color(SWT.COLOR_DARK_GREEN)); //$NON-NLS-1$
		}
		streamStart = -1;
	}

	private void appendTool(String line, Color c) {
		append(line + "\n", c, SWT.NORMAL); //$NON-NLS-1$
	}

	private void appendInfo(String text) {
		if (transcript.getCharCount() > 0) {
			append("\n", null, SWT.NORMAL); //$NON-NLS-1$
		}
		append(text + "\n", color(SWT.COLOR_DARK_GRAY), SWT.ITALIC); //$NON-NLS-1$
	}

	private void appendError(String text) {
		if (transcript.getCharCount() > 0) {
			append("\n", null, SWT.NORMAL); //$NON-NLS-1$
		}
		append(text + "\n", color(SWT.COLOR_RED), SWT.NORMAL); //$NON-NLS-1$
	}

	/** 差分は等幅フォントで、削除行は赤、追加行は緑で出す。長い差分は先頭だけ。 */
	private void appendPatch(List<String> lines) {
		Font font = JFaceResources.getTextFont();
		int n = Math.min(lines.size(), PATCH_MAX_LINES);
		for (int i = 0; i < n; i++) {
			String line = lines.get(i);
			int systemColor = SWT.COLOR_DARK_GRAY;
			if (line.startsWith("+")) { //$NON-NLS-1$
				systemColor = SWT.COLOR_DARK_GREEN;
			} else if (line.startsWith("-")) { //$NON-NLS-1$
				systemColor = SWT.COLOR_DARK_RED;
			}
			append("    " + truncate(line, TOOL_SUMMARY_MAX) + "\n", color(systemColor), SWT.NORMAL, font); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (lines.size() > n) {
			appendTool("    … (ほか " + (lines.size() - n) + " 行)", color(SWT.COLOR_DARK_GRAY)); //$NON-NLS-2$
		}
	}

	private void append(String text, Color fg, int fontStyle) {
		append(text, fg, fontStyle, null);
	}

	private void append(String text, Color fg, int fontStyle, Font font) {
		int start = transcript.getCharCount();
		transcript.append(text);
		if (fg != null || fontStyle != SWT.NORMAL || font != null) {
			StyleRange range = new StyleRange(start, text.length(), fg, null, fontStyle);
			range.font = font;
			transcript.setStyleRange(range);
		}
		transcript.setTopIndex(transcript.getLineCount() - 1);
	}

	private void setStatus(String text) {
		statusLabel.setText(text);
	}

	private void updateButtons() {
		sendButton.setEnabled(!busy);
		stopButton.setEnabled(busy);
	}

	private Color color(int systemColor) {
		return transcript.getDisplay().getSystemColor(systemColor);
	}

	/** mcp__eclipse__read_view のような名前を "Eclipse: read_view" にする。 */
	private String displayToolName(String toolName) {
		String prefix = "mcp__" + mcpServer.getName() + "__"; //$NON-NLS-1$ //$NON-NLS-2$
		return toolName != null && toolName.startsWith(prefix) ? "Eclipse: " + toolName.substring(prefix.length()) //$NON-NLS-1$
				: toolName;
	}

	static String summarizeToolInput(JsonObject input) {
		if (input == null) {
			return ""; //$NON-NLS-1$
		}
		for (String key : SUMMARY_KEYS) {
			JsonElement e = input.get(key);
			if (e != null && e.isJsonPrimitive()) {
				return truncate(firstLine(e.getAsString()), TOOL_SUMMARY_MAX);
			}
		}
		return truncate(input.toString(), TOOL_SUMMARY_MAX);
	}

	static String summarizeToolResult(String content) {
		if (content == null || content.isEmpty()) {
			return "(出力なし)";
		}
		String[] lines = content.split("\r?\n"); //$NON-NLS-1$
		StringBuilder sb = new StringBuilder();
		int n = Math.min(lines.length, TOOL_RESULT_MAX_LINES);
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append("\n    "); //$NON-NLS-1$
			}
			sb.append(truncate(lines[i], TOOL_SUMMARY_MAX));
		}
		if (lines.length > n) {
			sb.append("\n    … (ほか ").append(lines.length - n).append(" 行)"); //$NON-NLS-2$
		}
		return sb.toString();
	}

	private static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl >= 0 ? s.substring(0, nl) + " …" : s; //$NON-NLS-1$
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max) + "…" : s; //$NON-NLS-1$
	}

	// ---- バックエンドからのイベント ----

	/** バックエンドのスレッドから呼ばれるので UI スレッドに移す。古いセッションのイベントは捨てる。 */
	private final class UiListener implements ChatListener {

		private final ChatBackend owner;

		UiListener(ChatBackend owner) {
			this.owner = owner;
		}

		private void ui(Runnable r) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			if (display.isDisposed()) {
				return;
			}
			display.asyncExec(() -> {
				if (transcript == null || transcript.isDisposed() || backend != owner) {
					return;
				}
				r.run();
			});
		}

		@Override
		public void onSessionStarted(String sessionId, String model, String cwd) {
			ui(() -> {
				setStatus("接続中: " + model + " (session " + sessionId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
				if (sessionId != null && cwd != null) {
					lastSessionId = sessionId;
					lastSessionCwd = cwd;
					settings().put(SETTING_SESSION_ID, sessionId);
					settings().put(SETTING_SESSION_CWD, cwd);
					resumeAction.setEnabled(true);
				}
			});
		}

		@Override
		public void onAssistantTextDelta(String delta) {
			ui(() -> appendDelta(delta));
		}

		@Override
		public void onAssistantText(String text) {
			ui(() -> finishAssistantText(text));
		}

		@Override
		public void onToolUse(String toolUseId, String toolName, JsonObject input) {
			ui(() -> appendTool("▶ " + displayToolName(toolName) + "  " + summarizeToolInput(input), //$NON-NLS-1$ //$NON-NLS-2$
					color(SWT.COLOR_DARK_GRAY)));
		}

		@Override
		public void onToolResult(String toolUseId, String content, boolean isError, FileChange change) {
			ui(() -> {
				if (change != null) {
					// パスはツールの行に出ているので、結果の文章の代わりに差分を出す
					appendTool(change.isCreated() ? "  → 作成しました" : "  → 変更しました", color(SWT.COLOR_DARK_GRAY));
					appendPatch(change.getPatchLines());
					// claude はディスク上のファイルを直接書き換えるので、Eclipse 側に取り込む
					EclipseTools.refreshChangedFile(change.getFilePath());
				} else {
					appendTool("  → " + summarizeToolResult(content), //$NON-NLS-1$
							color(isError ? SWT.COLOR_RED : SWT.COLOR_DARK_GRAY));
				}
			});
		}

		@Override
		public void onTurnFinished(String subtype, boolean isError, String errorText) {
			ui(() -> {
				busy = false;
				if (isError) {
					appendError("エラーで終了しました (" + subtype + ")" //$NON-NLS-1$
							+ (errorText != null ? ": " + errorText : "")); //$NON-NLS-1$ //$NON-NLS-2$
				} else if (!"success".equals(subtype)) { //$NON-NLS-1$
					appendInfo("ターンが終了しました (" + subtype + ")"); //$NON-NLS-1$
				}
				setStatus("待機中");
				updateButtons();
			});
		}

		@Override
		public void onPermissionRequest(PermissionRequest request) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			if (display.isDisposed()) {
				request.deny("Eclipse is shutting down."); //$NON-NLS-1$
				return;
			}
			display.asyncExec(() -> {
				if (transcript == null || transcript.isDisposed() || backend != owner) {
					request.deny("Session closed."); //$NON-NLS-1$
					return;
				}
				handlePermissionRequest(request);
			});
		}

		@Override
		public void onError(String message) {
			ui(() -> appendError(message));
		}

		@Override
		public void onTerminated(int exitCode, String stderrTail, boolean expected) {
			ui(() -> {
				backend = null;
				busy = false;
				denyAllPending();
				if (!expected) {
					String detail = stderrTail == null || stderrTail.isBlank() ? "" : "\n" + stderrTail; //$NON-NLS-1$ //$NON-NLS-2$
					appendError("claude が終了しました (終了コード " + exitCode + ")" + detail); //$NON-NLS-1$
				}
				setStatus("未接続 (送信すると claude を起動します)");
				updateButtons();
			});
		}
	}
}
