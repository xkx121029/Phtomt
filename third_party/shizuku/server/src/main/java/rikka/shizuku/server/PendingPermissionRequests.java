package rikka.shizuku.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 权限确认请求的一次性登记表。
 *
 * <p>server 在发起确认（{@code showPermissionConfirmation}）时登记 (uid, pid, requestCode)，
 * 随后确认页的展示与结果回传都必须命中这张表。于是「这次请求是否真的由 server 发起」由
 * server 自己的内存记录裁定，而不再由 Intent 里的参数自证：第三方应用伪造 uid/pid
 * 拉起确认页时不存在任何登记，既不会弹框，也无法把结果写进权限配置。
 *
 * <p>每条记录带过期时间（默认 {@link #DEFAULT_TTL_MS}），命中一次即消费，重放同一组参数
 * 第二次必然失败。
 *
 * <p>纯 Java、无 Android 依赖，当前时间由调用方传入，便于用 JVM 单测覆盖
 * 「未消费通过 / 消费后拒绝 / 过期拒绝」三条行为。
 */
public final class PendingPermissionRequests {

    /** 确认页的合理存活窗口：足够用户看清卡片并点击，又不足以被反复利用 */
    public static final long DEFAULT_TTL_MS = 60_000L;

    private final long ttlMs;
    /** key -> 过期时刻（与调用方传入的 now 同一时基） */
    private final Map<String, Long> deadlines = new HashMap<>();

    public PendingPermissionRequests() {
        this(DEFAULT_TTL_MS);
    }

    public PendingPermissionRequests(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 登记一次待确认请求；同一组参数重复登记以最后一次为准（顺延过期时刻） */
    public synchronized void record(int uid, int pid, int requestCode, long now) {
        pruneExpired(now);
        deadlines.put(key(uid, pid, requestCode), now + ttlMs);
    }

    /** 只读查询：登记存在且未过期。确认页据此决定是否展示卡片，不消费记录 */
    public synchronized boolean isPending(int uid, int pid, int requestCode, long now) {
        return !isExpired(key(uid, pid, requestCode), now);
    }

    /** 消费：命中即删除并返回 true；未登记或已过期返回 false（过期记录顺带清理） */
    public synchronized boolean consume(int uid, int pid, int requestCode, long now) {
        String key = key(uid, pid, requestCode);
        if (isExpired(key, now)) {
            deadlines.remove(key);
            return false;
        }
        deadlines.remove(key);
        return true;
    }

    private boolean isExpired(String key, long now) {
        Long deadline = deadlines.get(key);
        return deadline == null || deadline <= now;
    }

    /** 清掉所有已过期记录，避免伪造请求的失败尝试堆积占内存 */
    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = deadlines.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    private static String key(int uid, int pid, int requestCode) {
        return uid + ":" + pid + ":" + requestCode;
    }
}
