# 发布手册（Maven Central）

> 发布是**本机手动动作**，一年做不了几次。这份手册的读者是拿着签名 key 的维护者；
> 仓库的「不做自动发版」原则不因 Gradle 的加入而改变——没有任何 CI 会替你发布。

## 〇、一次性准备（只做一遍）

### 1. Central Portal 账号与命名空间

在 [central.sonatype.com](https://central.sonatype.com) 注册账号，然后**验证命名空间**：

- 首选 group `io.anabranch`：要求证明对 **anabranch.io 域名**的控制权（加一条
  DNS TXT 记录，Portal 会给出具体值）。
- **域名不在手里就别硬凑**：退路是把 `build.gradle.kts` 里的 `group` 改成
  `io.github.cybernova2333`（GitHub 账号即可验证，几分钟的事）。
  Java 包名 `io.anabranch` **不用跟着改**——Maven group 与包名没有绑定关系，
  `io.github.cybernova2333:anabranch-core` 里装 `io.anabranch.*` 完全合法。

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

# 4. 出签名产物：完整仓库布局（jar/sources/javadoc/pom/module + .asc + 校验和）
gradle publishMavenPublicationToLocalStagingRepository

# 5. 打包上传
cd build/staging && zip -r ../anabranch-core-bundle.zip io && cd -
# 到 central.sonatype.com → Publish → Upload Bundle，传 build/anabranch-core-bundle.zip
# Portal 校验通过后手动点 Publish——这一步之后就撤不回了，撤回等于发一个新版本。

# 6. 落锚点：提交版本号改动，打 tag
git tag v<版本号> && git push origin v<版本号>
```

## 二、发布后核对

- 在 [central.sonatype.com](https://central.sonatype.com) 的 Deployments 页确认状态是
  PUBLISHED；同步到 `repo1.maven.org` 通常要几十分钟。
- 用一个空白项目按 README 里的坐标拉一次，确认能解析。

## 三、版本号约定

`0.x.y` 期间不承诺 API 稳定，但**落盘格式（`.meta`）的兼容性单独承诺**：
认不出的版本号一律作废重下，这是设计而不是缺陷（见 `src/io/anabranch/ResumeState.java`）。
破坏 `.meta` 兼容时至少升 minor 并在提交信息里写明。
