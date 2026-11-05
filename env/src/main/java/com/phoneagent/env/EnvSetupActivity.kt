package com.phoneagent.env

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 存储权限引导页。
 *
 * 环境组件没有常驻 UI，但 targetSdk 28 访问共享存储（/sdcard 上的工作区）属于运行时权限，
 * 没有界面就永远申请不到。主应用的「环境」页在需要时显式拉起这个页面，用户点一次"允许"即可。
 * 界面用系统控件临时拼出来——整个组件只此一处 UI，为此引入 Compose 不值得。
 */
class EnvSetupActivity : Activity() {

    private companion object {
        const val REQUEST_STORAGE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "编码环境需要存储权限"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        val desc = TextView(this).apply {
            text = "构建 APK 时环境组件要读取共享存储上的工程目录。\n" +
                "授予后本页可关闭，之后不会再打扰。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(24))
        }
        val grant = Button(this).apply {
            text = "授予存储权限"
            setOnClickListener { requestStorage() }
        }
        val close = Button(this).apply {
            text = "稍后再说"
            setOnClickListener { finish() }
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            desc,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            grant,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            close,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.gravity = Gravity.TOP
        setContentView(root)

        if (hasStorage()) finish()
    }

    private fun hasStorage(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        requestPermissions(
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            REQUEST_STORAGE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Toast.makeText(
            this,
            if (granted) "已授予，可以构建了" else "未授予：只能构建应用私有目录里的工程",
            Toast.LENGTH_SHORT,
        ).show()
        if (granted) finish()
    }
}
