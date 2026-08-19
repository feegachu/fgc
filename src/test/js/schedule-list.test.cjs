const test = require("node:test");
const assert = require("node:assert/strict");

const { normalizePage, responseLabel } = require("../../main/resources/static/js/features/schedule/schedule-list.js");

test("empty schedule result normalizes an out-of-range page to page 1", () => {
  const response = { content: [], page: 999, totalPages: 0, totalElements: 0 };

  assert.equal(normalizePage(response.page, response.totalPages), 1);
});

test("FGC-QUR-001 non-empty result preserves boundaries and rejects malformed pages", () => {
  assert.equal(normalizePage(1, 5), 1);
  assert.equal(normalizePage(2, 5), 2);
  assert.equal(normalizePage(999, 5), 5);
  assert.equal(normalizePage("2abc", 5), 1);
});

test("schedule labels prefer API values and keep code-map fallback", () => {
  const labels = { PLANNED: "예정" };

  assert.equal(responseLabel("서버 예정", labels, "PLANNED"), "서버 예정");
  assert.equal(responseLabel("", labels, "PLANNED"), "예정");
  assert.equal(responseLabel(null, labels, "UNKNOWN"), "UNKNOWN");
});
