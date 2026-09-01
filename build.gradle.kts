// Gradle 在这个仓库里只管一件事：打包与发布（jar + sources + javadoc + 签名 + POM）。
// 日常开发循环仍然是 tools/run-checks.sh——javac + python3，不需要 Gradle。
// 刻意不放 wrapper：那要往仓库里提交一个二进制 jar，而需要 Gradle 的只有发布这一个
// 动作、一个人。要求发布者本机装 Gradle 8+ 即可（见 docs/RELEASING.md）。
plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.anabranch"
version = "0.1.0"

java {
    withSourcesJar()
    withJavadocJar()
}

// 目录布局早于 Gradle 存在（src/ 直接是包根，test/ 是默认包的测试套件），
// 迁就 Gradle 的 src/main/java 约定就得动所有历史与工具脚本——不值得。
sourceSets {
    main { java.setSrcDirs(listOf("src")) }
    test { java.setSrcDirs(listOf("test")) }
}

tasks.compileJava {
    // 与 tools/run-checks.sh 的「javac --release 8 单独编一遍」是同一条判据：
    // minSdk 21 的实际含义是 core 的字节码与类库引用必须是 Java 8 能吃下的。
    options.release = 8
    options.encoding = "UTF-8"
}

tasks.compileTestJava {
    // 测试只跑在桌面 JVM 上，不受 release 8 约束（与 run-checks.sh 一致）。
    options.encoding = "UTF-8"
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        // 注释是中文散文体，不是每个参数都配 @param——doclint 的完整性检查在
        // 这里只会制造噪音，关掉。
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// 三套测试是 main() 驱动的自建套件（不是 JUnit），各自起真实的本地 HTTP 服务器。
// 以 JavaExec 接进 check，让 `gradle build` 与 run-checks.sh 跑的是同一批用例。
val suiteTasks = listOf("SingleSourceTest", "MultiSourceTest", "ThrottleTest").map { suite ->
    tasks.register<JavaExec>("run$suite") {
        group = "verification"
        description = "跑 $suite（真起本地 HTTP 服务器）"
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = suite
        systemProperty("stdout.encoding", "UTF-8")
    }
}
tasks.test { enabled = false }   // 没有 JUnit 测试，留着只会误导
tasks.check { dependsOn(suiteTasks) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "anabranch-core"
                description = "Multi-source chunked downloader for Android/Java: " +
                        "one file, N mirrors, per-chunk scheduling with failover " +
                        "and per-chunk verification. Pure Java, zero dependencies."
                url = "https://github.com/CyberNova2333/anabranch-core"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "CyberNova2333"
                        name = "CyberNova2333"
                        url = "https://github.com/CyberNova2333"
                    }
                }
                scm {
                    url = "https://github.com/CyberNova2333/anabranch-core"
                    connection = "scm:git:https://github.com/CyberNova2333/anabranch-core.git"
                    developerConnection = "scm:git:git@github.com:CyberNova2333/anabranch-core.git"
                }
            }
        }
    }
    repositories {
        // 发布走 Central Portal 的手动上传：先把完整仓库布局（含校验和与签名）
        // 落到 build/staging，打成 zip 再上传。不引第三方发布插件——它们迭代快、
        // 常破坏兼容，而这个动作一年做不了几次。步骤见 docs/RELEASING.md。
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}

// 只有真往 staging 发布（也就是给 Central 出包）时才执行签名。光把 required 设成
// false 不够：装了 gpg 但没有私钥的机器上，Sign 任务照样会跑并当场失败——
// publishToMavenLocal 这种本地动作不该被它拦住。
tasks.withType<Sign>().configureEach {
    onlyIf {
        gradle.taskGraph.allTasks.any { it.name == "publishMavenPublicationToLocalStagingRepository" }
    }
}
