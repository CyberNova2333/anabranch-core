package io.anabranch.aria2;

/**
 * aria2 native 后端的选择与 dead-man's switch。
 *
 * <p>要解决的问题：native 崩溃（SIGSEGV/SIGABRT）整体杀进程，Java 层没有任何
 * catch 的机会——「上一轮是哪个后端弄死我们的」没法在进程内记录。所以把判据挪到
 * 进程外：启动 native <b>之前</b>先落盘 armed 标记，只有确认干净关停后才 disarm；
 * 下次启动发现标记仍在，就知道上一轮死于非命，换另一个后端。
 *
 * <p>两档故障分开：<b>运行期崩溃</b>只能靠标记（上面那套）；<b>加载期失败</b>
 * （{@code UnsatisfiedLinkError}，可捕获）当场 {@link #reportLoadFailure} 换，
 * 不等下次启动。
 *
 * <p>设计细节与时序见 docs/ARIA2.md §四。
 */
public interface BackendSelector {

    /** 本次该用哪个后端。调用它就会消费掉上一轮留下的 armed 判词（若有）。 */
    String current();

    /** 落盘 armed 标记。<b>必须先于任何 native 调用</b>，且落盘要真的落（fsync）。 */
    void arm(String backend);

    /** 仅在确认 native 侧干净关停之后调用。 */
    void disarm();

    /** 加载期失败（可捕获的那种）。立刻把偏好切到另一个后端。 */
    void reportLoadFailure(String backend);
}
