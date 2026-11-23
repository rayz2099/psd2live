package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.ProjectRepository
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Application orchestration for CLI/tool inputs; the core exporter only consumes a prepared model. */
internal object WorkspaceModelLoader {
    suspend fun load(path: Path): RigPreviewModel = when {
        path.fileName.toString().endsWith(".psd2live", true) -> ProjectRepository().open(path).use { opened ->
            WorkspacePreviewBuilder().build(opened.history.head().snapshot)
        }
        path.fileName.toString().endsWith(".cmo3", true) -> loadCmo3(path)
        path.fileName.toString().endsWith(".psd", true) -> PSD2LivePipeline().buildPreview(path)
        else -> throw IllegalArgumentException("Export input must be a .psd2live project, .cmo3 model, or .psd file: $path")
    }

    /**
     * 加载 CMO3 工程构造只读预览；已有图集时跳过 PSD 栅格重构与二次重排，
     * 对无图集的未打包原工程则统一打包紧致图集并重绑 UV，同时保留原工程 Part/Drawable 显隐状态（仅保留被蒙版引用的隐藏图层）。
     */
    private fun loadCmo3(path: Path): RigPreviewModel {
        val bytes = Files.readAllBytes(path)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val doc = Cmo3ModelImport.read(bytes)
        val w = doc.puppet.canvasWidth.toInt().coerceAtLeast(1)
        val h = doc.puppet.canvasHeight.toInt().coerceAtLeast(1)
        val box = Bounds(0f, 0f, w.toFloat(), h.toFloat())
        val anchors = RigAnchors(box, box, box, box.centerX, box.centerY, box.centerX, box.bottom, box.centerY, box.bottom)
        val emptyImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val layerIds = doc.puppet.drawables.associate { it.id.raw to it.id.raw }
        val hiddenPartIds = doc.puppet.parts
            .filter { part -> !part.isSketch && !part.isVisible }
            .mapTo(LinkedHashSet()) { part -> part.id.raw }
        val fps = doc.fps ?: if (doc.physics.isNotEmpty()) RigEditOverlay.UNLIMITED_FPS else RigEditOverlay.DEFAULT_PHYSICS_FPS
        val overlay = RigEditOverlay(
            importedCmo3 = encoded,
            importedLayerIds = layerIds,
            skeleton = SkeletonSpec.Disabled,
            physicsEdits = doc.physics,
            physicsOrder = doc.physics.map { it.id },
            physicsFps = fps,
            motionClips = emptyList(),
        )
        val cfg = PipelineConfig(
            rigEdits = overlay,
            runtimeTarget = doc.puppet.runtimeTarget,
            mouthOutlineEnabled = false,
            generateDeformers = false,
            meshOnly = false,
            seeThroughBrows = false,
            seeThroughEyes = false,
            physicsFrontHair = false,
            physicsBackHair = false,
            physicsEyeJelly = false,
            generatePhysics = true,
            motionBasic = false,
            motionIdle = false,
            motionBlink = false,
            motionNod = false,
            motionShake = false,
            motionSkeleton = false,
            exportHiddenParts = false,
            exportHiddenDrawables = false,
        )

        val (finalAnalysis, finalAtlas, finalPages, finalPuppet) = prepareAtlasAndPuppet(
            doc, doc.puppet.parts, layerIds, cfg, w, h, anchors, emptyImg,
        )
        val bounds = finalPuppet.drawables.mapNotNull { d ->
            val pts = d.mesh?.positions?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            d.id.raw to boundsOf(pts)
        }.toMap()
        val halfW = w / 2f
        val halfH = h / 2f
        val baseRig = BuiltRig(finalPuppet, finalPages, bounds, layerIds, halfW, halfH, halfW, halfH, emptyList())
        val seeThrough = SeeThroughLayers.Settings.of(cfg)
        val rig = baseRig.withRigEdits(overlay, emptyMap(), emptyMap(), seeThrough)
        val pipe = PSD2LivePipeline()
        val bundle = CubismRuntimeBundle.deferred { pipe.buildRuntimeBundle("psd2live-preview", finalAnalysis, finalAtlas, rig, cfg).first }
        val sources = PreviewRigSources.of(baseRig)
        return RigPreviewModel(
            finalAnalysis,
            finalAtlas,
            rig,
            cfg,
            bundle,
            sources,
            hiddenEditorPartIds = hiddenPartIds,
        )
    }

