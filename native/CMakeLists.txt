cmake_minimum_required(VERSION 3.18)
project(babble_jni C CXX)

# Configured by build.py:
#   WHISPER_DIR   path to a whisper.cpp checkout
#   JAVA_HOME_DIR JDK used for jni.h
#   LIB_OUTPUT     output library name (babble_cuda / babble_cpu)
#   GGML_CUDA     ON for the GPU build

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(babble SHARED babble_jni.cpp)
target_include_directories(babble PRIVATE ${JAVA_HOME_DIR}/include ${JAVA_HOME_DIR}/include/linux)
target_link_libraries(babble PRIVATE whisper)
# Self-contained: no libstdc++ version requirements, and don't leak ggml symbols into the JVM.
target_link_options(babble PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL)
set_target_properties(babble PROPERTIES OUTPUT_NAME ${LIB_OUTPUT})
