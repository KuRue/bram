cmake_minimum_required(VERSION 3.22.1)
project(bram_llama LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

# Adreno acceleration for Milestone 2. The Vulkan backend is validated against the CPU
# reference before its capability is reported as available; CPU remains the correctness oracle.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# The NDK sysroot ships the C Vulkan headers but not the C++ bindings (vulkan/vulkan.hpp) that
# ggml-vulkan.cpp includes. Vendor a pinned Vulkan-Headers (which provides vulkan.hpp) and point
# find_package(Vulkan)'s include dir at it; libvulkan.so is still linked from the sysroot.
FetchContent_Declare(
    Vulkan-Headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    # vulkan-sdk-1.4.321.0
    GIT_TAG 2cd90f9d20df57eac214c148f3aed885372ddcfe
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(Vulkan-Headers)
FetchContent_GetProperties(Vulkan-Headers SOURCE_DIR _bram_vulkan_headers_src)
set(Vulkan_INCLUDE_DIR "${_bram_vulkan_headers_src}/include" CACHE PATH "Bram vendored Vulkan headers" FORCE)

# ggml-vulkan compiles its SPIR-V shaders with glslc at build time. glslc is a host tool that
# ships inside the NDK (shader-tools/<host>/), which is outside the cross-compile sysroot, so
# CMake's FindVulkan cannot discover it on its own. Point it at the NDK copy for the current host.
if(NOT Vulkan_GLSLC_EXECUTABLE)
    file(GLOB _bram_glslc
        "${ANDROID_NDK}/shader-tools/*/glslc"
        "${ANDROID_NDK}/shader-tools/*/glslc.exe"
        "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc"
        "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc.exe")
    if(_bram_glslc)
        list(GET _bram_glslc 0 _bram_glslc_path)
        set(Vulkan_GLSLC_EXECUTABLE "${_bram_glslc_path}" CACHE FILEPATH "NDK glslc" FORCE)
        message(STATUS "Bram: using NDK glslc at ${Vulkan_GLSLC_EXECUTABLE}")
    else()
        message(FATAL_ERROR "Bram: could not locate glslc under the NDK shader-tools directory")
    endif()
endif()

# ggml-vulkan resolves SPIRV-Headers with find_package(SPIRV-Headers CONFIG REQUIRED) for its
# host-side vulkan-shaders-gen tool. The NDK does not ship it, so vendor a pinned copy first:
# FetchContent (CMake >= 3.24) writes the find_package redirect that satisfies that lookup.
FetchContent_Declare(
    SPIRV-Headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    # vulkan-sdk-1.4.321.0
    GIT_TAG 2a611a970fdbc41ac2e3e328802aed9985352dca
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(SPIRV-Headers)
FetchContent_GetProperties(SPIRV-Headers SOURCE_DIR _bram_spirv_headers_src)

# ggml-vulkan requires find_package(SPIRV-Headers CONFIG REQUIRED). FetchContent's automatic
# redirect is unreliable under this NDK/CMake combination, so publish an explicit package config
# that points find_package at the in-tree interface target created just above.
set(_bram_spirv_headers_config_dir "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-config")
file(WRITE "${_bram_spirv_headers_config_dir}/SPIRV-HeadersConfig.cmake"
    "# Redirect to the FetchContent-populated in-tree SPIRV-Headers target.\n")
file(WRITE "${_bram_spirv_headers_config_dir}/SPIRV-HeadersConfigVersion.cmake"
    "set(PACKAGE_VERSION \"1.4.321\")\nset(PACKAGE_VERSION_COMPATIBLE TRUE)\nset(PACKAGE_VERSION_EXACT TRUE)\n")
set(SPIRV-Headers_DIR "${_bram_spirv_headers_config_dir}" CACHE PATH "Bram in-tree SPIRV-Headers" FORCE)

# ggml builds vulkan-shaders-gen for the host via ExternalProject. That host sub-build inherits the
# Ninja generator but not CMAKE_MAKE_PROGRAM, so its configure fails with "unable to find a build
# program corresponding to Ninja". Supply a host toolchain (ggml's documented override hook) that
# finds a host compiler the same way ggml does and points at this build's ninja. Using
# ${CMAKE_MAKE_PROGRAM} keeps it correct on the Linux CI host too.
file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" _bram_ninja_path)
set(_bram_host_toolchain "${CMAKE_CURRENT_BINARY_DIR}/bram-vk-host-toolchain.cmake")
file(WRITE "${_bram_host_toolchain}"
    "set(CMAKE_C_FLAGS -O2)\n"
    "set(CMAKE_CXX_FLAGS -O2)\n"
    "set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\n"
    "set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)\n"
    "set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)\n"
    "set(CMAKE_MAKE_PROGRAM \"${_bram_ninja_path}\" CACHE FILEPATH \"\" FORCE)\n"
    "find_program(_bram_host_cc NAMES cl gcc clang NO_CMAKE_FIND_ROOT_PATH)\n"
    "find_program(_bram_host_cxx NAMES cl g++ clang++ NO_CMAKE_FIND_ROOT_PATH)\n"
    "set(CMAKE_C_COMPILER \"\${_bram_host_cc}\")\n"
    "set(CMAKE_CXX_COMPILER \"\${_bram_host_cxx}\")\n")
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${_bram_host_toolchain}" CACHE FILEPATH "Bram host toolchain" FORCE)

# Exact source identity for Bram's first CPU baseline. Do not track upstream branches here.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 474c92e722ce77aee2060cd08629b9afb008d81b
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama_cpp)

# ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp>. In a Vulkan SDK that header sits under the
# same include root as vulkan/, but with vendored headers it lives in the separate SPIRV-Headers
# tree, and ggml-vulkan does not link the SPIRV-Headers target for its own compile. Add the include
# to the target directly so the backend source compiles.
if(TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE "${_bram_spirv_headers_src}/include")
endif()

add_library(bram_llama SHARED bram_llama_jni.cpp)
target_compile_features(bram_llama PRIVATE cxx_std_17)
target_compile_options(bram_llama PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(bram_llama PRIVATE llama-common llama android log)
