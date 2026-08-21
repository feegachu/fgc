/**
 * FGC-UI-VRUN-W01 실행 목록 (FUN-041)
 *
 * 이 화면은 Ajax 가 없다 (인터페이스정의서 5-2) — 목록·생성 가능 여부·생성(PRG)까지 서버 렌더링이다.
 * 그래서 이 스크립트가 하는 일은 두 가지뿐이다.
 *
 *   ① PRG 결과 Toast
 *      POST /validation-runs 는 리다이렉트로 돌아오므로 서버가 직접 Toast 를 띄울 수 없다.
 *      ValidationRunViewController 가 플래시로 넘긴 successMessage / errorMessage 를
 *      <main data-success-message> · <main data-error-message> 로 받아 한 번만 띄운다.
 *      전에는 화면 상단 fgc-banner 두 개로 표시했는데, 결과 알림은 Toast 라는 게 가이드 §11 이고
 *      목업(FGC-UI-VRUN-W01/index.html:149-151)도 FGC.toast 를 쓴다.
 *      한 번 읽고 data-* 를 지우는 이유는 뒤로가기로 돌아왔을 때 같은 알림이 다시 뜨지 않게 하기 위해서다.
 *
 *   ② 긴 값 접기·펴기 동기화
 *      실패 사유·확정자 셀은 서버가 table-cell-disclosure 마크업까지 그려 두고,
 *      실제로 잘렸는지는 렌더링 폭을 재야 알 수 있으므로 여기서 판정한다.
 */
(function () {
  "use strict";

  var main = document.getElementById("main-content");
  if (!main) return;

  var toast = window.FgcUi && window.FgcUi.toast;

  function flash(attribute, tone, duration) {
    var message = main.dataset[attribute];
    if (!message) return;
    delete main.dataset[attribute];
    if (typeof toast === "function") toast(message, tone, duration);
  }

  flash("successMessage", "success", 5000);
  flash("errorMessage", "error", 0);

  function syncDisclosures() {
    document.querySelectorAll(".table-cell-disclosure").forEach(function (root) {
      var preview = root.querySelector(".table-cell-preview");
      var details = root.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;
      var truncated = preview.scrollWidth > preview.clientWidth + 1
        || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !truncated;
      if (!truncated) details.open = false;
    });
  }

  window.requestAnimationFrame(syncDisclosures);
  window.addEventListener("resize", function () {
    window.requestAnimationFrame(syncDisclosures);
  });
})();
