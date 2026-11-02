'use strict';

const $ = (id) => document.getElementById(id);
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

const state = {
  lib: null,
  mode: 'metaphor',
  size: 'L',
  palette: 'teal',
  items: [],
  selected: null, // объект из state.items
  exploded: false,
};

// DOM-элемент для каждого объекта композиции
const els = new Map();

// Состояние 3D-сцены: s — масштаб доски под размер окна, cur/tgt — текущий и целевой наклон
const view = {
  s: 1,
  cur: { rx: 0, ry: 0, rz: 0 },
  tgt: { rx: 0, ry: 0, rz: 0 },
  px: 0.5,
  py: 0.5,
  dragging: false,
  raf: 0,
};

const HINTS = {
  metaphor: ['Введите метафору или заголовок — программа сама выберет объекты.', 'Например: рост финансов'],
  names: ['Перечислите названия компонентов через запятую — программа соберёт из них композицию.', 'Например: книга, лампа, телефон'],
};

const CUSTOM_FIELDS = [
  ['bgLight', 'Фон, светлый', '#d7cdf5'], ['bgMid', 'Фон, средний', '#aa9bf0'], ['bgDark', 'Фон, тёмный', '#7864d7'],
  ['objA', 'Объекты, A', '#f9ecb0'], ['objB', 'Объекты, B', '#f9a3aa'], ['objC', 'Объекты, C', '#ffe3e1'],
];

init();

async function init() {
  state.lib = await (await fetch('/api/library')).json();

  fillSelect($('size'), state.lib.sizes.map((s) => [s, s]));
  fillSelect($('layout'), state.lib.layouts.map((l) => [l.id, l.name]));
  renderPalettes();
  renderCustomFields();
  renderLibrary();
  applyTheme();

  initLlm();

  $('tab-metaphor').onclick = () => setMode('metaphor');
  $('tab-names').onclick = () => setMode('names');
  $('compose').onclick = compose;
  $('query').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); compose(); }
  });
  $('size').onchange = () => { state.size = $('size').value; rebuild(); renderLibrary(); };
  $('palette').onchange = () => { state.palette = $('palette').value; applyTheme(); renderLibrary(); refreshImages(); };
  $('add-palette').onclick = addPalette;
  $('rotation').oninput = () => edit((it) => { it.rotation = Number($('rotation').value); });
  $('scale').oninput = () => edit((it) => { it.scale = Number($('scale').value) / 100 * baseScale(it); });
  $('duplicate').onclick = duplicate;
  $('remove').onclick = removeSelected;
  $('forward').onclick = () => moveLayer(1);
  $('backward').onclick = () => moveLayer(-1);
  $('clear').onclick = () => { state.items = []; select(null); render(); };
  $('explode').onclick = toggleExplode;
  $('export').onclick = exportSvg;

  document.addEventListener('keydown', (e) => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    if (typing) return;
    if ((e.key === 'Delete' || e.key === 'Backspace') && state.selected) { e.preventDefault(); removeSelected(); }
    if (e.key === 'Escape') select(null);
  });

  const stage = $('stage');
  stage.addEventListener('pointermove', (e) => {
    const r = stage.getBoundingClientRect();
    view.px = (e.clientX - r.left) / r.width;
    view.py = (e.clientY - r.top) / r.height;
    aim();
  });
  stage.addEventListener('pointerleave', () => { view.px = 0.5; view.py = 0.5; aim(); });
  stage.addEventListener('pointerdown', (e) => { if (!e.target.closest('.item, .dock')) select(null); });
  new ResizeObserver(fit).observe(stage);
  new ResizeObserver(fit).observe($('dock'));

  setMode('metaphor');
  fit();
  render();
}

function fillSelect(el, pairs) {
  el.replaceChildren(...pairs.map(([value, text]) => new Option(text, value)));
}

function setMode(mode) {
  state.mode = mode;
  for (const m of ['metaphor', 'names']) {
    const tab = $('tab-' + m);
    tab.classList.toggle('active', m === mode);
    tab.setAttribute('aria-selected', String(m === mode));
  }
  $('hint').textContent = HINTS[mode][0];
  $('query').placeholder = HINTS[mode][1];
}

function setStatus(text, isError) {
  const el = $('status');
  el.textContent = text;
  el.classList.toggle('error', Boolean(isError));
}

/* ---------- Подключение ИИ ---------- */

const llm = { info: null };

