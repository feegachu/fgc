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
  var executeButton = document.getElementById("btn-execute");
  var refreshButton = document.getElementById("btn-refresh");
  var stepper = document.getElementById("stepper");
  var statusBadge = document.getElementById("hdr-status");
  var checklistSummary = document.getElementById("cond-summary");
  var checklistBody = document.getElementById("cond-body");
  var finalizeButton = document.getElementById("btn-finalize");
  if (!apiClient || !executeButton || !stepper) {
    return;
  }

  var runId = executeButton.dataset.runId;
  var runStatus = executeButton.dataset.runStatus;
  var pollTimer = null;
  var finalizeRequestPending = false;
  var finalizeIdempotencyKey = null;

  var STATUS_TONES = {
    FAILED: "fgc-badge--violation",
    FINALIZED: "fgc-badge--review",
    COMPLETED: "fgc-badge--normal"
  };

  /* 서버 렌더링(vrun/detail.html 스텝퍼 th:classappend)과 같은 규칙 —
     current_step 은 "마지막으로 끝난 단계", RUNNING 중이면 그 다음 칸이 진행 중이다. */
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

  function renderProgress(progress) {
    stepper.querySelectorAll("[data-step]").forEach(function (cell) {
      cell.classList.remove(
        "fgc-stepper__step--done", "fgc-stepper__step--running", "fgc-stepper__step--failed");
      var cls = stepClass(Number(cell.dataset.step), progress.status, progress.currentStep);
      if (cls) {
        cell.classList.add(cls);
      }
    });
    if (statusBadge) {
      statusBadge.textContent = progress.statusLabel;
      statusBadge.className = "fgc-badge " + (STATUS_TONES[progress.status] || "fgc-badge--warning");
    }
  }

  function poll() {
    apiClient.request("/api/v1/validation-runs/" + runId + "/progress")
      .then(function (envelope) {
        var progress = envelope.data;
        renderProgress(progress);
        // execute 직후엔 배치가 아직 안 떠서 CREATED 가 조회될 수 있다 — 종료 상태
        // (COMPLETED/FAILED/FINALIZED)에서만 폴링을 멈추고 리로드한다.
        if (progress.status !== "RUNNING" && progress.status !== "CREATED") {
          window.clearInterval(pollTimer);
          pollTimer = null;
          window.location.reload();
        }
      })
      .catch(function () {
        /* 일시 오류(네트워크 등)는 다음 폴링에서 재시도한다 */
      });
  }

  function startPolling() {
    if (pollTimer !== null) {
      return;
    }
    pollTimer = window.setInterval(poll, 2000); // IF-API-49: 2초 폴링
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

  function setChecklistState(summary, message, tone) {
    checklistSummary.textContent = summary;
    checklistSummary.className = tone ? "fgc-badge " + tone : "fgc-muted";
    checklistBody.replaceChildren();
    var empty = document.createElement("div");
    empty.className = "fgc-empty publishing-empty-state";
    empty.textContent = message;
    checklistBody.appendChild(empty);
    finalizeButton.dataset.checklistPassed = "false";
    updateFinalizeButtonState();
  }

  function updateFinalizeButtonState() {
    if (!finalizeButton) return;
    var canFinalize = finalizeButton.dataset.canFinalize === "true";
    var checklistPassed = finalizeButton.dataset.checklistPassed === "true";
    finalizeButton.disabled = finalizeRequestPending || runStatus !== "COMPLETED" || !canFinalize || !checklistPassed;
    if (!canFinalize) {
      finalizeButton.title = "확정 권한은 GA_ADMIN 또는 SYSTEM_ADMIN에게만 있습니다.";
    } else if (!checklistPassed) {
      finalizeButton.title = "확정 조건 6개를 모두 통과해야 합니다.";
    } else {
      finalizeButton.removeAttribute("title");
    }
  }

  function renderChecklist(checklist) {
    checklistBody.replaceChildren();
    checklist.conditions.forEach(function (condition) {
      var row = document.createElement("div");
      row.className = "publishing-result-placeholder";

      var title = document.createElement("strong");
      title.textContent = condition.no + ". " + condition.label;
      row.appendChild(title);

      var result = document.createElement(condition.passed ? "span" : "a");
      result.className = "fgc-badge " + (condition.passed ? "fgc-badge--normal" : "fgc-badge--violation");
      result.textContent = condition.passed ? "통과" : "미충족 " + condition.count + "건";
      if (!condition.passed) {
        var link = safeInternalLink(condition.linkUrl);
        if (link) result.href = link;
      }
      row.appendChild(result);
      checklistBody.appendChild(row);
    });

    checklistSummary.textContent = checklist.passed ? "6개 조건 모두 통과" : "미충족 조건 있음";
    checklistSummary.className = "fgc-badge "
      + (checklist.passed ? "fgc-badge--normal" : "fgc-badge--violation");
    finalizeButton.dataset.checklistPassed = String(checklist.passed);
    updateFinalizeButtonState();
  }

  function loadFinalizeChecklist() {
    if (!checklistSummary || !checklistBody || !finalizeButton) return;
    if (runStatus === "FINALIZED") {
      setChecklistState("확정 완료", "확정된 실행의 결과와 계산 근거가 잠겼습니다.", "fgc-badge--review");
      return;
    }
    if (runStatus !== "COMPLETED") {
      setChecklistState("확인 대기", "검증 실행이 완료되면 확정 조건을 확인할 수 있습니다.");
      return;
    }

    checklistSummary.textContent = "확인 중";
    apiClient.request("/api/v1/validation-runs/" + runId + "/finalize-checklist")
      .then(function (envelope) {
        var checklist = envelope.data;
        if (!checklist || !Array.isArray(checklist.conditions) || checklist.conditions.length !== 6) {
          throw new apiClient.ApiError({ message: "확정 조건 응답 형식이 올바르지 않습니다." }, envelope.requestId, 200);
        }
        renderChecklist(checklist);
      })
      .catch(function (error) {
        setChecklistState("조회 실패", error && error.message
          ? error.message : "확정 조건을 불러오지 못했습니다.", "fgc-badge--violation");
      });
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

  function finalizeRun() {
    updateFinalizeButtonState();
    if (finalizeButton.disabled || finalizeRequestPending) return;
    if (!window.confirm("이 검증 실행을 확정하면 결과와 계산 근거를 고칠 수 없습니다. 확정하시겠습니까?")) {
      return;
    }

    finalizeRequestPending = true;
    finalizeIdempotencyKey = finalizeIdempotencyKey || createFinalizeIdempotencyKey();
    updateFinalizeButtonState();
    finalizeButton.setAttribute("aria-busy", "true");

    apiClient.request("/api/v1/validation-runs/" + runId + "/finalize", {
      method: "POST",
      idempotencyKey: finalizeIdempotencyKey
    }).then(function () {
      if (toast) toast("검증 실행을 확정했습니다.", "success");
      window.location.reload();
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
      if (toast) toast(error && error.message ? error.message : "검증 실행 확정에 실패했습니다.", "error");
      loadFinalizeChecklist();
    });
  }

  executeButton.addEventListener("click", function () {
    executeButton.disabled = true;
    apiClient.request("/api/v1/validation-runs/" + runId + "/execute", { method: "POST" })
      .then(function () {
        if (toast) {
          toast("검증 실행을 시작했습니다. 진행률을 2초 간격으로 갱신합니다.", "info");
        }
        startPolling();
      })
      .catch(function (error) {
        if (toast) {
          toast(error && error.message ? error.message : "실행 요청에 실패했습니다.", "error");
        }
        executeButton.disabled = false;
      });
  });

  if (refreshButton) {
    refreshButton.addEventListener("click", function () {
      window.location.reload();
    });
  }

  // 새로고침으로 진입했는데 이미 실행 중이면 폴링을 이어 붙인다
  if (executeButton.dataset.runStatus === "RUNNING") {
    startPolling();
  }
  if (finalizeButton) {
    finalizeButton.addEventListener("click", finalizeRun);
    updateFinalizeButtonState();
  }
  loadFinalizeChecklist();
})();
