package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

/**
 * 画面の中身 (SWT のウィジェット) を文章にする。
 * ビューごとの専用コードを書かなくても、どのパースペクティブのどのビューでも、ダイアログでも Claude が読めるようにするためのもの。
 * 操作の対象になるウィジェット (ツリー、テーブル、リスト、コンボ、テキスト) には #1, #2, ... と番号を振る。
 * UI スレッドで呼ぶこと。
 */
final class WidgetReader {

	private static final int MAX_TEXT_CHARS = 6000;
	private static final int MAX_CELL_CHARS = 300;
	private static final int MAX_OUTPUT_CHARS = 40000;
	private static final int MAX_COMBO_CHOICES = 12;

	private final StringBuilder out = new StringBuilder();
	private final List<Control> addressable = new ArrayList<>();
	private final int maxItems;
	private int items;
	private boolean itemLimitReported;
	private boolean truncated;

	private WidgetReader(int maxItems) {
		this.maxItems = maxItems;
	}

	/**
	 * @param root     読み始めるコントロール。これ自身が隠れていても中身は読む
	 * @param maxItems ツリー・テーブル・リストの項目を、全部でいくつまで出すか
	 */
	static String read(Control root, int maxItems) {
		WidgetReader reader = new WidgetReader(maxItems);
		reader.visit(root, 0);
		if (reader.out.length() == 0) {
			return "(nothing readable: the content is empty or drawn by the view itself)\n"; //$NON-NLS-1$
		}
		return reader.out.toString();
	}

	/**
	 * read() が番号を振ったウィジェットを、番号の順に返す (#1 が先頭)。
	 * 番号は中身ではなくウィジェットの並びで決まるので、画面の構成が変わらない限り read() の出力と一致する。
	 */
	static List<Control> addressable(Control root) {
		// 項目は読まない (仮想テーブルの行を作らせないため)
		WidgetReader reader = new WidgetReader(0);
		reader.visit(root, 0);
		return reader.addressable;
	}

