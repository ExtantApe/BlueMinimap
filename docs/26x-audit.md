# 26.x audit

BlueMapMini is a Paper plugin for Paper 26.1 and newer only: `paper-plugin.yml`, `api-version: '26.1'`, Java 25, and the Paper 26.1.2 API at compile time. This note records what changed when 1.21.11 support was dropped and which newer Paper features were checked. Checked against docs.papermc.io on 2026-11-07.

## What changed

| | Before | Now |
| --- | --- | --- |
| Plugin file | `plugin.yml` | `paper-plugin.yml` |
| `api-version` | `1.21.11` | `26.1` |
| Paper API | `1.21.11-R0.1-SNAPSHOT` | `26.1.2.build.74-stable`, the oldest supported, so nothing newer slips in |
| Java | 21 | 25, which Paper 26.1 and BlueMap 5.17+ need anyway |
| Pack `min_format` | 75 (1.21.11) | 84 (26.1, 26.1.1 and 26.1.2 all use 84) |
| CI | Java 21 | Java 25 |
| Compiler | | Warns on deprecated and removal-scheduled API |

Paper has no 26.1.0 build. Its 26.1 line is 26.1.1 (alpha builds only) and 26.1.2 (stable), so "26.1" in practice means 26.1.2. BlueMap 5.29 itself declares `api-version: 26.1.1`.

**`paper-plugin.yml`.** Paper still marks Paper plugins experimental, and its docs don't say whether one can use a `plugin.yml` plugin's classes. BlueMap is one, so this was tested rather than assumed. BlueMap is declared as a required server dependency that loads first with `join-classpath: true`. Floodgate is declared the same way but optional, because the plugin finds it by reflection. There is no `commands` section: the commands were already Brigadier, registered through `LifecycleEvents.COMMANDS`. Paper plugins get their own classloader, so other plugins can't reach BlueMapMini's classes. None need to.

**Java 25** found one real compile error: a cache record named `Entry` clashed with `LinkedHashMap.Entry` inside an anonymous subclass. It's renamed to `CachedTile`.

There were no 1.21-only code paths or compatibility shims to remove. The plugin already used only API that exists on both. The code compiles without warnings against the 26.1.2, 26.2 and 26.3 APIs, apart from one deliberate case below.

## Checked and kept as they are

- **Commands:** already Brigadier, registered from the main class. Moving registration into a `PluginBootstrap` would only matter for commands needed before the plugin enables, and none are.
- **Adventure:** already used for the boss bar, text, shadow colour, multi-pack `ResourcePackRequest` and action bar. Other bars are detected with `Player#activeBossBars()` plus registered Bukkit bars and boss mobs.
- **Player settings:** already in the PersistentDataContainer, versioned.
- **Vanish detection:** reads the `vanished` metadata that vanish plugins set. Paper deprecates the metadata API but has no replacement for reading another plugin's flag, so the warning is suppressed in that one method with a note.
- **BlueMap API:** compiled against 2.7.8, which BlueMap 5.29 loads. 2.8.x adds nothing this plugin needs.

## Checked and not adopted

- **Data components and registries:** the plugin creates no items, blocks or registry entries.
- **Display entities:** they live in the world, and the HUD is drawn on the player's screen. Not a fit.
- **Dialogs:** a settings dialog could replace some `/hud` subcommands. That's a new feature rather than modernisation, so it's left as an idea.

## Testing on 26.x

All on 2026-11-07, with BlueMap 5.29. The 31 unit tests pass on Java 25. The tested jar differs from the final commit only in the description text in `paper-plugin.yml`.

| | Paper 26.1.2 | Paper 26.2 | Paper 26.3 |
| --- | --- | --- | --- |
| Live suite | 227/227 | 227/227 | 227/227 |
| Load, 20 players for a minute | 9/9 | 9/9 | 9/9 |
| `public-url` and the hosted-pack check | | | 36/36 |
| BlueMap on SQLite through the HTTP source | | | 1024/1024 cells |
| BlueMap starting next to BlueMapMini | | | 8/8 starts |

The live suite covers BlueMap's classes being reachable from the Paper plugin (tiles, markers, hidden players), commands and their back-off, `/bluemapmini reload`, `/bluemap reload` and a full `/reload`.

**Mocked or not tested:** the client is a protocol bot speaking 1.21.11 through ViaBackwards, not a real 26.x client. Magic's pack call is simulated with the same Bukkit method. Floodgate was not installed, so the optional `join-classpath` for it is untested. Paper 26.1.1, which only had alpha builds, was not tested.

**Found along the way:** `/bluemapmini debug dump` could finish after the plugin was disabled and then try to schedule its reply, which threw an error. It now checks first, as frame building already did.
