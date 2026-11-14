# Live tests

These run BlueMapMini on a real Paper server with real BlueMap tiles, and drive it with a protocol bot. Every frame the bot receives is decoded and compared cell by cell with BlueMap's tile files on disk. The unit tests in `src/test` cover the rendering maths; these cover everything that needs a server.

`run.sh` builds one throwaway server on 127.0.0.1, generates a small world with a fixed seed, has BlueMap render it, runs the tests you name, and deletes the server afterwards, including when a test fails. Only one server runs at a time.

## What's real and what isn't

- **Real:** Paper, BlueMap, the plugin jar, the generated pack, BlueMap's tiles and SQL storage.
- **A bot, not the game:** the client is [mineflayer](https://github.com/PrismarineJS/mineflayer), which speaks the 1.21.11 protocol, so it joins 26.x through ViaVersion and ViaBackwards. It doesn't draw anything. Frames are decoded with a model of the client's font rules.
- **Real Mayor** in the `mayor` test only.
- **Simulated:** the helper plugin acts like other plugins: vanish, boss bars, permissions, POI markers, a pack sent with the old one-pack method the way Magic does it, and a chat rules plugin that filters `CoordinateShareEvent`. The pack host for `public-url` is a local stand-in.
- **Not covered:** pressing the buttons in the `/hud share` dialog. The bot checks the dialog it's sent, but its protocol library has no packet for clicking one. The buttons run Paper's own callbacks into the same code `/hud share <names>` and `/hud share everyone` test.
- **Optional:** the font check runs the real Minecraft client's font code on the pack, if you give it a client classpath (see below). It's skipped otherwise, and the summary says so.

## Setting up

You need Java 25, Node 22 or newer, and about 1 GB of disk per server while it runs.

1. Build the plugin and the two test-only plugins, and install the bot:

   ```bash
   ./gradlew build
   cd test/live && npm ci
   ```

2. Put these in `test/live/work/`, which git ignores:

   | File | Where from |
   | --- | --- |
   | `paper-<version>.jar`, such as `paper-26.3.jar` | [PaperMC downloads](https://papermc.io/downloads/paper). Check the SHA-256 it lists. |
   | `bluemap.jar` | The `-paper.jar` from [BlueMap's releases](https://github.com/BlueMap-Minecraft/BlueMap/releases). 5.29 was used. |
   | `ViaVersion.jar`, `ViaBackwards.jar` | Builds that support your server version, from [ViaVersion](https://hangar.papermc.io/ViaVersion/ViaVersion) and [ViaBackwards](https://hangar.papermc.io/ViaVersion/ViaBackwards). |
   | `Magic.jar` (optional) | Only for the real-Magic check in `storage.js`. Skipped without it. |

3. Running a server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), and BlueMap downloads the client's resources under the same terms. `run.sh` won't start until you set `BMM_ACCEPT_EULA=1`.

## Running

```bash
BMM_ACCEPT_EULA=1 test/live/run.sh 26.3                          # the full suite
BMM_ACCEPT_EULA=1 test/live/run.sh 26.3 suite packurl storage load backoff
BMM_ACCEPT_EULA=1 test/live/run.sh 26.1.2 suite:coordsonly,onebar
```

| Test | What it checks | Time |
| --- | --- | --- |
| `suite` | Everything players see: map accuracy, icons, the readout, the clock, themes, sharing, hiding rules, one boss bar, updates, pack handling, reloads, icons, action bar, HTTP source, bad data. Pick groups with `suite:<group,group>`, such as `suite:clock,themes,share`. | about 20 min |
| `suite:clockrate` | Not part of `suite`: what the clock costs a player standing still, at steps of 1, 5 and 10 in-game minutes. | 4 min |
| `load` | 20 moving bots for a minute: update size, build time and tick. Results depend on how busy the machine is. | 2 min |
| `packurl` | `public-url` with `{sha1}`, the hosted-pack check, and `/bluemapmini pack export`. | 3 min |
| `storage` | BlueMap starting 8 times next to the plugin, and BlueMap on SQLite read through the HTTP source. | 6 min |
| `backoff` | Another plugin owning `/map` and `/hud`, and getting them back once it's removed. | 2 min |
| `mayor` | The real Mayor with BlueMapMini: shares stay inside chat circles with Mayor 0.5; without Mayor, sharing stays off by default and works with `share.require-mayor: false`. Needs `BMM_MAYOR_JAR`. |

Logs go to `test/live/work/server-<version>-<test>.log`. Each test prints a summary, with any failures and skipped checks.

Ports default to 25693 (game), 8193 (pack), 8123 (BlueMap) and 8293 (stand-in host). Change them with `BMM_PORT`, `BMM_PACK_PORT`, `BMM_WEB_PORT` and `BMM_HOST_PORT`. `BMM_LIVE_DIR` moves the work folder.

## The font check

`fontcheck/FontCheck.java` feeds the pack through the Minecraft client's own metadata and font loading code, without starting the game, and the suite compares the client's glyph advances with its own. To run it, download the client jar and its libraries for a version through the launcher's version manifest, then:

```bash
export BMM_CLIENT_CP="/path/to/client.jar:/path/to/libraries/*"
```

## The test-only plugins

`helper/` and `conflict/` are built by `./gradlew build` and are never shipped. The helper is a Paper plugin with one console command, `/bmt`. The conflict plugin is deliberately an old-style Bukkit plugin with `/map` and `/hud` in its `plugin.yml`, to test the back-off. Never install either on a real server.
