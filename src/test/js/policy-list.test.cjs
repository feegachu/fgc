const test = require("node:test");
const assert = require("node:assert/strict");

const { formatNumber, formatRate, formatRange } = require("../../main/resources/static/js/features/policy/policy-list.js");

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
