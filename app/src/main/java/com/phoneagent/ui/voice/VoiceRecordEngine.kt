package com.phoneagent.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.log10
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
 * - [error] 为中文错误消息（识别服务缺失/出错/没听清等），先于 finalText 到达时由调用方直接提示；
 * - [stop] 立即同步返回捕获元数据，识别文本允许晚于松手到达（浮层不等待识别结果）。
 */
interface VoiceRecordEngine {
    val level: StateFlow<Float>
    val finalText: StateFlow<String?>
    val error: StateFlow<String?>
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

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error

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

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error

    // recognizer 复用单例：每次 start 都新建会导致部分 ROM 泄漏/拒绝服务
    private var recognizer: SpeechRecognizer? = null
    private var fallbackJob: Job? = null
    private var startAt = 0L
    private var cancelled = false
    private var lastRmsAt = 0L
    private var lastPartial: String? = null

    /** SpeechRecognizer 错误码 → 中文提示 */
    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，请检查网络后重试"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，请检查网络后重试"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错，请稍后重试"
        SpeechRecognizer.ERROR_CLIENT -> "设备缺少可用的语音识别服务组件"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话声"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请靠近一点再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务繁忙，请稍后重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        else -> "识别失败（错误码 $code）"
    }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            lastRmsAt = SystemClock.elapsedRealtime()
            // 规格公式：level = clamp((dB + 60) / 60, 0, 1)；rmsdB 已是对数刻度
            _level.value = ((rmsdB + 60f) / 60f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (cancelled) return
            // 记录最后一次部分识别：最终结果为空时兜底
            lastPartial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
        }

        override fun onResults(results: Bundle) {
            if (cancelled) return
            val text = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: lastPartial?.takeIf { it.isNotBlank() }
            if (text == null) {
                _error.value = "没听清，请再试一次"
            } else {
                _finalText.value = text
            }
            _level.value = 0f
        }

        override fun onError(error: Int) {
            if (cancelled) return
            _error.value = errorMessage(error)
            _level.value = 0f
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _error.value = "系统没有可用的语音识别服务（国产 ROM 普遍缺少 Google 语音组件）"
            return false
        }
        cancelled = false
        _finalText.value = null
        _error.value = null
        lastPartial = null
        _level.value = 0f
        lastRmsAt = SystemClock.elapsedRealtime()
        startAt = lastRmsAt
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context)
            .also { recognizer = it }
        rec.apply {
            setRecognitionListener(listener)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
        // 兜底：部分 ROM 不回调 onRmsChanged，用轻脉冲动效保持光晕活性
        fallbackJob?.cancel()
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

/** 云端语音输入 API 配置：OpenAI 兼容 /audio/transcriptions 格式 */
data class CloudAsrConfig(
    /** 形如 https://api.siliconflow.cn/v1（末尾 / 会裁掉） */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/**
 * 云端识别引擎：AudioRecord 真实录 16kHz/单声道/PCM16 → WAV 文件，
 * 松手后 multipart 上传到 OpenAI 兼容的 /audio/transcriptions 端点，取回文本。
 *
 * 电平来源：录音线程里按 50ms 窗口算 RMS，规格公式归一化后发给光晕动画。
 * 上传在松手后异步进行：finalText/error 谁先到谁生效（与调用方既有约定一致）。
 * 录音上限 60s：到点自动停采，文件不再增长，等待松手分发。
 */
class CloudAsrEngine(
    private val context: Context,
    private val config: CloudAsrConfig,
) : VoiceRecordEngine {

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val _finalText = MutableStateFlow<String?>(null)
    override val finalText: StateFlow<String?> = _finalText

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var recordThread: Thread? = null
    private var uploadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var wavFile: File? = null
    private var startAt = 0L
    private var cancelled = false

    @Volatile private var stopRequested = false

    override fun start(): Boolean {
        _finalText.value = null
        _error.value = null
        _level.value = 0f
        if (config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            _error.value = "请先在「云端模型 → 语音输入」里配置 API 地址、密钥与模型"
            return false
        }
        if (ContextCompat_checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _error.value = "缺少麦克风权限"
            return false
        }
        cancelled = false
        stopRequested = false
        startAt = SystemClock.elapsedRealtime()
        val file = File(context.cacheDir, "voice_input_${startAt}.wav")
        wavFile = file

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            _error.value = "录音启动失败：${e.message ?: "麦克风被占用"}"
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            _error.value = "录音启动失败：麦克风不可用或权限未授予"
            return false
        }

        recordThread = Thread {
            audioRecord.startRecording()
            val buf = ShortArray(SAMPLE_RATE / 20) // 50ms 窗口，与规格一致
            var pcmBytes = 0L
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(WAV_HEADER_SIZE)) // 先占头，停止时回填
                while (!stopRequested && pcmBytes < MAX_BYTES) {
                    val n = audioRecord.read(buf, 0, buf.size)
                    if (n <= 0) break
                    // RMS → 归一化电平（规格公式：level = clamp((20*log10(rms)+60)/60, 0, 1)）
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i]
                    val rms = kotlin.math.sqrt(sum / n)
                    _level.value = if (rms < 1.0) 0f
                    else ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte()
                    }
                    out.write(bytes)
                    pcmBytes += bytes.size
                }
            }
            audioRecord.stop()
            audioRecord.release()
            writeWavHeader(file, pcmBytes)
        }.also { it.start() }
        return true
    }

    override fun stop(): VoiceCapture? {
        recordThread?.join(1000)
        recordThread = null
        _level.value = 0f
        val file = wavFile ?: return null
        val durationMs = SystemClock.elapsedRealtime() - startAt
        // 松手即返回；上传异步跑，结果经 finalText/error 流回调用方
        uploadJob = scope.launch {
            try {
                val url = "${config.baseUrl.trimEnd('/')}/audio/transcriptions"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", config.model)
                    .build()
                val resp = http.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer ${config.apiKey}")
                        .post(body)
                        .build(),
                ).execute()
                resp.use { r ->
                    val text = withContext(Dispatchers.Default) {
                        JSONObject(r.body?.string() ?: "{}").optString("text", "").trim()
                    }
                    if (!r.isSuccessful) {
                        _error.value = "识别请求失败（HTTP ${r.code}），请检查 API 配置与额度"
                    } else if (text.isEmpty()) {
                        _error.value = "没听清，请再试一次"
                    } else {
                        _finalText.value = text
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) _error.value = "识别请求失败：${e.message ?: "网络异常"}"
            } finally {
                file.delete() // 识别完成后音频即弃，不留残留
                wavFile = null
            }
        }
        return VoiceCapture(filePath = file.absolutePath, durationMs = durationMs)
    }

    override fun cancel() {
        cancelled = true
        stopRequested = true
        recordThread?.join(1000)
        recordThread = null
        uploadJob?.cancel()
        wavFile?.delete()
        wavFile = null
        _level.value = 0f
    }

    /** 界面销毁时调用：停录音、断上传、清理协程 */
    fun destroy() {
        stopRequested = true
        recordThread?.join(500)
        uploadJob?.cancel()
        wavFile?.delete()
        wavFile = null
        scope.cancel()
    }

    private fun ContextCompat_checkSelfPermission(): Int =
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)

    /** 44 字节标准 WAV 头回填（16kHz / 单声道 / 16bit） */
    private fun writeWavHeader(file: File, pcmBytes: Long) {
        val total = pcmBytes + 36
        RandomAccessFile(file, "rw").use { out ->
            out.seek(0)
            out.writeBytes("RIFF")
            out.writeLe(total.toInt())
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeLe(16)
            out.writeLe(1) // PCM
            out.writeLe(1) // mono
            out.writeLe(SAMPLE_RATE)
            out.writeLe(SAMPLE_RATE * 2)
            out.writeLe(2) // block align
            out.writeLe(16) // bits
            out.writeBytes("data")
            out.writeLe(pcmBytes.toInt())
        }
    }

    private fun RandomAccessFile.writeLe(v: Int) {
        write(v); write(v shr 8); write(v shr 16); write(v shr 24)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
        // 60 秒上限：16bit 单声道 = 32KB/s，封顶防异常时长把文件撑爆
        const val MAX_BYTES = 16_000L * 2 * 60
    }
}
