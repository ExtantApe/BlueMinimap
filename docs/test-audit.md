# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-10: pack.bind (0.2.1)

| Promise | Covered by |
| --- | --- |
| Empty `pack.bind` listens on every address, as before | unit `ListenAddressTest.emptyListensOnEveryAddressAsBefore` |
| `127.0.0.1` keeps the pack server on this machine: it answers on loopback, refuses the machine's network address, and downloads from loopback still work | unit `loopbackStaysOnThisMachine`, `aServerReallyListensOnlyWhereTold`; live "pack.bind 127.0.0.1: the pack port answers on loopback", "…refused on this machine's network address", and every pack download in the suite (all groups now run with it) |
| A host name or typo never widens to every address | unit `aHostNameOrTypoIsRefusedNotWidened` |

Live suite 335/335 on Paper 26.1.2.


## 2026-11-09: first full audit (at 0.2.0)

The README's Testing section already lists what the live suite covers, check by check, so this audit starts from the promises that matter most for children's safety and privacy and for failing closed, then confirms the rest map to the Testing list.

| Promise | Covered by |
| --- | --- |
| **Shares reach only players in the sender's world group who can see the sender, have `bluemapmini.share.receive`, and haven't turned shares off** | live `suite.js` share group: one person, world group with the Nether, another world excluded, shares off, receive and send permissions, players hidden from the sender |
| **A rules plugin (Mayor) can remove recipients or cancel a share** | live share group (stand-in rules plugin); live `mayor.js` with the real Mayor 0.2.0: shares stay inside circles, a no-circle sender in a circle-only world sends nothing |
| **Fail closed with an older Mayor: sharing off, admin told** | live `mayor.js` with the real Mayor 0.1.1; unit `MayorGuardTest` (including an unreadable version) |
| Sharing limits: cooldown, console log, `share.enabled: false` | live share group |
| Other players hidden by vanish, `hidePlayer`, spectator, invisibility, BlueMap, radius, other dimensions, `bluemapmini.seeplayers` | live players group |
| Players keep their HUD settings across the rename | unit `PlayerSettingsTest` (settings saved under the original keys still load) |
| Everything else in the README (map accuracy, icons, clock, themes, one boss bar, updates, pack handling, life cycle, bad data, other setups) | the README's Testing list, check by check, in `test/live/suite.js`, `packurl.js`, `storage.js` and `backoff.js` |

**Gaps added this audit:** none new. The old-name deny checks, the Mayor joint test and the guard tests were added with 0.2.0 earlier the same day, and all passed on Paper 26.1.2, 26.2 and 26.3.

**Skipped, with reasons:**
- Pressing the buttons in the `/hud share` dialog: the bot's protocol library can't click a dialog button. The buttons call the same code `/hud share <names>` and `/hud share everyone` test.
- How it looks in a real game client: the README's status line says so; it's on the real-client list.
