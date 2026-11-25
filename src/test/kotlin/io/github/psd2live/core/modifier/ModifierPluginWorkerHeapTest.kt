package io.github.psd2live.core.modifier

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.test.*

/** 验证插件子进程 JVM 堆上限默认注入、配置覆盖与显式参数保留逻辑。 */
class ModifierPluginWorkerHeapTest {
    private fun fakePlugin(commands: Map<String, List<String>>): InstalledModifierPlugin {
        val ref = ModifierPluginReference("test.plugin", "1.0.0", "0".repeat(64))
        val manifest = ModifierPluginManifest(
            protocol = 2,
            id = "test.plugin",
            version = "1.0.0",
            name = "Test",
            commands = commands,
            files = mapOf("runtime.jar" to "0".repeat(64)),
            algorithms = listOf(
                ModifierPluginAlgorithm(
                    type = "test.algo",
                    version = 1,
                    name = "Test",
                    configSchema = JsonObject(emptyMap()),
                    defaults = JsonObject(emptyMap()),
                ),
            ),
        )
        return InstalledModifierPlugin(manifest, ref, Path.of("/tmp/plugin"), true)
    }

    @Test
    fun resolveWorkerMaxHeapPriorityAndValidation() {
        assertEquals("1g", ModifierPluginWorkers.resolveWorkerMaxHeap(null, null))
        assertEquals("512m", ModifierPluginWorkers.resolveWorkerMaxHeap(null, "512m"))
        assertEquals("2g", ModifierPluginWorkers.resolveWorkerMaxHeap("2g", "512m"))
        assertFailsWith<IllegalArgumentException> {
            ModifierPluginWorkers.resolveWorkerMaxHeap("invalid-heap", null)
        }
    }

    @Test
    fun launchCommandInjectsHeapCapOnlyWhenJavaLacksExplicitCap() {
        val runtimeDir = Path.of("/tmp/worker-runtime")

        // 默认无堆限制的 $JAVA 启动命令应注入 -Xmx1g。
        val defaultEntry = fakePlugin(mapOf("jvm" to listOf("\$JAVA", "-jar", "runtime.jar")))
        val defaultCmd = ModifierPluginWorkers.launchCommand(defaultEntry, runtimeDir, workerMaxHeap = "1g")
        assertEquals("-Xmx1g", defaultCmd[1])
        assertEquals("-jar", defaultCmd[2])
        assertEquals("./runtime.jar", defaultCmd[3])

        // 覆盖堆上限时应注入自定义值。
        val customCmd = ModifierPluginWorkers.launchCommand(defaultEntry, runtimeDir, workerMaxHeap = "768m")
        assertEquals("-Xmx768m", customCmd[1])

        // 清单已显式指定 -Xmx 或 -XX:MaxRAMPercentage 时不得重复注入。
        val explicitXmx = fakePlugin(mapOf("jvm" to listOf("\$JAVA", "-Xmx2g", "-jar", "runtime.jar")))
        val xmxCmd = ModifierPluginWorkers.launchCommand(explicitXmx, runtimeDir, workerMaxHeap = "1g")
        assertEquals(listOf("-Xmx2g", "-jar", "./runtime.jar"), xmxCmd.drop(1))

        val explicitPct = fakePlugin(mapOf("jvm" to listOf("\$JAVA", "-XX:MaxRAMPercentage=25", "-jar", "runtime.jar")))
        val pctCmd = ModifierPluginWorkers.launchCommand(explicitPct, runtimeDir, workerMaxHeap = "1g")
        assertEquals(listOf("-XX:MaxRAMPercentage=25", "-jar", "./runtime.jar"), pctCmd.drop(1))

        // 非 $JAVA 原生二进制命令保持原样。
        val nativeEntry = fakePlugin(mapOf(ModifierPluginStore.platform to listOf("runtime.jar", "--serve")))
        val nativeCmd = ModifierPluginWorkers.launchCommand(nativeEntry, runtimeDir, workerMaxHeap = "1g")
        assertFalse(nativeCmd.any { it.startsWith("-Xmx") })
    }
}
