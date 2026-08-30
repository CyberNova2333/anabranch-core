package io.anabranch;

/**
 * 令牌桶限速器，**整个下载共用一个**。
 *
 * <p>共用是关键：限速要限的是「这次下载占多少带宽」，不是「每个分片占多少」。
 * 每个分片各配一个限速器的话，并发数一变实际速率就跟着变——用户设了 1MB/s，
 * 开 4 个分片就成了 4MB/s。
 *
 * <h3>为什么桶容量正好是一秒的量</h3>
 * 容量决定允许多大的突发。取一秒：短暂的空闲之后可以立刻用掉一秒的额度，
 * 观感上不会「明明刚才没下载，现在还是慢吞吞」；但也不会攒出十几秒的额度，
 * 那样一次突发就能把移动流量套餐咬掉一块。
 *
 * <h3>它不保证瞬时速率，只保证长期平均</h3>
 * 令牌桶天然允许桶内额度被一次性用掉。要真正的平滑需要按包整形，那是内核该干的事，
 * 不是一个下载库该在用户态模拟的。
 */
public final class RateLimiter {

    /** 不限速。所有 acquire 立即返回。 */
    public static final RateLimiter UNLIMITED = new RateLimiter(0);

    private final long bytesPerSec;
    private final long capacity;
    private double tokens;
    private long lastRefillNs;

    private RateLimiter(long bytesPerSec) {
        this.bytesPerSec = bytesPerSec;
        this.capacity = Math.max(1, bytesPerSec);      // 一秒的量
        this.tokens = capacity;
        this.lastRefillNs = System.nanoTime();
    }

    /** {@code <=0} 返回 {@link #UNLIMITED}。 */
    public static RateLimiter perSecond(long bytesPerSec) {
        return bytesPerSec <= 0 ? UNLIMITED : new RateLimiter(bytesPerSec);
    }

    public boolean isLimited() { return bytesPerSec > 0; }

    /**
     * 取 {@code want} 个字节的额度，不够就等。返回实际获批的字节数（{@code >=1}）。
     *
     * <p>**可能少于 want**：调用方应当按返回值决定这一次写多少，然后再来取。
     * 一次性等够一大块的额度会让写入变成一顿一顿的，而分多次取则平滑得多。
     *
     * <p>等待期间响应取消——否则「点了取消却还要等限速放行」在界面上就是卡住。
     * 被取消时返回 0，调用方应当中止。
     */
    public int acquire(int want, Cancellation c) {
        if (bytesPerSec <= 0) return want;
        if (want <= 0) return 0;
        synchronized (this) {
            while (true) {
                if (c != null && c.isCancelled()) return 0;
                refill();
                if (tokens >= 1) {
                    int give = (int) Math.min(want, Math.min(tokens, Integer.MAX_VALUE));
                    tokens -= give;
                    return give;
                }
                // 还差多少毫秒能攒够 1 个字节的额度。至少睡 1ms，避免忙等；
                // 至多 50ms，好让取消能及时被看见。
                long waitMs = (long) Math.ceil((1 - tokens) * 1000.0 / bytesPerSec);
                try {
                    wait(Math.max(1, Math.min(50, waitMs)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double add = (now - lastRefillNs) / 1e9 * bytesPerSec;
        if (add > 0) {
            tokens = Math.min(capacity, tokens + add);
            lastRefillNs = now;
        }
    }

    @Override public String toString() {
        return bytesPerSec <= 0 ? "RateLimiter(不限)" : "RateLimiter(" + bytesPerSec + " B/s)";
    }
}
