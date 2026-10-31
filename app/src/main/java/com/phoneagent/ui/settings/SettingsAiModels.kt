package com.phoneagent.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.Endpoint
import com.phoneagent.core.ai.ModelCatalogCodec
import com.phoneagent.core.ai.ProviderPresets
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * AI 模型配置页：端点（地址 + 密钥）→ 模型库入口 → 职责分配（哪个职责用哪个模型）。
 *
 * 填好地址与 Key 点「拉取模型」后立刻弹选择浮层，勾中的才进模型库；
 * 模型库本身收进二级页 [SettingsModelCatalog]——库里动辄几十上百个模型，
 * 平铺在本页会把「端点 / 职责」两件事挤散。职责行只记「模型名」，
 * 地址与 Key 一律由所属端点提供，避免同一个地址在页面里填三遍。
 */
@Composable
internal fun SettingsAiModels(
    vm: MainViewModel,
    st: SettingsState,
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit,
    // 模型选择浮层由 SettingsScreen 在页面根节点上渲染：本页根容器是可滚动的，
    // 浮层挂在这里拿不到「满屏」的高度约束，会被压成 0
    onPickRole: (String) -> Unit,
) {
    // 拉取结果 → 勾选入库浮层。浮层挂在本页自己的满屏 Box 上：主体是可滚动 Column，
    // 直接挂进去同样拿不到高度约束，列表的 weight 会塌成 0
    var fetched by remember { mutableStateOf<FetchedModels?>(null) }
    Box(Modifier.fillMaxSize()) {
        AiModelsPage(
            vm = vm,
            st = st,
            onBack = onBack,
            onOpenCatalog = onOpenCatalog,
            onPickRole = onPickRole,
            onFetched = { fetched = it },
        )
        fetched?.let { f ->
            ModelImportDialog(
                endpointLabel = f.endpointLabel,
                names = f.names,
                existing = st.catalog.filter { it.endpointId == f.endpointId }.map { it.name }.toSet(),
                onConfirm = { picked ->
                    picked.forEach { name ->
                        if (st.catalog.none { it.endpointId == f.endpointId && it.name == name }) {
                            st.catalog.add(CatalogModel(endpointId = f.endpointId, name = name))
                        }
                    }
                    fetched = null
                },
                onDismiss = { fetched = null },
            )
        }
    }
}

/** 一次「拉取模型」的结果：端点 + 服务端返回的模型名，交给根节点浮层勾选入库 */
private data class FetchedModels(val endpointId: String, val endpointLabel: String, val names: List<String>)

