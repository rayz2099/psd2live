package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceLayerTransform
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.document.transform
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RigCoordinateLimitsTest {
    private val paramAngle = ParameterId("ParamAngleX")

    private fun rectWarpPoints(x: Float, y: Float, w: Float, h: Float, rows: Int = 2, cols: Int = 2): FloatArray =
        (0..rows).flatMap { r ->
            (0..cols).flatMap { c -> listOf(x + c * w / cols, y + r * h / rows) }
        }.toFloatArray()

    private fun buildModel(
        canvasSize: Float = 2048f,
        extraDeformers: List<Deformer> = emptyList(),
        extraDrawables: List<Drawable> = emptyList(),
    ): PuppetModel {
        val rootWarp = Deformer.Warp(
            DeformerId("RootWarp"),
            "Root Warp",
            null,
            null,
            2,
            2,
            true,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(0f, 0f, canvasSize, canvasSize)))),
            ),
        )
        val rootWarpPixelChild = Deformer.Warp(
            DeformerId("PixelWarp"),
            "Pixel Warp",
            null,
            null,
            2,
            2,
            true,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(200f, 200f, 1600f, 1600f)))),
            ),
        )
        val rootRot = Deformer.Rotation(
            DeformerId("RootRot"),
            "Root Rot",
            null,
            null,
            0f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(1024f, 1024f, 0f, 1f)))),
        )
        val otherRootWarp = Deformer.Warp(
            DeformerId("OtherRootWarp"),
            "Other Root Warp",
            null,
            null,
            2,
            2,
            true,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(0f, 0f, canvasSize, canvasSize)))),
            ),
        )
        val childWarp = Deformer.Warp(
            DeformerId("ChildWarp"),
            "Child Warp",
            rootWarp.id,
            null,
            2,
            2,
            true,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(0.1f, 0.1f, 0.8f, 0.8f)))),
            ),
        )
        val childRot = Deformer.Rotation(
            DeformerId("ChildRot"),
            "Child Rot",
            rootWarp.id,
            null,
            0f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(0.5f, 0.5f, 0f, 1f)))),
        )
        val rootMesh = Drawable(
            DrawableId("MeshRoot"),
            "Mesh Root",
            null,
            BlendMode.Normal,
            emptyList(),
            DrawableMesh(
                floatArrayOf(400f, 400f, 800f, 400f, 800f, 800f, 400f, 800f),
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                intArrayOf(0, 1, 2, 0, 2, 3),
            ),
            null,
            texturePage = 0,
        )
        val localMesh = Drawable(
            DrawableId("MeshLocal"),
            "Mesh Local",
            childWarp.id,
            BlendMode.Normal,
            emptyList(),
            DrawableMesh(
                floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f),
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                intArrayOf(0, 1, 2, 0, 2, 3),
            ),
            KeyformGrid(
                listOf(KeyformAxis(paramAngle, floatArrayOf(-30f, 0f, 30f))),
                listOf(
                    KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
                    KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8))),
                    KeyformCell(intArrayOf(2), MeshDeltaForm(FloatArray(8))),
                ),
            ),
            texturePage = 0,
        )
        val drawables = listOf(rootMesh, localMesh) + extraDrawables
        return PuppetModel(
            parameters = listOf(Parameter(paramAngle, "Angle X", -30f, 30f, 0f)),
            parts = emptyList(),
            deformers = listOf(rootWarp, otherRootWarp, rootWarpPixelChild, rootRot, childWarp, childRot) + extraDeformers,
            drawables = drawables,
            rootChildren = drawables.map { OrgChild.Drawable(it.id) },
            rootPartId = null,
            canvasWidth = canvasSize,
            canvasHeight = canvasSize,
        ).withDerivedRenderRoot()
    }

    @Test
    fun structureMoveRejectsPixelWarpAndRotationIntoWarpParentWithSpaceLocal() {
        val model = buildModel()
        val movePixelWarp = buildJsonObject {
            put("action", "move"); put("kind", "warp"); put("id", "PixelWarp")
            put("parent_id", "RootWarp"); put("space", "local")
        }
        val errWarp = assertFailsWith<IllegalArgumentException> {
            RigStructureEdits.apply(model, listOf(movePixelWarp))
        }
        assertTrue("space=local" in errWarp.message.orEmpty() || "[0..1]" in errWarp.message.orEmpty())

        val movePixelRot = buildJsonObject {
            put("action", "move"); put("kind", "rotation"); put("id", "RootRot")
            put("parent_id", "RootWarp"); put("space", "local")
        }
        assertFailsWith<IllegalArgumentException> {
            RigStructureEdits.apply(model, listOf(movePixelRot))
        }
    }

    @Test
    fun structureBindLocalRejectsPixelMeshUnderWarpWhileCanvasBindSucceeds() {
        val model = buildModel()
        val bindLocal = buildJsonObject {
            put("action", "bind"); put("kind", "mesh"); put("id", "MeshRoot")
            put("parent_id", "RootWarp"); put("space", "local")
        }
        val err = assertFailsWith<IllegalArgumentException> {
            RigStructureEdits.apply(model, listOf(bindLocal))
        }
        assertTrue("space=canvas" in err.message.orEmpty())

        val bindCanvas = buildJsonObject {
            put("action", "bind"); put("kind", "mesh"); put("id", "MeshRoot")
            put("parent_id", "RootWarp"); put("space", "canvas")
        }
        val carried = RigStructureEdits.apply(model, listOf(bindCanvas))
        val boundMesh = carried.drawables.single { it.id.raw == "MeshRoot" }
        assertEquals(DeformerId("RootWarp"), boundMesh.parentDeformerId)
        assertTrue(boundMesh.mesh!!.positions.all { it in 0f..1f })
    }

    @Test
    fun structureMoveAllowsValidLocalChildWarpAndReplayRemainsBackwardCompatible() {
        val model = buildModel()
        val moveChild = buildJsonObject {
            put("action", "move"); put("kind", "warp"); put("id", "ChildWarp")
            put("parent_id", "OtherRootWarp"); put("space", "local")
        }
        val moved = RigStructureEdits.apply(model, listOf(moveChild))
        assertEquals(DeformerId("OtherRootWarp"), moved.deformers.single { it.id.raw == "ChildWarp" }.parent)

        val historicalMove = buildJsonObject {
            put("action", "move"); put("kind", "warp"); put("id", "PixelWarp")
            put("parent_id", "RootWarp"); put("space", "local")
        }
        val replayed = RigStructureEdits.replay(model, listOf(historicalMove))
        assertEquals(DeformerId("RootWarp"), replayed.deformers.single { it.id.raw == "PixelWarp" }.parent)
    }

    @Test
    fun canvasCreateWarpChildOfDeformerDefaultsToNormalizedUnitSquareUnderWarpParent() {
        val model = buildModel()
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", "NestedUnitWarp")
            put("name", "Nested Unit Warp")
            putJsonArray("meshes") {}
            put("add_to", "child_of_deformer")
            put("parent_id", "RootWarp")
        }
        val created = CanvasEdits.apply(model, cmd)
        val warp = created.deformers.single { it.id.raw == "NestedUnitWarp" } as Deformer.Warp
        val cp = warp.geometryGrid!!.cells.single().form.controlPoints
        assertTrue(cp.all { it in 0f..1f }, "Child warp under Warp parent must default to [0..1], got ${cp.toList()}")
    }

    @Test
    fun canvasCreateWarpValidatesBoundsByParentSpaceAndFixesWrappingChildRotationUnderWarp() {
        val model = buildModel()
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(model, buildJsonObject {
                put("op", "canvas_create_warp"); put("id", "BadChildWarp"); put("name", "Bad")
                putJsonArray("meshes") {}; put("add_to", "child_of_deformer"); put("parent_id", "RootWarp")
                put("bounds", buildJsonObject { put("x", 400); put("y", 200); put("w", 800); put("h", 900) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(model, buildJsonObject {
                put("op", "canvas_create_warp"); put("id", "HugeRootWarp"); put("name", "Huge")
                put("meshes", JsonArray(listOf(JsonPrimitive("MeshRoot"))))
                put("bounds", buildJsonObject { put("x", 0); put("y", 0); put("w", 100000); put("h", 100000) })
            })
        }
        val bigModel = buildModel(canvasSize = 8192f)
        CanvasEdits.apply(bigModel, buildJsonObject {
            put("op", "canvas_create_warp"); put("id", "Valid8KWarp"); put("name", "Valid 8K")
            put("meshes", JsonArray(listOf(JsonPrimitive("MeshRoot"))))
            put("bounds", buildJsonObject { put("x", 0); put("y", 0); put("w", 8192); put("h", 8192) })
        })
        CanvasEdits.apply(model, buildJsonObject {
            put("op", "canvas_create_warp"); put("id", "ValidChild"); put("name", "Valid Child")
            putJsonArray("meshes") {}; put("add_to", "child_of_deformer"); put("parent_id", "RootWarp")
            put("bounds", buildJsonObject { put("x", 0.1f); put("y", 0.1f); put("w", 0.8f); put("h", 0.8f) })
        })

        val wrapped = CanvasEdits.apply(model, buildJsonObject {
            put("op", "canvas_create_warp"); put("id", "WrapRot"); put("name", "Wrap Rot")
            putJsonArray("meshes") {}; put("add_to", "parent_of_deformer"); put("deformer_id", "ChildRot")
        })
        val wrapWarp = wrapped.deformers.single { it.id.raw == "WrapRot" } as Deformer.Warp
        assertTrue(wrapWarp.geometryGrid!!.cells.single().form.controlPoints.all { it in 0f..1f })
        val report = GeometrySafetyEvaluator.evaluate(model, wrapped, blockFoldovers = false)
        assertTrue(report.safe, "Wrapping ChildRot under RootWarp should pass safety gate: ${report.violations}")
    }

    @Test
    fun rotationCreationAndKeyformSetRejectOutOfRangeHandleScaleAngleAndPoints() {
        val model = buildModel()
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(model, buildJsonObject {
                put("op", "canvas_create_rotation"); put("id", "HugeHandle"); put("name", "Bad")
                put("meshes", JsonArray(listOf(JsonPrimitive("MeshRoot"))))
                put("origin", JsonArray(listOf(JsonPrimitive(500), JsonPrimitive(500))))
                put("handle_length", 100000)
            })
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(model, buildJsonObject {
                put("op", "canvas_create_rotation"); put("id", "HugeOrigin"); put("name", "Bad")
                put("meshes", JsonArray(listOf(JsonPrimitive("MeshRoot"))))
                put("origin", JsonArray(listOf(JsonPrimitive(100000), JsonPrimitive(0))))
            })
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(model, buildJsonObject {
                put("op", "canvas_create_rotation"); put("id", "HugeAngle"); put("name", "Bad")
                put("meshes", JsonArray(listOf(JsonPrimitive("MeshRoot"))))
                put("origin", JsonArray(listOf(JsonPrimitive(500), JsonPrimitive(500))))
                put("angle", 5000)
            })
        }

        for (badScale in listOf(0f, -1f, 100f)) {
            assertFailsWith<IllegalArgumentException> {
                applyKeyformSet(
                    model,
                    RigKeyformSetEdit(
                        RigTargetRef(RigTargetKind.ROTATION_DEFORMER, "RootRot"),
                        mapOf("ParamAngleX" to 30f),
                        RigKeyformGeometryEdit(originX = 1024f, originY = 1024f, angle = 15f, scale = badScale),
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            applyKeyformSet(
                model,
                RigKeyformSetEdit(
                    RigTargetRef(RigTargetKind.WARP_DEFORMER, "ChildWarp"),
                    mapOf("ParamAngleX" to 30f),
                    RigKeyformGeometryEdit(controlPoints = rectWarpPoints(0f, 0f, 1024f, 1024f).toList()),
                ),
            )
        }
    }

    @Test
    fun rigDeformRejectsPixelLikeAndExtremeNormalizedValuesWhileAllowingNormalEdits() {
        val model = buildModel()
        val geom = RigGeometryTools.geometry(model, "mesh", "MeshLocal", mapOf("ParamAngleX" to 0f))

        assertFailsWith<IllegalArgumentException> {
            RigGeometryTools.transform(geom, buildJsonArray {
                add(buildJsonObject { put("type", "translate"); put("delta", buildJsonArray { add(500); add(0) }) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            RigGeometryTools.transform(geom, buildJsonArray {
                add(buildJsonObject { put("type", "scale"); put("factors", buildJsonArray { add(100); add(1) }) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            RigGeometryTools.transform(geom, buildJsonArray {
                add(buildJsonObject { put("type", "bend"); put("amount", 50) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            RigGeometryTools.transform(geom, buildJsonArray {
                add(buildJsonObject { put("type", "rotate"); put("degrees", 10000) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            RigGeometryTools.transform(geom, buildJsonArray {
                add(buildJsonObject {
                    put("type", "landmarks")
                    put("from", buildJsonArray { add(buildJsonArray { add(0.5); add(0.5) }) })
                    put("to", buildJsonArray { add(buildJsonArray { add(500); add(500) }) })
                })
            })
        }

        val valid = RigGeometryTools.transform(geom, buildJsonArray {
            add(buildJsonObject { put("type", "translate"); put("delta", buildJsonArray { add(0.1); add(-0.05) }) })
            add(buildJsonObject { put("type", "scale"); put("factors", buildJsonArray { add(1.02); add(0.08) }) })
            add(buildJsonObject { put("type", "rotate"); put("degrees", 360) })
        })
        assertTrue(valid.all(Float::isFinite))
    }

    @Test
    fun layerTransformRejectsExtremeValuesWhileAllowingMirrorAndValidMoves() = runBlocking {
        val size = 128
        val rgba = ByteArray(64 * 64 * 4) { if (it % 4 == 3) -1 else 80 }
        val layer = WorkspaceSourceLayer(
            LayerId("PartA"),
            "PartA",
            "",
            SourceLayerKind.Raster,
            true,
            0,
            LayerBounds(16, 16, 64, 64),
            1f,
            false,
            LayerBlend.Normal,
            ChannelMask.ALL,
            LayerRaster(64, 64, rgba),
            null,
            null,
            false,
        )
        val doc = WorkspaceDocument(
            WorkspaceSourceArt(size, size, listOf(layer), emptyList()),
            emptyMap(),
            emptySet(),
            mapOf("PartA" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS, side = Side.NONE)),
            emptyMap(),
            RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)),
        )
        val model = WorkspacePreviewBuilder().build(doc)

        assertFailsWith<IllegalArgumentException> {
            WorkspaceLayerTransform.apply(doc, model, buildJsonObject {
                put("layer_id", "PartA"); put("scale", 500)
            })
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceLayerTransform.apply(doc, model, buildJsonObject {
                put("layer_id", "PartA"); put("translate", buildJsonArray { add(500000); add(0) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceLayerTransform.apply(doc, model, buildJsonObject {
                put("layer_id", "PartA")
                put("matrix", buildJsonArray { add(100); add(0); add(0); add(100); add(0); add(0) })
            })
        }

        val mirrored = WorkspaceLayerTransform.apply(doc, model, buildJsonObject {
            put("layer_id", "PartA"); put("scale", buildJsonArray { add(-1); add(1) })
            put("translate", buildJsonArray { add(4); add(-4) })
        })
        assertFalse(mirrored.source.layers.single().transform.isIdentity)
    }

    @Test
    fun viewportRectValidationRejectsAbsurdRectAndAcceptsValid8KCanvas() {
        assertFailsWith<IllegalArgumentException> {
            RigCoordinateLimits.validateViewportRect(Bounds(0f, 0f, 1_000_000f, 1_000_000f), 2048f, 2048f)
        }
        RigCoordinateLimits.validateViewportRect(Bounds(0f, 0f, 8192f, 8192f), 8192f, 8192f)
    }

    @Test
    fun geometrySafetyEvaluatorRejectsPixelInWarpAndChainedWorldOverflowWhileToleratingPreexisting() {
        val before = buildModel()
        val explodedChild = before.copy(
            deformers = before.deformers.map { d ->
                if (d.id.raw != "PixelWarp") d else (d as Deformer.Warp).copy(parent = DeformerId("RootWarp"))
            },
        )
        val reparentReport = GeometrySafetyEvaluator.evaluate(before, explodedChild, blockFoldovers = false)
        assertFalse(reparentReport.safe)
        assertTrue(reparentReport.violations.any { it.reason == GeometrySafetyReason.GEOMETRY_COORDINATE_OVERFLOW })

        val amplifiedParent = before.copy(
            deformers = before.deformers.map { d ->
                if (d.id.raw != "RootWarp") d else (d as Deformer.Warp).copy(
                    geometryGrid = KeyformGrid(
                        emptyList(),
                        listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(0f, 0f, 8192f, 8192f)))),
                    ),
                )
            },
        )
        val stretchedChild = amplifiedParent.copy(
            deformers = amplifiedParent.deformers.map { d ->
                if (d.id.raw != "ChildWarp") d else (d as Deformer.Warp).copy(
                    geometryGrid = KeyformGrid(
                        emptyList(),
                        listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(0f, 0f, 6f, 6f)))),
                    ),
                )
            },
        )
        val worldReport = GeometrySafetyEvaluator.evaluate(amplifiedParent, stretchedChild, blockFoldovers = false)
        assertFalse(worldReport.safe)
        assertTrue(worldReport.violations.any { it.reason == GeometrySafetyReason.GEOMETRY_COORDINATE_OVERFLOW })

        val preexistingReport = GeometrySafetyEvaluator.evaluate(explodedChild, explodedChild, blockFoldovers = false)
        assertTrue(preexistingReport.safe)

        val preexistingSlightlyEdited = explodedChild.copy(
            deformers = explodedChild.deformers.map { d ->
                if (d.id.raw != "PixelWarp") d else (d as Deformer.Warp).copy(
                    geometryGrid = KeyformGrid(
                        emptyList(),
                        listOf(KeyformCell(intArrayOf(), WarpLatticeForm(rectWarpPoints(200f, 200f, 1599f, 1599f)))),
                    ),
                )
            },
        )
        val preexistingEditedReport = GeometrySafetyEvaluator.evaluate(explodedChild, preexistingSlightlyEdited, blockFoldovers = false)
        assertTrue(preexistingEditedReport.safe)
    }
}
