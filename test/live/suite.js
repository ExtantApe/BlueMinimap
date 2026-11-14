// The BlueMapMini live test suite. Usage: node suite.js <label> <dir> <jar> <port> <packPort> <webPort> <netherMap> [groups]
// <dir> and <jar> are relative to the work folder. See README.md in this folder.
const K = require('./lib.js');
const { REPO, skip, Server, Bot, Pack, Truth, decodeFrame, gridOf, iconCell, textLines, check, section, summary, sleep, until, editYaml, readPng, ROOT } = K;
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const [label, dir, jar, portS, packPortS, webPortS, netherMap, groupArg] = process.argv.slice(2);
const port = +portS, packPort = +packPortS, webPort = +webPortS;
const only = groupArg ? groupArg.split(',') : null;
const ICON = { PLAYER: 16, POI: 17, DEATH: 18, NORTH: 19 };
const OVER = 'world';
const s = new Server(dir, jar, port);
const CFG = s.path('plugins/BlueMapMini/config.yml');
const truth = new Truth(s.path('bluemap/web/maps'));
const packs = new Map();
let botN = 0;
const liveBots = []; // Quit after each group, so a crash can't leave a duplicate login behind.

/** The shipped config with this server's ports. The clock is off unless a group turns it on, so idle tests stay idle. */
function baseConfig(edits = {}) {
  fs.copyFileSync(path.join(REPO, 'src/main/resources/config.yml'), CFG);
  editYaml(CFG, { 'pack.port': packPort, 'pack.bind': '127.0.0.1', 'tiles.http-url': `http://127.0.0.1:${webPort}`, 'hud.clock.enabled': false, ...edits });
}
async function reload(expectPush) {
  const m = s.mark(); s.cmd('bluemapmini reload');
  await s.waitLog(/reloaded/, 8000, m); await sleep(300);
}
/**
 * Runs the pack through the Minecraft client's own font code (fontcheck/FontCheck.java) and returns its
 * glyph advances, or null when no client classpath is set. The client isn't started, only its classes.
 */
function gameAdvances(packBytes) {
  const classpath = process.env.BMH_CLIENT_CP;
  if (!classpath) return null;
  const zip = path.join(ROOT, `fontcheck-${label}.zip`), out = path.join(ROOT, `fontcheck-${label}.json`);
  fs.writeFileSync(zip, packBytes);
  try {
    require('child_process').execFileSync(K.JAVA, ['-cp', classpath, path.join(__dirname, 'fontcheck', 'FontCheck.java'), zip, out], { cwd: ROOT, stdio: 'pipe', timeout: 180000 });
    return JSON.parse(fs.readFileSync(out, 'utf8'));
  } finally {
    fs.rmSync(zip, { force: true }); fs.rmSync(out, { force: true });
  }
}
function packOf(bot) {
  if (!bot.packBytes) return null;
  if (!packs.has(bot.packSha)) packs.set(bot.packSha, new Pack(bot.packBytes));
  return packs.get(bot.packSha);
}
async function join(name, opts = {}) {
  const bot = new Bot(name || `B${++botN}`, port, opts);
  liveBots.push(bot);
  await Promise.race([bot.spawned, sleep(20000)]);
  if (opts.waitPack !== false) await until(() => bot.packBytes, 8000);
  // Settings persist between runs and groups, which is correct, so tests start from known ones.
  if (name === 'Tester' && !opts.keep && !bot.kicked) {
    for (const c of ['/hud mode map', '/hud theme default', '/hud show clock', '/hud on', '/hud size medium', '/hud zoom 2', '/hud offset 0 0', '/hud show coords', '/hud show players', '/hud show markers', '/map zoom 8']) { bot.say(c); await sleep(120); }
  }
  await sleep(opts.settle ?? 800);
  return bot;
}
const tp = async (name, x, y, z, yaw = 180, pitch = 0) => { s.cmd(`tp ${name} ${x} ${y} ${z} ${yaw} ${pitch}`); await sleep(60); };
async function chat(bot, line, re, ms = 3000) {
  const at = bot.chat.length; bot.say(line);
  if (!re) { await sleep(350); return null; }
  return until(() => bot.chat.slice(at).find((m) => re.test(m)), ms);
}
/** Compares the bot's current frame with BlueMap's tiles, cell for cell. */
function compare(bot, map, bpc, x, z, w, h, step, brightness = 1) {
  const pk = packOf(bot), f = bot.hudFrame(pk); if (!f) return { error: 'no frame' };
  const g = gridOf(f, step); if (!g) return { error: 'no grid' };
  if (g.cols !== w || g.rows !== h) return { error: `grid ${g.cols}x${g.rows}, wanted ${w}x${h}` };
  const fx = Math.floor(x / bpc) - Math.floor(w / 2), fz = Math.floor(z / bpc) - Math.floor(h / 2);
  let bad = 0, voids = 0, sample = '';
  for (let cz = 0; cz < h; cz++) for (let cx = 0; cx < w; cx++) {
    const want = truth.cell(map, bpc, fx + cx, fz + cz, brightness), got = g.cells[cz * w + cx];
    if (want === (truth.cell(map, bpc, 99999999, 99999999, brightness))) voids++;
    if (want !== got) { bad++; if (!sample) sample = `(${cx},${cz}) want ${want.toString(16)} got ${got.toString(16)}`; }
  }
  return { bad, voids, total: w * h, gaps: g.gaps, sample, f, g };
}
async function expectMap(name, bot, map, bpc, x, z, w, h, step, brightness = 1, wantRendered = true) {
  let r;
  await until(() => { r = compare(bot, map, bpc, x, z, w, h, step, brightness); return r.bad === 0 && !r.error && r.gaps === 0; }, 10000, 200);
  r = r || {};
  const rendered = r.total ? r.total - r.voids : 0;
  return check(name, !r.error && r.bad === 0 && r.gaps === 0 && (!wantRendered || rendered > r.total * 0.5),
    r.error || `${r.total} cells, ${rendered} rendered, ${r.bad} wrong${r.sample ? ', e.g. ' + r.sample : ''}`);
}
const iconsOf = (f, i) => f.icons.filter((x) => x.icon === i);

/** The readout's text, clock picture and plate in the newest frame. */
function readoutOf(bot) {
  const f = bot.hudFrame(packOf(bot));
  if (!f) return null;
  return { line: textLines(f)[0]?.s || '', clocks: f.clocks, plates: f.plates, text: f.text.filter((t) => t.readout), frame: f };
}
// Clock glyphs are numbered across themes in name order, eight frames each: classic, desert, galaxy, high-contrast.
const THEME_ORDER = ['classic', 'desert', 'galaxy', 'high-contrast'];
const clockGlyph = (theme, minutes) => THEME_ORDER.indexOf(theme) * 8 + Math.floor(minutes * 8 / 1440);

