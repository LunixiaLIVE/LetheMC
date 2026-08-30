# LetheMC

Death costs you everything — **including your ender chest** — and sends you to **Purgatory**:
locked out of the server for a set amount of real time.

You leave Purgatory one of two ways. An admin can **resurrect** you, and you return exactly as
you were. Or you wait it out and are **reincarnated** — you return, but as no one.

Server-side only. Players connect with a vanilla client; nothing to install on their end.

---

## ⚠️ Required server setting

```properties
pause-when-empty-seconds=0
```

**LetheMC refuses to run if this is anything else.** It logs a block naming what is wrong and how
to fix it, and `/lethemc info` reports the mod as disabled. Deaths stay vanilla until you fix it —
the mod would rather do nothing than half-work.

A server with this set stops ticking once the last player leaves — which is exactly when a player
who just died has left. The purge runs on the tick loop, so the wipe would silently never happen,
players would keep everything, and nothing would tell you.

If you enable the pause while players are already in Purgatory, LetheMC hands their belongings
back and clears its records on the next start, so nobody is left in limbo.

---

## 💀 Hardcore — recommended, not required

LetheMC runs on any world. Nothing in it depends on hardcore: the death interception, the wipe,
Purgatory and the animal reclamation all behave identically on an ordinary survival server.

Hardcore is what the mod was *written around* — one life, and losing it costs everything — and it
locks difficulty to **Hard**. If your world is not hardcore you get a single note in the log
saying so, and everything works anyway.

If your world **is** hardcore, one detail is handled for you: vanilla turns a dead player into a
spectator on respawn. A normal LetheMC death never reaches that code, but a **resurrection** does,
so the conversion is suppressed for exactly that case — a resurrected player comes back in
survival, not stuck spectating with all their gear.

> [!NOTE]
> **Hardcore is written into `level.dat` when a world is created.** Setting `hardcore=true` in
> `server.properties` afterwards does *nothing* — vanilla ignores it for an existing world, and
> tells you nothing either way. Measured, not assumed: a world created with `hardcore=false`
> still reads `hardcore=0` after flipping the property and restarting.
>
> LetheMC warns about exactly this case. If your properties file asks for hardcore and the world
> is not hardcore, you get a console block explaining why — and confirming the mod is running
> normally regardless. If you never wanted hardcore, it stays quiet.
>
> To actually get it on an existing world, run **`/lethemc admin hardcore on`** — see below.

---

## Settings you do *not* need to change

Settings admins reasonably expect to matter, which do not.

### `keepInventory` — leave it alone

LetheMC does **not** require it, and setting it either way changes nothing about how a death is
handled. Nothing dropping on death is not the gamerule; the mod intercepts the *read* of
`keepInventory` for the single tick a player is dying and answers `true`, so vanilla spawns no
items. The stored gamerule is never written, and the override does not apply to anyone else, at
any other moment.

That is deliberate. Turning the real gamerule on would change the game for every player and every
other death on the server — including players LetheMC is configured to exempt — and an admin who
later turned it off would silently break the "nothing drops" promise.

### `difficulty` — only if you are hardcore

A hardcore world locks difficulty to **Hard** on its own, so `difficulty` in `server.properties`
is irrelevant there. On a non-hardcore world it behaves normally and LetheMC does not touch it.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Loaders | **Fabric** 0.19.3+ · **NeoForge** 26.2.0.7+ |
| Fabric API | 0.153.0+26.2 — *Fabric only; NeoForge needs nothing extra* |
| Java | 21+ |
| Side | Server only |

## Install

The only step whose **order matters** is hardcore. Everything else can be done whenever.

### 🆕 A brand-new server

Hardcore has to be decided **before the world is generated**, so set it first.

1. **Edit `server.properties` before the first launch:**
   ```properties
   pause-when-empty-seconds=0
   hardcore=true            # only if you want hardcore; it is optional
   ```
2. Put the LetheMC jar in `mods/` (plus **Fabric API**, if you are on Fabric).
3. **Start the server.** The world is created with whatever `hardcore` said, and you should see
   `LetheMC ready` in the log.
4. Tune it to taste with `/lethemc admin config set …`, or edit `config/lethemc/config.json`.

That's it — no restart dance, because the world was born the way you wanted it.

### 🔁 An existing server

`hardcore` in `server.properties` **cannot** convert a world that already exists, so it takes a
restart to switch it on. Do the rest first.

1. **Back up your world.** LetheMC starts taking things the first time somebody dies, and the
   grace period is the only way back.
