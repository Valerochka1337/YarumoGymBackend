const EQUIPMENT_LABELS = {"barbell": "Штанга", "dumbbells": "Гантели", "kettlebell": "Гиря", "ez_bar": "EZ-гриф", "plates": "Диски", "rack": "Стойка / силовая рама", "flat_bench": "Горизонтальная скамья", "incline_bench": "Наклонная скамья", "adjustable_bench": "Регулируемая скамья", "decline_bench": "Скамья с отрицательным наклоном", "upper_pulley": "Верхний блок", "lower_pulley": "Нижний блок", "crossover": "Кроссовер", "pullup_bar": "Турник", "dip_bars": "Брусья", "low_bar": "Низкая перекладина", "rings": "Кольца", "parallettes": "Паралетсы", "captains_chair": "Стойка для подъёма коленей", "support": "Опора", "nordic_bench": "Скамья для нордических сгибаний", "ankle_anchor": "Фиксатор стоп", "hyperextension_bench": "Гиперэкстензия", "reverse_hyper": "Тренажёр reverse hyper", "t_bar_machine": "T-гриф", "smith_machine": "Машина Смита", "hack_squat": "Гакк-тренажёр", "leg_press": "Жим ногами", "leg_extension": "Разгибатель ног", "leg_curl": "Сгибатель ног", "chest_press_machine": "Тренажёр для жима", "pec_deck": "Пек-дек", "shoulder_press_machine": "Тренажёр для жима плечами", "rear_delt_machine": "Тренажёр для задней дельты", "row_machine": "Тяговый тренажёр", "gravitron": "Гравитрон", "back_extension_machine": "Тренажёр для разгибаний спины", "hip_thrust_machine": "Тренажёр для хип-траста", "hip_abduction_machine": "Тренажёр для отведения бедра", "hip_adduction_machine": "Тренажёр для приведения бедра", "preacher_bench": "Скамья Скотта", "trap_bar": "Трэп-гриф", "rowing_machine": "Гребной тренажёр", "treadmill": "Беговая дорожка", "exercise_bike": "Велотренажёр", "elliptical": "Эллипс", "stair_climber": "Лестничный тренажёр", "stepper": "Степпер", "air_bike": "Air bike", "ski_erg": "SkiErg", "jump_rope": "Скакалка", "ab_wheel": "Колесо для пресса", "shrug_machine": "Тренажёр для шрагов", "box": "Тумба", "seated_calf_machine": "Тренажёр для икр сидя", "standing_calf_machine": "Тренажёр для икр стоя", "platform": "Платформа", "blocks": "Плинты", "board": "Доска для жима", "resistance_band": "Резиновая лента", "pool": "Бассейн"};
'use strict';

