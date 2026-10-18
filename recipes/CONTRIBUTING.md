# Contributing a hardware recipe

A recipe is one measured configuration for one device, engine build, and model. It is worth
contributing when you have run the measurement yourself on the hardware it names, and when someone
with the same phone and model could load your settings and see the same result.

Filling one in is mostly transcription. What takes judgement is the evidence, so that is most of this
document.

## Where the values come from

Bram shows everything a recipe must name, in the app: **Profiles → Advanced tools → Runtime
identity** — the engine commit, the device identity, the model file, and its full SHA-256. Select a
value to copy it.

- **Engine** (`runtime.engineCommits`): the llama.cpp commit the measurement ran on. A recipe is a
  measurement of one engine build; when the app's engine moves, the recipe becomes a seed that has to
  be revalidated locally, which is why it is recorded rather than assumed.
- **Model SHA-256** (`model.sha256`): the exact file the numbers came from, so "measured on this"
  is checkable. A recipe for a *split* GGUF cannot name a single file's hash yet and is not accepted
  for local validation; measure a single-file quantization instead.

## Measuring

Use the app's own benchmark (**Profiles → the profile → Performance → Benchmark**), on a phone that
is not charging if you can manage it, and let it finish:

- it runs three repetitions per test and records the spread; publish the spread, not just the mean;
- it cools back to the starting battery temperature between tests, which is what makes the numbers
  comparable at all;
- it records the battery temperature — put the start and end values in the recipe.

Record `flashAttention`, `kvCache`, `loadMode`, `batchTokens` and the rest exactly as the profile that
was measured had them. A recipe whose numbers came from different settings is worse than no recipe.

For an accelerator (NPU, OpenCL), Bram's agreement gate must pass before its speed counts. The recipe
states the agreement it reached; anything below the app's threshold is not evidence of anything.

## Writing it

Start from the closest fixture in `fixtures/recipe/valid/`, fill in your values, then check it:

```bash
python3 recipes/validate.py path/to/your-recipe.json
```

The schemas refuse a field the app does not implement, which is deliberate: the accepted vocabulary
is the whole of what a recipe may say. A recipe cannot carry a prompt, a script, a URL, a permission,
a memory ceiling, or anything that would bypass the app's CPU agreement gate, and no wording in a
recipe changes that.

Two rules that follow from how the app reads recipes:

- **Say what you measured, not what you expect.** Leave a field out when you did not measure it —
  an absent field means "not measured", never zero.
- **Do not extrapolate across models or devices.** Settings that won a sweep on one quantization are
  a seed for another, not evidence. If you did not measure the exact model file, do not name its
  hash.

Then add your recipe to the index with its SHA-256 and open a pull request. CI runs the same checker
over the whole corpus and over the snapshot the app ships.

## What maintainers check before merging

1. The measurement was taken on the hardware the recipe names, on a single-file model, with the
   hashes recorded.
2. The evidence block is complete enough to repeat: method, repetitions, spreads, temperatures.
3. Nothing claims more than it measured — no "fastest", no device it was not measured on, no
   agreement it did not reach.
4. `validate.py` passes, and the index entry's id, version, and hash match the file.

An accepted recipe is not a promise. It is a measurement with a name, a date, and an author attached
to it, and the app still validates it locally on the phone it is applied to.
