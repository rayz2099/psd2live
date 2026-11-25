package io.github.psd2live.targets.cubism

import io.github.psd2live.format.model.*
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Cubism 导出阶段的确定性 ID 清洗器。
 * 原因：历史 `.psd2live` 工程及旧修改器可能保留 `modifier:<id>:<hex>` 等含冒号或超长字符的内部 ID；
 * 在进入 `.moc3`/`.cmo3`/`.can3`/`.motion3`/`.physics3`/`.cdi3`/`.vtube.json` 导出前按命名空间做确定性无碰撞映射，
 * 既保护旧工程内部历史与引用兼容性，又确保所有 Cubism 产物跨文件 ID 完全一致且零非法字符告警。
 */
public object CubismIdSanitizer {
	// MOC3 固定 64 字节 C 字符串槽位需留 1 字节给 NUL 终止符，因此合法 Cubism ID 上限按 63 字节对齐，避免 .moc3 与 .cmo3 截断不一致。
	private const val MAX_ID_LENGTH = 63
	private val idPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,62}")

	public data class Maps(
		val parameters: Map<String, String> = emptyMap(),
		val paramGroups: Map<String, String> = emptyMap(),
		val parts: Map<String, String> = emptyMap(),
		val deformers: Map<String, String> = emptyMap(),
		val meshes: Map<String, String> = emptyMap(),
		val physicsGroups: Map<String, String> = emptyMap(),
		val glues: Map<String, String> = emptyMap(),
		val hitAreas: Map<String, String> = emptyMap(),
	) {
		public val isEmpty: Boolean
			get() = parameters.isEmpty() && paramGroups.isEmpty() && parts.isEmpty() && deformers.isEmpty() &&
				meshes.isEmpty() && physicsGroups.isEmpty() && glues.isEmpty() && hitAreas.isEmpty()
	}

	public data class Result(val ir: RigIR, val maps: Maps)

	public fun isValid(id: String): Boolean = idPattern.matches(id)

	/** 仅当存在不符合 Cubism 字母表的 ID 时才构建映射并重写 [ir]，合规模型零拷贝直通。 */
	public fun sanitize(ir: RigIR): RigIR = sanitizeWithMaps(ir).ir

	/** 返回清洗后的 [RigIR] 及各命名空间映射表，供宿主在导出报告与回读校验中保持一致。 */
	public fun sanitizeWithMaps(ir: RigIR): Result {
		val paramMap = buildNamespaceMap(collectParameters(ir))
		val groupMap = buildNamespaceMap(collectParamGroups(ir.parameterTree))
		val partMap = buildNamespaceMap(ir.parts.map { it.id })
		val deformerIds = ir.deformers.map { it.id } + ir.advanced.virtualBones.map { it.id }
		val deformerMap = buildNamespaceMap(deformerIds)
		val meshMap = buildNamespaceMap(ir.meshes.map { it.id })
		val physicsMap = buildNamespaceMap(ir.physics.groups.map { it.id })
		val glueMap = buildNamespaceMap(ir.glues.mapNotNull { it.id })
		val hitAreaIds = ir.hitAreas.map { it.id } + ir.advanced.hitAreas.map { it.id }
		val hitAreaMap = buildNamespaceMap(hitAreaIds)
		val maps = Maps(paramMap, groupMap, partMap, deformerMap, meshMap, physicsMap, glueMap, hitAreaMap)
		if (maps.isEmpty) return Result(ir, maps)
		return Result(rewrite(ir, maps), maps)
	}

	/** 仅针对单命名空间列表构造确定性清洗映射，保证跨文件及重复导出结果稳定且不与已有合法 ID 冲突。 */
	public fun buildNamespaceMap(ids: Iterable<String>): Map<String, String> {
		val distinct = ids.distinct()
		val invalid = distinct.filterNot(::isValid)
		if (invalid.isEmpty()) return emptyMap()
		val taken = distinct.filterTo(HashSet(), ::isValid)
		val out = LinkedHashMap<String, String>(invalid.size)
		for (raw in invalid.sorted()) {
			val chosen = allocateValidId(raw, taken)
			taken += chosen
			out[raw] = chosen
		}
		return out
	}

	private fun allocateValidId(raw: String, taken: Set<String>): String {
		val hash = shortHash(raw)
		val cleaned = sanitizeChars(raw)
		val basePrefix = if (cleaned.isEmpty() || cleaned.first() !in 'A'..'Z' && cleaned.first() !in 'a'..'z') {
			"Id_$cleaned"
		} else {
			cleaned
		}
		val maxPrefix = MAX_ID_LENGTH - 1 - hash.length
		val stem = basePrefix.take(maxPrefix).trimEnd('_').ifEmpty { "Id" }
		val primary = "${stem}_$hash"
		if (primary !in taken && isValid(primary)) return primary
		var seq = 2
		while (true) {
			val suffix = "_${hash}_$seq"
			val cut = basePrefix.take(MAX_ID_LENGTH - suffix.length).trimEnd('_').ifEmpty { "Id" }
			val candidate = "$cut$suffix"
			if (candidate !in taken && isValid(candidate)) return candidate
			seq++
		}
	}

	private fun sanitizeChars(raw: String): String {
		val sb = StringBuilder(raw.length)
		var prevUnderscore = false
		for (ch in raw) {
			val ok = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_'
			if (ok) {
				if (ch == '_' && prevUnderscore) continue
				sb.append(ch)
				prevUnderscore = (ch == '_')
			} else if (!prevUnderscore && sb.isNotEmpty()) {
				sb.append('_')
				prevUnderscore = true
			}
		}
		return sb.toString().trim('_')
	}

	private fun shortHash(raw: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray())
		return HexFormat.of().formatHex(digest).take(8)
	}

	private fun collectParameters(ir: RigIR): List<String> = buildList {
		ir.parameters.mapTo(this) { it.id }
		ir.restPose.keys.forEach(::add)
	}

	private fun collectParamGroups(nodes: List<ParameterNode>): List<String> = buildList {
		fun walk(list: List<ParameterNode>) {
			for (node in list) {
				if (node is ParameterNode.Group) {
					add(node.id)
					walk(node.children)
				}
			}
		}
		walk(nodes)
	}

	private fun rewrite(ir: RigIR, maps: Maps): RigIR {
		fun p(id: String) = maps.parameters[id] ?: id
		fun pg(id: String) = maps.paramGroups[id] ?: id
		fun part(id: String) = maps.parts[id] ?: id
		fun def(id: String) = maps.deformers[id] ?: id
		fun mesh(id: String) = maps.meshes[id] ?: id
		fun phys(id: String) = maps.physicsGroups[id] ?: id
		fun glue(id: String) = maps.glues[id] ?: id
		fun hit(id: String) = maps.hitAreas[id] ?: id

		fun <F> grid(g: KeyGrid<F>?): KeyGrid<F>? {
			if (g == null || maps.parameters.isEmpty()) return g
			return g.copy(axes = g.axes.map { it.copy(parameter = p(it.parameter)) })
		}

		fun channels(ch: Channels): Channels {
			if (ch.isEmpty() || maps.parameters.isEmpty()) return ch
			return ch.mapValues { requireNotNull(grid(it.value)) }
		}

		fun <S> shapes(list: List<BlendBinding<S>>): List<BlendBinding<S>> {
			if (list.isEmpty() || maps.parameters.isEmpty()) return list
			return list.map { b ->
				b.copy(parameter = p(b.parameter), limits = b.limits.map { it.copy(parameter = p(it.parameter)) })
			}
		}

		fun composite(c: Composite): Composite = c.copy(
			maskedBy = c.maskedBy.map(::mesh),
			maskedByParts = c.maskedByParts.map(::part),
		)

		fun childRef(ref: ChildRef): ChildRef = when (ref) {
			is ChildRef.PartRef -> ChildRef.PartRef(part(ref.id))
			is ChildRef.MeshRef -> ChildRef.MeshRef(mesh(ref.id))
		}

		fun paramNode(node: ParameterNode): ParameterNode = when (node) {
			is ParameterNode.Param -> ParameterNode.Param(p(node.id))
			is ParameterNode.Group -> node.copy(id = pg(node.id), children = node.children.map(::paramNode))
		}

		fun renderNode(node: RenderNode): RenderNode = when (node) {
			is RenderMesh -> RenderMesh(mesh(node.id))
			is RenderGroup -> node.copy(
				part = node.part?.let(::part),
				children = node.children.map(::renderNode),
				channels = channels(node.channels),
				composite = node.composite?.let(::composite),
			)
		}

		fun rotation(d: Deformer.Rotation): Deformer.Rotation = d.copy(
			id = def(d.id),
			parent = d.parent?.let(::def),
			part = d.part?.let(::part),
			pivot = grid(d.pivot),
			channels = channels(d.channels),
			shapes = shapes(d.shapes),
		)

		fun deformer(d: Deformer): Deformer = when (d) {
			is Deformer.Warp -> d.copy(
				id = def(d.id),
				parent = d.parent?.let(::def),
				part = d.part?.let(::part),
				lattice = grid(d.lattice),
				channels = channels(d.channels),
				shapes = shapes(d.shapes),
			)
			is Deformer.Rotation -> rotation(d)
		}

		fun hitArea(h: HitAreaIR): HitAreaIR = h.copy(id = hit(h.id), meshes = h.meshes.map(::mesh))

		val newParams = ir.parameters.map { it.copy(id = p(it.id)) }
		val newLinks = ir.parameterLinks.map { it.copy(horizontal = p(it.horizontal), vertical = p(it.vertical)) }
		val newTree = ir.parameterTree.map(::paramNode)
		val newRoles = ir.parameterRoles.map { it.copy(parameters = it.parameters.map(::p)) }
		val newParts = ir.parts.map { pt ->
			pt.copy(
				id = part(pt.id),
				children = pt.children.map(::childRef),
				channels = channels(pt.channels),
				composite = composite(pt.composite),
				shapes = shapes(pt.shapes),
			)
		}
		val newRootChildren = ir.rootChildren.map(::childRef)
		val newDeformers = ir.deformers.map(::deformer)
		val newMeshes = ir.meshes.map { m ->
			m.copy(
				id = mesh(m.id),
				parent = m.parent?.let(::def),
				maskedBy = m.maskedBy.map(::mesh),
				offsets = grid(m.offsets),
				channels = channels(m.channels),
				textureSource = m.textureSource?.let(::mesh),
				shapes = shapes(m.shapes),
			)
		}
		val newGlues = ir.glues.map { g ->
			g.copy(id = g.id?.let(::glue), meshA = mesh(g.meshA), meshB = mesh(g.meshB), channels = channels(g.channels))
		}
		val newRenderRoot = renderNode(ir.renderRoot) as RenderGroup
		val newTextures = ir.textures.copy(bindings = ir.textures.bindings.mapKeys { (k, _) -> mesh(k) })
		val newPhysics = ir.physics.copy(groups = ir.physics.groups.map { g ->
			g.copy(
				id = phys(g.id),
				inputs = g.inputs.map { it.copy(parameter = p(it.parameter)) },
				outputs = g.outputs.map { it.copy(parameter = p(it.parameter)) },
			)
		})
		val newClips = ir.clips.map { clip ->
			clip.copy(
				curves = clip.curves.map { it.copy(parameter = p(it.parameter)) },
				targetCurves = clip.targetCurves.map { tc ->
					val target = tc.target
					if (target is CurveTarget.PartOpacity) tc.copy(target = CurveTarget.PartOpacity(part(target.part))) else tc
				},
			)
		}
		val newRestPose = ir.restPose.mapKeys { (k, _) -> p(k) }
		val newAuthoring = ir.authoring.copy(
			paths = ir.authoring.paths.map { it.copy(mesh = mesh(it.mesh)) },
			vertexGroups = ir.authoring.vertexGroups.map { it.copy(mesh = mesh(it.mesh)) },
		)
		val adv = ir.advanced
		val newAdvanced = adv.copy(
			virtualBones = adv.virtualBones.map(::rotation),
			simulations = adv.simulations.map { sim ->
				sim.copy(
					targets = sim.targets.map { it.copy(mesh = mesh(it.mesh)) },
					particles = sim.particles.copy(anchorMesh = sim.particles.anchorMesh.map { if (it.isEmpty()) it else mesh(it) }),
					parameters = sim.parameters.map(::p),
					physicsGroups = sim.physicsGroups.map(::phys),
					statics = sim.statics.map { st ->
						st.copy(parameter = p(st.parameter), offsets = st.offsets.mapKeys { (k, _) -> mesh(k) })
					},
				)
			},
			colliders = adv.colliders.map { col ->
				col.copy(deformer = col.deformer?.let(::def), mesh = col.mesh?.let(::mesh))
			},
			expressions = adv.expressions.map { exp ->
				exp.copy(parameters = exp.parameters.map { it.copy(parameter = p(it.parameter)) })
			},
			hitAreas = adv.hitAreas.map(::hitArea),
			pose = adv.pose?.let { pose ->
				pose.copy(groups = pose.groups.map { grp ->
					grp.map { entry -> entry.copy(part = part(entry.part), links = entry.links.map(::part)) }
				})
			},
		)
		return ir.copy(
			parameters = newParams,
			parameterLinks = newLinks,
			parameterTree = newTree,
			parameterRoles = newRoles,
			parts = newParts,
			rootChildren = newRootChildren,
			rootPart = ir.rootPart?.let(::part),
			deformers = newDeformers,
			meshes = newMeshes,
			glues = newGlues,
			renderRoot = newRenderRoot,
			textures = newTextures,
			physics = newPhysics,
			clips = newClips,
			restPose = newRestPose,
			authoring = newAuthoring,
			advanced = newAdvanced,
			hitAreas = ir.hitAreas.map(::hitArea),
		)
	}
}
