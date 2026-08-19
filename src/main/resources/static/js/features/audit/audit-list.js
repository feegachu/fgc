(function () {
  "use strict";

  var rows = Array.from(document.querySelectorAll("[data-audit-detail-href]"));

  function isInteractiveTarget(target) {
    return target instanceof Element
        && target.closest("a, button, input, select, textarea, summary, details") !== null;
  }

  function openDetail(row) {
    var href = row.dataset.auditDetailHref;
    if (href) window.location.assign(href);
  }

  function syncTableCellDisclosures() {
    document.querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
      var preview = disclosure.querySelector(".table-cell-preview");
      var details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;

      var isTruncated = preview.scrollWidth > preview.clientWidth + 1
          || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
  }

  function scheduleDisclosureSync() {
    window.requestAnimationFrame(syncTableCellDisclosures);
  }

  rows.forEach(function (row) {
    row.addEventListener("click", function (event) {
      if (isInteractiveTarget(event.target)) return;
      openDetail(row);
    });

    row.addEventListener("keydown", function (event) {
      if (event.key !== "Enter" && event.key !== " ") return;
      if (event.target !== row) return;
      event.preventDefault();
      openDetail(row);
    });
  });

  scheduleDisclosureSync();
  window.addEventListener("load", scheduleDisclosureSync, { once: true });
  window.addEventListener("resize", scheduleDisclosureSync);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleDisclosureSync);
  }
})();
