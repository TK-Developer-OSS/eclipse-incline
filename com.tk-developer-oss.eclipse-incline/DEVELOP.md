eclipse 置き場所
D:\ide\eclipse\eclipse

## 目的
Eclipse でのあらゆる作業で Claude の支援を受けられるプラグインを自作する。
- 差分の表示と命令の実行だけのプラグインにはしない。Claude が Eclipse 自身 (パースペクティブ、各パースペクティブのビュー、エディタ、問題、デバッガ、コマンド) を見て操作できるようにする
- それにはローカルファイルの操作では足りない。中核は Eclipse 側で動かす MCP サーバで、Eclipse の状態と操作をツールとして Claude に公開する
- チャットビューの使い勝手は Claude Code の VS Code 拡張を手本にする

GitHub Copilot for Eclipse は不採用 (2024-09 以降必須・GitHub 認証が必要・自由度が低い)。

## 方針 (決定済み)
- LLM は Claude サブスク。認証は Claude Code CLI (claude.exe) に任せ、プラグインは CLI を子プロセスで起動する
  (`--input-format stream-json --output-format stream-json`)。OAuth トークンを直接使うことはしない
- 使える CLI: C:\Users\****\.vscode\extensions\anthropic.claude-code-2.1.285-win32-x64\resources\native-binary\claude.exe (PATH には入っていない)
- 機能は「両方」:
  1. チャットビュー (会話 / ツール許可ダイアログ / 差分プレビュー)
  2. Eclipse 自身を MCP サーバとして公開し、Claude が Eclipse を見て操作できるようにする (中核)
     - Eclipse を操作するツールは、claude の制御チャネル経由で公開する (「MCP サーバ」の節を参照)
     - 当初決めた lock ファイル方式 (WebSocket, ~/.claude/ide/<port>.lock で CLI が自動接続) では、自作のツールが Claude に渡らないことが分かった。
       この方式は CLI 自身の IDE 連携 (選択範囲の通知、診断、ターミナルで動かす claude からの差分表示) のために、あとで同じサーバに足す
  - 外部 MCP サーバの利用は claude 側 (`claude mcp add`) に任せる
- ローカル LLM は後回し。チャットの接続先を差し替えられる構成にしておく

## 互換性
- 対象: Eclipse 2020-09 (4.17) 以降。開発は 2023-06 (4.28, PHP パッケージ, JustJ 17) で行う
- Java 11 で書く (record / テキストブロック / switch 式などは使わない)。Bundle-RequiredExecutionEnvironment: JavaSE-11
- API は 4.17 にあるものだけを使う (PDE のターゲットプラットフォームを 2020-09 にして確認する)
- MCP Java SDK は Java 17 以上が必要なので使わない。JSON-RPC と WebSocket は自前で書く。JSON は Gson を使う
- UI は SWT で作る (2020-09 の Windows では Browser ウィジェットが IE になるため)

## 進め方
0. 2023-06 に PDE を入れる (済。JDT / Gson / LSP4E も入っている)
1. チャットビュー + claude の起動 + 許可ダイアログ (実装済み。2 つ目の Eclipse で会話と許可ダイアログが動くことを確認済み)
2. Eclipse の MCP サーバ (このプラグインの中核)
   - 2a. 土台: MCP サーバ本体、制御チャネルでの公開、許可の振り分け、最初のツール 11 個 (実装済み。自己テストで実機確認済み)
   - 2b. ビューとダイアログの操作: ツリーやテーブルの項目の選択・展開・ダブルクリック、ボタンとツールバー、文字の入力、メニュー (右クリック / メイン / ビューの ▽)、ダイアログの読み取りと操作 (実装済み。自己テストで実機確認済み)
   - 2c. 分野ごとのツール
     - 済 (自己テストで実機確認済み): 起動構成の作成と変更、実行して終了コードと出力を受け取る、デバッグ (ブレークポイント、スタックと変数、ステップ実行。Java で確認)、ターミナルの読み取りと入力、プロジェクトの取り込み
     - 未: C/C++ (GDB) と PHP のデバッグの実機確認、エディタを通した編集、言語ごとの機能 (JDT / PDT / CDT の検索やリファクタリング)、Git (EGit)
   - 注意: 元の Eclipse (PDE) でのビルドと、そこから起動した Eclipse での確認は 2026-09-30 15:49 以降していない。確認は自己テスト (javac でコンパイルしたバンドルを別の Eclipse に組み込む) で行った
   - 2d. 通信路の追加: lock ファイル方式 (CLI の IDE 連携)、HTTP (チャットビュー以外で動かす claude から使う)
