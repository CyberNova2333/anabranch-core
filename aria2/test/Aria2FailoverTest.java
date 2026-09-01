import io.anabranch.aria2.BackendSelector;
import io.anabranch.aria2.FileBackendSelector;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * dead-man's switch 的状态转移。纯文件 IO，没有网络——「不许 mock 网络」对它
 * 不适用，它本来就不碰网络。
 *
 * <p>每次「重启」都换一个全新的 FileBackendSelector 实例：判据必须完全在盘上，
 * 靠实例字段撑着的实现在这里会露馅——真实场景里进程是死透了再起来的。
 */
public class Aria2FailoverTest {

    static int passed = 0, failed = 0;
    static final String A = "aria2-openssl", B = "aria2-gnutls";

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("ab-aria2").toFile();

        // 全新状态：没有任何历史，选默认（第一个）后端。
        check("全新状态选默认后端", fresh(dir).current().equals(A), "");

        // 干净关停：arm → disarm，下一轮不该切。
        BackendSelector s = fresh(dir);
        s.arm(A);
        s.disarm();
        check("干净关停后仍选原后端", fresh(dir).current().equals(A), "");

        // 崩溃：arm 了没 disarm，进程「死」了——下一轮必须换。
        fresh(dir).arm(A);
        check("死于非命后换另一个后端", fresh(dir).current().equals(B), "");

        // 切换要粘住：没有新事件时不许自己抖回去。
        check("切换后偏好粘住", fresh(dir).current().equals(B), "");

        // 另一个也崩：交替回来。总得选一个，交替至少不吊死在同一个坏后端上。
        fresh(dir).arm(B);
        check("两个都崩时交替尝试", fresh(dir).current().equals(A), "");

        // 加载期失败：可捕获，当场换，不等下次启动。
        s = fresh(dir);
        s.reportLoadFailure(A);
        check("加载失败当场换后端", s.current().equals(B), "");

        // armed 内容损坏（半截写入、手工改坏）：不许崩，保守当默认后端处理 → 切 B。
        wipe(dir);
        try (FileOutputStream out = new FileOutputStream(new File(dir, "aria2.armed"))) {
            out.write(new byte[] { (byte) 0xE4, 0x20, 0x00, 0x7F });
        }
        String got = fresh(dir).current();
        check("armed 内容损坏时不崩且给出已知后端", got.equals(B), got);

        // 消费幂等：current() 是「写偏好 → 删标记」两步，若删标记前再崩（这里
        // 手工把标记放回去模拟），下一轮必须给出同样的答案，不能再切一次。
        wipe(dir);
        fresh(dir).arm(A);
        check("崩溃后第一次消费", fresh(dir).current().equals(B), "");
        try (FileOutputStream out = new FileOutputStream(new File(dir, "aria2.armed"))) {
            out.write(A.getBytes("UTF-8"));
        }
        check("标记残留时重复消费结果一致", fresh(dir).current().equals(B), "");

        // 干净关停把「这个后端好用」粘进偏好：即便偏好文件此前指向另一个。
        wipe(dir);
        s = fresh(dir);
        s.reportLoadFailure(A);        // 偏好 → B
        s.arm(B);
        s.disarm();                    // B 干净跑完
        check("干净关停把好用的后端粘进偏好", fresh(dir).current().equals(B), "");

        System.out.println("\n通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    static BackendSelector fresh(File dir) { return new FileBackendSelector(dir, A, B); }

    static void wipe(File dir) {
        new File(dir, "aria2.backend").delete();
        new File(dir, "aria2.armed").delete();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else { failed++; System.out.println("  ✘ " + name + (detail.isEmpty() ? "" : "  —— " + detail)); }
    }
}
