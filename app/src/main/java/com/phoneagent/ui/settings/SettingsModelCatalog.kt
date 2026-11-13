package com.phoneagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppRadii
import kotlinx.coroutines.launch

/**
 * 模型库二级页：按端点分组的已入库模型。
 *
 * 两种进入方式共用一个页面：
 * - [pickRole] 为 null：管理与查看（筛选 + 重新探测能力）；
 * - [pickRole] 非空：为某个职责挑模型，点一行即选中并返回（也可手填模型名）。
 *
 * 模型库不再平铺在「云端模型」页：几十上百个模型铺开会把端点与职责两件事挤散。
 */
@Composable
// 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun SettingsModelCatalog(
    vm: MainViewModel,
    st: SettingsState,
    pickRole: String?,
    onBack: () -> Unit,
    onPicked: (endpointId: String?, name: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val buzz = rememberHapticClick()
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    // 能力探测：按「端点 id/模型名」定位提示
    var probingKey by remember { mutableStateOf<String?>(null) }
    var probeHintFor by remember { mutableStateOf<String?>(null) }
    var probeHint by remember { mutableStateOf<String?>(null) }

    val current = when (pickRole) {
        "main" -> st.model
        "vision" -> st.visionModel
        "reason" -> st.reasonModel
        else -> null
    }

    // 模型库动辄上百条：整页走懒加载，只组合视口内的行，
    // 滚动时不再每帧把所有行重新测量一遍（实测见 docs/ui-refresh-plan.md 阶段三）。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = SettingsScreenPad,
            end = SettingsScreenPad,
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            bottom = LocalNavClearance.current.calculateBottomPadding() + AppSpacing.Xl,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        item(key = "catalog-header") {
            Column {
                SettingsPageHeader(
                    title = when (pickRole) {
                        "main" -> "选择主模型"
                        "vision" -> "选择视觉模型"
                        "reason" -> "选择思考模型"
                        else -> "模型库"
                    },
                    onBack = onBack,
                    subtitle = if (pickRole == null) {
                        "已入库的模型与能力徽章；问号代表没测出，与「不支持」不是一回事"
                    } else {
                        "点一行即为该职责选定模型；库里没有的也可以手填"
                    },
                )
                Spacer(Modifier.height(AppSpacing.Page))
            }
        }

        if (pickRole != null) {
            item(key = "catalog-manual") {
                Column {
                    SettingsSection(
                        title = "手填模型名",
                        subtitle = "服务端列表里没有该模型时直接输入",
                        tone = SettingsTone.Brand,
                    ) {
                        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                            ) {
                                OutlinedTextField(
                                    value = manual,
                                    onValueChange = { manual = it },
                                    singleLine = true,
                                    placeholder = { Text("模型名") },
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        buzz()
                                        if (manual.isNotBlank()) onPicked(null, manual.trim())
                                    },
                                    enabled = manual.isNotBlank(),
                                ) { Text("使用") }
                            }
                        }
                    }
                    Spacer(Modifier.height(AppSpacing.Lg))
                }
            }
        }

        // ========== 已入库模型 ==========
        // 标题与筛选框单独成块；下面的模型行逐条懒加载，
        // 不再包在一个会一次性量完全部行的大卡片里
        item(key = "catalog-filter") {
            SettingsSection(
                title = "已入库模型",
                subtitle = "${st.catalog.size} 个 · 来自 ${st.endpoints.size} 个端点",
                tone = SettingsTone.Brand,
            ) {
                Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("筛选模型名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val visible = st.catalog.toList().filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        }
        if (visible.isEmpty()) {
            item(key = "catalog-empty") {
                Text(
                    if (st.catalog.isEmpty()) "模型库为空：先回「云端模型」在端点里点「拉取模型」"
                    else "没有匹配的模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AppSpacing.Sm),
                )
            }
        } else {
            st.endpoints.toList().forEach { ep ->
                val groupModels = visible.filter { it.endpointId == ep.id }
                if (groupModels.isNotEmpty()) {
                    item(key = "ep-${ep.id}") {
                        Text(
                            endpointHost(ep.baseUrl),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AppSpacing.Md),
                        )
                    }
                    items(items = groupModels, key = { "ep-${it.endpointId}/${it.name}" }) { m ->
                        val key = "${m.endpointId}/${m.name}"
                        CatalogModelRow(
                            model = m,
                            selected = m.name == current,
                            picking = pickRole != null,
                            probing = probingKey == key,
                            hint = probeHint.takeIf { probeHintFor == key },
                            onPick = { onPicked(m.endpointId, m.name) },
                            onProbe = {
                                buzz()
                                probeHintFor = key
                                scope.launch {
                                    probingKey = key
                                    probeHint = "正在探测能力…"
                                    val owner = st.endpoints.firstOrNull { it.id == m.endpointId }
                                    if (owner == null) {
                                        probeHint = "该模型所属端点已被删除"
                                    } else {
                                        val r = vm.probeModel(
                                            owner.baseUrl,
                                            owner.apiKey,
                                            m.name,
                                            owner.protocol,
                                        )
                                        probeHint = r.fold(
                                            onSuccess = { ability ->
                                                updateCatalogModel(st, m.endpointId, m.name) {
                                                    it.copy(
                                                        vision = ability.vision,
                                                        tools = ability.tools,
                                                        jsonSchema = ability.jsonSchema,
                                                        note = ability.note,
                                                        probedAt = System.currentTimeMillis(),
                                                    )
                                                }
                                                // 这里不再顺手改写"视觉理解"档位：三态下的「自动」本就看探测结果，
                                                // 再写一手会在用户明确选了「强制关」时把设置偷偷翻回去
                                                ability.note.ifBlank { "探测完成" }
                                            },
                                            onFailure = { it.message ?: "探测失败" },
                                        )
                                    }
                                    probingKey = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 模型库单行：模型名 + 能力徽章 + 重新探测；挑选模式下整行可点，当前在用的一条高亮 */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
private fun CatalogModelRow(
    model: CatalogModel,
    selected: Boolean,
    picking: Boolean,
    probing: Boolean,
    hint: String?,
    onPick: () -> Unit,
    onProbe: () -> Unit,
) {
    val buzz = rememberHapticClick()
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        // 与端点卡同一块「下沉面」：模型条目和端点卡是同一层级的列表行，底色不能各写一套
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else AppSurfaceMuted,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (picking) Modifier.clickable { buzz(); onPick() } else Modifier),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(AppSpacing.Xs))
                    AbilityBadges(model.vision, model.tools, model.jsonSchema)
                }
                if (selected) {
                    Icon(
                        AppIcons.CheckCircle,
                        contentDescription = "当前使用",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                TextButton(onClick = onProbe, enabled = !probing) {
                    Text(if (probing) "探测中…" else "重新探测")
                }
            }
            val line = hint ?: model.note.ifBlank { null }
            line?.let {
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 就地更新模型库条目 */
private fun updateCatalogModel(
    st: SettingsState,
    endpointId: String,
    name: String,
    transform: (CatalogModel) -> CatalogModel,
) {
    val i = st.catalog.indexOfFirst { it.endpointId == endpointId && it.name == name }
    if (i >= 0) st.catalog[i] = transform(st.catalog[i])
}