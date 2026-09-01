// aria2_jni.cpp — libaria2-{openssl,gnutls}.so 的 JNI 胶水层。
// Java 侧契约见 aria2/src/io/anabranch/aria2/Aria2Native.java。
//
// 与「argv 驱动 + 进程内 RPC 控制」的老路子不同，这里走 libaria2 的**公开
// session API**（--enable-libaria2，<aria2/aria2.h>）：
//
//  - 不起 RPC 服务、不占端口、没有 secret 要保管——控制面就是这几个 JNI 函数；
//  - 事件循环由 Java 侧单线程反复调 runOnce 驱动（RUN_ONCE 模式很快返回），
//    取消 = 同一线程在两次 runOnce 之间调 sessionShutdown。于是 native 侧
//    不需要自己开线程，也就没有跨线程调 libaria2 的问题（它不是线程安全的）。
//  - 公开 API 没有 argv 解析路径，也就没有「参数错直接 exit() 杀进程」这一档
//    坑；坏选项在 sessionNew/addUri 里以返回值/异常暴露，全部兜在 try/catch
//    里——异常绝不能穿出 JNI 边界。
//
// 符号卫生：本文件只暴露 9 个 JNI 入口（version script libaria2-anabranch.map
// 强制 + -fvisibility=hidden + -Wl,-Bsymbolic）。TLS 库符号一个都不许泄漏——
// 以 GLOBAL 暴露的 OpenSSL 符号会被进程内其它 libssl 抢占污染，崩起来没有
// 行号可看。

#include <jni.h>

#include <string>
#include <vector>

#include <aria2/aria2.h>

// 日志按平台走：Android 进 logcat；宿主（桌面 JVM 上跑真下载测试用）进 stderr。
// 同一份胶水两边编译，测试测到的才是发货的那份逻辑。
#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "anabranch-aria2"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...)                            \
  do {                                       \
    std::fprintf(stderr, "[anabranch-aria2] "); \
    std::fprintf(stderr, __VA_ARGS__);       \
    std::fprintf(stderr, "\n");              \
  } while (0)
#endif

// 构建标记：strings 级可探测，核验设备上的库出自本构建链。
// __attribute__((used)) 防被 LTO/GC 丢弃。
extern "C" __attribute__((used)) volatile const char kBuildMarker[] =
    "anabranch-aria2: build-aria2.sh; aria2=1.37.0; minSdk=21; "
    "16K-aligned; symbol-hygiene=ANABRANCH_ARIA2_1.0";

