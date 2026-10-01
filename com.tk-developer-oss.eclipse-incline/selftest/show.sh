#!/bin/bash
# 自己テストの結果 (1 行 1 手順の JSON) を読みやすく表示する。
#   bash selftest/show.sh <手順の名前> [手順ごとの最大文字数]
POOL='C:/Users/****/.p2/pool/plugins'
JRE='/c/Users/****/.p2/pool/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.8.v20230801-1951/jre/bin'
IT="${INCLINE_IT_DIR:-D:/Temp/incline-selftest}"
HERE="$(cd "$(dirname "$0")" && pwd)"
GSON=$(ls "$POOL"/com.google.gson_*.jar | head -1)
"$JRE/javac.exe" -encoding UTF-8 -d "$(cygpath -w "$IT/out")" -cp "$GSON" "$(cygpath -w "$HERE/Show.java")" \
  && "$JRE/java.exe" -Dfile.encoding=UTF-8 -cp "$(cygpath -w "$IT/out");$GSON" Show "$(cygpath -w "$IT/out/$1.jsonl")" "${2:-1500}"
