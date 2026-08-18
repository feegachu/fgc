(function () {
  "use strict";

  const rows = Array.from(document.querySelectorAll(".exception-row"));
  const detailBody = document.getElementById("detail-body");
  const historyBody = document.getElementById("history-body");
  const selectedBadge = document.getElementById("sel-badge");

  if (!detailBody || !historyBody || !selectedBadge) return;

  function selectRow(row) {
    const caseId = row.dataset.exceptionId;
    const detailTemplate = document.getElementById(`exception-detail-${caseId}`);
    const historyTemplate = document.getElementById(`exception-history-${caseId}`);
    if (!detailTemplate || !historyTemplate) return;

    rows.forEach((candidate) => {
      const selected = candidate === row;
      candidate.classList.toggle("is-selected", selected);
      candidate.setAttribute("aria-selected", String(selected));
    });

    detailBody.replaceChildren(detailTemplate.content.cloneNode(true));
    historyBody.replaceChildren(historyTemplate.content.cloneNode(true));
    selectedBadge.textContent = `#${caseId}`;
  }

  rows.forEach((row) => {
    row.addEventListener("click", (event) => {
      if (event.target.closest("a")) return;
      selectRow(row);
    });
    row.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      if (event.target.closest("a")) return;
      event.preventDefault();
      selectRow(row);
    });
  });
})();
