/*
 * 근거 표시 동작 — 화면정의서 4장 규칙 5 "요율·한도·판정이 나오는 곳에는 마우스를 올리면 근거가 뜹니다".
 *
 * 여는 방법은 세 가지다.
 *   호버   — 규칙 5 의 문구 그대로
 *   포커스 — 키보드만 쓰는 사용자도 근거에 닿아야 한다
 *   클릭   — 터치 환경에는 호버가 없다. 클릭으로 열면 고정되어 마우스를 떼도 닫히지 않는다
 *
 * 마크업은 components.css 의 Evidence Popover 를 따른다.
 *   <span class="evidence" data-evidence>
 *     <button class="evidence-trigger" type="button" aria-describedby="ev-1">…</button>
 *     <div class="evidence-popover" id="ev-1" popover>…</div>
 *   </span>
 *
 * 위치를 JS 가 잡는 이유: popover 는 top layer 라 표 셀의 overflow 에 잘리지 않지만,
 * UA 기본 위치가 화면 중앙이라 호버로 열면 시선이 엉뚱한 곳으로 튄다.
 */
(function () {
  "use strict";

  var GAP = 8;
  var CLOSE_DELAY = 120;
  var supported = typeof HTMLElement !== "undefined"
    && typeof HTMLElement.prototype.togglePopover === "function";

  function place(trigger, popover) {
    var anchor = trigger.getBoundingClientRect();
    var box = popover.getBoundingClientRect();

    /* 아래에 자리가 없으면 위로 뒤집는다. */
    var top = anchor.bottom + GAP;
    if (top + box.height > window.innerHeight - GAP) {
      var above = anchor.top - box.height - GAP;
      if (above >= GAP) top = above;
      else top = Math.max(GAP, window.innerHeight - box.height - GAP);
    }

    /* 트리거 왼쪽에 맞추되 화면 밖으로 나가지 않게 가둔다. */
    var left = Math.min(anchor.left, window.innerWidth - box.width - GAP);
    left = Math.max(GAP, left);

    popover.style.setProperty("--evidence-x", left + "px");
    popover.style.setProperty("--evidence-y", top + "px");
  }

  function setup(root) {
    var trigger = root.querySelector(".evidence-trigger");
    var popover = root.querySelector(".evidence-popover");
    if (!trigger || !popover || root.dataset.evidenceReady === "true") return;
    root.dataset.evidenceReady = "true";

    /* popover 를 못 쓰면 근거를 그냥 펼쳐 둔다 — 숨겨서 못 읽게 하지 않는다. */
    if (!supported) {
      popover.removeAttribute("popover");
      trigger.hidden = true;
      return;
    }

    var closeTimer = null;
    var pinned = false;

    function open() {
      if (closeTimer) {
        window.clearTimeout(closeTimer);
        closeTimer = null;
      }
      if (popover.matches(":popover-open")) return;
      popover.showPopover();
      place(trigger, popover);
    }

    function close(immediate) {
      if (pinned) return;
      if (closeTimer) window.clearTimeout(closeTimer);
      closeTimer = window.setTimeout(function () {
        closeTimer = null;
        if (!pinned && popover.matches(":popover-open")) popover.hidePopover();
      }, immediate ? 0 : CLOSE_DELAY);
    }

    root.addEventListener("mouseenter", open);
    root.addEventListener("mouseleave", function () {
      close(false);
    });

    /* 포커스로 열되, 포커스가 이 묶음 밖으로 나가면 닫는다. */
    root.addEventListener("focusin", open);
    root.addEventListener("focusout", function (event) {
      if (!root.contains(event.relatedTarget)) close(true);
    });

    /* 클릭은 고정 토글이다. 마우스를 떼도 닫히지 않아 내용을 복사할 수 있다. */
    trigger.addEventListener("click", function () {
      if (pinned) {
        pinned = false;
        close(true);
        return;
      }
      pinned = true;
      open();
    });

    /* Esc·바깥 클릭으로 브라우저가 닫으면 고정도 함께 푼다. */
    popover.addEventListener("toggle", function (event) {
      if (event.newState === "closed") pinned = false;
    });

    window.addEventListener("resize", function () {
      if (popover.matches(":popover-open")) place(trigger, popover);
    });
  }

  function scan(scope) {
    (scope || document).querySelectorAll("[data-evidence]").forEach(setup);
  }

  scan(document);

  window.FgcUi = window.FgcUi || {};
  /* 표를 다시 그린 화면이 새로 붙은 근거를 등록할 때 쓴다. */
  window.FgcUi.evidence = { scan: scan };
})();
