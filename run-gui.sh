#!/usr/bin/env bash
set -euo pipefail

# 确保在仓库根目录下运行
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# 默认复用已生成的 build/run 启动参数以秒开；首次缺失或显式传入 --build / PSD2LIVE_BUILD=1 时才触发 Gradle 增量构建
need_build=0
if [ "${PSD2LIVE_BUILD:-0}" = "1" ] || [ ! -f build/run/java ] || [ ! -f build/run/jvm.args ]; then
    need_build=1
fi

pass_args=()
for arg in "$@"; do
    if [ "$arg" = "--build" ]; then
        need_build=1
    else
        pass_args+=("$arg")
    fi
done

if [ "$need_build" -eq 1 ]; then
    echo "=== 正在执行 Gradle 增量构建 (writeRunArgs)... ==="
    mise exec -- ./gradlew --no-configuration-cache writeRunArgs
else
    echo "=== 复用已有构建产物直接启动 (如需重新编译请加 --build 或执行 just build) ==="
fi

JAVA_EXE="$(cat build/run/java)"

# 默认使用 4G 堆与 JDK 25 ZGC，再叠加外部注入的 JVM 参数（JAVA_OPTS / PSD2LIVE_JVM_OPTS 以及命令行中的 -D* / -X*）
jvm_opts=(-Xmx4g -XX:+UseZGC)
if [ -n "${JAVA_OPTS:-}" ]; then
    read -r -a extra_java_opts <<< "$JAVA_OPTS"
    jvm_opts+=("${extra_java_opts[@]}")
fi
if [ -n "${PSD2LIVE_JVM_OPTS:-}" ]; then
    read -r -a extra_psd_opts <<< "$PSD2LIVE_JVM_OPTS"
    jvm_opts+=("${extra_psd_opts[@]}")
fi

app_args=()
for arg in "${pass_args[@]}"; do
    case "$arg" in
        -D*|-X*)
            jvm_opts+=("$arg")
            ;;
        *)
            app_args+=("$arg")
            ;;
    esac
done

exec "$JAVA_EXE" "${jvm_opts[@]}" @build/run/jvm.args "${app_args[@]}"
