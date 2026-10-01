package com.tkdevelopeross.eclipse.incline.tools;

import java.util.List;
import java.util.function.Predicate;

import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * ビューやダイアログの中を操作するツール: 項目の選択・展開・ダブルクリック、ボタンやタブのクリック、文字の入力、コンテキストメニュー。
 * 対象のウィジェットは、read_view / read_dialog が振った番号 (#1, #2, ...) か、表示されているラベルで指定する。
 */
final class InteractionTools {

	private static final int DEFAULT_MAX_ITEMS = 300;
	/** 子を後から読み込むツリーで、項目が現れるのを待つ時間。 */
	private static final int LOADING_WAIT_MS = 4000;
	private static final String WIDGET_HELP = "The number shown after the widget in read_view / read_dialog output (the 3 in 'Tree #3'). "; //$NON-NLS-1$

	private InteractionTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("read_dialog", //$NON-NLS-1$
				"Read the dialog or wizard that is currently open in Eclipse: its title, messages, fields, lists and buttons. " //$NON-NLS-1$
						+ "Other tools tell you when an action opened a dialog. Answer it with click / set_text / select_item using target 'dialog'. " //$NON-NLS-1$
						+ "Native operating system dialogs (file choosers) cannot be read.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().integer("maxItems", "Maximum number of tree/table/list rows to return (default " + DEFAULT_MAX_ITEMS + ")"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> {
					int maxItems = Args.optInt(args, "maxItems", DEFAULT_MAX_ITEMS); //$NON-NLS-1$
					return Ui.call(() -> {
						Shell dialog = UiTarget.findDialog();
						if (dialog == null) {
							return "No dialog is open."; //$NON-NLS-1$
						}
						return UiTarget.resolve(UiTarget.DIALOG).title() + "\n" + WidgetReader.read(dialog, maxItems); //$NON-NLS-1$
					});
				}));

		server.register(new EclipseTool("select_item", //$NON-NLS-1$
				"Act on an item of a tree, table or list inside a view or dialog, as the user would with the mouse: " //$NON-NLS-1$
						+ "select it, double-click it (open), expand or collapse a tree node, or tick its checkbox. " //$NON-NLS-1$
						+ "Tree nodes on the way are expanded automatically. Labels may be given partially if that is unambiguous. " //$NON-NLS-1$
						+ "Selecting is also how you tell Eclipse what a following execute_command or context_menu should act on.", //$NON-NLS-1$
				ToolEffect.CHANGE, args -> {
					String action = Args.optString(args, "action"); //$NON-NLS-1$
					return WidgetActions.CHECK.equals(action) || WidgetActions.UNCHECK.equals(action) ? ToolEffect.CHANGE
							: ToolEffect.NAVIGATE;
				},
				new Schema().string("target", UiTarget.HELP) //$NON-NLS-1$
						.stringList("path", "For a tree: the labels from the top-level item down to the item, e.g. [\"myproject\", \"src\", \"main.c\"]. " //$NON-NLS-1$ //$NON-NLS-2$
								+ "For a table or list: one text that identifies the row (a cell value, or the row as read_view printed it)") //$NON-NLS-1$
						.choice("action", "What to do with the item (default select). 'open' is a double-click", //$NON-NLS-1$ //$NON-NLS-2$
								WidgetActions.SELECT, WidgetActions.OPEN, WidgetActions.EXPAND, WidgetActions.COLLAPSE,
								WidgetActions.CHECK, WidgetActions.UNCHECK)
						.integer("widget", WIDGET_HELP + "Default: the first tree, table or list of the target") //$NON-NLS-1$ //$NON-NLS-2$
						.required("target", "path"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					String target = Args.string(args, "target"); //$NON-NLS-1$
					List<String> path = Args.optStringList(args, "path"); //$NON-NLS-1$
					String action = Args.optString(args, "action"); //$NON-NLS-1$
					int widget = Args.optInt(args, "widget", 0); //$NON-NLS-1$
					return selectItem(target, widget, path, action == null ? WidgetActions.SELECT : action);
				}));

		server.register(new EclipseTool("click", //$NON-NLS-1$
				"Click a button, checkbox, radio button, toolbar button, link or tab inside a view or dialog, found by its label " //$NON-NLS-1$
						+ "(for icon-only buttons, the tooltip). This includes the toolbar next to a view's title and the buttons of a dialog " //$NON-NLS-1$
						+ "(OK, Cancel, Next >, Finish). The user is asked to approve each call.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				new Schema().string("target", UiTarget.HELP) //$NON-NLS-1$
						.string("label", "The label as shown by read_view / read_dialog. A unique beginning or part of it is enough") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("occurrence", "When several things have the same label: which one, counting from 1 in reading order") //$NON-NLS-1$ //$NON-NLS-2$
						.required("target", "label"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					String target = Args.string(args, "target"); //$NON-NLS-1$
					String label = Args.string(args, "label"); //$NON-NLS-1$
					int occurrence = Args.optInt(args, "occurrence", 0); //$NON-NLS-1$
					return UiTarget.perform(target, () -> {
						UiTarget resolved = UiTarget.resolve(target);
						resolved.activate();
						return WidgetActions.click(resolved.root(), resolved.toolBar(), label, occurrence);
					});
				}));

		server.register(new EclipseTool("set_text", //$NON-NLS-1$
				"Type into a text field or choose a value in a combo inside a view or dialog. The existing content is replaced. " //$NON-NLS-1$
						+ "Use it for filter and search fields, dialog and wizard fields, and message fields such as a commit message. " //$NON-NLS-1$
						+ "To change a source file, use the Edit tool instead.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				// 入力欄に文字を入れるだけなら何も変わらない。変わるのは、エディタの内容を書き換えるときと、Enter で何かを実行するとき
				args -> UiTarget.EDITOR.equals(Args.optString(args, "target")) || Args.optBool(args, "enter", false) //$NON-NLS-1$ //$NON-NLS-2$
						? ToolEffect.CHANGE
						: ToolEffect.NAVIGATE,
				new Schema().string("target", UiTarget.HELP) //$NON-NLS-1$
						.string("text", "The text to enter. For a combo with fixed choices: the choice to select") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("widget", WIDGET_HELP + "Default: the first editable text field or combo of the target") //$NON-NLS-1$ //$NON-NLS-2$
						.bool("enter", "Press Enter in the field afterwards (default false)") //$NON-NLS-1$ //$NON-NLS-2$
						.required("target", "text"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					String target = Args.string(args, "target"); //$NON-NLS-1$
					// 空の文字列で欄を消すこともできるように、Args.string (空を拒む) は使わない
					if (!args.has("text") || !args.get("text").isJsonPrimitive()) { //$NON-NLS-1$ //$NON-NLS-2$
						throw new IllegalArgumentException("Missing required argument: text"); //$NON-NLS-1$
					}
					String text = args.get("text").getAsString(); //$NON-NLS-1$
					int widget = Args.optInt(args, "widget", 0); //$NON-NLS-1$
					boolean enter = Args.optBool(args, "enter", false); //$NON-NLS-1$
					return UiTarget.perform(target, () -> {
						UiTarget resolved = UiTarget.resolve(target);
						Control field = pick(resolved, widget, InteractionTools::isInput, InteractionTools::isEditableInput,
								"text field or combo"); //$NON-NLS-1$
						return WidgetActions.setText(field, text, enter);
					});
				}));

		server.register(new EclipseTool("context_menu", //$NON-NLS-1$
				"Use the right-click menu of a tree, table, list or text inside a view or dialog. It acts on what is selected there, " //$NON-NLS-1$
						+ "so call select_item first. Without menuPath it lists the menu's items; with a menuPath ending at a submenu it lists that submenu; " //$NON-NLS-1$
						+ "with a menuPath ending at a command it runs it (the user is asked to approve).", //$NON-NLS-1$
				ToolEffect.CHANGE,
				args -> Args.optStringList(args, "menuPath").isEmpty() ? ToolEffect.NAVIGATE : ToolEffect.CHANGE, //$NON-NLS-1$
				new Schema().string("target", UiTarget.HELP) //$NON-NLS-1$
						.stringList("menuPath", "The menu item labels from the top of the menu, e.g. [\"Team\", \"Commit...\"]. Omit to list the menu") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("widget", WIDGET_HELP + "Default: the first tree, table or list of the target") //$NON-NLS-1$ //$NON-NLS-2$
						.required("target"), //$NON-NLS-1$
				args -> {
					String target = Args.string(args, "target"); //$NON-NLS-1$
					List<String> menuPath = Args.optStringList(args, "menuPath"); //$NON-NLS-1$
					int widget = Args.optInt(args, "widget", 0); //$NON-NLS-1$
					return UiTarget.perform(target, () -> {
						UiTarget resolved = UiTarget.resolve(target);
						Control control = pick(resolved, widget, c -> true, InteractionTools::isItemContainer,
								"tree, table or list"); //$NON-NLS-1$
						resolved.activate();
						return WidgetActions.contextMenu(control, menuPath);
					});
				}));

		server.register(new EclipseTool("main_menu", //$NON-NLS-1$
				"Use Eclipse's main menu bar (File, Edit, Navigate, Search, Project, Run, Window, Help, plus whatever the current perspective adds). " //$NON-NLS-1$
						+ "Without menuPath it lists the top-level menus; with a menuPath ending at a submenu it lists that submenu; " //$NON-NLS-1$
						+ "with a menuPath ending at a command it runs it (the user is asked to approve). " //$NON-NLS-1$
						+ "Menu items act on the active editor or view and its selection, so pass 'part' to choose it. " //$NON-NLS-1$
						+ "This reaches everything the menus offer, including items that have no command id.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				args -> Args.optStringList(args, "menuPath").size() < 2 ? ToolEffect.NAVIGATE : ToolEffect.CHANGE, //$NON-NLS-1$
				new Schema().stringList("menuPath", "The menu labels from the menu bar down, e.g. [\"Project\", \"Clean...\"]. Omit to list the menu bar") //$NON-NLS-1$ //$NON-NLS-2$
						.string("part", "What to activate first: 'editor' for the front editor, or a view id"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					List<String> menuPath = Args.optStringList(args, "menuPath"); //$NON-NLS-1$
					String part = Args.optString(args, "part"); //$NON-NLS-1$
					return UiTarget.perform(null, () -> {
						if (part != null) {
							UiTarget.resolve(part).activate();
						}
						Menu menuBar = Ui.window().getShell().getMenuBar();
						if (menuBar == null) {
							throw new IllegalStateException("The workbench window has no menu bar."); //$NON-NLS-1$
						}
						return WidgetActions.menu(menuBar, menuPath, "main menu"); //$NON-NLS-1$
					});
				}));

		server.register(new EclipseTool("view_menu", //$NON-NLS-1$
				"Use the drop-down menu of a view (the small triangle or three dots next to the view's toolbar), where views keep their settings: " //$NON-NLS-1$
						+ "filters, grouping, sorting, layout. Without menuPath it lists the items; with a menuPath ending at a command it runs it " //$NON-NLS-1$
						+ "(the user is asked to approve).", //$NON-NLS-1$
				ToolEffect.CHANGE,
				args -> Args.optStringList(args, "menuPath").isEmpty() ? ToolEffect.NAVIGATE : ToolEffect.CHANGE, //$NON-NLS-1$
				new Schema().string("viewId", "View id as shown by get_workbench_state or list_views") //$NON-NLS-1$ //$NON-NLS-2$
						.stringList("menuPath", "The menu item labels from the top of the menu, e.g. [\"Group By\", \"Type\"]. Omit to list the menu") //$NON-NLS-1$ //$NON-NLS-2$
						.required("viewId"), //$NON-NLS-1$
				args -> {
					String viewId = Args.string(args, "viewId"); //$NON-NLS-1$
					List<String> menuPath = Args.optStringList(args, "menuPath"); //$NON-NLS-1$
					return UiTarget.perform(viewId, () -> {
						UiTarget resolved = UiTarget.resolve(viewId);
						Menu menu = resolved.viewMenu();
						if (menu == null) {
							throw new IllegalStateException(resolved.title() + " has no view menu."); //$NON-NLS-1$
						}
						resolved.activate();
						return WidgetActions.menu(menu, menuPath, "view menu"); //$NON-NLS-1$
					});
				}));
	}

	private static String selectItem(String target, int widget, List<String> path, String action) throws Exception {
		long deadline = System.currentTimeMillis() + LOADING_WAIT_MS;
		while (true) {
			try {
				return UiTarget.perform(target, () -> {
					UiTarget resolved = UiTarget.resolve(target);
					Control control = pick(resolved, widget, InteractionTools::isItemContainer,
							InteractionTools::isItemContainer, "tree, table or list"); //$NON-NLS-1$
					resolved.bringToTop();
					if (control instanceof Tree) {
						return WidgetActions.tree((Tree) control, path, action);
					}
					if (control instanceof Table) {
						return WidgetActions.table((Table) control, path, action);
					}
					return WidgetActions.list((org.eclipse.swt.widgets.List) control, path, action);
				});
			} catch (WidgetActions.ItemNotFoundException e) {
				// 子を後から読み込むツリーでは、開いた直後はまだ項目がない。少し待ってやり直す (開いた状態は残っている)
				if (!e.mayStillBeLoading() || System.currentTimeMillis() > deadline) {
					throw e;
				}
				Thread.sleep(300);
			}
		}
	}

	/**
	 * @param number      read の出力に出ている番号。0 なら、byDefault に合う最初のウィジェットを選ぶ
	 * @param whenNumbered 番号で指定されたウィジェットが満たすべき条件
	 * @param kinds       エラーメッセージ用の、選べるウィジェットの種類
	 */
	private static Control pick(UiTarget target, int number, Predicate<Control> whenNumbered, Predicate<Control> byDefault,
			String kinds) {
		List<Control> numbered = WidgetReader.addressable(target.root());
		if (number > 0) {
			if (number > numbered.size()) {
				throw new IllegalArgumentException("There is no widget #" + number + " in " + target.title() + " (it has " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ numbered.size() + " numbered widgets). Read it again: the numbers change when its layout changes."); //$NON-NLS-1$
			}
			Control control = numbered.get(number - 1);
			if (!whenNumbered.test(control)) {
				throw new IllegalArgumentException("Widget #" + number + " is a " + control.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
						+ ", not a " + kinds + "."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return control;
		}
		for (Control control : numbered) {
			if (byDefault.test(control)) {
				return control;
			}
		}
		throw new IllegalStateException(target.title() + " has no " + kinds + "."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static boolean isItemContainer(Control control) {
		return control instanceof Tree || control instanceof Table || control instanceof org.eclipse.swt.widgets.List;
	}

	private static boolean isInput(Control control) {
		return control instanceof Text || control instanceof StyledText || control instanceof Combo
				|| control instanceof CCombo;
	}

	private static boolean isEditableInput(Control control) {
		if (!control.isEnabled()) {
			return false;
		}
		if (control instanceof Text) {
			return ((Text) control).getEditable();
		}
		if (control instanceof StyledText) {
			return ((StyledText) control).getEditable();
		}
		return control instanceof Combo || control instanceof CCombo;
	}
}
