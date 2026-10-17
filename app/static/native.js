/* ============================================================
 * Xianyu Copilot · 原生交互层（无依赖，ES2020）
 * 用法：<script src="/static/native.js" defer></script>
 *        Native.api / Native.esc / Native.swipe / Native.thinking / Native.itemCard / Native.sheet
 * ============================================================ */
(function () {
  'use strict';

  /* ---------- 0. 安全输出：所有买家可控文本先转义再进 innerHTML ---------- */
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;' };
  const esc = (s) => String(s ?? '').replace(/[&<>"'`]/g, (c) => ESC[c]);

  /* ---------- 0. API：统一带口令、超时与错误处理 ---------- */
  const TOKEN_KEY = 'xc_admin_token';
  async function api(path, opts = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), opts.timeout ?? 15000);
    const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
    const t = localStorage.getItem(TOKEN_KEY);
    if (t) headers.Authorization = 'Bearer ' + t;
    try {
      const r = await fetch(path, { ...opts, headers, signal: ctrl.signal });
      if (r.status === 401) {
        const v = prompt('请输入管理口令 ADMIN_TOKEN');
        if (v) { localStorage.setItem(TOKEN_KEY, v.trim()); return api(path, opts); }
      }
      if (!r.ok) throw new Error(r.status + ' ' + (await r.text()).slice(0, 120));
      return r.json();
    } finally { clearTimeout(timer); }
  }

  /* ---------- 触感反馈（Android 有效，iOS Safari 不支持时静默） ---------- */
  const haptic = (ms = 8) => { try { navigator.vibrate && navigator.vibrate(ms); } catch (_) {} };

  /* ---------- 1. TabBar ---------- */
  function tabbar(root, onChange) {
    root.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-tab]');
      if (!btn || btn.getAttribute('aria-selected') === 'true') return;
      root.querySelectorAll('[data-tab]').forEach((b) => b.setAttribute('aria-selected', String(b === btn)));
      haptic();
      onChange(btn.dataset.tab);
    });
  }
  function setBadge(tab, n) {
    const el = document.querySelector(`[data-tab="${tab}"] .tabbar__badge`);
    if (el) { el.dataset.n = String(n); el.textContent = n > 99 ? '99+' : n ? String(n) : ''; }
  }

  /* ---------- 2. 左滑操作：事件委托，一个监听器管整张列表 ---------- */
  function swipe(listEl, { onAction } = {}) {
    let row = null, startX = 0, startY = 0, baseDx = 0, dx = 0, lock = null, maxDx = 0, openRow = null;
    const set = (r, v, animate) => {
      r.classList.toggle('is-animating', !!animate);
      r.querySelector('.swipe__content').style.setProperty('--dx', v + 'px');
    };
    const close = (r) => { if (r) { set(r, 0, true); if (openRow === r) openRow = null; } };

    listEl.addEventListener('pointerdown', (e) => {
      const r = e.target.closest('.swipe');
      if (!r || e.target.closest('.swipe__btn')) return;
      if (openRow && openRow !== r) close(openRow);
      row = r; startX = e.clientX; startY = e.clientY; lock = null;
      maxDx = r.querySelector('.swipe__actions').offsetWidth;
      baseDx = openRow === r ? -maxDx : 0; dx = baseDx;
    });
    listEl.addEventListener('pointermove', (e) => {
      if (!row) return;
      const mx = e.clientX - startX, my = e.clientY - startY;
      if (lock === null && Math.hypot(mx, my) > 6) lock = Math.abs(mx) > Math.abs(my) * 1.2 ? 'x' : 'y';
      if (lock !== 'x') return;
      row.setPointerCapture?.(e.pointerId);
      let v = baseDx + mx;
      if (v > 0) v = v * 0.2;                                   // 向右：阻尼
      if (v < -maxDx) v = -maxDx + (v + maxDx) * 0.25;          // 超出按钮宽度：橡皮筋
      dx = v; set(row, v, false);
    });
    const end = () => {
      if (!row) return;
      if (lock === 'x') {
        const open = dx < -maxDx * 0.4;
        set(row, open ? -maxDx : 0, true);
        if (open && openRow !== row) haptic();
        openRow = open ? row : null;
        const r = row; r.dataset.justSwiped = '1';               // 吞掉本次 click，避免误开详情
        setTimeout(() => { delete r.dataset.justSwiped; }, 0);
      }
      row = null;
    };
    listEl.addEventListener('pointerup', end);
    listEl.addEventListener('pointercancel', end);
    listEl.addEventListener('click', (e) => {
      const r = e.target.closest('.swipe');
      if (r && r.dataset.justSwiped) { e.stopPropagation(); e.preventDefault(); return; }
      const btn = e.target.closest('.swipe__btn');
      if (btn) { e.stopPropagation(); haptic(12); onAction?.(btn.dataset.action, r.dataset, r); close(r); return; }
      if (openRow) { close(openRow); e.stopPropagation(); }
    }, true);
    document.addEventListener('pointerdown', (e) => { if (openRow && !openRow.contains(e.target)) close(openRow); });
  }

  /** 生成一行可左滑的卡片；content 为已转义的 HTML，data 写入 data-* 供回调使用 */
  function swipeRow(contentHtml, actions, data = {}) {
    const attrs = Object.entries(data).map(([k, v]) => `data-${k}="${esc(v)}"`).join(' ');
    const btns = actions.map((a) =>
      `<button class="swipe__btn swipe__btn--${a.tone}" data-action="${esc(a.id)}">${esc(a.label)}</button>`).join('');
    return `<div class="swipe" ${attrs}><div class="swipe__actions">${btns}</div>` +
           `<div class="swipe__content">${contentHtml}</div></div>`;
  }

  /* ---------- 3. 宝贝浮动卡：滚动时收紧 ---------- */
  function itemCard(item) {
    if (!item) return '';
    const orig = item.original_price && item.original_price > item.price ? `<small>¥${esc(item.original_price)}</small>` : '';
    const chip = item.order_status ? `<span class="item-float__chip">${esc(item.order_status)}</span>` : '';
    const cta = item.can_ship ? `<button class="item-float__cta pressable" data-act="ship" data-order="${esc(item.order_id)}">发货</button>` : '';
    return `<div class="item-float" id="item-float">
      <img class="item-float__img" src="${esc(item.image || '')}" alt="" loading="lazy" referrerpolicy="no-referrer">
      <div style="min-width:0"><div class="item-float__title">${esc(item.title || '宝贝 ' + item.item_id)}</div>
      <div class="item-float__price">¥${esc(item.price ?? '-')}${orig} ${chip}</div></div>${cta}</div>`;
  }
  function bindCompactOnScroll(scroller) {
    let ticking = false;
    scroller.addEventListener('scroll', () => {
      if (ticking) return; ticking = true;
      requestAnimationFrame(() => {
        document.getElementById('item-float')?.classList.toggle('is-compact', scroller.scrollTop > 24);
        ticking = false;
      });
    }, { passive: true });
  }

  /* ---------- 4. AI 思考链折叠 ---------- */
  function thinking(text, ms, streaming = false) {
    if (!text && !streaming) return '';
    const label = streaming ? '正在思考…' : `已思考 ${(ms / 1000).toFixed(1)} 秒`;
    return `<details class="think${streaming ? ' is-streaming' : ''}"><summary>${label}</summary>` +
           `<div class="think__body">${esc(text || '')}</div></details>`;
  }

  /* ---------- 底部弹层：下拉关闭 ---------- */
  function sheet(panel, { onClose } = {}) {
    let y0 = null, dy = 0;
    const grab = panel.querySelector('.sheet__grabber') || panel;
    grab.addEventListener('pointerdown', (e) => { y0 = e.clientY; dy = 0; panel.classList.add('is-dragging'); grab.setPointerCapture(e.pointerId); });
    grab.addEventListener('pointermove', (e) => { if (y0 === null) return; dy = Math.max(0, e.clientY - y0); panel.style.setProperty('--sy', dy + 'px'); });
    const up = () => {
      if (y0 === null) return; y0 = null; panel.classList.remove('is-dragging');
      if (dy > panel.offsetHeight * 0.25) { panel.style.setProperty('--sy', '100%'); setTimeout(() => { onClose?.(); panel.style.setProperty('--sy', '0px'); }, 300); }
      else panel.style.setProperty('--sy', '0px');
    };
    grab.addEventListener('pointerup', up); grab.addEventListener('pointercancel', up);
  }

  /* ---------- 实时：SSE + 指数退避，回到前台立即重连 ---------- */
  function live(onEvent) {
    let es = null, wait = 1000;
    const open = () => {
      const t = localStorage.getItem(TOKEN_KEY) || '';
      es = new EventSource('/api/events?token=' + encodeURIComponent(t));
      es.onopen = () => { wait = 1000; };
      es.onmessage = (m) => { try { onEvent(JSON.parse(m.data)); } catch (_) {} };
      es.onerror = () => { es.close(); setTimeout(open, wait); wait = Math.min(wait * 2, 30000); };
    };
    document.addEventListener('visibilitychange', () => { if (!document.hidden && es && es.readyState === 2) { wait = 1000; open(); } });
    open();
  }

  window.Native = { esc, api, haptic, tabbar, setBadge, swipe, swipeRow, itemCard, bindCompactOnScroll, thinking, sheet, live };
})();
