const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {JSDOM} = require('jsdom');
const S = require('../../main/resources/playground/state.js');
const {catalog} = require('../../main/resources/playground/fixtures.json');
const fixture = () => S.scenario(catalog, 'hard');
function proposal(state, operations) {
  return {runId:S.uuid(),result:{proposal:{proposalId:S.uuid(),baseRevision:state.snapshot.revision,contextVersion:state.contextVersion,expiresAtMillis:Date.now()+300000,operations}}};
}
test('proposal is atomic when a later operation is invalid', () => {
  const state = fixture(), before = S.clone(state);
  const run = proposal(state,[{action:'edit_set',set_id:state.snapshot.exercises[0].sets[1].set_id,values:{weight_kg:50}},{action:'delete_set',set_id:state.snapshot.exercises[0].sets[0].set_id}]);
  assert.throws(() => S.decide(state,run,'APPLIED',catalog),/Выполненный/);
  assert.deepEqual(state,before); assert.equal(run.receiptStatus,undefined);
});
test('changing context or expiry records STALE without applying a proposal', () => {
  for (const [index, mutate] of [s=>S.changed(s), s=>{s.contextVersion=S.version();}, ()=>{}].entries()) {
    const state=fixture(), run=proposal(state,[{action:'available_time',minutes:1}]);
    mutate(state); if(index === 2)run.result.proposal.expiresAtMillis=0;
    const snapshot=S.clone(state.snapshot);
    assert.equal(S.decide(state,run,'APPLIED',catalog),'STALE'); assert.deepEqual(state.snapshot,snapshot);
    assert.equal(state.receipts[0].body.status,'STALE');
  }
});
test('apply survives persistence, receipt retry cannot apply twice, undo preserves completed facts', () => {
  let state=fixture(); const before=S.clone(state.snapshot);
  const run=proposal(state,[{action:'edit_set',set_id:state.snapshot.exercises[0].sets[1].set_id,values:{weight_kg:55,reps:8}}]);
  state.runs.push(run); S.decide(state,run,'APPLIED',catalog);
  state=JSON.parse(JSON.stringify(state));
  assert.equal(state.snapshot.exercises[0].sets[1].weight_kg,55);
  assert.deepEqual(state.snapshot.exercises[0].sets[0],before.exercises[0].sets[0]);
  assert.throws(()=>S.decide(state,state.runs[0],'APPLIED',catalog),/уже принято/);
  S.decide(state,proposal(state,[{action:'undo_last'}]),'APPLIED',catalog);
  assert.deepEqual(state.snapshot.exercises,before.exercises);
  assert.equal(state.snapshot.revision,before.revision+2);
});
test('rejection preserves workout and records one stable receipt', () => {
  const state=fixture(), before=S.clone(state.snapshot), run=proposal(state,[{action:'available_time',minutes:1}]);
  S.decide(state,run,'REJECTED',catalog); assert.deepEqual(state.snapshot,before);
  assert.equal(state.receipts[0].body.status,'REJECTED');
  assert.equal(state.receipts[0].body.proposalId,run.result.proposal.proposalId);
});
test('replacement keeps completed history in its original section', () => {
  const state=fixture(), section=state.snapshot.exercises[0];
  const next=S.apply(state.snapshot,[{action:'replace_remaining',section_id:section.section_id,exercise_id:catalog[3].exercise_id,remaining_set_ids:section.sets.slice(1).map(s=>s.set_id),weight_kg:20}],catalog);
  assert.deepEqual(next.exercises[0].sets,[section.sets[0]]);
  assert.equal(next.exercises[1].exercise_id,catalog[3].exercise_id);
  assert.equal(next.exercises[1].sets.length,2);
  assert.notEqual(next.exercises[1].sets[0].set_id,section.sets[1].set_id);
});
test('rest identity and unknown operations fail closed', () => {
  const state=fixture();
  assert.throws(()=>S.apply(state.snapshot,[{action:'skip_rest',rest_start_id:'old'}],catalog),/Отдых/);
  assert.throws(()=>S.apply(state.snapshot,[{action:'unknown'}],catalog),/не поддерживает/);
});
async function until(predicate) { for(let i=0;i<100;i++){if(predicate())return;await new Promise(r=>setTimeout(r,10));}assert.fail('UI did not reach expected state'); }
async function ui(t, saved, override) {
  const dom=new JSDOM(readFileSync('src/main/resources/playground/index.html','utf8'),{url:'http://localhost:18081/dev/coach/',runScripts:'outside-only'});t.after(()=>dom.window.close());
  const w=dom.window, requests=[]; w.TextDecoder=TextDecoder;
  if(saved)w.sessionStorage.setItem('coach-playground-v1',JSON.stringify(saved));
  w.fetch=async(path, options={})=>{
    const body=options.body?JSON.parse(options.body):undefined;requests.push({path,body,options});let data;
    if (override) { const custom=await override(path,options); if(custom)return custom; }
    if(path.endsWith('/bootstrap'))data={accessToken:'test-secret-token',catalog};
    else if(path.endsWith('/settings'))data={revision:0,baseUrl:'https://example.test/v1',coachModel:'real-model',textModel:'real-model',visionModel:'real-model',coachModels:['real-model'],enabled:true,hasApiKey:true};
    else if(path.endsWith('/coach-models'))data={availability:'AVAILABLE',models:['real-model'],defaultModel:'real-model'};
    else if(options.method==='PUT')data={accepted:true};
    else if(path==='/v1/coach/runs' || path.endsWith('/messages'))data={runId:body.requestId,workoutId:body.state?.snapshot.workout_id || body.workoutId,ordinal:1,state:'SUCCEEDED',result:{kind:'answer',text:'<img src=x onerror=alert(1)>',quickReplies:[]},lastEventSequence:2};
    else if(path.includes('/events?'))return {ok:true,status:200,body:new ReadableStream({start(){}})};
    else if(path.includes('/receipt'))data={};
    else if(path.includes('/runs?'))data=[];
    else throw Error('Unexpected request '+path);
    return {ok:true,status:200,json:async()=>data};
  };
  w.eval(readFileSync('src/main/resources/playground/state.js','utf8'));w.eval(readFileSync('src/main/resources/playground/playground.js','utf8'));
  await until(()=>!w.document.getElementById('send').disabled);
  return {w,requests};
}
test('UI submits real coach contract, renders model text safely and never persists access credentials', async t => {
  const {w,requests}=await ui(t);
  w.document.getElementById('message').value='Слишком тяжело';
  w.document.getElementById('chat-form').dispatchEvent(new w.Event('submit',{cancelable:true}));
  await until(()=>w.document.getElementById('messages').textContent.includes('<img'));
  const sent=requests.find(r=>r.path.endsWith('/messages'));
  assert.equal(sent.body.model,'real-model'); assert.equal(sent.body.state.snapshot.exercises.length,3);
  assert.equal(sent.options.headers.Authorization,'Bearer test-secret-token');
  assert.equal(w.document.querySelector('#messages img'),null);
  assert.ok(!w.sessionStorage.getItem('coach-playground-v1').includes('test-secret-token'));
  assert.equal(w.localStorage.length,0);
});
test('reload delivers pending receipt and retries identical submission',async t=>{
  const saved=fixture();
  saved.pendingRun={requestId:S.uuid(),workoutId:saved.snapshot.workout_id,contextVersion:saved.contextVersion,snapshot:S.clone(saved.snapshot),message:'Повтор',history:[],model:'real-model'};
  saved.receipts=[{runId:S.uuid(),body:{receiptId:S.uuid(),proposalId:S.uuid(),status:'APPLIED'}}];
  const {w,requests}=await ui(t,saved);
  await until(()=>requests.some(r=>r.path==='/v1/coach/runs'));
  assert.deepEqual(requests.find(r=>r.path==='/v1/coach/runs').body,saved.pendingRun);
  assert.deepEqual(requests.find(r=>r.path.endsWith('/receipt')).body,saved.receipts[0].body);
  await until(()=>!JSON.parse(w.sessionStorage.getItem('coach-playground-v1')).pendingRun);
});

