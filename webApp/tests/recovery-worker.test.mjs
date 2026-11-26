import test from 'node:test';
import assert from 'node:assert/strict';
import {createRecoveryWorker} from '../web/recovery-worker.js';

function fixture({shared = true} = {}) {
  const workers = [], busy = [], progress = [], errors = [];
  let language = 'en', terminations = 0;
  const client = createRecoveryWorker({
    createWorker() {
      const worker = {
        sent: [], terminated: false,
        postMessage(message) { this.sent.push(message); },
        terminate() { this.terminated = true; },
        emit(data) { this.onmessage({data}); },
        fail() { this.onerror({preventDefault() {}}); },
      };
      workers.push(worker);
      return worker;
    },
    createStopBuffer: () => shared ? new Int32Array(new SharedArrayBuffer(4)) : null,
    getLanguage: () => language,
    messages: {busy: 'Busy', loadFailed: 'Worker failed', stopped: 'Stopped'},
    onBusy: (...args) => busy.push(args),
    onProgress: text => progress.push(text),
    onError: error => errors.push(error.message),
    onTerminated: () => terminations++,
  });
  client.start();
  return {client, workers, busy, progress, errors,
    setLanguage(value) { language = value; },
    get terminations() { return terminations; },
  };
}

function respond(worker, result, index = worker.sent.length - 1) {
  worker.emit({id: worker.sent[index].id, result});
}

// Request IDs guard results while untagged engine progress belongs to active work.
test('requests publish only matching results and active progress', async () => {
  const {client, workers: [worker], busy, progress} = fixture();
  worker.emit({progress: 'idle'});
  const result = client.call({action: 'recover', matrix: 'input'}, 'Repair');
  assert.equal(client.pending.action, 'recover');
  assert.equal(client.pending.promise, result);
  assert.equal(worker.sent[0].request.matrix, 'input');
  assert.equal(worker.sent[0].request.language, 'en');
  worker.emit({ready: true});
  worker.emit({id: worker.sent[0].id + 1, result: 'wrong'});
  worker.emit({id: worker.sent[0].id + 1, progress: 'wrong'});
  worker.emit({progress: 'searching'});
  assert.equal(client.pending.promise, result);
  respond(worker, {candidates: ['verified']});
  assert.deepEqual(await result, {candidates: ['verified']});
  worker.emit({progress: 'late'});
  assert.equal(client.pending, null);
  assert.deepEqual(progress, ['searching']);
  assert.deepEqual(busy, [[true, 'Repair'], [false]]);
});

test('concurrent user work is rejected without replacing the active request', async () => {
  const {client, workers: [worker]} = fixture();
  const first = client.call({action: 'recover'});
  await assert.rejects(client.call({action: 'edit'}), /Busy/);
  assert.equal(worker.sent.length, 1);
  respond(worker, 'retained');
  assert.equal(await first, 'retained');
});

for (const [firstAction, nextAction] of [['recover', 'uiState'], ['uiState', 'recover']]) {
  test(`${nextAction} waits for ${firstAction}, including a failed request`, async () => {
    const f = fixture(), worker = f.workers[0];
    const first = f.client.call({action: firstAction});
    const failure = assert.rejects(first, /invalid input/);
    const next = f.client.call({action: nextAction});
    assert.equal(worker.sent.length, 1);
    f.setLanguage('ja');
    respond(worker, {error: 'invalid input'});
    await failure;
    assert.equal(worker.sent.length, 2);
    assert.equal(worker.sent[1].request.action, nextAction);
    assert.equal(worker.sent[1].request.language, 'ja');
    respond(worker, 'next result');
    assert.equal(await next, 'next result');
  });
}

test('cooperative stop keeps the request alive to return retained candidates', async () => {
  const f = fixture(), worker = f.workers[0];
  const result = f.client.call({action: 'recover'});
  assert.equal(f.client.stop(), 'stopping');
  assert.equal(Atomics.load(worker.sent[0].request.stop, 0), 1);
  assert.equal(worker.terminated, false);
  assert.equal(f.client.pending.promise, result);
  respond(worker, {candidates: ['retained'], interrupted: true});
  assert.deepEqual(await result, {candidates: ['retained'], interrupted: true});
  assert.equal(f.terminations, 0);
  assert.equal(f.client.stop(), 'idle');
});

for (const shared of [true, false]) {
  test(`forced stop replaces the worker and ignores its late events (shared memory: ${shared})`, async () => {
    const f = fixture({shared}), old = f.workers[0];
    const first = f.client.call({action: 'recover'});
    const stopped = assert.rejects(first, /Stopped/);
    if (shared) assert.equal(f.client.stop(), 'stopping');
    assert.equal(f.client.stop(), 'terminated');
    await stopped;
    assert.equal(old.terminated, true);
    assert.equal(f.terminations, 1);
    const current = f.workers[1];
    const next = f.client.call({action: 'recover'});
    assert.notEqual(old.sent[0].id, current.sent[0].id);
    if (shared) {
      assert.notEqual(old.sent[0].request.stop, current.sent[0].request.stop);
      assert.equal(Atomics.load(current.sent[0].request.stop, 0), 0);
    } else assert.equal(current.sent[0].request.stop, null);
    old.emit({id: current.sent[0].id, result: 'stale'});
    old.emit({progress: 'stale progress'});
    old.fail();
    assert.equal(f.client.pending.promise, next);
    assert.deepEqual(f.progress, []);
    assert.deepEqual(f.errors, []);
    respond(current, 'new result');
    assert.equal(await next, 'new result');
  });
}

test('worker errors settle pending work and report idle startup failures', async () => {
  const f = fixture(), worker = f.workers[0];
  worker.fail();
  const result = f.client.call({action: 'recover'});
  const failed = assert.rejects(result, /Worker failed/);
  worker.fail();
  await failed;
  assert.equal(f.client.pending, null);
  assert.deepEqual(f.errors, ['Worker failed', 'Worker failed']);
  assert.deepEqual(f.busy.at(-1), [false]);
});

test('response errors and dispatch failures release the request for subsequent work', async () => {
  const f = fixture(), worker = f.workers[0];
  const result = f.client.call({action: 'recover'});
  const failed = assert.rejects(result, /decode failed/);
  worker.emit({id: worker.sent[0].id, error: 'decode failed'});
  await failed;
  const send = worker.postMessage;
  worker.postMessage = () => { throw new Error('clone failed'); };
  await assert.rejects(f.client.call({action: 'recover'}), /clone failed/);
  assert.equal(f.client.pending, null);
  assert.deepEqual(f.busy.at(-1), [false]);
  worker.postMessage = send;
  const next = f.client.call({action: 'recover'});
  respond(worker, 'recovered');
  assert.equal(await next, 'recovered');
});

test('exit and resume replace only an idle worker', async () => {
  const f = fixture(), old = f.workers[0];
  f.client.start();
  assert.equal(f.workers.length, 1);
  const first = f.client.call({action: 'recover'});
  assert.throws(() => f.client.suspend(), /Busy/);
  assert.equal(old.terminated, false);
  respond(old, 'saved');
  await first;
  f.client.suspend();
  assert.equal(old.terminated, true);
  old.emit({progress: 'late'});
  old.fail();
  f.client.start();
  const next = f.client.call({action: 'regions'});
  respond(f.workers[1], 'resumed');
  assert.equal(await next, 'resumed');
  assert.deepEqual(f.errors, []);
  assert.deepEqual(f.progress, []);
});
