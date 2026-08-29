# nixReaper — Design Doc

**Name:** `nixReaper` — Modrinth slug `nixreaper` **verified free** 2026-08-20. CurseForge unverified (Cloudflare 403) — check manually before registering.
**Status:** implemented and **in-game tested end to end on 2026-08-28** against a real client (v0.1.0.4). Four bugs found and fixed during that session — see §10.
**Target:** Fabric, MC 26.x, **server-side only** (vanilla clients connect unmodified)

---

## 1. Core Spec — LOCKED

| # | Requirement |
|---|---|
| **A** | Server-side only. No client install required. |
| **B** | Death costs the player everything. Nothing drops on the ground. |
| **C** | Death = lockout for a configured number of **real-time minutes**. Wall-clock only — no Minecraft time, no game ticks. |
| **D** | Join attempt during lockout shows a **live `DD:HH:MM:SS` countdown** of time remaining — a duration, never a wall-clock timestamp (§3.5). |
| **E** | Open **player** commands to view the setup + `admin` subcommand for config and hot reload. |
| **F** | Death screen held **X seconds (default 15)** so the player sees what killed them. Lockout timer starts at **screen expiry OR manual disconnect, whichever comes first.** |
| **G** | **Total UUID purge** — everything associated with the player is erased except their ban-list entry, whitelist entry, and op status. Includes stats and advancements. |
| **H** | Files leave the live world **immediately** on removal (entombed in a graveyard), and are hard-deleted after a **grace period (default 5 min)**. |
| **I** | **Lockout duration must always exceed the grace period.** Enforced by config validation — the hard delete can never outlive the ban. |
| **J** | `/nr admin pardon` **cancels both the lockout and any pending purge** — pardoning inside the grace period restores the player's files out of the graveyard **and carries their inventory + XP through the respawn that follows** (§4.10). |
| **K** | The mod **refuses to run** if `pause-when-empty-seconds > 0`, because the purge would silently never fire (§4.11). |
| **L** | Composes with vanilla **hardcore**: the spectator lock is bypassed, not disabled, and a resurrected player never becomes a spectator (§4.12). |
| **M** | The lockout is called **Purgatory**; leaving it is **Resurrection** (intact) or **Reincarnation** (empty). Stakes are always named plainly as *Inventory, Ender Chest & XP* (§4.14). |
| **N** | Reincarnation **destroys the animals you tamed**, and they are untouchable by anyone while you are in Purgatory (§11). |

---

## 2. Architecture

```
t=0        DEATH
           ├─ force keep-inventory IN MEMORY  →  nothing drops
           └─ ledger entry: UUID, deathAt, state=DYING, timer NOT started

t=0..15s   DEATH SCREEN
           ├─ respawn requests ignored
           └─ player may disconnect manually at any point

t=T        T = min(deathScreenSeconds elapsed, manual disconnect)
           ├─ lockoutStartsAt = T, state=LOCKED, wipePending=true
           └─ if still connected: disconnect with countdown message

t=T        ENTOMB  (tail of PlayerList.remove — same tick, not a timer)
           ├─ vanilla has just flushed playerdata + stats + advancements
           ├─ MOVE all four files → <world>/nixreaper/graveyard/<uuid>/
           ├─ take an exclusive FileLock on each
           └─ graveyardAt = T, purgeAt = T + wipe.graceMinutes
           ── live world dirs are clean from this instant ──

t=T+5min   HARD DELETE  (wipe.graceMinutes, default 5)
           ├─ release locks, delete the plot, rmdir
           └─ wipePending=false — unrecoverable from here

later      RECONNECT ATTEMPT
           └─ join intercept checks UUID vs ledger
              ├─ still locked   →  reject with live countdown
              │                    + grace countdown (§4.6)
              ├─ wipePending    →  clear plot AND live files, then admit  ← §4.7
              └─ clean          →  admit; no data on disk = brand-new player
```

### Why this shape
Forcing keep-inventory **in memory** — never touching the real gamerule — means nothing ever drops: no item scatter, no corpse to loot, no drop-then-clear race, no surprise state change for admins or other mods.

**Entombment replaces the old five-minute purge delay.** That delay existed for one reason: vanilla flushes three files on player removal, and a purge firing immediately raced all three. Five minutes was a wall-clock *guess* that the flush had finished.

It doesn't have to be a guess. `PlayerList.remove` calls `save(player)` near the top, which runs `PlayerDataStorage.save` → `ServerStatsCounter.save` → `PlayerAdvancements.save`. In 26.2 **all three are fully synchronous** — plain `NbtIo.writeCompressed` and `Files.newBufferedWriter`, no IO pool, no future — and `remove` has exactly **one** `return` instruction, so `@At("TAIL")` is unambiguous and unconditional. At that point every file exists in its final state and the player is already out of the list. The ordering is a fact, not a race we outran.

So the two jobs the delay was conflating get split:
- **Removal from the live world is immediate** — a move, at T, verified by ordering.
- **The grace period runs against the graveyard copy**, which is what §4.6 actually wanted.

---

## 3. Locked Decisions

### 3.1 Timer start (**F**)
`lockoutStartsAt = min(deathAt + deathScreenSeconds, manualDisconnectAt)`.

Ledger entry is written at **t=0** (state `DYING`) so a crash during the death screen still bans them; `lockoutStartsAt` is stamped at **t=T**.

- **Crash between t=0 and t=T:** on restart a `DYING` entry with no start time falls back to `lockoutStartsAt = deathAt`. Errs in the player's favor by at most `deathScreenSeconds`.
- **Minor, accepted:** disconnecting early starts the timer early, so a rage-quit shaves up to 15s off the lockout. Noise against a 6-hour default.

### 3.2 In-memory keep-inventory override
The mod behaves as if `keepInventory=true` regardless of the gamerule or server.properties. **The gamerule itself is never modified.** Purely to stop drops — items are destroyed at purge time.

### 3.3 Custom ban ledger, keyed by UUID
Still a custom ledger rather than the vanilla ban list — but note that dropping the MC-days clock removed one of the two original justifications (the vanilla list stores absolute dates and literally could not express "3 Minecraft days"). With real-time only, a vanilla timed ban *could* express the duration. The remaining reasons still hold and are sufficient:

1. **The mod needs its own per-player state anyway** — `wipePending`, `purgeAt`, `state`, `durationSnapshot` have nowhere to live in the vanilla ban list. Once that store exists, splitting the lockout across two systems adds desync risk for nothing.
2. **Pardon must cancel the purge (§4.6, J).** If lockouts lived in the vanilla ban list, an admin could clear one with vanilla `/pardon` and leave a scheduled purge dangling — the player returns, then gets wiped anyway.
3. **Live countdown formatting.** Vanilla renders a fixed expiry timestamp; **D** wants `2h 14m` computed per join attempt.

Tradeoff accepted: no `/banlist` entry, vanilla `/pardon` won't clear it. Mod ships its own commands (§5).

### 3.4 Real-time clock only (**C**)
Lockout duration is `lockout.minutes`, measured in **wall-clock time**. No Minecraft-day clock, no tick counting.

The timer counts down while the server is offline — a 6-hour lockout started at 22:00 has expired by 04:00 whether or not the server ran overnight. This is the intended and less surprising behavior.

