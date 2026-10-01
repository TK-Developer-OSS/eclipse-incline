package com.tkdevelopeross.eclipse.incline.backend;

import java.io.IOException;

/**
 * チャットの接続先。今は claude CLI だけだが、ローカル LLM などに差し替えられるようにしておく。
 * リスナーはバックエンド側のスレッドから呼ばれる。
 */
public interface ChatBackend {

	void setListener(ChatListener listener);

	void start() throws IOException;

	boolean isRunning();

	void sendUserMessage(String text);

	/** 実行中のターンを中断する。 */
	void interrupt();

	void stop();
}