3. 差分プレビュー (実装済み。自己テストのスクリーンショットで画面を確認済み) / 設定画面 (未着手)

## 構成
- backend\ : ChatBackend / ChatListener / PermissionRequest / FileChange。接続先を差し替えるためのインターフェース
- claude\ : ClaudeCliBackend (claude を子プロセスで起動して stream-json で会話し、MCP サーバも同じ経路で公開する) / ClaudeCliLocator (claude の場所を探す)
- mcp\ : McpServer (通信路に依存しない JSON-RPC の処理) / McpTool / ToolEffect
- tools\ : Eclipse を見て操作するツール。EclipseTools.createServer() が全部を登録する
- ui\ : ChatView (ビュー ID com.tkdevelopeross.eclipse.incline.chatView) / PermissionDialog / EditPreview
  - ビューの置き場所: plugin.xml の perspectiveExtensions (targetID="*") で、全パースペクティブの下段 (Problems のスタック。Search・Console と同じ段) にタブとして入れる。保存済みのレイアウトには効かないので、既存ワークスペースでは Window → Perspective → Reset Perspective が要る
  - 入力欄: Enter で送信、Alt+Enter で改行。送信ボタンは claude を起動する前に無効にする
- selftest\ : SelfTest。Eclipse の起動時に動く (plugin.xml の org.eclipse.ui.startup)。システムプロパティ incline.selftest がなければ何もしない
- selftest\ (プロジェクト直下のフォルダ) : 自己テストの起動スクリプト、手順、テスト用のプロジェクト。プラグインには入らない
- 起動オプション: `-p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio`
  - 許可要求は stdout に control_request (subtype: can_use_tool) で届き、stdin に control_response (behavior: allow / deny) で答える
  - 中断は control_request (subtype: interrupt) を送る
- claude の場所の決め方: -Dincline.claude.path → 環境変数 INCLINE_CLAUDE_PATH → VS Code 拡張の同梱版 (最新) → ~/.local/bin → PATH
- 作業ディレクトリ: アクティブなエディタのプロジェクト → 開いているプロジェクトが 1 つならそれ → ワークスペースのルート

## MCP サーバ
- 公開の方法 (チャットビューから起動した claude 向け): claude の制御チャネルを使う。ソケットもポートも要らない
  - 起動直後に control_request (subtype: initialize, sdkMcpServers: ["eclipse"]) を送る
  - claude が MCP の JSON-RPC を control_request (subtype: mcp_message, server_name, message) で送ってくるので、control_response の response に { mcp_response: 返事 } を入れて返す。通知には { jsonrpc: "2.0", result: {}, id: 0 } を返す
  - Claude から見たツール名は mcp__eclipse__<ツール名>。Claude は ToolSearch で必要なツールの定義を読み込んでから呼ぶ
  - サーバの使い方 (initialize の instructions) は Claude のシステムプロンプトに入る
- lock ファイル方式でツールを公開しない理由: CLI (2.1.285) は、IDE サーバのツールのうち mcp__ide__getDiagnostics と mcp__ide__executeCode だけを Claude に渡す。それ以外は Claude から見えない
- 許可: ツールごとの ToolEffect で決める。READ (読むだけ) と NAVIGATE (表示を切り替えるだけ) は確認なしで通し、CHANGE (ワークスペースや設定を変えうる) は許可ダイアログを出す
  - 引数で影響が変わるツールは、引数を見て決める (McpTool.getEffect(arguments))。例: select_item は選択や展開なら NAVIGATE、チェックなら CHANGE
