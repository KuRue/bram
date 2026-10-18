#!/usr/bin/env python3
"""Check the recipe schemas against the fixture corpus, and the bundled snapshot against both.

Run from the repository root:

    python3 recipes/validate.py

Requires ``jsonschema`` (``pip install jsonschema``; the CI job installs it).

The corpus is the contract between two implementations of one format: this script checks what the
JSON Schemas accept and refuse, and ``RecipeFixtureCorpusTest`` in ``core:domain`` checks the same
files through Bram's own parser. Both read the same ``expect.txt`` per invalid fixture, so a rule
that drifts between the schema and the app fails CI rather than surprising a contributor.

Schemas validate documents; they cannot check that an index's hashes match the files beside it, or
that a recipe's id agrees with the index entry that lists it. Both are checked here for the bundled
snapshot, because that snapshot is shipped inside the app.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

import jsonschema

RECIPES = Path(__file__).resolve().parent
REPO = RECIPES.parent

SCHEMAS = {
    "recipe": json.loads((RECIPES / "schema" / "hardware-recipe.schema.json").read_text()),
    "index": json.loads((RECIPES / "schema" / "source-index.schema.json").read_text()),
}
VALIDATORS = {kind: jsonschema.Draft202012Validator(schema) for kind, schema in SCHEMAS.items()}

# Bram's parser names a section without the document prefix ("proposal.threads"), but prefixes
# top-level fields ("recipe.version") and the index's recipe list ("index.recipes[0]").
SECTIONS_WITHOUT_PREFIX = {
    "recipe": {"match", "runtime", "model", "workload", "proposal", "evidence", "limits"},
    "index": {"source"},
}

BUNDLED = REPO / "app" / "src" / "main" / "assets" / "hardware-recipes" / "official-v1"

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def render(parts) -> str:
    out = ""
    for part in parts:
        if isinstance(part, int):
            out += f"[{part}]"
        else:
            out += ("." if out else "") + str(part)
    return out


def location(kind: str, error: jsonschema.ValidationError) -> str:
    """The field path in the same words Bram's parser uses, so one expectation serves both."""
    parts = list(error.absolute_path)
    if error.validator == "required":
        missing = re.search(r"'([^']+)' is a required property", error.message)
        if missing:
            parts.append(missing.group(1))
    elif error.validator == "additionalProperties":
        names = re.findall(r"'([^']+)'", error.message)
        if names:
            parts.append(names[0])
    rendered = render(parts)
    if parts and parts[0] in SECTIONS_WITHOUT_PREFIX[kind]:
        return rendered
    return f"{kind}.{rendered}" if rendered else kind


def load(path: Path):
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as error:
        fail(f"{path.relative_to(REPO)}: not JSON: {error}")
        return None


def check_valid(kind: str) -> None:
    for path in sorted((RECIPES / "fixtures" / kind / "valid").glob("*.json")):
        document = load(path)
        if document is None:
            continue
        errors = sorted(VALIDATORS[kind].iter_errors(document), key=lambda e: list(e.absolute_path))
        for error in errors:
            fail(f"{path.relative_to(REPO)}: schema refused a valid fixture at {location(kind, error)}: {error.message}")


def check_invalid(kind: str) -> None:
    directory = RECIPES / "fixtures" / kind / "invalid"
    for case in sorted(path for path in directory.iterdir() if path.is_dir()):
        expectation = (case / "expect.txt").read_text().strip()
        documents = sorted(case.glob("*.json"))
        if len(documents) != 1:
            fail(f"{case.relative_to(REPO)}: expected exactly one document, found {len(documents)}")
            continue
        document = load(documents[0])
        if document is None:
            continue
        errors = sorted(VALIDATORS[kind].iter_errors(document), key=lambda e: list(e.absolute_path))
        if not errors:
            fail(f"{case.relative_to(REPO)}: the schema accepted a fixture that must be refused ({expectation})")
            continue
        if not any(expectation in location(kind, error) for error in errors):
            found = ", ".join(sorted(location(kind, error) for error in errors))
            fail(f"{case.relative_to(REPO)}: expected a problem at {expectation}, the schema named {found}")


def check_bundled_snapshot() -> None:
    index_path = BUNDLED / "index.json"
    if not index_path.is_file():
        fail(f"{index_path.relative_to(REPO)}: missing")
        return
    index = load(index_path)
    if index is None:
        return
    for error in VALIDATORS["index"].iter_errors(index):
        fail(f"bundled index at {location('index', error)}: {error.message}")
    for digest in index.get("recipes", []):
        recipe_path = BUNDLED / digest["file"]
        if not recipe_path.is_file():
            fail(f"bundled index lists {digest['file']}, which is not beside it")
            continue
        data = recipe_path.read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        if actual != digest["sha256"]:
            fail(f"{digest['file']}: index hash {digest['sha256']} does not match the file's {actual}")
        recipe = json.loads(data)
        for error in VALIDATORS["recipe"].iter_errors(recipe):
            fail(f"bundled {digest['file']} at {location('recipe', error)}: {error.message}")
        if recipe.get("id") != digest["id"] or recipe.get("version") != digest["version"]:
            fail(f"{digest['file']}: index says {digest['id']} v{digest['version']}, file says "
                 f"{recipe.get('id')} v{recipe.get('version')}")


def check_paths(paths: list[str]) -> None:
    """Validate single documents, for a contributor checking a recipe before opening a pull request."""
    for name in paths:
        path = Path(name)
        document = load(path)
        if document is None:
            continue
        kind = "index" if document.get("schema") == "bram.hardware-recipes.index" else "recipe"
        if document.get("schema") not in {SCHEMAS["index"]["properties"]["schema"]["const"],
                                          SCHEMAS["recipe"]["properties"]["schema"]["const"]}:
            fail(f"{name}: schema is \"{document.get('schema')}\", expected one of "
                 f"{SCHEMAS['recipe']['properties']['schema']['const']} or "
                 f"{SCHEMAS['index']['properties']['schema']['const']}")
            continue
        errors = sorted(VALIDATORS[kind].iter_errors(document), key=lambda e: list(e.absolute_path))
        for error in errors:
            fail(f"{name} at {location(kind, error)}: {error.message}")
        if not errors:
            print(f"{name}: valid {kind}")


def report() -> int:
    if not failures:
        return 0
    print(f"{len(failures)} problem(s):")
    for message in failures:
        print(f"  - {message}")
    return 1


def main(argv: list[str]) -> int:
    if argv:
        check_paths(argv)
        return report()
    check_valid("recipe")
    check_invalid("recipe")
    check_valid("index")
    check_invalid("index")
    check_bundled_snapshot()
    if report() != 0:
        return 1
    valid = len(list((RECIPES / "fixtures" / "recipe" / "valid").glob("*.json"))) \
        + len(list((RECIPES / "fixtures" / "index" / "valid").glob("*.json")))
    invalid = len(list((RECIPES / "fixtures" / "recipe" / "invalid").iterdir())) \
        + len(list((RECIPES / "fixtures" / "index" / "invalid").iterdir()))
    print(f"schemas, {valid} valid fixtures, {invalid} invalid fixtures, and the bundled snapshot all agree")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
