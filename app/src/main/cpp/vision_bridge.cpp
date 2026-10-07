// vision_bridge.cpp —— 端侧视觉推理 JNI 垫片
//
// 运行流程（整体借鉴 tools/mtmd/mtmd-cli.cpp）：
//  1. llama_model_load_from_file 加载主模型 + llama_init_from_model 建上下文
//  2. mtmd_init_from_file 加载 mmproj 视觉投影，得到 mtmd_context
//  3. 构造 ChatML 提示（内含默认媒体占位符 <__media__>），传入 RGB 位图
//  4. mtmd_tokenize 将文本+图片切成 chunk；逐 chunk 调 mtmd_helper_eval_chunk_single
//  5. 采样循环生成，直到 EOG；detokenize 拼接为文本返回
//
// 本文件不链接 libllama/libmtmd，全部符号通过 dlopen+dlsym 解析，保证与 jniLibs 里的 so 一致。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <string>
#include <vector>
#include <cstdio>
#include <chrono>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "VisionBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 运行时句柄
void * g_libllama = nullptr;
void * g_libmtmd = nullptr;

// ------- llama.h 函数指针（经 dlsym 解析） -------
using llama_model_default_params_t = struct llama_model_params (*)(void);
using llama_model_load_from_file_t = struct llama_model * (*)(const char *, const struct llama_model_params &);
using llama_model_free_t = void (*)(struct llama_model *);
using llama_context_default_params_t = struct llama_context_params (*)(void);
using llama_init_from_model_t = struct llama_context * (*)(struct llama_model *, const struct llama_context_params &);
using llama_free_t = void (*)(struct llama_context *);
using llama_get_model_t = const struct llama_model * (*)(const struct llama_context *);
using llama_model_n_embd_t = int32_t (*)(const struct llama_model *);
using llama_model_get_vocab_t = const struct llama_vocab * (*)(const struct llama_model *);
using llama_model_chat_template_t = const char * (*)(const struct llama_model *, const char *);
using llama_vocab_is_eog_t = bool (*)(const struct llama_vocab *, llama_token);
using llama_token_to_piece_t = int32_t (*)(const struct llama_vocab *, llama_token, char *, int32_t, int32_t, bool);
using llama_decode_t = int32_t (*)(struct llama_context *, struct llama_batch);
using llama_batch_init_t = struct llama_batch (*)(int32_t, int32_t, int32_t);
using llama_batch_free_t = void (*)(struct llama_batch);
using llama_set_rng_seed_t = void (*)(struct llama_context *, uint32_t);
using llama_sampler_chain_init_t = struct llama_sampler * (*)(struct llama_sampler_chain_params);
using llama_sampler_chain_default_params_t = struct llama_sampler_chain_params (*)(void);
using llama_sampler_chain_add_t = void (*)(struct llama_sampler *, struct llama_sampler *);
using llama_sampler_init_top_k_t = struct llama_sampler * (*)(int32_t);
using llama_sampler_init_top_p_t = struct llama_sampler * (*)(float, size_t);
using llama_sampler_init_temp_t = struct llama_sampler * (*)(float);
using llama_sampler_free_t = void (*)(struct llama_sampler *);
using llama_sampler_sample_t = llama_token (*)(struct llama_sampler *, struct llama_context *, int32_t);
using llama_set_embeddings_t = void (*)(struct llama_context *, bool);

