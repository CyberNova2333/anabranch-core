#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守住 core 的四条边界。

这四条不是风格偏好，每一条都对应一个「破了之后很难再补回来」的性质：

1. **core 不碰 Android。** 下载调度、分块、续传、校验全是纯逻辑。一旦掺进
   Context / Handler / SharedPreferences，就再也没法在桌面 JVM 上脱机验证——
   而这个库的测试全部靠「真起本地 HTTP 服务器真下文件」，那是它敢说自己正确的
   唯一依据。Android 相关的接线（前台服务、通知、WorkManager）应该是另一个
   artifact，不是往 core 里塞。

2. **core 零第三方依赖。** 引一个就等于让所有使用者去处理版本冲突。这一层需要
   的东西（Range、超时、重定向、SHA-256）JDK 自带的就够。想换 OkHttp 的人自己
   实现一个 Engine 即可——那正是把 Engine 抽出来的意义。

3. **不用 minSdk 21 上没有的 API。** 目标是能在 API 21 的 Android 上直接用，
   不强制要求使用者开 desugaring。lambda 与 default 方法会被 d8 脱糖，没问题；
   但 java.util.function / stream / Optional / java.nio.file / java.time 是**运行时
   类库**，API 24 之前的设备上根本没有，脱糖也救不了（core library desugaring
   要额外配置，不该由一个下载库来强制）。

4. **分层不许倒挂。** Engine 只管「取一个字节区间」，不许反过来引用调度层
   （Downloader / Policy / SourceSet / ResumeState）。这条一破，换引擎就等于换掉
   整套重试与切源策略——而「能换引擎」正是这个库要支持 native aria2 的前提。

用法：

    python3 tools/check-core-boundaries.py

不合规时退出码为 1，并逐条指出文件与行号。
"""

import os
import re
import sys

CORE = os.path.join("src", "io", "anabranch")

# 第 3 条：这些包在 API 24 之前的 Android 上不存在
FORBIDDEN_RUNTIME = (
    "java.util.function.",
    "java.util.stream.",
    "java.util.Optional",
    "java.nio.file.",
    "java.time.",
)

# 第 2 条：允许的 import 前缀。javax 只留 crypto/net 这类 Android 上确实有的，
# 但目前 core 一个都没用到——保持空白，真要加时再显式放行，别顺手开口子。
ALLOWED_IMPORT = ("java.", "io.anabranch.")

# 第 4 条：Engine 的实现不许引用这些调度层类型
ENGINE_IMPLS = ("HttpEngine.java",)
SCHEDULING = ("Downloader", "Policy", "SourceSet", "ResumeState", "Request", "Stats")


def strip_comments_and_strings(text):
    """判据只看代码。注释里出现 android 一词不该报错——本文件自己就是例子。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(\\.|[^"\\])*"', '""', text)


def main():
    if not os.path.isdir(CORE):
        print("找不到 %s——请在仓库根目录运行。" % CORE, file=sys.stderr)
        return 2

    problems = []
    files = sorted(f for f in os.listdir(CORE) if f.endswith(".java"))
    if not files:
        print("%s 下一个 .java 都没有，判据无从谈起。" % CORE, file=sys.stderr)
        return 2

    for name in files:
        path = os.path.join(CORE, name)
        with open(path, encoding="utf-8") as f:
            raw = f.read()
        code = strip_comments_and_strings(raw)
        lines = raw.split("\n")

        for i, line in enumerate(lines, 1):
            m = re.match(r"\s*import\s+(?:static\s+)?([\w.]+)\s*;", line)
            if not m:
                continue
            imp = m.group(1)

            # ① 不碰 Android
            if imp.startswith("android.") or imp.startswith("androidx."):
                problems.append((path, i,
                    "core 不许引用 Android：%s\n"
                    "      Android 接线属于另一个 artifact，见本文件开头第 1 条。" % imp))
                continue

            # ② 零第三方依赖
            if not imp.startswith(ALLOWED_IMPORT):
                problems.append((path, i,
                    "core 不许引入第三方依赖：%s\n"
                    "      需要别的实现就自己写一个 Engine，见第 2 条。" % imp))
                continue

            # ③ minSdk 21 上不存在的运行时类库
            for bad in FORBIDDEN_RUNTIME:
                if imp.startswith(bad):
                    problems.append((path, i,
                        "%s 在 API 24 之前的 Android 上不存在：%s\n"
                        "      lambda 会脱糖，但这些是运行时类库，脱糖救不了。见第 3 条。"
                        % (bad.rstrip("."), imp)))
                    break

        # ③ 补充：不写 import 直接全限定名也算
        for bad in FORBIDDEN_RUNTIME:
            for mm in re.finditer(re.escape(bad), code):
                ln = code[:mm.start()].count("\n") + 1
                problems.append((path, ln,
                    "用了 %s（全限定名写法也算），见第 3 条。" % bad.rstrip(".")))

        # ④ 分层不许倒挂
        if name in ENGINE_IMPLS:
            for sched in SCHEDULING:
                for mm in re.finditer(r"\b%s\b" % sched, code):
                    ln = code[:mm.start()].count("\n") + 1
                    problems.append((path, ln,
                        "Engine 实现不许引用调度层的 %s。\n"
                        "      引擎只管「取一个字节区间」，见第 4 条。" % sched))

    if problems:
        print("✘ core 边界被打破（%d 处）：\n" % len(problems), file=sys.stderr)
        for path, line, msg in problems:
            print("  %s:%d\n      %s" % (path, line, msg), file=sys.stderr)
        return 1

    print("✔ core 边界核对通过（%d 个文件）：不碰 Android、零第三方依赖、"
          "无 API 24+ 运行时类库、分层无倒挂" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
