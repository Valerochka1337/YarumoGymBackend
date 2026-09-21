const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {JSDOM, VirtualConsole} = require('jsdom');
const html=readFileSync('src/main/resources/static/admin/index.html','utf8');
const script=readFileSync('src/main/resources/static/admin/admin.js','utf8');
const owner='11111111-1111-4111-8111-111111111111',id='22222222-2222-4222-8222-222222222222';
const payload={name:'Присед',muscleGroup:'LEGS',type:'STRENGTH',isCustom:true,updatedAt:1,needsMuscleMapReview:false,equipmentRequirementState:'KNOWN',muscles:[{muscle:'ABS',contribution:0},{muscle:'QUADS',contribution:100}],equipmentIds:['barbell']};
const record={user_id:owner,email:'athlete@example.test',kind:'exercise',id,revision:7,payload};
const summary={users:1,verifiedUsers:1,activeSessions:1,records:[{kind:'exercise',count:1}],recentActions:[]};
const tick=()=>new Promise(resolve=>setTimeout(resolve,10));
async function until(predicate) {for(let i=0;i<100;i++){if(predicate())return;await tick();}assert.fail('Expected UI state was not reached');}
async function setup(overrides={}, loggedIn=true) {
  const errors=[],requests=[];
  const virtualConsole=new VirtualConsole();virtualConsole.on('jsdomError',e=>errors.push(e));
  const dom=new JSDOM(html,{url:'https://admin.test/admin/',runScripts:'outside-only',virtualConsole,pretendToBeVisual:true});
  const w=dom.window;
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  w.HTMLDialogElement.prototype.close=function(){this.open=false;};
  w.confirm=()=>true;
  w.structuredClone=structuredClone;
  w.fetch=async(path,options={})=>{
    const url=new URL(path,'https://admin.test'),method=options.method||'GET',body=options.body?JSON.parse(options.body):null;
    requests.push({path:url.pathname,query:url.searchParams,method,body,headers:options.headers});
    let status=200,data;
    const custom=overrides[method+' '+url.pathname];
    if(custom){const result=await custom(body,requests.at(-1));status=result.status||200;data=result.data;}
    else if(url.pathname.endsWith('/session'))data={email:'admin@example.test',userId:owner,csrfToken:'test-csrf'};
    else if(url.pathname.endsWith('/summary'))data=summary;
    else if(url.pathname.endsWith('/catalog'))data={equipment:['barbell','dumbbells'],muscles:['ABS','QUADS']};
    else if(url.pathname.endsWith('/exercise-options'))data=[{id,name:'Присед'}];
    else if(url.pathname==='/admin/api/records')data={items:[{...record,name:payload.name,muscle_group:'LEGS',exercise_type:'STRENGTH'}],hasMore:false,offset:0};
    else if(url.pathname==='/admin/api/users')data={items:[{id:owner,email:'athlete@example.test',email_verified:true,record_count:1}],hasMore:false,offset:0};
    else if(url.pathname.endsWith('/records/exercise/'+id) && method==='GET')data=record;
    else if(url.pathname.endsWith('/records/exercise/'+id) && method==='PUT')data={revision:8};
    else throw new Error('Unexpected API request: '+method+' '+url.pathname);
    return {ok:status>=200&&status<300,status,text:async()=>JSON.stringify(data)};
  };
  w.eval(script);
  if(loggedIn) {
    await until(()=>!w.document.getElementById('shell').hidden);
    await until(()=>w.document.querySelectorAll('.stat').length===4);
  } else await until(()=>requests.length>0);
  const click=label=>{const found=[...w.document.querySelectorAll('button')].find(x=>x.textContent.trim()===label&&!x.hidden);assert.ok(found,'Button '+label);found.click();};
  const field=label=>{const found=[...w.document.querySelectorAll('#dialog-content label')].find(x=>x.firstChild?.textContent===label);assert.ok(found,'Field '+label);return found.querySelector('input,select,textarea');};
  async function editor() {w.document.querySelector('[data-view=exercise]').click();await until(()=>w.document.querySelector('.table-link'));click('Изменить →');await until(()=>w.document.querySelector('#dialog-content form'));}
  function submit() {const form=w.document.querySelector('#dialog-content form');form.dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));}
  return {dom,w,requests,errors,click,field,editor,submit};
}

