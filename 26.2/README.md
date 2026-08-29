# nixReaper

Death costs you everything — **including your ender chest** — and locks you out of the server
for a set amount of real time.

Server-side only. Players connect with a vanilla client; nothing to install on their end.

---

## ⚠️ Required server setting

```properties
pause-when-empty-seconds=0
```

**nixReaper will refuse to run if this is anything else.** You will see a large `ERROR` block
in the console at startup, and `/nr info` will report the mod as disabled.

Why: a server with this setting stops ticking once the last player leaves — which is exactly
when a player who just died has left. The wipe would silently never happen, players would
keep everything, and nothing would tell you. Rather than half-work, nixReaper stands down
completely and leaves deaths as vanilla until you fix it.

If you enable the pause while players are already locked out, nixReaper hands their items
back and clears its records on the next start, so nobody is left in limbo.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Loader | Fabric 0.19.3+ |
| Fabric API | 0.153.0+26.2 |
| Java | 21+ |
| Side | Server only |

## Install

1. Drop the jar in your server's `mods/` folder alongside Fabric API.
2. Set `pause-when-empty-seconds=0` in `server.properties`.
3. Start the server. You should see `nixReaper ready` in the log.

---

## What happens when a player dies

1. **Nothing drops.** There is no body to loot and no items on the ground.
2. The death screen stays up for 15 seconds so they can read what killed them.
3. They are disconnected and locked out for `lockout.minutes` (6 hours by default).
4. Their inventory, ender chest, XP, advancements and statistics are erased.
5. Rejoining before the lockout ends shows a live `DD:HH:MM:SS` countdown, plus how long a
   pardon can still undo it.
6. When it expires they come back as a brand-new player.

The lockout is **real time**. It keeps counting while the server is offline — a 6 hour
lockout started at 22:00 has expired by 04:00 whether or not the server ran overnight.

### The grace period

For `wipe.graceMinutes` after a death (5 by default), `/nr admin pardon <player>` gives the
player **everything** back — items, worn armour, ender chest, XP, advancements, stats — and
lifts the lockout. Use it for deaths that were not the player's fault: a lag spike, a bad chunk
load, your own mistake.

After that window the data is gone for good and a pardon only lifts the lockout. The player is
told which of the two applies whenever they try to rejoin.

Admins **cannot pardon themselves**, only other people. Otherwise the penalty is optional for
anyone holding the permission. A genuinely bogus death is still recoverable — by another admin,
or from the server console.

---

## Commands

Root command is `/nixreaper`, aliased to `/nr`.

### Everyone

| Command | Does |
|---|---|
| `/nr` or `/nr info` | What happens when you die, current durations |
| `/nr status` | Your own lockout, if any |

### Admin

Requires the op level set by `bypass.permissionLevel` (default 4). The server console always
qualifies.

| Command | Does |
|---|---|
| `/nr admin list` | Everyone currently locked out |
| `/nr admin status <player>` | Their lockout, where their data is, whether a pardon still restores it |
| `/nr admin pardon <player>` | Lift the lockout, and restore everything if inside the grace period. **You cannot pardon yourself** — ask another admin or use the console |
| `/nr admin purge <player>` | Erase their data now, skipping the grace period |
| `/nr admin lock <player> [minutes]` | Lock someone out manually |
| `/nr admin config list` | Show all settings |
| `/nr admin config get <key>` | Read one setting |
| `/nr admin config set <key> <value>` | Change one setting, saved immediately |
| `/nr admin reload` | Re-read the config from disk |

> **Heads up:** the permission level that grants `/nr admin` is the same one that makes a
> player exempt from dying. Anyone who can pardon is also immune. Set
> `bypass.permissionLevel` with that in mind.

---

## Config

`config/nixreaper/config.json`, created on first start. Every key is also settable in-game
with `/nr admin config set`.

| Key | Default | Meaning |
|---|---|---|
| `wipe.playerData` | `true` | Erase inventory, ender chest, XP, spawn point |
| `wipe.advancements` | `true` | Erase advancements **and recipe book unlocks** |
| `wipe.stats` | `true` | Erase statistics |
| `wipe.graceMinutes` | `5` | How long a pardon still restores everything |
| `wipe.lockFiles` | `true` | Lock held files so nothing else can block the wipe |
| `lockout.minutes` | `360` | Lockout length in real-time minutes (6 hours) |
| `lockout.deathScreenSeconds` | `15` | How long the death screen is held |
| `message.death` | — | Shown on the disconnect that follows a death |
| `message.rejoin` | — | Shown when a locked-out player tries to join |
| `bypass.permissionLevel` | `4` | Op level exempt from the penalty (`0` exempts everyone) |

**`lockout.minutes` must be greater than `wipe.graceMinutes`.** Both are in minutes, so at the
defaults that means a minimum lockout of 6 minutes. Values that break the rule are rejected with a message
telling you the minimum, and a bad config file is corrected on load rather than refusing to
boot.

### Message placeholders

| Placeholder | Expands to |
|---|---|
| `%player%` | Player name |
| `%time_remaining%` | `2h 14m` |
| `%time_remaining_short%` | `00:02:14:33` — `DD:HH:MM:SS` |
| `%unlock_time%` | Wall-clock time the lockout ends, in the **server's** timezone |
| `%death_reason%` | The vanilla death message |
| `%grace_remaining%` | Time a pardon still restores everything, or `expired` |
| `%grace_remaining_short%` | `00:00:04:12` — `DD:HH:MM:SS` |
| `%grace_line%` | A ready-written sentence covering both cases |

Colour codes use `§`. Use `\n` for a line break.

The default rejoin message shows a **countdown**, not a clock time. An absolute unlock time has
to be printed in some timezone, and the server's is not necessarily the player's, with nothing
on screen saying so. `%unlock_time%` is still available if you want it — it just isn't the
default.

---

## Notes

- **Ops are exempt by default.** Test with a non-op account, or you will die and nothing will
  happen.
- **Recipe books reset.** Recipe unlocks live in the advancement file, so a player returns
  unable to craft things they had already discovered. In practice this is more noticeable
  than losing the advancements themselves.
- **Whitelist and op status are never touched.** Neither are vanilla bans.
- **Tamed pets and scoreboard scores are not erased.** Pets store their owner on the entity,
  and scoreboards are keyed by player name in a shared file.

## License

See `LICENSE`.
