# Security

Bram is alpha software and has not had a security audit. Do not rely on it yet for sensitive
autonomous actions, and do not expose a local model server directly to an untrusted network.

## Report a vulnerability

Use a private GitHub security advisory for the repository. Do not put API keys, private prompts,
model files, or exploit details in a public issue.

## Current boundaries

### Network and credentials

- Remote endpoints and MCP servers use HTTPS unless the user explicitly allows plain HTTP for that
  one endpoint or server, which is meant for a trusted local network. The manifest permits
  cleartext traffic app-wide, so this check is enforced in Bram's own clients rather than by the
  platform.
- Endpoint API keys, endpoint custom headers, and MCP bearer tokens are encrypted at rest
  with Android Keystore-backed keys.
- An MCP server's identity is pinned to its configured origin: redirects to another host are
  refused, so its `Authorization` header never reaches a third party. Server-provided tool names,
  descriptions, and schemas are parsed defensively and capped in size.
- Remote routing obeys each conversation's privacy class. A Local only conversation never reaches a
  remote endpoint, including through fallback.

### Tools

- Tool output is untrusted input and cannot override system or user instructions.
- Every tool call passes the approval gate. Side effects ask unless the user has granted that scope
  or chosen Auto-approve; an unanswered request times out into a refusal.
- A call recovered from plain text, rather than parsed from the model's tool-call format, is never
  matched by a remembered grant. Once a conversation holds content someone else wrote (a fetched
  page, another app's screen), recovered calls ask even under Auto-approve.
- The screen tools (`tap`, `type_text`, `scroll`) need the user to enable Bram's accessibility
  service in system settings, ask for approval, and can never act on Bram's own window or Android's
  permission-controller and package-installer screens. What `read_screen` returns is marked as
  untrusted.
- File tools reach a directory inside Bram's private storage and at most one document folder the
  user grants through the system picker; writes ask.
- `termux_exec` runs commands as the user through Termux's `RUN_COMMAND` intent, and only when Termux
  is installed, allows external apps, and has granted Bram that permission.
- Skills are authored text rendered into the prompt, not code. Agent-proposed skills stay drafts
  until the user activates them.

### Processes, components, and models

- Native inference runs in `:inference`, a separate, non-exported process, so a backend or driver
  failure does not take the conversation down with it.
- Exported components are limited to the launcher activity and the accessibility service, which
  only the system can bind (`BIND_ACCESSIBILITY_SERVICE`). Every receiver and the other services are
  not exported.
- An imported model is copied into app-private storage with a full SHA-256 recorded at import.
- The llama.cpp runtime is pinned to an immutable upstream commit. A backend is used only after it
  reproduces the CPU reference on the device.

## Known limitations

- No certificate pinning for remote endpoints or MCP servers.
- GGUF files, LiteRT-LM packages, and their embedded templates are untrusted input to native code.
  The import hash identifies the bytes but does not establish who published them.
- Auto-approve runs tools without asking. It is meant for runs the user is willing to vouch for.
- The accessibility service can read and operate other apps once the user enables it. The approval
  gate is what stands between the model and those actions.
- Autonomous runs (tasks and automations) and their auditing are young. The run journal records
  each one, but they should not be treated as a hardened automation platform.

Any change that broadens tool permissions, exported components, cleartext traffic, background
execution, file access, or remote routing should include a threat-model update and tests.
