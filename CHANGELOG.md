# Changelog

## 0.2.0.1

- **Stands down in single player.** NeoForge has no way to declare a mod server-only in its
  metadata, so the jar loaded into single-player worlds and ran there in full — where a death
  would shut a player out of their own save with no admin able to let them back in. Fabric was
  already safe; the two now behave the same.

## 0.2.0.0 — first public release

Death takes everything you own and sends you to Purgatory. An admin can resurrect you inside a
grace period; otherwise you are reincarnated and return as no one.

### The penalty

- **Nothing drops on death** — no body to loot, no items on the ground.
- **Inventory, ender chest, XP, advancements and statistics** are taken. The ender chest is the
  point: every other death-penalty mod leaves it alone, which is why players pre-stash gear and
  shrug the penalty off.
- **Purgatory** — a lockout measured in *real* time, which keeps counting while the server is
  offline. Rejoining shows a live countdown and whether resurrection is still possible.
- **Two exits.** Resurrection returns everything, including worn armour and XP, without even a
  death screen. Reincarnation returns you as no one, with a greeting so an empty inventory reads
  as the mechanic rather than a bug.

### It follows you into the world

Player files are not the only place a UUID is kept.

- **Tamed pets and livestock are destroyed**, along with whatever they were carrying — a loaded
  chest donkey parked somewhere safe is not a death-proof vault.
- **Foxes forget you**, and a fox no living player trusts is destroyed with them. Trust is
  cleared per slot, so one player's death never costs another theirs.
- **Trial vaults forget** that a past life looted them. This is the only survivor that *costs*
  the player rather than granting: without it, a reincarnated player is silently refused by a
  vault they have never opened.
- **Villager reputation expires** — discounts *and* grudges, since a player who returns as no one
  should not be remembered either way.
- **A trading hall built by one player is destroyed** when that player's life ends. Only villagers
  actually traded with or cured count; hitting one does not make it yours, and villagers anyone
  living still trades with are untouched.

### Grace-period wards

While a player is in Purgatory with their belongings still restorable, nothing of theirs can be
ridden, opened, harmed or traded with — by anyone, including monsters. They are offline and cannot
defend it, and a resurrection is supposed to give it all back intact.

### For admins

- Every behaviour is a config key, settable in-game and applied immediately.
- **Separate permissions for who dies and who can resurrect**, so a server is not forced to choose
  between having admins and having stakes. By default nobody is exempt.
- Admins **cannot resurrect themselves**; another admin or the console still can.
- `/lethemc admin hardcore on` converts a world to hardcore — something vanilla cannot do for a
  world that already exists — and LetheMC warns when `server.properties` and `level.dat` disagree.
- Tab completion on every argument, including players currently in Purgatory, who are by
  definition offline and therefore invisible to vanilla's own player suggestions.

### Requirements

- **Fabric or NeoForge**, Minecraft 26.1.x or 26.2.x — one jar per minor line.
- **Dedicated servers only.** Players install nothing; a vanilla client just connects.
- **Java 25.**
- `pause-when-empty-seconds=0`. A paused server stops ticking exactly when a dying player has
  left, so the wipe would silently never happen — LetheMC refuses to run rather than half-work.