// ------- mtmd.h 函数指针 -------
using mtmd_context_params_default_t = struct mtmd_context_params (*)(void);
using mtmd_init_from_file_t = mtmd_context * (*)(const char *, const struct llama_model *, const struct mtmd_context_params &);
using mtmd_free_t = void (*)(mtmd_context *);
using mtmd_default_marker_t = const char * (*)(void);
using mtmd_bitmap_init_t = mtmd_bitmap * (*)(uint32_t, uint32_t, const unsigned char *);
using mtmd_bitmap_free_t = void (*)(mtmd_bitmap *);
using mtmd_input_chunks_init_t = mtmd_input_chunks * (*)(void);
using mtmd_input_chunks_free_t = void (*)(mtmd_input_chunks *);
using mtmd_tokenize_t = int32_t (*)(mtmd_context *, mtmd_input_chunks *, const mtmd_input_text *, const mtmd_bitmap **, size_t);
using mtmd_input_chunks_size_t = size_t (*)(const mtmd_input_chunks *);
using mtmd_input_chunks_get_t = const mtmd_input_chunk * (*)(const mtmd_input_chunks *, size_t);
using mtmd_input_chunk_get_type_t = enum mtmd_input_chunk_type (*)(const mtmd_input_chunk *);
using mtmd_input_chunk_get_n_tokens_t = size_t (*)(const mtmd_input_chunk *);
using mtmd_get_output_embd_t = float * (*)(mtmd_context *);
using mtmd_log_set_t = void (*)(ggml_log_callback, void *);
using mtmd_helper_eval_chunk_single_t = int32_t (*)(mtmd_context *, struct llama_context *, const mtmd_input_chunk *, llama_pos, llama_seq_id, int32_t, bool, llama_pos *);
using mtmd_helper_get_n_tokens_t = size_t (*)(const mtmd_input_chunks *);
using mtmd_batch_init_t = mtmd_batch * (*)(mtmd_context *);
using mtmd_batch_free_t = void (*)(mtmd_batch *);
using mtmd_batch_add_chunk_t = int32_t (*)(mtmd_batch *, const mtmd_input_chunk *);
using mtmd_batch_encode_t = int32_t (*)(mtmd_batch *);
using mtmd_batch_get_output_embd_t = float * (*)(mtmd_batch *, const mtmd_input_chunk *);
using mtmd_decode_use_mrope_t = bool (*)(const mtmd_context *);
using mtmd_image_tokens_get_n_tokens_t = size_t (*)(const mtmd_image_tokens *);
using mtmd_input_chunk_get_tokens_image_t = const mtmd_image_tokens * (*)(const mtmd_input_chunk *);
using mtmd_image_tokens_get_decoder_pos_t = struct mtmd_decoder_pos (*)(const mtmd_image_tokens *, llama_pos, size_t);
using mtmd_helper_decode_image_chunk_t = int32_t (*)(mtmd_context *, struct llama_context *, const mtmd_input_chunk *, float *, llama_pos, llama_seq_id, int32_t, llama_pos *, void *, void *);
using mtmd_decode_use_non_causal_t = bool (*)(const mtmd_context *, const mtmd_input_chunk *);

// 全局函数指针，加载 .so 时填充
// llama
llama_model_default_params_t        pf_llama_model_default_params;
llama_model_load_from_file_t        pf_llama_model_load_from_file;
llama_model_free_t                  pf_llama_model_free;
llama_context_default_params_t      pf_llama_context_default_params;
llama_init_from_model_t             pf_llama_init_from_model;
llama_free_t                        pf_llama_free;
llama_get_model_t                   pf_llama_get_model;
llama_model_get_vocab_t             pf_llama_model_get_vocab;
llama_model_chat_template_t         pf_llama_model_chat_template;
llama_vocab_is_eog_t                pf_llama_vocab_is_eog;
llama_token_to_piece_t              pf_llama_token_to_piece;
llama_decode_t                      pf_llama_decode;
llama_batch_init_t                  pf_llama_batch_init;
llama_batch_free_t                  pf_llama_batch_free;
llama_set_rng_seed_t                pf_llama_set_rng_seed;
llama_sampler_chain_init_t          pf_llama_sampler_chain_init;
llama_sampler_chain_default_params_t pf_llama_sampler_chain_default_params;
llama_sampler_chain_add_t           pf_llama_sampler_chain_add;
llama_sampler_init_top_k_t          pf_llama_sampler_init_top_k;
llama_sampler_init_top_p_t          pf_llama_sampler_init_top_p;
llama_sampler_init_temp_t           pf_llama_sampler_init_temp;
llama_sampler_free_t                pf_llama_sampler_free;
llama_sampler_sample_t              pf_llama_sampler_sample;
llama_set_embeddings_t              pf_llama_set_embeddings;

