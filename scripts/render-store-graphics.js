#!/usr/bin/env node
// Renders the Google Play listing graphics into both fastlane metadata trees
// (mobile/fastlane and tv/fastlane) for en-US and ru-RU:
//   icon.png (512x512), featureGraphic.png (1024x500), tvBanner.png (1280x720, TV only),
//   phoneScreenshots/ (1080x1920), tenInchScreenshots/ (2560x1600) and tvScreenshots/ (1920x1080): posters with the
//   brand gradient, an English caption and the capture inside a CSS-drawn phone, tablet or TV frame.
// Sources: docs/design-ref/{app-icon,logo-mark}.svg and the emulator captures in docs/store-screenshots.
// Usage: node scripts/render-store-graphics.js [--screenshots] [--offline]
//   --screenshots  only the posters (leave icon, feature graphic and TV banner untouched)
//   --offline      do not load Inter from Google Fonts (system sans instead)
// Needs the `playwright` npm package with Chromium (e.g. NODE_PATH=<dir with node_modules/playwright>).
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const LOCALES = ['en-US', 'ru-RU'];
const FONT = `'Inter', 'DejaVu Sans', system-ui, sans-serif`;
const BRAND = 'linear-gradient(160deg, #3a1c8c, #9b2fc9)';
// Inter from Google Fonts when online (`--offline` skips it and uses the system sans).
const FONT_LINK = process.argv.includes('--offline') ? ''
  : '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@600;800&display=block">';
const ONLY_SCREENSHOTS = process.argv.includes('--screenshots');

const TAGLINE = {
  'en-US': ['Your films, music and books.', 'One library.'],
  'ru-RU': ['Твои фильмы. Твоя музыка. Твои книги.', 'В одной библиотеке.'],
};

// Store screenshots: real captures from emulators signed in to a demo server with open-licensed content
// (docs/store-screenshots/{phone,tablet,tv}/*.jpg). Each becomes a "poster": brand gradient, an English caption
// and the capture inside a device frame drawn here in CSS (no third-party device art). [source, caption]
const SHOTS = {
  phone: [
    ['home', 'Your whole library, one tap away'],
    ['film', 'Every audio track and subtitle'],
    ['series', 'Series with seasons and progress'],
    ['player', 'Picks up right where you stopped'],
    ['album', 'All your music in one place'],
    ['now-playing', 'Keeps playing in the background'],
    ['books', 'Books, comics and audiobooks'],
    ['search', 'Find anything in seconds'],
  ],
  tablet: [
    ['home', 'Your whole library on a bigger screen'],
    ['film', 'Every audio track and subtitle'],
    ['series', 'Series with seasons and progress'],
    ['player', 'Picks up right where you stopped'],
    ['album', 'All your music in one place'],
    ['now-playing', 'Keeps playing in the background'],
    ['books', 'Books, comics and audiobooks'],
    ['search', 'Find anything in seconds'],
  ],
  tv: [
    ['home', 'Your library on the big screen'],
    ['film', 'Every audio track and subtitle'],
    ['series', 'Series with seasons and progress'],
    ['player', 'Picks up right where you stopped'],
    ['album', 'All your music, made for the remote'],
    ['now-playing', 'Up Next, lyrics and a sleep timer'],
    ['books', 'Books, comics and audiobooks'],
    ['search', 'Find anything in seconds'],
  ],
};

const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const dataUrl = (p) => 'data:image/png;base64,' + fs.readFileSync(path.join(ROOT, p)).toString('base64');
const mark = read('docs/design-ref/logo-mark.svg').replace('<svg ', '<svg width="100%" height="100%" ');
// Full-bleed square icon: Play applies its own mask, so drop the rounded corners.
const iconSvg = read('docs/design-ref/app-icon.svg')
  .replace('rx="228"', '')
  .replace('<svg ', '<svg width="512" height="512" ');

