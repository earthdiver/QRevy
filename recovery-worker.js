/** Owns engine requests and worker lifetime; presentation stays with the caller. */
export function createRecoveryWorker({
  createWorker,
  getLanguage,
  messages,
  onBusy,
  onProgress,
  onError,
  onTerminated,
  createStopBuffer = () => typeof SharedArrayBuffer !== 'undefined' && globalThis.crossOriginIsolated
    ? new Int32Array(new SharedArrayBuffer(4)) : null,
}) {
  let worker = null;
  let pending = null;
  let sequence = 0;

  function finish(error, result) {
    const completed = pending;
    pending = null;
    onBusy(false);
    if (completed) {
      if (error) completed.reject(error);
      else completed.resolve(result);
    }
  }

  function start() {
    if (worker) return;
    const current = createWorker();
    worker = current;
    current.onmessage = ({data}) => {
      // Events queued by a terminated worker must not affect the replacement.
      if (worker !== current || data.ready) return;
      if (!pending) return;
      // Engine progress messages have no request ID; responses always have one.
      if (data.id != null && data.id !== pending.id) return;
      if (data.progress) {
        onProgress(data.progress);
        return;
      }
      if (data.id !== pending.id) return;
      const error = data.error || data.result?.error;
      finish(error ? new Error(error) : null, data.result);
    };
    current.onerror = event => {
      event.preventDefault();
      if (worker !== current) return;
      const error = new Error(messages.loadFailed);
      finish(error);
      onError(error);
    };
  }

  function call(request, label) {
    if (pending) {
      // Eligibility checks may wait for user work, and user work may wait for them.
      if (pending.action === 'uiState' || request.action === 'uiState') {
        return pending.promise.catch(() => {}).then(() => call(request, label));
      }
      return Promise.reject(new Error(messages.busy));
    }
    let resolve, reject;
    const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
    pending = {id: ++sequence, action: request.action, promise, resolve, reject, stop: null};
    try {
      pending.stop = createStopBuffer();
      onBusy(true, label);
      worker.postMessage({id: pending.id, request: {...request, stop: pending.stop, language: getLanguage()}});
    } catch (error) {
      finish(error);
    }
    return promise;
  }

  function terminateWorker() {
    const previous = worker;
    worker = null;
    previous?.terminate();
  }

  function stop() {
    if (!pending) return 'idle';
    if (pending.stop && Atomics.load(pending.stop, 0) === 0) {
      Atomics.store(pending.stop, 0, 1);
      return 'stopping';
    }
    terminateWorker();
    onTerminated();
    finish(new Error(messages.stopped));
    start();
    return 'terminated';
  }

  function suspend() {
    // Exit waits for idle work; it must never leave an active request unresolved.
    if (pending) throw new Error(messages.busy);
    terminateWorker();
  }

  return {
    get pending() { return pending && {action: pending.action, promise: pending.promise}; },
    call,
    stop,
    start,
    suspend,
  };
}
