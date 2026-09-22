// playwright-core 自带的浏览器定位是给 @playwright/test 用的，这里直接跑脚本，
// 需要自己找 chromium。脚本原先各自写死 chromium-1228 这一个路径，
// 换台机器（playwright 版本不同 → 目录名带不同 build 号）就以"file not found"这种
// 与业务无关的原因失败。统一到这里按优先级探测。
import { globSync, existsSync } from 'node:fs';

/**
 * 返回可用的 chromium 可执行文件路径；找不到时打印怎么办并退出（不抛 file-not-found）。
 *
 * 优先级：CHROME_PATH 环境变量 → 已安装的 chromium 各变体（取 build 号最大的）。
 */
export function resolveChromium() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) {
    return process.env.CHROME_PATH;
  }
  const home = process.env.HOME || '';
  const patterns = [
    `${home}/.cache/ms-playwright/chromium-*/chrome-linux64/chrome`,
    `${home}/.cache/ms-playwright/chromium-*/chrome-linux/chrome`,
    `${home}/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux64/headless_shell`,
  ];
  const candidates = patterns.flatMap((pattern) => globSync(pattern));
  if (candidates.length === 0) {
    console.error(
      '找不到 chromium。先装浏览器再跑：\n' +
      '  npx playwright install chromium\n' +
      '或指定已有路径：CHROME_PATH=/path/to/chrome node <脚本>');
    process.exit(2);
  }
  const buildNumber = (path) => Number((path.match(/chromium[_-]?(\d+)/) || [])[1] || 0);
  return candidates.sort((a, b) => buildNumber(b) - buildNumber(a))[0];
}

/** 浏览器脚本通用的启动参数：无沙箱（容器/CI 内必需）+ 假麦克风（语音用例模拟输入） */
export const COMMON_ARGS = ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'];
