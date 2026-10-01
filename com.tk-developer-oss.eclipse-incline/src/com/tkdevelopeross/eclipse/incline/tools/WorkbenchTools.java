package com.tkdevelopeross.eclipse.incline.tools;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * ワークベンチの状態を見るツールと、パースペクティブ・エディタを切り替えるツール。
 */
final class WorkbenchTools {

	private static final int MAX_SELECTION_CHARS = 2000;
	private static final int MAX_SELECTED_ELEMENTS = 10;
	private static final int MAX_ELEMENT_CHARS = 120;

	private WorkbenchTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("get_workbench_state", //$NON-NLS-1$
				"Describe what the user currently has in Eclipse: the workspace and its projects, the current perspective, the active part, " //$NON-NLS-1$
						+ "the open editors (file paths, unsaved changes), the text selected in the front editor, and the open views with what is selected in them. " //$NON-NLS-1$
						+ "Call this first whenever a request depends on what the user is looking at or has selected.", //$NON-NLS-1$
				ToolEffect.READ, new Schema(), args -> Ui.call(WorkbenchTools::describeState)));

		server.register(new EclipseTool("list_perspectives", //$NON-NLS-1$
				"List the perspectives available in this Eclipse (label and id). The current one is marked with *.", //$NON-NLS-1$
				ToolEffect.READ, new Schema(), args -> Ui.call(WorkbenchTools::listPerspectives)));

		server.register(new EclipseTool("open_perspective", //$NON-NLS-1$
				"Switch the workbench window to a perspective (for example Debug, Git, Java, PHP). Get the id from list_perspectives.", //$NON-NLS-1$
				ToolEffect.NAVIGATE,
				new Schema().string("perspectiveId", "Perspective id, e.g. org.eclipse.debug.ui.DebugPerspective").required("perspectiveId"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> {
					String id = Args.string(args, "perspectiveId"); //$NON-NLS-1$
					return Ui.call(() -> openPerspective(id));
				}));

		server.register(new EclipseTool("open_file", //$NON-NLS-1$
				"Open a file in an Eclipse editor so the user sees it, optionally selecting a line. It does not take the keyboard focus.", //$NON-NLS-1$
				ToolEffect.NAVIGATE,
				new Schema().string("path", "Absolute file system path, or a workspace path such as /project/src/main.c") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("line", "1-based line number to select and reveal").required("path"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> {
					String path = Args.string(args, "path"); //$NON-NLS-1$
					int line = Args.optInt(args, "line", 0); //$NON-NLS-1$
					// ワークスペースの読み直しは UI スレッドの外で済ませる (ビルド中だと待たされるため)
					IFile file = workspaceFile(path);
					return Ui.call(() -> openFile(file, path, line));
				}));
	}

	// ---- get_workbench_state ----

	private static String describeState() {
		StringBuilder sb = new StringBuilder();
		IWorkspaceRoot root = Workspaces.root();
		sb.append("Workspace: ").append(Workspaces.location(root)).append('\n'); //$NON-NLS-1$
		IProject[] projects = root.getProjects();
		sb.append("Projects:").append(projects.length == 0 ? " (none)\n" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (IProject project : projects) {
			sb.append("  ").append(project.getName()); //$NON-NLS-1$
			if (!project.isOpen()) {
				sb.append(" [closed]"); //$NON-NLS-1$
			}
			sb.append(" -> ").append(Workspaces.location(project)).append('\n'); //$NON-NLS-1$
		}

		IWorkbenchWindow window = Ui.window();
		IWorkbenchPage page = window.getActivePage();
		if (page == null) {
			sb.append("The workbench window has no active page.\n"); //$NON-NLS-1$
			return sb.toString();
		}
		IPerspectiveDescriptor perspective = page.getPerspective();
		sb.append("Perspective: ") //$NON-NLS-1$
				.append(perspective == null ? "(none)" : perspective.getLabel() + " (" + perspective.getId() + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append('\n');
		IWorkbenchPart activePart = page.getActivePart();
		sb.append("Active part: ") //$NON-NLS-1$
				.append(activePart == null ? "(none)" : activePart.getTitle() + " (" + activePart.getSite().getId() + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append('\n');

		IEditorPart frontEditor = page.getActiveEditor();
		IEditorReference[] editors = page.getEditorReferences();
		sb.append("Editors (* = in front):").append(editors.length == 0 ? " (none)\n" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (IEditorReference ref : editors) {
			sb.append(frontEditor != null && ref.getEditor(false) == frontEditor ? "  * " : "    "); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append(ref.getTitle());
			if (ref.isDirty()) {
				sb.append(" [unsaved changes]"); //$NON-NLS-1$
			}
			String path = editorPath(ref);
			if (path != null) {
				sb.append(" -> ").append(path); //$NON-NLS-1$
			}
			sb.append('\n');
		}
		if (frontEditor != null) {
			appendEditorSelection(sb, frontEditor);
		}

		sb.append("Open views:\n"); //$NON-NLS-1$
		ISelectionService selections = window.getSelectionService();
		for (IViewReference ref : page.getViewReferences()) {
			IWorkbenchPart part = ref.getPart(false);
			sb.append("  ").append(ref.getPartName()).append(" (").append(UiTarget.viewId(ref)).append(')'); //$NON-NLS-1$ //$NON-NLS-2$
			if (part != null && page.isPartVisible(part)) {
				sb.append(" [visible]"); //$NON-NLS-1$
			}
			sb.append('\n');
			if (part != null && ref.getSecondaryId() == null) {
				appendStructuredSelection(sb, selections.getSelection(ref.getId()));
			}
		}
		return sb.toString();
	}

	private static String editorPath(IEditorReference ref) {
		try {
			IEditorInput input = ref.getEditorInput();
			if (input instanceof IPathEditorInput) {
				return ((IPathEditorInput) input).getPath().toOSString();
			}
			if (input instanceof IURIEditorInput) {
				return ((IURIEditorInput) input).getURI().toString();
			}
		} catch (PartInitException | RuntimeException e) {
			// 場所の分からない入力 (ローカルにないファイルなど) はタイトルだけ出す
		}
		return null;
	}

	private static void appendEditorSelection(StringBuilder sb, IEditorPart editor) {
		ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
		if (textEditor == null || textEditor.getSelectionProvider() == null) {
			return;
		}
		ISelection selection = textEditor.getSelectionProvider().getSelection();
		if (!(selection instanceof ITextSelection)) {
			return;
		}
		ITextSelection text = (ITextSelection) selection;
		if (text.getLength() > 0 && text.getText() != null) {
			sb.append("Selected in the front editor (lines ").append(text.getStartLine() + 1).append('-') //$NON-NLS-1$
					.append(text.getEndLine() + 1).append("):\n"); //$NON-NLS-1$
			for (String line : Ui.clip(text.getText(), MAX_SELECTION_CHARS).split("\r?\n", -1)) { //$NON-NLS-1$
				sb.append("    ").append(line).append('\n'); //$NON-NLS-1$
			}
		} else if (text.getStartLine() >= 0) {
			sb.append("Caret in the front editor: line ").append(text.getStartLine() + 1).append('\n'); //$NON-NLS-1$
		}
	}

	private static void appendStructuredSelection(StringBuilder sb, ISelection selection) {
		if (!(selection instanceof IStructuredSelection) || selection.isEmpty()) {
			return;
		}
		Object[] elements = ((IStructuredSelection) selection).toArray();
		sb.append("      selected: "); //$NON-NLS-1$
		for (int i = 0; i < elements.length; i++) {
			if (i == MAX_SELECTED_ELEMENTS) {
				sb.append(", ... (").append(elements.length - i).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
				break;
			}
			if (i > 0) {
				sb.append(", "); //$NON-NLS-1$
			}
			sb.append(describeElement(elements[i]));
		}
		sb.append('\n');
	}

	private static String describeElement(Object element) {
		IResource resource = Adapters.adapt(element, IResource.class);
		if (resource != null) {
			return Workspaces.location(resource);
		}
		String text = String.valueOf(element).replace('\n', ' ').replace('\r', ' ');
		if (text.matches("[\\w.$]+@[0-9a-f]+")) { //$NON-NLS-1$
			// 既定の toString (クラス名@ハッシュ値) は意味がないので、種類だけを出す
			text = "(" + element.getClass().getSimpleName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return text.length() > MAX_ELEMENT_CHARS ? text.substring(0, MAX_ELEMENT_CHARS) + "..." : text; //$NON-NLS-1$
	}

	// ---- パースペクティブ ----

	private static String listPerspectives() {
		IWorkbenchPage page = Ui.window().getActivePage();
		IPerspectiveDescriptor current = page == null ? null : page.getPerspective();
		IPerspectiveDescriptor[] all = PlatformUI.getWorkbench().getPerspectiveRegistry().getPerspectives();
		Arrays.sort(all, Comparator.comparing(IPerspectiveDescriptor::getLabel, String.CASE_INSENSITIVE_ORDER));
		StringBuilder sb = new StringBuilder();
		for (IPerspectiveDescriptor descriptor : all) {
			boolean isCurrent = current != null && current.getId().equals(descriptor.getId());
			sb.append(isCurrent ? "* " : "  ").append(descriptor.getLabel()).append(" (").append(descriptor.getId()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append(")\n"); //$NON-NLS-1$
		}
		return sb.toString();
	}

	private static String openPerspective(String id) throws Exception {
		IWorkbench workbench = PlatformUI.getWorkbench();
		IPerspectiveDescriptor descriptor = workbench.getPerspectiveRegistry().findPerspectiveWithId(id);
		if (descriptor == null) {
			throw new IllegalArgumentException(
					"Unknown perspective id: " + id + ". Call list_perspectives for the available ids."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		workbench.showPerspective(id, Ui.window());
		return "Switched to the " + descriptor.getLabel() + " perspective."; //$NON-NLS-1$ //$NON-NLS-2$
	}

	// ---- open_file ----

	/** @return ワークスペースのファイルなら、その IFile (ディスクにあるがワークスペースがまだ知らないものは読み込んでから)。外のファイルなら null */
	private static IFile workspaceFile(String path) throws CoreException {
		IPath p = new Path(path);
		if (p.getDevice() == null && !p.isUNC()) {
			IResource member = Workspaces.root().findMember(p);
			if (member instanceof IFile) {
				return (IFile) member;
			}
		}
		for (IFile file : Workspaces.filesAt(path)) {
			if (!file.exists() && file.getProject().isOpen() && file.getParent().exists()) {
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}
			if (file.exists()) {
				return file;
			}
		}
		return null;
	}

	private static String openFile(IFile file, String path, int line) throws Exception {
		IWorkbenchPage page = Ui.page();
		IEditorPart editor;
		if (file != null) {
			editor = IDE.openEditor(page, file, false);
		} else {
			File local = new File(path);
			if (!local.isFile()) {
				throw new IllegalArgumentException("File not found: " + path); //$NON-NLS-1$
			}
			editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().fromLocalFile(local));
		}
		String result = "Opened " + editor.getTitle() + " in an editor"; //$NON-NLS-1$ //$NON-NLS-2$
		if (line > 0) {
			result += selectLine(editor, line);
		}
		return result + "."; //$NON-NLS-1$
	}

	private static String selectLine(IEditorPart editor, int line) {
		ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
		IDocument document = textEditor == null || textEditor.getDocumentProvider() == null ? null
				: textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
		if (document == null) {
			return ", but it is not a text editor, so line " + line + " could not be selected"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		int index = Math.max(0, Math.min(line, document.getNumberOfLines()) - 1);
		try {
			IRegion region = document.getLineInformation(index);
			textEditor.selectAndReveal(region.getOffset(), region.getLength());
			return " and selected line " + (index + 1); //$NON-NLS-1$
		} catch (BadLocationException e) {
			return ", but line " + line + " could not be selected"; //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