**Why the world clock was dropped:** a Minecraft-day lockout is controlled by *other players*. Everyone sleeping burns the night instantly, so a 1-MC-day ban can elapse in seconds of real time — the ban expires, the player rejoins with everything intact, and the still-pending purge then fires on them while online. Worse, it hands the group a lever: sleep-cycle to spring a friend early, or refuse to sleep to stretch someone's sentence. No config validation can bound any of it (§4.4b).

### 3.5 Countdown message
Computed fresh at each join attempt, so always accurate.

**A duration, not a timestamp.** An absolute unlock time has to be rendered in *some* timezone,
and the server's is not necessarily the player's — so a player in another region reads a number
that is simply wrong for them, with nothing on screen saying so. `DD:HH:MM:SS` means the same
thing everywhere and needs no conversion. The day field is always printed rather than appearing
only when non-zero, so the column positions never shift as the value counts down.

`%unlock_time%` is retained for anyone who wants it, but is no longer in the default template.

| Placeholder | Expands to |
|---|---|
| `%time_remaining%` | humanized — `2h 14m`, `45m` |
| `%time_remaining_short%` | `00:02:14:33` — `DD:HH:MM:SS` |
| `%unlock_time%` | absolute wall-clock time the lockout ends, in the **server's** timezone — still supported, no longer used by the default message |
| `%player%` | player name |
| `%death_reason%` | vanilla death message |
| `%grace_remaining%` | time left in which a pardon still restores everything — `4m 12s`, or `expired` |
| `%grace_remaining_short%` | `00:00:04:12` — `DD:HH:MM:SS` |
| `%grace_line%` | the whole recoverability sentence, pre-coloured, both branches handled — used by the default `message.rejoin` |

---

## 4. The UUID Purge (**G**, **H**)

### 4.1 Purge these
Per-UUID files in the world folder:

| Path | Contains |
|---|---|
| `<world>/players/data/<uuid>.dat` | inventory, ender chest, XP, spawn point, position, health, hunger, effects, gamemode, attributes |
| `<world>/players/data/<uuid>.dat_old` | backup — **see §6.3** |
| `<world>/players/advancements/<uuid>.json` | advancements **and recipe-book unlocks** |
| `<world>/players/stats/<uuid>.json` | all statistics |

> ⚠️ **26.x moved these.** They are NOT `<world>/playerdata/`, `<world>/advancements/`,
> `<world>/stats/` as in earlier versions — everything now lives under `<world>/players/`.
> The code was never wrong here (it resolves via `LevelResource`, which picked up the new
> layout automatically), but the old paths are what a human will type when verifying by hand,
> and `ls` on a non-existent directory returns nothing, which reads exactly like "empty".

### 4.2 ⚠️ NEVER purge these
| Path | Why it must survive |
|---|---|
| `whitelist.json` | **On a whitelisted server, removing them locks them out permanently** — the lockout becomes a life sentence. Hard exclusion. |
| `ops.json` | Silently deops staff who die. Hard exclusion. |
| `banned-players.json` | Vanilla bans are unrelated to this mod. |
| `usercache.json` | Name↔UUID cache; removing it breaks name resolution in commands for no benefit. |
| nixReaper ledger | The ban entry itself — explicitly excluded per spec. |

### 4.3 Side effects of wiping advancements + stats — intended, but worth knowing
- **Recipe book resets completely.** Recipe unlocks live in the advancement file, so the player returns unable to craft anything they hadn't rediscovered. Bigger in practice than losing advancement toasts.
- **Advancement toasts re-fire** on rejoin. Cosmetic.
- **Stat-tracking scoreboard objectives zero out** for that player.

### 4.4 Constraint: lockout must outlast the grace period (**I**)

**Rule:** `lockout.minutes > wipe.graceMinutes`.

Both values are in minutes, so the rule is a plain comparison an admin can check by eye. The default 5 minute grace yields a **6-minute minimum lockout**. The hard delete can therefore never still be pending when the ban lifts.

> **Key history.** `wipe.delaySeconds` → `wipe.graceSeconds` (rename, aliased) → `wipe.graceMinutes` (**unit change, deliberately NOT aliased**).
>
> Aliasing the seconds key onto the minutes field would silently reinterpret an existing `300`
> as five *hours*. An old config loses the value and takes the default instead, which is the
> safe direction to be wrong in.

This removes the bug a bare delay would otherwise introduce — without the constraint, a short lockout produces:

```
t=T        disconnect, hard delete scheduled for T+5min
t=T+2min   lockout expires, player rejoins WITH ALL THEIR STUFF
t=T+5min   hard delete fires on a LIVE player
           → vanilla re-saves their playerdata on next disconnect
           → purge silently undone
```

**Where it must be enforced** — all three, since every one of them is reachable at runtime:
1. **Config load** — reject or clamp an invalid file, log loudly.
2. **`config set`** — in *both* directions. Lowering `lockout.minutes` and raising `wipe.graceMinutes` can each break the invariant, so both setters validate against the other's current value.
3. **`/nr admin lock <player> [dur]`** — a manual lock with a custom duration is subject to the same floor.

Error text should name both values and the resulting minimum, e.g.
`lockout.minutes must be greater than wipe.graceMinutes (5). Try 6 or higher.`

### 4.4b ✅ The constraint is now airtight
Two paths previously escaped it. Both are closed:
- **Pardon** — closed by **J** (§4.6). Cancelling the purge alongside the lockout leaves nothing scheduled when a player is released early.
- **`MC_DAYS`** — closed by **C**. It was fundamentally unvalidatable: with everyone sleeping, a 1-MC-day lockout elapses in seconds, so the ban reliably expires while the purge is still pending. Real-time-only removes the mode entirely.

With both gone, **no lockout can expire while a purge is pending** — *provided the purge actually runs on schedule.*

> ⚠️ **Testing broke this assumption.** On a server with `pause-when-empty-seconds > 0` the
> purge never fires at all, so the lockout expires with the wipe still pending regardless of
> any config arithmetic (§4.11, measured 2026-08-28). The invariant holds over *timing*, not
> over *the sweep being alive*. Closed by **K**, which refuses to run in that configuration.
>
> This is also the case that promoted §4.7's join guard from "durability mechanism" back to a
> genuine correctness backstop: it is what caught the missed purge and cleared the data before
> admitting the player. Do not remove it.

### 4.5 Server shutdown during the grace period
A restart at T+2min means the scheduled hard delete never runs. The window is 5 minutes wide, so this is a realistic occurrence, not a corner case.

`wipePending` is persisted in the ledger and re-checked on startup — and enforced at join (§4.7) as the backstop. Without it, a well-timed restart is a free full-inventory pardon.

Startup recovery has three jobs, in this order:
1. **Re-take the file locks.** They died with the last JVM. Nothing else in the design re-establishes them.
2. **Entomb anyone caught mid-flight.** A `DYING` entry, or a `LOCKED` one that never got its files moved. Shutdown flushes everything on the way out, so the files are settled and the move is safe immediately.
3. **Run any hard delete that fell due while the server was off.** Grace is real-time like the lockout (**C**); a restart must not silently extend anyone's mercy window.

### 4.6 ✅ LOCKED: pardon cancels the lockout *and* the pending purge (**J**)

`/nr admin pardon <player>` clears the ledger entry **and** cancels any scheduled purge. Consequence, by design:

| When the pardon lands | Result |
|---|---|
| Inside `wipe.graceMinutes` (default 5) | **Full restoration** — files lifted back out of the graveyard into `playerdata/`, `advancements/`, `stats/`; lockout lifted |
| Before entombment ran at all | Same outcome — the files never left, so cancelling *is* the restoration |
| After the grace period | Lockout lifted only — the data is already gone and is **not recoverable** |

