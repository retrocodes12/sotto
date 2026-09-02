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

# The Sotto modem plus the JNI bridge, loaded from Kotlin as System.loadLibrary("sotto").
# The modem reuses the Reed-Solomon headers vendored with ggwave (ggwave/src/reed-solomon).
add_library(sotto SHARED jni_bridge.cpp sotto_modem.cpp)
target_include_directories(sotto PRIVATE . ggwave/src)
target_compile_options(sotto PRIVATE -O2 -Wall -Wextra)
target_link_libraries(sotto PRIVATE ggwave log)