const GROUPS = {
  async clock() {
    section('The clock on the coordinate line');
    baseConfig({ 'hud.clock.enabled': true, 'hud.clock.step-minutes': 5 }); await reload();
    s.cmd('gamerule advance_time false'); s.cmd('time set 6000'); // Tick 6000 is noon.
    const b = await join('Tester');
    await chat(b, '/hud mode coords'); await tp('Tester', 8, 120, -9, 180, 0);
    // Waits for the position too, so a frame from before the teleport never counts.
    const at = async (ticks, want) => { s.cmd(`time set ${ticks}`); return until(() => { const r = readoutOf(b); return r && r.line.startsWith('8 120 -9 ') && r.line.endsWith(want) && r; }, 4000); };
    let r = await at(6000, '12:00');
    check('Noon reads 12:00 after the coordinates', r && r.line === '8 120 -9 N 12:00', r?.line);
    check('with the sun frame of the default theme', r && r.clocks.length === 1 && r.clocks[0].index === clockGlyph('classic', 720), r && JSON.stringify(r.clocks));
    const plate = r && r.plates.map((p) => `${p.x}:${p.width}`).join(',');
    r = await at(18000, '00:00');
    check('Midnight reads 00:00, with the moon', r && r.clocks[0]?.index === clockGlyph('classic', 0), r?.line);
    check('The plate stays put as the time changes', r && r.plates.map((p) => `${p.x}:${p.width}`).join(',') === plate, r && r.plates.map((p) => `${p.x}:${p.width}`).join(','));
    r = await at(0, '06:00');
    check('Tick 0 is 06:00, sunrise', r && r.clocks[0]?.index === clockGlyph('classic', 360), r?.line);
    r = await at(6020, '12:00'); // 12:01, so a running clock couldn't reach 12:05 in the next 1.5 seconds either.
    const before = b.frames.length; await sleep(1500);
    check('Inside one five-minute step nothing new is sent', b.frames.length === before && readoutOf(b).line.endsWith('12:00'), `${b.frames.length - before} frames`);
    r = await at(6100, '12:05');
    check('The next step shows 12:05', r, readoutOf(b)?.line);
    const hidden = await chat(b, '/hud hide clock', /Hiding the clock/);
    r = await until(() => { const x = readoutOf(b); return x && !/:/.test(x.line) && x.clocks.length === 0 && x; }, 3000);
    check('/hud hide clock removes the clock and the time', hidden && r, readoutOf(b)?.line);
    await chat(b, '/hud show clock', /Showing the clock/);
    check('/hud show clock brings it back', await until(() => readoutOf(b)?.line.endsWith('12:05'), 3000), readoutOf(b)?.line);
    s.cmd('execute in minecraft:the_nether run tp Tester 8 70 -9 180 0');
    r = await until(() => { const x = readoutOf(b); return x && x.line.startsWith('8 70 -9') && x; }, 5000);
    check('No clock in the Nether, where time stands still', r && r.clocks.length === 0 && !r.line.includes(':'), r?.line);
    s.cmd('execute in minecraft:overworld run tp Tester 8 120 -9 180 0');
    check('It comes back in the overworld', await until(() => readoutOf(b)?.line.endsWith('12:05'), 5000), readoutOf(b)?.line);
    await chat(b, '/hud mode map');
    r = await until(() => { const x = readoutOf(b); return x && x.frame.runs.length > 0 && x.line.endsWith('12:05') && x; }, 4000);
    check('The minimap shows the clock too', r && r.clocks.length === 1, r?.line);
    await chat(b, '/map');
    r = await until(() => { const x = readoutOf(b); return x && x.frame.fontName === 'bluemapmini:full' && x; }, 4000);
    check('So does the fullscreen map', r && r.clocks.length === 1 && textLines(r.frame).some((l) => l.s.endsWith('12:05')), r && textLines(r.frame).map((l) => l.s).join(' | '));
    check('No missing glyphs anywhere', r && r.frame.missing === 0 && r.frame.net === 0, r && `${r.frame.missing} missing`);
    await chat(b, '/map');
    baseConfig({ 'hud.clock.enabled': false }); await reload();
    check('Turning the clock off on the server hides it', await until(() => { const x = readoutOf(b); return x && x.clocks.length === 0 && !x.line.includes(':'); }, 4000), readoutOf(b)?.line);
    await b.cleanQuit();
  },

  async share() {
    section('Sharing coordinates');
    baseConfig({ 'share.cooldown-seconds': 0 }); await reload();
    await s.run('bmt mkworld otherworld', /BMT mkworld/, 60000);
    const t = await join('Tester');
    const alex = await join('Alex'), blair = await join('Blair'), casey = await join('Casey'), drew = await join('Drew');
    await tp('Tester', 8, 120, -9); await tp('Alex', 20, 120, 20); await tp('Blair', -20, 120, 5);
    s.cmd('execute in minecraft:the_nether run tp Casey 0 70 0');
    s.cmd('execute in minecraft:otherworld run tp Drew 0 100 0');
    await sleep(1500);
    const heard = (bot, from) => bot.chat.slice(from).find((m) => /shared where they are/.test(m));
    const marks = () => [alex, blair, casey, drew].map((b) => b.chat.length);
    const got = (m) => [alex, blair, casey, drew].map((b, i) => heard(b, m[i]) ? b.bot.username : null).filter(Boolean);

    let m = marks(), log = s.mark();
    let sent = await chat(t, '/hud share Alex', /Sent your coordinates/);
    await sleep(500);
    check('Sharing with one person reaches only them', sent && JSON.stringify(got(m)) === '["Alex"]', `${sent} | ${got(m)}`);
    check('They see who sent it, the coordinates and the dimension', heard(alex, m[0]) === 'Tester shared where they are: 8 120 -9 in the Overworld', heard(alex, m[0]));
    check('The sender is told exactly who got it', sent === 'Sent your coordinates (8 120 -9) to 1 player: Alex.', sent);
    check('The share is logged to the console', /Tester shared 8 120 -9 in world with Alex/.test(s.since(log)));

    m = marks();
    sent = await chat(t, '/hud share everyone', /Sent your coordinates/); await sleep(500);
    check('Everyone means the sender\'s world group, the Nether included', JSON.stringify(got(m)) === '["Alex","Blair","Casey"]', `${sent} | ${got(m)}`);
    check('Not someone in another world', !got(m).includes('Drew'));

    m = marks();
    const none = await chat(t, '/hud share Drew', /can receive|Sent/);
    check('Naming someone in another world sends nothing', /Nobody you picked can receive/.test(none || '') && got(m).length === 0, none);

    await chat(alex, '/hud hide shares', /won't receive/);
    m = marks();
    sent = await chat(t, '/hud share everyone', /Sent your coordinates/); await sleep(500);
    check('A player who turned shares off doesn\'t get them', JSON.stringify(got(m)) === '["Blair","Casey"]', got(m).join(','));
    const off = await chat(t, '/hud share Alex', /can receive|Sent/);
    check('Naming them says it can\'t reach them', /Nobody you picked/.test(off || ''), off);
    await chat(alex, '/hud show shares', /You'll receive/);

    s.cmd('bmt perm Blair bluemapmini.share.receive false'); await sleep(300);
    m = marks(); await chat(t, '/hud share everyone', /Sent your coordinates/); await sleep(500);
    check('Without bluemapmini.share.receive nothing arrives', !got(m).includes('Blair') && got(m).includes('Alex'), got(m).join(','));
    s.cmd('bmt perm Blair bluemapmini.share.receive true');
    s.cmd('bmt perm Tester bluemapmini.share.send false'); await sleep(300);
    const denied = await chat(t, '/hud share Alex', /can't share|Sent|Unknown/);
    check('Without bluemapmini.share.send the sender is told no', /can't share your coordinates/.test(denied || ''), denied);
    s.cmd('bmt perm Tester bluemapmini.share.send true');
    // A server that turned sharing off under the old name (say, for a children's group) must stay off after the rename.
    s.cmd('bmt perm Blair bluemaphud.share.receive false'); await sleep(300);
    m = marks(); log = s.mark(); await chat(t, '/hud share everyone', /Sent your coordinates/); await sleep(500);
    check('An old bluemaphud.share.receive false still stops shares arriving', !got(m).includes('Blair') && got(m).includes('Alex'), got(m).join(','));
    check('and the log says to set the new name', /Blair has blueminimap\.share\.receive true but the old bluemaphud\.share\.receive false, so it's false/.test(s.since(log)));
    s.cmd('bmt perm Blair bluemaphud.share.receive true');
    s.cmd('bmt perm Tester bluemaphud.share.send false'); await sleep(300);
    const oldDenied = await chat(t, '/hud share Alex', /can't share|Sent|Unknown/);
    check('An old bluemaphud.share.send false still stops the sender', /can't share your coordinates/.test(oldDenied || ''), oldDenied);
    s.cmd('bmt perm Tester bluemaphud.share.send true'); await sleep(300);
    s.cmd('bmt hidefrom Tester Alex on'); await sleep(300);
    const hidden = await chat(t, '/hud share Alex', /Nobody called|Sent/);
    check('A player hidden from the sender can\'t be named', /Nobody called Alex is online/.test(hidden || ''), hidden);
    s.cmd('bmt hidefrom Tester Alex off'); await sleep(300);

    await s.run('bmt sharefilter remove Blair', /BMT sharefilter/);
    m = marks(); log = s.mark();
    sent = await chat(t, '/hud share everyone', /Sent your coordinates/); await sleep(500);
    const notSent = t.chat.slice(-3).find((x) => /^Not sent to/.test(x));
    check('A rules plugin can remove a recipient', JSON.stringify(got(m)) === '["Alex","Casey"]', got(m).join(','));
    check('It saw everyone offered first', /BMT share from Tester at 8 120 -9 to \[Alex, Blair, Casey\]/.test(s.since(log)), s.since(log).split('\n').find((l) => /BMT share/.test(l)));
    check('The sender hears who was left out', /Not sent to Blair/.test(notSent || ''), notSent);
    await s.run('bmt sharefilter cancel', /BMT sharefilter/);
    m = marks();
    const cancelled = await chat(t, '/hud share Alex', /weren't sent|Sent/);
    check('A rules plugin can stop a share', /weren't sent/.test(cancelled || '') && got(m).length === 0, cancelled);
    await s.run('bmt sharefilter off', /BMT sharefilter/);

    baseConfig({ 'share.cooldown-seconds': 0, 'share.candidates': 'all-online' }); await reload();
    m = marks();
    const fallback = await chat(t, '/hud share Drew', /can receive|Sent/);
    check('all-online with nothing filtering stays in the world group', /Nobody you picked/.test(fallback || '') && got(m).length === 0, fallback);
    await s.run('bmt sharefilter log', /BMT sharefilter/);
    m = marks();
    sent = await chat(t, '/hud share Drew', /can receive|Sent/); await sleep(500);
    check('all-online with a filter reaches other worlds', /to 1 player: Drew/.test(sent || '') && got(m).includes('Drew'), sent);
    await s.run('bmt sharefilter off', /BMT sharefilter/);

    baseConfig({ 'share.cooldown-seconds': 30 }); await reload();
    await chat(t, '/hud share Alex', /Sent/);
    const wait = await chat(t, '/hud share Alex', /share again|Sent/);
    check('A cooldown stops repeated shares', /You can share again in (2\d|30) seconds/.test(wait || ''), wait); // A busy machine slows the round trip.
    baseConfig({ 'share.cooldown-seconds': 0, 'share.enabled': false }); await reload();
    const disabled = await chat(t, '/hud share Alex', /turned off|Sent/);
    check('share.enabled: false turns it off', /turned off on this server/.test(disabled || ''), disabled);

    baseConfig({ 'share.cooldown-seconds': 0 }); await reload();
    const dialogs = [];
    t.bot._client.on('show_dialog', (p) => dialogs.push(p));
    t.say('/hud share');
    await until(() => dialogs.length > 0, 4000);
    const dump = JSON.stringify(dialogs[0] || null);
    console.log('  DIALOG ' + dump.slice(0, 3000));
    check('/hud share opens a dialog', dialogs.length === 1, `${dialogs.length} dialogs`);
    check('It offers the people in the world group, and nobody else', /Alex/.test(dump) && /Blair/.test(dump) && /Casey/.test(dump) && !/Drew/.test(dump));
    check('with a tick box for each and both send buttons', (dump.match(/minecraft:boolean/g) || []).length === 3 && /Send to the people I ticked/.test(dump) && /Send to everyone here \(3\)/.test(dump) && /paper:dialog_click_callback/.test(dump));
    // Pressing the buttons isn't tested: the bot's protocol library has no dialog click packet. They run Paper's
    // own callbacks into the same share code the commands above test.
    for (const b of [alex, blair, casey, drew]) await b.cleanQuit();
    await t.cleanQuit();
  },

  /** Measures what the clock costs a player who stands still. Not run by default; see docs/decisions.md. */
  async clockrate() {
    section('What the clock costs an idle player');
    s.cmd('gamerule advance_time true');
    const results = [];
    for (const step of [1, 5, 10]) {
      baseConfig({ 'hud.clock.enabled': true, 'hud.clock.step-minutes': step }); await reload();
      for (const mode of ['map', 'coords']) {
        const b = await join('Tester');
        await chat(b, `/hud mode ${mode}`); await tp('Tester', 8, 120, -9, 180, 0); await sleep(2000);
        const f0 = b.frames.length, b0 = b.barBytes.length, t0 = Date.now();
        await sleep(30000);
        const seconds = (Date.now() - t0) / 1000, frames = b.frames.length - f0;
        const bytes = b.barBytes.slice(b0).reduce((sum, x) => sum + x.zlib, 0);
        const h = await s.run('bmt health', /BMT mspt ([\d.]+)/);
        const expected = seconds / (step * 50 / 60); // An in-game minute is 50/60 of a real second.
        results.push({ step, mode, perSecond: frames / seconds, bytesPerSecond: bytes / seconds, mspt: h ? +h[1] : null });
        console.log(`  step ${step}, ${mode}: ${frames} updates in ${seconds.toFixed(0)} s (${(frames / seconds).toFixed(2)}/s, about ${expected.toFixed(0)} expected), ${(bytes / seconds).toFixed(0)} B/s compressed, tick ${h?.[1]?.slice(0, 5)} ms`);
        check(`Step ${step} (${mode}) sends no more than one update per step`, frames <= expected + 2, `${frames} vs ${expected.toFixed(1)}`);
        await b.cleanQuit();
      }
    }
    s.cmd('gamerule advance_time false');
    fs.writeFileSync(path.join(ROOT, `clockrate-${label}.json`), JSON.stringify(results, null, 1));
  },

  async themes() {
    section('Themes');
    baseConfig({ 'hud.clock.enabled': true }); await reload();
    s.cmd('gamerule advance_time false'); s.cmd('time set 6000');
    const b = await join('Tester');
    await chat(b, '/hud mode coords'); await tp('Tester', 8, 120, -9, 180, 0);
    const list = await chat(b, '/hud theme', /Themes:/);
    check('/hud theme lists every theme and marks the current one', list && /Classic \(yours\)/.test(list) && /Desert/.test(list) && /Galaxy/.test(list) && /High Contrast/.test(list), list);
    const colours = { classic: [0x000000, 0xFFFFFF, 0xBEC6D2], desert: [0x1E1208, 0xFFFFFF, 0xE2CDB0], galaxy: [0x140A28, 0xFFFFFF, 0xCFC6F2], 'high-contrast': [0x000000, 0xFFFFFF, 0xFFFF00] };
    for (const theme of THEME_ORDER) {
      const said = await chat(b, `/hud theme ${theme}`, /Theme set to/);
      const r = await until(() => { const x = readoutOf(b); return x && x.clocks[0]?.index === clockGlyph(theme, 720) && x; }, 4000);
      const [plate, text, facing] = colours[theme];
      const ok = r && r.plates.every((p) => p.color === plate) && r.text.some((t) => t.color === text) && r.text.some((t) => t.color === facing);
      check(`${theme}: its clock, plate and colours`, said && ok, r && `plate ${r.plates.map((p) => p.color.toString(16)).join(',')} text ${[...new Set(r.text.map((t) => t.color.toString(16)))].join(',')}`);
    }
    const plateWidths = new Set();
    for (const theme of THEME_ORDER) { await chat(b, `/hud theme ${theme}`, /Theme set to/); await sleep(500); plateWidths.add(readoutOf(b).plates.map((p) => p.width).join('+')); }
    check('Every theme draws the same size line', plateWidths.size === 1, [...plateWidths].join(' | '));
    await chat(b, '/hud theme desert', /Theme set to/);
    const bad = await chat(b, '/hud theme sparkles', /no theme called sparkles/);
    check('An unknown theme says what exists', bad && /classic, desert, galaxy, high-contrast/.test(bad), bad);
    await b.cleanQuit();
    const back = await join('Tester', { keep: true });
    check('The choice is saved', await until(() => readoutOf(back)?.clocks[0]?.index === clockGlyph('desert', 720), 5000), JSON.stringify(readoutOf(back)?.clocks));
    const reset = await chat(back, '/hud theme default', /server's theme, Classic/);
    check('/hud theme default goes back to the server\'s', reset && await until(() => readoutOf(back)?.clocks[0]?.index === clockGlyph('classic', 720), 4000), reset);
    await back.cleanQuit();

    const m = s.mark();
    baseConfig({ 'hud.clock.enabled': true, 'hud.theme': 'galaxy' }); await reload();
    const fresh = await join(`T${Date.now() % 100000}`);
    await chat(fresh, '/hud mode coords');
    check('A new player gets the server\'s default theme', await until(() => readoutOf(fresh)?.clocks[0]?.index === clockGlyph('galaxy', 720), 5000), JSON.stringify(readoutOf(fresh)?.clocks));
    check('The log lists the themes and the default', /Themes: classic, desert, galaxy, high-contrast\. New players get galaxy\./.test(s.since(m)));
    await fresh.cleanQuit();
    const m2 = s.mark();
    baseConfig({ 'hud.clock.enabled': true, 'hud.theme': 'sparkles' }); await reload();
    check('An unknown default theme is reported and falls back to classic', /no theme called sparkles, so the default is classic/.test(s.since(m2)), s.since(m2).split('\n').find((l) => /theme/i.test(l))?.slice(0, 160));

    const folder = s.path('plugins/BlueMapMini/themes');
    fs.mkdirSync(folder, { recursive: true });
    const custom = JSON.parse(fs.readFileSync(path.join(REPO, 'src/main/resources/themes/classic.json'), 'utf8'));
    custom.name = 'Ocean'; custom.colors.plate = '#001A33';
    fs.writeFileSync(path.join(folder, 'ocean.json'), JSON.stringify(custom));
    fs.writeFileSync(path.join(folder, 'broken.json'), '{ nope');
    const m3 = s.mark();
    baseConfig({ 'hud.clock.enabled': true }); await reload();
    check('A server theme in plugins/BlueMapMini/themes is added', /Themes: classic, desert, galaxy, high-contrast, ocean\./.test(s.since(m3)), s.since(m3).split('\n').find((l) => /Themes:/.test(l))?.slice(0, 160));
    check('A broken one is reported and skipped', /Theme broken couldn't be loaded/.test(s.since(m3)));
    check('A hard-to-read one is reported, and still loads', /Theme ocean is hard to read in places: facing #BEC6D2/.test(s.since(m3)));
    const o = await join('Tester');
    await chat(o, '/hud theme ocean', /Theme set to Ocean/);
    check('Players can pick it', await until(() => readoutOf(o)?.plates.every((p) => p.color === 0x001A33), 4000));
    await chat(o, '/hud theme default');
    await o.cleanQuit();
    fs.rmSync(folder, { recursive: true, force: true });
    baseConfig(); await reload();
  },

  async onebar() {
    section('Only ever one boss bar');
    baseConfig(); await reload();
    const b = await join('Tester');
    await tp('Tester', 0, 120, 0);
    const pk = () => packOf(b);
    await until(() => b.hudFrame(pk()), 5000);
    const one = (name) => check(name, b.bars.size === 1 && b.maxBars === 1, `now ${b.bars.size}, most ever ${b.maxBars}`);
    one('Minimap with coordinates uses one bar');
    const inTitle = await until(() => { const fr = b.hudFrame(pk()); return fr && fr.runs.length > 0 && textLines(fr).some((l) => l.s.startsWith('0 120 0 ')); }, 3000);
    check('The coordinates are inside that same bar title', inTitle, textLines(b.hudFrame(pk()))[0]?.s);
    for (const c of ['/hud hide coords', '/hud show coords', '/hud size large', '/hud size medium', '/hud zoom 4', '/hud zoom 2', '/hud offset 10 8', '/hud offset 0 0', '/hud off', '/hud on']) { await chat(b, c); await sleep(450); }
    await until(() => b.bars.size === 1, 3000); one('Every /hud option keeps it to one');
    // Coordinates-only mode must reuse the same bar, never add one beside it.
    await chat(b, '/hud mode coords', /only coordinates/);
    const co = await until(() => { const fr = b.hudFrame(pk()); return fr && fr.runs.length === 0 && fr.icons.length === 0 && textLines(fr).some((l) => l.s.startsWith('0 120 0 ')) && fr; }, 3000);
    check('Coordinates-only draws just the line, in that same bar', co && b.bars.size === 1 && b.maxBars === 1, co ? textLines(co)[0].s : 'no coordinates-only frame');
    for (const c of ['/hud off', '/hud on', '/hud offset 10 8', '/hud offset 0 0', '/hud hide coords', '/hud size large', '/hud zoom 4', '/hud mode map', '/hud mode coords']) { await chat(b, c); await sleep(450); }
    await until(() => b.bars.size === 1, 3000); one('Every option in coordinates-only mode keeps it to one');
    await chat(b, '/map'); await until(() => b.hudFrame(pk())?.fontName === 'bluemapmini:full', 3000); one('Fullscreen from coordinates-only uses the same bar');
    await chat(b, '/map');
    check('Closing it goes back to coordinates only', await until(() => { const fr = b.hudFrame(pk()); return fr && fr.fontName === 'bluemapmini:hud' && fr.runs.length === 0 && fr.text.length > 0; }, 3000));
    b.sneak(true); await sleep(150); b.swapF(); await sleep(700); b.swapF(); await sleep(700); b.sneak(false); one('Sneak + F from coordinates-only uses the same bar');
    s.cmd('gamemode spectator Tester'); await sleep(1500); s.cmd('gamemode creative Tester'); await sleep(1500); one('Auto-hide and return in coordinates-only keeps it to one');
    await chat(b, '/hud mode map'); await chat(b, '/hud size medium'); await chat(b, '/hud zoom 2');
    await until(() => b.hudFrame(pk())?.runs.length > 0, 3000); one('Switching back to the map keeps it to one');
    await chat(b, '/map'); await until(() => b.hudFrame(pk())?.fontName === 'bluemapmini:full', 3000); one('Fullscreen uses the same bar');
    await chat(b, '/map'); await until(() => b.hudFrame(pk())?.fontName === 'bluemapmini:hud', 3000);
    b.sneak(true); await sleep(150); b.swapF(); await sleep(700); b.swapF(); await sleep(700); b.sneak(false); one('Sneak + F uses the same bar');
    // Paper makes a new bar ID after every viewer has gone, as with /hud off, so IDs alone prove nothing.
    // What matters is that a new one is never added while another is showing.
    let showing = 0, overlap = 0;
    for (const e of b.barEvents) { if (e.action === 0) { showing++; if (showing > 1) overlap++; } if (e.action === 1) showing--; }
    check('No bar is ever added while another is showing', overlap === 0, `${b.barEvents.filter((e) => e.action === 0).length} adds, ${overlap} overlaps`);
    await reload(); await sleep(800); one('/bluemapmini reload keeps it to one');
    baseConfig({ 'pack.boss-bar-color': 'blue' }); await reload(); await until(() => b.packSha === b.pushes.at(-1).hash, 4000); await sleep(1200); one('A pack-changing reload keeps it to one');
    baseConfig(); await reload(); await until(() => b.packSha === b.pushes.at(-1).hash, 4000); await sleep(1200);
    s.cmd('bluemap reload'); await sleep(9000); one('/bluemap reload keeps it to one');
    s.cmd(`bmt magicpack Tester http://127.0.0.1:${packPort}/nope.zip`); await sleep(3500); await until(() => b.bars.size === 1, 6000); await sleep(800); one('A pack wipe and re-send keeps it to one');
    s.cmd('gamemode spectator Tester'); await sleep(1500); s.cmd('gamemode creative Tester'); await sleep(1500); one('Auto-hide and return keeps it to one');
    const rm = s.mark(); s.cmd('reload confirm'); await s.waitLog(/Reload complete|complete/i, 60000, rm); await sleep(7000);
    await until(() => b.bars.size === 1, 6000); one('A full /reload leaves exactly one, with none orphaned');
    await b.cleanQuit();
    const c = await join('Tester'); await until(() => c.bars.size === 1, 5000);
    check('Rejoining starts with one', c.bars.size === 1 && c.maxBars === 1, c.maxBars);
    await chat(c, '/hud mode coords', /only coordinates/); await sleep(600);
    await c.cleanQuit();
    const d = await join('Tester', { keep: true }); await until(() => d.bars.size === 1 && d.hudFrame(packOf(d)), 5000);
    const df = d.hudFrame(packOf(d));
    check('Rejoining in coordinates-only starts with one bar, still coordinates only', d.bars.size === 1 && d.maxBars === 1 && df && df.runs.length === 0 && df.text.length > 0, `${d.maxBars} bars`);
    await chat(d, '/hud mode map'); await d.cleanQuit();
    await s.stop(); await s.start();
  },

  async coordsonly() {
    section('Coordinates-only mode');
    baseConfig(); await reload();
    const b = await join('Tester');
    const pk = () => packOf(b);
    await tp('Tester', 8, 120, -9, 180);
    await chat(b, '/hud mode coords', /only coordinates/);
    const only = () => { const fr = b.hudFrame(pk()); return fr && fr.runs.length === 0 && fr.icons.length === 0 ? fr : null; };
    const lineOf = () => { const fr = only(); return fr ? textLines(fr)[0]?.s : null; };
    check('Shows X, Y, Z and facing alone', await until(() => lineOf() === '8 120 -9 N', 3000), lineOf());
    let fr = only();
    const tx = fr.text, xs = tx.map((t) => t.x), mid = (Math.min(...xs) + Math.max(...tx.map((t) => t.x + t.adv))) / 2;
    check('It is centred on screen', fr.net === 0 && Math.abs(mid) <= 3, `net ${fr.net}, middle ${mid}`);
    check('It sits on a dark plate near the top', fr.plates.length > 0 && fr.plates.every((p) => p.color === 0) && Math.min(...fr.plates.map((p) => p.top)) < 20, `plate top ${Math.min(...fr.plates.map((p) => p.top))}`);
    check('It is far smaller than a map frame', JSON.stringify(b.bars.values().next().value.title).length < 1500, JSON.stringify(b.bars.values().next().value.title).length + ' chars');
    await tp('Tester', 8, 64, -9, 90);
    check('Moving and turning update it', await until(() => lineOf() === '8 64 -9 W', 3000), lineOf());
    await sleep(800); let n = b.frames.length; await tp('Tester', 8, 64, -9, 92); await sleep(1200);
    check('A small turn sends nothing', b.frames.length === n, b.frames.length - n);
    const top0 = Math.min(...only().text.map((t) => t.top)), x0 = Math.min(...only().text.map((t) => t.x));
    await chat(b, '/hud offset 30 16', /Coordinates moved/);
    await until(() => only() && Math.min(...only().text.map((t) => t.x)) !== x0, 3000);
    const top1 = Math.min(...only().text.map((t) => t.top)), x1 = Math.min(...only().text.map((t) => t.x));
    check('/hud offset moves it', x1 - x0 === 30 && top1 - top0 === 16, `x +${x1 - x0}, y +${top1 - top0}`);
    await chat(b, '/hud offset 0 0', /moved/);
    const why = await chat(b, '/hud hide coords', /all that's showing/);
    check('/hud hide coords explains instead of hiding everything', why && await until(() => lineOf(), 1500), why);
    const off = await chat(b, '/hud off', /Coordinates off/);
    check('/hud off hides it', off && await until(() => !b.hudFrame(pk()), 3000), off);
    await chat(b, '/hud on', /Coordinates on/);
    check('/hud on brings it back', await until(() => lineOf(), 3000));
    s.cmd('gamemode spectator Tester'); 
    check('Spectator mode hides it, like the minimap', await until(() => !b.hudFrame(pk()), 4000));
    s.cmd('gamemode creative Tester');
    check('It returns after spectator', await until(() => lineOf(), 4000));

    // Boss bars, and a dimension BlueMap doesn't map.
    s.cmd('execute in minecraft:the_end run tp Tester 0 100 0'); await sleep(1500);
    check('The Ender Dragon bar hides it, so there is never a second bar', await until(() => !b.hudFrame(pk()), 6000));
    baseConfig({ 'hud.auto-hide.other-boss-bar': false }); await reload();
    check('With that auto-hide off it shows in the End', await until(() => lineOf()?.startsWith('0 100 0 '), 5000), lineOf());
    const confs = s.path('plugins/BlueMap/maps'); const end = fs.readdirSync(confs).find((f) => /end/i.test(f));
    fs.renameSync(path.join(confs, end), path.join(confs, end + '.off'));
    try {
      const m = s.mark(); s.cmd('bluemap reload'); await s.waitLog(/Reloaded|reloaded|Reload/, 30000, m - 200); await sleep(6000);
      check('It still shows where BlueMap has no map', await until(() => lineOf()?.startsWith('0 100 0 '), 6000), lineOf());
      await chat(b, '/map', /no map/);
      await sleep(800);
      check('/map there explains and leaves the coordinates up', !!lineOf() && b.hudFrame(pk()).fontName === 'bluemapmini:hud', lineOf());
      await chat(b, '/map');
      const noMap = await chat(b, '/hud mode map', /no map/);
      check('/hud mode map there explains why nothing shows', noMap && await until(() => !b.hudFrame(pk()), 3000), noMap);
      await chat(b, '/hud mode coords', /only coordinates/);
      check('/hud mode coords brings it back there', await until(() => lineOf(), 3000));
    } finally {
      fs.renameSync(path.join(confs, end + '.off'), path.join(confs, end));
      s.cmd('bluemap reload'); await sleep(9000);
    }
    s.cmd('execute in minecraft:overworld run tp Tester 8 120 -9 180 0'); await sleep(1200);
    baseConfig(); await reload();
    const desc = () => { const f = b.hudFrame(pk()); return f ? `${f.fontName} runs ${f.runs.length} text ${textLines(f).map((l) => l.s).join('|')} bars ${b.bars.size}` : `no frame, bars ${b.bars.size}`; };
    check('Back in the Overworld it is still coordinates only', await until(() => lineOf() === '8 120 -9 N', 4000), desc());

    // Action bar channel.
    baseConfig({ 'hud.channel': 'action-bar' }); await reload(); await sleep(1500);
    const adds = b.barEvents.length;
    const lastAb = () => { const a = b.actionBars.at(-1); return a && decodeFrame(a.text, pk()); };
    check('On the action bar channel it arrives there with no map', await until(() => { const f = lastAb(); return f && !f.empty && f.runs.length === 0 && textLines(f)[0]?.s === '8 120 -9 N'; }, 4000), lastAb() && textLines(lastAb())[0]?.s);
    check('No boss bar is shown in that mode', b.bars.size === 0, b.bars.size);
    const abTop = Math.max(...lastAb().text.map((t) => t.top));
    check('It sits above the text line, clear of the hotbar', abTop < -8, abTop);
    await chat(b, '/map'); await until(() => b.hudFrame(pk())?.fontName === 'bluemapmini:full', 3000);
    check('Fullscreen still uses one boss bar', b.bars.size === 1);
    await chat(b, '/map'); await until(() => b.bars.size === 0, 3000);
    check('Closing it returns to the action bar with no bar left', b.bars.size === 0 && b.barEvents.slice(adds).filter((e) => e.action === 0).length === 1);
    baseConfig(); await reload();

    await chat(b, '/hud mode map', /Showing the minimap/);
    check('/hud mode map brings the map back', await until(() => b.hudFrame(pk())?.runs.length > 0, 4000));

    // The server default only applies to players who haven't chosen.
    baseConfig({ 'hud.default-mode': 'coords' }); await reload();
    const fresh = await join(`New${Date.now() % 100000}`);
    // Where they spawn varies, so this checks the shape: a coordinate line and no map.
    const freshLine = await until(() => { const f = fresh.hudFrame(packOf(fresh)); return f && f.runs.length === 0 && f.icons.length === 0 ? textLines(f)[0]?.s : null; }, 5000);
    check('With default-mode coords, a new player starts with coordinates only', /^-?\d+ -?\d+ -?\d+ (N|NE|E|SE|S|SW|W|NW)$/.test(freshLine || ''), freshLine);
    check('A player who chose the map keeps it', await until(() => b.hudFrame(pk())?.runs.length > 0, 3000));
    await fresh.cleanQuit();
    baseConfig(); await reload();
    // The dragon's own bar is expected while auto-hide is off; what must never happen is a second bar of ours.
    const ours = new Set(b.frames.filter((f) => JSON.stringify(f.title).includes('bluemapmini')).map((f) => f.id));
    let shown = 0, over = 0, most = 0;
    for (const e of b.barEvents) { if (!ours.has(e.id)) continue; if (e.action === 0) { shown++; most = Math.max(most, shown); if (shown > 1) over++; } if (e.action === 1) shown--; }
    check('Never a second bar of ours at any point in this group', over === 0 && most <= 1, `${over} overlaps, most ${most}, ${ours.size} bar IDs used`);
    await b.cleanQuit();
  },

  async pack() {
    section('Pack delivery');
    baseConfig(); await reload();
    const b = await join('Tester');
    check('Pack is pushed once on join', b.pushes.length === 1, b.pushes.length);
    check('Pack is marked required', b.pushes[0]?.forced === true);
    check('Pack URL uses the address the player connected to', /^http:\/\/127\.0\.0\.1:/.test(b.pushes[0]?.url || ''), b.pushes[0]?.url);
    check('Pack downloads with a matching SHA-1', b.packSha === b.pushes[0]?.hash, b.packSha);
    // pack.bind: 127.0.0.1 (every group uses it). The download above came through loopback; this Mac's own
    // network address must be refused, so the port never reaches the LAN or the internet.
    const net = require('net'), os = require('os');
    const lan = Object.values(os.networkInterfaces()).flat().find((i) => i && !i.internal && i.family === 'IPv4')?.address;
    const reach = (host) => new Promise((res) => { const c = net.connect(packPort, host); c.once('connect', () => { c.destroy(); res(true); }); c.once('error', () => res(false)); setTimeout(() => { c.destroy(); res(false); }, 3000); });
    check('pack.bind 127.0.0.1: the pack port answers on loopback', await reach('127.0.0.1'));
    if (!lan) skip("pack.bind 127.0.0.1: refused on this machine's network address", 'no non-loopback IPv4 address here');
    else check("pack.bind 127.0.0.1: refused on this machine's network address", !(await reach(lan)), lan);
    const pk = packOf(b);
    const adv = gameAdvances(b.packBytes);
    if (!adv) skip("This test's advances match the game's own font code", 'set BMH_CLIENT_CP to a Minecraft client classpath to run it');
    else {
      let wrong = 0, n = 0;
      for (const [font, table] of Object.entries(adv)) for (const [c, a] of Object.entries(table)) { n++; if (pk.fonts[font].get(+c)?.advance !== a) wrong++; }
      check("This test's advances match the game's own font code", n > 9000 && wrong === 0, `${n} glyphs, ${wrong} differ`);
    }
    await until(() => b.barEvents.length > 0, 8000);
    const add = b.barEvents.find((e) => e.action === 0);
    check('Boss bar is pink with zero progress', add && add.color === 0 && add.health === 0, JSON.stringify(add));
    const f = b.hudFrame(pk);
    check('Frame uses our font with shadow off', f && f.fontName === 'bluemapmini:hud' && f.shadow === 0, f && f.fontName + ' shadow ' + f.shadow);
    check('Frame has no missing glyphs and nets to zero', f && f.missing === 0 && f.net === 0, f && `missing ${f.missing}, net ${f.net}`);
    await b.cleanQuit();
  },

  async terrain() {
    section('Real terrain matches BlueMap tiles');
    baseConfig(); await reload();
    const b = await join('Tester');
    s.cmd('op Tester'); await sleep(200);
    await tp('Tester', 40, 120, -30);
    for (const [bpc, x, z] of [[1, 40, -30], [2, 40, -30], [4, 17, 9], [8, -60, 50], [16, 0, 0], [32, 0, 0], [64, 0, 0]]) {
      await tp('Tester', x, 120, z);
      await chat(b, `/hud zoom ${bpc}`, /zoom is now/);
      // Lower levels only exist in part of this small world, so don't insist on coverage when zoomed out.
      await expectMap(`Minimap at ${bpc} block(s) per cell`, b, OVER, bpc, x, z, 32, 32, 2, 1, bpc <= 8);
    }
    await chat(b, '/hud zoom 2', /zoom is now/);
    for (const [name, cells] of [['small', 24], ['large', 40], ['medium', 32]]) {
      await chat(b, `/hud size ${name}`, /size is now/);
      await expectMap(`Size ${name} draws ${cells} cells`, b, OVER, 2, 0, 0, cells, cells, 2, 1, false);
    }
    const far = 40000 + Math.floor(Math.random() * 20000) * 2; truth.clear();
    await tp('Tester', far, 120, far); await sleep(800);
    await expectMap('Unrendered area is all void colour', b, OVER, 2, far, far, 32, 32, 2, 1, false);
    await tp('Tester', -7, 120, -7);
    await expectMap('Negative coordinates line up', b, OVER, 2, -7, -7, 32, 32, 2);
    await chat(b, '/map zoom 4', /zoom is now/);
    await chat(b, '/map');
    await expectMap('Fullscreen matches tiles, darkened', b, OVER, 4, -7, -7, 96, 54, 3, 0.75);
    const full = b.hudFrame(packOf(b));
    check('Fullscreen uses its own font', full?.fontName === 'bluemapmini:full', full?.fontName);
    await chat(b, '/map'); await sleep(600);
    await b.cleanQuit();
  },

  async updates() {
    section('Updates only on change');
    baseConfig(); await reload();
    const b = await join('Tester');
    await chat(b, '/hud hide coords', /Hiding/);
    await tp('Tester', 21, 120, 21); await sleep(1500);
    let n = b.frames.length; await sleep(3000);
    check('Standing still sends nothing for 3 s', b.frames.length === n, b.frames.length - n);
    n = b.frames.length; await tp('Tester', 21, 120, 21, 183); await sleep(1200);
    check('A small turn inside one heading sends nothing', b.frames.length === n, b.frames.length - n);
    n = b.frames.length; await tp('Tester', 21.8, 120, 21.8, 183); await sleep(1200);
    check('Moving inside one cell sends nothing', b.frames.length === n, b.frames.length - n);
    n = b.frames.length; await tp('Tester', 24, 120, 21, 183); await sleep(1200);
    check('Moving a cell sends one frame', b.frames.length === n + 1, b.frames.length - n);
    n = b.frames.length; await tp('Tester', 24, 120, 21, 90); await sleep(1200);
    check('Turning to a new heading sends one frame', b.frames.length === n + 1, b.frames.length - n);
    n = b.frames.length;
    for (let i = 0; i < 40; i++) { await tp('Tester', 30 + i * 2, 120, 21); await sleep(10); }
    await sleep(1000);
    const rate = (b.frames.length - n) / Math.max(1, (2400 + 1000) / 1000);
    check('Fast movement stays at or under 4 updates a second', rate <= 4.5, rate.toFixed(1) + '/s');
    await chat(b, '/hud show coords', /Showing/);
    await sleep(600);
    await b.cleanQuit();
  },

  async heading() {
    section('Heading, north marker, coordinates');
    baseConfig(); await reload();
    const b = await join('Tester');
    await tp('Tester', 8, 120, -9, 180); await sleep(800);
    const pk = packOf(b);
    for (const [yaw, heading, name] of [[180, 0, 'N'], [-135, 2, 'NE'], [-90, 4, 'E'], [-45, 6, 'SE'], [0, 8, 'S'], [45, 10, 'SW'], [90, 12, 'W'], [135, 14, 'NW']]) {
      await tp('Tester', 8, 120, -9, yaw);
      const ok = await until(() => { const f = b.hudFrame(pk); return f && f.icons.some((i) => i.icon === heading) && textLines(f).some((l) => l.s.endsWith(' ' + name)); }, 3000);
      const f = b.hudFrame(pk), g = gridOf(f, 2), arrow = f.icons.find((i) => i.icon === heading);
      const cell = arrow && iconCell(arrow, g);
      check(`Yaw ${yaw} shows the ${name} arrow at the centre`, ok && cell && cell.x === 16 && cell.z === 16, cell && JSON.stringify(cell));
    }
    const f = b.hudFrame(pk), g = gridOf(f, 2);
    const north = iconsOf(f, ICON.NORTH)[0];
    check('North marker is in the top-left corner', north && iconCell(north, g).x <= 3 && iconCell(north, g).z <= 3, north && JSON.stringify(iconCell(north, g)));
    const line = textLines(f)[0];
    check('Coordinates read the real X, Y and Z', line && line.s.startsWith('8 120 -9 '), line && line.s);
    const pl = f.plates, tx = f.text.filter((t) => t.readout);
    check('The readout sits on a black plate that covers it', pl.length > 0 && pl.every((p) => p.color === 0) && tx.every((t) => t.x >= Math.min(...pl.map((p) => p.x)) && t.x + 4 <= Math.max(...pl.map((p) => p.x + p.width))), `${pl.length} plate glyphs`);
    check('The plate leaves a gap below the map', pl.every((p) => p.top >= g.top + g.rows * 2 + 1), pl[0]?.top);
    const xs = tx.map((t) => t.x), mid = (Math.min(...xs) + Math.max(...xs)) / 2;
    check('The readout is centred under the map', Math.abs(mid - (g.left + g.cols)) <= 3, `${mid} vs ${g.left + g.cols}`);
    check('Numbers are white and the facing is dimmer', tx.some((t) => t.color === 0xFFFFFF) && tx.filter((t) => 'NESW'.includes(t.ch)).every((t) => t.color === 0xBEC6D2));
    await tp('Tester', 8, 64, -9, 135);
    check('Height updates when moving vertically', await until(() => textLines(b.hudFrame(pk))[0]?.s.startsWith('8 64 -9 '), 3000), textLines(b.hudFrame(pk))[0]?.s);
    await chat(b, '/hud hide coords', /Hiding/);
    const hid = await until(() => { const fr = b.hudFrame(pk); return fr.text.length === 0 && fr.plates.length === 0; }, 3000);
    check('/hud hide coords removes both the text and the plate', hid);
    const nn = b.frames.length; await tp('Tester', 8, 100, -9, 135); await sleep(1200);
    check('With coordinates hidden, moving vertically sends nothing', b.frames.length === nn, b.frames.length - nn);
    await chat(b, '/map');
    const fullLine = await until(() => { const fr = b.hudFrame(pk); return fr.fontName === 'bluemapmini:full' && textLines(fr).find((l) => l.s.startsWith('8 100 -9 ')); }, 4000);
    check('Fullscreen shows X, Y, Z and facing even with them hidden on the minimap', fullLine, fullLine?.s);
    const fr = b.hudFrame(pk), gf = gridOf(fr, 3);
    const legend = textLines(fr).map((l) => l.s).join(' | ');
    check('Fullscreen keeps the zoom and counts legend', /1:8/.test(legend) && /PLAYERS 0/.test(legend) && /MARKERS \d/.test(legend), legend);
    check('The fullscreen line is on plates, inside the map width', fr.plates.length > 0 && fr.plates.every((p) => p.x >= gf.left && p.x + p.width <= gf.left + gf.cols * 3), fr.plates.length);
    await chat(b, '/map'); await chat(b, '/hud show coords', /Showing/);
    check('Coordinates sit below the map', line && line.top >= g.top + g.rows * 2, line && line.top);
    await b.cleanQuit();
  },

  async players() {
    section('Other players');
    baseConfig({ 'players.radius': 200 }); await reload();
    const b = await join('Tester'), o = await join('Other');
    await tp('Tester', 0, 120, 0); await tp('Other', 20, 120, 10);
    const pk = packOf(b);
    const dots = () => iconsOf(b.hudFrame(pk) || { icons: [] }, ICON.PLAYER);
    const seen = (want, ms = 3000) => until(() => (want ? dots().length === 1 : dots().length === 0), ms);
    await seen(true);
    const inPlace = await until(() => { const fr = b.hudFrame(pk), gr = fr && gridOf(fr, 2), dd = fr && iconsOf(fr, ICON.PLAYER)[0], cc = dd && iconCell(dd, gr); return cc && cc.x === 26 && cc.z === 21 && cc; }, 4000);
    const g = gridOf(b.hudFrame(pk), 2), d = dots()[0], cell = d && iconCell(d, g);
    check('A nearby player appears in the right cell', inPlace, cell && JSON.stringify(cell));
    const step = async (name, on, off) => {
      await on(); const hidden = await seen(false); await off(); const back = await seen(true);
      check(`${name} hides them, and undoing it brings them back`, hidden && back, `hidden ${!!hidden}, back ${!!back}`);
    };
    await step('Vanish metadata', () => s.cmd('bmt vanish Other on'), () => s.cmd('bmt vanish Other off'));
    await step('hidePlayer', () => s.cmd('bmt hidefrom Tester Other on'), () => s.cmd('bmt hidefrom Tester Other off'));
    await step('Spectator mode', () => s.cmd('gamemode spectator Other'), () => s.cmd('gamemode creative Other'));
    await step('Invisibility', () => s.cmd('effect give Other invisibility 600 0 true'), () => s.cmd('effect clear Other'));
    await step('Hidden in BlueMap', () => s.cmd('bmt bmhide Other on'), () => s.cmd('bmt bmhide Other off'));
    await step('/hud hide players', () => chat(b, '/hud hide players'), () => chat(b, '/hud show players'));
    await step('Losing bluemapmini.seeplayers', () => s.cmd('bmt perm Tester bluemapmini.seeplayers false'), () => s.cmd('bmt perm Tester bluemapmini.seeplayers true'));
    await step('Moving past the radius', () => tp('Other', 500, 120, 0), () => tp('Other', 20, 120, 10));
    s.cmd('execute in minecraft:the_nether run tp Other 20 120 10');
    check('A player in another dimension is not shown', await seen(false));
    s.cmd(`execute in minecraft:overworld run tp Other 20 120 10`); await seen(true);
    await tp('Other', 150, 120, 0);
    check('No edge arrow for off-map players by default', await seen(false));
    baseConfig({ 'players.radius': 200, 'players.edge-arrows': true }); await reload(); await sleep(1500);
    const arrow = await until(() => { const f = b.hudFrame(packOf(b)); return f && f.icons.find((i) => i.icon === 4 && i.color === 0x66D9FF); }, 3000);
    check('With edge arrows on, an arrow points east at the edge', arrow && iconCell(arrow, gridOf(b.hudFrame(packOf(b)), 2)).x >= 28, arrow && JSON.stringify(arrow));
    await o.cleanQuit(); await b.cleanQuit();
  },

  async markers() {
    section('Markers and death point');
    baseConfig(); await reload();
    const b = await join('Tester');
    s.cmd('bmt poidel world testset'); s.cmd('bmt poidel world other');
    await tp('Tester', 0, 120, 0);
    const pk = packOf(b);
    const pois = () => iconsOf(b.hudFrame(pk) || { icons: [] }, ICON.POI);
    await s.run(`bmt poi world testset p1 10 70 -20 My Base`, /poi added/);
    check('A BlueMap POI appears within 6 s', await until(() => pois().length === 1, 8000));
    const g = gridOf(b.hudFrame(pk), 2), cell = pois()[0] && iconCell(pois()[0], g);
    check('It sits in the right cell', cell && cell.x === 21 && cell.z === 6, cell && JSON.stringify(cell));
    check('No label on the minimap', !textLines(b.hudFrame(pk)).some((l) => l.s.includes('BASE')));
    await chat(b, '/map zoom 2', /zoom/); await chat(b, '/map');
    const lab = await until(() => { const f = b.hudFrame(pk); return f.fontName === 'bluemapmini:full' && textLines(f).find((l) => l.s.includes('MY BASE')); }, 4000);
    check('Fullscreen shows the label', lab, lab && lab.s);
    await s.run(`bmt poi world testset p2 12 70 -22 Café «x» 日本 & <b>`, /poi added/); await sleep(5500);
    const f2 = b.hudFrame(pk);
    check('Accents, symbols and CJK in a label are safe', f2.missing === 0 && f2.net === 0, textLines(f2).map((l) => l.s).join(' | ').slice(0, 120));
    await s.run(`bmt poi world testset p3 14 70 -24 ${'very long label '.repeat(20)}`, /poi added/); await sleep(5500);
    const f3 = b.hudFrame(pk);
    check('A very long label stays aligned', f3.net === 0 && f3.missing === 0, 'net ' + f3.net);
    await chat(b, '/map'); await sleep(500);
    s.cmd('bmt poidel world testset'); await until(() => pois().length === 0, 8000);
    await s.run(`bmt poi world testset far 400 70 0 Far`, /poi added/);
    const edge = await until(() => { const f = b.hudFrame(pk); return f.icons.find((i) => i.icon === 4 && i.color === 0xFFC845); }, 8000);
    check('An off-map POI gets an edge arrow pointing east', edge, edge && JSON.stringify(iconCell(edge, gridOf(b.hudFrame(pk), 2))));
    await chat(b, '/hud hide markers', /Hiding/);
    check('/hud hide markers hides it', await until(() => !b.hudFrame(pk).icons.some((i) => i.icon === 4 && i.color === 0xFFC845), 3000));
    await chat(b, '/hud show markers', /Showing/);
    s.cmd('bmt poidel world testset');
    await s.run(`bmt poi world secret s1 6 70 6 Secret`, /poi added/);
    baseConfig({}); fs.writeFileSync(CFG, fs.readFileSync(CFG, 'utf8').replace('exclude: []', 'exclude: [secret]')); await reload(); await sleep(6000);
    check('markers.exclude filters a marker set', !pois().length, pois().length);
    baseConfig({}); fs.writeFileSync(CFG, fs.readFileSync(CFG, 'utf8').replace('include: []', 'include: [nothing]')); await reload(); await sleep(6000);
    check('markers.include filters marker sets', !pois().length, pois().length);
    baseConfig(); await reload();
    check('Markers return when filters are removed', await until(() => pois().length === 1, 8000));
    s.cmd('bmt poidel world secret');
    await tp('Tester', 5, 120, 5); await sleep(300);
    s.cmd('kill Tester'); await sleep(1500);
    await until(() => b.bot.entity && b.bot.health > 0, 5000); await tp('Tester', 0, 120, 0);
    const death = await until(() => iconsOf(b.hudFrame(pk) || { icons: [] }, ICON.DEATH)[0], 5000);
    check('The death point is marked after dying and respawning', death, death && JSON.stringify(iconCell(death, gridOf(b.hudFrame(pk), 2))));
    await b.cleanQuit();
  },

  async autohide() {
    section('Hiding');
    baseConfig(); await reload();
    const b = await join('Tester');
    await tp('Tester', 0, 120, 0);
    const pk = packOf(b);
    const has = () => !!b.hudFrame(pk);
    const gone = () => until(() => !has(), 3500), back = () => until(has, 3500);
    await back();
    const cycle = async (name, on, off) => {
      s.cmd(on); const hid = await gone(); s.cmd(off); const ret = await back();
      check(`${name} hides the minimap, and it returns`, hid && ret, `hid ${!!hid}, back ${!!ret}`);
    };
    await cycle('An Adventure boss bar', 'bmt advbar Tester on', 'bmt advbar Tester off');
    await cycle('A registered Bukkit boss bar', 'bmt keyedbar Tester on', 'bmt keyedbar Tester off');
    await cycle('Spectator mode', 'gamemode spectator Tester', 'gamemode creative Tester');
    s.cmd('bmt plainbar Tester on'); await sleep(2500);
    check('KNOWN LIMIT: an unregistered Bukkit bar is not detected, and the map stays', has());
    s.cmd('bmt plainbar Tester off'); await sleep(300);
    s.cmd('summon minecraft:wither 0 125 3 {NoAI:1b,CustomName:"\\"tw\\"",Invulnerable:0b}');
    const w = await gone();
    s.cmd('kill @e[type=minecraft:wither]'); const wb = await back();
    check('A Wither fight hides the minimap, and it returns', w && wb, `hid ${!!w}, back ${!!wb}`);
    await chat(b, '/hud off', /off/);
    check('/hud off hides it', await gone());
    await chat(b, '/map');
    const fullOk = await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:full', 3000);
    check('/map still works while the minimap is off', fullOk);
    await chat(b, '/map'); check('Closing fullscreen leaves the minimap off', await gone());
    await chat(b, '/hud on', /on/); await back();
    await chat(b, '/map'); await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:full', 3000);
    const bars = b.bars.size;
    check('Fullscreen reuses one bar', bars === 1, bars);
    await chat(b, '/map'); await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:hud', 3000);

    baseConfig({ 'hud.auto-hide.spectator': false, 'hud.auto-hide.other-boss-bar': false }); await reload(); await sleep(800);
    s.cmd('gamemode spectator Tester'); await sleep(1500);
    check('auto-hide.spectator false keeps the map', has());
    s.cmd('gamemode creative Tester'); s.cmd('bmt advbar Tester on'); await sleep(1800);
    check('auto-hide.other-boss-bar false keeps the map', has());
    s.cmd('bmt advbar Tester off');

    baseConfig({ 'fullscreen.timeout-seconds': 2 }); await reload(); await sleep(500);
    await chat(b, '/map'); await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:full', 3000);
    check('Fullscreen closes itself at the timeout', await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:hud', 5000));

    baseConfig(); await reload(); await sleep(500);
    b.sneak(true); await sleep(200); b.swapF(); await sleep(200);
    check('Sneak + F opens fullscreen', await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:full', 3000));
    b.swapF(); check('Sneak + F closes it', await until(() => b.hudFrame(pk)?.fontName === 'bluemapmini:hud', 3000));
    b.sneak(false); await sleep(200); const n = b.frames.length; b.swapF(); await sleep(1200);
    check('F without sneaking does nothing to the map', b.hudFrame(pk)?.fontName === 'bluemapmini:hud' && b.frames.length === n);
    baseConfig({ 'fullscreen.sneak-swap-toggle': false }); await reload(); await sleep(400);
    b.sneak(true); await sleep(200); b.swapF(); await sleep(1200);
    check('sneak-swap-toggle false leaves sneak + F alone', b.hudFrame(pk)?.fontName === 'bluemapmini:hud'); b.sneak(false);
    baseConfig({ 'hud.auto-hide.quick-hide-gesture': true }); await reload(); await sleep(400);
    await tp('Tester', 0, 120, 0, 180, 90); await sleep(300);
    b.sneak(true); await sleep(200); b.swapF();
    check('Quick-hide gesture looking down hides the minimap', await gone());
    b.swapF(); check('Doing it again brings it back', await back()); b.sneak(false);
    baseConfig({ 'fullscreen.enabled': false }); await reload(); await sleep(400);
    const off = await chat(b, '/map', /turned off/);
    check('fullscreen.enabled false refuses /map', off, off);
    baseConfig(); await reload(); await chat(b, '/hud on'); await b.cleanQuit();
  },

  async dimensions() {
    section('Dimensions and map changes');
    baseConfig(); await reload();
    const b = await join('Tester');
    await chat(b, '/hud zoom 2');
    await tp('Tester', 0, 120, 0);
    await expectMap('Overworld map', b, OVER, 2, 0, 0, 32, 32, 2);
    s.cmd('execute in minecraft:the_nether run tp Tester 20 100 20'); await sleep(1500);
    await expectMap('Nether switches to the Nether map', b, netherMap, 2, 20, 20, 32, 32, 2);
    s.cmd('execute in minecraft:the_end run tp Tester 0 100 0'); await sleep(1500);
    check('The Ender Dragon bar hides the minimap', await until(() => !b.hudFrame(packOf(b)), 5000));
    baseConfig({ 'hud.auto-hide.other-boss-bar': false }); await reload();
    await expectMap('The End switches to the End map', b, 'world_the_end', 2, 0, 0, 32, 32, 2, 1, false);
    const confs = s.path('plugins/BlueMap/maps'); const end = fs.readdirSync(confs).find((f) => /end/i.test(f));
    fs.renameSync(path.join(confs, end), path.join(confs, end + '.off'));
    s.cmd('bluemap reload'); await s.waitLog(/Reloaded|reloaded|Reload/, 30000, s.mark() - 200); await sleep(6000);
    check('A dimension BlueMap does not map hides the HUD', await until(() => !b.hudFrame(packOf(b)), 6000));
    const why = await chat(b, '/hud on', /no map|isn't ready/);
    check('/hud on explains why', why, why);
    fs.renameSync(path.join(confs, end + '.off'), path.join(confs, end));
    s.cmd('bluemap reload'); await sleep(9000);
    await until(() => b.hudFrame(packOf(b)), 8000);
    check('The map returns after BlueMap reloads', !!b.hudFrame(packOf(b)));
    s.cmd('execute in minecraft:overworld run tp Tester 0 120 0'); await sleep(1200);
    await expectMap('Back in the Overworld', b, OVER, 2, 0, 0, 32, 32, 2);
    baseConfig(); fs.writeFileSync(CFG, fs.readFileSync(CFG, 'utf8').replace('map-overrides: {}', `map-overrides:\n    world: ${netherMap}`)); await reload();
    await expectMap('map-overrides picks the configured map', b, netherMap, 2, 0, 0, 32, 32, 2, 1, false);
    baseConfig(); await reload();
    await b.cleanQuit();
  },

  async lifecycle() {
    section('Reloads and restarts');
    baseConfig(); await reload();
    let b = await join('Tester');
    await chat(b, '/hud zoom 4', /zoom/);
    await tp('Tester', 0, 120, 0);
    let pushes = b.pushes.length;
    await reload(); await sleep(1500);
    check('/bluemapmini reload with no pack change re-sends nothing', b.pushes.length === pushes, b.pushes.length - pushes);
    check('The map keeps working after it', !!b.hudFrame(packOf(b)));
    baseConfig({ 'hud.cell-pixels': 3 }); await reload();
    check('A pack-changing reload re-sends with the same ID', await until(() => b.pushes.length === pushes + 1, 4000) && b.pushes.at(-1).uuid === b.pushes[0].uuid, b.pushes.length - pushes);
    await until(() => packOf(b) && b.packSha === b.pushes.at(-1).hash, 4000);
    await expectMap('The new cell size draws correctly', b, OVER, 4, 0, 0, 32, 32, 3);
    baseConfig(); await reload(); await until(() => b.packSha === b.pushes.at(-1).hash, 4000);
    await expectMap('And back again', b, OVER, 4, 0, 0, 32, 32, 2);
    const m = s.mark(); s.cmd('bluemap reload'); await sleep(9000);
    await expectMap('The map returns after /bluemap reload', b, OVER, 4, 0, 0, 32, 32, 2);
    check('No errors from /bluemap reload', s.errors(m).length === 0, s.errors(m)[0] || '');
    await chat(b, '/hud off', /off/);
    await b.cleanQuit(); await sleep(300);
    b = await join('Tester', { keep: true });
    await sleep(1500);
    check('/hud off survives a rejoin', !b.hudFrame(packOf(b)));
    await chat(b, '/hud on', /on/); await until(() => b.hudFrame(packOf(b)), 3000);
    check('/hud zoom survives a rejoin', (await chat(b, '/hud zoom in', /zoom is now 2/)) !== null);
    await chat(b, '/hud zoom 4', /zoom/); await chat(b, '/hud offset 12 8', /moved/);
    await b.cleanQuit();
    await s.stop(); await s.start();
    b = await join('Tester', { keep: true }); await tp('Tester', 0, 120, 0);
    const pk = packOf(b);
    await until(() => b.hudFrame(pk), 6000);
    const f = b.hudFrame(pk), g = f && gridOf(f, 2);
    check('Settings survive a server restart', g && g.left === 12 - 32 && g.top === 8 + 8, g && `left ${g.left} top ${g.top}`);
    const reachable = await chat(b, '/hud offset 500 500', /moved to 500, 64/);
    check('Offset y is capped at max-offset-y', reachable, reachable);
    await chat(b, '/hud corner right', /moved/);
    await chat(b, '/hud corner center', /moved/);
    await chat(b, '/hud offset 0 0', /moved/);
    await until(() => { const fr = b.hudFrame(pk), gr = fr && gridOf(fr, 2); return gr && gr.left === -32 && gr.top === 8; }, 3000);
    check('offset 0 0 is exactly centred', gridOf(b.hudFrame(pk), 2).left === -32);
    await chat(b, '/hud zoom 2', /zoom/);
    const rm = s.mark(); s.cmd('reload confirm'); await s.waitLog(/Reload complete|Reloaded|complete/i, 60000, rm); await sleep(6000);
    const back = await until(() => b.pushes.length >= 1 && b.hudFrame(packOf(b)), 8000);
    check('Survives a full /reload', back);
    const errs = s.errors(rm).filter((l) => /extantape|BlueMapMini/.test(l));
    check('No BlueMapMini errors from /reload', errs.length === 0, errs[0]?.slice(0, 200) || '');
    await b.cleanQuit();
    await s.stop(); await s.start();
  },

  async magic() {
    section("Another plugin wiping packs (Magic's method)");
    baseConfig(); await reload();
    const b = await join('Tester');
    await until(() => b.hudFrame(packOf(b)), 5000);
    const pushes = b.pushes.length;
    s.cmd(`bmt magicpack Tester http://127.0.0.1:${packPort}/nope.zip`);
    await until(() => b.pops.length >= 1, 3000);
    check('The old one-pack method pops every pack', b.pops.length >= 1, JSON.stringify(b.pops[0]));
    check('The map hides right away', await until(() => !b.hudFrame(packOf(b)), 3000));
    check('Our pack is re-sent', await until(() => b.pushes.filter((p) => p.url.includes('bluemapmini')).length >= pushes + 1, 6000), b.pushes.map((p) => p.url.split('/').pop()).join(','));
    check('The map returns', await until(() => b.hudFrame(packOf(b)), 5000));
    const n = b.pushes.length;
    s.cmd(`bmt magicpack Tester http://127.0.0.1:${packPort}/nope2.zip`);
    await sleep(6000);
    check('A second wipe is not answered, so it can never loop', b.pushes.filter((p) => p.url.includes('bluemapmini')).length === pushes + 1, b.pushes.length - n);
    await b.cleanQuit();
    const again = await join('Tester');
    check('Rejoining restores the pack', await until(() => again.hudFrame(packOf(again)), 5000));
    await again.cleanQuit();
    baseConfig({ 'pack.re-send-on-removal': false }); await reload();
    const c = await join('Tester'); await until(() => c.hudFrame(packOf(c)), 5000);
    s.cmd(`bmt magicpack Tester http://127.0.0.1:${packPort}/nope.zip`); await sleep(4000);
    check('re-send-on-removal false leaves it removed', c.pushes.filter((p) => p.url.includes('bluemapmini')).length === 1);
    await c.cleanQuit();
  },

  async packmodes() {
    section('Pack settings');
    baseConfig(); await reload();
    const d = await join('Decline', { mode: 'decline', settle: 1200 });
    check('Declining a required pack kicks with the custom message', d.kicked && d.kicked.includes('needs the BlueMapMini'), d.kicked);
    await d.cleanQuit();
    // An admin online, to hear about it.
    const admin = await join('Tester');
    const fm = s.mark();
    const fl = await join('FailDl', { mode: 'fail', settle: 2000 });
    check('A failed download never kicks, even with the pack required', !fl.kicked && fl.ended === null, fl.kicked);
    check('The player is told the map is off and will try again', fl.chat.some((m) => /couldn't be downloaded, so the map is off for now/.test(m)), fl.chat.slice(-3).join(' | '));
    check('No map without the pack', fl.frames.length === 0, `${fl.frames.length} frames`);
    check('The console says who and why', /FailDl didn't get the BlueMapMini pack: the download failed/.test(s.since(fm)));
    check('Admins online are warned', admin.chat.some((m) => /FailDl couldn't download the map's pack/.test(m)), admin.chat.slice(-2).join(' | '));
    const pushes = fl.pushes.length;
    fl.mode = 'accept'; // The hosting is fixed.
    s.cmd('execute in minecraft:the_nether run tp FailDl 0 70 0');
    check('Changing world tries again', await until(() => fl.pushes.length > pushes, 5000), `${fl.pushes.length - pushes} new pushes`);
    s.cmd('execute in minecraft:overworld run tp FailDl 0 120 0');
    check('and once the pack loads, the map appears', await until(() => fl.frames.length > 0, 8000), `${fl.frames.length} frames`);
    await fl.cleanQuit(); await admin.cleanQuit();
    const ig = await join('Ignore', { mode: 'ignore', settle: 2500 });
    check('No frames before the client confirms the pack', ig.frames.length === 0 && !ig.kicked, ig.frames.length); await ig.cleanQuit();

    baseConfig({ 'pack.required': false }); await reload();
    const opt = await join('Optional', { mode: 'decline', settle: 2000 });
    check('Declining an optional pack stays connected with no map', !opt.kicked && opt.ended === null && opt.frames.length === 0);
    check('The pack is sent as optional', opt.pushes[0]?.forced === false);
    const why = await chat(opt, '/hud on', /hasn't loaded/);
    check('/hud on explains the missing pack', why, why);
    await opt.cleanQuit();

    baseConfig({ 'pack.boss-bar-color': 'blue' }); await reload();
    const blue = await join('Blue'); await until(() => blue.barEvents.some((e) => e.action === 0), 4000);
    const pk = packOf(blue);
    check('boss-bar-color changes the bar', blue.barEvents.find((e) => e.action === 0)?.color === 1, JSON.stringify(blue.barEvents.find((e) => e.action === 0)));
    check('And the transparent textures', !!pk.files['assets/minecraft/textures/gui/sprites/boss_bar/blue_progress.png'] && !pk.files['assets/minecraft/textures/gui/sprites/boss_bar/pink_progress.png']);
    await blue.cleanQuit();

    baseConfig({ 'pack.hide-boss-bar': false }); await reload();
    const nb = await join('NoHide');
    check('hide-boss-bar false ships no vanilla textures', Object.keys(packOf(nb).files).every((f) => !f.startsWith('assets/minecraft/')));
    await nb.cleanQuit();

    baseConfig({ 'pack.host': 'localhost' }); await reload();
    const h = await join('HostSet'); check('pack.host sets the URL host', h.pushes[0]?.url.startsWith(`http://localhost:${packPort}/`), h.pushes[0]?.url); await h.cleanQuit();

    // Nothing serves this address, so the hosted-pack check must warn about it.
    const pm = s.mark();
    baseConfig({ 'pack.public-url': `http://127.0.0.1:${packPort + 50}/custom.zip` }); await reload();
    check('A dead public-url is warned about', await s.waitLog(/Could not download pack.public-url \(http:\/\/127.0.0.1:\d+\/custom.zip\)/, 40000, pm));
    const pu = await join('PubUrl', { mode: 'ignore', waitPack: false, settle: 1500 });
    check('pack.public-url is what players get', pu.pushes[0]?.url.endsWith('/custom.zip'), pu.pushes[0]?.url); await pu.cleanQuit();

    const m = s.mark();
    baseConfig({ 'pack.send': false }); await reload(); await sleep(1200);
    check('pack.send false warns when no merged pack is set', /no resource pack/.test(s.since(m)));
    const ns = await join('NoSend', { waitPack: false, settle: 1800 });
    check('pack.send false sends no pack', ns.pushes.length === 0);
    check('And draws straight away for a merged pack', ns.frames.length > 0, ns.frames.length);
    await ns.cleanQuit();

    baseConfig({ 'hud.enabled-by-default': false }); await reload();
    const dn = await join('New' + Math.floor(Math.random() * 1e6), { settle: 1800 });
    check('enabled-by-default false starts new players with it off', !dn.hudFrame(packOf(dn)));
    await chat(dn, '/hud on', /on/); check('They can turn it on', await until(() => dn.hudFrame(packOf(dn)), 3000));
    await dn.cleanQuit();
    baseConfig(); await reload();
  },

  async icons() {
    section('Custom icons');
    const folder = s.path('plugins/BlueMapMini/icons');
    const png = (w, h, fill) => {
      const raw = Buffer.alloc((w * 4 + 1) * h);
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (fill(x, y)) raw.writeUInt32BE(0xFFFFFFFF, y * (w * 4 + 1) + 1 + x * 4);
      const chunk = (k, d) => { const c = Buffer.alloc(8 + d.length + 4); c.writeUInt32BE(d.length, 0); c.write(k, 4, 'latin1'); d.copy(c, 8); c.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(k, 'latin1'), d])) >>> 0, 8 + d.length); return c; };
      const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
      return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
    };
    fs.writeFileSync(path.join(folder, 'player.png'), png(9, 9, (x, y) => x < 3 && y < 3)); // Narrow art.
    fs.writeFileSync(path.join(folder, 'poi.png'), png(16, 16, () => true)); // Wrong size.
    fs.writeFileSync(path.join(folder, 'death.png'), Buffer.from('not a png'));
    const m = s.mark(); baseConfig(); await reload(); await sleep(500);
    check('A wrong-sized icon is reported', /poi\.png.*9x9/.test(s.since(m)), s.since(m).split('\n').filter((l) => /poi\.png/.test(l))[0]?.slice(0, 120));
    check('An unreadable icon does not break the build', /reloaded/.test(s.since(m)) && s.errors(m).filter((l) => /Exception/.test(l)).length === 0, s.errors(m)[0]?.slice(0, 140));
    const b = await join('Tester'), o = await join('Other');
    await tp('Tester', 0, 120, 0); await tp('Other', 20, 120, 10);
    const pk = packOf(b);
    check('Narrow artwork gets its own advance', pk.fonts.hud.get([...pk.fonts.hud.keys()].find((c) => pk.fonts.hud.get(c).kind === 'icon' && pk.fonts.hud.get(c).index === 16)).advance === 4);
    const ok = await until(() => { const f = b.hudFrame(pk); return f && f.icons.some((i) => i.icon === ICON.PLAYER); }, 3000);
    const f = b.hudFrame(pk);
    check('A frame with custom art still nets to zero', ok && f.net === 0, f?.net);
    await o.cleanQuit(); await b.cleanQuit();
    for (const n of ['player.png', 'poi.png', 'death.png']) fs.rmSync(path.join(folder, n));
    baseConfig(); await reload();
  },

  async actionbar() {
    section('Action bar channel');
    baseConfig({ 'hud.channel': 'action-bar' }); await reload();
    const b = await join('Tester');
    await tp('Tester', 0, 120, 0);
    const pk = packOf(b);
    await until(() => b.actionBars.length > 0, 5000);
    check('Frames arrive on the action bar', b.actionBars.length > 0, b.actionBars.length);
    check('No boss bar in this mode', b.barEvents.filter((e) => e.action === 0).length === 0);
    const last = () => decodeFrame(b.actionBars.at(-1).text, pk);
    const f = last(), g = gridOf(f, 2);
    check('It decodes with no missing glyphs and nets to zero', f.missing === 0 && f.net === 0, `missing ${f.missing}, net ${f.net}`);
    check('The map sits above the text line, clear of the hotbar', g && g.top + g.rows * 2 <= -8, g && `rows end at ${g.top + g.rows * 2}`);
    const cols = g.cols; let bad = 0; const fx = -16, fz = -16;
    for (let z = 0; z < 32; z++) for (let x = 0; x < 32; x++) if (truth.cell(OVER, 2, fx + x, fz + z) !== g.cells[z * cols + x]) bad++;
    check('Colours match BlueMap tiles', bad === 0, bad + ' wrong');
    await sleep(600); const n = b.actionBars.length; await sleep(3200);
    const per = (b.actionBars.length - n) / 3.2;
    check('It is re-sent about every 1.5 s while idle', per > 0.4 && per < 1.2, per.toFixed(2) + '/s');
    await chat(b, '/hud offset 0 20', /moved/); await sleep(800);
    const f2 = last(), g2 = gridOf(f2, 2);
    check('Offset y moves it up in this mode', g2.top === g.top - 20, `${g.top} to ${g2.top}`);
    await chat(b, '/hud offset 0 0', /moved/);
    await chat(b, '/map');
    check('Fullscreen still uses a boss bar', await until(() => b.barEvents.some((e) => e.action === 0), 3000));
    await chat(b, '/map'); await until(() => b.bars.size === 0, 3000);
    check('Closing it returns to the action bar', b.bars.size === 0);
    const before = b.actionBars.length; await chat(b, '/hud off', /off/); await sleep(800);
    const clear = b.actionBars.slice(before).at(-1);
    check('/hud off clears the action bar', clear && !K.plainNbt(clear.text)?.font, JSON.stringify(clear?.text)?.slice(0, 80));
    await chat(b, '/hud on', /on/);
    fs.copyFileSync(s.path('plugins/BlueMapMini/pack/bluemapmini-pack.zip'), path.join(ROOT, `pack-actionbar-${label}.zip`));
    await b.cleanQuit();
    baseConfig(); await reload();
  },

  async sources() {
    section('Tile sources and bad data');
    baseConfig({ 'tiles.source': 'http' }); await reload();
    let b = await join('Tester');
    await chat(b, '/hud zoom 2'); await tp('Tester', 10, 120, 10);
    await expectMap('HTTP source matches the tiles', b, OVER, 2, 10, 10, 32, 32, 2);
    const far = 40000 + Math.floor(Math.random() * 20000) * 2; truth.clear();
    await tp('Tester', far, 120, far); await sleep(500);
    await expectMap('HTTP source handles an unrendered tile (204)', b, OVER, 2, far, far, 32, 32, 2, 1, false);
    await tp('Tester', 10, 120, 10);
    baseConfig({ 'tiles.source': 'http', 'tiles.http-url': 'http://127.0.0.1:9' }); let m = s.mark(); await reload(); await sleep(2000);
    let f = b.hudFrame(packOf(b)), g = f && gridOf(f, 2);
    check('An unreachable webserver draws void without errors', g && new Set(g.cells).size === 1 && s.errors(m).length === 0, s.errors(m)[0]?.slice(0, 140) || (g && new Set(g.cells).size));
    baseConfig({ 'tiles.web-root': '/nonexistent/folder' }); m = s.mark(); await reload(); await sleep(2000);
    f = b.hudFrame(packOf(b)); g = f && gridOf(f, 2);
    check('A wrong web root draws void without errors', g && new Set(g.cells).size === 1 && s.errors(m).length === 0, s.errors(m)[0]?.slice(0, 140) || '');

    // Tile changes and bad files, on a copy that gets restored.
    baseConfig({ 'tiles.refresh-minutes': 1 }); await reload();
    await tp('Tester', 250, 120, 250); await tp('Tester', 10, 120, 10);
    await expectMap('Back on the file source', b, OVER, 2, 10, 10, 32, 32, 2);
    const tile = truth.tilePath(OVER, 1, 0, 0), saved = fs.readFileSync(tile);
    try {
      fs.writeFileSync(tile, Buffer.from('this is not a png'));
      m = s.mark();
      await tp('Tester', 300, 120, 300); await sleep(300);
      // Zoom 4 is not cached yet, so it has to read the now-corrupt tile.
      await chat(b, '/hud zoom 4'); await tp('Tester', 10, 120, 10); await sleep(2500);
      f = b.hudFrame(packOf(b));
      check('A corrupt tile does not stop the map', !!f && f.missing === 0);
      check('A corrupt tile logs no errors', s.errors(m).length === 0, s.errors(m)[0]?.slice(0, 140) || '');
      fs.writeFileSync(tile, saved);
      await chat(b, '/hud zoom 2');
      // A BlueMap-shaped tile painted solid: the HUD should pick it up within the refresh time.
      const solid = (() => {
        const w = 501, h = 1002, raw = Buffer.alloc((w * 4 + 1) * h);
        for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) raw.writeUInt32BE(0xFF0000FF, y * (w * 4 + 1) + 1 + x * 4);
        const chunk = (k, d) => { const c = Buffer.alloc(12 + d.length); c.writeUInt32BE(d.length, 0); c.write(k, 4, 'latin1'); d.copy(c, 8); c.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(k, 'latin1'), d])) >>> 0, 8 + d.length); return c; };
        const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
        return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
      })();
      await tp('Tester', 100, 120, 100); await sleep(1000);
      fs.writeFileSync(tile, solid); const t0 = Date.now(); let overwritten = null;
      const changed = await until(() => {
        if (overwritten === null && !fs.readFileSync(tile).equals(solid)) overwritten = ((Date.now() - t0) / 1000).toFixed(1);
        if (overwritten !== null) { fs.writeFileSync(tile, solid); }
        const fr = b.hudFrame(packOf(b)), gr = fr && gridOf(fr, 2); return gr && gr.cells.filter((c) => c === 0xFF0000).length > 100;
      }, 80000, 500);
      check('A re-rendered tile reaches the HUD within the refresh time', changed, `${((Date.now() - t0) / 1000).toFixed(0)} s, refresh is 60 s` + (overwritten ? `, BlueMap overwrote the test tile at ${overwritten} s` : ''));
    } finally { fs.writeFileSync(tile, saved); truth.clear(); }
    await b.cleanQuit();
    baseConfig(); await reload();
  },

  async edges() {
    section('Edge cases');
    baseConfig(); await reload();
    let b = await join('Tester');
    s.cmd('op Tester'); await sleep(150);
    const m0 = s.mark();
    s.cmd('tp Tester 29999000 120 -29999000'); await sleep(2500);
    let f = b.hudFrame(packOf(b)); const line = f && textLines(f)[0]?.s;
    check('The far edge of the world works', f && f.net === 0 && line?.startsWith('29999000 120 -29999000'), line);
    await chat(b, '/hud zoom 64', /zoom/); await sleep(800);
    f = b.hudFrame(packOf(b)); check('Including at the widest zoom', f && f.net === 0);
    await chat(b, '/hud zoom 2', /zoom/);
    check('No errors at the world edge', s.errors(m0).length === 0, s.errors(m0)[0]?.slice(0, 160) || '');
    await tp('Tester', 0, 120, 0);
    const bad = await chat(b, '/hud zoom 3', /Zoom must be/);
    check('A bad zoom value gets a clear message', bad, bad);
    const low = await chat(b, '/hud zoom in', /zoom is now 1/); await chat(b, '/hud zoom in', /zoom is now 1/);
    check('Zooming in past the limit stops at 1', low);
    for (let i = 0; i < 7; i++) await chat(b, '/hud zoom out');
    check('Zooming out past the limit stops at 64', await chat(b, '/hud zoom out', /zoom is now 64/));
    await chat(b, '/hud zoom 2', /zoom/);
    const c1 = await s.run('hud on', /Only players/); check('Console gets a clear message from /hud', c1, c1?.[0]);
    const c2 = await s.run('map', /Only players/); check('Console gets a clear message from /map', c2, c2?.[0]);
    s.cmd('execute as Tester run hud off'); check('execute as <player> works', await until(() => !b.hudFrame(packOf(b)), 3000));
    s.cmd('execute as Tester run hud on'); await until(() => b.hudFrame(packOf(b)), 3000);
    s.cmd('deop Tester'); await sleep(300);
    const denied = await chat(b, '/bluemapmini reload', /Incorrect|Unknown|permission/); check('Non-ops can not reload', denied, denied);
    check('Non-ops can still use /bluemapmini help', await chat(b, '/bluemapmini help', /BlueMapMini/));
    s.cmd('op Tester');
    s.cmd('bmt perm Tester bluemapmini.use false');
    check('Removing bluemapmini.use hides everything', await until(() => !b.hudFrame(packOf(b)), 3000));
    await chat(b, '/map'); await sleep(1000);
    check('Even fullscreen', !b.hudFrame(packOf(b)));
    await chat(b, '/map'); s.cmd('bmt perm Tester bluemapmini.use true');
    check('It returns with the permission', await until(() => b.hudFrame(packOf(b)), 3000));
    s.cmd('bmt perm Tester bluemapmini.fullscreen false'); await sleep(200);
    await chat(b, '/map'); await sleep(800);
    check('Losing bluemapmini.fullscreen blocks /map', b.hudFrame(packOf(b))?.fontName === 'bluemapmini:hud');
    s.cmd('bmt perm Tester bluemapmini.fullscreen true'); await sleep(300);
    await b.cleanQuit();

    const m1 = s.mark();
    for (let i = 0; i < 12; i++) { const q = new Bot('Churn', port); await Promise.race([q.spawned, sleep(5000)]); await sleep(i % 3 === 0 ? 0 : 200 * (i % 4)); await q.cleanQuit(); }
    await sleep(800);
    const dbg = await s.run('bluemapmini debug', /(\d+) sessions/);
    check('12 quick join/quits leave no sessions behind', dbg && +dbg[1] === 0, dbg?.[0]);
    check('And no errors', s.errors(m1).length === 0, s.errors(m1)[0]?.slice(0, 160) || '');

    b = await join('Tester'); s.cmd('op Tester'); await sleep(200);
    await tp('Tester', 0, 120, 0); await chat(b, '/hud zoom 2');
    const dump = s.path(`plugins/BlueMapMini/debug-world.png`); fs.rmSync(dump, { force: true });
    const w = await chat(b, '/bluemapmini debug dump', /Wrote/, 40000);
    check('/bluemapmini debug dump writes a PNG', w && fs.existsSync(dump), w);
    if (fs.existsSync(dump)) {
      // The dump holds the plugin's averaged cells before the HUD's colour steps, which is truth.cell with a
      // step of 1. Exact, apart from tiles BlueMap may re-render while the test runs.
      const NONE = 0x010203; // An unlikely colour that marks cells BlueMap hasn't rendered.
      const raw = new Truth(s.path('bluemap/web/maps'), NONE, 1);
      const png = readPng(fs.readFileSync(dump)); let mism = 0, n = 0, example = '';
      for (let z = 0; z < png.h; z++) for (let x = 0; x < png.w; x++) {
        const cx = -64 + x, cz = -64 + z, a = png.argb(x, z);
        if (a >>> 24 === 0) continue;
        const t = raw.cell(OVER, 2, cx, cz);
        if (t === NONE) continue;
        n++;
        if ((a & 0xFFFFFF) !== (t & 0xFFFFFF)) { mism++; if (!example) example = `first at ${cx},${cz}: ${(a & 0xFFFFFF).toString(16)} vs ${(t & 0xFFFFFF).toString(16)}`; }
      }
      check('The dump matches BlueMap cell for cell', n > 1000 && mism / n < 0.005, `${n} cells, ${mism} differ${example ? ', ' + example : ''}`);
    }
    await chat(b, '/bluemapmini debug pattern', /toggled/);
    const pat = await until(() => { const fr = b.hudFrame(packOf(b)), gr = fr && gridOf(fr, 2); return gr && gr.cells[0] === 0xFFFFFF && gr.cells[1] === 0xFFFFFF; }, 3000);
    check('Test pattern shows its white border', pat);
    await chat(b, '/bluemapmini debug pattern', /toggled/);
    const ex = await chat(b, '/bluemapmini pack export', /pack is at/); check('/bluemapmini pack export names the file', ex && fs.existsSync(s.path('plugins/BlueMapMini/pack/bluemapmini-pack.zip')), ex);
    await b.cleanQuit();
  },

  async load() {
    section('Load: 20 players moving');
    baseConfig(); await reload();
    const bots = [];
    for (let i = 0; i < 20; i++) bots.push(await join(`L${i}`, { settle: 100 }));
    await sleep(2000);
    s.cmd('bluemapmini reload'); await sleep(1500); s.cmd('bmt gc'); await sleep(1000);
    for (const b of bots) { b.barBytes.length = 0; b.frames.length = 0; }
    const dbgBefore = await s.run('bluemapmini debug', /frames (\d+), average build (\d+) us/);
    const m = s.mark(); const start = Date.now(); let tick = 0;
    while (Date.now() - start < 60000) {
      tick++;
      for (let i = 0; i < bots.length; i++) {
        const a = (tick / 20) + i * 0.3, r = 40 + i * 3;
        s.cmd(`tp L${i} ${(Math.cos(a) * r).toFixed(1)} 120 ${(Math.sin(a) * r).toFixed(1)} ${(tick * 13 + i * 40) % 360 - 180} 0`);
      }
      await sleep(400);
    }
    const dur = (Date.now() - start) / 1000;
    const all = bots.flatMap((b) => b.barBytes);
    const sorted = all.map((x) => x.zlib).sort((a, b) => a - b);
    const avg = sorted.reduce((a, x) => a + x, 0) / Math.max(1, sorted.length), p95 = sorted[Math.floor(sorted.length * 0.95)], max = sorted.at(-1);
    const raw = all.reduce((a, x) => a + x.raw, 0) / Math.max(1, all.length);
    console.log(`  ${all.length} updates in ${dur.toFixed(0)} s. Compressed average ${avg.toFixed(0)} B, 95th percentile ${p95} B, largest ${max} B. Uncompressed average ${raw.toFixed(0)} B.`);
    check('Updates stay under 5 KB compressed on real terrain with 20 players', p95 < 5120, `p95 ${p95} B, largest ${max} B`);
    const perBot = all.length / bots.length / dur;
    check('Each player gets at most 4 updates a second', perBot <= 4.2, perBot.toFixed(2) + '/s');
    const dbg = await s.run('bluemapmini debug', /frames (\d+), average build (\d+) us, about (\d+) bytes/);
    check('Average frame build stays under 1 ms', dbg && +dbg[2] < 1000, dbg?.[0]);
    const h = await s.run('bmt health', /BMT mspt ([\d.]+) tps ([\d.]+) usedMB (\d+)/);
    console.log('  ' + h?.[0]);
    check('The server keeps 20 TPS', h && +h[2] > 19.5, h?.[0]);
    check('Server tick stays healthy', h && +h[1] < 50, h?.[1] + ' ms');
    check('No errors under load', s.errors(m).filter((l) => /extantape|BlueMapMini/.test(l)).length === 0, s.errors(m).find((l) => /extantape|BlueMapMini/.test(l))?.slice(0, 160) || '');
    for (const b of bots) b.quit();
    await sleep(2000);
    const after = await s.run('bluemapmini debug', /(\d+) sessions/);
    check('All sessions are cleaned up', after && +after[1] === 0, after?.[0]);
  },
};

(async () => {
  console.log(`##### ${label}: ${dir}`);
  const bukkit = s.path('bukkit.yml');
  fs.writeFileSync(bukkit, fs.readFileSync(bukkit, 'utf8').replace(/connection-throttle: \d+/, 'connection-throttle: -1'));
  const spigot = s.path('spigot.yml'); // The tests send commands faster than a person, and must not be kicked for it.
  fs.writeFileSync(spigot, fs.readFileSync(spigot, 'utf8').replace(/spam-exclusions:\n(\s*)- .*/, 'spam-exclusions:\n$1- /hud\n$1- /map\n$1- /blueminimap\n$1- /skill').replace('enable-spam-exclusions: false', 'enable-spam-exclusions: true'));
  await s.start(); await sleep(6000);
  s.cmd('op Tester'); s.cmd('whitelist off'); s.cmd('difficulty easy'); s.cmd('gamerule keep_inventory true');
  for (const c of ['execute in minecraft:overworld run forceload add -32 -32 32 32']) s.cmd(c);
  const names = only || Object.keys(GROUPS).filter((n) => n !== 'load' && n !== 'clockrate');
  for (const n of names) {
    const m = s.mark();
    try { await GROUPS[n](); } catch (e) { check(`Group ${n} finished`, false, e.stack?.slice(0, 400)); }
    for (const bot of liveBots.splice(0)) if (bot.ended === null) await bot.cleanQuit();
    const hudErrors = s.errors(m).filter((l) => /extantape|\[BlueMapMini\]/.test(l) && !/icons must be|Ignoring|no resource pack|doesn't contain|Could not download pack.public-url \(http:\/\/127.0.0.1:\d+\/custom.zip\)|no theme called sparkles|Theme broken couldn't be loaded|Theme ocean is hard to read|FailDl didn't get the BlueMapMini pack|Blair has blueminimap\.share\.receive true but the old bluemaphud\.share\.receive false|Tester has blueminimap\.share\.send true but the old bluemaphud\.share\.send false/.test(l));
    check(`No BlueMapMini errors during ${n}`, hudErrors.length === 0, hudErrors[0]?.slice(0, 200) || '');
    const drops = s.since(m).split('\n').filter((l) => /lost connection: (?!Disconnected|This server needs|.*resource pack)/.test(l));
    check(`No unexpected disconnects during ${n}`, drops.length === 0, drops[0]?.slice(0, 160) || '');
    await sleep(500);
  }
  await s.stop();
  const failed = summary();
  fs.writeFileSync(path.join(ROOT, `results-${label}.json`), JSON.stringify({ failed }));
  process.exit(failed ? 1 : 0);
})().catch(async (e) => { console.error('SUITE CRASHED', e); try { await s.stop(); } catch (_) {} process.exit(2); });