const $ = id => document.getElementById(id);
const names = {ai:'ИИ · Провайдер и модели',overview:'Обзор',users:'Пользователи',exercise:'Упражнения',gym:'Залы',routine:'Программы',workout:'Тренировки',measurement:'Замеры',schedule:'Расписание',audit:'Журнал изменений'};
Object.assign(names, {'standard:exercise':'Стандартный каталог · Упражнения','standard:gym':'Стандартные залы','standard:routine':'Стандартные шаблоны','standard:equipment':'Оборудование'});
const isStandard = () => view.startsWith('standard:');
const singular = {exercise:'Упражнение',gym:'Зал',routine:'Программа',workout:'Тренировка',measurement:'Замер',schedule:'Событие',equipment:'Оборудование'};
const groups = {CHEST:'Грудь',BACK:'Спина',LEGS:'Ноги',SHOULDERS:'Плечи',ARMS:'Руки',CORE:'Кор',CARDIO:'Кардио',FULL_BODY:'Всё тело'};
const types = {STRENGTH:'Силовое',TIMED:'На время',CARDIO:'Кардио'};
const muscles = {UPPER_CHEST:'Верх груди',LOWER_CHEST:'Низ груди',FRONT_DELTS:'Передние дельты',SIDE_DELTS:'Средние дельты',REAR_DELTS:'Задние дельты',ROTATOR_CUFF:'Ротаторная манжета',SERRATUS_ANTERIOR:'Передняя зубчатая',BICEPS:'Бицепс',TRICEPS:'Трицепс',FOREARMS:'Предплечья',ABS:'Пресс',OBLIQUES:'Косые мышцы живота',HIP_FLEXORS:'Сгибатели бедра',ADDUCTORS:'Приводящие мышцы',QUADS:'Квадрицепсы',TIBIALIS_ANTERIOR:'Передняя большеберцовая',CALVES:'Икры',HAMSTRINGS:'Задняя поверхность бедра',GLUTES:'Ягодицы',HIP_ABDUCTORS:'Отводящие мышцы',LOWER_BACK:'Поясница',LATS:'Широчайшие',UPPER_BACK:'Верх спины',TRAPS:'Трапеции',NECK:'Шея'};
const actions = {create:'Создание',edit:'Редактирование',revoke_sessions:'Отзыв сессий',grant_admin:'Назначение администратора',revoke_admin:'Отзыв прав администратора'};
let csrf = '', view = 'overview', owner = null, offset = 0, hasMore = false, requestVersion = 0, dialogVersion = 0, catalog = null, dialogBusy = false, dialogDirty = false;
const pageSize = 50;
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key,value] of Object.entries(attrs)) {
    if (key.startsWith('on')) node.addEventListener(key.slice(2),value);
    else if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (['value','checked','disabled','hidden','required'].includes(key)) node[key] = value;
    else node.setAttribute(key,value);
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined) node.append(child instanceof Node ? child : String(child));
  return node;
}
const button = (label, handler, cls = 'secondary') => el('button',{type:'button',class:cls,onclick:()=>guard(handler)},label);
const date = value => value ? new Intl.DateTimeFormat('ru-RU',{dateStyle:'medium',timeStyle:'short'}).format(new Date(/^\d+$/.test(String(value)) ? Number(value) : value)) : '—';
const shortId = value => String(value).slice(0,8);
function notice(message, error = false) { $('notice').hidden = !message; $('notice').textContent = message; $('notice').className = error ? 'error' : ''; }
async function guard(fn) { try { await fn(); } catch(e) { notice(e.message,true); } }
async function api(path, method = 'GET', body) {
  const headers = {'Content-Type':'application/json'};
  if (csrf) headers['X-CSRF-Token'] = csrf;
  let response;
  try { response = await fetch('/admin/api' + path,{method,headers,credentials:'same-origin',cache:'no-store',body:body === undefined ? undefined : JSON.stringify(body)}); }
  catch { throw new Error('Нет связи с сервером. Проверь соединение и повтори попытку.'); }
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : null; } catch { throw new Error('Сервер временно недоступен. Попробуй ещё раз.'); }
  if (!response.ok) {
    if (response.status === 401 && csrf) { clearSession(); showLogin('Сессия завершилась. Войди снова.'); }
    const error = new Error(data?.message || 'Не удалось выполнить запрос');
    error.status = response.status;
    throw error;
  }
  return data;
}
function clearSession() {
  csrf=''; owner=null; requestVersion++; dialogVersion++; dialogBusy=false; dialogDirty=false;
  $('dialog').close(); $('dialog-content').replaceChildren();
  $('ai-settings').replaceChildren(); $('overview').replaceChildren(); $('table-wrap').replaceChildren(); $('shell').hidden=true;
}
async function showLogin(message='') {
  $('login').hidden=false; $('login-error').textContent=message;

}
async function entered(session) {
  csrf=session.csrfToken; $('admin-email').textContent=session.email; $('login-password').value='';
  $('login').hidden=true; $('shell').hidden=false; catalog=null;
  await navigate('overview'); $('content').focus();
}
$('login-form').addEventListener('submit',async event=>{
  event.preventDefault(); const submit=event.submitter; submit.disabled=true; $('login-error').textContent='';
  try { await entered(await api('/login','POST',{username:$('login-username').value,password:$('login-password').value})); }
  catch(e) { $('login-error').textContent=e.message; } finally { submit.disabled=false; }
});
$('logout').onclick=()=>guard(async()=>{await api('/logout','POST',{}); clearSession(); await showLogin();});
$('navigation').addEventListener('click',event=>{const target=event.target.closest('[data-view]'); if(target) guard(()=>navigate(target.dataset.view));});
$('refresh').onclick=()=>guard(load);
$('clear-owner').onclick=()=>{owner=null;offset=0;guard(load);};
$('prev').onclick=()=>{offset=Math.max(0,offset-pageSize);guard(load);};
$('next').onclick=()=>{if(hasMore){offset+=pageSize;guard(load);}};
$('deleted').onchange=()=>{offset=0;guard(load);};
let searchTimer;
$('search').oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{offset=0;guard(load);},250);};
$('create').onclick=()=>guard(async()=>{if(isStandard()) return editStandard({kind:view.split(':')[1],id:view==='standard:equipment'?'':crypto.randomUUID(),revision:0,payload:null,standard:true}); if(owner) await editRecord({user_id:owner.id,email:owner.email,kind:view,id:crypto.randomUUID(),revision:0,payload:null}); else await chooseOwner();});
function closeDialog() {
  if(dialogBusy) return;
  if(dialogDirty && !window.confirm('Закрыть без сохранения изменений?')) return;
  dialogVersion++; dialogDirty=false; $('dialog').close();
}
$('dialog-close').onclick=closeDialog;
$('dialog').addEventListener('cancel',event=>{event.preventDefault();closeDialog();});
function openDialog(title,caption='') {
  dialogVersion++; dialogDirty=false; dialogBusy=false;
  $('dialog-title').textContent=title; $('dialog-caption').textContent=caption;
  $('dialog-content').replaceChildren(); $('dialog-close').disabled=false;
  if(!$('dialog').open) $('dialog').showModal();
  return dialogVersion;
}
async function navigate(next) { view=next;offset=0;$('search').value='';$('deleted').checked=false;notice('');await load(); }
async function load() {
  const version=++requestVersion;
  $('page-title').textContent=names[view];
  $('page-description').textContent=view==='ai' ? 'Подключение OpenAI-совместимого провайдера. Изменения применяются сразу после сохранения.' : isStandard() ? 'Общие объекты доступны всем, включая офлайн. Архив сохраняет содержимое и ссылки.' : view==='overview' ? 'Пользователи, данные и последние действия — всё в одном месте.' : view==='audit' ? 'Кто, что и зачем изменил. История сохраняется вместе с версиями записей.' : view==='users' ? 'Аккаунты, способы входа и данные пользователей.' : 'Данные пользователей приложения. Правки появятся на устройствах при синхронизации.';
  for(const nav of $('navigation').querySelectorAll('button')) { if(nav.dataset.view===view) nav.setAttribute('aria-current','page'); else nav.removeAttribute('aria-current'); }
  $('overview').hidden=view!=='overview'; $('listing').hidden=['overview','ai'].includes(view); $('ai-settings').hidden=view!=='ai'; if(view!=='ai') $('ai-settings').replaceChildren();
  $('owner-banner').hidden=isStandard() || !owner || ['users','overview','ai'].includes(view);
  $('owner-name').textContent=owner ? 'Данные: ' + owner.email : '';
  $('create').hidden=!isStandard() && !['gym','exercise'].includes(view);
  $('deleted-label').hidden=['overview','users','audit'].includes(view);
  $('search').disabled=view==='audit';
  $('search').placeholder=view==='users' ? 'Поиск по email или ID пользователя' : 'Поиск по названию, email или ID';
  $('refresh').disabled=true; $('table-wrap').setAttribute('aria-busy','true');
  try {
    if(view==='ai') { $('ai-settings').replaceChildren(); const data=await api('/ai-settings'); if(version===requestVersion) renderAiSettings(data); return; }
    if(isStandard()) { await loadStandard(version); return; }
    if(view==='overview') { const data=await api('/summary'); if(version===requestVersion) renderOverview(data); return; }
    $('table-wrap').replaceChildren(el('p',{class:'empty'},'Загружаем данные…'));
    const params=new URLSearchParams({offset:String(offset),limit:String(pageSize)});
    if(owner && view!=='users') params.set('userId',owner.id);
    if(view!=='audit') params.set('q',$('search').value);
    if(!['users','audit'].includes(view)) {params.set('kind',view);params.set('deleted',String($('deleted').checked));}
    const data=await api((view==='users'?'/users':view==='audit'?'/audit':'/records')+'?'+params);
    if(version!==requestVersion) return;
    hasMore=data.hasMore;
    renderListing(data.items);
    $('page-range').textContent=data.items.length ? (offset+1)+'–'+(offset+data.items.length) + (hasMore ? ' · есть ещё' : '') : 'Нет записей';
    $('prev').disabled=offset===0;$('next').disabled=!hasMore;
  } catch(e) {
    if(version===requestVersion) {notice(e.message,true);$('table-wrap').replaceChildren(el('p',{class:'empty'},'Не удалось загрузить данные. Нажми «Обновить».'));$('prev').disabled=true;$('next').disabled=true;}
  } finally { if(version===requestVersion){$('refresh').disabled=false;$('table-wrap').removeAttribute('aria-busy');} }
}
function table(headers,rows) {
  if(!rows.length) return el('div',{class:'empty'},'Здесь пока ничего нет. Попробуй изменить поиск или фильтры.');
  return el('table',{},el('thead',{},el('tr',{},headers.map(h=>el('th',{scope:'col'},h)))),el('tbody',{},rows.map(cells=>el('tr',{},cells.map(c=>el('td',{},c))))));
}
function recordLabel(r) { return r.name || (singular[r.kind] + (r.event_time ? ' · '+date(r.event_time) : '')); }
function renderListing(rows) {
  let result;
  if(view==='users') result=table(['Пользователь','Вход','Записи','Создан',''],rows.map(u=>[
    el('div',{},button(u.email,()=>showUser(u.id),'table-link'),el('span',{class:'cell-sub'},shortId(u.id)),u.is_admin?el('span',{class:'badge'},'ADMIN'):null),
    el('div',{},u.email_verified?'Подтверждён':'Email не подтверждён',el('span',{class:'cell-sub'},[u.google_connected?'Google':null,u.password_enabled?'Пароль':null].filter(Boolean).join(' · '))),
    u.record_count,date(u.created_at),button('Открыть →',()=>showUser(u.id),'table-link')]));
  else if(view==='audit') result=auditTable(rows);
  else result=table([singular[view],'Владелец',view==='exercise'?'Группа / тип':'Состояние','Версия',''],rows.map(r=>[
    el('div',{},button(recordLabel(r),()=>openRecord(r),'table-link'),el('span',{class:'cell-sub'},shortId(r.id))),
    button(r.email,()=>showUser(r.user_id),'table-link'),
    view==='exercise'?el('div',{},groups[r.muscle_group]||'—',el('span',{class:'cell-sub'},types[r.exercise_type]||'—')):el('span',{class:'pill'},r.deleted?'Удалено':'Активно'),
    String(r.revision),button(r.deleted?'Просмотреть →':['gym','exercise'].includes(view)?'Изменить →':'Просмотреть →',()=>openRecord(r),'table-link')]));
  $('table-wrap').replaceChildren(result);
}
function auditTable(rows) {
  return table(['Действие','Администратор','Причина','Дата',''],rows.map(a=>[
    el('div',{},el('span',{class:'cell-main'},actions[a.action]||a.action),el('span',{class:'cell-sub'},(singular[a.kind]||'Аккаунт')+' · '+shortId(a.record_id||a.user_id))),
    a.actor_email,a.reason,date(a.created_at),button('Подробности →',()=>showAudit(a.id),'table-link')]));
}
function renderOverview(data) {
  const counts=Object.fromEntries(data.records.map(r=>[r.kind,Number(r.count)]));
  const stats=el('div',{class:'stats'},[
    ['Пользователи',data.users,data.verifiedUsers+' с подтверждённым email'],
    ['Упражнения',counts.exercise||0,'Во всех аккаунтах'],
    ['Залы',counts.gym||0,'Личные каталоги пользователей'],
    ['Тренировки',counts.workout||0,data.activeSessions+' активных сессий'],
  ].map(([label,value,note])=>el('article',{class:'stat'},el('span',{class:'stat-label'},label),el('strong',{},String(value)),el('small',{},note))));
  const collections=el('section',{class:'panel'},el('div',{class:'panel-head'},el('h3',{},'Данные приложения')),el('p',{class:'hint'},'Выбери раздел для просмотра. Чтобы создать запись, сначала выбери пользователя.'),el('div',{class:'collection-grid'},Object.keys(singular).map(kind=>button([names[kind],el('span',{},String(counts[kind]||0))],()=>navigate(kind),'collection-button'))));
  const recent=el('section',{class:'panel'},el('div',{class:'panel-head'},el('h3',{},'Последние изменения'),button('Весь журнал →',()=>navigate('audit'),'quiet')),el('div',{class:'table-wrap'},auditTable(data.recentActions)));
  $('overview').replaceChildren(stats,collections,recent);
}
function details(entries) { return el('dl',{class:'detail-grid'},entries.map(([label,value])=>el('div',{},el('dt',{},label),el('dd',{},String(value??'—'))))); }
async function showUser(id) {
  const ticket=openDialog('Пользователь','АККАУНТ');
  $('dialog-content').textContent='Загружаем…';
  const user=await api('/users/'+id); if(ticket!==dialogVersion || !$('dialog').open) return;
  $('dialog-title').textContent=user.email;
  const links=el('div',{class:'button-row'},Object.keys(singular).map(kind=>button(names[kind],async()=>{owner={id,email:user.email};closeDialog();await navigate(kind);},'secondary')));
  const sessions=table(['Устройство','Создана'],user.sessions.map(s=>[s.deviceName,date(s.createdAt)]));
  const box=el('div',{},details([['Email',user.email],['Роль',user.is_admin?'Администратор':'Пользователь'],['Создан',date(user.created_at)],['Подтверждение email',user.email_verified?'Подтверждён':'Ожидается'],['Способы входа',[user.google_connected?'Google':null,user.password_enabled?'Пароль':null].filter(Boolean).join(', ')],['ID аккаунта',id]]),el('h3',{},'Данные пользователя'),links,el('h3',{},'Активные сессии'),el('div',{class:'table-wrap'},sessions),el('div',{class:'button-row'},button('Журнал действий',async()=>{owner={id,email:user.email};closeDialog();await navigate('audit');},'quiet'),button('Завершить все сессии',()=>revokeDialog(user),'danger')));
  $('dialog-content').replaceChildren(box);
}
function labeled(text,type='text',value='',attrs={}) {const input=el(type==='textarea'?'textarea':'input',{...(type==='textarea'?{}:{type}),value,...attrs});return {label:el('label',{},text,input),input};}
function selectField(text,options,value) {const input=el('select',{},Object.entries(options).map(([key,name])=>el('option',{value:key},name)));input.value=value;return {label:el('label',{},text,input),input};}
function checkField(text,value) {const input=el('input',{type:'checkbox',checked:!!value});return {label:el('label',{class:'check'},input,text),input};}
function revokeDialog(user) {
  openDialog('Завершить все сессии?',user.email);
  const reason=labeled('Причина','textarea','',{required:true,minlength:'3',maxlength:'500'});
  const error=el('p',{class:'error',role:'alert'});
  const form=el('form',{},el('p',{class:'muted'},'Пользователю придётся снова войти на всех устройствах. Его тренировки и другие данные останутся на месте.'),reason.label,error);
  const submit=el('button',{type:'submit',class:'danger'},'Завершить сессии');
  form.append(el('div',{class:'form-actions'},button('Отмена',closeDialog),submit));
  const operationId=crypto.randomUUID();
  form.onsubmit=async event=>{event.preventDefault();submit.disabled=true;dialogBusy=true;error.textContent='';
    try {await api('/users/'+user.id+'/revoke-sessions','POST',{operationId,reason:reason.input.value});dialogBusy=false;closeDialog();notice('Сессии пользователя завершены.');await load();}
    catch(e){error.textContent=e.message;}finally{submit.disabled=false;dialogBusy=false;}
  };
  $('dialog-content').replaceChildren(form);
}
async function chooseOwner() {
  const kind=view, ticket=openDialog('Для кого создать запись?','ВЫБОР ПОЛЬЗОВАТЕЛЯ');
  const search=labeled('Email пользователя','search'); const list=el('div',{class:'button-row'});
  $('dialog-content').append(search.label,list);
  let serial=0,timer;
  async function query() {
    const version=++serial; const data=await api('/users?q='+encodeURIComponent(search.input.value)+'&limit=20');
    if(ticket!==dialogVersion || version!==serial)return;
    list.replaceChildren(...data.items.map(user=>button(user.email,async()=>{owner={id:user.id,email:user.email};await editRecord({user_id:user.id,email:user.email,kind,id:crypto.randomUUID(),revision:0,payload:null});})));
    if(!data.items.length)list.textContent='Пользователи не найдены';
  }
  search.input.oninput=()=>{clearTimeout(timer);timer=setTimeout(()=>guard(query),250);};
  await query();search.input.focus();
}
async function openRecord(row) {
  const ticket=openDialog('Загрузка…',singular[row.kind]);
  const record=await api('/users/'+row.user_id+'/records/'+row.kind+'/'+row.id);
  if(ticket!==dialogVersion || !$('dialog').open)return;
  if(!record.deleted && ['exercise','gym'].includes(record.kind))return editRecord(record);
  $('dialog-title').textContent=record.payload?.name || singular[record.kind];
  $('dialog-content').append(details([['Владелец',record.email],['Версия',record.revision],['ID записи',record.id],['Состояние',record.deleted?'Удалена':'Активна']]));
  if(record.payload) {
    const p=record.payload;
    const facts=[];
    if(p.note)facts.push(['Заметка',p.note]);
    if(p.startedAt)facts.push(['Начало',date(p.startedAt)],['Завершение',date(p.finishedAt)]);
    if(p.measuredAt)facts.push(['Дата замера',date(p.measuredAt)],['Вес, кг',p.weightKg],['Жир, %',p.bodyFatPercentage]);
    if(p.dateTimeMillis)facts.push(['Запланировано',date(p.dateTimeMillis)]);
    if(p.exercises)facts.push(['Упражнений',p.exercises.length],['Подходов',p.exercises.reduce((n,x)=>n+(x.sets||x.plannedSets||[]).length,0)]);
    $('dialog-content').append(details(facts),el('details',{},el('summary',{},'Все поля записи'),el('pre',{class:'json'},JSON.stringify(p,null,2))));
  } else $('dialog-content').append(el('p',{class:'muted'},'Содержимое удалённой записи не хранится в текущем снимке.'));
}
function picker(title,options,selected) {
  const values=new Set(selected||[]),fieldset=el('fieldset',{class:'form-section'},el('legend',{},title));
  const search=labeled('Найти в списке','search','',{placeholder:'Начни вводить название'}),counter=el('p',{class:'hint'}),choices=el('div',{class:'choices'});
  function draw() {
    const filtered=options.filter(x=>x.name.toLocaleLowerCase('ru').includes(search.input.value.toLocaleLowerCase('ru')));
    choices.replaceChildren(...filtered.slice(0,100).map(option=>{
      const box=checkField(option.name,values.has(option.id));
      box.input.onchange=()=>{box.input.checked?values.add(option.id):values.delete(option.id);dialogDirty=true;count();};
      return box.label;
    }));
    if(!filtered.length)choices.append(el('p',{class:'hint'},'Ничего не найдено'));
    count();if(filtered.length>100)counter.append(' · Показаны первые 100, уточни поиск');
  }
  function count(){counter.textContent='Выбрано: '+values.size;}
  search.input.oninput=draw;fieldset.append(search.label,counter,choices);draw();
  return {node:fieldset,values:()=>[...values]};
}
async function editRecord(record) {
  const ticket=openDialog((record.payload?'Изменить: ':'Создать: ')+singular[record.kind].toLowerCase(),record.standard?'Стандартный каталог':record.email);
  $('dialog-content').textContent='Подготавливаем форму…';
  if(!catalog)catalog=await api('/catalog');
  Object.assign(EQUIPMENT_LABELS,catalog.equipmentLabels||{});
  const shared=record.standard?await publicCatalog():null;
  if(shared) {catalog={equipment:shared.equipment.filter(x=>!x.archived || record.payload?.equipmentIds?.includes(x.id)).map(x=>x.id),muscles:Object.keys(muscles)};for(const e of shared.equipment)EQUIPMENT_LABELS[e.id]=e.payload.name;}
  const exerciseOptions=record.kind==='gym'?(shared?shared.records.filter(x=>x.kind==='exercise' && (!x.archived || record.payload?.exerciseIds?.includes(x.id))).map(x=>({id:x.id,name:x.payload.name})):await api('/users/'+record.user_id+'/exercise-options')):[];
  if(ticket!==dialogVersion || !$('dialog').open)return;
  const original=record.payload || (record.kind==='gym'?{name:'',inventoryConfigured:true,exerciseIds:[],equipmentIds:[]}:{name:'',muscleGroup:'FULL_BODY',type:'STRENGTH',isCustom:true,needsMuscleMapReview:false,equipmentRequirementState:'UNKNOWN',muscles:[],equipmentIds:[]});
  const form=el('form'),fields=el('fieldset',{class:'form-section'});// All controls share a disableable fieldset.
  const name=labeled('Название','text',original.name,{required:true,maxlength:'200'});
  const reason=labeled('Причина изменения','textarea','',{required:true,minlength:'3',maxlength:'500',placeholder:'Например: исправлено название или обновлено оборудование'});
  const grid=el('div',{class:'form-grid'});name.label.className='wide';grid.append(name.label);
  const eq=picker(record.kind==='gym'?'Оборудование в зале':'Необходимое оборудование',catalog.equipment.map(id=>({id,name:EQUIPMENT_LABELS[id]||id})),original.equipmentIds);
  let group,type,custom,review,state,configured,exercisePicker,muscleInputs;
  if(record.kind==='exercise') {
    group=selectField('Группа мышц',groups,original.muscleGroup);type=selectField('Тип упражнения',types,original.type);
    state=selectField('Требования к оборудованию',{UNKNOWN:'Нужно уточнить',KNOWN:'Известны'},original.equipmentRequirementState);
    custom=checkField('Пользовательское упражнение',original.isCustom);review=checkField('Карта мышц требует проверки',original.needsMuscleMapReview);
    grid.append(group.label,type.label,state.label,el('div',{},custom.label,review.label));
    const muscleSection=el('fieldset',{class:'form-section'},el('legend',{},'Участие мышц'));
    const rows=el('div',{class:'choices'});
    muscleInputs=catalog.muscles.map(id=>{
      const select=selectField(muscles[id]||id,{'':'Не участвует','0':'Стабилизатор','50':'Вторичная','100':'Основная'},String(original.muscles.find(x=>x.muscle===id)?.contribution ?? ''));
      select.label.className='muscle-row';rows.append(select.label);return {id,input:select.input};
    });
    muscleSection.append(rows);
    fields.append(grid,eq.node,muscleSection);
  } else {
    configured=selectField('Доступность упражнений',{true:'По оборудованию зала',false:'По списку упражнений'},String(original.inventoryConfigured));
    grid.append(configured.label);
    exercisePicker=picker('Доступные упражнения',exerciseOptions,original.exerciseIds);
    const hint=el('p',{class:'hint'},'При изменении оборудования сервер проверит, что зал по-прежнему подходит для связанных программ и активных тренировок.');
    function mode(){exercisePicker.node.hidden=configured.input.value==='true';eq.node.hidden=configured.input.value!=='true';}
    configured.input.onchange=mode;mode();
    fields.append(grid,hint,eq.node,exercisePicker.node);
  }
  fields.append(reason.label);form.append(el('p',{class:'hint'},(record.standard?'Стандартный каталог':'Владелец: '+record.email)+' · '+(record.revision?'Версия '+record.revision:'Новая запись')),fields);
  const error=el('p',{class:'error',role:'alert'}),reload=button('Открыть актуальную версию',async()=>{if(window.confirm('Загрузить запись с сервера? Несохранённые поля формы будут потеряны.'))await (record.standard?editStandard(await api('/standard/'+record.kind+'/'+record.id)):openRecord(record));},'secondary');
  reload.hidden=true;
  const save=el('button',{type:'submit',class:'primary'},'Сохранить изменения');
  form.append(error,reload,el('div',{class:'form-actions'},button('Отмена',closeDialog),save));
  let pending=null;
  form.addEventListener('input',()=>{dialogDirty=true;});
  form.onsubmit=async event=>{
    event.preventDefault();error.textContent='';reload.hidden=true;
    const payload={...original,name:name.input.value.trim(),equipmentIds:eq.values()};
    if(record.kind==='exercise')Object.assign(payload,{muscleGroup:group.input.value,type:type.input.value,isCustom:custom.input.checked,needsMuscleMapReview:review.input.checked,equipmentRequirementState:state.input.value,muscles:muscleInputs.filter(x=>x.input.value!=='').map(x=>({muscle:x.id,contribution:Number(x.input.value)}))});
    else Object.assign(payload,{inventoryConfigured:configured.input.value==='true',exerciseIds:exercisePicker.values()});
    const signature=JSON.stringify({payload,reason:reason.input.value});
    if(pending?.signature!==signature)pending={signature,body:{operationId:crypto.randomUUID(),baseRevision:record.revision,payload:{...payload,updatedAt:Date.now()},reason:reason.input.value}};
    dialogBusy=true;fields.disabled=true;save.disabled=true;$('dialog-close').disabled=true;
    try {
      await api(record.standard?'/standard/'+record.kind+'/'+record.id:'/users/'+record.user_id+'/records/'+record.kind+'/'+record.id,'PUT',pending.body);
      dialogBusy=false;dialogDirty=false;closeDialog();notice('Изменения сохранены. Приложение получит их при следующей синхронизации.');await load();
    } catch(e) {error.textContent=e.message;if(e.status===409)reload.hidden=false;}
    finally {dialogBusy=false;fields.disabled=false;save.disabled=false;$('dialog-close').disabled=false;}
  };
  $('dialog-content').replaceChildren(form);name.input.focus();
}
async function showAudit(id) {
  const ticket=openDialog('Изменение','ЖУРНАЛ');
  const entry=await api('/audit/'+id);if(ticket!==dialogVersion || !$('dialog').open)return;
  $('dialog-title').textContent=actions[entry.action]||entry.action;
  $('dialog-content').append(details([['Администратор',entry.actor_email],['Дата',date(entry.created_at)],['Причина',entry.reason],['Владелец',entry.user_id],['Запись',entry.record_id],['Версия',entry.revision]]));
  for(const [key,label] of [['before_payload','До изменения'],['after_payload','После изменения']])$('dialog-content').append(el('h3',{},label),el('pre',{class:'json'},entry[key]?JSON.stringify(entry[key],null,2):'Нет содержимого'));
}
(async()=>{try {await entered(await api('/session'));}catch {await showLogin();}})();

