/**
 * FGC-UI-LEDG-W01 검증원장 목록·상세·역분개 (FUN-046·047)
 * GET  /journals                                (IF-API-34 · MPA)
 * GET  /api/v1/journals/{id}                    (IF-API-35)
 * POST /api/v1/journals/{id}/reverse            (IF-API-36)
 * POST /api/v1/journals/{id}/correction-exceptions (IF-API-36A)
 * GET  /api/v1/journals/imbalances              (IF-API-37)
 */
(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var root = document.querySelector(".publishing-page-ledger");
  if (!apiClient || !root) return;

  var listBody = document.getElementById("list-body");
  var detailBody = document.getElementById("detail-body");
  var detailBadge = document.getElementById("detail-badge");
  var imbalanceBanner = document.getElementById("imbalance-banner");
  var imbalanceText = document.getElementById("imbalance-text");
  var balanceBanner = document.getElementById("balance-banner");
  var reverseTarget = document.getElementById("journal-reverse-target");
  var reverseReason = document.getElementById("journal-reverse-reason");
  var reverseEvidence = document.getElementById("journal-reverse-evidence");
  var reverseReasonError = document.getElementById("journal-reverse-reason-error");
  var reverseSubmit = document.getElementById("journal-reverse-submit");
  var correctionTarget = document.getElementById("journal-correction-target");
  var correctionReason = document.getElementById("journal-correction-reason");
  var correctionEvidence = document.getElementById("journal-correction-evidence");
  var correctionReasonError = document.getElementById("journal-correction-reason-error");
  var correctionSubmit = document.getElementById("journal-correction-submit");
  var canReverse = root.dataset.canReverse === "true";
  var state = { selectedId: null, selected: null, pending: false };

  function text(value) {
    return value === null || value === undefined || value === "" ? "—" : String(value);
  }

  function won(value) {
    var amount = Number(value || 0);
    return Number.isFinite(amount) ? amount.toLocaleString("ko-KR") + "원" : "—";
  }

  function element(tag, className, content) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = content;
    return node;
  }

  function cell(content, className) {
    return element("td", className, content);
  }

  function statusTone(status) {
    if (status === "POSTED") return "fgc-badge--normal";
    if (status === "REVERSED") return "fgc-badge--review";
    return "fgc-badge--neutral";
  }

  function appendKeyValue(container, label, value) {
    var item = element("div", "");
    item.appendChild(element("strong", "", label));
    item.appendChild(element("span", "fgc-muted", text(value)));
    container.appendChild(item);
  }

  function renderLines(lines) {
    var wrapper = element("div", "");
    wrapper.style.overflowX = "auto";
    var table = element("table", "fgc-table");
    var head = document.createElement("thead");
    var headerRow = document.createElement("tr");
    ["번호", "계정", "차변", "대변", "설계사", "항목"].forEach(function (label) {
      var th = element("th", "", label);
      th.setAttribute("scope", "col");
      headerRow.appendChild(th);
    });
    head.appendChild(headerRow);
    table.appendChild(head);
    var body = document.createElement("tbody");
    lines.forEach(function (line) {
      var row = document.createElement("tr");
      row.appendChild(cell(text(line.lineNo)));
      row.appendChild(cell(text(line.accountName || line.accountCode)));
      row.appendChild(cell(won(line.debitAmount), "fgc-td-num"));
      row.appendChild(cell(won(line.creditAmount), "fgc-td-num"));
      row.appendChild(cell(text(line.agentName)));
      row.appendChild(cell(text(line.commissionItemName)));
      body.appendChild(row);
    });
    table.appendChild(body);
    wrapper.appendChild(table);
    return wrapper;
  }

  // vrun-detail.js의 safeInternalLink()와 동일한 규칙 — 서버 응답의 redirectUrl도
  // 클라이언트에서 같은 오리진 상대경로인지 검증한 뒤에만 이동한다.
  function safeInternalLink(linkUrl) {
    if (!linkUrl || typeof linkUrl !== "string" || !linkUrl.startsWith("/") || linkUrl.startsWith("//")) {
      return null;
    }
    try {
      var url = new URL(linkUrl, window.location.origin);
      return url.origin === window.location.origin ? url.pathname + url.search + url.hash : null;
    } catch (error) {
      return null;
    }
  }

  function reverseAllowed(detail) {
    return canReverse && detail.status === "POSTED" && detail.journalType !== "REVERSAL"
      && !detail.reversedByJournalHeaderId;
  }

  function showBalanceResult(totalCount) {
    var hasImbalance = Number(totalCount) > 0;
    imbalanceBanner.hidden = !hasImbalance;
    imbalanceText.textContent = hasImbalance
      ? "이 검증 실행에 차변·대변이 맞지 않는 분개가 " + totalCount + "건 있습니다."
      : "";
    balanceBanner.hidden = hasImbalance;
    if (!hasImbalance) {
      balanceBanner.className = "fgc-banner fgc-banner--success";
      balanceBanner.querySelector("span:last-child").textContent =
        "선택한 분개의 검증 실행은 원장 불균형이 0건입니다.";
    }
  }

  // 2026-08-19 yslee - 상세 분개의 검증실행 기준 불균형 배너를 IF-API-37과 연동
  // 기존 코드: 배너 요소와 연동 주석만 있고 항상 hidden 상태로 남아 있었음
  // 문제: 사용자가 POSTED 전 필수 조건인 원장 불균형 0건 여부를 화면에서 확인할 수 없음
  // 개선: 상세의 validationRunId로 불균형 뷰를 조회해 오류 건수와 0건 결과를 구분 표시
  function loadImbalance(validationRunId) {
    imbalanceBanner.hidden = true;
    balanceBanner.hidden = false;
    balanceBanner.className = "fgc-banner fgc-banner--muted";
    balanceBanner.querySelector("span:last-child").textContent = validationRunId
      ? "원장 불균형 여부를 확인하는 중입니다."
      : "이 분개에는 연결된 검증 실행이 없어 실행 단위 불균형을 조회할 수 없습니다.";
    if (!validationRunId) return Promise.resolve();

    return apiClient.request("/api/v1/journals/imbalances?validationRunId="
      + encodeURIComponent(validationRunId))
      .then(function (envelope) {
        showBalanceResult(envelope.data.totalCount);
      }).catch(function () {
        imbalanceBanner.hidden = true;
        balanceBanner.hidden = false;
        balanceBanner.className = "fgc-banner fgc-banner--muted";
        balanceBanner.querySelector("span:last-child").textContent =
          "원장 불균형 결과를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.";
      });
  }

  function renderDetail(detail) {
    state.selected = detail;
    detailBody.replaceChildren();
    detailBadge.replaceChildren(element("span", "fgc-badge " + statusTone(detail.status), detail.statusLabel));

    var summary = element("div", "kv");
    appendKeyValue(summary, "분개번호", detail.journalNo);
    appendKeyValue(summary, "분개일", detail.journalDate);
    appendKeyValue(summary, "유형", detail.journalTypeLabel);
    appendKeyValue(summary, "계약", detail.contractNo || detail.contractId);
    appendKeyValue(summary, "차변 합계", won(detail.debitTotal));
    appendKeyValue(summary, "대변 합계", won(detail.creditTotal));
    appendKeyValue(summary, "원분개", detail.reversalOfJournalNo);
    appendKeyValue(summary, "역분개", detail.reversedByJournalNo);
    detailBody.appendChild(summary);
    if (!detail.balanced) {
      var mismatch = element("p", "fgc-banner fgc-banner--error",
        "차변·대변이 맞지 않습니다. 차액 " + won(detail.differenceAmount));
      mismatch.style.marginTop = "12px";
      detailBody.appendChild(mismatch);
    }
    detailBody.appendChild(renderLines(detail.lines || []));

    var actions = element("div", "", "");
    actions.style.marginTop = "16px";
    if (reverseAllowed(detail)) {
      var button = element("button", "fgc-btn fgc-btn--primary", "역분개");
      button.type = "button";
      button.dataset.reverseJournal = detail.journalHeaderId;
      actions.appendChild(button);
      var correctionButton = element("button", "fgc-btn fgc-btn--ghost", "역분개 + 재기표");
      correctionButton.type = "button";
      correctionButton.dataset.correctionJournal = detail.journalHeaderId;
      correctionButton.style.marginLeft = "8px";
      actions.appendChild(correctionButton);
    } else if (detail.status === "POSTED" && !canReverse) {
      actions.appendChild(element("p", "fgc-muted", "역분개는 SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN 권한이 필요합니다."));
    } else {
      actions.appendChild(element("p", "fgc-muted", "POSTED 원분개만 역분개할 수 있습니다."));
    }
    detailBody.appendChild(actions);
    loadImbalance(detail.validationRunId);
  }

  function loadDetail(journalId) {
    state.selectedId = journalId;
    detailBody.replaceChildren(element("p", "fgc-muted", "분개 상세를 불러오는 중입니다."));
    return apiClient.request("/api/v1/journals/" + encodeURIComponent(journalId))
      .then(function (envelope) {
        renderDetail(envelope.data);
        listBody.querySelectorAll("tr[data-journal-id]").forEach(function (row) {
          row.classList.toggle("is-selected", row.dataset.journalId === String(journalId));
        });
      }).catch(function (error) {
        detailBody.replaceChildren(element("p", "fgc-error", error && error.message
          ? error.message : "분개 상세를 불러오지 못했습니다."));
      });
  }

  function openReverseModal() {
    if (!state.selected || !reverseAllowed(state.selected) || !window.FgcUi.modal) return;
    reverseTarget.textContent = state.selected.journalNo + " · " + state.selected.journalTypeLabel;
    reverseReason.value = "";
    reverseEvidence.value = "";
    reverseReasonError.hidden = true;
    window.FgcUi.modal.open("journal-reverse");
  }

  function openCorrectionModal() {
    if (!state.selected || !reverseAllowed(state.selected) || !window.FgcUi.modal) return;
    correctionTarget.textContent = state.selected.journalNo + " · " + state.selected.journalTypeLabel;
    correctionReason.value = "";
    correctionEvidence.value = "";
    correctionReasonError.hidden = true;
    window.FgcUi.modal.open("journal-correction-request");
  }

  // 2026-08-19 yslee - FUN-047 원분개 역분개 API를 LEDG-W01에 연결
  // 기존 코드: 목록·상세가 정적 빈 화면이고 역분개 입력 및 호출 동작이 없음
  // 문제: 구현된 IF-API-36을 정산담당자가 화면에서 사용할 수 없음
  // 개선: POSTED 원분개만 선택해 필수 사유와 증빙을 보내고 서버 결과로 목록·상세를 갱신
  function submitReverse() {
    var reason = reverseReason.value.trim();
    if (!reason) {
      reverseReasonError.hidden = false;
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
      var result = envelope.data;
      window.FgcUi.modal.close("journal-reverse");
      if (window.FgcUi.toast) window.FgcUi.toast("역분개 " + result.journalHeaderId + "을 생성했습니다.", "success");
      window.location.reload();
      return result;
    }).catch(function (error) {
      if (window.FgcUi.toast) window.FgcUi.toast(error && error.message
        ? error.message : "역분개 생성에 실패했습니다.", "error");
    }).finally(function () {
      state.pending = false;
      reverseSubmit.disabled = false;
      reverseSubmit.removeAttribute("aria-busy");
    });
  }

  // 2026-08-20 yslee - LEDG-W01 정정 요청을 원장 정정 예외와 연결
  // 기존 코드: 단순 역분개 버튼만 있어 올바른 신규 분개를 입력할 업무 화면으로 이동할 수 없음
  // 문제: 역분개+재기표 내부 서비스가 있어도 사용자는 예외 처리 흐름에서 실제 정정을 수행할 수 없음
  // 개선: IF-API-36A로 예외를 멱등 생성한 뒤 서버가 반환한 EXCP-W01 선택 주소로 이동
  function submitCorrectionRequest() {
    var reason = correctionReason.value.trim();
    if (!reason) {
      correctionReasonError.hidden = false;
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
      var target = safeInternalLink(envelope.data.redirectUrl);
      if (target) {
        window.location.assign(target);
      } else if (window.FgcUi.toast) {
        window.FgcUi.toast("원장 정정 예외는 생성됐지만 이동할 주소가 올바르지 않습니다. 예외함에서 직접 확인하세요.", "error");
      }
    }).catch(function (error) {
      if (window.FgcUi.toast) window.FgcUi.toast(error && error.message
        ? error.message : "원장 정정 요청을 만들지 못했습니다.", "error");
    }).finally(function () {
      state.pending = false;
      correctionSubmit.disabled = false;
      correctionSubmit.removeAttribute("aria-busy");
    });
  }

  listBody.addEventListener("click", function (event) {
    var row = event.target.closest("tr[data-journal-id]");
    if (row) loadDetail(row.dataset.journalId);
  });
  listBody.addEventListener("keydown", function (event) {
    if (event.key !== "Enter" && event.key !== " ") return;
    var row = event.target.closest("tr[data-journal-id]");
    if (!row) return;
    event.preventDefault();
    loadDetail(row.dataset.journalId);
  });
  detailBody.addEventListener("click", function (event) {
    if (event.target.closest("[data-reverse-journal]")) openReverseModal();
    if (event.target.closest("[data-correction-journal]")) openCorrectionModal();
  });
  reverseReason.addEventListener("input", function () { reverseReasonError.hidden = Boolean(reverseReason.value.trim()); });
  reverseSubmit.addEventListener("click", submitReverse);
  correctionReason.addEventListener("input", function () {
    correctionReasonError.hidden = Boolean(correctionReason.value.trim());
  });
  correctionSubmit.addEventListener("click", submitCorrectionRequest);

  if (root.dataset.selectedJournalId) {
    loadDetail(root.dataset.selectedJournalId);
  }

})();
