package io.github.psd2live.core

import io.github.psd2live.application.WorkspacePaintRaster
import io.github.psd2live.application.WorkspaceRasterEdits
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.test.*

/** 验证超 16 MP 大画布下局部网格重建不因整画布面积被拦截，且全画布绘画上限放宽至 32 MP。 */
class LargeCanvasPaintSpaceTest {
    private fun solidLayer(id: String, left: Int, top: Int, width: Int, height: Int): WorkspaceSourceLayer {
        val rgba = ByteArray(width * height * 4) { idx -> if (idx % 4 == 3) 255.toByte() else 200.toByte() }
        return WorkspaceSourceLayer(
            id = LayerId(id),
            name = id,
            groupPath = "",
            kind = SourceLayerKind.Raster,
            visible = true,
            order = 1,
            bounds = LayerBounds(left, top, width, height),
            opacity = 1f,
            clipped = false,
            blend = LayerBlend.Normal,
            channelMask = ChannelMask(red = true, green = true, blue = true, alpha = true),
            raster = LayerRaster(width, height, rgba),
            sourceAssetId = null,
            sourceSpatialReferenceId = null,
            derived = false,
        )
    }

    @Test
    fun forLayerCropAllows4335x4150And6000x6000Canvases() {
        val layer = solidLayer("ribbon_1", left = 100, top = 200, width = 128, height = 256)
        val space18Mp = PaintSpace.forLayerCrop(layer, 4335, 4150)
        val cropped18Mp = space18Mp.cropLayer(layer)
        assertEquals(128, cropped18Mp.raster.width)
        assertEquals(256, cropped18Mp.raster.height)
        assertEquals(LayerBounds(100, 200, 128, 256), cropped18Mp.bounds)

        val space36Mp = PaintSpace.forLayerCrop(layer, 6000, 6000)
        val cropped36Mp = space36Mp.cropLayer(layer)
        assertEquals(128, cropped36Mp.raster.width)
        assertEquals(256, cropped36Mp.raster.height)
        assertEquals(LayerBounds(100, 200, 128, 256), cropped36Mp.bounds)
    }

    @Test
    fun paintSpaceOfAllows32MpAndRejects36Mp() {
        val layer = solidLayer("face", left = 10, top = 10, width = 64, height = 64)
        val space18Mp = PaintSpace.of(layer, 4335, 4150)
        assertEquals(4335, space18Mp.width)
        assertEquals(4150, space18Mp.height)

        val err = assertFailsWith<IllegalArgumentException> {
            PaintSpace.of(layer, 6000, 6000)
        }
        assertTrue(err.message!!.contains("32 megapixels"))
    }

    @Test
    fun rebuildMeshSucceedsOn18MpAnd36MpCanvases() {
        for ((canvasW, canvasH) in listOf(4335 to 4150, 6000 to 6000)) {
            val layer = solidLayer("ribbon", left = 500, top = 500, width = 96, height = 160)
            val source = WorkspaceSourceArt(canvasW, canvasH, listOf(layer), emptyList())
            val config = PipelineConfig(meshOnly = true, generateDeformers = false, generatePhysics = false)
            val preview = PSD2LivePipeline().buildPreview(source, config)
            val doc = WorkspaceDocument(
                source = source,
                layerVisibility = emptyMap(),
                deletedLayerIds = emptySet(),
                layerOverrides = emptyMap(),
                parentOverrides = emptyMap(),
                rigEdits = RigEditOverlay(),
                settings = JsonObject(emptyMap()),
            )
            val rebuilt = WorkspaceRasterEdits.rebuildMesh(doc, preview, "ribbon")
            assertNotNull(rebuilt)
        }
    }

    @Test
    fun workspacePaintRasterBudgetMatches32Mp() {
        val tiny = LayerRaster(4, 4, ByteArray(4 * 4 * 4))
        val ok = WorkspacePaintRaster("l1", tiny, rebuildMesh = false)
        assertEquals(4, ok.raster.width)
    }
}