const page = (w, h, body, css = '') => `<!doctype html><html><head><meta charset="utf-8">${FONT_LINK}<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:${w}px;height:${h}px;overflow:hidden;background:#000}
body{font-family:${FONT};color:#fff;-webkit-font-smoothing:antialiased}
.brand{position:absolute;inset:0;background:${BRAND};overflow:hidden}
.glow{position:absolute;border-radius:50%;filter:blur(60px)}
${css}</style></head><body>${body}</body></html>`;

const glows = (w, h) => `
<div class="glow" style="width:${w * 0.6}px;height:${w * 0.6}px;left:${-w * 0.18}px;top:${-h * 0.5}px;background:rgba(120,90,255,.35)"></div>
<div class="glow" style="width:${w * 0.55}px;height:${w * 0.55}px;right:${-w * 0.15}px;bottom:${-h * 0.6}px;background:rgba(255,80,200,.25)"></div>`;

// Horizontal lockup: mark | wordmark over tagline. s = scale factor.
function lockup(w, h, locale, s) {
  const [l1, l2] = TAGLINE[locale];
  return page(w, h, `<div class="brand">${glows(w, h)}
  <div class="wrap"><div class="mark">${mark}</div>
    <div class="text"><div class="word">Antresol</div><div class="tag">${l1}<br>${l2}</div></div></div></div>`, `
.wrap{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:${36 * s}px}
.mark{width:${200 * s}px;height:${200 * s}px;flex:none}
.word{font-weight:800;font-size:${96 * s}px;letter-spacing:-0.025em;line-height:1}
.tag{margin-top:${18 * s}px;font-weight:600;font-size:${locale === 'ru-RU' ? 27 * s : 32 * s}px;line-height:1.3;
  color:rgba(255,255,255,.86);letter-spacing:-0.005em}`);
}

// Poster canvases (Play: each side 320–3840 px, long side at most twice the short side) and where the caption and the
// device go. Captions are English for every locale (the UI in the captures is English).
const POSTER = {
  phone: { w: 1080, h: 1920, font: 76, cap: [90, 110, 900, 230], area: [70, 400, 940, 1450] },
  tablet: { w: 2560, h: 1600, font: 92, cap: [160, 70, 2240, 230], area: [140, 350, 2280, 1180] },
  tv: { w: 1920, h: 1080, font: 62, cap: [160, 40, 1600, 150], area: [150, 215, 1620, 840] },
};

const jpegUrl = (p) => 'data:image/jpeg;base64,' + fs.readFileSync(path.join(ROOT, p)).toString('base64');
const jpegSize = (p) => { // width/height from the SOFn marker
  const b = fs.readFileSync(path.join(ROOT, p));
  for (let i = 2; i < b.length;) {
    const marker = b[i + 1], len = b.readUInt16BE(i + 2);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { w: b.readUInt16BE(i + 7), h: b.readUInt16BE(i + 5) };
    }
    i += 2 + len;
  }
  throw new Error('No JPEG size in ' + p);
};

// Device frames, all measured from the screen's short side (s) so phone, tablet and TV keep their proportions:
// bezel width, screen corner radius (the frame's outer radius is screen radius + bezel, so the corners stay concentric).
const DEVICE = {
  phone: { bezel: 0.03, radius: 0.096, stand: 0 },   // Pixel-8-like: thin bezel, round corners, punch-hole camera
  tablet: { bezel: 0.045, radius: 0.035, stand: 0 }, // tablet: wider bezel, camera in the long top bezel
  tv: { bezel: 0.012, radius: 0.006, stand: 0.075 }, // TV: slim bezel on a small stand
};

