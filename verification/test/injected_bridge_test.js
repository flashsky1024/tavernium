'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const script = fs.readFileSync(process.argv[2], 'utf8');
let passed = 0;
function check(name, value) { assert.ok(value, name); passed++; console.log('[PASS] ' + name); }
function setup(origin, iframe) {
    const calls = [], listeners = new Map(), events = new Map();
    const document = {addEventListener(type, fn) { events.set(type, fn); }};
    function Blob() { this.type = 'text/plain'; }
    function FileReader() { this.readAsDataURL = () => { this.result = 'data:text/plain;base64,eA=='; this.onload(); }; }
    const window = {
        URL: {createObjectURL: () => 'blob:demo', revokeObjectURL() {}},
        fetch: () => Promise.resolve(),
        addEventListener() {},
        setTimeout() { throw new Error('context should already be ready'); },
        requestAnimationFrame() {}, cancelAnimationFrame() {},
        SillyTavern: {getContext: () => ({chat: [{mes: '私密正文'}], eventTypes: {MESSAGE_RECEIVED: 'received'},
            eventSource: {on(name, fn) { listeners.set(name, fn); }}})},
        TavernKeep: {noteNetActivity: (...args) => calls.push(['net', ...args]),
            saveBlob: (...args) => calls.push(['blob', ...args]),
            replyReady: (...args) => calls.push(['reply', ...args])}
    };
    window.top = iframe ? {} : window;
    vm.runInNewContext(script, {window, document, location: {origin}, Blob, FileReader,
        console: {log() {}, error() {}}, setTimeout, clearTimeout, Date});
    return {window, calls, events, listeners, Blob};
}
let s = setup('https://other.example.com', false);
check('异步脚本落到外站时拒绝安装', !s.window.__tkHb && !s.window.__tkBlobHooked);
s = setup('https://tavern.example.com', true);
check('子框架不会取得认证 facade', !s.window.__tkHb && s.listeners.size === 0);
s = setup('https://tavern.example.com', false);
s.window.fetch('/api/chat');
check('网络心跳携带真实页面凭证', s.calls[0][0] === 'net' && /^[a-f0-9]{64}$/.test(s.calls[0][1]));
const token = s.calls[0][1];
s.window.URL.createObjectURL(new s.Blob());
s.events.get('click')({target: {closest: () => ({download: 'export.txt', href: 'blob:demo'})},
    preventDefault() {}, stopPropagation() {}});
check('Blob 下载沿用认证闭包', JSON.stringify(s.calls[1]) === JSON.stringify(['blob', token, 'export.txt', 'text/plain', 'eA==']));
s.listeners.get('received')(0, 'normal');
check('回复提醒沿用同一页面凭证', JSON.stringify(s.calls[2]) === JSON.stringify(['reply', token, '1']));
check('凭证与 facade 不暴露到 window', !Object.values(s.window).includes(token) && !('bridge' in s.window));
check('保活脚本可正常执行', s.window.__tkHb === 1 && s.window.__tkRafHooked === 1);
console.log('PASSED: ' + passed + ' FAILED: 0');
