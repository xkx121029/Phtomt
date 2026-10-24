package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainTabSpec
import com.phoneagent.ui.MainTabs
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons

/**
 * 选项卡分页（Superagent 融合 Phase 6）：每个 Tab 一行——图标、名称、可见性开关、
 * 上移/下移。改动即时写 [com.phoneagent.data.prefs.AppSettings]（经 save()），
 * MainActivity 观察 settings 流即时重排；主页与设置钉住不可隐藏。
 */
@Composable
internal fun SettingsTabs(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    val buzz = rememberHapticClick()

    // 展示顺序：以 tabOrder 为准，缺的新 Tab 补在尾部、脏 id 滤掉
    val orderedIds = remember(st.tabOrder) {
        (st.tabOrder + MainTabs.all.map { it.id }).distinct().filter { MainTabs.specOf(it) != null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("选项卡", onBack, subtitle = "底部导航的可见性与排序，改动即时生效")
        Spacer(Modifier.height(20.dp))

        SettingsSection(
            title = "底部选项卡",
            subtitle = "主页与设置是固定出入口，不可隐藏",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                orderedIds.forEachIndexed { index, id ->
                    val spec = MainTabs.specOf(id) ?: return@forEachIndexed
                    if (index > 0) SettingsDivider(inset = false)
                    TabConfigRow(
                        spec = spec,
                        canHide = id !in MainTabs.pinned,
                        visible = id in MainTabs.pinned || id in st.tabVisible,
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedIds.lastIndex,
                        onVisibleChange = { vis ->
                            buzz()
                            st.tabVisible = if (vis) (st.tabVisible + id).distinct()
                            else st.tabVisible - id
                            save()
                        },
                        onMoveUp = {
                            if (index > 0) {
                                buzz()
                                val list = orderedIds.toMutableList()
                                val prev = list[index - 1]
                                list[index - 1] = list[index]
                                list[index] = prev
                                st.tabOrder = list
                                save()
                            }
                        },
                        onMoveDown = {
                            if (index < orderedIds.lastIndex) {
                                buzz()
                                val list = orderedIds.toMutableList()
                                val next = list[index + 1]
                                list[index + 1] = list[index]
                                list[index] = next
                                st.tabOrder = list
                                save()
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 单个 Tab 配置行：图标 + 名称 + 可见性开关 + 上移/下移 */
@Composable
private fun TabConfigRow(
    spec: MainTabSpec,
    canHide: Boolean,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            spec.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                spec.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                if (canHide) "显示后出现在底部导航" else "固定显示，不可隐藏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                AppIcons.ChevronUp,
                contentDescription = "上移",
                tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                AppIcons.ChevronDown,
                contentDescription = "下移",
                tint = if (canMoveDown) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
        Switch(
            checked = visible,
            onCheckedChange = onVisibleChange,
            enabled = canHide,
        )
    }
}
