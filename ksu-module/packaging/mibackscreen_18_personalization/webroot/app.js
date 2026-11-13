/* ==========================================================================
   MiBackscreen背屏附加资源 · 状态面板
   --------------------------------------------------------------------------
   兼容性：APatch 与 KernelSU 的 WebUI 桥 API 一致，都注入 window.ksu：
       exec(cmd)              → 同步返回 stdout
       exec(cmd, cbName)      → 回调 cbName(errno, stdout, stderr)
       exec(cmd, opts, cbName)
       toast(msg) / moduleInfo()
   新版 KernelSU 支持 ksu://icon/<pkg> 直接取应用图标。
   手写薄适配层，不依赖 npm 打包（模块必须免构建才能直接刷）。
   ========================================================================== */

'use strict';

const MODDIR = (function () {
    try {
        const info = JSON.parse(window.ksu.moduleInfo());
        if (info && info.id) return '/data/adb/modules/' + info.id;
    } catch (e) { /* 桥不支持 moduleInfo，往下走 */ }

    const m = location.pathname.match(/\/data\/adb\/modules\/([^/]+)\//);
    if (m) return '/data/adb/modules/' + m[1];

    const s = location.href.match(/\/data\/adb\/modules\/([^/]+)\//);
    if (s) return '/data/adb/modules/' + s[1];

    return '/data/adb/modules/mibackscreen_18_personalization';
})();

const STATUS_SH  = MODDIR + '/status.sh';
const LOGPACK_SH = MODDIR + '/logpack.sh';

const PA_PKG  = 'com.miui.personalassistant';
const SSC_PKG = 'com.xiaomi.subscreencenter';
const TM_PKG  = 'com.android.thememanager';

// ------------------------------------------------------------------ shell 桥
let cbSeq = 0;
let bridgeMode = 'cb2';          // 'cb2' | 'cb3' | 'sync'

let busyCount = 0;
let busyTimer = null;

// 等浏览器真的画一帧再往下走（双 rAF + 一个宏任务）
// ksu.exec 在有些管理器里是同步阻塞的 —— 不先画一帧，页面直接冻住，
// 用户看到的还是「点了没反应」。
function nextPaint() {
    return new Promise(function (r) {
        requestAnimationFrame(function () {
            requestAnimationFrame(function () { setTimeout(r, 0); });
        });
    });
}

function busyOn(btn, text) {
    if (!btn) return function () {};
    const oldHTML = btn.innerHTML;
    const oldDisabled = btn.disabled;
    btn.disabled = true;
    btn.classList.add('busy');
    btn.innerHTML = '<span class="spinner"></span><span class="blab">' + esc(text || '处理中…') + '</span>';
    return function () {
        btn.disabled = oldDisabled;
        btn.classList.remove('busy');
        btn.innerHTML = oldHTML;
    };
}

function busyStart(label) {
    busyCount++;
    const bar = document.getElementById('busybar');
    const t0 = Date.now();
    const base = label || '处理中';
    if (busyCount === 1) {
        if (bar) bar.hidden = false;
        busyTimer = setInterval(function () {
            const s = Math.round((Date.now() - t0) / 1000);
            if (s < 3) return;
            document.querySelectorAll('button.busy .blab').forEach(function (l) {
                l.textContent = base + ' ' + s + 's';
            });
        }, 1000);
    }
    return function () {
        busyCount = Math.max(0, busyCount - 1);
        if (busyCount === 0) {
            clearInterval(busyTimer);
            busyTimer = null;
            if (bar) bar.hidden = true;
        }
    };
}

function hasBridge() {
    return !!(window.ksu && typeof window.ksu.exec === 'function');
}

function exec(cmd, timeoutMs) {
    const limit = timeoutMs || 90000;
    return new Promise(function (resolve) {
        if (!hasBridge()) {
            resolve({ errno: -1, stdout: '', stderr: 'NO_BRIDGE' });
            return;
        }

        const name = '__mb_cb_' + (++cbSeq);
        let settled = false;

        function finish(errno, stdout, stderr) {
            if (settled) return;
            settled = true;
            try { delete window[name]; } catch (e) { window[name] = undefined; }
            resolve({
                errno: typeof errno === 'number' ? errno : -1,
                stdout: stdout == null ? '' : String(stdout),
                stderr: stderr == null ? '' : String(stderr)
            });
        }

        window[name] = function (errno, stdout, stderr) { finish(errno, stdout, stderr); };
        window.setTimeout(function () { finish(-1, '', 'TIMEOUT'); }, limit);

        // 桥降级探测：cb2 → cb3 → sync。
        // 只有「同步」那一档会阻塞主线程（表现为滑动卡顿、内容不重绘）。
        // 所以异步两档都失败后才落到同步，而且落之前已经过 nextPaint 让出过一帧。
        function tryCb3() {
            try {
                window.ksu.exec(cmd, '{}', name);
                bridgeMode = 'cb3';
            } catch (e2) {
                try {
                    finish(0, window.ksu.exec(cmd), '');
                    bridgeMode = 'sync';
                } catch (e3) {
                    finish(-1, '', String(e3));
                }
            }
        }

        try {
            if (bridgeMode === 'sync') {
                finish(0, window.ksu.exec(cmd), '');
            } else if (bridgeMode === 'cb3') {
                window.ksu.exec(cmd, '{}', name);
            } else {
                window.ksu.exec(cmd, name);
            }
        } catch (e) {
            tryCb3();
        }
    });
}

function toast(msg) {
    try {
        if (window.ksu && typeof window.ksu.toast === 'function') { window.ksu.toast(msg); return; }
    } catch (e) { /* 忽略 */ }
    showToast(msg);
}

function showToast(msg) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(showToast._t);
    showToast._t = setTimeout(function () { el.classList.remove('show'); }, 2600);
}

function q(s) {
    return "'" + String(s).replace(/'/g, "'\\''") + "'";
}

// ------------------------------------------------------------------ 行协议
function parseProtocol(text) {
    const records = [];
    let cur = null;

    text.split('\n').forEach(function (raw) {
        const line = raw.replace(/\r$/, '');

        if (line === '@@') {
            if (cur) records.push(cur);
            cur = { id: '', level: 'info', title: '', detail: '', fix: '', pkg: '', action: '' };
            return;
        }
        if (!cur) return;

        const i = line.indexOf('=');
        if (i < 0) return;

        const k = line.slice(0, i);
        const v = line.slice(i + 1);
        if (Object.prototype.hasOwnProperty.call(cur, k)) cur[k] = v;
    });

    if (cur) records.push(cur);
    return records;
}

// ------------------------------------------------------------------ 渲染
const SECTIONS = [
    { key: 'env',    title: '运行环境' },
    { key: 'conf',   title: '冲突检测' },
    { key: 'res',    title: '资源' },
    { key: 'inj',    title: '注入状态' },
    { key: 'pre',    title: '预设内容' },
    { key: 'card',   title: '卡片就绪度' },
    { key: 'app',    title: '背屏中心' },
    { key: 'aiapp',  title: 'AI 应用卡' },
    { key: 'result', title: '结论' }
];

const MARK = { ok: '\u2713', warn: '!', fail: '\u2715', info: '\u00b7' };

// status.sh 通过 action=<id> 指定修复动作，这里查表执行。
// 动作脚本输出末尾一行 RESULT=ok|nothing|partial|fail。
const ACTIONS = {
    fix_perms: {
        label: '修好资源权限',
        busy: '修复中…',
        hint: '再点一次就会把资源文件改成「应用也读得到」的权限',
        titleOk: '已修好资源权限',
        titleNothing: '权限本来就正常',
        titlePartial: '修了一部分',
        titleFail: '修复未完全成功',
        fixOk: '已重启主题商店，重新打开背屏个性化即可',
        cmd: 'sh ' + q(MODDIR + '/fix-perms.sh')
    },
    fix_regperm: {
        label: '修好卡片写入',
        busy: '修复中…',
        hint: '再点一次就会把背屏卡片状态文件的属主/权限改成「背屏应用可写」（内容不动）',
        titleOk: '已修好卡片写入',
        titleNothing: '本来就可以写',
        titleFail: '没修成功',
        fixOk: '回到应用卡中心再点一次「添加」，这次背屏上就会出现了',
        cmd: 'sh ' + q(MODDIR + '/fix-regperm.sh')
    },
    clear_reareye: {
        label: '清除 REAREye 预设包',
        busy: '清除中…',
        hint: '再点一次就会删除 REAREye 的预设资源包（只删缓存，不动它的应用和设置）',
        titleOk: '已清除 REAREye 预设包',
        titleNothing: '无需清除',
        titleFail: '清除失败',
        fixOk: '重启手机后打开背屏即可看到卡片',
        cmd: 'sh ' + q(MODDIR + '/clear-reareye.sh')
    },
    fix_aiapp: {
        label: '修好 AI 卡目录',
        busy: '修复中…',
        hint: '再点一次就会把 AI 卡资源目录的属主/权限改回 system_theme（不删任何文件）',
        titleOk: '已修好 AI 卡目录',
        titleNothing: '本来就正常',
        titleFail: '修复未完全成功',
        fixOk: '已重启主题商店，回去重新点一次「应用」',
        cmd: 'sh ' + q(MODDIR + '/fix-aiapp.sh')
    }
};

function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}
function escNl(s) { return esc(s).replace(/\n/g, '<br>'); }

// 分组展开状态跨刷新保留：用户点开了哪组，重新检测后还是开着。
const openGroups = {};

function itemHTML(r, withIcon, withFix) {
    let html = '<div class="dot">' + (MARK[r.level] || MARK.info) + '</div>';
    if (withIcon && r.pkg && hasBridge()) {
        html += '<img class="icon" alt="" src="ksu://icon/' + encodeURIComponent(r.pkg) +
                '" onerror="this.style.display=\'none\'">';
    }
    html += '<div class="body">';
    html += '<div class="t">' + esc(r.title) + '</div>';
    if (r.detail) html += '<div class="d">' + esc(r.detail) + '</div>';
    if (withFix && r.fix) html += '<div class="fix">' + esc(r.fix) + '</div>';
    if (withFix && r.action && ACTIONS[r.action]) {
        html += '<button class="fixbtn" type="button">' + esc(ACTIONS[r.action].label) + '</button>';
    }
    html += '</div>';
    return html;
}

function makeItem(r, withIcon, withFix) {
    const el = document.createElement('div');
    el.className = 'item ' + (MARK[r.level] ? r.level : 'info');
    el.innerHTML = itemHTML(r, withIcon, withFix);
    const ab = el.querySelector('button.fixbtn');
    if (ab) ab.addEventListener('click', function () { runAction(r.action, ab); });
    return el;
}

function sectionOf(r) {
    for (let i = 0; i < SECTIONS.length; i++) {
        const k = SECTIONS[i].key;
        if (r.id === k || r.id.indexOf(k + '.') === 0) return SECTIONS[i];
    }
    return null;
}

// 总览里的大号结论。status.sh 的结论行是 level=verdict，title 放 ok|warn|fail，
// detail 是一句总结（如「2 项注意」），fix 是解释。
function setVerdict(level, title, text) {
    const v = document.getElementById('verdict');
    v.className = 'verdict ' + level;
    const icon = v.querySelector('.vicon');
    if (level === 'busy') icon.innerHTML = '<span class="spinner"></span>';
    else icon.textContent = MARK[level] || MARK.info;
    document.getElementById('vtitle').textContent = title;
    document.getElementById('vtext').textContent = text || '';
}

function render(records) {
    const issuesWrap = document.getElementById('issues');
    const wrap = document.getElementById('sections');
    issuesWrap.innerHTML = '';
    wrap.innerHTML = '';

    const checks = records.filter(function (r) { return r.id !== 'result' && r.id.indexOf('result.') !== 0; });
    const verdict = records.filter(function (r) { return r.id === 'result' || r.id.indexOf('result.') === 0; })[0];

    // 顶部副标题：模块版本 · Root 方案 · 系统
    const pick = function (id) {
        const r = checks.filter(function (x) { return x.id === id; })[0];
        return r && r.detail ? r.detail : '';
    };
    const metaParts = [pick('env.module'), pick('env.root'), (pick('env.os').split(' ')[0] || '')]
        .filter(function (x) { return x; });
    document.getElementById('meta').textContent =
        metaParts.length ? metaParts.join(' · ') : '18 系列个性化 · KSU 附加模块';

    // 需要处理：异常排前面，注意其次；带修复说明和修复按钮
    const issues = checks.filter(function (r) { return r.level === 'fail'; })
        .concat(checks.filter(function (r) { return r.level === 'warn'; }));
    if (issues.length) {
        const g = document.createElement('div');
        g.className = 'group';
        g.innerHTML = '<h2>需要处理</h2>';
        const card = document.createElement('div');
        card.className = 'card';
        issues.forEach(function (r) {
            const sec = sectionOf(r);
            card.appendChild(makeItem(r, !!(sec && sec.key === 'card'), true));
        });
        g.appendChild(card);
        issuesWrap.appendChild(g);
    }

    // 逐项明细：每组一张可折叠卡片，标题行带小结，默认收起
    let nOk = 0, nWarn = 0, nFail = 0;
    const detailGroup = document.createElement('div');
    detailGroup.className = 'group';
    detailGroup.innerHTML = '<h2>详细检查</h2>';

    SECTIONS.forEach(function (sec) {
        if (sec.key === 'result') return;
        const items = checks.filter(function (r) { return sectionOf(r) === sec; });
        if (!items.length) return;

        let ok = 0, warn = 0, fail = 0;
        items.forEach(function (r) {
            if (r.level === 'ok') ok++;
            else if (r.level === 'warn') warn++;
            else if (r.level === 'fail') fail++;
        });
        nOk += ok; nWarn += warn; nFail += fail;

        const chip = fail ? ['fail', fail + ' 项异常']
            : warn ? ['warn', warn + ' 项注意']
                : ok ? ['ok', ok === items.length ? '全部正常' : ok + ' 项正常']
                    : ['info', items.length + ' 项'];

        const fold = document.createElement('details');
        fold.className = 'card fold';
        if (openGroups[sec.key]) fold.open = true;
        fold.addEventListener('toggle', function () { openGroups[sec.key] = fold.open; });

        const summary = document.createElement('summary');
        summary.innerHTML = '<span class="ftitle">' + esc(sec.title) + '</span>' +
            '<span class="chip ' + chip[0] + '">' + esc(chip[1]) + '</span><span class="chev"></span>';
        fold.appendChild(summary);

        const rows = document.createElement('div');
        rows.className = 'rows';
        items.forEach(function (r) { rows.appendChild(makeItem(r, sec.key === 'card', false)); });
        fold.appendChild(rows);

        detailGroup.appendChild(fold);
    });
    wrap.appendChild(detailGroup);

    // 结论优先用脚本给的（它还能判断「视图被隔离」这类计数看不出的情况）；没有就按计数自己判断
    if (verdict && MARK[verdict.title] && verdict.detail) {
        setVerdict(verdict.title, verdict.detail, verdict.fix);
    } else {
        const lv = nFail ? 'fail' : nWarn ? 'warn' : nOk ? 'ok' : 'info';
        setVerdict(lv, lv === 'fail' ? nFail + ' 项异常' : lv === 'warn' ? nWarn + ' 项注意'
            : lv === 'ok' ? '全部通过' : '没有检测数据', '');
    }
}

// ------------------------------------------------------------------ 主流程
let lastReport = '';
let lastRefreshAt = 0;
let refreshing = false;

async function refresh(silent) {
    // 并发保护：refresh 内部会跑 status.sh（一次 exec，可能阻塞主线程）。
    // 没有这道闸，visibilitychange / resize / 按钮点击可能叠加出多个在途请求，
    // 表现就是「切回来之后滑动只有滚动条在动」—— 主线程被排队中的 exec 占满了。
    if (refreshing) return;
    refreshing = true;
    try {
        await refreshInner(silent);
    } finally {
        refreshing = false;
        lastRefreshAt = Date.now();
    }
}

async function refreshInner(silent) {
    const wrap = document.getElementById('sections');

    if (!silent || !wrap.querySelector('.fold')) {
        setVerdict('busy', '正在检测…', '稍等几秒，正在检查模块状态');
    }

    if (!hasBridge()) {
        wrap.innerHTML =
            '<div class="err">当前环境没有 WebUI 桥（window.ksu）。<br><br>' +
            '请从 <b>APatch / KernelSU 管理器的模块卡片</b>点开本页面，<br>' +
            '或安装 WebUI X / KSU WebUI Standalone 后再打开。<br><br>' +
            '不想装的话，直接在管理器模块卡片上点「操作」也能看到同样的诊断。</div>';
        setVerdict('fail', '无法访问', '当前环境没有 WebUI 桥');
        return;
    }

    const res = await exec('sh ' + q(STATUS_SH));

    if (res.errno !== 0 && res.stderr) {
        wrap.innerHTML = '<div class="err">诊断脚本执行失败：<br>' + esc(res.stderr) + '</div>';
        setVerdict('fail', '执行失败', '诊断脚本没能跑完');
        return;
    }

    const records = parseProtocol(res.stdout);
    if (!records.length) {
        wrap.innerHTML = '<div class="err">诊断脚本没有输出。<br>修复：在管理器里重刷模块，或点「导出日志」反馈。</div>';
        setVerdict('fail', '没有输出', '在管理器里重刷模块，或点「导出日志」反馈');
        return;
    }

    lastReport = res.stdout;
    render(records);
}

// ------------------------------------------------------------------ 操作
async function actRestartHosts(btn) {
    const done = busyOn(btn, '重启中');
    const stop = busyStart('重启宿主');
    await nextPaint();
    await exec('am force-stop ' + SSC_PKG + '; am force-stop ' + TM_PKG +
               '; am force-stop ' + PA_PKG);
    done();
    stop();
    showToast('已停止背屏中心/主题商店/应用卡中心，重新打开即会读取预设');
}

// 操作结果（修复、导出日志）放在总览下面，最新的在最上
function prependCard(title, detail, fix, level) {
    const card = document.createElement('div');
    card.className = 'card';
    const box = document.createElement('div');
    box.className = 'item ' + (level || 'info');
    box.innerHTML = '<div class="dot">' + (MARK[level] || MARK.info) + '</div><div class="body">' +
        '<div class="t">' + esc(title) + '</div>' +
        (detail ? '<div class="d">' + escNl(detail) + '</div>' : '') +
        (fix ? '<div class="fix">' + escNl(fix) + '</div>' : '') + '</div>';
    card.appendChild(box);
    const wrap = document.getElementById('notices');
    wrap.insertBefore(card, wrap.firstChild);
}

async function runAction(id, btn) {
    const a = ACTIONS[id];
    if (!a) return;

    // 不用 window.confirm：管理器没实现 onJsConfirm 时会静默丢弃对话框，
    // 按钮看起来就「点了没反应」。改成连点两次确认，只依赖 DOM。
    if (btn.dataset.armed !== '1') {
        btn.dataset.armed = '1';
        btn.textContent = '再点一次确认';
        btn.classList.add('armed');
        clearTimeout(btn._t);
        btn._t = setTimeout(function () {
            btn.dataset.armed = '';
            btn.textContent = a.label;
            btn.classList.remove('armed');
        }, 8000);
        showToast(a.hint || '再点一次确认');
        return;
    }

    btn.dataset.armed = '';
    clearTimeout(btn._t);
    btn.classList.remove('armed');
    const done = busyOn(btn, a.busy || '处理中');
    const stop = busyStart(a.label);
    await nextPaint();

    const res = await exec(a.cmd, 60000);
    const out = (res.stdout || '').trim();
    const ok        = /\bRESULT=ok\b/.test(out);
    const nothing   = /\bRESULT=nothing\b/.test(out);
    const partial   = /\bRESULT=partial\b/.test(out);

    let lines = out.split('\n').filter(function (l) {
        return l.indexOf('RESULT=') !== 0;
    });
    if (lines.length > 1) lines = lines.slice(1);
    const human = lines.join('\n').trim();

    done();
    stop();
    await refresh(true);

    prependCard(
        ok ? (a.titleOk || '已完成')
            : nothing ? (a.titleNothing || '无需处理')
                : partial ? (a.titlePartial || a.titleFail || '只成功了一部分')
                    : (a.titleFail || '操作失败'),
        human || (ok ? '已完成' : '请导出日志反馈'),
        (ok || partial) ? (a.fixOk || '') : '',
        ok || nothing ? 'ok' : partial ? 'warn' : 'fail'
    );
    window.scrollTo(0, 0);
    showToast(ok ? '已完成' : (nothing ? '无需处理' : (partial ? '补了一部分' : '操作失败')));
}

async function actLogpack(btn) {
    const done = busyOn(btn, '打包中');
    const stop = busyStart('打包日志');
    showToast('正在打包（含诊断与背屏状态文件），约 10~30 秒，别退出本页');
    await nextPaint();

    const res = await exec('sh ' + q(LOGPACK_SH), 120000);
    const path = (res.stdout || '').trim().split('\n').pop();

    done();
    stop();

    if (path && path.indexOf('/') === 0) {
        showToast('日志已保存：' + path);
        window.__lastLogPath = path;
        prependCard('日志包已生成', path,
            '用文件管理器进入 内部存储/Download 就能看到，直接发给开发者即可', 'ok');
    } else {
        showToast('打包失败，请把下面这段发给我');
        copyFallback();
    }
}

function copyFallback() {
    const ta = document.createElement('textarea');
    ta.value = lastReport;
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); showToast('诊断内容已复制'); }
    catch (e) { showToast('复制失败，请手动截图'); }
    document.body.removeChild(ta);
}

