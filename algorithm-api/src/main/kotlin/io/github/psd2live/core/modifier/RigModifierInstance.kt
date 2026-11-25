package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A reference captured at insertion, independent of the editor's current selection. */
@Serializable
data class ModifierTarget(val kind: String, val id: String) {
    init {
        require(kind in setOf("mesh", "deformer", "part", "layer")) { "Unknown modifier target kind: $kind" }
        require(id.isNotBlank() && id.none(Char::isISOControl)) { "Invalid modifier target ID" }
    }
}

@Serializable
data class RigModifierInstance(
    val id: String,
    val type: String,
    val version: Int,
    val targets: List<ModifierTarget>,
    val enabled: Boolean = true,
    val config: JsonObject = JsonObject(emptyMap()),
    val presetGroup: String? = null,
    /** Exact upstream input used by an expensive generated result; independent of editable configuration. */
    val inputFingerprint: String? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val parameterProviders: Map<String, String> = emptyMap(),
    /** Migration preserves existing object references with immutable, persisted local-output aliases. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputAliases: Map<String, String> = emptyMap(),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val plugin: ModifierPluginReference? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid modifier instance ID" }
        require(type.isNotBlank() && version > 0) { "A modifier needs a type and positive algorithm version" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size) { "A modifier needs distinct targets" }
        require(outputAliases.all { (local, id) -> local.isNotBlank() && id.isNotBlank() && id.none(Char::isISOControl) }) {
            "Output aliases need valid local names and object IDs"
        }
        require(outputAliases.values.distinct().size == outputAliases.size) { "Output aliases must be distinct" }
    }

    /** A generated local token is reversible for adapters that call native name-based algorithms. */
    fun localOutputName(output: String): String? {
        outputAliases.entries.firstOrNull { it.value == output }?.let { return it.key }
        val prefix = "modifier:$id:"
        if (!output.startsWith(prefix)) return null
        val hex = output.removePrefix(prefix)
        if (hex.length % 2 != 0) return null
        val bytes = hex.chunked(2).map { it.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    /** Encode every byte, including separators, so distinct local names cannot alias one another. */
    fun outputId(localName: String): String {
        require(localName.isNotBlank()) { "An output needs a local name" }
        outputAliases[localName]?.let { return it }
        val encoded = java.util.HexFormat.of().formatHex(localName.encodeToByteArray())
        return "modifier:$id:$encoded"
    }

    companion object {
        // MOC3 定长 64 字节 C 字符串槽位需留 1 字节给 NUL 终止符，上限收紧至 63 字符防止 .moc3 尾部截断。
        private const val MAX_CUBISM_ID_LENGTH = 63
        private val cubismIdPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,62}")

        /**
         * 为新建修改器生成符合 Cubism 字母表 `[A-Za-z][A-Za-z0-9_]{0,62}` 的确定性输出 ID。
         * 原因：默认 `modifier:$id:$hex` 含冒号，Cubism 导出与编辑器会报非法字符告警；通过 `outputAliases`
         * 映射可让新建修改器原生合规，同时不破坏存量 `.psd2live` 工程的 `modifier:` 历史标识。
         */
        fun cubismOutputId(instanceId: String, localName: String): String {
            require(instanceId.isNotBlank() && localName.isNotBlank()) { "Instance ID and local name are required" }
            val cleanInst = sanitizeToken(instanceId)
            val cleanLocal = sanitizeToken(localName)
            val hash = shortHash("$instanceId\u0000$localName")
            val rawPrefix = "Mod_${cleanInst}_${cleanLocal}"
            val maxPrefixLen = MAX_CUBISM_ID_LENGTH - 1 - hash.length
            val trimmed = rawPrefix.take(maxPrefixLen).trimEnd('_')
            val candidate = "${trimmed}_$hash"
            check(cubismIdPattern.matches(candidate)) { "Generated invalid Cubism ID: $candidate" }
            return candidate
        }

        /** 为新建 swing 修改器预分配全部方向与分段的 Cubism 合规别名，避免后续扩容分段时触发别名不可变校验。 */
        fun swingOutputAliases(instanceId: String, maxSegments: Int = 3): Map<String, String> = buildMap {
            put("swing", cubismOutputId(instanceId, "swing"))
            for (kind in listOf("lateral", "vertical")) {
                for (index in 0 until maxSegments) {
                    val localName = "swing:$kind:$index"
                    put(localName, cubismOutputId(instanceId, localName))
                }
            }
        }

        private fun sanitizeToken(raw: String): String {
            val out = StringBuilder(raw.length)
            var prevUnderscore = false
            for (ch in raw) {
                val valid = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'
                if (valid) {
                    out.append(ch)
                    prevUnderscore = false
                } else if (!prevUnderscore && out.isNotEmpty()) {
                    out.append('_')
                    prevUnderscore = true
                }
            }
            return out.toString().trimEnd('_').ifEmpty { "m" }
        }

        private fun shortHash(input: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
            return java.util.HexFormat.of().formatHex(digest).take(8)
        }
    }
}