async function initLlm() {
  await refreshLlm();
  $('llm-badge').onclick = openLlmDialog;
  $('llm-provider').onchange = () => fillLlmForm($('llm-provider').value, true);
  $('llm-save').onclick = saveLlm;
  $('llm-off').onclick = async () => {
    llm.info = await (await fetch('/api/llm', { method: 'DELETE' })).json();
    renderLlmBadge();
    llmMessage('ИИ отключён: подбор идёт по тегам');
  };
}

async function refreshLlm() {
  llm.info = await (await fetch('/api/llm')).json();
  renderLlmBadge();
}

function llmLabel() {
  const i = llm.info;
  return i && i.connected ? `${i.providerName.replace(/ \(.*\)/, '')} · ${i.model}` : '';
}

function renderLlmBadge() {
  const badge = $('llm-badge');
  const on = llm.info && llm.info.connected;
  badge.classList.toggle('on', on);
  badge.textContent = on ? `ИИ: ${llmLabel()}` : 'Подключить ИИ';
  badge.title = on ? 'Подбор по метафоре идёт через эту модель. Нажмите, чтобы изменить'
    : 'Без ИИ объекты подбираются по тегам. Нажмите, чтобы подключить модель';
}

function llmMessage(text, isError) {
  const el = $('llm-msg');
  el.textContent = text;
  el.classList.toggle('error', Boolean(isError));
}

async function openLlmDialog() {
  await refreshLlm();
  fillSelect($('llm-provider'), llm.info.presets.map((p) => [p.id, p.name]));
  const i = llm.info;
  const provider = i.connected ? i.provider : 'ollama';
  $('llm-provider').value = provider;
  await fillLlmForm(provider, !i.connected);
  if (i.connected) {
    $('llm-url').value = i.baseUrl;
    $('llm-model').value = i.model;
  }
  llmMessage('');
  $('llm-dialog').showModal();
}

// Подставляет адрес и модель выбранного сервиса; для локальных подтягивает список установленных моделей
async function fillLlmForm(provider, resetValues) {
  const p = llm.info.presets.find((x) => x.id === provider);
  if (resetValues) {
    $('llm-url').value = p.baseUrl;
    $('llm-model').value = p.model;
    $('llm-key').value = '';
  }
  $('llm-key').placeholder = llm.info.connected && llm.info.provider === provider && llm.info.hasKey
    ? 'ключ сохранён, оставьте пустым чтобы не менять'
    : (p.needsKey ? 'обязателен' : 'не нужен для локальных моделей');
  $('llm-models').replaceChildren();
  if (provider === 'ollama' || provider === 'lmstudio') {
    const models = await (await fetch('/api/llm/models?provider=' + provider)).json();
    $('llm-models').replaceChildren(...models.map((m) => new Option(m, m)));
    if (resetValues && models.length) $('llm-model').value = models[0];
    if (!models.length) llmMessage('Локальный сервер не отвечает. Запустите ' + p.name.replace(/ \(.*\)/, '') + ' или выберите другой сервис.', true);
  }
}

async function saveLlm() {
  llmMessage('Проверяю…');
  $('llm-save').disabled = true;
  try {
    const key = $('llm-key').value;
    const save = await fetch('/api/llm', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      // пустое поле при уже сохранённом ключе: ключ не меняем (null)
      body: JSON.stringify({
        provider: $('llm-provider').value,
        baseUrl: $('llm-url').value,
        model: $('llm-model').value,
        apiKey: key === '' ? null : key,
      }),
    });
    const data = await save.json();
    if (!save.ok) return llmMessage(data.error, true);
    llm.info = data;
    renderLlmBadge();
    const test = await (await fetch('/api/llm/test', { method: 'POST' })).json();
    if (test.ok) {
      const names = test.picked.map((id) => state.lib.objects.find((o) => o.id === id)?.name || id).join(', ');
      llmMessage(`Подключено. Тестовый запрос «обучение и финансы» → ${names}`);
      $('llm-key').value = '';
    } else {
      llmMessage(`Настройки сохранены, но модель не ответила: ${test.error}`, true);
    }
  } catch (e) {
    llmMessage('Не удалось связаться с сервером приложения', true);
  } finally {
    $('llm-save').disabled = false;
  }
}

/* ---------- Палитры ---------- */

function currentPalette() {
  return state.lib.palettes.find((p) => p.id === state.palette);
}

function renderPalettes() {
  fillSelect($('palette'), state.lib.palettes.map((p) => [p.id, p.name]));
  $('palette').value = state.palette;
}

