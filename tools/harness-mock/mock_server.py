import argparse
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
DEFAULT_PORT = 8099
MAX_BODY_BYTES = 1024 * 1024
CONTEXT_WINDOW_TOKENS = 8192
TOOL_NAME = "device_status"
TOOL_ARGUMENTS = "{}"
READ_FILE_TOOL = "read_file"
READ_FILE_ARGUMENTS = '{"path":"oversized.txt"}'
MALFORMED_ARGUMENTS = "{not json"
FINAL_TEXT = "Mock endpoint: tool result received."
TRUNCATED_FINAL_TEXT = "Mock endpoint: answered after the cut-off."
TRUNCATED_PARTIAL_TEXT = "The answer so far is forty-two, and the reasoning was long."
# The continuation nudge the app appends after a cut-off round with no visible answer. Its wording
# is pinned by TruncationContinuationTest; the mock keys off "length limit" to tell the second
# request from the first.
TRUNCATION_NUDGE_MARKER = "length limit"
OVERSIZED_TEXT_CHARS = 200_000
SLOW_SECONDS = 10.0
# Long enough that a turn sent into this scenario is still silent well past the checks a device
# test makes, and past the point where a premature stall verdict would show: the mock must not
# answer before the test has had a chance to stop the turn.
STALL_SECONDS = 120.0
SCENARIOS = (
    "text",
    "stream_chat",
    "happy_tool_call",
    "history_check",
    "read_file_oversized",
    "parallel_calls",
    "malformed_args",
    "http_500",
    "slow_response",
    "stall_response",
    "oversized_result",
    "status_failed",
    "truncated_then_answer",
    "truncated_partial",
)
DEFAULT_SCENARIO = "happy_tool_call"
TOOL_SCENARIOS = ("happy_tool_call", "history_check", "read_file_oversized", "parallel_calls", "malformed_args", "oversized_result")
SCENARIO_TOOLS = {
    "read_file_oversized": (READ_FILE_TOOL, READ_FILE_ARGUMENTS),
}


def tool_for(scenario):
    return SCENARIO_TOOLS.get(scenario, (TOOL_NAME, TOOL_ARGUMENTS))
MODELS = [
    {
        "id": "mock-small",
        "object": "model",
        "created": 0,
        "owned_by": "bram-harness",
        "context_window": CONTEXT_WINDOW_TOKENS,
        "max_model_len": CONTEXT_WINDOW_TOKENS,
    }
]
INVALID_REQUEST_ERROR = {
    "error": {"message": "invalid request body", "type": "invalid_request_error"}
}
NOT_FOUND_ERROR = {"error": {"message": "not found", "type": "invalid_request_error"}}
SCRIPTED_SERVER_ERROR = {
    "error": {"message": "scripted failure", "type": "server_error"}
}
# Big enough that a full harness suite (~4 requests per test) never rolls entries out from under a
# test that baselines the log before it starts.
MAX_LOG_ENTRIES = 500


def parse_body(raw):
    root = json.loads(raw.decode("utf-8"))
    if not isinstance(root, dict):
        raise ValueError("root must be a JSON object")
    return root


def tool_names(payload):
    offered = []
    for tool in payload.get("tools") or []:
        if not isinstance(tool, dict):
            continue
        name = tool.get("name")
        function = tool.get("function")
        if not name and isinstance(function, dict):
            name = function.get("name")
        if name:
            offered.append(name)
    return offered


def matching_output_after_latest_user(items, is_user, is_output):
    latest_user = None
    for index, item in enumerate(items):
        if is_user(item):
            latest_user = index
    if latest_user is None:
        return False
    for item in items[latest_user + 1 :]:
        if is_output(item):
            return True
    return False


def chat_output_after_latest_user(messages):
    return matching_output_after_latest_user(
        messages,
        lambda message: message.get("role") == "user",
        lambda message: message.get("role") == "tool",
    )


def responses_output_after_latest_user(items):
    return matching_output_after_latest_user(
        items,
        lambda item: item.get("type") == "message" and item.get("role") == "user",
        lambda item: item.get("type") == "function_call_output",
    )


def should_call_tool(offered, tool_name, already_answered):
    if tool_name not in offered:
        return False
    if already_answered:
        return False
    return True


def stream_chat_frames():
    def chunk(delta):
        return "data: " + json.dumps(
            {"choices": [{"index": 0, "delta": delta}]}
        )

    return [
        chunk({"role": "assistant", "reasoning_content": "Checking the device"}),
        chunk({"content": "The "}),
        chunk({"content": "weather is fine."}),
        "data: [DONE]",
    ]


def new_call_id():
    return "call_" + uuid.uuid4().hex


def tool_arguments(scenario):
    if scenario == "malformed_args":
        return MALFORMED_ARGUMENTS
    return tool_for(scenario)[1]


def call_count(scenario):
    return 2 if scenario == "parallel_calls" else 1


def final_text(scenario):
    return "x" * OVERSIZED_TEXT_CHARS if scenario == "oversized_result" else FINAL_TEXT


