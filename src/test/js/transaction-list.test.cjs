const test = require("node:test");
const assert = require("node:assert/strict");

const { buildTransactionFilterState } = require(
  "../../main/resources/static/js/features/transaction/transaction-list.js"
);

function fields() {
  return {
    month: { value: "2026-08" },
    stage: { value: "GA_TO_FC" },
    insurer: { value: "1" },
    contractNo: { value: "  C004  " },
    source: { value: "GA_CONFIRMED_PAYMENT" },
    agent: { value: "2" },
    item: { value: "3" },
    status: { value: "CONFIRMED" },
    noAttribution: { checked: false }
  };
}

test("FGC-FUN-044 TRAN-W01 keeps the attribution imbalance filter when submitting a search", () => {
  const state = buildTransactionFilterState(fields(), 1, true);

  assert.equal(state.attributionImbalanceOnly, true);
  assert.equal(state.settlementMonth, "2026-08");
  assert.equal(state.contractNo, "C004");
});

test("FGC-FUN-044 TRAN-W01 clears the hidden attribution imbalance filter on reset", () => {
  const state = buildTransactionFilterState(fields(), 1, false);

  assert.equal(state.attributionImbalanceOnly, false);
});
