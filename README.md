<div align="center">

# ⚰️ LetheMC

### Death takes everything — including your ender chest — and sends you to Purgatory.

![](https://img.shields.io/badge/Fabric-DBA463?style=for-the-badge&logoColor=white)&nbsp;![](https://img.shields.io/badge/NeoForge-F16436?style=for-the-badge&logoColor=white)

[![](https://img.shields.io/badge/Download_on-Modrinth-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/project/lethemc)&nbsp;[![](https://img.shields.io/badge/Download_on-CurseForge-F16436?style=for-the-badge&logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/lethemc-multi)

![](https://img.shields.io/badge/Minecraft-26.1.x_%7C_26.2.x-62B47A?style=flat-square) ![](https://img.shields.io/badge/Side-Server--side-3498DB?style=flat-square) ![](https://img.shields.io/badge/Vanilla_clients-supported-4A90D9?style=flat-square) ![](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

</div>

---

> [!NOTE]
> **In Greek myth, Lethe is the river the dead drink from before rebirth, so that they remember
> nothing.** That is the whole mechanic. Death costs you everything you own, Purgatory holds you
> out of the server for a stretch of real time, and what comes back is a stranger.

## 🪦 What happens when you die

1. **Nothing drops.** There is no body to loot and no items on the ground.
2. The death screen holds for fifteen seconds so you can read what killed you.
3. You are disconnected and enter **Purgatory** — locked out for a configurable stretch of
   **real** time, which keeps counting while the server is offline.
4. Your inventory, **ender chest**, XP, advancements and statistics are taken.
5. Rejoining shows a live countdown, and whether you can still be saved.

You leave Purgatory one of two ways.

| | |
|---|---|
| ⚱️ **Resurrected** | An admin reaches you inside the grace period. You return with **everything** — inventory, worn armour, ender chest, XP, advancements, stats — placed back in the world alive, without even seeing a death screen |
| 🔥 **Reincarnated** | Purgatory simply expires. You return as no one, greeted as such, with nothing of your old life left |

## 🔒 The ender chest is the point

Every other death-penalty mod leaves the ender chest alone, which is why they are toothless —
players pre-stash their good gear and shrug the penalty off. **LetheMC takes it.** There is no
safe-deposit box.

## 🐺 It follows you out into the world

Your player files are not the only place your name is kept, so the reckoning does not stop there.

- **Tamed animals are destroyed** — wolves, cats, parrots, horses, donkeys, mules, llamas and
  camels, along with whatever they were carrying. A loaded chest donkey parked somewhere safe is
  not a death-proof vault.
- **Foxes forget you**, and a fox no living player trusts is gone with them.
- **Trial vaults forget** that a past life already looted them, so a reincarnated player is not
  silently refused by a vault they have never opened.
- **Villagers forget** what they thought of you — the discounts of a past life do not follow you
  into one that did nothing to earn them.
- **A trading hall you alone built is destroyed.** Villagers anyone living still trades with are
  left alone.

And while you are in Purgatory, **none of it can be touched** — not ridden, not opened, not
harmed, not traded with. You are offline and cannot defend it, so nobody empties your donkey or
rescues your villagers while you wait.

## ⚙️ Built for admins

Everything is a config key, settable in-game and applied immediately. Turn off anything you do
not want: keep pets, spare the villagers, leave statistics alone, change every message.

Two separate permissions decide **who dies** and **who can resurrect**, so you are not forced to
choose between having admins and having stakes. By default nobody is exempt — you can save other
people, and you die exactly like everyone else.

Runs on any world. **Hardcore is recommended, not required** — it is what the mod was written
around, and it locks difficulty to Hard. On a hardcore world LetheMC suppresses vanilla's
respawn-to-spectator, so a resurrected player comes back alive rather than watching.

## 📥 Install

Drop the jar in `mods/` alongside Fabric API, set `pause-when-empty-seconds=0` in
`server.properties`, and start the server. Players need nothing — a vanilla client just connects.

**Setting up an existing world?** Hardcore is decided when a world is generated, so it needs one
extra step — there are ordered instructions for both a new server and an existing one in the
[full README](../../blob/multi_26.2/README.md#install).

> [!IMPORTANT]
> **`pause-when-empty-seconds` must be `0`.** A paused server stops ticking exactly when the last
> player leaves — usually the one who just died — so the wipe would silently never happen.
> LetheMC refuses to run rather than half-work, and the console says exactly why.

## 📚 Full documentation

Setup, every config key, message placeholders and the admin commands live on the code branch:

**[📖 README on `multi_26.2`](../../blob/multi_26.2/README.md)** · **[26.1.x branch](../../tree/multi_26.1)** · **[🏛️ Design notes](../../blob/multi_26.2/DESIGN.md)**

## 🧩 More mods

Part of **[Lunixia's Minecraft QOL Mods](https://github.com/LunixiaLIVE/Lunixia-Minecraft-QOL-Mods)**.

## 📜 License

Released under the **MIT License**.

<div align="center"><sub>⛏️ by <a href="https://github.com/LunixiaLIVE">LunixiaLIVE</a></sub></div>
