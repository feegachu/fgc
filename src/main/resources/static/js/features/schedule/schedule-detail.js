(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var format = window.FgcUi && window.FgcUi.format;
  var labels = window.FgcUi && window.FgcUi.scheduleLabels;
  var main = document.getElementById("main-content");
  var lineBody = document.getElementById("line-body");
  var lineTable = document.querySelector("[data-schedule-line-table]");
  var versionBody = document.getElementById("version-body");
  var versionTable = document.querySelector("[data-schedule-version-table]");
  var headerState = document.getElementById("hdr-state");
  var headerList = document.getElementById("hdr-kv");
  var actionNote = document.getElementById("schedule-action-note");
  var confirmButton = document.getElementById("btn-confirm");
  var confirmSubmit = document.getElementById("btn-confirm-submit");
  var regenerateButton = document.getElementById("btn-regenerate");
  var regenerateReason = document.getElementById("regenerate-reason");
  var regenerateReasonField = document.getElementById("regenerate-reason-field");
  var regenerateReasonError = document.getElementById("regenerate-reason-error");
  var regenerateSubmit = document.getElementById("btn-regenerate-submit");
  var exportButton = document.getElementById("btn-export");
  var stageSelect = document.getElementById("stage");

  if (!main || !lineBody || !apiClient || !format || !labels) return;

  var EMPTY = labels.EMPTY;
  var REASON_MAX = 40;
  var LINE_COLUMNS = 13;
  var VERSION_COLUMNS = 8;

  var currentPaymentStage = null;
  var currentHeader = null;
  var canProcess = confirmButton ? !confirmButton.disabled : false;

  var scheduleHeaderId = main.dataset.scheduleHeaderId || idFromPath();
  if (!scheduleHeaderId) {
    renderLineState("스케줄 ID를 확인할 수 없습니다.", "is-error");
    renderHeaderState("스케줄 ID를 확인할 수 없습니다.", "is-error");
    return;
  }

  load();
  if (confirmButton) confirmButton.addEventListener("click", openConfirmDialog);
  if (confirmSubmit) confirmSubmit.addEventListener("click", confirmSchedule);
  if (exportButton) exportButton.addEventListener("click", exportCsv);
  if (stageSelect) stageSelect.addEventListener("change", moveToPaymentStage);
  if (regenerateButton) regenerateButton.addEventListener("click", regenerateSchedule);
  if (regenerateReason) regenerateReason.addEventListener("input", updateRegenerateSubmit);
  if (regenerateSubmit) regenerateSubmit.addEventListener("click", submitRegeneration);

  function idFromPath() {
    var matched = window.location.pathname.match(/^\/schedules\/(\d+)\/?$/);
    return matched ? matched[1] : null;
  }

  /* ─────────────────────────── 조회 ─────────────────────────── */

  function load() {
    renderHeaderState("스케줄을 불러오는 중입니다.", "is-loading");
    renderLineState("예상 스케줄을 불러오는 중입니다.", "is-loading");
    setBusy(lineTable, true);

    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId))
      .then(function (envelope) {
        var detail = envelope && envelope.data;
        if (!detail || !detail.header) throw new Error("Invalid schedule detail response");
        renderHeader(detail.header);
        renderLines(Array.isArray(detail.lines) ? detail.lines : []);
        loadVersions();
      })
      .catch(function (error) {
        var message = format.errorText(error, "예상 스케줄을 불러오지 못했습니다.");
        renderHeaderState(message, "is-error", load);
        renderLineState(message, "is-error", load);
        setBusy(lineTable, false);
        updateTotals(0, 0);
        toast(message, "error", 5000);
      });
  }

  function loadVersions() {
    if (!versionBody) return;
    renderVersionState("버전 이력을 불러오는 중입니다.", "is-loading");
    setBusy(versionTable, true);

    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/versions")
      .then(function (envelope) {
        renderVersions(envelope && Array.isArray(envelope.data) ? envelope.data : []);
      })
      .catch(function (error) {
        var message = format.errorText(error, "버전 이력을 불러오지 못했습니다.");
        /* 예전에는 재시도 버튼도 Toast 도 없이 표 안 텍스트와 콘솔 로그뿐이었다. */
        renderVersionState(message, "is-error", loadVersions);
        toast(message, "error", 5000);
      })
      .finally(function () {
        setBusy(versionTable, false);
      });
  }

  /* ─────────────────────────── 헤더 ─────────────────────────── */

  function renderHeader(header) {
    currentHeader = header;
    setText("hdr-id", "schedule_header #" + value(header.scheduleHeaderId));
    setText("hdr-contract", header.contractNo);
    setText("hdr-product", join(header.insurerName, header.productName));
    setText("hdr-version", header.scheduleVersionNo == null ? EMPTY : "v" + header.scheduleVersionNo);
    setText("hdr-policy", header.policyVersionLabel);
    setText("hdr-reason", join(header.generationReason, format.dateTime(header.generatedAt)));

    /* 적용 체계·용도는 분류값이라 상태 5색을 쓰지 않는다 (규칙 4). */
    setBadge("hdr-regime",
      labels.responseLabel(header.scheduleRegimeLabel, labels.SCHEDULE_REGIME, header.scheduleRegime),
      labels.CLASSIFICATION_TONE, false);
    setBadge("hdr-purpose",
      labels.responseLabel(header.schedulePurposeLabel, labels.SCHEDULE_PURPOSE, header.schedulePurpose),
      labels.CLASSIFICATION_TONE, false);
    setBadge("hdr-status",
      labels.responseLabel(header.statusLabel, labels.SCHEDULE_STATUS, header.status),
      labels.statusTone(header.status), labels.isLocked(header.status));
    setBadge("hdr-active",
      header.activeYn === true ? "사용중" : "미사용",
      header.activeYn === true ? "status-badge-success" : "status-badge-neutral", false);

    if (headerState) headerState.hidden = true;
    if (headerList) headerList.hidden = false;

    if (stageSelect && header.paymentStage) {
      currentPaymentStage = header.paymentStage;
      stageSelect.value = header.paymentStage;
      stageSelect.disabled = false;
    }
    updateActionButtons(header);
  }

  /*
   * 비활성 사유를 가시 텍스트로 알린다.
   * 예전에는 confirmButton.title 하나로 뭉뚱그렸는데, disabled 요소는 포커스가 가지 않아
   * 스크린리더가 title 에 닿지 못했고 권한·상태·사용중 세 사유가 구분되지도 않았다.
   */
  function updateActionButtons(header) {
    var reasons = [];
    if (!canProcess) reasons.push("확정·재생성은 정산 담당자만 할 수 있습니다.");
    if (header.activeYn !== true) reasons.push("사용 중인 버전이 아닙니다.");
    if (header.status === "CONFIRMED") reasons.push("이미 확정된 스케줄입니다.");
    else if (header.status !== "PLANNED") reasons.push("예정 상태에서만 확정할 수 있습니다.");

    if (confirmButton) {
      confirmButton.disabled = !canProcess || header.status !== "PLANNED" || header.activeYn !== true;
    }
    if (regenerateButton) {
      regenerateButton.disabled = !canProcess || header.activeYn !== true || header.status === "CANCELLED";
    }
    if (actionNote) {
      var blocked = (confirmButton && confirmButton.disabled) || (regenerateButton && regenerateButton.disabled);
      actionNote.textContent = reasons.join(" ");
      actionNote.hidden = !blocked || reasons.length === 0;
    }
  }

  function moveToPaymentStage() {
    var targetStage = stageSelect.value;
    if (!targetStage || targetStage === currentPaymentStage) return;

    stageSelect.disabled = true;
    stageSelect.setAttribute("aria-busy", "true");
    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId)
      + "/active?paymentStage=" + encodeURIComponent(targetStage))
      .then(function (envelope) {
        var targetScheduleHeaderId = envelope && envelope.data;
        if (targetScheduleHeaderId == null) throw new Error("이동할 스케줄을 찾을 수 없습니다.");
        window.location.assign("/schedules/" + encodeURIComponent(String(targetScheduleHeaderId)));
      })
      .catch(function (error) {
        stageSelect.value = currentPaymentStage || "";
        stageSelect.disabled = false;
        stageSelect.removeAttribute("aria-busy");
        toast(format.errorText(error, "선택한 지급단계의 스케줄을 찾을 수 없습니다."), "warning", 4500);
      });
  }

  /* ─────────────────────────── 확정 ─────────────────────────── */

  /*
   * 확정은 되돌릴 수 없다 (화면정의서 :889). 전에는 버튼을 누르면 곧바로 POST 가 나갔고,
   * 정작 되돌릴 수 있는 재생성에만 확인 모달이 있었다 — 위험도와 확인 강도가 뒤집혀 있었다.
   */
  function openConfirmDialog() {
    if (!confirmButton || confirmButton.disabled) return;
    if (currentHeader) {
      setText("confirm-contract", currentHeader.contractNo);
      setText("confirm-version", currentHeader.scheduleVersionNo == null
        ? EMPTY : "v" + currentHeader.scheduleVersionNo);
      setText("confirm-line-count", format.int(currentHeader.lineCount));
      setText("confirm-total", format.won(currentHeader.expectedTotal));
    }
    openModal("schedule-confirm");
  }

  function confirmSchedule() {
    if (!confirmSubmit || confirmSubmit.disabled) return;

    confirmSubmit.disabled = true;
    confirmSubmit.setAttribute("aria-busy", "true");
    if (confirmButton) confirmButton.disabled = true;

    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/confirm", {
      method: "POST"
    }).then(function (envelope) {
      var detail = envelope && envelope.data;
      if (!detail || !detail.header) throw new Error("Invalid schedule confirmation response");
      closeModal("schedule-confirm");
      renderHeader(detail.header);
      renderLines(Array.isArray(detail.lines) ? detail.lines : []);
      loadVersions();
      toast("스케줄을 확정했습니다. 이제 금액을 고칠 수 없습니다. 바꾸려면 새 버전을 만드세요.", "success", 4500);
    }).catch(function (error) {
      toast(format.errorText(error, "예상 스케줄을 확정하지 못했습니다."), "error", 5000);
      if (confirmButton) confirmButton.disabled = false;
    }).finally(function () {
      /*
       * 여기서 확정 버튼으로 포커스를 옮기지 않는다 — 실패하면 모달이 열린 채로 남는데
       * 그때 바깥 버튼을 포커스하면 포커스 트랩이 깨진다.
       * 성공 시 포커스 복귀는 modal.js:37 의 closeModal 이 이미 처리한다.
       */
      confirmSubmit.disabled = false;
      confirmSubmit.removeAttribute("aria-busy");
    });
  }

  /* ───────────────────────── 새 버전 만들기 ───────────────────────── */

  function regenerateSchedule() {
    if (!regenerateButton || regenerateButton.disabled) return;
    if (regenerateReason) regenerateReason.value = "";
    setReasonError("");
    updateRegenerateSubmit();
    openModal("schedule-regenerate");
  }

  function updateRegenerateSubmit() {
    if (!regenerateSubmit) return;
    var reason = regenerateReason ? regenerateReason.value.trim() : "";
    regenerateSubmit.disabled = !reason || reason.length > REASON_MAX;
    if (reason && reason.length <= REASON_MAX) setReasonError("");
  }

  /* 사유 오류는 Toast 가 아니라 필드 옆 슬롯에 남긴다 (가이드 §11). */
  function setReasonError(message) {
    if (!regenerateReasonError) return;
    regenerateReasonError.textContent = message;
    regenerateReasonError.hidden = !message;
    if (regenerateReasonField) regenerateReasonField.classList.toggle("is-error", Boolean(message));
  }

  function submitRegeneration() {
    if (!regenerateSubmit || regenerateSubmit.disabled) return;
    var reason = regenerateReason ? regenerateReason.value.trim() : "";
    if (!reason) {
      setReasonError("저장 불가 — 생성 사유를 입력하세요.");
      if (regenerateReason) regenerateReason.focus();
      return;
    }
    if (reason.length > REASON_MAX) {
      setReasonError("생성 사유는 " + REASON_MAX + "자 이하여야 합니다.");
      if (regenerateReason) regenerateReason.focus();
      return;
    }

    regenerateButton.disabled = true;
    regenerateSubmit.disabled = true;
    regenerateSubmit.setAttribute("aria-busy", "true");

    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/regenerate", {
      method: "POST",
      body: { reason: reason }
    }).then(function (envelope) {
      var result = envelope && envelope.data;
      if (!result || !result.scheduleHeaderId) throw new Error("Invalid schedule regeneration response");
      closeModal("schedule-regenerate");
      toast("새 스케줄 버전을 만들었습니다. 새 버전으로 이동합니다.", "success", 3500);
      window.setTimeout(function () {
        window.location.assign("/schedules/" + encodeURIComponent(result.scheduleHeaderId));
      }, 700);
    }).catch(function (error) {
      toast(format.errorText(error, "새 스케줄 버전을 만들지 못했습니다."), "error", 5000);
      regenerateButton.disabled = false;
      updateRegenerateSubmit();
    }).finally(function () {
      regenerateSubmit.removeAttribute("aria-busy");
    });
  }

  /* ─────────────────────────── CSV ─────────────────────────── */

  /*
   * 전에는 window.location.assign 만 호출해 성공·실패 어느 쪽도 알리지 않았다.
   * SCHE-W01 과 같은 방식으로 fetch + Blob 저장에 Toast 를 붙인다.
   */
  function exportCsv() {
    exportButton.disabled = true;
    exportButton.setAttribute("aria-busy", "true");
    window.fetch("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/export.csv",
      { credentials: "same-origin" })
      .then(function (response) {
        if (!response.ok) throw new Error("CSV 내보내기에 실패했습니다. (HTTP " + response.status + ")");
        return response.blob();
      })
      .then(function (blob) {
        var objectUrl = window.URL.createObjectURL(blob);
        var link = document.createElement("a");
        link.href = objectUrl;
        link.download = "schedule-" + scheduleHeaderId + ".csv";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(objectUrl);
        toast("회차 표 CSV를 내려받았습니다.", "success", 3500);
      })
      .catch(function (error) {
        toast(format.errorText(error, "CSV 내보내기에 실패했습니다."), "error", 5000);
      })
      .finally(function () {
        exportButton.disabled = false;
        exportButton.removeAttribute("aria-busy");
      });
  }

  /* ─────────────────────────── 회차 표 ─────────────────────────── */

  function renderLines(lines) {
    clear(lineBody);
    if (lines.length === 0) {
      renderLineState("등록된 회차가 없습니다.", "is-empty");
      updateTotals(0, 0);
      setBusy(lineTable, false);
      return;
    }

    var total = 0;
    lines.forEach(function (line) {
      total += Number(line.expectedAmount) || 0;

      var row = document.createElement("tr");
      row.appendChild(numberCell(format.int(line.lineNo)));
      row.appendChild(numberCell(format.int(line.installmentNo)));
      row.appendChild(numberCell(format.int(line.contractMonthNo)));
      row.appendChild(textCell(format.date(line.dueDate)));
      row.appendChild(disclosureCell(line.commissionItemName));
      row.appendChild(disclosureCell(line.recipientName));
      row.appendChild(disclosureCell(line.basisCode));
      row.appendChild(moneyCell(line.basisAmount));
      row.appendChild(textCell(labels.responseLabel(null, labels.CALCULATION_TYPE, line.calculationType), "is-center"));
      row.appendChild(numberCell(line.ratePct == null ? EMPTY : format.rate(line.ratePct) + "%"));
      row.appendChild(moneyCell(line.expectedAmount));
      row.appendChild(badgeCell(
        labels.responseLabel(line.lineStatusLabel, labels.LINE_STATUS, line.lineStatus),
        labels.statusTone(line.lineStatus),
        labels.isLocked(line.lineStatus)));
      /* IF-API-28 의 ruleRef — 규칙 5 근거 표시. 예전에는 응답에 있는데도 렌더하지 않았다. */
      row.appendChild(numberCell(line.ruleRef == null ? EMPTY : format.int(line.ruleRef)));
      lineBody.appendChild(row);
    });

    updateTotals(lines.length, total);
    setBusy(lineTable, false);
    scheduleDisclosureSync();
  }

  function updateTotals(count, total) {
    setText("line-count", format.int(count));
    setText("line-total", format.won(total));
  }

  /* ─────────────────────────── 버전 표 ─────────────────────────── */

  function renderVersions(versions) {
    clear(versionBody);
    if (versions.length === 0) {
      renderVersionState("같은 계약의 스케줄 버전이 없습니다.", "is-empty");
      return;
    }

    versions.forEach(function (version) {
      var row = document.createElement("tr");
      var isCurrent = String(version.scheduleHeaderId) === String(scheduleHeaderId);
      /* 굵기 인라인 스타일 대신 공통 .is-selected + aria-current 로 현재 버전을 알린다. */
      if (isCurrent) {
        row.className = "is-selected";
        row.setAttribute("aria-current", "true");
      }

      var versionCell = document.createElement("td");
      versionCell.className = "is-center";
      var link = document.createElement("a");
      link.href = "/schedules/" + encodeURIComponent(version.scheduleHeaderId);
      link.className = "tabular-nums";
      link.textContent = version.scheduleVersionNo == null ? EMPTY : "v" + version.scheduleVersionNo;
      versionCell.appendChild(link);
      row.appendChild(versionCell);

      row.appendChild(badgeCell(
        labels.responseLabel(version.statusLabel, labels.SCHEDULE_STATUS, version.status),
        labels.statusTone(version.status),
        labels.isLocked(version.status)));
      row.appendChild(badgeCell(
        version.activeYn === true ? "사용중" : "미사용",
        version.activeYn === true ? "status-badge-success" : "status-badge-neutral", false));
      row.appendChild(disclosureCell(version.policyVersionLabel));
      row.appendChild(disclosureCell(version.generationReason));
      row.appendChild(textCell(format.dateTime(version.generatedAt)));
      row.appendChild(numberCell(format.int(version.lineCount)));
      row.appendChild(moneyCell(version.expectedTotal));
      versionBody.appendChild(row);
    });

    scheduleDisclosureSync();
  }

  /* ───────────────────── 로딩 · 빈 결과 · 오류 ───────────────────── */

  /*
   * 예전에는 로딩·빈·오류 6상태가 전부 같은 .fgc-empty 였고 오류만 인라인으로 색을 칠했다.
   * 상태별 클래스와 role 을 나누고, 오류에는 항상 다시 시도 버튼을 붙인다.
   * renderVersionMessage / appendMessage 로 갈려 있던 중복 함수도 하나로 합쳤다.
   */
  function stateRow(body, columns, message, variant, onRetry) {
    clear(body);
    var row = document.createElement("tr");
    var cell = document.createElement("td");
    cell.colSpan = columns;
    cell.appendChild(stateBlock(message, variant, onRetry));
    row.appendChild(cell);
    body.appendChild(row);
  }

  function stateBlock(message, variant, onRetry) {
    var block = document.createElement("p");
    block.className = "schedule-inline-state " + variant;
    block.setAttribute("role", variant === "is-error" ? "alert" : "status");
    block.appendChild(document.createTextNode(message));
    if (onRetry) {
      var retry = document.createElement("button");
      retry.type = "button";
      retry.className = "button button-secondary";
      retry.textContent = "다시 시도";
      retry.addEventListener("click", onRetry);
      block.appendChild(retry);
    }
    return block;
  }

  function renderLineState(message, variant, onRetry) {
    stateRow(lineBody, LINE_COLUMNS, message, variant, onRetry);
  }

  function renderVersionState(message, variant, onRetry) {
    if (!versionBody) return;
    stateRow(versionBody, VERSION_COLUMNS, message, variant, onRetry);
  }

  /* 헤더 영역도 상태를 구분한다 — 전에는 로딩·오류·무데이터가 모두 "—" 로 같아 보였다. */
  function renderHeaderState(message, variant, onRetry) {
    if (!headerState) return;
    if (headerList) headerList.hidden = true;
    headerState.className = "schedule-inline-state " + variant;
    headerState.setAttribute("role", variant === "is-error" ? "alert" : "status");
    headerState.hidden = false;
    clear(headerState);
    headerState.appendChild(document.createTextNode(message));
    if (onRetry) {
      var retry = document.createElement("button");
      retry.type = "button";
      retry.className = "button button-secondary";
      retry.textContent = "다시 시도";
      retry.addEventListener("click", onRetry);
      headerState.appendChild(retry);
    }
  }

  /* ─────────────────────────── 셀 유틸 ─────────────────────────── */

  function textCell(content, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    cell.textContent = value(content);
    return cell;
  }

  function numberCell(content) {
    return textCell(content, "is-number tabular-nums");
  }

  function moneyCell(content) {
    var cell = textCell(format.won(content), "is-number tabular-nums");
    if (format.isNegative(content)) cell.classList.add("is-negative-amount");
    return cell;
  }

  function badgeCell(text, tone, locked) {
    var cell = document.createElement("td");
    cell.className = "is-center";
    var element = document.createElement("span");
    element.className = "status-badge " + tone;
    element.appendChild(document.createTextNode(text));
    if (locked) {
      var lock = document.createElement("span");
      lock.className = "material-symbols-rounded schedule-badge-lock";
      lock.setAttribute("aria-hidden", "true");
      lock.textContent = "lock";
      element.appendChild(lock);
    }
    cell.appendChild(element);
    return cell;
  }

  /* 긴 값 접기·펴기 — components.css:576-653. DOM 으로 만들어 escape 를 따로 하지 않는다. */
  function disclosureCell(content) {
    var text = value(content);
    var cell = document.createElement("td");
    var root = document.createElement("div");
    root.className = "table-cell-disclosure";

    var preview = document.createElement("span");
    preview.className = "table-cell-preview is-single-line";
    preview.textContent = text;
    root.appendChild(preview);

    var details = document.createElement("details");
    details.className = "table-cell-details";
    details.hidden = true;

    var summary = document.createElement("summary");
    var more = document.createElement("span");
    more.className = "table-cell-more";
    more.textContent = "전체 보기";
    var less = document.createElement("span");
    less.className = "table-cell-less";
    less.textContent = "접기";
    var chevron = document.createElement("span");
    chevron.className = "material-symbols-rounded table-cell-chevron";
    chevron.setAttribute("aria-hidden", "true");
    chevron.textContent = "expand_more";
    summary.append(more, less, chevron);

    var full = document.createElement("p");
    full.className = "table-cell-full";
    full.textContent = text;

    details.append(summary, full);
    root.appendChild(details);
    cell.appendChild(root);
    return cell;
  }

  function scheduleDisclosureSync() {
    window.requestAnimationFrame(function () {
      document.querySelectorAll(".table-cell-disclosure").forEach(function (root) {
        var preview = root.querySelector(".table-cell-preview");
        var details = root.querySelector(".table-cell-details");
        if (!preview || !details || preview.clientWidth === 0) return;
        var truncated = preview.scrollWidth > preview.clientWidth + 1
          || preview.scrollHeight > preview.clientHeight + 1;
        details.hidden = !truncated;
        if (!truncated) details.open = false;
      });
    });
  }

  function setBadge(id, text, tone, locked) {
    var element = document.getElementById(id);
    if (!element) return;
    clear(element);
    var badge = document.createElement("span");
    badge.className = "status-badge " + tone;
    badge.appendChild(document.createTextNode(text));
    if (locked) {
      var lock = document.createElement("span");
      lock.className = "material-symbols-rounded schedule-badge-lock";
      lock.setAttribute("aria-hidden", "true");
      lock.textContent = "lock";
      badge.appendChild(lock);
    }
    element.appendChild(badge);
  }

  function setText(id, content) {
    var element = document.getElementById(id);
    if (element) element.textContent = value(content);
  }

  function setBusy(table, busy) {
    if (table) table.setAttribute("aria-busy", busy ? "true" : "false");
  }

  function join(first, second) {
    var parts = [first, second].filter(function (item) {
      return item != null && item !== "" && item !== EMPTY;
    });
    return parts.length ? parts.join(" · ") : EMPTY;
  }

  function value(content) {
    return content == null || content === "" ? EMPTY : String(content);
  }

  function clear(element) {
    while (element.firstChild) element.removeChild(element.firstChild);
  }

  function openModal(name) {
    if (window.FgcUi && window.FgcUi.modal) window.FgcUi.modal.open(name);
  }

  function closeModal(name) {
    if (window.FgcUi && window.FgcUi.modal) window.FgcUi.modal.close(name);
  }

  function toast(message, tone, duration) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") {
      window.FgcUi.toast(message, tone, duration);
    }
  }
})();