	/** @return " [Run] [Stop (disabled)]" のような並び。名前のある項目がなければ空 */
	static String describeToolBar(ToolBar toolBar) {
		StringBuilder sb = new StringBuilder();
		for (ToolItem item : toolBar.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) != 0) {
				continue;
			}
			String label = item.getText();
			if (label.isEmpty() && item.getToolTipText() != null) {
				label = item.getToolTipText();
			}
			if (label.isEmpty()) {
				continue;
			}
			sb.append(" [").append(clean(Labels.normalize(label))); //$NON-NLS-1$
			if ((item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0 && item.getSelection()) {
				sb.append(" (on)"); //$NON-NLS-1$
			}
			if (!item.getEnabled()) {
				sb.append(" (disabled)"); //$NON-NLS-1$
			}
			sb.append(']');
		}
		return sb.toString();
	}

	private void visit(Control control, int depth) {
		// 個別の型を先に見る (Tree や StyledText なども Composite の一種なので)
		if (control instanceof Tree) {
			tree((Tree) control, depth);
		} else if (control instanceof Table) {
			table((Table) control, depth);
		} else if (control instanceof StyledText) {
			StyledText text = (StyledText) control;
			text(text, text.getText(), text.getEditable(), depth);
		} else if (control instanceof Text) {
			Text text = (Text) control;
			if ((text.getStyle() & SWT.PASSWORD) != 0) {
				line(depth, "Password field" + number(text) + " (content not shown)"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				text(text, text.getText(), text.getEditable(), depth);
			}
		} else if (control instanceof org.eclipse.swt.widgets.List) {
			list((org.eclipse.swt.widgets.List) control, depth);
		} else if (control instanceof Combo) {
			Combo combo = (Combo) control;
			combo(combo, combo.getText(), combo.getItems(), depth);
		} else if (control instanceof CCombo) {
			CCombo combo = (CCombo) control;
			combo(combo, combo.getText(), combo.getItems(), depth);
		} else if (control instanceof Button) {
			button((Button) control, depth);
		} else if (control instanceof Label) {
			Label label = (Label) control;
			if ((label.getStyle() & SWT.SEPARATOR) == 0) {
				label(label.getText(), depth);
			}
		} else if (control instanceof CLabel) {
			label(((CLabel) control).getText(), depth);
		} else if (control instanceof Link) {
			label(((Link) control).getText(), depth);
		} else if (control instanceof ToolBar) {
			String toolBar = describeToolBar((ToolBar) control);
			if (!toolBar.isEmpty()) {
				line(depth, "Toolbar:" + toolBar); //$NON-NLS-1$
			}
		} else if (control instanceof Browser) {
			line(depth, "Browser showing " + ((Browser) control).getUrl() + " (page content is not readable)"); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (control instanceof Composite) {
			composite((Composite) control, depth);
		}
		// それ以外 (Sash、ProgressBar など) には読むものがない
	}

	/** 操作の対象になるウィジェットに番号を振る。中身が空でも振る (中身が変わっても番号がずれないように)。 */
	private String number(Control control) {
		addressable.add(control);
		return " #" + addressable.size(); //$NON-NLS-1$
	}

	private void composite(Composite composite, int depth) {
		int childDepth = depth;
		if (composite instanceof Group) {
			line(depth, "Group: " + clean(Labels.normalize(((Group) composite).getText()))); //$NON-NLS-1$
			childDepth++;
		} else if (composite instanceof TabFolder) {
			TabFolder folder = (TabFolder) composite;
			Set<TabItem> selected = new HashSet<>(Arrays.asList(folder.getSelection()));
			StringBuilder sb = new StringBuilder("Tabs:"); //$NON-NLS-1$
			for (TabItem item : folder.getItems()) {
				sb.append(" [").append(clean(item.getText())).append(selected.contains(item) ? " (selected)]" : "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			line(depth, sb.toString());
			childDepth++;
		} else if (composite instanceof CTabFolder) {
			CTabFolder folder = (CTabFolder) composite;
			StringBuilder sb = new StringBuilder("Tabs:"); //$NON-NLS-1$
			for (CTabItem item : folder.getItems()) {
				sb.append(" [").append(clean(item.getText())).append(item == folder.getSelection() ? " (selected)]" : "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			line(depth, sb.toString());
			childDepth++;
		}
		Control[] children = composite.getChildren();
		String className = composite.getClass().getSimpleName();
		if (isTerminal(composite)) {
			terminal(composite, depth);
			return;
		}
		if (children.length == 0 && composite instanceof Canvas && composite.getClass() != Canvas.class
				&& !className.isEmpty()) {
			// 図やグラフのように、自分で描画しているもの。名前のないクラスはエディタの行番号欄のような飾りなので出さない
			line(depth, "(" + className + ": drawn by the view itself, not readable)"); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		for (Control child : children) {
			// 隠れているページやタブの中身は出さない
			if (child.getVisible()) {
				visit(child, childDepth);
			}
		}
	}

	// ---- ターミナル ----

	/** @return Terminal ビューの画面 (TM Terminal の、文字を自分で描画するキャンバス) なら true */
	static boolean isTerminal(Control control) {
		return control.getClass().getName().equals("org.eclipse.tm.internal.terminal.textcanvas.TextCanvas"); //$NON-NLS-1$
	}

	/**
	 * ターミナルに出ている文字 (スクロールで隠れた分も含む) を取り出す。
	 * ターミナルのプラグインは入っていないこともあるので、クラスを直接は参照せず、画面の部品が持つメソッドを名前で呼ぶ。
	 *
	 * @return 取り出せなければ null
	 */
	static String terminalText(Control canvas) {
		try {
			Object text = canvas.getClass().getMethod("getAllText").invoke(canvas); //$NON-NLS-1$
			if (text == null) {
				return ""; //$NON-NLS-1$
			}
			// 行末の空白と、末尾の空行を落とす (画面の幅と高さの分だけ空白で埋まっている)
			StringBuilder sb = new StringBuilder();
			int lastContent = 0;
			for (String line : text.toString().replace('\0', ' ').split("\r?\n", -1)) { //$NON-NLS-1$
				int end = line.length();
				while (end > 0 && line.charAt(end - 1) == ' ') {
					end--;
				}
				sb.append(line, 0, end).append('\n');
				if (end > 0) {
					lastContent = sb.length();
				}
			}
			return sb.substring(0, lastContent);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private void terminal(Control canvas, int depth) {
		String text = terminalText(canvas);
		if (text == null) {
			line(depth, "(terminal: its text could not be read)"); //$NON-NLS-1$
			return;
		}
		if (text.isEmpty()) {
			line(depth, "Terminal (empty)"); //$NON-NLS-1$
			return;
		}
		// ターミナルは新しい出力が下にあるので、長いときは末尾を残す
		boolean cut = text.length() > MAX_TEXT_CHARS;
		line(depth, "Terminal (" + text.length() + " chars" + (cut ? ", showing the end" : "") + "):"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		String shown = cut ? text.substring(text.length() - MAX_TEXT_CHARS) : text;
		for (String textLine : shown.split("\n", -1)) { //$NON-NLS-1$
			line(depth + 1, textLine);
		}
	}

	private void tree(Tree tree, int depth) {
		line(depth, "Tree" + number(tree) + header(columnTexts(tree)) + (tree.getItemCount() == 0 ? " (empty)" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		Set<TreeItem> selected = new HashSet<>(Arrays.asList(tree.getSelection()));
		boolean checkable = (tree.getStyle() & SWT.CHECK) != 0;
		int columns = Math.max(1, tree.getColumnCount());
		for (TreeItem item : tree.getItems()) {
			if (!treeItem(item, depth + 1, columns, checkable, selected)) {
				return;
			}
		}
	}

	/** @return 項目数の上限に達したら false */
	private boolean treeItem(TreeItem item, int depth, int columns, boolean checkable, Set<TreeItem> selected) {
		boolean hasChildren = item.getItemCount() > 0;
		boolean expanded = hasChildren && item.getExpanded();
		String[] texts = new String[columns];
		for (int i = 0; i < columns; i++) {
			texts[i] = item.getText(i);
		}
		String label = cells(texts);
		if (label.isEmpty() && !hasChildren) {
			// 中身をまだ読み込んでいない行 (非同期に埋まるツリーの、画面の外の行など)
			return true;
		}
		if (!take()) {
			return false;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(hasChildren ? (expanded ? "v " : "> ") : "  "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (checkable) {
			sb.append(item.getChecked() ? "[x] " : "[ ] "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append(label);
		if (selected.contains(item)) {
			sb.append("  <selected>"); //$NON-NLS-1$
		}
		line(depth, sb.toString());
		if (expanded) {
			for (TreeItem child : item.getItems()) {
				if (!treeItem(child, depth + 1, columns, checkable, selected)) {
					return false;
				}
			}
		}
		return true;
	}

	private void table(Table table, int depth) {
		int count = table.getItemCount();
		line(depth, "Table" + number(table) + header(columnTexts(table)) + " (" + count + (count == 1 ? " row)" : " rows)")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		boolean checkable = (table.getStyle() & SWT.CHECK) != 0;
		// getItems() は仮想テーブルの全行を作ってしまうので、出す分だけ 1 行ずつ取る
		for (int row = 0; row < count; row++) {
			if (!take()) {
				return;
			}
			TableItem item = table.getItem(row);
			line(depth + 1, (checkable ? (item.getChecked() ? "[x] " : "[ ] ") : "") + rowText(table, item) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ (table.isSelected(row) ? "  <selected>" : "")); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** テーブルの 1 行を、read() の出力と同じ形 (列を " | " でつないだもの) にする。 */
	static String rowText(Table table, TableItem item) {
		int columns = Math.max(1, table.getColumnCount());
		String[] texts = new String[columns];
		for (int i = 0; i < columns; i++) {
			texts[i] = item.getText(i);
		}
		return cells(texts);
	}

	private void list(org.eclipse.swt.widgets.List list, int depth) {
		int count = list.getItemCount();
		line(depth, "List" + number(list) + " (" + count + (count == 1 ? " item)" : " items)")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		for (int i = 0; i < count; i++) {
			if (!take()) {
				return;
			}
			line(depth + 1, clean(list.getItem(i)) + (list.isSelected(i) ? "  <selected>" : "")); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private void combo(Control combo, String text, String[] choices, int depth) {
		StringBuilder sb = new StringBuilder("Combo").append(number(combo)).append(": ").append(clean(text)); //$NON-NLS-1$ //$NON-NLS-2$
		if (choices.length > 0 && choices.length <= MAX_COMBO_CHOICES) {
			sb.append(" (choices: "); //$NON-NLS-1$
			for (int i = 0; i < choices.length; i++) {
				sb.append(i > 0 ? ", " : "").append(clean(choices[i])); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append(')');
		} else {
			sb.append(" (").append(choices.length).append(" choices)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		line(depth, sb.toString());
	}

	private void text(Control control, String content, boolean editable, int depth) {
		String kind = (editable ? "Editable text" : "Text") + number(control); //$NON-NLS-1$ //$NON-NLS-2$
		if (content.trim().isEmpty()) {
			line(depth, kind + " (empty)"); //$NON-NLS-1$
			return;
		}
		if (content.indexOf('\n') < 0 && content.length() <= MAX_CELL_CHARS) {
			line(depth, kind + ": " + content); //$NON-NLS-1$
			return;
		}
		line(depth, kind + " (" + content.length() + " chars):"); //$NON-NLS-1$ //$NON-NLS-2$
		for (String textLine : Ui.clip(content, MAX_TEXT_CHARS).split("\r?\n", -1)) { //$NON-NLS-1$
			line(depth + 1, textLine);
		}
	}

	private void button(Button button, int depth) {
		int style = button.getStyle();
		if ((style & SWT.ARROW) != 0) {
			return;
		}
		String label = button.getText();
		if (label.isEmpty() && button.getToolTipText() != null) {
			label = button.getToolTipText();
		}
		if (label.isEmpty()) {
			return;
		}
		String kind = "Button"; //$NON-NLS-1$
		if ((style & SWT.CHECK) != 0) {
			kind = button.getSelection() ? "Checkbox [x]" : "Checkbox [ ]"; //$NON-NLS-1$ //$NON-NLS-2$
		} else if ((style & SWT.RADIO) != 0) {
			kind = button.getSelection() ? "Radio (o)" : "Radio ( )"; //$NON-NLS-1$ //$NON-NLS-2$
		} else if ((style & SWT.TOGGLE) != 0) {
			kind = button.getSelection() ? "Toggle [on]" : "Toggle [off]"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		line(depth, kind + ": " + clean(Labels.normalize(label)) + (button.getEnabled() ? "" : " (disabled)")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private void label(String text, int depth) {
		String cleaned = clean(Labels.normalize(text));
		if (!cleaned.isEmpty()) {
			line(depth, cleaned);
		}
	}

	/** @return 項目数の上限に達していたら false (最初の 1 回だけ、省略したことを書く) */
	private boolean take() {
		if (items >= maxItems) {
			if (!itemLimitReported) {
				itemLimitReported = true;
				line(0, "... more items are not shown (limit " + maxItems + "; pass a larger maxItems to see more)"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return false;
		}
		items++;
		return true;
	}

	private void line(int depth, String text) {
		if (truncated) {
			return;
		}
		if (out.length() > MAX_OUTPUT_CHARS) {
			truncated = true;
			out.append("... output truncated\n"); //$NON-NLS-1$
			return;
		}
		for (int i = 0; i < depth; i++) {
			out.append("  "); //$NON-NLS-1$
		}
		out.append(text).append('\n');
	}

	private static String[] columnTexts(Tree tree) {
		TreeColumn[] columns = tree.getColumns();
		String[] texts = new String[columns.length];
		for (int i = 0; i < columns.length; i++) {
			texts[i] = columns[i].getText();
		}
		return texts;
	}

	private static String[] columnTexts(Table table) {
		TableColumn[] columns = table.getColumns();
		String[] texts = new String[columns.length];
		for (int i = 0; i < columns.length; i++) {
			texts[i] = columns[i].getText();
		}
		return texts;
	}

	private static String header(String[] columns) {
		String joined = cells(columns);
		return joined.isEmpty() ? "" : " [columns: " + joined + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** 列を " | " でつなぐ。後ろの空の列は出さない。 */
	static String cells(String[] texts) {
		int last = texts.length - 1;
		while (last >= 0 && clean(texts[last]).isEmpty()) {
			last--;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i <= last; i++) {
			if (i > 0) {
				sb.append(" | "); //$NON-NLS-1$
			}
			sb.append(clean(texts[i]));
		}
		return sb.toString();
	}

	/** 1 行に収める。 */
	static String clean(String text) {
		if (text == null) {
			return ""; //$NON-NLS-1$
		}
		String oneLine = text.replace("\r", "").replace('\n', ' ').replace('\t', ' ').trim(); //$NON-NLS-1$ //$NON-NLS-2$
		return oneLine.length() > MAX_CELL_CHARS ? oneLine.substring(0, MAX_CELL_CHARS) + "..." : oneLine; //$NON-NLS-1$
	}
}