async function actCopy(btn) {
    const done = busyOn(btn, '收集中');
    const stop = busyStart('整理诊断文本');
    await nextPaint();
    const res = await exec('sh ' + q(STATUS_SH) + ' --text', 60000);
    const text = '【MiBackscreen背屏附加资源 诊断】\n' + (res.stdout || lastReport);

    if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            done();
            stop();
            showToast('诊断内容已复制，可直接粘贴');
            return;
        } catch (e) { /* 降级 */ }
    }

    const b64 = btoa(unescape(encodeURIComponent(text)));
    const res2 = await exec(
        'echo ' + q(b64) + ' | base64 -d > /sdcard/Download/mibackscreen-diagnose.txt && echo ok',
        30000);
    done();
    stop();
    if ((res2.stdout || '').indexOf('ok') >= 0) {
        showToast('已保存到 Download/mibackscreen-diagnose.txt');
    } else {
        copyFallback();
    }
}

// ------------------------------------------------------------------ 启动
function syncDockPadding() {
    const dock = document.querySelector('.dock');
    if (!dock) return;
    // dock 的 padding-bottom 已含手势条高度，这里只补浮层与内容之间的间隙。
    // 安全区一次性读出来，避免 CSS var 与 JS 各算一遍导致底部空一大块。
    const probe = getComputedStyle(document.body).getPropertyValue('--safe-area-inset-bottom');
    const inset = parseFloat(probe) || 0;
    const want = (dock.getBoundingClientRect().height + 20 - inset) + 'px';
    // 值没变就不写。ResizeObserver 回调里改 body padding 会再触发一轮布局，
    // 无条件写会形成「布局 → 回调 → 写样式 → 再布局」的抖动，滑动时尤其明显。
    if (want === syncDockPadding._last) return;
    syncDockPadding._last = want;
    document.body.style.paddingBottom = want;
}

