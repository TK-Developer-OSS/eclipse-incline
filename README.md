# eclipse-incline

Claude Code を Eclipse の中で使うためのプラグイン。

- Claude Chat ビューで Claude Code と対話する (VS Code 版と同じ使い勝手。編集は diff を見て許可する)
- Eclipse の中で MCP サーバが動き、Claude がビュー・ダイアログ・メニュー・コマンド・起動/デバッグ・ターミナルを見て操作できる

対象: Eclipse 4.17 以降 (Java 11 以降)。claude CLI (Claude Code) が別途必要。

## インストール

Help → Install New Software… → Add… で次の場所を追加し、「Incline (Claude for Eclipse)」を選ぶ。

```
https://raw.githubusercontent.com/TK-Developer-OSS/eclipse-incline/dev/updatesite
```

インストール後、Window → Show View → Other… → Incline → Claude Chat。

## 構成

| フォルダ | 内容 |
|---|---|
| com.tk-developer-oss.eclipse-incline | プラグイン本体 (設計メモは DEVELOP.md) |
| com.tkdevelopeross.eclipse.incline.feature | フィーチャー |
| com.tkdevelopeross.eclipse.incline.site | アップデートサイトの定義とビルドスクリプト (build.sh) |
| updatesite | ビルド済みのアップデートサイト (p2 リポジトリ) |
