#!/bin/bash
# アップデートサイト (p2 リポジトリ) を作る。出来上がりはリポジトリ直下の updatesite/。
#
#   bash com.tkdevelopeross.eclipse.incline.site/build.sh
#
# やること:
#   1. プラグインをコンパイルして jar にする (バージョンの qualifier は UTC の日時)
#   2. フィーチャーを jar にする
#   3. インストール済みの Eclipse の p2 パブリッシャで updatesite/ を作り直し、category.xml のカテゴリを付ける
# gson は Eclipse 4.17 の素の Platform に入っていないことがあるので、Orbit の gson バンドルもサイトに入れる (フィーチャーには含めない)。
# インストール済みの Eclipse には書き込まない (構成は作業フォルダに作る)。

# ---- この PC の場所 ----
ECLIPSE='D:/ide/eclipse/eclipse'
POOL='C:/Users/****/.p2/pool/plugins'
JRE='/c/Users/****/.p2/pool/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.8.v20230801-1951/jre/bin'
GSON="$POOL/com.google.gson_2.10.1.v20230109-0753.jar"
WORK="${INCLINE_BUILD_DIR:-D:/Temp/incline-selftest/build}"

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
PLUGIN="$ROOT/com.tk-developer-oss.eclipse-incline"
FEATURE="$ROOT/com.tkdevelopeross.eclipse.incline.feature"
SITE="$ROOT/updatesite"
QUALIFIER="$(date -u +v%Y%m%d-%H%M)"
BASE="$(sed -n 's/^Bundle-Version: *\([0-9.]*\)\.qualifier.*/\1/p' "$PLUGIN/META-INF/MANIFEST.MF" | tr -d '\r')"
VERSION="$BASE.$QUALIFIER"
FVERSION="$(sed -n 's/^ *version="\([0-9.]*\)\.qualifier".*/\1/p' "$FEATURE/feature.xml" | head -1).$QUALIFIER"
echo "plugin $VERSION / feature $FVERSION"

rm -rf "$WORK"
mkdir -p "$WORK/classes/META-INF" "$WORK/src/plugins" "$WORK/src/features" "$WORK/feature" "$WORK/config/org.eclipse.equinox.simpleconfigurator"

# 1. プラグイン
SRCS=$(find "$PLUGIN/src" -name '*.java' | while read f; do cygpath -w "$f"; done)
"$JRE/javac.exe" -J-Duser.language=en --release 11 -encoding UTF-8 -nowarn -d "$(cygpath -w "$WORK/classes")" -cp "$POOL/*" $SRCS
sed "s/^Bundle-Version: .*/Bundle-Version: $VERSION/" "$PLUGIN/META-INF/MANIFEST.MF" > "$WORK/classes/META-INF/MANIFEST.MF"
cp "$PLUGIN/plugin.xml" "$WORK/classes/"
"$JRE/jar.exe" --create --file "$(cygpath -w "$WORK/src/plugins/com.tkdevelopeross.eclipse.incline_$VERSION.jar")" \
  --manifest "$(cygpath -w "$WORK/classes/META-INF/MANIFEST.MF")" -C "$(cygpath -w "$WORK/classes")" .
cp "$GSON" "$WORK/src/plugins/"

# 2. フィーチャー
sed -e "s/version=\"[0-9.]*\.qualifier\"/version=\"$FVERSION\"/" \
    -e "s/id=\"com.tkdevelopeross.eclipse.incline\"\(.*\)/id=\"com.tkdevelopeross.eclipse.incline\"\1/" \
    "$FEATURE/feature.xml" | awk -v v="$VERSION" '
      /id="com.tkdevelopeross.eclipse.incline"$/ { inplugin = 1 }
      inplugin && /version="0.0.0"/ { sub(/version="0.0.0"/, "version=\"" v "\""); inplugin = 0 }
      { print }' > "$WORK/feature/feature.xml"
"$JRE/jar.exe" --create --file "$(cygpath -w "$WORK/src/features/com.tkdevelopeross.eclipse.incline.feature_$FVERSION.jar")" \
  -C "$(cygpath -w "$WORK/feature")" feature.xml

# 3. p2 リポジトリ
grep -E '^(eclipse[.]buildId|osgi[.]bundles|osgi[.]bundles[.]defaultStartLevel|osgi[.]framework|osgi[.]framework[.]extensions)=' \
  "$ECLIPSE/configuration/config.ini" > "$WORK/config/config.ini"
{
  echo "osgi.install.area=file:/$ECLIPSE/"
  echo "osgi.configuration.cascaded=false"
  echo "org.eclipse.update.reconcile=false"
  echo "eclipse.p2.data.area=@config.dir/.p2"
  echo "org.eclipse.equinox.simpleconfigurator.configUrl=file:/$WORK/config/org.eclipse.equinox.simpleconfigurator/bundles.info"
} >> "$WORK/config/config.ini"
grep -v '^com.tkdevelopeross.eclipse.incline,' "$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
  > "$WORK/config/org.eclipse.equinox.simpleconfigurator/bundles.info"

p2app() {
  "$JRE/java.exe" -Xmx512m -jar "$ECLIPSE"/plugins/org.eclipse.equinox.launcher_*.jar \
    -configuration "file:/$WORK/config/" -data @none -nosplash -consoleLog -application "$@"
}
rm -rf "$SITE"
mkdir -p "$SITE"
SITEURI="file:/$(cygpath -m "$SITE")"
p2app org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
  -metadataRepository "$SITEURI" -artifactRepository "$SITEURI" -metadataRepositoryName "Incline" -artifactRepositoryName "Incline" \
  -source "$(cygpath -w "$WORK/src")" -publishArtifacts -compress
p2app org.eclipse.equinox.p2.publisher.CategoryPublisher \
  -metadataRepository "$SITEURI" -categoryDefinition "file:/$(cygpath -m "$HERE/category.xml")" -categoryQualifier "$QUALIFIER" -compress

echo "done -> $SITE"
ls -R "$SITE"
