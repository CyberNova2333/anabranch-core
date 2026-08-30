package io.anabranch;

import java.util.List;

/**
 * 选路与失败处置策略。
 *
 * <p>策略只做<b>决定</b>，不碰 IO、不改状态。所有观测数据从只读的 {@link Stats} 拿。
 * 这样策略可以单独测：造一份假的 Stats，看它选谁。
 */
public interface Policy {

    /**
     * 为某一分片挑一条源。
     *
     * @param candidates 静态启用、且未被运行时禁用的源；调用方保证非空
     * @return 必须是 candidates 里的一个
     */
    Source pick(List<Source> candidates, Stats stats, ChunkRef chunk);

    /**
     * 某条源在某一分片上失败之后怎么办。
     *
     * @param attempt 这一分片已经失败过几次（从 1 开始）
     */
    Decision onFailure(Source s, ChunkRef chunk, Throwable e, int attempt, Stats stats);

    enum Decision {
        /** 同一条源再试一次。适合超时、连接重置这类瞬时故障。 */
        RETRY_SAME,
        /** 换一条源试。 */
        SWITCH_SOURCE,
        /** 本次下载内把这条源拉黑，并换一条。 */
        DISABLE_SOURCE,
        /** 放弃整次下载。 */
        ABORT
    }

    /**
     * 默认策略：按权重与实测吞吐挑最快的，失败先原地重试再换源，连错三次拉黑。
     *
     * <p>「先原地重试再换源」的顺序是有理由的：多数失败是瞬时的（超时、连接重置），
     * 立刻换源会让一条好线路因为一次抖动就被冷落，而换过去的那条未必更好。
     * 但连续失败三次就不再是抖动了——那条源大概率是真的坏了或者被墙了。
     */
    static Policy fastestFirst() {
        return new Policy() {
            @Override
            public Source pick(List<Source> candidates, Stats stats, ChunkRef chunk) {
                Source best = null;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (Source s : candidates) {
                    double ema = stats.emaBytesPerSec(s);
                    // 没有样本时用权重当先验，量纲对齐到「假想 1MB/s × 权重」，
                    // 好让没跑过的源有机会被选中试一次，而不是永远排在已有样本之后。
                    double score = ema > 0 ? ema : s.weight * 1024.0 * 1024.0;
                    // 已经在跑的分片越多，越往后排——否则最快那条会被一次性压满，
                    // 其余线路的带宽白白闲置。
                    score /= (1 + stats.inFlight(s));
                    if (score > bestScore) { bestScore = score; best = s; }
                }
                return best;
            }

            @Override
            public Decision onFailure(Source s, ChunkRef chunk, Throwable e,
                                      int attempt, Stats stats) {
                if (stats.consecutiveFailures(s) >= 3) return Decision.DISABLE_SOURCE;
                if (attempt <= 1) return Decision.RETRY_SAME;
                return Decision.SWITCH_SOURCE;
            }
        };
    }

    /** 纯静态权重轮转，不看实测。可预测，适合测试与「就要按我说的来」的场景。 */
    static Policy weighted() {
        return new Policy() {
            private int cursor = 0;

            @Override
            public synchronized Source pick(List<Source> candidates, Stats stats, ChunkRef chunk) {
                int total = 0;
                for (Source s : candidates) total += s.weight;
                int pick = Math.floorMod(cursor++, Math.max(1, total));
                for (Source s : candidates) {
                    pick -= s.weight;
                    if (pick < 0) return s;
                }
                return candidates.get(0);
            }

            @Override
            public Decision onFailure(Source s, ChunkRef chunk, Throwable e,
                                      int attempt, Stats stats) {
                if (stats.consecutiveFailures(s) >= 3) return Decision.DISABLE_SOURCE;
                return attempt <= 1 ? Decision.RETRY_SAME : Decision.SWITCH_SOURCE;
            }
        };
    }
}