def usage_chat():
    return {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}


def usage_responses():
    return {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}


def unfinished_chat_call(messages):
    expected = []
    answered = set()
    for message in messages:
        if not isinstance(message, dict):
            continue
        if message.get("role") == "assistant":
            for call in message.get("tool_calls") or []:
                if isinstance(call, dict) and call.get("id"):
                    expected.append(call["id"])
        if message.get("role") == "tool":
            answered.add(message.get("tool_call_id"))
    return next((call_id for call_id in expected if call_id not in answered), None)


def unfinished_responses_call(items):
    expected = []
    answered = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        if item.get("type") == "function_call" and item.get("call_id"):
            expected.append(item["call_id"])
        if item.get("type") == "function_call_output":
            answered.add(item.get("call_id"))
    return next((call_id for call_id in expected if call_id not in answered), None)


def history_error(path, payload):
    if path == "/v1/chat/completions":
        messages = payload.get("messages")
        if isinstance(messages, list):
            missing = unfinished_chat_call(messages)
            if missing:
                return f"history incomplete: assistant tool call {missing} has no matching tool result"
    else:
        items = payload.get("input")
        if isinstance(items, list):
            missing = unfinished_responses_call(items)
            if missing:
                return f"history incomplete: function call {missing} has no matching function_call_output"
    return None


def chat_reply(payload, scenario=DEFAULT_SCENARIO):
    messages = payload.get("messages")
    if not isinstance(messages, list):
        return None, INVALID_REQUEST_ERROR
    model = payload.get("model") or MODELS[0]["id"]
    # A reasoning-only reply the runtime cut off at the length limit: nothing visible, finish
    # reason "length". The app answers a round like that with a nudge to finish, so the second
    # request - the one carrying the nudge - gets the real answer.
    if scenario == "truncated_then_answer":
        nudged = any(
            isinstance(message, dict)
            and message.get("role") == "system"
            and TRUNCATION_NUDGE_MARKER in (message.get("content") or "")
            for message in messages
        )
        if nudged:
            choice = {
                "index": 0,
                "message": {"role": "assistant", "content": TRUNCATED_FINAL_TEXT},
                "finish_reason": "stop",
            }
        else:
            choice = {
                "index": 0,
                "message": {"role": "assistant", "content": ""},
                "finish_reason": "length",
            }
        return (
            {
                "id": "chatcmpl-" + uuid.uuid4().hex,
                "object": "chat.completion",
                "created": 0,
                "model": model,
                "choices": [choice],
                "usage": usage_chat(),
            },
            None,
        )
    # A reply that got cut off after its visible text started: it exists, so it settles with the
    # truncation notice rather than being run again.
    if scenario == "truncated_partial":
        choice = {
            "index": 0,
            "message": {"role": "assistant", "content": TRUNCATED_PARTIAL_TEXT},
            "finish_reason": "length",
        }
        return (
            {
                "id": "chatcmpl-" + uuid.uuid4().hex,
                "object": "chat.completion",
                "created": 0,
                "model": model,
                "choices": [choice],
                "usage": usage_chat(),
            },
            None,
        )
    calls = None
    if scenario in TOOL_SCENARIOS and should_call_tool(
        tool_names(payload), tool_for(scenario)[0], chat_output_after_latest_user(messages)
    ):
        calls = [
            {
                "id": new_call_id(),
                "type": "function",
                "function": {"name": tool_for(scenario)[0], "arguments": tool_arguments(scenario)},
            }
            for _ in range(call_count(scenario))
        ]
    if calls is not None:
        choice = {
            "index": 0,
            "message": {"role": "assistant", "content": None, "tool_calls": calls},
            "finish_reason": "tool_calls",
        }
    else:
        choice = {
            "index": 0,
            "message": {"role": "assistant", "content": final_text(scenario)},
            "finish_reason": "stop",
        }
    return (
        {
            "id": "chatcmpl-" + uuid.uuid4().hex,
            "object": "chat.completion",
            "created": 0,
            "model": model,
            "choices": [choice],
            "usage": usage_chat(),
        },
        None,
    )


def responses_reply(payload, scenario=DEFAULT_SCENARIO):
    input_items = payload.get("input")
    if isinstance(input_items, str):
        input_items = [{"type": "message", "role": "user", "content": input_items}]
    if not isinstance(input_items, list):
        return None, INVALID_REQUEST_ERROR
    model = payload.get("model") or MODELS[0]["id"]
    if scenario == "status_failed":
        return (
            {
                "id": "resp_" + uuid.uuid4().hex,
                "object": "response",
                "created": 0,
                "status": "failed",
                "model": model,
                "error": {"code": "server_error", "message": "scripted failure"},
                "output": [],
                "usage": usage_responses(),
            },
            None,
        )
    calls = None
    if scenario in TOOL_SCENARIOS and should_call_tool(
        tool_names(payload), tool_for(scenario)[0], responses_output_after_latest_user(input_items)
    ):
        calls = [
            {
                "type": "function_call",
                "call_id": new_call_id(),
                "name": tool_for(scenario)[0],
                "arguments": tool_arguments(scenario),
            }
            for _ in range(call_count(scenario))
        ]
    if calls is not None:
        output = calls
    else:
        output = [
            {
                "type": "message",
                "role": "assistant",
                "content": [{"type": "output_text", "text": final_text(scenario)}],
            }
        ]
    return (
        {
            "id": "resp_" + uuid.uuid4().hex,
            "object": "response",
            "created": 0,
            "status": "completed",
            "model": model,
            "output": output,
            "usage": usage_responses(),
        },
        None,
    )