test('exercise editor preserves stabilizers and sends revision with CSRF',async t=>{
  const ui=await setup();t.after(()=>ui.dom.window.close());await ui.editor();
  assert.equal(ui.field('Пресс').value,'0');
  ui.field('Название').value='Присед со штангой';
  ui.field('Причина изменения').value='Уточнено название';
  ui.submit();await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const request=ui.requests.find(r=>r.method==='PUT');
  assert.equal(request.headers['X-CSRF-Token'],'test-csrf');
  assert.equal(request.body.baseRevision,7);
  assert.equal(request.body.payload.name,'Присед со штангой');
  assert.deepEqual(request.body.payload.muscles,[{muscle:'ABS',contribution:0},{muscle:'QUADS',contribution:100}]);
  await until(()=>!ui.w.document.getElementById('dialog').open);
  assert.equal(ui.errors.length,0);
  assert.equal(ui.w.localStorage.length,0);
  await until(()=>!ui.w.document.getElementById('refresh').disabled);
});

test('revision conflict preserves unsaved form and offers explicit reload',async t=>{
  const ui=await setup({['PUT /admin/api/users/'+owner+'/records/exercise/'+id]:()=>({status:409,data:{message:'Запись изменилась'}})});
  t.after(()=>ui.dom.window.close());await ui.editor();ui.field('Название').value='Моя правка';ui.field('Причина изменения').value='Исправление';
  ui.submit();await until(()=>ui.w.document.querySelector('#dialog-content .error').textContent);
  assert.equal(ui.field('Название').value,'Моя правка');
  assert.equal(ui.w.document.getElementById('dialog').open,true);
  assert.ok([...ui.w.document.querySelectorAll('button')].some(x=>x.textContent==='Открыть актуальную версию'&&!x.hidden));
});

test('retry after lost response reuses exact operation and payload',async t=>{
  let calls=0;
  const ui=await setup({['PUT /admin/api/users/'+owner+'/records/exercise/'+id]:()=>{if(++calls===1)throw new Error('connection lost');return {data:{revision:8}};}});
  t.after(()=>ui.dom.window.close());await ui.editor();ui.field('Причина изменения').value='Проверка повторного сохранения';
  ui.submit();await until(()=>ui.w.document.querySelector('#dialog-content .error').textContent);
  ui.submit();await until(()=>ui.requests.filter(r=>r.method==='PUT').length===2);
  const writes=ui.requests.filter(r=>r.method==='PUT');assert.deepEqual(writes[0].body,writes[1].body);
  await until(()=>!ui.w.document.getElementById('dialog').open&&!ui.w.document.getElementById('refresh').disabled);
});

