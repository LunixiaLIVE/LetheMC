# Changelog — LetheMC (`multi_26.1`)

Minecraft **26.1.x** · Fabric + NeoForge · server-side · standalone (no extra library mods).

## [0.2.0.1]
### Fixed
- **Stands down in single player.** NeoForge has no way to declare a mod server-only in its
  metadata — the `[[mods]]` block has no `side` field, and the ones in the file belong to
  dependencies — so the jar loaded into single-player worlds and ran there in full. That is the
  worst place for it: Purgatory is a lockout enforced at login and lifted early only by an admin,
  and a single-player save has neither, so a death would have shut the player out of their own
  world until the timer expired. Fabric was already safe (`environment: server`), so the two
  loaders now behave identically.

## [0.2.0.0]
### Added
- **Fabric and NeoForge from one jar**, via Architectury Loom as build tooling only — nothing
  imports `dev.architectury`, so there is no runtime dependency.
- **The penalty.** Nothing drops on death. Inventory, **ender chest**, XP, advancements and
  statistics are taken, and the player enters **Purgatory** — a lockout measured in real time that
  keeps counting while the server is offline.
- **Two exits.** *Resurrection* returns everything, including worn armour and XP, without even a
  death screen. *Reincarnation* returns the player as no one, with a greeting so an empty
  inventory reads as the mechanic rather than a bug.
- **Tamed pets and livestock are destroyed** on reincarnation, along with their cargo — a loaded
  chest donkey is not a death-proof vault.
- **Foxes forget a dead life**, per trust slot, and a fox no living player trusts is destroyed.
- **Trial vaults forget** that a past life looted them. The only survivor that *cost* the player
  rather than granting: without it a reincarnated player is silently refused by a vault they have
  never opened.
- **Villager reputation expires** — discounts and grudges alike.
- **Trading halls built by one player are destroyed** when that life ends. Only villagers actually
  traded with or cured count; hitting one does not make it yours, and any living customer spares it.
- **Grace-period wards.** While a player is in Purgatory with their belongings restorable, nothing
  of theirs can be ridden, opened, harmed or traded with — by anyone, including monsters.
- **Admin controls.** Every behaviour is a config key, settable in-game and applied immediately.
  Separate permissions for who dies and who can resurrect. Admins cannot resurrect themselves.
  `/lethemc admin hardcore on` converts a world to hardcore, which vanilla cannot do for a world
  that already exists. Tab completion on every argument, including players currently in Purgatory
  — who are by definition offline and therefore invisible to vanilla's own player suggestions.

### Requirements
- **Java 25** — the jar is compiled to Java 25 and will not load on anything older.
- `pause-when-empty-seconds=0` — a paused server stops ticking exactly when a dying player has
  left, so the wipe would silently never happen. LetheMC refuses to run rather than half-work.
- **Dedicated servers only.** Players install nothing; a vanilla client just connects.
