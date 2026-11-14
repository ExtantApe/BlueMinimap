// BlueMapMini with the real HouseRules: shares follow chat circles, and with a HouseRules too old to see BlueMapMini's
// share event, sharing is off rather than unfiltered.
// Usage: node houserules.js <dir> <jar> <port> <houseRulesJar> <oldHouseRulesJar>, <dir> and <jar> relative to the work folder.
const { Server, Bot, check, section, summary, sleep, until } = require('./lib.js');
const fs = require('fs');
const [dir, jar, portS, newJar, oldJar] = process.argv.slice(2);
const port = +portS;
const s = new Server(dir, jar, port);
const installed = s.path('plugins', 'HouseRules.jar');

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
    section('BlueMapMini with HouseRules 0.2: shares follow the circles');
    fs.copyFileSync(newJar, installed);
    fs.mkdirSync(s.path('plugins', 'HouseRules'), { recursive: true });
    fs.writeFileSync(s.path('plugins', 'HouseRules', 'config.yml'), CONFIG);
    await s.start(); await sleep(4000);
    check('HouseRules found BlueMapMini\'s share event', /BlueMapMini found: coordinate shares follow the same rules as chat/.test(s.log));
    bots = await joinAll(['Tester', 'Alex', 'Blair', 'Casey']);
    for (const [who, circle] of [['Tester', 'red'], ['Alex', 'red'], ['Blair', 'blue']]) s.cmd(`bmt perm ${who} houserules.circle.${circle} true`);
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

    section('BlueMapMini with HouseRules 0.1.1: sharing is off, not unfiltered');
    fs.copyFileSync(oldJar, installed);
    await s.start(); await sleep(4000);
    bots = await joinAll(['Tester', 'Alex', 'Blair']);
    s.cmd('bmt perm Tester houserules.circle.red true'); s.cmd('bmt perm Alex houserules.circle.red true'); await sleep(800);
    marks = Object.fromEntries(Object.entries(bots).map(([n, b]) => [n, b.chat.length]));
    const mark = s.mark();
    const off = await say(bots.Tester, '/hud share everyone', /Sent your coordinates|off on this server|can receive/);
    await sleep(600);
    check('The sender is told sharing is off', /Sharing coordinates is off on this server for now/.test(off || ''), off);
    check('and nobody receives anything', !heard(bots.Alex, marks.Alex) && !heard(bots.Blair, marks.Blair));
    check('The log tells the admin to update HouseRules', /HouseRules 0\.1\.1 can't see BlueMapMini's shares.*Update HouseRules to 0\.2\.0/.test(s.since(mark)));
    for (const b of Object.values(bots)) await b.cleanQuit();
    await s.stop();

    section('BlueMapMini without HouseRules: sharing works');
    fs.rmSync(installed, { force: true });
    await s.start(); await sleep(4000);
    bots = await joinAll(['Tester', 'Alex']);
    const m = bots.Alex.chat.length;
    const plain = await say(bots.Tester, '/hud share everyone', /Sent your coordinates|off on this server|can receive/);
    await sleep(600);
    check('control: without HouseRules a share goes through', /Sent your coordinates/.test(plain || '') && heard(bots.Alex, m), plain);
    for (const b of Object.values(bots)) await b.cleanQuit();
    await s.stop();
  } catch (e) {
    check('houserules finished', false, e.stack?.slice(0, 400));
    for (const b of Object.values(bots)) try { await b.cleanQuit(); } catch {}
    try { await s.stop(); } catch {}
  }
  fs.rmSync(installed, { force: true });
  summary();
})();
