# Bram documentation

## Start here

| Document | For |
| --- | --- |
| [README](../README.md) | What Bram is and how to get it |
| [Project overview](PROJECT_OVERVIEW.md) | What works today, area by area, with the hardware evidence |
| [Architecture](../ARCHITECTURE.md) | How Bram is put together, and why |
| [Roadmap](../ROADMAP.md) | What is open now and next |
| [Security](../SECURITY.md) | Trust boundaries and known limitations |

## Working on Bram

| Document | For |
| --- | --- |
| [Contributing](../CONTRIBUTING.md) | Ground rules and the verification gates (unit, device, hardware) |
| [Building Bram](BUILDING.md) | Toolchain, optional backends, emulators, the mock server, instrumentation |
| [Hardware recipes](HARDWARE_RECIPES.md) | The recipe format, the bundled S25 Ultra snapshot, and how a phone is matched to one |
| [APK releases](RELEASING.md) | Signing keys, version tags, and the release workflow |
| [Third-party notices](../THIRD_PARTY_NOTICES.md) | Bundled components and their licenses |

## Proposed work

| Document | For |
| --- | --- |
| [Product and execution plan](PRODUCT_PLAN.md) | Navigation and UI, adaptive configuration, Home activity, automations, child agents, hardware recipes, and Hexagon release packaging |

## Records

Design plans and research, kept as written with a status line at the top of each. They explain
why the code is the way it is; for the current state, prefer the documents above.

| Document | What it records |
| --- | --- |
| [Milestone history](history/MILESTONES.md) | Every completed milestone: what landed, what was measured, the dead ends |
| [Harness overhaul](HARNESS_OVERHAUL.md) | The M0–M6 agent-harness plan and its results (complete) |
| [Device adaptation](DEVICE_ADAPTATION.md) | On-device tuning, compatibility, and hardware coverage (phases 1–3 shipped) |
| [Performance options](PERFORMANCE_OPTIONS.md) | Research on CPU, NPU, GPU, and alternative engines (August 2026) |
| [Networking client decision](NETWORKING-CLIENT-DECISION.md) | Why remote generation moved to OkHttp |
