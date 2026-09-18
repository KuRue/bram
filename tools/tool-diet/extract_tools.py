"""Extracts the built-in ToolDefinition blocks from the Kotlin sources into JSON.

Usage:  py -3 extract_tools.py [repo_root] > tools.json

Only reads: every `ToolDefinition(` call in app/src/main/kotlin is parsed, its top-level
`key = value` pairs split depth-aware, and its name/description/schema evaluated from plain
and raw string literals plus `+` and `.trimIndent()`/`.trim()`. Anything it cannot evaluate is
reported with `"unresolved": [...]` rather than guessed at.
"""

import json
import re
import sys
from pathlib import Path

RAW_STRING = re.compile(r'^"""', re.S)


def find_call_blocks(text):
    blocks = []
    index = 0
    while True:
        start = text.find("ToolDefinition(", index)
        if start < 0:
            return blocks
        open_paren = start + len("ToolDefinition(") - 1
        depth = 0
        in_plain = False
        in_raw = False
        position = open_paren
        while position < len(text):
            char = text[position]
            if in_raw:
                if text.startswith('"""', position):
                    in_raw = False
                    position += 3
                    continue
            elif in_plain:
                if char == "\\":
                    position += 2
                    continue
                if char == '"':
                    in_plain = False
            else:
                if text.startswith('"""', position):
                    in_raw = True
                    position += 3
                    continue
                if char == '"':
                    in_plain = True
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        blocks.append(text[open_paren + 1 : position])
                        index = position
                        break
            position += 1
        else:
            return blocks


def split_top_level(block):
    items = []
    current = []
    depth = 0
    in_plain = False
    in_raw = False
    position = 0
    while position < len(block):
        char = block[position]
        if in_raw:
            if block.startswith('"""', position):
                in_raw = False
                current.append('"""')
                position += 3
                continue
            current.append(char)
        elif in_plain:
            current.append(char)
            if char == "\\":
                if position + 1 < len(block):
                    current.append(block[position + 1])
                position += 1
            elif char == '"':
                in_plain = False
        else:
            if block.startswith('"""', position):
                in_raw = True
                current.append('"""')
                position += 3
                continue
            if char == '"':
                in_plain = True
                current.append(char)
            elif char in "([{":
                depth += 1
                current.append(char)
            elif char in ")]}":
                depth -= 1
                current.append(char)
            elif char == "," and depth == 0:
                items.append("".join(current).strip())
                current = []
            else:
                current.append(char)
        position += 1
    if current:
        items.append("".join(current).strip())
    return items


def evaluate_string(expression):
    """The text of a string expression, or None when it uses anything but literals and +/trim."""
    text = expression.strip()
    if text.endswith(".trimIndent()"):
        text = text[: -len(".trimIndent()")].rstrip()
    elif text.endswith(".trim()"):
        text = text[: -len(".trim()")].rstrip()
    if text.endswith(".trimEnd()"):
        text = text[: -len(".trimEnd()")].rstrip()
    pieces = []
    position = 0
    while position < len(text):
        char = text[position]
        if char.isspace() or char == "+":
            position += 1
            continue
        if text.startswith('"""', position):
            end = text.find('"""', position + 3)
            if end < 0:
                return None
            pieces.append(text[position + 3 : end])
            position = end + 3
            continue
        if char == '"':
            position += 1
            value = []
            while position < len(text):
                current = text[position]
                if current == "\\":
                    nxt = text[position + 1] if position + 1 < len(text) else ""
                    value.append(
                        {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$"}.get(nxt, "\\" + nxt)
                    )
                    position += 2
                    continue
                if current == '"':
                    position += 1
                    break
                value.append(current)
                position += 1
            pieces.append("".join(value))
            continue
        return None
    return "".join(pieces)


def parse_block(block):
    fields = {}
    unresolved = []
    for item in split_top_level(block):
        equals = item.find("=")
        if equals <= 0:
            continue
        key = item[:equals].strip()
        value = item[equals + 1 :].strip()
        if key in ("name", "description", "inputSchemaJson"):
            evaluated = evaluate_string(value)
            if evaluated is None:
                unresolved.append(key)
            else:
                fields[key] = evaluated
        elif key == "readOnly":
            fields["readOnly"] = value == "true"
        elif key == "requiredPermissions":
            fields["requiredPermissions"] = re.findall(r'"([^"]+)"', value)
    if unresolved:
        fields["unresolved"] = unresolved
    return fields


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    tools = []
    for source in sorted((root / "app/src/main/kotlin").rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        for block in find_call_blocks(text):
            fields = parse_block(block)
            if "name" not in fields or "unresolved" in fields:
                if "name" not in fields:
                    continue
            fields["source"] = str(source.relative_to(root))
            tools.append(fields)
    seen = {}
    for tool in tools:
        seen.setdefault(tool["name"], tool)
    payload = json.dumps(list(seen.values()), indent=2, ensure_ascii=False)
    if out is not None:
        out.write_text(payload, encoding="utf-8")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(payload)


if __name__ == "__main__":
    main()
