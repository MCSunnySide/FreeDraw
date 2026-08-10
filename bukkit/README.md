# FreeDraw (Bukkit/Paper)

Server-side implementation of [FreeDraw](https://github.com/squi2rel/FreeDraw) for
Bukkit/Paper servers. It lets players who run the **FreeDraw Fabric client mod**
draw with a brush / eraser in-world on vanilla-style servers.

## How it works

The Fabric mod's client and server talk over the `freedraw:payload` plugin
channel. On a Fabric server the mod's own server-side classes handle that
traffic; this plugin re-implements exactly the same server-side logic on
Bukkit/Paper:

- **Join** – push `CONFIG` (tool items, ranges, limits) and `MAX_POINTS` to the client.
- **NEW_PATH** – assign a server-side UUID and reply with the remapped id.
- **ADD_POINTS** – accumulate points, finalize paths with at least 3 points, and
  rebroadcast to every player within `broadcastRange` (same world).
- **REMOVE_PATH** – delete a path and notify all players currently viewing it.
- **Region sync** – a per-tick task tracks which paths are inside each client's
  `broadcastRange` and sends `CREATE_PATH` + `ADD_POINTS` / `REMOVE_PATH` as they
  enter/leave range, so existing drawings appear to nearby players.
- **Persistence** – paths are stored (deflate-compressed) in `data.bin`, saved on
  shutdown and periodically.

## Compatibility

| Side | Requirement |
| --- | --- |
| Server | Paper or Spigot **1.21.x** (Java 21) |
| Client | Fabric **1.21.4** with the FreeDraw mod of the same `major.minor` version (currently **1.0.x**) |

The client checks that the server's reported version shares its `major.minor`,
so keep `plugin_version` in `gradle.properties` aligned with the mod version.

Vanilla clients are unaffected – they never send FreeDraw packets.

## Building

```sh
cd bukkit
./gradlew shadowJar          # or: gradle build
```

The shaded jar (JOML relocated) is produced at `build/libs/freedraw-bukkit-<version>.jar`.

> **IDE note:** `bukkit/` is a standalone Gradle project inside the Fabric mod
> repository. Open the `bukkit/` folder directly (File → Open Folder) in your IDE
> so the language server picks up the correct source roots and dependencies.

## Installation

1. Copy the jar into your server's `plugins/` folder.
2. Restart (or `/reload`).
3. On first start a default `config.json` and empty `data.bin` are created in `plugins/FreeDraw/`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/drawcolor <color\|rainbow>` | Set your drawing color (dye names, `#RRGGBB`/`#AARRGGBB` hex, `rainbow`) | `freedraw.drawcolor` (default: everyone) |
| `/freedraw reload` | Reload `config.json` | `freedraw.admin` (default: op) |
| `/freedraw save` | Save drawings immediately | `freedraw.admin` |
| `/freedraw clear confirm` | Delete **all** drawings and notify online players | `freedraw.admin` |

## Configuration (`plugins/FreeDraw/config.json`)

Same options as the Fabric mod's `freedraw-server.json`:

| Field | Default | Description |
| --- | --- | --- |
| `brushItem` | `minecraft:brush` | Item id that acts as the brush |
| `eraserItem` | `minecraft:resin_brick` | Item id that acts as the eraser |
| `brushIdStart` / `brushIdEnd` | `-1` | Optional brush item model-data range (VR) |
| `eraserId` | `-1` | Optional eraser item model-data (VR) |
| `brushQuat` / `eraserQuat` | identity | Tool orientation quaternion |
| `brushLength` / `eraserLength` | `0.1` | Tool length |
| `maxPoints` | `2048` | Maximum points per path |
| `broadcastRange` | `128` | Blocks within which drawings are shared |
| `uploadInterval` | `100` | Client point-upload interval |
| `defaultColor` | `0xFFFF0000` | Default color (ARGB) |
| `desktopRange` | `2` | Desktop (non-VR) interaction range |

## License

MIT, same as the FreeDraw mod.