test('SSE resumes persisted cursor and fragmented frames render only the cumulative final answer', async t => {
  const state=fixture(), id=S.uuid();
  state.runs=[{runId:id,workoutId:state.snapshot.workout_id,ordinal:1,state:'RUNNING',cursor:7,startedAt:Date.now(),draft:'Старый черновик'}];
  state.discoveryCursor=1; state.eventCursor=7;
  const final={runId:id,workoutId:state.snapshot.workout_id,ordinal:1,state:'SUCCEEDED',lastEventSequence:10,result:{kind:'answer',text:'Итоговый ответ',quickReplies:[]}};
  const frames=[
    {sequence:7,type:'text',text:'Повторное событие'},
    {sequence:8,type:'text',text:'Новый'},
    {sequence:9,type:'text',text:'Новый ответ'},
    {sequence:10,type:'completed',run:final},
  ].map(event=>`id: ${event.sequence}\ndata: ${JSON.stringify({...event,runId:id,origin:'USER'})}\n\n`).join('');
  const bytes=new TextEncoder().encode(frames);
  const {w,requests}=await ui(t,state,async path=>{
    if(path.includes('/events?'))return {ok:true,status:200,body:new ReadableStream({start(controller){ for(let i=0;i<bytes.length;i+=13)controller.enqueue(bytes.slice(i,i+13));controller.close();}})};
    if(path===`/v1/coach/runs/${id}`)return {ok:true,status:200,json:async()=>state.runs[0]};
  });
  await until(()=>w.document.getElementById('messages').textContent.includes('Итоговый ответ'));
  assert.ok(requests.some(r=>r.path.endsWith('/events?after=7')));
  const persisted=JSON.parse(w.sessionStorage.getItem('coach-playground-v1'));
  assert.equal(persisted.eventCursor,10);
  assert.equal(w.document.querySelectorAll('#messages .bubble').length,1);
  assert.ok(!w.document.getElementById('messages').textContent.includes('Повторное событие'));
});