- ツールは UI スレッドではないスレッドで動く。ワークベンチに触る処理は Ui.call() で UI スレッドに渡す (asyncExec とタイムアウト。モーダルダイアログが開いている間も動く)
- ツールの失敗は例外で知らせる。メッセージがそのまま Claude に伝わるので、次に何をすればよいかを書く
- 今あるツール:
  - get_workbench_state: ワークスペース、プロジェクト、パースペクティブ、エディタ (未保存・選択範囲)、開いているビューとその選択
  - list_perspectives / open_perspective
  - list_views / show_view / read_view。read_view は WidgetReader で SWT のウィジェット (ツリー、テーブル、テキスト、ボタン、ツールバーなど) を文章にするので、ビューごとの専用コードなしでどのビューも読める
  - open_file: 行を指定してエディタで開く
  - get_problems: 問題マーカー (問題ビューの元データ)。ビルドが終わるのを待ってから読む
  - refresh_workspace: ディスクから読み直して、自動ビルドを待つ
  - list_commands / execute_command: Eclipse のコマンド (メニュー・ツールバー・キー操作の実体) を探して実行する。execute_command は CHANGE
  - read_dialog: 開いているダイアログやウィザードを読む
  - select_item: ツリー・テーブル・リストの項目を選択・ダブルクリック・展開・折りたたみ・チェックする (チェックだけ CHANGE)
  - click: ボタン、チェックボックス、ラジオボタン、ツールバーのボタン (ビューのタイトル横のものを含む)、リンク、タブを押す (CHANGE)
  - set_text: 入力欄に文字を入れる、コンボの値を選ぶ (エディタが対象のときと、Enter を押すときは CHANGE)
  - context_menu / main_menu / view_menu: 右クリックメニュー、メインメニュー、ビューの ▽ メニューの一覧を読む、項目を実行する (実行は CHANGE)
  - import_project: ディスク上のフォルダをワークスペースのプロジェクトにする (CHANGE)
  - list_launch_configurations / get_launch_configuration / set_launch_configuration: 起動構成を調べる、作る、属性を変える。属性は型ごとに違うので、値を JSON のオブジェクトでそのまま渡す (set は CHANGE)
  - launch: 構成を実行 / デバッグする。終了 (デバッグならブレークポイントでの停止) を待って、終了コードとコンソールの出力を返す (CHANGE)
  - get_launches / get_console_output / terminate_launch: 起動したものの状態、出力、停止 (terminate は CHANGE)
  - set_breakpoint / remove_breakpoint / list_breakpoints: 行ブレークポイント。作るのは、その言語のエディタに「この行で切り替えて」と頼む方法 (IToggleBreakpointsTarget) なので、言語を問わない
  - get_debug_state / get_variable / debug_step: 止まっているスレッドのスタックと変数、ステップ実行と再開。Eclipse の標準のデバッグモデルを使い、表示は Debug ビューと同じラベルにする
    - 標準のモデルを使わないデバッガ (C/C++ の GDB 連携) では、debug_step は Eclipse のコマンドを送り、状態は Debug / Variables ビューを read_view で読む (未確認)
  - send_to_terminal: Terminal ビュー (ローカルのシェル、SSH、シリアル) に文字を送り、新しく出た分を返す (CHANGE)。読むのは read_view
    - ターミナルは独自に描画する部品なので、TM Terminal のメソッド (getAllText / pasteString / sendKey) を名前で呼ぶ。ターミナルのプラグインへの依存は持たない
