/**
 * FGC-UI-VRUN-W02 실행·진행률 (FUN-042·043)
 * POST /api/v1/validation-runs/{id}/execute  (IF-API-48, 202 · 배치 비동기)
 * GET  /api/v1/validation-runs/{id}/progress (IF-API-49, 2초 폴링)
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
  if (!apiClient || !executeButton || !stepper) {
    return;
  }

  var runId = executeButton.dataset.runId;
  var pollTimer = null;

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
        if (progress.status !== "RUNNING") {
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
})();
