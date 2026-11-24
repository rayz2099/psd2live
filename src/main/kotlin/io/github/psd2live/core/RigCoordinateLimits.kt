package io.github.psd2live.core

import org.umamo.render.eval.rotationBlendDeltas
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs

/**
 * Canvas-aware coordinate-space and deformation parameter limits.
 *
 * Prevents parent-space mismatches (e.g. writing canvas pixel coordinates into a parent Warp's
 * normalized `[0..1]` lattice) and absurd scalar values that trigger bounding-box explosions
 * or OOM during deformation evaluation and software rasterization.
 */
internal object RigCoordinateLimits {
    const val MAX_CANVAS_SIDE = 8192f

    /** Local lattice coordinate bound under a Deformer.Warp parent (`[0..1]` nominal, allowing 8x overhang). */
    const val MAX_WARP_LOCAL_COORD = 8f

    /** Local width/height bound for a Warp directly inside a Deformer.Warp parent. */
    const val MAX_WARP_LOCAL_EXTENT = 10f

    const val MIN_ROTATION_SCALE = 1e-4f
    const val MAX_ROTATION_SCALE = 16f
    const val MAX_ANGLE_DEGREES = 3600f

    /** Normalized target-bounds bounds for `rig_deform` (NOT canvas pixels). */
    const val MAX_DEFORM_TRANSLATE_DELTA = 8f
    const val MIN_DEFORM_SCALE_FACTOR = 1e-4f
    const val MAX_DEFORM_SCALE_FACTOR = 16f
    const val MAX_DEFORM_PIVOT_COORD = 8f
    const val MAX_DEFORM_BEND_AMOUNT = 8f
    const val MAX_DEFORM_LANDMARK_COORD = 8f

    const val MIN_LAYER_TRANSFORM_SCALE = 1e-4f
    const val MAX_LAYER_TRANSFORM_SCALE = 16f
    const val MAX_LAYER_MATRIX_LINEAR = 32f

    fun canvasSpan(model: PuppetModel): Float {
        if (model.canvasWidth.isFinite() && model.canvasHeight.isFinite() &&
            model.canvasWidth > 0f && model.canvasHeight > 0f
        ) {
            return maxOf(model.canvasWidth, model.canvasHeight).coerceIn(512f, MAX_CANVAS_SIDE * 2f)
        }
        var maxAbs = 0f
        for (drawable in model.drawables) {
            if (drawable.parentDeformerId != null) continue
            val positions = drawable.mesh?.positions ?: continue
            for (v in positions) {
                if (v.isFinite()) maxAbs = maxOf(maxAbs, abs(v))
            }
        }
        for (deformer in model.deformers) {
            if (deformer.parent != null) continue
            when (deformer) {
                is Deformer.Warp -> deformer.geometryGrid?.cells.orEmpty().forEach { cell ->
                    for (v in cell.form.controlPoints) {
                        if (v.isFinite()) maxAbs = maxOf(maxAbs, abs(v))
                    }
                }
                is Deformer.Rotation -> deformer.geometryGrid?.cells.orEmpty().forEach { cell ->
                    if (cell.form.originX.isFinite()) maxAbs = maxOf(maxAbs, abs(cell.form.originX))
                    if (cell.form.originY.isFinite()) maxAbs = maxOf(maxAbs, abs(cell.form.originY))
                }
            }
        }
        return maxOf(maxAbs * 2f, 4096f).coerceAtMost(MAX_CANVAS_SIDE * 2f)
    }

    fun maxCanvasCoord(canvasSpan: Float): Float =
        maxOf(canvasSpan * 4f, 8192f).coerceAtMost(32_768f)

    fun maxCanvasExtent(canvasSpan: Float): Float =
        maxOf(canvasSpan * 4f, 8192f).coerceAtMost(32_768f)

    fun maxWorldCoord(canvasSpan: Float): Float =
        maxOf(canvasSpan * 6f, 16_384f).coerceAtMost(65_536f)

