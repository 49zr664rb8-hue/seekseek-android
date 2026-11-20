#!/usr/bin/env bash
# 手工构建 APK（不依赖 Gradle）：aapt2 → javac → d8 → 打包 → 对齐 → 签名
# 注意：javac/d8 是 Windows 程序，必须用相对路径，否则 MSYS 路径会被解析成 \c\Users\...
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP="app"
SDK="${ANDROID_SDK:-/c/android-tools/sdk}"
JDK="${JDK_HOME:-/c/android-tools/jdk17}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
WIN_JAR="$(cygpath -w "$ANDROID_JAR" 2>/dev/null || echo "$ANDROID_JAR")"
OUT="build"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "缺少 android.jar：$ANDROID_JAR"
  exit 1
fi

JAVAC="$JDK/bin/javac"
KEYTOOL="$JDK/bin/keytool"
D8="$BT/d8.bat"; [ -f "$D8" ] || D8="$BT/d8"
APKSIGNER="$BT/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"

# .bat 工具需要 Windows 风格的 JAVA_HOME
if command -v cygpath >/dev/null 2>&1; then
  export JAVA_HOME="$(cygpath -w "$JDK")"
else
  export JAVA_HOME="$JDK"
fi
export PATH="$JDK/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] 链接资源与清单"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$WIN_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  -A "$APP/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "[3/6] 编译 Java"
find "$APP/java" "$OUT/gen" -name "*.java" -printf '"%p"\n' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 17 -target 17 \
  -classpath "$WIN_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] 转 dex"
# d8 的参数文件不认引号（javac 需要），这里用不带引号的相对路径
find "$OUT/classes" -name "*.class" -printf '%p\n' > "$OUT/classes.txt"
"$D8" --lib "$WIN_JAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] 打包"
python - "$OUT" <<'PYEOF'
import os, shutil, sys, zipfile
out = sys.argv[1]
shutil.copy(os.path.join(out, "base.apk"), os.path.join(out, "unsigned.apk"))
dexdir = os.path.join(out, "dex")
with zipfile.ZipFile(os.path.join(out, "unsigned.apk"), "a", zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith(".dex"):
            z.write(os.path.join(dexdir, name), name)
PYEOF

echo "[6/6] 对齐并签名"
# 有正式密钥就用正式的（密码从 release.keystore.pwd 读），否则退回调试密钥
KS="debug.keystore"
KS_PASS="android"
KEY_ALIAS="androiddebugkey"
if [ -f release.keystore ] && [ -f release.keystore.pwd ]; then
  KS="release.keystore"
  KS_PASS="$(tr -d '\r\n' < release.keystore.pwd)"
  KEY_ALIAS="deepseek"
  echo "    使用正式密钥签名"
else
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  echo "    使用调试密钥签名"
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" --out "$OUT/seekseek-1.0.1.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/seekseek-1.0.1.apk"
# 清掉中间产物，目录里只留成品
rm -f "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "完成：$OUT/seekseek-1.0.1.apk"
ls -la "$OUT/seekseek-1.0.1.apk" | awk '{printf "体积: %.1f MB\n", $5/1024/1024}'
