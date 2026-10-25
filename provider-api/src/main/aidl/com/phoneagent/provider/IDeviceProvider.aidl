package com.phoneagent.provider;

import com.phoneagent.provider.ActionResult;
import com.phoneagent.provider.ShellResultParcel;
import com.phoneagent.provider.A11ySelector;

/**
 * PhoneAgent 权限 Provider 的统一 AIDL 契约。
 *
 * Provider 独立 APK（com.phoneagent.provider）承载三条通道，主应用绑定本接口远程调用：
 * 1. 无障碍：读屏（元素树 JSON / 截图 PNG）+ 执行（手势 / 节点操作 / 全局键 / 语音助手）；
 *    活节点句柄（AccessibilityNodeInfo）不能跨进程，节点定位与执行逻辑整块放在 Provider 进程内，
 *    通过 [A11ySelector] 传递定位线索、[ActionResult] 回传执行结果与实际落点（供主应用画光标）。
 * 2. 内置 Shizuku：server 与授权状态都在 Provider 进程，主应用经此转发 shell 命令。
 * 3. Termux：RUN_COMMAND 权限与结果回传接收器都在 Provider 进程。
 *
 * 所有方法在 Binder 线程回调，Provider 侧自行切协程；主应用侧调用须带超时兜底
 * （Provider 进程被杀时 binder 调用可能抛 DeadObjectException 或永久挂起）。
 */
interface IDeviceProvider {

    // ==================== 状态查询（轻量，可轮询） ====================

    /** 契约版本：主应用据此判断接口兼容性，增删方法时递增 */
    int apiVersion();

    /** Provider 的无障碍服务是否已在系统设置中开启 */
    boolean isA11yEnabled();

    /** Provider 的无障碍服务实例是否已连接（可执行） */
    boolean isA11yConnected();

    /** 内置 Shizuku 三态：0=UNAVAILABLE 1=PERMISSION_DENIED 2=READY */
    int shizukuState();

    /**
     * 请求 Shizuku 权限（授权确认页由 Provider 内的 server 拉起）。
     * 已授权直接返回 true；无响应/超时/失败返回 false（主应用按未授权处理）。
     */
    boolean shizukuRequestPermission();

    /** Termux 是否已安装 */
    boolean termuxInstalled();

    /** 是否已授予 Termux 的 RUN_COMMAND 权限 */
    boolean termuxPermissionGranted();

    // ==================== 无障碍：感知 ====================

    /**
     * 抓取当前屏幕元素树。
     * 返回 JSON：{"stats":"...","missingAccessibility":false,"packageName":"...",
     *   "screenWidth":1080,"screenHeight":2400,"elements":[{...UiElement 字段}]}
     * 字段与主应用 com.phoneagent.domain.model.ScreenSnapshot/UiElement 一一对应。
     * 服务未连接时返回 null。
     */
    String captureScreen();

    /** 无障碍截图（API 30+），返回 PNG 字节；不支持或失败返回 null */
    byte[] takeScreenshot();

    /** 落点审计：屏幕上承接 (x,y) 的控件是谁；返回 JSON NodeHit 或 null */
    String hitTest(int x, int y);

    /**
     * 目标控件此刻的中心点（定位线索重新落到活节点树）。
     * 返回 [x, y]，找不到返回 null。
     */
    int[] liveCenter(in A11ySelector selector);

    // ==================== 无障碍：执行 ====================

    /** 点击屏幕坐标 */
    ActionResult click(int x, int y);

    /** 长按；durationMs &lt;= 0 时用 Provider 内默认按压时长 */
    ActionResult longClick(int x, int y, int durationMs);

    /** 按定位线索在活节点树上找控件并直接 performAction；失败时上层可降级为坐标手势 */
    ActionResult clickNode(in A11ySelector selector, boolean longClick, int durationMs);

    /** 滑动手势 */
    ActionResult swipe(int x1, int y1, int x2, int y2, int durationMs);

    /** 在可滚动容器内滚动一屏；direction = "up"（往下翻看）/"down"（往上翻回） */
    ActionResult scrollContainer(String direction);

    /** 全局按键（GLOBAL_ACTION_BACK/HOME/RECENTS…） */
    boolean globalAction(int action);

    /** 向输入框填充文本（整段覆写）；x/y &lt; 0 时仅靠元素树定位 */
    ActionResult typeText(String text, int x, int y);

    /** 启动应用（LAUNCHER activity） */
    ActionResult launchApp(String packageName);

    /** 用系统应用打开链接/文件；pkg 可空（空时优先系统自带应用） */
    ActionResult openUri(String uri, String pkg);

    /** 用系统 Intent Action 直达指定设置页 */
    ActionResult openSettingsAction(String action);

    /** 唤起系统语音助手并尽力传话；text 可空（仅唤醒） */
    ActionResult launchAssistant(String text);

    /** 外部方式已拉起助手后单独传话；返回中文结果说明 */
    String handoffToAssistant(String text);

    // ==================== shell 通道 ====================

    /** 经内置 Shizuku 执行 shell 命令（Provider 内默认超时 30s 强杀） */
    ShellResultParcel execShizuku(String command);

    /** 经 Termux 执行命令；timeoutMs &lt;= 0 用 Provider 内默认 25s */
    ShellResultParcel execTermux(String command, int timeoutMs);

    /** Termux 实跑探测（echo 探针，覆盖 allow-external-apps 未开等场景） */
    ShellResultParcel termuxProbe();

    // ==================== 内置 Shizuku 的无线 ADB 引导 ====================

    /** 引导状态机快照，JSON（与主应用 AdbStatus 字段一致），主应用 UI 渲染用 */
    String adbStatus();

    /** 确保底层通路就绪（AUTO/SHIZUKU 通道决策在 Provider 内做） */
    ShellResultParcel adbEnsureReady();

    /** 引导式配对：用户在主应用界面输入 6 位配对码，落地执行在 Provider */
    ShellResultParcel adbPairAndEnsureReady(String code);

    // ==================== 看门狗 / 保活 ====================

    /**
     * 写入看门狗配置并即时生效（配置持久化在 Provider 侧）。
     * enabled=false 时立即停止看门狗前台服务；true 时启动（已运行则只更新策略）。
     * autoRepairA11y：无障碍掉线且 Provider 持有 WRITE_SECURE_SETTINGS 时自动重写启用；
     * autoRepairShizuku：检测到 Shizuku 不在线时经无线 ADB 引导自动重拉（已配对可静默，未配对失败后退避）。
     * 返回是否成功应用配置。
     */
    boolean setWatchdogConfig(boolean enabled, boolean autoRepairA11y, boolean autoRepairShizuku);

    /**
     * 看门狗状态快照 JSON：
     * {"enabled","autoRepairA11y","autoRepairShizuku","running","a11yConnected",
     *  "shizukuState",  // 0/1/2，同 shizukuState()
     *  "canRepairA11y", // 当前进程是否已获授 WRITE_SECURE_SETTINGS（决定自动修复是否可用）
     *  "repairCount","lastRepairMessage","lastRepairAtMs"}
     */
    String watchdogStatus();
}
