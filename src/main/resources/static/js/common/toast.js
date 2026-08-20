(function () {
  "use strict";

  var ICONS = {
    info: "info",
    success: "check_circle",
    warning: "warning",
    error: "error",
    loading: "progress_activity"
  };

  function removeToast(toast) {
    if (!toast || !toast.parentNode) return;
    toast.remove();
  }

  function showToast(message, tone, duration) {
    var region = document.getElementById("toast-region");
    if (!region || !message) return null;

    var safeTone = Object.prototype.hasOwnProperty.call(ICONS, tone) ? tone : "info";
    var toast = document.createElement("div");
    toast.className = "toast toast-" + safeTone;
    toast.setAttribute("role", safeTone === "error" ? "alert" : "status");
    toast.setAttribute("aria-atomic", "true");

    var iconContainer = document.createElement("span");
    iconContainer.className = "toast-icon";
    iconContainer.setAttribute("aria-hidden", "true");
    var icon = document.createElement("span");
    icon.className = "material-symbols-rounded";
    icon.textContent = ICONS[safeTone];
    iconContainer.appendChild(icon);

    var content = document.createElement("div");
    content.className = "toast-content";
    var text = document.createElement("p");
    text.className = "toast-message";
    text.textContent = message;
    content.appendChild(text);

    var close = document.createElement("button");
    close.className = "icon-button toast-close";
    close.type = "button";
    close.setAttribute("aria-label", "알림 닫기");
    close.appendChild(document.createElement("span"));
    close.firstChild.className = "material-symbols-rounded";
    close.firstChild.setAttribute("aria-hidden", "true");
    close.firstChild.textContent = "close";
    close.addEventListener("click", function () { removeToast(toast); });

    toast.append(iconContainer, content, close);
    region.appendChild(toast);

    var timeout = typeof duration === "number"
      ? duration
      : (safeTone === "error" || safeTone === "loading" ? 0 : 5000);
    if (timeout > 0) {
      window.setTimeout(function () { removeToast(toast); }, timeout);
    }
    return toast;
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.toast = showToast;
})();