2. **Stop the server.**
3. Set `pause-when-empty-seconds=0` in `server.properties`.
4. Put the LetheMC jar in `mods/` (plus **Fabric API**, if you are on Fabric).
5. **Start the server.** You should see `LetheMC ready`. Nobody's data is touched until they die.
6. *Only if you want hardcore:* run **`/lethemc admin hardcore on`**, then **restart** the server.
   The `level.dat` change is saved immediately, but clients read the hardcore flag when they log
   in, so hearts do not change until they reconnect to a restarted server.
7. Tune it with `/lethemc admin config set …`.

> [!TIP]
> Set `purgatory.minutes` and `wipe.graceMinutes` to something short while you try it out, so you
> are not locked out for six hours testing your own server. `/lethemc admin config set
> purgatory.minutes 5` and `/lethemc admin config set wipe.graceMinutes 2` are comfortable.

### Checking it worked

`/lethemc info` reports what a death costs and the current durations. If the mod stood down, it
says so there and names the reason in the console.

---

## What happens when a player dies

1. **Nothing drops.** There is no body to loot and no items on the ground.
2. The death screen stays up for 15 seconds so they can read what killed them. It reads
   *"...& sent to Purgatory"*.
3. They are disconnected and enter **Purgatory** for `purgatory.minutes` (6 hours by default).
4. Their inventory, ender chest, XP, advancements and statistics are taken.
5. Rejoining during Purgatory shows how long until they may return, and whether resurrection
   is still possible.
6. When Purgatory ends they are **reincarnated** — a brand-new player, greeted as such.
   The animals they tamed are destroyed at the same moment.

The Purgatory is **real time**. It keeps counting while the server is offline — a 6 hour
Purgatory started at 22:00 has expired by 04:00 whether or not the server ran overnight.

### Resurrection

For `wipe.graceMinutes` after a death (5 by default) the player's belongings still exist, and
`/lethemc admin resurrect <player>` gives back
**everything**: inventory, worn armour, ender chest, XP, advancements, stats. They rejoin and
are placed back in the world at their bed or spawn, alive, without even seeing a death screen.

Use it for deaths that were not the player's fault: a lag spike, a bad chunk load, your own
mistake.

After that window the belongings are gone for good. The same command still works, but it only
releases them from Purgatory early — they return with nothing. `/lethemc admin status <player>`
tells you which outcome you will get **before** you decide.

Admins **cannot resurrect themselves**, only other people. Otherwise the penalty is optional for
anyone holding the permission. A genuinely bogus death is still recoverable — by another admin,
or from the server console.

### Reincarnation

When Purgatory ends the player is greeted, so an empty inventory reads as the mechanic rather
than a bug:

> **You have been reincarnated.** Nothing of your old life remains.
> *Maybe this time you won't be so careless.*

That second line is picked at random from `config/lethemc/reincarnation.txt` — plain text, one
phrase per line, ten included. Edit it and run `/lethemc admin reload`; no restart. Remove every
phrase and the greeting appears on its own.

### Your animals

Reincarnation takes the animals you tamed, too. Wolves, cats, parrots, horses, donkeys, mules,
llamas and camels are **destroyed** when your old life ends — along with anything they were
carrying.

**Foxes work differently, because trust is shared.** A fox can trust two players, so one of them
dying must not cost the other their fox. When your life ends a fox simply forgets you — and if
that leaves nobody living who trusts it, the fox is destroyed too, along with whatever it was
carrying. A fox trusted by a survivor keeps living; only your trust is removed.

That last part is the point. Without it a chest donkey parked somewhere safe is a death-proof
vault, and "nothing of your old life remains" is simply untrue. Releasing the animals instead
would not help: a released donkey still holds its cargo, and dropping the cargo just puts it on
the ground where it can be picked up.

**While you are in Purgatory your animals cannot be touched by anyone.** Not ridden, not opened,
not harmed — not even by monsters. You are offline and cannot defend them, so nobody gets to
empty your donkey while you wait, and nothing can destroy something a resurrection is meant to
give back.

**A resurrection keeps them.** Only reincarnation takes them.

Animals tamed *before* this feature was installed are never touched, so adding the mod to an
existing world does not cull everyone's pets.

### The world remembers you too

Your player files are not the only place your name is kept. Three things out in the world record
it against your UUID, and all three outlive a purge unless LetheMC deals with them.

**Trial vaults forget you.** A vault you emptied in a past life opens again. This is the only one
that *costs* you something rather than granting it — without it a reincarnated player walks up to
a vault they have never opened in this life and is silently refused, with nothing on screen to
explain why.

**Villagers forget what they thought of you.** Trade discounts earned before a death do not
follow you into a life that did nothing to earn them.

