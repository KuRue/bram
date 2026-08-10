# Security

Bram is pre-alpha and has not received a security audit. Do not rely on it yet for sensitive autonomous actions or expose a local model server directly to an untrusted network.

## Report a vulnerability

Use a private GitHub security advisory for the repository when available. Do not include API keys, private prompts, model files, or exploit details in a public issue.

## Current boundaries

- Remote endpoints use HTTPS unless the user explicitly enables cleartext HTTP for a trusted local network.
- Endpoint credentials are encrypted at rest through Android Keystore-backed storage.
- Tool output is untrusted and cannot override system or user instructions.
- The current approval gate automatically permits only read-only tools with no requested Android permissions.
- Native inference is assigned to a separate, non-exported Android process.
- Imported GGUF files use retained read-only document permissions. Bram verifies a full SHA-256
  fingerprint at import and rejects a changed file size at load.
- The ARM64 CPU runtime pins an immutable llama.cpp revision and runs a tokenizer plus one-token
  decode self-test before reporting CPU as validated.
- Agent-created skills are designed to begin in a draft state and require separate activation.

## Known pre-alpha limitations

- The OpenAI-compatible runtime is non-streaming and does not yet apply certificate pinning.
- Persistent conversation, memory, skill, and automation implementations are not present yet.
- The llama.cpp CPU path builds in CI but has not yet completed physical-device correctness,
  cancellation, process-death, low-memory, or thermal-soak validation.
- GGUF files and their embedded metadata/templates remain untrusted native-runtime input. Import
  hashing identifies the selected bytes but does not establish publisher provenance or compare
  them with an authoritative expected hash.
- Only seekable document-provider files can be loaded; there is no app-managed copy fallback.
- Accelerator backends, model provenance, sandboxed skill assets, and autonomous-run auditing
  remain roadmap work.

Any change that broadens tool permissions, exported Android components, cleartext traffic, background execution, file access, or remote routing should include a threat-model update and tests.
