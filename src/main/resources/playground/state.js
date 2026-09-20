/* The browser is a simulator of the Android application boundary, not a second coach. */
(function (root) {
  const clone = value => JSON.parse(JSON.stringify(value));
  const uuid = () => globalThis.crypto.randomUUID();
  const version = () => (uuid() + uuid()).replaceAll('-', '');
  function makeSet(weight = null, index = 0) {
    return { set_id: uuid(), index, completed: false, weight_kg: weight, reps: 10, set_type: 'WORK', actual_rir: null, reported_feelings: [] };
  }
  function scenario(catalog, kind) {
    const snapshot = {
      phase: 'READY', workout_id: uuid(), revision: 1, observed_at_millis: Date.now(), available_time_minutes: kind === 'short' ? 5 : 45,
      future_rest_seconds: 120, excluded_exercise_ids: [], decisions: [],
      profile: { training_goal: 'HYPERTROPHY', preferred_rep_min: 8, preferred_rep_max: 12 },
      autoregulation_options: { goal: 'PRESERVE_PLAN', available_weights_kg: Object.fromEntries(catalog.map(e => [e.exercise_id, Array.from({length: 40}, (_, i) => (i + 1) * 2.5)])) },
      exercises: catalog.slice(0, 3).map((e, position) => ({ ...clone(e), position, section_id: uuid(), sets: Array.from({length: 3}, (_, i) => makeSet(e.weight_kg, i)) }))
    };
    if (kind === 'hard') Object.assign(snapshot.exercises[0].sets[0], { completed: true, completed_at: Date.now(), actual_weight_kg: 60, actual_reps: 6, actual_rir: 0, reported_feelings: ['HARDER_THAN_EXPECTED'] });
    return { snapshot, contextVersion: version(), sequence: 0, initiative: false, runs: [], messages: [], receipts: [], trace: [], undo: null };
  }
  function changed(state) {
    state.snapshot.revision++;
    state.snapshot.observed_at_millis = Date.now();
    state.contextVersion = version();
    state.undo = null;
  }
  function apply(snapshot, operations, catalog, undo) {
    let next = clone(snapshot);
    const section = id => {
      const e = next.exercises.find(e => e.section_id === id);
      if (!e) throw Error('Секция больше не существует');
      return e;
    };
    const set = id => {
      const s = next.exercises.flatMap(e => e.sets).find(s => s.set_id === id);
      if (!s) throw Error('Подход больше не существует');
      return s;
    };
    const exercise = id => {
      const e = catalog.find(e => e.exercise_id === id);
      if (!e) throw Error('Упражнение отсутствует в каталоге стенда');
      return clone(e);
    };
    const rest = id => {
      if (!next.rest || next.rest.start_id !== id) throw Error('Отдых уже изменился');
      return next.rest;
    };
    for (const op of operations) {
      switch (op.action) {
        case 'edit_set': case 'record_result': {
          const s = set(op.set_id);
          if (op.action === 'edit_set' && s.completed) throw Error('Выполненный подход нельзя менять как план');
          for (const [key, value] of Object.entries(op.values)) {
            s[op.action === 'record_result' && ['weight_kg','reps','duration_sec','speed_kmh','incline_pct'].includes(key) ? `actual_${key}` : key] = value;
          }
          break;
        }
        case 'set_completed': Object.assign(set(op.set_id), { completed: op.completed, completed_at: op.completed ? Date.now() : null }); break;
        case 'report_feelings': set(op.set_id).reported_feelings = clone(op.feelings); break;
        case 'add_set': { const e = section(op.section_id); e.sets.push(makeSet(e.sets.at(-1)?.weight_kg ?? null, e.sets.length)); break; }
        case 'delete_set':
          if (set(op.set_id).completed) throw Error('Выполненный подход нельзя удалить');
          next.exercises.forEach(e => { e.sets = e.sets.filter(s => s.set_id !== op.set_id); }); break;
        case 'remove_remaining': { const e = section(op.section_id); e.sets = e.sets.filter(s => s.completed); if (!e.sets.length) next.exercises = next.exercises.filter(x => x !== e); break; }
        case 'add_exercise': next.exercises.splice(op.position ?? next.exercises.length, 0, { ...exercise(op.exercise_id), section_id: uuid(), sets: [makeSet()] }); break;
        case 'replace_remaining': {
          const e = section(op.section_id), remaining = e.sets.filter(s => !s.completed);
          if (JSON.stringify(remaining.map(s => s.set_id).sort()) !== JSON.stringify([...op.remaining_set_ids].sort())) throw Error('Состав оставшихся подходов изменился');
          const replacement = { ...exercise(op.exercise_id), section_id: uuid(), sets: remaining.map(s => ({...s, set_id: uuid(), weight_kg: op.weight_kg ?? null})) };
          e.sets = e.sets.filter(s => s.completed);
          const i = next.exercises.indexOf(e);
          next.exercises.splice(i + (e.sets.length ? 1 : 0), e.sets.length ? 0 : 1, replacement); break;
        }
        case 'move_exercise': { const e = section(op.section_id); next.exercises.splice(next.exercises.indexOf(e),1); next.exercises.splice(op.position,0,e); break; }
        case 'swap_exercises': { const a = next.exercises.indexOf(section(op.first_section_id)), b = next.exercises.indexOf(section(op.second_section_id)); [next.exercises[a],next.exercises[b]] = [next.exercises[b],next.exercises[a]]; break; }
        case 'reorder_exercises':
          if (new Set(op.section_ids).size !== next.exercises.length || op.section_ids.length !== next.exercises.length) throw Error('Неверный порядок секций');
          next.exercises = op.section_ids.map(section); break;
        case 'available_time': next.available_time_minutes = op.minutes; break;
        case 'excluded_exercises': next.excluded_exercise_ids = clone(op.exercise_ids); break;
        case 'future_rest_duration': next.future_rest_seconds = op.seconds; break;
        case 'start_rest': next.rest = { start_id: uuid(), planned_seconds: op.seconds, remaining_seconds: op.seconds }; break;
        case 'extend_rest': { const r = rest(op.rest_start_id); r.remaining_seconds += op.seconds; r.planned_seconds += op.seconds; break; }
        case 'skip_rest': rest(op.rest_start_id); delete next.rest; break;
        case 'undo_last':
          if (operations.length !== 1 || !undo) throw Error('Нет изменений, которые можно отменить');
          next = clone(undo); break;
        default: throw Error(`Симулятор не поддерживает операцию: ${op.action}`);
      }
    }
    next.exercises.forEach((e, position) => { e.position = position; e.sets.forEach((s, i) => { s.index = i; }); });
    next.revision = snapshot.revision + 1;
    next.observed_at_millis = Date.now();
    return next;
  }
  function stale(state, proposal, now = Date.now()) {
    return proposal.baseRevision !== state.snapshot.revision || proposal.contextVersion !== state.contextVersion || proposal.expiresAtMillis <= now;
  }
  function decide(state, run, status, catalog) {
    const proposal = run.result.proposal;
    if (run.receiptStatus) throw Error('Решение уже принято');
    if (stale(state, proposal)) status = 'STALE';
    if (status === 'APPLIED') {
      const before = clone(state.snapshot);
      const next = apply(before, proposal.operations, catalog, state.undo);
      state.snapshot = next;
      state.undo = proposal.operations[0]?.action === 'undo_last' ? null : before;
      state.contextVersion = version();
    }
    run.receiptStatus = status;
    state.receipts.push({ runId: run.runId, body: { receiptId: uuid(), proposalId: proposal.proposalId, status } });
    return status;
  }
  const api = { clone, uuid, version, scenario, changed, apply, stale, decide };
  if (typeof module !== 'undefined') module.exports = api;
  else root.CoachState = api;
})(globalThis);
