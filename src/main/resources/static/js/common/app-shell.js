(function () {
  "use strict";

  var MOBILE_SIDEBAR_QUERY = "(max-width: 47.9375rem)";
  var SIDEBAR_OPEN_SECTIONS_KEY = "fgc.sidebar.open-sections.v1";
  var mobileSidebarMedia = window.matchMedia(MOBILE_SIDEBAR_QUERY);
  var sidebar = document.querySelector("[data-app-sidebar]");
  var sidebarToggle = document.querySelector("[data-action='open-app-sidebar']");
  var sidebarBackdrop = document.querySelector(".app-sidebar-backdrop");

  function getSidebarSections() {
    if (!sidebar) return [];
    return Array.prototype.slice.call(sidebar.querySelectorAll("details[data-sidebar-section]"));
  }

  function readOpenSidebarSections() {
    try {
      var stored = JSON.parse(window.sessionStorage.getItem(SIDEBAR_OPEN_SECTIONS_KEY) || "[]");
      return Array.isArray(stored) ? stored : [];
    } catch (error) {
      return [];
    }
  }

  function persistOpenSidebarSections() {
    try {
      var openSections = getSidebarSections()
        .filter(function (section) { return section.open; })
        .map(function (section) { return section.getAttribute("data-sidebar-section"); });
      window.sessionStorage.setItem(SIDEBAR_OPEN_SECTIONS_KEY, JSON.stringify(openSections));
    } catch (error) {
      // Storage may be unavailable in privacy-restricted browsing contexts.
    }
  }

  function initializeSidebarSectionState() {
    var sections = getSidebarSections();
    var savedOpenSections = readOpenSidebarSections();

    sections.forEach(function (section) {
      var sectionId = section.getAttribute("data-sidebar-section");
      if (savedOpenSections.indexOf(sectionId) !== -1) section.open = true;
      section.addEventListener("toggle", persistOpenSidebarSections);
    });

    // Preserve both the server-opened active section and previously opened sections.
    persistOpenSidebarSections();
  }

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

  function navigateToGlobalMonth(month) {
    var url = new URL(window.location.href);
    url.searchParams.set("month", month);
    url.searchParams.delete("page");
    window.location.assign(url.pathname + url.search);
  }

  function initializeGlobalMonthSelector() {
    var root = document.querySelector("[data-month-selector]");
    if (!root || !window.FgcUi || !window.FgcUi.createMonthSelector) return;

    window.FgcUi.createMonthSelector(root, {
      getOpenTabCount: function () {
        return window.FgcUi.workspaceTabs ? window.FgcUi.workspaceTabs.getOpenTabCount() : 1;
      },
      onApply: function (month) {
        return new Promise(function (resolve, reject) {
          var previousTabs = null;
          window.requestAnimationFrame(function () {
            try {
              if (window.FgcUi.workspaceTabs) previousTabs = window.FgcUi.workspaceTabs.applyGlobalMonth(month);
              navigateToGlobalMonth(month);
            } catch (error) {
              if (window.FgcUi.workspaceTabs && previousTabs) window.FgcUi.workspaceTabs.restoreTabs(previousTabs);
              reject(error);
            }
          });
        });
      }
    });
  }

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
  initializeGlobalMonthSelector();
  initializeSidebarSectionState();
  syncSidebarMode();
})();
