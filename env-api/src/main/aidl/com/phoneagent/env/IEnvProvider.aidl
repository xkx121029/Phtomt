package com.phoneagent.env;

import com.phoneagent.env.IEnvCallback;

interface IEnvProvider {

    /** 契约版本：增删方法时递增（主应用据此判断组件是否需要更新） */
    int apiVersion();

    /** Linux 环境（proot + rootfs）是否已装配 */
    boolean linuxReady();

    /** 当前工具链状态（JSON，键见 EnvApi） */
    String stateJson();

    /** 启动一次安装或构建；请求体是 JSON，见 EnvApi 的 OP_* 与 KEY_* */
    oneway void run(String requestJson, IEnvCallback callback);

    /** 取消正在执行的任务（下载/安装/构建都会被中断） */
    oneway void cancel();
}
