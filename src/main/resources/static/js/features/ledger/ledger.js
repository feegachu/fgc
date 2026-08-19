/**
 * FGC-UI-LEDG-W01 검증원장 목록·상세·역분개 (FUN-046·047)
 * GET  /api/v1/journals                         (IF-API-34)
 * GET  /api/v1/journals/{id}                    (IF-API-35)
 * POST /api/v1/journals/{id}/reverse            (IF-API-36)
 */
(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var root = document.querySelector(".publishing-page-ledger");
  if (!apiClient || !root) return;

  var form = document.getElementById("ledger-filter-form");
  var listBody = document.getElementById("list-body");
  var detailBody = document.getElementById("detail-body");
  var detailBadge = document.getElementById("detail-badge");
  var rowCount = document.getElementById("row-count");
  var previousButton = document.getElementById("ledger-prev");
  var nextButton = document.getElementById("ledger-next");
  var pageInfo = document.getElementById("ledger-page-info");
  var resetButton = document.getElementById("f-reset");
  var reverseTarget = document.getElementById("journal-reverse-target");
  var reverseReason = document.getElementById("journal-reverse-reason");
  var reverseEvidence = document.getElementById("journal-reverse-evidence");
  var reverseReasonError = document.getElementById("journal-reverse-reason-error");
  var reverseSubmit = document.getElementById("journal-reverse-submit");
  var canReverse = root.dataset.canReverse === "true";
  var state = { page: 1, totalPages: 1, selectedId: null, selected: null, pending: false };

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

  function query(page) {
    var params = new URLSearchParams();
    ["from", "to"].forEach(function (name) {
      var value = document.getElementById("f-" + name).value;
      if (value) params.set(name, value);
    });
    [["type", "f-type"], ["account", "f-account"], ["contract", "f-contract"], ["status", "f-status"]]
      .forEach(function (entry) {
        var value = document.getElementById(entry[1]).value.trim();
        if (value) params.set(entry[0], value);
      });
    params.set("page", String(page));
    params.set("size", "20");
    return params.toString();
  }

  function renderEmpty(message) {
    listBody.replaceChildren();
    var row = document.createElement("tr");
    var td = document.createElement("td");
    td.colSpan = 7;
    td.appendChild(element("div", "fgc-empty", message));
    row.appendChild(td);
    listBody.appendChild(row);
  }

  function renderRows(content) {
    listBody.replaceChildren();
    if (!content.length) {
      renderEmpty("조건에 맞는 자료가 없습니다.");
      return;
    }
    content.forEach(function (journal) {
      var row = document.createElement("tr");
      row.tabIndex = 0;
      row.dataset.journalId = journal.journalHeaderId;
      row.classList.toggle("is-selected", String(journal.journalHeaderId) === String(state.selectedId));
      row.appendChild(cell(text(journal.journalNo), "fgc-mono"));
      row.appendChild(cell(text(journal.journalDate)));
      row.appendChild(cell(text(journal.journalTypeLabel)));
      row.appendChild(cell(text(journal.contractNo || journal.contractId), "fgc-mono"));
      row.appendChild(cell(won(journal.debitTotal), "fgc-td-num"));
      row.appendChild(cell(won(journal.creditTotal), "fgc-td-num"));
      var statusCell = document.createElement("td");
      statusCell.appendChild(element("span", "fgc-badge " + statusTone(journal.status), journal.statusLabel));
      row.appendChild(statusCell);
      listBody.appendChild(row);
    });
  }

  function loadList(page) {
    renderEmpty("분개 목록을 불러오는 중입니다.");
    return apiClient.request("/api/v1/journals?" + query(page))
      .then(function (envelope) {
        var data = envelope.data;
        state.page = data.page;
        state.totalPages = Math.max(data.totalPages, 1);
        rowCount.textContent = data.totalElements;
        pageInfo.textContent = state.page + " / " + state.totalPages;
        previousButton.disabled = state.page <= 1;
        nextButton.disabled = state.page >= state.totalPages;
        renderRows(data.content || []);
      }).catch(function (error) {
        renderEmpty(error && error.message ? error.message : "분개 목록을 불러오지 못했습니다.");
      });
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
      headerRow.appendChild(element("th", "", label));
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

  function reverseAllowed(detail) {
    return canReverse && detail.status === "POSTED" && detail.journalType !== "REVERSAL"
      && !detail.reversedByJournalHeaderId;
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
    detailBody.appendChild(renderLines(detail.lines || []));

    var actions = element("div", "", "");
    actions.style.marginTop = "16px";
    if (reverseAllowed(detail)) {
      var button = element("button", "fgc-btn fgc-btn--primary", "역분개");
      button.type = "button";
      button.dataset.reverseJournal = detail.journalHeaderId;
      actions.appendChild(button);
    } else if (detail.status === "POSTED" && !canReverse) {
      actions.appendChild(element("p", "fgc-muted", "역분개는 SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN 권한이 필요합니다."));
    } else {
      actions.appendChild(element("p", "fgc-muted", "POSTED 원분개만 역분개할 수 있습니다."));
    }
    detailBody.appendChild(actions);
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
      return loadList(state.page).then(function () { return loadDetail(result.journalHeaderId); });
    }).catch(function (error) {
      if (window.FgcUi.toast) window.FgcUi.toast(error && error.message
        ? error.message : "역분개 생성에 실패했습니다.", "error");
    }).finally(function () {
      state.pending = false;
      reverseSubmit.disabled = false;
      reverseSubmit.removeAttribute("aria-busy");
    });
  }

  // 2026-08-19 yslee - 검증원장 조건 조회를 명시적 조회 버튼 방식으로 변경
  // 기존 코드: 페이지 진입 즉시 전체 원장을 조회하고 폼 제출 시 조건 조회를 실행
  // 문제: 사용자가 조회 조건을 확정하기 전에 전체 원장 조회 요청이 발생함
  // 개선: 초기 자동 조회를 제거하고 조회 버튼으로 제출한 조건만 1페이지부터 조회
  form.addEventListener("submit", function (event) {
    event.preventDefault();
    state.selectedId = null;
    loadList(1);
  });
  resetButton.addEventListener("click", function () {
    form.reset();
    state.selectedId = null;
    loadList(1);
  });
  previousButton.addEventListener("click", function () { if (state.page > 1) loadList(state.page - 1); });
  nextButton.addEventListener("click", function () { if (state.page < state.totalPages) loadList(state.page + 1); });
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
  });
  reverseReason.addEventListener("input", function () { reverseReasonError.hidden = Boolean(reverseReason.value.trim()); });
  reverseSubmit.addEventListener("click", submitReverse);

  renderEmpty("조회 조건을 설정하고 조회 버튼을 눌러 주세요.");
})();
