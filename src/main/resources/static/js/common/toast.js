(function () {
  "use strict";

  var ICONS = {
    info: "info",
    success: "check_circle",
    warning: "warning",
    error: "error"
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

    var icon = document.createElement("span");
    icon.className = "material-symbols-rounded";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = ICONS[safeTone];

    var text = document.createElement("p");
    text.className = "toast-message";
    text.textContent = message;

    var close = document.createElement("button");
    close.className = "icon-button";
    close.type = "button";
    close.setAttribute("aria-label", "알림 닫기");
    close.appendChild(document.createElement("span"));
    close.firstChild.className = "material-symbols-rounded";
    close.firstChild.setAttribute("aria-hidden", "true");
    close.firstChild.textContent = "close";
    close.addEventListener("click", function () { removeToast(toast); });

    toast.append(icon, text, close);
    region.appendChild(toast);

    window.setTimeout(function () { removeToast(toast); }, duration || 5000);
    return toast;
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.toast = showToast;
})();
