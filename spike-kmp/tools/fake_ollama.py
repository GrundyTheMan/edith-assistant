"""A tiny stand-in for Ollama's /api/chat, for the cloud build: it streams newline-separated JSON in HTTP chunks, the way Ollama does,
so the iPhone simulator can test the app's streaming reader without a real model. Not used anywhere else."""
import http.server
import json
import sys
import time

# A thinking model sends its reasoning first, in "thinking" with an empty "content", then the answer in "content".
THINKING = ["The user", " wants hello."]
WORDS = ["Hello", " from", " the", " fake", " Ollama."]


def line(content, done, thinking=""):
    message = {"role": "assistant", "content": content}
    if thinking:
        message["thinking"] = thinking
    return (json.dumps({"model": "fake", "message": message, "done": done}) + "\n").encode()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for word, think in [("", t) for t in THINKING] + [(w, "") for w in WORDS] + [("", "")]:
            data = line(word, word == "" and think == "", think)
            self.wfile.write(b"%x\r\n" % len(data) + data + b"\r\n")
            self.wfile.flush()
            time.sleep(0.3)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-ollama: " + fmt % args + "\n")


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("127.0.0.1", 18080), Handler).serve_forever()
