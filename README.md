<h1 align="center"><img src="img/lesslag_logo_small.png" width="30px" alt="logo banner" style="margin-bottom: -5px; border-radius: 10px;"> LessLag</h1>
<p align="center">
  LessLag is a <b>performance optimization</b> plugin for Minecraft servers that <b>reduces lag automatically</b> by controlling worlds, entities, redstone, mob AI, and much more. Designed to keep TPS high without sacrificing gameplay.
</p>

<p align="center">
  <img src="https://badgen.net/badge/minecraft/1.16-26.1.2/blue" alt="Minecraft Version">
  <img src="https://badgen.net/badge/server/paper%20|%20spigot/green" alt="Server Type">
  <img src="https://badgen.net/badge/performance/optimized%20for%20lag%20reduction/purple" alt="Performance">
  <img src="https://badgen.net/badge/java/17+/orange" alt="Java Version">
</p>

---

## 💡 About

LessLag dynamically monitors server performance and automatically disables or throttles **lag-inducing features**.
From unloading inactive worlds to merging dropped items, disabling redstone during TPS drops, and controlling entity AI—LessLag **keeps your server smooth** with minimal change of vanilla features.

---

## ✨ Key Features

* ⚙️ **Fully Customizable** – Every feature can be customized in the config (`plugins/LessLag/config.yml`)
* 🗺 **World Management** – Unload inactive worlds automatically
* 🧹 **Item & Entity Control** – Auto-clear dropped items, stack drops, cap entities per world and per chunk
* 🚷 **Player Protection** – Kick packet spammers, block teleportation when TPS are low, anti-chat spam
* 🔌 **Redstone Optimization** – Disable redstone, fluids, explosions, falling blocks
* 🧠 **Mob AI Optimization** – Freeze mobs when no players nearby
* 🧭 **Chunk Loading Control** – Prevent mass chunk load during lag spikes
* 📊 **Performance Profiler** – Real-time TPS, MSPT, CPU, RAM, player ping tracking
* ⚡ **Smart TPS Handling** – Auto-disable heavy operations when TPS drops

---

## 📂 Commands

| Command            | Description                    |
| ------------------ | ------------------------------ |
| `/lesslag` / `/ll` | Main command                   |
| `/ll reload`       | Reloads configuration          |
| `/ll info`         | Shows plugin info              |
| `/ll profiler`     | Toggles performance profiler   |
| `/ll worlds`       | Lists loaded worlds with stats |

---

## 🔑 Permissions

| Permission         | Description                    |
| ------------------ | ------------------------------ |
| `lesslag.admin`    | Access `/ll` command & get notified about performance related data |

---

## ✨ Showcase

<div align="center">
  <img src="img/feature_item_stacking.png" width="90%" alt="item stacking">
</div>
<p align="center"><b>Improved item stacking, also works with un-stackable items, no item amount limit</p>

<br>
<div align="center">
  <img src="img/feature_customizable.png" width="90%" alt="item stacking">
</div>
<p align="center">Everything is customizable, even the item stack holograms</p>

<br>
<div align="center">
  <img src="img/feature_performance_profiler.png" width="90%" alt="item stacking">
</div>
<p align="center">Performance Profiler</p>

<br>
<div align="center">
  <img src="img/feature_world_list_command.png" width="90%" alt="item stacking">
</div>
<p align="center">World list command <code>/ll worlds</code></p>

<br>
<div align="center">
  <img src="img/feature_anti_spam.png" width="90%" alt="item stacking">
</div>
<p align="center">Anti chat spam</p>

<br>
<div align="center">
  <img src="img/feature_disable_blocks.png" width="90%" alt="item stacking">
</div>
<p align="center">Disable Fluids, Redstone and more when TPS are low</p>

<br>
<div align="center">
  <img src="img/feature_item_clear.png" width="90%" alt="item stacking">
</div>
<p align="center">Auto-clear dropped items</p>

<br>
<div align="center">
  <img src="img/feature_smart_entity_removal.png" width="90%" alt="item stacking">
</div>
<p align="center">Smart entity removal</p>

<br>
<div align="center">
  <img src="img/feature_world_unload.png" width="90%" alt="item stacking">
</div>
<p align="center">Auto-unload inactive worlds</p></b>

---

## 🔗 Downloads

* [SpigotMC](https://www.spigotmc.org/resources/lesslag.127762/)
* [Hangar](https://hangar.papermc.io/BridgerSilk/LessLag)
* [Modrinth](https://modrinth.com/plugin/lesslag)
* [CurseForge](soon)
* [Bukkit](soon)

---

## 📌 Latest Update

**Version:** `v0.0.6`

* ✅ added: Explosion Queue System
* ✅ added: _(WIP)_ Web interface with login system for a web based performance data viewer _(will be released in a future update)_

---

## ⚙️ Requirements

* Minecraft 1.16+
* Java 17 or higher
* Paper / Spigot server *(or forks of those)*
* [Protocol Lib](https://ci.dmulloy2.net/job/ProtocolLib/lastStableBuild/)

---

## 🤝 Contribute

We welcome contributions!

1. Fork the repository
2. Create a feature branch (`feature/my-feature`)
3. Commit changes
4. Open a Pull Request

---

## ❤️ Donate

If you enjoy LessLag and want to support its development:

* Buy me a coffee with [Ko-fi](https://ko-fi.com/bridgersilk)

---
Have a nice day <3