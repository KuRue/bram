# Hardware recipes

A hardware recipe is a measured configuration for one device, engine build, and model, published so
a phone can start from someone else's sweep instead of running its own. Bram ships one reviewed
snapshot and reads it like any other source: strictly, and with the hashes checked.

This document describes what is in the app today. The plan behind it — a public recipe repository,
user-added sources, evidence upload — is [§6 of the product plan](PRODUCT_PLAN.md#6-hardware-recipes-and-additional-repositories),
and the parts of it that are not implemented yet are listed at the bottom.

## Recipe sources

Open **Settings → Hardware → Recipe sources** to inspect the bundled snapshot or add an external
one. Paste an HTTPS URL ending in `/index.json`, or `owner/repository@<full 40-character commit>`
for a GitHub repository with an index at its root. GitHub shorthand uses the raw content at that
immutable commit. To change that commit, add the new URL as a separate source; an existing generic
HTTPS URL can publish a new revision at the same location.

**Download for review** checks every document's schema, hash, recipe ID, and version. It does not
activate anything. Review the source URL, revision, index SHA-256, device/model conditions, settings,
maintainer, and evidence before choosing **Trust and pin snapshot**. Every user-added source is
labelled external, including one whose index declares itself official. The bundled snapshot is
trusted through the app's distribution; no network source can grant itself that status.

Trust in this version means **explicitly accepting those snapshot bytes**. The SHA-256 pin checks
integrity, not publisher identity. Signed network indexes, verification keys, and key-rotation review
are not implemented; the app makes no signature/authorship claim. External source trust is kept
separate from the local measurement gate described below.

**Check for update** downloads another review. Only accepting it replaces the active pin. A failed
download, hash/schema check, or cache write keeps the accepted snapshot. Changing a source ID or
reusing a currently cached revision for different bytes is refused. Approving an old review cannot
overwrite a source that changed since the review was downloaded.

Accepted sources are available offline after restart. The app retains the current and previous
accepted snapshots; **Restore revision** swaps them without making a network request. Removing a
source confirms removal of its cached suggestions; it does not edit profiles or bundled recipes.
A corrupt cache is reported separately, so healthy sources and the bundled snapshot stay visible.
Review drafts are held in memory and are never implicitly accepted after restart.

Downloads accept HTTPS only, no credentials/query/fragment, and no nonstandard port. Redirects are
refused. Recipe files must be plain JSON filenames beside the index, without paths or escaped URL
characters. Limits are 256 KiB per index, 64 KiB per recipe, 16 recipes and 1 MiB per snapshot,
8 external sources, and two snapshots per source. Each source is stored in one atomically replaced
file capped at 5 MiB (including JSON encoding), bounding the accepted cache to 40 MiB. Cache writes
sync both file contents and directory changes, including source removal. Network connect/read
timeouts are 10 seconds, within a shared 45-second snapshot download budget checked
between requests and reads. Updates are manual; there is no periodic refresh.

## The bundled snapshot

```
app/src/main/assets/hardware-recipes/official-v1/
├── index.json                                    source identity, revision, recipe hashes
└── samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu.json
```

The one recipe is the owner's Galaxy S25 Ultra (SM-S938U1, SM8750) running LFM2.5-2.6B Q4_0 on the
CPU path: 4 threads, 512-token batch, 8K context, measured 2026-10-17 at 100.8 prompt and 27.9
decode tok/s (20.9 at 4K depth). The evidence names the exact model file by SHA-256, so "measured on
this" is a checkable claim rather than a claim about a model name.

## The format

`HardwareRecipe` and `RecipeIndex` in `core:domain` are the format; parsing and matching live in
`HardwareRecipe.kt` and `RecipeMatching.kt`. A recipe carries identity, match conditions, runtime
requirements, model constraints, workload, proposal, evidence, and limits — and nothing else.
Every object's keys are checked against an allowlist, so an unknown field is refused by name rather
than dropped silently.

Two rules make the whole thing safe to point at an untrusted source:

**The vocabulary is the safety.** A recipe can only name settings Bram already offers — a backend, a
thread count, a batch size, an attention or KV mode — each bounded, each with a type. There is no
field for a prompt, a script, a URL, a permission, a memory ceiling, or a way to turn off the CPU
agreement gate, because those are not settings a recipe may have an opinion about. A document that
carries one is refused; it is not partially honoured. `HardwareRecipeTest` asserts exactly that for
each of them.

**Proposals are diffs, not profiles.** Every field in a proposal is optional, and an absent field
means "leave this alone". Applying a recipe produces a list of changes ("Batch 512 → 1024") computed
against the profile it would apply to, so nothing is ever silently overwritten.

## Matching, and what "exact" means

A recipe applies when the manufacturer, the CPU features it requires, the backends it needs, and the
model's architecture and quantization all match. Then it is either:

- **exact** — the device model, the engine commit, and (when recorded) the model file's bytes are
  the ones in the evidence; or
- **a seed** — the phone is the same family (its SoC is one the recipe names) but something differs.
  The candidate carries one line per difference ("measured on engine a94b… this build is deadbee"),
  because a seed is a suggestion to try, not evidence.

A phone whose chip the recipe never named does not see it at all: thread counts and CPU masks are
shaped by the SoC, so a matching device-name string is not enough to carry them.

An unknown local content hash or a recipe without the measured model hash is only a seed. A
different model size is also stated as a difference. OpenCL proposals use the profile's `OPENCL`
backend identity; the runtime's `GPUOpenCL` device prefix is not a profile ID.

## Preview and local validation

The source screen's **Local configuration** section selects a local GGUF profile and lists matching
recipes from the bundled snapshot and accepted external snapshots. A pending source review never
supplies candidates. Each match shows its source/revision, exact-or-seed status, differences,
evidence date, known incompatibilities, and the proposed before/after settings. Selecting **Validate
and apply if faster** explicitly chooses that candidate; loading a source never changes a profile.

Validation uses the existing tuning ownership, CPU reference, native call watches, cancellation,
cooldown, memory gate, and 5% noise margin. Unknown runtimes, unsupported settings, unavailable
backends, Vulkan's automatic-policy exclusion, unavailable cores, out-of-range context/batches, and
settings that would be silently sanitized are refused before loading. Split GGUFs are not offered
for validation until the app has an identity for the complete multipart content. Expert-streaming
profiles are not yet supported by this validator.

The local measurement uses Bram's fixed 24-token CPU reference and teacher-forced replay. Padding
starts at the recipe's context depth, limited to the context both profiles support with a 256-token
reserve. Kernels warm up before recorded timing. Profile replays attach the same tuned threadpools
and prompt/decode thread counts as chat;
the fixed CPU reference keeps its own thread settings. The replay reports those counts and whether
the custom pool was applied; ignored threadpool settings cannot pass validation. Two pairs run in
reversed order. Every replay must return the complete CPU sequence and pass the existing 90% agreement
threshold; a matching short prefix cannot pass. Prompt plus decode time must beat the incumbent by
more than 5% **in both pairs**. A final replay must also pass agreement near the proposed context
limit, against a CPU reference at that depth. The timing total shown is for those local paired
replays; the published pp512/tg128 benchmark numbers remain separate evidence.

The pass stops within the existing ten-minute bound and can be cancelled. A native hang uses the
existing inference-process restart path. Thermal drift or memory pressure interrupts the comparison
instead of granting a win. No profile settings are written during measurement. Before committing,
the source pin, device/runtime fingerprint, and saved profile are checked again. Only a fully
validated winner is saved; the short persistence step disables cancellation. The original loaded
profile is restored after a normal finish; cancellation/failure reports when a reload is required.

Failures, inconclusive speed, and interrupted runs keep the incumbent. Device-local results are
bounded to 128 records and 256 KiB, keyed by source and recipe hashes, exact model content,
device/OS/driver/app/engine fingerprint, settings combinations, and validation-method version.
An excluded recipe has an explicit **Retry validation** action; changing any key ingredient leaves
the old exclusion behind. Process recovery marks an unfinished check Interrupted and never grants
approval from it. Even a previous pass is measured again when requested. Applied profiles retain
source/revision/hash provenance independently of the source cache; removing a source does not
remove that provenance. These regression fixtures establish local correctness agreement and timing;
they do not establish general model quality or a universal speed claim.

## Not implemented yet

The format, matcher, bundled snapshot, source cache, review screen, and rollback are in place;
these are not:

- signed network indexes, pinned verification keys, and key-change review;
- evidence upload;
- the public recipe repository, its JSON Schema, contribution template, and fixtures.
