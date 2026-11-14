// Command back-off: with an old-style plugin owning /map and /hud, BlueMapMini must take its next names, and
// get the short names back once that plugin is gone.
// Usage: node backoff.js <dir> <jar> <port> <conflictJar>, with <dir> and <jar> relative to the work folder.
const { Server, Bot, check, section, summary, sleep, until } = require('./lib.js');
const fs = require('fs'), path = require('path');
const [dir, jar, portS, conflictJar] = process.argv.slice(2);
const port = +portS;
const s = new Server(dir, jar, port);
const installed = s.path('plugins', 'LiveTestConflict.jar');

async function say(bot, command, pattern) {
  const from = bot.chat.length;
  bot.say(command);
  return until(() => bot.chat.slice(from).find((line) => pattern.test(line)), 5000);
}

(async () => {
  try {
    section('Another plugin owns /map and /hud');
    fs.copyFileSync(conflictJar, installed);
    await s.start(); await sleep(4000);
    s.cmd('op Tester');
    const names = s.log.match(/Commands: fullscreen map is (\S+), minimap is (\S+)\./);
    check('BlueMapMini backs off to /worldmap and /minimap', names && names[1] === '/worldmap' && names[2] === '/minimap', names?.[0]);
    const bot = new Bot('Tester', port);
    await Promise.race([bot.spawned, sleep(20000)]); await until(() => bot.packBytes, 8000); await sleep(1000);
    check('/map still reaches the other plugin', await say(bot, '/map', /CONFLICT PLUGIN OWNS \/map/));
    check('/hud still reaches the other plugin', await say(bot, '/hud', /CONFLICT PLUGIN OWNS \/hud/));
    check('/minimap reaches BlueMapMini', await say(bot, '/minimap off', /Minimap off/));
    check('/bluemapmini hud always works', await say(bot, '/bluemapmini hud on', /Minimap on/));
    check('/bluemapmini:hud always works', await say(bot, '/bluemapmini:hud off', /Minimap off/));
    await say(bot, '/bluemapmini:hud on', /Minimap on/);
    await bot.cleanQuit(); await s.stop();

    section('The other plugin is removed');
    fs.rmSync(installed, { force: true });
    await s.start(); await sleep(4000);
    const back = s.log.match(/Commands: fullscreen map is (\S+), minimap is (\S+)\./);
    check('BlueMapMini takes /map and /hud again after a restart', back && back[1] === '/map' && back[2] === '/hud', back?.[0]);
    await s.stop();
  } catch (e) {
    check('Back-off test finished', false, e.stack?.slice(0, 300));
  } finally {
    fs.rmSync(installed, { force: true });
    try { await s.stop(); } catch (_) {}
  }
  process.exit(summary() ? 1 : 0);
})();
