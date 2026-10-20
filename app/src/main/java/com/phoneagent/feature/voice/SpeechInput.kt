package com.phoneagent.feature.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * 端侧语音输入：走系统 RecognitionService（SpeechRecognizer 内嵌识别，不弹系统全屏对话框），
 * 国产 ROM 均内置实现，不依赖 GMS。
 *
 * 生命周期：[start] 开始监听 → 识别结束/出错自动释放；[release] 在组合销毁时兜底。
 * 错误以中文回调（用户规则：错误信息显示为中文），调用方自行决定呈现方式。
 */
class SpeechInput(private val onResult: (String) -> Unit, private val onError: (String) -> Unit) {

    private var recognizer: SpeechRecognizer? = null

    /** 是否已授予录音权限（SpeechRecognizer 自 Android 11 起强制要求 RECORD_AUDIO） */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            com.phoneagent.PhoneAgentApp.appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /** 权限清单里需要的危险权限 */
    fun requiredPermission(): String = Manifest.permission.RECORD_AUDIO

    /** 开始识别；权限未授予或本机无识别实现时走中文错误回调 */
    fun start() {
        if (!hasPermission()) {
            onError("缺少麦克风权限，请允许后重试")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(com.phoneagent.PhoneAgentApp.appContext)) {
            onError("本机没有可用的语音识别服务")
            return
        }
        release()
        val rec = SpeechRecognizer.createSpeechRecognizer(com.phoneagent.PhoneAgentApp.appContext)
        rec.setRecognitionListener(listener)
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // 中文优先；识别服务按系统语言自行兜底
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            },
        )
        recognizer = rec
    }

    /** 主动停止并释放（组合销毁 / 用户取消时调用） */
    fun release() {
        recognizer?.runCatching {
            stopListening()
            destroy()
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) onError("没有听清内容，请再说一次") else onResult(text)
            release()
        }

        override fun onError(error: Int) {
            onError(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清内容，请再说一次"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限，请到系统设置中允许"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务网络异常，请稍后重试"
                SpeechRecognizer.ERROR_AUDIO -> "录音异常，请检查麦克风"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试"
                SpeechRecognizer.ERROR_CLIENT -> "识别服务异常，请重试"
                else -> "识别失败（错误码 $error）"
            })
            release()
        }
    }
}
