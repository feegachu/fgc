(function () {
  "use strict";

  var storageKey = "fgc.sidebar.collapsed.v1";
  var narrowSidebarQuery = "(max-width: 79.9375rem)";
  var collapsed;

  try {
    var stored = window.localStorage.getItem(storageKey);
    collapsed = stored === null ? window.matchMedia(narrowSidebarQuery).matches : stored === "true";
  } catch (error) {
    collapsed = window.matchMedia(narrowSidebarQuery).matches;
  }

  document.body.classList.toggle("is-sidebar-collapsed", collapsed);
  document.body.classList.add("is-sidebar-ready");
})();
