package io.anabranch;

/** 下载结果。 */
public final class Result {
    public final long totalBytes;
    public final String etag;
    /** 是否每一块都按 {@link Integrity} 逐块校验过。 */
    public final boolean chunkVerified;
    /** 本次实际从网络取的字节数（续传时小于 totalBytes）。 */
    public final long transferredBytes;

    public Result(long totalBytes, String etag, boolean chunkVerified, long transferred) {
        this.totalBytes = totalBytes;
        this.etag = etag == null ? "" : etag;
        this.chunkVerified = chunkVerified;
        this.transferredBytes = transferred;
    }

    @Override public String toString() {
        return "Result{total=" + totalBytes + ", verified=" + chunkVerified
                + ", transferred=" + transferredBytes + "}";
    }
}
