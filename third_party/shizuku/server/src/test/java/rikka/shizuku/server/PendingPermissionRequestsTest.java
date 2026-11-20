package rikka.shizuku.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link PendingPermissionRequests} 的行为约定：
 * 未消费通过、消费后拒绝、过期拒绝，且不同请求之间互不串号。
 */
public class PendingPermissionRequestsTest {

    private static final long TTL = 60_000L;
    private static final long T0 = 1_000_000L;

    @Test
    public void 未登记时既不通过也不消费() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);

        assertFalse(table.isPending(10123, 4242, 1, T0));
        assertFalse(table.consume(10123, 4242, 1, T0));
    }

    @Test
    public void 登记后在过期前通过() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);

        assertTrue(table.isPending(10123, 4242, 1, T0));
        assertTrue(table.isPending(10123, 4242, 1, T0 + TTL - 1));
    }

    @Test
    public void 消费一次后即拒绝重放() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);

        assertTrue(table.consume(10123, 4242, 1, T0));
        assertFalse(table.consume(10123, 4242, 1, T0));
        assertFalse(table.isPending(10123, 4242, 1, T0));
    }

    @Test
    public void 过期后既不可查询也不可消费() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);

        long expired = T0 + TTL;
        assertFalse(table.isPending(10123, 4242, 1, expired));
        assertFalse(table.consume(10123, 4242, 1, expired));
    }

    @Test
    public void 查询不消费记录() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);

        assertTrue(table.isPending(10123, 4242, 1, T0));
        assertTrue(table.isPending(10123, 4242, 1, T0));
        assertTrue(table.consume(10123, 4242, 1, T0));
    }

    @Test
    public void 三元组任一不同都不命中() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);

        assertFalse(table.isPending(10124, 4242, 1, T0));
        assertFalse(table.isPending(10123, 4243, 1, T0));
        assertFalse(table.isPending(10123, 4242, 2, T0));
        assertTrue(table.isPending(10123, 4242, 1, T0));
    }

    @Test
    public void 重复登记顺延过期时刻() {
        PendingPermissionRequests table = new PendingPermissionRequests(TTL);
        table.record(10123, 4242, 1, T0);
        table.record(10123, 4242, 1, T0 + TTL - 1);

        assertTrue(table.isPending(10123, 4242, 1, T0 + TTL));
        assertFalse(table.isPending(10123, 4242, 1, T0 + TTL * 2));
    }
}
