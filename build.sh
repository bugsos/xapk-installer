#!/usr/bin/env bash
# 构建 XAPK 安装器：
#   ./build.sh            等价于 ./build.sh debug
#   ./build.sh debug      产出 app-debug.apk
#   ./build.sh release    产出已签名的 app-release.apk（签名取自 keystore.properties）
#
# JDK 定位顺序：项目内 .jdk/  >  Homebrew openjdk@17  >  环境变量 JAVA_HOME
# Gradle 定位顺序：本机已解压的 wrapper dist  >  PATH 里的 gradle
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

VARIANT="${1:-debug}"
shift 2>/dev/null || true

case "$VARIANT" in
  debug)
    TASK=":app:assembleDebug"
    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    TASK=":app:assembleRelease"
    APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "用法: ./build.sh [debug|release]"
    exit 1
    ;;
esac

find_jdk() {
  local candidates=(
    "$PROJECT_DIR/.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
  )
  for c in "${candidates[@]}"; do
    if [ -x "$c/bin/java" ]; then
      echo "$c"
      return 0
    fi
  done
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME"
    return 0
  fi
  return 1
}

find_gradle() {
  local dist="$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin"
  if [ -d "$dist" ]; then
    local g
    g="$(find "$dist" -maxdepth 5 -name gradle -type f 2>/dev/null | head -1)"
    if [ -n "$g" ]; then
      echo "$g"
      return 0
    fi
  fi
  if command -v gradle >/dev/null 2>&1; then
    command -v gradle
    return 0
  fi
  return 1
}

JDK="$(find_jdk)" || {
  echo "找不到 JDK 17+。请安装后重试："
  echo "  brew install openjdk@17"
  echo "或把 JDK 解压到 $PROJECT_DIR/.jdk/"
  exit 1
}
GRADLE="$(find_gradle)" || {
  echo "找不到 Gradle 8.11.1+。"
  exit 1
}

echo "JDK    : $JDK"
echo "Gradle : $GRADLE"
echo "变体   : $VARIANT"
echo

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

"$GRADLE" --no-daemon "$@" "$TASK"

echo
if [ ! -f "$APK" ]; then
  # release 没配上签名时 AGP 产出的是 -unsigned.apk
  UNSIGNED="${APK%.apk}-unsigned.apk"
  if [ "$VARIANT" = "release" ] && [ -f "$UNSIGNED" ]; then
    echo "构建结束：产出的是未签名包（keystore.properties 缺失或未生效）"
    echo "  $UNSIGNED"
    exit 1
  fi
  echo "构建结束但没找到 APK，请检查上面的输出。"
  exit 1
fi

echo "构建完成：$APK"
ls -lh "$APK"
