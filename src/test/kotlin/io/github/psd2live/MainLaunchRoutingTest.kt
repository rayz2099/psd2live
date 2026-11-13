package io.github.psd2live

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 Main 启动路由：显式 CLI 子命令不得因附带 --port/--store/--token 误入桌面 GUI。 */
class MainLaunchRoutingTest {
	@Test fun explicitCliCommandsNeverLaunchGui() {
		assertFalse(isGuiLaunch(listOf("mcp", "--port", "23881", "--token", "tok", "--store", "/tmp/store", "--profile", "full"), null))
		assertFalse(isGuiLaunch(listOf("mcp", "23881"), null))
		assertFalse(isGuiLaunch(listOf("export", "a.psd", "--target", "cubism"), null))
		assertFalse(isGuiLaunch(listOf("targets"), null))
		assertFalse(isGuiLaunch(listOf("--clear-user-data"), null))
		assertFalse(isGuiLaunch(listOf("--help"), null))
		assertFalse(isGuiLaunch(listOf("mcp", "--help"), null))
		assertFalse(isGuiLaunch(listOf("--input", "a.psd", "--output", "out"), null))
	}

	@Test fun guiCommandsLaunchDesktopWindow() {
		assertTrue(isGuiLaunch(emptyList(), emptyList()))
		assertTrue(isGuiLaunch(listOf("/tmp/a.psd"), listOf("/tmp/a.psd")))
		assertTrue(isGuiLaunch(listOf("gui", "--port", "23872", "--store", "/tmp/s"), null))
		assertTrue(isGuiLaunch(listOf("--port", "23872", "--store", "/tmp/s"), null))
		assertTrue(isGuiLaunch(listOf("23872"), null))
	}
}
