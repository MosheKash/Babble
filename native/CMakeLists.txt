cmake_minimum_required(VERSION 3.18)

# Configured by build.py (or the natives CI workflow):
#   WHISPER_DIR   path to a whisper.cpp checkout
#   JAVA_HOME_DIR JDK used for jni.h
#   LIB_OUTPUT    output library name (babble_cuda / babble_vulkan / babble_metal / babble_cpu)
#   GGML_CUDA / GGML_VULKAN / GGML_METAL   which GPU backend (none = CPU only)

# Windows: link the C runtime statically so players don't need a Visual C++ redistributable.
# (Must be set before project() / add_subdirectory so whisper and ggml use the same runtime.)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
cmake_policy(SET CMP0091 NEW)

project(babble_jni C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(APPLE)
    # Ship one self-contained dylib: compile the Metal shaders into it.
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

if(WIN32)
    set(JNI_MD win32)
elseif(APPLE)
    set(JNI_MD darwin)
else()
    set(JNI_MD linux)
endif()

add_library(babble SHARED babble_jni.cpp)
target_include_directories(babble PRIVATE ${JAVA_HOME_DIR}/include ${JAVA_HOME_DIR}/include/${JNI_MD})
target_link_libraries(babble PRIVATE whisper)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # Self-contained: no libstdc++ version requirements, and don't leak ggml symbols into the JVM.
    target_link_options(babble PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL)
endif()
set_target_properties(babble PROPERTIES OUTPUT_NAME ${LIB_OUTPUT})
if(WIN32)
    set_target_properties(babble PROPERTIES PREFIX "")  # babble_vulkan.dll, as System.mapLibraryName expects
endif()
