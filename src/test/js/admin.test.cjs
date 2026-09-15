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
