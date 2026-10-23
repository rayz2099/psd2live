package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceOperations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

/** The user's MCP endpoint settings; the token doubles as the stdio proxy's credential. */
data class AgentMcpSettings(
    val enabled: Boolean = true,
    val port: Int = DEFAULT_MCP_PORT,
    val token: String,
    val profile: AgentToolProfile = AgentToolProfile.CORE,
) {
    /** The reason these settings cannot start an endpoint, or null when they can. */
    fun problem(): AgentMcpSettingsProblem? = when {
        port !in 1024..65535 -> AgentMcpSettingsProblem.PORT
        !AgentMcpCredentials.isValidToken(token) -> AgentMcpSettingsProblem.TOKEN
        else -> null
    }
}

enum class AgentMcpSettingsProblem { PORT, TOKEN }

interface AgentMcpSettingsStore {
    fun load(): AgentMcpSettings
    fun save(settings: AgentMcpSettings)
}

/**
 * 用户级 Java Preferences 存储。
 * 为支持多 Agent 并行及 CLI 动态分配端口，允许通过构造参数、JVM 参数或环境变量进行端口与令牌覆盖，
 * 避免不同实例间相互覆盖持久化配置。
 */
class PreferencesAgentMcpSettingsStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(AgentMcpCredentials::class.java),
    private val overridePort: Int? = null,
    private val overrideToken: String? = null,
) : AgentMcpSettingsStore {
    override fun load(): AgentMcpSettings {
        val envToken = System.getenv("PSD2LIVE_MCP_TOKEN")?.takeIf(AgentMcpCredentials::isValidToken)
        val token = overrideToken?.takeIf(AgentMcpCredentials::isValidToken)
            ?: envToken
            ?: preferences.get(TOKEN_KEY, null)?.takeIf(AgentMcpCredentials::isValidToken)
            ?: AgentMcpCredentials.generateToken().also { preferences.put(TOKEN_KEY, it) }

        val sysPort = System.getProperty("psd2live.agent.port")?.toIntOrNull()?.takeIf { it in 1024..65535 }
        val envPort = System.getenv("PSD2LIVE_MCP_PORT")?.toIntOrNull()?.takeIf { it in 1024..65535 }
        val prefPort = preferences.getInt(PORT_KEY, DEFAULT_MCP_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_MCP_PORT
        val port = overridePort?.takeIf { it in 1024..65535 } ?: sysPort ?: envPort ?: prefPort

        return AgentMcpSettings(
            enabled = preferences.getBoolean(ENABLED_KEY, true),
            port = port,
            token = token,
            profile = AgentToolProfile.fromKey(preferences.get(PROFILE_KEY, null)) ?: AgentToolProfile.CORE,
        )
    }

    override fun save(settings: AgentMcpSettings) {
        preferences.putBoolean(ENABLED_KEY, settings.enabled)
        // 仅在非临时覆盖或用户在界面主动调整了端口时持久化，防止多实例临时端口污染主配置
        if (overridePort == null || settings.port != overridePort) {
            preferences.putInt(PORT_KEY, settings.port)
        }
        if (overrideToken == null) {
            preferences.put(TOKEN_KEY, settings.token)
        }
        preferences.put(PROFILE_KEY, settings.profile.key)
        runCatching { preferences.flush() }
    }

    private companion object {
        const val TOKEN_KEY = "agent_mcp_bearer_token"
        const val PORT_KEY = "agent_mcp_port"
        const val PROFILE_KEY = "agent_mcp_profile"
        const val ENABLED_KEY = "agent_mcp_enabled"
    }
}

sealed interface AgentMcpStatus {
    data object Stopped : AgentMcpStatus
    data class Running(val connection: AgentMcpConnectionInfo) : AgentMcpStatus
    data class Failed(val message: String) : AgentMcpStatus
}

/**
 * Owns the process's MCP operations for the whole session and restarts the endpoint when its settings change.
 * Jobs and request results outlive a restart; connected clients reconnect to the new endpoint.
 */
class AgentMcpController(
    private val workspace: WorkspaceBackend,
    private val store: AgentMcpSettingsStore = PreferencesAgentMcpSettingsStore(),
    private val host: String = "127.0.0.1",
    /** Told of every tool call any endpoint this controller starts serves. */
    private val observer: AgentCallObserver? = null,
) : AutoCloseable {
    private val operations = WorkspaceOperations(workspace)
    private var service: AgentMcpService? = null
    private var closed = false
    private val _settings = MutableStateFlow(store.load())
    private val _status = MutableStateFlow<AgentMcpStatus>(AgentMcpStatus.Stopped)

    val settings: StateFlow<AgentMcpSettings> = _settings.asStateFlow()
    val status: StateFlow<AgentMcpStatus> = _status.asStateFlow()

    /** Every operation as the help page describes it: its calls in both tool profiles and its request fields. */
    fun operationDocs(): List<AgentOperationDoc> = agentOperationDocs(operations.registry.definitions())

    /** Starts the saved settings. */
    fun start(): AgentMcpStatus = apply(_settings.value, save = false)

    /** Saves [settings] and restarts the endpoint with them. Blocks while the old endpoint stops and the new one binds. */
    @Synchronized
    fun apply(settings: AgentMcpSettings, save: Boolean = true): AgentMcpStatus {
        check(!closed) { "Agent MCP controller is closed" }
        settings.problem()?.let { throw IllegalArgumentException("Invalid MCP settings: $it") }
        if (save) store.save(settings)
        _settings.value = settings
        service?.close()
        service = null
        val status = if (!settings.enabled) AgentMcpStatus.Stopped else {
            val next = AgentMcpService(workspace, AgentMcpConfig(host, settings.port, settings.token, settings.profile), operations, observer)
            try {
                AgentMcpStatus.Running(next.start()).also { service = next }
            } catch (failure: Exception) {
                runCatching { next.close() }
                AgentMcpStatus.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        }
        _status.value = status
        return status
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { service?.close() }
        service = null
        _status.value = AgentMcpStatus.Stopped
        operations.close()
        runCatching { (workspace as? AutoCloseable)?.close() }
    }
}
