# 发布手册（Maven Central）

> 发布是**有意识的手动动作**，一年做不了几次。两条路等价：本机跑（§一）或
> 在 GitHub Actions 里勾一下（§四）。「不做自动发版」在这里的含义是：没有任何
> push/tag 事件能触发发布，发布只来自一个人的一次勾选或一条命令。

## 〇、一次性准备（只做一遍）

### 1. Central Portal 账号与命名空间

在 [central.sonatype.com](https://central.sonatype.com) 注册账号，然后**验证命名空间**
`io.github.cybernova2333`：Portal 会给一个临时仓库名，在 GitHub 账号下建一个同名
公开仓库即完成验证，几分钟的事，验证后可删。

group 与 Java 包名（`io.anabranch`）没有绑定关系——
`io.github.cybernova2333:anabranch-core` 里装 `io.anabranch.*` 完全合法。刻意不用
`io.anabranch` 做 group：那要证明对 anabranch.io 域名的控制权，而这个库不打算
背一个域名。

### 2. GPG 签名 key

Central 要求所有产物带 `.asc` 签名。构建脚本用的是 `useGpgCmd()`——直接调本机
`gpg`，不用把私钥交给 Gradle：

```bash
gpg --gen-key                                  # 已有 key 可跳过
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>   # Central 会去这里验证
```

多把 key 时在 `~/.gradle/gradle.properties` 里指定：
`signing.gnupg.keyName=<KEYID>`。

### 3. 本机 Gradle

装 Gradle 8+（仓库刻意不放 wrapper——那要提交一个二进制 jar，而需要 Gradle 的
只有发布这一个动作）。构建只用核心插件，除 Gradle 发行版本身外不下载任何东西。

## 一、每次发布

```bash
# 1. 定版本：改 build.gradle.kts 的 version（去掉 -SNAPSHOT 之类后缀）
# 2. 基线核对：全量检查连跑四轮（涉及并发的用例一次绿不算数）
for i in 1 2 3 4; do sh tools/run-checks.sh >/dev/null && echo "第 $i 轮 ok"; done

# 3. 构建 + 交叉验证（gradle build 会把三套测试再跑一遍）
gradle build

# 4. 一条命令出上传包：清 staging → 签名发布全部 publication → 打 zip
#    （产物布局、.asc 与四类校验和齐全，maven-metadata 已按 Portal 要求排除；
#    这条流程已用一次性测试 key 端到端验证过，含 gpg --verify）
gradle centralBundle

# 5. 到 central.sonatype.com → Publish → Upload Bundle，传 build/central-bundle.zip
#    Portal 校验通过后手动点 Publish——这一步之后就撤不回了，撤回等于发一个新版本。

# 6. 落锚点：提交版本号改动，打 tag
git tag v<版本号> && git push origin v<版本号>
```

## 一·五、连 anabranch-aria2（AAR）一起发布时

AAR 由 CI 组装（build-aria2.yml，本地 Gradle 编不出 .so 也不该编）。多两步：

```bash
# 1. 从选定的构建 run 下载 artifact（libaria2-so 或 libaria2-so-slim），
#    把里面的 anabranch-aria2-<版本>.aar 放到：
mkdir -p aria2/dist && cp <下载目录>/anabranch-aria2-<版本>.aar aria2/dist/

# 2. 照常 gradle centralBundle——检测到该文件就会把 anabranch-aria2 一起
#    打进 bundle（packaging=aar、依赖同版 core、许可 GPL-3.0-or-later，
#    sources/javadoc jar 由 Gradle 现出）。
```

发布 AAR 前核对 aria2/THIRD-PARTY-NOTICES.md §三 的义务清单（NOTICES 已在
AAR 内，分发页面还需注明许可与源码获取方式）。**真机验证没过之前别发 AAR**
——core 可以先单独发，aria2/dist/ 里没有文件时 centralBundle 只出 core。

## 二、发布后核对

- 在 [central.sonatype.com](https://central.sonatype.com) 的 Deployments 页确认状态是
  PUBLISHED；同步到 `repo1.maven.org` 通常要几十分钟。
- 用一个空白项目按 README 里的坐标拉一次，确认能解析。

## 三、版本号约定

`0.x.y` 期间不承诺 API 稳定，但**落盘格式（`.meta`）的兼容性单独承诺**：
认不出的版本号一律作废重下，这是设计而不是缺陷（见 `src/io/anabranch/ResumeState.java`）。
破坏 `.meta` 兼容时至少升 minor 并在提交信息里写明。

## 四、CI 发版口（`.github/workflows/build-core.yml`）

与 §一 完全等价的另一条路，签名与上传都在 runner 上做，本机连 Gradle 都不用装。
只能手动触发（Actions → 「构建 / 发版 anabranch-core」→ Run workflow）：

| 输入 | 含义 |
|---|---|
| `release` | 勾 = 正式发版：导入 GPG 私钥 → `centralBundle` → 上传 Portal 并轮询校验 → 打 `v<版本>` tag。不勾 = 只出未签名 artifact |
| `auto_publish` | 勾 = Portal 校验通过后自动 Publish；**不勾（默认）= 停在 Portal 等你点 Publish，那之前还能 Drop 撤回** |
| `aria2_run_id` | 可选：某次「构建 libaria2」的 run id，把它的 AAR 一并发布为 anabranch-aria2（§一·五 的 CI 版） |
| `aria2_artifact` | 与上配套：`libaria2-so`（全量）或 `libaria2-so-slim`（裁剪） |

**一次性准备**——仓库 Settings → Secrets and variables → Actions 里加四个 secret：

| secret | 内容 |
|---|---|
| `MAVEN_GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <KEYID>` 的完整输出（ASCII 装甲，含 BEGIN/END 行） |
| `MAVEN_GPG_PASSPHRASE` | 该私钥的口令（无口令则留空字符串） |
| `CENTRAL_USERNAME` | Portal 右上角头像 → View Account → Generate User Token 给出的 username |
| `CENTRAL_PASSWORD` | 同一处给出的 password（是令牌，不是登录密码） |

workflow 的前置核对会拒绝：缺 secret、版本号带 `-SNAPSHOT`、`v<版本>` tag 已存在
（同一版本发过了，改版本号再来）。发版成功后 artifact 里附一份签名过的
bundle zip 备查。

安全边界：私钥只在 runner 内存里活一次 job，GitHub 对 secret 做日志遮蔽；
Portal 令牌可随时在 Portal 撤销重发。真要收回发版口，删掉那四个 secret 即可
——workflow 会在第一步就停下。

