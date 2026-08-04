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
- Agent-created skills are designed to begin in a draft state and require separate activation.

## Known pre-alpha limitations

- The OpenAI-compatible runtime is non-streaming and does not yet apply certificate pinning.
- Persistent conversation, memory, skill, and automation implementations are not present yet.
- The llama.cpp boundary is a stub; native model parsing and backend hardening have not been implemented.
- Download verification, model provenance, sandboxed skill assets, and autonomous-run auditing remain roadmap work.

Any change that broadens tool permissions, exported Android components, cleartext traffic, background execution, file access, or remote routing should include a threat-model update and tests.
