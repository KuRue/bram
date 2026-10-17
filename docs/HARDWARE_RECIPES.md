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
separate from the eventual local measurement gate, and this screen cannot apply settings yet.

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
file capped at 5 MiB (including JSON encoding), bounding the accepted cache to 40 MiB. Network
connect/read timeouts are 10 seconds, within a shared 45-second snapshot download budget checked
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

Every candidate — exact included — still has to be validated locally before its settings take
effect, and a failed check keeps the local known-good profile. That local revalidation is
[slice 7's remaining work](#not-implemented-yet).

## Not implemented yet

The format, matcher, bundled snapshot, source cache, review screen, and rollback are in place;
these are not:

- signed network indexes, pinned verification keys, and key-change review;
- local revalidation before a proposal is applied, and the device-local exclusion recorded when it
  fails;
- profile-specific matching and before/after proposals in the settings screen;
- evidence upload;
- the public recipe repository, its JSON Schema, contribution template, and fixtures.
