package com.tkdevelopeross.eclipse.incline.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart2;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.IViewDescriptor;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * ビューを探す・開く・中身を読むツール。
 */
final class ViewTools {

	private static final int DEFAULT_MAX_ITEMS = 300;

	private ViewTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("list_views", //$NON-NLS-1$
				"List the views this Eclipse can show, as 'Category / Name (id)'. Views already open in the current perspective are marked with *. " //$NON-NLS-1$
						+ "Pass a filter to narrow the list.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("filter", "Words that must all appear in the category, name or id (case-insensitive), e.g. 'debug' or 'git staging'"), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					String filter = Args.optString(args, "filter"); //$NON-NLS-1$
					return Ui.call(() -> listViews(filter));
				}));

		server.register(new EclipseTool("show_view", //$NON-NLS-1$
				"Open a view in the current perspective and bring it to the front, so the user sees it and read_view can read it. " //$NON-NLS-1$
						+ "Get the id from list_views.", //$NON-NLS-1$
				ToolEffect.NAVIGATE,
				new Schema().string("viewId", "View id, e.g. org.eclipse.ui.views.ProblemView") //$NON-NLS-1$ //$NON-NLS-2$
						.bool("activate", "Also give the view the keyboard focus (default false). Needed before execute_command when the command acts on this view.") //$NON-NLS-1$ //$NON-NLS-2$
						.required("viewId"), //$NON-NLS-1$
				args -> {
					String id = Args.string(args, "viewId"); //$NON-NLS-1$
					boolean activate = Args.optBool(args, "activate", false); //$NON-NLS-1$
					return Ui.call(() -> showView(id, activate));
				}));

		server.register(new EclipseTool("read_view", //$NON-NLS-1$
				"Read what an open view currently displays: its trees, tables, lists, text, labels, buttons and toolbars. " //$NON-NLS-1$
						+ "Works for any view of any perspective (Problems, Console, Debug, Variables, Breakpoints, Search, Git Staging, History, Outline, ...), " //$NON-NLS-1$
						+ "and with viewId 'editor' for the controls of the front editor. " //$NON-NLS-1$
						+ "In trees, 'v' marks an expanded node and '>' a collapsed one whose children are not shown; <selected> marks the selection. " //$NON-NLS-1$
						+ "Trees, tables, lists and text fields are numbered (#1, #2, ...): pass that number as 'widget' to select_item, set_text and context_menu. " //$NON-NLS-1$
						+ "The view must be open: call show_view first if it is not.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("viewId", "View id as shown by get_workbench_state or list_views, or 'editor'") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("maxItems", "Maximum number of tree/table/list rows to return (default " + DEFAULT_MAX_ITEMS + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.required("viewId"), //$NON-NLS-1$
				args -> {
					String id = Args.string(args, "viewId"); //$NON-NLS-1$
					int maxItems = Args.optInt(args, "maxItems", DEFAULT_MAX_ITEMS); //$NON-NLS-1$
					return Ui.call(() -> readView(id, maxItems));
				}));
	}

	private static String listViews(String filter) {
		String[] terms = filter == null ? new String[0] : filter.toLowerCase(Locale.ROOT).split("\\s+"); //$NON-NLS-1$
		Set<String> open = new HashSet<>();
		IWorkbenchPage page = Ui.window().getActivePage();
		if (page != null) {
			for (IViewReference ref : page.getViewReferences()) {
				open.add(ref.getId());
			}
		}
		List<String> lines = new ArrayList<>();
		for (IViewDescriptor descriptor : PlatformUI.getWorkbench().getViewRegistry().getViews()) {
			String[] category = descriptor.getCategoryPath();
			String name = (category == null || category.length == 0 ? "" : String.join("/", category) + " / ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ descriptor.getLabel() + " (" + descriptor.getId() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
			if (containsAll(name.toLowerCase(Locale.ROOT), terms)) {
				lines.add(name + (open.contains(descriptor.getId()) ? " *" : "")); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		if (lines.isEmpty()) {
			return "No view matches \"" + filter + "\". Try a shorter or different word."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
		return lines.size() + " views (* = open in the current perspective):\n" + String.join("\n", lines) + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	static boolean containsAll(String haystack, String[] terms) {
		for (String term : terms) {
			if (!term.isEmpty() && !haystack.contains(term)) {
				return false;
			}
		}
		return true;
	}

	private static String showView(String viewId, boolean activate) throws Exception {
		String id = viewId;
		String secondaryId = null;
		int colon = viewId.indexOf(':');
		if (colon > 0) {
			id = viewId.substring(0, colon);
			secondaryId = viewId.substring(colon + 1);
		}
		if (PlatformUI.getWorkbench().getViewRegistry().find(id) == null) {
			throw new IllegalArgumentException("Unknown view id: " + id + ". Call list_views for the available ids."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IViewPart part = Ui.page().showView(id, secondaryId,
				activate ? IWorkbenchPage.VIEW_ACTIVATE : IWorkbenchPage.VIEW_VISIBLE);
		String name = part instanceof IWorkbenchPart2 ? ((IWorkbenchPart2) part).getPartName() : part.getTitle();
		return "The " + name + " view is now shown" + (activate ? " and active." : "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static String readView(String viewId, int maxItems) {
		UiTarget target = UiTarget.resolve(viewId);
		StringBuilder sb = new StringBuilder(target.title());
		if (!target.isVisible()) {
			sb.append(" [hidden behind another tab, so its content may not be up to date]"); //$NON-NLS-1$
		}
		sb.append('\n');
		if (target.part() instanceof IWorkbenchPart2) {
			String description = ((IWorkbenchPart2) target.part()).getContentDescription();
			if (description != null && !description.isEmpty()) {
				sb.append("Status: ").append(description).append('\n'); //$NON-NLS-1$
			}
		}
		// タイトルの横のツールバーは、ビューの中身の外側にある
		ToolBar toolBar = target.toolBar();
		String toolBarItems = toolBar == null ? "" : WidgetReader.describeToolBar(toolBar); //$NON-NLS-1$
		if (!toolBarItems.isEmpty()) {
			sb.append("View toolbar:").append(toolBarItems).append('\n'); //$NON-NLS-1$
		}
		sb.append(WidgetReader.read(target.root(), maxItems));
		return sb.toString();
	}
}
