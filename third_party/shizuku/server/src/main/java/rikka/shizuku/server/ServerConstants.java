package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    // vendor 适配（内嵌 server）：原值 "moe.shizuku.privileged.api" 指向官方 Shizuku 管理器 App。
    // 本工程把 server 内嵌进权限 Provider 应用（com.phoneagent.provider）：
    // "manager" 即 Provider——server 启动时检查的"管理器已安装"、Binder 直发目标、
    // WRITE_SECURE_SETTINGS 授权对象、权限确认页与结果回调方都指向 Provider 进程，
    // 主应用经 AIDL（IDeviceProvider）间接使用 Shizuku，自身不再持有 Shizuku 客户端。
    // 权限名 PERMISSION 保持不变以兼容 vendored 客户端 API。
    public static final String MANAGER_APPLICATION_ID = "com.phoneagent.provider";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
