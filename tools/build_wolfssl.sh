#!/usr/bin/env bash
#
# 交叉编译 wolfSSL 为 Android native 库
#
# 为什么用 NDK r16b：
#   这是最后一个支持 android-9（Android 2.3）的 NDK。r17 起最低只到 android-16，
#   而 Android 2.3 的 libc 缺少 __stack_chk_fail 等较新符号，用高版本 NDK 编出的
#   库在 2.3 上会加载失败。
#
# 为什么在链接后追加 -Wl,-z,lazy 与 cxa_stub.o：
#   1) NDK 的 CMake toolchain 默认加 -Wl,-z,now，会在 dlopen 时立即解析全部符号，
#      一旦某个符号在 API 9 不存在就直接加载失败；改成惰性绑定可避免。
#   2) clang 会为 -funwind-tables 引入 libgcc 的 C++ EH 辅助符号
#      (__cxa_call_unexpected / __cxa_begin_cleanup / __cxa_type_match)，
#      Android 2.3 的 libc 并不导出它们，故用 cxa_stub.c 补齐空实现。
#      wolfSSL 是纯 C 实现，不会进入 C++ 异常路径。
#
# 用法：
#   ./build_wolfssl.sh                 # 默认 armeabi-v7a / android-9
#   ./build_wolfssl.sh arm64-v8a 21    # arm64（现代机型，实际走系统 TLS，仅保证可安装）
#
set -e

NDK="${NDK:-/opt/ndk-r16b}"
ABI="${1:-armeabi-v7a}"
PLATFORM="${2:-android-9}"
WOLFSSL_SRC="${WOLFSSL_SRC:-/tmp/wssl}"
BUILD_DIR="${BUILD_DIR:-/tmp/wolfbuild}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$HERE/../app/src/main/jniLibs/$ABI"

CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
SYSROOT="$NDK/sysroot"
STRIP="$NDK/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-strip"
[ "$ABI" = "arm64-v8a" ] && STRIP="$NDK/toolchains/aarch64-linux-android-4.9/prebuilt/linux-x86_64/bin/aarch64-linux-android-strip"

echo "==> ABI=$ABI PLATFORM=$PLATFORM"
mkdir -p "$OUT_DIR"

# 1. 配置并编译 libwolfssl.so
cmake -S "$BUILD_DIR" -B "$BUILD_DIR/build-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$PLATFORM" \
    -DANDROID_TOOLCHAIN=clang \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-fno-stack-protector"

cmake --build "$BUILD_DIR/build-$ABI" -j4 --target wolfssl

# 2. 编译 C++ EH 符号补齐桩
case "$ABI" in
    armeabi-v7a) TARGET=armv7-none-linux-androideabi ;;
    arm64-v8a)   TARGET=aarch64-none-linux-android ;;
    x86)         TARGET=i686-none-linux-android ;;
    *)           TARGET=armv7-none-linux-androideabi ;;
esac
"$CLANG" --target="$TARGET" --gcc-toolchain="$NDK/toolchains/llvm/prebuilt/linux-x86_64" \
    --sysroot="$SYSROOT" -D__ANDROID_API__="${PLATFORM#android-}" \
    -fPIC -Os -c "$BUILD_DIR/cxa_stub.c" -o "$BUILD_DIR/build-$ABI/cxa_stub.o"

# 3. 重链接：关掉 BIND_NOW，链入 stub
cd "$BUILD_DIR/build-$ABI"
CMD="$(cat CMakeFiles/wolfssl.dir/link.txt)"
eval "$CMD cxa_stub.o -Wl,-z,lazy"

# 4. strip 并输出
"$STRIP" --strip-unneeded libwolfssl.so
cp libwolfssl.so "$OUT_DIR/libwolfssl.so"
echo "==> 输出: $OUT_DIR/libwolfssl.so"
echo "==> 残留未定义符号（应仅为基础 libc）:"
readelf --dyn-syms -W libwolfssl.so | awk '$7=="UND" {print $8}' | sed 's/@.*//' | sort -u | tr '\n' ' '
echo
