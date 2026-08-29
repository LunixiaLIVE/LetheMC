# Testing nixReaper

Notes from the 2026-08-28 session, kept so the next test server does not have to
rediscover any of it.

## Standing up a server

1. Fabric server launcher:
   `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/<installer>/server/jar`
   (26.2 / 0.19.3 / 1.1.2 worked).
2. `mods/` gets the nixReaper jar plus a matching Fabric API.
3. `eula.txt` → `eula=true`.
4. Copy `server.properties.example`, **set your own `rcon.password`**.
5. `java -jar fabric-server-launch.jar nogui`.

Drive admin commands with `rcon.py` (set `RCON_PASSWORD` in the environment).

## Things that will waste your time if you don't know them

**Ops are exempt.** `bypass.permissionLevel` defaults to 4, so an op dies and
nothing happens. The tester must stay non-op — which is also why admin commands
have to come over RCON rather than in-game.

**`pause-when-empty-seconds` must be 0.** Otherwise nixReaper stands down and
refuses to run at all. This is deliberate; see the startup banner.

**`hardcore` is baked into `level.dat` at world creation.** Setting it in
`server.properties` does nothing to an existing world. To test hardcore you must
delete the world and let it regenerate. Confirm with
`difficulty_settings: {hardcore: 1}` via `nbt_read.py`.

**26.x moved the player directories.** They are:

    world/players/data/<uuid>.dat   (+ .dat_old)
    world/players/advancements/<uuid>.json
    world/players/stats/<uuid>.json

NOT `world/playerdata/` etc. Checking the old paths silently "passes" every
time, because `ls` on a directory that does not exist prints nothing — which
looks exactly like "empty". This produced two false confirmations before it was
caught.

**A `FileLock` on Windows locks byte ranges, not the handle.** Another process
can `open()` a locked file perfectly well and only fails on an actual read or
write. Testing a lock by opening and closing it proves nothing.

**Worn armour is in an `equipment` compound in 26.x**, not `ArmorItems`, so it
does not appear in `data get entity <player> Inventory`.

**A player's first death entombs 3 files, not 4** — there is no `.dat_old` until
a second save has rotated one.

## Verifying, rather than assuming

The failure mode worth guarding against is a check that cannot fail. Prefer:

- Read state **before** the test and record it, so there is something to compare
  against rather than a judgement call afterwards.
- Read it again **after a disconnect/reconnect**, not just immediately after the
  event. A bug that persists bad data looks perfect until the save happens —
  this is exactly how a regression that destroyed a player's inventory and XP
  passed an immediate check.
- Make failure loud. If a path cannot be exercised, say so rather than counting
  it as passed.

`nbt_read.py` reads any NBT file (`level.dat`, playerdata) and is the quickest
way to check what is actually on disk:

```python
import importlib.util, sys
spec = importlib.util.spec_from_file_location("n", "nbt_read.py")
m = importlib.util.module_from_spec(spec); sys.argv = ["x"]; spec.loader.exec_module(m)
d = m.load("world/players/data/<uuid>.dat")
print(d["XpLevel"], d.get("respawn"), d.get("equipment"))
```

## Still untested

- **Anything multiplayer.** The keepInventory override is a global flag
  (`GameRules#get` has no player context), so an exempt player dying during
  another player's pardoned respawn would wrongly keep their items. Two deaths in
  one tick is also unexercised.
- **Linux.** File locks are mandatory on Windows and only advisory on POSIX, so
  the platform that has been tested is not the platform that matters. The
  graveyard *move* is the real protection there.
- A purge firing on schedule in a restarted JVM (lock re-acquisition and pardon
  after restart are both confirmed; the delete itself is not).
- Entombment retry after a genuinely blocked move, and a failing restore.
- `/nr admin purge`, `/nr admin lock`, `/nr status`, and bad-config clamping.
