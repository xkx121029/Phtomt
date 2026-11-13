package com.phoneagent.ui.onboarding

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.phoneagent.core.ai.ProviderPresets
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.SettingsState
import com.phoneagent.ui.settings.applyProviderPreset
import com.phoneagent.ui.settings.commitModelConfig
import com.phoneagent.ui.settings.connectionFailureText
import com.phoneagent.ui.settings.modelReady
import com.phoneagent.ui.settings.protocolOf
import com.phoneagent.ui.theme.AppSpacing
import kotlinx.coroutines.launch

/**
 * 引导第 5 步：连接模型。
 *
 * 这一步是「向导走完但还不能用」的补丁——旧向导只写「到设置里配好即可」，
 * 用户回到主界面发第一条任务才发现没有 Key。这里把配置搬进向导本身，
 * 并且与「设置 → 快速开始」共用同一份 [SettingsState]、同一套落库函数
 * （[commitModelConfig]），两条冷启动路径不会各写一套。
 *
 * 默认预填智谱 GLM 的地址与模型名（只有 Key 必填）；失败原因就地中文化。
 * 允许跳过：不配模型也能进主界面，只是任务发不出去。
 */
@Composable
internal fun OnboardingModelPage(
    vm: MainViewModel,
    st: SettingsState,
    onNext: () -> Unit,
) {
    val ready = st.modelReady()

    /**
     * 保存并前进。
     *
     * 写入交给 [MainViewModel.saveSettings]（ViewModel 作用域）：本页马上被换掉，
     * 页面作用域的协程会随组合销毁被取消，写在页面协程里可能保存一半就中断。
     */
    fun saveAndNext() {
        if (ready) {
            commitModelConfig(st)
            vm.saveSettings(st.toSettings())
        }
        onNext()
    }

    OnboardingPage(
        content = {
            OnboardingPageHeader(
                icon = AppIcons.Key,
                title = "连接一个模型",
                body = "填服务商控制台里的 API Key 即可，地址与模型名已按预设填好。" +
                    "也可以先跳过，到「设置 → 快速开始」再配。",
            )
            Spacer(Modifier.height(AppSpacing.Lg))
            ModelConfigForm(vm = vm, st = st)
        },
        footer = {
            PrimaryOnboardingButton(
                label = if (ready) "下一步" else "先跳过，稍后配置",
                onClick = { saveAndNext() },
            )
        },
    )
}

/**
 * 模型配置表单：服务商预设 + 三项输入 + 就地测连接。
 *
 * 与「设置 → 快速开始」的第 ① 步是同一件事，但两处版式不同（那边是设置分区卡、
 * 这里是引导页），所以只共用数据与落库逻辑，不强行共用 UI。
 */
@Composable
private fun ModelConfigForm(vm: MainViewModel, st: SettingsState) {
    val ready = st.modelReady()
    ProviderPresetRow(st = st)
    Spacer(Modifier.height(AppSpacing.Md))
    ModelField(
        value = st.apiKey,
        label = "API Key",
        placeholder = "粘贴服务商控制台里的 Key",
        secret = true,
        onChange = { st.apiKey = it },
    )
    Spacer(Modifier.height(AppSpacing.Sm))
    ModelField(
        value = st.baseUrl,
        label = "API 地址",
        placeholder = null,
        secret = false,
        onChange = { st.baseUrl = it },
    )
    Spacer(Modifier.height(AppSpacing.Sm))
    ModelField(
        value = st.model,
        label = "模型名",
        placeholder = null,
        secret = false,
        onChange = { st.model = it },
    )
    Spacer(Modifier.height(AppSpacing.Md))
    ConnectTestRow(vm = vm, st = st, ready = ready)
}

/** 服务商预设横排：点一下把地址 / 模型名 / 协议一并填好 */
@Composable
private fun ProviderPresetRow(st: SettingsState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        ProviderPresets.all.forEach { preset ->
            FilterChip(
                selected = st.baseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/'),
                onClick = { applyProviderPreset(st, preset) },
                label = {
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/** 单个输入字段：Key 走密码掩码，地址与模型名明文 */
@Composable
private fun ModelField(
    value: String,
    label: String,
    placeholder: String?,
    secret: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 「测试连接」按钮 + 结果回显。
 *
 * 进行中 / 成功 / 失败三态只在这一处维护：按钮禁用避免连点，
 * 失败原因走 [connectionFailureText] 换成「下一步该做什么」的中文说法。
 */
@Composable
private fun ConnectTestRow(vm: MainViewModel, st: SettingsState, ready: Boolean) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintOk by remember { mutableStateOf(false) }

    // 已经配好的状态（例如用户从设置里配完再回到引导）不逼他重填，直接给结论
    LaunchedEffect(Unit) {
        if (ready) {
            hint = "已配置好，可以直接下一步"
            hintOk = true
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        OutlinedButton(
            enabled = ready && !testing,
            onClick = {
                commitModelConfig(st)
                scope.launch {
                    testing = true
                    hint = "正在连接…"
                    hintOk = false
                    val result = vm.testConnection(
                        st.baseUrl,
                        st.apiKey,
                        st.model,
                        st.protocolOf(st.baseUrl),
                    )
                    hintOk = result.isSuccess
                    hint = if (result.isSuccess) {
                        "连接成功，可以继续了"
                    } else {
                        connectionFailureText(result.exceptionOrNull()?.message)
                    }
                    testing = false
                }
            },
        ) {
            Text(if (testing) "测试中…" else "测试连接")
        }
        Text(
            "填了 Key，下一步的例子才能直接跑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    hint?.let {
        Spacer(Modifier.height(AppSpacing.Sm))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (hintOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
