package com.tkdevelopeross.eclipse.incline.claude;

import java.io.File;

import org.eclipse.core.runtime.Platform;

/**
 * claude CLI の場所を探す。設定画面ができるまではこの順で決める:
 * <ol>
 * <li>システムプロパティ incline.claude.path (eclipse.ini の -D で指定)</li>
 * <li>環境変数 INCLINE_CLAUDE_PATH</li>
 * <li>VS Code の Claude Code 拡張に同梱されているもの (一番新しい版)</li>
 * <li>~/.local/bin/claude</li>
 * <li>PATH 上の claude</li>
 * </ol>
 */
public final class ClaudeCliLocator {

	private static final String EXTENSION_PREFIX = "anthropic.claude-code-"; //$NON-NLS-1$

	private ClaudeCliLocator() {
	}

	public static String locate() {
		String explicit = System.getProperty("incline.claude.path"); //$NON-NLS-1$
		if (isExecutable(explicit)) {
			return explicit;
		}
		explicit = System.getenv("INCLINE_CLAUDE_PATH"); //$NON-NLS-1$
		if (isExecutable(explicit)) {
			return explicit;
		}
		File home = new File(System.getProperty("user.home")); //$NON-NLS-1$
		File fromVsCode = findInVsCodeExtensions(new File(home, ".vscode/extensions")); //$NON-NLS-1$
		if (fromVsCode != null) {
			return fromVsCode.getAbsolutePath();
		}
		File local = new File(home, ".local/bin/" + exeName()); //$NON-NLS-1$
		if (local.isFile()) {
			return local.getAbsolutePath();
		}
		return "claude"; //$NON-NLS-1$
	}

	private static boolean isExecutable(String path) {
		return path != null && !path.isBlank() && new File(path).isFile();
	}

	private static String exeName() {
		return Platform.OS_WIN32.equals(Platform.getOS()) ? "claude.exe" : "claude"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static File findInVsCodeExtensions(File extensionsDir) {
		File[] dirs = extensionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith(EXTENSION_PREFIX));
		if (dirs == null) {
			return null;
		}
		File best = null;
		int[] bestVersion = null;
		for (File dir : dirs) {
			File exe = new File(dir, "resources/native-binary/" + exeName()); //$NON-NLS-1$
			if (!exe.isFile()) {
				continue;
			}
			int[] version = parseVersion(dir.getName().substring(EXTENSION_PREFIX.length()));
			if (best == null || compare(version, bestVersion) > 0) {
				best = exe;
				bestVersion = version;
			}
		}
		return best;
	}

	/** "2.1.285-win32-x64" → {2, 1, 285} */
	private static int[] parseVersion(String s) {
		int dash = s.indexOf('-');
		String[] parts = (dash >= 0 ? s.substring(0, dash) : s).split("\\."); //$NON-NLS-1$
		int[] version = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				version[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				version[i] = 0;
			}
		}
		return version;
	}

	private static int compare(int[] a, int[] b) {
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}
}
