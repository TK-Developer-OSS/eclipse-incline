package com.tkdevelopeross.eclipse.incline.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com.tkdevelopeross.eclipse.incline.mcp.McpServer;
import com.tkdevelopeross.eclipse.incline.mcp.ToolEffect;

/**
 * ビルドが報告する問題を読むツールと、ディスク上の変更をワークスペースに取り込むツール。
 */
final class WorkspaceTools {

	private static final int DEFAULT_LIMIT = 100;
	private static final int BUILD_WAIT_SECONDS = 60;
	private static final String PATH_HELP = "a project name, a workspace path such as /project/src, or an absolute file system path. Default: the whole workspace"; //$NON-NLS-1$

	private WorkspaceTools() {
	}

	static void register(McpServer server) {
		server.register(new EclipseTool("get_problems", //$NON-NLS-1$
				"List the errors and warnings that Eclipse's builders and validators report (the data behind the Problems view), " //$NON-NLS-1$
						+ "grouped by file with line numbers. Waits for a running build to finish first. " //$NON-NLS-1$
						+ "Use it after changing code to check the result instead of guessing.", //$NON-NLS-1$
				ToolEffect.READ,
				new Schema().string("path", "Limit to a project, folder or file: " + PATH_HELP) //$NON-NLS-1$ //$NON-NLS-2$
						.string("severity", "Lowest severity to list: 'error', 'warning' or 'info' (default 'info', which lists everything)") //$NON-NLS-1$ //$NON-NLS-2$
						.integer("limit", "Maximum number of problems to list (default " + DEFAULT_LIMIT + ")"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				args -> {
					IResource scope = Workspaces.resolve(Args.optString(args, "path")); //$NON-NLS-1$
					int minSeverity = severity(Args.optString(args, "severity")); //$NON-NLS-1$
					int limit = Args.optInt(args, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
					boolean built = Workspaces.waitForBuild(BUILD_WAIT_SECONDS);
					return problems(scope, minSeverity, limit, built);
				}));

		server.register(new EclipseTool("refresh_workspace", //$NON-NLS-1$
				"Make Eclipse re-read files from disk (like pressing F5), wait for the automatic build, and report the problem counts. " //$NON-NLS-1$
						+ "Call it after files were created, changed or deleted outside Eclipse, for example by a Bash command. " //$NON-NLS-1$
						+ "Files changed with the Edit and Write tools are refreshed automatically.", //$NON-NLS-1$
				ToolEffect.NAVIGATE, new Schema().string("path", "What to refresh: " + PATH_HELP), //$NON-NLS-1$ //$NON-NLS-2$
				args -> {
					IResource target = Workspaces.resolve(Args.optString(args, "path")); //$NON-NLS-1$
					target.refreshLocal(IResource.DEPTH_INFINITE, null);
					boolean built = Workspaces.waitForBuild(BUILD_WAIT_SECONDS);
					int[] counts = Workspaces
							.countProblems(target.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE));
					return "Refreshed " + describe(target) + ". " + buildNote(built) + "Problems now: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ Workspaces.describeCounts(counts) + "."; //$NON-NLS-1$
				}));

		server.register(new EclipseTool("import_project", //$NON-NLS-1$
				"Add a directory on disk to the Eclipse workspace as a project, so that Eclipse shows, builds and can run it. " //$NON-NLS-1$
						+ "If the directory has a .project file (an existing Eclipse project), it is used; otherwise a plain project is created for the directory. " //$NON-NLS-1$
						+ "Use this after creating a project directory with other tools. The user is asked to approve.", //$NON-NLS-1$
				ToolEffect.CHANGE,
				new Schema().string("path", "Absolute file system path of the project directory") //$NON-NLS-1$ //$NON-NLS-2$
						.string("name", "Project name. Default: the name in .project, or the directory name") //$NON-NLS-1$ //$NON-NLS-2$
						.required("path"), //$NON-NLS-1$
				args -> importProject(Args.string(args, "path"), Args.optString(args, "name")))); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * ディスク上のフォルダを、ワークスペースのプロジェクトにする。
	 * .project があればそれを使い、なければ素のプロジェクトとして登録する。
	 */
	private static String importProject(String path, String name) throws CoreException {
		File directory = new File(path);
		if (!directory.isDirectory()) {
			throw new IllegalArgumentException("Not a directory: " + path); //$NON-NLS-1$
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IPath location = new Path(directory.getAbsolutePath());
		IProjectDescription description;
		if (new File(directory, ".project").isFile()) { //$NON-NLS-1$
			description = workspace.loadProjectDescription(location.append(".project")); //$NON-NLS-1$
			if (name != null) {
				description.setName(name);
			}
		} else {
			description = workspace.newProjectDescription(name != null ? name : directory.getName());
		}
		// ワークスペースのフォルダの直下にあるものは、フォルダ名がそのままプロジェクト名になり、場所は指定しない (Eclipse の決まり)
		if (workspace.getRoot().getLocation().equals(location.removeLastSegments(1))) {
			description.setName(directory.getName());
			description.setLocation(null);
		} else {
			description.setLocation(location);
		}
		IProject project = workspace.getRoot().getProject(description.getName());
		if (project.exists()) {
			if (!location.equals(project.getLocation())) {
				throw new IllegalStateException("The workspace already has a project named " + project.getName() //$NON-NLS-1$
						+ " at " + Workspaces.location(project) + ". Pass a different name."); //$NON-NLS-1$
			}
			if (!project.isOpen()) {
				project.open(null);
			}
			project.refreshLocal(IResource.DEPTH_INFINITE, null);
			return "The project " + project.getName() + " is already in the workspace; it was opened and refreshed."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		project.create(description, null);
		project.open(null);
		String[] natures = project.getDescription().getNatureIds();
		return "Added the project " + project.getName() + " (" + Workspaces.location(project) + ") to the workspace. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ (natures.length == 0 ? "It is a plain project with no natures (no builder for a language)." //$NON-NLS-1$
						: "Natures: " + String.join(", ", natures) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String problems(IResource scope, int minSeverity, int limit, boolean built) throws CoreException {
		IMarker[] markers = scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
		List<IMarker> shown = new ArrayList<>();
		for (IMarker marker : markers) {
			if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) >= minSeverity) {
				shown.add(marker);
			}
		}
		// ファイルごとにまとめ、その中では重いものから
		shown.sort(Comparator.comparing((IMarker m) -> m.getResource().getFullPath().toString())
				.thenComparing(m -> -m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO))
				.thenComparing(m -> m.getAttribute(IMarker.LINE_NUMBER, 0)));

		StringBuilder sb = new StringBuilder();
		sb.append(describe(scope)).append(": ").append(Workspaces.describeCounts(Workspaces.countProblems(markers))) //$NON-NLS-1$
				.append(". ").append(buildNote(built)).append('\n'); //$NON-NLS-1$
		IResource current = null;
		int count = 0;
		for (IMarker marker : shown) {
			if (count == limit) {
				sb.append("... ").append(shown.size() - limit) //$NON-NLS-1$
						.append(" more not shown (raise limit, or narrow path / severity)\n"); //$NON-NLS-1$
				break;
			}
			IResource resource = marker.getResource();
			if (!resource.equals(current)) {
				current = resource;
				sb.append(Workspaces.location(resource)).append('\n');
			}
			sb.append("  ").append(severityName(marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO))).append(' '); //$NON-NLS-1$
			int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
			if (line > 0) {
				sb.append("line ").append(line).append(": "); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append(marker.getAttribute(IMarker.MESSAGE, "").replace('\n', ' ')).append('\n'); //$NON-NLS-1$
			count++;
		}
		return sb.toString();
	}

	private static int severity(String name) {
		if (name == null || "info".equalsIgnoreCase(name)) { //$NON-NLS-1$
			return IMarker.SEVERITY_INFO;
		}
		if ("warning".equalsIgnoreCase(name)) { //$NON-NLS-1$
			return IMarker.SEVERITY_WARNING;
		}
		if ("error".equalsIgnoreCase(name)) { //$NON-NLS-1$
			return IMarker.SEVERITY_ERROR;
		}
		throw new IllegalArgumentException("severity must be 'error', 'warning' or 'info'"); //$NON-NLS-1$
	}

	private static String severityName(int severity) {
		if (severity == IMarker.SEVERITY_ERROR) {
			return "ERROR"; //$NON-NLS-1$
		}
		return severity == IMarker.SEVERITY_WARNING ? "WARNING" : "INFO"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String describe(IResource resource) {
		return resource instanceof IWorkspaceRoot ? "The workspace" : resource.getFullPath().toString(); //$NON-NLS-1$
	}

	/** 問題の一覧がどのくらい新しいかを Claude に伝える。 */
	private static String buildNote(boolean buildFinished) {
		if (!ResourcesPlugin.getWorkspace().isAutoBuilding()) {
			return "Build Automatically is off, so these are the problems of the last build. "; //$NON-NLS-1$
		}
		return buildFinished ? "The automatic build has finished. " //$NON-NLS-1$
				: "A build is still running, so the problems may be incomplete. "; //$NON-NLS-1$
	}
}