- どのツールの結果にも、ダイアログが開いていればそのことを添える (EclipseTool.call)。許可ダイアログなど、このプラグイン自身のダイアログは除く
- claude が Edit / Write で書き換えたファイルは、チャットビューが自動でワークスペースに反映する (EclipseTools.refreshChangedFile)
- まだできないこと: ドラッグ & ドロップ、キー入力そのもの、OS のダイアログ (ファイル選択など)、独自に描画している部分 (図など) の読み取り、ターミナルの折り返された行をつなげること
- 実機で分かったこと:
  - Java のデバッグで止まると「Confirm Perspective Switch」のダイアログが出る。Claude は read_dialog と click で答えられる
  - 終了した起動は、次の起動のときに Eclipse が一覧から消す (既定の設定)。前の実行の出力は get_console_output では取れなくなる
  - Windows のコンソールプログラムの出力は、起動構成に org.eclipse.debug.ui.ATTR_CONSOLE_ENCODING = MS932 を入れないと文字化けする
  - エディタの未保存の内容は、read_view に viewId 'editor' を渡すと読める
  - C (MSYS2 の UCRT64、D:\msys64。2026-10-01 に確認):
    - make でのビルドは、外部プログラムの起動構成 (ATTR_LOCATION = D:\msys64\usr\bin\make.exe、環境変数 PATH に D:\msys64\ucrt64\bin;D:\msys64\usr\bin を足す) でできる。gcc のエラーは出力にそのまま出て、終了コードは 2
    - ターミナルでも同じことができる (cd、set PATH、make)。send_to_terminal は、プロンプトが戻るまで (最大 waitSeconds) 待つ
    - GDB でのデバッグ: C/C++ Application の構成 (PROGRAM_NAME、org.eclipse.cdt.dsf.gdb.DEBUG_NAME = gdb.exe、DEBUGGER_STOP_AT_MAIN) で起動して main で止まり、変数の読み取り、ステップ実行、再開、終了まで通った (手順 c-debug)
      - GDB 連携は標準のデバッグモデルを使わないので、get_debug_state は Debug ビューと Variables ビューを順に表に出して読む。debug_step は Eclipse のコマンドを送り、そのあとの状態を同じ方法で読んで返す
      - 「Confirm Perspective Switch」のダイアログが開いている間は、Eclipse がデバッグのコマンドを無効にする。先にダイアログに答える (「Remember my decision」を入れて「Switch」にすると、以後は出ない)
      - get_launches は、止まっているかどうかを判定できないので「debugging」とだけ答える
    - Avast (CyberCapture) が、ビルドしたばかりの exe を毎回検査に回して 20～30 秒止めていた。自己テストの作業フォルダ D:\Temp\incline-selftest を Avast の例外に入れてもらって解消 (起動が 28 秒 → 0.6 秒)。作業フォルダの場所は変えないこと
    - 自己テストは Git Bash から起動するので、環境変数 NoDefaultCurrentDirectoryInExePath=1 が付く。ターミナルで hello.exe が見つからないのはそのせいで、.\hello.exe と書けば動く

## ビューとダイアログの操作
- 対象 (target) は、ビューの ID、'editor' (手前のエディタ)、'dialog' (開いているダイアログ) のどれか (UiTarget)
- read_view / read_dialog は、ツリー・テーブル・リスト・コンボ・テキストに #1, #2, ... と番号を振る (WidgetReader)。操作のツールは、この番号 (widget) か、表示されているラベルで対象を指定する
  - 番号はウィジェットの並びで決まり、中身が変わってもずれない (空のテキストにも番号を振る)
- 操作は、ウィジェットの状態を変えてから SWT のイベント (Selection / DefaultSelection / Expand / Show など) をリスナーに直接送る (WidgetActions)。JFace のビューアやダイアログはこのイベントで動く。画面に見えていなくても、フォーカスがなくても動く
- ラベルは、完全一致 → 前方一致 → 部分一致の順に緩めて探す (Labels)。1 つに決まらなければ、候補を並べたエラーを返す
- 子を後から読み込むツリーは、項目が現れるまで最大 4 秒待ってやり直す
- 操作がモーダルダイアログを開くと、その操作はダイアログが閉じるまで戻らない。UiTarget.perform() は 12 秒で待つのをやめて、ダイアログが開いたことを Claude に伝える
  - Claude は read_dialog で読み、target 'dialog' で click / set_text / select_item して答える。ツールの処理は asyncExec で動くので、モーダルダイアログのイベントループの中でも実行される
  - 操作のあとは毎回、ダイアログが開いているかどうかを結果に添える
