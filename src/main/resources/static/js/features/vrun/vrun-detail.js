/**
 * FGC-UI-VRUN-W02 실행·진행률·확정 체크리스트 (FUN-042·043·044)
 * POST /api/v1/validation-runs/{id}/execute  (IF-API-48, 202 · 배치 비동기)
 * GET  /api/v1/validation-runs/{id}/progress (IF-API-49, 2초 폴링)
 * GET  /api/v1/validation-runs/{id}/finalize-checklist (IF-API-50)
 * POST /api/v1/validation-runs/{id}/finalize (IF-API-51)
 *
 * 헤더·스텝퍼·대상 선별·결과 요약은 서버 렌더링이다 — 이 스크립트는 실행 버튼과
 * 폴링 중 스텝퍼/상태 배지 갱신만 맡고, 종료 상태(COMPLETED/FAILED)는 전체
 * 리로드로 반영한다(결과 요약 4블록을 다시 그리는 코드를 만들지 않는다).
 */
(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var toast = window.FgcUi && window.FgcUi.toast;
  var format = window.FgcUi && window.FgcUi.format;
  var modal = window.FgcUi && window.FgcUi.modal;
  var executeButton = document.getElementById("btn-execute");
  var refreshButton = document.getElementById("btn-refresh");
  var stepper = document.getElementById("stepper");
  var progressText = document.getElementById("progress-text");
  var statusBadge = document.querySelector("#hdr-status .status-badge");
  var checklistSummary = document.getElementById("cond-summary");
  var checklistBody = document.getElementById("cond-body");
  var finalizeButton = document.getElementById("btn-finalize");
  var finalizeLabel = document.getElementById("btn-finalize-label");
  var finalizeSubmit = document.getElementById("btn-finalize-submit");
  var finalizedClose = document.getElementById("btn-finalized-close");
  var finalizeNote = document.getElementById("finalize-action-note");
  var runPicker = document.getElementById("pick-run");
  var runPickerButton = document.getElementById("btn-pick-run");
  if (!apiClient || !executeButton || !stepper) {
    return;
  }

  var runId = executeButton.dataset.runId;
  var runStatus = executeButton.dataset.runStatus;
  var pollTimer = null;
  var pollFailures = 0;
  var pollWarned = false;
  var finalizeRequestPending = false;
  var finalizeIdempotencyKey = null;

  /*
   * 상태 배지 톤 — templates/vrun/status-badge.html 의 서버 렌더링 매핑과 같은 클래스를 쓴다.
   * 전에는 이 파일과 두 템플릿에 각각 4벌이 흩어져 있었고 CREATED 와 RUNNING 이 같은 톤이라
   * "아직 시작 안 함"과 "돌고 있음"을 구분할 수 없었다.
   */
  var STATUS_BADGE_CLASSES = {
    CREATED: "status-badge-neutral",
    RUNNING: "status-badge-info",
    COMPLETED: "status-badge-success",
    FINALIZED: "status-badge-review",
    FAILED: "status-badge-error"
  };

  var STATUS_BADGE_CLASS_NAMES = Object.keys(STATUS_BADGE_CLASSES).map(function (key) {
    return STATUS_BADGE_CLASSES[key];
  });

  /* 서버 렌더링(vrun/detail.html 스텝퍼 th:classappend)과 같은 규칙 —
     current_step 은 "마지막으로 끝난 단계", RUNNING 중이면 그 다음 칸이 진행 중이다.
     클래스명은 ValidationRunViewController.stepClass() 가 정하는 계약이라 그대로 쓴다. */
  function stepClass(stepNo, status, currentStep) {
    if (status === "FAILED" && stepNo === currentStep) {
      return "fgc-stepper__step--failed";
    }
    if (status === "FINALIZED") {
      return "fgc-stepper__step--done";
    }
    if ((status === "RUNNING" || status === "COMPLETED" || status === "FAILED") && stepNo <= currentStep) {
      return "fgc-stepper__step--done";
    }
    if (status === "RUNNING" && stepNo === currentStep + 1 && stepNo <= 8) {
      return "fgc-stepper__step--running";
    }
    return "";
  }

  /* 색만으로 상태를 전달하지 않도록 칸마다 텍스트 대체도 같이 갱신한다 (규칙 4). */
  function stepStateText(cls) {
    if (cls.indexOf("failed") >= 0) return "실패";
    if (cls.indexOf("done") >= 0) return "완료";
    if (cls.indexOf("running") >= 0) return "진행 중";
    return "대기";
  }

  function renderProgress(progress) {
    stepper.querySelectorAll("[data-step]").forEach(function (cell) {
      cell.classList.remove(
        "fgc-stepper__step--done", "fgc-stepper__step--running", "fgc-stepper__step--failed");
      var cls = stepClass(Number(cell.dataset.step), progress.status, progress.currentStep);
      if (cls) {
        cell.classList.add(cls);
      }
      var state = cell.querySelector("[data-step-state]");
      if (state) state.textContent = stepStateText(cls);
    });

    /* 화면정의서 :1442 — 진행률은 current_step/10 × 100. 상세에도 표시한다. */
    if (progressText) {
      var step = Number(progress.currentStep) || 0;
      progressText.textContent = step + "/10 단계 (" + (step * 10) + "%)";
    }

    if (statusBadge) {
      /* className 통째 덮어쓰기는 병행 클래스를 잃는다 — exception-list.js 처럼 교체만 한다. */
      statusBadge.classList.remove.apply(statusBadge.classList, STATUS_BADGE_CLASS_NAMES);
      statusBadge.classList.add(STATUS_BADGE_CLASSES[progress.status] || "status-badge-neutral");
      var text = statusBadge.querySelector("span:not(.vrun-badge-lock)");
      if (text) {
        text.textContent = progress.status === "FINALIZED" ? "확정" : progress.statusLabel;
      }
    }
  }

  function poll() {
    apiClient.request("/api/v1/validation-runs/" + runId + "/progress")
      .then(function (envelope) {
        var progress = envelope.data;
        pollFailures = 0;
        if (pollWarned) {
          pollWarned = false;
          notify("진행률 조회가 정상으로 돌아왔습니다.", "info", 3000);
        }
        renderProgress(progress);
        // execute 직후엔 배치가 아직 안 떠서 CREATED 가 조회될 수 있다 — 종료 상태
        // (COMPLETED/FAILED/FINALIZED)에서만 폴링을 멈추고 리로드한다.
        if (progress.status !== "RUNNING" && progress.status !== "CREATED") {
          stopPolling();
          notify("검증 계산이 끝났습니다. 결과를 불러옵니다.", "success", 3000);
          window.setTimeout(function () { window.location.reload(); }, 700);
        }
      })
      .catch(function (error) {
        /*
         * 전에는 여기가 완전 무음이었다 — 네트워크가 끊겨도 사용자는 스텝퍼가 멈춘 이유를 알 수 없었다.
         * 일시 오류는 다음 폴링에서 회복되므로 연속 3회(약 6초) 실패해야 한 번만 알린다.
         */
        pollFailures += 1;
        if (pollFailures >= 3 && !pollWarned) {
          pollWarned = true;
          notify(errorText(error, "진행률을 불러오지 못하고 있습니다. 연결을 확인해 주세요."), "warning", 6000);
        }
      });
  }

  function startPolling() {
    if (pollTimer !== null) {
      return;
    }
    pollTimer = window.setInterval(poll, 2000); // IF-API-49: 2초 폴링
  }

  function stopPolling() {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }

  // 2026-08-19 yslee - FUN-044 확정 조건을 IF-API-50 응답으로 표시
  // 기존 코드: 체크리스트 영역이 정적 "연동 대기" 문구와 비활성 버튼만 표시
  // 문제: 사용자가 어떤 조건 때문에 확정할 수 없는지 화면에서 확인할 수 없음
  // 개선: 서버가 판정한 6개 조건을 그대로 표시하고 통과 여부를 후속 확정 게이트에 전달
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

  function setSummaryBadge(text, tone) {
    checklistSummary.textContent = text;
    checklistSummary.className = tone ? "status-badge " + tone : "vrun-card-note";
  }

  function setChecklistState(summary, message, tone, variant, onRetry) {
    setSummaryBadge(summary, tone);
    checklistBody.replaceChildren();
    checklistBody.appendChild(stateBlock(message, variant || "is-empty", onRetry));
    finalizeButton.dataset.checklistPassed = "false";
    updateFinalizeButtonState();
  }

  function stateBlock(message, variant, onRetry) {
    var block = document.createElement("p");
    block.className = "vrun-inline-state " + variant;
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

  /*
   * 비활성 사유는 title 이 아니라 버튼 바깥의 가시 텍스트로 전달한다.
   * disabled 요소는 포커스가 가지 않아 스크린리더가 title 에 닿을 수 없다.
   */
  function updateFinalizeButtonState() {
    if (!finalizeButton) return;
    var canFinalize = finalizeButton.dataset.canFinalize === "true";
    var checklistPassed = finalizeButton.dataset.checklistPassed === "true";
    finalizeButton.disabled = finalizeRequestPending || runStatus !== "COMPLETED" || !canFinalize || !checklistPassed;
    finalizeButton.removeAttribute("title");

    var reason = "";
    if (!canFinalize) {
      reason = "확정 권한은 GA_ADMIN 또는 SYSTEM_ADMIN 에게만 있습니다.";
    } else if (runStatus === "FINALIZED") {
      reason = "이미 확정된 실행입니다. 결과를 바꾸려면 새 실행을 만드세요.";
    } else if (runStatus !== "COMPLETED") {
      reason = "검증 계산이 끝난 실행만 확정할 수 있습니다.";
    } else if (!checklistPassed) {
      reason = "확정 조건 6개를 모두 통과해야 합니다. 아래 체크리스트를 확인하세요.";
    }
    if (finalizeNote) {
      finalizeNote.textContent = reason;
      finalizeNote.hidden = !reason;
    }
    if (finalizeLabel) {
      finalizeLabel.textContent = runStatus === "FINALIZED" ? "확정됨" : "확정";
    }
  }

  function renderChecklist(checklist) {
    checklistBody.replaceChildren();
    var list = document.createElement("div");
    list.className = "vrun-checklist";

    checklist.conditions.forEach(function (condition) {
      var item = document.createElement("div");
      item.className = "vrun-checklist-item";

      var label = document.createElement("div");
      label.className = "vrun-checklist-label";
      label.appendChild(disclosure(condition.no + ". " + condition.label));
      item.appendChild(label);

      var result = document.createElement("div");
      result.className = "vrun-checklist-result";

      var badge = document.createElement("span");
      badge.className = "status-badge "
        + (condition.passed ? "status-badge-success" : "status-badge-error");
      badge.textContent = condition.passed ? "통과" : "미충족 " + condition.count + "건";
      result.appendChild(badge);

      /*
       * 전에는 배지 자체를 <a> 로 만들었다. safeInternalLink 가 null 을 돌려주면
       * href 없는 유령 링크가 남아 키보드로 닿을 수 없었고, 링크 텍스트도 "미충족 n건" 뿐이라
       * 어디로 가는지 알 수 없었다. 링크가 실제로 있을 때만 별도 "바로가기" 를 만든다 (목업 :424).
       */
      if (!condition.passed) {
        var link = safeInternalLink(condition.linkUrl);
        if (link) {
          var anchor = document.createElement("a");
          anchor.href = link;
          anchor.textContent = "바로가기";
          anchor.setAttribute("aria-label", condition.label + " 미충족 건 바로가기");
          result.appendChild(anchor);
        }
      }

      item.appendChild(result);
      list.appendChild(item);
    });

    checklistBody.appendChild(list);
    setSummaryBadge(checklist.passed ? "6개 조건 모두 통과" : "미충족 조건 있음",
      checklist.passed ? "status-badge-success" : "status-badge-error");
    finalizeButton.dataset.checklistPassed = String(checklist.passed);
    updateFinalizeButtonState();
    syncDisclosures();
  }

  function loadFinalizeChecklist() {
    if (!checklistSummary || !checklistBody || !finalizeButton) return;
    if (runStatus === "FINALIZED") {
      setChecklistState("확정 완료", "확정된 실행의 결과와 계산 근거가 잠겼습니다.",
        "status-badge-review", "is-empty");
      setBusy(false);
      return;
    }
    if (runStatus !== "COMPLETED") {
      setChecklistState("확인 대기", "검증 실행이 완료되면 확정 조건을 확인할 수 있습니다.", null, "is-empty");
      setBusy(false);
      return;
    }

    setSummaryBadge("확인 중", null);
    setBusy(true);
    apiClient.request("/api/v1/validation-runs/" + runId + "/finalize-checklist")
      .then(function (envelope) {
        var checklist = envelope.data;
        if (!checklist || !Array.isArray(checklist.conditions) || checklist.conditions.length !== 6) {
          throw new apiClient.ApiError({ message: "확정 조건 응답 형식이 올바르지 않습니다." }, envelope.requestId, 200);
        }
        renderChecklist(checklist);
      })
      .catch(function (error) {
        var message = errorText(error, "확정 조건을 불러오지 못했습니다.");
        setChecklistState("조회 실패", message, "status-badge-error", "is-error", loadFinalizeChecklist);
        notify(message, "error", 5000);
      })
      .finally(function () {
        setBusy(false);
      });
  }

  function setBusy(busy) {
    if (checklistBody) checklistBody.setAttribute("aria-busy", busy ? "true" : "false");
  }

  // 2026-08-19 yslee - FUN-044 검증 실행 확정 API를 화면 버튼에 연결
  // 기존 코드: 확정 버튼이 항상 비활성이고 IF-API-51을 호출하는 사용자 동작이 없음
  // 문제: 서버 확정 기능이 구현돼도 VRUN-W02에서 사람이 검토 후 확정할 수 없음
  // 개선: 권한·완료 상태·체크리스트를 모두 확인하고 멱등키로 한 번만 확정 요청
  function createFinalizeIdempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return "vrun-finalize-" + runId + "-" + window.crypto.randomUUID();
    }
    return "vrun-finalize-" + runId + "-" + Date.now();
  }

  /*
   * 확정 확인 — 전에는 브라우저 기본 확인 대화상자였다.
   * 브라우저 기본 대화상자는 화면정의서 :1487 이 요구하는 "검증 결과 잠금이며 실제 송금·회계
   * 마감이 아닙니다" 문구를 담을 수 없고 포커스 복귀·스타일도 제어할 수 없다.
   */
  function openFinalizeDialog() {
    updateFinalizeButtonState();
    if (finalizeButton.disabled || finalizeRequestPending) return;
    if (modal) modal.open("vrun-finalize");
  }

  function finalizeRun() {
    if (finalizeRequestPending) return;

    finalizeRequestPending = true;
    finalizeIdempotencyKey = finalizeIdempotencyKey || createFinalizeIdempotencyKey();
    updateFinalizeButtonState();
    finalizeButton.setAttribute("aria-busy", "true");
    if (finalizeSubmit) {
      finalizeSubmit.disabled = true;
      finalizeSubmit.setAttribute("aria-busy", "true");
    }

    apiClient.request("/api/v1/validation-runs/" + runId + "/finalize", {
      method: "POST",
      idempotencyKey: finalizeIdempotencyKey
    }).then(function () {
      /*
       * 전에는 Toast 를 띄우자마자 reload() 해서 DOM 이 통째로 바뀌어 성공 피드백이 보이지 않았다.
       * 발표 시연 10컷의 마지막 단계다 — 목업 :195-210 의 확정 완료 모달로 대신하고
       * 사용자가 닫을 때 리로드한다.
       */
      if (modal) {
        modal.close("vrun-finalize");
        modal.open("vrun-finalized");
      } else {
        window.location.reload();
      }
    }).catch(function (error) {
      finalizeRequestPending = false;
      finalizeButton.removeAttribute("aria-busy");
      // 2026-08-19 yslee - FGC-VRUN-006 멱등키 충돌 시 다음 재시도에서 새 키를 발급하도록 수정
      // 기존 코드: 모든 실패 후 최초 멱등키를 계속 재사용
      // 문제: 다른 검증 실행에 귀속된 키 충돌 시 새로고침 전까지 같은 409 오류가 반복됨
      // 개선: 문서에서 새 키 재시도를 요구하는 FGC-VRUN-006에서만 저장된 키를 초기화
      if (error && error.code === "FGC-VRUN-006") {
        finalizeIdempotencyKey = null;
      }
      if (modal) modal.close("vrun-finalize");
      notify(errorText(error, "검증 실행 확정에 실패했습니다."), "error", 5000);
      loadFinalizeChecklist();
    }).finally(function () {
      if (finalizeSubmit) {
        finalizeSubmit.disabled = false;
        finalizeSubmit.removeAttribute("aria-busy");
      }
    });
  }

  /* 긴 값 접기·펴기 — components.css:576-653. DOM 으로 만들어 escape 를 따로 하지 않는다. */
  function disclosure(text) {
    var value = text == null || text === "" ? "-" : String(text);
    var root = document.createElement("div");
    root.className = "table-cell-disclosure";

    var preview = document.createElement("span");
    preview.className = "table-cell-preview";
    preview.textContent = value;
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
    full.textContent = value;

    details.append(summary, full);
    root.appendChild(details);
    return root;
  }

  function syncDisclosures() {
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

  /* 오류 문구에 오류코드(FGC-VRUN-001~006)와 요청 ID 를 함께 붙인다. */
  function errorText(error, fallback) {
    if (format && typeof format.errorText === "function") return format.errorText(error, fallback);
    return (error && error.message) || fallback;
  }

  function notify(message, tone, duration) {
    if (typeof toast === "function") toast(message, tone, duration);
  }

  executeButton.addEventListener("click", function () {
    executeButton.disabled = true;
    executeButton.setAttribute("aria-busy", "true");
    apiClient.request("/api/v1/validation-runs/" + runId + "/execute", { method: "POST" })
      .then(function () {
        notify("검증 실행을 시작했습니다. 진행률을 2초 간격으로 갱신합니다.", "info");
        startPolling();
      })
      .catch(function (error) {
        notify(errorText(error, "실행 요청에 실패했습니다."), "error");
        executeButton.disabled = false;
      })
      .finally(function () {
        executeButton.removeAttribute("aria-busy");
      });
  });

  if (refreshButton) {
    refreshButton.addEventListener("click", function () {
      notify("최신 상태를 불러옵니다.", "info", 2000);
      window.setTimeout(function () { window.location.reload(); }, 300);
    });
  }

  /*
   * 실행 선택 — 전에는 select 의 change 만으로 곧바로 이동했다.
   * 키보드로 값을 훑기만 해도 페이지가 바뀌었고 이동한다는 예고도 없었다.
   */
  if (runPickerButton && runPicker) {
    runPickerButton.addEventListener("click", function () {
      if (runPicker.value) {
        window.location.assign("/validation-runs/" + encodeURIComponent(runPicker.value));
      }
    });
  }

  if (finalizedClose) {
    finalizedClose.addEventListener("click", function () {
      window.location.reload();
    });
  }

  if (finalizeSubmit) {
    finalizeSubmit.addEventListener("click", finalizeRun);
  }

  // 새로고침으로 진입했는데 이미 실행 중이면 폴링을 이어 붙인다
  if (executeButton.dataset.runStatus === "RUNNING") {
    startPolling();
  }
  if (finalizeButton) {
    finalizeButton.addEventListener("click", openFinalizeDialog);
    updateFinalizeButtonState();
  }
  loadFinalizeChecklist();
})();
