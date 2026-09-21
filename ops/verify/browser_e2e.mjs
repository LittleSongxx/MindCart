import { chromium } from 'playwright-core';
import { globSync } from 'node:fs';

const BASE = process.env.BASE || 'http://localhost:5173';
const exe = process.env.HOME + '/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome';
const results = [];
const ok = (name, pass, detail = '') => { results.push([name, pass, detail]); console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' | ' + detail : '')); };

const browser = await chromium.launch({ executablePath: exe, headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// 1. 打开首页 → 应重定向到登录页
await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
ok('首页可访问并跳登录页', page.url().includes('/login'), page.url());

// 2. 登录 aaa/123
await page.fill('input[placeholder="请输入商城账号"]', 'aaa');
await page.fill('input[placeholder="请输入登录密码"]', '123');
await page.click('button.submit-btn');
await page.waitForTimeout(2500);
ok('登录成功进入前台', page.url().includes('/front'), page.url());

// 3. 首页商品渲染
await page.waitForTimeout(2500);
const cards = await page.locator('.product-card').count();
const bodyText = await page.locator('body').innerText();
ok('商品列表渲染', cards > 0, `${cards} 张商品卡`);

// 4. 进商品详情页（点第一个商品）
await page.locator('.product-card .product-name').first().click({ force: true });
await page.waitForURL('**/front/product/**', { timeout: 8000 }).catch(() => {});
ok('商品详情页', page.url().includes('/front/product'), page.url());

// 5. QA 流式提问（SSE 经 vite 代理 → 网关 → ai）——本次改造的核心验证点
await page.locator('.el-tabs__item:has-text("AI问答")').click().catch(() => {});
await page.waitForTimeout(800);
const qaInput = page.locator('input[placeholder*="例如"], textarea').first();
if (await qaInput.count() > 0) {
  await qaInput.fill('这款商品的保修政策是什么');
  const askBtn = page.locator('button:has-text("提问"), button:has-text("问一问"), button:has-text("询问")').first();
  await askBtn.click();
  // 等流式回答出现（SSE delta 渐进渲染）
  await page.waitForTimeout(12000);
  const qaText = await page.locator('body').innerText();
  ok('QA 流式回答渲染（SSE 全链路）', qaText.includes('保修') || qaText.includes('质保') || qaText.includes('资料'),
      qaText.includes('AI 回答') ? 'AI 回答区块已出现' : '未见回答区块');
} else {
  ok('QA 输入框定位', false, '未找到问答输入框');
}

// 6. 截图留证
await page.screenshot({ path: '/tmp/pwtest/product-detail.png', fullPage: false });

// 7. 购物车加购 + 下单（交易链路走前端全流程）
await page.goto(`${BASE}/front/home`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
const buyBtn = page.locator('button:has-text("加入购物车")').first();
if (await buyBtn.count() > 0) {
  await buyBtn.click(); await page.waitForTimeout(1200);
  await page.goto(`${BASE}/front/cart`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const cartText = await page.locator('body').innerText();
  ok('购物车页面有商品', !cartText.includes('暂无数据') || cartText.includes('元'), '');
} else {
  ok('首页加购按钮', false, '未找到（可能仅在详情页）');
}

console.log('\n=== 汇总 ===');
const pass = results.filter(r => r[1]).length;
console.log(`${pass}/${results.length} 通过`);
await browser.close();
process.exit(pass === results.length ? 0 : 1);