// Цвета схемы окрашивают и саму страницу: фон доски, свечение, переливы
function applyTheme() {
  const p = currentPalette();
  const root = document.documentElement.style;
  root.setProperty('--pl', p.bgLight);
  root.setProperty('--pd', p.bgDark);
  root.setProperty('--po', p.objB);
  $('swatches').replaceChildren(...['bgLight', 'bgMid', 'bgDark', 'objA', 'objB', 'objC'].map((k) => {
    const i = document.createElement('i');
    i.style.background = p[k];
    return i;
  }));
}

function renderCustomFields() {
  $('custom').replaceChildren(...CUSTOM_FIELDS.map(([key, label, value]) => {
    const l = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'color';
    input.id = 'custom-' + key;
    input.value = value;
    l.append(input, label);
    return l;
  }));
}

async function addPalette() {
  const body = { name: 'Своя палитра' };
  for (const [key] of CUSTOM_FIELDS) body[key] = $('custom-' + key).value.toUpperCase();
  const res = await fetch('/api/palettes', json(body));
  const data = await res.json();
  if (!res.ok) return setStatus(data.error, true);
  state.lib.palettes.push(data);
  state.palette = data.id;
  renderPalettes();
  applyTheme();
  renderLibrary();
  refreshImages();
  setStatus('Палитра «' + data.name + '» добавлена');
}

/* ---------- Библиотека ---------- */

function assetUrl(id, variant) {
  return `/api/asset/${state.size}/${id}/${variant}?palette=${encodeURIComponent(state.palette)}`;
}

function dims(id) {
  return state.lib.dims[state.size][id];
}

function renderLibrary() {
  $('library').replaceChildren(...state.lib.objects.map((o) => {
    const tile = document.createElement('div');
    tile.className = 'tile';
    for (const variant of ['main', 'bg']) {
      const img = document.createElement('img');
      img.src = assetUrl(o.id, variant);
      img.alt = o.name + (variant === 'bg' ? ' (фон)' : '');
      img.title = 'Добавить: ' + img.alt;
      img.onclick = () => addItem(o.id, variant);
      tile.append(img);
    }
    tile.append(o.name);
    return tile;
  }));
}

/* ---------- Операции над композицией ---------- */

// Масштаб по умолчанию: объект вписывается в квадрат ~300 px.
function baseScale(it) {
  const d = dims(it.objectId);
  return 300 / Math.max(d.w, d.h);
}

function addItem(id, variant) {
  const d = dims(id);
  const scale = (variant === 'bg' ? 400 : 300) / Math.max(d.w, d.h);
  const c = state.lib.canvas;
  const off = (state.items.length % 5) * 30;
  const it = { objectId: id, variant, x: c.w / 2 + off, y: c.h / 2 + off, rotation: 0, scale };
  state.items.push(it);
  select(it);
  render({ pop: it });
}

function edit(fn) {
  if (!state.selected) return;
  fn(state.selected);
  render();
}

function duplicate() {
  const it = state.selected;
  if (!it) return;
  const copy = { ...it, x: it.x + 30, y: it.y + 30 };
  state.items.push(copy);
  select(copy);
  render({ pop: copy });
}

function removeSelected() {
  const it = state.selected;
  if (!it) return;
  const el = els.get(it);
  const finish = () => {
    state.items.splice(state.items.indexOf(it), 1);
    select(null);
    render();
  };
  if (el && !reduceMotion) {
    el.querySelector('.lift').animate(
      [{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'translateZ(60px) scale(.7)' }],
      { duration: 200, easing: 'ease-in', fill: 'forwards' }).onfinish = finish;
  } else {
    finish();
  }
}

function moveLayer(delta) {
  const it = state.selected;
  const i = state.items.indexOf(it);
  const j = i + delta;
  if (!it || j < 0 || j >= state.items.length) return;
  [state.items[i], state.items[j]] = [state.items[j], state.items[i]];
  render();
}

function select(it) {
  state.selected = it;
  for (const [item, el] of els) el.classList.toggle('selected', item === it);
  syncDock();
}

function syncDock() {
  const it = state.selected;
  for (const id of ['rotation', 'scale', 'duplicate', 'remove', 'forward', 'backward']) {
    $(id).disabled = !it || state.exploded;
  }
  if (it) {
    $('rotation').value = Math.round(it.rotation);
    $('scale').value = Math.min(300, Math.max(5, Math.round(it.scale / baseScale(it) * 100)));
  }
}

/* ---------- Автосборка ---------- */

