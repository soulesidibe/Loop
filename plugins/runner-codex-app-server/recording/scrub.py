#!/usr/bin/env python3
"""Scrubs a raw codex app server recording for the m3-03 fixtures.

Reads `<scenario>.server.jsonl` written by record.py and writes a copy safe to publish: the home folder becomes
`/home/user`, the workspace `/workspace`, the user name `user`, ids that name the account or the machine become zero
UUIDs, the machine's host name `host`, emails become `user@example.com`, and anything shaped like a token becomes `<redacted>`. Each line is parsed and
written back compact, keys in their order, as the existing fixture is. Then it runs the same check as the grep in
src/test/resources/real/README.md and exits 1 when something is left.

The scrub is a first pass, not a proof: read every fixture by hand before committing it.

Standard library only. See README.md in this folder.
"""

import argparse
import getpass
import json
import os
import re
import socket
import sys
from pathlib import Path

ZERO_UUID = "00000000-0000-0000-0000-000000000000"

# Keys whose string value names the account, the user or the machine.
ID_KEYS = {
    "installationId",
    "accountId",
    "account_id",
    "chatgptAccountId",
    "chatgpt_account_id",
    "workspaceId",
    "userId",
    "user_id",
    "organizationId",
    "orgId",
    "deviceId",
}
EMAIL_KEYS = {"email"}
HOST_KEYS = {"serverName", "hostname", "hostName", "host"}
TOKEN_KEYS = {"accessToken", "access_token", "idToken", "id_token", "refreshToken", "refresh_token", "apiKey", "token"}

JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")
SECRET_KEY = re.compile(r"\bsk-[A-Za-z0-9_-]{16,}")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")


def host_names():
    """The machine's host names, long and short, which often carry the owner's name."""
    names = {socket.gethostname(), socket.getfqdn()}
    names |= {n.split(".")[0] for n in list(names)}
    return sorted((n for n in names if n and len(n) >= 3 and n != "localhost"), key=len, reverse=True)


def path_variants(path: str):
    """The path as written, resolved, and with or without macOS's /private prefix, longest first."""
    variants = {path, os.path.realpath(path)}
    for p in list(variants):
        if p.startswith("/private/"):
            variants.add(p[len("/private") :])
        elif p.startswith(("/tmp/", "/var/")):
            variants.add("/private" + p)
    return sorted((v.rstrip("/") for v in variants if v and v != "/"), key=len, reverse=True)


class Scrubber:
    def __init__(self, workspace: str, home: str, user: str):
        self.replacements = [(v, "/workspace") for v in path_variants(workspace)]
        self.replacements += [(v, "/home/user") for v in path_variants(home)]
        self.user = user
        self.hosts = host_names()

    def text(self, s: str) -> str:
        for old, new in self.replacements:
            s = s.replace(old, new)
        for host in self.hosts:
            s = re.sub(re.escape(host), "host", s, flags=re.IGNORECASE)
        s = JWT.sub("<redacted>", s)
        s = SECRET_KEY.sub("<redacted>", s)
        s = EMAIL.sub("user@example.com", s)
        if self.user and len(self.user) >= 3:
            s = re.sub(rf"\b{re.escape(self.user)}\b", "user", s)
        return s

    def value(self, v, key=None):
        if isinstance(v, dict):
            return {k: self.value(x, k) for k, x in v.items()}
        if isinstance(v, list):
            return [self.value(x) for x in v]
        if isinstance(v, str):
            if key in ID_KEYS:
                return ZERO_UUID
            if key in EMAIL_KEYS:
                return "user@example.com"
            if key in HOST_KEYS:
                return "host"
            if key in TOKEN_KEYS:
                return "<redacted>"
            return self.text(v)
        return v


def leaks(text: str, home: str, user: str, workspace: str):
    """What the README's grep looks for, so the scrub fails the same way the grep does."""
    patterns = [re.escape(home), r"/Users/", r"/private/", r"/var/folders/", JWT.pattern, SECRET_KEY.pattern]
    patterns += [re.escape(v) for v in path_variants(workspace)]
    patterns += ["(?i)" + re.escape(h) for h in host_names()]
    if user and len(user) >= 3:
        patterns.append(rf"\b{re.escape(user)}\b")
    found = []
    for n, line in enumerate(text.splitlines(), 1):
        for p in patterns:
            if re.search(p, line):
                found.append((n, p))
        for m in EMAIL.finditer(line):
            if m.group(0) != "user@example.com":
                found.append((n, "an email"))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("raw", help="the raw <scenario>.server.jsonl")
    parser.add_argument("out", help="the scrubbed fixture to write")
    parser.add_argument("--workspace", required=True, help="the workspace the session ran in")
    args = parser.parse_args()

    home = str(Path.home())
    user = getpass.getuser()
    scrubber = Scrubber(args.workspace, home, user)

    lines = []
    for n, raw in enumerate(Path(args.raw).read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            sys.exit(f"line {n} of {args.raw} is not JSON, a fixture holds protocol messages only")
        lines.append(json.dumps(scrubber.value(message), separators=(",", ":"), ensure_ascii=False))

    text = "\n".join(lines) + "\n"
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(text, encoding="utf-8")
    print(f"Wrote {len(lines)} lines to {args.out}")

    found = leaks(text, home, user, args.workspace)
    if found:
        for n, what in found:
            print(f"  line {n}: still matches {what}")
        sys.exit("Something is left. Fix it before committing.")
    print("The check found nothing. Now read the fixture by hand.")


if __name__ == "__main__":
    main()
