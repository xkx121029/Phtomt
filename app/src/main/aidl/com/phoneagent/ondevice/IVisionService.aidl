// AIDL 跨进程视觉识别接口（与本地视觉 Agent 外挂 APK 保持一致）。
// 主程序（com.phoneagent）绑定外挂服务，调用端侧 3B 视觉模型进行控件框选与分析。
// 所有坐标均为归一化比例（0~1）。
package com.phoneagent.ondevice;

interface IVisionService {
    boolean isEngineReady();

    boolean loadModel();

    String detectControls(in byte[] rgba, int width, int height, String userPrompt);

    String analyze(in byte[] rgba, int width, int height, String userPrompt);

    String locate(in byte[] rgba, int width, int height, String targetText);

    int getCallCount();
}