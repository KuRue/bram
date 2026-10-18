# Hardware recipe schemas and fixtures

This directory is the format's public face: the JSON Schemas a recipe is written against, a fixture
corpus that pins what is accepted and refused, and the checker that keeps both honest. It is staged
here so it can become the public **`KuRue/bram-hardware-recipes`** repository the product plan calls
for — which is **the owner's to create**; nothing here publishes itself.

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
python3 recipes/validate.py                       # the corpus and the app's bundled snapshot
python3 recipes/validate.py path/to/recipe.json   # one document, for a contributor before a pull request
```

It needs `jsonschema` (`pip install jsonschema`). CI runs the first form on every change.

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

## Before this becomes the public repository

1. The owner creates `KuRue/bram-hardware-recipes` and decides its license (Apache-2.0 would match
   the app, and the plan recommends a permissive license for schema and data — but it is the owner's
   decision, exactly as the app's license was).
2. These files move there unchanged, with a CI workflow that runs `validate.py` on every pull request
   and a short `README` pointing readers at the app.
3. The app's bundled snapshot stays the reviewed default; the public repository is a source a user
   adds, under the review-and-pin rules in `docs/HARDWARE_RECIPES.md`.