// mtmd
mtmd_context_params_default_t       pf_mtmd_context_params_default;
mtmd_init_from_file_t               pf_mtmd_init_from_file;
mtmd_free_t                         pf_mtmd_free;
mtmd_default_marker_t               pf_mtmd_default_marker;
mtmd_bitmap_init_t                  pf_mtmd_bitmap_init;
mtmd_bitmap_free_t                  pf_mtmd_bitmap_free;
mtmd_input_chunks_init_t            pf_mtmd_input_chunks_init;
mtmd_input_chunks_free_t            pf_mtmd_input_chunks_free;
mtmd_tokenize_t                     pf_mtmd_tokenize;
mtmd_input_chunks_size_t            pf_mtmd_input_chunks_size;
mtmd_input_chunks_get_t             pf_mtmd_input_chunks_get;
mtmd_input_chunk_get_type_t         pf_mtmd_input_chunk_get_type;
mtmd_input_chunk_get_n_tokens_t     pf_mtmd_input_chunk_get_n_tokens;
mtmd_get_output_embd_t              pf_mtmd_get_output_embd;
mtmd_log_set_t                      pf_mtmd_log_set;
mtmd_helper_eval_chunk_single_t     pf_mtmd_helper_eval_chunk_single;
mtmd_helper_get_n_tokens_t          pf_mtmd_helper_get_n_tokens;
mtmd_decode_use_mrope_t             pf_mtmd_decode_use_mrope;
mtmd_image_tokens_get_n_tokens_t    pf_mtmd_image_tokens_get_n_tokens;
mtmd_input_chunk_get_tokens_image_t pf_mtmd_input_chunk_get_tokens_image;
mtmd_image_tokens_get_decoder_pos_t pf_mtmd_image_tokens_get_decoder_pos;
mtmd_helper_decode_image_chunk_t    pf_mtmd_helper_decode_image_chunk;
mtmd_decode_use_non_causal_t        pf_mtmd_decode_use_non_causal;
mtmd_batch_init_t                   pf_mtmd_batch_init;
mtmd_batch_free_t                   pf_mtmd_batch_free;
mtmd_batch_add_chunk_t              pf_mtmd_batch_add_chunk;
mtmd_batch_encode_t                 pf_mtmd_batch_encode;
mtmd_batch_get_output_embd_t        pf_mtmd_batch_get_output_embd;

template <typename F>
F load_sym(void * handle, const char * name) {
    dlerror();
    void * p = dlsym(handle, name);
    if (!p) {
        LOGE("dlsym failed for %s: %s", name, dlerror());
    }
    return reinterpret_cast<F>(p);
}

#define LOAD_LIB(handle, var, name) var = load_sym<decltype(var)>(handle, name)

