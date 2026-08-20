(function () {
  "use strict";

  function setProgressWidths() {
    document.querySelectorAll("[data-progress]").forEach(function (bar) {
      var value = Number(bar.dataset.progress);
      var safeValue = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
      bar.style.setProperty("--progress", safeValue + "%");
    });
  }

  // 계약번호·예외 내용 셀은 실제 렌더링 폭을 넘칠 때만 "전체 보기"를 드러낸다
  // (exception-list.js와 같은 패턴 — 글자수 기준이 아니라 실측 scrollWidth/scrollHeight 비교).
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

  function init() {
    setProgressWidths();
    scheduleDisclosureSync();
    window.addEventListener("load", scheduleDisclosureSync, { once: true });
    window.addEventListener("resize", scheduleDisclosureSync);
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(scheduleDisclosureSync);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
