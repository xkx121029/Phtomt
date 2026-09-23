package com.phoneagent.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.Endpoint
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.core.ai.ProviderPresets
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import kotlinx.coroutines.launch

/**
 * AI 模型配置页：端点（地址 + 密钥）→ 模型库（能力徽章）→ 职责分配（哪个职责用哪个模型）。
 * 职责行只记「模型名」，地址与 Key 一律由所属端点提供，避免同一个地址在页面里填三遍。
 */
@Composable
internal fun SettingsAiModels(vm: MainViewModel, st: SettingsState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val buzz = rememberHapticClick()
    var extStatus by remember { mutableStateOf<String?>(null) }
    var extTesting by remember { mutableStateOf(false) }
    var presetHint by remember { mutableStateOf<String?>(null) }
    // 拉模型：提示只挂在发起的那张端点卡上，避免一份提示串到所有卡片
    var fetchingEndpointId by remember { mutableStateOf<String?>(null) }
    var fetchHintFor by remember { mutableStateOf<String?>(null) }
    var fetchHint by remember { mutableStateOf<String?>(null) }
    // 能力探测：按「端点 id/模型名」定位提示
    var probingKey by remember { mutableStateOf<String?>(null) }
    var probeHintFor by remember { mutableStateOf<String?>(null) }
    var probeHint by remember { mutableStateOf<String?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    var pickRole by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        SettingsTopBar("AI 模型配置", onBack)
        Spacer(Modifier.height(16.dp))

        // ========== A 端点 ==========
        GroupCard {
            GroupHeader("端点", "API 地址与密钥；一个端点下可以放多个模型")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 快捷预设：点一下填好地址 + 主/视觉/思考模型，并把它们写进端点与模型库
                Text("快捷预设", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderPresets.all.forEach { preset ->
                        FilterChip(
                            selected = st.baseUrl.trim().trimEnd('/') == preset.baseUrl,
                            onClick = {
                                buzz()
                                applyProviderPreset(st, preset)
                                presetHint = "已套用「${preset.name}」：${preset.model}（端点与模型已入库）"
                            },
                            label = {
                                Text(preset.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            },
                        )
                    }
                }
                presetHint?.let { hint ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "支持 http://、内网地址与 localhost（如 http://192.168.1.5:8000/v1），不写协议时自动补全",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                st.endpoints.toList().forEachIndexed { idx, ep ->
                    if (idx > 0) Spacer(Modifier.height(10.dp))
                    EndpointCard(
                        baseUrl = ep.baseUrl,
                        apiKey = ep.apiKey,
                        modelCount = st.catalog.count { it.endpointId == ep.id },
                        fetching = fetchingEndpointId == ep.id,
                        hint = fetchHint.takeIf { fetchHintFor == ep.id },
                        onBaseUrl = { renameEndpoint(st, ep.id, it) },
                        onApiKey = { st.endpoints.setKey(ep.id, it) },
                        onFetch = {
                            val target = ep
                            fetchHintFor = target.id
                            if (target.baseUrl.isBlank()) {
                                fetchHint = "请先填写 API 地址"
                            } else {
                                scope.launch {
                                    fetchingEndpointId = target.id
                                    fetchHint = "正在获取模型列表…"
                                    val r = vm.listModels(target.baseUrl, target.apiKey)
                                    fetchHint = r.fold(
                                        onSuccess = { names ->
                                            val clean = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                                            var added = 0
                                            clean.forEach { n ->
                                                if (st.catalog.none { it.endpointId == target.id && it.name == n }) {
                                                    st.catalog.add(CatalogModel(endpointId = target.id, name = n))
                                                    added++
                                                }
                                            }
                                            "已获取 ${clean.size} 个模型，新增 $added 个"
                                        },
                                        onFailure = { it.message ?: "获取模型列表失败" },
                                    )
                                    fetchingEndpointId = null
                                }
                            }
                        },
                        onDelete = {
                            buzz()
                            val i = st.endpoints.indexOfFirst { it.id == ep.id }
                            if (i >= 0) {
                                val gone = st.endpoints.removeAt(i)
                                st.catalog.removeAll { it.endpointId == gone.id }
                            }
                            fetchHintFor = null
                            fetchHint = null
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))
                val hasBlank = st.endpoints.any { it.baseUrl.isBlank() }
                OutlinedButton(
                    onClick = {
                        buzz()
                        st.endpoints.add(Endpoint(id = "", baseUrl = "", apiKey = ""))
                    },
                    enabled = !hasBlank,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasBlank) "请先填写上面新增的端点" else "添加端点") }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== B 模型库 ==========
        GroupCard {
            GroupHeader("模型库", "能力徽章由真实请求探测得出；问号代表没测出，与「不支持」不是一回事")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it },
                    singleLine = true,
                    placeholder = { Text("筛选模型名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val visible = st.catalog.toList().filter {
                    modelQuery.isBlank() || it.name.contains(modelQuery, ignoreCase = true)
                }
                if (visible.isEmpty()) {
                    Text(
                        if (st.catalog.isEmpty()) "模型库为空：先在「端点」里点「获取模型」" else "没有匹配的模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    st.endpoints.toList().forEach { ep ->
                        val items = visible.filter { it.endpointId == ep.id }
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                endpointHost(ep.baseUrl),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            items.forEach { m ->
                                val key = "${m.endpointId}/${m.name}"
                                CatalogModelRow(
                                    model = m,
                                    probing = probingKey == key,
                                    hint = probeHint.takeIf { probeHintFor == key },
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
                                                val r = vm.probeModel(owner.baseUrl, owner.apiKey, m.name)
                                                probeHint = r.fold(
                                                    onSuccess = { ability ->
                                                        updateCatalogModel(st, m.endpointId, m.name) {
                                                            it.copy(
                                                                vision = ability.vision,
                                                                tools = ability.tools,
                                                                note = ability.note,
                                                                probedAt = System.currentTimeMillis(),
                                                            )
                                                        }
                                                        // 主槽同名模型被证实能识图 → 单向打开视觉理解（反向不自动关，尊重用户手改）
                                                        if (ability.vision == true && st.model.trim() == m.name) {
                                                            st.hasVision = true
                                                        }
                                                        ability.note.ifBlank { "探测完成" }
                                                    },
                                                    onFailure = { it.message ?: "探测失败" },
                                                )
                                            }
                                            probingKey = null
                                        }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== C 职责分配 ==========
        GroupCard {
            GroupHeader("职责分配", "点一行更换该职责使用的模型；长按拖动排序")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow(
                    "主模型思考",
                    "主模型支持深度思考时，规划/重规划直接由主模型完成",
                    st.mainThinking,
                ) { st.mainThinking = it }
                Spacer(Modifier.height(8.dp))

                ToggleRow(
                    "执行审核（审核者）",
                    "每个动作由审核者 AI 复核是否有页面依据，防止点到不存在的控件",
                    st.enableReview,
                ) { st.enableReview = it }
                Spacer(Modifier.height(8.dp))

                ToggleRow(
                    "链路聚合（增强）",
                    "开启后思考模型参与规划/重规划等复杂任务",
                    st.enableChain,
                ) { st.enableChain = it }
                Spacer(Modifier.height(10.dp))

                val chainItems = remember { mutableStateListOf(*st.chainOrder.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("main", "vision", "reason")) }
                ReorderableColumn(items = chainItems) { key, dragHandle ->
                    when (key) {
                        "main" -> RoleRow(
                            dragHandle = dragHandle,
                            title = "主模型",
                            subtitle = "每步执行决策",
                            model = st.model,
                            endpointLabel = endpointLabelOf(st, st.baseUrl),
                            showToggle = false,
                            enabled = true,
                            onToggle = {},
                            onClick = { buzz(); pickRole = "main" },
                        )
                        "vision" -> RoleRow(
                            dragHandle = dragHandle,
                            title = "视觉模型",
                            subtitle = "截图描述 + 定位点击坐标",
                            model = st.visionModel,
                            endpointLabel = endpointLabelOf(st, st.visionBaseUrl),
                            showToggle = true,
                            enabled = st.visionEnabled,
                            onToggle = { st.visionEnabled = it },
                            onClick = { buzz(); pickRole = "vision" },
                        )
                        "reason" -> if (st.enableChain) RoleRow(
                            dragHandle = dragHandle,
                            title = "思考模型",
                            subtitle = "规划 / 重规划（深度思考）",
                            model = st.reasonModel,
                            endpointLabel = endpointLabelOf(st, st.reasonBaseUrl),
                            showToggle = true,
                            enabled = true,
                            onToggle = {},
                            onClick = { buzz(); pickRole = "reason" },
                        )
                    }
                }
                LaunchedEffect(chainItems.size, chainItems.toList()) {
                    st.chainOrder = chainItems.toList()
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== D 功能选项 ==========
        GroupCard {
            GroupHeader("功能选项")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow("启用视觉理解", "结合截图理解图片、图表等元素树无法表达的内容", st.hasVision) { st.hasVision = it }
                Spacer(Modifier.height(4.dp))
                Text(
                    "由所选主模型的能力徽章判定，可手动覆盖：主模型能识图时，每步会直接把截图交给它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "主模型识图时跳过视觉描述",
                    "主模型能直接看图时不再额外调用视觉模型生成文字描述，省一次调用与等待",
                    st.skipVisionDescWhenMainSees,
                ) { st.skipVisionDescWhenMainSees = it }
                Spacer(Modifier.height(8.dp))
                Text("视觉读图方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "自动 = 优先云端，失败回退本地 OCR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val visionModes = listOf("云端" to "CLOUD", "本地OCR" to "LOCAL", "自动" to "AUTO")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    visionModes.forEachIndexed { i, (label, key) ->
                        SegmentedButton(
                            selected = st.visionMode == key,
                            onClick = { st.visionMode = key },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = visionModes.size),
                            label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("外挂视觉 Agent", "用本地视觉 APK（端侧 3B）框选控件，优先于云端与本地 OCR；不可用时自动回落", st.enableExternalVision) { st.enableExternalVision = it }
                Spacer(Modifier.height(6.dp))
                ToggleRow("混合路由", "简单任务（元素树可读）走端侧 3B 省额度；复杂任务（如游戏/WebView）直接走云端视觉", st.smartVisionRoute) { st.smartVisionRoute = it }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            extTesting = true; extStatus = "连接测试中…"
                            // 空白图也会触发完整 IPC：外挂服务收到后返回 OCR/3B 控件结果
                            val bmp = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888)
                            val controls = com.phoneagent.device.vision.ExternalVisionProvider.detectControls(ctx, bmp, 15_000)
                            val connected = com.phoneagent.device.vision.ExternalVisionProvider.isConnected
                            extStatus = if (connected) {
                                "外挂视觉服务已连接，跨进程识别返回 ${controls.size} 个控件"
                            } else {
                                "外挂视觉不可用：服务未安装或绑定失败（可回首页视觉卡点击\"打开外挂\"检查）"
                            }
                            extTesting = false
                        }
                    },
                    enabled = !extTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (extTesting) "连接测试中…" else "测试外挂连接") }
                extStatus?.let { s ->
                    Spacer(Modifier.height(4.dp))
                    Text(s, style = MaterialTheme.typography.bodySmall,
                        color = if (s.contains("已连接")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("附送屏幕截图", value = st.attachScreenshot) { st.attachScreenshot = it }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 测试所有 API ----
        var testingAll by remember { mutableStateOf(false) }
        val apiResults = remember { mutableStateListOf<ApiTestItemResult>() }
        OutlinedButton(
            onClick = {
                buzz()
                apiResults.clear()
                testingAll = true
                scope.launch {
                    buildApiTestTargets(st).forEach { t ->
                        val r = try {
                            vm.testConnection(t.baseUrl, t.apiKey, t.model)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                        apiResults.add(
                            ApiTestItemResult(
                                label = t.label,
                                ok = r.isSuccess,
                                detail = if (r.isSuccess) "连接正常" else (r.exceptionOrNull()?.message ?: "未知错误"),
                            )
                        )
                    }
                    testingAll = false
                }
            },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testingAll) "正在测试所有 API…" else "测试所有 API")
        }
        if (testingAll) {
            Text(
                "按 主模型 / 视觉模型 / 思考模型 逐项验证连接…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        apiResults.forEach { item ->
            // 上下排列：错误详情含完整返回正文，需占满整行换行展示，避免被单行布局挤出屏幕
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (item.ok) "✓ " else "✗ ") + item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 保存按钮
        var testing by remember { mutableStateOf(false) }
        var testError by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = {
                buzz()
                testError = null
                scope.launch {
                    testing = true
                    try {
                        val r = vm.testConnection(st.baseUrl, st.apiKey, st.model)
                        testing = false
                        if (r.isSuccess) {
                            vm.saveSettings(st.toSettings())
                        } else {
                            testError = "模型连接失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
                        }
                    } catch (e: Exception) {
                        testing = false
                        testError = "模型连接失败：${e.message}"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "正在测试连接…" else "保存 AI 配置")
        }
        testError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
    }

    // ---- 模型选择弹层 ----
    pickRole?.let { role ->
        val current = when (role) {
            "main" -> st.model
            "vision" -> st.visionModel
            else -> st.reasonModel
        }
        ModelPickerDialog(
            title = when (role) {
                "main" -> "选择主模型"
                "vision" -> "选择视觉模型"
                else -> "选择思考模型"
            },
            models = st.catalog.toList(),
            endpointLabelOf = { m ->
                st.endpoints.firstOrNull { it.id == m.endpointId }?.let { endpointHost(it.baseUrl) } ?: ""
            },
            current = current,
            onPickModel = { m ->
                applyRoleModel(st, role, m.endpointId, m.name)
                pickRole = null
            },
            onPickManual = { name ->
                applyRoleModel(st, role, null, name)
                pickRole = null
            },
            onDismiss = { pickRole = null },
        )
    }
}

/** 模型库单行：模型名 + 能力徽章 + 重新探测 */
@Composable
private fun CatalogModelRow(
    model: CatalogModel,
    probing: Boolean,
    hint: String?,
    onProbe: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    AbilityBadges(model.vision, model.tools)
                }
                TextButton(onClick = onProbe, enabled = !probing) {
                    Text(if (probing) "探测中…" else "重新探测")
                }
            }
            val line = hint ?: model.note.ifBlank { null }
            line?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 改地址时同步推导端点 id，并把该端点下的模型条目一起迁到新 id，避免模型变成孤儿 */
private fun renameEndpoint(st: SettingsState, oldId: String, newUrl: String) {
    val newId = ModelCatalogCodec.endpointId(newUrl)
    val i = st.endpoints.indexOfFirst { it.id == oldId }
    if (i < 0) return
    st.endpoints[i] = st.endpoints[i].copy(id = newId, baseUrl = newUrl)
    if (newId != oldId) {
        for (j in st.catalog.indices) {
            if (st.catalog[j].endpointId == oldId) st.catalog[j] = st.catalog[j].copy(endpointId = newId)
        }
    }
}

/** 改 Key：端点 id 只由地址推导，Key 变了 id 不变，无需迁移 */
private fun androidx.compose.runtime.snapshots.SnapshotStateList<Endpoint>.setKey(id: String, key: String) {
    val i = indexOfFirst { it.id == id }
    if (i >= 0) this[i] = this[i].copy(apiKey = key)
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

/** 端点显示名；地址为空或端点已删则返回空串（职责行会退化成只显示职责说明） */
private fun endpointLabelOf(st: SettingsState, baseUrl: String): String {
    val id = ModelCatalogCodec.endpointId(baseUrl)
    val ep = st.endpoints.firstOrNull { it.id == id } ?: return ""
    return endpointHost(ep.baseUrl)
}

/** 把选中的模型落到对应职责：地址与 Key 跟随端点一起带过去，手填则只改模型名 */
private fun applyRoleModel(st: SettingsState, role: String, endpointId: String?, name: String) {
    val ep = endpointId?.let { id -> st.endpoints.firstOrNull { it.id == id } }
    when (role) {
        "main" -> {
            st.model = name
            if (ep != null) {
                st.baseUrl = ep.baseUrl
                st.apiKey = ep.apiKey
            }
        }
        "vision" -> {
            st.visionModel = name
            if (ep != null) {
                st.visionBaseUrl = ep.baseUrl
                st.visionApiKey = ep.apiKey
            }
            st.visionEnabled = true
        }
        else -> {
            st.reasonModel = name
            if (ep != null) {
                st.reasonBaseUrl = ep.baseUrl
                st.reasonApiKey = ep.apiKey
            }
        }
    }
}