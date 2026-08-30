package io.anabranch;

/**
 * 校验信息，<b>由调用方提供</b>——库不去任何地方取。
 *
 * <p>逐块摘要是多源下载的地基：没有它，从 A 源取第 3 块、从 B 源取第 4 块之后
 * 没有任何办法确认拼出来的东西是对的，整份 sha256 对不上时也说不清是哪一块坏的。
 */
public final class Integrity {
    /** 逐块摘要的块大小；<=0 表示没有逐块信息。 */
    public final long chunkSize;
    /** 每块的十六进制 SHA-256（小写）。 */
    public final String[] chunkHashes;
    /** 整份文件的十六进制 SHA-256；可为 null。 */
    public final String wholeSha256;

    public Integrity(long chunkSize, String[] chunkHashes, String wholeSha256) {
        this.chunkSize = chunkSize;
        this.chunkHashes = chunkHashes == null ? new String[0] : chunkHashes.clone();
        this.wholeSha256 = wholeSha256;
    }

    public static Integrity wholeOnly(String sha256) {
        return new Integrity(0, null, sha256);
    }

    public boolean hasChunks() { return chunkSize > 0 && chunkHashes.length > 0; }

    public String hashFor(int index) {
        return index >= 0 && index < chunkHashes.length ? chunkHashes[index] : null;
    }
}
