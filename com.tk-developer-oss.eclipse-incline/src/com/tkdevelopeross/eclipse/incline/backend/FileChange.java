package com.tkdevelopeross.eclipse.incline.backend;

import java.util.Collections;
import java.util.List;

/**
 * ツールが行ったファイルの変更。
 * patchLines は unified diff と同じ形の行: "@@ -1,5 +1,5 @@" / " 文脈" / "-削除" / "+追加"。
 * 新規作成のときは "+" の行だけが入る。
 */
public final class FileChange {

	private final String filePath;
	private final boolean created;
	private final List<String> patchLines;

	public FileChange(String filePath, boolean created, List<String> patchLines) {
		this.filePath = filePath;
		this.created = created;
		this.patchLines = Collections.unmodifiableList(patchLines);
	}

	public String getFilePath() {
		return filePath;
	}

	public boolean isCreated() {
		return created;
	}

	public List<String> getPatchLines() {
		return patchLines;
	}
}
