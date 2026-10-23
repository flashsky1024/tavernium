'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const script = fs.readFileSync(process.argv[2], 'utf8');
let passed = 0;
function check(name, value) { assert.ok(value, name); passed++; console.log('[PASS] ' + name); }
function setup(options = {}) {
    const listeners = new Map(), calls = [], timers = [];
    const events = {MESSAGE_RECEIVED: 'received', GENERATION_STARTED: 'start', GENERATION_STOPPED: 'stop', CHAT_CHANGED: 'chat'};
    const context = {chat: [{mes: '私密的角色回复', is_user: false}], eventSource: {
        on(name, fn) {
            if (options.failRegistration && name === 'start') throw new Error('registration failed');
            if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn);
        },
        removeListener(name, fn) {
            listeners.set(name, (listeners.get(name) || []).filter(item => item !== fn));
        }
    }};
    context[options.legacy ? 'event_types' : 'eventTypes'] = events;
    const win = {setTimeout(fn) { timers.push(fn); }};
    win.top = options.iframe ? {} : win;
    if (!options.delayed) win.SillyTavern = {getContext: () => context};
    const scope = vm.createContext({window: win, bridge: {replyReady: (...args) => calls.push(args)}});
    function emit(name, ...args) { for (const fn of listeners.get(name) || []) fn(...args); }
    function inject() { vm.runInContext(script, scope); }
    inject();
    return {win, context, calls, timers, listeners, events, emit, inject};
}
let s = setup();
s.emit('received', 0, 'normal');
check('正常回复提醒', s.calls.length === 1);
check('桥只接收无内容事件序号', JSON.stringify(s.calls) === '[["1"]]');
s.emit('received', 0, 'normal');
check('同一回复重复事件不重复提醒', s.calls.length === 1);
s.inject();
check('心跳重复注入不重复注册', s.listeners.get('received').length === 1);
for (const type of ['first_message', 'quiet', 'impersonate']) {
    s.context.chat[0].mes += '!'; s.emit('received', 0, type);
}
check('首次问候、静默和代写不提醒', s.calls.length === 1);
for (const message of [{mes: '用户', is_user: true}, {mes: '系统', is_system: true}, {mes: '  '}, {}]) {
    s.context.chat[0] = message; s.emit('received', 0, 'normal');
}
check('用户、系统和空消息不提醒', s.calls.length === 1);
s.context.chat[0] = {mes: '新回复'};
for (const id of [null, undefined, '', -1, NaN, 99, 'bad']) s.emit('received', id, 'normal');
check('非法索引不提醒', s.calls.length === 1);
s.emit('start', 'normal'); s.emit('stop'); s.emit('received', 0, 'normal');
check('手动停止不当作生成成功', s.calls.length === 1);
s.emit('start', 'quiet'); s.emit('received', 0, 'normal');
check('静默生成期间不提醒', s.calls.length === 1);
s.emit('start', 'normal', {}, true); s.emit('received', 0, 'normal');
check('dry run 不提醒', s.calls.length === 1);
s.emit('start', 'normal');
s.context.streamingProcessor = {isStopped: true}; s.emit('received', 0, 'normal');
s.context.streamingProcessor = {abortController: {signal: {aborted: true}}}; s.emit('received', 0, 'normal');
check('流式出错和取消不提醒', s.calls.length === 1);
s.context.streamingProcessor = null; s.emit('received', 0, 'swipe');
check('重新生成的回复可提醒', s.calls.length === 2);
s.emit('start', 'normal'); s.emit('received', 0, 'normal');
check('相同文本的新生成也可提醒', s.calls.length === 3);
s.emit('stop'); s.emit('chat'); s.emit('received', 0, 'normal');
check('切换聊天重置旧状态', s.calls.length === 4);
s = setup({legacy: true}); s.emit('received', 0, 'normal');
check('兼容旧版 event_types 命名', s.calls.length === 1);
s = setup({iframe: true});
check('子 frame 不安装监听器', s.listeners.size === 0 && s.timers.length === 0);
s = setup({delayed: true}); s.inject();
check('酒馆未就绪时只保留一个重试', s.timers.length === 1);
s.win.SillyTavern = {getContext: () => s.context}; s.timers.shift()(); s.emit('received', 0, 'normal');
check('酒馆延迟初始化后可绑定', s.calls.length === 1 && s.timers.length === 0);
s = setup({delayed: true});
for (let i = 0; i < 100 && s.timers.length; i++) s.timers.shift()();
check('非酒馆页面重试有上限', s.timers.length === 0 && !s.win.__tkReplyPending);
s.win.SillyTavern = {getContext: () => s.context}; s.inject(); s.emit('received', 0, 'normal');
check('后续心跳可恢复绑定', s.calls.length === 1);
s = setup({failRegistration: true});
check('注册失败会清理已安装监听器', s.listeners.get('received').length === 0 && !s.win.__tkReplyPending);
console.log('PASSED: ' + passed + ' FAILED: 0');
