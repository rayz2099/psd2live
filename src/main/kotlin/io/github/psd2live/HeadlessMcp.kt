package io.github.psd2live

import io.github.psd2live.agent.AgentMcpConfig
import io.github.psd2live.agent.AgentMcpCredentials
import io.github.psd2live.agent.AgentMcpService
import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.DEFAULT_MCP_PORT
import io.github.psd2live.application.HeadlessPresentation
import io.github.psd2live.application.WorkspaceHost
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.core.CubismSdkPreviewSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * `psd2live mcp`: the MCP endpoint without a window, for an agent working alone. It serves the same operations as the
 * editor from the same application workspace host ([WorkspaceHost]), shown through a presentation that keeps nothing
 * on screen ([HeadlessPresentation]); no view model, Compose state or window is created. It has its own workspace
 * store (`--store`, default a `psd2live-mcp` folder in the user's home), so it never shares the editor's history or
 * instance lock. Prints the endpoint, the token and what it can do, then serves until stopped.
 */
internal object HeadlessMcp {
	fun run(arguments: List<String>): Int {
		// Views render with AWT images on the CPU; nothing needs a display.
		System.setProperty("java.awt.headless", "true")
		val rawArgs = arguments.drop(1)
		val options = mutableMapOf<String, String>()
		var index = 0
		while (index < rawArgs.size) {
			val arg = rawArgs[index]
			if (index == 0 && arg.toIntOrNull() != null) {
				// 兼容位置参数直接指定端口（如 mcp 23872），简化命令行与多 Agent 启动脚本
				options["--port"] = arg
				index++
			} else if (arg in setOf("--port", "-p", "--token", "--store", "--profile") && index + 1 < rawArgs.size) {
				val key = if (arg == "-p") "--port" else arg
				options[key] = rawArgs[index + 1]
				index += 2
			} else {
				throw IllegalArgumentException("Usage: mcp [port] [--port N] [--token T] [--store DIR] [--profile core|full]")
			}
		}

		val sysPort = System.getProperty("psd2live.agent.port")?.toIntOrNull()?.takeIf { it in 1024..65535 }
		val envPort = System.getenv("PSD2LIVE_MCP_PORT")?.toIntOrNull()?.takeIf { it in 1024..65535 }
		val explicitPort = options["--port"]?.toIntOrNull()?.takeIf { it in 1024..65535 }
		val port = explicitPort ?: sysPort ?: envPort ?: DEFAULT_MCP_PORT

		// 多端口实例并行时，若未显式指定 store，自动按端口隔离目录，避免多个 Agent 并发读写同一份状态与锁
		val defaultStoreName = if (port != DEFAULT_MCP_PORT) "psd2live-mcp-$port" else "psd2live-mcp"
		val store = Path.of(options["--store"] ?: Path.of(System.getProperty("user.home"), defaultStoreName).toString()).toAbsolutePath()
		Files.createDirectories(store)

		val profile = options["--profile"]?.let { requireNotNull(AgentToolProfile.fromKey(it)) { "Unknown profile: $it" } } ?: AgentToolProfile.CORE
		val presentation = HeadlessPresentation()
		val host = WorkspaceHost(presentation, store)
		presentation.attach(host)
		val operations = WorkspaceOperations(host)
		val envToken = System.getenv("PSD2LIVE_MCP_TOKEN")?.takeIf(AgentMcpCredentials::isValidToken)
		val token = options["--token"] ?: envToken ?: AgentMcpCredentials.generateToken()
		val service = AgentMcpService(host, AgentMcpConfig(port = port, token = token, profile = profile), operations)
		val connection = service.start()
		println("endpoint ${connection.endpoint}")
		println("token ${connection.token}")
		println("profile ${profile.key}")
		println("store $store")
		// Motion sampling runs the Cubism Core, which a build may leave out; every other operation needs nothing more.
		println("capabilities view_sample_motion=${if (CubismSdkPreviewSession.coreAvailable()) "yes" else "no"}")
		System.out.flush()
		val stopped = CountDownLatch(1)
		Runtime.getRuntime().addShutdownHook(Thread({
			runCatching { service.close() }; runCatching { operations.close() }; runCatching { host.close() }; runCatching { presentation.close() }
			stopped.countDown()
		}, "psd2live-mcp-shutdown"))
		stopped.await()
		return 0
	}
}