@Composable
private fun AiModelsPage(
    vm: MainViewModel,
    st: SettingsState,
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit,
    onPickRole: (String) -> Unit,
    onFetched: (FetchedModels) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val buzz = rememberHapticClick()
    var presetHint by remember { mutableStateOf<String?>(null) }
    // 拉模型：提示只挂在发起的那张端点卡上，避免一份提示串到所有卡片
    var fetchingEndpointId by remember { mutableStateOf<String?>(null) }
    var fetchHintFor by remember { mutableStateOf<String?>(null) }
    var fetchHint by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("云端模型", onBack, subtitle = "端点、模型库与职责分配")
        Spacer(Modifier.height(20.dp))

        // ========== A 端点 ==========
        SettingsSection(
            title = "端点",
            subtitle = "API 地址与密钥；一个端点下可以放多个模型",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                Spacer(Modifier.height(10.dp))
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
                        protocol = ep.protocol,
                        onProtocol = { st.endpoints.setProtocol(ep.id, it) },
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
                                    val r = vm.listModels(target.baseUrl, target.apiKey, target.protocol)
                                    if (r.isSuccess) {
                                        val clean = r.getOrDefault(emptyList())
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                        fetchHint = null
                                        // 拉到就直接给勾选浮层：不再静默全量入库——
                                        // 几十上百个模型一次全塞进去，模型库立刻没法挑
                                        onFetched(FetchedModels(target.id, endpointHost(target.baseUrl), clean))
                                    } else {
                                        fetchHint = r.exceptionOrNull()?.message ?: "获取模型列表失败"
                                    }
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
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== B 模型库（入口） ==========
        // 库里的模型通常几十上百条，平铺在本页会把端点与职责两件事挤散，故收进二级页
        SettingsSection(
            title = "模型库",
            subtitle = "已入库的模型与能力徽章",
            tone = SettingsTone.Brand,
        ) {
            SettingsRow(
                icon = AppIcons.ListTodo,
                title = "已入库模型",
                subtitle = "查看能力徽章 / 重新探测",
                tone = SettingsTone.Brand,
                summary = "${st.catalog.size} 个 · ${st.endpoints.size} 个端点",
                onClick = onOpenCatalog,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ========== C 职责分配 ==========
        SettingsSection(
            title = "职责分配",
            subtitle = "点一行更换该职责使用的模型；长按拖动排序",
            tone = SettingsTone.Brand,
        ) {
            SettingsToggleRow(
                title = "主模型思考",
                subtitle = "主模型支持深度思考时，规划/重规划直接由主模型完成",
                value = st.mainThinking,
            ) { st.mainThinking = it }
            SettingsDivider(inset = false)
            SettingsToggleRow(
                title = "执行审核（审核者）",
                subtitle = "每个动作由审核者 AI 复核是否有页面依据，防止点到不存在的控件",
                value = st.enableReview,
            ) { st.enableReview = it }
            SettingsDivider(inset = false)
            SettingsToggleRow(
                title = "链路聚合（增强）",
                subtitle = "开启后思考模型参与规划/重规划等复杂任务",
                value = st.enableChain,
            ) { st.enableChain = it }

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                            onClick = { buzz(); onPickRole("main") },
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
                            onClick = { buzz(); onPickRole("vision") },
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
                            onClick = { buzz(); onPickRole("reason") },
                        )
                    }
                }
                LaunchedEffect(chainItems.size, chainItems.toList()) {
                    st.chainOrder = chainItems.toList()
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== D 功能选项 ==========
        SettingsSection(title = "功能选项", tone = SettingsTone.Brand) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("主模型识图", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "自动 = 按模型库的探测结果决定：探测出能识图才把截图交给主模型，探测不出按不支持处理；" +
                        "强制开/关是你说了算，探测不参与",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val mainVisionModes = listOf("自动" to "AUTO", "强制开" to "ON", "强制关" to "OFF")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    mainVisionModes.forEachIndexed { i, (label, key) ->
                        SegmentedButton(
                            selected = st.mainVisionMode == key,
                            onClick = { buzz(); st.mainVisionMode = key },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = mainVisionModes.size),
                            label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        )
                    }
                }
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
                Spacer(Modifier.height(4.dp))
            }
            SettingsDivider(inset = false)
            SettingsToggleRow(
                title = "主模型识图时跳过视觉描述",
                subtitle = "主模型能直接看图时不再额外调用视觉模型生成文字描述，省一次调用与等待",
                value = st.skipVisionDescWhenMainSees,
            ) { st.skipVisionDescWhenMainSees = it }
            SettingsDivider(inset = false)
            SettingsToggleRow(
                title = "附送屏幕截图",
                subtitle = "附带当前屏幕截图辅助模型判断",
                value = st.attachScreenshot,
            ) { st.attachScreenshot = it }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                // 端侧 3B 视觉模型已并入主程序进程内，下载/加载/启用收在「设置 → 端侧视觉」
                Text(
                    "端侧 3B 视觉模型在「设置 → 端侧视觉」中下载与启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 测试所有 API ----
        var testingAll by remember { mutableStateOf(false) }
        val apiResults = remember { mutableStateListOf<ApiTestItemResult>() }
        SettingsSection(
            title = "连接测试",
            subtitle = "按 主模型 / 视觉模型 / 思考模型 逐项验证连接",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedButton(
                    onClick = {
                        buzz()
                        apiResults.clear()
                        testingAll = true
                        scope.launch {
                            buildApiTestTargets(st).forEach { t ->
                                val r = try {
                                    vm.testConnection(t.baseUrl, t.apiKey, t.model, st.protocolOf(t.baseUrl))
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
                apiResults.forEach { item ->
                    // 上下排列：错误详情含完整返回正文，需占满整行换行展示，避免被单行布局挤出屏幕
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (item.ok) "✓ " else "✗ ") + item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.ok) AppTheme.colors.success else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 保存按钮：收进与分区同语言的中性卡（原先裸露在卡片外，与全页节奏对不上）
        var testing by remember { mutableStateOf(false) }
        var testError by remember { mutableStateOf<String?>(null) }
        SettingsPlainCard {
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        buzz()
                        testError = null
                        scope.launch {
                            testing = true
                            try {
                                val r = vm.testConnection(st.baseUrl, st.apiKey, st.model, st.protocolOf(st.baseUrl))
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // 测试失败不堵死保存：部分供应商只在特定模型/地区放行，
                    // 连接测试通不过但配置本身可用，给出「仍要保存」逃生门
                    TextButton(
                        onClick = {
                            testError = null
                            vm.saveSettings(st.toSettings())
                        },
                    ) { Text("仍要保存") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ========== E 语音输入 ==========
        // OpenAI 兼容 /audio/transcriptions 格式：硅基流动 / Groq / OpenAI / 自建 whisper 都适用
        var asrHint by remember { mutableStateOf<String?>(null) }
        SettingsSection(
            title = "语音输入",
            subtitle = "长按聊天输入框说话后，用这个 API 转文字；留空则回退系统语音识别",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    "填到版本号为止（如 https://api.siliconflow.cn/v1），请求会打到其下的 /audio/transcriptions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = st.asrBaseUrl,
                    onValueChange = { st.asrBaseUrl = it },
                    singleLine = true,
                    placeholder = { Text("API 地址，如 https://api.siliconflow.cn/v1") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = st.asrApiKey,
                    onValueChange = { st.asrApiKey = it },
                    singleLine = true,
                    placeholder = { Text("API 密钥") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = st.asrModel,
                    onValueChange = { st.asrModel = it },
                    singleLine = true,
                    placeholder = { Text("模型名，如 FunAudioLLM/SenseVoiceSmall") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        buzz()
                        scope.launch {
                            vm.saveSettings(st.toSettings())
                            asrHint = if (st.asrBaseUrl.isBlank()) "已保存：语音输入走系统识别"
                            else "已保存：长按输入框即可语音输入"
                        }
                    },
                ) { Text("保存语音配置") }
                asrHint?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
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

/** 改协议档位：同 Key，id 不变 */
private fun androidx.compose.runtime.snapshots.SnapshotStateList<Endpoint>.setProtocol(id: String, protocol: String) {
    val i = indexOfFirst { it.id == id }
    if (i >= 0) this[i] = this[i].copy(protocol = protocol)
}

/** 按归一化地址查端点协议；未入库（如尚未保存的地址）回落 OpenAI 兼容 */
internal fun SettingsState.protocolOf(baseUrl: String): String =
    endpoints.firstOrNull { it.id == ModelCatalogCodec.endpointId(baseUrl) }?.protocol ?: "openai"

/** 端点显示名；地址为空或端点已删则返回空串（职责行会退化成只显示职责说明） */
private fun endpointLabelOf(st: SettingsState, baseUrl: String): String {
    val id = ModelCatalogCodec.endpointId(baseUrl)
    val ep = st.endpoints.firstOrNull { it.id == id } ?: return ""
    return endpointHost(ep.baseUrl)
}

/** 把选中的模型落到对应职责：地址与 Key 跟随端点一起带过去，手填则只改模型名 */
internal fun applyRoleModel(st: SettingsState, role: String, endpointId: String?, name: String) {
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