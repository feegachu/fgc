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
    // 서버 오류 메시지는 "제목 — 상세" 형식을 많이 쓴다(FgcErrorCode 메시지 다수 해당) —
    // 있으면 제목만 본문 크기로, 상세는 그 아래 작은 글씨로 줄바꿔서 보여준다.
    var separatorIndex = message.indexOf(" — ");
    var title = separatorIndex === -1 ? message : message.slice(0, separatorIndex);
    var detail = separatorIndex === -1 ? null : message.slice(separatorIndex + 3);
    var text = document.createElement("p");
    text.className = "toast-message";
    text.textContent = title;
    content.appendChild(text);
    if (detail) {
      var detailText = document.createElement("p");
      detailText.className = "toast-message-detail";
      detailText.textContent = detail;
      content.appendChild(detailText);
    }

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
