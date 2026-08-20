const test = require("node:test");
const assert = require("node:assert/strict");

const {
  formatNumber,
  formatRate,
  formatRange,
  tableCellDisclosure
} = require("../../main/resources/static/js/features/policy/policy-list.js");

test("POL-W01 displays rates to four decimal places without rounding", () => {
  assert.equal(formatRate("650.000000"), "650.0000");
  assert.equal(formatRate("34.800000"), "34.8000");
  assert.equal(formatRate("1.234567"), "1.2345");
  assert.equal(formatRate("1200"), "1,200.0000");
});

test("POL-W01 formats serialized policy amounts without trailing zeroes", () => {
  assert.equal(formatNumber(1200000, { maximumFractionDigits: 0 }), "1,200,000");
});

test("POL-W01 renders missing or malformed policy numbers as a dash", () => {
  assert.equal(formatNumber(null), "-");
  assert.equal(formatNumber(""), "-");
  assert.equal(formatNumber("   "), "-");
  assert.equal(formatNumber("not-a-number"), "-");
  assert.equal(formatRate(null), "-");
  assert.equal(formatRate("not-a-rate"), "-");
  assert.equal(formatRange(null, null), "-");
  assert.equal(formatRange(null, 12), "- ~ 12회차");
  assert.equal(formatRange(1, null), "1 ~ -회차");
});

test("POL-W01 only provides full-view disclosure for long escaped values", () => {
  assert.equal(tableCellDisclosure("짧은 정책명", 20, false), "짧은 정책명");

  const disclosure = tableCellDisclosure("매우 긴 <정책> 이름과 판단 사유", 10, false);
  assert.match(disclosure, /table-cell-disclosure/);
  assert.match(disclosure, /table-cell-details' hidden/);
  assert.match(disclosure, /전체 보기/);
  assert.match(disclosure, /매우 긴 &lt;정책&gt; 이름과 판단 사유/);
  assert.doesNotMatch(disclosure, /<정책>/);
});
