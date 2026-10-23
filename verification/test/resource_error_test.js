/**
 * 资源加载失败钩子测试（node，无需浏览器）。
 *
 * 背景：日志里的 `@debug-core.js:280` 曾被误读成"失败图片来自 LittleWhiteBox"，
 * 但同一条 Settings not ready 警告有时来自 script.js、有时来自 debug-core.js，
 * 说明 `@文件:行` 只是 console 包装器的栈顶位置，不能当来源证据。
 *
 * 所以钩子现在额外记录失败元素自身的身份（tag/#id/.class / 父元素 / 是否在 DOM 中）。
 * 本测试验证这些字段确实出现在输出里。
 *
 * 用法: node resource_error_test.js <hook.js>
 */
'use strict';
const fs = require('fs');

const hookPath = process.argv[2];
if (!hookPath) {
    console.error('用法: node resource_error_test.js <hook.js>');
    process.exit(2);
}
const hookCode = fs.readFileSync(hookPath, 'utf8');

// ---- 模拟环境 ----
const handlers = {};
const logs = [];
const win = {};
win.addEventListener = function (type, fn) { handlers[type] = fn; };
global.window = win;
const origError = console.error;
console.error = function (m) { logs.push(String(m)); };

if (!hookCode.includes('__tkErrHooked')) {
    console.error = origError;
    origError('脚本内容可疑，未包含 __tkErrHooked 守卫');
    process.exit(2);
}
// eslint-disable-next-line no-eval
eval(hookCode);

let passed = 0;
let failed = 0;
function check(name, ok, extra) {
    if (ok) {
        passed++;
        origError('  [PASS] ' + name);
    } else {
        failed++;
        origError('  [FAIL] ' + name + (extra ? '  ' + extra : ''));
    }
}
function section(n) { origError('\n--- ' + n + ' ---'); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeEl(tag, props) {
    return Object.assign({
        tagName: tag,
        id: '',
        className: '',
        outerHTML: '<' + tag.toLowerCase() + '>',
        isConnected: true,
        parentElement: null,
    }, props || {});
}

(async function main() {
// 1) 挂在 DOM 里的 img
section('失败资源：在 DOM 中的 img');
const parent = makeEl('DIV', { id: 'chat', className: 'mes_list' });
const img = makeEl('IMG', {
    id: 'probe',
    className: 'avatar thumb',
    src: 'https://tavern.example.com/',
    outerHTML: '<img id="probe" class="avatar thumb" src="https://tavern.example.com/">',
    parentElement: parent,
});
handlers.error({ target: img });
check('记录了资源类型与 URL', logs.some((l) => l.includes('resource failed: IMG https://tavern.example.com/')));
check('记录了元素身份', logs.some((l) => l.includes('el=IMG#probe.avatar.thumb in DIV#chat.mes_list')),
    logs[logs.length - 1]);
check('记录了是否在 DOM 中', logs.some((l) => l.includes('connected=yes')));
check('记录了 outerHTML 片段', logs.some((l) => l.includes('html=<img id="probe"')));

// 2) new Image() 创建、从未插入 DOM 的探测图
section('失败资源：脱离 DOM 的探测 img');
logs.length = 0;
const probe = makeEl('IMG', {
    src: 'https://tavern.example.com/',
    isConnected: false,
    parentElement: null,
    outerHTML: '<img src="https://tavern.example.com/">',
});
handlers.error({ target: probe });
check('标记为 detached', logs.some((l) => l.includes('{detached}')),
    logs[logs.length - 1]);
check('标记为不在 DOM 中', logs.some((l) => l.includes('connected=no')));

// 3) link / script 同样适用
section('失败资源：link 与 script');
logs.length = 0;
const link = makeEl('LINK', { href: 'https://cdn.example.com/a.css', outerHTML: '<link rel="stylesheet" href="a.css">' });
handlers.error({ target: link });
check('link 用 href 取 URL', logs.some((l) => l.includes('resource failed: LINK https://cdn.example.com/a.css')));

logs.length = 0;
const script = makeEl('SCRIPT', { src: 'https://tavern.example.com/api/plugins/baibaoku/v1/client.js', outerHTML: '<script src="/api/plugins/baibaoku/v1/client.js"></script>' });
handlers.error({ target: script });
check('script 记录来源 URL', logs.some((l) => l.includes('baibaoku/v1/client.js')));

// 4) 普通 JS 异常仍走原来的格式，不能被资源分支吃掉
section('普通 JS 异常');
logs.length = 0;
handlers.error({ target: null, message: 'x is not a function', filename: 'https://a/b.js', lineno: 42 });
check('保留 message @ file:line', logs.some((l) => l.includes('x is not a function @ https://a/b.js:42')),
    logs[logs.length - 1]);

// 5) 超长 URL 必须截断，避免一行刷屏
// 钩子有"每秒最多 5 条"的限流，前面 5 条已用完额度，先等一个窗口再测
await sleep(1100);
section('超长 URL 截断');
logs.length = 0;
const longImg = makeEl('IMG', { src: 'https://a.com/' + 'x'.repeat(300), outerHTML: '<img>' });
handlers.error({ target: longImg });
check('URL 被截断', logs.some((l) => l.includes('...') && l.length < 400),
    'len=' + (logs[0] || '').length);

// 6) 空 src / 1x1 占位图必须静默（否则每 2 秒一条，淹没真正的错误）
await sleep(1100);
section('空 src 占位图被静默');
logs.length = 0;
const emptySrc = makeEl('IMG', {
    src: 'https://tavern.example.com/',
    outerHTML: '<img src="">',
    naturalWidth: 0,
    hasAttribute: (n) => n === 'src',
    getAttribute: (n) => (n === 'src' ? '' : null),
});
handlers.error({ target: emptySrc });
check('空 src 的 IMG 不记录', logs.length === 0, 'logs=' + logs.length);

const pixel = makeEl('IMG', {
    src: 'https://tavern.example.com/',
    naturalWidth: 1,
});
handlers.error({ target: pixel });
check('1x1 兜底占位图不记录', logs.length === 0, 'logs=' + logs.length);

const broken = makeEl('IMG', {
    src: 'https://a.com/x.png',
    naturalWidth: 0,
    outerHTML: '<img src="x.png">',
    hasAttribute: (n) => n === 'src',
    getAttribute: (n) => (n === 'src' ? 'x.png' : null),
});
handlers.error({ target: broken });
check('真实图片 404 仍然记录', logs.some((l) => l.includes('x.png')),
    'logs=' + logs.length);

// 7) 幂等：重复注入不叠加 handler
section('幂等守卫');
const beforeFn = handlers.error;
// eslint-disable-next-line no-eval
eval(hookCode);
check('重复注入不替换 handler', handlers.error === beforeFn);

console.error = origError;
origError('\n========================================');
origError('PASSED: ' + passed + '   FAILED: ' + failed);
origError('========================================');
process.exit(failed > 0 ? 1 : 0);
})();