async function compose() {
  const query = $('query').value.trim();
  if (!query) return setStatus('Введите запрос', true);
  setStatus('Собираю…');
  $('compose').disabled = true;
  try {
    const res = await fetch('/api/compose', json({
      mode: state.mode,
      query,
      size: state.size,
      palette: state.palette,
      layout: $('layout').value,
      background: $('background').checked,
    }));
    const data = await res.json();
    if (!res.ok) return setStatus(data.error, true);
    state.items = data.composition.items;
    state.selected = null;
    if (state.exploded) toggleExplode();
    render({ flyIn: true });
    const names = data.picked.map((id) => state.lib.objects.find((o) => o.id === id).name).join(', ');
    let how = { llm: 'подобрано ИИ (' + llmLabel() + ')', tags: 'подобрано по тегам', names: 'найдено по названиям' }[data.source];
    if (data.llmError) how += `. ИИ не сработал: ${data.llmError}`;
    setStatus(`${names} — ${how}`, Boolean(data.llmError));
  } catch (e) {
    setStatus('Не удалось связаться с сервером', true);
  } finally {
    $('compose').disabled = false;
  }
}

/* ---------- Сцена ---------- */

const BOARD_W = () => state.lib.canvas.w;
const BOARD_H = () => state.lib.canvas.h;

// Подгоняет доску под размер области сцены
function fit() {
  if (!state.lib) return;
  $('scene').style.bottom = $('dock').offsetHeight + 44 + 'px';
  const r = $('scene').getBoundingClientRect();
  view.s = Math.max(0.2, Math.min((r.width - 40) / BOARD_W(), (r.height - 30) / BOARD_H()));
  for (const [it, el] of els) placeFrame(it, el);
  kick();
}

function aim() {
  const { px, py } = view;
  if (state.exploded) {
    view.tgt = { rx: 40 - (py - 0.5) * 6, ry: (px - 0.5) * 8, rz: -20 };
  } else if (view.dragging || reduceMotion) {
    view.tgt = { rx: 0, ry: 0, rz: 0 };
  } else {
    view.tgt = { rx: -(py - 0.5) * 8, ry: (px - 0.5) * 12, rz: 0 };
  }
  kick();
}

function kick() {
  if (!view.raf) view.raf = requestAnimationFrame(tick);
}

function tick() {
  view.raf = 0;
  const k = reduceMotion ? 1 : (view.dragging ? 0.45 : 0.1);
  let moving = false;
  for (const a of ['rx', 'ry', 'rz']) {
    const d = view.tgt[a] - view.cur[a];
    if (Math.abs(d) > 0.01) { view.cur[a] += d * k; moving = true; } else { view.cur[a] = view.tgt[a]; }
  }
  const { rx, ry, rz } = view.cur;
  $('board').style.transform = `scale(${view.s}) rotateX(${rx}deg) rotateY(${ry}deg) rotateZ(${rz}deg)`;
  const root = document.documentElement.style;
  root.setProperty('--mx', (view.px - 0.5).toFixed(3));
  root.setProperty('--my', (view.py - 0.5).toFixed(3));
  if (moving) view.raf = requestAnimationFrame(tick);
}

function toggleExplode() {
  state.exploded = !state.exploded;
  $('stage').classList.toggle('exploded', state.exploded);
  $('explode').setAttribute('aria-pressed', String(state.exploded));
  $('explode').textContent = state.exploded ? 'Собрать слои' : 'Раздвинуть слои';
  $('stage-hint').textContent = state.exploded
    ? 'Режим просмотра слоёв: нижний слой — первый в списке. Нажмите «Собрать слои», чтобы продолжить редактирование.'
    : 'Перетаскивайте объекты мышью, Delete удаляет выбранный. Всё, что выходит за границу холста, в файл не попадёт.';
  layers();
  syncDock();
  aim();
}

/* ---------- Элементы объектов ---------- */

function makeEl(it) {
  const d = dims(it.objectId);
  const el = document.createElement('div');
  el.className = 'item';
  el.style.width = d.w + 'px';
  el.style.height = d.h + 'px';

  const depth = document.createElement('div');
  depth.className = 'depth';
  const lift = document.createElement('div');
  lift.className = 'lift';
  lift.style.width = d.w + 'px';
  lift.style.height = d.h + 'px';
  const img = document.createElement('img');
  img.src = assetUrl(it.objectId, it.variant);
  img.alt = state.lib.objects.find((o) => o.id === it.objectId).name;
  img.draggable = false;
  const frame = document.createElement('div');
  frame.className = 'frame';
  lift.append(img, frame);
  depth.append(lift);
  el.append(depth);

  el.addEventListener('pointerdown', (e) => startDrag(e, it));
  return el;
}

function placeFrame(it, el) {
  el.style.setProperty('--bw', (2.5 / (it.scale * view.s)).toFixed(2) + 'px');
}

