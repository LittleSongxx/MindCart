// 管理端"语音会话与归因"页浏览器验收：指标卡 + 会话表 + 详情弹窗（对话流水/归因订单）。
// 运行：BASE=http://localhost:8081 node ops/verify/voice_manager_e2e.mjs
import { chromium } from 'playwright-core';

const exe = process.env.HOME + '/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome';
const BASE = process.env.BASE || 'http://localhost:8081';
const results = [];
const ok = (name, pass, detail = '') => {
  results.push([name, pass, detail]);
  console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' | ' + detail : ''));
};

const browser = await chromium.launch({ executablePath: exe, headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
page.on('pageerror', e => console.log('[pageerror]', e.message));

// 管理员登录
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.fill('input[placeholder="请输入商城账号"]', 'admin');
await page.fill('input[placeholder="请输入登录密码"]', 'admin');
await page.click('button.submit-btn');
await page.waitForTimeout(2500);

// 打开语音会话页
await page.goto(`${BASE}/manager/voiceSession`, { waitUntil: 'networkidle' });
await page.waitForTimeout(2000);

const body = await page.locator('body').innerText();
ok('页面标题渲染', body.includes('语音会话与归因'));
ok('运营指标卡渲染', /会话总数/.test(body) && /语音 GMV/.test(body) && /转化率/.test(body));

const statValues = await page.locator('.stat b').allInnerTexts();
ok('指标取自真实数据', statValues.some(v => v && v !== '—'), statValues.join(' / '));

const rows = await page.locator('.el-table__row').count();
ok('会话表格有数据行', rows > 0, `${rows} 行`);

// 打开第一条会话详情
await page.locator('.el-table__row').first().locator('button').first().click();
await page.waitForTimeout(1500);
const dialog = await page.locator('.el-dialog').innerText().catch(() => '');
ok('会话详情弹窗（对话流水）', dialog.includes('对话流水'), dialog.slice(0, 60).replace(/\n/g, ' '));
ok('会话详情弹窗（归因订单）', dialog.includes('归因订单'));

await browser.close();
const failed = results.filter(r => !r[1]).length;
console.log(failed === 0 ? '\n管理端语音会话页 E2E 全部通过 ✅' : `\n${failed} 项失败 ❌`);
process.exit(failed === 0 ? 0 : 1);