def respond(path, payload, scenario):
    if scenario == "http_500":
        return 500, SCRIPTED_SERVER_ERROR
    if scenario == "slow_response":
        time.sleep(SLOW_SECONDS)
    if scenario == "stall_response":
        time.sleep(STALL_SECONDS)
    if scenario == "history_check":
        missing = history_error(path, payload)
        if missing:
            return 400, {"error": {"message": missing, "type": "invalid_request_error"}}
    if path == "/v1/chat/completions":
        if scenario == "status_failed":
            return 500, SCRIPTED_SERVER_ERROR
        reply, error = chat_reply(payload, scenario)
    else:
        reply, error = responses_reply(payload, scenario)
    if error is not None:
        return 400, error
    return 200, reply


def describe_request(path, payload):
    if path == "/v1/chat/completions":
        roles = [
            {
                "role": message.get("role"),
                "contentLength": len(message["content"]) if isinstance(message.get("content"), str) else None,
                "toolCalls": len(message.get("tool_calls") or []),
                "toolCallIds": [
                    call.get("id")
                    for call in message.get("tool_calls") or []
                    if isinstance(call, dict)
                ],
                "toolCallId": message.get("tool_call_id"),
                "stream": payload.get("stream") is True,
            }
            for message in payload.get("messages") or []
            if isinstance(message, dict)
        ]
    else:
        roles = [
            {
                "type": item.get("type"),
                "role": item.get("role"),
                "callId": item.get("call_id"),
            }
            for item in payload.get("input") or []
            if isinstance(item, dict)
        ]
    return {
        "path": path,
        "model": payload.get("model"),
        "tools": len(payload.get("tools") or []),
        "items": roles,
    }


def record_request(server, entry):
    with server.request_log_lock:
        server.request_log.append(entry)
        del server.request_log[:-MAX_LOG_ENTRIES]


class MockRequestHandler(BaseHTTPRequestHandler):
    server_version = "BramMock/0.1"
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/__health":
            self._send_json(200, {"status": "ok"})
        elif path == "/__log":
            with self.server.request_log_lock:
                self._send_json(200, {"requests": list(self.server.request_log)})
        elif path == "/__scenario":
            self._send_json(200, {"scenario": self._scenario()})
        elif path == "/v1/models":
            self._send_json(200, {"object": "list", "data": MODELS})
        else:
            self._send_json(404, NOT_FOUND_ERROR)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/__scenario/"):
            name = path[len("/__scenario/") :]
            if name not in SCENARIOS:
                self._send_json(404, NOT_FOUND_ERROR)
                return
            previous = self._scenario()
            self.server.scenario = name
            self._send_json(200, {"scenario": name, "previous": previous})
            return
        if path not in ("/v1/chat/completions", "/v1/responses"):
            self._send_json(404, NOT_FOUND_ERROR)
            return
        length_header = self.headers.get("Content-Length")
        try:
            length = int(length_header or "0")
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY_BYTES:
            self._send_json(
                413,
                {"error": {"message": "request body too large", "type": "invalid_request_error"}},
            )
            return
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            payload = parse_body(raw)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            self._send_json(400, INVALID_REQUEST_ERROR)
            return
        record_request(
            self.server,
            {
                "scenario": self._scenario(),
                **describe_request(path, payload),
            },
        )
        if path == "/v1/chat/completions" and self._scenario() == "stream_chat":
            self._send_sse(stream_chat_frames())
            return
        status, body = respond(path, payload, self._scenario())
        self._send_json(status, body)

    def _scenario(self):
        return getattr(self.server, "scenario", DEFAULT_SCENARIO)

    def _send_sse(self, frames):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for frame in frames:
            self.wfile.write((frame + "\n\n").encode("utf-8"))
            self.wfile.flush()
        self.close_connection = True

    def _send_json(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def build_server(port, scenario=DEFAULT_SCENARIO):
    server = ThreadingHTTPServer((HOST, port), MockRequestHandler)
    server.scenario = scenario
    server.request_log = []
    server.request_log_lock = threading.Lock()
    return server


def main():
    parser = argparse.ArgumentParser(description="Bram deterministic OpenAI-compatible mock server")
    parser.add_argument(
        "--scenario",
        choices=SCENARIOS,
        default=DEFAULT_SCENARIO,
        help="scripted behavior for completion endpoints",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port on 127.0.0.1")
    args = parser.parse_args()
    server = build_server(args.port, args.scenario)
    print(f"Bram mock server ({args.scenario}) on http://{HOST}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
