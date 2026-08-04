# Native integration boundary

Vendor a pinned llama.cpp revision here only after the CPU vertical slice is ready. The Android build should produce backend-specific shared libraries that are loaded inside the `:inference` process, never the UI process.

Recommended order:

1. CPU ARM64 and exact tokenizer API.
2. Vulkan and Adreno OpenCL as separate optional libraries.
3. Snapdragon Hexagon backend with Q4_0 correctness fixtures.
4. A native capability probe that performs a tiny operation and compares it to a CPU reference.

The engine build hash and backend library hashes must be included in cached execution-plan fingerprints.