This is a deliberate **mercy window** for bogus deaths — lag spike, dodgy hitbox, admin error. It is the mod's only undo, and it is time-limited by design.

Restoring is safe without any online-player check: a pardonable player is necessarily offline, because their lockout is still running. Nothing has written them a fresh profile to clobber.

**Admins cannot pardon themselves.** Otherwise the penalty is optional for anyone holding the
permission — die, pardon, keep everything, repeat. Another admin or the server console can still
pardon them, so a genuinely bogus death stays recoverable; it just stops being a decision someone
makes alone about themselves. Note this guard is *mostly dormant today*: `bypass.permissionLevel`
gates `/nr admin` and the death exemption with the same value, so anyone who can pardon never
gets a ledger entry in the first place. It is reachable when a locked-out player is opped
afterwards, and becomes load-bearing the moment those two permissions are split (§10).

A **failed** restore does not clear the ledger entry. The plot stays intact, the lockout stays put, and the admin can retry — dropping the entry would strand the files with nothing left pointing at them.

**Command feedback must state which outcome occurred**, since the admin otherwise cannot tell:
- `Pardoned <player> — purge cancelled, data restored.`
- `Pardoned <player> — lockout lifted, but data was already purged.`

`/nr admin status <player>` should surface time-remaining-in-window so an admin can see whether restoration is still possible before deciding.

### 4.7 `wipePending` is load-bearing
It was a safety net when the purge fired immediately. It is now a core mechanism, enforcing four things:
1. Never admit a player mid-purge (§4.4)
2. Survive a restart inside the grace period (§4.5)
3. Retry a failed **entombment** — something held a file open at removal time; retried every 5s
4. Retry a failed **hard delete** (IO error, foreign file lock) — retried every 30s

Note it means "the obligation is not discharged", not "a timer is running". `graveyardAt` is what says whether the files have moved; `purgeAt` is what says when they die.

### 4.8 Not purged in v1
- **Tamed pets** — wolves, cats, horses, parrots store their owner UUID *on the entity, in chunk data*, not in any player file. A literal reading of "everything associated with that UUID" covers them, but killing or orphaning pets on death is a far larger design statement than wiping files, and it wasn't asked for. The one genuinely ambiguous case; say the word if it should change.
- **Scoreboard scores and teams** — vanilla keys these by **player name** in a shared `<world>/data/scoreboard.dat`, not per-UUID files. Purging a shared file to remove one player is out of scope and risky.
- Item frames, ender pearls in flight, player heads.

### 4.9 The graveyard

`<world>/nixreaper/graveyard/<uuid>/` holding `playerdata.dat`, `playerdata.dat_old`, `advancements.json`, `stats.json` — fixed names, so restore is a deterministic mapping back to the live paths.

**Why inside the world folder, not `config/`.** A move within one filesystem is a rename: atomic, instant, and incapable of leaving a second copy behind if the server dies halfway. Putting the graveyard under the world guarantees it shares a filesystem with `playerdata/` no matter how the host has mounted things. Bonus: a world backup captures the graveyard consistently with the world it belongs to.

**Restore reads the directory, not the config.** Entombment consults `wipe.*` to decide what to take; restore puts back whatever is actually in the plot. Otherwise flipping `wipe.stats` to false between a death and a pardon would strand that player's stats in the graveyard forever.

**`.dat_old` is taken too.** `Util.safeReplaceFile` rotates `.dat` → `.dat_old` on every save, so the backup holds state from one save ago. Leaving it behind leaves a near-complete copy of the inventory sitting in the world folder — which was the entire point of §6.3.

#### File locking (`wipe.lockFiles`, default true)
Each entombed file is held open under an exclusive `FileLock` for the whole grace period, so the hard delete at the end cannot be blocked by something else having opened it in the meantime.

> ⚠️ **Mandatory on Windows, advisory on Linux.** Windows enforces the lock in the kernel — another process opening the file gets a sharing violation, exactly the guarantee we want. POSIX locks only bind processes that ask for locks themselves, so a stray `rsync` or backup job on a Linux host reads straight through one.
>
> **The real protection on Linux is the move**: the file is no longer at any path another process would think to look at. The lock is defence in depth, not the defence. A lock that cannot be taken is therefore logged and shrugged off, never fatal.

**Considered and rejected: defaulting this off on Linux.** Since the lock buys nothing there,
holding it looks like pure overhead -- so the overhead was measured rather than assumed. The
server holds ~180 file descriptors against a limit of 524,288, and the lock costs 4 more per
entombed player for the length of the grace period only. Roughly 131,000 players would have to
die inside a single grace window to exhaust it.

So the setting is dead weight on Linux, not a cost. Flipping the default per-platform would buy
four descriptors out of half a million while introducing a behavioural difference between a
Windows and a Linux server that someone eventually has to debug. Left alone deliberately.

The measurement's real value is the reminder that **the move, not the lock, is what protects the
purge on Linux** -- so the advisory-lock finding is not a defect waiting on a heavier fix.
Wrapping this in `flock`, or mounting with mandatory locking, would be solving a problem that
does not exist.

> The key name misleads. Every sibling in the `wipe.` namespace names something that gets wiped
> -- `wipe.playerData`, `wipe.advancements`, `wipe.pets` -- so `wipe.lockFiles` reads as "wipe
> the lock files" rather than "lock the files". Observed live: the author misread his own key.
> Not renamed, because the confusion is cosmetic and a rename costs a migration.

Locks must be released before any move or delete — on Windows a file this process holds open cannot be deleted, so skipping the release turns every hard delete into an `AccessDeniedException`. Released explicitly on `SERVER_STOPPING` rather than relying on process exit.

### 4.10 Pardon must survive the respawn (**J**) — found in testing

Restoring the files is **not sufficient**, and the original design was wrong about this.

A pardoned player's restored data still has them dead (`Health: 0`), so they rejoin to a death
screen. Clicking Respawn runs `PlayerList.respawn(player, keepEverything=false, …)`, and
vanilla discards inventory **and** experience unless keepInventory reads true at that instant.
The in-memory override is keyed on `DYING_NOW`, which is populated only between `ALLOW_DEATH`
and `AFTER_DEATH` on the original death tick — a tick in a session that has already ended.

Measured on 2026-08-28: a player pardoned 1 second after entombment, with all four files
confirmed restored, respawned owning `Inventory: []` and `XpLevel: 0`. Their ender chest
survived **only** because vanilla preserves `EnderItems` across death independently. So the
headline promise of the grace period was false in practice.

> **This was not a graveyard-rework regression.** The pre-graveyard design had the identical
> hole — pardon cancelled the purge, files stayed in place, player rejoined dead, respawned,
> lost everything. Only in-game testing could surface it, which is why "mixins verified
> applied" was never a sufficient bar to ship on.

**Fix:** a third ledger state, `PARDONED`. Pardon restores the files and keeps the entry
rather than deleting it; the join gate admits `PARDONED` without sweeping; a `@Redirect`
around the `PlayerList.respawn` call in `handleClientCommand` raises a flag that
`GameRulesMixin` honours, then retires the entry.

Redirecting the *call* rather than injecting at the method head is deliberate:
`handleClientCommand` can execute twice (the netty thread aborts through
`ensureRunningOnSameThread`, then the server thread runs it properly), and a flag raised on the
aborted pass would never be cleared. The flag is global — `GameRules#get` has no player
context — so it must not outlive the single call it wraps, or an unrelated player dying in the
same window would wrongly keep their items. A `PARDONED` player who rejoins *alive* (pardoned
after `/nr admin lock`, never dead) has no respawn coming, so the entry is retired on join.

