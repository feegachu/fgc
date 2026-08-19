(function () {
  "use strict";

  var NARROW_SIDEBAR_QUERY = "(max-width: 79.9375rem)";
  var SIDEBAR_OPEN_SECTIONS_KEY = "fgc.sidebar.open-sections.v1";
  var SIDEBAR_COLLAPSED_KEY = "fgc.sidebar.collapsed.v1";
  var narrowSidebarMedia = window.matchMedia(NARROW_SIDEBAR_QUERY);
  var sidebar = document.querySelector("[data-app-sidebar]");
  var collapseButton = document.querySelector("[data-action='collapse-app-sidebar']");
  var expandButton = document.querySelector("[data-action='expand-app-sidebar']");

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

  function readSidebarCollapsed() {
    try {
      var stored = window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY);
      if (stored === null) return narrowSidebarMedia.matches;
      return stored === "true";
    } catch (error) {
      return narrowSidebarMedia.matches;
    }
  }

  function hasStoredSidebarPreference() {
    try {
      return window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) !== null;
    } catch (error) {
      return false;
    }
  }

  function persistSidebarCollapsed(collapsed) {
    try {
      window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed));
    } catch (error) {
      // Storage may be unavailable in privacy-restricted browsing contexts.
    }
  }

  function setSidebarCollapsed(collapsed, persist) {
    if (!sidebar) return;

    document.body.classList.toggle("is-sidebar-collapsed", collapsed);
    document.body.classList.add("is-sidebar-ready");
    sidebar.dataset.sidebarState = collapsed ? "collapsed" : "expanded";
    if (collapseButton) collapseButton.setAttribute("aria-expanded", String(!collapsed));
    if (expandButton) expandButton.setAttribute("aria-expanded", String(!collapsed));
    if (persist) persistSidebarCollapsed(collapsed);
  }

  function initializeSidebarMode() {
    setSidebarCollapsed(readSidebarCollapsed(), false);
  }

  function syncNarrowSidebarDefault() {
    if (!hasStoredSidebarPreference()) setSidebarCollapsed(narrowSidebarMedia.matches, false);
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
              resolve();
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
    if (event.target.closest("[data-action='collapse-app-sidebar']")) {
      setSidebarCollapsed(true, true);
      return;
    }

    if (event.target.closest("[data-action='expand-app-sidebar']")) {
      setSidebarCollapsed(false, true);
      return;
    }

    if (sidebar && document.body.classList.contains("is-sidebar-collapsed") && sidebar.contains(event.target)
        && !event.target.closest("input, select, textarea, form")) {
      var sectionSummary = event.target.closest("summary");
      setSidebarCollapsed(false, true);
      if (sectionSummary) {
        event.preventDefault();
        var section = sectionSummary.closest("details");
        if (section) section.open = true;
      }
    }
  });

  narrowSidebarMedia.addEventListener("change", syncNarrowSidebarDefault);
  initializeGlobalMonthSelector();
  initializeSidebarSectionState();
  initializeSidebarMode();
})();
