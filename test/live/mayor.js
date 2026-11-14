// BlueMapMini with the real Mayor: shares follow chat circles. Without Mayor, sharing is off by default rather than
// unfiltered, and a server without chat rules can turn it on with share.require-mayor: false.
// Usage: node mayor.js <dir> <jar> <port> <mayorJar>, <dir> and <jar> relative to the work folder.
const { Server, Bot, check, section, summary, sleep, until } = require('./lib.js');
const fs = require('fs');
const [dir, jar, portS, newJar] = process.argv.slice(2);
const port = +portS;
const s = new Server(dir, jar, port);
const installed = s.path('plugins', 'Mayor.jar');

// Made-up circles. The overworld is circle-only, so only circles decide who hears whom there.
const CONFIG = 'circles: [red, blue]\ncircle-only-worlds: [world]\n';

async function say(bot, command, pattern) {
  const from = bot.chat.length;
  bot.say(command);
  return until(() => bot.chat.slice(from).find((line) => pattern.test(line)), 5000);
}
const heard = (bot, from) => bot.chat.slice(from).some((m) => /shared where they are/.test(m));

async function joinAll(names) {
  const bots = {};
  for (const n of names) {
    bots[n] = new Bot(n, port);
    await Promise.race([bots[n].spawned, sleep(20000)]);
    await until(() => bots[n].packBytes, 8000);
    await sleep(300);
  }
  return bots;
}

(async () => {
  let bots = {};
  try {
    section('BlueMapMini with Mayor 0.5: shares follow the circles');
    fs.copyFileSync(newJar, installed);
    fs.mkdirSync(s.path('plugins', 'Mayor'), { recursive: true });
    fs.writeFileSync(s.path('plugins', 'Mayor', 'config.yml'), CONFIG);
    await s.start(); await sleep(4000);
    check('Mayor found BlueMapMini\'s share event', /BlueMapMini found: coordinate shares follow the same rules as chat/.test(s.log));
    bots = await joinAll(['Tester', 'Alex', 'Blair', 'Casey']);
    for (const [who, circle] of [['Tester', 'red'], ['Alex', 'red'], ['Blair', 'blue']]) s.cmd(`bmt perm ${who} mayor.circle.${circle} true`);
    await sleep(800);
    let marks = Object.fromEntries(Object.entries(bots).map(([n, b]) => [n, b.chat.length]));
    const sent = await say(bots.Tester, '/hud share everyone', /Sent your coordinates|weren't sent|can receive/);
    await sleep(600);
    check('A share reaches the sender\'s circle', heard(bots.Alex, marks.Alex), sent);
    check('and nobody outside it', !heard(bots.Blair, marks.Blair) && !heard(bots.Casey, marks.Casey), sent);
    marks = Object.fromEntries(Object.entries(bots).map(([n, b]) => [n, b.chat.length]));
    const lonely = await say(bots.Casey, '/hud share everyone', /Sent your coordinates|weren't sent|can receive|can't/);
    await sleep(600);
    check('Someone with no circle in a circle-only world sends nothing', !Object.entries(bots).some(([n, b]) => n !== 'Casey' && heard(b, marks[n])), lonely);
    for (const b of Object.values(bots)) await b.cleanQuit();
    await s.stop();

    section('BlueMapMini without Mayor, default settings: sharing is off, not unfiltered');
    fs.rmSync(installed, { force: true });
    await s.start(); await sleep(4000);
    bots = await joinAll(['Tester', 'Alex']);
    let m = bots.Alex.chat.length;
    let mark = s.mark();
    const off = await say(bots.Tester, '/hud share everyone', /Sent your coordinates|off on this server|can receive/);
    await sleep(600);
    check('The sender is told sharing is off', /Sharing coordinates is off on this server for now/.test(off || ''), off);
    check('and nobody receives anything', !heard(bots.Alex, m));
    check("The log says Mayor isn't installed", /follows Mayor's chat rules and Mayor isn't installed/.test(s.since(mark)));
    for (const b of Object.values(bots)) await b.cleanQuit();
    await s.stop();

    section('BlueMapMini without Mayor, require-mayor: false: sharing works');
    const cfg = s.path('plugins', 'BlueMapMini', 'config.yml');
    fs.writeFileSync(cfg, fs.readFileSync(cfg, 'utf8').replace('require-mayor: true', 'require-mayor: false'));
    await s.start(); await sleep(4000);
    bots = await joinAll(['Tester', 'Alex']);
    m = bots.Alex.chat.length;
    const plain = await say(bots.Tester, '/hud share everyone', /Sent your coordinates|off on this server|can receive/);
    await sleep(600);
    check('A server without chat rules can share', /Sent your coordinates/.test(plain || '') && heard(bots.Alex, m), plain);
    for (const b of Object.values(bots)) await b.cleanQuit();
    await s.stop();
  } catch (e) {
    check('mayor finished', false, e.stack?.slice(0, 400));
    for (const b of Object.values(bots)) try { await b.cleanQuit(); } catch {}
    try { await s.stop(); } catch {}
  }
  fs.rmSync(installed, { force: true });
  summary();
})();
