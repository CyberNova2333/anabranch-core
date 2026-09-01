# anabranch-aria2 · 第三方组件声明与许可

> 适用对象：`libaria2-{openssl,gnutls}.so` 及装着它们的 AAR。
> **只用纯 Java 路线（anabranch-core）的使用者与本文件无关**——这正是把
> aria2 后端拆成独立 artifact 的理由（core 铁律 4）。

## 一、整体许可结论

`.so` 静态链接了 aria2（GPL-2.0-or-later）。因此**二进制的组合整体按
GPL-3.0-or-later 的条款分发**：

- aria2 是「GPLv2 或更高版本」，本项目自己的胶水与 Java 侧代码是 Apache-2.0；
  Apache-2.0 与 GPLv2 不兼容、与 GPLv3 兼容，所以组合体取 v3。
- aria2 的 COPYING 带 OpenSSL 链接例外，OpenSSL 侧的链接由此覆盖。
- 装着 `.so` 的 AAR 作为一个分发单元，整体同样受上述条款约束；其中
  `classes.jar`（`io.anabranch.aria2.*`）的**源码**仍是 Apache-2.0。

**对应源码的提供方式**（GPL 义务）：下表每个组件的官方 tarball（版本与
sha256 与构建时逐一硬校验的完全相同，见 `.github/workflows/build-aria2.yml`）
+ 本仓库 `aria2/native/` 下的全部构建脚本与胶水源码。照着
`build-aria2.yml` 跑一遍即可复现这批 `.so`。

## 二、组件清单（静态链接进 .so 的）

| 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|
| aria2 | 1.37.0 | GPL-2.0-or-later（含 OpenSSL 链接例外） | github.com/aria2/aria2 |
| wslay（aria2 内置依赖） | 随 aria2 1.37.0 | MIT | 随 aria2 tarball |
| OpenSSL | 1.1.1w | OpenSSL / SSLeay 双许可 | openssl.org |
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

1. 本文件随产物一起走（AAR 内已打包一份）；
2. 分发页面注明二进制按 GPL-3.0-or-later 分发、对应源码从本仓库与上表
   官方 tarball 获取；
3. 若改了任何组件版本，同步更新上表与 workflow 里的 sha256 pin——
   「提供对应源码」指的是**分发出去那份二进制**的源码，版本对不上等于没提供。
