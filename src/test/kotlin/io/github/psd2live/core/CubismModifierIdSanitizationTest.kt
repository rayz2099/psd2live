package io.github.psd2live.core

import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.Cmo3Target
import io.github.psd2live.targets.cubism.CubismIdSanitizer
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.cubism.VTubeStudioTarget
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** 验证新建修改器输出合规 Cubism ID、历史 modifier: 工程向后兼容及导出跨文件确定性清洗。 */
class CubismModifierIdSanitizationTest {
    private fun blankPng(size: Int = 16): ByteArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(4, 4, 0xffff0000.toInt())
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "png", out))
        return out.toByteArray()
    }

    @Test
    fun newSwingModifierEmitsCubismSafeOutputIdsWithoutColons() {
        val aliases = RigModifierInstance.swingOutputAliases("fairy_chest_rib", RigSwingEdit.MAX_SEGMENTS)
        val instance = RigModifierInstance(
            id = "fairy_chest_rib",
            type = "swing",
            version = 1,
            targets = listOf(ModifierTarget("deformer", "WarpRibbon")),
            outputAliases = aliases,
        )
        for (local in listOf("swing", "swing:lateral:0", "swing:lateral:2", "swing:vertical:1")) {
            val outId = instance.outputId(local)
            assertFalse(':' in outId, "Output ID must not contain colon: $outId")
            assertTrue(RigIdConversion.ID_PATTERN.matches(outId), "Output ID must match Cubism alphabet: $outId")
            assertEquals(local, instance.localOutputName(outId))
        }
    }

    @Test
    fun legacyModifierColonIdsRemainReadableAndSanitizeConsistentlyAcrossCubismExports() {
        val legacyInst = RigModifierInstance("fairy_chest_rib", "swing", 1, listOf(ModifierTarget("deformer", "WarpRibbon")))
        val legacyParamId = legacyInst.outputId("swing:vertical:1")
        assertTrue(legacyParamId.startsWith("modifier:fairy_chest_rib:"))
        assertEquals("swing:vertical:1", legacyInst.localOutputName(legacyParamId))

        val pngBytes = blankPng(16)
        val legacyPhysId = legacyInst.outputId("physics:0")
        val mesh = Mesh(
            id = "MeshRibbon",
            name = "Ribbon",
            parent = null,
            geometry = MeshGeometry(
                positions = Floats.wrap(floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)),
                uvs = Floats.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)),
                indices = Ints.wrap(intArrayOf(0, 1, 2, 0, 2, 3)),
            ),
            offsets = KeyGrid(
                axes = listOf(KeyAxis(legacyParamId, Floats.wrap(floatArrayOf(-1f, 0f, 1f)))),
                cells = listOf(
                    KeyCell(Ints.wrap(intArrayOf(0)), MeshOffsets(Floats.wrap(floatArrayOf(-5f, 0f, -5f, 0f, -5f, 0f, -5f, 0f)))),
                    KeyCell(Ints.wrap(intArrayOf(1)), MeshOffsets(Floats.wrap(FloatArray(8)))),
                    KeyCell(Ints.wrap(intArrayOf(2)), MeshOffsets(Floats.wrap(floatArrayOf(5f, 0f, 5f, 0f, 5f, 0f, 5f, 0f)))),
                ),
            ),
            page = 0,
        )
        val ir = RigIR(
            canvas = Canvas(64f, 64f),
            parameters = listOf(
                Parameter("ParamAngleX", "Angle X", -30f, 30f, 0f),
                Parameter(legacyParamId, "Ribbon Swing", -1f, 1f, 0f),
            ),
            parameterTree = listOf(
                ParameterNode.Param("ParamAngleX"),
                ParameterNode.Param(legacyParamId),
            ),
            parts = listOf(Part("PartRoot", "Root", listOf(ChildRef.MeshRef("MeshRibbon")))),
            rootChildren = listOf(ChildRef.PartRef("PartRoot")),
            meshes = listOf(mesh),
            renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, listOf(RenderMesh("MeshRibbon"))),
            textures = Textures(
                pages = listOf(TexturePage(16, 16, Bytes.wrap(pngBytes))),
                bindings = mapOf("MeshRibbon" to 0),
            ),
            physics = Physics(
                groups = listOf(
                    PhysicsGroup(
                        id = legacyPhysId,
                        name = "Ribbon Physics",
                        inputs = listOf(PhysicsInput("ParamAngleX", 100f, PhysicsSource.X, false)),
                        outputs = listOf(PhysicsOutput(legacyParamId, 1, 1f, 100f, PhysicsSource.ANGLE, false)),
                        segments = listOf(io.github.psd2live.format.model.PhysicsSegment(10f, 0.9f, 0.9f, 1.2f)),
                        normalization = io.github.psd2live.format.model.PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f),
                    ),
                ),
                fps = 60f,
            ),
            clips = listOf(
                Clip(
                    id = "clip_idle",
                    name = "Idle",
                    group = "Idle",
                    file = "idle",
                    duration = 1f,
                    fps = 30f,
                    loop = true,
                    curves = listOf(
                        Curve(legacyParamId, 0f, 0f, listOf(CurveSegment.Linear(1f, 1f))),
                    ),
                ),
            ),
        )

        val sanitized = CubismIdSanitizer.sanitizeWithMaps(ir)
        val safeIr = sanitized.ir
        val safeParamId = requireNotNull(sanitized.maps.parameters[legacyParamId])
        assertTrue(RigIdConversion.ID_PATTERN.matches(safeParamId))
        assertFalse(':' in safeParamId)

        // 1) MOC3 + sidecars (.physics3.json / .motion3.json / .cdi3.json)
        val mocBundle = Moc3Target.bundle(safeIr, ExportOptions("sample"))
        val mocBytes = mocBundle.files.first { it.name.endsWith(".moc3") }.bytes
        val mocPuppet = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
        assertEquals(emptyList(), RigIdCheck.invalid(mocPuppet))
        assertTrue(mocPuppet.parameters.any { it.id.raw == safeParamId })
        val physicsText = mocBundle.files.first { it.name.endsWith(".physics3.json") }.bytes.decodeToString()
        val motionText = mocBundle.files.first { it.name.endsWith(".motion3.json") }.bytes.decodeToString()
        assertTrue(safeParamId in physicsText)
        assertFalse(legacyParamId in physicsText)
        assertTrue(safeParamId in motionText)
        assertFalse(legacyParamId in motionText)

        // 2) CMO3 + CAN3
        val cmoTarget = Cmo3Target()
        val cmoResult = cmoTarget.convert(safeIr, ExportOptions("sample"))
        val cmoBytes = Cmo3.write(cmoResult.model)
        val cmoPuppet = Cmo3Import.fromModelSource(Cmo3.read(cmoBytes).root as CModelSource)
        assertEquals(emptyList(), RigIdCheck.invalid(cmoPuppet))
        assertTrue(cmoPuppet.parameters.any { it.id.raw == safeParamId })
        val can3Bytes = cmoTarget.can3(safeIr, ExportOptions("sample"), cmoResult)
        assertNotNull(can3Bytes)

        // 3) VTube Studio
        val vtsFiles = LinkedHashMap<String, ByteArray>()
        VTubeStudioTarget.plan(safeIr, ExportOptions("sample")).write { path, bytes -> vtsFiles[path] = bytes }
        val vtsJson = requireNotNull(vtsFiles["sample.vtube.json"]).decodeToString()
        assertFalse(legacyParamId in vtsJson)
    }

    /** 端到端校验 PSD2LivePipeline.export 与 ExportService 对含冒号历史修改器 ID 的自动清洗与零告警。 */
    @Test
    fun pipelineAndExportServiceSanitizeLegacyModifierIdsWithZeroInvalidIdWarnings() {
        val tempDir = java.nio.file.Files.createTempDirectory("cubism-id-sanitize")
        try {
            val legacyInst = RigModifierInstance("fairy_chest_rib", "swing", 1, listOf(ModifierTarget("deformer", "WarpRibbon")))
            val legacyParamId = legacyInst.outputId("swing:vertical:1")
            assertTrue(':' in legacyParamId)

            val rgba = ByteArray(16 * 16 * 4) { idx -> if (idx % 4 == 3) 255.toByte() else 180.toByte() }
            val layer = io.github.psd2live.document.WorkspaceSourceLayer(
                id = org.umamo.format.art.LayerId("ribbon"),
                name = "ribbon",
                groupPath = "",
                kind = org.umamo.format.art.SourceLayerKind.Raster,
                visible = true,
                order = 1,
                bounds = org.umamo.format.art.LayerBounds(8, 8, 16, 16),
                opacity = 1f,
                clipped = false,
                blend = org.umamo.format.art.LayerBlend.Normal,
                channelMask = org.umamo.format.art.ChannelMask(red = true, green = true, blue = true, alpha = true),
                raster = org.umamo.format.art.LayerRaster(16, 16, rgba),
                sourceAssetId = null,
                sourceSpatialReferenceId = null,
                derived = false,
            )
            val source = io.github.psd2live.document.WorkspaceSourceArt(64, 64, listOf(layer), emptyList())
            val editOverlay = RigEditOverlay(
                parameterEdits = listOf(RigParameterEdit(legacyParamId, "Legacy Swing", -1f, 1f, 0f, created = true)),
                motionClips = listOf(
                    MotionClip("idle", "Idle", curves = listOf(MotionCurve(legacyParamId, listOf(MotionKey(0f, 0f), MotionKey(1f, 1f))))),
                ),
            )
            val cfg = PipelineConfig(
                meshOnly = false,
                generateDeformers = false,
                generatePhysics = false,
                motionBasic = false,
                exportCmo3 = true,
                exportMoc3 = true,
                exportCan3 = true,
                exportJson = true,
                rigEdits = editOverlay,
            )
            val pipeline = PSD2LivePipeline()
            val preview = pipeline.buildPreview(source, cfg)
            val exportDir = tempDir.resolve("model-export")
            val result = pipeline.export(preview, "legacy.psd", exportDir)

            assertTrue(
                result.warnings.none { "characters other than" in it || "Cubism allows" in it },
                "Unexpected invalid Cubism ID warning: ${result.warnings}",
            )
            val mocBytes = java.nio.file.Files.readAllBytes(exportDir.resolve("legacy.moc3"))
            val mocPuppet = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
            assertEquals(emptyList(), RigIdCheck.invalid(mocPuppet))

            val cmoBytes = java.nio.file.Files.readAllBytes(exportDir.resolve("legacy.cmo3"))
            val cmoPuppet = Cmo3Import.fromModelSource(Cmo3.read(cmoBytes).root as CModelSource)
            assertEquals(emptyList(), RigIdCheck.invalid(cmoPuppet))

            val expectedParamIds = mocPuppet.parameters.map { it.id.raw }.toSet()
            assertEquals(expectedParamIds, cmoPuppet.parameters.map { it.id.raw }.toSet())
            assertFalse(legacyParamId in expectedParamIds)

            // ExportService (project_export_target: moc3) 产出完全一致的合规参数 ID。
            val targetDir = tempDir.resolve("target-moc3")
            java.nio.file.Files.createDirectories(targetDir)
            ExportService.writeStaged(preview, Moc3Target, ExportOptions("legacy"), targetDir)
            val targetMoc = Moc3Import.fromMocDocument(Moc3.read(java.nio.file.Files.readAllBytes(targetDir.resolve("legacy.moc3"))), null)
            assertEquals(expectedParamIds, targetMoc.parameters.map { it.id.raw }.toSet())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
