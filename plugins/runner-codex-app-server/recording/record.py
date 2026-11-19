#!/usr/bin/env python3
"""Records one real codex app server session for the m3-03 fixtures.

Starts `codex app-server` in a throwaway workspace and drives it as Loop's runner does: `initialize`, the
`initialized` notification, `thread/start`, then `turn/start` with the scenario's prompt, each request sent once the
answer before it arrived. Server requests (approvals, user input) get a fixed answer. Every line the server writes goes
to `<scenario>.server.jsonl`, every line the recorder writes to `<scenario>.client.jsonl`, stderr to
`<scenario>.stderr.log`, and the settings to `<scenario>.meta.json`. The raw files hold real paths and ids: scrub them
with scrub.py before anything goes into the repository.

Standard library only. See README.md in this folder.
"""

import argparse
import json
import queue
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

SAFE_PROMPT_TAIL = " Do not run any command, do not edit any file."

SCENARIOS = {
    "success": {
        "prompt": "Reply with the single word hi." + SAFE_PROMPT_TAIL,
        "approval_policy": "never",
        "sandbox": "read-only",
    },
    "failure": {
        "prompt": "Reply with the single word hi." + SAFE_PROMPT_TAIL,
        "approval_policy": "never",
        "sandbox": "read-only",
        "model": "loop-no-such-model",
    },
    "approval": {
        "prompt": "Create a file named hello.txt containing the line hello, then run ls to list the folder.",
        "approval_policy": "untrusted",
        "sandbox": "read-only",
    },
    "input-request": {
        "prompt": (
            "Before doing anything else, use the request_user_input tool to ask me which greeting I prefer, "
            "with the two options hi and hello. Then reply with the greeting I chose." + SAFE_PROMPT_TAIL
        ),
        "approval_policy": "never",
        "sandbox": "read-only",
        "experimental": True,
        # Without it, codex 0.167.0 asks the question as plain text and never sends item/tool/requestUserInput.
        "codex_config": ["features.default_mode_request_user_input=true"],
    },
    "usage": {
        "prompt": "Read README.md and summarize it in two sentences. Do not edit any file.",
        "approval_policy": "never",
        "sandbox": "read-only",
    },
}

