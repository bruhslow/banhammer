# BanHammer

High-performance, ultra-lightweight moderation plugin for Paper, Purpur, and Spigot servers providing an interactive judgment weapon.

---

### Direct Download

* **Latest Release**: [Download `BanHammer.jar`](https://github.com/bruhslow/banhammer/releases/latest)
* **Repository Build**: [`build/libs/BanHammer.jar`](build/libs/BanHammer.jar)

---

### Overview

| Detail | Specification |
| :--- | :--- |
| **Platform** | Paper / Purpur / Spigot |
| **Minecraft Versions** | 1.16 through 26.x |
| **Java Version** | 21+ (Java 25 recommended for 26.x) |
| **Soft Dependencies** | LiteBans, AdvancedBan, LibertyBans, EssentialsX, LuckPerms, DiscordSRV |
| **License** | MIT |

---

### Key Features

* **Dynamic Weapon Detection**: Automatically uses the Mace on modern servers (1.21+, 26.x) and falls back to a Netherite Axe on legacy versions.
* **One-Shot Critical Execution**: Jumping critical hits and height-based smash attacks trigger the punishment sequence immediately. Ground hits deal normal damage to prevent accidental executions.
* **Totem of Undying Negation**: Targets holding totems in either hand cannot bypass judgment.
* **Bound Weapon Protection**: Prevents dropping (`Q`), transferring to chests, item frames, or armor stands.
* **Vanish on Staff Death**: If staff members carrying the hammer perish, the weapon disappears instantly from drops to prevent player retrieval.
* **Visual Lightning & Particle Effects**: Cosmetic strike effects without causing fire hazards or terrain damage.
* **Asynchronous Disk Operations**: Ban reasons and punishment logging run off the main server thread with zero tick drops.

---

### Commands & Permissions

| Command | Arguments | Permission | Description |
| :--- | :--- | :--- | :--- |
| `/banhammer` | None | `banhammer.use` | Grants the Ban Hammer to the executing staff member |
| `/banhammer reason` | `<player> <reason>` | `banhammer.use` | Pre-sets a punishment reason for a specific target |
| `/banhammer reload` | None | `banhammer.admin` | Reloads plugin configuration and language files |

---

### Quick Start

1. Download **`BanHammer.jar`** and place it in your server's `plugins/` directory.
2. Restart or reload your server.
3. Grant staff members the `banhammer.use` permission via LuckPerms or your permission manager.
4. Run `/banhammer` in-game to receive your judgment weapon.

---

### Building from Source

Prerequisites: JDK 21+.

```bash
# Clone the repository
git clone https://github.com/bruhslow/banhammer.git

# Build the plugin archive
./gradlew build
```

Compiled JARs will be generated in `build/libs/`.

---

### License

This project is licensed under the MIT License.
