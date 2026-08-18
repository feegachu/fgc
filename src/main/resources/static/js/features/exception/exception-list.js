(function () {
  "use strict";

  const rows = Array.from(document.querySelectorAll(".exception-row"));
  const detailBody = document.getElementById("detail-body");
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

  if (!detailBody || !historyBody || !selectedBadge) return;

  function formatActionTime(value) {
    return value ? value.replace("T", " ").slice(0, 16) : "-";
  }

  function appendHistory(action, actionLabel) {
    let list = historyBody.querySelector(".exception-history-list");
    if (!list) {
      list = document.createElement("ol");
      list.className = "exception-history-list";
      historyBody.replaceChildren(list);
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
    if (rowStatus) rowStatus.textContent = label;
    if (detailStatus) detailStatus.textContent = label;
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
        error.textContent = requestError.message || "처리 내용을 저장하지 못했습니다.";
        updateSubmit();
      });
    });
  }

  function selectRow(row) {
    const caseId = row.dataset.exceptionId;
    const detailTemplate = document.getElementById(`exception-detail-${caseId}`);
    const historyTemplate = document.getElementById(`exception-history-${caseId}`);
    if (!detailTemplate || !historyTemplate) return;

    rows.forEach((candidate) => {
      const selected = candidate === row;
      candidate.classList.toggle("is-selected", selected);
      candidate.setAttribute("aria-selected", String(selected));
    });

    detailBody.replaceChildren(detailTemplate.content.cloneNode(true));
    historyBody.replaceChildren(historyTemplate.content.cloneNode(true));
    selectedBadge.textContent = `#${caseId}`;
    bindActionForm(detailBody.querySelector("[data-exception-action-form]"), row);
  }

  rows.forEach((row) => {
    row.addEventListener("click", (event) => {
      if (event.target.closest("a")) return;
      selectRow(row);
    });
    row.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      if (event.target.closest("a")) return;
      event.preventDefault();
      selectRow(row);
    });
  });

  const selectedId = document.getElementById("main-content").dataset.selectedExceptionId;
  const initialRow = rows.find((row) => row.dataset.exceptionId === selectedId);
  if (initialRow) selectRow(initialRow);
})();
