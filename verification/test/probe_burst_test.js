/**
 * 性能探针（PERF_PROBE_SEED）行为测试（node，无需浏览器）。
 *
 * 测试对象是「从 MainActivity.java 原样提取」的探针种子脚本。要验证的核心是：
 * 探针**不能**用常驻 requestAnimationFrame 循环常年制造帧——那会让诊断工具自己
 * 变成 RenderThread 的负载来源（真机报告里 RenderThread 长期 30%+ 就有这个嫌疑）。
 *
 * 现在改成「每 3 秒只测 500ms」的突发采样，因此：
 *   1) rAF 调用次数应远低于常驻循环（约 1/6 占空比）；
 *   2) 仍然能算出合理的 fps（40~75）；
 *   3) 长任务计数器存在且可累加；
 *   4) 脚本重复执行不会叠加多个循环（幂等守卫）。
 *
 * 用法: node probe_burst_test.js <probe_seed.js>
 */
'use strict';
const fs = require('fs');

const seedPath = process.argv[2];
if (!seedPath) {
    console.error('用法: node probe_burst_test.js <probe_seed.js>');
    process.exit(2);
}
const seedCode = fs.readFileSync(seedPath, 'utf8');

// ---- 模拟浏览器环境 ----
const rafTimers = new Map();
let rafSeq = 1;
let rafCalls = 0;               // 被请求的帧回调总数（用于判断占空比）
const t0 = Date.now();
const win = {};
win.performance = { now: () => Date.now() - t0 };
win.requestAnimationFrame = function (cb) {
    rafCalls++;
    const id = rafSeq++;
    rafTimers.set(id, setTimeout(() => {
        rafTimers.delete(id);
        cb(win.performance.now());
    }, 16));
    return id;
};
win.cancelAnimationFrame = function (id) {
    const t = rafTimers.get(id);
    if (t !== undefined) {
        clearTimeout(t);
        rafTimers.delete(id);
    }
};
win.setInterval = setInterval;
global.window = win;

if (!seedCode.includes('__tkPerfSeeded')) {
    console.error('脚本内容可疑，未包含 __tkPerfSeeded 守卫');
    process.exit(2);
}
// PerformanceObserver 在 node 里不存在，脚本内部 try/catch 会吞掉——正好验证健壮性
// eslint-disable-next-line no-eval
eval(seedCode);

let passed = 0;
let failed = 0;
function check(name, ok, extra) {
    if (ok) {
        passed++;
        console.log('  [PASS] ' + name);
    } else {
        failed++;
        console.log('  [FAIL] ' + name + (extra ? '  ' + extra : ''));
    }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async function main() {
    console.log('\n--- 探针已安装（__tkPerfSeeded=' + win.__tkPerfSeeded + '）---');
    check('幂等守卫已置位', win.__tkPerfSeeded === 1);

    // 1.2 秒：常驻循环在这段时间内约 75 次 rAF，突发采样只有首轮 500ms（约 31 次）
    await sleep(1200);
    const firstWindow = rafCalls;
    check('1.2s 内 rAF 次数远低于常驻循环（实测 ' + firstWindow + '，常驻约 75）',
        firstWindow > 15 && firstWindow < 50);

    // 再等 2.1 秒，跨过第二个 500ms 采样窗口
    await sleep(2100);
    const total = rafCalls;
    check('3.3s 总帧数约为常驻的 1/3 以下（实测 ' + total + '，常驻约 206）',
        total < 100);
    check('出现第二次采样（总数大于首窗）', total > firstWindow);

    check('fps 落在合理区间（实测 ' + win.__tkFps + '）',
        typeof win.__tkFps === 'number' && win.__tkFps >= 40 && win.__tkFps <= 75);
    check('长任务计数器存在', typeof win.__tkLongTasks === 'number');
    check('长任务峰值字段存在', typeof win.__tkLtMax === 'number');

    // 重复执行不应装出新的常驻循环。先等到突发窗口结束（t≈3.5s）再测，
    // 否则会把进行中的那一轮采样误算成"新循环"。
    await sleep(1200);
    const before = rafCalls;
    // eslint-disable-next-line no-eval
    eval(seedCode);
    await sleep(1000);
    check('重复注入后空档期无帧（未装出常驻循环）', rafCalls - before < 5,
        'delta=' + (rafCalls - before));

    console.log('\n========================================');
    console.log('PASSED: ' + passed + '   FAILED: ' + failed);
    console.log('========================================');
    process.exit(failed > 0 ? 1 : 0);
})();
