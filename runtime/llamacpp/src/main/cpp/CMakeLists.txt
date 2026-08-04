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

# Exact source identity for Bram's first CPU baseline. Do not track upstream branches here.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 474c92e722ce77aee2060cd08629b9afb008d81b
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(bram_llama SHARED bram_llama_jni.cpp)
target_compile_features(bram_llama PRIVATE cxx_std_17)
target_compile_options(bram_llama PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(bram_llama PRIVATE llama-common llama android log)
