package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

/**
 * ビューやダイアログの中のウィジェットを、ユーザーがマウスやキーボードで操作したときと同じように動かす。
 * ウィジェットの状態を変えたうえで、登録されているリスナーへ SWT のイベントを直接送る
 * (JFace のビューアやダイアログはこのイベントで動く)。画面に見えていなくても、フォーカスがなくても動く。
 * UI スレッドで呼ぶこと。
 */
final class WidgetActions {

	static final String SELECT = "select"; //$NON-NLS-1$
	static final String OPEN = "open"; //$NON-NLS-1$
	static final String EXPAND = "expand"; //$NON-NLS-1$
	static final String COLLAPSE = "collapse"; //$NON-NLS-1$
	static final String CHECK = "check"; //$NON-NLS-1$
	static final String UNCHECK = "uncheck"; //$NON-NLS-1$

	private static final int MAX_SCAN_ROWS = 5000;
	private static final Pattern ANCHOR = Pattern.compile("<a(?:\\s+href=\"([^\"]*)\")?\\s*>(.*?)</a>", //$NON-NLS-1$
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private WidgetActions() {
	}

	/** 探している項目や行が (まだ) 無い。 */
	static final class ItemNotFoundException extends IllegalStateException {

		private static final long serialVersionUID = 1L;
		private final boolean loading;

		ItemNotFoundException(String message, boolean loading) {
			super(message);
			this.loading = loading;
		}

		/** @return 中身を後から読み込んでいる途中に見える。少し待つと現れるかもしれない */
		boolean mayStillBeLoading() {
			return loading;
		}
	}

	// ---- ツリー / テーブル / リストの項目 ----

	/**
	 * @param path 一番上の項目から目的の項目までのラベル。途中の項目は開きながらたどる
	 */
	static String tree(Tree tree, List<String> path, String action) {
		if (path.isEmpty()) {
			throw new IllegalArgumentException(
					"path must name the item from the top of the tree, e.g. [\"project\", \"src\", \"main.c\"]."); //$NON-NLS-1$
		}
		TreeItem item = null;
		for (int i = 0; i < path.size(); i++) {
			TreeItem[] level = item == null ? tree.getItems() : item.getItems();
			item = findTreeItem(level, path.get(i), item);
			if (i < path.size() - 1) {
				expand(tree, item);
			}
		}
		String label = Labels.quote(WidgetReader.clean(item.getText()));
		switch (action) {
		case SELECT:
			select(tree, item);
			return "Selected " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case OPEN:
			select(tree, item);
			tree.notifyListeners(SWT.DefaultSelection, itemEvent(item));
			return "Double-clicked " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case EXPAND:
			tree.showItem(item);
			expand(tree, item);
			return "Expanded " + label + ". " + children(item); //$NON-NLS-1$ //$NON-NLS-2$
		case COLLAPSE:
			if (item.getExpanded()) {
				tree.notifyListeners(SWT.Collapse, itemEvent(item));
				item.setExpanded(false);
			}
			return "Collapsed " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case CHECK:
		case UNCHECK:
			requireCheckable(tree.getStyle(), "tree"); //$NON-NLS-1$
			item.setChecked(CHECK.equals(action));
			tree.notifyListeners(SWT.Selection, checkEvent(item));
			return (CHECK.equals(action) ? "Checked " : "Unchecked ") + label + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		default:
			throw unknownAction(action);
		}
	}

	private static TreeItem findTreeItem(TreeItem[] level, String wanted, TreeItem parent) {
		List<TreeItem> items = Arrays.asList(level);
		TreeItem found = Labels.single(items, TreeItem::getText, wanted);
		if (found == null) {
			// 子を後から読み込むツリーは、読み込みが済むまで空か、「Pending...」のような仮の項目だけを持つ
			boolean loading = level.length == 0 || (level.length == 1 && isPlaceholder(level[0].getText()));
			throw new ItemNotFoundException("No item " + Labels.quote(wanted) //$NON-NLS-1$
					+ (parent == null ? " at the top level of the tree" : " under " + Labels.quote(parent.getText())) //$NON-NLS-1$ //$NON-NLS-2$
					+ ". Items there: " + Labels.list(items, TreeItem::getText) + ".", loading); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return found;
	}

	private static boolean isPlaceholder(String text) {
		String trimmed = text.trim();
		return trimmed.isEmpty() || trimmed.endsWith("...") || trimmed.endsWith("…"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static void expand(Tree tree, TreeItem item) {
		if (item.getItemCount() == 0 || item.getExpanded()) {
			return;
		}
		// JFace のツリーは、このイベントを受けてから本当の子を作る
		tree.notifyListeners(SWT.Expand, itemEvent(item));
		item.setExpanded(true);
	}

	private static void select(Tree tree, TreeItem item) {
		tree.setSelection(item);
		tree.notifyListeners(SWT.Selection, itemEvent(item));
	}

	private static String children(TreeItem item) {
		TreeItem[] children = item.getItems();
		if (children.length == 0) {
			return "It has no children."; //$NON-NLS-1$
		}
		return "Children: " + Labels.list(Arrays.asList(children), TreeItem::getText) + "."; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** @param path 行を特定する文字 1 つ (セルの内容、または read の出力の 1 行) */
	static String table(Table table, List<String> path, String action) {
		int row = findRow(table, rowPattern(path));
		TableItem item = table.getItem(row);
		String label = Labels.quote(WidgetReader.rowText(table, item));
		switch (action) {
		case SELECT:
			select(table, row, item);
			return "Selected the row " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case OPEN:
			select(table, row, item);
			table.notifyListeners(SWT.DefaultSelection, itemEvent(item));
			return "Double-clicked the row " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case CHECK:
		case UNCHECK:
			requireCheckable(table.getStyle(), "table"); //$NON-NLS-1$
			item.setChecked(CHECK.equals(action));
			table.notifyListeners(SWT.Selection, checkEvent(item));
			return (CHECK.equals(action) ? "Checked the row " : "Unchecked the row ") + label + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		case EXPAND:
		case COLLAPSE:
			throw new IllegalArgumentException("A table row cannot be expanded or collapsed."); //$NON-NLS-1$
		default:
			throw unknownAction(action);
		}
	}

	private static void select(Table table, int row, TableItem item) {
		table.setSelection(row);
		table.showSelection();
		table.notifyListeners(SWT.Selection, itemEvent(item));
	}

	private static int findRow(Table table, String wanted) {
		String want = Labels.normalize(wanted).toLowerCase(Locale.ROOT);
		int count = Math.min(table.getItemCount(), MAX_SCAN_ROWS);
		int columns = Math.max(1, table.getColumnCount());
		// セルが完全に一致 → 行全体が一致 → 行のどこかに含まれる、の順に緩める
		List<Integer> byCell = new ArrayList<>();
		List<Integer> byRow = new ArrayList<>();
		List<Integer> byPart = new ArrayList<>();
		for (int row = 0; row < count; row++) {
			TableItem item = table.getItem(row);
			boolean cellMatches = false;
			for (int column = 0; column < columns && !cellMatches; column++) {
				cellMatches = Labels.normalize(item.getText(column)).toLowerCase(Locale.ROOT).equals(want);
			}
			String rowText = WidgetReader.rowText(table, item).toLowerCase(Locale.ROOT);
			if (cellMatches) {
				byCell.add(row);
			} else if (rowText.equals(want)) {
				byRow.add(row);
			} else if (rowText.contains(want)) {
				byPart.add(row);
			}
		}
		List<Integer> found = !byCell.isEmpty() ? byCell : !byRow.isEmpty() ? byRow : byPart;
		if (found.isEmpty()) {
			throw new ItemNotFoundException("No row contains " + Labels.quote(wanted) + " (" + count //$NON-NLS-1$ //$NON-NLS-2$
					+ (count == 1 ? " row" : " rows") + " searched). Read the table to see its rows.", count == 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		if (found.size() > 1) {
			StringBuilder examples = new StringBuilder();
			for (int i = 0; i < found.size() && i < 3; i++) {
				examples.append(i > 0 ? ", " : "") //$NON-NLS-1$ //$NON-NLS-2$
						.append(Labels.quote(WidgetReader.rowText(table, table.getItem(found.get(i)))));
			}
			throw new IllegalStateException(found.size() + " rows match " + Labels.quote(wanted) + ", for example " //$NON-NLS-1$ //$NON-NLS-2$
					+ examples + ". Pass more of the row's text (the whole line as read works)."); //$NON-NLS-1$
		}
		return found.get(0);
	}

	static String list(org.eclipse.swt.widgets.List list, List<String> path, String action) {
		List<String> items = Arrays.asList(list.getItems());
		String wanted = rowPattern(path);
		String found = Labels.single(items, s -> s, wanted);
		if (found == null) {
			throw new ItemNotFoundException("The list has no item " + Labels.quote(wanted) + ". Items: " //$NON-NLS-1$ //$NON-NLS-2$
					+ Labels.list(items, s -> s) + ".", items.isEmpty()); //$NON-NLS-1$
		}
		int index = items.indexOf(found);
		String label = Labels.quote(WidgetReader.clean(found));
		switch (action) {
		case SELECT:
			list.setSelection(index);
			list.notifyListeners(SWT.Selection, new Event());
			return "Selected " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		case OPEN:
			list.setSelection(index);
			list.notifyListeners(SWT.Selection, new Event());
			list.notifyListeners(SWT.DefaultSelection, new Event());
			return "Double-clicked " + label + "."; //$NON-NLS-1$ //$NON-NLS-2$
		default:
			throw new IllegalArgumentException("A list item can only be selected or opened."); //$NON-NLS-1$
		}
	}

	private static String rowPattern(List<String> path) {
		if (path.size() != 1) {
			throw new IllegalArgumentException(
					"For a table or list, path must hold one text that identifies the row, e.g. [\"main.c\"]."); //$NON-NLS-1$
		}
		return path.get(0);
	}

	private static void requireCheckable(int style, String kind) {
		if ((style & SWT.CHECK) == 0) {
			throw new IllegalStateException("This " + kind + " has no checkboxes."); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static IllegalArgumentException unknownAction(String action) {
		return new IllegalArgumentException("Unknown action " + Labels.quote(action) //$NON-NLS-1$
				+ ". Use select, open, expand, collapse, check or uncheck."); //$NON-NLS-1$
	}

	private static Event itemEvent(Widget item) {
		Event event = new Event();
		event.item = item;
		return event;
	}

	private static Event checkEvent(Widget item) {
		Event event = itemEvent(item);
		event.detail = SWT.CHECK;
		return event;
	}

	// ---- ボタン / ツールバー / リンク / タブ ----

	/** クリックできるもの 1 つ。 */
	private static final class Clickable {

		final Widget widget;
		final String kind;
		final String label;

		Clickable(Widget widget, String kind, String label) {
			this.widget = widget;
			this.kind = kind;
			this.label = label;
		}

		String describe() {
			return kind + " " + Labels.quote(Labels.normalize(label)); //$NON-NLS-1$
		}
	}

	/**
	 * @param viewToolBar ビューのタイトルの横にあるツールバー (root の外にある)。なければ null
	 * @param occurrence  同じラベルのものが複数あるときに、何番目かを 1 から数えて指定する。指定しないなら 0
	 */
	static String click(Control root, ToolBar viewToolBar, String label, int occurrence) {
		List<Clickable> all = new ArrayList<>();
		collectClickables(root, all);
		if (viewToolBar != null) {
			addToolItems(viewToolBar, "view toolbar button", all); //$NON-NLS-1$
		}
		List<Clickable> found = Labels.matches(all, c -> c.label, label);
		if (found.isEmpty()) {
			throw new IllegalStateException("Nothing to click is labelled " + Labels.quote(label) //$NON-NLS-1$
					+ ". Buttons, toolbar buttons, links and tabs here: " + Labels.list(all, c -> c.label) + "."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Clickable chosen = found.get(0);
		if (occurrence > 0) {
			if (occurrence > found.size()) {
				throw new IllegalArgumentException(
						"Only " + found.size() + " match " + Labels.quote(label) + ", so occurrence " + occurrence + " does not exist."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			chosen = found.get(occurrence - 1);
		} else if (found.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < found.size(); i++) {
				sb.append(i > 0 ? ", " : "").append(i + 1).append(": ").append(found.get(i).describe()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			throw new IllegalStateException(found.size() + " things match " + Labels.quote(label) + " (" + sb //$NON-NLS-1$ //$NON-NLS-2$
					+ "). Pass occurrence to choose one, or a more specific label."); //$NON-NLS-1$
		}
		return click(chosen);
	}

	private static void collectClickables(Control control, List<Clickable> out) {
		if (control instanceof Button) {
			Button button = (Button) control;
			String label = button.getText().isEmpty() ? button.getToolTipText() : button.getText();
			if ((button.getStyle() & SWT.ARROW) == 0 && label != null && !label.isEmpty()) {
				out.add(new Clickable(button, "button", label)); //$NON-NLS-1$
			}
		} else if (control instanceof Link) {
			String label = ((Link) control).getText().replaceAll("</?[aA][^>]*>", ""); //$NON-NLS-1$ //$NON-NLS-2$
			if (!label.trim().isEmpty()) {
				out.add(new Clickable(control, "link", label)); //$NON-NLS-1$
			}
		} else if (control instanceof ToolBar) {
			addToolItems((ToolBar) control, "toolbar button", out); //$NON-NLS-1$
		} else if (control instanceof Composite) {
			if (control instanceof TabFolder) {
				for (TabItem item : ((TabFolder) control).getItems()) {
					out.add(new Clickable(item, "tab", item.getText())); //$NON-NLS-1$
				}
			} else if (control instanceof CTabFolder) {
				for (CTabItem item : ((CTabFolder) control).getItems()) {
					out.add(new Clickable(item, "tab", item.getText())); //$NON-NLS-1$
				}
			}
			for (Control child : ((Composite) control).getChildren()) {
				// WidgetReader と同じく、隠れているページやタブの中身は対象にしない
				if (child.getVisible()) {
					collectClickables(child, out);
				}
			}
		}
	}

	private static void addToolItems(ToolBar toolBar, String kind, List<Clickable> out) {
		for (ToolItem item : toolBar.getItems()) {
			String label = item.getText().isEmpty() ? item.getToolTipText() : item.getText();
			if ((item.getStyle() & SWT.SEPARATOR) == 0 && label != null && !label.isEmpty()) {
				out.add(new Clickable(item, kind, label));
			}
		}
	}

	private static String click(Clickable target) {
		// リスナーがダイアログを閉じるとウィジェットは破棄されるので、返す文章は先に作っておく
		String done = "Clicked the " + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$
		Widget widget = target.widget;
		if (widget instanceof Button) {
			Button button = (Button) widget;
			if (!button.isEnabled()) {
				throw new IllegalStateException("The " + target.describe() + " is disabled."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			int style = button.getStyle();
			if ((style & (SWT.CHECK | SWT.TOGGLE)) != 0) {
				button.setSelection(!button.getSelection());
				done = (button.getSelection() ? "Turned on the " : "Turned off the ") + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else if ((style & SWT.RADIO) != 0) {
				selectRadio(button);
				done = "Chose the " + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$
			}
			button.notifyListeners(SWT.Selection, new Event());
		} else if (widget instanceof ToolItem) {
			ToolItem item = (ToolItem) widget;
			if (!item.isEnabled()) {
				throw new IllegalStateException("The " + target.describe() + " is disabled."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if ((item.getStyle() & SWT.CHECK) != 0) {
				item.setSelection(!item.getSelection());
				done = (item.getSelection() ? "Turned on the " : "Turned off the ") + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else if ((item.getStyle() & SWT.RADIO) != 0) {
				item.setSelection(true);
			}
			item.notifyListeners(SWT.Selection, new Event());
		} else if (widget instanceof Link) {
			Link link = (Link) widget;
			Matcher anchor = ANCHOR.matcher(link.getText());
			Event event = new Event();
			// リンクのリスナーは、どのリンクが押されたかを text で受け取る (href があればその値、なければ表示の文字)
			event.text = !anchor.find() ? link.getText() : anchor.group(1) != null ? anchor.group(1) : anchor.group(2);
			link.notifyListeners(SWT.Selection, event);
		} else if (widget instanceof TabItem) {
			TabItem item = (TabItem) widget;
			item.getParent().setSelection(item);
			item.getParent().notifyListeners(SWT.Selection, itemEvent(item));
			done = "Switched to the " + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			CTabItem item = (CTabItem) widget;
			item.getParent().setSelection(item);
			item.getParent().notifyListeners(SWT.Selection, itemEvent(item));
			done = "Switched to the " + target.describe() + "."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return done;
	}

	/** ラジオボタンは、同じ親の中でそれまで選ばれていたものを外してから選ぶ。 */
	private static void selectRadio(Button button) {
		for (Control sibling : button.getParent().getChildren()) {
			if (sibling != button && sibling instanceof Button && (sibling.getStyle() & SWT.RADIO) != 0
					&& ((Button) sibling).getSelection()) {
				((Button) sibling).setSelection(false);
				sibling.notifyListeners(SWT.Selection, new Event());
			}
		}
		button.setSelection(true);
	}

	// ---- 文字の入力 ----

	/** @param enter 入力のあとで Enter を押す (入力欄の既定の動作を起こす) */
	static String setText(Control control, String text, boolean enter) {
		if (!control.isEnabled()) {
			throw new IllegalStateException("That field is disabled."); //$NON-NLS-1$
		}
		if (control instanceof Text) {
			Text field = (Text) control;
			if (!field.getEditable()) {
				throw new IllegalStateException("That text is read-only."); //$NON-NLS-1$
			}
			field.setText(text);
			if (enter) {
				field.notifyListeners(SWT.DefaultSelection, new Event());
			}
			return enter ? "Entered the text and pressed Enter." : "Entered the text."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (control instanceof StyledText) {
			StyledText field = (StyledText) control;
			if (!field.getEditable()) {
				throw new IllegalStateException("That text is read-only."); //$NON-NLS-1$
			}
			if (enter) {
				throw new IllegalArgumentException("enter is not supported for this kind of text area. Put the line break in the text instead."); //$NON-NLS-1$
			}
			field.setText(text);
			return "Replaced the text."; //$NON-NLS-1$
		}
		if (control instanceof Combo) {
			Combo combo = (Combo) control;
			if ((combo.getStyle() & SWT.READ_ONLY) != 0) {
				int index = choose(combo.getItems(), text);
				combo.select(index);
				combo.notifyListeners(SWT.Selection, new Event());
				return "Chose " + Labels.quote(combo.getItem(index)) + "."; //$NON-NLS-1$ //$NON-NLS-2$
			}
			combo.setText(text);
			if (enter) {
				combo.notifyListeners(SWT.DefaultSelection, new Event());
			}
			return enter ? "Entered the text and pressed Enter." : "Entered the text."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (control instanceof CCombo) {
			CCombo combo = (CCombo) control;
			if (!combo.getEditable()) {
				int index = choose(combo.getItems(), text);
				combo.select(index);
				combo.notifyListeners(SWT.Selection, new Event());
				return "Chose " + Labels.quote(combo.getItem(index)) + "."; //$NON-NLS-1$ //$NON-NLS-2$
			}
			combo.setText(text);
			if (enter) {
				combo.notifyListeners(SWT.DefaultSelection, new Event());
			}
			return enter ? "Entered the text and pressed Enter." : "Entered the text."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		throw new IllegalArgumentException("That widget is not a text field or a combo."); //$NON-NLS-1$
	}

	private static int choose(String[] choices, String wanted) {
		List<String> items = Arrays.asList(choices);
		String found = Labels.single(items, s -> s, wanted);
		if (found == null) {
			throw new IllegalStateException(Labels.quote(wanted) + " is not one of the choices: " //$NON-NLS-1$
					+ Labels.list(items, s -> s) + "."); //$NON-NLS-1$
		}
		return items.indexOf(found);
	}

	// ---- コンテキストメニュー ----

	/**
	 * @param path メニューの項目までのラベル。空ならメニューの中身を返す。サブメニューで終わっていれば、その中身を返す
	 */
	static String contextMenu(Control control, List<String> path) {
		Menu menu = control.getMenu();
		if (menu == null || menu.isDisposed()) {
			throw new IllegalStateException("That widget has no context menu."); //$NON-NLS-1$
		}
		return menu(menu, path, "context menu"); //$NON-NLS-1$
	}

	/**
	 * メニュー (右クリックメニュー、メインメニュー、ビューのメニュー) の中身を返す、または項目を実行する。
	 *
	 * @param path メニューの項目までのラベル。空ならメニューの中身を返す。サブメニューで終わっていれば、その中身を返す
	 * @param kind 結果の文章に使うメニューの呼び名
	 */
	static String menu(Menu menu, List<String> path, String kind) {
		// メニューの項目は、開く直前に選択に合わせて作られる。開いたときと同じイベントを送って作らせる
		List<Menu> shown = new ArrayList<>();
		try {
			Menu current = show(menu, shown);
			for (int i = 0; i < path.size(); i++) {
				List<MenuItem> items = items(current);
				MenuItem item = Labels.single(items, MenuItem::getText, path.get(i));
				if (item == null) {
					throw new IllegalStateException("The menu has no item " + Labels.quote(path.get(i)) + ". Its items:\n" //$NON-NLS-1$ //$NON-NLS-2$
							+ describe(current));
				}
				Menu submenu = (item.getStyle() & SWT.CASCADE) != 0 ? item.getMenu() : null;
				if (submenu != null) {
					current = show(submenu, shown);
					continue;
				}
				if (i < path.size() - 1) {
					throw new IllegalStateException(Labels.quote(path.get(i)) + " is not a submenu."); //$NON-NLS-1$
				}
				if (!item.isEnabled()) {
					throw new IllegalStateException("The menu item " + Labels.quote(Labels.normalize(item.getText())) //$NON-NLS-1$
							+ " is disabled for the current selection."); //$NON-NLS-1$
				}
				if ((item.getStyle() & SWT.CHECK) != 0) {
					item.setSelection(!item.getSelection());
				} else if ((item.getStyle() & SWT.RADIO) != 0) {
					item.setSelection(true);
				}
				item.notifyListeners(SWT.Selection, new Event());
				return "Chose " + Labels.quote(String.join(" > ", path)) + " from the " + kind + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			return (path.isEmpty() ? "The " + kind : "Submenu " + Labels.quote(String.join(" > ", path))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ " ('>' marks a submenu):\n" + describe(current); //$NON-NLS-1$
		} finally {
			for (int i = shown.size() - 1; i >= 0; i--) {
				if (!shown.get(i).isDisposed()) {
					shown.get(i).notifyListeners(SWT.Hide, new Event());
				}
			}
		}
	}

	private static Menu show(Menu menu, List<Menu> shown) {
		menu.notifyListeners(SWT.Show, new Event());
		shown.add(menu);
		return menu;
	}

	private static List<MenuItem> items(Menu menu) {
		List<MenuItem> items = new ArrayList<>();
		for (MenuItem item : menu.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) == 0) {
				items.add(item);
			}
		}
		return items;
	}

	private static String describe(Menu menu) {
		StringBuilder sb = new StringBuilder();
		for (MenuItem item : items(menu)) {
			sb.append("  "); //$NON-NLS-1$
			if ((item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) {
				sb.append(item.getSelection() ? "[x] " : "[ ] "); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append(Labels.normalize(item.getText()));
			if ((item.getStyle() & SWT.CASCADE) != 0) {
				sb.append(" >"); //$NON-NLS-1$
			}
			if (!item.isEnabled()) {
				sb.append(" (disabled)"); //$NON-NLS-1$
			}
			sb.append('\n');
		}
		return sb.length() == 0 ? "  (empty)\n" : sb.toString(); //$NON-NLS-1$
	}
}
