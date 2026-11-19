package org.umamo.format

import org.umamo.format.art.LayerBlend
import org.umamo.format.psd.PsdReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 PSD 分组标记块（`lsct` 与深层嵌套 `lsdk`）的穿透语义与嵌套解析。
 * 用于保障由 Photoshop / ag-psd 写出的穿透组和超过 6 层嵌套组在导入时结构与混合语义不失真。
 */
class PsdSectionDividerTest {
	/** 校验记录头为 `norm` 且 `lsct` 子混合键为 `pass` 的开/闭分组都会被识别为穿透组。 */
	@Test fun lsctPassThroughWithNormRecordBlendKeyReadsAsPassThrough() {
		// PSD 存储顺序为自底向上：闭合边界 -> 子图层 -> 分组头。
		val records = listOf(
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "LeafOpen"),
			dividerSpec(name = "PassOpen", tag = "lsct", type = 1, recBlend = "norm", divBlend = "pass", subType = 0),
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "LeafClosed"),
			dividerSpec(name = "PassClosed", tag = "lsct", type = 2, recBlend = "norm", divBlend = "pass"),
		)
		val psdBytes = buildPsd(records)
		val art = PsdReader.read(psdBytes)

		val expNames = listOf("LeafOpen", "LeafClosed")
		val actNames = art.layers.map { it.name }
		assertEquals(expNames, actNames)

		val expPaths = listOf("PassOpen", "PassClosed")
		val actPaths = art.layers.map { it.groupPath }
		assertEquals(expPaths, actPaths)

		val byName = art.groups.associateBy { it.name }
		val openGroup = byName.getValue("PassOpen")
		assertTrue(openGroup.passThrough)
		assertEquals(LayerBlend.Normal, openGroup.blend)

		val closedGroup = byName.getValue("PassClosed")
		assertTrue(closedGroup.passThrough)
		assertEquals(LayerBlend.Normal, closedGroup.blend)
	}

	/** 校验普通组、正片叠底组及旧式记录头 `pass` 写法均保持原有行为不被破坏。 */
	@Test fun lsctNormalAndMultiplyFoldersKeepExistingBlendAndNonPassThrough() {
		val records = listOf(
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "InNorm"),
			dividerSpec(name = "NormGroup", tag = "lsct", type = 1, recBlend = "norm", divBlend = "norm", subType = 0),
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "InMul"),
			dividerSpec(name = "MulGroup", tag = "lsct", type = 1, recBlend = "mul ", divBlend = "mul ", subType = 0),
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "InLegacyPass"),
			dividerSpec(name = "LegacyPass", tag = "lsct", type = 1, recBlend = "pass"),
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "InLegacyNorm"),
			dividerSpec(name = "LegacyNorm", tag = "lsct", type = 1, recBlend = "norm"),
		)
		val psdBytes = buildPsd(records)
		val art = PsdReader.read(psdBytes)
		val byName = art.groups.associateBy { it.name }

		val normGroup = byName.getValue("NormGroup")
		assertFalse(normGroup.passThrough)
		assertEquals(LayerBlend.Normal, normGroup.blend)

		val mulGroup = byName.getValue("MulGroup")
		assertFalse(mulGroup.passThrough)
		assertEquals(LayerBlend.Multiply, mulGroup.blend)

		val legacyPass = byName.getValue("LegacyPass")
		assertTrue(legacyPass.passThrough)
		assertEquals(LayerBlend.Normal, legacyPass.blend)

		val legacyNorm = byName.getValue("LegacyNorm")
		assertFalse(legacyNorm.passThrough)
		assertEquals(LayerBlend.Normal, legacyNorm.blend)
	}

	/** 校验深层嵌套使用 `lsdk` 作为分组头与闭合边界时，层级路径与穿透属性解析完全等价于 `lsct`。 */
	@Test fun lsdkNestedGroupsAndBoundingDividersParseIdentically() {
		// 构造 7 层嵌套（L1..L6 用 lsct，第 7 层 L7Pass/L7Mul 用 lsdk）以及外层普通图层验证栈能正确闭合。
		val records = mutableListOf<LayerSpec>()
		records += pixelSpec(name = "RootLeaf")
		repeat(6) {
			records += dividerSpec(name = "</Layer group>", tag = "lsct", type = 3)
		}
		records += dividerSpec(name = "</Layer group>", tag = "lsdk", type = 3)
		records += pixelSpec(name = "DeepPassLeaf")
		records += dividerSpec(name = "L7Pass", tag = "lsdk", type = 1, recBlend = "norm", divBlend = "pass", subType = 0)

		records += dividerSpec(name = "</Layer group>", tag = "lsdk", type = 3)
		records += pixelSpec(name = "DeepMulLeaf")
		records += dividerSpec(name = "L7Mul", tag = "lsdk", type = 2, recBlend = "mul ", divBlend = "mul ", subType = 0)

		for (level in 6 downTo 1) {
			records += dividerSpec(name = "L$level", tag = "lsct", type = 1, recBlend = "norm", divBlend = "pass", subType = 0)
		}

		val psdBytes = buildPsd(records)
		val art = PsdReader.read(psdBytes)
		val layerPaths = art.layers.associate { it.name to it.groupPath }
		assertEquals(3, art.layers.size)
		assertEquals("", layerPaths.getValue("RootLeaf"))
		assertEquals("L1/L2/L3/L4/L5/L6/L7Pass", layerPaths.getValue("DeepPassLeaf"))
		assertEquals("L1/L2/L3/L4/L5/L6/L7Mul", layerPaths.getValue("DeepMulLeaf"))

		val groupByPath = art.groups.associateBy { it.path }
		val deepPass = groupByPath.getValue("L1/L2/L3/L4/L5/L6/L7Pass")
		assertTrue(deepPass.passThrough)
		assertEquals(LayerBlend.Normal, deepPass.blend)

		val deepMul = groupByPath.getValue("L1/L2/L3/L4/L5/L6/L7Mul")
		assertFalse(deepMul.passThrough)
		assertEquals(LayerBlend.Multiply, deepMul.blend)
	}

	/** 校验 `lsct` 块偏移 +4 不是标准 `'8BIM'` 签名时不误判为穿透组。 */
	@Test fun invalidDividerBlendSignatureDoesNotEnablePassThrough() {
		val badBlock = bytes {
			writeBytes("8BIM")
			writeBytes("lsct")
			writeInt(12)
			writeInt(1)
			writeBytes("XXXX")
			writeBytes("pass")
		}
		val records = listOf(
			dividerSpec(name = "</Layer group>", tag = "lsct", type = 3),
			pixelSpec(name = "Child"),
			LayerSpec(name = "BadSigGroup", recBlend = "norm", isPixel = false, addInfo = badBlock),
		)
		val psdBytes = buildPsd(records)
		val art = PsdReader.read(psdBytes)
		val group = art.groups.single()
		assertEquals("BadSigGroup", group.name)
		assertFalse(group.passThrough)
	}

	/** 仅描述单条测试图层记录的结构，避免手写字节拼接分散在多个测试中。 */
	private data class LayerSpec(
		val name: String,
		val recBlend: String,
		val isPixel: Boolean,
		val addInfo: ByteArray,
	)

	/** 构造分组头或闭合边界记录，精确控制 `lsct`/`lsdk` 块内容。 */
	private fun dividerSpec(
		name: String,
		tag: String,
		type: Int,
		recBlend: String = "norm",
		divBlend: String? = null,
		subType: Int? = null,
	): LayerSpec {
		val payload = bytes {
			writeInt(type)
			if (divBlend != null) {
				writeBytes("8BIM")
				writeBytes(divBlend)
				if (subType != null) writeInt(subType)
			}
		}
		val block = bytes {
			writeBytes("8BIM")
			writeBytes(tag)
			writeInt(payload.size)
			write(payload)
		}
		return LayerSpec(name = name, recBlend = recBlend, isPixel = false, addInfo = block)
	}

	/** 构造 1x1 像素普通图层记录，用于验证子图层归属路径与过滤分隔记录。 */
	private fun pixelSpec(name: String, recBlend: String = "norm"): LayerSpec =
		LayerSpec(name = name, recBlend = recBlend, isPixel = true, addInfo = byteArrayOf())

	/** 组装最小合法 8-bit RGB PSD 字节流，确保端到端走通 `PsdReader` 与 `PsdLayerRecords`。 */
	private fun buildPsd(records: List<LayerSpec>): ByteArray {
		val chData = mutableListOf<ByteArray>()
		val info = bytes {
			writeShort(records.size)
			for (rec in records) {
				val nameBytes = rec.name.toByteArray(Charsets.US_ASCII)
				val padLen = (4 - ((1 + nameBytes.size) % 4)) % 4
				val extra = bytes {
					writeInt(0)
					writeInt(0)
					writeByte(nameBytes.size)
					write(nameBytes)
					if (padLen > 0) write(ByteArray(padLen))
					write(rec.addInfo)
				}
				val chIds = listOf(0, 1, 2, -1)
				val pxBytes = if (rec.isPixel) byteArrayOf(0xFF.toByte()) else byteArrayOf()
				val chBlocks = chIds.map { id ->
					val block = bytes {
						writeShort(0)
						write(pxBytes)
					}
					id to block
				}
				val bottom = if (rec.isPixel) 1 else 0
				val right = if (rec.isPixel) 1 else 0
				writeInt(0); writeInt(0); writeInt(bottom); writeInt(right)
				writeShort(chBlocks.size)
				for ((id, block) in chBlocks) {
					writeShort(id)
					writeInt(block.size)
					chData += block
				}
				writeBytes("8BIM")
				writeBytes(rec.recBlend)
				writeByte(255); writeByte(0); writeByte(0); writeByte(0)
				writeInt(extra.size)
				write(extra)
			}
			for (block in chData) write(block)
		}
		val paddedInfo = if (info.size % 2 == 1) info + byteArrayOf(0) else info
		val section = bytes {
			writeInt(paddedInfo.size)
			write(paddedInfo)
			writeInt(0)
		}
		val emptyHead = ByteArray(6)
		return bytes {
			writeBytes("8BPS"); writeShort(1); write(emptyHead); writeShort(3)
			writeInt(4); writeInt(4); writeShort(8); writeShort(3)
			writeInt(0); writeInt(0)
			writeInt(section.size); write(section)
		}
	}

	/** 辅助顺序写入大端字节流。 */
	private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { stream ->
		DataOutputStream(stream).use(write)
	}.toByteArray()
}
