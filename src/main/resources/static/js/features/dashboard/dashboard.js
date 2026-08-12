(function () {
  "use strict";

  function setProgressWidths() {
    document.querySelectorAll("[data-progress]").forEach(function (bar) {
      var value = Number(bar.dataset.progress);
      var safeValue = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
      bar.style.setProperty("--progress", safeValue + "%");
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", setProgressWidths, { once: true });
  } else {
    setProgressWidths();
  }
})();