test('user supplied markup is displayed as text and never becomes an element',async t=>{
  const attack='<img src=x onerror="window.compromised=true">';
  const ui=await setup({'GET /admin/api/records':()=>({data:{items:[{...record,name:attack}],hasMore:false,offset:0}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view=exercise]').click();
  await until(()=>ui.w.document.getElementById('table-wrap').textContent.includes(attack));
  assert.equal(ui.w.document.querySelector('#table-wrap img'),null);
  assert.equal(ui.w.compromised,undefined);assert.equal(ui.errors.length,0);
});

test('gym editor keeps selected exercises when editing inventory mode',async t=>{
  const gym={...record,kind:'gym',payload:{name:'Зал',updatedAt:1,inventoryConfigured:false,exerciseIds:[id],equipmentIds:['barbell']}};
  const path='/admin/api/users/'+owner+'/records/gym/'+id;
  const ui=await setup({'GET /admin/api/records':()=>({data:{items:[{...gym,name:'Зал'}],hasMore:false,offset:0}}),['GET '+path]:()=>({data:gym}),['PUT '+path]:()=>({data:{revision:8}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view=gym]').click();
  await until(()=>ui.w.document.querySelector('.table-link'));ui.click('Изменить →');await until(()=>ui.w.document.querySelector('#dialog-content form'));
  ui.field('Название').value='Обновлённый зал';ui.field('Причина изменения').value='Обновлено название';
  ui.submit();await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const write=ui.requests.find(r=>r.method==='PUT');
  assert.deepEqual(write.body.payload.exerciseIds,[id]);assert.deepEqual(write.body.payload.equipmentIds,['barbell']);
  assert.equal(write.body.payload.inventoryConfigured,false);
  await until(()=>!ui.w.document.getElementById('dialog').open&&!ui.w.document.getElementById('refresh').disabled);
});


test('admin logs in with username and password without Google and can log out',async t=>{
  let loggedIn=false;
  const ui=await setup({
    'GET /admin/api/session':()=>({status:401,data:{message:'Войди снова'}}),
    'POST /admin/api/login':body=>{
      if(body.password!=='browser test password')return {status:401,data:{message:'Неверные учётные данные'}};
      loggedIn=true;return {data:{email:'owner@example.test',csrfToken:'login-csrf'}};
    },
    'POST /admin/api/logout':()=>{loggedIn=false;return {data:{}};},
  },false);
  t.after(()=>ui.dom.window.close());
  const d=ui.w.document;
  assert.equal(d.getElementById('login-username').type,'text');
  d.getElementById('login-username').value='admin';
  d.getElementById('login-password').value='wrong password';
  ui.click('Войти в админку ↗');
  await until(()=>d.getElementById('login-error').textContent);
  await until(()=>!d.querySelector('#login-form button').disabled);
  assert.equal(d.getElementById('shell').hidden,true);
  d.getElementById('login-password').value='browser test password';
  ui.click('Войти в админку ↗');
  await until(()=>!d.getElementById('shell').hidden);
  await until(()=>d.querySelectorAll('.stat').length===4);
  assert.equal(loggedIn,true);
  assert.deepEqual(ui.requests.filter(r=>r.path.endsWith('/login')).at(-1).body,{username:'admin',password:'browser test password'});
  assert.equal(d.getElementById('login-password').value,'');
  d.getElementById('logout').click();
  await until(()=>!d.getElementById('login').hidden);
  assert.equal(loggedIn,false);
  assert.equal(ui.requests.find(r=>r.path.endsWith('/logout')).headers['X-CSRF-Token'],'login-csrf');
  assert.ok(ui.requests.every(r=>!/(google|nonce|config)/.test(r.path)));
  assert.equal(d.querySelector('script[src^="https://accounts.google.com"]'),null);
  assert.equal(ui.w.localStorage.length,0);
  assert.equal(ui.w.sessionStorage.length,0);
  assert.equal(ui.errors.length,0);
});

test('standard equipment editor needs no owner and preserves exact retry payload',async t=>{
  const equipment={kind:'equipment',id:'adjustable_bench',revision:3,archived:false,payload:{name:'Скамья',group:'Скамьи',synonyms:['лавка'],provides:['adjustable_bench','flat_bench']}};
  const shared={active:true,revision:3,records:[],equipment:[equipment,{kind:'equipment',id:'flat_bench',revision:0,archived:false,payload:{name:'Плоская скамья',group:'Скамьи',synonyms:[],provides:['flat_bench']}}]};
  let attempt=0;
  const ui=await setup({'GET /admin/api/standard':()=>({data:{items:[equipment],hasMore:false,offset:0}}),'GET /v1/catalog':()=>({data:shared}),'PUT /admin/api/standard/equipment/adjustable_bench':()=>++attempt===1?Promise.reject(new Error('offline')):({data:{revision:4}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view="standard:equipment"]').click();
  await until(()=>ui.w.document.getElementById('table-wrap').textContent.includes('Скамья'));
  ui.click('Изменить');await until(()=>ui.w.document.querySelector('#dialog-content form'));
  ui.field('Название').value='Обновлённая скамья';ui.field('Причина изменения').value='Уточнение каталога';ui.submit();
  await until(()=>ui.w.document.querySelector('#dialog-content .error').textContent);ui.submit();await until(()=>attempt===2);
  const writes=ui.requests.filter(x=>x.method==='PUT');assert.deepEqual(writes[0].body,writes[1].body);assert.equal(writes[0].body.baseRevision,3);assert.deepEqual(writes[0].body.payload.provides,['adjustable_bench','flat_bench']);
  assert.equal(ui.requests.some(x=>x.path.includes('/users/')),false);
});

test('standard template editor saves ordered exercises rest sets and shared gyms',async t=>{
  const gymId='33333333-3333-4333-8333-333333333333';
  const routine={kind:'routine',id,revision:2,archived:false,payload:{name:'План',note:'Тест',updatedAt:1,gymIds:[gymId],exercises:[{exerciseId:id,position:0,restSeconds:90,plannedSets:[{weightKg:50,reps:8,durationSec:null,speedKmh:null,inclinePct:null}]}]}};
  const ui=await setup({'GET /admin/api/standard':()=>({data:{items:[routine],hasMore:false,offset:0}}),'GET /v1/catalog':()=>({data:{active:true,revision:2,records:[{kind:'exercise',id,archived:false,payload},{kind:'gym',id:gymId,archived:false,payload:{name:'Общий зал'}}],equipment:[]}}),['PUT /admin/api/standard/routine/'+id]:()=>({data:{revision:3}})});
  t.after(()=>ui.dom.window.close());ui.w.document.querySelector('[data-view="standard:routine"]').click();await until(()=>ui.w.document.getElementById('table-wrap').textContent.includes('План'));ui.click('Изменить');await until(()=>ui.w.document.querySelector('#dialog-content form'));
  ui.field('Причина изменения').value='Изменение программы';ui.field('Отдых, секунды').value='120';ui.submit();await until(()=>ui.requests.some(x=>x.method==='PUT'));
  const write=ui.requests.find(x=>x.method==='PUT');assert.equal(write.body.payload.exercises[0].restSeconds,120);assert.equal(write.body.payload.exercises[0].plannedSets[0].reps,8);assert.deepEqual(write.body.payload.gymIds,[gymId]);assert.equal(write.body.payload.exercises[0].position,0);
});


test('AI settings keep the stored key write-only and save with CSRF and revision',async t=>{
  const data={coachPrompt:'Исходный промпт',revision:3,enabled:true,baseUrl:'https://provider.test',textModel:'text',visionModel:'vision',coachModel:'coach',coachModels:['coach'],hasApiKey:true,encryptionAvailable:true};
  const ui=await setup({
    'GET /admin/api/ai-settings':()=>({data}),
    'PUT /admin/api/ai-settings':body=>({data:{...data,revision:4,textModel:body.textModel,coachPrompt:body.coachPrompt}}),
  });
  t.after(()=>ui.dom.window.close());
  ui.w.document.querySelector('[data-view=ai]').click();
  await until(()=>ui.w.document.querySelector('#ai-settings-form'));
  const prompt=ui.w.document.getElementById('ai-coachPrompt');
  assert.equal(prompt.value,'Исходный промпт');
  prompt.value='  Новый промпт\nВторая строка  ';
  const key=ui.w.document.getElementById('ai-apiKey');
  assert.equal(key.type,'password');assert.equal(key.value,'');
  ui.w.document.getElementById('ai-textModel').value='updated';
  ui.w.document.getElementById('ai-settings-form').dispatchEvent(new ui.w.Event('submit',{bubbles:true,cancelable:true}));
  await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const request=ui.requests.find(r=>r.method==='PUT');
  assert.equal(request.headers['X-CSRF-Token'],'test-csrf');
  assert.equal(request.body.coachPrompt,'  Новый промпт\nВторая строка  ');
  assert.equal(request.body.revision,3);assert.equal(request.body.textModel,'updated');
  assert.equal(Object.hasOwn(request.body,'apiKey'),false);
  assert.equal(ui.w.localStorage.length,0);
  await until(()=>ui.w.document.getElementById('notice').textContent==='Настройки ИИ сохранены');
});

test('planner settings edit drafts and save server configuration without starting a workout',async t=>{
  const slot={role:'PRIMARY',movement:'Жим',exerciseType:'STRENGTH',exerciseCount:1,sets:3,repsMin:6,repsMax:12,restSeconds:120,durationSeconds:0};
  const data={model:'text',instructions:'Черновик',historyDays:28,detailDays:7,maxRounds:6,maxToolCalls:12,timeoutSeconds:45,weightStepKg:2.5,collections:[{id:'fitness',goal:'GENERAL_FITNESS',name:'Общая форма',sequence:['fitness-a'],patterns:[{id:'fitness-a',name:'Всё тело',focus:'FULL_BODY',description:'<b>Не HTML</b>',slots:[slot]}]}]};
  const ui=await setup({
    'GET /admin/api/planner-settings':()=>({data}),
    'GET /admin/api/planner-exercises':()=>({data:[{id:'00000000-0000-0000-0000-000000000010',name:'Присед'}]}),
    'PUT /admin/api/planner-settings':body=>({data:body}),
  });
  t.after(()=>ui.dom.window.close());
  ui.w.document.querySelector('[data-view=planner]').click();
  await until(()=>ui.w.document.querySelector('.planner-settings'));
  const form=ui.w.document.querySelector('.planner-settings');
  const field=label=>[...form.querySelectorAll('label')].find(x=>x.firstChild?.textContent===label).querySelector('input,select,textarea');
  const change=(input,value)=>{input.value=value;input.dispatchEvent(new ui.w.Event('input',{bubbles:true}));};
  change(field('Модель (пусто — текущая модель текста)'),'planner-model');
  change(field('Подходов'),'4');
  assert.equal(field('История, дней (до 28)').max,'28');
  assert.equal(field('Подробно, дней (до 7)').max,'7');
  assert.equal(form.querySelector('.planner-pattern b'),null);
  const accent=[...form.querySelectorAll('input[type=radio]')].filter(x=>x.name==='accent-00000000-0000-0000-0000-000000000010');
  assert.deepEqual(accent.map(x=>x.value),['MORE','NORMAL','LESS','NEVER']);
  const more=accent.find(x=>x.value==='MORE');more.checked=true;more.dispatchEvent(new ui.w.Event('change',{bubbles:true}));
  form.dispatchEvent(new ui.w.Event('submit',{bubbles:true,cancelable:true}));
  await until(()=>ui.requests.some(r=>r.method==='PUT'));
  const write=ui.requests.find(r=>r.method==='PUT');
  assert.equal(write.path,'/admin/api/planner-settings');
  assert.equal(write.headers['X-CSRF-Token'],'test-csrf');
  assert.equal(write.body.model,'planner-model');
  assert.equal(write.body.collections[0].patterns[0].slots[0].sets,4);
  assert.deepEqual(write.body.defaultExerciseAccents,[{exerciseId:'00000000-0000-0000-0000-000000000010',accent:'MORE'}]);
  assert.equal(write.body.collections[0].patterns[0].id,'fitness-a');
  assert.equal(write.body.collections[0].patterns[0].description,'<b>Не HTML</b>');
  assert.deepEqual(write.body.collections[0].sequence,['fitness-a']);
  assert.equal(ui.requests.some(r=>/calendar|proposal|generate/.test(r.path)),false);
  await until(()=>ui.w.document.getElementById('notice').textContent==='Настройки планировщика сохранены');
  const savedForm=ui.w.document.querySelector('.planner-settings');
  const normal=[...savedForm.querySelectorAll('input[type=radio]')].find(x=>x.value==='NORMAL');
  normal.checked=true;normal.dispatchEvent(new ui.w.Event('change',{bubbles:true}));
  savedForm.dispatchEvent(new ui.w.Event('submit',{bubbles:true,cancelable:true}));
  await until(()=>ui.requests.filter(r=>r.method==='PUT').length===2);
  assert.deepEqual(ui.requests.filter(r=>r.method==='PUT').at(-1).body.defaultExerciseAccents,[]);
  assert.equal(ui.w.document.querySelectorAll('input[type=radio][name="accent-00000000-0000-0000-0000-000000000010"]').length,4);
  assert.equal(ui.errors.length,0);
});

test('planner settings keep stale default read-only and allow its reset',async t=>{
  const stale='00000000-0000-0000-0000-000000000099';
  const data={model:'text',instructions:'Черновик',historyDays:28,detailDays:7,maxRounds:6,maxToolCalls:12,timeoutSeconds:45,weightStepKg:2.5,defaultExerciseAccents:[{exerciseId:stale,accent:'NEVER'}],collections:[{id:'fitness',goal:'GENERAL_FITNESS',name:'Общая форма',sequence:['fitness-a'],patterns:[{id:'fitness-a',name:'Всё тело',focus:'FULL_BODY',description:'',slots:[{role:'PRIMARY',movement:'Жим',exerciseType:'STRENGTH',exerciseCount:1,sets:3,repsMin:6,repsMax:12,restSeconds:120,durationSeconds:0}]}]}]};
  const ui=await setup({
    'GET /admin/api/planner-settings':()=>({data}),
    'GET /admin/api/planner-exercises':()=>({data:[]}),
    'PUT /admin/api/planner-settings':body=>({data:body}),
  });
  t.after(()=>ui.dom.window.close());
  ui.w.document.querySelector('[data-view=planner]').click();
  await until(()=>ui.w.document.querySelector('.planner-settings'));
  const form=ui.w.document.querySelector('.planner-settings');
  const radios=[...form.querySelectorAll('input[type=radio][name="accent-'+stale+'"]')];
  assert.equal(radios.length,1);assert.equal(radios[0].value,'NEVER');assert.equal(radios[0].disabled,true);
  const reset=[...form.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='Удалённое упражнение · '+stale+' · сбросить до обычного');
  assert.ok(reset);reset.click();
  form.dispatchEvent(new ui.w.Event('submit',{bubbles:true,cancelable:true}));
  await until(()=>ui.requests.some(r=>r.method==='PUT'));
  assert.deepEqual(ui.requests.find(r=>r.method==='PUT').body.defaultExerciseAccents,[]);
  assert.equal(ui.errors.length,0);
});

test('AI diagnostics renders only sanitized fields, filters locally, and copies without writes',async t=>{
  const attack='<img src=x onerror="window.compromised=true">';
  const now=Date.now();
  const diagnostics={
    generatedAt:new Date(now).toISOString(),
    retention:{maxRuns:200,maxAgeHours:24,processLocal:true,lostOnRestart:true},
    database:{status:'OK'},calendarQueue:{status:'ERROR',queued:null,running:null,failed:null,ready:null},
    runs:[
      {id:'00000000-0000-4000-8000-000000000001',startedAt:new Date(now-60_000).toISOString(),durationMs:12,outcome:'FAILURE',failureCategory:'UPSTREAM_REJECTED',model:attack,httpStatus:400,upstreamCode:'<script>secret</script>',upstreamType:'invalid_request_error',upstreamParam:'tools',rounds:1,toolCalls:2,stages:[{stage:'PLANNER_TOOL',outcome:'FAILURE',durationMs:2}],ownerId:'must-not-copy'},
      {id:'00000000-0000-4000-8000-000000000002',startedAt:new Date(now-120_000).toISOString(),durationMs:4,outcome:'SUCCESS',failureCategory:'NONE',model:null,httpStatus:null,upstreamCode:null,upstreamType:null,upstreamParam:null,rounds:0,toolCalls:0,stages:[]},
    ],
  };
  const ui=await setup({'GET /admin/api/ai-diagnostics':()=>({data:diagnostics})});
  t.after(()=>ui.dom.window.close());
  ui.w.Date.now=()=>Date.parse(diagnostics.generatedAt);
  let copied='';Object.defineProperty(ui.w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;}}});
  ui.click('ИИ · Диагностика');
  await until(()=>ui.w.document.getElementById('ai-diagnostics').textContent.includes('Ограничения диагностики'));
  const root=ui.w.document.getElementById('ai-diagnostics');
  assert.equal(root.querySelector('img'),null);assert.equal(ui.w.compromised,undefined);
  assert.equal(root.textContent.includes('<script>secret</script>'),false);
  const outcome=root.querySelector('select[aria-label="Исход попытки"]');outcome.value='FAILURE';outcome.dispatchEvent(new ui.w.Event('change'));
  await until(()=>root.textContent.includes('00000000'));
  assert.equal(root.textContent.includes('000000000002'),false);
  [...root.querySelectorAll('button')].find(x=>x.textContent==='Скопировать отчёт').click();await until(()=>copied);
  assert.equal(copied.includes('must-not-copy'),false);assert.equal(copied.includes('<script>secret</script>'),false);
  assert.equal(copied.includes('PLANNER_TOOL'),true);
  assert.ok(ui.requests.every(request=>request.method==='GET'));
  assert.equal(root.textContent.includes('После перезапуска данные теряются'),true);
  assert.equal(ui.errors.length,0);
});

test('AI diagnostics shows an explicit empty state',async t=>{
  const ui=await setup({'GET /admin/api/ai-diagnostics':()=>({data:{generatedAt:'2026-09-20T10:00:00Z',retention:{maxRuns:200,maxAgeHours:24,processLocal:true,lostOnRestart:true},database:{status:'OK'},calendarQueue:{status:'OK',queued:0,running:0,failed:0,ready:0},runs:[]}})});
  t.after(()=>ui.dom.window.close());ui.click('ИИ · Диагностика');
  await until(()=>ui.w.document.getElementById('ai-diagnostics').textContent.includes('Нет диагностических запусков'));
  assert.equal(ui.requests.filter(request=>request.path.endsWith('/ai-diagnostics')).every(request=>request.method==='GET'),true);
});

test('diagnostic events explain recovered rejection and copied reports omit untrusted fields', async t=>{
  const now=new Date().toISOString();
  const event={site:'PLAN_VALIDATION',reason:'DURATION_TOO_SHORT',round:2,tool:'VALIDATE_AND_FINALIZE_PLAN',actual:45,minimum:2160,maximum:2700,field:'result.exercises[0].plannedSets[0].reps',arguments:'secret-arguments'};
  const data={generatedAt:now,process:{id:'00000000-0000-4000-8000-000000000000',startedAt:now,revision:'a'.repeat(40)},
    retention:{maxRuns:200,maxAgeHours:24},database:{status:'OK'},calendarQueue:{status:'OK'},
    runs:[{id:'00000000-0000-4000-8000-000000000001',startedAt:now,outcome:'SUCCESS',failureCategory:'NONE',events:[event,{...event,reason:'secret-reason'},{...event,field:'result.secret-field',actual:-1,maximum:Infinity}],droppedEvents:7}]};
  const ui=await setup({'GET /admin/api/ai-diagnostics':()=>({data})});t.after(()=>ui.dom.window.close());
  let copied='';Object.defineProperty(ui.w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;}}});
  ui.click('ИИ · Диагностика');
  await until(()=>ui.w.document.getElementById('ai-diagnostics').textContent.includes('DURATION_TOO_SHORT'));
  const root=ui.w.document.getElementById('ai-diagnostics');
  assert.ok(root.textContent.includes('минимум 2160'));
  assert.ok(root.textContent.includes('Ранних событий пропущено: 7'));
  assert.ok(root.textContent.includes('a'.repeat(40)));
  ui.click('Скопировать отчёт');await until(()=>copied);
  const report=JSON.parse(copied);
  assert.equal(report.runs[0].outcome,'SUCCESS');
  assert.equal(report.runs[0].events.length,2);
  assert.equal(report.runs[0].events[0].field,event.field);
  assert.equal(report.runs[0].events[1].field,null);
  assert.equal(report.runs[0].events[1].actual,null);
  assert.equal(copied.includes('secret'),false);
  assert.equal(ui.errors.length,0);
});
