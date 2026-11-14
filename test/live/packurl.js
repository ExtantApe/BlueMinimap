// Live test for pack.public-url with {sha1}, the hosted-pack check, and /bluemapmini pack export.
// Usage: node packurl.js <label> <dir> <jar> <port> <packPort> <webPort> <hostPort>
// A local HTTP server stands in for the real host, such as a CDN or a per-build address.
const K = require('./lib.js');
const { REPO, Server, Bot, check, section, summary, sleep, until, editYaml } = K;
const fs = require('fs'), path = require('path'), http = require('http'), crypto = require('crypto');
const [label, dir, jar, portS, packPortS, webPortS, hostPortS] = process.argv.slice(2);
const port = +portS, packPort = +packPortS, webPort = +webPortS, hostPort = +hostPortS;
const s = new Server(dir, jar, port);
const CFG = s.path('plugins/BlueMapMini/config.yml');
const EXPORT = s.path('plugins/BlueMapMini/pack/bluemapmini-pack.zip');
const sha = (buf) => crypto.createHash('sha1').update(buf).digest('hex');

// A stand-in for a per-build host address: /<token>/<file>, served from memory.
const hosted = new Map();
const requests = [];
const host = http.createServer((req, res) => {
  requests.push(req.url);
  const body = hosted.get(req.url);
  if (!body) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Length': body.length });
  res.end(body);
});
const TEMPLATE = `http://127.0.0.1:${hostPort}/tok123/{sha1}.zip`;
const urlFor = (h) => `http://127.0.0.1:${hostPort}/tok123/${h}.zip`;

function setPack(edits) { editYaml(CFG, edits); }
async function reload() {
  const m = s.mark(); s.cmd('bluemapmini reload');
  const ok = await s.waitLog(/BlueMapMini reloaded/, 10000, m);
  return { m, ok };
}
const builtSha = () => sha(fs.readFileSync(EXPORT));
async function join(name) {
  const bot = new Bot(name, port);
  await Promise.race([bot.spawned, sleep(20000)]);
  await until(() => bot.pushes.length && bot.downloadStatus !== undefined, 10000);
  await sleep(1500);
  return bot;
}

