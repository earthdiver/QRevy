import test from 'node:test';
import assert from 'node:assert/strict';
import {recordHistory, restoreHistoryEntry, historyChangedCells, saveHistoryCheckpoint, checkpointHistoryIndex} from '../web/workspace-history.js';

const defaults = {version: 1, black: 96, quad: null, gridReference: false, previewQuad: null, photographicSource: false};
function workspace() {
  return {
    matrix: {text: '01\n?*', sources: '0156'}, source: {text: '01\n??', sources: '0000'},
    settings: {...defaults}, history: [], cursor: -1, image: null,
  };
}
function record(state, label = 'セル編集', selection = () => null) {
  return recordHistory(state, label, defaults, selection);
}

test('undo and redo restore independent matrices, manual provenance, and settings', () => {
  const state = workspace();
  record(state, 'TXT行列');
  const initial = structuredClone(state.history[0]);
  state.matrix.text = '11\n?*';
  state.matrix.sources = '1156';
  state.settings.black = 80;
  record(state);
  const edited = structuredClone(state.history[1]);
  restoreHistoryEntry(state, 0, defaults);
  assert.equal(state.history.length, 2);
  assert.deepEqual(state.matrix, initial.matrix);
  assert.deepEqual(state.source, initial.source);
  state.matrix.sources = '0000';
  state.settings.black = 1;
  assert.deepEqual(state.history[0], initial);
  restoreHistoryEntry(state, 1, defaults);
  assert.deepEqual(state.matrix, edited.matrix);
  assert.deepEqual(state.settings, edited.settings);
  assert.equal(state.cursor, 1);
});

test('editing after undo branches without changing the retained history or saved checkpoint', () => {
  const state = workspace();
  record(state, 'TXT行列');
  state.settings.black = 80; record(state, '設定を変更');
  saveHistoryCheckpoint(state);
  const checkpoint = structuredClone(state.checkpoint);
  state.settings.black = 70; record(state, '設定を変更');
  restoreHistoryEntry(state, 0, defaults);
  const initial = structuredClone(state.history[0]);
  state.settings.black = 60; record(state, '設定を変更');
  assert.equal(state.history.length, 2);
  assert.equal(state.cursor, 1);
  assert.deepEqual(state.history[0], initial);
  assert.deepEqual(state.checkpoint, checkpoint);
  restoreHistoryEntry(state, checkpointHistoryIndex(state), defaults);
  assert.equal(state.settings.black, 80);
  assert.equal(state.cursor, 2);
  assert.deepEqual(state.checkpoint, checkpoint);
});

test('the forty-entry limit preserves a cursor-only checkpoint before eviction', () => {
  const state = workspace();
  record(state, 'TXT行列');
  state.checkpointCursor = 0;
  const saved = structuredClone(state.history[0]);
  for (let i = 0; i < 45; i++) {
    state.settings.black = i;
    record(state, '設定を変更');
  }
  assert.equal(state.history.length, 40);
  assert.equal(state.cursor, 39);
  assert.equal(state.history[0].settings.black, 5);
  assert.deepEqual(state.checkpoint, saved);
  restoreHistoryEntry(state, checkpointHistoryIndex(state), defaults);
  assert.equal(state.history.length, 40);
  assert.equal(state.cursor, 39);
  assert.deepEqual(state.matrix, saved.matrix);
  assert.deepEqual(state.settings, saved.settings);
  assert.equal(checkpointHistoryIndex(state), 39);
  assert.equal(state.history.length, 40);
});

test('automatic candidates preserve replay conditions until input or settings change', () => {
  const state = workspace();
  record(state, 'TXT行列');
  state.automatic = {status: {selectedIndex: 0}, reports: [{report: 'evidence'}]};
  state.automaticTrial = {automaticScanAssumption: 'UTF8', automaticScanListExtension: 2, samplingInverted: true};
  state.oneTimeSingleSegment = true;
  const selection = {version: 1, ecc: 'L', mask: 0, codewords: [1, 2]};
  state.settings.black = 80;
  record(state, '簡易自動修復', () => selection);
  const automatic = structuredClone(state.history[1]);
  state.settings.black = 70;
  record(state, '候補を選択', () => selection);
  assert.equal(state.history[2].automaticScanAssumption, 'UTF8');
  assert.deepEqual(state.history[2].selection, selection);
  record(state, '設定を変更', () => {
    assert.equal(state.oneTimeSingleSegment, false);
    return null;
  });
  assert.equal(state.history[3].oneTimeSingleSegment, false);
  // An unchanged input still keeps its automatic conditions.
  assert.equal(state.history[3].automaticScanAssumption, 'UTF8');
  state.settings.black = 60;
  record(state, '設定を変更');
  assert.equal(state.history[4].automatic, null);
  assert.equal(state.history[4].automaticScanAssumption, undefined);
  restoreHistoryEntry(state, 1, defaults);
  assert.equal(state.automaticTrial.samplingInverted, true);
  assert.equal(state.oneTimeSingleSegment, true);
  state.automatic.reports[0].report = 'changed';
  assert.deepEqual(state.history[1], automatic);
  state.matrix.sources = '6156';
  record(state);
  assert.equal(state.automaticTrial, null);
  assert.equal(state.automatic, null);
});

test('history counts value and provenance changes while excluding preview metadata', () => {
  const state = workspace();
  record(state, 'TXT行列');
  state.matrix.text = '11\n?*';
  state.matrix.sources = '0656';
  state.settings.previewQuad = [1, 2];
  state.settings.gridReference = true;
  state.settings.black = 80;
  record(state);
  const entry = state.history[1];
  assert.equal(entry.kind, 'USER_EDIT');
  assert.equal(entry.changedCells, 2);
  assert.deepEqual(entry.changes, [{key: 'black', before: 96, after: 80}]);
  assert.equal(historyChangedCells({...entry, changedCells: undefined}, state.history[0]), 2);
  assert.equal(historyChangedCells({...entry, changedCells: 0}, state.history[0]), 0);
  assert.equal(historyChangedCells({matrix: {text: '?', sources: '0'}}, state.history[0]), 0);
});

test('imported entries retain legacy defaults, QRR identity, and explicit geometry flags', () => {
  const state = workspace();
  record(state, 'TXT行列');
  const entry = state.history[0];
  entry.settings = {quad: [1, 2, 3, 4]};
  entry.qrrIndex = 7;
  entry.automaticScanAssumption = 'SINGLE_SEGMENT';
  state.image = new Blob(['synthetic image marker']);
  restoreHistoryEntry(state, 0, defaults);
  assert.equal(state.settings.photographicSource, true);
  assert.equal(state.settings.gridReference, true);
  assert.equal(state.settings.black, defaults.black);
  assert.deepEqual(state.automaticTrial, {
    automaticScanAssumption: 'SINGLE_SEGMENT', automaticScanListExtension: 1, samplingInverted: false,
  });
  entry.settings.gridReference = false;
  restoreHistoryEntry(state, 0, defaults);
  assert.equal(state.settings.gridReference, false);
  record(state, '修復結果');
  assert.equal(state.history[1].qrrIndex, 7);
});

test('invalid restoration and empty work leave history untouched', () => {
  const state = workspace();
  assert.equal(saveHistoryCheckpoint(state), false);
  assert.equal(checkpointHistoryIndex(state), 0);
  const before = structuredClone(state);
  assert.equal(restoreHistoryEntry(state, -1, defaults), null);
  assert.equal(restoreHistoryEntry(state, 0, defaults), null);
  assert.deepEqual(state, before);
  state.matrix = null;
  assert.equal(record(state), false);
  assert.equal(state.history.length, 0);
});
