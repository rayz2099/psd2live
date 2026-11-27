# psd2live 任务运行器
set shell := ["bash", "-cu"]

# 默认列出所有可用任务
default:
    @just --list

# 本地启动 GUI 开发界面（支持 `just dev 23872`、`just dev auto`、透传应用参数如 `--store/--token/--lang`，以及注入 JVM 参数如 `-Xmx8g`/`-D...`）
dev *args:
    #!/usr/bin/env bash
    set -euo pipefail
    raw_args=({{args}})
    jvm_args=()
    app_args=()
    has_psd_file=0
    has_gui_cmd=0

    for arg in "${raw_args[@]}"; do
        case "$arg" in
            -D*|-X*)
                jvm_args+=("$arg")
                ;;
            gui)
                has_gui_cmd=1
                app_args+=("$arg")
                ;;
            *.psd|*.PSD|*.psd2live|*.PSD2LIVE)
                has_psd_file=1
                app_args+=("$arg")
                ;;
            *)
                app_args+=("$arg")
                ;;
        esac
    done

    first_arg="${app_args[0]:-}"
    if [ "$first_arg" = "auto" ]; then
        target_port=""
        for p in $(seq 23871 23880); do
            if ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
                target_port="$p"
                break
            fi
        done
        if [ -z "$target_port" ]; then
            echo "Error: 端口组 23871..23880 内所有端口均已被占用！" >&2
            exit 1
        fi
        echo "=== 自动分配 MCP 端口: $target_port (Endpoint: http://127.0.0.1:$target_port/mcp) ==="
        exec ./run-gui.sh "${jvm_args[@]}" gui --port "$target_port" "${app_args[@]:1}"
    elif [[ "$first_arg" =~ ^[0-9]+$ ]]; then
        echo "=== 启动 GUI 并绑定 MCP 端口: $first_arg (Endpoint: http://127.0.0.1:$first_arg/mcp) ==="
        exec ./run-gui.sh "${jvm_args[@]}" gui --port "$first_arg" "${app_args[@]:1}"
    elif [ "$has_psd_file" -eq 1 ] && [ "$has_gui_cmd" -eq 0 ]; then
        echo "=== 启动 GUI 并打开文件 ==="
        exec ./run-gui.sh "${jvm_args[@]}" "${app_args[@]}"
    else
        echo "=== 启动 GUI (默认 MCP 端口: 23871) ==="
        if [ "$has_gui_cmd" -eq 1 ]; then
            exec ./run-gui.sh "${jvm_args[@]}" "${app_args[@]}"
        else
            exec ./run-gui.sh "${jvm_args[@]}" gui "${app_args[@]}"
        fi
    fi

# 启动无窗口 Headless MCP 独立服务（支持指定端口如 `just mcp 23872`、`just mcp auto` 自动探测或透传参数）
mcp port="" *args:
    #!/usr/bin/env bash
    set -euo pipefail
    target_port="{{port}}"
    if [ "$target_port" = "auto" ]; then
        for p in $(seq 23871 23880); do
            if ! lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
                target_port="$p"
                break
            fi
        done
        if [ "$target_port" = "auto" ]; then
            echo "Error: 端口组 23871..23880 内所有端口均已被占用！" >&2
            exit 1
        fi
        echo "=== 自动分配 Headless MCP 端口: $target_port ==="
        exec ./run-gui.sh mcp --port "$target_port" {{args}}
    elif [[ "$target_port" =~ ^[0-9]+$ ]]; then
        echo "=== 启动 Headless MCP 服务在端口: $target_port ==="
        exec ./run-gui.sh mcp --port "$target_port" {{args}}
    elif [ -n "$target_port" ]; then
        exec ./run-gui.sh mcp "$target_port" {{args}}
    else
        echo "=== 启动默认 Headless MCP 服务 (默认端口: 23871) ==="
        exec ./run-gui.sh mcp 23871 {{args}}
    fi

# 查看 MCP 端口组 (23871..23880) 监听状态与占用进程
ports:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== PSD2Live MCP 端口组状态 (23871..23880) ==="
    printf "%-7s %-12s %-8s %s\n" "PORT" "STATUS" "PID" "COMMAND"
    for port in $(seq 23871 23880); do
        line=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n 1 || true)
        if [ -n "$line" ]; then
            pid=$(echo "$line" | awk '{print $2}')
            cmd=$(echo "$line" | awk '{print $1}')
            printf "%-7s %-12s %-8s %s\n" "$port" "LISTENING" "$pid" "$cmd"
        else
            printf "%-7s %-12s %-8s %s\n" "$port" "FREE" "-" "-"
        fi
    done

# 释放/杀掉指定端口的监听进程
kill-port port:
    #!/usr/bin/env bash
    set -euo pipefail
    line=$(lsof -nP -iTCP:"{{port}}" -sTCP:LISTEN 2>/dev/null | tail -n 1 || true)
    if [ -n "$line" ]; then
        pid=$(echo "$line" | awk '{print $2}')
        echo "正在终止端口 {{port}} 上的进程 (PID: $pid)..."
        kill "$pid" 2>/dev/null || kill -9 "$pid"
        echo "端口 {{port}} 进程已终止。"
    else
        echo "端口 {{port}} 未被监听。"
    fi

# 终止端口组 (23871..23880) 上的所有 psd2live 实例
kill-all-ports:
    #!/usr/bin/env bash
    set -euo pipefail
    for port in $(seq 23871 23880); do
        line=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n 1 || true)
        if [ -n "$line" ]; then
            pid=$(echo "$line" | awk '{print $2}')
            echo "终止端口 $port 上的进程 (PID: $pid)..."
            kill "$pid" 2>/dev/null || kill -9 "$pid"
        fi
    done
    echo "端口组清理完毕。"

# 通过 Gradle 原生 application 任务运行
run *args:
    mise exec -- ./gradlew --no-configuration-cache run {{ if args != "" { quote("--args=" + args) } else { "" } }}

# 编译项目与 Rust 运行时
build:
    mise exec -- ./gradlew --no-configuration-cache assemble

# 运行单元与集成测试
test *args:
    mise exec -- ./gradlew --no-configuration-cache test {{args}}

# 清理所有模块的构建缓存与产物
clean:
    mise exec -- ./gradlew --no-configuration-cache clean
