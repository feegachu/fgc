(() => {
  "use strict";

  const form = document.querySelector("[data-contract-filter-form]");
  const resetButton = document.querySelector("[data-contract-filter-reset]");
  const rows = Array.from(document.querySelectorAll("[data-contract-row]"))
    .filter((row) => !row.classList.contains("contract-server-row") || row.classList.contains("is-rendered"));
  const emptyRow = document.querySelector("[data-contract-empty-row]");
  const resultCounts = document.querySelectorAll("[data-contract-result-count], [data-contract-pagination-count]");

  if (!form || rows.length === 0) {
    return;
  }

  const normalize = (value) => String(value || "").trim().toLocaleUpperCase("ko-KR");

  const getFilters = () => {
    const data = new FormData(form);
    return {
      contractNo: normalize(data.get("contractNo")),
      insurer: normalize(data.get("insurer")),
      product: normalize(data.get("product")),
      agent: normalize(data.get("agent")),
      dateFrom: String(data.get("dateFrom") || ""),
      dateTo: String(data.get("dateTo") || ""),
      status: normalize(data.get("status")),
      capStatus: normalize(data.get("capStatus"))
    };
  };

  const matches = (row, filters) => {
    const rowData = row.dataset;
    return (!filters.contractNo || normalize(rowData.contractNo).includes(filters.contractNo))
      && (!filters.insurer || normalize(rowData.insurer) === filters.insurer)
      && (!filters.product || normalize(rowData.product) === filters.product)
      && (!filters.agent || normalize(rowData.agent) === filters.agent)
      && (!filters.dateFrom || rowData.date >= filters.dateFrom)
      && (!filters.dateTo || rowData.date <= filters.dateTo)
      && (!filters.status || normalize(rowData.status) === filters.status)
      && (!filters.capStatus || normalize(rowData.capStatus) === filters.capStatus);
  };

  const render = () => {
    const filters = getFilters();
    let visibleCount = 0;

    rows.forEach((row) => {
      const visible = matches(row, filters);
      row.hidden = !visible;
      if (visible) {
        visibleCount += 1;
      }
    });

    resultCounts.forEach((count) => {
      count.textContent = String(visibleCount);
    });

    if (emptyRow) {
      emptyRow.hidden = visibleCount !== 0;
    }
  };

  let inputTimer;
  form.addEventListener("input", () => {
    window.clearTimeout(inputTimer);
    inputTimer = window.setTimeout(render, 120);
  });
  form.addEventListener("change", render);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    render();
  });

  if (resetButton) {
    resetButton.addEventListener("click", () => {
      window.requestAnimationFrame(render);
    });
  }

  render();
})();
