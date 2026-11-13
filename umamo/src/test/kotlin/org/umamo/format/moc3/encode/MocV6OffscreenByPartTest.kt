package org.umamo.format.moc3.encode

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证 Cubism 5.3 (MOC3 v6) 在无 offscreen 节点时仍按 partCount 填充 152/160 节，满足官方 Core 一致性校验。 */
class MocV6OffscreenByPartTest {
	@Test
	fun offscreenByPartSectionsAreFilledWithMinusOneWhenOffscreensEmpty() {
		val paramId = ParameterId("ParamAngleX")
		val param = Parameter(paramId, "Angle X", -30f, 30f, 0f)
		val positions = floatArrayOf(0f, 0f, 8f, 0f, 0f, 4f)
		val uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		val indices = intArrayOf(0, 1, 2)
		val mesh = DrawableMesh(positions, uvs, indices)
		val drawable = Drawable(DrawableId("mesh-0"), "mesh-0", null, BlendMode.Normal, emptyList(), mesh, null, texturePage = 0)
		val partAChildren = listOf<OrgChild>(OrgChild.Drawable(drawable.id))
		val partBChildren = emptyList<OrgChild>()
		val partA = Part(PartId("PartA"), "Part A", partAChildren, isVisible = true)
		val partB = Part(PartId("PartB"), "Part B", partBChildren, isVisible = true)
		val orgChildren = listOf<OrgChild>(
			OrgChild.Part(partA.id),
			OrgChild.Part(partB.id),
		)
		val rawPuppet = PuppetModel(
			listOf(param),
			listOf(partA, partB),
			emptyList(),
			listOf(drawable),
			orgChildren,
			null,
			canvasWidth = 64f,
			canvasHeight = 64f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)
		val derived = rawPuppet.withDerivedRenderRoot()
		val puppet = restMeshesToCanvasSpace(derived)
		val canvasToParent = canvasToParentSpaceFor(puppet)
		val options = Moc3ExportOptions()
		val bundle = Moc3Sidecars.bundle(
			puppet,
			"v6test",
			pages = emptyList(),
			canvasToParentSpace = canvasToParent,
			options = options,
			version = MocVersion.V53,
		)
		val mocBytes = bundle.files.single { it.name == "v6test.moc3" }.bytes
		val mocModel = MocCodec.read(mocBytes)
		assertEquals(MocVersion.V53, mocModel.version)

		val idx152 = Section.OFFSCREEN_BY_PART.indexIn(MocVersion.V53)
		val idx160 = Section.OFFSCREEN_BY_PART_ALIAS.indexIn(MocVersion.V53)
		assertTrue(mocModel.offsets[idx152] < mocBytes.size, "section 152 offset must precede EOF")
		assertTrue(mocModel.offsets[idx160] < mocBytes.size, "section 160 offset must precede EOF")

		val partCount = mocModel.partsCountOrZero()
		val expected = IntArray(partCount) { -1 }
		assertTrue(expected.size >= 2)
		assertContentEquals(expected, mocModel.sections.intArray(Section.OFFSCREEN_BY_PART))
		assertContentEquals(expected, mocModel.sections.intArray(Section.OFFSCREEN_BY_PART_ALIAS))
	}

	private fun MocModel.partsCountOrZero(): Int =
		countInfo.getOrElse(Sections.CI_PARTS) { 0 }
}
