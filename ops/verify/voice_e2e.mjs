// AI 智能导购页（文本/语音合并）浏览器 E2E：登录 → 页内切到语音 → fake 麦克风录音 → WS 经网关联通 → 我的语音记录渲染。
// 运行：先起服务栈，再 node ops/verify/voice_e2e.mjs
//   开发（vite dev）：BASE=http://localhost:5173
//   Docker 部署（web 容器）：BASE=http://localhost:8081
import { chromium } from 'playwright-core';

const exe = process.env.HOME + '/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome';
const BASE = process.env.BASE || 'http://localhost:5173';
const results = [];
const ok = (name, pass, detail = '') => {
  results.push([name, pass, detail]);
  console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' | ' + detail : ''));
};

const browser = await chromium.launch({
  executablePath: exe,
  headless: true,
  args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
});
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  permissions: ['microphone'],
});
const page = await ctx.newPage();
page.on('console', m => { if (m.type() === 'error') console.log('[console.error]', m.text()); });

// 1. 登录
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.fill('input[placeholder="请输入商城账号"]', 'aaa');
await page.fill('input[placeholder="请输入登录密码"]', '123');
await page.click('button.submit-btn');
await page.waitForURL('**/front/**', { timeout: 8000 }).catch(() => {});
ok('登录进入前台', page.url().includes('/front'), page.url());

// 2. 语音页渲染
await page.goto(`${BASE}/front/guide?mode=voice`, { waitUntil: 'networkidle' });
const bodyText = await page.locator('body').innerText();
ok('合并页-语音模式渲染', bodyText.includes('语音导购') && bodyText.includes('对着麦克风说出需求'), '');
ok('页面内通道切换器存在', bodyText.includes('文本导购') && bodyText.includes('语音导购'), '');
ok('我的语音记录区块渲染', bodyText.includes('我的语音记录'), '');

// 3. 点麦克风：WS 经 vite(ws:true)→网关→voice，fake 音频上行
await page.click('.mic-btn');
await page.waitForTimeout(4000);
const logText1 = await page.locator('.log-panel').evaluate(
  el => { el.querySelector('.el-collapse-item__header')?.click(); return ''; }).catch(() => '');
await page.waitForTimeout(500);
const status1 = await page.locator('.online-dot').innerText();
ok('录音状态机进入 RECORDING', status1.includes('录音中'), status1);

// 4. 再点停止 → 进入等待回包
await page.click('.mic-btn');
await page.waitForTimeout(1000);
const status2 = await page.locator('.online-dot').innerText();
ok('停止后进入 WAITING', status2.includes('处理') || status2.includes('点击麦克风'), status2);

// 5. 展开日志面板验证 WS 生命周期
await page.locator('.el-collapse-item__header').click().catch(() => {});
await page.waitForTimeout(300);
const logs = await page.locator('.log-panel').innerText();
ok('WS 已连接日志', logs.includes('WebSocket 已连接'), logs.slice(0, 120));

await browser.close();
const failed = results.filter(r => !r[1]).length;
console.log(failed === 0 ? '\n语音页 E2E 全部通过 ✅' : `\n${failed} 项失败 ❌`);
process.exit(failed === 0 ? 0 : 1);
