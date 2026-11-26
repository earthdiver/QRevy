const HISTORY_LIMIT = 40;
const clone = value => structuredClone(value);
const previewSettings = new Set(['gridReference', 'grid', 'previewQuad', 'provisionalBlack', 'provisionalWhite']);

export function readAutomaticTrial(value) {
  return value?.automaticScanAssumption == null ? null : {
    automaticScanAssumption: value.automaticScanAssumption,
    automaticScanListExtension: value.automaticScanListExtension ?? 1,
    samplingInverted: !!value.samplingInverted,
  };
}

export function historyChangedCells(entry, previous) {
  if (Number.isInteger(entry.changedCells)) return entry.changedCells;
  const before = previous?.matrix?.text.replace(/\n/g, '');
  const after = entry.matrix?.text.replace(/\n/g, '');
  return before && after && before.length === after.length
    ? [...after].reduce((count, value, i) => count +
      (value !== before[i] || entry.matrix.sources[i] !== previous.matrix.sources[i] ? 1 : 0), 0)
    : 0;
}

function snapshot(state, label, defaults, selection) {
  const previous = state.history[state.cursor];
  const settings = clone(state.settings), matrix = clone(state.matrix);
  const changes = previous ? Object.keys(defaults)
    .filter(key => !previewSettings.has(key) && JSON.stringify(previous.settings[key]) !== JSON.stringify(settings[key]))
    .map(key => ({key, before: previous.settings[key], after: settings[key]})) : [];
  const kind = label === '簡易自動修復' ? 'AUTO_RECOVERY' : !previous ? 'IMPORT'
    : label === '設定を変更' ? 'SETTINGS' : label.includes('セル') ? 'USER_EDIT'
    : label.includes('候補') ? 'CANDIDATE' : label === '修復結果' ? 'AUTO_RECOVERY'
    : changes.some(change => ['quad', 'finders', 'orientation', 'version', 'imageOps'].includes(change.key)) ? 'GEOMETRY' : 'SYSTEM';
  return {
    timestamp: Date.now(), kind, changes, changedCells: historyChangedCells({matrix}, previous),
    imageOnly: !!state.imageOnly, automatic: clone(state.automatic || null),
    ...clone(state.automaticTrial || {}), selection,
    oneTimeSingleSegment: !!state.oneTimeSingleSegment, qrrIndex: previous?.qrrIndex ?? -1,
    label, matrix, source: clone(state.source), settings,
  };
}

/** Records one edit transaction; rendering and persistence belong to the caller. */
export function recordHistory(state, label, defaults, currentSelection) {
  if (!state.matrix) return false;
  const prior = state.history[state.cursor];
  if (!['簡易自動修復', '候補を選択'].includes(label) && prior &&
      (JSON.stringify(prior.matrix) !== JSON.stringify(state.matrix) ||
       JSON.stringify(prior.settings) !== JSON.stringify(state.settings))) {
    state.automaticTrial = null;
    state.automatic = null;
  }
  if (label === '設定を変更') state.oneTimeSingleSegment = false;
  // Preserve the saved point before branching or dropping an old entry.
  if (!state.checkpoint && state.checkpointCursor != null && state.history[state.checkpointCursor]) {
    state.checkpoint = clone(state.history[state.checkpointCursor]);
  }
  state.history = state.history.slice(0, state.cursor + 1);
  state.history.push(snapshot(state, label, defaults, currentSelection()));
  if (state.history.length > HISTORY_LIMIT) {
    state.history.shift();
    state.checkpointCursor = Math.max(0, (state.checkpointCursor ?? 0) - 1);
  }
  state.cursor = state.history.length - 1;
  return true;
}

/** Copies saved values so subsequent edits cannot mutate undo/redo entries. */
export function restoreHistoryEntry(state, index, defaults) {
  const entry = state.history[index];
  if (!entry) return null;
  state.cursor = index;
  state.imageOnly = !!entry.imageOnly;
  state.automatic = clone(entry.automatic || null);
  state.automaticTrial = readAutomaticTrial(entry);
  state.oneTimeSingleSegment = !!entry.oneTimeSingleSegment;
  state.matrix = clone(entry.matrix);
  state.source = clone(entry.source);
  state.settings = {...defaults, photographicSource: !!state.image, gridReference: !!entry.settings.quad, ...clone(entry.settings)};
  return entry;
}

export function saveHistoryCheckpoint(state) {
  if (!state.history[state.cursor]) return false;
  state.checkpointCursor = state.cursor;
  state.checkpoint = clone(state.history[state.cursor]);
  return true;
}

/** Makes an evicted or branched-away checkpoint available for normal restoration. */
export function checkpointHistoryIndex(state) {
  if (!state.checkpoint) return state.checkpointCursor ?? 0;
  const key = JSON.stringify(state.checkpoint);
  const index = state.history.findIndex(entry => JSON.stringify(entry) === key);
  if (index >= 0) return index;
  state.history = state.history.slice(0, state.cursor + 1);
  state.history.push(clone(state.checkpoint));
  if (state.history.length > HISTORY_LIMIT) state.history.shift();
  state.checkpointCursor = state.history.length - 1;
  return state.checkpointCursor;
}
