/* Xianyu Copilot UI Kit —— 原生质感交互组件（纯原生 JS，无依赖）
 * 暴露全局：UI.esc / UI.api / UI.haptic / UI.toast / UI.swipe / UI.sheet / UI.itemCard / UI.thinking / UI.pullToRefresh
 */
(function () {
  const UI = {};

  // ---------- 安全：所有来自买家/服务端的文本必须经过 esc() 再进 innerHTML ----------
  UI.esc = (v) => String(v ?? '').replace(/[&<>"'`]/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;'
  }[c]));

  // ---------- API：自动携带管理令牌，401 时弹窗索取 ----------
  const TOKEN_KEY = 'xc_admin_token';
  UI.api = async function (url, opts = {}) {
    const headers = Object.assign({}, opts.headers || {});
    const t = localStorage.getItem(TOKEN_KEY);
    if (t) headers['Authorization'] = 'Bearer ' + t;
    const res = await fetch(url, Object.assign({}, opts, { headers }));
    if (res.status === 401) {
      const input = prompt('请输入管理令牌 (ADMIN_TOKEN)');
      if (input) { localStorage.setItem(TOKEN_KEY, input.trim()); return UI.api(url, opts); }
    }
    return res;  // 与 fetch 返回值一致，旧代码 .then(r => r.json()) 无需改动
  };

  UI.haptic = (ms = 8) => { try { navigator.vibrate && navigator.vibrate(ms); } catch (_) {} };

  UI.toast = function (text, ms = 1800) {
    const el = document.createElement('div');
    el.className = 'toast'; el.textContent = text;
    document.body.appendChild(el); setTimeout(() => el.remove(), ms);
  };

  // ---------- 左滑操作 ----------
  // UI.swipe(containerEl)：对容器内所有 .swipe-row 生效（事件委托，列表重绘后无需重新绑定）
  // 结构：<div class="swipe-row"><div class="swipe-row__actions"><button data-act="read">…</button></div>
  //        <div class="swipe-row__content">…</div></div>
  let openRow = null;
  UI.swipe = function (container, onAction) {
    if (container.__swipeBound) return; container.__swipeBound = true;
    let row, content, sx, sy, dx, w, locked, dragging;
    const close = (r) => { if (r) { r.querySelector('.swipe-row__content').style.transform = ''; r.classList.remove('is-open'); } };
    container.addEventListener('pointerdown', e => {
      row = e.target.closest('.swipe-row'); if (!row) return;
      if (openRow && openRow !== row) { close(openRow); openRow = null; }
      content = row.querySelector('.swipe-row__content');
      w = row.querySelector('.swipe-row__actions').offsetWidth;
      sx = e.clientX; sy = e.clientY; dx = 0; locked = null; dragging = false;
    });
    container.addEventListener('pointermove', e => {
      if (!row) return;
      const mx = e.clientX - sx, my = e.clientY - sy;
      if (locked === null && (Math.abs(mx) > 6 || Math.abs(my) > 6)) locked = Math.abs(mx) > Math.abs(my) ? 'x' : 'y';
      if (locked !== 'x') return;
      dragging = true; row.classList.add('is-dragging');
      const base = row.classList.contains('is-open') ? -w : 0;
      dx = Math.min(0, base + mx);
      if (dx < -w) dx = -w - Math.pow(-w - dx, 0.7);   // 超出部分橡皮筋阻尼
      content.style.transform = `translateX(${dx}px)`;
    });
    const end = () => {
      if (!row) return;
      row.classList.remove('is-dragging');
      if (dragging) {
        const open = dx < -w / 2;
        content.style.transform = open ? `translateX(${-w}px)` : '';
        row.classList.toggle('is-open', open);
        if (open) { openRow = row; UI.haptic(); }
        row.__suppressClick = true; setTimeout(() => row && (row.__suppressClick = false), 50);
      }
      row = null;
    };
    container.addEventListener('pointerup', end);
    container.addEventListener('pointercancel', end);
    container.addEventListener('click', e => {
      const r = e.target.closest('.swipe-row');
      if (r && r.__suppressClick) { e.stopPropagation(); e.preventDefault(); return; }
      const btn = e.target.closest('.swipe-row__actions button');
      if (btn && onAction) { UI.haptic(12); onAction(btn.dataset.act, r.dataset); close(r); openRow = null; }
    }, true);
  };

  // ---------- 底部抽屉（下拉关闭 + 背景点击关闭 + 返回键关闭） ----------
  UI.sheet = function (sheetEl, maskEl) {
    const api = {
      open() {
        maskEl.classList.remove('hidden'); sheetEl.classList.remove('hidden');
        requestAnimationFrame(() => { maskEl.classList.add('is-open'); sheetEl.classList.add('is-open'); });
        history.pushState({ sheet: true }, '');
      },
      close(fromPop) {
        maskEl.classList.remove('is-open'); sheetEl.classList.remove('is-open');
        sheetEl.style.transform = '';
        setTimeout(() => { maskEl.classList.add('hidden'); sheetEl.classList.add('hidden'); }, 300);
        if (!fromPop && history.state && history.state.sheet) history.back();
      }
    };
    maskEl.addEventListener('click', () => api.close());
    window.addEventListener('popstate', () => sheetEl.classList.contains('is-open') && api.close(true));
    const grab = sheetEl.querySelector('.sheet__grabber') || sheetEl;
    let sy = null, dy = 0;
    grab.addEventListener('pointerdown', e => { sy = e.clientY; dy = 0; sheetEl.style.transition = 'none'; grab.setPointerCapture(e.pointerId); });
    grab.addEventListener('pointermove', e => { if (sy === null) return; dy = Math.max(0, e.clientY - sy); sheetEl.style.transform = `translateY(${dy}px)`; });
    grab.addEventListener('pointerup', () => {
      sheetEl.style.transition = ''; sy = null;
      if (dy > 120) api.close(); else sheetEl.style.transform = '';
    });
    return api;
  };

  // ---------- 对话内浮动宝贝卡片 ----------
  // item: {item_id, title, price, pic, status, url}；滚动时自动收缩为紧凑模式
  UI.itemCard = function (item, scrollEl) {
    const e = UI.esc;
    const html = `
      <div class="item-float" data-item-float>
        <div class="item-float__card">
          ${item.pic ? `<img class="item-float__pic" src="${e(item.pic)}" referrerpolicy="no-referrer" loading="lazy" alt="">`
                     : `<div class="item-float__pic"></div>`}
          <div class="min-w-0 flex-1">
            <div class="text-xs font-semibold text-slate-900 truncate">${e(item.title || ('宝贝 ' + item.item_id))}</div>
            <div class="item-float__desc text-[10px] text-slate-400 mt-0.5">${e(item.status || '在售')} · ID ${e(item.item_id)}</div>
          </div>
          <div class="text-right shrink-0">
            <div class="text-sm font-bold text-rose-500">${item.price != null ? '¥' + e(item.price) : ''}</div>
            ${item.url ? `<a href="${e(item.url)}" target="_blank" rel="noopener" class="text-[10px] text-brand">查看</a>` : ''}
          </div>
        </div>
      </div>`;
    if (scrollEl && !scrollEl.__itemFloatBound) {
      scrollEl.__itemFloatBound = true;
      scrollEl.addEventListener('scroll', () => {
        const f = scrollEl.querySelector('[data-item-float]');
        f && f.classList.toggle('is-compact', scrollEl.scrollTop > 24);
      }, { passive: true });
    }
    return html;
  };

  // ---------- AI 思考链折叠 ----------
  // thinking: {steps:[{label:'意图', text:'议价'}, {label:'专家', text:'price'}, …], ms, streaming}
  UI.thinking = function (thinking) {
    if (!thinking || !thinking.steps || !thinking.steps.length) return '';
    const e = UI.esc;
    const steps = thinking.steps.map(s => `<div class="thinking__step"><b>${e(s.label)}</b><span>${e(s.text)}</span></div>`).join('');
    return `<details class="thinking ${thinking.streaming ? 'is-streaming' : ''}">
      <summary><span class="chev">›</span>AI 思考 ${thinking.ms ? (thinking.ms / 1000).toFixed(1) + 's' : ''}</summary>
      <div class="thinking__steps">${steps}</div></details>`;
  };

  // ---------- 下拉刷新 ----------
  UI.pullToRefresh = function (scrollEl, onRefresh) {
    const ind = document.createElement('div'); ind.className = 'ptr'; ind.textContent = '下拉刷新';
    scrollEl.prepend(ind);
    let sy = null, dy = 0;
    scrollEl.addEventListener('touchstart', e => { if (scrollEl.scrollTop <= 0) sy = e.touches[0].clientY; }, { passive: true });
    scrollEl.addEventListener('touchmove', e => {
      if (sy === null) return;
      dy = Math.max(0, Math.min(80, (e.touches[0].clientY - sy) * 0.5));
      ind.style.height = dy + 'px'; ind.textContent = dy > 56 ? '松开刷新' : '下拉刷新';
    }, { passive: true });
    scrollEl.addEventListener('touchend', async () => {
      if (sy === null) return; sy = null;
      if (dy > 56) { ind.textContent = '刷新中…'; UI.haptic(); try { await onRefresh(); } finally {} }
      ind.style.transition = 'height .25s'; ind.style.height = '0'; setTimeout(() => ind.style.transition = '', 260);
    });
  };

  window.UI = UI;
})();