    fun parentIsWarp(model: PuppetModel, parentId: DeformerId?): Boolean =
        parentId != null && model.deformers.any { it.id == parentId && it is Deformer.Warp }

    fun localCoordLimit(model: PuppetModel, parentId: DeformerId?): Float =
        if (parentIsWarp(model, parentId)) MAX_WARP_LOCAL_COORD else maxCanvasCoord(canvasSpan(model))

    fun localExtentLimit(model: PuppetModel, parentId: DeformerId?): Float =
        if (parentIsWarp(model, parentId)) MAX_WARP_LOCAL_EXTENT else maxCanvasExtent(canvasSpan(model))

    fun validateWarpBounds(model: PuppetModel, parentId: DeformerId?, x: Float, y: Float, w: Float, h: Float) {
        require(listOf(x, y, w, h).all(Float::isFinite) && w > 1e-6f && h > 1e-6f) {
            "Warp bounds must contain finite numbers and have positive width and height"
        }
        val underWarp = parentIsWarp(model, parentId)
        val coordLimit = localCoordLimit(model, parentId)
        val extentLimit = localExtentLimit(model, parentId)
        val hint = if (underWarp) {
            " Child warps under a Warp parent (${parentId!!.raw}) use normalized [0..1] lattice space, not canvas pixels; omit bounds to inherit [0, 0, 1, 1]."
        } else {
            " Canvas-space warps are bounded by the canvas size (coord limit +/-$coordLimit, extent limit $extentLimit)."
        }
        require(w <= extentLimit && h <= extentLimit) {
            "Warp bounds size ($w x $h) exceeds limit $extentLimit.$hint"
        }
        val right = x + w
        val bottom = y + h
        require(listOf(x, y, right, bottom).all { it.isFinite() && abs(it) <= coordLimit }) {
            "Warp bounds [$x, $y, $w, $h] exceed parent coordinate limit +/-$coordLimit.$hint"
        }
    }

    fun validateRotationCreation(
        model: PuppetModel,
        parentId: DeformerId?,
        originX: Float,
        originY: Float,
        baseAngle: Float,
        handleLength: Float?,
    ) {
        require(listOf(originX, originY, baseAngle).all(Float::isFinite)) {
            "Rotation origin and angle must be finite"
        }
        val coordLimit = localCoordLimit(model, parentId)
        val underWarp = parentIsWarp(model, parentId)
        require(abs(originX) <= coordLimit && abs(originY) <= coordLimit) {
            val hint = if (underWarp) " Under Warp parent ${parentId!!.raw}, local origin uses normalized [0..1] units." else ""
            "Rotation origin ($originX, $originY) exceeds coordinate limit +/-$coordLimit.$hint"
        }
        require(abs(baseAngle) <= MAX_ANGLE_DEGREES) {
            "Rotation angle $baseAngle exceeds +/-$MAX_ANGLE_DEGREES degrees"
        }
        if (handleLength != null) {
            val maxHandle = maxCanvasExtent(canvasSpan(model))
            require(handleLength.isFinite() && handleLength > 1e-6f && handleLength <= maxHandle) {
                "Rotation handle_length $handleLength must be within (0, $maxHandle]"
            }
        }
    }

    fun validateRotationPivotForm(
        model: PuppetModel,
        parentId: DeformerId?,
        targetLabel: String,
        originX: Float,
        originY: Float,
        angle: Float,
        scale: Float,
    ) {
        require(listOf(originX, originY, angle, scale).all(Float::isFinite)) {
            "$targetLabel rotation keyform parameters must be finite"
        }
        val coordLimit = localCoordLimit(model, parentId)
        require(abs(originX) <= coordLimit && abs(originY) <= coordLimit) {
            val spaceNote = if (parentIsWarp(model, parentId)) "parent Warp normalized [0..1] space" else "canvas space"
            "$targetLabel origin ($originX, $originY) exceeds $spaceNote limit +/-$coordLimit"
        }
        require(abs(angle) <= MAX_ANGLE_DEGREES) {
            "$targetLabel angle $angle exceeds +/-$MAX_ANGLE_DEGREES degrees"
        }
        require(scale in MIN_ROTATION_SCALE..MAX_ROTATION_SCALE) {
            "$targetLabel scale $scale must be within $MIN_ROTATION_SCALE..$MAX_ROTATION_SCALE"
        }
    }

