package com.tkdevelopeross.eclipse.incline.tools;

import java.net.URI;

import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.tkdevelopeross.eclipse.incline.Activator;

/**
 * ワークスペース (プロジェクト・リソース・ビルド) に関する共通処理。
 */
final class Workspaces {

	private Workspaces() {
	}

	static IWorkspaceRoot root() {
		return ResourcesPlugin.getWorkspace().getRoot();
	}

	/**
	 * プロジェクト名、ワークスペースのパス (/project/folder)、ファイルシステムの絶対パスのどれかからリソースを探す。
	 *
	 * @param path null ならワークスペース全体
	 */
	static IResource resolve(String path) {
		IWorkspaceRoot root = root();
		if (path == null) {
			return root;
		}
		IPath p = new Path(path);
		// ドライブ名や UNC が付いていれば、ワークスペースのパスではない
		if (p.getDevice() == null && !p.isUNC()) {
			IResource member = root.findMember(p);
			if (member != null) {
				return member;
			}
		}
		if (p.isAbsolute()) {
			URI location = URIUtil.toURI(p);
			for (IFile file : root.findFilesForLocationURI(location)) {
				if (file.exists()) {
					return file;
				}
			}
			for (IContainer container : root.findContainersForLocationURI(location)) {
				if (container.exists()) {
					return container;
				}
			}
		}
		throw new IllegalArgumentException("Not found in the Eclipse workspace: " + path //$NON-NLS-1$
				+ ". Pass a project name, a workspace path such as /project/folder, or an absolute file system path inside a project."); //$NON-NLS-1$
	}

	/** @return そのファイルシステム上の場所にあたる、ワークスペースのファイル (ワークスペースにまだ無いものも含む)。プロジェクトの外なら空 */
	static IFile[] filesAt(String osPath) {
		IPath p = new Path(osPath);
		if (!p.isAbsolute()) {
			return new IFile[0];
		}
		return root().findFilesForLocationURI(URIUtil.toURI(p));
	}

	/** @return ファイルシステム上のパス。無ければワークスペースのパス */
	static String location(IResource resource) {
		IPath location = resource.getLocation();
		return location != null ? location.toOSString() : resource.getFullPath().toString();
	}

	/**
	 * claude がディスク上で書き換えたファイルをワークスペースに反映する
	 * (エディタ・Project Explorer・問題ビューが古いままにならないように)。終わるのを待たない。
	 */
	static void refreshChangedFile(String osPath) {
		final IFile[] files = filesAt(osPath);
		if (files.length == 0) {
			return;
		}
		Job job = new Job("Claude が変更したファイルを反映") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				for (IFile file : files) {
					if (!file.getProject().isOpen()) {
						continue;
					}
					// 新しいフォルダの中に作られたファイルは、ワークスペースが知っている一番近い親から読み直す
					IResource target = file;
					int depth = IResource.DEPTH_ZERO;
					IContainer parent = file.getParent();
					if (!parent.exists()) {
						while (!parent.exists()) {
							parent = parent.getParent();
						}
						target = parent;
						depth = IResource.DEPTH_INFINITE;
					}
					try {
						target.refreshLocal(depth, monitor);
					} catch (CoreException e) {
						Activator.logError("リフレッシュに失敗しました: " + file.getFullPath(), e);
					}
				}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	/**
	 * ビルドが動いていれば終わるまで待つ (問題の一覧が古いままにならないように)。
	 *
	 * @return 時間内に終わったら true
	 */
	static boolean waitForBuild(int seconds) {
		final long deadline = System.currentTimeMillis() + seconds * 1000L;
		IProgressMonitor monitor = new NullProgressMonitor() {
			@Override
			public boolean isCanceled() {
				return System.currentTimeMillis() > deadline;
			}
		};
		try {
			Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
			Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
			return true;
		} catch (OperationCanceledException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** @return { エラーの数, 警告の数, 情報の数 } */
	static int[] countProblems(IMarker[] markers) {
		int[] counts = new int[3];
		for (IMarker marker : markers) {
			int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
			if (severity == IMarker.SEVERITY_ERROR) {
				counts[0]++;
			} else if (severity == IMarker.SEVERITY_WARNING) {
				counts[1]++;
			} else {
				counts[2]++;
			}
		}
		return counts;
	}

	static String describeCounts(int[] counts) {
		return counts[0] + (counts[0] == 1 ? " error, " : " errors, ") + counts[1] //$NON-NLS-1$ //$NON-NLS-2$
				+ (counts[1] == 1 ? " warning, " : " warnings, ") + counts[2] + (counts[2] == 1 ? " info" : " infos"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
