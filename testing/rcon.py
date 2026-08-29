#!/usr/bin/env python3
"""Minimal Minecraft RCON client -- no dependencies.

    python rcon.py "nr admin list" "nr admin status Someone"

Host/port/password come from env vars, so no credentials live in this file:

    RCON_HOST      default 127.0.0.1
    RCON_PORT      default 25575
    RCON_PASSWORD  default "changeme" -- set this

Why this exists: the player under test must stay NON-op, because
bypass.permissionLevel gates the death exemption AND /nr admin with the same
value -- so an op can run pardon but never dies, and a player who dies can
never run pardon. RCON has full console permissions and sidesteps that, which
is the only practical way to drive admin commands during a solo test.

Also note the server console cannot be driven by piping stdin into a launcher:
that makes every command fail with "An unexpected error occurred while trying
to execute that command", including vanilla ones. RCON avoids that too.
"""
import os
import socket
import struct
import sys

# Server replies contain section signs and, in the default rejoin message, a
# death rune; a Windows console defaults to cp1252 and dies on both.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))


def _password():
    """Env var, else a 0600 file next to the server. Never a literal in argv.

    Passing the password on a command line -- including as a `VAR=x cmd` prefix
    over ssh -- leaks it into the process list, where any user on the box can
    read it out of `ps`. Learned the hard way.
    """
    env = os.environ.get("RCON_PASSWORD")
    if env:
        return env
    for candidate in (".rcon_pw", os.path.join(os.path.dirname(__file__), "..", ".rcon_pw")):
        try:
            with open(candidate, "r", encoding="utf8") as fh:
                return fh.read().strip()
        except OSError:
            continue
    return "changeme"


PASSWORD = _password()

LOGIN, COMMAND = 3, 2


def _send(sock, req_id, pkt_type, body):
    payload = struct.pack("<ii", req_id, pkt_type) + body.encode("utf8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _read_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def _recv(sock):
    raw_len = _read_exactly(sock, 4)
    if raw_len is None:
        return None, None
    (length,) = struct.unpack("<i", raw_len)
    data = _read_exactly(sock, length)
    if data is None:
        return None, None
    req_id, _pkt_type = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf8", errors="replace")


def main(commands):
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        _send(sock, 1, LOGIN, PASSWORD)
        req_id, _ = _recv(sock)
        if req_id != 1:
            print("RCON auth failed -- check RCON_PASSWORD", file=sys.stderr)
            return 1
        for i, cmd in enumerate(commands, start=2):
            _send(sock, i, COMMAND, cmd)
            _, body = _recv(sock)
            print("> {}".format(cmd))
            print(body.strip() if body and body.strip() else "(no output)")
            print()
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
