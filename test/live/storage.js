// BlueMap starting next to BlueMapMini, BlueMap on SQLite storage, and the real Magic plugin if work/Magic.jar exists.
// Usage: node storage.js <dir> <jar> <port> <webPort> <packPort>. PHASES=startups,sql,magic picks which run.
const K = require('./lib.js');
const { REPO, skip, Server, Bot, Pack, Truth, gridOf, check, section, summary, sleep, until, editYaml, ROOT } = K;
const fs = require('fs'), path = require('path');
const [dir, jar, port, web, pp] = [process.argv[2], process.argv[3], +process.argv[4], +process.argv[5], +process.argv[6]];
if (!pp) { console.error('Usage: node storage.js <dir> <jar> <port> <webPort> <packPort>'); process.exit(2); }
const newServer = () => new Server(dir, jar, port);
const conf = (s, f) => s.path('plugins/BlueMap', f);

async function startups(s0) {
  section('BlueMap startup, repeated, with BlueMapMini installed');
  let failed = 0; const N = 8;
  for (let i = 0; i < N; i++) {
    const s = newServer(); await s.start(); await sleep(8000);
    const bad = /Failed to load/.test(s.log); if (bad) { failed++; console.log('  start', i, (s.log.match(/Failed to load![\s\S]{0,300}/) || [''])[0].replace(/\n/g, ' | ').slice(0, 300)); }
    await s.stop();
  }
  console.log(`  BlueMap failed to load on ${failed} of ${N} starts with BlueMapMini installed.`);
  check('BlueMap starts reliably next to BlueMapMini', failed === 0, `${failed}/${N} failed`);
}

async function sql() {
  section('BlueMap on SQL storage');
  const mapsDir = path.join(ROOT, dir, 'plugins/BlueMap/maps'), sqlConf = path.join(ROOT, dir, 'plugins/BlueMap/storages/sql.conf');
  const maps = fs.readdirSync(mapsDir).filter((f) => f.endsWith('.conf'));
  const backup = Object.fromEntries([...maps.map((f) => path.join(mapsDir, f)), sqlConf].map((f) => [f, fs.readFileSync(f, 'utf8')]));
  const cfgFile = path.join(ROOT, dir, 'plugins/BlueMapMini/config.yml');
  try {
    for (const f of maps) fs.writeFileSync(path.join(mapsDir, f), backup[path.join(mapsDir, f)].replace(/^storage: .*/m, 'storage: "sql"'));
    let c = backup[sqlConf].replace(/^connection-url: .*/m, 'connection-url: "jdbc:sqlite:bluemap/maps.db"');
    fs.writeFileSync(sqlConf, c);
    fs.rmSync(path.join(ROOT, dir, 'bluemap/maps.db'), { force: true });
    fs.copyFileSync(path.join(REPO, 'src/main/resources/config.yml'), cfgFile);
    editYaml(cfgFile, { 'tiles.source': 'http', 'tiles.http-url': `http://127.0.0.1:${web}`, 'pack.port': pp });
    const s = newServer(); await s.start(); await sleep(8000);
    const loaded = !/Failed to load/.test(s.log);
    check('BlueMap loads on SQLite storage', loaded, (s.log.match(/Failed to load![\s\S]{0,240}/) || [''])[0].replace(/\n/g, ' '));
    s.cmd('bluemap force-update world'); await sleep(2000); // BlueMap 5.29 needs the map name from the console.
    const b = new Bot('Tester', port); await Promise.race([b.spawned, sleep(20000)]); await until(() => b.packBytes, 8000);
    b.say('/hud on'); b.say('/hud zoom 2'); b.say('/hud size medium'); b.say('/hud offset 0 0'); await sleep(300);
    s.cmd('tp Tester 10 120 10 180 0');
    const pk = () => new Pack(b.packBytes), truth = new Truth(path.join(ROOT, dir, 'bluemap/web/maps'));
    const P = pk();
    let best = 0, tries = 0;
    const end = Date.now() + 150000;
    while (Date.now() < end) {
      const f = b.hudFrame(P), g = f && gridOf(f, 2);
      if (g && g.cols === 32) {
        let same = 0;
        for (let z = 0; z < 32; z++) for (let x = 0; x < 32; x++) if (truth.cell('world', 2, Math.floor(10 / 2) - 16 + x, Math.floor(10 / 2) - 16 + z) === g.cells[z * 32 + x]) same++;
        best = Math.max(best, same); tries++;
        if (best >= 1000) break;
      }
      await sleep(3000);
    }
    const dbSize = fs.existsSync(path.join(ROOT, dir, 'bluemap/maps.db')) ? fs.statSync(path.join(ROOT, dir, 'bluemap/maps.db')).size : 0;
    check('The HTTP source draws tiles held in SQL', best >= 1000, `${best}/1024 cells match the same world rendered to files; database ${dbSize} bytes`);
    const errs = s.errors().filter((l) => /extantape|\[BlueMapMini\]/.test(l));
    check('No BlueMapMini errors on SQL storage', errs.length === 0, errs[0]?.slice(0, 180) || '');
    await b.cleanQuit(); await s.stop();
  } finally {
    for (const [f, text] of Object.entries(backup)) fs.writeFileSync(f, text);
    fs.copyFileSync(path.join(REPO, 'src/main/resources/config.yml'), cfgFile);
    editYaml(cfgFile, { 'pack.port': pp, 'tiles.http-url': `http://127.0.0.1:${web}` });
  }
}