Verified: `XpLevel: 35`, full inventory, all four armour pieces (26.x keeps worn gear in an
`equipment` compound, not `ArmorItems`), ender chest intact, ledger emptied.

### 4.11 The empty-pause precondition (**K**) — found in testing

`pause-when-empty-seconds` stops the server ticking once the last player leaves. The hard
delete runs on the tick loop, and the moment it comes due is precisely the moment a dying
player has just left — very often as the last player online. Measured on 2026-08-28 with the
vanilla default of 60: grace expired at 18:05:14 and the purge had still not run three minutes
later. The lockout then expired with `wipePending` still true, violating §4.4b's invariant.

The join-gate backstop (§4.7) caught it and cleared everything before admitting the player, so
no data leaked — which is exactly the durability role §4.7 describes.

**Rejected fix:** overriding `pauseWhenEmptySeconds()` to return 0. It works (`ifle` skips the
whole pause block when ≤ 0, and `DedicatedServer` overrides the method so the mixin must target
*that*, not the `MinecraftServer` base). But it silently reverses a setting the admin chose,
usually to save CPU on a host running several servers.

**Chosen fix:** read the setting at startup and, if it is non-zero, **stand down entirely** —
a loud multi-line `ERROR` banner, `/nr info` and `/nr admin list` reporting DISABLED, and no
death interception, no lockout enforcement, no file movement at all. Half-working is the one
outcome worse than off: taking a player's inventory and then failing to run the lockout is
strictly worse than never having touched them. Standing down also *undoes* pending state —
restoring anyone already in the graveyard and clearing the ledger — so flipping the setting
mid-lockout cannot strand files with nothing left to purge or return them. Documented as a
hard requirement in the README.


### 4.12 Hardcore (**L**) — verified 2026-08-29

`hardcore=true` does exactly two things in 26.2: it locks difficulty to Hard, and on respawn it
runs an unconditional `setGameMode(SPECTATOR)`. That branch tests `isHardcore()` and nothing
else — there is no "has died" flag anywhere. The spectator state persists only because it is
written to `playerGameType` in playerdata afterwards.

**A normal nixReaper death never reaches it.** `shouldBlockRespawn` cancels the respawn packet
at HEAD for the whole death screen, so `PlayerList.respawn` and the spectator conversion are
both unreachable while a player is `DYING`. They are held, kicked, wiped, and return in
survival. Vanilla's penalty is *bypassed*, not disabled — hardcore hearts and the difficulty
lock remain.

The player also cannot escape into spectating during the hold: the button is inert for the
entire window, not merely at the end.

**The pardon was the one hole.** A pardoned player respawns for real, so the conversion did
fire — handing them everything back and then locking them out of using it permanently. Fixed
by redirecting the `setGameMode` call and skipping it for a pardoned respawn.

> `hardcore` is baked into `level.dat` at world **creation**. Changing `server.properties` does
> nothing to an existing world; testing it requires regenerating.

### 4.13 Auto-respawn, and the regression it caused

A pardon restores the player *as they were*, which includes being dead — so they arrive at a
death screen. On hardcore that screen's button reads "Spectate world", which states the
opposite of what happened. The label cannot be changed: the client renders it from the single
`isHardcore` flag in the login packet, and the only way to alter the word would be to lie about
hardcore at login, which would also strip the hardcore hearts.

So the screen is removed rather than relabelled — the server respawns the player itself, one
tick after they join.

**The first implementation was wrong and cost a tester their inventory.** It called
`PlayerList.respawn` directly. Vanilla does three more things immediately after that call:
reassigns the packet listener's `player` field to the NEW entity, `resetPosition()`, and
`restartClientLoadTimerAfterRespawn()`. Skipping them left `connection.player` pointing at the
old, removed entity — so interactions were validated against a corpse, and on disconnect
`PlayerList.remove()` **saved that stale entity over the real playerdata**. 84 levels, worn
armour and a full ender chest were destroyed, and it looked perfect until the save happened.

The fix stopped reimplementing: the server now sends the connection a synthetic
`PERFORM_RESPAWN` packet. Vanilla runs its own complete sequence, and the keepInventory and
no-spectator redirects hang off that same method, so they apply for free. Auto-respawn is
literally "the server presses the button for you".

> Same lesson as the original graveyard race, in the opposite direction: vanilla does things in
> **sequences**, and reimplementing one step while assuming the rest is incidental breaks it
> silently. Both fixes came from letting vanilla run its own path.

### 4.14 Vocabulary (**M**)

The mechanics were sound but the words were technical. The player-facing model is now:

| Concept | Term |
|---|---|
| The lockout | **Purgatory** — a holding place, not a punishment, and it ends |
| Pardon inside the grace period | **Resurrected** — you return as you were |
| Purgatory expiring | **Reincarnated** — you return, but as no one |
| Pardon after the grace period | **released early** — mercy, not resurrection |
| What is at stake | **Inventory, Ender Chest & XP** — always plain |

The split is deliberate: poetic for the *state*, literal for the *stakes*. A player should never
have to decode a metaphor to find out what they are losing.

*Reincarnated* rather than *regenerated* — Regeneration is already a Minecraft potion effect,
and it connotes restoration, which is what Resurrection does. Reincarnation is the exact
opposite and the correct word: new life, nothing carried over.

`/nr admin resurrect` is an **alias** for `pardon`, not a replacement. One behaviour, quietly
downgrading when the remains are gone, reachable by whichever word the admin thinks in.

#### Reincarnation greeting
Purgatory ending used to be silent — the player simply appeared holding nothing, which reads as
a bug rather than the mechanic. They now get a greeting plus a random line from
`config/nixreaper/reincarnation.txt` (plain text, one phrase per line, reloadable, ships with
ten). **The file is the only switch**: empty it and the greeting appears alone. A separate
enable flag would be one more thing to explain and to desynchronise.

### 4.15 The death screen (26.2)

`DeathScreen.visitText` lays out three items at fixed offsets — title at y=30, cause of death at
y=85, score at y=100 — plus two buttons. "Game Over!", "Score:", "Respawn"/"Spectate world" and
"Title Screen" are all client-side translation keys and are **not** server-controllable. The
score's value is an int the client stringifies itself, so no text can be smuggled through it.

The one exception is the death message: `ServerPlayer.die` builds a
`ClientboundPlayerCombatKillPacket` around `CombatTracker.getDeathMessage()`, and the client
renders that Component verbatim. `ServerPlayerMixin` modifies that argument, giving
`message.deathScreen`.

**It must stay one line.** y=85 and y=100 leave a single line of headroom and there is no
`font.split` on that path, so anything that wraps collides with "Score:".


---

## 5. Commands (**E**)

Root `/nixreaper`, alias `/nr`.

### Player (open to everyone)
```
/nr                         # alias for info
/nr info                    # what happens when you die: clock, duration,
                            #   what gets wiped — human-readable
/nr status                  # your own lockout state
```

### Admin (`bypass.permissionLevel`, default op 4)
```
/nr admin config list             # all keys + current values
/nr admin config get <key>
/nr admin config set <key> <val>  # writes through immediately, no restart
/nr admin reload                  # hot-reload config from disk
/nr admin status <player>         # includes whether a purge is still pending
/nr admin list                    # everyone currently locked out
/nr admin pardon <player>         # lifts lockout AND cancels pending purge;
                                  #   inside the window = full restore (§4.6)
/nr admin lock <player> [dur]     # apply the penalty manually
/nr admin purge <player>          # force the pending purge immediately
```