function place(it, el) {
  const d = dims(it.objectId);
  el.style.transform =
    `translate3d(${it.x}px, ${it.y}px, 0) rotate(${it.rotation}deg) scale(${it.scale}) translate(${-d.w / 2}px, ${-d.h / 2}px)`;
  placeFrame(it, el);
}

// Глубина слоя: в обычном виде почти плоско (виден лёгкий параллакс), в раздвинутом — большие зазоры
function layers() {
  const n = state.items.length;
  state.items.forEach((it, i) => {
    // доска лежит на z = 0, поэтому слои расходятся только вверх от неё
    const z = state.exploded ? (i + 1) * Math.min(42, 260 / n) : i * 3;
    els.get(it)?.firstChild.style.setProperty('--z', z + 'px');
  });
}

// Синхронизирует DOM со state.items: создаёт новые, убирает лишние, расставляет по порядку слоёв
function render(opts = {}) {
  const host = $('items');
  for (const [it, el] of els) {
    if (!state.items.includes(it)) { el.remove(); els.delete(it); }
  }
  const created = [];
  state.items.forEach((it, i) => {
    let el = els.get(it);
    if (!el) {
      el = makeEl(it);
      els.set(it, el);
      created.push(el);
    }
    place(it, el);
    if (host.children[i] !== el) host.insertBefore(el, host.children[i] || null);
    el.classList.toggle('selected', it === state.selected);
  });
  layers();
  syncDock();

  if (reduceMotion) return;
  if (opts.flyIn) created.forEach((el, i) => flyIn(el, state.items[[...host.children].indexOf(el)], i));
  if (opts.pop) popIn(els.get(opts.pop));
}

// Объекты влетают с краёв доски и садятся на место с лёгким отскоком
function flyIn(el, it, i) {
  const cx = it.x - BOARD_W() / 2;
  const cy = it.y - BOARD_H() / 2;
  const len = Math.hypot(cx, cy) || 1;
  const dist = 700 / it.scale;
  const dx = (cx / len) * dist || (i % 2 ? dist : -dist);
  const dy = (cy / len) * dist * 0.5 - 200 / it.scale;
  el.querySelector('.lift').animate([
    { opacity: 0, transform: `translate3d(${dx}px, ${dy}px, 260px) rotate(${i % 2 ? 24 : -24}deg) scale(.55)` },
    { opacity: 1, transform: 'translate3d(0, 0, 0) rotate(0deg) scale(1)' },
  ], { duration: 900, delay: 90 * i, easing: 'cubic-bezier(.34, 1.4, .64, 1)', fill: 'backwards' });
}

function popIn(el) {
  if (!el) return;
  el.querySelector('.lift').animate([
    { opacity: 0, transform: 'translateZ(120px) scale(.6)' },
    { opacity: 1, transform: 'translateZ(0) scale(1)' },
  ], { duration: 520, easing: 'cubic-bezier(.34, 1.56, .64, 1)', fill: 'backwards' });
}

function rebuild() {
  for (const el of els.values()) el.remove();
  els.clear();
  state.selected = null;
  render();
}

function refreshImages() {
  for (const [it, el] of els) el.querySelector('img').src = assetUrl(it.objectId, it.variant);
}

/* ---------- Перетаскивание ---------- */

function startDrag(e, it) {
  if (state.exploded) return;
  e.preventDefault();
  e.stopPropagation();
  const el = els.get(it);
  select(it);
  view.dragging = true;
  aim();
  el.classList.add('dragging');
  el.setPointerCapture(e.pointerId);

  const start = { x: e.clientX, y: e.clientY, ox: it.x, oy: it.y };
  const move = (ev) => {
    // доска во время перетаскивания выпрямляется, поэтому достаточно поделить на масштаб
    it.x = start.ox + (ev.clientX - start.x) / view.s;
    it.y = start.oy + (ev.clientY - start.y) / view.s;
    place(it, el);
  };
  const up = () => {
    el.removeEventListener('pointermove', move);
    el.removeEventListener('pointerup', up);
    el.removeEventListener('pointercancel', up);
    el.classList.remove('dragging');
    view.dragging = false;
    aim();
  };
  el.addEventListener('pointermove', move);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
}

/* ---------- Экспорт ---------- */

async function exportSvg() {
  if (!state.items.length) return setStatus('Холст пуст: добавьте объекты', true);
  const res = await fetch('/api/export', json({ size: state.size, palette: state.palette, items: state.items }));
  if (!res.ok) {
    const data = await res.json();
    return setStatus(data.error, true);
  }
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'composition.svg';
  a.click();
  URL.revokeObjectURL(a.href);
  setStatus('Файл composition.svg скачан');
}

function json(body) {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}