// 加载 libllama 与 libmtmd，解析全部符号
bool load_native_libs() {
    if (g_libllama && g_libmtmd) return true;

    g_libllama = dlopen("libllama.so", RTLD_LAZY | RTLD_GLOBAL);
    g_libmtmd  = dlopen("libmtmd.so",  RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libllama) { LOGE("dlopen libllama.so failed: %s", dlerror()); return false; }
    if (!g_libmtmd)  { LOGE("dlopen libmtmd.so failed: %s", dlerror()); return false; }

    LOAD_LIB(g_libllama, pf_llama_model_default_params,        "llama_model_default_params");
    LOAD_LIB(g_libllama, pf_llama_model_load_from_file,        "llama_model_load_from_file");
    LOAD_LIB(g_libllama, pf_llama_model_free,                  "llama_model_free");
    LOAD_LIB(g_libllama, pf_llama_context_default_params,      "llama_context_default_params");
    LOAD_LIB(g_libllama, pf_llama_init_from_model,             "llama_init_from_model");
    LOAD_LIB(g_libllama, pf_llama_free,                        "llama_free");
    LOAD_LIB(g_libllama, pf_llama_get_model,                   "llama_get_model");
    LOAD_LIB(g_libllama, pf_llama_model_get_vocab,             "llama_model_get_vocab");
    LOAD_LIB(g_libllama, pf_llama_model_chat_template,         "llama_model_chat_template");
    LOAD_LIB(g_libllama, pf_llama_vocab_is_eog,                "llama_vocab_is_eog");
    LOAD_LIB(g_libllama, pf_llama_token_to_piece,              "llama_token_to_piece");
    LOAD_LIB(g_libllama, pf_llama_decode,                      "llama_decode");
    LOAD_LIB(g_libllama, pf_llama_batch_init,                  "llama_batch_init");
    LOAD_LIB(g_libllama, pf_llama_batch_free,                  "llama_batch_free");
    LOAD_LIB(g_libllama, pf_llama_set_rng_seed,                "llama_set_rng_seed");
    LOAD_LIB(g_libllama, pf_llama_sampler_chain_init,          "llama_sampler_chain_init");
    LOAD_LIB(g_libllama, pf_llama_sampler_chain_default_params,"llama_sampler_chain_default_params");
    LOAD_LIB(g_libllama, pf_llama_sampler_chain_add,           "llama_sampler_chain_add");
    LOAD_LIB(g_libllama, pf_llama_sampler_init_top_k,          "llama_sampler_init_top_k");
    LOAD_LIB(g_libllama, pf_llama_sampler_init_top_p,          "llama_sampler_init_top_p");
    LOAD_LIB(g_libllama, pf_llama_sampler_init_temp,           "llama_sampler_init_temp");
    LOAD_LIB(g_libllama, pf_llama_sampler_free,                "llama_sampler_free");
    LOAD_LIB(g_libllama, pf_llama_sampler_sample,              "llama_sampler_sample");
    LOAD_LIB(g_libllama, pf_llama_set_embeddings,              "llama_set_embeddings");

    LOAD_LIB(g_libmtmd, pf_mtmd_context_params_default,        "mtmd_context_params_default");
    LOAD_LIB(g_libmtmd, pf_mtmd_init_from_file,                "mtmd_init_from_file");
    LOAD_LIB(g_libmtmd, pf_mtmd_free,                          "mtmd_free");
    LOAD_LIB(g_libmtmd, pf_mtmd_default_marker,                "mtmd_default_marker");
    LOAD_LIB(g_libmtmd, pf_mtmd_bitmap_init,                   "mtmd_bitmap_init");
    LOAD_LIB(g_libmtmd, pf_mtmd_bitmap_free,                   "mtmd_bitmap_free");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunks_init,             "mtmd_input_chunks_init");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunks_free,             "mtmd_input_chunks_free");
    LOAD_LIB(g_libmtmd, pf_mtmd_tokenize,                      "mtmd_tokenize");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunks_size,             "mtmd_input_chunks_size");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunks_get,              "mtmd_input_chunks_get");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunk_get_type,          "mtmd_input_chunk_get_type");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunk_get_n_tokens,      "mtmd_input_chunk_get_n_tokens");
    LOAD_LIB(g_libmtmd, pf_mtmd_get_output_embd,               "mtmd_get_output_embd");
    LOAD_LIB(g_libmtmd, pf_mtmd_log_set,                       "mtmd_log_set");
    LOAD_LIB(g_libmtmd, pf_mtmd_helper_eval_chunk_single,      "mtmd_helper_eval_chunk_single");
    LOAD_LIB(g_libmtmd, pf_mtmd_helper_get_n_tokens,           "mtmd_helper_get_n_tokens");
    LOAD_LIB(g_libmtmd, pf_mtmd_decode_use_mrope,              "mtmd_decode_use_mrope");
    LOAD_LIB(g_libmtmd, pf_mtmd_image_tokens_get_n_tokens,     "mtmd_image_tokens_get_n_tokens");
    LOAD_LIB(g_libmtmd, pf_mtmd_input_chunk_get_tokens_image,  "mtmd_input_chunk_get_tokens_image");
    LOAD_LIB(g_libmtmd, pf_mtmd_image_tokens_get_decoder_pos,  "mtmd_image_tokens_get_decoder_pos");
    LOAD_LIB(g_libmtmd, pf_mtmd_helper_decode_image_chunk,     "mtmd_helper_decode_image_chunk");
    LOAD_LIB(g_libmtmd, pf_mtmd_decode_use_non_causal,         "mtmd_decode_use_non_causal");
    LOAD_LIB(g_libmtmd, pf_mtmd_batch_init,                    "mtmd_batch_init");
    LOAD_LIB(g_libmtmd, pf_mtmd_batch_free,                    "mtmd_batch_free");
    LOAD_LIB(g_libmtmd, pf_mtmd_batch_add_chunk,               "mtmd_batch_add_chunk");
    LOAD_LIB(g_libmtmd, pf_mtmd_batch_encode,                  "mtmd_batch_encode");
    LOAD_LIB(g_libmtmd, pf_mtmd_batch_get_output_embd,         "mtmd_batch_get_output_embd");

    return true;
}

// 视觉模型运行时上下文
struct VisionModel {
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    llama_vocab * vocab = nullptr; // 无所有权
    mtmd_context * mctx = nullptr;
    int n_threads = 4;
    bool loaded = false;
    std::string error;

    ~VisionModel() {
        if (mctx)  pf_mtmd_free(mctx);
        if (lctx)  pf_llama_free(lctx);
        if (model) pf_llama_model_free(model);
    }

