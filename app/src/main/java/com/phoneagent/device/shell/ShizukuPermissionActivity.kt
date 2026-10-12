package com.phoneagent.device.shell

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 权限确认页（内嵌 server 的"管理器"角色）。
 *
 * 内嵌 server（rikka.shizuku.server.ShizukuService）在收到 requestPermission 时，
 * 以 action「com.phoneagent.intent.action.REQUEST_PERMISSION」拉起本页
 * （原 Shizuku 官方管理器的 RequestPermissionActivity 同款流程，逻辑参照其实现）：
 * 等 server binder（最多 5s）→ 校验 shell 的 GRANT_RUNTIME_PERMISSIONS →
 * 弹出确认（允许=永久 / 拒绝=一次性）→ 把结果回传 server。
 *
 * UI 采用应用内嵌入式卡片（非系统弹窗），Material 3 常规样式。
 */
class ShizukuPermissionActivity : ComponentActivity() {

    private companion object {
        /** 等 server binder 的超时秒数（对齐官方管理器的 5s） */
        const val BINDER_WAIT_SECONDS = 5L
    }

    /** 就绪后的确认信息；null 表示仍在等 binder / 校验中 */
    private var uiState by mutableStateOf<Ui?>(null)

    private data class Ui(val uid: Int, val pid: Int, val requestCode: Int, val label: String)

    /** server intent 携带的请求参数；缺任一项视为无效请求 */
    private data class RequestArgs(val uid: Int, val pid: Int, val requestCode: Int, val ai: ApplicationInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PermissionScreen() }
        // binder 调用与 5s 等待都不放主线程；结果经 mutableStateOf 回主线程渲染
        Thread { prepare() }.start()
    }

    /** 等 binder → 解析请求参数 → 校验授权能力 → 进入确认 UI（或直接拒绝/结束） */
    private fun prepare() {
        if (waitForBinder()) {
            when (val args = readRequestArgs()) {
                null -> finish()
                else -> showConfirmOrDeny(args)
            }
        } else {
            finish()
        }
    }

    /** 从 intent 解析请求参数；缺任一项返回 null */
    private fun readRequestArgs(): RequestArgs? {
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        val extrasOk = uid != -1 && pid != -1 && requestCode != -1
        val ai = readApplicationInfoExtra()
        if (!extrasOk || ai == null) {
            return null
        }
        return RequestArgs(uid, pid, requestCode, ai)
    }

    /** shell 权限受限（无线调试权限被收窄）时无法代为授权：按一次性拒绝收场 */
    private fun showConfirmOrDeny(args: RequestArgs) {
        val canGrant = runCatching {
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!canGrant) {
            sendResult(args.uid, args.pid, args.requestCode, allowed = false, onetime = true)
            finish()
            return
        }

        val label = runCatching { args.ai.loadLabel(packageManager).toString() }
            .getOrDefault(args.ai.packageName)
        uiState = Ui(args.uid, args.pid, args.requestCode, label)
    }

    /** 粘性监听器：binder 已缓存时立即回调；否则最多等 5s（对齐官方管理器超时） */
    private fun waitForBinder(): Boolean {
        val latch = CountDownLatch(1)
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                latch.countDown()
                Shizuku.removeBinderReceivedListener(this)
            }
        }
        // 回调投递主线程（粘性分发要求 Handler），latch 在后台线程等待，无死锁
        Shizuku.addBinderReceivedListenerSticky(listener, Handler(Looper.getMainLooper()))
        val received = try {
            latch.await(BINDER_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        Shizuku.removeBinderReceivedListener(listener)
        return received
    }

    private fun readApplicationInfoExtra(): ApplicationInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("applicationInfo")
        }

    /** 把确认结果回传内嵌 server（永久允许会写入其权限持久化配置） */
    private fun sendResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle().apply {
            putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
            putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        }
        runCatching { Shizuku.dispatchPermissionConfirmationResult(uid, pid, requestCode, data) }
    }

    // Composable 函数按惯例首字母大写，豁免 detekt 的函数命名规则
    @Suppress("FunctionNaming")
    @Composable
    private fun PermissionScreen() {
        // 半透明遮罩铺满全屏，卡片居中：嵌入式弹窗观感（非系统 AlertDialog）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            val ui = uiState
            Card(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (ui == null) {
                        Text(
                            "正在连接执行服务…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "若长时间无响应请稍后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "允许「${ui.label}」通过 Shizuku 执行？",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "该应用将获得 shell 级执行权限，可代替你运行系统命令。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                sendResult(ui.uid, ui.pid, ui.requestCode, allowed = false, onetime = true)
                                finish()
                            }) { Text("拒绝（一次性）") }
                            TextButton(onClick = {
                                sendResult(ui.uid, ui.pid, ui.requestCode, allowed = true, onetime = false)
                                finish()
                            }) { Text("允许（永久）") }
                        }
                    }
                }
            }
        }
    }
}
