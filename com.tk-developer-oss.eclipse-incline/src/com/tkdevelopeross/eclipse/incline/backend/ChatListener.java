package com.tkdevelopeross.eclipse.incline.backend;

import com.google.gson.JsonObject;

/**
 * バックエンドからのイベント。UI スレッドではなくバックエンドのスレッドから呼ばれる。
 */
public interface ChatListener {

	void onSessionStarted(String sessionId, String model, String cwd);

	/** 応答の文章のうち、新しく届いた分。全文はこのあと onAssistantText で届く。少しずつ届けない接続先では呼ばれない。 */
	default void onAssistantTextDelta(String delta) {
	}

	void onAssistantText(String text);

	void onToolUse(String toolUseId, String toolName, JsonObject input);

	/** change はツールがファイルを書き換えたときだけ入る。それ以外は null。 */
	void onToolResult(String toolUseId, String content, boolean isError, FileChange change);

	/** 1 ターンの終わり。subtype は "success" / "error_during_execution" など。 */
	void onTurnFinished(String subtype, boolean isError, String errorText);

	/** 必ず allow() か deny() で答えること。答えるまで CLI は待ち続ける。 */
	void onPermissionRequest(PermissionRequest request);

	void onError(String message);

	/** プロセスが終了した。expected は stop() による終了かどうか。 */
	void onTerminated(int exitCode, String stderrTail, boolean expected);
}
