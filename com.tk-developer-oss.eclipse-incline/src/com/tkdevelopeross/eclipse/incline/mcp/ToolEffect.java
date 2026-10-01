package com.tkdevelopeross.eclipse.incline.mcp;

/**
 * ツールが Eclipse に与える影響。実行前にユーザーの許可を求めるかどうかは、これで決める。
 */
public enum ToolEffect {

	/** 状態を読むだけ。 */
	READ,

	/** 表示を切り替えるだけ (パースペクティブ・ビュー・エディタを開く、ワークスペースを読み直す)。 */
	NAVIGATE,

	/** ワークスペースや設定を変えうる。実行前にユーザーの許可を求める。 */
	CHANGE
}