namespace {

// 扁平 String[]（key, value 交替）→ KeyVals。奇数长度按丢弃末尾处理——
// 这里没法抛 Java 异常再优雅返回，宁可少一个选项也不崩。
aria2::KeyVals toKeyVals(JNIEnv* env, jobjectArray arr) {
  aria2::KeyVals kv;
  if (arr == nullptr) return kv;
  const jsize n = env->GetArrayLength(arr);
  for (jsize i = 0; i + 1 < n; i += 2) {
    auto jk = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
    auto jv = static_cast<jstring>(env->GetObjectArrayElement(arr, i + 1));
    if (jk != nullptr && jv != nullptr) {
      const char* k = env->GetStringUTFChars(jk, nullptr);
      const char* v = env->GetStringUTFChars(jv, nullptr);
      if (k != nullptr && v != nullptr) kv.emplace_back(k, v);
      if (v != nullptr) env->ReleaseStringUTFChars(jv, v);
      if (k != nullptr) env->ReleaseStringUTFChars(jk, k);
    }
    if (jk != nullptr) env->DeleteLocalRef(jk);
    if (jv != nullptr) env->DeleteLocalRef(jv);
  }
  return kv;
}

std::vector<std::string> toStrings(JNIEnv* env, jobjectArray arr) {
  std::vector<std::string> out;
  if (arr == nullptr) return out;
  const jsize n = env->GetArrayLength(arr);
  out.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
    if (js != nullptr) {
      const char* s = env->GetStringUTFChars(js, nullptr);
      if (s != nullptr) {
        out.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
      }
      env->DeleteLocalRef(js);
    }
  }
  return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
  aria2::libraryInit();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_anabranch_aria2_Aria2Native_sessionNew(JNIEnv* env, jclass,
                                               jobjectArray globalOptions) {
  try {
    aria2::SessionConfig cfg;
    // keepRunning=true：最后一条下载结束后 run(RUN_ONCE) 仍返回 1，session
    // 的生死由 Java 侧 sessionShutdown 说了算——不然「先建 session 后 addUri」
    // 的窗口里循环就自己停了。
    cfg.keepRunning = true;
    // 信号处理器是宿主 App 的东西，一个下载库不该去挂。
    cfg.useSignalHandler = false;
    aria2::Session* s = aria2::sessionNew(toKeyVals(env, globalOptions), cfg);
    return reinterpret_cast<jlong>(s);
  } catch (...) {
    LOGE("sessionNew failed");
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_io_anabranch_aria2_Aria2Native_sessionShutdown(JNIEnv*, jclass,
                                                    jlong session) {
  if (session == 0) return;
  try {
    aria2::shutdown(reinterpret_cast<aria2::Session*>(session), false);
  } catch (...) {
    LOGE("shutdown failed");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_io_anabranch_aria2_Aria2Native_sessionRelease(JNIEnv*, jclass,
                                                   jlong session) {
  if (session == 0) return;
  try {
    aria2::sessionFinal(reinterpret_cast<aria2::Session*>(session));
  } catch (...) {
    LOGE("sessionFinal failed");
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_anabranch_aria2_Aria2Native_addUri(JNIEnv* env, jclass, jlong session,
                                           jobjectArray uris,
                                           jobjectArray options) {
  if (session == 0) return nullptr;
  try {
    aria2::A2Gid gid = 0;
    int rc = aria2::addUri(reinterpret_cast<aria2::Session*>(session), &gid,
                           toStrings(env, uris), toKeyVals(env, options));
    if (rc != 0) {
      LOGE("addUri failed rc=%d", rc);
      return nullptr;
    }
    return env->NewStringUTF(aria2::gidToHex(gid).c_str());
  } catch (...) {
    LOGE("addUri threw");
    return nullptr;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_anabranch_aria2_Aria2Native_runOnce(JNIEnv*, jclass, jlong session) {
  if (session == 0) return -1;
  try {
    // RUN_ONCE：处理完当前就绪的事件即返回（1=还有事做，0=已停，<0=出错）。
    return aria2::run(reinterpret_cast<aria2::Session*>(session),
                      aria2::RUN_ONCE);
  } catch (...) {
    LOGE("run threw");
    return -1;
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_anabranch_aria2_Aria2Native_progressOf(JNIEnv* env, jclass,
                                               jlong session, jstring gidHex) {
  if (session == 0 || gidHex == nullptr) return nullptr;
  try {
    const char* hex = env->GetStringUTFChars(gidHex, nullptr);
    if (hex == nullptr) return nullptr;
    aria2::A2Gid gid = aria2::hexToGid(hex);
    env->ReleaseStringUTFChars(gidHex, hex);
    if (aria2::isNull(gid)) return nullptr;

    aria2::DownloadHandle* h = aria2::getDownloadHandle(
        reinterpret_cast<aria2::Session*>(session), gid);
    if (h == nullptr) return nullptr;
    jlong vals[3] = {static_cast<jlong>(h->getCompletedLength()),
                     static_cast<jlong>(h->getTotalLength()),
                     static_cast<jlong>(h->getDownloadSpeed())};
    aria2::deleteDownloadHandle(h);

    jlongArray out = env->NewLongArray(3);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 3, vals);
    return out;
  } catch (...) {
    LOGE("progressOf threw");
    return nullptr;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_anabranch_aria2_Aria2Native_statusOf(JNIEnv* env, jclass, jlong session,
                                             jstring gidHex) {
  if (session == 0 || gidHex == nullptr) return -1;
  try {
    const char* hex = env->GetStringUTFChars(gidHex, nullptr);
    if (hex == nullptr) return -1;
    aria2::A2Gid gid = aria2::hexToGid(hex);
    env->ReleaseStringUTFChars(gidHex, hex);
    if (aria2::isNull(gid)) return -1;

    aria2::DownloadHandle* h = aria2::getDownloadHandle(
        reinterpret_cast<aria2::Session*>(session), gid);
    if (h == nullptr) return -1;
    aria2::DownloadStatus st = h->getStatus();
    aria2::deleteDownloadHandle(h);

    // 显式映射，不赌 aria2 枚举编号永远不变（Java 侧常量与此一一对应）。
    switch (st) {
      case aria2::DOWNLOAD_ACTIVE:   return 0;
      case aria2::DOWNLOAD_WAITING:  return 1;
      case aria2::DOWNLOAD_PAUSED:   return 2;
      case aria2::DOWNLOAD_COMPLETE: return 3;
      case aria2::DOWNLOAD_ERROR:    return 4;
      case aria2::DOWNLOAD_REMOVED:  return 5;
    }
    return -1;
  } catch (...) {
    LOGE("statusOf threw");
    return -1;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_anabranch_aria2_Aria2Native_errorOf(JNIEnv* env, jclass, jlong session,
                                            jstring gidHex) {
  if (session == 0 || gidHex == nullptr) return nullptr;
  try {
    const char* hex = env->GetStringUTFChars(gidHex, nullptr);
    if (hex == nullptr) return nullptr;
    aria2::A2Gid gid = aria2::hexToGid(hex);
    env->ReleaseStringUTFChars(gidHex, hex);
    if (aria2::isNull(gid)) return nullptr;

    aria2::DownloadHandle* h = aria2::getDownloadHandle(
        reinterpret_cast<aria2::Session*>(session), gid);
    if (h == nullptr) return nullptr;
    aria2::DownloadStatus st = h->getStatus();
    int ec = h->getErrorCode();
    aria2::deleteDownloadHandle(h);

    // 公开 API 只给错误码不给文案；码表见 aria2 的 exit status 文档。
    if (st != aria2::DOWNLOAD_ERROR) return nullptr;
    std::string msg = "aria2 error_code=" + std::to_string(ec);
    return env->NewStringUTF(msg.c_str());
  } catch (...) {
    LOGE("errorOf threw");
    return nullptr;
  }
}
