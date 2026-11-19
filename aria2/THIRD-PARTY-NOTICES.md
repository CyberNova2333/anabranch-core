# anabranch-aria2 · 第三方组件声明与许可

> 适用对象：`libaria2-{openssl,gnutls}.so` 及装着它们的 AAR。
> **只用纯 Java 路线（anabranch-core）的使用者与本文件无关**——这正是把
> aria2 后端拆成独立 artifact 的理由（core 铁律 4）。

## 一、整体许可结论

`.so` 静态链接了 aria2（`GPL-2.0-or-later`）。本项目自身的 JNI 胶水与 Java 侧代码采用 `AGPL-3.0-only`（适用根目录附加条款）；二者结合分发二进制产物时，依据 **GPL 第 3 版第 13 条与 AGPLv3 第 13 条的兼容组合机制**处理：

- 上游 `aria2` 及其原有材料继续适用 **GPL-3.0-or-later**（由 `GPL-2.0-or-later` 依第 13 条组合路径适用 GPLv3），本仓库不将原 GPL 材料概括改授 `AGPL-3.0-only`，也不使根目录附加条款的附加要求或第 9.5 款豁免自动覆盖原 GPL 部分；
- 本项目自研的 `anabranch-aria2` JNI 胶水（`aria2/native/aria2_jni.cpp` 等）与 Java 侧源码（`aria2/src/io/anabranch/aria2/*`）采用 `AGPL-3.0-only`（含附加条款）；
- 静态链接进 `.so` 的 **OpenSSL 3.5.9** 采用 **`Apache-2.0`**（自 OpenSSL 3.0 起已由旧 OpenSSL/SSLeay 许可切换为 Apache-2.0），与 GPLv3、LGPLv3 及 AGPLv3 均原生兼容，不存在与 `GnuTLS`/`nettle`/`GMP` 或 `AGPL-3.0-only` 胶水的链接许可冲突；
- 装着 `.so` 的 AAR 作为组合分发单元，对外分发时须同时满足 AGPLv3 §13 与 GPLv3 的对应源码、许可副本与版权声明保留义务。

**许可与版权原文**：AAR 内 `META-INF/licenses/`（由 `collect-licenses.sh` 从
pinned 上游源码原样抽出，索引见其中 `INDEX.txt`）——GPL/AGPL 要求附 License 副本，
Apache-2.0 / BSD / MIT 要求二进制分发复制版权与许可声明，本文件的汇总表不能
代替原文。

**对应源码的提供方式**（GPLv3 §6 / AGPLv3 §6）：每个发布版本的 GitHub Release
（`https://github.com/CyberNova2333/anabranch-core/releases/tag/v<版本>`）与 AAR
并列挂一个 `anabranch-aria2-<版本>-corresponding-source*.tar.gz`：静态链接的
全部上游 tarball（原文件，sha256 与构建时硬校验值一致）+ 该 commit 的胶水、
Java 源码、构建脚本与 workflow。不只靠上游服务器——上游 URL 会挪，义务不能
跟着悬空。照包内 README.txt 跑一遍即可复现这批 `.so`。

## 二、组件清单（静态链接进 .so 的）

| 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|
| aria2 | 1.37.0 | GPL-2.0-or-later（随包保留原 OpenSSL 例外声明） | github.com/aria2/aria2 |
| wslay（aria2 内置依赖） | 随 aria2 1.37.0 | MIT | 随 aria2 tarball |
| OpenSSL | 3.5.9 | Apache-2.0 | openssl.org / github.com/openssl/openssl |
| GnuTLS | 3.8.3 | LGPL-2.1-or-later | gnutls.org |
| nettle | 3.9.1 | LGPL-3.0-or-later 或 GPL-2.0-or-later（双许可） | ftp.gnu.org/gnu/nettle |
| GMP | 6.3.0 | LGPL-3.0-or-later 或 GPL-2.0-or-later（双许可） | gmplib.org |
| libxml2 | 2.9.14 | MIT | gitlab.gnome.org/GNOME/libxml2 |
| SQLite | 3.44.2 | Public Domain | sqlite.org |
| libssh2 | 1.11.0 | BSD-3-Clause | libssh2.org |
| zlib | 1.3.1 | Zlib | zlib.net |

按后端分：`libaria2-openssl.so` 含 aria2/wslay/OpenSSL/libxml2/SQLite/
libssh2/zlib；`libaria2-gnutls.so` 含 aria2/wslay/GnuTLS/nettle/GMP/libxml2/
SQLite/libssh2/zlib，外加 OpenSSL（仅供 libssh2 的加密原语——libssh2 没有
gnutls 后端）。裁剪构建（`ARIA2_SLIM=1`，见 build-aria2.sh）不含 libxml2/
SQLite/libssh2，gnutls 后端相应不含 OpenSSL。

## 三、每次重建后的义务清单

发布（分发含 `.so` 的产物）前核对：

1. 本文件与 `META-INF/licenses/` 随产物一起走（AAR 内已打包）；
2. 对应源码包与 AAR 挂在同一个 GitHub Release 下（发版口勾 `include_aria2`
   时自动完成）；分发页面注明二进制按 GPL-3.0-or-later 分发并指向该 Release；
3. 若改了任何组件版本，同步更新上表与 workflow 里的 sha256 pin——
   「提供对应源码」指的是**分发出去那份二进制**的源码，版本对不上等于没提供。
