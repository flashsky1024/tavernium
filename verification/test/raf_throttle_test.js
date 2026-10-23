/**
 * 帧率节流脚本行为测试（node，无需浏览器）。
 *
 * 测试对象是「从 MainActivity.java 原样提取」的 FRAME_RATE_HOOK_JS：
 * 在模拟环境里把 window.requestAnimationFrame 模拟成 60fps（16ms 触发），
 * 然后验证：
 *   1) 未设置间隔时完全透传，维持原帧率；
 *   2) 设置 33ms 间隔后，自续帧循环的帧数显著下降（约减半）；
 *   3) 设置间隔后回调不会丢失（全部最终执行）；
 *   4) cancelAnimationFrame 能取消尚未执行的节流回调。
 *
 * 用法: node raf_throttle_test.js <hook.js>
 */
'use strict';
const fs = require('fs');

const hookPath = process.argv[2];
if (!hookPath) {
    console.error('用法: node raf_throttle_test.js <hook.js>');
    process.exit(2);
}
const hookCode = fs.readFileSync(hookPath, 'utf8');

// ---- 模拟浏览器环境：rAF 每 16ms 触发一次（60fps） ----
const rafTimers = new Map();
let rafSeq = 1;
const win = {};
win.performance = { now: () => Number(process.hrtime.bigint() / 1000000n) };
win.requestAnimationFrame = function (cb) {
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
global.window = win;

// 执行被测脚本（其内部通过 window.xxx 访问，裸 setTimeout/clearTimeout 用 node 全局）
if (!hookCode.includes('__tkRafHooked')) {
    console.error('脚本内容可疑，未包含 __tkRafHooked 守卫');
    process.exit(2);
}
// eslint-disable-next-line no-eval
eval(hookCode);

// ---- 断言工具 ----
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
function section(name) {
    console.log('\n--- ' + name + ' ---');
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 自续帧循环：在回调里再次请求 rAF，模拟真实动画/渲染循环。 */
function runLoop(ms) {
    return new Promise((resolve) => {
        let frames = 0;
        const t0 = Date.now();
        function tick() {
            frames++;
            if (Date.now() - t0 < ms) {
                window.requestAnimationFrame(tick);
            } else {
                resolve(frames);
            }
        }
        window.requestAnimationFrame(tick);
    });
}

(async function main() {
    // 1) 默认（未设置间隔）：应完全透传，帧率仍是 60fps
    section('默认不限制（__tkMinFrameInterval 未设置）');
    const baseFrames = await runLoop(320);
    // 320ms / 16ms ≈ 20 帧；允许调度抖动
    check('透传后维持 ~60fps（实测 ' + baseFrames + ' 帧/320ms）',
        baseFrames >= 12 && baseFrames <= 26);

    // 2) 设置 33ms 间隔：自续循环帧数应显著下降
    section('限制为 30fps（__tkMinFrameInterval=33）');
    window.__tkMinFrameInterval = 33;
    const throttledFrames = await runLoop(320);
    // 320ms / 33ms ≈ 9~10 帧
    check('帧数下降到一半左右（实测 ' + throttledFrames + ' 帧/320ms）',
        throttledFrames <= Math.ceil(baseFrames * 0.7),
        'base=' + baseFrames + ' throttled=' + throttledFrames);
    check('仍保持 >= 6 帧（不至于卡死）', throttledFrames >= 6);

    // 3) 同一 tick 内批量请求：不丢失任何回调
    section('回调不丢失');
    let invoked = 0;
    const batch = [];
    for (let i = 0; i < 5; i++) {
        batch.push(window.requestAnimationFrame(() => invoked++));
    }
    check('返回了 5 个有效 id', batch.every((id) => typeof id === 'number' && id > 0));
    await sleep(150);
    check('5 个回调全部执行（invoked=' + invoked + '）', invoked === 5);

    // 4) cancelAnimationFrame 能取消节流中的待执行回调
    section('取消未执行的回调');
    let canceledRan = false;
    const cid = window.requestAnimationFrame(() => { canceledRan = true; });
    window.cancelAnimationFrame(cid);
    await sleep(150);
    check('已取消的回调未被执行', canceledRan === false);

    // 5) 恢复为 0：应重新透传
    section('恢复系统默认（__tkMinFrameInterval=0）');
    window.__tkMinFrameInterval = 0;
    const restored = await runLoop(320);
    check('恢复后帧率回到 ~60fps（实测 ' + restored + ' 帧/320ms）',
        restored >= 12);

    console.log('\n========================================');
    console.log('PASSED: ' + passed + '   FAILED: ' + failed);
    console.log('========================================');
    process.exit(failed > 0 ? 1 : 0);
})();
