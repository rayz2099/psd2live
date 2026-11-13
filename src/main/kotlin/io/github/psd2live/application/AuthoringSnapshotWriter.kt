package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 导出画师原工程（CMO3/PSD）静态结构快照（`$baseName.authoring.json`）。
 * 目的：官方 `.moc3` 运行时包在编译后会抹去变形器网格详情、参数分组及原 Part 显隐关系，
 * 旁路快照让前端检查器能还原原工程制作结构与对齐参考。
 */
internal object AuthoringSnapshotWriter {
	private val json = Json { prettyPrint = false }

	/** 将 [preview] 的静态结构与原工程显隐元数据写入 `directory/baseName.authoring.json`。 */
	fun write(preview: RigPreviewModel, baseName: String, directory: Path): Path {
		Files.createDirectories(directory)
		val payload = buildSnapshot(preview)
		val text = json.encodeToString(JsonObject.serializer(), payload)
		val target = directory.resolve("$baseName.authoring.json")
		Files.writeString(target, text, StandardCharsets.UTF_8)
		return target
	}

	private fun buildSnapshot(preview: RigPreviewModel): JsonObject {
		val puppet = preview.rig.puppet
		val hiddenParts = preview.hiddenEditorPartIds
		val partParents = collectPartParents(puppet)
		val drawableParts = puppet.partByDrawable()
		val groupByParam = collectParamGroups(puppet.parameterTree)
		val paramBindings = LinkedHashMap<String, Int>()

		val deformerItems = buildJsonArray {
			for (deformer in puppet.deformers) {
				val params = collectDeformerParams(deformer)
				for (paramId in params) {
					val cur = paramBindings[paramId] ?: 0
					paramBindings[paramId] = cur + 1
				}
				add(encodeDeformer(deformer, params))
			}
		}

		val drawableItems = buildJsonArray {
			for (drawable in puppet.drawables) {
				val params = LinkedHashSet<String>()
				collectGridParams(drawable.geometryGrid, params)
				collectChannelParams(drawable.channelGrids, params)
				collectBlendParams(drawable.blendShapes, params)
				for (paramId in params) {
					val cur = paramBindings[paramId] ?: 0
					paramBindings[paramId] = cur + 1
				}
				val partId = drawableParts[drawable.id]?.raw
				val deformerId = drawable.parentDeformerId?.raw
				add(buildJsonObject {
					put("id", drawable.id.raw)
					put("name", drawable.name)
					if (partId != null) put("partId", partId) else put("partId", kotlinx.serialization.json.JsonNull)
					if (deformerId != null) put("deformerId", deformerId) else put("deformerId", kotlinx.serialization.json.JsonNull)
					put("visible", drawable.isVisible)
					put("maskedBy", buildJsonArray {
						for (maskId in drawable.maskedBy) add(JsonPrimitive(maskId.raw))
					})
					put("params", encodeStringList(params))
				})
			}
		}

		val partItems = buildJsonArray {
			for (part in puppet.parts) {
				val partParams = LinkedHashSet<String>()
				collectChannelParams(part.channelGrids, partParams)
				collectBlendParams(part.blendShapes, partParams)
				for (paramId in partParams) {
					val cur = paramBindings[paramId] ?: 0
					paramBindings[paramId] = cur + 1
				}
				val parentId = partParents[part.id.raw]
				val isHidden = part.id.raw in hiddenParts || !part.isVisible
				add(buildJsonObject {
					put("id", part.id.raw)
					put("name", part.name)
					if (parentId != null) put("parentId", parentId) else put("parentId", kotlinx.serialization.json.JsonNull)
					put("hidden", isHidden)
				})
			}
		}

		val paramItems = buildJsonArray {
			for (param in puppet.parameters) {
				val id = param.id.raw
				val group = groupByParam[id]
				val count = paramBindings[id] ?: 0
				add(buildJsonObject {
					put("id", id)
					put("name", param.name)
					if (group != null) put("group", group) else put("group", kotlinx.serialization.json.JsonNull)
					put("min", param.min)
					put("max", param.max)
					put("default", param.default)
					put("bindings", count)
				})
			}
		}

		val canvasObj = buildJsonObject {
			put("width", puppet.canvasWidth)
			put("height", puppet.canvasHeight)
		}

		return buildJsonObject {
			put("version", 1)
			put("canvas", canvasObj)
			put("parts", partItems)
			put("deformers", deformerItems)
			put("drawables", drawableItems)
			put("parameters", paramItems)
		}
	}

	private fun collectPartParents(puppet: PuppetModel): Map<String, String?> {
		val parents = LinkedHashMap<String, String?>()
		val partById = puppet.partById
		fun walk(children: List<OrgChild>, parentId: String?) {
			for (child in children) {
				if (child is OrgChild.Part) {
					parents[child.id.raw] = parentId
					val sub = partById[child.id] ?: continue
					walk(sub.children, child.id.raw)
				}
			}
		}
		walk(puppet.rootChildren, null)
		return parents
	}

	private fun collectParamGroups(tree: List<ParameterNode>): Map<String, String> {
		val result = LinkedHashMap<String, String>()
		fun walk(nodes: List<ParameterNode>, path: List<String>) {
			for (node in nodes) {
				when (node) {
					is ParameterNode.Param -> if (path.isNotEmpty()) {
						result[node.id.raw] = path.joinToString(" / ")
					}
					is ParameterNode.Group -> {
						val nextPath = path + node.name
						walk(node.children, nextPath)
					}
				}
			}
		}
		walk(tree, emptyList())
		return result
	}

	private fun collectDeformerParams(deformer: Deformer): Set<String> {
		val params = LinkedHashSet<String>()
		when (deformer) {
			is Deformer.Warp -> {
				collectGridParams(deformer.geometryGrid, params)
				collectChannelParams(deformer.channelGrids, params)
				collectBlendParams(deformer.blendShapes, params)
			}
			is Deformer.Rotation -> {
				collectGridParams(deformer.geometryGrid, params)
				collectChannelParams(deformer.channelGrids, params)
				collectBlendParams(deformer.blendShapes, params)
			}
		}
		return params
	}

	private fun encodeDeformer(deformer: Deformer, params: Set<String>): JsonObject = buildJsonObject {
		val kind = if (deformer is Deformer.Warp) "warp" else "rotation"
		val parentId = deformer.parent?.raw
		val partId = deformer.partId?.raw
		put("id", deformer.id.raw)
		put("name", deformer.name)
		put("kind", kind)
		if (parentId != null) put("parentId", parentId) else put("parentId", kotlinx.serialization.json.JsonNull)
		if (partId != null) put("partId", partId) else put("partId", kotlinx.serialization.json.JsonNull)
		put("visible", deformer.isVisible)
		if (deformer is Deformer.Warp) {
			put("rows", deformer.rows)
			put("cols", deformer.columns)
		}
		put("params", encodeStringList(params))
	}

	private fun collectGridParams(grid: KeyformGrid<*>?, target: MutableSet<String>) {
		val axes = grid?.axes ?: return
		for (axis in axes) {
			target.add(axis.parameterId.raw)
		}
	}

	private fun collectChannelParams(channels: ChannelGrids, target: MutableSet<String>) {
		for (grid in channels.gridsByChannel.values) {
			collectGridParams(grid, target)
		}
	}

	private fun collectBlendParams(bindings: List<BlendShapeBinding<*>>, target: MutableSet<String>) {
		for (binding in bindings) {
			target.add(binding.parameterId.raw)
		}
	}

	private fun encodeStringList(values: Collection<String>): JsonArray = buildJsonArray {
		for (v in values) add(JsonPrimitive(v))
	}
}