`config set` and `reload` are both needed: `set` for live tweaks, `reload` for admins who hand-edited the JSON.

### Config keys

| Key | Type | Default | Meaning |
|---|---|---|---|
| `wipe.playerData` | bool | `true` | Purge `players/data/<uuid>.dat` (+ `.dat_old`) |
| `wipe.advancements` | bool | `true` | Purge `players/advancements/<uuid>.json` |
| `wipe.stats` | bool | `true` | Purge `players/stats/<uuid>.json` |
| `wipe.graceMinutes` | int | `5` | How long an entombed player stays restorable before the hard delete (**H**). Must stay below the lockout duration — §4.4. |
| `wipe.lockFiles` | bool | `true` | Hold an exclusive OS lock on graveyard files for the whole grace period — §4.9 |
| `lockout.minutes` | int | `360` | Lockout duration in real-time minutes (6h). **Min = `wipe.graceMinutes` + 1, i.e. 6 at defaults** — §4.4 |
| `lockout.deathScreenSeconds` | int | `15` | Death-screen hold; timer starts at expiry or manual disconnect |
| `message.death` | string | — | Disconnect message on death |
| `message.rejoin` | string | — | Blocked-join message |
| `bypass.permissionLevel` | int | `4` | Op level exempt from the penalty |

Config + ledger persisted as JSON in the world folder.

---

## 6. Technical Notes (MC 26.x Fabric, server-side)

### 6.1 Hook points
- **Keep-inventory override:** intercept the death/drop decision and force the keep branch without reading or writing the real gamerule.
- **Death screen hold:** ignore the client's respawn request for `deathScreenSeconds`; detect manual disconnect during that window to stamp `lockoutStartsAt` early.
- **Join intercept:** the vanilla player-manager join check already returns a disconnect reason (or none) and is where vanilla's own ban check lives — the natural seam. Compute the countdown there, and enforce §4.4 there.
- **Entomb:** `@Inject(method="remove", at=@At("TAIL"))` on `PlayerList` — the first instant the files are safely takeable. See §6.2.
- **Hard delete:** tick-loop check against `purgeAt`, `wipe.graceMinutes` after entombment.

### 6.2 Ordering — resolved by a hook, not a delay
Vanilla flushes **three** files on player removal: playerdata, advancements, and stats. An immediate purge raced all three, and a partial win was the dangerous outcome — one file recreated by vanilla while the others succeeded, so **G** fails silently while appearing to work.

The original answer was to wait 5 minutes and assume the writes had landed. **Superseded.** Disassembling 26.2 shows the ordering is knowable exactly:

- `PlayerList.remove(ServerPlayer)` calls `save(player)` near the top.
- `PlayerList.save(ServerPlayer)` calls `PlayerDataStorage.save` → `ServerStatsCounter.save` → `PlayerAdvancements.save`.
- All three are **fully synchronous** — `NbtIo.writeCompressed` + `Util.safeReplaceFile`, and two plain `Files.newBufferedWriter` calls. No `Util.ioPool()`, no executor, no `CompletableFuture`.
- `remove` compiles to a **single `return`** (offset 235), so `@At("TAIL")` is unambiguous and cannot be skipped by an early-exit path.

So at the tail of `remove`, every file exists in its final state and the player is already out of the list — nothing will write them again while offline. Entombing there is correct by construction rather than by timing, which is strictly stronger than the old delay *and* far faster.

**Re-verify these three facts on every MC version bump.** If Mojang ever moves a player save onto the IO pool, the guarantee evaporates silently — the entomb would move a file that is about to be rewritten, and the rewritten copy would survive in the live folder looking like nothing went wrong.

### 6.3 ✅ `.dat_old` confirmed present on 26.x
`Util.safeReplaceFile` rotates `<uuid>.dat` to `<uuid>.dat_old` on every save, both in
`<world>/players/data/`. Confirmed in testing: a player's first death entombs **3** files (no
prior save to rotate), every subsequent death entombs **4**. Whether the server would actually
*read* `.dat_old` back if `.dat` were missing remains unverified and does not matter — both are
taken, so there is nothing left to fall back to either way.

### 6.4 Persistence
Ledger record per UUID:
```
{ deathAt, lockoutStartsAt, state, durationMillis, graceMillis,
  wipePending, graveyardAt, entombRetryAt, purgeAt, deathReason, name }
```
- `state`: `DYING` (death screen, timer not started) → `LOCKED` → cleared on expiry.
- `purgeAt` — absolute timestamp, so a restart can reschedule or immediately run an overdue purge.
- **Snapshot the duration at time of death** so `config set lockout.minutes` doesn't retroactively re-time everyone already locked out.
- All timestamps are wall-clock epoch millis (**C**).
- Store in the world folder — never memory-only, or a restart is a free pardon.

### 6.5 Build
Fabric 26.x, Java 25, loom-no-remap recipe per suite conventions. Server-side only → **no client mixins**, and the Fabric API dependency may be avoidable entirely (verify before adding the badge).

---

## 7. Competitive Position

Surveyed Modrinth + CurseForge 2026-08-20. This combination does not exist.

| Mod | DL | Server-only | 26.x | Overlap |
|---|---|---|---|---|
| Panda Death Ban | 2,402 | ✅ | ✅ 26.2 | C + D; no wipe, no commands |
| SimpleDeathBans | 809 | ❌ client req | 26.1 | C, + soul links / rituals |
| Simple Death Ban | 350 | ✅ | 1.21.11 | C, fixed 5 min |
| Customizable Death Ban | 1,221 | ❌ | — | C + revive totem |
| Delayed Respawn | 796 | ✅ | **dead 2023** | C only — abandoned |
| Hardcore Revival | 9.0M | ❌ client req | ✅ 26.2 | different genre (K.O./revive) |

**Differentiators, ranked:**
1. **Ender chest wipe.** Searched both stores — **nothing does this.** It's vanilla's safe-deposit box, which is why every existing death-penalty mod is toothless: players pre-stash gear and death costs them a stone pickaxe. Headline feature.
2. **True clean slate.** Not "you lost your stuff" — the server forgets you existed. No competitor wipes advancements or stats.
3. **In-game admin command tree + hot reload.** Competitors are all edit-JSON-and-restart.
4. **The pardon mercy window.** A time-limited undo for bogus deaths; no competitor offers one.
5. **Server-side only.** The 9M-download giant requires a client install; this doesn't.

*(An MC-days clock was previously listed here as a differentiator. Dropped with **C** — real-time only.)*

**Positioning:** *the mod that closes the ender chest loophole* — NOT "another lives mod." That tier is saturated and caps around 2k downloads.

---

## 8. Build Status (2026-08-29)

Project: `IdeaProjects/MC_Code/nixReaper/26.2` — single-loader Fabric, `environment: "server"`, Java 21, loom 1.16.0-alpha.13, MC 26.2 / loader 0.19.3 / fabric-api 0.153.0+26.2. Jar: `build/libs/nixreaper-0.1.1.1_MC-26.2.jar`.

