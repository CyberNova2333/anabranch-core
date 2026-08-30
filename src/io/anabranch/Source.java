package io.anabranch;

/**
 * 一条线路。
 *
 * <p><b>同一个 SourceSet 里的所有 Source 必须对同一个 key 产出逐字节相同的内容。</b>
 * 这是全库的前提——分片从哪条源取是运行时决定的，内容不一致就会拼出一个
 * 每一段都「下载成功」、合起来却是坏的文件。
 *
 * <p>URL 由 {@link Resolver} 给，而不是「base + 文件名」拼出来。差别在于：
 * 拼接隐含了「所有镜像同路径」，而现实里镜像路径不一致、要签名 URL、
 * CDN 带 token 都很常见。
 */
public final class Source {

    /** 逻辑文件名 → 这条源上的 URL。 */
    public interface Resolver { String urlFor(String key); }

    public final String id;
    /** 静态权重，>0。只在没有实测数据时起作用。 */
    public final int weight;
    /** 该源允许的并发分片数；<=0 表示用全局默认。 */
    public final int maxChunks;
    /** 静态开关。运行时被策略禁用是另一回事，记在 Stats 里，不改这个字段。 */
    public final boolean enabled;

    private final Resolver resolver;

    public Source(String id, int weight, int maxChunks, boolean enabled, Resolver resolver) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("source id 为空");
        if (resolver == null) throw new IllegalArgumentException("resolver 为空");
        this.id = id;
        this.weight = Math.max(1, weight);
        this.maxChunks = maxChunks;
        this.enabled = enabled;
        this.resolver = resolver;
    }

    /** base + key 的常见情形。base 不以 '/' 结尾会自动补上。 */
    public static Source ofBase(String id, String base) {
        final String b = base.endsWith("/") ? base : base + "/";
        return new Source(id, 1, 0, true, key -> b + key);
    }

    /** 固定 URL，忽略 key。单源场景用。 */
    public static Source ofUrl(String id, String url) {
        return new Source(id, 1, 0, true, key -> url);
    }

    public String urlFor(String key) { return resolver.urlFor(key); }

    @Override public String toString() { return "Source(" + id + ")"; }
}