async function publicCatalog() {
  const response=await fetch('/v1/catalog',{cache:'no-cache'});
  if(!response.ok)throw new Error('Не удалось загрузить стандартный каталог');
  return JSON.parse(await response.text());
}
async function loadStandard(version) {
  const kind=view.split(':')[1];
  const params=new URLSearchParams({kind,q:$('search').value,archived:String($('deleted').checked),offset:String(offset),limit:String(pageSize)});
  const data=await api('/standard?'+params);
  if(version!==requestVersion)return;
  hasMore=data.hasMore;
  $('table-wrap').replaceChildren(table(['Название','Состояние','Версия','Действия'],data.items.map(r=>[
    r.payload.name,r.archived?'Архив':'Активно',String(r.revision),el('div',{},button('Изменить',()=>editStandard(r)),button(r.archived?'Вернуть из архива':'В архив',()=>archiveStandard(r)))
  ])));
  $('page-range').textContent=data.items.length?`${offset+1}–${offset+data.items.length}`:'Нет записей';
  $('prev').disabled=offset===0;$('next').disabled=!hasMore;
}
async function archiveStandard(record) {
  openDialog(record.archived?'Вернуть из архива':'Архивировать',record.payload.name);
  const reason=labeled('Причина изменения','textarea','',{required:true,minlength:'3',maxlength:'500'});
  const form=el('form',{},reason.label),error=el('p',{role:'alert',class:'error'}),save=el('button',{type:'submit',class:'primary'},'Подтвердить');
  let pending=null;
  form.append(error,save);
  form.onsubmit=async event=>{
    event.preventDefault();
    if(!pending || pending.reason!==reason.input.value)pending={operationId:crypto.randomUUID(),baseRevision:record.revision,reason:reason.input.value,archived:!record.archived};
    dialogBusy=true;save.disabled=true;
    try {await api(`/standard/${record.kind}/${record.id}/archive`,'POST',pending);dialogBusy=false;dialogDirty=false;closeDialog();await load();}
    catch(e){error.textContent=e.message;}finally{dialogBusy=false;save.disabled=false;}
  };
  $('dialog-content').append(form);
}
async function editStandard(record) {
  record={...record,standard:true};
  if(['exercise','gym'].includes(record.kind))return editRecord(record);
  const ticket=openDialog((record.payload?'Изменить: ':'Создать: ')+singular[record.kind],'Стандартный каталог');
  const shared=await publicCatalog();if(ticket!==dialogVersion)return;
  const original=record.payload||{name:'',note:'',exercises:[],gymIds:[],group:'',synonyms:[],provides:[]};
  const form=el('form'),fields=el('fieldset',{class:'form-section'});
  const name=labeled('Название','text',original.name,{required:true,maxlength:'200'}),reason=labeled('Причина изменения','textarea','',{required:true,minlength:'3',maxlength:'500'});
  fields.append(name.label);
  let read;
  if(record.kind==='equipment') {
    const id=labeled('Постоянный ID','text',record.id,{required:true,pattern:'[a-z][a-z0-9_]{0,99}'});id.input.disabled=!!record.payload;
    const group=labeled('Группа','text',original.group,{required:true,maxlength:'200'}),synonyms=labeled('Синонимы, через запятую','text',original.synonyms.join(', '));
    const provides=picker('Предоставляет оборудование',shared.equipment.map(x=>({id:x.id,name:x.payload.name})),original.provides);
    fields.append(id.label,group.label,synonyms.label,provides.node);
    read=()=>({id:id.input.value,payload:{name:name.input.value.trim(),group:group.input.value.trim(),synonyms:[...new Set(synonyms.input.value.split(',').map(x=>x.trim()).filter(Boolean))],provides:[...new Set([id.input.value,...provides.values()])]}});
  } else {
    const note=labeled('Заметка','textarea',original.note,{maxlength:'10000'}),gyms=picker('Залы',shared.records.filter(x=>x.kind==='gym' && (!x.archived || original.gymIds.includes(x.id))).map(x=>({id:x.id,name:x.payload.name})),original.gymIds);
    const rows=el('div'),entries=[];
    const options=Object.fromEntries(shared.records.filter(x=>x.kind==='exercise' && (!x.archived || original.exercises.some(e=>e.exerciseId===x.id))).map(x=>[x.id,x.payload.name]));
    function addExercise(value={}) {
      const box=el('fieldset',{class:'form-section'}),exercise=selectField('Упражнение',options,value.exerciseId||Object.keys(options)[0]),rest=labeled('Отдых, секунды','number',value.restSeconds??'',{min:'0',max:'86400'}),sets=el('div'),setEntries=[];
      const entry={box,exercise,rest,setEntries};entries.push(entry);
      function addSet(value={}) {
        const row=el('div',{class:'form-grid'}),inputs={};
        for(const [key,label] of Object.entries({weightKg:'Вес, кг',reps:'Повторы',durationSec:'Время, сек',speedKmh:'Скорость, км/ч',inclinePct:'Наклон, %'})){
          const field=labeled(label,'number',value[key]??'',{min:key==='inclinePct'?'-100':'0',step:['reps','durationSec'].includes(key)?'1':'any'});inputs[key]=field.input;row.append(field.label);
        }
        const item={row,inputs};setEntries.push(item);
        row.append(button('Удалить подход',()=>{setEntries.splice(setEntries.indexOf(item),1);row.remove();dialogDirty=true;}));sets.append(row);
      }
      box.append(exercise.label,rest.label,sets,button('Добавить подход',()=>{addSet();dialogDirty=true;}),button('Выше',()=>{const i=entries.indexOf(entry);if(i>0){[entries[i-1],entries[i]]=[entry,entries[i-1]];rows.replaceChildren(...entries.map(x=>x.box));dialogDirty=true;}}),button('Удалить упражнение',()=>{entries.splice(entries.indexOf(entry),1);box.remove();dialogDirty=true;}));
      (value.plannedSets||[]).forEach(addSet);rows.append(box);
    }
    original.exercises.forEach(addExercise);
    fields.append(note.label,gyms.node,rows,button('Добавить упражнение',()=>{addExercise();dialogDirty=true;}));
    read=()=>({id:record.id,payload:{name:name.input.value.trim(),note:note.input.value,gymIds:gyms.values(),exercises:entries.map((e,position)=>({exerciseId:e.exercise.input.value,position,restSeconds:e.rest.input.value===''?null:Number(e.rest.input.value),plannedSets:e.setEntries.map(s=>Object.fromEntries(Object.entries(s.inputs).map(([k,v])=>[k,v.value===''?null:Number(v.value)])))}))}});
  }
  fields.append(reason.label);form.append(fields);
  const error=el('p',{class:'error',role:'alert'}),save=el('button',{type:'submit',class:'primary'},'Сохранить изменения');let pending=null;
  form.append(error,save);form.oninput=()=>{dialogDirty=true;};
  form.onsubmit=async event=>{
    event.preventDefault();const next=read(),signature=JSON.stringify([next,reason.input.value]);
    if(pending?.signature!==signature)pending={signature,id:next.id,body:{operationId:crypto.randomUUID(),baseRevision:record.revision,reason:reason.input.value,payload:{...next.payload,...(record.kind==='routine'?{updatedAt:Date.now()}:{})}}};
    dialogBusy=true;fields.disabled=true;save.disabled=true;
    try{await api(`/standard/${record.kind}/${pending.id}`,'PUT',pending.body);dialogBusy=false;dialogDirty=false;closeDialog();await load();}
    catch(e){error.textContent=e.message;}finally{dialogBusy=false;fields.disabled=false;save.disabled=false;}
  };
  $('dialog-content').append(form);name.input.focus();
}