    bool init(const std::string & model_path, const std::string & mmproj_path, int threads) {
        n_threads = threads > 0 ? threads : 4;

        llama_model_params mparams = pf_llama_model_default_params();
        // Android 端侧：全部走 CPU
        mparams.n_gpu_layers = 0;
        model = pf_llama_model_load_from_file(model_path.c_str(), mparams);
        if (!model) { error = "加载主模型失败"; return false; }

        vocab = (llama_vocab *) pf_llama_model_get_vocab(model);

        llama_context_params cparams = pf_llama_context_default_params();
        cparams.n_ctx = 8192;
        cparams.n_batch = 512;
        cparams.n_ubatch = 512;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        cparams.embeddings = false;
        lctx = pf_llama_init_from_model(model, cparams);
        if (!lctx) { error = "创建文本上下文失败"; return false; }

        mtmd_context_params mparams2 = pf_mtmd_context_params_default();
        mparams2.use_gpu = false;
        mparams2.n_threads = n_threads;
        mparams2.print_timings = false;
        mparams2.warmup = false;
        mctx = pf_mtmd_init_from_file(mmproj_path.c_str(), model, mparams2);
        if (!mctx) { error = "加载视觉投影(mmproj)失败"; return false; }

        loaded = true;
        return true;
    }
};

// 将 token 序列 detokenize 为 UTF-8 文本
std::string detokenize(const llama_vocab * vocab, const std::vector<llama_token> & tokens) {
    std::string out;
    char buf[512];
    for (llama_token t : tokens) {
        int n = pf_llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
    }
    return out;
}

} // namespace

// ------- JNI -------

