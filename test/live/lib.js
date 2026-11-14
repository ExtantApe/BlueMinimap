// Live test kit for BlueMapMini: server control, a protocol bot, a frame decoder, and an independent
// reading of BlueMap's tiles to compare frames against. See README.md in this folder.
const path = require('path'), fs = require('fs'), zlib = require('zlib'), cp = require('child_process'), crypto = require('crypto');
/** Holds the server jars, plugins and throwaway servers. Never committed. */
const ROOT = path.resolve(process.env.BMH_LIVE_DIR || path.join(__dirname, 'work'));
/** The repository this kit lives in. */
const REPO = path.resolve(__dirname, '..', '..');
const mineflayer = require('mineflayer');
const JAVA = process.env.JAVA || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// NBT strings are Java's modified UTF-8. Node would turn supplementary characters into U+FFFD.
const nodeToString = Buffer.prototype.toString;
Buffer.prototype.toString = function (enc, start, end) {
  if (enc !== undefined && enc !== 'utf8' && enc !== 'utf-8') return nodeToString.apply(this, arguments);
  const src = this.subarray(start || 0, end === undefined ? this.length : end);
  if (!src.includes(0xED)) return nodeToString.call(src, 'utf8');
  const out = [];
  for (let i = 0; i < src.length; i++) {
    if (src[i] === 0xED && (src[i + 1] & 0xF0) === 0xA0 && src[i + 3] === 0xED && (src[i + 4] & 0xF0) === 0xB0) {
      const hi = 0xD000 | ((src[i + 1] & 0x3F) << 6) | (src[i + 2] & 0x3F);
      const lo = 0xD000 | ((src[i + 4] & 0x3F) << 6) | (src[i + 5] & 0x3F);
      const c = 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00);
      out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      i += 5;
    } else out.push(src[i]);
  }
  return nodeToString.call(Buffer.from(out), 'utf8');
};