### Verified
- Compiles clean; jar builds.
- Dev server boots, mod initialises (`nixReaper ready -- lockout 360 min, grace 5 min`).
- **All four mixins confirmed applied** with correct descriptors:
  `GameRulesMixin` → `world.level.gamerules.GameRules`, `PlayerListMixin` → `server.players.PlayerList`,
  `ServerGamePacketListenerMixin` → `server.network.ServerGamePacketListenerImpl`,
  `ServerPlayerMixin` → `server.level.ServerPlayer` (death-screen message, §4.15).
- **The entombment hook resolves.** `PlayerListMixin` now carries a second injector,
  `@Inject(method="remove", at=@At("TAIL"))`. With `defaultRequire: 1` a target that failed to resolve would throw
  `InvalidInjectionException` when `PlayerList` loads during boot — it doesn't, and the class is loaded before
  `Done (…)`. Disassembly independently confirms `remove` has a **single** `return` (offset 235), so `TAIL` is
  unambiguous and has no early-exit path that could skip it.

### ✅ Verified in game
Two full behavioural passes against a real client: **Windows 2026-08-28** (throwaway server,
later deleted) and **Linux/Fedora 43 2026-08-29** (`minecraft@10.10.40.11`, hardcore world).

- nothing drops; death screen held 15s; manual disconnect starts the clock early (**F**)
- entombment fires at the removal instant; live dirs measured genuinely empty
- 4 files entombed including `.dat_old` (3 on a player's first death — nothing to rotate yet)
- file locks **mandatory on Windows, advisory on Linux** (§4.9), hard delete unblockable on both
- both countdowns on the rejoin screen; the message flips correctly when the remains are gone
- hard delete on schedule on an empty server; lockout expiry; entry self-cleaning
- join-gate backstop recovering a missed purge
- **the ender chest wipe** — items surviving three deaths, cleared only by the purge
- resurrection returning inventory, worn armour, ender chest and XP, and **surviving a
  disconnect/reconnect cycle** (the check bug 6 defeated)
- auto-respawn at 1 tick, landing at the player's **bed/anchor**, never spectator on hardcore
- pardon after a **mid-grace restart**, off re-acquired locks from a disk-loaded ledger
- stand-down leaving deaths fully vanilla (**K**)
- live `config set` in both directions, and `reload` from disk

### Still not verified
- **Anything multiplayer.** The keepInventory override is a global flag (`GameRules#get` has no
  player context), so an exempt player dying during another player's pardoned respawn would
  wrongly keep their items. Two deaths in one tick is also unexercised. Structurally impossible
  to test solo.
- A purge **firing** on schedule in a restarted JVM. Lock re-acquisition, ledger survival, grace
  not drifting, and pardon-after-restart are all confirmed; the delete itself is not.
- The crash-recovery branches: a `DYING` entry with no start stamp, and entombing someone caught
  mid-flight.
- Entombment retry after a genuinely blocked move, and a failing restore.
- Stand-down **with pending entries** (restoring people out of the graveyard on boot).
- `/nr admin purge`, `/nr admin lock`, `/nr status`, and bad-config clamping.
- The self-pardon guard has never fired — it is unreachable until `bypass.permissionLevel` is
  split, since anyone who can pardon is currently also exempt from dying.

### 26.2 API drift found during implementation
Worth recording — several of these are new since the 26.1 single-loader mods:
- `GameRules` moved to `net.minecraft.world.level.gamerules` and split into `GameRules` / `GameRuleMap` / `GameRule<T>`.
  Reads are now `GameRules.get(GameRule<T>)` returning `T`; the rule constant is `GameRules.KEEP_INVENTORY`
  (was `RULE_KEEPINVENTORY`). There is no `getBoolean`.
- `Level.getGameRules()` is gone; `ServerLevel.getGameRules()` exists.
- `PlayerList.canPlayerLogin` now takes `(SocketAddress, NameAndId)` — **not** `GameProfile`.
  `NameAndId` is a record with `id()` / `name()`.
- **Integer permission levels are gone.** `CommandSourceStack.permissions()` and `ServerPlayer.permissions()` return a
  `PermissionSet`; check with `hasPermission(Permission)` against `Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER`.
  nixReaper keeps `bypass.permissionLevel` as 0-4 in config and maps it internally, so admins keep the vocabulary they know.
- `authlib` `GameProfile` no longer exposes `getName()` / `getId()`. Use `player.getName().getString()`.
- `disconnect(Component)` lives on `ServerCommonPacketListenerImpl`.
- Mixin `compatibilityLevel: JAVA_21` works when compiling at release 21 (the suite's JAVA_25 note applies to
  Architectury builds compiled at 25).

---

## 9. Next Steps

1. Verify CurseForge slug `nixreaper` manually.
2. Scaffold Fabric 26.x server-side project.
3. Implement keep-inventory override + ledger + death-screen hold + timer-start rule (§3.1).
4. Implement the delayed purge + the §4.4 constraint validators + `wipePending` enforcement — **verify §6.3 `.dat_old` first.**
5. Implement join intercept + countdown, then the command tree.

### Test matrix
- **Happy path:** die → sit the 15s → disconnect → wait out lockout → rejoin → confirm empty inventory, empty ender chest, zero XP, **zero advancements, zero stats, empty recipe book**.
- **§4.4 constraint:** `config set lockout.minutes 3` with a 5 minute grace → confirm rejection naming both values. Then `config set wipe.graceMinutes 10` against a 6-minute lockout → confirm rejection in that direction too.
- **§3.4 offline countdown:** die → stop the server → wait past the lockout → start → confirm the player can rejoin immediately.
- **§4.5 restart:** die → restart the server inside the 5-minute window → confirm the purge still happens.
- **§4.2 whitelist:** die on a whitelisted server → confirm rejoin works after the lockout.
- **§4.6 pardon:** pardon inside the window → confirm FULL restoration (gear, XP, advancements, stats) and the correct feedback string. Pardon after the window → confirm lockout lifts, data stays gone, feedback says so.

---

## 10. Bugs found in testing (2026-08-28)

All four found by driving a real client against a throwaway server; none were reachable from
"it compiles and the mixins applied". Fixed in `0.1.0.3` / `0.1.0.4`.

| # | Severity | Bug | Root cause | Fix |
|---|---|---|---|---|
| 1 | **High** | Pardon restored the files, then the respawn destroyed inventory + XP. Ender chest survived only by vanilla accident. | keep-inventory override keyed on `DYING_NOW`, empty by respawn time | `PARDONED` state + `@Redirect` around `PlayerList.respawn` (§4.10) |
| 2 | **High** | Hard delete never ran on a paused (empty) server | sweep lives on the tick loop; `pause-when-empty-seconds` stops ticking exactly when the purge falls due | stand down and refuse to run (§4.11) |
| 3 | Medium | Player told "permanently erased" while the files were still on disk | message flipped when `purgeAt` *passed*, not when the delete *succeeded* | `DataState` enum |
| 4 | Low | `/nr admin status` reported "in graveyard" after the purge | `entombed()` is `graveyardAt > 0`, never cleared, and was tested before `wipePending` | `DataState` enum |
| 5 | Low | The respawn redirect hooked **both** `PlayerList.respawn` call sites, so a `PARDONED` player returning from the End consumed their pardon | no `ordinal` on the `@Redirect` | `ordinal = 1` (the death respawn) |
| 6 | **High** | Auto-respawn saved a **stale entity over live playerdata**, destroying inventory, armour and XP. Introduced in `0.1.0.7`, fixed in `0.1.0.8`. | called `PlayerList.respawn` directly and skipped the three things vanilla does after it, leaving `connection.player` on the removed entity | replay a `PERFORM_RESPAWN` packet instead (§4.13) |
| 7 | Medium | The kick screen said "Everything you owned has been erased" **before the player was even entombed**, and stayed wrong for the whole grace period | hardcoded assertion in the default `message.death` | `%grace_line%`, which reads `dataState()` |

3, 4 and 7 share one root cause: **user-facing text asserting facts about the data instead of
deriving them from it.** Three separate bugs of identical shape. `Ledger.Entry.dataState()` is
now the single source of truth (`LIVE` / `ENTOMBED` / `ERASED`) and every message, `status` and
`list` reads it. The rule worth keeping: *any string that states something about the data must
be computed from the data, never written as a constant.*

Bug 6 is the one to dwell on. It was **mine, introduced during this session's fixes**, and it
did real damage — a player's inventory and 84 levels, gone. It also passed an immediate
verification: reading the player's state right after the respawn showed everything correct,
because the corruption only materialised when the stale entity was written to disk on
disconnect. That is why the check that matters is **read the state again after a
disconnect/reconnect**, not just after the event.

### Process note
The mod sat for 8 days at "builds clean, all 3 mixins confirmed applied" and was one decision
away from being published. Two of these four bugs are severe enough to make the mod's headline
feature not work, and **neither is detectable without a client**. Mixin verification says the
code is reachable, not that it is right.

### Not a bug, but worth fixing
`bypass.permissionLevel` gates the death exemption **and** `/nr admin` with the same value, so
anyone who can pardon is also immune to dying. This makes the mod awkward to test (the tester
must stay non-op and drive admin commands over RCON) and forces server owners to choose between
having moderators and having moderators who can die. There is also no value meaning "nobody is
exempt" — `0` means *everyone* bypasses, not no one. Worth splitting into two settings.

---

## 11. Reclaiming tamed animals (**N**)

Wiping a player's files takes their inventory, ender chest and XP. It does not take a donkey
standing at spawn with a chest full of diamonds -- and that is enough to defeat the whole
mechanic. *"Nothing of your old life remains, except this stash I parked somewhere safe."*

### 11.1 Why a UUID cannot answer the question

A player's UUID does not change when they are reincarnated. So "is this animal still yours?"
is unanswerable from ownership alone: a wolf tamed before you died and one tamed ten minutes
ago in your current life look **identical**.

The first design tracked *reincarnated players* in a set and untamed anything they owned. It
was wrong for exactly this reason, and the flaw surfaced as a question rather than a bug --
*"what if I have been reincarnated once already, then log off and come back to a pet I tamed
since?"* The set would have freed it.

**Each life gets an ID instead.** Taming stamps the animal with the ID of the life that claimed
it, and the animal is only yours while the two match:

| Situation | Animal's stamp | Your current life | Result |
|---|---|---|---|
| Tamed in life 1, you are in life 2 | `A` | `B` | destroyed |
| Tamed in life 2, you log out and back in | `B` | `B` | still yours |
| Tamed in life 2, you reincarnate again | `B` | `C` | destroyed |

The animal carries its own provenance, which is what makes it correct for a horse that has sat
in an unloaded chunk for a month.

### 11.2 Where each ID lives

**On the animal:** custom NBT via `addAdditionalSaveData` / `readAdditionalSaveData`. It travels
with the entity in chunk data, so it survives restarts and is still right for animals nobody has
visited in weeks. Verified to round-trip through `/data get` and through full server restarts.

**On the player: in a file the mod owns**, not in playerdata. This was decided twice, the second
time correctly. The sweep runs against loaded animals whose owner may be offline, in another
dimension, or -- immediately after a reincarnation -- may have no playerdata at all, because the
purge deleted it. A lookup that needs the player present cannot answer at the moment it matters
most.

It also gives resurrection the right behaviour for free: the ID is rotated **only** when the
remains are destroyed, so a resurrected player is still living the same life and their animals
never notice.

### 11.3 Destroyed, not released

Releasing was the first instinct -- the animal survives as a wild one, tameable again, the bond
broken but the creature spared. It is wrong here for two reasons.

For a wolf it is barely a penalty: the animal is standing right there and one bone undoes it.
And for a chest animal it does not close the loophole at all -- a released donkey still holds
its cargo. Dropping the cargo instead has the same hole with extra steps, because anything on
the ground can be picked up.

So the animal and everything on it are destroyed together. That also removed a whole accessor
mixin: nothing ever has to reach into a horse's `protected` inventory to empty it.

> This is a harsher call than it looks -- bred stats, names and years of work go with it. It was
> made deliberately: *"this is hardcore, the whole point is to not die, and if you do you lose
> everything."*

### 11.4 Three class trees, three toggles

Ownership is not one mechanism in Minecraft, and the config mirrors that rather than fighting it:

| Config | Covers | Mechanism |
|---|---|---|
| `wipe.pets` | wolves, cats, parrots | `TamableAnimal` -- tame flag, sitting, owner reference |
| `wipe.livestock` | horses, donkeys, mules, llamas, camels | `AbstractHorse` -- its own tame flag and inventory |
| `wipe.foxes` | foxes | two independent trust slots |

**Horses do not extend `TamableAnimal`.** Missing that would have left the single largest
loophole intact, since a chest animal is the stash worth parking.

`tameWithName` is the hook for horses rather than `setOwner`, because `readAdditionalSaveData`
assigns the owner field directly and never calls it. Hooking something that ran on load would
re-stamp the animal with the *current* life every time its chunk loaded, and it would never be
reclaimed -- a bug that would have looked like the feature simply not working.

**Foxes needed their own rule, twice over.** Trust is not ownership and it is not exclusive: a
fox holds two independent slots, filled by whoever fed each parent, so one player's death must
not empty the other's slot. Vanilla's `clearTrusted` empties both at once, which is precisely
the behaviour to avoid, and it is `private` -- hence `FoxAccessor` reaching the two
`DATA_TRUSTED_ID` accessors directly so each can be cleared alone.

The stamps are keyed by **trusted UUID, not by slot number**. Vanilla shifts entries between the
two slots as trust changes, so a positional record would attach the wrong life to the wrong
player the first time that happened. Confirmed in testing: a dual-trust kit stored its two
stamps in the opposite order to the `Trusted` list, and both still resolved correctly.

The load-time trap that caught horses does *not* apply here. `Fox.readAdditionalSaveData`
restores trust straight from `TRUSTED_LIST_CODEC` into the data slots and never calls
`addTrustedEntity`, so hooking that method stamps only genuine new trust. Verified against the
26.2 bytecode rather than assumed, because the horse version of this mistake would have been
invisible.

The first implementation stopped there: forget the player, never destroy the fox, on the
reasoning that *a fox that tolerated you was never yours to lose*. That was wrong for the same
reason releasing a donkey was wrong -- it left a fox farm standing through a death, which is a
kept herd surviving in everything but name. The rule now is:

> After stale trusts are cleared, if **no living player trusts the fox**, it is destroyed along
> with whatever is in its mouth. If any surviving trust remains, only the stale slots are cleared
> and the fox lives.

This generalises past the two-player case rather than special-casing it. Two trustees who have
both died leave no surviving trust, so the fox goes -- and it converges the same way if they die
at different times, since the second death makes that player the sole remaining trustee. An
*unstamped* trust counts as surviving, so a fox trusted before the feature existed is never
destroyed by it.

### 11.5 Two guards against catastrophe

Both exist to stop the feature doing something irreversible on a misunderstanding:

- **No stamp, no action.** An animal without one was tamed before the feature existed. Acting on
  a missing stamp would delete every pet on the server the first time the sweep ran.
- **Unknown player, no action.** If we have never seen the owner, the lookup returns null rather
  than a mismatch. Otherwise every animal belonging to anyone who had not logged in since the
  feature landed would die the moment its chunk loaded.

### 11.6 The sweep

Every `wipe.petsCheckIntervalTicks` (default 20 -- one second) across all loaded entities. Cheap
per entity: a type check, a null check, a string comparison.

Frequent on purpose. The loophole is short-range -- park a loaded animal near spawn, die, and
collect it on the walk back -- so a slow sweep leaves a window in which exactly that works.

Deletion is **lazy by design**: an animal in an unloaded chunk is checked the moment someone
loads it. No world scanning, no force-loading, no registry to go stale. Confirmed in testing --
animals left in an unloaded chunk died as soon as the chunk came back.

### 11.7 The grace period was still open

Destroying the animals when the remains are destroyed closes the loophole at the *end* of the
grace period. During the grace period itself the owner is offline, unable to defend anything,
and the animals are still theirs. Four separate ways in, all now closed:

| Vector | Closed by |
|---|---|
| Open the chest and empty it | `Mob.interact` returns FAIL |
| Keep a screen open from before the death | mount menu's `stillValid` returns false; `ServerPlayer` closes it on its next tick |
| Stay mounted and ride it away | the sweep ejects passengers |
| Kill it and take the drops | `hurtServer` blocked on both class trees |

Blocking **all** damage rather than only player attacks matters twice over: a wandering zombie
killing an unattended donkey scatters exactly the same cargo, and it would destroy something a
resurrection is meant to hand back intact.

The `stillValid` approach is worth noting as a pattern. The alternative was hunting for which
players had which screen open and closing them by hand; instead the menu answers the question
honestly and vanilla does the closing. It cannot drift out of sync with the ward, because it
*is* the ward.

**The ward begins at death, not at entombment.** Keying it on `wipePending` left a fifteen-second
window during the death screen. Small, and hard to exploit without knowing a death was coming --
but "you had to be quick" is not a property worth designing in.

### 11.8 Verified

With two accounts, on Linux:

- one player's death rotated only their own ID and destroyed only their animals; the other
  player's were untouched
- animals in unloaded chunks died when the chunk next loaded
- a donkey's 32 diamond blocks were destroyed with it and did not hit the ground
- a second player, mounted with the chest open at the moment of death, was ejected and locked
  out **within one second**, and could not reopen it
- damage refused from a server command, proving the block covers every source
- resurrection lifted the ward immediately and preserved the animals -- the same `/damage`
  command was refused, then applied a second later
- stamps survived full server restarts, not merely chunk unloads

### 11.9 Not covered

Villager reputation (`GossipContainer`, keyed by player UUID on each villager) and trial vault
reward tracking (`VaultServerData.rewardedPlayers`) both outlive a reincarnation. Vaults are the
sharper of the two: a "brand-new" player finds a vault they cleared in a past life refuses them,
with nothing to explain why.

Other mods storing their own per-player data are untouched by any of this.

## 12. What the world remembers

Player files are not the only place a UUID is kept. A sweep of the 26.2 bytecode for classes that
persist player UUIDs turned up six, of which three were worth acting on.

| Record | Where | Verdict |
|---|---|---|
| `VaultServerData.rewardedPlayers` | `Set<UUID>` on each vault | **handled** |
| `GossipContainer.gossips` | `Map<UUID, EntityGossips>` on each villager | **handled** |
| villagers themselves | the trading hall problem | **handled** |
| `Scoreboard.playerScores` | one shared file, keyed by **name** | left alone -- reachable, but usually the server's own machinery |
| `CustomBossEvent.players` | `Set<UUID>` in level.dat | left alone -- admin configuration |
| `AngerManagement.angerByUuid`, `Raid.heroesOfTheVillage` | warden / raid state | left alone -- both expire on their own |

Vanilla already clears neutral-mob anger via `NeutralMob.playerDied`, so that needed nothing.
`ThrownEnderpearl.findOwnerIncludingDeadPlayer` deliberately keeps a pearl bound to a dead
player, which is a genuine cross-life link and left as a curiosity.

### 12.1 Vaults: purge the set, not the answer

The vault asks whether a player is rewarded in two places, and only one of them can usefully be
intercepted. `tryInsertKey` calls `hasRewardedPlayer`; `VaultSharedData` calls
`getRewardedPlayers().contains(uuid)` to decide who counts as a nearby player at all. The second
runs first, and a rewarded player is not "connected", so the vault stays `inactive` and
`tryInsertKey` bails at its very first check -- `canEjectReward(config, state)` -- long before the
reward check is reached.

Overriding the answer to `hasRewardedPlayer` therefore changed nothing observable. **Found by
testing; reading the method name alone would never have shown it.** Both paths read the same set,
so the purge happens there. Removing the UUID also keeps vanilla's 128-entry cap honest -- dead
entries would push live ones out.

### 12.2 The null field that made a feature unliftable

`VaultBlockEntity.serverData` is **null while `loadAdditional` runs**: the field is assigned in
the constructor after `super()`. Decoding stamps straight into it silently dropped them, and the
next save wrote the empty map back, erasing them from disk as well.

The failure mode was worse than losing the feature. With no stamp nothing is judged stale, so a
vault that survived one restart barred its looters **permanently** -- the exact penalty this
exists to lift, made unliftable. It passed every test because no test restarted between looting
and checking.

Load now parks the raw string; `BlockEntity.setLevel` flushes it once the object exists. That
hook is on the base class because `setLevel` is *inherited*, and Mixin cannot target an inherited
method through a subclass -- it fails outright and took the server down, which is how it was
found.

> Two lessons, both already in §10 and both re-earned here. **`data get block` serialises the
> block entity from memory on demand**, so seeing a tag there proves it is in RAM, not that it
> ever reached disk -- the region file had to be read directly. And after theorising the cause
> twice and being wrong twice, one log line answered it immediately.

### 12.3 Villagers: hearsay must not be fatal

Reputation is answered on read rather than swept -- `getReputation` is the single path, and
sweeping would rebuild a map per villager per sweep. Stale reputation reads as zero.

**Stale gossip is dropped before new gossip is recorded.** Reporting zero while leaving the entry
in place meant the next trade re-dated it and handed a whole past life's standing back: 23 points
restored for one wheat sold.

Destruction is reserved for the private trading hall -- villagers one player cured, bred and
levelled, otherwise waiting fully stocked for their next life. It is gated on **direct dealings
only**, tracked separately from the stamps:

`GossipContainer.transferFrom` merges straight into the entry map and never calls `add`, so
`add` is the mark of a real encounter -- a trade, a cure, a punch. Gossip spreads on its own, and
destroying on reputation alone would ripple outward through a village as the news travelled,
taking villagers the player never met. Stamps still travel with transferred gossip, because a
dead life's reputation must read as nothing wherever it lands; only the *destruction* stays local.

Any living customer spares the villager, **online or not** -- the check reads `incarnations.json`,
never the player list.

> `wipe.villagers` is the most destructive setting in the mod: it removes world content rather
> than belongings, and on a shared server a hall used by one player who dies is gone for good.
> Default `true` was a deliberate call, on the grounds that a hall is built work in the same
> sense a loaded chest donkey is a stash.

