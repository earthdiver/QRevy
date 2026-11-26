// Fail when an upstream edit is missing from a browser-specific source variant.
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const core = new URL('../../androidApp/src/main/java/io/github/earthdiver/qrrepair/core/', import.meta.url);
const overrides = new URL('../src/main/kotlin/overrides/', import.meta.url);
const substitutions = {
  'PartialPayloadAnalyzer.kt': [['java.nio.charset.Charset.forName("Shift_JIS")', 'qrevy.web.Charsets.forName("Shift_JIS")']],
  'QrPayloadTextDecoder.kt': [['Charset.forName("Shift_JIS")', 'qrevy.web.Charsets.forName("Shift_JIS")'], ['Charset.forName("GB2312")', 'qrevy.web.Charsets.forName("GB2312")']],
  'StrictQrPayloadValidator.kt': [['Charset.forName(charset)', 'qrevy.web.Charsets.forName(charset)']],
  'RecoveryMemoryPolicy.kt': [[
    '        fun current(): RecoveryMemoryPolicy = Runtime.getRuntime().let {\n            forHeap(it.maxMemory(), it.totalMemory() - it.freeMemory())\n        }',
    '        // Web adaptation: Safari does not expose Java heap statistics. Fixed conservative budgets\n        // preserve explicit solver accounting without claiming a measurement of browser memory.\n        fun current(): RecoveryMemoryPolicy = RecoveryMemoryPolicy(32 * MIB, 32 * MIB, 32 * MIB)'
  ]]
};
for (const [name, replacements] of Object.entries(substitutions)) {
  let expected = await readFile(new URL(name, core), 'utf8');
  for (const [before, after] of replacements) {
    assert.equal(expected.split(before).length, 2, `Review the browser adaptation of ${name}`);
    expected = expected.replace(before, after);
  }
  assert.equal(await readFile(new URL('Browser' + name, overrides), 'utf8'), expected,
    `Synchronize the browser adaptation of ${name} with the repository source`);
}
console.log('Browser source adaptations match the repository.');