extern "C" JNIEXPORT jstring JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeLoadLibs(JNIEnv * env, jclass) {
    if (!load_native_libs()) {
        return env->NewStringUTF("加载原生库失败");
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeInit(
        JNIEnv * env, jclass, jstring modelPath, jstring mmprojPath, jint threads) {
    if (!load_native_libs()) {
        LOGE("native libs not loaded");
        return 0;
    }
    const char * mp = env->GetStringUTFChars(modelPath, nullptr);
    const char * pp = env->GetStringUTFChars(mmprojPath, nullptr);
    VisionModel * vm = new VisionModel();
    bool ok = vm->init(mp ? mp : "", pp ? pp : "", threads);
    env->ReleaseStringUTFChars(modelPath, mp);
    env->ReleaseStringUTFChars(mmprojPath, pp);
    if (!ok) {
        LOGE("VISION init failed: %s", vm->error.c_str());
        delete vm;
        return 0;
    }
    return reinterpret_cast<jlong>(vm);
}

extern "C" JNIEXPORT void JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) {
        VisionModel * vm = reinterpret_cast<VisionModel *>(handle);
        delete vm;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeVersion(JNIEnv * env, jclass) {
    if (!load_native_libs()) return env->NewStringUTF("unavailable");
    return env->NewStringUTF("llama.cpp mtmd 视觉引擎");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeError(JNIEnv * env, jclass, jlong handle) {
    VisionModel * vm = reinterpret_cast<VisionModel *>(handle);
    if (!vm) return env->NewStringUTF("");
    return env->NewStringUTF(vm->error.c_str());
}

// 图像推理主入口：
//   nativeDetect(handle, rgbBytes, width, height, userPrompt, maxTokens, temperature) -> String(模型文本)
extern "C" JNIEXPORT jstring JNICALL
Java_com_phoneagent_device_vision_NativeVisionEngine_nativeDetect(
        JNIEnv * env, jclass, jlong handle,
        jbyteArray rgb, jint width, jint height,
        jstring userPrompt, jint maxTokens, jfloat temperature) {
    if (!handle) return env->NewStringUTF("__ERR_NOT_LOADED__");
    VisionModel * vm = reinterpret_cast<VisionModel *>(handle);
    if (!vm->loaded) return env->NewStringUTF("__ERR_NOT_READY__");

    // ---- 推理看门狗：单次推理超过该秒数则主动中断并返回超时错误，避免端侧 3B 在 CPU 上"卡死" ----
    const long TIMEOUT_S = 120;
    const auto t0 = std::chrono::steady_clock::now();
    const auto elapsed_s = [&]() -> long {
        return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - t0).count();
    };

    jbyte * rgbData = env->GetByteArrayElements(rgb, nullptr);
    const unsigned char * img = reinterpret_cast<const unsigned char *>(rgbData);

    // 默认提示词：让模型描述屏幕上的控件并给出归一化坐标框
    std::string user_text;
    {
        const char * user = env->GetStringUTFChars(userPrompt, nullptr);
        user_text = user ? user : "";
        env->ReleaseStringUTFChars(userPrompt, user);
    }

    // ChatML（Qwen2.5-VL）提示，图片占位符替换为默认媒体标记 <__media__>
    const char * media = pf_mtmd_default_marker();
    if (!media) media = "<__media__>";
    std::string prompt = "<|im_start|>system\nYou are a UI automation assistant. Analyze the screenshot.\n"
                         "List the interactive UI controls. For each, return it as JSON array of objects with fields: "
                         "{\"role\": \"button|input|switch|text|scrollable|icon\", \"purpose\": \"中文用途\", "
                         "\"x\": 归一化中心x(0~1), \"y\": 归一化中心y(0~1), \"w\": 归一化宽度, \"h\": 归一化高度, \"text\": \"控件文本或图标描述\"}. "
                         "Only output the JSON array, no explanation.<|im_end|>\n"
                         "<|im_start|>user\n请分析这张屏幕截图：" + std::string(media) + "\n" + user_text +
                         "<|im_end|>\n<|im_start|>assistant\n";

    mtmd_bitmap * bitmap = pf_mtmd_bitmap_init((uint32_t) width, (uint32_t) height, img);
    env->ReleaseByteArrayElements(rgb, rgbData, JNI_ABORT);

    if (!bitmap) {
        return env->NewStringUTF("__ERR_BITMAP__");
    }

    mtmd_input_text text;
    text.text = prompt.data();
    text.text_len = prompt.size();
    text.add_special = false;   // 手动构造了 ChatML，由 parser 处理特殊 token
    text.parse_special = true;

    mtmd_input_chunks * chunks = pf_mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = { bitmap };
    int32_t res = pf_mtmd_tokenize(vm->mctx, chunks, &text, bitmaps, 1);
    pf_mtmd_bitmap_free(bitmap);
    if (res != 0) {
        pf_mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("__ERR_TOKENIZE__");
    }

    // 逐 chunk 编码/解码，最后一 chunk 的最后一个 token 需输出 logits 供采样
    size_t n_chunks = pf_mtmd_input_chunks_size(chunks);
    llama_pos n_past = 0;
    for (size_t i = 0; i < n_chunks; i++) {
        if (elapsed_s() >= TIMEOUT_S) {
            pf_mtmd_input_chunks_free(chunks);
            LOGE("推理超时（%lds）在图像编码阶段，中断", elapsed_s());
            return env->NewStringUTF("__ERR_TIMEOUT__");
        }
        const mtmd_input_chunk * chunk = pf_mtmd_input_chunks_get(chunks, i);
        bool logits_last = (i == n_chunks - 1);
        llama_pos new_n_past = n_past;
        int32_t r = pf_mtmd_helper_eval_chunk_single(vm->mctx, vm->lctx, chunk, n_past, 0, 512, logits_last, &new_n_past);
        if (r != 0) {
            pf_mtmd_input_chunks_free(chunks);
            return env->NewStringUTF("__ERR_EVAL__");
        }
        n_past = new_n_past;
    }
    pf_mtmd_input_chunks_free(chunks);

    // 采样生成
    llama_sampler_chain_params sp = pf_llama_sampler_chain_default_params();
    sp.no_perf = true;
    struct llama_sampler * smpl = pf_llama_sampler_chain_init(sp);
    pf_llama_sampler_chain_add(smpl, pf_llama_sampler_init_temp(temperature > 0 ? temperature : 0.2f));
    pf_llama_sampler_chain_add(smpl, pf_llama_sampler_init_top_k(20));
    pf_llama_sampler_chain_add(smpl, pf_llama_sampler_init_top_p(0.9f, 1));

    const int n_max = maxTokens > 0 ? (maxTokens < 2048 ? maxTokens : 2048) : 1024;
    std::vector<llama_token> gen_tokens;
    gen_tokens.reserve(n_max);

    llama_batch batch = pf_llama_batch_init(1, 0, 1);
    for (int i = 0; i < n_max; i++) {
        if (elapsed_s() >= TIMEOUT_S) {
            LOGE("推理超时（%lds）在生成阶段，中断", elapsed_s());
            pf_llama_batch_free(batch);
            pf_llama_sampler_free(smpl);
            return env->NewStringUTF("__ERR_TIMEOUT__");
        }
        llama_token id = pf_llama_sampler_sample(smpl, vm->lctx, -1);
        if (pf_llama_vocab_is_eog(vm->vocab, id)) {
            break;
        }
        gen_tokens.push_back(id);
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        if (pf_llama_decode(vm->lctx, batch)) {
            break;
        }
    }
    pf_llama_batch_free(batch);

    std::string out = detokenize(vm->vocab, gen_tokens);
    pf_llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}