#!/usr/bin/env bash
set -euo pipefail

# 确保在仓库根目录下运行
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# 使用 Gradle 增量构建并写出启动参数，跳过守护进程依附以保证 GUI 信号与 Ctrl+C 正常传递
mise exec -- ./gradlew --no-configuration-cache writeRunArgs

JAVA_EXE="$(cat build/run/java)"
exec "$JAVA_EXE" @build/run/jvm.args "$@"
