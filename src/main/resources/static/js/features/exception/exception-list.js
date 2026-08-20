(function () {
  "use strict";

  const rows = Array.from(document.querySelectorAll(".exception-row"));
  const detailBody = document.getElementById("detail-body");
  const occurrenceBody = document.getElementById("occurrence-body");
  const historyBody = document.getElementById("history-body");
  const selectedBadge = document.getElementById("sel-badge");
  const apiClient = window.FgcUi && window.FgcUi.apiClient;
  const toast = window.FgcUi && window.FgcUi.toast;

  const STATUS_LABELS = {
    NEW: "신규",
    IN_REVIEW: "검토중",
    RESOLVED: "해결",
    REJECTED: "오탐·반려"
  };

  const STATUS_BADGE_CLASSES = {
    NEW: "status-badge-error",
    IN_REVIEW: "status-badge-info",
    RESOLVED: "status-badge-success",
    REJECTED: "status-badge-review"
  };

  const STATUS_BADGE_CLASS_NAMES = Object.values(STATUS_BADGE_CLASSES);

  function syncTableCellDisclosures() {
    document.querySelectorAll(".table-cell-disclosure").forEach((disclosure) => {
      const preview = disclosure.querySelector(".table-cell-preview");
      const details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;

      const isTruncated = preview.scrollWidth > preview.clientWidth + 1
        || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
  }

  function scheduleDisclosureSync() {
    window.requestAnimationFrame(syncTableCellDisclosures);
  }

  if (!detailBody || !occurrenceBody || !historyBody || !selectedBadge) return;

  function formatActionTime(value) {
    return value ? value.replace("T", " ").slice(0, 16) : "-";
  }

  function appendHistory(action, actionLabel) {
    let list = historyBody.querySelector(".exception-history-list");
    if (!list) {
      list = document.createElement("ol");
      list.className = "exception-history-list";
      // 캐시된 wrapper(#history-body 유일한 자식) 안을 교체해야 재선택 후에도 이력이 남는다
      historyBody.firstElementChild.replaceChildren(list);
    }

    const item = document.createElement("li");
    item.className = "exception-history-item";
    const title = document.createElement("strong");
    title.textContent = `${action.actionSeq}. ${actionLabel}`;
    const transition = document.createElement("span");
    transition.className = "fgc-muted";
    transition.textContent = ` · ${STATUS_LABELS[action.fromStatus] || "-"} → ${STATUS_LABELS[action.toStatus] || "-"}`;
    const reason = document.createElement("p");
    reason.style.margin = "4px 0 0";
    reason.textContent = action.reason;
    const meta = document.createElement("small");
    meta.className = "fgc-muted";
    meta.textContent = `${action.actionByLoginId} · ${formatActionTime(action.actionAt)}`;
    item.append(title, transition, reason);
    if (action.evidenceRef) {
      const evidence = document.createElement("p");
      evidence.className = "fgc-muted";
      evidence.style.margin = "4px 0 0";
      evidence.textContent = `증빙: ${action.evidenceRef}`;
      item.append(evidence);
    }
    item.append(meta);
    list.append(item);
  }

  function setStatus(row, status) {
    row.dataset.status = status;
    const label = STATUS_LABELS[status] || status;
    const rowStatus = row.querySelector('[data-role="status"]');
    const detailStatus = detailBody.querySelector('[data-role="status"]');
    [rowStatus, detailStatus].forEach((badge) => {
      if (!badge) return;
      badge.textContent = label;
      badge.title = status;
      badge.classList.remove(...STATUS_BADGE_CLASS_NAMES);
      badge.classList.add(STATUS_BADGE_CLASSES[status] || "status-badge-neutral");
    });
  }

  function updateActionOptions(form, status) {
    form.dataset.currentStatus = status;
    form.querySelectorAll("select[name='actionType'] option[value]").forEach((option) => {
      if (!option.value) return;
      const supported = status === "NEW"
        ? option.dataset.supportsNew === "true"
        : option.dataset.supportsReview === "true";
      option.disabled = !supported;
      option.hidden = !supported;
    });
    form.elements.actionType.value = "";
  }

  function bindActionForm(form, row) {
    if (!form || !apiClient) return;
    const submitButton = form.querySelector("button[type='submit']");
    const error = form.querySelector("[data-action-error]");
    const updateSubmit = () => {
      submitButton.disabled = !form.elements.actionType.value || !form.elements.reason.value.trim();
    };

    form.addEventListener("input", updateSubmit);
    form.addEventListener("change", updateSubmit);
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const actionLabel = form.elements.actionType.selectedOptions[0].textContent.trim();
      submitButton.disabled = true;
      error.textContent = "";

      apiClient.request(`/api/v1/exceptions/${form.dataset.exceptionId}/actions`, {
        method: "POST",
        body: {
          actionType: form.elements.actionType.value,
          reason: form.elements.reason.value.trim(),
          evidenceRef: form.elements.evidenceRef.value.trim() || null
        }
      }).then((envelope) => {
        const action = envelope.data;
        appendHistory(action, actionLabel);
        setStatus(row, action.toStatus);
        if (action.actionType === "ASSIGN") {
          const rowAssignee = row.querySelector('[data-role="assignee"]');
          const detailAssignee = detailBody.querySelector('[data-role="assignee"]');
          if (rowAssignee) rowAssignee.textContent = action.actionByLoginId;
          if (detailAssignee) detailAssignee.textContent = action.actionByLoginId;
        }
        form.elements.reason.value = "";
        form.elements.evidenceRef.value = "";
        if (action.toStatus === "NEW" || action.toStatus === "IN_REVIEW") {
          updateActionOptions(form, action.toStatus);
          updateSubmit();
        } else {
          const notice = document.createElement("p");
          notice.className = "fgc-muted exception-action-notice";
          notice.textContent = "종결된 예외입니다.";
          form.replaceWith(notice);
        }
        if (toast) toast("처리 내용이 저장되었습니다.", "success");
      }).catch((requestError) => {
        const message = requestError.message || "처리 내용을 저장하지 못했습니다.";
        error.textContent = message;
        if (toast) toast(message, "error");
        updateSubmit();
      });
    });
  }

  // template을 매번 새로 clone하면 조치 저장으로 바꾼 이력·폼 상태가 재선택 시 초기값으로
  // 되돌아가므로, 행별로 한 번만 clone한 DOM을 캐시해 세션 중 변경을 유지한다.
  const panelCache = new Map();

  function clonePanel(template) {
    const wrapper = document.createElement("div");
    wrapper.append(template.content.cloneNode(true));
    return wrapper;
  }

  function selectRow(row) {
    const caseId = row.dataset.exceptionId;
    let panels = panelCache.get(caseId);
    if (!panels) {
      const detailTemplate = document.getElementById(`exception-detail-${caseId}`);
      const occurrenceTemplate = document.getElementById(`exception-occurrence-${caseId}`);
      const historyTemplate = document.getElementById(`exception-history-${caseId}`);
      if (!detailTemplate || !occurrenceTemplate || !historyTemplate) return;
      panels = {
        detail: clonePanel(detailTemplate),
        occurrence: clonePanel(occurrenceTemplate),
        history: clonePanel(historyTemplate)
      };
      panelCache.set(caseId, panels);
      bindActionForm(panels.detail.querySelector("[data-exception-action-form]"), row);
    }

    rows.forEach((candidate) => {
      const selected = candidate === row;
      candidate.classList.toggle("is-selected", selected);
      candidate.setAttribute("aria-selected", String(selected));
    });

    detailBody.replaceChildren(panels.detail);
    occurrenceBody.replaceChildren(panels.occurrence);
    historyBody.replaceChildren(panels.history);
    selectedBadge.textContent = `#${caseId}`;
  }

  rows.forEach((row) => {
    row.addEventListener("click", (event) => {
      if (event.target.closest("a, button, details, input, select, textarea")) return;
      selectRow(row);
    });
    row.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      if (event.target.closest("a, button, details, input, select, textarea")) return;
      event.preventDefault();
      selectRow(row);
    });
  });

  const selectedId = document.getElementById("main-content").dataset.selectedExceptionId;
  const initialRow = rows.find((row) => row.dataset.exceptionId === selectedId);
  if (initialRow) selectRow(initialRow);

  scheduleDisclosureSync();
  window.addEventListener("load", scheduleDisclosureSync, { once: true });
  window.addEventListener("resize", scheduleDisclosureSync);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleDisclosureSync);
  }
})();
