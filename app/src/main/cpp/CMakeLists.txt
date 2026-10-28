cmake_minimum_required(VERSION 3.10)
project(wolftls C)

# 预编译的 libwolfssl.so（由 tools/build_wolfssl.sh 产出，放在 jniLibs/<ABI>/）
add_library(wolfssl SHARED IMPORTED)
set_target_properties(wolfssl PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwolfssl.so)

add_library(wolftls SHARED wolfssl_jni.c)

# 与 libwolfssl.so 编译时完全一致的宏配置，避免结构体/函数声明不一致
target_compile_definitions(wolftls PRIVATE WOLFSSL_USER_SETTINGS)
target_include_directories(wolftls PRIVATE ${CMAKE_SOURCE_DIR})

# Android 2.3 (API 9)：libc 没有 __stack_chk_fail，且需惰性绑定以免加载期解析失败
target_compile_options(wolftls PRIVATE -fno-stack-protector)
target_link_options(wolftls PRIVATE -Wl,-z,lazy)

target_link_libraries(wolftls wolfssl log)