- コマンドやコンテキストメニューは、アクティブなビューとその選択に対して働く。click と context_menu は、操作の前に対象のビューをアクティブにする
- Claude に触らせないもの (UiTarget で除外する):
  - このプラグイン自身のダイアログ。Shell に Activator.OWN_DIALOG_KEY の目印を付けたものは「開いているダイアログ」に数えない。
    ユーザーが click を「確認せずに許可」にしたあとで、Claude が許可ダイアログの「許可」を自分で押せてしまうのを防ぐため。プラグインにダイアログを足すときは、必ずこの目印を付ける
  - チャットビュー自身。自分の入力欄に文字を入れて送信する、セッションを消す、といった操作をさせないため

## 差分の表示
- チャットビューの差分は、MCP サーバを通さずにプラグインが作る。許可要求 (can_use_tool) でツールの入力 (file_path / old_string / new_string / content) がプラグインに届くため
  - ターミナルで動かす claude からの差分表示 (openDiff) は、lock ファイル方式を足すときに対応する
- 変更前 (許可ダイアログ): Edit / Write のとき、EditPreview が現在のファイルに置換を当てて変更後の内容を作り、org.eclipse.compare の TextMergeViewer で左右に並べる
  - claude に合わせて、ファイルは UTF-8 として読み、改行は LF にそろえて比較する
  - old_string が見つからない / ファイルが 1MB を超えるときは、old_string と new_string だけを比較する
  - 比較ビューアを作れなかったときは、ツールの入力を文字のまま見せる (ダイアログが開けないと claude が待ち続けるため)
- 変更後 (会話欄): Edit / Write の結果に付いてくる tool_use_result.structuredPatch を FileChange にして、削除行を赤、追加行を緑で出す。新規作成は structuredPatch が空なので content を追加行にする

## 動かし方
- Eclipse でプロジェクトを F5 (リフレッシュ) → 右クリック → Run As → Eclipse Application
- 起動した Eclipse で Window → Show View → Other… → Incline → Claude Chat

## Eclipse を起動しない確認 (Claude が行う)
- コンパイル: JustJ の javac (C:\Users\****\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.8.v20230801-1951\jre\bin\javac.exe) に `--release 11 -Xlint:all` を付ける
  - クラスパスは、Require-Bundle に書いたバンドルと、それらが再エクスポートするバンドルの JAR だけにする (場所は C:\Users\****\.p2\pool\plugins、版は D:\ide\eclipse\eclipse\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info)
- ClaudeCliBackend: claude の代わりに、記録した stream-json の行を出力する .bat を実行ファイルとして渡すと、利用枠を使わずに出力の解釈を確かめられる
- MCP サーバ: McpServer.handle() に JSON-RPC を渡せば、claude なしで確かめられる。ツールの定義が claude に受け入れられるかは、EclipseTools.createServer() を ClaudeCliBackend に渡して実際の claude に呼ばせる (ワークベンチがないので、ツールの中身はエラーを返す)
- WidgetReader / WidgetActions: open() しない Shell にウィジェットを作れば、画面に出さずに確かめられる。JFace のビューア (TreeViewer / TableViewer / MenuManager) も OSGi なしで動くので、リスナーが本当に呼ばれるかまで確かめられる
- ビューとダイアログの見た目と動き、ワークベンチに触るツールの中身は、下の自己テストで確かめる

