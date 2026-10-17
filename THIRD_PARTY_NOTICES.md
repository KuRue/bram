# Third-party notices

Bram's APK includes the components below. Versions are the ones pinned in
`gradle/libs.versions.toml` and `runtime/llamacpp/src/main/cpp/CMakeLists.txt`; update this file
when a pin changes.

## llama.cpp / ggml

Bram's local runtime is built from
[`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) commit
`a94d563ed801d1da1b8c2432946de07d0231bb3d`. The same license text ships in the APK as
`llama_cpp_LICENSE.txt`.

MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## KleidiAI

Bram's ARM64 CPU build includes the KleidiAI microkernels
([`ARM-software/kleidiai`](https://github.com/ARM-software/kleidiai) v1.24.0), fetched at build
time by llama.cpp's build system and compiled into the CPU backend. The dotprod/i8mm kernels are
selected only at runtime on CPUs that support those instructions.

MIT License

Copyright 2025 Arm Limited and/or its affiliates

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Apache License 2.0 components

These are distributed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0):

| Component | Version | Source |
| --- | --- | --- |
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) | 0.17.1 | [google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| OkHttp and Okio | 5.4.0 | [square/okhttp](https://github.com/square/okhttp) |
| Gson (a LiteRT-LM dependency) | 2.14.0 | [google/gson](https://github.com/google/gson) |
| Haze | 1.7.2 | [chrisbanes/haze](https://github.com/chrisbanes/haze) |
| AndroidX and Jetpack Compose | see `gradle/libs.versions.toml` | [androidx](https://github.com/androidx/androidx) |
| Kotlin standard library and kotlinx.coroutines | see `gradle/libs.versions.toml` | [JetBrains/kotlin](https://github.com/JetBrains/kotlin), [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OpenCL-Headers (compile time, opt-in OpenCL build only) | `886dc9b7` | [KhronosGroup/OpenCL-Headers](https://github.com/KhronosGroup/OpenCL-Headers) |

## Vulkan and SPIR-V headers

The Vulkan backend compiles against
[`KhronosGroup/Vulkan-Headers`](https://github.com/KhronosGroup/Vulkan-Headers) at `2cd90f9d`
(Apache-2.0 or MIT) and [`KhronosGroup/SPIRV-Headers`](https://github.com/KhronosGroup/SPIRV-Headers)
at `2a611a97` (MIT-style Khronos license). Both are header-only; their terms are in each repository.

## JSON-java

[`stleary/JSON-java`](https://github.com/stleary/JSON-java) (`org.json:json`, 20250517) is in the
public domain.
