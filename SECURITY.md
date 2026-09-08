# Security

Bram is pre-alpha and has not received a security audit. Do not rely on it yet for sensitive autonomous actions or expose a local model server directly to an untrusted network.

## Report a vulnerability

Use a private GitHub security advisory for the repository when available. Do not include API keys, private prompts, model files, or exploit details in a public issue.

## Current boundaries

- Remote endpoints use HTTPS unless the user explicitly enables cleartext HTTP for a trusted local network.
- Endpoint credentials are encrypted at rest through Android Keystore-backed storage.
- Tool output is treated as untrusted input and cannot override system or user instructions.
- The approval gate restricts tool execution according to explicit permissions and policy.
- Native inference runs in a separate, non-exported Android process so a backend or driver failure does not have to terminate the conversation UI.
- Imported GGUF files use retained read-only document permissions. Bram verifies a full SHA-256 fingerprint at import and rejects a changed file size at load.
- The ARM64 llama.cpp runtime pins an immutable upstream revision and performs on-device validation before a backend is treated as usable.
- Accelerator correctness is measured against a CPU reference; a backend that merely initializes successfully is not considered validated.
- Agent-created skills are designed to begin in a draft state and require separate activation.

## Known pre-alpha limitations

- The OpenAI-compatible runtime does not yet apply certificate pinning.
- Broader autonomous behavior, memory, skills, and scheduled-work surfaces are still evolving and should not be treated as a hardened automation platform.
- Vulkan compiles and runs on the primary Snapdragon 8 Elite test device but has failed Bram's correctness validation and is not treated as a validated backend there.
- GGUF files and their embedded metadata/templates remain untrusted native-runtime input. Import hashing identifies the selected bytes but does not establish publisher provenance or compare them with an authoritative expected hash.
- Only seekable document-provider files can be loaded; there is no app-managed copy fallback for every provider.
- Accelerator backends, model provenance, sandboxed skill assets, and autonomous-run auditing remain active development areas.

Any change that broadens tool permissions, exported Android components, cleartext traffic, background execution, file access, or remote routing should include a threat-model update and tests.
