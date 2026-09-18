"""Scores tool descriptions against a real model, for the description diet.

Usage:
  py -3 eval_tools.py --asks asks.json --tools tools-current.json [--tools-alt dieted.json ...]
                      [--server http://127.0.0.1:8080/v1] [--model qwen3-1.7b] [--runs 3]

Each ask names the tool a user's request is about and (optionally) argument values the call must
carry. Every description set is offered in full and the model chooses with tool_choice=auto; a
tool-choice hit means it called the expected tool, an argument hit means the expected values are
present in the arguments. The report also prints each set's total description characters.
"""

import argparse
import json
import urllib.request


def chat(server, model, messages, tools, temperature, seed):
    body = json.dumps(
        {
            "model": model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": temperature,
            "seed": seed,
            "max_tokens": 400,
            "stream": False,
        }
    ).encode()
    request = urllib.request.Request(
        server.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(response.read().decode())


def offer(tool):
    schema = {}
    try:
        schema = json.loads(tool["inputSchemaJson"])
    except (KeyError, json.JSONDecodeError):
        pass
    return {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool["description"],
            "parameters": schema,
        },
    }


def arguments_of(message):
    calls = message.get("tool_calls") or []
    if not calls:
        return None, None
    function = calls[0].get("function") or {}
    raw = function.get("arguments") or "{}"
    try:
        return function.get("name"), json.loads(raw)
    except json.JSONDecodeError:
        return function.get("name"), None


def evaluate(label, tools, asks, server, model, temperature, runs):
    offers = [offer(tool) for tool in tools]
    total_chars = sum(len(tool["description"]) for tool in tools)
    hits = 0
    arg_hits = 0
    arg_total = 0
    misses = []
    for ask in asks:
        expected = ask["expect"]
        expect_arguments = ask.get("arguments", {})
        for run in range(runs):
            reply = chat(
                server,
                model,
                [{"role": "user", "content": ask["ask"]}],
                offers,
                temperature,
                seed=run,
            )
            choice = reply["choices"][0]
            name, arguments = arguments_of(choice["message"])
            if name == expected:
                hits += 1
                if expect_arguments:
                    arg_total += 1
                    if arguments and all(
                        str(value).lower() in json.dumps(arguments).lower()
                        for value in expect_arguments.values()
                    ):
                        arg_hits += 1
                    else:
                        misses.append(f"{ask['ask'][:40]}… args={arguments}")
            else:
                misses.append(f"{ask['ask'][:40]}… called {name!r} want {expected!r}")
    attempts = len(asks) * runs
    return {
        "label": label,
        "characters": total_chars,
        "tool_hits": f"{hits}/{attempts}",
        "tool_accuracy": round(hits / attempts, 3),
        "argument_hits": f"{arg_hits}/{arg_total}" if arg_total else "-",
        "argument_accuracy": round(arg_hits / arg_total, 3) if arg_total else None,
        "misses": misses,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asks", required=True)
    parser.add_argument("--tools", required=True, help="baseline tools JSON")
    parser.add_argument("--tools-alt", action="append", default=[], help="alternative tools JSON")
    parser.add_argument(
        "--diet",
        action="append",
        default=[],
        help="JSON map of tool name -> replacement description; offered alongside the baseline",
    )
    parser.add_argument("--server", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--model", default="qwen3-1.7b")
    parser.add_argument("--temperature", type=float, default=0.2)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--out", default=None)
    arguments = parser.parse_args()

    with open(arguments.asks, encoding="utf-8") as handle:
        asks = json.load(handle)
    with open(arguments.tools, encoding="utf-8") as handle:
        baseline = json.load(handle)

    alternatives = list(arguments.tools_alt)
    for path in arguments.diet:
        with open(path, encoding="utf-8") as handle:
            replacements = json.load(handle)
        missing = sorted(set(replacements) - {tool["name"] for tool in baseline})
        if missing:
            raise SystemExit(f"{path}: names not in the baseline tools: {missing}")
        alternatives.append(_dieted(baseline, replacements))

    report = [evaluate("current", baseline, asks, arguments.server, arguments.model, arguments.temperature, arguments.runs)]
    for path in arguments.tools_alt:
        with open(path, encoding="utf-8") as handle:
            alternative = json.load(handle)
        report.append(
            evaluate(
                path,
                alternative,
                asks,
                arguments.server,
                arguments.model,
                arguments.temperature,
                arguments.runs,
            )
        )
    for path, alternative in zip(arguments.diet, alternatives[-len(arguments.diet):]):
        report.append(
            evaluate(
                path,
                alternative,
                asks,
                arguments.server,
                arguments.model,
                arguments.temperature,
                arguments.runs,
            )
        )

    for entry in report:
        print(
            f"{entry['label']:>40}  chars={entry['characters']:>5}  "
            f"tool={entry['tool_hits']} ({entry['tool_accuracy']})  args={entry['argument_hits']}"
        )
        for miss in entry["misses"][:6]:
            print(f"    miss: {miss}")
    if arguments.out:
        with open(arguments.out, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)


def _dieted(baseline, replacements):
    """The baseline tool list with the mapped descriptions swapped in; nothing else changes."""
    return [
        {**tool, "description": replacements.get(tool["name"], tool["description"])}
        for tool in baseline
    ]


if __name__ == "__main__":
    main()
