package example

import io.github.psd2live.core.*
import org.umamo.runtime.model.*
import kotlin.test.*

class SwingEngineTest {
    @Test fun standaloneGenerationKeepsRestAndPinnedEdge() {
        val points = floatArrayOf(0f, 0f, 10f, 0f, 0f, 30f, 10f, 30f)
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
        val input = PuppetModel(emptyList(), emptyList(), listOf(warp), emptyList(), emptyList(), null)
        val edit = RigSwingEdit.single("swing", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("angle"),
            fulcrum = SwingFulcrum.TOP)
        assertEquals(edit, RigSwingEdit.fromJson(edit.toJson()))
        val (result, issues) = SwingGenerator.applyOne(input, edit)
        assertTrue(issues.isEmpty())
        val grid = (result.deformers.single() as Deformer.Warp).geometryGrid!!
        assertEquals(3, grid.cells.size)
        assertContentEquals(points, grid.cells.single { it.coordinate.contentEquals(intArrayOf(1)) }.form.controlPoints)
        grid.cells.forEach { cell ->
            assertContentEquals(points.copyOfRange(0, 4), cell.form.controlPoints.copyOfRange(0, 4))
        }
        assertTrue(grid.cells.any { !it.form.controlPoints.contentEquals(points) })
        assertTrue(input.parameters.isEmpty())
        assertEquals(1, warp.geometryGrid!!.cells.size)
    }

    /** 校验既有 Warp 网格不足以支撑多段摇摆时必须硬拒绝并给出可操作报错，达标网格则正常通过。 */
    @Test fun wrappingRejectsUndersizedExistingWarpAndAcceptsValidGrids() {
        fun warpModel(rows: Int, cols: Int, width: Float = 40f, height: Float = 120f): PuppetModel {
            val pts = (0..rows).flatMap { r ->
                (0..cols).flatMap { c -> listOf(c * width / cols, r * height / rows) }
            }.toFloatArray()
            val warp = Deformer.Warp(
                DeformerId("WarpHair"), "Hair", null, null, rows, cols, true,
                KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(pts)))),
            )
            return PuppetModel(emptyList(), emptyList(), listOf(warp), emptyList(), emptyList(), null)
        }

        val threeSegTop = RigSwingEdit.single(
            "sw_hair", "Hair", SwingKind.LATERAL, listOf("WarpHair"), listOf("p1", "p2", "p3"),
            fulcrum = SwingFulcrum.TOP,
        )
        val err2x2 = assertFailsWith<IllegalArgumentException> {
            SwingWrapping.wrap(warpModel(2, 2), threeSegTop)
        }
        assertTrue(err2x2.message!!.contains("WarpHair"))
        assertTrue(err2x2.message!!.contains("2x2"))
        assertTrue(err2x2.message!!.contains("4x2"))
        assertTrue(err2x2.message!!.contains("6x3"))
        assertTrue(err2x2.message!!.contains("warp_set_topology"))

        // 交叉轴仅 1 段同样不足以保持边缘弯曲形态。
        assertFailsWith<IllegalArgumentException> {
            SwingWrapping.wrap(warpModel(4, 1), threeSegTop)
        }

        // 4x2 与 6x3 纵向网格满足 3 段摇摆硬底线与推荐密度。
        assertEquals(listOf("WarpHair"), SwingWrapping.wrap(warpModel(4, 2), threeSegTop).targets)
        assertEquals(listOf("WarpHair"), SwingWrapping.wrap(warpModel(6, 3), threeSegTop).targets)

        // 横向支点按 columns 校验：2x2 拒绝、2x4 与 3x6 通过。
        val threeSegLeft = RigSwingEdit.single(
            "sw_ribbon", "Ribbon", SwingKind.VERTICAL, listOf("WarpHair"), listOf("p1", "p2", "p3"),
            fulcrum = SwingFulcrum.LEFT,
        )
        val errLeft = assertFailsWith<IllegalArgumentException> {
            SwingWrapping.wrap(warpModel(2, 2, width = 120f, height = 40f), threeSegLeft)
        }
        assertTrue(errLeft.message!!.contains("2x4"))
        assertTrue(errLeft.message!!.contains("3x6"))
        assertEquals(listOf("WarpHair"), SwingWrapping.wrap(warpModel(2, 4, width = 120f, height = 40f), threeSegLeft).targets)
        assertEquals(listOf("WarpHair"), SwingWrapping.wrap(warpModel(3, 6, width = 120f, height = 40f), threeSegLeft).targets)
    }
}