// ---- Reporting ----
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail: String(detail) });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? '  [' + detail + ']' : ''}`);
  return !!ok;
}
/** A check that couldn't run here. It's listed in the summary, and counts as neither a pass nor a failure. */
const skipped = [];
function skip(name, reason) {
  skipped.push({ name, reason });
  console.log(`SKIP  ${name}  [${reason}]`);
}
const section = (t) => console.log(`\n=== ${t}`);
function summary() {
  const failed = results.filter((r) => !r.ok);
  console.log(`\nSUMMARY ${results.length - failed.length}/${results.length} passed${skipped.length ? `, ${skipped.length} skipped` : ''}`);
  failed.forEach((f) => console.log(`  FAILED ${f.name} [${f.detail}]`));
  skipped.forEach((f) => console.log(`  SKIPPED ${f.name} [${f.reason}]`));
  return failed.length;
}

// ---- Server ----
class Server {
  constructor(dir, jar, port) { this.dir = path.join(ROOT, dir); this.jar = path.join(ROOT, jar); this.port = port; this.log = ''; }
  async start(timeoutMs = 240000) {
    this.log = '';
    this.proc = cp.spawn(JAVA, ['-Xmx3G', '-Djava.awt.headless=true', '-jar', this.jar, 'nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] });
    const add = (d) => { this.log += d.toString(); };
    this.proc.stdout.on('data', add); this.proc.stderr.on('data', add);
    this.exited = new Promise((r) => this.proc.on('exit', r));
    await this.waitLog(/Done \(|Failed to start/, timeoutMs);
    if (/Failed to start/.test(this.log)) throw new Error('Server failed to start');
    return this;
  }
  cmd(c) { this.proc.stdin.write(c + '\n'); }
  mark() { return this.log.length; }
  since(m) { return this.log.slice(m); }
  async waitLog(re, timeoutMs = 20000, from = 0) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) { const m = this.log.slice(from).match(re); if (m) return m; await sleep(100); }
    return null;
  }
  async run(c, re, timeoutMs = 8000) { const m = this.mark(); this.cmd(c); return re ? this.waitLog(re, timeoutMs, m) : (await sleep(150), null); }
  async stop() {
    if (!this.proc) return;
    this.cmd('stop');
    await Promise.race([this.exited, sleep(60000)]);
    if (this.proc.exitCode === null) { this.proc.kill('SIGKILL'); await this.exited; }
    this.proc = null;
    await sleep(500);
  }
  errors(from = 0) {
    return this.log.slice(from).split('\n').filter((l) => /\bERROR\b|Exception|\bat com\.extantape|BlueMapMini.*WARN|WARN.*BlueMapMini/.test(l) && !/Advanced terminal/.test(l));
  }
  path(...p) { return path.join(this.dir, ...p); }
  setConfig(edits) { editYaml(this.path('plugins/BlueMapMini/config.yml'), edits); }
}

/** Sets scalar values by dotted path, keeping everything else in the file. */
function editYaml(file, edits) {
  let lines = fs.readFileSync(file, 'utf8').split('\n');
  for (const [dotted, value] of Object.entries(edits)) {
    const keys = dotted.split('.');
    let depth = 0, found = false;
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].match(/^(\s*)([\w-]+):(.*)$/);
      if (!m) continue;
      if (m[1].length === depth * 2 && m[2] === keys[depth]) {
        if (depth === keys.length - 1) { lines[i] = `${m[1]}${m[2]}: ${JSON.stringify(value)}`; found = true; break; }
        depth++;
      }
    }
    if (!found) throw new Error('No such config key ' + dotted);
  }
  fs.writeFileSync(file, lines.join('\n'));
}

// ---- PNG ----
function readPng(data) {
  if (data.readUInt32BE(0) !== 0x89504e47) throw new Error('Not a PNG');
  let pos = 8, w = 0, h = 0, idat = [], ctype = 6;
  while (pos < data.length) {
    const n = data.readUInt32BE(pos), kind = data.toString('latin1', pos + 4, pos + 8), body = data.subarray(pos + 8, pos + 8 + n);
    pos += 12 + n;
    if (kind === 'IHDR') { w = body.readUInt32BE(0); h = body.readUInt32BE(4); ctype = body[9]; if (body[8] !== 8 || ctype !== 6) throw new Error('Only RGBA8'); }
    if (kind === 'IDAT') idat.push(body);
  }
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * 4, out = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], so = y * (stride + 1) + 1, d = y * stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= 4 ? out[d + i - 4] : 0, b = y ? out[d - stride + i] : 0, c = i >= 4 && y ? out[d - stride + i - 4] : 0;
      let v = raw[so + i];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      out[d + i] = v & 255;
    }
  }
  return { w, h, rgba: out, argb: (x, y) => { const o = (y * w + x) * 4; return ((out[o + 3] << 24) | (out[o] << 16) | (out[o + 1] << 8) | out[o + 2]) >>> 0; } };
}

// ---- Pack and frame decoding ----
const TINY = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.,:/+'()_?", READOUT = '0123456789-NESW:0123456789'; // Then the clock's fixed-width digits.
const NAMED = { black: 0, dark_blue: 0xAA, dark_green: 0xAA00, dark_aqua: 0xAAAA, dark_red: 0xAA0000, dark_purple: 0xAA00AA, gold: 0xFFAA00, gray: 0xAAAAAA, dark_gray: 0x555555, blue: 0x5555FF, green: 0x55FF55, aqua: 0x55FFFF, red: 0xFF5555, light_purple: 0xFF55FF, yellow: 0xFFFF55, white: 0xFFFFFF };
function unzipAll(zipPath) {
  const names = cp.execFileSync('unzip', ['-Z1', zipPath], { encoding: 'utf8', maxBuffer: 1 << 26 }).trim().split('\n');
  const files = {};
  for (const n of names) if (!n.endsWith('/')) files[n] = cp.execFileSync('unzip', ['-p', zipPath, n], { maxBuffer: 1 << 28 });
  return files;
}
class Pack {
  constructor(buffer) {
    const tmp = path.join(ROOT, `tmp-${crypto.randomBytes(4).toString('hex')}.zip`);
    fs.writeFileSync(tmp, buffer);
    this.files = unzipAll(tmp); fs.unlinkSync(tmp);
    this.fonts = {};
    for (const [name, data] of Object.entries(this.files)) {
      const m = name.match(/^assets\/bluemapmini\/font\/(\w+)\.json$/);
      if (m) this.fonts[m[1]] = this.loadFont(JSON.parse(data.toString('utf8')));
    }
  }
  loadFont(font) {
    const map = new Map(), sheets = {};
    for (const p of font.providers) {
      if (p.type === 'space') { for (const [k, v] of Object.entries(p.advances)) map.set(k.codePointAt(0), { kind: 'space', advance: v }); continue; }
      if (p.ascent > p.height) throw new Error(`ascent ${p.ascent} > height ${p.height}`);
      const file = 'assets/bluemapmini/textures/' + p.file.split(':')[1];
      const sheet = sheets[file] || (sheets[file] = readPng(this.files[file]));
      const rows = p.chars.map((s) => [...s]), cols = rows[0].length;
      const cw = sheet.w / cols, ch = sheet.h / rows.length, scale = p.height / ch;
      const kind = /_runs\.png$/.test(file) ? 'run' : /_icons\.png$/.test(file) ? 'icon' : /_readout\.png$/.test(file) ? 'read'
        : /_plate\d*\.png$/.test(file) ? 'plate' : /_clock\.png$/.test(file) ? 'clock' : 'text';
      rows.forEach((row, r) => row.forEach((c, i) => {
        if (c.codePointAt(0) === 0) return;
        let lit = 0;
        outer: for (let x = cw - 1; x >= 0; x--) for (let y = 0; y < ch; y++) if (sheet.rgba[((r * ch + y) * sheet.w + i * cw + x) * 4 + 3]) { lit = x + 1; break outer; }
        map.set(c.codePointAt(0), { kind, index: r * cols + i, ascent: p.ascent, advance: Math.floor(0.5 + lit * scale) + 1, width: lit * scale });
      }));
    }
    return map;
  }
}
function plainNbt(tag) {
  if (tag === null || typeof tag !== 'object') return tag;
  const { type, value } = tag;
  if (type === 'compound') {
    if (Array.isArray(value)) return value.map((e) => Object.fromEntries(Object.entries(e).map(([k, v]) => [k, plainNbt(v)])));
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plainNbt(v)]));
  }
  if (type === 'list') return plainNbt(value);
  return value;
}
/** Turns a title into placed glyphs, using the client's layout rules and the pack actually downloaded. */
function decodeFrame(title, pack) {
  const root = plainNbt(title);
  if (!root || !root.font) return { empty: true, root };
  const font = pack.fonts[root.font.split(':')[1]];
  const f = { fontName: root.font, shadow: root.shadow_color, runs: [], icons: [], text: [], plates: [], clocks: [], net: 0, missing: 0, spans: (root.extra || []).length };
  let x = 0;
  for (const s of root.extra || []) {
    const col = s.color, rgb = col === undefined ? 0xFFFFFF : col in NAMED ? NAMED[col] : parseInt(col.slice(1), 16);
    for (const ch of s.text !== undefined ? s.text : s[''] || '') {
      const g = font.get(ch.codePointAt(0));
      if (!g) { f.missing++; continue; }
      if (g.kind === 'run') f.runs.push({ x, top: 7 - g.ascent, width: g.width, color: rgb });
      if (g.kind === 'icon') f.icons.push({ icon: g.index, x, top: 7 - g.ascent, color: rgb });
      if (g.kind === 'text') f.text.push({ ch: TINY[g.index], x, top: 7 - g.ascent, color: rgb, adv: g.advance });
      if (g.kind === 'read') f.text.push({ ch: READOUT[g.index], x, top: 7 - g.ascent, color: rgb, adv: g.advance, readout: true });
      if (g.kind === 'plate') f.plates.push({ x, top: 7 - g.ascent, width: g.width, color: rgb });
      if (g.kind === 'clock') f.clocks.push({ index: g.index, x, top: 7 - g.ascent });
      x += g.advance;
    }
  }
  f.net = x;
  return f;
}
/** Reads the grid back out of the runs. */
function gridOf(f, step) {
  if (!f.runs.length) return null;
  const left = f.runs.reduce((a, r) => Math.min(a, r.x), Infinity), top = f.runs.reduce((a, r) => Math.min(a, r.top), Infinity);
  const right = f.runs.reduce((a, r) => Math.max(a, r.x + r.width), -Infinity), bottom = f.runs.reduce((a, r) => Math.max(a, r.top), -Infinity) + step;
  const cols = (right - left) / step, rows = (bottom - top) / step;
  const cells = new Array(cols * rows).fill(-1);
  let gaps = 0;
  for (const r of f.runs) {
    const z = (r.top - top) / step;
    for (let i = 0; i < r.width / step; i++) {
      const cell = z * cols + (r.x - left) / step + i;
      if (cells[cell] !== -1) gaps++; // Overlap.
      cells[cell] = r.color;
    }
  }
  gaps += cells.filter((c) => c === -1).length;
  return { left, top, cols, rows, cells, gaps, step };
}
function iconCell(icon, grid) {
  const half = Math.floor(grid.step / 2);
  return { x: (icon.x - grid.left - half + 4) / grid.step, z: (icon.top + 4 - half - grid.top) / grid.step };
}
function textLines(f) {
  const rows = {};
  for (const t of f.text) (rows[t.top] = rows[t.top] || []).push(t);
  return Object.keys(rows).map(Number).sort((a, b) => a - b).map((top) => {
    const glyphs = rows[top].sort((a, b) => a.x - b.x);
    let s = '', end = null;
    for (const g of glyphs) { if (end !== null && g.x - end >= 4) s += ' '; s += g.ch; end = g.x + g.adv; }
    return { top, s };
  });
}

// ---- BlueMap tiles, read independently of the plugin ----
class Truth {
  constructor(mapsRoot, voidColor = 0x14161a, step = 8) { this.root = mapsRoot; this.cache = new Map(); this.voidColor = voidColor; this.step = step; }
  tilePath(map, lod, x, z) {
    const enc = `x${x}z${z}`; let out = '', parts = [];
    for (const c of enc) { out += c; if (c >= '0' && c <= '9') { parts.push(out); out = ''; } }
    const file = parts.pop() + '.png';
    return path.join(this.root, map, 'tiles', String(lod), ...parts, file);
  }
  tile(map, lod, x, z) {
    const k = `${map}/${lod}/${x}/${z}`, p = this.tilePath(map, lod, x, z);
    const mtime = fs.existsSync(p) ? fs.statSync(p).mtimeMs : 0, hit = this.cache.get(k);
    if (hit && hit.mtime === mtime) return hit.png; // BlueMap rewrites tiles as chunks change, so follow the file.
    let png = null;
    try { png = mtime ? readPng(fs.readFileSync(p)) : null; } catch (e) { png = null; }
    this.cache.set(k, { mtime, png });
    return png;
  }
  clear() { this.cache.clear(); }
  sample(map, lod, bx, bz) {
    const px = lod === 1 ? 1 : lod === 2 ? 5 : 25, size = 500;
    const pxX = Math.floor(bx / px), pxZ = Math.floor(bz / px);
    const t = this.tile(map, lod, Math.floor(pxX / size), Math.floor(pxZ / size));
    if (!t) return 0;
    return t.argb(((pxX % size) + size) % size, ((pxZ % size) + size) % size);
  }
  lodFor(bpc) { return bpc >= 25 ? 3 : bpc >= 5 ? 2 : 1; }
  cell(map, bpc, cx, cz, brightness = 1) {
    const lod = this.lodFor(bpc), px = lod === 1 ? 1 : lod === 2 ? 5 : 25;
    const n = Math.max(1, Math.min(3, Math.floor(bpc / px))), sp = Math.max(1, Math.floor(bpc / n));
    let r = 0, g = 0, b = 0, c = 0;
    for (let sz = 0; sz < n; sz++) for (let sx = 0; sx < n; sx++) {
      const a = this.sample(map, lod, cx * bpc + sx * sp, cz * bpc + sz * sp);
      if (a >>> 24 === 0) continue;
      r += (a >> 16) & 255; g += (a >> 8) & 255; b += a & 255; c++;
    }
    const v = c === 0 ? this.voidColor : (Math.floor(r / c) << 16) | (Math.floor(g / c) << 8) | Math.floor(b / c);
    const shade = (ch) => Math.min(255, Math.floor((Math.floor(ch * brightness) + this.step / 2) / this.step) * this.step);
    return (shade((v >> 16) & 255) << 16) | (shade((v >> 8) & 255) << 8) | shade(v & 255);
  }
}

// ---- Bot ----
class Bot {
  constructor(name, port, opts = {}) {
    this.name = name; this.mode = opts.mode || 'accept'; this.pushes = []; this.pops = []; this.chat = []; this.barEvents = [];
    this.frames = []; this.barBytes = []; this.actionBars = []; this.ended = null; this.kicked = null; this.pack = null;
    this.autoDiscard = opts.autoDiscard !== false; this.bars = new Map();
    this.bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: opts.version || '1.21.11', auth: 'offline', physicsEnabled: false, hideErrors: true });
    this.bot.physicsEnabled = false;
    const c = this.bot._client;
    // Tests move players from the server. The library's position replies only add noise, and through
    // ViaBackwards they get the bot kicked, so they are dropped. Teleport confirmations still go out.
    const write = c.write.bind(c);
    c.write = (name, params) => { if (!['position', 'position_look', 'look', 'flying'].includes(name)) write(name, params); };
    this.spawned = new Promise((res) => this.bot.once('spawn', () => { this.bot.physicsEnabled = false; res(); }));
    c.on('add_resource_pack', (p) => this.onPush(p));
    c.on('remove_resource_pack', (p) => this.onPop(p));
    c.on('boss_bar', (p) => this.onBar(p));
    c.on('action_bar', (p) => this.actionBars.push({ t: Date.now(), text: p.text }));
    c.on('raw.boss_bar', (buf) => this.barBytes.push({ t: Date.now(), raw: buf.length, zlib: zlib.deflateSync(buf).length }));
    this.bot.on('messagestr', (m) => this.chat.push(m));
    this.bot.on('kicked', (r) => { this.kicked = JSON.stringify(r); });
    this.bot.on('end', (r) => { this.ended = r; });
    this.bot.on('error', () => {});
  }
  async onPush(p) {
    this.pushes.push({ t: Date.now(), ...p });
    this.lastPack = p.uuid;
    try { this.downloadStatus = (await fetch(p.url)).status; } catch (e) { this.downloadStatus = 'failed'; }
    if (this.downloadStatus === 200) {
      const res = await fetch(p.url), buf = Buffer.from(await res.arrayBuffer());
      this.packSha = crypto.createHash('sha1').update(buf).digest('hex');
      if (p.url.includes('bluemapmini')) { this.packUrl = p.url; this.packBytes = buf; }
    }
    const send = (result) => this.bot._client.write('resource_pack_receive', { uuid: p.uuid, result });
    if (this.mode === 'ignore') return;
    if (this.mode === 'decline') return send(1);
    if (this.mode === 'fail') return send(2);
    send(3); send(4); send(0);
  }
  onPop(p) {
    this.pops.push({ t: Date.now(), uuid: p.uuid });
    // A real client reports DISCARDED for each popped pack (ServerPackManager.RemovalReason.DISCARDED).
    if (this.autoDiscard && this.lastPack) this.bot._client.write('resource_pack_receive', { uuid: p.uuid || this.lastPack, result: 7 });
  }
  onBar(p) {
    const id = String(p.entityUUID);
    this.barEvents.push({ t: Date.now(), action: p.action, id, color: p.color, health: p.health });
    if (p.action === 0) this.bars.set(id, { title: p.title, color: p.color, health: p.health });
    if (p.action === 1) this.bars.delete(id);
    this.maxBars = Math.max(this.maxBars || 0, this.bars.size);
    if (p.action === 3) { const b = this.bars.get(id); if (b) b.title = p.title; }
    if (p.action === 0 || p.action === 3) this.frames.push({ t: Date.now(), id, title: p.title });
  }
  say(m) { this.bot.chat(m); }
  sneak(on) { this.bot._client.write('player_input', { inputs: { shift: on } }); }
  swapF() { this.bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); }
  /** Bars other than the given one, from this bot's point of view. */
  barCount() { return this.bars.size; }
  /** The newest frame drawn with our font. */
  frame(pack) {
    for (let i = this.frames.length - 1; i >= 0; i--) {
      const b = this.bars.get(this.frames[i].id);
      if (!b) continue;
      const d = decodeFrame(b.title, pack);
      if (!d.empty) return d;
    }
    return null;
  }
  hudFrame(pack) {
    for (const b of this.bars.values()) { const d = decodeFrame(b.title, pack); if (!d.empty) return d; }
    return null;
  }
  quit() { try { this.bot.quit(); } catch (e) {} }
  async cleanQuit() { this.quit(); const end = Date.now() + 5000; while (this.ended === null && Date.now() < end) await sleep(50); }
}
async function until(fn, ms = 6000, every = 50) { const end = Date.now() + ms; while (Date.now() < end) { const v = await fn(); if (v) return v; await sleep(every); } return null; }
module.exports = { REPO, JAVA, skip, Server, Bot, Pack, Truth, decodeFrame, gridOf, iconCell, textLines, readPng, plainNbt, check, section, summary, sleep, until, editYaml, ROOT };
