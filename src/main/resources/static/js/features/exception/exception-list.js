(function () {
  "use strict";

  const rows = Array.from(document.querySelectorAll(".exception-row"));
  const detailBody = document.getElementById("detail-body");
  const occurrenceBody = document.getElementById("occurrence-body");
  const historyBody = document.getElementById("history-body");
  const selectedBadge = document.getElementById("sel-badge");
  const apiClient = window.FgcUi && window.FgcUi.apiClient;
  const toast = window.FgcUi && window.FgcUi.toast;
  const accountOptionsTemplate = document.getElementById("journal-account-options");

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
        : status === "REJECTED"
          ? option.dataset.supportsRejected === "true"
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
        const shouldOpenJournalCorrection = row.dataset.exceptionType === "JOURNAL_CORRECTION_REQUIRED"
            && (action.actionType === "START_REVIEW" || action.actionType === "REOPEN")
            && action.toStatus === "IN_REVIEW";
        appendHistory(action, actionLabel);
        setStatus(row, action.toStatus);
        if (row.dataset.exceptionType === "JOURNAL_CORRECTION_REQUIRED") {
          setJournalCorrectionMode(
            form.parentElement.querySelector("[data-journal-correction-form]"), action.toStatus);
        }
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
        // 2026-08-20 yslee - 검토 시작 처리 저장 성공 시에만 원장 정정 모달 표시
        // 기존 코드: 예외 목록 페이지를 다시 조회해 상세 본문에 정정 폼을 노출
        // 문제: 불필요한 페이지 이동이 발생하고 처리 결과 상태를 확인하지 않은 채 화면 전환
        // 개선: JOURNAL_CORRECTION_REQUIRED가 IN_REVIEW로 전이된 경우에만 공통 모달 API 호출
        if (shouldOpenJournalCorrection && window.FgcUi && window.FgcUi.modal) {
          window.FgcUi.modal.open(`journal-correction-${form.dataset.exceptionId}`);
        }
      }).catch((requestError) => {
        const message = requestError.message || "처리 내용을 저장하지 못했습니다.";
        error.textContent = message;
        if (toast) toast(message, "error");
        updateSubmit();
      });
    });
  }

  function accountSelect(selectedCode) {
    const select = document.createElement("select");
    select.className = "fgc-select";
    select.dataset.correctionAccount = "true";
    select.required = true;
    if (accountOptionsTemplate) {
      select.append(accountOptionsTemplate.content.cloneNode(true));
    }
    select.value = selectedCode || "";
    return select;
  }

  function amountInput(kind, value) {
    const input = document.createElement("input");
    input.className = "fgc-input";
    input.type = "number";
    input.min = "0";
    input.max = "9999999999999";
    input.step = "1";
    input.required = true;
    input.dataset[kind] = "true";
    input.value = String(value || 0);
    return input;
  }

  function correctionField(labelText, control) {
    const label = document.createElement("label");
    label.className = "fgc-field";
    const labelSpan = document.createElement("span");
    labelSpan.className = "fgc-label";
    labelSpan.textContent = labelText;
    label.append(labelSpan, control);
    return label;
  }

  function refreshCorrectionLineTitles(container) {
    Array.from(container.querySelectorAll(".journal-correction-line")).forEach((row, index) => {
      row.querySelector(".journal-correction-line__title").textContent = `${index + 1}번 라인`;
    });
  }

  function appendCorrectionLine(container, line = {}) {
    const row = document.createElement("div");
    row.className = "journal-correction-line";
    row.dataset.originalLineNo = line.lineNo || "";
    const heading = document.createElement("strong");
    heading.className = "journal-correction-line__title";
    const removeButton = document.createElement("button");
    removeButton.className = "fgc-btn fgc-btn--ghost journal-correction-line__remove";
    removeButton.type = "button";
    removeButton.textContent = "라인 삭제";
    removeButton.addEventListener("click", () => {
      row.remove();
      refreshCorrectionLineTitles(container);
    });
    const memo = document.createElement("input");
    memo.className = "fgc-input";
    memo.maxLength = 500;
    memo.dataset.correctionDescription = "true";
    memo.value = line.memo || "";
    row.append(
      heading,
      removeButton,
      correctionField("계정과목", accountSelect(line.accountCode)),
      correctionField("차변", amountInput("correctionDebit", line.debitAmount)),
      correctionField("대변", amountInput("correctionCredit", line.creditAmount)),
      correctionField("라인 설명", memo)
    );
    container.append(row);
    refreshCorrectionLineTitles(container);
  }

  function renderCorrectionLines(container, lines) {
    container.replaceChildren();
    lines.forEach((line) => appendCorrectionLine(container, line));
  }

  // 2026-08-20 yslee - FGC-UI-EXCP-W01 원분개 일자와 상세행을 읽기 전용으로 표시
  // 기존 코드: 분개번호·유형·합계만 표시하고 원본 상세행은 편집 입력에만 복사함
  // 문제: 사용자가 원분개 일자와 계정별 차·대변 원본을 신규 입력과 구분해 확인할 수 없음
  // 개선: 원본 헤더와 모든 상세행을 별도 읽기 전용 그리드로 렌더링한 뒤 신규 입력을 프리필함
  function renderOriginalJournal(container, journal) {
    const summary = document.createElement("div");
    summary.className = "journal-correction-original";
    const title = document.createElement("strong");
    title.textContent = `${journal.journalNo} · ${journal.journalTypeLabel}`;
    const date = document.createElement("span");
    date.className = "fgc-muted";
    date.textContent = `분개일 ${journal.journalDate}`;
    const amount = document.createElement("span");
    amount.className = "fgc-muted";
    amount.textContent = `차변 ${Number(journal.debitTotal).toLocaleString("ko-KR")}원 · 대변 ${Number(journal.creditTotal).toLocaleString("ko-KR")}원`;
    const status = document.createElement("span");
    status.className = "fgc-muted";
    status.textContent = `원장상태 ${journal.statusLabel}`;
    const validation = document.createElement("strong");
    validation.className = journal.balanced ? "journal-correction-validation is-valid" : "journal-correction-validation is-invalid";
    validation.textContent = journal.balanced ? "차변·대변 검증 통과" : "차변·대변 검증 필요";
    summary.append(title, date, amount, status, validation);
    if (journal.correctionGroupKey) {
      const correctionGroup = document.createElement("span");
      correctionGroup.className = "fgc-muted";
      correctionGroup.textContent = `정정그룹 ${journal.correctionGroupKey}`;
      summary.append(correctionGroup);
    }
    if (journal.reversedByJournalHeaderId) {
      const reversalLink = document.createElement("a");
      reversalLink.className = "fgc-btn fgc-btn--ghost";
      reversalLink.href = `/journals?selected=${journal.reversedByJournalHeaderId}`;
      reversalLink.textContent = `역분개 #${journal.reversedByJournalHeaderId}`;
      summary.append(reversalLink);
    }
    if (journal.repostedJournalHeaderId) {
      const repostedLink = document.createElement("a");
      repostedLink.className = "fgc-btn fgc-btn--ghost";
      repostedLink.href = `/journals?selected=${journal.repostedJournalHeaderId}`;
      repostedLink.textContent = `재기표 #${journal.repostedJournalHeaderId}`;
      summary.append(repostedLink);
    }

    const lineGrid = document.createElement("div");
    lineGrid.className = "journal-correction-original-lines";
    ["라인", "계정과목", "차변", "대변", "설명"].forEach((heading) => {
      const cell = document.createElement("strong");
      cell.className = "journal-correction-original-line is-heading";
      cell.textContent = heading;
      lineGrid.append(cell);
    });
    (journal.lines || []).forEach((line) => {
      const values = [
        line.lineNo,
        `${line.accountCode} · ${line.accountName || "-"}`,
        `${Number(line.debitAmount).toLocaleString("ko-KR")}원`,
        `${Number(line.creditAmount).toLocaleString("ko-KR")}원`,
        line.memo || "-"
      ];
      values.forEach((value) => {
        const cell = document.createElement("span");
        cell.className = "journal-correction-original-line";
        cell.textContent = value;
        lineGrid.append(cell);
      });
    });
    container.replaceChildren(summary, lineGrid);
  }

  // 2026-08-20 yslee - 원장 정정 모달을 예외 상태에 따라 편집·조회 모드로 전환
  // 기존 코드: 검토 시작 직후에만 모달을 열고 재선택·종결 상태에서 다시 확인할 진입점이 없음
  // 문제: 검토중 작업을 이어갈 수 없고 오탐·반려 후 원분개와 미실행 결과를 확인할 수 없음
  // 개선: 검토중은 편집 가능, 해결·오탐·반려는 조회 전용으로 고정하고 재진입 버튼 상태 동기화
  function setJournalCorrectionMode(form, status) {
    if (!form) return;
    const readOnly = status !== "NEW" && status !== "IN_REVIEW";
    form.dataset.correctionStatus = status;
    form.dataset.correctionReadOnly = String(readOnly);
    form.querySelectorAll("[data-correction-editor]").forEach((editor) => {
      editor.hidden = readOnly;
      editor.querySelectorAll("input, select, textarea, button").forEach((control) => {
        control.disabled = readOnly;
      });
    });
    const notice = form.querySelector("[data-correction-read-only-notice]");
    if (notice) notice.hidden = !readOnly;
    const rejectedNotice = form.querySelector("[data-correction-rejected-notice]");
    if (rejectedNotice) rejectedNotice.hidden = status !== "REJECTED";
    const resolvedNotice = form.querySelector("[data-correction-resolved-notice]");
    if (resolvedNotice) resolvedNotice.hidden = status !== "RESOLVED";

    const panel = form.closest("[data-modal]")?.parentElement;
    const openButton = panel?.querySelector("[data-journal-correction-open]");
    if (openButton) {
      openButton.hidden = status === "NEW";
      const label = openButton.querySelector("[data-journal-correction-open-label]");
      if (label) label.textContent = status === "IN_REVIEW" ? "원장 정정 계속" : "원장 정정 확인";
    }
    if (!readOnly) {
      const loaded = form.dataset.loaded === "true";
      const submitButton = form.querySelector("button[type='submit']");
      const addLineButton = form.querySelector("[data-add-correction-line]");
      const linesContainer = form.querySelector("[data-correction-lines]");
      if (loaded && form.journalSnapshot && linesContainer && !linesContainer.children.length) {
        form.elements.journalDate.value = form.journalSnapshot.journalDate;
        form.elements.description.value = form.journalSnapshot.description || "";
        renderCorrectionLines(linesContainer, form.journalSnapshot.lines || []);
      }
      if (submitButton) submitButton.disabled = !loaded;
      if (addLineButton) addLineButton.disabled = !loaded;
    }
  }

  function bindJournalCorrectionForm(form, row) {
    if (!form || !apiClient) return;
    const submitButton = form.querySelector("button[type='submit']");
    const error = form.querySelector("[data-correction-error]");
    const originalContainer = form.querySelector("[data-original-journal]");
    const linesContainer = form.querySelector("[data-correction-lines]");
    const addLineButton = form.querySelector("[data-add-correction-line]");
    setJournalCorrectionMode(form, form.dataset.correctionStatus);

    addLineButton?.addEventListener("click", () => {
      appendCorrectionLine(linesContainer);
    });

    apiClient.request(`/api/v1/journals/${encodeURIComponent(form.dataset.journalId)}`)
      .then((envelope) => {
        const journal = envelope.data;
        form.journalSnapshot = journal;
        renderOriginalJournal(originalContainer, journal);
        form.dataset.loaded = "true";
        if (form.dataset.correctionReadOnly !== "true") {
          form.elements.journalDate.value = journal.journalDate;
          form.elements.description.value = journal.description || "";
          renderCorrectionLines(linesContainer, journal.lines || []);
          submitButton.disabled = false;
          if (addLineButton) addLineButton.disabled = false;
        }
      }).catch((requestError) => {
        originalContainer.replaceChildren();
        error.textContent = requestError.message || "원분개를 불러오지 못했습니다.";
      });

    form.addEventListener("submit", (event) => {
      event.preventDefault();
      if (form.dataset.correctionReadOnly === "true"
          || form.dataset.loaded !== "true" || submitButton.disabled) return;
      const lineRows = Array.from(linesContainer.querySelectorAll(".journal-correction-line"));
      const lines = lineRows.map((lineRow) => ({
        originalLineNo: lineRow.dataset.originalLineNo
          ? Number(lineRow.dataset.originalLineNo) : null,
        accountCode: lineRow.querySelector("[data-correction-account]").value,
        debitAmount: Number(lineRow.querySelector("[data-correction-debit]").value),
        creditAmount: Number(lineRow.querySelector("[data-correction-credit]").value),
        lineDescription: lineRow.querySelector("[data-correction-description]").value.trim() || null
      }));

      submitButton.disabled = true;
      error.textContent = "";
      apiClient.request(`/api/v1/exceptions/${form.dataset.exceptionId}/journal-correction`, {
        method: "POST",
        body: {
          reason: form.elements.reason.value.trim(),
          evidenceRef: form.elements.evidenceRef.value.trim() || null,
          journalDate: form.elements.journalDate.value,
          description: form.elements.description.value.trim(),
          lines
        }
      }).then((envelope) => {
        const result = envelope.data;
        appendHistory(result, "정정");
        setStatus(row, result.status);
        const completed = document.createElement("div");
        completed.className = "fgc-banner fgc-banner--success journal-correction-result";
        const message = document.createElement("span");
        message.textContent = "역분개와 재기표를 완료했습니다.";
        const correctionGroup = document.createElement("span");
        correctionGroup.className = "fgc-muted";
        correctionGroup.textContent = `정정그룹 ${result.correctionGroupKey}`;
        const originalLink = document.createElement("a");
        originalLink.className = "fgc-btn fgc-btn--ghost";
        originalLink.href = `/journals?selected=${result.originalJournalHeaderId}`;
        originalLink.textContent = `원분개 #${result.originalJournalHeaderId}`;
        const reversalLink = document.createElement("a");
        reversalLink.className = "fgc-btn fgc-btn--ghost";
        reversalLink.href = `/journals?selected=${result.reversalJournalHeaderId}`;
        reversalLink.textContent = `역분개 #${result.reversalJournalHeaderId}`;
        const repostedLink = document.createElement("a");
        repostedLink.className = "fgc-btn fgc-btn--ghost";
        repostedLink.href = `/journals?selected=${result.repostedJournalHeaderId}`;
        repostedLink.textContent = `재기표 #${result.repostedJournalHeaderId}`;
        completed.append(
          message, correctionGroup, originalLink, reversalLink, repostedLink
        );
        setJournalCorrectionMode(form, result.status);
        form.replaceWith(completed);
        if (toast) toast("원장 정정을 완료했습니다.", "success");
      }).catch((requestError) => {
        error.textContent = requestError.message || "원장 정정을 완료하지 못했습니다.";
        submitButton.disabled = false;
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
      bindJournalCorrectionForm(
        panels.detail.querySelector("[data-journal-correction-form]"), row);
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
