package com.tkdevelopeross.eclipse.incline.tools;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.tkdevelopeross.eclipse.incline.Activator;

/**
 * 読み取りや操作の対象になる画面の一部: ビュー、手前のエディタ、または開いているダイアログ。
 * resolve() と、その結果を使う処理は UI スレッドで呼ぶこと。
 */
final class UiTarget {

	static final String EDITOR = "editor"; //$NON-NLS-1$
	static final String DIALOG = "dialog"; //$NON-NLS-1$

	/** ツールの引数の説明に使う。 */
	static final String HELP = "What to act on: a view id (from get_workbench_state or list_views), 'editor' for the front editor, " //$NON-NLS-1$
			+ "or 'dialog' for the dialog or wizard that is currently open"; //$NON-NLS-1$

	private static final int ACTION_TIMEOUT_SECONDS = 30;
	private static final long POLL_MILLIS = 250;
	/** 操作を始めてから、ダイアログが開いたかどうかを調べ始めるまでの時間。すぐ終わる操作はこの間に終わる。 */
	private static final long DIALOG_GRACE_MILLIS = 1500;

	private final String title;
	private final Control root;
	private final IWorkbenchPart part;

	private UiTarget(String title, Control root, IWorkbenchPart part) {
		this.title = title;
		this.root = root;
		this.part = part;
	}

	static UiTarget resolve(String target) {
		if (DIALOG.equals(target)) {
			Shell dialog = findDialog();
			if (dialog == null) {
				throw new IllegalStateException("No dialog is open."); //$NON-NLS-1$
			}
			return new UiTarget("Dialog: " + dialogTitle(dialog), dialog, null); //$NON-NLS-1$
		}
		if (target.equals(Activator.CHAT_VIEW_ID) || target.startsWith(Activator.CHAT_VIEW_ID + ":")) { //$NON-NLS-1$
			// 自分の入力欄に文字を入れて送信する、セッションを消す、といった操作をさせない
			throw new IllegalArgumentException("That is the chat view this conversation runs in. It cannot be read or operated."); //$NON-NLS-1$
		}
		IWorkbenchPage page = Ui.page();
		IWorkbenchPart part;
		String title;
		if (EDITOR.equals(target)) {
			IEditorPart editor = page.getActiveEditor();
			if (editor == null) {
				throw new IllegalStateException("No editor is open."); //$NON-NLS-1$
			}
			part = editor;
			title = "Editor: " + editor.getTitle(); //$NON-NLS-1$
		} else {
			IViewReference ref = findView(page, target);
			IViewPart view = ref == null ? null : ref.getView(true);
			if (view == null) {
				throw new IllegalStateException("The view " + target //$NON-NLS-1$
						+ " is not open in the current perspective. Call show_view first (list_views lists the view ids)."); //$NON-NLS-1$
			}
			part = view;
			// getTitle() には件数などの状態が付くことがあるので、ビューの名前を使う
			title = "View: " + ref.getPartName() + " (" + viewId(ref) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		MPart model = part.getSite().getService(MPart.class);
		Object widget = model == null ? null : model.getWidget();
		if (!(widget instanceof Control) || ((Control) widget).isDisposed()) {
			throw new IllegalStateException(title + " has not been created yet. Call show_view first."); //$NON-NLS-1$
		}
		return new UiTarget(title, (Control) widget, part);
	}

	/** "View: Problems (org.eclipse.ui.views.ProblemView)" のような見出し。 */
	String title() {
		return title;
	}

	Control root() {
		return root;
	}

	/** @return ビューかエディタ。ダイアログなら null */
	IWorkbenchPart part() {
		return part;
	}

	/** @return ビューやエディタが、ほかのタブの後ろに隠れていなければ true。ダイアログは常に true */
	boolean isVisible() {
		return part == null || part.getSite().getPage().isPartVisible(part);
	}

	/** 手前に出す (フォーカスは移さない)。ユーザーに何を操作しているかが見えるように。 */
	void bringToTop() {
		if (part != null) {
			part.getSite().getPage().bringToTop(part);
		}
	}

	/** アクティブにする。コマンドやメニューは、アクティブなビュー / エディタとその選択に対して働くため。 */
	void activate() {
		if (part != null) {
			part.getSite().getPage().activate(part);
		}
	}

	/** @return ビューのタイトルの横にあるツールバー (ビューの中身の外側にある)。なければ null */
	ToolBar toolBar() {
		if (!(part instanceof IViewPart)) {
			return null;
		}
		IToolBarManager manager = ((IViewPart) part).getViewSite().getActionBars().getToolBarManager();
		if (manager instanceof ToolBarManager) {
			ToolBar toolBar = ((ToolBarManager) manager).getControl();
			if (toolBar != null && !toolBar.isDisposed()) {
				return toolBar;
			}
		}
		return null;
	}

	/**
	 * @return ビューのメニュー (ツールバーの横の ▽ から開くもの)。ビューでなければ、または中身がなければ null
	 */
	Menu viewMenu() {
		if (!(part instanceof IViewPart)) {
			return null;
		}
		IMenuManager manager = ((IViewPart) part).getViewSite().getActionBars().getMenuManager();
		if (!(manager instanceof MenuManager) || manager.isEmpty()) {
			return null;
		}
		MenuManager menuManager = (MenuManager) manager;
		Menu menu = menuManager.getMenu();
		if (menu == null || menu.isDisposed()) {
			// ユーザーがまだ一度も開いていないメニューは、ウィジェットがない。ビューの上に作る (画面には出ない)
			menu = menuManager.createContextMenu(root);
		}
		return menu;
	}

	/** @return ビューの ID。同じビューを複数開けるもの (二次 ID つき) は "ID:二次ID" */
	static String viewId(IViewReference ref) {
		return ref.getSecondaryId() == null ? ref.getId() : ref.getId() + ":" + ref.getSecondaryId(); //$NON-NLS-1$
	}

	private static IViewReference findView(IWorkbenchPage page, String viewId) {
		for (IViewReference ref : page.getViewReferences()) {
			if (viewId(ref).equals(viewId)) {
				return ref;
			}
		}
		// 二次 ID なしで指定されたら、その ID のビューのうち最初のもの
		for (IViewReference ref : page.getViewReferences()) {
			if (ref.getId().equals(viewId)) {
				return ref;
			}
		}
		return null;
	}

	// ---- ダイアログ ----

	/**
	 * いま開いているダイアログやウィザードを探す。ダイアログの上にさらにダイアログが開いていれば、一番上のもの。
	 * OS のダイアログ (ファイル選択など) は SWT のウィジェットではないので見つからない。
	 *
	 * @return なければ null
	 */
	static Shell findDialog() {
		Display display = Display.getCurrent();
		Set<Shell> windows = new HashSet<>();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			windows.add(window.getShell());
		}
		Shell active = display.getActiveShell();
		if (active != null && isDialog(active, windows)) {
			return active;
		}
		// Eclipse が前面にないときはアクティブなシェルがないので、親子関係が一番深いものを選ぶ
		Shell best = null;
		int bestDepth = -1;
		for (Shell shell : display.getShells()) {
			if (!isDialog(shell, windows)) {
				continue;
			}
			int depth = 0;
			for (Composite parent = shell.getParent(); parent != null; parent = parent.getParent()) {
				depth++;
			}
			if (depth >= bestDepth) {
				best = shell;
				bestDepth = depth;
			}
		}
		return best;
	}