async function magic() {
  section('The real Magic plugin');
  const jarFile = path.join(ROOT, 'Magic.jar');
  if (!fs.existsSync(jarFile)) { skip('The real Magic plugin', 'put Magic.jar in the work folder to run it'); return; }
  const target = path.join(ROOT, dir, 'plugins/Magic.jar');
  fs.copyFileSync(jarFile, target);
  try {
    const s = newServer(); await s.start(); await sleep(25000);
    const enabled = /Enabling Magic/.test(s.log), bad = /Could not load plugin 'Magic|Error occurred while enabling Magic/.test(s.log);
    check('Magic loads beside BlueMapMini', enabled && !bad, (s.log.match(/\[Magic\][^\n]*(error|fail)[^\n]*/i) || [''])[0].slice(0, 160));
    const b = new Bot('Tester', port); await Promise.race([b.spawned, sleep(30000)]);
    await sleep(25000);
    console.log('  pushes:', b.pushes.map((p) => `${((p.t - b.pushes[0].t) / 1000).toFixed(1)}s ${p.url.split('/').slice(2, 3)}/${p.url.split('/').pop().slice(0, 28)}`).join(' | '), ' pops:', b.pops.length);
    const ours = b.pushes.filter((p) => p.url.includes('bluemapmini')), theirs = b.pushes.filter((p) => !p.url.includes('bluemapmini'));
    check('Magic sends its own pack, so the interaction is real', theirs.length > 0, `${theirs.length} Magic pushes, ${b.pops.length} pops`);
    check('Our pack is there after Magic has finished', ours.length >= 1 && (b.pops.length === 0 || ours.at(-1).t > b.pops.at(-1).t), `${ours.length} of ours`);
    const P = b.packBytes && new Pack(b.packBytes);
    b.say('/hud on'); await sleep(1500);
    check('The map is drawing with Magic installed', P && b.hudFrame(P) && b.hudFrame(P).missing === 0 && b.hudFrame(P).net === 0);
    await b.cleanQuit();
    const c = s.mark();
    const off = fs.readFileSync(s.path('plugins/Magic/config.yml'), 'utf8');
    await s.stop();
    fs.writeFileSync(path.join(ROOT, dir, 'plugins/Magic/config.yml'), off.replace(/^resource_pack:.*$/m, '') + '\nresource_pack: ""\n');
    const s2 = newServer(); await s2.start(); await sleep(20000);
    const b2 = new Bot('Tester', port); await Promise.race([b2.spawned, sleep(30000)]); await sleep(15000);
    check('With resource_pack: "" Magic stays out of the way', b2.pushes.every((p) => p.url.includes('bluemapmini')) && b2.pops.length === 0, `${b2.pushes.length} pushes, ${b2.pops.length} pops`);
    await b2.cleanQuit(); await s2.stop();
  } finally {
    fs.rmSync(target, { force: true });
    fs.rmSync(path.join(ROOT, dir, 'plugins/.paper-remapped/Magic.jar'), { force: true });
  }
}

(async () => {
  const only = process.env.PHASES ? process.env.PHASES.split(',') : null;
  for (const [name, fn] of [['startups', startups], ['sql', sql], ['magic', magic]]) {
    if (only && !only.includes(name)) continue;
    try { await fn(); } catch (e) { check(`Phase ${name} finished`, false, e.stack?.slice(0, 400)); }
  }
  summary(); process.exit(0);
})();