function devicePoster(kind, src, caption) {
  const P = POSTER[kind], D = DEVICE[kind];
  const img = jpegSize(src);
  const landscape = img.w > img.h;
  const [ax, ay, aw, ah] = P.area;
  // Largest screen that fits the area with its bezel (and stand), keeping the capture's aspect ratio.
  const fit = (sw) => {
    const sh = sw * img.h / img.w, short = Math.min(sw, sh), bz = short * D.bezel;
    return { sw, sh, bz, w: sw + 2 * bz, h: sh + 2 * bz + short * D.stand };
  };
  let lo = 10, hi = aw;
  for (let i = 0; i < 40; i++) { const m = (lo + hi) / 2, f = fit(m); if (f.w <= aw && f.h <= ah) lo = m; else hi = m; }
  const f = fit(Math.floor(lo));
  const sw = Math.round(f.sw), sh = Math.round(f.sh), bz = Math.round(f.bz), short = Math.min(sw, sh);
  const r = Math.round(short * D.radius);
  const left = Math.round(ax + (aw - f.w) / 2);
  let top = Math.round(ay + (ah - f.h) / 2), capTop = P.cap[1];
  if (landscape && P.h > P.w) {
    // A landscape capture on a portrait poster (the phone player): centre caption and device together.
    const gap = 80, group = P.cap[3] + gap + f.h;
    capTop = Math.round((P.h - group) / 2);
    top = capTop + P.cap[3] + gap;
  }
  let extras = '';
  if (kind === 'phone') {
    // Punch-hole camera: centred on the top edge in portrait; the device turns 90° for landscape video, so the
    // camera goes to the left edge and the side buttons (right edge in portrait) to the top.
    const d = Math.round(short * 0.067), off = Math.round(short * 0.061);
    extras += landscape
      ? `<div class="hole" style="width:${d}px;height:${d}px;left:${bz + off - d / 2}px;top:${bz + sh / 2 - d / 2}px"></div>`
      : `<div class="hole" style="width:${d}px;height:${d}px;left:${bz + sw / 2 - d / 2}px;top:${bz + off - d / 2}px"></div>`;
    const bw = Math.max(5, Math.round(short * 0.009));
    const btn = (from, len) => landscape
      ? `<div class="btn" style="left:${Math.round(f.w * (1 - from - len))}px;top:${-bw + 1}px;width:${Math.round(f.w * len)}px;height:${bw}px"></div>`
      : `<div class="btn" style="top:${Math.round(f.h * from)}px;right:${-bw + 1}px;height:${Math.round(f.h * len)}px;width:${bw}px"></div>`;
    extras += btn(0.2, 0.07) + btn(0.31, 0.13);
  } else if (kind === 'tablet') {
    const d = Math.max(8, Math.round(bz * 0.32));
    extras += `<div class="hole" style="width:${d}px;height:${d}px;left:${f.w / 2 - d / 2}px;top:${bz / 2 - d / 2}px"></div>`;
  } else if (kind === 'tv') {
    const neckW = Math.round(sw * 0.07), neckH = Math.round(short * D.stand * 0.55);
    const footW = Math.round(sw * 0.3), footH = Math.round(short * D.stand * 0.45);
    extras += `<div class="neck" style="width:${neckW}px;height:${neckH}px;left:${f.w / 2 - neckW / 2}px;top:${sh + 2 * bz}px"></div>
      <div class="foot" style="width:${footW}px;height:${footH}px;left:${f.w / 2 - footW / 2}px;top:${sh + 2 * bz + neckH}px"></div>`;
  }
  return page(P.w, P.h, `<div class="brand">${glows(P.w, P.h)}
  <div class="cap" style="top:${capTop}px">${caption}</div>
  <div class="dev" style="left:${left}px;top:${top}px;width:${sw + 2 * bz}px;height:${sh + 2 * bz}px;border-radius:${r + bz}px">
    <div class="screen" style="left:${bz}px;top:${bz}px;width:${sw}px;height:${sh}px;border-radius:${r}px">
      <img src="${jpegUrl(src)}" width="${sw}" height="${sh}"></div>${extras}</div></div>`, `
.cap{position:absolute;left:${P.cap[0]}px;width:${P.cap[2]}px;height:${P.cap[3]}px;display:flex;
  align-items:center;justify-content:center;text-align:center;text-wrap:balance;font-weight:800;font-size:${P.font}px;
  line-height:1.1;letter-spacing:-0.02em}
.dev{position:absolute;background:#0b0b0f;
  box-shadow:inset 0 0 0 2px #34343d,inset 0 0 0 4px #121217,0 ${P.h * 0.02}px ${P.h * 0.05}px rgba(14,4,40,.55)}
.screen{position:absolute;overflow:hidden;background:#000}
.screen img{display:block;width:100%;height:100%}
.hole{position:absolute;border-radius:50%;background:#050507;box-shadow:inset 0 0 0 2px #1d1d24}
.btn{position:absolute;background:#26262d;border-radius:3px}
.neck{position:absolute;background:linear-gradient(90deg,#16161b,#2c2c34,#16161b)}
.foot{position:absolute;border-radius:6px 6px 3px 3px;background:linear-gradient(180deg,#2c2c34,#121216);
  box-shadow:0 ${P.h * 0.012}px ${P.h * 0.03}px rgba(14,4,40,.5)}`);
}