# How long to keep reading after turn/completed, for the notifications that trail it (rate limits for example).
DRAIN_SECONDS = 3.0


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Session:
    def __init__(self, args, out_dir: Path):
        self.args = args
        self.scenario = args.scenario
        self.server_log = open(out_dir / f"{self.scenario}.server.jsonl", "w", encoding="utf-8")
        self.client_log = open(out_dir / f"{self.scenario}.client.jsonl", "w", encoding="utf-8")
        self.stderr_log = open(out_dir / f"{self.scenario}.stderr.log", "w", encoding="utf-8")
        command = args.codex.split() + ["app-server"] + sum((["-c", c] for c in args.codex_config), [])
        self.proc = subprocess.Popen(
            command,
            cwd=args.workspace,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr_log,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self.lines: "queue.Queue[str | None]" = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.answered_requests = []

    def _read(self):
        for line in self.proc.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def send(self, message: dict):
        line = json.dumps(message, separators=(",", ":"), ensure_ascii=False)
        self.client_log.write(line + "\n")
        self.client_log.flush()
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()
        label = message.get("method") or f"answer to {message.get('id')}"
        print(f"  -> {label}")

    def next_message(self, deadline: float):
        """The next server message, or None when the server closed stdout or the deadline passed."""
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                return None
            if line is None:
                return None
            self.server_log.write(line if line.endswith("\n") else line + "\n")
            self.server_log.flush()
            stripped = line.strip()
            if not stripped:
                continue
            try:
                message = json.loads(stripped)
            except json.JSONDecodeError:
                print(f"  <- (not JSON) {stripped[:120]}")
                continue
            self._print(message)
            if "method" in message and "id" in message:
                self.answer(message)
                continue
            return message

    @staticmethod
    def _print(message: dict):
        if "method" in message:
            kind = "request" if "id" in message else "notification"
            print(f"  <- {kind} {message['method']}")
        elif "error" in message:
            print(f"  <- error for {message.get('id')}: {json.dumps(message['error'])[:200]}")
        else:
            print(f"  <- answer to {message.get('id')}")

    def answer(self, request: dict):
        method = request["method"]
        params = request.get("params") or {}
        decision = self.args.decision
        if method in ("item/commandExecution/requestApproval", "item/fileChange/requestApproval"):
            result = {"decision": decision}
        elif method in ("execCommandApproval", "applyPatchApproval"):
            result = {"decision": "approved" if decision.startswith("accept") else "abort"}
        elif method == "item/tool/requestUserInput":
            answers = {}
            for question in params.get("questions") or []:
                options = question.get("options") or []
                if self.args.answer is not None:
                    chosen = self.args.answer
                elif options:
                    chosen = options[0]["label"]
                else:
                    chosen = "hi"
                answers[question["id"]] = {"answers": [chosen]}
            result = {"answers": answers}
        else:
            print(f"     (the recorder does not answer {method}, it sends an error)")
            self.send({"id": request["id"], "error": {"code": -32601, "message": f"recorder: {method} not handled"}})
            self.answered_requests.append(method)
            return
        self.send({"id": request["id"], "result": result})
        self.answered_requests.append(method)

    def request(self, request_id: int, method: str, params: dict, timeout: float):
        """Sends a request and returns its answer, or None when none came before the timeout."""
        self.send({"method": method, "id": request_id, "params": params})
        deadline = time.monotonic() + timeout
        while True:
            message = self.next_message(deadline)
            if message is None:
                return None
            if message.get("id") == request_id and "method" not in message:
                return message

    def wait_for_turn_end(self, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        while True:
            message = self.next_message(deadline)
            if message is None:
                return "timeout or server closed"
            if message.get("method") == "turn/completed":
                status = ((message.get("params") or {}).get("turn") or {}).get("status")
                return f"turn/completed ({status})"

    def drain(self, seconds: float):
        deadline = time.monotonic() + seconds
        while self.next_message(deadline) is not None:
            pass

    def close(self):
        try:
            self.proc.stdin.close()
        except BrokenPipeError:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        self.drain(0.5)
        for f in (self.server_log, self.client_log, self.stderr_log):
            f.close()


def codex_version(codex: str) -> str:
    out = subprocess.run(codex.split() + ["--version"], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenario", choices=sorted(SCENARIOS))
    parser.add_argument("--workspace", required=True, help="the throwaway workspace codex runs in")
    parser.add_argument("--out", default=".scratch/codex-recordings", help="where the raw files go (gitignored)")
    parser.add_argument("--prompt", help="override the scenario's prompt")
    parser.add_argument("--model", help="override the model (thread/start and turn/start)")
    parser.add_argument("--approval-policy", choices=["untrusted", "on-request", "never"])
    parser.add_argument("--sandbox", choices=["read-only", "workspace-write", "danger-full-access"])
    parser.add_argument("--experimental", action="store_true", default=None, help="initialize with experimentalApi")
    parser.add_argument("--thread-config", help="a JSON object passed as thread/start config")
    parser.add_argument("--codex-config", action="append", default=[], help="a -c key=value for codex app-server")
    parser.add_argument("--decision", default="accept", help="the answer to approvals: accept, decline or cancel")
    parser.add_argument("--answer", help="the answer to user input questions (default: the first option)")
    parser.add_argument("--timeout", type=float, default=300, help="seconds to wait for the turn to end")
    parser.add_argument("--no-turn", action="store_true", help="stop after thread/start, no model call")
    parser.add_argument("--codex", default="codex", help="the codex command")
    parser.add_argument("--expect-version", default="codex-cli 0.167.0", help="refuse another codex version")
    args = parser.parse_args()

    preset = SCENARIOS[args.scenario]
    prompt = args.prompt or preset["prompt"]
    model = args.model or preset.get("model")
    approval_policy = args.approval_policy or preset["approval_policy"]
    sandbox = args.sandbox or preset["sandbox"]
    experimental = args.experimental if args.experimental is not None else preset.get("experimental", False)
    thread_config = json.loads(args.thread_config) if args.thread_config else None
    args.codex_config = preset.get("codex_config", []) + args.codex_config

    version = codex_version(args.codex)
    if args.expect_version and version != args.expect_version:
        sys.exit(f"codex prints {version!r}, expected {args.expect_version!r}. Install the pinned version first.")

    workspace = Path(args.workspace).resolve()
    if not workspace.is_dir():
        sys.exit(f"no workspace folder at {workspace}")
    args.workspace = str(workspace)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    meta = {
        "scenario": args.scenario,
        "cli": version,
        "recordedAt": now_iso(),
        "prompt": prompt,
        "model": model,
        "approvalPolicy": approval_policy,
        "sandbox": sandbox,
        "experimentalApi": experimental,
        "threadConfig": thread_config,
        "codexConfig": args.codex_config,
        "decision": args.decision,
        "noTurn": args.no_turn,
    }
    print(f"Recording {args.scenario} with {version} in {workspace}")

    session = Session(args, out_dir)
    outcome = "not started"
    try:
        init_params = {"clientInfo": {"name": "loop", "version": "0.0.0"}}
        if experimental:
            init_params["capabilities"] = {"experimentalApi": True}
        if session.request(1, "initialize", init_params, 30) is None:
            outcome = "no answer to initialize"
            return
        session.send({"method": "initialized"})

        thread_params = {"approvalPolicy": approval_policy, "sandbox": sandbox, "cwd": str(workspace)}
        if model:
            thread_params["model"] = model
        if thread_config:
            thread_params["config"] = thread_config
        thread_answer = session.request(2, "thread/start", thread_params, 60)
        if thread_answer is None or "error" in thread_answer:
            outcome = "thread/start failed"
            session.drain(DRAIN_SECONDS)
            return
        thread_id = thread_answer["result"]["thread"]["id"]
        if args.no_turn:
            outcome = "stopped after thread/start (--no-turn)"
            session.drain(DRAIN_SECONDS)
            return

        turn_params = {
            "threadId": thread_id,
            "input": [{"type": "text", "text": prompt}],
            "approvalPolicy": approval_policy,
            "cwd": str(workspace),
        }
        if model:
            turn_params["model"] = model
        turn_answer = session.request(3, "turn/start", turn_params, 60)
        if turn_answer is None:
            outcome = "no answer to turn/start"
        elif "error" in turn_answer:
            outcome = "turn/start answered with an error"
            session.drain(DRAIN_SECONDS)
        else:
            outcome = session.wait_for_turn_end(args.timeout)
            session.drain(DRAIN_SECONDS)
    finally:
        session.close()
        meta["outcome"] = outcome
        meta["serverRequestsAnswered"] = session.answered_requests
        (out_dir / f"{args.scenario}.meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        print(f"Outcome: {outcome}")
        print(f"Raw files in {out_dir}/{args.scenario}.*  (scrub them with scrub.py before committing)")


if __name__ == "__main__":
    main()
