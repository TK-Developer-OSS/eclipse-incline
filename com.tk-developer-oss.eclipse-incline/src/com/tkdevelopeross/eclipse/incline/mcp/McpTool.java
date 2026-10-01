package com.tkdevelopeross.eclipse.incline.mcp;

import com.google.gson.JsonObject;

/**
 * MCP サーバが Claude に公開するツール。call は UI スレッドではないスレッドから呼ばれる。
 */
public interface McpTool {

	String getName();

	/** Claude が読む説明。いつ使うツールなのかが分かるように書く。 */
	String getDescription();

	/** 引数の JSON Schema (type: object)。 */
	JsonObject getInputSchema();

	/**
	 * @param arguments 呼び出しの引数。引数によって影響が変わるツールはこれを見て答える。
	 *                  ツールの一覧を作るときのように引数がまだないときは null (影響が一番大きい場合を答える)
	 */
	ToolEffect getEffect(JsonObject arguments);

	/**
	 * @return Claude に返す文章
	 * @throws Exception 失敗したとき。メッセージがそのまま Claude に伝わる
	 */
	String call(JsonObject arguments) throws Exception;
}
