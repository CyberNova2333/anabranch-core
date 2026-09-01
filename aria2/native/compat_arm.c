/*
 * compat_arm.c — 仅 armeabi-v7a（32 位，minSdk 21）参与最终链接。
 *
 * 32 位 bionic 的 fseeko/ftello 是 API 24 才进 libc.so 的（64 位 ABI 从
 * API 21 起就有；NDK「使用 64 位文件偏移」文档与 bionic 头的
 * __INTRODUCED_IN 标注可查）。哪个依赖库的 configure 拿错误的 stub 探测
 * 到了它们，产物在 Android 7.0 以下的 32 位设备上就会因 dlsym 缺口拒绝
 * 加载——加载失败，一行日志都没有。
 *
 * 双保险：
 *  a) 编译期 -D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS 让 libc++ 的 filebuf 不引用
 *     它们（主要来源）；
 *  b) 本文件给漏网的引用一个本地实现兜底。32 位 bionic 的 off_t 就是
 *     32 位 long，与 fseek/ftell 原型等价；>2GB 的单文件定位在 32 位上
 *     本就无解，与系统自身行为一致。version script 会把这两个符号
 *     local 化，绝不外泄。
 *
 * 真正防回归的不是这个文件，是 build-aria2.sh 构建后那道「UND 符号对
 * NDK API-21 stub 差集必须为空」的硬校验——任何这里兜不住的高 API 符号
 * 都会让构建当场失败，而不是等设备上炸。
 */
#if defined(__ANDROID__) && defined(__arm__)

#include <stdio.h>

__attribute__((visibility("hidden"))) int fseeko(FILE* fp, off_t off,
                                                 int whence) {
  return fseek(fp, off, whence);
}

__attribute__((visibility("hidden"))) off_t ftello(FILE* fp) {
  return ftell(fp);
}

#endif /* __ANDROID__ && __arm__ */
