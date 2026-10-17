# Hardware recipes

A hardware recipe is a measured configuration for one device, engine build, and model, published so
a phone can start from someone else's sweep instead of running its own. Bram ships one reviewed
snapshot and reads it like any other source: strictly, and with the hashes checked.

This document describes what is in the app today. The plan behind it — a public recipe repository,
user-added sources, evidence upload — is [§6 of the product plan](PRODUCT_PLAN.md#6-hardware-recipes-and-additional-repositories),
and the parts of it that are not implemented yet are listed at the bottom.

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

Every candidate — exact included — still has to be validated locally before its settings take
effect, and a failed check keeps the local known-good profile. That local revalidation is
[slice 7's remaining work](#not-implemented-yet).

## Not implemented yet

The format, the matcher, and the snapshot are in place; these are not:

- the source manager (adding a GitHub repository or HTTPS index, pinned revisions, key-change
  review, offline cache, rollback);
- local revalidation before a proposal is applied, and the device-local exclusion recorded when it
  fails;
- the Settings → Hardware → Recipe sources screen;
- evidence upload;
- the public recipe repository, its JSON Schema, contribution template, and fixtures.
