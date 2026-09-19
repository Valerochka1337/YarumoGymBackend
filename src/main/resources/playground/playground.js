/* global CoachState */
'use strict';
const S = CoachState, $ = id => document.getElementById(id);
const storageKey = 'coach-playground-v1';
let state, catalog = [], token, settings, ready = false, paused = false, busy = false;
let serial = Promise.resolve(), authRefresh;
const streams = new Map();
const active = run => ['QUEUED', 'RUNNING'].includes(run.state);
const labels = { APPLIED: 'Применено', REJECTED: 'Отклонено', STALE: 'Устарело', QUEUED: 'В очереди', RUNNING: 'Coach думает', FAILED: 'Ошибка', CANCELLED: 'Отменено', SUPERSEDED: 'Заменено новой проверкой', SUCCEEDED: 'Готово' };
function el(tag, text, className) { const e = document.createElement(tag); if (text != null) e.textContent = text; if (className) e.className = className; return e; }
function error(e) { $('error').textContent = e.message || String(e); $('error').hidden = false; }
function clearError() { $('error').hidden = true; }
function save() { sessionStorage.setItem(storageKey, JSON.stringify(state)); }
function trace(text) { state.trace.push(`${new Date().toLocaleTimeString()}  ${text}`); state.trace = state.trace.slice(-250); save(); renderTrace(); }
function renderTrace() { $('trace').textContent = state.trace.join('\n'); $('trace').scrollTop = $('trace').scrollHeight; $('snapshot').textContent = JSON.stringify(state.snapshot, null, 2); }
async function request(path, method = 'GET', body, retry = true) {
  const headers = { 'X-Coach-Playground': '1' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (path.startsWith('/v1/')) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 && retry && path.startsWith('/v1/')) { await authenticate(); return request(path, method, body, false); }
  const value = await response.json().catch(() => ({}));
  if (!response.ok) throw Error(`${value.message || 'Запрос не выполнен'} (${value.code || response.status})`);
  return value;
}
async function authenticate() {
  if (!authRefresh) authRefresh = request('/dev/coach/api/bootstrap', 'POST').then(data => { token = data.accessToken; catalog = data.catalog; }).finally(() => { authRefresh = null; });
  return authRefresh;
}
function enqueue(action) {
  const result = serial.then(action);
  serial = result.catch(error);
  return result;
}
function persistSession(activeSession = true) {
  state.sequence++;
  state.pendingSession = {
    eventId: S.uuid(), sequence: state.sequence, contextVersion: state.contextVersion,
    snapshot: S.clone(state.snapshot), initiativeEnabled: state.initiative, active: activeSession,
  };
  save();
  return enqueue(flushSession);
}
async function flushSession() {
  const body = state.pendingSession;
  if (!body) return;
  const result = await request(`/v1/coach/sessions/${body.snapshot.workout_id}`, 'PUT', body);
  if (!result.accepted) throw Error('Состояние сервера новее этой вкладки. Начните новый сценарий.');
  if (state.pendingSession === body) { delete state.pendingSession; save(); }
}
async function flushReceipts() {
  while (state.receipts.length) {
    const receipt = state.receipts[0];
    await request(`/v1/coach/runs/${receipt.runId}/receipt`, 'POST', receipt.body);
    state.receipts.shift(); save();
    trace(`Подтверждение доставлено: ${receipt.body.status}`);
  }
}
function edit(mutator) {
  mutator(); S.changed(state); save(); renderWorkout(); renderMessages(); renderTrace();
  persistSession().catch(error);
}
function input(value, options, change) {
  const e = el('input'); Object.assign(e, { type: 'number', min: '0', ...options, value: value ?? '' });
  e.addEventListener('change', () => {
    if (!e.checkValidity()) { e.reportValidity(); return; }
    edit(() => change(e.value === '' ? null : Number(e.value)));
  });
  return e;
}
function renderWorkout() {
  $('revision').textContent = `Версия ${state.snapshot.revision}`;
  $('minutes').value = state.snapshot.available_time_minutes ?? '';
  $('rest').value = state.snapshot.future_rest_seconds ?? '';
  $('initiative').checked = state.initiative;
  const target = $('exercises'); target.replaceChildren();
  for (const exercise of state.snapshot.exercises) {
    const card = el('div', null, 'exercise'); card.append(el('h3', exercise.name));
    const head = el('div', null, 'set-grid set-header');
    for (const label of ['№', 'Вес, кг', 'Повторы', 'RIR', 'Ощущение', '✓']) head.append(el('span', label));
    card.append(head);
    for (const set of exercise.sets) {
      const row = el('div', null, `set-grid${set.completed ? ' completed' : ''}`);
      row.append(el('span', String(set.index + 1), 'muted'));
      for (const [field, title, step, max] of [['weight_kg','Вес','0.5','1000'],['reps','Повторы','1','1000'],['actual_rir','RIR','1','10']]) {
        const key = set.completed && field !== 'actual_rir' ? `actual_${field}` : field;
        const entry = input(set[key] ?? set[field], {step, max}, value => { set[key] = value; if (field === 'actual_rir') set.actual_rir_at_least_four = false; });
        entry.setAttribute('aria-label', `${exercise.name}, подход ${set.index + 1}: ${title}`); row.append(entry);
      }
      const feel = el('select'); feel.setAttribute('aria-label', `${exercise.name}, подход ${set.index + 1}: ощущение`);
      for (const [value, name] of [['','—'],['PLANNED_EFFORT','По плану'],['HARDER_THAN_EXPECTED','Тяжело'],['FATIGUE','Усталость'],['PAIN','Боль'],['TECHNIQUE_BREAKDOWN','Техника'],['INTERRUPTED','Прерван']]) { const option = el('option', name); option.value = value; feel.append(option); }
      feel.value = set.reported_feelings?.[0] ?? '';
      feel.onchange = () => edit(() => { set.reported_feelings = feel.value ? [feel.value] : []; }); row.append(feel);
      const done = el('input'); done.type = 'checkbox'; done.checked = set.completed;
      done.setAttribute('aria-label', `${exercise.name}, подход ${set.index + 1}: выполнен`);
      done.onchange = () => edit(() => {
        set.completed = done.checked; set.completed_at = done.checked ? Date.now() : null;
        if (done.checked) { set.actual_weight_kg ??= set.weight_kg; set.actual_reps ??= set.reps; }
      }); row.append(done); card.append(row);
    }
    target.append(card);
  }
  $('rest-state').textContent = state.snapshot.rest ? `Активный отдых: ${state.snapshot.rest.remaining_seconds} сек. (время в симуляторе не отсчитывается)` : '';
}
const fieldNames = { weight_kg: 'вес, кг', reps: 'повторы', actual_rir: 'RIR', duration_sec: 'длительность, сек', speed_kmh: 'скорость', incline_pct: 'наклон', set_type: 'тип' };
function operationText(op) {
  const exercise = state.snapshot.exercises.find(e => e.section_id === op.section_id || e.sets.some(s => s.set_id === op.set_id));
  const set = exercise?.sets.find(s => s.set_id === op.set_id);
  const prefix = exercise ? `${exercise.name}${set ? `, подход ${set.index + 1}` : ''}` : '';
  if (op.values) return `${prefix}: ${Object.entries(op.values).map(([key,value]) => `${fieldNames[key] || key} ${set?.[op.action === 'record_result' && ['weight_kg','reps','duration_sec','speed_kmh','incline_pct'].includes(key) ? `actual_${key}` : key] ?? '—'} → ${value ?? '—'}`).join('; ')}`;
  const names = { add_set:'Добавить подход', delete_set:'Удалить подход', remove_remaining:'Убрать оставшиеся подходы', add_exercise:'Добавить упражнение', replace_remaining:'Заменить оставшиеся подходы', move_exercise:'Переместить упражнение', swap_exercises:'Поменять упражнения местами', reorder_exercises:'Изменить порядок упражнений', set_completed:'Изменить отметку выполнения', report_feelings:'Записать ощущение', start_rest:'Начать отдых', extend_rest:'Продлить отдых', skip_rest:'Пропустить отдых', future_rest_duration:'Отдых между подходами', available_time:'Оставшееся время', excluded_exercises:'Исключить упражнения', undo_last:'Отменить последнее применение' };
  const target = catalog.find(e => e.exercise_id === op.exercise_id)?.name;
  return `${names[op.action] || op.action}${prefix ? `: ${prefix}` : ''}${target ? ` → ${target}` : ''}${op.seconds != null ? `: ${op.seconds} сек.` : ''}${op.minutes != null ? `: ${op.minutes} мин.` : ''}${op.position != null ? ` → позиция ${op.position + 1}` : ''}${op.completed != null ? `: ${op.completed ? 'выполнен' : 'не выполнен'}` : ''}${op.feelings ? `: ${op.feelings.join(', ')}` : ''}`;
}
function button(text, action, className) { const b = el('button', text, className); b.type = 'button'; b.onclick = () => Promise.resolve().then(action).catch(error); return b; }
function renderMessages() {
  const target = $('messages'), atBottom = target.scrollHeight - target.scrollTop - target.clientHeight < 80;
  target.replaceChildren();
  if (!state.runs.length && !state.messages.length) target.append(el('p','Отметьте подход или напишите тренеру.','empty'));
  for (const run of state.runs) {
    const message = state.messages.find(m => m.runId === run.runId);
    if (message) target.append(el('div', message.text, 'bubble user'));
    const noChange = run.result?.kind === 'no_change';
    const bubble = el('div', null, `bubble${noChange ? ' no-change' : ''}`);
    bubble.append(el('div', `${message ? 'Coach' : 'Инициативная проверка'} · ${labels[run.state] || run.state}${run.elapsed ? ` · ${run.elapsed} с` : ''}`, 'meta'));
    bubble.append(el('div', noChange ? 'План сохранён · без сообщения пользователю' : run.result?.text ?? run.draft ?? run.errorCode ?? 'Ожидание ответа…'));
    const proposal = run.result?.proposal;
    if (proposal) {
      const box = el('div',null,'proposal'), list = el('ul');
      for (const op of proposal.operations) list.append(el('li', operationText(op)));
      box.append(list);
      const details = el('details'); details.append(el('summary','Операции и версия'),el('pre',JSON.stringify(proposal,null,2))); box.append(details);
      if (run.receiptStatus || run.applicationStatus) box.append(el('p', labels[run.receiptStatus || run.applicationStatus], 'muted'));
      else if (S.stale(state, proposal)) box.append(el('p','Устарело: состояние тренировки изменилось или истёк срок.','muted'));
      else {
        const actions = el('div',null,'toolbar');
        const accept = button('Применить', () => decision(run,'APPLIED'), 'primary');
        try { S.apply(state.snapshot,proposal.operations,catalog,state.undo); } catch(e) { accept.disabled = true; box.append(el('p', e.message, 'muted')); }
        actions.append(accept,button('Отклонить', () => decision(run,'REJECTED'))); box.append(actions);
      }
      bubble.append(box);
    }
    if (run.result?.quickReplies?.length) {
      const quick = el('div',null,'quick');
      for (const text of run.result.quickReplies) quick.append(button(text,() => { $('message').value = text; $('chat-form').requestSubmit(); }));
      bubble.append(quick);
    }
    target.append(bubble);
  }
  if (atBottom) target.scrollTop = target.scrollHeight;
}
async function decision(run, status) {
  status = S.decide(state, run, status, catalog); save();
  trace(`Предложение: ${status}`); renderWorkout(); renderMessages();
  await enqueue(flushReceipts); await persistSession();
}
function mergeRun(incoming) {
  if (incoming.workoutId !== state.snapshot.workout_id) return;
  let run = state.runs.find(r => r.runId === incoming.runId);
  if (!run) { run = { runId: incoming.runId, cursor: 0, startedAt: Date.now() }; state.runs.push(run); }
  const previous = run.state;
  Object.assign(run, incoming);
  if (run.applicationStatus) run.receiptStatus = run.applicationStatus;
  if (previous !== run.state) {
    trace(`${run.runId.slice(0,8)} · ${run.state}${run.errorCode ? ` · ${run.errorCode}` : ''}`);
    if (!active(run)) run.elapsed = ((Date.now() - run.startedAt) / 1000).toFixed(1);
  }
  state.runs.sort((a,b) => (a.ordinal || Infinity) - (b.ordinal || Infinity));
  save(); renderMessages();
  if (active(run) && !paused) stream(run).catch(error);
}
async function stream(run) {
  if (streams.has(run.runId) || paused) return;
  const controller = new AbortController(); streams.set(run.runId, controller);
  try {
    const response = await fetch(`/v1/coach/runs/${run.runId}/events?after=${run.cursor || 0}`, { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
    if (response.status === 401) { await authenticate(); return; }
    if (!response.ok) throw Error(`Поток: HTTP ${response.status}`);
    const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = '';
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break;
      buffer += decoder.decode(chunk.value, {stream:true}); buffer = buffer.replaceAll('\r\n','\n');
      let end;
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0,end); buffer = buffer.slice(end+2);
        const data = block.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
        if (!data) continue;
        const event = JSON.parse(data);
        if (event.sequence <= (run.cursor || 0)) continue;
        run.cursor = event.sequence;
        if (event.type === 'text') { run.draft = event.text; renderMessages(); }
        if (event.type === 'progress') trace(`${run.runId.slice(0,8)} · ${event.stage}`);
        if (event.type === 'completed') mergeRun(event.run);
        save();
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') { trace('Поток прерван; повторное подключение через несколько секунд'); }
  } finally { streams.delete(run.runId); }
}
async function discover() {
  const workout = state.snapshot.workout_id;
  // A stable ordinal cursor discovers new automatic work; active runs recover after reload.
  let after = state.discoveryCursor || 0, rows;
  do {
    rows = await request(`/v1/coach/sessions/${workout}/runs?after=${after}`);
    if (workout !== state.snapshot.workout_id) return;
    rows.forEach(mergeRun);
    if (rows.length) after = Math.max(...rows.map(r => r.ordinal));
  } while (rows.length === 200);
  state.discoveryCursor = after; save();
  for (const run of [...state.runs].filter(active)) mergeRun(await request(`/v1/coach/runs/${run.runId}`));
}
async function sendPending() {
  if (!state.pendingRun) return;
  const body = state.pendingRun;
  const run = await request('/v1/coach/runs','POST',body);
  if (state.pendingRun === body) delete state.pendingRun;
  mergeRun(run); save();
}
async function submit(event) {
  event.preventDefault(); if (!ready || busy) return;
  const text = $('message').value.trim(); if (!text) return;
  busy = true; $('send').disabled = true; clearError();
  try {
    await enqueue(async () => { await flushSession(); await flushReceipts(); await sendPending(); });
    const requestId = S.uuid();
    state.pendingRun = { requestId, workoutId: state.snapshot.workout_id, contextVersion: state.contextVersion, snapshot: S.clone(state.snapshot), message: text, history: [], model: $('model').value || undefined };
    state.messages.push({ runId: requestId, text }); save();
    $('message').value = '';
    await enqueue(sendPending);
  } catch(e) { error(e); } finally { busy = false; $('send').disabled = !ready; }
}
async function loadModels() {
  const info = await request('/v1/ai/coach-models');
  const previous = $('model').value; $('model').replaceChildren();
  for (const model of info.models) { const option = el('option', model); option.value = model; $('model').append(option); }
  $('model').value = info.models.includes(previous) ? previous : info.defaultModel || '';
  ready = info.availability === 'AVAILABLE';
  $('send').disabled = !ready || busy;
  $('connection').textContent = ready ? 'Настоящая модель · настроена' : 'Настройте провайдера';
}
async function loadSettings() {
  settings = await request('/dev/coach/api/settings');
  $('base-url').value = settings.baseUrl;
  $('coach-model').value = settings.coachModel || settings.textModel;
  $('api-key').placeholder = settings.hasApiKey ? 'Сохранён · оставьте пустым, чтобы не менять' : 'Введите API-ключ';
  if (!settings.enabled || !settings.hasApiKey) $('settings-panel').hidden = false;
}
$('settings-toggle').onclick = () => { $('settings-panel').hidden = !$('settings-panel').hidden; };
$('settings-form').onsubmit = async event => {
  event.preventDefault(); clearError(); const b = event.submitter; b.disabled = true;
  try {
    const model = $('coach-model').value.trim();
    const key = $('api-key').value.trim();
    settings = await request('/dev/coach/api/settings','PUT',{ revision: settings.revision, enabled: true, baseUrl: $('base-url').value.trim(), textModel: settings.textModel || model, visionModel: settings.visionModel || model, coachModel: model, coachModels: [...new Set([model,...settings.coachModels])].slice(0,20), ...(key ? {apiKey:key} : {}) });
    $('api-key').value = ''; $('settings-status').textContent = 'Сохранено. Проверка связи — при первом сообщении.';
    await loadSettings(); await loadModels();
  } catch(e) { error(e); await loadSettings().catch(error); } finally { b.disabled = false; }
};
$('minutes').onchange = () => { if ($('minutes').reportValidity()) edit(() => { state.snapshot.available_time_minutes = $('minutes').value === '' ? null : Number($('minutes').value); }); };
$('rest').onchange = () => { if ($('rest').reportValidity()) edit(() => { state.snapshot.future_rest_seconds = $('rest').value === '' ? null : Number($('rest').value); }); };
$('initiative').onchange = () => edit(() => { state.initiative = $('initiative').checked; });
$('chat-form').onsubmit = submit;
$('message').onkeydown = event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chat-form').requestSubmit(); } };
$('reset').onclick = async () => {
  $('reset').disabled = true;
  try {
    await persistSession(false);
    await enqueue(async () => { await flushReceipts(); await sendPending(); await discover(); for (const run of state.runs.filter(active)) await request(`/v1/coach/runs/${run.runId}/cancel`,'POST'); });
    streams.forEach(c => c.abort());
    state = S.scenario(catalog,$('scenario').value); save(); renderWorkout(); renderMessages(); renderTrace();
    await persistSession(); clearError();
  } catch(e) { error(e); } finally { $('reset').disabled = false; }
};
$('stream-toggle').onclick = () => {
  paused = !paused; $('stream-toggle').textContent = paused ? 'Подключить поток' : 'Отключить поток';
  if (paused) streams.forEach(c => c.abort());
  else enqueue(discover).catch(error);
  trace(paused ? 'Доставка событий отключена; worker продолжает работу' : 'Возобновление с сохранённой позиции');
};
$('cancel').onclick = () => enqueue(async () => { await sendPending(); await discover(); for (const run of state.runs.filter(active)) mergeRun(await request(`/v1/coach/runs/${run.runId}/cancel`,'POST')); }).catch(error);
$('export').onclick = () => {
  const blob = new Blob([JSON.stringify({version:1, exportedAt:new Date().toISOString(), model:$('model').value, ...state},null,2)],{type:'application/json'});
  const url = URL.createObjectURL(blob), a = el('a'); a.href = url; a.download = `coach-session-${state.snapshot.workout_id}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
};
async function tick() {
  try {
    await enqueue(async () => {
      await flushSession(); await flushReceipts(); await sendPending();
      if (!paused) await discover();
      for (const run of state.runs) if (run.result?.proposal && !run.receiptStatus && !run.applicationStatus && S.stale(state, run.result.proposal)) {
        S.decide(state,run,'STALE',catalog); save(); renderMessages();
      }
    });
  } catch(e) { error(e); }
  setTimeout(tick,4000);
}
async function start() {
  await authenticate();
  const saved = sessionStorage.getItem(storageKey);
  try { state = saved ? JSON.parse(saved) : null; } catch { state = null; }
  if (!state?.snapshot || !Array.isArray(state.receipts)) state = S.scenario(catalog,'normal');
  renderWorkout(); renderMessages(); renderTrace();
  await loadSettings(); await loadModels();
  await persistSession(); await tick();
  // Fresh snapshots keep server-side time-based initiative eligible while the page is open.
  setInterval(() => { if (state.initiative) persistSession().catch(error); },30000);
}
start().catch(error);
