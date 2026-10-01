#!/bin/bash
# 実際の Eclipse の中でプラグインを動かす自己テスト。
#
#   bash selftest/run.sh selftest/plans/tools.json
#   bash selftest/run.sh selftest/plans/chat.json ANTHROPIC_MODEL=haiku     (本物の claude を使う。利用枠を消費する)
#   bash selftest/show.sh tools                                              (結果を読みやすく表示する)
#
# やること:
#   1. src をコンパイルして、作業フォルダにバンドルを作る
#   2. インストール済みの Eclipse のバンドル一覧にそのバンドルを足した構成を、作業フォルダに作る
#   3. 作業フォルダのワークスペースで Eclipse を起動する。SelfTest (src/.../selftest/SelfTest.java) が手順を実行して Eclipse を閉じる
# インストール済みの Eclipse と、ふだんのワークスペースには書き込まない。
# 結果は $IT/out/<手順の名前>.jsonl、Eclipse のログは $IT/out/console.log。
#
# 注意: Java のデバッグを含む手順は、Windows ファイアウォールの確認を出すことがある (デバッガが接続待ちのポートを開くため)。

# ---- この PC の場所 ----
ECLIPSE='D:/ide/eclipse/eclipse'
POOL='C:/Users/****/.p2/pool/plugins'
JRE='/c/Users/****/.p2/pool/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.8.v20230801-1951/jre/bin'
IT="${INCLINE_IT_DIR:-D:/Temp/incline-selftest}"

HERE="$(cd "$(dirname "$0")" && pwd)"
P="$(dirname "$HERE")"
PLAN_SOURCE="$1"; shift
for kv in "$@"; do export "$kv"; done
NAME="$(basename "$PLAN_SOURCE" .json)"

# 1. バンドル (クラスを直下に置いたフォルダ)
rm -rf "$IT/bundle" "$IT/ws" "$IT/config"
mkdir -p "$IT/bundle/META-INF" "$IT/config/org.eclipse.equinox.simpleconfigurator" "$IT/out" "$IT/ws"
SRCS=$(find "$P/src" -name '*.java' | while read f; do cygpath -w "$f"; done)
"$JRE/javac.exe" -J-Duser.language=en --release 11 -encoding UTF-8 -nowarn -d "$(cygpath -w "$IT/bundle")" -cp "$POOL/*" $SRCS 2>&1 | head -30
[ "${PIPESTATUS[0]}" = "0" ] || { echo "COMPILE FAILED"; exit 1; }
cp "$P/META-INF/MANIFEST.MF" "$IT/bundle/META-INF/" && cp "$P/plugin.xml" "$IT/bundle/"

# 2. 構成。フレームワークの場所などはインストール済みの Eclipse の config.ini から写し、p2 の領域は作業フォルダに分ける
grep -E '^(eclipse[.]product|eclipse[.]application|eclipse[.]buildId|osgi[.]bundles|osgi[.]bundles[.]defaultStartLevel|osgi[.]framework|osgi[.]framework[.]extensions|osgi[.]splashPath)=' \
  "$ECLIPSE/configuration/config.ini" > "$IT/config/config.ini"
{
  echo "osgi.install.area=file:/$ECLIPSE/"
  echo "osgi.configuration.cascaded=false"
  echo "org.eclipse.update.reconcile=false"
  echo "eclipse.p2.data.area=@config.dir/.p2"
  echo "org.eclipse.equinox.simpleconfigurator.configUrl=file:/$IT/config/org.eclipse.equinox.simpleconfigurator/bundles.info"
} >> "$IT/config/config.ini"
grep -v '^com.tkdevelopeross.eclipse.incline,' "$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
  > "$IT/config/org.eclipse.equinox.simpleconfigurator/bundles.info"
echo "com.tkdevelopeross.eclipse.incline,1.0.0.qualifier,file:/$IT/bundle/,4,false" >> "$IT/config/org.eclipse.equinox.simpleconfigurator/bundles.info"
echo 'org.eclipse.ui/showIntro=false' > "$IT/custom.ini"

# 3. テスト用のプロジェクトと手順 (手順の中の ${IT} を作業フォルダに置き換える)
cp -r "$HERE/demo" "$IT/ws/demo" && cp -r "$HERE/cdemo" "$IT/ws/cdemo"
sed "s|[$]{IT}|$IT|g" "$PLAN_SOURCE" > "$IT/plan.json"

START=$(date +%s)
"$JRE/java.exe" -Dosgi.requiredJavaVersion=17 -Xms256m -Xmx1536m --add-modules=ALL-SYSTEM -Doomph.setup.skip=true \
  "-Dincline.selftest=$(cygpath -w "$IT/plan.json")" \
  -jar "$ECLIPSE"/plugins/org.eclipse.equinox.launcher_*.jar \
  -data "$(cygpath -w "$IT/ws")" -configuration "file:/$IT/config/" \
  -os win32 -ws win32 -arch x86_64 -nosplash -consoleLog -pluginCustomization "$(cygpath -w "$IT/custom.ini")" \
  > "$IT/out/console.log" 2>&1
echo "eclipse exit=$? after $(( $(date +%s) - START ))s -> $IT/out/$NAME.jsonl"
