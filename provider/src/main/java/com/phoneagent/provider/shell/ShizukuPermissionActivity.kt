package com.phoneagent.provider.shell

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
 * UI 采用应用内嵌入式卡片（非系统弹窗）。主应用原版为 Compose 实现；Provider 模块
 * 无 Compose 依赖，故改用纯 View 代码布局（无任何 res 资源），观感保持一致：
 * 半透明遮罩铺满全屏 + 居中圆角卡片（圆角取主应用 AppRadii.Card = 24dp）。
 * 主题用 Manifest 声明的 @android:style/Theme.Translucent.NoTitleBar（窗口透明，遮罩自绘）。
 */
class ShizukuPermissionActivity : Activity() {

    private companion object {
        /** 等 server binder 的超时秒数（对齐官方管理器的 5s） */
        const val BINDER_WAIT_SECONDS = 5L
    }

    /** 就绪后的确认信息；null 表示仍在等 binder / 校验中 */
    @Volatile
    private var uiState: Ui? = null

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var buttonRow: LinearLayout

    /** 主线程渲染：prepare() 在后台线程跑，结果统一 post 回主线程刷新 View */
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Ui(val uid: Int, val pid: Int, val requestCode: Int, val label: String)

    /** server intent 携带的请求参数；缺任一项视为无效请求 */
    private data class RequestArgs(val uid: Int, val pid: Int, val requestCode: Int, val ai: ApplicationInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        // binder 调用与 5s 等待都不放主线程；结果经 uiState + 主线程 Handler 回渲染
        Thread { prepare() }.start()
    }

    /** 等 binder → 解析请求参数 → 校验授权能力 → 进入确认 UI（或直接拒绝/结束） */
    private fun prepare() {
        if (waitForBinder()) {
            when (val args = readRequestArgs()) {
                null -> finishOnUiThread()
                else -> showConfirmOrDeny(args)
            }
        } else {
            finishOnUiThread()
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
            finishOnUiThread()
            return
        }

        val label = runCatching { args.ai.loadLabel(packageManager).toString() }
            .getOrDefault(args.ai.packageName)
        uiState = Ui(args.uid, args.pid, args.requestCode, label)
        mainHandler.post { renderUiState() }
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

    /** finish 可能从后台线程触发，统一收口主线程 */
    private fun finishOnUiThread() {
        mainHandler.post { finish() }
    }

    /** uiState 就绪后把卡片从「连接中」切换为「确认授权」 */
    private fun renderUiState() {
        val ui = uiState ?: return
        titleView.text = "允许「${ui.label}」通过 Shizuku 执行？"
        bodyView.text = "该应用将获得 shell 级执行权限，可代替你运行系统命令。"
        buttonRow.visibility = View.VISIBLE
    }

    // ==================== 纯代码 UI（无 res 依赖） ====================

    private fun buildContent(): View {
        // 半透明遮罩铺满全屏，卡片居中：嵌入式弹窗观感（非系统 AlertDialog）
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0x73, 0x00, 0x00, 0x00)) // 黑 45%
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat() // 主应用 AppRadii.Card
            }
        }

        titleView = TextView(this).apply {
            setTextColor(0xFF1B1B1F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        bodyView = TextView(this).apply {
            setTextColor(0xFF49454F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        buttonRow.addView(buildTextButton("拒绝（一次性）", filled = false) {
            val ui = uiState ?: return@buildTextButton
            sendResult(ui.uid, ui.pid, ui.requestCode, allowed = false, onetime = true)
            finish()
        })
        buttonRow.addView(buildTextButton("允许（永久）", filled = true) {
            val ui = uiState ?: return@buildTextButton
            sendResult(ui.uid, ui.pid, ui.requestCode, allowed = true, onetime = false)
            finish()
        })

        card.addView(titleView)
        card.addView(TextView(this).apply { // 标题与正文间距（原 Spacer(8.dp)）
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        })
        card.addView(bodyView)
        card.addView(TextView(this).apply { // 正文与按钮行间距（原 Spacer(12.dp)）
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
        })
        card.addView(buttonRow)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(32)
            marginEnd = dp(32)
            gravity = Gravity.CENTER
        }
        scrim.addView(card, cardParams)
        return scrim
    }

    /** 文字按钮：主文案描边/填充两态（原 Compose TextButton 的极简等价物） */
    private fun buildTextButton(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isClickable = true
            setOnClickListener { onClick() }
            if (filled) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF1B1B1F.toInt())
                    cornerRadius = dp(20).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(8), 0, 0, 0) }
            } else {
                setTextColor(0xFF49454F.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(8), 0) }
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
