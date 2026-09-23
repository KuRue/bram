import json
import threading
import time
import unittest
import urllib.error
import urllib.request

import mock_server
from mock_server import (
    CONTEXT_WINDOW_TOKENS,
    FINAL_TEXT,
    HOST,
    MALFORMED_ARGUMENTS,
    OVERSIZED_TEXT_CHARS,
    TOOL_NAME,
)

CHAT_PATH = "/v1/chat/completions"
RESPONSES_PATH = "/v1/responses"
MODEL_ID = "mock-small"
USER_MESSAGE = "check the device"
TOOL_OUTPUT = json.dumps({"tool": TOOL_NAME, "result": "battery 80%, storage 40% free"})

CHAT_TOOLS = [
    {
        "type": "function",
        "function": {"name": TOOL_NAME, "description": "report device status", "parameters": {}},
    }
]
RESPONSES_TOOLS = [
    {
        "type": "function",
        "name": TOOL_NAME,
        "description": "report device status",
        "parameters": {},
    }
]
READ_FILE_TOOLS = [
    {
        "type": "function",
        "function": {"name": "read_file", "description": "read a file", "parameters": {}},
    }
]


class LoopbackServer:
    def __init__(self, scenario=mock_server.DEFAULT_SCENARIO):
        self.server = mock_server.build_server(0, scenario)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.server.server_address[1]

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def open_request(request):
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