    fun validateLocalPoints(
        model: PuppetModel,
        parentId: DeformerId?,
        targetLabel: String,
        points: FloatArray,
    ) {
        val coordLimit = localCoordLimit(model, parentId)
        val underWarp = parentIsWarp(model, parentId)
        for (i in points.indices) {
            val v = points[i]
            require(v.isFinite() && abs(v) <= coordLimit) {
                val spaceNote = if (underWarp) {
                    "parent Warp ${parentId!!.raw} normalized [0..1] local space"
                } else {
                    "canvas space"
                }
                "$targetLabel coordinate $v at index $i exceeds $spaceNote limit +/-$coordLimit"
            }
        }
    }

    fun validateDeformerMove(model: PuppetModel, deformer: Deformer, newParentId: DeformerId?) {
        if (deformer.parent == newParentId) return
        val underWarp = parentIsWarp(model, newParentId)
        val coordLimit = localCoordLimit(model, newParentId)
        val extentLimit = localExtentLimit(model, newParentId)
        val guidance = if (underWarp) {
            " Moving a deformer with space=local into parent Warp ${newParentId!!.raw} does NOT convert canvas pixels into [0..1] parent-local space; create top-down under ${newParentId.raw} or wrap with canvas(mode='warp', add_to='parent_of_deformer')."
        } else {
            ""
        }
        when (deformer) {
            is Deformer.Warp -> {
                val label = "warp:${deformer.id.raw}"
                fun checkControlPoints(cp: FloatArray, source: String) {
                    require(cp.all(Float::isFinite)) { "$label has non-finite control points ($source)" }
                    var minX = Float.POSITIVE_INFINITY
                    var maxX = Float.NEGATIVE_INFINITY
                    var minY = Float.POSITIVE_INFINITY
                    var maxY = Float.NEGATIVE_INFINITY
                    for (i in 0 until cp.size - 1 step 2) {
                        val x = cp[i]
                        val y = cp[i + 1]
                        require(abs(x) <= coordLimit && abs(y) <= coordLimit) {
                            "Cannot move $label under ${newParentId?.raw ?: "root"}: control point ($x, $y) exceeds limit +/-$coordLimit.$guidance"
                        }
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                    if (minX <= maxX) {
                        val w = maxX - minX
                        val h = maxY - minY
                        require(w <= extentLimit && h <= extentLimit) {
                            "Cannot move $label under ${newParentId?.raw ?: "root"}: lattice extent ($w x $h) exceeds limit $extentLimit.$guidance"
                        }
                    }
                }
                deformer.geometryGrid?.cells.orEmpty().forEach { cell ->
                    checkControlPoints(cell.form.controlPoints, "keyform")
                }
                deformer.blendShapes.forEach { binding ->
                    binding.forms.filterNotNull().forEach { form ->
                        require(form.controlPoints.all { it.isFinite() && abs(it) <= coordLimit * 2f }) {
                            "Cannot move $label under ${newParentId?.raw ?: "root"}: blend shape control points exceed +/-${coordLimit * 2f}.$guidance"
                        }
                    }
                }
            }
            is Deformer.Rotation -> {
                val label = "rotation:${deformer.id.raw}"
                deformer.geometryGrid?.cells.orEmpty().forEach { cell ->
                    val f = cell.form
                    require(listOf(f.originX, f.originY, f.angle, f.scale).all(Float::isFinite)) {
                        "$label has non-finite pivot values"
                    }
                    require(abs(f.originX) <= coordLimit && abs(f.originY) <= coordLimit) {
                        "Cannot move $label under ${newParentId?.raw ?: "root"}: origin (${f.originX}, ${f.originY}) exceeds limit +/-$coordLimit.$guidance"
                    }
                    require(abs(f.angle) <= MAX_ANGLE_DEGREES) {
                        "Cannot move $label: angle ${f.angle} exceeds +/-$MAX_ANGLE_DEGREES degrees"
                    }
                    require(f.scale in MIN_ROTATION_SCALE..MAX_ROTATION_SCALE) {
                        "Cannot move $label: scale ${f.scale} must be within $MIN_ROTATION_SCALE..$MAX_ROTATION_SCALE"
                    }
                }
                deformer.blendShapes.forEach { binding ->
                    binding.forms.filterNotNull().forEach { f ->
                        require(listOf(f.originX, f.originY, f.angle, f.scale).all(Float::isFinite)) {
                            "$label blend shape has non-finite values"
                        }
                        require(abs(f.originX) <= coordLimit * 2f && abs(f.originY) <= coordLimit * 2f) {
                            "Cannot move $label under ${newParentId?.raw ?: "root"}: blend origin exceeds limit.$guidance"
                        }
                    }
                }
            }
        }
    }

    fun validateDrawableBindLocal(model: PuppetModel, drawable: Drawable, newParentId: DeformerId?) {
        if (drawable.parentDeformerId == newParentId) return
        val mesh = drawable.mesh ?: return
        val underWarp = parentIsWarp(model, newParentId)
        val coordLimit = localCoordLimit(model, newParentId)
        val hint = if (underWarp) " Use space=canvas to carry the mesh cleanly into parent Warp ${newParentId!!.raw}." else ""
        val label = "mesh:${drawable.id.raw}"
        for (i in mesh.positions.indices) {
            val v = mesh.positions[i]
            require(v.isFinite() && abs(v) <= coordLimit) {
                "Cannot bind $label with space=local under ${newParentId?.raw ?: "root"}: rest position $v exceeds limit +/-$coordLimit.$hint"
            }
        }
        drawable.geometryGrid?.cells.orEmpty().forEach { cell ->
            val deltas = cell.form.positionDeltas
            if (deltas.size == mesh.positions.size) {
                for (i in deltas.indices) {
                    val v = mesh.positions[i] + deltas[i]
                    require(v.isFinite() && abs(v) <= coordLimit) {
                        "Cannot bind $label with space=local under ${newParentId?.raw ?: "root"}: keyed position $v exceeds limit +/-$coordLimit.$hint"
                    }
                }
            }
        }
        drawable.blendShapes.forEach { binding ->
            binding.forms.filterNotNull().forEach { form ->
                val deltas = form.positionDeltas
                require(deltas.all { it.isFinite() && abs(it) <= coordLimit * 2f }) {
                    "Cannot bind $label with space=local under ${newParentId?.raw ?: "root"}: blend deltas exceed limit.$hint"
                }
            }
        }
    }

    fun validateDeformOp(
        type: String,
        delta: List<Float>,
        factors: List<Float>,
        pivot: List<Float>,
        amount: Float,
        degrees: Float,
        curve: List<Float>,
        root: List<Float>,
        tip: List<Float>,
        from: List<List<Float>>,
        to: List<List<Float>>,
    ) {
        when (type) {
            "translate" -> {
                require(delta.all { it.isFinite() && abs(it) <= MAX_DEFORM_TRANSLATE_DELTA }) {
                    "rig_deform translate.delta $delta must be normalized fractions of target bounds within +/-$MAX_DEFORM_TRANSLATE_DELTA (not canvas pixels)"
                }
            }
            "scale" -> {
                require(factors.all { it.isFinite() && it in MIN_DEFORM_SCALE_FACTOR..MAX_DEFORM_SCALE_FACTOR }) {
                    "rig_deform scale.factors $factors must be within $MIN_DEFORM_SCALE_FACTOR..$MAX_DEFORM_SCALE_FACTOR"
                }
                require(pivot.all { it.isFinite() && abs(it) <= MAX_DEFORM_PIVOT_COORD }) {
                    "rig_deform scale.pivot $pivot must be within +/-$MAX_DEFORM_PIVOT_COORD"
                }
            }
            "rotate" -> {
                require(degrees.isFinite() && abs(degrees) <= MAX_ANGLE_DEGREES) {
                    "rig_deform rotate.degrees $degrees must be within +/-$MAX_ANGLE_DEGREES"
                }
                require(pivot.all { it.isFinite() && abs(it) <= MAX_DEFORM_PIVOT_COORD }) {
                    "rig_deform rotate.pivot $pivot must be within +/-$MAX_DEFORM_PIVOT_COORD"
                }
            }
            "bend" -> {
                require(amount.isFinite() && abs(amount) <= MAX_DEFORM_BEND_AMOUNT) {
                    "rig_deform bend.amount $amount must be within +/-$MAX_DEFORM_BEND_AMOUNT"
                }
            }
            "curve" -> {
                require(curve.all { it.isFinite() && abs(it) <= MAX_DEFORM_BEND_AMOUNT }) {
                    "rig_deform curve.controls $curve must be within +/-$MAX_DEFORM_BEND_AMOUNT"
                }
            }
            "sway", "arc" -> {
                require(degrees.isFinite() && abs(degrees) <= MAX_ANGLE_DEGREES) {
                    "rig_deform $type.degrees $degrees must be within +/-$MAX_ANGLE_DEGREES"
                }
                require(root.all { it.isFinite() && abs(it) <= MAX_DEFORM_PIVOT_COORD } &&
                    tip.all { it.isFinite() && abs(it) <= MAX_DEFORM_PIVOT_COORD }
                ) {
                    "rig_deform $type root/tip must be within +/-$MAX_DEFORM_PIVOT_COORD"
                }
            }
            "landmarks" -> {
                require((from + to).all { pair -> pair.all { it.isFinite() && abs(it) <= MAX_DEFORM_LANDMARK_COORD } }) {
                    "rig_deform landmarks coordinates must be normalized fractions within +/-$MAX_DEFORM_LANDMARK_COORD"
                }
            }
        }
    }

    fun validateViewportRect(rect: Bounds, canvasWidth: Float, canvasHeight: Float) {
        require(
            listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite) &&
                rect.width > 0f && rect.height > 0f,
        ) { "Canvas rectangle must contain finite coordinates and have positive width and height" }
        val span = maxOf(
            if (canvasWidth.isFinite() && canvasWidth > 0f) canvasWidth else 0f,
            if (canvasHeight.isFinite() && canvasHeight > 0f) canvasHeight else 0f,
            512f,
        ).coerceAtMost(MAX_CANVAS_SIDE * 2f)
        val coordLimit = maxCanvasCoord(span)
        val extentLimit = maxCanvasExtent(span)
        require(rect.width <= extentLimit && rect.height <= extentLimit) {
            "Canvas rectangle size (${rect.width} x ${rect.height}) exceeds viewport extent limit $extentLimit"
        }
        require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { abs(it) <= coordLimit }) {
            "Canvas rectangle [${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}] exceeds viewport coordinate limit +/-$coordLimit"
        }
    }

    fun validateLayerTransform(
        canvasWidth: Float,
        canvasHeight: Float,
        tx: Float,
        ty: Float,
        sx: Float,
        sy: Float,
        px: Float,
        py: Float,
        rotateDeg: Float,
    ) {
        val span = maxOf(
            if (canvasWidth.isFinite() && canvasWidth > 0f) canvasWidth else 0f,
            if (canvasHeight.isFinite() && canvasHeight > 0f) canvasHeight else 0f,
            512f,
        ).coerceAtMost(MAX_CANVAS_SIDE * 2f)
        val coordLimit = maxCanvasCoord(span)
        require(listOf(tx, ty, sx, sy, px, py, rotateDeg).all(Float::isFinite)) {
            "layer_transform parameters must be finite"
        }
        require(abs(sx) in MIN_LAYER_TRANSFORM_SCALE..MAX_LAYER_TRANSFORM_SCALE &&
            abs(sy) in MIN_LAYER_TRANSFORM_SCALE..MAX_LAYER_TRANSFORM_SCALE
        ) {
            "layer_transform scale ($sx, $sy) magnitude must be within $MIN_LAYER_TRANSFORM_SCALE..$MAX_LAYER_TRANSFORM_SCALE"
        }
        require(listOf(tx, ty, px, py).all { abs(it) <= coordLimit }) {
            "layer_transform translate/pivot exceeds canvas coordinate limit +/-$coordLimit"
        }
        require(abs(rotateDeg) <= MAX_ANGLE_DEGREES) {
            "layer_transform rotate $rotateDeg exceeds +/-$MAX_ANGLE_DEGREES degrees"
        }
    }

    fun validateLayerTransformMatrix(canvasWidth: Float, canvasHeight: Float, matrix: List<Float>) {
        require(matrix.size == 6 && matrix.all(Float::isFinite)) {
            "layer_transform matrix must contain 6 finite numbers"
        }
        val a = matrix[0]; val b = matrix[1]; val c = matrix[2]
        val d = matrix[3]; val e = matrix[4]; val f = matrix[5]
        require(listOf(a, b, c, d).all { abs(it) <= MAX_LAYER_MATRIX_LINEAR }) {
            "layer_transform matrix linear entries exceed +/-$MAX_LAYER_MATRIX_LINEAR"
        }
        val det = a.toDouble() * d.toDouble() - b.toDouble() * c.toDouble()
        require(abs(det) >= 1e-8) {
            "layer_transform matrix is singular or collapsed"
        }
        val span = maxOf(
            if (canvasWidth.isFinite() && canvasWidth > 0f) canvasWidth else 0f,
            if (canvasHeight.isFinite() && canvasHeight > 0f) canvasHeight else 0f,
            512f,
        ).coerceAtMost(MAX_CANVAS_SIDE * 2f)
        val translationLimit = maxWorldCoord(span) * 2f
        require(abs(e) <= translationLimit && abs(f) <= translationLimit) {
            "layer_transform matrix translation ($e, $f) exceeds +/-$translationLimit"
        }
    }

    /** Evaluates exact `(angle, scale)` of [rotation] at [pose], or null if its grid has no matching corners. */
    fun rotationStateAt(model: PuppetModel, rotation: Deformer.Rotation, pose: Map<String, Float>): Pair<Float, Float>? {
        val params = model.parameters.associateBy { it.id.raw }
        val defaults = model.parameters.associate { it.id to it.default }
        val paramValue: (ParameterId) -> Float = { id -> pose[id.raw] ?: defaults[id] ?: 0f }
        val defaultValue: (ParameterId) -> Float = { id -> defaults[id] ?: 0f }
        val grid = rotation.geometryGrid
        var angle = 0f
        var scale = if (grid == null) 1f else 0f
        if (grid != null) {
            val cells = cellsByLinearIndex(grid)
            val corners = gridCorners(grid) { p -> pose[p.raw] ?: params[p.raw]?.default ?: 0f } ?: return null
            for (corner in corners) {
                val form = cells[corner.linearIndex]?.form ?: continue
                angle += corner.weight * form.angle
                scale += corner.weight * form.scale
            }
        }
        rotationBlendDeltas(rotation, paramValue, defaultValue)?.let { delta ->
            angle += delta.angle
            scale += delta.scale
        }
        return angle to scale
    }
}
