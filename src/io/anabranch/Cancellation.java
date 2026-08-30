package io.anabranch;

/** 取消信号。与进度回调分开——只想看进度的调用方不该被迫实现取消。 */
public interface Cancellation {
    boolean isCancelled();
    Cancellation NONE = () -> false;
}