(async () => {
  await new Promise((r) => host.listen(hostPort, '127.0.0.1', r));
  fs.copyFileSync(path.join(REPO, 'src/main/resources/config.yml'), CFG);
  editYaml(CFG, { 'pack.port': packPort, 'tiles.http-url': `http://127.0.0.1:${webPort}`, 'pack.public-url': TEMPLATE });

  section(`${label}: startup with nothing hosted yet`);
  await s.start();
  const done = s.log.indexOf('Done (');
  const warn404 = await s.waitLog(/answered HTTP 404 for (\S+)/, 40000);
  const first = builtSha();
  check('Startup warns that the hosted pack is missing', warn404, warn404 && warn404[1]);
  check('The warning names the address with {sha1} filled in', warn404 && warn404[1] === urlFor(first) + '.', warn404 && warn404[1]);
  check('Startup finished without waiting for the check', done >= 0 && s.log.indexOf('answered HTTP 404') > done);
  check('The host was asked for exactly that address', requests.includes(`/tok123/${first}.zip`), requests.join(' '));

  section(`${label}: correct file hosted`);
  hosted.set(`/tok123/${first}.zip`, fs.readFileSync(EXPORT));
  let r = await reload();
  let ok = await s.waitLog(/serves the current pack \(SHA-1 (\w+)\)/, 20000, r.m);
  check('Reload re-checks and confirms the hosted pack', ok && ok[1] === first, ok && ok[1]);
  check('Same settings rebuild the same hash', builtSha() === first);
  let m = s.mark(); s.cmd('bluemapmini debug');
  let dbg = await s.waitLog(/public-url (matches|[^\n]*)/, 5000, m);
  check('/bluemapmini debug shows the check result', dbg && dbg[1].startsWith('matches'), dbg && dbg[1]);
  m = s.mark(); s.cmd('bluemapmini pack export');
  await s.waitLog(/Players are sent/, 5000, m); await sleep(200);
  const exp = s.since(m);
  check('Export gives the path and SHA-1', exp.includes('bluemapmini-pack.zip, SHA-1 ' + first), exp.trim().split('\n')[0]);
  check('Export says to upload it unchanged', /upload that zip unchanged/.test(exp));
  check('Export gives the filled-in address and check status', exp.includes(`Players are sent ${urlFor(first)} (check: matches)`));

  const bot = await join('PackA');
  const push = bot.pushes[0] || {};
  check('The player is sent the filled-in address', push.url === urlFor(first), push.url);
  check('The player is sent the matching hash', push.hash === first, push.hash);
  check('The player downloads it and gets the same bytes', bot.downloadStatus === 200 && bot.packSha === first, `${bot.downloadStatus} ${bot.packSha}`);
  const frame = await until(() => bot.frames.length > 0, 8000);
  check('The HUD shows after the pack loads', frame);
  check('The player is not kicked', !bot.kicked, bot.kicked);
  await bot.cleanQuit();

  section(`${label}: stale file hosted`);
  hosted.set(`/tok123/${first}.zip`, Buffer.from('an older pack'));
  r = await reload();
  const stale = await s.waitLog(/doesn't match the pack this plugin built\. Hosted SHA-1 (\w+), built (\w+)/, 20000, r.m);
  check('A stale hosted file warns with both hashes', stale && stale[1] === sha(Buffer.from('an older pack')) && stale[2] === first, stale && stale[0]);
  check('The warning is a WARN line, not an error', /WARN\]: \[BlueMapMini\] The file at pack.public-url/.test(s.since(r.m)));
  check('The server keeps running', s.proc && s.proc.exitCode === null);

  section(`${label}: the address follows a rebuilt pack`);
  hosted.set(`/tok123/${first}.zip`, fs.readFileSync(EXPORT));
  setPack({ 'pack.boss-bar-color': 'blue' });
  r = await reload();
  const moved = await s.waitLog(/answered HTTP 404 for (\S+)\./, 20000, r.m);
  const second = builtSha();
  check('Changing a pack setting changes the hash', second !== first, second);
  check('The check follows the new hash', moved && moved[1] === urlFor(second), moved && moved[1]);
  hosted.set(`/tok123/${second}.zip`, fs.readFileSync(EXPORT));
  const bot2 = await join('PackB');
  check('A new player is sent the new address', bot2.pushes[0] && bot2.pushes[0].url === urlFor(second), bot2.pushes[0] && bot2.pushes[0].url);
  check('and the new hash', bot2.pushes[0] && bot2.pushes[0].hash === second);
  await bot2.cleanQuit();
  setPack({ 'pack.boss-bar-color': 'pink' });
  r = await reload();
  ok = await s.waitLog(/serves the current pack \(SHA-1 (\w+)\)/, 20000, r.m);
  check('Changing it back returns to the first hash, still hosted', ok && ok[1] === first && builtSha() === first, ok && ok[1]);

  section(`${label}: check turned off`);
  setPack({ 'pack.verify-public-url': false });
  const before = requests.length;
  r = await reload(); await sleep(3000);
  check('No download when verify-public-url is false', requests.length === before, requests.slice(before).join(' '));
  m = s.mark(); s.cmd('bluemapmini debug');
  dbg = await s.waitLog(/public-url ([^\n]*)/, 5000, m);
  check('Debug says the check is off', dbg && dbg[1].startsWith('check turned off'), dbg && dbg[1]);
  setPack({ 'pack.verify-public-url': true });

  section(`${label}: fixed address without a placeholder`);
  hosted.set('/tok123/fixed.zip', fs.readFileSync(EXPORT));
  setPack({ 'pack.public-url': `http://127.0.0.1:${hostPort}/tok123/fixed.zip` });
  r = await reload();
  ok = await s.waitLog(/serves the current pack/, 20000, r.m);
  check('A fixed address still works and is checked', ok);

  section(`${label}: unreachable host`);
  setPack({ 'pack.public-url': `http://127.0.0.1:1/tok123/{sha1}.zip` });
  r = await reload();
  const down = await s.waitLog(/Could not download pack.public-url[^\n]*/, 40000, r.m);
  check('An unreachable host warns and says how to turn the check off', down && /verify-public-url to false/.test(down[0]), down && down[0].slice(0, 160));

  section(`${label}: address that never answers`);
  setPack({ 'pack.public-url': `http://10.255.255.1/tok123/{sha1}.zip` });
  const t0 = Date.now(); r = await reload();
  check('Reload returns promptly while the check waits', r.ok && Date.now() - t0 < 5000, `${Date.now() - t0} ms`);
  const tps0 = s.mark(); s.cmd('tps');
  const tps = await s.waitLog(/TPS from last[^\n]*/, 5000, tps0);
  check('The server keeps ticking during the check', tps, tps && tps[0].replace(/§./g, ''));
  const slow = await s.waitLog(/Could not download pack.public-url \(http:\/\/10\.255\.255\.1/, 70000, r.m);
  check('It gives up with a warning after the timeout', slow, slow ? `${Math.round((Date.now() - t0) / 1000)} s` : '');

  section(`${label}: invalid address`);
  setPack({ 'pack.public-url': 'http://exa mple/{sha1}.zip' });
  r = await reload();
  const bad = await s.waitLog(/isn't a valid http or https address/, 10000, r.m);
  check('An invalid address warns', bad);
  m = s.mark();
  const bot3 = await join('PackC');
  check('A joining player gets no pack and no error', bot3.pushes.length === 0 && s.errors(m).filter((l) => /Exception|ERROR/.test(l)).length === 0, s.errors(m).join(' | ').slice(0, 200));
  await bot3.cleanQuit();

  section(`${label}: built-in hosting unchanged`);
  setPack({ 'pack.public-url': '' });
  r = await reload();
  const bot4 = await join('PackD');
  check('An empty public-url still uses the built-in host', bot4.pushes[0] && bot4.pushes[0].url.includes(`:${packPort}/bluemapmini-`) && bot4.downloadStatus === 200, bot4.pushes[0] && bot4.pushes[0].url);
  m = s.mark(); s.cmd('bluemapmini debug');
  dbg = await s.waitLog(/public-url ([^\n]*)/, 5000, m);
  check('Debug says public-url is not used', dbg && dbg[1].startsWith('not used'), dbg && dbg[1]);
  await bot4.cleanQuit();

  const errs = s.errors().filter((l) => /Exception|ERROR|at com\.extantape/.test(l));
  check('No errors or exceptions in the whole run', errs.length === 0, errs.slice(0, 3).join(' | '));

  section(`${label}: restart with the hosted file in place`);
  setPack({ 'pack.public-url': TEMPLATE });
  await s.stop();
  await s.start();
  ok = await s.waitLog(/serves the current pack \(SHA-1 (\w+)\)/, 40000);
  check('After a restart the same hash is built and confirmed', ok && ok[1] === first, ok && ok[1]);

  await s.stop();
  host.close();
  process.exit(summary() ? 1 : 0);
})().catch(async (e) => { console.error(e); try { await s.stop(); } catch (x) {} process.exit(2); });
