const test = require("node:test");
const assert = require("node:assert/strict");

const { normalizePage } = require("../../main/resources/static/js/features/schedule/schedule-list.js");

test("empty schedule result normalizes an out-of-range page to page 1", () => {
  const response = { content: [], page: 999, totalPages: 0, totalElements: 0 };

  assert.equal(normalizePage(response.page, response.totalPages), 1);
});

test("non-empty schedule result keeps valid pages and clamps to the last page", () => {
  assert.equal(normalizePage(2, 5), 2);
  assert.equal(normalizePage(999, 5), 5);
});
