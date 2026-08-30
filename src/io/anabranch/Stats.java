package io.anabranch;

/**
 * 库维护、策略只读的运行时观测。
 *
 * <p>吞吐用 <b>EMA 而不是历史峰值</b>：峰值会被一次偶然的快传永久锁死，
 * 之后哪怕这条源已经烂了，选路仍然偏向它。
 */
public interface Stats {
    /** 指数滑动平均吞吐（字节/秒）；没有样本时返回 0。 */
    double emaBytesPerSec(Source s);
    /** 连续失败次数，成功一次清零。 */
    int consecutiveFailures(Source s);
    /** 当前正在这条源上跑的分片数。 */
    int inFlight(Source s);
    /** 最近一次成功的时刻（毫秒）；从未成功返回 0。 */
    long lastSuccessAtMillis(Source s);
    /** 是否已被策略在本次下载中禁用。 */
    boolean isDisabled(Source s);
}
