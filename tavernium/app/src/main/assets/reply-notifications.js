// 在带有认证 bridge 的闭包中执行；不依赖被保活脚本改写过的 document.hidden。
(function () {
    if (window.top !== window || window.__tkReplyHooked || window.__tkReplyPending) return;
    window.__tkReplyPending = true;
    var attempts = 0;

    function bind() {
        var context;
        try {
            context = window.SillyTavern && window.SillyTavern.getContext();
        } catch (ignored) {}
        var events = context && (context.eventTypes || context.event_types);
        var source = context && context.eventSource;
        if (!source || typeof source.on !== 'function' || !events || !events.MESSAGE_RECEIVED) {
            if (++attempts < 60) {
                window.setTimeout(bind, 1000);
            } else {
                window.__tkReplyPending = false;
            }
            return;
        }

        var generation = 0;
        var sequence = 0;
        var stopped = false;
        var quiet = false;
        var lastChat, lastId, lastText, lastGeneration;
        function reset() {
            stopped = false;
            quiet = false;
            lastChat = null;
            lastId = null;
            lastText = null;
            lastGeneration = -1;
        }
        function started(type, options, dryRun) {
            generation++;
            stopped = false;
            quiet = type === 'quiet' || type === 'impersonate' || !!dryRun;
        }
        function received(id, type) {
            try {
                if (stopped || quiet || type === 'first_message'
                        || type === 'quiet' || type === 'impersonate') return;
                var current = window.SillyTavern.getContext();
                var chat = current.chat;
                if (id === null || id === undefined || id === '') return;
                var index = Number(id);
                if (!Array.isArray(chat) || !Number.isInteger(index) || index < 0) return;
                var message = chat[index];
                if (!message || message.is_user || message.is_system
                        || typeof message.mes !== 'string' || !message.mes.trim()) return;
                var stream = current.streamingProcessor;
                if (stream && (stream.isStopped || (stream.abortController
                        && stream.abortController.signal.aborted))) return;
                if (chat === lastChat && index === lastId && message.mes === lastText
                        && generation === lastGeneration) return;
                lastChat = chat;
                lastId = index;
                lastText = message.mes;
                lastGeneration = generation;
                // 只跨桥传递无内容的事件序号，角色名和正文留在页面内。
                bridge.replyReady(String(++sequence));
            } catch (ignored) {}
        }
        var registrations = [];
        function on(type, handler) {
            if (!type) return;
            registrations.push([type, handler]);
            source.on(type, handler);
        }
        try {
            on(events.MESSAGE_RECEIVED, received);
            on(events.GENERATION_STARTED, started);
            on(events.GENERATION_STOPPED, function () { stopped = true; });
            on(events.CHAT_CHANGED, reset);
        } catch (ignored) {
            if (typeof source.removeListener === 'function') {
                registrations.forEach(function (entry) {
                    try { source.removeListener(entry[0], entry[1]); } catch (ignored) {}
                });
            }
            window.__tkReplyPending = false;
            return;
        }
        window.__tkReplyHooked = true;
        window.__tkReplyPending = false;
    }
    bind();
})();
