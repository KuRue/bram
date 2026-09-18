"""Applies the diet map to the Kotlin ToolDefinition blocks.

Usage:  py -3 apply_diet.py dieted-descriptions.json [repo_root]

For every `ToolDefinition(` block whose `name` is in the map, the `description = <expr>` value is
replaced with the mapped text, wrapped into `"..." +` chunks the way the surrounding code wraps.
Names, schemas, and every other field are untouched; the file is only rewritten when the block's
name is in the map.
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from extract_tools import find_call_blocks, parse_block, split_top_level  # noqa: E402

WRAP = 96


def escape(text):
    return text.replace("\\", "\\\\").replace('"', '\\"')


def wrap(text, indent):
    """`"a b " +` chunks, each line at most WRAP characters including its indent."""
    lines = []
    current = ""
    for word in text.split(" "):
        if current and len(indent) + len(current) + 1 + len(word) + 3 > WRAP:
            lines.append(current + " ")
            current = word
        else:
            current = f"{current} {word}".strip()
    if current:
        lines.append(current)
    if len(lines) == 1:
        return f'"{escape(lines[0])}"'
    rendered = [f'"{escape(line)}"' for line in lines]
    return (" +\n" + indent + "    ").join(rendered)


def replace_description(block, new_text):
    """The block with its description expression swapped; raises when the shape is unexpected."""
    for item in split_top_level(block):
        equals = item.find("=")
        if equals <= 0 or item[:equals].strip() != "description":
            continue
        start = block.find(item)
        end = start + len(item)
        indent_match = re.search(r"\n([ \t]*)description =", block)
        indent = indent_match.group(1) if indent_match else "        "
        return block[:start] + "description = " + wrap(new_text, indent) + block[end:]
    raise ValueError("no description field found")


def main():
    diet_path = Path(sys.argv[1])
    root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(".")
    diet = json.loads(diet_path.read_text(encoding="utf-8"))
    changed = []
    for source in sorted((root / "app/src/main/kotlin").rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        updated = text
        for block in find_call_blocks(text):
            fields = parse_block(block)
            name = fields.get("name")
            if name not in diet:
                continue
            updated = updated.replace(block, replace_description(block, diet[name]), 1)
            changed.append(f"{name} ({source.name})")
        if updated != text:
            source.write_text(updated, encoding="utf-8")
    print("updated: " + ", ".join(changed))


if __name__ == "__main__":
    main()
