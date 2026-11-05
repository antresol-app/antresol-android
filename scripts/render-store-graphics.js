#!/usr/bin/env node
// Renders the Google Play listing graphics into both fastlane metadata trees
// (mobile/fastlane and tv/fastlane) for en-US and ru-RU:
//   icon.png (512x512), featureGraphic.png (1024x500), tvBanner.png (1280x720, TV only),
//   phoneScreenshots/ (1080x1920 framed + captioned), tenInchScreenshots/ and tvScreenshots/ (copied).
// Sources: docs/design-ref/{app-icon,logo-mark}.svg and the gallery in docs/screenshots.
// Usage: node scripts/render-store-graphics.js   (needs the `playwright` npm package with Chromium)
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const LOCALES = ['en-US', 'ru-RU'];
const FONT = `'Inter', 'DejaVu Sans', system-ui, sans-serif`;
const BRAND = 'linear-gradient(160deg, #3a1c8c, #9b2fc9)';

const TAGLINE = {
  'en-US': ['Your films, music and books.', 'One library.'],
  'ru-RU': ['Твои фильмы. Твоя музыка. Твои книги.', 'В одной библиотеке.'],
};

// Phone shots: [gallery file, en caption, ru caption]
const PHONE = [
  ['04-home.png', 'Your whole library, one tap away', 'Вся библиотека под рукой'],
  ['07-movie-detail.png', 'Every audio track and subtitle', 'Все звуковые дорожки и субтитры'],
  ['08-series-detail.png', 'Series with seasons and progress', 'Сериалы с сезонами и прогрессом'],
  ['11-music.png', 'All your music in one place', 'Вся твоя музыка в одном месте'],
  ['14-now-playing.png', 'Keeps playing in the background', 'Играет и в фоне'],
  ['16-books.png', 'Books, comics and audiobooks', 'Книги, комиксы и аудиокниги'],
  ['18-audiobook-playing.png', 'Audiobooks pick up where you left off', 'Аудиокниги с того места, где остановился'],
  ['21-search-results.png', 'Find anything in seconds', 'Найдётся всё за секунды'],
];
const TABLET = ['02-home.png', '03-movies.png', '04-movie-detail.png', '05-series-detail.png',
  '08-music.png', '10-album.png', '12-books.png', '13-audiobook.png'];
const TV = ['04-home.png', '05-home-shelves.png', '07-movie-detail.png', '08-series-detail.png',
  '11-music.png', '13-now-playing.png', '14-books.png', '15-audiobook.png'];

const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const dataUrl = (p) => 'data:image/png;base64,' + fs.readFileSync(path.join(ROOT, p)).toString('base64');
const mark = read('docs/design-ref/logo-mark.svg').replace('<svg ', '<svg width="100%" height="100%" ');
// Full-bleed square icon: Play applies its own mask, so drop the rounded corners.
const iconSvg = read('docs/design-ref/app-icon.svg')
  .replace('rx="228"', '')
  .replace('<svg ', '<svg width="512" height="512" ');

const page = (w, h, body, css = '') => `<!doctype html><html><head><meta charset="utf-8"><style>
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

function phoneFrame(file, caption) {
  const W = 1080, H = 1920, SH = 1500, SW = Math.round(SH * 1080 / 2400);
  return page(W, H, `<div class="brand">${glows(W, H)}
  <div class="cap">${caption}</div>
  <img class="shot" src="${dataUrl('docs/screenshots/mobile/phone/' + file)}"></div>`, `
.cap{position:absolute;left:90px;right:90px;top:110px;height:220px;display:flex;align-items:center;justify-content:center;
  text-align:center;text-wrap:balance;font-weight:800;font-size:76px;line-height:1.1;letter-spacing:-0.02em}
.shot{position:absolute;left:${(W - SW) / 2}px;top:${H - SH - 70}px;width:${SW}px;height:${SH}px;border-radius:56px;
  border:3px solid rgba(255,255,255,.35);box-shadow:0 30px 80px rgba(20,6,46,.55)}`);
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

function copyShots(srcDir, files, outDir) {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  files.forEach((f) => fs.copyFileSync(path.join(ROOT, srcDir, f), path.join(outDir, f)));
}

(async () => {
  const browser = await chromium.launch();
  for (const app of ['mobile', 'tv']) {
    for (const loc of LOCALES) {
      const img = path.join(ROOT, app, 'fastlane/metadata/android', loc, 'images');
      await shot(browser, page(512, 512, iconSvg), 512, 512, path.join(img, 'icon.png'));
      await toRgba(browser, path.join(img, 'icon.png'));
      await shot(browser, lockup(1024, 500, loc, 1), 1024, 500, path.join(img, 'featureGraphic.png'));
      if (app === 'tv') {
        await shot(browser, lockup(1280, 720, loc, 1.3), 1280, 720, path.join(img, 'tvBanner.png'));
        copyShots('docs/screenshots/tv', TV, path.join(img, 'tvScreenshots'));
      } else {
        const dir = path.join(img, 'phoneScreenshots');
        fs.rmSync(dir, { recursive: true, force: true });
        for (const [file, en, ru] of PHONE) {
          await shot(browser, phoneFrame(file, loc === 'ru-RU' ? ru : en), 1080, 1920, path.join(dir, file));
        }
        copyShots('docs/screenshots/mobile/tablet', TABLET, path.join(img, 'tenInchScreenshots'));
      }
    }
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
