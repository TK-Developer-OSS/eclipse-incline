package com.tkdevelopeross.eclipse.incline.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Edit / Write ツールの入力から、変更前と変更後の内容を作る (許可ダイアログの差分表示用)。
 * claude はファイルを UTF-8 として読み、改行を LF にそろえてから置換するので、ここでも同じようにする。
 */
final class EditPreview {

	/** これより大きいファイルは全体を比較せず、変更する部分だけを見せる。 */
	private static final long MAX_FILE_BYTES = 1024 * 1024;

	private final String filePath;
	private final String before;
	private final String after;
	private final boolean newFile;
	private final boolean partial;

	private EditPreview(String filePath, String before, String after, boolean newFile, boolean partial) {
		this.filePath = filePath;
		this.before = before;
		this.after = after;
		this.newFile = newFile;
		this.partial = partial;
	}

	String getFilePath() {
		return filePath;
	}

	String getBefore() {
		return before;
	}

	String getAfter() {
		return after;
	}

	/** ファイルがまだ無い (変更前は空)。 */
	boolean isNewFile() {
		return newFile;
	}

	/** ファイル全体ではなく、old_string と new_string だけを比較している。 */
	boolean isPartial() {
		return partial;
	}

	/** @return Edit / Write 以外のツール、または差分を作れない入力のときは null */
	static EditPreview create(String toolName, JsonObject input) {
		String filePath = string(input, "file_path"); //$NON-NLS-1$
		if (filePath == null) {
			return null;
		}
		Path path = toAbsolutePath(filePath);
		boolean exists = path != null && Files.exists(path);
		String current = exists ? read(path) : null;

		if ("Write".equals(toolName)) { //$NON-NLS-1$
			String content = string(input, "content"); //$NON-NLS-1$
			if (content == null || path == null || (exists && current == null)) {
				return null;
			}
			return new EditPreview(filePath, exists ? current : "", normalize(content), !exists, false); //$NON-NLS-1$
		}
		if ("Edit".equals(toolName)) { //$NON-NLS-1$
			String oldString = string(input, "old_string"); //$NON-NLS-1$
			String newString = string(input, "new_string"); //$NON-NLS-1$
			if (oldString == null || newString == null) {
				return null;
			}
			oldString = normalize(oldString);
			newString = normalize(newString);
			if (path != null && !exists && oldString.isEmpty()) {
				// old_string が空の Edit は新規作成
				return new EditPreview(filePath, "", newString, true, false); //$NON-NLS-1$
			}
			String after = current == null ? null : apply(current, oldString, newString, bool(input, "replace_all")); //$NON-NLS-1$
			if (after == null) {
				return new EditPreview(filePath, oldString, newString, false, true);
			}
			return new EditPreview(filePath, current, after, false, false);
		}
		return null;
	}

	/** @return old_string が見つからないときは null */
	private static String apply(String current, String oldString, String newString, boolean replaceAll) {
		if (oldString.isEmpty()) {
			return current.isEmpty() ? newString : null;
		}
		int index = current.indexOf(oldString);
		if (index < 0) {
			return null;
		}
		if (replaceAll) {
			return current.replace(oldString, newString);
		}
		return current.substring(0, index) + newString + current.substring(index + oldString.length());
	}

	private static Path toAbsolutePath(String filePath) {
		try {
			Path path = Paths.get(filePath);
			return path.isAbsolute() ? path : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** @return 読めない、または大きすぎるときは null */
	private static String read(Path path) {
		try {
			if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
				return null;
			}
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) { //$NON-NLS-1$
				text = text.substring(1);
			}
			return normalize(text);
		} catch (IOException e) {
			return null;
		}
	}

	private static String normalize(String text) {
		return text.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String string(JsonObject obj, String key) {
		if (obj == null) {
			return null;
		}
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}
}
