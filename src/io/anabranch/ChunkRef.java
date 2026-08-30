package io.anabranch;

/** 一个分片的坐标。传给策略，让它能按「这是哪一块」做决定。 */
public final class ChunkRef {
    public final int index;
    public final long start;
    public final long endInclusive;

    public ChunkRef(int index, long start, long endInclusive) {
        this.index = index;
        this.start = start;
        this.endInclusive = endInclusive;
    }

    public long length() { return endInclusive - start + 1; }

    @Override public String toString() {
        return "#" + index + "[" + start + "-" + endInclusive + "]";
    }
}
