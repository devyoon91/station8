'use strict';
/*
 * Station8 UI 스크린샷 캡처 (puppeteer-core + 로컬 Chrome headless).
 * Chromium을 내려받지 않고 이미 설치된 Chrome을 executablePath로 물려 쓴다 — 폐쇄망 친화.
 *
 * 사용:
 *   1) 앱을 먼저 띄운다 (예: ./gradlew :station8-app:bootRun, http://localhost:8080)
 *   2) cd tools/screenshots && npm install
 *   3) BASE_URL / S8_USER / S8_PASS 환경변수 세팅 (.env.example 참고)
 *   4) npm run capture
 *
 * 산출물: docs/assets/screenshots/<name>.<viewport>.<theme>.png + manifest.json
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const puppeteer = require('puppeteer-core');

// 무의존성 .env 로더: tools/screenshots/.env 가 있으면 읽어 process.env에 채운다 (기존 값 우선).
(function loadDotEnv() {
  const envPath = path.join(__dirname, '.env');
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/i);
    if (!m || line.trim().startsWith('#')) continue;
    const key = m[1];
    let val = m[2].replace(/^["']|["']$/g, '');
    if (process.env[key] === undefined) process.env[key] = val;
  }
})();

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const USERNAME = process.env.S8_USER || 'admin';
const PASSWORD = process.env.S8_PASS || '';
const THEMES = (process.env.THEMES || 'light,dark').split(',').map((s) => s.trim()).filter(Boolean);
const OUT_DIR = process.env.OUT_DIR
  ? path.resolve(process.env.OUT_DIR)
  : path.resolve(__dirname, '..', '..', 'docs', 'assets', 'screenshots');
const NAV_TIMEOUT = Number(process.env.NAV_TIMEOUT_MS || 20000);

const VIEWPORTS = {
  desktop: { width: 1440, height: 900, deviceScaleFactor: 1 },
  mobile: { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
};

function findChrome() {
  if (process.env.CHROME_PATH && fs.existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH;
  const home = os.homedir();
  const candidates = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    path.join(home, 'AppData/Local/Google/Chrome/Application/chrome.exe'),
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return null;
}

async function login(page) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0', timeout: NAV_TIMEOUT });
  await page.type('input[name="username"]', USERNAME);
  await page.type('input[name="password"]', PASSWORD);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle0', timeout: NAV_TIMEOUT }),
    page.click('button[type="submit"], input[type="submit"]'),
  ]);
  // 로그인 성공 판정: /login 으로 되튕기지 않았는지
  if (page.url().includes('/login')) {
    throw new Error('로그인 실패 — S8_USER / S8_PASS 확인 (현재 URL: ' + page.url() + ')');
  }
}

// 동적 {id} 치환: 인증된 페이지 컨텍스트에서 목록을 조회해 첫 항목의 id를 얻는다.
async function resolveId(page, kind) {
  try {
    if (kind === 'definition') {
      const id = await page.evaluate(async (base) => {
        const r = await fetch(base + '/api/line/definitions', { credentials: 'include' });
        if (!r.ok) return null;
        const data = await r.json();
        const arr = Array.isArray(data) ? data : data.content || data.items || [];
        return arr.length ? (arr[0].id || arr[0].definitionId) : null;
      }, BASE_URL);
      return id;
    }
    if (kind === 'instance') {
      // 대시보드 HTML에서 첫 인스턴스 링크를 긁는다 (전용 목록 API가 없을 수 있음).
      const id = await page.evaluate(async (base) => {
        const r = await fetch(base + '/line/dashboard', { credentials: 'include' });
        if (!r.ok) return null;
        const html = await r.text();
        const m = html.match(/\/line\/instance\/([\w-]+)/);
        return m ? m[1] : null;
      }, BASE_URL);
      return id;
    }
  } catch (e) {
    return null;
  }
  return null;
}

async function shoot(page, screen, viewportName, theme) {
  const vp = VIEWPORTS[viewportName] || VIEWPORTS.desktop;
  await page.setViewport(vp);
  await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: theme }]);
  await page.goto(BASE_URL + screen._path, { waitUntil: 'networkidle0', timeout: NAV_TIMEOUT });
  if (screen.waitFor) {
    try { await page.waitForSelector(screen.waitFor, { timeout: 5000 }); } catch (e) { /* 진행 */ }
  }
  if (screen.waitMs) await new Promise((r) => setTimeout(r, screen.waitMs));
  const file = `${screen.name}.${viewportName}.${theme}.png`;
  await page.screenshot({ path: path.join(OUT_DIR, file), fullPage: true });
  return file;
}

async function main() {
  const chrome = findChrome();
  if (!chrome) {
    console.error('[x] 로컬 Chrome을 못 찾음. CHROME_PATH 환경변수로 chrome.exe 경로를 지정하세요.');
    process.exit(2);
  }
  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

  const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'screens.json'), 'utf8'));
  const screens = config.screens;
  const needAuth = screens.some((s) => s.auth);
  if (needAuth && !PASSWORD) {
    console.warn('[!] S8_PASS 미설정 — 인증 화면은 실패할 수 있음. .env.example 참고.');
  }

  console.log(`[i] Chrome : ${chrome}`);
  console.log(`[i] BASE   : ${BASE_URL}`);
  console.log(`[i] OUT    : ${OUT_DIR}`);
  console.log(`[i] themes : ${THEMES.join(', ')}`);

  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--force-color-profile=srgb'],
  });
  const page = await browser.newPage();
  page.setDefaultNavigationTimeout(NAV_TIMEOUT);

  const manifest = [];
  try {
    if (needAuth) {
      console.log('[i] 로그인 중...');
      await login(page);
      console.log('[✓] 로그인 성공');
    }

    for (const screen of screens) {
      // 동적 경로 치환
      let resolvedPath = screen.path;
      if (screen.resolve) {
        const id = await resolveId(page, screen.resolve);
        if (!id) {
          console.warn(`[skip] ${screen.name} — ${screen.resolve} id를 못 구함 (데이터 없음?)`);
          continue;
        }
        resolvedPath = screen.path.replace('{id}', encodeURIComponent(id));
      }
      screen._path = resolvedPath;

      const viewports = screen.viewports && screen.viewports.length ? screen.viewports : ['desktop'];
      const files = [];
      for (const vpName of viewports) {
        for (const theme of THEMES) {
          try {
            const file = await shoot(page, screen, vpName, theme);
            files.push(file);
            console.log(`[✓] ${file}`);
          } catch (e) {
            console.error(`[x] ${screen.name} ${vpName}/${theme}: ${e.message}`);
          }
        }
      }
      manifest.push({ name: screen.name, title: screen.title, path: resolvedPath, files });
    }
  } finally {
    await browser.close();
  }

  fs.writeFileSync(
    path.join(OUT_DIR, 'manifest.json'),
    JSON.stringify({ base: BASE_URL, capturedScreens: manifest }, null, 2)
  );
  console.log(`[✓] manifest.json — ${manifest.length}개 화면`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