**Villagers you alone traded with are destroyed.** A private trading hall — villagers one player
cured, bred and levelled, that nobody else has ever used — is that player's work as surely as a
loaded chest donkey is their stash. Left standing it waits, fully stocked, for their next life.

> ⚠️ **`wipe.villagers` is the most destructive setting in this mod.** It removes world content
> rather than a player's belongings, and it cannot be undone. Set it to `false` if that is not
> what you want.

Three rules keep it from emptying your village:

- **Only villagers you built up count** — trading with one, or curing it. Hitting a villager
  does *not* make it yours, so a stranger who punches your villagers and then dies cannot take
  them with him. Gossip also spreads between villagers on its own, so they hear about players
  they have never met; destroying on reputation alone would ripple outwards through a village
  as the news travelled.
- **Any living customer spares the villager**, whether they are online or not. A hall shared with
  someone who is still alive keeps working for them.
- **Villagers bred and left alone are never touched**, and neither is anyone you traded with
  before installing the mod.
- **While you are in Purgatory, nobody can trade with the villagers you alone built.** Otherwise
  a friend could walk your hall during the grace period, buy one item from each villager, and
  make themselves the living customer that spares the lot. Villagers anyone else already trades
  with are unaffected and stay open for business.

---

## Commands

Root command is `/lethemc`, aliased to `/lethemc`.

### Everyone

| Command | Does |
|---|---|
| `/lethemc` or `/lethemc info` | What happens when you die, current durations |
| `/lethemc status` | Your own Purgatory, if any |

### Admin

Requires the op level set by `admin.permissionLevel` (default 4). The server console always
qualifies — which is what makes a solo admin's own bogus death recoverable, since they cannot
resurrect themselves.

| Command | Does |
|---|---|
| `/lethemc admin list` | Everyone currently in Purgatory |
| `/lethemc admin status <player>` | Their Purgatory, where their data is, whether resurrection still restores it |
| `/lethemc admin resurrect <player>` | Release from Purgatory, restoring everything if the belongings still exist. **You cannot resurrect yourself** — ask another admin or use the console |
| `/lethemc admin purge <player>` | Erase their data now, skipping the grace period |
| `/lethemc admin lock <player> [minutes]` | Send someone to Purgatory manually |
| `/lethemc admin config list` | Show all settings |
| `/lethemc admin config get <key>` | Read one setting |
| `/lethemc admin config set <key> <value>` | Change one setting, saved immediately |
| `/lethemc admin reload` | Re-read the config from disk |
| `/lethemc admin hardcore <on\|off>` | Convert this world to or from hardcore. **Restart required** — see below |

### Who dies, and who can administer

These are two separate settings, so you are not forced to choose between having admins and
having stakes.

| Goal | `bypass.permissionLevel` | `admin.permissionLevel` |
|---|---|---|
| **Nobody is safe, admins can still help** *(default)* | `-1` | `4` |
| Only the owner is immune | `4` | `4` |
| Moderators can resurrect; only the owner is immune | `4` | `3` |
| Builders immune, moderators mortal | `2` | `3` |

The default means you can resurrect other people and still face Purgatory yourself, which is
usually what you want on a server whose whole premise is that death costs something. `/lethemc info`
tells players when nobody is exempt, so it is visible rather than assumed.

Both are settable in-game and take effect immediately:

```
/lethemc admin config set bypass.permissionLevel -1
/lethemc admin config set admin.permissionLevel 3
/lethemc admin reload
```

> **Upgrading?** An existing `config.json` already has `bypassPermissionLevel` written out and
> keeps whatever it says, so your current behaviour does not change. Only fresh installs get
> the `-1` default. Set it explicitly if you want the new behaviour.

---

## Converting a world to hardcore

```
/lethemc admin hardcore on
/lethemc admin hardcore off
```

Vanilla will not do this. `hardcore` in `server.properties` is read only when a world is
*created* and silently ignored afterwards — so an existing world can never be converted by
editing that file, no matter how many times you restart.

This command edits `level.dat` directly and saves it immediately. Turning it **on** also sets
difficulty to Hard and locks it, which is what hardcore means; turning it **off** unlocks
difficulty and leaves its value alone.

> [!IMPORTANT]
> **A restart is required.** Clients read the hardcore flag from the login packet, so hearts and
> the death screen keep looking the way they did until players reconnect to a restarted server.
> The file is already correct — it is only the connected clients that are behind.

It is deliberately a command rather than something driven by `server.properties`. Reading that
file would mean any server carrying a stale `hardcore=true` converted a long-running survival
world on its next boot, changing death for every player, without anyone being asked. This way it
is one world, one deliberate act, recorded in the log with the name of whoever ran it.