## 自己テスト (実際の Eclipse の中で確かめる)
- 使い方 (プロジェクトのフォルダで、Git Bash から):
  - `bash selftest/run.sh selftest/plans/tools.json` → `bash selftest/show.sh tools`
  - 手順: tools (ビュー・ダイアログ・コマンド)、run-debug (実行・デバッグ・ターミナル)、menus (メニュー)、chat (本物の claude と会話して編集と許可ダイアログを通す)、resume (ストリーミング表示と会話の再開)
  - C の手順 (MSYS2 が D:\msys64 にある前提): c-make (make でビルド、エラーの取得、実行、ターミナル)、c-terminal (ターミナルだけ)、c-debug (GDB でデバッグ)
  - chat と resume は利用枠を使うので、`ANTHROPIC_MODEL=haiku` を付ける。テストの文面に「合言葉」のような言い方を使うと、haiku が不審な指示と受け取って従わないことがあった
- 仕組み: run.sh が src を javac でコンパイルしてバンドルを作り、インストール済みの Eclipse のバンドル一覧にそれを足した構成で、別の Eclipse を起動する。
  起動時に SelfTest が手順 (JSON) を順に実行して結果を 1 行ずつ書き、Eclipse を閉じる。1 回 1 分ほど
  - ツールは claude から呼ばれるときと同じ経路 (McpServer.handle) で呼ぶ。許可ダイアログは通らないので、確認なしで通るかどうか (auto) を結果に記録する
  - 手順には、ツールの呼び出しのほかに、スクリーンショット、チャットビューでの送信、許可ダイアログのボタンを押す、などがある (SelfTest.java の先頭に一覧)
  - 作業フォルダは D:\Temp\incline-selftest (環境変数 INCLINE_IT_DIR で変えられる)。結果は out\<手順名>.jsonl、スクリーンショットは out\*.png、Eclipse のログは out\console.log
  - インストール済みの Eclipse と、ふだんのワークスペースには書き込まない。構成もワークスペースも作業フォルダに毎回作り直す
- 注意:
  - 画面に Eclipse のウィンドウが 1 分ほど開く。スクリーンショットは画面からの写しなので、ほかのウィンドウが重なっているとそれも写る
  - Java のデバッグを含む手順 (run-debug) は、デバッガが接続待ちのポートを開くので、Windows ファイアウォールの確認が出ることがある (2026-09-30 に 1 回出た)
  - 依存 (MANIFEST.MF) を変えたあとに古い構成が残っていると、NoClassDefFoundError になる。run.sh は構成を毎回消している

## メモ
- このフォルダ (\\rocky9\****\cline\eclipse\com.tkdevelopeross.eclipse.incline) がプラグインのプロジェクト。VS Code もここを開く (フォルダ名は com.tk-developer-oss.eclipse-incline から変更する。変更が済んだらこの括弧書きは消す)
  - 親の \\rocky9\****\cline\eclipse は Eclipse のワークスペース (.metadata あり)。プロジェクトは PDE のウィザードで作成済み
- プロジェクト名 / フォルダ名 / Bundle-SymbolicName / Java パッケージはすべて com.tkdevelopeross.eclipse.incline にそろえる (パッケージ名にハイフンが使えないため)。フォルダ名の変更は Eclipse の Refactor → Rename で行う
- 会話の履歴とセッション ID は claude が持つ。履歴は claude が C:\Users\****\.claude\projects\<作業ディレクトリ名>\<セッションID>.jsonl に書く。プラグインはメモリ上の状態 (起動中のプロセス、画面の表示、「確認せずに許可」の選択) しか持たない
  - 再開: チャットビューのツールバーの「前回の会話を再開」で、最後に使った会話の続きから始められる (`--resume <セッションID>`)。
    セッション ID と作業ディレクトリは Eclipse の標準の仕組み (dialog settings) でワークスペースの .metadata に覚える。claude は作業ディレクトリごとに履歴を持つので、再開は元の作業ディレクトリで起動する
  - 再開しても、これまでのやり取りは会話欄には表示されない (claude は過去のメッセージを出力し直さないため)
- 応答の文章は `--include-partial-messages` で少しずつ受け取り (stream_event の text_delta)、届いた分から会話欄に出す。最後に届く全文で置き換える
- 2023-06 の Gson は 2.14.0。4.17 にはもっと古い版しかないので、Require-Bundle ではなく Import-Package com.google.gson (バージョンは下限を低くした範囲) で参照する
