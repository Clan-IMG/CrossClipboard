# CrossClipboard

Seamlessly sync FAWE clipboards across multiple Minecraft servers.

```
/server plots1     //copy
/server plots2     //paste        <- the same clipboard, no schematic files in between
```

CrossClipboard is a Paper plugin. Install the same jar on every backend server that should share clipboards and
point them all at one Redis. The proxy (Velocity/BungeeCord) needs nothing.

## Requirements

- Paper 1.21.11
- [FastAsyncWorldEdit](https://www.spigotmc.org/resources/13932/) (recommended) or plain WorldEdit
- A Redis server every backend can reach
- The **same FAWE/WorldEdit version on every server**, because the clipboard is exchanged in FAWE's own format

## How it works

- When a player leaves a server, their clipboard is stored in Redis, once per player. It is serialized on the
  server thread at that moment (FAWE closes a leaving player's clipboard right after), so a very large clipboard
  can cause a short hitch when its owner leaves; the upload itself runs in the background. `sync.max-size-mb`
  bounds this.
- When a player joins a server, their stored clipboard is loaded into their WorldEdit session.
- Behind a proxy the new server usually sees the join *before* the old one sees the quit. The new server
  therefore waits until the old one announces that it finished uploading (over Redis pub/sub), and only then
  loads. If the old server never answers (e.g. it crashed), it loads after `handoff-timeout-seconds`.
- Everything is per player. Players with different clipboards never interfere with each other.
- A clipboard that has not changed since it was last synced is not uploaded again.
- Rotation and flip (`//rotate`, `//flip`) applied to the clipboard are carried over.

Not synced: undo/redo history, brushes, masks and selections. Undo only works on the server where the edit
happened.

## Commands

`/crossclipboard` (alias `/cc`)

| Command | What it does |
| --- | --- |
| `status` | Shows whether you have a clipboard here and what is stored for you |
| `push` | Stores your current clipboard now |
| `pull` | Replaces your clipboard with the stored one now |
| `clear` | Deletes the stored copy (your local clipboard stays) |
| `reload` | Reloads `config.yml` and the language file (admin) |

Syncing is automatic; the commands are only needed for manual control.

## Permissions

| Permission | Default | |
| --- | --- | --- |
| `crossclipboard.use` | everyone | Clipboard follows the player; use the commands |
| `crossclipboard.admin` | op | `/crossclipboard reload` |

## Configuration

See the commented [config.yml](src/main/resources/config.yml). The ones you will most likely touch:

| Key | Default | |
| --- | --- | --- |
| `redis.url` or `redis.host` / `port` / `password` | | Where Redis is. `url` takes `redis://default:PASSWORD@host:6379/0` as Coolify prints it |
| `redis.key-prefix` | `crossclipboard` | Change if several networks share one Redis |
| `sync.max-size-mb` | `50` | Larger clipboards stay local and are not synced |
| `sync.ttl-hours` | `24` | How long a stored clipboard is kept |
| `sync.handoff-timeout-seconds` | `5` | Longest a server waits for the previous one to finish uploading |
| `language` | `en` | `en` or `de`; files in `plugins/CrossClipboard/lang/` can be edited |

Changing the Redis settings needs a restart; everything else applies with `/crossclipboard reload`.

## Building

```
./gradlew shadowJar        # build/libs/CrossClipboard-<version>.jar
./gradlew test
```

The Redis integration test only runs when you point it at a throwaway Redis:

```
CROSSCLIPBOARD_TEST_REDIS=127.0.0.1:6379 ./gradlew test
```
