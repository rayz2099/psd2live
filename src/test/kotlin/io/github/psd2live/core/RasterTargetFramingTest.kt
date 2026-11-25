package io.github.psd2live.core

import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.TargetSetting
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.raster.RasterTargets
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** 验证光栅/视频导出的超画布取景模式（fit_motion / pad）与默认 canvas 模式契约。 */
class RasterTargetFramingTest {
    private fun redTexturePng(): ByteArray {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                img.setRGB(x, y, 0xffff0000.toInt())
            }
        }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "png", out))
        return out.toByteArray()
    }

    /** 构造 100x100 画布内红色方块，在动画末帧向右甩出画布至 x=[130, 170]。 */
    private fun outOfCanvasMotionIr(): RigIR {
        val png = redTexturePng()
        val mesh = Mesh(
            id = "MeshRed",
            name = "Red",
            parent = null,
            geometry = MeshGeometry(
                positions = Floats.wrap(floatArrayOf(20f, 30f, 60f, 30f, 60f, 70f, 20f, 70f)),
                uvs = Floats.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)),
                indices = Ints.wrap(intArrayOf(0, 1, 2, 0, 2, 3)),
            ),
            offsets = KeyGrid(
                axes = listOf(KeyAxis("ParamShift", Floats.wrap(floatArrayOf(0f, 1f)))),
                cells = listOf(
                    KeyCell(Ints.wrap(intArrayOf(0)), MeshOffsets(Floats.wrap(FloatArray(8)))),
                    KeyCell(
                        Ints.wrap(intArrayOf(1)),
                        MeshOffsets(Floats.wrap(floatArrayOf(110f, 0f, 110f, 0f, 110f, 0f, 110f, 0f))),
                    ),
                ),
            ),
            page = 0,
        )
        return RigIR(
            canvas = Canvas(100f, 100f),
            parameters = listOf(Parameter("ParamShift", "Shift", 0f, 1f, 0f)),
            parts = listOf(Part("PartRoot", "Root", listOf(ChildRef.MeshRef("MeshRed")))),
            rootChildren = listOf(ChildRef.PartRef("PartRoot")),
            meshes = listOf(mesh),
            renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, listOf(RenderMesh("MeshRed"))),
            textures = Textures(
                pages = listOf(TexturePage(16, 16, Bytes.wrap(png))),
                bindings = mapOf("MeshRed" to 0),
            ),
            clips = listOf(
                Clip(
                    id = "clip_out",
                    name = "Out",
                    group = "Idle",
                    file = "out",
                    duration = 1f,
                    fps = 2f,
                    loop = false,
                    curves = listOf(
                        Curve("ParamShift", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f))),
                    ),
                ),
            ),
        )
    }

    private fun countRedPixels(pngBytes: ByteArray): Int {
        val img = ImageIO.read(ByteArrayInputStream(pngBytes))
        var count = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                val r = (argb ushr 16) and 0xff
                val g = (argb ushr 8) and 0xff
                val a = (argb ushr 24) and 0xff
                if (a > 200 && r > 200 && g < 50) count++
            }
        }
        return count
    }

    @Test
    fun rasterTargetsExposeFramingAndPaddingSettings() {
        val targets = RasterTargets(IrFrameRenderer)
        val mp4 = targets.all.single { it.id == "mp4" }
        val framing = mp4.settings.filterIsInstance<TargetSetting.Choice>().single { it.key == "framing" }
        assertEquals("canvas", framing.default)
        assertEquals(listOf("canvas", "fit_motion", "pad"), framing.values)
        val padding = mp4.settings.filterIsInstance<TargetSetting.Number>().single { it.key == "padding" }
        assertEquals(0.0, padding.min)
        assertEquals(0.5, padding.max)
    }

    @Test
    fun fitMotionAndPadPreserveOutOfCanvasMotionWhileCanvasCropsToPsd() {
        val ir = outOfCanvasMotionIr()
        val seqTarget = RasterTargets(IrFrameRenderer).sequence

        // 默认 canvas 模式：末帧方块在 x=[130, 170]，完全落在 [0, 100] 之外，应被裁切。
        val canvasFiles = LinkedHashMap<String, ByteArray>()
        seqTarget.plan(ir, ExportOptions("t", settings = mapOf("size" to "100", "fps" to "2"))).write { p, b -> canvasFiles[p] = b }
        assertTrue(countRedPixels(canvasFiles.values.first()) > 100)
        assertEquals(0, countRedPixels(canvasFiles.values.last()))

        // fit_motion 模式：单机位覆盖整段并集包围盒，末帧超画布红色像素完整保留且帧尺寸恒定。
        val fitFiles = LinkedHashMap<String, ByteArray>()
        seqTarget.plan(
            ir,
            ExportOptions("t", settings = mapOf("size" to "200", "fps" to "2", "framing" to "fit_motion", "padding" to "0.05")),
        ).write { p, b -> fitFiles[p] = b }
        val fitImages = fitFiles.values.map { ImageIO.read(ByteArrayInputStream(it)) }
        assertEquals(1, fitImages.map { it.width to it.height }.distinct().size)
        assertTrue(countRedPixels(fitFiles.values.last()) > 100)

        // pad 模式：padding=0.5 将 100x100 画布扩展至 [-50, 150]，末帧左半区 [130, 150] 可见。
        val padFiles = LinkedHashMap<String, ByteArray>()
        seqTarget.plan(
            ir,
            ExportOptions("t", settings = mapOf("size" to "200", "fps" to "2", "framing" to "pad", "padding" to "0.5")),
        ).write { p, b -> padFiles[p] = b }
        assertTrue(countRedPixels(padFiles.values.last()) > 50)
    }
}