    private data class PreparedCmo3(
        val analysis: PipelineAnalysis,
        val atlas: PackedAtlas,
        val pages: Map<String, Int>,
        val puppet: org.umamo.runtime.model.PuppetModel,
    )

    /** 若 CMO3 未自带图集（CModelTextureAtlas=0），则按网格重建紧致图层并打包图集与重算 UV。 */
    private fun prepareAtlasAndPuppet(
        doc: Cmo3ModelImport.Document,
        parts: List<org.umamo.runtime.model.Part>,
        layerIds: Map<String, String>,
        cfg: PipelineConfig,
        w: Int,
        h: Int,
        anchors: RigAnchors,
        emptyImg: BufferedImage,
    ): PreparedCmo3 {
        val hasAtlas = doc.puppet.atlas.pages.isNotEmpty()
        if (hasAtlas) {
            val boundDrawables = doc.puppet.drawables.map { drawable ->
                val mappedPage = doc.pages[drawable.id.raw]
                if (mappedPage != null && drawable.texturePage < 0) drawable.copy(texturePage = mappedPage) else drawable
            }
            val emptySource = object : SourceArt {
                override val widthPx = w
                override val heightPx = h
                override val layers: List<SourceLayer> = emptyList()
                override val groups: List<SourceGroup> = emptyList()
            }
            val analysis = PipelineAnalysis(emptySource, emptyList(), anchors, emptyList(), emptyImg)
            val puppet = doc.puppet.copy(parts = parts, drawables = boundDrawables)
            return PreparedCmo3(analysis, doc.atlas, doc.pages, puppet)
        }

        val sourceLayers = Cmo3ModelImport.layers(doc, layerIds)
        val packedSource = object : SourceArt {
            override val widthPx = w
            override val heightPx = h
            override val layers: List<SourceLayer> = sourceLayers
            override val groups: List<SourceGroup> = emptyList()
        }
        val classified = sourceLayers.map { src ->
            val cls = CharacterAnalyzer.classify(src, cfg)
            val safeOpaque = cls.opaquePixels.coerceAtLeast(1)
            cls.copy(opaquePixels = safeOpaque)
        }
        if (classified.isEmpty()) {
            val analysis = PipelineAnalysis(packedSource, emptyList(), anchors, emptyList(), emptyImg)
            val puppet = doc.puppet.copy(parts = parts)
            return PreparedCmo3(analysis, doc.atlas, doc.pages, puppet)
        }

        val packedAtlas = AtlasLayout.pack(classified, cfg)
        val packedById = classified.associateBy { it.source.id.raw }
        val packedPages = LinkedHashMap<String, Int>()
        val drawables = doc.puppet.drawables.map { drawable ->
            val layerId = layerIds[drawable.id.raw] ?: return@map drawable
            val layer = packedById[layerId] ?: return@map drawable
            val placement = packedAtlas.placementByLayerId[layerId] ?: return@map drawable
            packedPages[drawable.id.raw] = placement.page
            val tileId = PuppetSourceAtlas.tileIdFor(layerId, Cmo3ModelImport.PAINT_SOURCE_ID)
            val mesh = drawable.mesh ?: return@map drawable.copy(texturePage = placement.page, atlasTileId = tileId)
            val pageImg = packedAtlas.pages[placement.page].image
            val texture = LayerTexture.packed(layer, placement, pageImg.width, pageImg.height)
            val newUvs = texture.toUvs(mesh.positions)
            val newMesh = org.umamo.runtime.model.DrawableMesh(mesh.positions, newUvs, mesh.indices)
            drawable.copy(mesh = newMesh, texturePage = placement.page, atlasTileId = tileId)
        }
        val analysis = PipelineAnalysis(packedSource, classified, anchors, emptyList(), emptyImg)
        val builtAtlasPair = PuppetSourceAtlas.build(analysis, packedAtlas, sourceIdRaw = Cmo3ModelImport.PAINT_SOURCE_ID)
        val puppet = doc.puppet.copy(
            parts = parts,
            drawables = drawables,
            atlas = builtAtlasPair.first,
            sources = builtAtlasPair.second,
        )
        return PreparedCmo3(analysis, packedAtlas, packedPages, puppet)
    }

    private fun boundsOf(pts: FloatArray): Bounds {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        return Bounds(minX, minY, maxX, maxY)
    }
}
