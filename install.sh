#!/usr/bin/env bash
#
# CommitGenie 打包与安装脚本
#
# 用法：
#   ./install.sh              打包并安装到用户插件目录（推荐，无需 sudo）
#   ./install.sh --system     打包并安装到 Android Studio 应用包内（需要写权限）
#   ./install.sh --build-only 只打包，不安装
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SYSTEM=0
BUILD_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --system)     TARGET_SYSTEM=1 ;;
        --build-only) BUILD_ONLY=1 ;;
        -h|--help)    sed -n '3,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)            echo "未知参数：$arg" >&2; exit 1 ;;
    esac
done

# 与 Gradle 使用同一安装路径；路径含空格时也作为单个参数传递。
APP_PATH="${COMMITGENIE_IDE_PATH:-/Applications/Android Studio.app}"
JBR_HOME="$APP_PATH/Contents/jbr/Contents/Home"

# 只打包时不读取安装目标，也不创建用户插件目录。
if [[ $BUILD_ONLY -eq 0 ]]; then
    if [[ $TARGET_SYSTEM -eq 1 ]]; then
        INSTALL_ROOT="$APP_PATH/Contents/plugins"
    else
        if [[ -n "${COMMITGENIE_PLUGIN_DIR:-}" ]]; then
            INSTALL_ROOT="$COMMITGENIE_PLUGIN_DIR"
        else
            PRODUCT_INFO="$APP_PATH/Contents/Resources/product-info.json"
            if [[ ! -f "$PRODUCT_INFO" ]]; then
                echo "错误：未找到 $PRODUCT_INFO，请确认 IDE 安装路径" >&2
                exit 1
            fi
            DATA_DIR_NAME="$(sed -n 's/.*"dataDirectoryName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$PRODUCT_INFO")"
            if [[ -z "$DATA_DIR_NAME" ]]; then
                echo "错误：无法解析 dataDirectoryName" >&2
                exit 1
            fi
            INSTALL_ROOT="$HOME/Library/Application Support/Google/$DATA_DIR_NAME/plugins"
        fi
        if ! mkdir -p "$INSTALL_ROOT"; then
            echo "错误：无法创建用户插件目录 $INSTALL_ROOT" >&2
            exit 1
        fi
    fi
    if [[ ! -d "$INSTALL_ROOT" || ! -w "$INSTALL_ROOT" ]]; then
        echo "错误：插件目录 $INSTALL_ROOT 不存在或不可写，请检查目录权限或使用用户安装模式。" >&2
        exit 1
    fi
fi

# 构建使用 IDE 自带的 JBR，保证与插件目标字节码一致
if [[ -x "$JBR_HOME/bin/java" ]]; then
    export JAVA_HOME="$JBR_HOME"
fi

echo "==> 清理旧产物"
cd "$PROJECT_DIR"
rm -rf build/distributions

echo "==> 打包插件"
set +e
# 构建阶段看门狗：编译任务若长时间无任何输出，多半是卡死而非变慢
./gradlew buildPlugin --console=plain "-PidePath=$APP_PATH" &
BUILD_PID=$!
WATCHDOG_SECONDS=${WATCHDOG_SECONDS:-600}
( sleep "$WATCHDOG_SECONDS"; kill -0 "$BUILD_PID" 2>/dev/null && {
    echo >&2
    echo "错误：构建超过 ${WATCHDOG_SECONDS} 秒仍未结束，判定为卡死。" >&2
    echo "可用 WATCHDOG_SECONDS=1800 ./install.sh 放宽等待时间。" >&2
    kill -TERM "$BUILD_PID" 2>/dev/null
} ) &
WATCHDOG_PID=$!
wait "$BUILD_PID"
exit_code=$?
# 收尾看门狗；wait 吞掉它的退出状态，避免构建成功后仍打印 Terminated 噪音
kill "$WATCHDOG_PID" 2>/dev/null
wait "$WATCHDOG_PID" 2>/dev/null
set -e
if [[ $exit_code -ne 0 ]]; then
    echo >&2
    if [[ $exit_code -eq 137 ]]; then
        echo "构建被系统终止（退出码 137），通常是内存不足。" >&2
        echo "请关闭 Android Studio 等占用内存的程序后重试。" >&2
    elif [[ $exit_code -eq 143 ]]; then
        echo "构建被看门狗终止，请检查是否卡在 compileKotlin 阶段。" >&2
    else
        echo "构建失败，请查看上方 Gradle 输出。" >&2
    fi
    exit $exit_code
fi

ZIP_PATH="$(find "$PROJECT_DIR/build/distributions" -maxdepth 1 -name '*.zip' | head -1)"
if [[ -z "$ZIP_PATH" ]]; then
    echo "错误：未找到打包产物（build/distributions/*.zip）" >&2
    exit 1
fi
echo "    产物：$ZIP_PATH"

if [[ $BUILD_ONLY -eq 1 ]]; then
    echo "==> 仅打包，跳过安装"
    exit 0
fi

echo "==> 安装到 $INSTALL_ROOT"

# 打包产物结构为 <PluginName>/lib/*.jar，plugin.xml 在 jar 内部而非散文件。
# 顶层目录即插件根，整棵拷贝即可，无需在解压目录里查找 plugin.xml。
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT
unzip -q "$ZIP_PATH" -d "$STAGING"

PLUGIN_ROOT="$(find "$STAGING" -mindepth 1 -maxdepth 1 -type d | head -1)"
if [[ -z "$PLUGIN_ROOT" ]]; then
    echo "错误：打包产物中未找到插件目录" >&2
    exit 1
fi
PLUGIN_NAME="$(basename "$PLUGIN_ROOT")"

rm -rf "${INSTALL_ROOT:?}/$PLUGIN_NAME"
cp -R "$PLUGIN_ROOT" "$INSTALL_ROOT/"

echo "==> 已安装：$INSTALL_ROOT/$PLUGIN_NAME"
echo
echo "重启 Android Studio 后生效。"
echo "验证方式：Settings → Tools → CommitGenie 应出现配置页；"
echo "提交面板的消息输入框工具栏应出现生成按钮（快捷键 ⌃\\）。"
