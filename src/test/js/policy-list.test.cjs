const test = require("node:test");
const assert = require("node:assert/strict");

const { formatNumber, formatRange } = require("../../main/resources/static/js/features/policy/policy-list.js");

test("POL-W01 formats serialized policy numbers without trailing zeroes", () => {
  assert.equal(formatNumber("650.000000", { maximumFractionDigits: 6 }), "650");
  assert.equal(formatNumber("34.800000", { maximumFractionDigits: 6 }), "34.8");
  assert.equal(formatNumber(1200000, { maximumFractionDigits: 0 }), "1,200,000");
});

test("POL-W01 renders missing or malformed policy numbers as a dash", () => {
  assert.equal(formatNumber(null), "-");
  assert.equal(formatNumber(""), "-");
  assert.equal(formatNumber("   "), "-");
  assert.equal(formatNumber("not-a-number"), "-");
  assert.equal(formatRange(null, null), "-");
  assert.equal(formatRange(null, 12), "- ~ 12회차");
  assert.equal(formatRange(1, null), "1 ~ -회차");
});
