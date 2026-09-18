"""Measures the similarity thresholds against the on-device embedder.

Usage:
  py -3 calibrate.py [--server http://127.0.0.1:8081/v1] [--pairs pairs.json] [--repo ../..]

The app compares, with bge-small-en-v1.5 embeddings:
  - skill-vs-tool coverage: cosine(skill description, max(named tool text, bare tool text))
    against DEFAULT_COVERAGE_THRESHOLD, to suppress a skill an offered tool already covers;
  - draft nudge: cosine(query, draft description) against DEFAULT_DRAFT_HINT_THRESHOLD;
  - tool-selection floor: cosine(query, "name: description") against DEFAULT_MIN_SIMILARITY.

This script reproduces those exact forms, embeds the labeled pairs from pairs.json (plus the
tool-diet asks), and prints each group's distribution and a threshold suggestion. It changes
nothing: the numbers go into the code by hand, with the measurement named in the comment.
"""

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "tool-diet"))
from extract_tools import find_call_blocks, parse_block  # noqa: E402


def load_tools(repo):
    tools = {}
    for source in sorted((repo / "app/src/main/kotlin").rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        for block in find_call_blocks(text):
            fields = parse_block(block)
            name = fields.get("name")
            if name and "description" in fields and name not in tools:
                tools[name] = fields["description"]
    return tools


def embed(server, model, texts):
    body = json.dumps({"model": model, "input": texts}).encode()
    request = urllib.request.Request(
        server.rstrip("/") + "/embeddings",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.loads(response.read().decode())
    return [item["embedding"] for item in payload["data"]]


class Embedder:
    def __init__(self, server, model):
        self.server = server
        self.model = model
        self.cache = {}

    def vector(self, text):
        if text not in self.cache:
            self.cache[text] = embed(self.server, self.model, [text])[0]
        return self.cache[text]

    def cosine(self, a, b):
        va, vb = self.vector(a), self.vector(b)
        dot = sum(x * y for x, y in zip(va, vb))
        na = sum(x * x for x in va) ** 0.5
        nb = sum(y * y for y in vb) ** 0.5
        return dot / (na * nb) if na and nb else 0.0


def describe(label, values, expect_high):
    if not values:
        return
    ordered = sorted(values)
    low, high = ordered[0], ordered[-1]
    median = ordered[len(ordered) // 2]
    print(f"  {label:<22} n={len(values):>3}  min={low:.3f}  median={median:.3f}  max={high:.3f}")
    if expect_high:
        print(f"  {'':<22} lowest expected {high:.3f} (threshold must sit below this)")
    else:
        print(f"  {'':<22} highest unexpected {high:.3f} (threshold must sit above this)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://127.0.0.1:8081/v1")
    parser.add_argument("--model", default="bge-small")
    parser.add_argument("--pairs", default=str(Path(__file__).parent / "pairs.json"))
    parser.add_argument("--repo", default=str(Path(__file__).parent.parent.parent))
    parser.add_argument(
        "--asks",
        default=str(Path(__file__).parent.parent / "tool-diet" / "asks.json"),
    )
    arguments = parser.parse_args()

    repo = Path(arguments.repo)
    tools = load_tools(repo)
    pairs = json.loads(Path(arguments.pairs).read_text(encoding="utf-8"))
    embedder = Embedder(arguments.server, arguments.model)

    print("== skill-vs-tool coverage (current threshold 0.70)")
    suppress = []
    keep = []
    for pair in pairs["coverage"]:
        skill = pair["skill"]
        if pair.get("tool"):
            tool = pair["tool"]
            score = max(
                embedder.cosine(skill, f"{tool}: {tools[tool]}"),
                embedder.cosine(skill, tools[tool]),
            )
        else:
            score = max(
                max(
                    embedder.cosine(skill, f"{tool}: {description}"),
                    embedder.cosine(skill, description),
                )
                for tool, description in tools.items()
            )
        (suppress if pair["expect"] == "suppress" else keep).append(score)
        print(f"  {score:.3f}  {pair['expect']:<8} {skill[:70]}")
    describe("should suppress", suppress, expect_high=True)
    describe("should keep", keep, expect_high=False)
    if suppress and keep:
        low, high = min(suppress), max(keep)
        print(f"  suggestion: {(low + high) / 2:.2f} (gap {high:.3f}..{low:.3f})")

    print("== draft nudge (current threshold 0.60)")
    nudge = []
    quiet = []
    for pair in pairs["draft"]:
        score = embedder.cosine(pair["query"], pair["draft"])
        (nudge if pair["expect"] == "nudge" else quiet).append(score)
        print(f"  {score:.3f}  {pair['expect']:<8} {pair['query'][:45]} / {pair['draft'][:40]}")
    describe("should nudge", nudge, expect_high=True)
    describe("should stay quiet", quiet, expect_high=False)
    if nudge and quiet:
        low, high = min(nudge), max(quiet)
        print(f"  suggestion: {(low + high) / 2:.2f} (gap {high:.3f}..{low:.3f})")

    print("== tool-selection floor (current threshold 0.35)")
    asks = json.loads(Path(arguments.asks).read_text(encoding="utf-8"))
    relevant = []
    irrelevant = []
    for ask in asks:
        for name, description in tools.items():
            score = embedder.cosine(ask["ask"], f"{name}: {description}")
            (relevant if name == ask["expect"] else irrelevant).append(score)
    describe("expected tool", relevant, expect_high=True)
    describe("other tools", irrelevant, expect_high=False)
    if relevant and irrelevant:
        low, high = min(relevant), max(irrelevant)
        print(f"  suggestion: {(low + high) / 2:.2f} (gap {high:.3f}..{low:.3f})")


if __name__ == "__main__":
    main()
