cmake_minimum_required(VERSION 3.22)
project(sotto CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggwave core, vendored (see ggwave/VENDORED.md). Always optimised: the decoder
# runs an FFT per 1024-sample frame on the capture thread, and AGP's debug
# variant would otherwise compile it at -O0.
add_library(ggwave STATIC ggwave/src/ggwave.cpp)
target_include_directories(ggwave PUBLIC ggwave/include ggwave/src)
target_compile_options(ggwave PRIVATE -O2 -Wno-unused-parameter)

# Thin JNI wrapper, loaded from Kotlin as System.loadLibrary("sotto").
add_library(sotto SHARED ggwave_jni.cpp)
target_compile_options(sotto PRIVATE -O2 -Wall -Wextra)
target_link_libraries(sotto PRIVATE ggwave log)