test('concern resolves from the conversation event without a manual status button', async t => {
  const state=fixture(), eventId=S.uuid();
  const event={sequence:1,type:'concern',eventId,text:'Уточните, что произошло.',decision:{reasonCode:'reported_safety_issue',state:{policyVersion:'behavior-1',openConcerns:['workout:PAIN']}}};
  const resolved={sequence:2,type:'concern_resolved',resolvedConcernKeys:['workout:PAIN']};
  const bytes=new TextEncoder().encode([event,event,resolved].map(e=>`data: ${JSON.stringify(e)}\n\n`).join(''));
  const {w}=await ui(t,state,async path=>{
    if(path.includes('/events?'))return {ok:true,status:200,body:new ReadableStream({start(c){c.enqueue(bytes);c.close();}})};
  });
  await until(()=>JSON.parse(w.sessionStorage.getItem('coach-playground-v1')).eventCursor === 2);
  assert.equal(w.document.querySelectorAll('#messages .bubble').length,1);
  assert.equal(w.document.querySelector('#messages .bubble button'),null);
  const saved=JSON.parse(w.sessionStorage.getItem('coach-playground-v1'));
  assert.equal(saved.concerns[0].resolved,true);
  assert.equal(saved.concerns.length,1);
});

test('structured question prepares a preview and applying persists a receipt before delivery', async t => {
  const state=fixture(), questionId=S.uuid(), proposalId=S.uuid();
  state.interventions=[{kind:'question',text:'Почему?',question:{questionId,version:1,expiresAtMillis:Date.now()+300000,options:[{id:'HARDER_THAN_EXPECTED',text:'Было тяжелее'}]}}];
  const result={kind:'proposal',questionId,status:'ANSWERED',text:'Сократить повторы',proposal:{proposalId,version:1,baseRevision:state.snapshot.revision,contextVersion:state.contextVersion,expiresAtMillis:Date.now()+300000,operations:[{action:'edit_set',set_id:state.snapshot.exercises[0].sets[1].set_id,values:{reps:5}}]}};
  const {w,requests}=await ui(t,state,async path=>{
    if(path.endsWith('/answers'))return {ok:true,status:200,json:async()=>result};
  });
  [...w.document.querySelectorAll('#messages button')].find(b=>b.textContent==='Было тяжелее').click();
  await until(()=>w.document.getElementById('messages').textContent.includes('Сократить повторы'));
  let saved=JSON.parse(w.sessionStorage.getItem('coach-playground-v1'));
  assert.notEqual(saved.snapshot.exercises[0].sets[1].reps,5);
  assert.equal(requests.find(r=>r.path.endsWith('/answers')).body.expectedVersion,1);
  [...w.document.querySelectorAll('#messages button')].find(b=>b.textContent==='Применить').click();
  await until(()=>requests.some(r=>r.path.endsWith(`/proposals/${proposalId}/receipt`)));
  saved=JSON.parse(w.sessionStorage.getItem('coach-playground-v1'));
  assert.equal(saved.snapshot.exercises[0].sets[1].reps,5);
  assert.equal(requests.find(r=>r.path.endsWith(`/proposals/${proposalId}/receipt`)).body.resultRevision,state.snapshot.revision+1);
  assert.equal(saved.interventions.filter(i=>i.proposal).length,1);
});