function renderAiSettings(data) {
  const form=el('form',{class:'login-card',id:'ai-settings-form'});
  const enabled=el('input',{type:'checkbox',checked:data.enabled,id:'ai-enabled'});
  const fields={};
  form.append(el('label',{class:'check'},enabled,'Включить ИИ'));
  for(const [name,label,value] of [
    ['baseUrl','URL провайдера',data.baseUrl],['textModel','Модель текста',data.textModel],
    ['visionModel','Модель изображений',data.visionModel],['coachModel','Модель тренера (пусто — модель текста)',data.coachModel],
    ['coachModels','Дополнительные модели тренера через запятую',data.coachModels.join(', ')],
  ]) {
    fields[name]=el('input',{id:'ai-'+name,type:name==='baseUrl'?'url':'text',value,maxlength:name==='baseUrl'?2048:name==='coachModels'?4019:200,autocomplete:'off',spellcheck:'false'});
    form.append(el('label',{},label,fields[name]));
  }
  fields.coachPrompt=el('textarea',{id:'ai-coachPrompt',rows:18,maxlength:16000,required:true,spellcheck:'false'});
  fields.coachPrompt.value=data.coachPrompt || '';
  form.append(el('label',{},'Системный промпт Live Coach',fields.coachPrompt),el('p',{class:'muted'},'Изменения промпта начнут действовать в течение 5 минут.'));
  const key=el('input',{id:'ai-apiKey',type:'password',maxlength:16384,autocomplete:'new-password',spellcheck:'false',disabled:!data.encryptionAvailable});
  const clear=el('input',{type:'checkbox',id:'ai-clearApiKey'});
  form.append(el('label',{},'Новый API-ключ',key),el('p',{class:'muted'},data.hasApiKey?'Ключ сохранён. Оставьте поле пустым, чтобы сохранить его.':'API-ключ ещё не задан.'),el('label',{class:'check'},clear,'Удалить сохранённый API-ключ'));
  if(!data.encryptionAvailable) form.append(el('p',{class:'error'},'На сервере не настроен ключ шифрования. Обратитесь к администратору сервера.'));
  const submit=el('button',{type:'submit',class:'primary'},'Сохранить настройки');
  const error=el('p',{class:'error',role:'alert'});
  form.append(submit,error);
  form.addEventListener('submit',async event=>{
    event.preventDefault(); submit.disabled=true; error.textContent='';
    const version=requestVersion;
    try {
      const body={revision:data.revision,enabled:enabled.checked,clearApiKey:clear.checked};
      for(const [name,input] of Object.entries(fields)) body[name]=name==='coachModels'?input.value.split(',').map(x=>x.trim()).filter(Boolean):name==='coachPrompt'?input.value:input.value.trim();
      if(key.value) body.apiKey=key.value;
      const result=await api('/ai-settings','PUT',body);
      key.value='';
      if(version===requestVersion) {renderAiSettings(result);notice('Настройки ИИ сохранены');}
    } catch(e) {if(version===requestVersion) error.textContent=e.message;}
    finally {submit.disabled=false;}
  });
  $('ai-settings').replaceChildren(form);
}
