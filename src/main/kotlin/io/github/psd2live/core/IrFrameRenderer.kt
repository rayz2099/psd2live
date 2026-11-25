package io.github.psd2live.core

import io.github.psd2live.format.compile.FrameRenderer
import io.github.psd2live.format.compile.FrameSession
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PlacedRaster
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.model.PhysicsSource
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer

/**
 * Renders the IR for raster exports: geometry from the engine's CPU evaluator, colors from the IR, pixels
 * from the software rasterizer (texture-alpha masks, multiply and screen colors, every color and alpha blend mode,
 * isolated parts as layers), and the editor's pendulum physics stepped between frames.
 */
internal object IrFrameRenderer : FrameRenderer {
	override fun open(ir: RigIR, physics: Boolean): FrameSession {
		val rasterizer = SoftwareRasterizer(ir, AtlasPagePng::pixelsOf)
		val colors = IrColors(ir)
		val geometry = IrGeometryEvaluator.open(ir)
		val engine = if (!physics || ir.physics.groups.isEmpty()) null else PhysicsEngine(
			ir.physics.groups.map { group -> RigPhysicsEdit(group.id, group.name,
				group.inputs.map { PhysicsInput(it.parameter, it.weight, type(it.source), it.reflect) },
				group.outputs.map { PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, type(it.source), it.reflect) },
				group.segments.map { PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
				group.normalization.let { PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax, it.angleMin, it.angleDefault, it.angleMax) }) },
			ir.parameters.associate { it.id to PhysicsEngine.Range(it.min, it.max, it.default) }, ir.physics.fps?.takeIf { it > 0f })
		return object : FrameSession {
			override val simulatesPhysics: Boolean = engine != null

			override fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>?): RasterImage {
				val values = parameters + engine?.step(parameters, deltaSeconds).orEmpty()
				return rasterizer.render(geometry.evaluate(values), colors.at(values), frame, meshes, colors.parts(values))
			}

			// The pose is evaluated once; each mesh is drawn over its own reach, in parallel.
			override fun renderEach(parameters: Map<String, Float>, frame: FrameSpec, meshes: List<String>): List<PlacedRaster?> {
				val values = parameters + engine?.step(parameters, 0f).orEmpty()
				return rasterizer.renderMeshes(geometry.evaluate(values), colors.at(values), frame, meshes, colors.parts(values))
			}

			// Physics and geometry step in frame order; the frames are then drawn in parallel.
			override fun renderSequence(frames: List<Pair<Map<String, Float>, Float>>, frame: FrameSpec): List<RasterImage> {
				val values = frames.map { (parameters, delta) -> parameters + engine?.step(parameters, delta).orEmpty() }
				return rasterizer.renderFrames(values.map { geometry.evaluate(it) to colors.at(it) }, frame, values.map(colors::parts))
			}

			// 先统一步进物理并求值所有帧的几何顶点，求得整段动作与原画布的固定并集包围盒后再并行光栅化，
			// 既完整保留破框/超画布甩动，又避免逐帧镜头缩放抖动。
			override fun renderSequenceFitMotion(
				frames: List<Pair<Map<String, Float>, Float>>,
				canvasWidth: Float,
				canvasHeight: Float,
				padding: Float,
				maxSize: Int,
				background: Int,
			): List<RasterImage> {
				val values = frames.map { (parameters, delta) -> parameters + engine?.step(parameters, delta).orEmpty() }
				val poses = values.map { geometry.evaluate(it) to colors.at(it) }
				val hiddenMeshes = collectHiddenMeshes(ir)
				val staticOpacity = ir.meshes.associate { it.id to it.opacity }
				var minX = 0f
				var minY = 0f
				var maxX = canvasWidth
				var maxY = canvasHeight
				for ((pose, _) in poses) {
					for ((meshId, coords) in pose.positions) {
						if (meshId in hiddenMeshes) continue
						val alpha = pose.opacity[meshId] ?: staticOpacity[meshId] ?: 1f
						if (alpha <= 0f) continue
						var idx = 0
						while (idx + 1 < coords.size) {
							val vx = coords[idx]
							val vy = coords[idx + 1]
							if (vx.isFinite() && vy.isFinite()) {
								if (vx < minX) minX = vx
								if (vx > maxX) maxX = vx
								if (vy < minY) minY = vy
								if (vy > maxY) maxY = vy
							}
							idx += 2
						}
					}
				}
				val spanW = (maxX - minX).coerceAtLeast(1f)
				val spanH = (maxY - minY).coerceAtLeast(1f)
				val padX = spanW * padding
				val padY = spanH * padding
				val spec = FrameSpec.fromRect(minX - padX, minY - padY, spanW + padX * 2f, spanH + padY * 2f, maxSize, background)
				return rasterizer.renderFrames(poses, spec, values.map(colors::parts))
			}

			override fun close() = geometry.close()
		}
	}

	/** 收集隐藏图层及隐藏部件子树内的全部网格，避免不可见辅助图层撑大动作取景框。 */
	private fun collectHiddenMeshes(ir: RigIR): Set<String> {
		val hidden = HashSet<String>()
		val partsById = ir.parts.associateBy { it.id }
		fun hidePart(part: io.github.psd2live.format.model.Part) {
			for (child in part.children) when (child) {
				is io.github.psd2live.format.model.ChildRef.MeshRef -> hidden += child.id
				is io.github.psd2live.format.model.ChildRef.PartRef -> partsById[child.id]?.let(::hidePart)
			}
		}
		ir.parts.filterNot { it.visible }.forEach(::hidePart)
		ir.meshes.filter { !it.visible || it.geometry == null }.forEach { hidden += it.id }
		return hidden
	}

	private fun type(source: PhysicsSource) = if (source == PhysicsSource.ANGLE) PhysicsSourceType.ANGLE else PhysicsSourceType.X
}
