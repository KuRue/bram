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

# Arm KleidiAI microkernels for the CPU backend (arm64 only; the x86_64 emulator build must not
# see the option — the ggml integration adds ARM sources for any architecture). A plain Android
# arm64 build configures no ISA extensions, so ggml compiles none of the dotprod/i8mm kernels;
# the ones Bram wants are added below with their own -march after llama.cpp is fetched.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# Adreno acceleration for Milestone 2. The Vulkan backend is validated against the CPU
# reference before its capability is reported as available; CPU remains the correctness oracle.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# OpenCL for Adreno. ggml ships matmul kernels tuned for Adreno specifically, and Vulkan on this
# hardware fails the correctness comparison in an operation every layer uses, so this is the more
# promising GPU path here rather than a second-best one. Opt-in through BRAM_OPENCL because it adds
# two vendored dependencies and a device without an OpenCL driver gains nothing from it.
option(BRAM_OPENCL "Build the OpenCL backend for Adreno" OFF)
if(BRAM_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    # Embed the kernels rather than shipping them beside the library: the runtime loads them from
    # the binary, which avoids another set of asset files to keep in step with the .so.
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)

    # Android has no OpenCL SDK, so the headers come from Khronos.
    FetchContent_Declare(
        OpenCL-Headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        # v2024.10.24
        GIT_TAG 886dc9b7ff48366c2adca31d8587cf47169ba893
        GIT_SHALLOW FALSE
        GIT_PROGRESS TRUE
    )
    FetchContent_MakeAvailable(OpenCL-Headers)
    FetchContent_GetProperties(OpenCL-Headers SOURCE_DIR _bram_opencl_headers_src)

    # The library is linked against a copy of the vendor's own libOpenCL.so rather than the Khronos
    # ICD loader. The loader enumerates drivers through an ICD registry, and this device has no
    # /vendor/Khronos/OpenCL/vendors directory to enumerate — it reported "platform IDs not
    # available" for exactly that reason. The vendor library is in the platform's public library
    # list, so the real one is loaded from /vendor at runtime; this copy only satisfies the linker.
    set(_bram_opencl_stub "${CMAKE_CURRENT_SOURCE_DIR}/opencl-stub/libOpenCL.so")
    if(NOT EXISTS "${_bram_opencl_stub}")
        message(FATAL_ERROR "BRAM_OPENCL needs opencl-stub/libOpenCL.so, pulled from a device.")
    endif()

    # find_package(OpenCL) has no config to find here. Seed the cache entries FindOpenCL checks —
    # the singular ones — so it accepts these instead of searching a sysroot with no OpenCL in it.
    set(OpenCL_INCLUDE_DIR "${_bram_opencl_headers_src}" CACHE PATH "Bram OpenCL headers" FORCE)
    set(OpenCL_LIBRARY "${_bram_opencl_stub}" CACHE FILEPATH "Bram OpenCL link stub" FORCE)
endif()

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

# The Hexagon SDK's hexagon_fun.cmake calls string(FIND ${PREBUILT_LIB_DIR} ...) at include time,
# but PREBUILT_LIB_DIR is only supplied by the per-skel sub-builds. Left empty the call loses an
# argument and configuration fails. Define an inert default: it matches none of the toolv8x probes,
# which is the correct answer for the top-level include, and each skel still passes its own value.
if(GGML_HEXAGON AND NOT PREBUILT_LIB_DIR)
    set(PREBUILT_LIB_DIR "bram-unused" CACHE STRING "Inert default for the Hexagon SDK probes")
endif()

# Exact source identity for Bram's first CPU baseline. Do not track upstream branches here.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG a94d563ed801d1da1b8c2432946de07d0231bb3d
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

