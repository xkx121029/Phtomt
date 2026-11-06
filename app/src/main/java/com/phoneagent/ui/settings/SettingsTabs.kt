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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.prefs.AgentSurface
import com.phoneagent.ui.MainTabSpec
import com.phoneagent.ui.MainTabs
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing

/**
 * 选项卡分页（Superagent 融合 Phase 6）：每个 Tab 一行——图标、名称、可见性开关、
 * 上移/下移。改动即时写 [com.phoneagent.data.prefs.AppSettings]（经 save()），
 * MainActivity 观察 settings 流即时重排；主页与设置钉住不可隐藏。
 */
@Composable
internal fun SettingsTabs(
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
    /** 由「外观与导航」内嵌渲染时为 true：标题已由外层给，这里不再重复画一遍 */
    embedded: Boolean = false,
) {
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
        if (!embedded) {
            SettingsPageHeader("选项卡", onBack, subtitle = "工作台形态与底部导航的可见性、排序")
            Spacer(Modifier.height(AppSpacing.Page))
        }

        // ===== 工作台形态 =====
        SettingsSection(
            title = "工作台形态",
            subtitle = "分开 = 操控 / 对话 / 编码各占一个选项卡；合一 = 操控与对话收进 Agent 页，编码仍单独一页",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                val unified = st.agentSurface == AgentSurface.UNIFIED
                val options = listOf(
                    AgentSurface.SPLIT to "分开显示",
                    AgentSurface.UNIFIED to "合为一个 Agent",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = st.agentSurface == key,
                            onClick = {
                                if (st.agentSurface != key) {
                                    buzz()
                                    st.agentSurface = key
                                    save()
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                            label = {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    if (unified) {
                        "对话已并入 Agent 页：提问直接作答、要动手就按操控流程执行，由云端自动判断；" +
                            "编码仍是独立的 Coding 页。下方列表里对话一项的显隐暂不生效。"
                    } else {
                        "三个入口分开显示，各管各的；底部列表可单独开关与排序。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        // ===== 启动默认页 =====
        // 候选只取「当前可见」的 Tab：隐藏的页选不了，避免选了个启动时根本不存在的落点。
        // 若某页被隐藏后又删掉了配置里的默认页，主界面会自动回落主页。
        val landingCandidates = MainTabs.visibleTabs(st.tabOrder, st.tabVisible, st.agentSurface)
        val landingId = landingCandidates.firstOrNull { it.id == st.defaultTab }?.id ?: MainTabs.HOME
        SettingsSection(
            title = "启动默认页",
            subtitle = "打开软件直接落到这一页；所选页被隐藏时回落到主页",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                landingCandidates.forEachIndexed { index, spec ->
                    if (index > 0) SettingsDivider(inset = false)
                    LandingRow(
                        spec = spec,
                        selected = spec.id == landingId,
                        onSelect = {
                            if (st.defaultTab != spec.id) {
                                buzz()
                                st.defaultTab = spec.id
                                save()
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        SettingsSection(
            title = "底部选项卡",
            subtitle = "主页与设置是固定出入口，不可隐藏",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
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

        Spacer(Modifier.height(AppSpacing.Xl))
    }
}

/**
 * 启动默认页的单行选项：整行可点、单选。
 * 选中态只靠 RadioButton 与文字加深，不加水平滑入/位移。
 */
@Composable
private fun LandingRow(
    spec: MainTabSpec,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(AppSpacing.Sm))
        Icon(
            spec.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(AppSpacing.Md))
        Text(
            spec.title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
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
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            spec.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(AppSpacing.Md))
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