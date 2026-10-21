# Hardware recipe schemas and fixtures

This directory is the format's public face: the JSON Schemas a recipe is written against, a fixture
corpus that pins what is accepted and refused, and the checker that keeps both honest. It is
mirrored at [`KuRue/bram-hardware-recipes`](https://github.com/KuRue/bram-hardware-recipes), where
recipes are contributed; this copy stays beside the app because the app's parser and CI read it
from here.

```
schema/hardware-recipe.schema.json   one recipe: identity, match, runtime, model, workload, proposal, evidence, limits
schema/source-index.schema.json      a source's entry document: identity, revision, recipe hashes
fixtures/recipe/valid/               recipes that must be accepted
fixtures/recipe/invalid/<case>/      one document plus expect.txt naming the field it must be refused at
fixtures/index/…                     the same for source indexes
validate.py                          checks the schemas against the corpus *and* the snapshot the app ships
```

## Checking your work

```bash
python3 recipes/validate.py                       # the corpus, plus the snapshot or published index beside it
python3 recipes/validate.py path/to/recipe.json   # one document, for a contributor before a pull request
```

It needs `jsonschema` (`pip install jsonschema`). CI runs the first form on every change, in this
repository and in the public one.

## Publishing a source

The app downloads a network source only with a detached `index.signature.json` beside `index.json`,
signed over the final index bytes with the publisher's P-256 key. Sign after the last edit to the
index; [`tools/recipe-signing`](../tools/recipe-signing/README.md) has the helper, the format, and how
users check the key fingerprint. The schemas stay unchanged: the signature is a separate file.

## Why there are two implementations, and one corpus

Bram enforces the format in Kotlin (`core:domain`, `HardwareRecipe`), because that is what reads an
untrusted source on the phone. The schemas here are for everyone else — contributors, editors, the
public repository's CI. Two implementations of one format drift unless something joins them, so the
fixture corpus is the contract: each invalid case names the field it must be refused at, in the
vocabulary both sides use (`proposal.threads`, `recipe.maintainer`, `index.recipes[0].sha256`), and
both are checked against it on every change:

- `python3 recipes/validate.py` — what the **schemas** accept and refuse, plus the bundled snapshot's
  hashes and identity against the files beside it;
- `RecipeFixtureCorpusTest` in `core:domain` — the same files through **Bram's parser**.

A rule that changes on one side and not the other fails CI rather than surprising a contributor.

## What the schemas deliberately cannot express

- **Duplicate recipe ids in one index.** JSON Schema cannot compare array items; Bram's parser
  refuses them, and a test covers it.
- **Hashes that match the files.** A schema sees one document at a time. `validate.py` checks the
  bundled snapshot's hashes, and the app checks a downloaded snapshot's when it accepts it.
- **Whether the numbers are true.** A recipe can be perfectly well-formed and still wrong; that is
  what the `evidence` block and [CONTRIBUTING.md](CONTRIBUTING.md) are for.

## The public repository

[`KuRue/bram-hardware-recipes`](https://github.com/KuRue/bram-hardware-recipes) is the public home,
Apache-2.0 licensed like the app.

- **Recipes live there.** A contributor's measurement and the index that lists it belong in that
  repository, where its CI and review apply.
- **The format lands here first.** Schema, fixture, and validator changes start in this repository,
  so the Kotlin parser test and the published corpus move together; the schema, fixtures and
  validator are then copied there byte for byte. Only the two READMEs and `CONTRIBUTING.md` name
  paths of their own.
- **The bundled snapshot stays the reviewed default.** The app ships `official-v1`. The public
  repository is a source a user adds, under the review-and-pin rules in
  `docs/HARDWARE_RECIPES.md`, and publishing it as a network source needs the publisher's key and
  `index.signature.json` ([helper](../tools/recipe-signing/README.md)).
