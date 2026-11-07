import { chromium } from 'playwright-core';

const browser = await chromium.launch({ channel: 'msedge' });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });
page.on('pageerror', (e) => errs.push(String(e)));
await page.goto('http://localhost:5197/?import=project', { waitUntil: 'networkidle', timeout: 60000 });
await page.waitForTimeout(6000);
await page.screenshot({ path: 'out/qa/workbench.png' });
console.log('errors:', errs.slice(0, 5));
console.log('title:', await page.title());
await browser.close();