	private static boolean isDialog(Shell shell, Set<Shell> windows) {
		if (shell.isDisposed() || !shell.isVisible() || windows.contains(shell)) {
			return false;
		}
		// このプラグイン自身のダイアログ (許可ダイアログ) は、Claude には存在しないものとして扱う
		if (shell.getData(Activator.OWN_DIALOG_KEY) != null) {
			return false;
		}
		// タイトルもなくモーダルでもないものは、ツールチップや入力補完のポップアップ
		boolean modal = (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
		return modal || !shell.getText().isEmpty();
	}

	private static String dialogTitle(Shell dialog) {
		return dialog.getText().isEmpty() ? "(untitled)" : dialog.getText(); //$NON-NLS-1$
	}

	/** @return 開いているダイアログのタイトル。なければ null */
	static String openDialogTitle() {
		Shell dialog = findDialog();
		return dialog == null ? null : dialogTitle(dialog);
	}

	/**
	 * 画面を操作する処理を UI スレッドで実行し、結果に「いまダイアログが開いているか」を添える。
	 * 操作がモーダルダイアログを開くと、ダイアログが閉じるまで処理は戻ってこない。そのときはエラーにせず、
	 * ダイアログが開いたことを伝える (Claude が read_dialog と click で続きを進められるように)。
	 *
	 * @param target 操作の対象 ("dialog" なら、操作のあとで閉じたかどうかも伝える)。対象がなければ null
	 */
	static String perform(String target, Callable<String> action) throws Exception {
		CompletableFuture<String> running = Ui.submit(action);
		long start = System.currentTimeMillis();
		String result;
		while (true) {
			try {
				result = Ui.await(running, POLL_MILLIS);
				break;
			} catch (TimeoutException e) {
				long waited = System.currentTimeMillis() - start;
				if (waited > ACTION_TIMEOUT_SECONDS * 1000L) {
					throw new TimeoutException("The action has not finished after " + ACTION_TIMEOUT_SECONDS //$NON-NLS-1$
							+ " seconds. Eclipse may be busy with a long operation."); //$NON-NLS-1$
				}
				if (waited < DIALOG_GRACE_MILLIS) {
					continue;
				}
				String dialog;
				try {
					// ダイアログのイベントループが回っていれば、この問い合わせはすぐ返る。UI スレッドが処理中なら返らない
					dialog = Ui.await(Ui.submit(UiTarget::openDialogTitle), POLL_MILLIS);
				} catch (TimeoutException busy) {
					continue;
				}
				if (dialog != null) {
					return "The action has not returned yet; the dialog \"" + dialog //$NON-NLS-1$
							+ "\" is open and waiting. Use read_dialog to see it, and click / set_text with target 'dialog' to answer it."; //$NON-NLS-1$
				}
			}
		}
		// 開いたままのダイアログは、EclipseTool がどの結果にも添える。ここでは閉じたことだけを伝える
		return DIALOG.equals(target) && Ui.call(UiTarget::openDialogTitle) == null ? result + " The dialog has closed." //$NON-NLS-1$
				: result;
	}

	/** @return 開いているダイアログのタイトル。なければ (または UI スレッドがすぐに答えなければ) null */
	static String probeDialogTitle() {
		try {
			return Ui.await(Ui.submit(UiTarget::openDialogTitle), 1000);
		} catch (Exception | LinkageError e) {
			return null;
		}
	}
}