LetheMC does not need hardcore either way — this is here because vanilla offers no path at all.

---

## Config

`config/lethemc/config.json`, created on first start. Every key is also settable in-game
with `/lethemc admin config set`.

| Key | Default | Meaning |
|---|---|---|
| `wipe.playerData` | `true` | Erase inventory, ender chest, XP, spawn point |
| `wipe.advancements` | `true` | Erase advancements **and recipe book unlocks** |
| `wipe.stats` | `true` | Erase statistics |
| `wipe.graceMinutes` | `5` | How long resurrection still restores everything |
| `wipe.lockFiles` | `true` | Lock held files so nothing else can block the wipe |
| `wipe.pets` | `true` | Destroy tamed wolves, cats and parrots on reincarnation |
| `wipe.livestock` | `true` | Destroy tamed horses, donkeys, mules, llamas and camels — **and their cargo** |
| `wipe.foxes` | `true` | Make foxes forget they trusted you, and destroy those no living player trusts |
| `wipe.vaultRewards` | `true` | Trial vaults forget that a past life looted them |
| `wipe.villagerReputation` | `true` | Villagers forget what they thought of a past life |
| `wipe.villagers` | `true` | Destroy villagers whose only customers have died — **see the warning below** |
| `wipe.petsCheckIntervalTicks` | `20` | How often to look for animals belonging to an ended life |
| `purgatory.minutes` | `360` | Purgatory length in real-time minutes (6 hours) |
| `purgatory.deathScreenSeconds` | `15` | How long the death screen is held |
| `message.death` | — | Shown on the disconnect that follows a death |
| `message.rejoin` | — | Shown when someone in Purgatory tries to join |
| `message.deathScreen` | — | The one line of the in-game death screen a server can control. **Must stay one line** — see below |
| `message.reincarnation` | — | Greeting when Purgatory ends |
| `bypass.permissionLevel` | `-1` | Who is exempt from dying. `-1` nobody, `0` everyone, `1`-`4` that op level and above |
| `admin.permissionLevel` | `4` | Who can run `/lethemc admin ...`. `0`-`4` |

**`purgatory.minutes` must be greater than `wipe.graceMinutes`.** Both are in minutes, so at the
defaults that means a minimum Purgatory of 6 minutes. Values that break the rule are rejected with a message
telling you the minimum, and a bad config file is corrected on load rather than refusing to
boot.

### Message placeholders

| Placeholder | Expands to |
|---|---|
| `%player%` | Player name |
| `%time_remaining%` | `2h 14m` |
| `%time_remaining_short%` | `00:02:14:33` — `DD:HH:MM:SS` |
| `%unlock_time%` | Wall-clock time the Purgatory ends, in the **server's** timezone |
| `%death_reason%` | The vanilla death message |
| `%grace_remaining%` | Time resurrection still restores everything, or `expired` |
| `%grace_remaining_short%` | `00:00:04:12` — `DD:HH:MM:SS` |
| `%grace_line%` | A ready-written sentence covering both cases |

Colour codes use `§`. Use `\n` for a line break — **except in `message.deathScreen`**, which
must stay a single line. The in-game death screen places that text 15 pixels above "Score:"
with no wrapping, so anything longer collides with it.

Everything else on that screen — "Game Over!", "Score:", and the buttons — comes from the
client's own language file. A server-side mod cannot change those, which is why the death
message is the only line LetheMC touches.

Times are shown as **durations**, never clock times. An absolute unlock time has to be printed
in some timezone, and the server's is not necessarily the player's, with nothing on screen
saying so. `%unlock_time%` is still available if you want it — it just isn't used by default.

---

## Notes

- **Ops are no longer exempt by default.** If you *want* an immune admin, set
  `bypass.permissionLevel` to their op level.
- **Recipe books reset.** Recipe unlocks live in the advancement file, so a player returns
  unable to craft things they had already discovered. In practice this is more noticeable
  than losing the advancements themselves.
- **Whitelist and op status are never touched.** Neither are vanilla bans.
- **Animals are checked lazily.** One in an unloaded chunk is dealt with the moment someone
  loads that chunk, so there is no world scan and no startup cost.
- **Scoreboard scores and teams survive.** Vanilla keys those by player *name* in a single
  shared file, so removing one player's entries is not something this mod attempts.
- **Scoreboards are keyed by player *name*, not UUID**, in one shared file — so they are usually
  the server's own machinery (ranks, minigames) rather than player progress, and are left alone.
- **Other mods' data survives.** Anything storing its own per-player records is untouched.

## License

See `LICENSE`.
