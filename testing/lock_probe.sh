#!/usr/bin/env bash
# Waits for the next entombment, then probes the graveyard files from a second
# process while the server still holds its locks on them.
#
# The point of the probe: FileChannel.tryLock() is kernel-enforced on Windows and
# only advisory on POSIX. nixReaper's design claims the graveyard *move* is the
# real protection on Linux and the lock is only defence in depth -- this is what
# actually checks that, rather than assuming it.
#
# Run from the server directory:  bash tools/lock_probe.sh
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=server.log

# grep -c prints 0 and exits 1 when there are no matches. `|| echo 0` would then
# append a SECOND zero and every later integer test would fail -- which is
# exactly how the first attempt at this spun until it timed out.
count_entombs() { grep -c 'Entombed' "$LOG" 2>/dev/null || true; }

BASE=$(count_entombs)
BASE=${BASE:-0}
echo "waiting for an entombment (currently $BASE)..."

for _ in $(seq 1 300); do
    NOW=$(count_entombs); NOW=${NOW:-0}
    [ "$NOW" -gt "$BASE" ] && break
    sleep 1
done

NOW=$(count_entombs); NOW=${NOW:-0}
if [ "$NOW" -le "$BASE" ]; then
    echo "TIMED OUT -- no entombment seen. Nothing probed."
    exit 1
fi

echo "=== ENTOMB $(date +%H:%M:%S) ==="
grep 'Entombed' "$LOG" | tail -1

echo "--- live dirs (26.x paths) ---"
for d in world/players/data world/players/advancements world/players/stats; do
    printf '    %-34s -> %s file(s)\n' "$d" "$(ls -1 "$d" 2>/dev/null | wc -l)"
done

echo "--- graveyard ---"
find world/nixreaper -type f -printf '    %f  %s bytes\n' 2>/dev/null

echo
echo "=== LOCK PROBE from a second process ==="
python3 - <<'PY'
import glob, os
files = sorted(glob.glob('world/nixreaper/graveyard/*/*'))
if not files:
    print("    no graveyard files to probe")
for f in files:
    name = os.path.basename(f)
    try:
        with open(f, 'rb') as fh:
            fh.read(16)
        r = 'READ OK'
    except OSError as e:
        r = 'READ BLOCKED(%s)' % type(e).__name__
    # Net-zero write: read byte 0, seek BACK to 0, write the same byte.
    #
    # The obvious version -- fh.seek(0); fh.write(fh.read(1)) -- is a data
    # destroyer. read(1) advances the cursor, so the write lands at offset 1 and
    # duplicates byte 0 into byte 1. On Windows the mandatory lock blocks it and
    # nothing happens; on Linux the advisory lock lets it through and silently
    # corrupts the file. That turned "{\"" into "{{" in the JSON files and the
    # gzip magic 1f 8b into 1f 1f, and cost a tester their inventory.
    try:
        with open(f, 'r+b') as fh:
            first = fh.read(1)
            if first:
                fh.seek(0)
                fh.write(first)
        w = 'WRITE OK'
    except OSError as e:
        w = 'WRITE BLOCKED(%s)' % type(e).__name__
    print('    %-22s %-22s %s' % (name, r, w))
print()
print("    Windows expectation: BLOCKED / BLOCKED  (mandatory locks)")
print("    Linux   expectation: OK / OK            (advisory locks)")
PY

echo
echo "--- kernel's view: does the JVM hold locks on these inodes? ---"
PID=$(pgrep -f fabric-server-launch | head -1)
echo "    server pid: ${PID:-<none>}"
if [ -r /proc/locks ]; then
    awk -v p="${PID:-0}" '$5 == p' /proc/locks | head -10
    MATCHES=$(awk -v p="${PID:-0}" '$5 == p' /proc/locks | wc -l)
    echo "    /proc/locks entries owned by the server: $MATCHES"
else
    echo "    /proc/locks not readable"
fi
