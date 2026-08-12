(function () {
  "use strict";

  var STORAGE_KEY = "fgc.workspace-tabs.v1";
  var MAX_TABS = 10;
  var DASHBOARD_TAB_ID = "FGC-UI-DASH-W01";

  function readTabs() {
    try {
      var value = window.sessionStorage.getItem(STORAGE_KEY);
      var parsed = value ? JSON.parse(value) : [];
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isValidTab).map(function (tab) {
        return tab.href === "/dashboard"
          ? { id: DASHBOARD_TAB_ID, title: "업무 대시보드", href: "/", icon: "dashboard" }
          : tab;
      });
    } catch (error) {
      return [];
    }
  }

  function isValidTab(tab) {
    return tab && typeof tab.id === "string" && typeof tab.title === "string" && typeof tab.href === "string";
  }

  function writeTabs(tabs) {
    try {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tabs.slice(-MAX_TABS)));
    } catch (error) {
      // Storage can be unavailable in hardened browser contexts. Navigation still works.
    }
  }

  function currentTab() {
    var body = document.body;
    var id = body.dataset.workspaceTabId;
    var title = body.dataset.workspaceTabTitle || document.title.replace(/\s*\|\s*FGC\s*$/, "");
    if (!id || !title) return null;

    return {
      id: id,
      title: title,
      href: window.location.pathname + window.location.search,
      icon: body.dataset.workspaceTabIcon || "description"
    };
  }

  function upsertCurrentTab(tabs, tab) {
    var index = tabs.findIndex(function (item) { return item.id === tab.id; });
    if (index >= 0) {
      tabs[index] = tab;
      return tabs;
    }

    tabs.push(tab);
    if (tabs.length > MAX_TABS) {
      var removable = tabs.findIndex(function (item) { return item.id !== DASHBOARD_TAB_ID; });
      tabs.splice(removable >= 0 ? removable : 0, 1);
    }
    return tabs;
  }

  function createIcon(name) {
    var icon = document.createElement("span");
    icon.className = "material-symbols-rounded";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = name;
    return icon;
  }

  function createTabElement(tab, activeId) {
    var tabElement = document.createElement("div");
    var isActive = tab.id === activeId;
    tabElement.className = "workspace-tab" + (isActive ? " is-active" : "");
    tabElement.dataset.tabId = tab.id;

    var link = document.createElement("a");
    link.className = "workspace-tab-link";
    link.href = tab.href;
    if (isActive) link.setAttribute("aria-current", "page");
    link.appendChild(createIcon(tab.icon));

    var label = document.createElement("span");
    label.className = "workspace-tab-label";
    label.textContent = tab.title;
    link.appendChild(label);
    tabElement.appendChild(link);

    var close = document.createElement("button");
    close.className = "workspace-tab-close";
    close.type = "button";
    close.dataset.action = "close-workspace-tab";
    close.dataset.tabId = tab.id;
    close.setAttribute("aria-label", tab.title + " 탭 닫기");
    close.appendChild(createIcon("close"));
    tabElement.appendChild(close);

    return tabElement;
  }

  function renderTabs(container, tabs, activeId) {
    var fragment = document.createDocumentFragment();
    tabs.forEach(function (tab) {
      fragment.appendChild(createTabElement(tab, activeId));
    });
    container.replaceChildren(fragment);

    var active = container.querySelector(".workspace-tab.is-active");
    if (active) active.scrollIntoView({ block: "nearest", inline: "nearest" });
  }

  function closeTab(tabId, tabs, activeId, render) {
    var index = tabs.findIndex(function (tab) { return tab.id === tabId; });
    if (index < 0) return;
    if (tabs.length === 1 && tabId === DASHBOARD_TAB_ID) return;

    var wasActive = tabId === activeId;
    tabs.splice(index, 1);

    if (!tabs.length) {
      tabs.push({ id: DASHBOARD_TAB_ID, title: "업무 대시보드", href: "/", icon: "dashboard" });
    }

    writeTabs(tabs);
    render(tabs, activeId);
    if (wasActive) {
      var next = tabs[Math.min(index, tabs.length - 1)];
      window.location.assign(next.href);
    }
  }

  function updateOverflowState(container, overflowButton) {
    if (!overflowButton) return;
    overflowButton.hidden = container.scrollWidth <= container.clientWidth + 1;
  }

  function initWorkspaceTabs() {
    var container = document.querySelector("[data-workspace-tabs]");
    if (!container) return;

    var current = currentTab();
    if (!current) return;

    var tabs = upsertCurrentTab(readTabs(), current);
    writeTabs(tabs);
    var overflowButton = document.querySelector("[data-action='open-workspace-overflow']");
    function render(updatedTabs, activeId) {
      renderTabs(container, updatedTabs, activeId);
      updateOverflowState(container, overflowButton);
    }
    render(tabs, current.id);

    container.addEventListener("click", function (event) {
      var closeButton = event.target.closest("[data-action='close-workspace-tab']");
      if (!closeButton) return;
      event.preventDefault();
      event.stopPropagation();
      closeTab(closeButton.dataset.tabId, tabs, current.id, render);
    });

    if (overflowButton) {
      overflowButton.addEventListener("click", function () {
        container.scrollBy({ left: 220, behavior: "smooth" });
      });
    }

    window.addEventListener("resize", function () {
      updateOverflowState(container, overflowButton);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initWorkspaceTabs, { once: true });
  } else {
    initWorkspaceTabs();
  }
})();
