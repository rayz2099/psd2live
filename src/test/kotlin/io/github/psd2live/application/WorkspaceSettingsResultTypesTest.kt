package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlin.test.Test

/** 设置查询必须接受真实编码结果，避免新增配置字段被响应校验拒绝。 */
class WorkspaceSettingsResultTypesTest {
    /** 非默认排序步长也应合法，防止只验证默认值而遗漏字段。 */
    @Test fun encodedSettingsIncludeDrawOrderStep() {
        val config = PipelineConfig(drawOrderStep = 10)
        val settings = WorkspaceSettingsCodec.encode(config)
        validateOperationSchema(settings, WorkspaceSettingsResultTypes.settings, "result.settings")
    }
}
