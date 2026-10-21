package com.phoneagent.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/** 一次有效录音的捕获结果（松手时由引擎同步返回） */
data class VoiceCapture(
    /** 录音文件路径；系统 SpeechRecognizer 不暴露录音文件，此时为 null */
    val filePath: String?,
    val durationMs: Long,
)

/**
 * 音频采集与识别引擎抽象 —— 本组件与音频处理的解耦点。
 *
 * [VoiceRecordField] 只认这个接口：组件内不出现任何 AudioRecord / SpeechRecognizer /
 * 网络请求 / ASR 调用；换识别实现（本地 wav → 云端 ASR、系统识别、假数据）只需换注入的引擎。
 *
 * 约定：
 * - [level] 返回已做 dB 归一化的原始电平 0..1，一阶低通平滑由 UI 帧循环完成（见组件注释）；
 * - [finalText] 为松手后异步产出的最终识别文本（start 时必须清空）；
 * - [stop] 立即同步返回捕获元数据，识别文本允许晚于松手到达（浮层不等待识别结果）。
 */
interface VoiceRecordEngine {
    val level: StateFlow<Float>
    val finalText: StateFlow<String?>
    fun start(): Boolean
    fun stop(): VoiceCapture?
    fun cancel()
}

/**
 * 演示引擎：模拟音量起伏（正弦叠加），600ms 后产出一条假识别文本。
 * 不写盘，filePath 为占位字符串 —— 仅用于调 UI 与手势。
 */
class FakeVoiceEngine : VoiceRecordEngine {

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val _finalText = MutableStateFlow<String?>(null)
    override val finalText: StateFlow<String?> = _finalText

    private var scope: CoroutineScope? = null
    private var startAt = 0L

    override fun start(): Boolean {
        startAt = SystemClock.elapsedRealtime()
        _finalText.value = null
        val s = CoroutineScope(Dispatchers.Default + Job())
        scope = s
        s.launch {
            var tick = 0
            while (isActive) {
                // 叠加两个不同频率的正弦 + 少量噪声，模拟说话起伏
                val v = 0.42f + 0.26f * sin(tick / 5.2f) +
                    0.09f * sin(tick / 1.7f) + (0..4).random() / 100f
                _level.value = v.coerceIn(0f, 1f)
                tick++
                delay(50)
            }
        }
        s.launch {
            delay(600)
            _finalText.value = "帮我设一个明早七点的闹钟"
        }
        return true
    }

    override fun stop(): VoiceCapture? {
        scope?.cancel()
        scope = null
        _level.value = 0f
        return VoiceCapture(
            filePath = null,
            durationMs = SystemClock.elapsedRealtime() - startAt,
        )
    }

    override fun cancel() {
        scope?.cancel()
        scope = null
        _level.value = 0f
    }
}

/**
 * 系统识别引擎：走 SpeechRecognizer（离线优先，由系统决定识别实现）。
 *
 * 音量来源说明：SpeechRecognizer 独占麦克风，不能再开 AudioRecord 采 PCM，
 * 电平取 RecognitionListener#onRmsChanged 的 rmsdB 按规格公式归一化
 * （该回调在部分 ROM 上不可靠，见 start() 里的兜底脉冲）。
 */
class SpeechRecognizerEngine(private val context: Context) : VoiceRecordEngine {

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val _finalText = MutableStateFlow<String?>(null)
    override val finalText: StateFlow<String?> = _finalText

    private var recognizer: SpeechRecognizer? = null
    private var fallbackJob: Job? = null
    private var startAt = 0L
    private var cancelled = false
    private var lastRmsAt = 0L

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            lastRmsAt = SystemClock.elapsedRealtime()
            // 规格公式：level = clamp((dB + 60) / 60, 0, 1)；rmsdB 已是对数刻度
            _level.value = ((rmsdB + 60f) / 60f).coerceIn(0f, 1f)
        }

        override fun onResults(results: Bundle) {
            if (cancelled) return
            _finalText.value = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            _level.value = 0f
        }

        override fun onError(error: Int) {
            if (!cancelled) _finalText.value = "" // 识别失败：给空串，由调用方提示
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        cancelled = false
        _finalText.value = null
        _level.value = 0f
        lastRmsAt = SystemClock.elapsedRealtime()
        startAt = lastRmsAt
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
        // 兜底：部分 ROM 不回调 onRmsChanged，用轻脉冲动效保持气泡活性
        fallbackJob = CoroutineScope(Dispatchers.Default + Job()).launch {
            while (isActive) {
                if (SystemClock.elapsedRealtime() - lastRmsAt > 400) {
                    val t = SystemClock.elapsedRealtime() / 1000f
                    _level.value = 0.22f + 0.06f * sin(t * 6f)
                }
                delay(50)
            }
        }
        return true
    }

    override fun stop(): VoiceCapture? {
        fallbackJob?.cancel()
        recognizer?.stopListening()
        _level.value = 0f
        return VoiceCapture(filePath = null, durationMs = SystemClock.elapsedRealtime() - startAt)
    }

    override fun cancel() {
        cancelled = true
        fallbackJob?.cancel()
        recognizer?.cancel()
        _level.value = 0f
    }

    /** 界面销毁时调用，释放 recognizer */
    fun destroy() {
        fallbackJob?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
