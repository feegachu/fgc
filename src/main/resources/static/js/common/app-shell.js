(function () {
  "use strict";

  var MOBILE_SIDEBAR_QUERY = "(max-width: 47.9375rem)";
  var mobileSidebarMedia = window.matchMedia(MOBILE_SIDEBAR_QUERY);
  var sidebar = document.querySelector("[data-app-sidebar]");
  var sidebarToggle = document.querySelector("[data-action='open-app-sidebar']");
  var sidebarBackdrop = document.querySelector(".app-sidebar-backdrop");

  function getSidebarFocusableElements() {
    if (!sidebar) return [];
    return Array.prototype.filter.call(
      sidebar.querySelectorAll("a[href], button:not([disabled]), summary, select:not([disabled]), [tabindex]:not([tabindex='-1'])"),
      function (element) { return element.getClientRects().length > 0; }
    );
  }

  function setMobileSidebarOpen(open, restoreFocus) {
    if (!sidebar || !sidebarToggle || !sidebarBackdrop || !mobileSidebarMedia.matches) return;

    if (!open && restoreFocus) sidebarToggle.focus();

    sidebar.classList.toggle("is-mobile-open", open);
    sidebar.toggleAttribute("inert", !open);
    sidebar.setAttribute("aria-hidden", String(!open));
    sidebarToggle.setAttribute("aria-expanded", String(open));
    sidebarToggle.setAttribute("aria-label", open ? "주요 업무 메뉴 닫기" : "주요 업무 메뉴 열기");
    sidebarBackdrop.classList.toggle("is-visible", open);
    document.body.classList.toggle("is-app-sidebar-open", open);

    if (open) {
      var closeButton = sidebar.querySelector("[data-action='close-app-sidebar']");
      if (closeButton) closeButton.focus();
    }
  }

  function syncSidebarMode() {
    if (!sidebar || !sidebarToggle || !sidebarBackdrop) return;

    if (mobileSidebarMedia.matches) {
      setMobileSidebarOpen(false, false);
      return;
    }

    sidebar.classList.remove("is-mobile-open");
    sidebar.removeAttribute("inert");
    sidebar.removeAttribute("aria-hidden");
    sidebarToggle.setAttribute("aria-expanded", "false");
    sidebarToggle.setAttribute("aria-label", "주요 업무 메뉴 열기");
    sidebarBackdrop.classList.remove("is-visible");
    document.body.classList.remove("is-app-sidebar-open");
  }

  function changeGlobalMonth(select) {
    var url = new URL(window.location.href);
    url.searchParams.set("month", select.value);
    url.searchParams.delete("page");
    window.location.assign(url.pathname + url.search);
  }

  document.addEventListener("change", function (event) {
    var select = event.target.closest("[data-action='change-global-month']");
    if (select) changeGlobalMonth(select);
  });

  document.addEventListener("click", function (event) {
    if (event.target.closest("[data-action='open-app-sidebar']")) {
      setMobileSidebarOpen(true, false);
      return;
    }

    if (event.target.closest("[data-action='close-app-sidebar']")) {
      setMobileSidebarOpen(false, true);
    }
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && sidebar && sidebar.classList.contains("is-mobile-open")) {
      setMobileSidebarOpen(false, true);
      return;
    }

    if (event.key === "Tab" && sidebar && sidebar.classList.contains("is-mobile-open")) {
      var focusableElements = getSidebarFocusableElements();
      var firstElement = focusableElements[0];
      var lastElement = focusableElements[focusableElements.length - 1];
      if (!firstElement || !lastElement) return;

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    }
  });

  mobileSidebarMedia.addEventListener("change", syncSidebarMode);
  syncSidebarMode();
})();
