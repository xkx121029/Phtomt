package com.phoneagent.env;

/**
 * 环境组件 → 主应用的单向回调。
 *
 * 全部方法都是 oneway：服务端在后台线程里跑长任务，回调只做「把最新状态丢回客户端」，
 * 不需要等待客户端返回。若走双向调用，客户端主线程一忙就会把安装线程卡住。
 */
oneway interface IEnvCallback {

    /** 一步开始或结束时上报；[state] 取 "start" / "ok" / "fail" */
    void onStep(int index, int total, String title, String state);

    /** 安装 / 构建过程的原始日志行（UI 直接滚动展示，也是排障的第一手材料） */
    void onLog(String line);

    /** 整个请求结束；[ok]=false 时 [message] 就是失败原因 */
    void onFinished(boolean ok, String message);
}