async function shot(browser, html, w, h, out) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  await p.setContent(html, { waitUntil: 'load' });
  await p.evaluate(() => document.fonts.ready);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  await p.screenshot({ path: out, clip: { x: 0, y: 0, width: w, height: h } });
  await ctx.close();
  console.log(path.relative(ROOT, out));
}

// Play wants the icon as a 32-bit PNG, but Chromium writes opaque screenshots as 24-bit RGB.
// Read the pixels back through a canvas and re-encode them as 8-bit RGBA (colour type 6).
async function toRgba(browser, file) {
  const ctx = await browser.newContext({ deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const { w, h, px } = await p.evaluate(async (src) => {
    const im = new Image(); im.src = src; await im.decode();
    const c = document.createElement('canvas'); c.width = im.width; c.height = im.height;
    const g = c.getContext('2d'); g.drawImage(im, 0, 0);
    return { w: c.width, h: c.height, px: Array.from(g.getImageData(0, 0, c.width, c.height).data) };
  }, 'data:image/png;base64,' + fs.readFileSync(file).toString('base64'));
  await ctx.close();
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let i = 0; i < w * 4; i++) raw[y * (w * 4 + 1) + 1 + i] = px[y * w * 4 + i];
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0;
  });
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
}

(async () => {
  const browser = await chromium.launch();
  for (const app of ['mobile', 'tv']) {
    for (const loc of LOCALES) {
      const img = path.join(ROOT, app, 'fastlane/metadata/android', loc, 'images');
      if (!ONLY_SCREENSHOTS) {
        await shot(browser, page(512, 512, iconSvg), 512, 512, path.join(img, 'icon.png'));
        await toRgba(browser, path.join(img, 'icon.png'));
        await shot(browser, lockup(1024, 500, loc, 1), 1024, 500, path.join(img, 'featureGraphic.png'));
        if (app === 'tv') await shot(browser, lockup(1280, 720, loc, 1.3), 1280, 720, path.join(img, 'tvBanner.png'));
      }
      const sets = app === 'tv' ? [['tv', 'tvScreenshots']] : [['phone', 'phoneScreenshots'], ['tablet', 'tenInchScreenshots']];
      for (const [kind, folder] of sets) {
        const dir = path.join(img, folder);
        fs.rmSync(dir, { recursive: true, force: true });
        let n = 0;
        for (const [name, caption] of SHOTS[kind]) {
          const out = path.join(dir, `${String(++n).padStart(2, '0')}-${name}.png`);
          const html = devicePoster(kind, `docs/store-screenshots/${kind}/${name}.jpg`, caption);
          await shot(browser, html, POSTER[kind].w, POSTER[kind].h, out);
        }
      }
    }
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