// 状态栏毛玻璃：静止在顶端时透明（不压 hero 圆角），滚过一点才浮现。
// 只在状态真的翻转时写 class —— scroll 是高频事件，每帧无脑 toggle
// 会让样式反复失效，滑动时白做一堆重算。
let veilOn = null;
function syncStatusVeil() {
    const veil = document.getElementById('statusveil');
    if (!veil) return;
    const want = window.scrollY > 4;
    if (want === veilOn) return;
    veilOn = want;
    veil.classList.toggle('on', want);
}

async function actRefresh(btn) {
    const done = busyOn(btn, '检测中');
    const stop = busyStart('检测中');
    await nextPaint();
    await refresh(false);
    done();
    stop();
}

document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('btn-refresh').addEventListener('click', function () { actRefresh(this); });
    document.getElementById('btn-restart').addEventListener('click', function () { actRestartHosts(this); });
    document.getElementById('btn-log').addEventListener('click', function () { actLogpack(this); });
    document.getElementById('btn-copy').addEventListener('click', function () { actCopy(this); });

    syncDockPadding();
    if (window.ResizeObserver) {
        new ResizeObserver(syncDockPadding).observe(document.querySelector('.dock'));
    }
    window.addEventListener('resize', syncDockPadding);

    // 状态栏毛玻璃跟着滚动切换；先跑一次覆盖「刷新后停在中间」的情况。
    syncStatusVeil();
    window.addEventListener('scroll', syncStatusVeil, { passive: true });

    refresh(false);
});

// 切回页面时按需刷新：30 秒内的来回切换不重跑。
// 不加这道闸，每次从别的应用切回来都会 exec 一次 status.sh；
// 桥若是同步实现，主线程会被阻塞 —— 表现是「能滑但内容不重绘，只有滚动条在动」。
document.addEventListener('visibilitychange', function () {
    if (document.hidden) return;
    if (Date.now() - lastRefreshAt < 30000) return;
    refresh(true);
});
