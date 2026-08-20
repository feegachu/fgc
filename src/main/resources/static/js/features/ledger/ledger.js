/**
 * FGC-UI-LEDG-W01 검증원장 목록·상세·역분개 (FUN-046·047)
 * GET  /journals                                   (IF-API-34 · MPA)
 * GET  /api/v1/journals/{id}                       (IF-API-35)
 * POST /api/v1/journals/{id}/reverse               (IF-API-36)
 * POST /api/v1/journals/{id}/correction-exceptions (IF-API-36A)
 * GET  /api/v1/journals/imbalances                 (IF-API-37)
 */
(function () {
  "use strict";

  const apiClient = window.FgcUi && window.FgcUi.apiClient;
  const format = window.FgcUi && window.FgcUi.format;
  const root = document.querySelector(".ledger-page");
  if (!apiClient || !root) return;

  const listBody = document.getElementById("list-body");
  const detailBody = document.getElementById("detail-body");
  const detailBadge = document.getElementById("detail-badge");
  const imbalanceBanner = document.getElementById("imbalance-banner");
  const imbalanceText = document.getElementById("imbalance-text");
  const balanceBanner = document.getElementById("balance-banner");
  const balanceMessage = balanceBanner && balanceBanner.querySelector("[data-balance-message]");
  const reverseTarget = document.getElementById("journal-reverse-target");
  const reverseReason = document.getElementById("journal-reverse-reason");
  const reverseEvidence = document.getElementById("journal-reverse-evidence");
  const reverseReasonError = document.getElementById("journal-reverse-reason-error");
  const reverseSubmit = document.getElementById("journal-reverse-submit");
  const correctionTarget = document.getElementById("journal-correction-target");
  const correctionReason = document.getElementById("journal-correction-reason");
  const correctionEvidence = document.getElementById("journal-correction-evidence");
  const correctionReasonError = document.getElementById("journal-correction-reason-error");
  const correctionSubmit = document.getElementById("journal-correction-submit");

  if (!listBody || !detailBody || !detailBadge || !imbalanceBanner || !balanceBanner
    || !balanceMessage || !reverseReason || !reverseSubmit || !correctionReason || !correctionSubmit) return;

  const canReverse = root.dataset.canReverse === "true";
  const SUCCESS_TOAST_KEY = "fgc.ledger.success-toast";
  const state = { selectedId: null, selected: null, pending: false };

  function text(value) {
    return value === null || value === undefined || value === "" ? "—" : String(value);
  }

  /*
   * 표시 형식은 공통 유틸만 쓴다 (FGC-SIR-008).
   * format.won() 은 음수 괄호까지 만들고, 호출부가 isNegative() 로 빨강을 함께 적용한다.
   */
  function money(value) {
    return format ? format.won(value) : text(value) + "원";
  }

  function date(value) {
    return format ? format.date(value) : text(value);
  }

  function errorText(error, fallback) {
    return format ? format.errorText(error, fallback) : ((error && error.message) || fallback);
  }

  function element(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = content;
    return node;
  }

  function amountClass(value, imbalanced) {
    const classes = ["is-number", "tabular-nums"];
    if (format && format.isNegative(value)) classes.push("is-negative-amount");
    if (imbalanced) classes.push("ledger-imbalanced-amount");
    return classes.join(" ");
  }

  function statusTone(status) {
    if (status === "POSTED") return "status-badge-success";
    if (status === "REVERSED") return "status-badge-review";
    return "status-badge-neutral";
  }

  /*
   * 긴 값은 textContent 로만 넣어 HTML 을 해석하지 않는다. 실제로 잘린 경우에만
   * 공통 table-cell-disclosure 의 전체 보기 버튼을 노출한다.
   */
  function disclosure(value, singleLine, valueClass) {
    const displayed = text(value);
    const wrapper = element("div", "table-cell-disclosure");
    const previewClass = "table-cell-preview" + (singleLine ? " is-single-line" : "")
      + (valueClass ? " " + valueClass : "");
    wrapper.appendChild(element("span", previewClass, displayed));

    const details = element("details", "table-cell-details");
    details.hidden = true;
    const summary = document.createElement("summary");
    summary.appendChild(element("span", "table-cell-more", "전체 보기"));
    summary.appendChild(element("span", "table-cell-less", "접기"));
    const chevron = element("span", "material-symbols-rounded table-cell-chevron", "expand_more");
    chevron.setAttribute("aria-hidden", "true");
    summary.appendChild(chevron);
    details.appendChild(summary);
    details.appendChild(element("p", "table-cell-full" + (valueClass ? " " + valueClass : ""), displayed));
    wrapper.appendChild(details);
    return wrapper;
  }

  function syncTableCellDisclosures() {
    root.querySelectorAll(".table-cell-disclosure").forEach(function (item) {
      const preview = item.querySelector(".table-cell-preview");
      const details = item.querySelector(".table-cell-details");
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

  function appendDetailItem(container, label, value, options) {
    const item = element("div", options && options.wide ? "ledger-detail-wide" : "");
    item.appendChild(element("dt", "", label));
    const definition = document.createElement("dd");
    if (options && options.disclosure) {
      definition.appendChild(disclosure(value, options.singleLine !== false, options.valueClass));
    } else {
      definition.className = options && options.valueClass ? options.valueClass : "";
      definition.textContent = text(value);
    }
    item.appendChild(definition);
    container.appendChild(item);
  }

  function cell(content, className) {
    const node = element("td", className);
    if (content instanceof Node) node.appendChild(content);
    else node.textContent = content;
    return node;
  }

  function renderLines(lines) {
    if (!lines.length) {
      const empty = element("p", "empty-state ledger-detail-empty", "분개 상세행이 없습니다.");
      empty.setAttribute("role", "status");
      return empty;
    }

    const viewport = element("div", "data-table-viewport ledger-detail-table-viewport");
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "region");
    viewport.setAttribute("aria-label", "분개 상세행 표");

    const table = element("table", "data-table ledger-detail-table");
    const caption = element("caption", "visually-hidden", "선택한 분개의 차변·대변 상세행");
    table.appendChild(caption);

    const colgroup = document.createElement("colgroup");
    [
      "ledger-detail-col-line",
      "ledger-detail-col-account",
      "ledger-detail-col-amount",
      "ledger-detail-col-amount",
      "ledger-detail-col-agent",
      "ledger-detail-col-item",
      "ledger-detail-col-memo"
    ].forEach(function (className) {
      colgroup.appendChild(element("col", className));
    });
    table.appendChild(colgroup);

    const head = document.createElement("thead");
    const headerRow = document.createElement("tr");
    ["번호", "계정과목", "차변", "대변", "설계사", "항목", "적요"].forEach(function (label, index) {
      const header = element("th", index === 2 || index === 3 ? "is-number" : "", label);
      header.setAttribute("scope", "col");
      headerRow.appendChild(header);
    });
    head.appendChild(headerRow);
    table.appendChild(head);

    const body = document.createElement("tbody");
    lines.forEach(function (line) {
      const row = document.createElement("tr");
      const account = text(line.accountCode) + " · " + text(line.accountName);
      row.appendChild(cell(text(line.lineNo), "tabular-nums"));
      row.appendChild(cell(disclosure(account, true), "ledger-disclosure-cell"));
      row.appendChild(cell(money(line.debitAmount), amountClass(line.debitAmount)));
      row.appendChild(cell(money(line.creditAmount), amountClass(line.creditAmount)));
      row.appendChild(cell(text(line.agentName)));
      row.appendChild(cell(disclosure(line.commissionItemName, true), "ledger-disclosure-cell"));
      row.appendChild(cell(disclosure(line.memo, true), "ledger-disclosure-cell"));
      body.appendChild(row);
    });
    table.appendChild(body);
    viewport.appendChild(table);
    return viewport;
  }

  function reverseEligible(detail) {
    return detail.status === "POSTED" && detail.journalType !== "REVERSAL"
      && !detail.reversedByJournalHeaderId;
  }

  function reverseAllowed(detail) {
    return canReverse && reverseEligible(detail);
  }

  function renderActions(detail) {
    const actions = element("div", "ledger-detail-actions");
    if (reverseEligible(detail)) {
      const reverseButton = element("button", "button button-primary", "역분개");
      reverseButton.type = "button";
      reverseButton.dataset.reverseJournal = detail.journalHeaderId;

      const correctionButton = element("button", "button button-secondary", "역분개 + 재기표");
      correctionButton.type = "button";
      correctionButton.dataset.correctionJournal = detail.journalHeaderId;

      if (!canReverse) {
        const guideId = "ledger-reverse-disabled-reason";
        reverseButton.disabled = true;
        correctionButton.disabled = true;
        reverseButton.setAttribute("aria-describedby", guideId);
        correctionButton.setAttribute("aria-describedby", guideId);
        const guide = element("p", "ledger-action-guide",
          "역분개는 SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN 권한이 필요합니다.");
        guide.id = guideId;
        actions.append(reverseButton, correctionButton, guide);
      } else {
        actions.append(reverseButton, correctionButton);
      }
    } else {
      actions.appendChild(element("p", "ledger-action-guide",
        "POSTED 상태이고 아직 역분개되지 않은 원분개만 처리할 수 있습니다."));
    }
    return actions;
  }

  function setBalanceMessage(className, message, role) {
    balanceBanner.className = "guidance " + className;
    balanceBanner.hidden = false;
    balanceBanner.setAttribute("role", role);
    balanceMessage.textContent = message;
  }

  function showBalanceResult(totalCount) {
    const hasImbalance = Number(totalCount) > 0;
    imbalanceBanner.hidden = !hasImbalance;
    imbalanceText.textContent = hasImbalance
      ? "이 검증 실행에 차변·대변이 맞지 않는 분개가 " + totalCount + "건 있습니다."
      : "";
    balanceBanner.hidden = hasImbalance;
    if (!hasImbalance) {
      setBalanceMessage("guidance-info", "선택한 분개의 검증 실행은 원장 불균형이 0건입니다.", "status");
    }
  }

  // 상세 분개의 검증실행 기준 불균형 배너를 IF-API-37과 연동한다.
  function loadImbalance(validationRunId) {
    imbalanceBanner.hidden = true;
    balanceBanner.removeAttribute("aria-busy");

    if (!validationRunId) {
      setBalanceMessage("guidance-neutral",
        "이 분개에는 연결된 검증 실행이 없어 실행 단위 불균형을 조회할 수 없습니다.", "status");
      return Promise.resolve();
    }

    setBalanceMessage("guidance-neutral", "원장 불균형 여부를 확인하는 중입니다.", "status");
    balanceBanner.setAttribute("aria-busy", "true");
    return apiClient.request("/api/v1/journals/imbalances?validationRunId="
      + encodeURIComponent(validationRunId))
      .then(function (envelope) {
        showBalanceResult(envelope.data.totalCount);
      }).catch(function (error) {
        const message = errorText(error,
          "원장 불균형 결과를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.");
        imbalanceBanner.hidden = true;
        setBalanceMessage("guidance-warning", message, "alert");
        if (window.FgcUi.toast) window.FgcUi.toast(message, "error");
      }).finally(function () {
        balanceBanner.removeAttribute("aria-busy");
      });
  }

  function renderDetail(detail) {
    state.selected = detail;
    detailBody.replaceChildren();
    detailBadge.replaceChildren(element("span",
      "status-badge " + statusTone(detail.status), detail.statusLabel));

    const summary = element("dl", "ledger-detail-summary");
    appendDetailItem(summary, "분개번호", detail.journalNo,
      { disclosure: true, valueClass: "tabular-nums" });
    appendDetailItem(summary, "분개일", date(detail.journalDate),
      { valueClass: "tabular-nums" });
    appendDetailItem(summary, "유형", detail.journalTypeLabel);
    appendDetailItem(summary, "계약", detail.contractNo || detail.contractId,
      { valueClass: "tabular-nums" });
    appendDetailItem(summary, "차변 합계", money(detail.debitTotal),
      { valueClass: amountClass(detail.debitTotal, !detail.balanced) });
    appendDetailItem(summary, "대변 합계", money(detail.creditTotal),
      { valueClass: amountClass(detail.creditTotal, !detail.balanced) });
    appendDetailItem(summary, "원분개", detail.reversalOfJournalNo,
      { disclosure: true, valueClass: "tabular-nums" });
    appendDetailItem(summary, "역분개", detail.reversedByJournalNo,
      { disclosure: true, valueClass: "tabular-nums" });
    appendDetailItem(summary, "재기표", detail.repostedJournalNo,
      { disclosure: true, valueClass: "tabular-nums" });
    appendDetailItem(summary, "정정그룹", detail.correctionGroupKey,
      { disclosure: true, valueClass: "tabular-nums" });
    appendDetailItem(summary, "적요", detail.description,
      { disclosure: true, singleLine: false, wide: true });
    detailBody.appendChild(summary);
    detailBody.appendChild(renderLines(detail.lines || []));
    detailBody.appendChild(renderActions(detail));
    scheduleDisclosureSync();
    loadImbalance(detail.validationRunId);
  }

  function selectRow(journalId) {
    listBody.querySelectorAll("tr[data-journal-id]").forEach(function (row) {
      const selected = row.dataset.journalId === String(journalId);
      row.classList.toggle("is-selected", selected);
      row.setAttribute("aria-selected", String(selected));
    });
  }

  function loadDetail(journalId) {
    state.selectedId = journalId;
    selectRow(journalId);
    detailBody.setAttribute("aria-busy", "true");
    const loading = element("p", "text-secondary ledger-placeholder", "분개 상세를 불러오는 중입니다.");
    loading.setAttribute("role", "status");
    detailBody.replaceChildren(loading);

    return apiClient.request("/api/v1/journals/" + encodeURIComponent(journalId))
      .then(function (envelope) {
        renderDetail(envelope.data);
      }).catch(function (error) {
        const message = errorText(error, "분개 상세를 불러오지 못했습니다.");
        const failure = element("p", "field-error ledger-placeholder", message);
        failure.setAttribute("role", "alert");
        detailBody.replaceChildren(failure);
        if (window.FgcUi.toast) window.FgcUi.toast(message, "error");
      }).finally(function () {
        detailBody.removeAttribute("aria-busy");
      });
  }

  function setReasonError(field, error, visible) {
    error.hidden = !visible;
    field.setAttribute("aria-invalid", String(visible));
  }

  function openReverseModal() {
    if (!state.selected || !reverseAllowed(state.selected) || !window.FgcUi.modal) return;
    reverseTarget.textContent = state.selected.journalNo + " · " + state.selected.journalTypeLabel;
    reverseReason.value = "";
    reverseEvidence.value = "";
    setReasonError(reverseReason, reverseReasonError, false);
    window.FgcUi.modal.open("journal-reverse");
  }

  function openCorrectionModal() {
    if (!state.selected || !reverseAllowed(state.selected) || !window.FgcUi.modal) return;
    correctionTarget.textContent = state.selected.journalNo + " · " + state.selected.journalTypeLabel;
    correctionReason.value = "";
    correctionEvidence.value = "";
    setReasonError(correctionReason, correctionReasonError, false);
    window.FgcUi.modal.open("journal-correction-request");
  }

  function submitReverse() {
    const reason = reverseReason.value.trim();
    if (!reason) {
      setReasonError(reverseReason, reverseReasonError, true);
      reverseReason.focus();
      return;
    }
    if (state.pending || !state.selected || !reverseAllowed(state.selected)) return;

    state.pending = true;
    reverseSubmit.disabled = true;
    reverseSubmit.setAttribute("aria-busy", "true");
    apiClient.request("/api/v1/journals/" + encodeURIComponent(state.selected.journalHeaderId) + "/reverse", {
      method: "POST",
      body: { reason: reason, evidenceRef: reverseEvidence.value.trim() || null }
    }).then(function (envelope) {
      const result = envelope.data;
      window.FgcUi.modal.close("journal-reverse");
      sessionStorage.setItem(SUCCESS_TOAST_KEY,
        "역분개 " + result.journalHeaderId + "을 생성했습니다.");
      window.location.reload();
      return result;
    }).catch(function (error) {
      const message = errorText(error, "역분개 생성에 실패했습니다.");
      if (window.FgcUi.toast) window.FgcUi.toast(message, "error");
    }).finally(function () {
      state.pending = false;
      reverseSubmit.disabled = false;
      reverseSubmit.removeAttribute("aria-busy");
    });
  }

  function submitCorrectionRequest() {
    const reason = correctionReason.value.trim();
    if (!reason) {
      setReasonError(correctionReason, correctionReasonError, true);
      correctionReason.focus();
      return;
    }
    if (state.pending || !state.selected || !reverseAllowed(state.selected)) return;

    state.pending = true;
    correctionSubmit.disabled = true;
    correctionSubmit.setAttribute("aria-busy", "true");
    apiClient.request("/api/v1/journals/"
      + encodeURIComponent(state.selected.journalHeaderId) + "/correction-exceptions", {
      method: "POST",
      body: { reason: reason, evidenceRef: correctionEvidence.value.trim() || null }
    }).then(function (envelope) {
      window.location.assign(envelope.data.redirectUrl);
    }).catch(function (error) {
      const message = errorText(error, "원장 정정 요청을 만들지 못했습니다.");
      if (window.FgcUi.toast) window.FgcUi.toast(message, "error");
    }).finally(function () {
      state.pending = false;
      correctionSubmit.disabled = false;
      correctionSubmit.removeAttribute("aria-busy");
    });
  }

  listBody.addEventListener("click", function (event) {
    if (event.target.closest("a, button, details, input, select, textarea")) return;
    const row = event.target.closest("tr[data-journal-id]");
    if (row) loadDetail(row.dataset.journalId);
  });

  listBody.addEventListener("keydown", function (event) {
    if (event.target.closest("a, button, details, input, select, textarea")) return;
    if (event.key !== "Enter" && event.key !== " ") return;
    const row = event.target.closest("tr[data-journal-id]");
    if (!row) return;
    event.preventDefault();
    loadDetail(row.dataset.journalId);
  });

  detailBody.addEventListener("click", function (event) {
    if (event.target.closest("[data-reverse-journal]")) openReverseModal();
    if (event.target.closest("[data-correction-journal]")) openCorrectionModal();
  });

  reverseReason.addEventListener("input", function () {
    setReasonError(reverseReason, reverseReasonError, !reverseReason.value.trim());
  });
  reverseSubmit.addEventListener("click", submitReverse);
  correctionReason.addEventListener("input", function () {
    setReasonError(correctionReason, correctionReasonError, !correctionReason.value.trim());
  });
  correctionSubmit.addEventListener("click", submitCorrectionRequest);

  scheduleDisclosureSync();
  window.addEventListener("load", scheduleDisclosureSync, { once: true });
  window.addEventListener("resize", scheduleDisclosureSync);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleDisclosureSync);
  }

  // 새 목록을 서버에서 다시 렌더링한 뒤에도 성공 Toast 가 사라지지 않게 한 번만 복원한다.
  const successMessage = sessionStorage.getItem(SUCCESS_TOAST_KEY);
  if (successMessage) {
    sessionStorage.removeItem(SUCCESS_TOAST_KEY);
    if (window.FgcUi.toast) window.FgcUi.toast(successMessage, "success");
  }

  if (root.dataset.selectedJournalId) {
    loadDetail(root.dataset.selectedJournalId);
  }
})();