class MockServerTest(unittest.TestCase):
    def setUp(self):
        self.loop = LoopbackServer()
        self.base = f"http://{HOST}:{self.loop.port}"
        self.addCleanup(self.loop.close)

    def get(self, path):
        return open_request(urllib.request.Request(self.base + path))

    def post(self, path, payload):
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        return open_request(request)

    def post_raw(self, path, body):
        request = urllib.request.Request(self.base + path, data=body, method="POST")
        return open_request(request)

    def arm(self, scenario):
        status, body = self.post(f"/__scenario/{scenario}", {})
        self.assertEqual(status, 200)
        self.assertEqual(body["scenario"], scenario)

    def chat(self, messages, tools=CHAT_TOOLS):
        return self.post(
            CHAT_PATH, {"model": MODEL_ID, "messages": messages, "tools": tools}
        )

    def responses(self, input_items, tools=RESPONSES_TOOLS):
        return self.post(
            RESPONSES_PATH, {"model": MODEL_ID, "input": input_items, "tools": tools}
        )

    def test_health(self):
        status, body = self.get("/__health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok"})

    def test_models_catalog(self):
        status, body = self.get("/v1/models")
        self.assertEqual(status, 200)
        models = body["data"]
        self.assertEqual(models[0]["id"], MODEL_ID)
        self.assertEqual(models[0]["context_window"], CONTEXT_WINDOW_TOKENS)

    def test_chat_text_scenario_never_calls_tool(self):
        self.arm("text")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 200)
        message = body["choices"][0]["message"]
        self.assertEqual(message["content"], FINAL_TEXT)
        self.assertNotIn("tool_calls", message)
        self.assertEqual(body["choices"][0]["finish_reason"], "stop")

    def test_chat_tool_call_then_final(self):
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 200)
        message = body["choices"][0]["message"]
        calls = message["tool_calls"]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["function"]["name"], TOOL_NAME)
        self.assertEqual(calls[0]["function"]["arguments"], "{}")
        self.assertTrue(calls[0]["id"])
        self.assertEqual(body["choices"][0]["finish_reason"], "tool_calls")

        status, body = self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": calls},
                {"role": "tool", "tool_call_id": calls[0]["id"], "content": TOOL_OUTPUT},
            ]
        )
        self.assertEqual(status, 200)
        message = body["choices"][0]["message"]
        self.assertEqual(message["content"], FINAL_TEXT)
        self.assertNotIn("tool_calls", message)

    def test_chat_older_tool_output_does_not_suppress_new_call(self):
        status, body = self.chat(
            [
                {"role": "user", "content": "first"},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call_old"}]},
                {"role": "tool", "tool_call_id": "call_old", "content": TOOL_OUTPUT},
                {"role": "user", "content": "again"},
            ]
        )
        self.assertEqual(status, 200)
        self.assertIn("tool_calls", body["choices"][0]["message"])

    def test_chat_tool_result_with_unrelated_text_finishes(self):
        status, body = self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call_1"}]},
                {"role": "tool", "tool_call_id": "call_1", "content": '{"battery": "80%"}'},
            ]
        )
        self.assertEqual(status, 200)
        message = body["choices"][0]["message"]
        self.assertEqual(message["content"], FINAL_TEXT)
        self.assertNotIn("tool_calls", message)

    def test_chat_tool_not_offered_replies_with_text(self):
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}], tools=[])
        self.assertEqual(status, 200)
        self.assertNotIn("tool_calls", body["choices"][0]["message"])

    def test_history_check_rejects_dangling_tool_call(self):
        self.arm("history_check")
        status, body = self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call_lost"}]},
                {"role": "user", "content": "again"},
            ]
        )
        self.assertEqual(status, 400)
        self.assertIn("call_lost", body["error"]["message"])

    def test_history_check_accepts_matched_call_and_result(self):
        self.arm("history_check")
        status, body = self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call_kept"}]},
                {"role": "tool", "tool_call_id": "call_kept", "content": TOOL_OUTPUT},
            ]
        )
        self.assertEqual(status, 200)
        self.assertEqual(body["choices"][0]["message"]["content"], FINAL_TEXT)

    def test_history_check_accepts_a_fresh_conversation(self):
        self.arm("history_check")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 200)
        self.assertIn("tool_calls", body["choices"][0]["message"])

    def test_history_check_responses_rejects_dangling_call(self):
        self.arm("history_check")
        status, body = self.responses(
            [
                {"type": "message", "role": "user", "content": USER_MESSAGE},
                {"type": "function_call", "call_id": "call_lost", "name": TOOL_NAME, "arguments": "{}"},
                {"type": "message", "role": "user", "content": "again"},
            ]
        )
        self.assertEqual(status, 400)
        self.assertIn("call_lost", body["error"]["message"])

    def test_stream_chat_answers_with_sse_frames(self):
        self.arm("stream_chat")
        request = urllib.request.Request(
            self.base + CHAT_PATH,
            data=json.dumps(
                {"model": MODEL_ID, "stream": True, "messages": [{"role": "user", "content": "hi"}]}
            ).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request) as response:
            self.assertEqual("text/event-stream", response.headers["Content-Type"])
            body = response.read().decode("utf-8")

        self.assertIn("reasoning_content", body)
        self.assertIn("The ", body)
        self.assertIn("weather is fine.", body)
        self.assertTrue(body.rstrip().endswith("data: [DONE]"))

        status, log = self.get("/__log")
        self.assertTrue(log["requests"][-1]["items"][0]["stream"])

    def test_chat_parallel_calls_have_distinct_ids(self):
        self.arm("parallel_calls")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 200)
        calls = body["choices"][0]["message"]["tool_calls"]
        self.assertEqual(len(calls), 2)
        self.assertNotEqual(calls[0]["id"], calls[1]["id"])

    def test_read_file_oversized_calls_read_file_with_the_seeded_path(self):
        self.arm("read_file_oversized")
        status, body = self.chat(
            [{"role": "user", "content": "read the oversized file"}], tools=READ_FILE_TOOLS
        )
        self.assertEqual(status, 200)
        call = body["choices"][0]["message"]["tool_calls"][0]
        self.assertEqual(call["function"]["name"], "read_file")
        self.assertEqual(call["function"]["arguments"], '{"path":"oversized.txt"}')

    def test_read_file_oversized_is_not_called_when_not_offered(self):
        self.arm("read_file_oversized")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}], tools=[])
        self.assertEqual(status, 200)
        self.assertNotIn("tool_calls", body["choices"][0]["message"])

    def test_chat_malformed_arguments(self):
        self.arm("malformed_args")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 200)
        calls = body["choices"][0]["message"]["tool_calls"]
        self.assertEqual(calls[0]["function"]["arguments"], MALFORMED_ARGUMENTS)

    def test_chat_oversized_final_text(self):
        self.arm("oversized_result")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        calls = body["choices"][0]["message"]["tool_calls"]
        status, body = self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": calls},
                {"role": "tool", "tool_call_id": calls[0]["id"], "content": TOOL_OUTPUT},
            ]
        )
        self.assertEqual(status, 200)
        content = body["choices"][0]["message"]["content"]
        self.assertEqual(len(content), OVERSIZED_TEXT_CHARS)

    def test_responses_tool_call_then_final(self):
        status, body = self.responses(
            [{"type": "message", "role": "user", "content": USER_MESSAGE}]
        )
        self.assertEqual(status, 200)
        call = body["output"][0]
        self.assertEqual(call["type"], "function_call")
        self.assertEqual(call["name"], TOOL_NAME)
        self.assertEqual(call["arguments"], "{}")
        self.assertTrue(call["call_id"])

        status, body = self.responses(
            [
                {"type": "message", "role": "user", "content": USER_MESSAGE},
                {
                    "type": "function_call",
                    "call_id": call["call_id"],
                    "name": TOOL_NAME,
                    "arguments": "{}",
                },
                {"type": "function_call_output", "call_id": call["call_id"], "output": TOOL_OUTPUT},
            ]
        )
        self.assertEqual(status, 200)
        final = body["output"][0]
        self.assertEqual(final["type"], "message")
        self.assertEqual(final["content"][0]["type"], "output_text")
        self.assertEqual(final["content"][0]["text"], FINAL_TEXT)

    def test_responses_status_failed(self):
        self.arm("status_failed")
        status, body = self.responses(
            [{"type": "message", "role": "user", "content": USER_MESSAGE}]
        )
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "failed")
        self.assertIn("message", body["error"])

    def test_chat_status_failed_is_server_error(self):
        self.arm("status_failed")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 500)

    def test_http_500(self):
        self.arm("http_500")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertEqual(status, 500)
        self.assertIn("scripted failure", body["error"]["message"])
        status, _ = self.get("/v1/models")
        self.assertEqual(status, 200)

    def test_slow_response_delays_reply(self):
        original = mock_server.SLOW_SECONDS
        mock_server.SLOW_SECONDS = 0.25
        self.addCleanup(setattr, mock_server, "SLOW_SECONDS", original)
        self.arm("slow_response")
        started = time.monotonic()
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        elapsed = time.monotonic() - started
        self.assertEqual(status, 200)
        self.assertEqual(body["choices"][0]["message"]["content"], FINAL_TEXT)
        self.assertGreaterEqual(elapsed, 0.2)

    def test_stall_response_stays_silent_then_answers(self):
        original = mock_server.STALL_SECONDS
        mock_server.STALL_SECONDS = 0.5
        self.addCleanup(setattr, mock_server, "STALL_SECONDS", original)
        self.arm("stall_response")
        started = time.monotonic()
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        elapsed = time.monotonic() - started
        self.assertEqual(status, 200)
        self.assertEqual(body["choices"][0]["message"]["content"], FINAL_TEXT)
        self.assertGreaterEqual(elapsed, 0.45)

    def test_invalid_json_body(self):
        status, _ = self.post_raw(CHAT_PATH, b"{not json")
        self.assertEqual(status, 400)

    def test_non_object_root_body(self):
        status, _ = self.post_raw(CHAT_PATH, b"[1, 2]")
        self.assertEqual(status, 400)

    def test_unknown_paths(self):
        status, _ = self.get("/v1/nope")
        self.assertEqual(status, 404)
        status, _ = self.post("/v1/nope", {})
        self.assertEqual(status, 404)

    def test_scenario_endpoint_switches_behavior(self):
        self.arm("text")
        status, body = self.get("/__scenario")
        self.assertEqual(body["scenario"], "text")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertNotIn("tool_calls", body["choices"][0]["message"])
        status, body = self.post("/__scenario/bogus", {})
        self.assertEqual(status, 404)
        status, body = self.post("/__scenario/happy_tool_call", {})
        self.assertEqual(body["previous"], "text")
        status, body = self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.assertIn("tool_calls", body["choices"][0]["message"])

    def test_request_log_records_requests_in_order(self):
        status, body = self.get("/__log")
        self.assertEqual(body["requests"], [])
        self.chat([{"role": "user", "content": USER_MESSAGE}])
        self.chat(
            [
                {"role": "user", "content": USER_MESSAGE},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call_1"}]},
                {"role": "tool", "tool_call_id": "call_1", "content": TOOL_OUTPUT},
            ]
        )
        status, body = self.get("/__log")
        entries = body["requests"]
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0]["path"], CHAT_PATH)
        self.assertEqual(entries[0]["model"], MODEL_ID)
        self.assertEqual(entries[0]["tools"], 1)
        self.assertEqual([item["role"] for item in entries[1]["items"]], ["user", "assistant", "tool"])
        self.assertEqual(entries[1]["items"][1]["toolCallIds"], ["call_1"])
        self.assertEqual(entries[1]["items"][2]["toolCallId"], "call_1")
        self.assertEqual(entries[1]["items"][2]["contentLength"], len(TOOL_OUTPUT))


if __name__ == "__main__":
    unittest.main()
