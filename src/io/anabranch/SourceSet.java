package io.anabranch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一组源 + 选路策略，外加本次下载的运行时观测。
 *
 * <p>观测数据挂在 SourceSet 上而不是 Source 上，是因为 {@link Source} 是不可变的
 * 描述，可以被多次下载复用；而「这条源刚才慢/刚才挂了」只对本次下载有意义。
 * 想跨下载复用观测，把同一个 SourceSet 传进去就行。
 */
public final class SourceSet {

    private final List<Source> all;
    private final Policy policy;
    private final Map<String, Tracker> trackers = new HashMap<>();
    private final Stats view = new Stats() {
        public double emaBytesPerSec(Source s)   { return t(s).ema; }
        public int consecutiveFailures(Source s) { return t(s).consecutiveFailures.get(); }
        public int inFlight(Source s)            { return t(s).inFlight.get(); }
        public long lastSuccessAtMillis(Source s){ return t(s).lastSuccess.get(); }
        public boolean isDisabled(Source s)      { return t(s).disabled; }
    };

    public SourceSet(List<Source> sources, Policy policy) {
        if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("没有源");
        this.all = Collections.unmodifiableList(new ArrayList<>(sources));
        this.policy = policy == null ? Policy.fastestFirst() : policy;
        for (Source s : all) trackers.put(s.id, new Tracker());
    }

    public SourceSet(Source... sources) { this(Arrays.asList(sources), null); }

    /** 单源便利构造，等价于第 1 步的行为。 */
    public static SourceSet single(String url) {
        return new SourceSet(Collections.singletonList(Source.ofUrl("single", url)), null);
    }

    public List<Source> all() { return all; }
    public Policy policy() { return policy; }
    public Stats stats() { return view; }

    /** 静态启用、且未被本次下载禁用的源。 */
    public List<Source> available() {
        List<Source> out = new ArrayList<>(all.size());
        for (Source s : all) if (s.enabled && !t(s).disabled) out.add(s);
        return out;
    }

    // ── 供 Downloader 记账 ───────────────────────────────────────────

    void onStart(Source s) { t(s).inFlight.incrementAndGet(); }

    void onSuccess(Source s, long bytes, long nanos) {
        Tracker tr = t(s);
        tr.inFlight.decrementAndGet();
        tr.consecutiveFailures.set(0);
        tr.lastSuccess.set(System.currentTimeMillis());
        if (nanos > 0 && bytes > 0) {
            double bps = bytes / (nanos / 1e9);
            // EMA：α=0.3。取这个值是为了「记得住历史，但换线路后几块之内就能跟上」。
            // α 太小会让一条已经变慢的源迟迟降不下来，太大就退化成只看最后一次。
            synchronized (tr) { tr.ema = tr.ema <= 0 ? bps : 0.3 * bps + 0.7 * tr.ema; }
        }
    }

    void onFailure(Source s) {
        Tracker tr = t(s);
        tr.inFlight.decrementAndGet();
        tr.consecutiveFailures.incrementAndGet();
    }

    void disable(Source s) { t(s).disabled = true; }

    /** 每次下载开始时清掉上一次的禁用标记——上一次坏了不代表这次还坏。 */
    void resetDisabled() { for (Tracker tr : trackers.values()) tr.disabled = false; }

    private Tracker t(Source s) {
        Tracker tr = trackers.get(s.id);
        if (tr == null) throw new IllegalArgumentException("不属于本 SourceSet 的源：" + s.id);
        return tr;
    }

    private static final class Tracker {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicLong lastSuccess = new AtomicLong();
        volatile double ema = 0;
        volatile boolean disabled = false;
    }
}
