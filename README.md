# BlueMapMini

BlueMapMini: a BlueMap minimap and coordinates HUD for Paper, rendered in game from [BlueMap](https://bluemap.bluecolored.de/)'s own tiles, with a fullscreen map too. It's an add-on to BlueMap, not a separate map: it needs BlueMap on the server and shows what BlueMap has rendered. It uses a small resource pack with its own namespace: no client mods, no shader overrides and no held map items.

It's an unofficial add-on for BlueMap and isn't affiliated with BlueMap's authors.

**Status:** pre-release, version 0.3.0, renamed from BlueMinimap (see below). It has passed a full test suite on live Paper 26.1.2, 26.2 and 26.3 servers with real BlueMap tiles, using a protocol bot. It hasn't been seen in a real game client yet. See [Testing](#testing).

- Requires Paper 26.1 or newer, Java 25, and a BlueMap version that matches your server (see below). Java Edition only. Paper 1.21.11 and older aren't supported.
- Works alongside other resource packs, because it never overrides a shader and only adds its own files.

| Server | BlueMap | Java |
| --- | --- | --- |
| Paper 26.1.2 | 5.20 or newer | 25 |
| Paper 26.2 | 5.22 or newer (skip 5.21) | 25 |
| Paper 26.3 | 5.24 or newer | 25 |

These minimums come from BlueMap's release notes. Only BlueMap 5.29 was tested, on all three. Paper's 26.1 line has no 26.1.0 build, and 26.1.1 only had alpha builds, so 26.1.2 is the one tested.

## Install

1. Put `BlueMapMini-<version>.jar` in `plugins/`, next to BlueMap.
2. Open port `8164` so players can download the pack, or set `pack.public-url` to host it elsewhere. Servers sharing a machine each need their own `pack.port`. `pack.bind` picks the address it listens on (empty means every address); `127.0.0.1` keeps it on the server's own machine, for when the pack reaches players another way.
3. Start the server. Settings are in `plugins/BlueMapMini/config.yml`.

If BlueMap stores its maps in SQL, set `tiles.source: http`.

## Upgrading from BlueMinimap

BlueMapMini was called BlueMinimap in 0.2.x, and renamed in 0.3.0 (D194) so its name starts with BlueMap like the other BlueMap add-ons. Players keep their HUD settings.

- With the server stopped, swap `BlueMinimap-0.2.1.jar` for `BlueMapMini-0.3.0.jar`.
- On first start, `plugins/BlueMinimap/` is copied to `plugins/BlueMapMini/` if that doesn't exist yet, so your config, themes and icons come across. The old folder is left untouched. The pack is rebuilt as `plugins/BlueMapMini/pack/bluemapmini-pack.zip`; if you host the pack yourself (`public-url`), upload the new file.
- It still answers to the name BlueMinimap, so HouseRules and anything else that looks for BlueMinimap keeps working without an update. The share event keeps its class, `com.extantape.blueminimap.api.CoordinateShareEvent`.
- For 0.3.x only, the old names still work: `/blueminimap` and the `blueminimap.*` permissions, alongside BlueMapHUD's. For permissions that are on by default, a "no" on any of the names wins, so a group you'd stopped from sharing stays stopped. Set the `bluemapmini.*` names instead.

## Upgrading from BlueMapHUD

It was called BlueMapHUD up to 0.1.0, and BlueMinimap in 0.2.x. Players keep their HUD settings.

- With the server stopped, swap `BlueMapHUD-0.1.0.jar` for `BlueMinimap-0.2.0.jar`.
- On first start, `plugins/BlueMapHUD/` is copied to `plugins/BlueMinimap/` if that doesn't exist yet, so your config, themes and icons come across. The old folder is left untouched as a backup; nothing is overwritten. The pack is rebuilt as `plugins/BlueMinimap/pack/blueminimap-pack.zip`. If you host the pack yourself (`public-url`), upload the new file.
- For 0.2.x only, the old names still work: `/bmhud` (logged at startup), `/bluemaphud:map`, `/bluemaphud:hud` and the `bluemaphud.*` permissions. They go in the next release. For permissions that are on by default, a "no" on either the old or the new name wins, so a group you'd stopped from sharing stays stopped. Any difference between the two names is logged once per player. Set the `blueminimap.*` names instead.
- If HouseRules is installed, update it to 0.2.0 at the same time. With an older HouseRules, BlueMinimap turns `/hud share` off and logs why, because HouseRules 0.1.x wouldn't see the shares and couldn't keep them inside chat circles.
- Plugins that listen for shares: the event is now `com.extantape.blueminimap.api.CoordinateShareEvent`, with the same methods.

## Commands

| Command | What it does |
| --- | --- |
| `/map` | Opens or closes the fullscreen map. Sneak + F does the same. |
| `/map zoom <n\|in\|out>` | Sets the fullscreen zoom. |
| `/hud`, `/hud on`, `/hud off` | Toggles the minimap. The choice is saved per player. |
| `/hud zoom <n\|in\|out>` | Sets blocks per cell: 1, 2, 4, 8, 16, 32 or 64. |
| `/hud size small\|medium\|large` | Sets the size to 24, 32 or 40 cells. |
| `/hud offset <x> <y>` | Moves the minimap, in GUI pixels from top centre. |
| `/hud corner left\|right\|center` | Moves it to a rough preset position. |
| `/hud hide\|show coords\|players\|markers\|clock` | Toggles one part of the map. Coordinates show X, Y, Z and facing; the clock adds the in-game time after them. |
| `/hud share` | Asks who to send your coordinates to: tick people in a dialog, or send to everyone you can. |
| `/hud share <names>`, `/hud share everyone` | The same without the dialog. Names are separated by spaces or commas. |
| `/hud hide\|show shares` | Stops or restarts receiving coordinates other players share. |
| `/hud theme`, `/hud theme <name>\|default` | Lists the themes, with yours marked and each one clickable, or switches to one. `default` goes back to the server's. The choice is saved per player. |
| `/hud mode coords\|map` | `coords` shows only the X, Y, Z and facing line, in the same bar, and works in worlds BlueMap doesn't map. `map` brings the minimap back. Both turn the HUD on, and `/hud mode` alone says which one you have. New players start in `hud.default-mode`, which is `map` unless the config says `coords`. |
| `/bluemapmini help` | Shows help. |
| `/bluemapmini reload` | Reloads the config, and re-sends the pack if it changed. |
| `/bluemapmini debug` | Shows cache sizes, build times and update sizes. |
| `/bluemapmini debug dump` | Writes your view to a PNG to compare with BlueMap. |
| `/bluemapmini debug pattern` | Shows a test pattern. |
| `/bluemapmini pack export` | Shows where the pack zip is, its SHA-1, and the address players are sent. |

If another plugin owns a name, BlueMapMini backs off: `/map` to `/worldmap`, then `/bluemapmini map`, and `/hud` to `/minimap`, then `/bluemapmini hud`. It logs which names it got. `/bluemapmini map`, `/bluemapmini hud`, `/bluemapmini:map` and `/bluemapmini:hud` always work. New names apply after a restart.

## Permissions

| Permission | Default | Allows |
| --- | --- | --- |
| `bluemapmini.use` | everyone | Seeing the map at all. Removing it hides everything for that group. |
| `bluemapmini.fullscreen` | everyone | The fullscreen map. |
| `bluemapmini.seeplayers` | everyone | Other players on the map. |
| `bluemapmini.share.send` | everyone | Sharing your coordinates with `/hud share`. |
| `bluemapmini.share.receive` | everyone | Receiving coordinates other players share. |
| `bluemapmini.admin` | ops | `/bluemapmini reload`, `debug` and `pack`. |

## Themes and the clock

A theme sets the HUD's colours, the plate behind the coordinates and the look of the clock. Four are built in:

| Theme | Looks like |
| --- | --- |
| `classic` | Today's look: white numbers on a dark plate, with a plain sun and moon. The default. |
| `desert` | A warm plate and a dune with a cactus, as the sun rises, crosses the sky and sets, and the moon follows. |
| `galaxy` | A deep purple plate and a small sun on a planet's orbit, read like a clock face with noon at the top. |
| `high-contrast` | A solid black plate, white and yellow text and the plainest shapes, for low vision and bright screens. |

`hud.theme` in `config.yml` picks the one new players get. Players choose their own with `/hud theme <name>`. Every theme is in the pack, so switching needs no new download.

The clock follows the coordinates as a small picture and 24-hour time, such as `18:30`. It changes every `hud.clock.step-minutes` in-game minutes, 5 by default, and its digits are fixed-width, so the line never shifts. It's hidden where time stands still, such as the Nether and the End. `hud.clock.enabled: false` turns it off for everyone, and `/hud hide clock` for one player.

**Your own theme.** Copy one of the files in [`src/main/resources/themes`](src/main/resources/themes) into `plugins/BlueMapMini/themes/`, rename it (lowercase, such as `ocean.json`), change it, and run `/bluemapmini reload`. A file with a built-in theme's name replaces it. The file holds:

- `name`: the name players see.
- `colors`: `text`, `facing`, `time` and `legend` for text, `plate` for the plate, and `self`, `player`, `poi`, `death` and `north` for the map's icons. Each is `#RRGGBB`.
- `plate-opacity`: from 26 to 255. 255 is solid.
- `clock.palette`: one character per colour. `.` is transparent.
- `clock.frames`: 1 to 24 pictures, 9 rows of 9 characters each. They share the day evenly from 00:00, so 8 frames change every 3 hours.

Up to 8 themes fit in the pack. Every built-in theme passes the same readability test, and the plugin warns about one of yours that doesn't. Text needs a WCAG contrast of 4.5:1 against the plate, and three quarters of each clock picture needs 3:1. Both are checked over ten backgrounds from white to black, including the brightest pure colours. A theme that fails still loads, so you can fix it.

## Sharing coordinates

`/hud share` sends your current coordinates to people you choose, as a chat message with the coordinates and the dimension, such as `Alex shared where they are: 120 64 -35 in the Overworld`. Clicking the numbers copies them. Nothing is shared unless a player asks, and there's no teleport link.

- **Who can receive:** by default, players in the sender's world, counting its `_nether` and `_the_end` as the same world, who can see the sender, have `bluemapmini.share.receive`, and haven't turned shares off with `/hud hide shares`.
- **Feedback:** the sender is told exactly who got their coordinates, and who couldn't.
- **Limits:** a player waits `share.cooldown-seconds` (30 by default) between shares, and each share is logged to the console. `share.enabled: false` turns sharing off.

**Your server's own rules.** BlueMapMini fires `com.extantape.blueminimap.api.CoordinateShareEvent` on the main thread before each share. It carries the sender, the world, the block coordinates and a mutable set of recipients. A plugin can remove recipients, for example to match who may chat with whom, or cancel the share. With `share.candidates: all-online`, recipients can include every player online, so that such a plugin can follow rules that reach across worlds. BlueMapMini only widens a share like that while a plugin is listening to the event, and logs a warning at startup if nothing is. To build against it, depend on BlueMapMini as a server dependency with `join-classpath: true` in your `paper-plugin.yml`.

## Resource packs

The plugin sends its pack alongside any others using the multi-pack API, and players don't see a map until their client confirms it loaded. With `pack.required`, a player who declines the pack is kicked with `pack.kick-message`. A download that fails never kicks: the player plays without the map and is told why, admins are warned, and the pack is offered again when they change worlds or rejoin. Its only file outside its own namespace is a transparent boss bar texture, in pink by default. If another pack restyles that colour, set `pack.boss-bar-color` to one it leaves alone.

**Using it with Magic.** Magic sends its pack with Bukkit's older one-pack method, which clears every other server pack. BlueMapMini re-sends its pack once when that happens, but it's better to stop the conflict. In `plugins/Magic/config.yml` set `resource_pack: ""`, then add Magic's pack to your main pack.

**Hosting it elsewhere.** Set `pack.public-url` to where you upload `plugins/BlueMapMini/pack/bluemapmini-pack.zip`. Upload it unchanged, because re-zipping changes its SHA-1 and clients reject a pack whose hash doesn't match. `{sha1}` in the address is replaced with the pack's hash, such as `https://example.com/packs/{sha1}.zip`, so the address changes whenever the pack does. The same settings and plugin version always build the same hash. On startup and `/bluemapmini reload` the plugin downloads the address once in the background and warns, with both hashes, if it isn't the current pack. It never blocks startup. If the server can't reach the address but players can, set `pack.verify-public-url: false`. `/bluemapmini pack export` shows the path, the hash and the address players are sent.

**Using a merged pack.** Set `pack.send: false`, run `/bluemapmini pack export`, and merge that zip into your server's pack. On startup the plugin warns if its files are missing.

**Custom icons.** Put 9x9 PNGs in `plugins/BlueMapMini/icons/` and run `/bluemapmini reload`. The names are `arrow_00.png` to `arrow_15.png` (clockwise from north), `player.png`, `poi.png`, `death.png` and `north.png`. Draw them in white, because the plugin tints them.

## Known limits

- **Screen position:** the server can't see a player's screen size, so the map starts at top centre. Players can move it with `/hud offset` or `/hud corner`.
- **Other boss bars:** the minimap hides for Adventure boss bars, registered Bukkit bars and the Wither and Ender Dragon. A bar made with Bukkit's unregistered `createBossBar` can't be detected, so the minimap stays up and the two share the screen.
- **Clock:** it shows the in-game time of the world you're in, in steps of `hud.clock.step-minutes`. It doesn't show in worlds where time stands still.
- **Coordinates:** the coordinate line is part of the minimap, so it hides whenever the minimap does, including during boss fights and with `/hud off`. In `/hud mode coords` it follows the same rules, apart from also showing where BlueMap has no map. The fullscreen map always shows it.
- **Vertical position:** `/hud offset y` moves in whole map cells, up to `hud.max-offset-y`.
- **Action bar mode:** the map sits above the hotbar and can flicker if another plugin also uses the action bar. The fullscreen map still uses a boss bar.
- **Other pink boss bars:** the pack makes one boss bar colour transparent, so another plugin's bar in that colour disappears too. Choose a colour with `pack.boss-bar-color` that nothing else uses.
- **Repeated pack wipes:** the pack is re-sent once per session. If another plugin clears packs again, the map stays hidden for that player until they rejoin. This is what stops two plugins fighting in a loop.
- **Bedrock players:** Geyser and Floodgate players don't get a map, because they can't use the pack.

## How it works

Each map cell is one or more characters in a generated font, coloured with hex text colour and with the shadow turned off, so a whole map is a single text component shown as a boss bar title. Colours come from BlueMap's low-res tiles, which are shrunk once and cached in 64x64 cell chunks by two background threads.

Every 5 ticks the main thread copies each player's state, and background threads compare it with the last frame. If nothing visible changed, nothing is sent. The clock counts as a change once per step.

Measured in the test suite on a synthetic map:

| | Result | Budget |
| --- | --- | --- |
| Minimap frame build | about 40 µs | 1 ms |
| Minimap update, 32x32 with 20 players, compressed | about 1.7 KB | 5 KB |
| Fullscreen update, 96x54, compressed | about 4.5 KB | none |

Real terrain has more colour variation than the test map, so expect larger updates in play. `/bluemapmini debug` reports sizes before compression.

## Testing

`./gradlew build` compiles against the Paper 26.1.2 API, the oldest supported, and runs the tests. They include a simulated client that loads the generated pack and draws frames using the font rules read from the Minecraft 26.3 client: glyph advance, the `ascent <= height` check and text centring. It checks that every map cell lands on the right pixels and that no frame drifts.

The plugin has also been tested on live Paper 26.1.2, 26.2 and 26.3 servers with BlueMap 5.29. A protocol bot played the client. It speaks the 1.21.11 protocol and joined through ViaBackwards, so the packets were translated rather than sent by a real 26.x client. Every frame it received was decoded and compared cell by cell with BlueMap's tile files on disk.

The live tests are in [`test/live`](test/live/README.md), with what they cover and what's simulated. `test/live/run.sh` builds a throwaway server, runs them and deletes it.

**Passed on all three servers:**

- **Map accuracy:** every zoom level, every size, all three dimensions, negative coordinates, unrendered areas and the fullscreen map. Re-rendered tiles reach the HUD within the refresh time.
- **Icons and text:** the X, Y, Z and facing readout, heading arrows at all 8 compass points, player dots, POI markers and labels (including accents, CJK and very long labels), edge arrows, the death point and coordinates.
- **Clock:** the right time and picture at noon, dusk, midnight and sunrise, nothing new sent inside a step, the line never moving as the time changes, `/hud hide clock`, no clock in the Nether, and the minimap and fullscreen map.
- **Themes:** every theme's clock, plate and colours, the same size line in each, `/hud theme` listing and switching, saved choices, the server's default, unknown names, and a server's own theme loading, failing to parse, or warned as hard to read.
- **Sharing:** one person, everyone in the world group (the Nether included, another world not), players who turned shares off, the send and receive permissions, players hidden from the sender, a rules plugin removing a recipient or cancelling the share, `all-online` with and without a filter, the cooldown, `share.enabled: false`, and the dialog's contents.
- **One boss bar:** the map and coordinates share a single bar. A second never appeared through every `/hud` option, fullscreen, sneak + F, reloads, pack re-sends, auto-hide and a full `/reload`.
- **Hiding:** vanish, `hidePlayer`, spectators, invisibility, players hidden in BlueMap, the radius, other dimensions, permissions, other boss bars, the Wither, the Ender Dragon and every `auto-hide` setting.
- **Updates:** nothing is sent while idle apart from each clock step, and at most 4 updates a second otherwise.
- **Pack handling:** required, optional and declined packs, failed downloads (no kick, a message, an admin warning and a retry on world change), `host`, `public-url` with and without `{sha1}`, the hosted-pack check (matching, stale, missing, unreachable, never answering, invalid and turned off), `send: false`, bar colours, custom icons, and another plugin wiping packs with the old one-pack method.
- **Life cycle:** `/bluemapmini reload`, `/bluemap reload`, a full `/reload`, server restarts, saved settings and quick join/quits.
- **Bad data:** corrupt tiles, a wrong web root, an unreachable webserver and the far edge of the world.
- **Other setups:** the action bar channel, the HTTP source, and command back-off from another plugin's `plugin.yml` commands.

**Also passed:**

- **SQL storage:** on 26.3, tiles held in SQLite through the HTTP source matched the same world rendered to files on all 1024 cells.
- **Startup:** BlueMap loaded on all 8 of 8 restarts of a 26.3 server with BlueMapMini installed.
- **Game code:** the Minecraft 26.1.2, 26.2 and 26.3 client code loads the generated pack and fonts, with the game's advances matching this project's for all 18,944 glyphs, the clock pictures and fixed-width time digits included. The 26.1, 26.1.1 and 26.1.2 clients all use resource pack format 84, the pack's minimum.

**20 moving players, one minute, on real terrain.** 26.3 is this release, with themes and the clock; 26.1.2 and 26.2 were measured on the build just before them:

| | Paper 26.1.2 | Paper 26.2 | Paper 26.3 | Budget |
| --- | --- | --- | --- | --- |
| Average frame build | 73 µs | 84 µs | 63 µs | 1 ms |
| Update size, compressed (95th percentile) | 2.3 KB | 2.3 KB | 2.1 KB | 5 KB |
| Largest update, compressed | 2.6 KB | 2.6 KB | 2.2 KB | none |
| Server tick | 12.1 ms | 11.5 ms | 7.6 ms | 50 ms |

These ran on a shared machine with other test servers running, so tick times vary with its load. One 26.3 run, with the machine's load average at 55, dropped to 18.9 TPS while its tick stayed at 18 ms; a rerun at lower load held 20 TPS.

**Not tested yet:**

- A real game client: how the map actually looks, the invisible boss bar, icons drawing over the map, GUI scales and screen widths, and Sodium and Iris with a shader pack.
- The real Magic plugin. Its pack call was simulated with the same Bukkit method it uses.
- Pressing the `/hud share` dialog's buttons. The bot checks the dialog it's sent, but can't click one. The buttons run the same code as `/hud share <names>` and `/hud share everyone`, which are tested.
- ZigZag loaded alongside the pack in a client. It overlaps only on the pink boss bar textures, which this pack replaces.
- A Bedrock join, including Floodgate detection through `paper-plugin.yml`'s classpath sharing, MySQL or PostgreSQL storage, and back-off from another plugin's Brigadier commands.

To check a real client, join as an op and run `/bluemapmini debug pattern`. You should see a sharp colour gradient with a white border and no bar behind it.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. Java 25 is needed to build and run it. It's a Paper plugin (`paper-plugin.yml`), so it won't load on Spigot. [docs/26x-audit.md](docs/26x-audit.md) records what changed when 1.21.11 support was dropped.

## License

MIT. See [LICENSE](LICENSE).