# KleidiAI's dotprod/i8mm microkernels. ggml compiles only the kernels whose ISA the toolchain was
# configured for, and a plain arm64 Android build configures none, so the kernels and the trait
# that dispatches to them are built here as an OBJECT library with their own -march. That is safe
# on every device: the trait runtime-checks the CPU (ggml_cpu_has_dotprod / ggml_cpu_has_arm_i8mm)
# before selecting a kernel, and the rest of ggml-cpu stays at the armv8-a baseline.
# The trait sources are taken out of ggml-cpu's own list because per-source compile options do not
# survive into a target defined in another directory; an object library's own options always do,
# and its objects merge into ggml-cpu when linked.
if(TARGET ggml-cpu AND ANDROID_ABI STREQUAL "arm64-v8a")
    # FetchContent lowercases the declared name for the populated directory.
    set(_bram_kleidiai_src "${CMAKE_BINARY_DIR}/_deps/kleidiai_download-src")
    if(EXISTS "${_bram_kleidiai_src}/kai/ukernels/matmul")
        set(_bram_kleidiai_kernels
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p1x8_qsi4c32p4x8_1x4x32_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p1x4_qsi4c32p4x4_1x4_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p4x4_qsi4c32p4x4_16x4_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi8cxp/kai_matmul_clamp_f32_qai8dxp4x4_qsi8cxp4x4_16x4_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi8cxp/kai_matmul_clamp_f32_qai8dxp1x4_qsi8cxp4x4_1x4_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi8cxp/kai_matmul_clamp_f32_qai8dxp1x8_qsi8cxp4x8_1x4_neon_dotprod.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p4x8_qsi4c32p4x8_16x4_neon_i8mm.c"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi8cxp/kai_matmul_clamp_f32_qai8dxp4x8_qsi8cxp4x8_16x4_neon_i8mm.c")

        # The trait compiles here (not in ggml-cpu) so its kernel tables see the ISA extensions.
        # ggml-cpu.cpp still calls ggml_backend_cpu_kleidiai_buffer_type() under
        # GGML_USE_CPU_KLEIDIAI, and these objects are linked into ggml-cpu, so the symbol exists.
        get_target_property(_bram_cpu_sources ggml-cpu SOURCES)
        list(FILTER _bram_cpu_sources EXCLUDE REGEX "kleidiai/(kleidiai|kernels)\\.cpp$")
        set_property(TARGET ggml-cpu PROPERTY SOURCES ${_bram_cpu_sources})

        set(_bram_kleidiai_trait
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/ggml-cpu/kleidiai/kleidiai.cpp"
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/ggml-cpu/kleidiai/kernels.cpp")
        add_library(bram_kleidiai_arch OBJECT ${_bram_kleidiai_trait} ${_bram_kleidiai_kernels})
        set_target_properties(bram_kleidiai_arch PROPERTIES C_STANDARD 11 CXX_STANDARD 17)
        target_compile_options(bram_kleidiai_arch PRIVATE -march=armv8.6-a+dotprod+i8mm)
        target_compile_definitions(bram_kleidiai_arch PRIVATE GGML_USE_CPU_KLEIDIAI _GNU_SOURCE _XOPEN_SOURCE=600)
        target_include_directories(bram_kleidiai_arch PRIVATE
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/.."
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/."
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/ggml-cpu"
            "${CMAKE_BINARY_DIR}/_deps/llama_cpp-src/ggml/src/../include"
            "${_bram_kleidiai_src}"
            "${_bram_kleidiai_src}/kai"
            "${_bram_kleidiai_src}/kai/ukernels"
            "${_bram_kleidiai_src}/kai/ukernels/matmul"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi8cxp"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_fp32_bf16p_bf16p"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_f16p_qsi4c32p"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/matmul_clamp_f32_f32p_f32p"
            "${_bram_kleidiai_src}/kai/ukernels/matmul/pack")
        target_link_libraries(ggml-cpu PRIVATE bram_kleidiai_arch)
        message(STATUS "Bram: KleidiAI dotprod/i8mm kernels and trait built with -march=armv8.6-a+dotprod+i8mm")
    else()
        message(WARNING "Bram: KleidiAI sources not found at ${_bram_kleidiai_src}; skipped")
    endif()
endif()

add_library(bram_llama SHARED bram_llama_jni.cpp)
target_compile_features(bram_llama PRIVATE cxx_std_17)
target_compile_options(bram_llama PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(bram_llama PRIVATE llama-common llama android log)

# The Hexagon backend also produces DSP-side skel libraries (libggml-htp-vNN.so), built by
# ExternalProject. Gradle asks the native build only for bram_llama, so without an explicit
# dependency those sub-projects are configured but never built. They are Hexagon binaries, not
# ARM64, and the NPU loader resolves them by path at runtime, so stage them next to the JNI library
# for packaging; ADSP_LIBRARY_PATH points at the extracted directory on device.
if(GGML_HEXAGON)
    set(_bram_htp_targets htp-v73 htp-v75 htp-v79 htp-v81)
    add_dependencies(bram_llama ${_bram_htp_targets})
    set(_bram_htp_src "${CMAKE_BINARY_DIR}/_deps/llama_cpp-build/ggml/src/ggml-hexagon")
    foreach(_htp_version v73 v75 v79 v81)
        add_custom_command(TARGET bram_llama POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${_bram_htp_src}/libggml-htp-${_htp_version}.so"
                "$<TARGET_FILE_DIR:bram_llama>/"
            COMMENT "Staging libggml-htp-${_htp_version}.so beside the JNI library"
            VERBATIM)
    endforeach()
endif()
