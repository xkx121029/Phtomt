package com.phoneagent.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.ui.components.ConfigDialCard
import com.phoneagent.ui.components.DialOption
import com.phoneagent.ui.components.approvalDialOptions
import com.phoneagent.ui.components.modelDialOptions
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing

/**
 * Agent 页的配置卡：模型 + 授权等级，两张并排的小卡片，卡内嵌转盘选择器。
 *
 * 与编码页共用 [ConfigDialCard]、[modelDialOptions]、[approvalDialOptions]，
 * 两页的样式、可选项与交互只留一份实现——用户从一页切到另一页不该看到两套控件。
 *
 * 授权等级取 [ActionMode] 三档（保守 / 均衡 / 自由），对应 Agent 引擎放行的意图范围；
 * 模型取模型库名单并保证当前值始终在轮上。
 */
@Composable
internal fun AgentConfigDialRow(
    settings: AppSettings.Settings,
    onPickModel: (name: String, endpointId: String?) -> Unit,
    onPickMode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelOptions = remember(settings.catalog, settings.model) {
        modelDialOptions(settings.catalog, settings.model)
    }
    val modeOptions = remember { ActionMode.entries.map { DialOption(it.key, it.label) } }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        ConfigDialCard(
            title = "模型",
            icon = AppIcons.SmartToy,
            options = modelOptions,
            selectedId = settings.model,
            onSelect = { name ->
                onPickModel(name, settings.catalog.firstOrNull { it.name == name }?.endpointId)
            },
            modifier = Modifier.weight(1f),
        )
        ConfigDialCard(
            title = "授权等级",
            icon = AppIcons.Guard,
            options = modeOptions,
            selectedId = settings.actionMode,
            onSelect = onPickMode,
            modifier = Modifier.weight(1f),
        )
    }
}
