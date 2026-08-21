(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var format = window.FgcUi && window.FgcUi.format;
  var modal = window.FgcUi && window.FgcUi.modal;
  var main = document.querySelector("[data-transaction-form]");
  if (!apiClient || !format || !modal || !main) return;

  var AGENTS = [];
  var MANUAL_PAYMENT_SOURCE_TYPE = "GA_MANUAL_PAYMENT";
  var SOURCE_TYPES = [[MANUAL_PAYMENT_SOURCE_TYPE, "GA 수기지급"]];
  var contracts = [];
  var attributionSequence = 0;
  var saving = false;
  var initializationError = false;
  var paymentId = new URLSearchParams(window.location.search).get("id");
  var lastPrecheckResult = null;
  /*
   * 확정 게이트 6단계 — 화면정의서 :710-718 · 운영정책서 제31조의 순서를 그대로 옮긴다.
   * 게이트를 임의로 늘리거나 줄이지 않는다. 이전 구현은 차익거래·업무키 게이트를 만들어 넣었는데
   * 두 문서 어디에도 없고(화면정의서 :795 "검증 시 생성: cap_check, exception_case",
   * 제31조 게이트 목록) precheck 가 해당 코드를 발행하지도 않아 영구히 판정되지 않는 칸이었다.
   *
   * codes 는 IF-API-24 blockers[] 의 오류 카탈로그 코드다. Blocker.code 는 FgcErrorCode.getCode()
   * ("FGC-TRAN-002" 형태), message 는 부록 A 한글 표준 문구이므로
   * (TransactionPrecheckResponse.Blocker) 영문 키워드 부분일치로는 분류할 수 없다 —
   * 이전 구현에서 "FGC-CAP-*" 가 전부 "CAP" 키에 걸려 한도 게이트로 몰리고
   * 나머지는 마지막 게이트로 떨어지던 원인이다.
   *
   * 6단계는 검사가 아니라 "앞 단계를 전부 통과하면 확정" 이라는 결과다 —
   * 앞의 어느 게이트에도 속하지 않는 차단 사유(증빙 누락·정책버전 없음 등)를 여기서 받는다.
   */
  /*
   * 서버가 "거부하면서 기록하는" 차단 사유 — 확정 요청이 서버에 닿아야만
   * cap_check(VIOLATION·REVIEW_REQUIRED) 와 exception_case 가 남는다.
   * IF-API-24 사전검증은 아무것도 저장하지 않으므로(TransactionPrecheckResponse)
   * 이 코드들에서 확정 요청을 막아 버리면 위반 이력이 영구히 생기지 않는다 —
   * 화면정의서 TRAN-W02 :775 "한도 초과인데 확정 → 서버가 거부 + 예외 자동 생성",
   * CommissionPaymentServiceImpl 의 insertCapCheck → createIfNecessary → failFirst 순서와
   * @Transactional(noRollbackFor = ...Rejected) 가 이 동작을 보장한다.
   * 확정을 허용하는 게 아니라 "서버 판정을 받으러 보내는" 것이며 상태는 DRAFT 그대로다.
   * 입력 오류(FGC-TRAN-*)는 여기 넣지 않는다 — 보내 봐야 DATA_QUALITY 예외만 쌓인다.
   */
  var RECORDABLE_BLOCKER_CODES = ["FGC-CAP-001", "FGC-CAP-002"];

  var GATES = [
    { label: "작성중(DRAFT) 저장", codes: ["FGC-TRAN-005"] },
    { label: "귀속행 입력", codes: ["FGC-TRAN-002"] },
    { label: "귀속합계 = 지급액", codes: ["FGC-TRAN-003"] },
    { label: "검토필요 귀속 해소", codes: ["FGC-CAP-002"] },
    { label: "1,200% 사전검증", codes: ["FGC-CAP-001", "FGC-CAP-003", "FGC-CAP-004"] },
    { label: "전부 통과 → 확정", codes: [] }
  ];

  var stage = document.getElementById("stage");
  var sourceType = document.getElementById("sourceType");
  var bizKey = document.getElementById("bizKey");
  var settlementMonth = document.getElementById("settlementMonth");
  var recipient = document.getElementById("recipient");
  var item = document.getElementById("item");
  var amount = document.getElementById("amount");
  var cashflow = document.getElementById("cashflow");
  var evidence = document.getElementById("evidence");
  var attrBody = document.getElementById("attr-body");
  var addButton = document.getElementById("btn-add-attr");
  var saveButton = document.getElementById("btn-save");
  var precheckButton = document.getElementById("btn-precheck");
  var confirmButton = document.getElementById("btn-confirm");
  var recipientField = document.getElementById("recipient-field");
  var formStatus = document.getElementById("transaction-form-status");
  var lockNotice = document.getElementById("draft-lock-notice");
  var confirmSubmitButton = document.getElementById("btn-confirm-submit");
  var confirmSummary = document.getElementById("transaction-confirm-summary");
  var confirmBlockers = document.getElementById("transaction-confirm-blockers");
  var confirmFollowUp = document.getElementById("transaction-confirm-followup");
  var canProcess = !saveButton.disabled;
  var previousPaymentStage = stage.value;

  initializeSettlementMonth();
  fillOptions(sourceType, SOURCE_TYPES, "원천유형을 선택하세요");
  sourceType.value = MANUAL_PAYMENT_SOURCE_TYPE;
  fillOptions(recipient, [], "설계사를 불러오는 중입니다.");
  if (!paymentId && !bizKey.value) bizKey.value = generateBusinessKey();

  addButton.addEventListener("click", addAttributionRow);
  saveButton.addEventListener("click", save);
  precheckButton.addEventListener("click", precheck);
  confirmButton.addEventListener("click", openConfirmation);
  confirmSubmitButton.addEventListener("click", confirmPayment);
  amount.addEventListener("input", updateAttributionSummary);
  settlementMonth.addEventListener("change", function () {
    loadAgents();
    loadCommissionItems();
    attrBody.querySelectorAll("[data-attr-date]").forEach(function (input) {
      if (!input.value) input.value = monthFirstDay();
    });
  });
  cashflow.addEventListener("change", filterCommissionItemsByCashflow);
  stage.addEventListener("change", handlePaymentStageChange);
  item.addEventListener("change", syncAttributionModeForSelectedItem);
  recipient.addEventListener("change", handleRecipientChange);

  renderGates([], null);
  if (canProcess) {
    precheckButton.disabled = !paymentId;
    confirmButton.disabled = true;
  }

  loadContracts()
    .then(function () {
      if (paymentId) return loadDraft(paymentId);
      return Promise.all([loadAgents(), loadCommissionItems()])
        .then(function () { syncPaymentStageUi(); addAttributionRow(); });
    })
    .then(function () {
      main.setAttribute("aria-busy", "false");
      if (!initializationError) formStatus.hidden = true;
    })
    .catch(function (error) {
      main.setAttribute("aria-busy", "false");
      formStatus.classList.add("is-error");
      formStatus.textContent = format.errorText(error, "지급 건 화면을 초기화하지 못했습니다. 화면을 새로고침하세요.");
      showError(error, "지급 건 화면을 초기화하지 못했습니다. 화면을 새로고침하세요.");
    });

  function loadAgents() {
    var asOf = monthFirstDay();
    if (!asOf) return Promise.resolve();
    recipient.disabled = true;
    return apiClient.request("/api/v1/base/agents?asOf=" + encodeURIComponent(asOf) + "&page=1&size=100")
      .then(function (envelope) {
        var rows = envelope && envelope.data && Array.isArray(envelope.data.content)
          ? envelope.data.content
          : [];
        AGENTS = rows.filter(function (agent) {
          return agent.activeYn !== false && agent.agentStatus === "ACTIVE";
        }).map(function (agent) {
          return {
            id: agent.agentId,
            code: agent.agentCode,
            name: agent.agentName,
            rank: agent.rankCode,
            organizationId: agent.organizationId,
            organizationName: agent.organizationName
          };
        });
        fillOptions(recipient, AGENTS.map(function (agent) {
          return [String(agent.id), agent.code + " " + agent.name];
        }), "수령 설계사를 선택하세요");
        recipient.disabled = false;
      })
      .catch(function (error) {
        fillOptions(recipient, [], "설계사를 불러오지 못했습니다.");
        markReferenceError(error, "설계사 목록을 불러오지 못했습니다. 정산월을 확인한 뒤 다시 시도하세요.");
        showError(error, "설계사 목록을 불러오지 못했습니다.");
      });
  }

  function loadContracts(agentId) {
    var query = "?page=1&size=100" + (agentId ? "&agentId=" + encodeURIComponent(agentId) : "");
    return apiClient.request("/api/v1/contracts" + query)
      .then(function (envelope) {
        contracts = envelope && envelope.data && Array.isArray(envelope.data.content)
          ? envelope.data.content
          : [];
        refreshContractSelects();
      })
      .catch(function (error) {
        markReferenceError(error, "계약 목록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.");
        showError(error, "계약 목록을 불러오지 못했습니다.");
      });
  }

  function loadCommissionItems() {
    var asOf = monthFirstDay();
    if (!asOf) return Promise.resolve();
    return apiClient.request("/api/v1/base/commission-items?asOf=" + encodeURIComponent(asOf))
      .then(function (envelope) {
        var items = envelope && Array.isArray(envelope.data) ? envelope.data : [];
        item.dataset.items = JSON.stringify(items);
        filterCommissionItemsByCashflow();
      })
      .catch(function (error) {
        markReferenceError(error, "수수료 항목을 불러오지 못했습니다. 정산월을 확인한 뒤 다시 시도하세요.");
        showError(error, "수수료 항목을 불러오지 못했습니다.");
      });
  }

  function filterCommissionItemsByCashflow() {
    var items = [];
    try { items = JSON.parse(item.dataset.items || "[]"); } catch (ignored) { items = []; }
    var selected = item.value;
    var options = items.filter(function (entry) {
      return entry.cashflowType === cashflow.value
        && (!isInsurerToGa() || entry.itemCode !== "NEWCOMER_SUPPORT");
    })
      .map(function (entry) {
        return [String(entry.commissionItemId), entry.itemName];
      });
    fillOptions(item, options, "수수료 항목을 선택하세요");
    item.value = selected;
    syncAttributionModeForSelectedItem();
  }

  function loadDraft(id) {
    return apiClient.request("/api/v1/transactions/" + encodeURIComponent(id))
      .then(function (envelope) {
        var draft = envelope && envelope.data;
        if (!draft || draft.status !== "DRAFT") throw new Error("수정 가능한 지급 초안이 아닙니다.");
        stage.value = draft.paymentStage || "";
        sourceType.value = MANUAL_PAYMENT_SOURCE_TYPE;
        bizKey.value = draft.sourceBusinessKey || "";
        settlementMonth.value = String(draft.settlementMonth || "").slice(0, 7);
        cashflow.value = draft.cashflowType || "PAYMENT";
        return Promise.all([loadAgents(), loadCommissionItems()]).then(function () {
          recipient.value = draft.agentId == null ? "" : String(draft.agentId);
          filterCommissionItemsByCashflow();
          item.value = draft.commissionItemId == null ? "" : String(draft.commissionItemId);
          amount.value = draft.amount == null ? "" : draft.amount;
          evidence.value = draft.evidenceRef || "";
          clear(attrBody);
          (draft.attributions || []).forEach(function (attribution) {
            addAttributionRow();
            var row = attrBody.lastElementChild;
            row.querySelector("[data-contract-id]").value = attribution.contractId == null
              ? "" : String(attribution.contractId);
            row.querySelector("[data-attr-date]").value = attribution.attributionDate || "";
            row.querySelector("[data-attr-month]").value = attribution.attributionMonth || "";
            row.querySelector("[data-attr-amount]").value = attribution.amount;
            setInclusionDecision(
              row.querySelector("[data-inclusion]"),
              attribution.inclusionDecisionStatus,
              attribution.exclusionType
            );
            row.querySelector("[data-attr-method]").value = attribution.attributionMethod;
            row.querySelector("[data-allocation-basis]").value = attribution.allocationBasis || "";
            row.querySelector("[data-attr-evidence]").value = attribution.evidenceRef || "";
            applyAttributionMode(row);
          });
          if (!attrBody.querySelector("[data-attr-row]")) addAttributionRow();
          setTransactionStatus("작성중 · #" + id, "status-badge-neutral");
          setButtonLabel(saveButton, "수정 저장");
          syncPaymentStageUi();
          previousPaymentStage = stage.value;
          updateAttributionAgentLabels();
          updateAttributionSummary();
        });
      });
  }

  function addAttributionRow() {
    if (attrBody.querySelector(".empty-state")) clear(attrBody);
    attributionSequence += 1;
    var row = document.createElement("tr");
    row.dataset.attrRow = "";

    appendTextCell(row, attributionSequence);
    var scopeCell = appendTextCell(row, "계약");
    scopeCell.dataset.attributionScope = "";
    row.appendChild(controlCell(contractSelect()));
    appendAgentCell(row);
    row.appendChild(controlCell(input("date", monthFirstDay(), "data-attr-date")));
    row.appendChild(controlCell(readonlyInput(monthFirstDay())));
    row.appendChild(controlCell(input("number", suggestedAmount(), "data-attr-amount")));
    row.appendChild(controlCell(inclusionControls()));
    row.appendChild(controlCell(methodSelect()));
    row.appendChild(controlCell(input("text", "직접 귀속", "data-allocation-basis")));
    row.appendChild(controlCell(input("text", "", "data-attr-evidence")));

    var removeCell = document.createElement("td");
    var removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "button button-ghost";
    removeButton.textContent = "삭제";
    removeButton.setAttribute("aria-label", attributionSequence + "번 귀속행 삭제");
    removeButton.addEventListener("click", function () {
      row.remove();
      renumberRows();
      updateAttributionSummary();
      if (!attrBody.querySelector("[data-attr-row]")) renderEmptyAttributions();
    });
    removeCell.appendChild(removeButton);
    row.appendChild(removeCell);

    row.querySelector("[data-attr-amount]").addEventListener("input", updateAttributionSummary);
    row.querySelector("[data-contract-id]").addEventListener("change", function () {
      syncAttributionDateWithContract(row);
    });
    row.querySelector("[data-attr-date]").addEventListener("change", function (event) {
      var monthInput = row.querySelector("[data-attr-month]");
      monthInput.value = event.target.value ? event.target.value.slice(0, 7) + "-01" : "";
    });
    row.querySelector("[data-attr-method]").addEventListener("change", function () {
      applyAttributionMode(row);
    });
    attrBody.appendChild(row);
    applyAttributionMode(row);
    updateAttributionSummary();
  }

  function contractSelect() {
    var select = document.createElement("select");
    select.dataset.contractId = "";
    fillOptions(select, contracts.map(function (contract) {
      return [String(contract.contractId), contract.contractNo + " · " + contract.productName];
    }), "계약을 선택하세요");
    return select;
  }

  function refreshContractSelects() {
    attrBody.querySelectorAll("[data-contract-id]").forEach(function (select) {
      var selected = select.value;
      fillOptions(select, contracts.map(function (contract) {
        return [String(contract.contractId), contract.contractNo + " · " + contract.productName];
      }), "계약을 선택하세요");
      select.value = selected;
    });
  }

  function inclusionControls() {
    var decision = document.createElement("select");
    decision.dataset.inclusion = "";
    fillOptions(decision, inclusionOptions());
    return decision;
  }

  function inclusionOptions() {
    var options = [
      ["INCLUDED", "산입"],
      ["REVIEW_REQUIRED", "검토필요"],
      ["EXCLUDED:VOICE_RECORDING", "제외 · 녹취"],
      ["EXCLUDED:BROADCAST", "제외 · 방송"],
      ["EXCLUDED:NEW_AGENT_SUPPORT", "제외 · 신인 지원"]
    ];
    if (isInsurerToGa()) options.push(["EXCLUDED:COMPLIANCE_3PCT", "제외 · 준법경영비"]);
    return options;
  }

  function setInclusionDecision(select, inclusionStatus, exclusionType) {
    var value = inclusionStatus === "EXCLUDED"
      ? "EXCLUDED:" + (exclusionType || "VOICE_RECORDING")
      : inclusionStatus;
    select.value = value;
  }

  function parseInclusionDecision(value) {
    var parts = value.split(":");
    return {
      inclusionStatus: parts[0],
      exclusionType: parts.length > 1 ? parts[1] : "NONE"
    };
  }

  function methodSelect() {
    var select = document.createElement("select");
    select.dataset.attrMethod = "";
    fillOptions(select, [
      ["DIRECT", "직접 귀속"], ["SETTLEMENT_SUPPORT_MONTHLY", "정착지원금 월배부"],
      ["FIRST_CONTRACT_CARRY_FORWARD", "최초계약 이월"], ["APPROVED_ALLOCATION", "승인 배부"],
      ["MANUAL_REVIEW", "수기 검토"]
    ]);
    return select;
  }

  function selectedCommissionItem() {
    var items = [];
    try { items = JSON.parse(item.dataset.items || "[]"); } catch (ignored) { items = []; }
    return items.find(function (entry) {
      return String(entry.commissionItemId) === item.value;
    }) || null;
  }

  function isNewcomerSupportSelected() {
    var selected = selectedCommissionItem();
    return !isInsurerToGa() && selected && selected.itemCode === "NEWCOMER_SUPPORT";
  }

  function isInsurerToGa() { return stage.value === "INSURER_TO_GA"; }

  function handlePaymentStageChange() {
    if (stage.value === previousPaymentStage) return;
    clear(attrBody);
    attributionSequence = 0;
    renderEmptyAttributions();
    item.value = "";
    recipient.value = "";
    previousPaymentStage = stage.value;
    syncPaymentStageUi();
    updateAttributionSummary();
    toast("지급단계가 변경되어 수수료 항목과 귀속행을 초기화했습니다.", "warning", 5000);
  }

  function syncPaymentStageUi() {
    var insurerToGa = isInsurerToGa();
    recipientField.hidden = insurerToGa;
    recipient.disabled = insurerToGa;
    if (insurerToGa) recipient.value = "";
    filterCommissionItemsByCashflow();
    syncAttributionModeForSelectedItem();
  }

  function syncAttributionModeForSelectedItem() {
    attrBody.querySelectorAll("[data-attr-row]").forEach(applyAttributionMode);
  }

  function applyAttributionMode(row) {
    var newcomerSupport = isNewcomerSupportSelected();
    var scope = row.querySelector("[data-attribution-scope]");
    var contract = row.querySelector("[data-contract-id]");
    var method = row.querySelector("[data-attr-method]");
    var decision = row.querySelector("[data-inclusion]");
    var evidence = row.querySelector("[data-attr-evidence]");
    var contractCell = contract.parentElement;
    var methodCell = method.parentElement;
    var noContract = contractCell.querySelector("[data-no-contract]");
    var automaticMethod = methodCell.querySelector("[data-automatic-method]");
    var previousDecision = decision.value;

    if (!noContract) {
      noContract = document.createElement("span");
      noContract.dataset.noContract = "";
      noContract.className = "transaction-panel-meta";
      noContract.textContent = "-";
      noContract.hidden = true;
      contractCell.appendChild(noContract);
    }
    if (!automaticMethod) {
      automaticMethod = document.createElement("span");
      automaticMethod.dataset.automaticMethod = "";
      automaticMethod.className = "transaction-panel-meta";
      automaticMethod.textContent = "-";
      automaticMethod.hidden = true;
      methodCell.appendChild(automaticMethod);
    }

    scope.textContent = newcomerSupport ? "설계사" : "계약";
    contract.hidden = newcomerSupport;
    contract.disabled = newcomerSupport;
    noContract.hidden = !newcomerSupport;
    ["SETTLEMENT_SUPPORT_MONTHLY", "FIRST_CONTRACT_CARRY_FORWARD"].forEach(function (value) {
      var option = method.querySelector('option[value="' + value + '"]');
      if (option) option.disabled = isInsurerToGa();
      if (isInsurerToGa() && method.value === value) method.value = "DIRECT";
    });
    if (newcomerSupport) {
      contract.value = "";
      if (!method.querySelector('option[value="NEWCOMER_NON_CONTRACT"]')) {
        var newcomerOption = document.createElement("option");
        newcomerOption.value = "NEWCOMER_NON_CONTRACT";
        newcomerOption.textContent = "신인 비계약";
        method.appendChild(newcomerOption);
      }
      method.value = "NEWCOMER_NON_CONTRACT";
      method.disabled = true;
      method.hidden = true;
      automaticMethod.hidden = false;
      fillOptions(decision, [
        ["EXCLUDED:NEW_AGENT_SUPPORT", "제외 · 신인 지원"],
        ["REVIEW_REQUIRED", "검토필요"]
      ]);
      decision.value = previousDecision === "REVIEW_REQUIRED"
        ? "REVIEW_REQUIRED" : "EXCLUDED:NEW_AGENT_SUPPORT";
      evidence.placeholder = "신인 지원 증빙을 입력하세요";
      return;
    }

    var newcomerOption = method.querySelector('option[value="NEWCOMER_NON_CONTRACT"]');
    if (newcomerOption) newcomerOption.remove();
    method.hidden = false;
    automaticMethod.hidden = true;
    if (method.value === "NEWCOMER_NON_CONTRACT") method.value = "DIRECT";
    method.disabled = false;
    fillOptions(decision, inclusionOptions());
    var newAgentExclusion = decision.querySelector('option[value="EXCLUDED:NEW_AGENT_SUPPORT"]');
    if (newAgentExclusion) newAgentExclusion.disabled = isInsurerToGa();
    decision.value = Array.from(decision.options).some(function (option) {
      return option.value === previousDecision && !option.disabled;
    }) ? previousDecision : "INCLUDED";
    evidence.placeholder = "증빙 입력";
  }

  function handleRecipientChange() {
    clearAttributionsForRecipientChange();
    loadContracts(contractFilterAgentId());
  }

  // 2026-08-19 hjKang - 관리자수수료 수취인은 계약 목록을 좁히지 않는다.
  // 팀장·지사장·본부장은 그 계약을 모집한 사람이 아니라(운영정책서 제20조 패턴 GA-LIFE-A)
  // 모집설계사 기준으로 계약을 필터링하면 목록이 비어버려 관리자수수료를 등록할 수 없다.
  // 서버는 계약 조직 계층의 해당 직급 관리자인지 다시 검증하므로 여기서 넓혀도 안전하다.
  function contractFilterAgentId() {
    var selected = AGENTS.filter(function (agent) {
      return String(agent.id) === String(recipient.value);
    })[0];
    var isManager = selected && selected.rank && selected.rank !== "FC";
    return isManager ? "" : recipient.value;
  }

  function clearAttributionsForRecipientChange() {
    if (!attrBody.querySelector("[data-attr-row]")) return;
    clear(attrBody);
    attributionSequence = 0;
    renderEmptyAttributions();
    updateAttributionSummary();
    toast("수령 설계사가 변경되어 기존 귀속행을 비웠습니다. 계약을 다시 선택하세요.", "warning", 5000);
  }

  function save() {
    if (saving) return;
    var payload;
    clearFieldErrors();
    try { payload = buildPayload(); } catch (error) { showValidationError(error); return; }

    saving = true;
    saveButton.disabled = true;
    main.setAttribute("aria-busy", "true");
    setBusy(saveButton, true, "저장 중");
    var editing = Boolean(paymentId);
    var path = editing ? "/api/v1/transactions/" + encodeURIComponent(paymentId) : "/api/v1/transactions";
    apiClient.request(path, { method: editing ? "PUT" : "POST", body: payload })
      .then(function (envelope) {
        paymentId = envelope.data && envelope.data.commissionTransactionId;
        setTransactionStatus("작성중 · #" + paymentId, "status-badge-neutral");
        lockDraftInputs();
        precheckButton.disabled = false;
        confirmButton.disabled = true;
        toast("작성중(DRAFT)으로 저장했습니다. 저장만 했을 뿐 검증은 하지 않았습니다.", "success", 5000);
      })
      .catch(function (error) { showError(error, "수수료 지급 건을 저장하지 못했습니다."); })
      .finally(function () {
        saving = false;
        main.setAttribute("aria-busy", "false");
        setBusy(saveButton, false, "임시저장 (검증 없음)");
        if (!paymentId && canProcess) saveButton.disabled = false;
      });
  }

  function precheck() {
    if (!paymentId) return;
    precheckButton.disabled = true;
    confirmButton.disabled = true;
    setBusy(precheckButton, true, "검증 중");
    renderCapMessage("loading", "1,200% 한도를 계산하는 중입니다.");

    apiClient.request("/api/v1/transactions/" + encodeURIComponent(paymentId) + "/precheck", {
      method: "POST"
    }).then(function (envelope) {
      var result = envelope.data;
      lastPrecheckResult = result;
      renderPrecheck(result);
      confirmButton.disabled = !canProcess;
      confirmButton.dataset.confirmable = String(result.confirmable === true);
      toast(
        result.confirmable
          ? "사전검증을 통과했습니다. 이제 확정할 수 있습니다."
          : "사전검증에서 확정 차단 사유가 발견되었습니다.",
        result.confirmable ? "success" : "warning",
        5000
      );
    }).catch(function (error) {
      showError(error, "1,200% 사전검증을 수행하지 못했습니다.");
      renderCapMessage("error", format.errorText(error, "사전검증 중 오류가 발생했습니다. 다시 시도하세요."));
    }).finally(function () {
      setBusy(precheckButton, false, "사전검증");
      precheckButton.disabled = !canProcess;
    });
  }

  function renderPrecheck(result) {
    var capBody = document.getElementById("cap-body");
    clear(capBody);
    capBody.setAttribute("aria-busy", "false");
    (result.capPreview || []).forEach(function (preview) {
      capBody.appendChild(capPreviewCard(preview));
    });
    if (!result.capPreview || result.capPreview.length === 0) {
      renderCapMessage("empty", "표시할 1,200% 검증 결과가 없습니다.");
    }
    renderGates(result.blockers || [], result.confirmable);
  }

  function capPreviewCard(preview) {
    var card = document.createElement("div");
    card.className = "transaction-cap-card";

    var heading = document.createElement("div");
    heading.className = "transaction-cap-heading";
    heading.appendChild(textElement("span", (preview.paymentStageLabel || preview.paymentStage) + " · " + preview.contractNo));
    var badge = textElement("span", preview.resultStatusLabel + statusSuffix(preview.resultStatus));
    badge.className = "status-badge " + capStatusBadge(preview.resultStatus);
    heading.appendChild(badge);
    card.appendChild(heading);

    var usage = Math.max(0, number(preview.usagePct));
    var progress = document.createElement("progress");
    progress.className = "transaction-cap-progress " + capBarClass(preview.resultStatus);
    progress.max = 100;
    progress.value = Math.min(usage, 100);
    progress.setAttribute("aria-label", "한도 사용률 " + format.usageRate(preview.usagePct) + "%");
    card.appendChild(progress);

    var usageLine = document.createElement("div");
    usageLine.className = "transaction-cap-usage";
    usageLine.appendChild(textElement("span", "사용률 " + format.usageRate(preview.usagePct) + "%"));
    usageLine.appendChild(textElement("span", "한도 " + format.won(preview.limitAmount)));
    card.appendChild(usageLine);

    card.appendChild(amountLine("이번 건 전 산입 누계", preview.existingIncludedAmount));
    card.appendChild(amountLine("이번 건 산입액", preview.candidateAmount));
    card.appendChild(amountLine("합계", preview.includedAmount, true));
    card.appendChild(amountLine("잔여 한도", preview.remainingAmount, preview.remainingAmount < 0));
    return card;
  }

  function amountLine(label, value, emphasize) {
    var line = document.createElement("div");
    line.className = "transaction-cap-amount-line" + (emphasize ? " is-emphasized" : "");
    line.appendChild(textElement("span", label));
    var amountText = textElement("span", format.won(value));
    if (format.isNegative(value)) amountText.className = "is-negative-amount";
    line.appendChild(amountText);
    return line;
  }

  function renderGates(blockers, confirmable) {
    var gateList = document.getElementById("gate-list");
    clear(gateList);
    var matched = GATES.map(function () { return []; });
    (blockers || []).forEach(function (blocker) {
      var code = String((blocker && blocker.code) || "");
      var index = GATES.findIndex(function (gate) {
        return gate.codes.indexOf(code) !== -1;
      });
      /* 어느 게이트에도 없는 코드는 마지막 "전부 통과 → 확정" 이 받는다. */
      matched[index < 0 ? GATES.length - 1 : index].push(blocker);
    });

    /*
     * 제31조는 "반드시 다음 순서로 처리한다" 이고 precheck 도 앞 단계가 막히면 뒤 단계 계산에
     * 도달하지 못한다(예: 귀속행이 없으면 한도 계산 루프 자체를 돌지 않는다).
     * 그래서 앞 게이트가 차단이면 뒤 게이트는 "통과" 가 아니라 "미확인" 으로 둔다 —
     * 판정되지 않은 것을 통과로 보이게 하지 않는다.
     */
    var blockedBefore = false;
    /* 사전검증 전에는 confirmable 이 null 이다 — 아직 아무것도 판정되지 않았다. */
    var evaluated = confirmable != null;

    GATES.forEach(function (gate, index) {
      var failures = matched[index];
      var state;
      if (!evaluated) state = "todo";
      else if (confirmable === true) state = "pass";
      else if (failures.length) state = "fail";
      else state = blockedBefore ? "todo" : "pass";
      if (failures.length) blockedBefore = true;
      var row = document.createElement("div");
      row.className = "transaction-gate-step transaction-gate-" + state;
      var icon = textElement("span", state === "pass" ? "check" : state === "fail" ? "close" : "more_horiz");
      icon.className = "material-symbols-rounded transaction-gate-icon";
      icon.setAttribute("aria-hidden", "true");
      row.appendChild(icon);
      var copy = document.createElement("div");
      var label = textElement("div", (index + 1) + ". " + gate.label + " · " + (state === "pass" ? "통과" : state === "fail" ? "차단" : "미확인"));
      label.className = "transaction-gate-label";
      copy.appendChild(label);
      var detail = textElement("div", failures.length
        ? failures.map(function (blocker) { return blocker.message || blocker.code; }).join(" / ")
        : state === "pass" ? "서버 사전검증 결과 통과했습니다."
          : evaluated ? "앞 단계가 막혀 아직 판정하지 않았습니다."
            : "사전검증 후 서버 판정이 표시됩니다.");
      detail.className = "transaction-gate-detail";
      copy.appendChild(detail);
      row.appendChild(copy);
      gateList.appendChild(row);
    });
  }

  function openConfirmation() {
    if (!paymentId || confirmButton.disabled || !lastPrecheckResult) return;
    prepareConfirmation(lastPrecheckResult);
    modal.open("transaction-confirm");
  }

  /** 서버에 보내야 판정이 기록되는 차단인가 — RECORDABLE_BLOCKER_CODES 주석 참고. */
  function hasRecordableBlocker(result) {
    return (result.blockers || []).some(function (blocker) {
      return RECORDABLE_BLOCKER_CODES.indexOf(blocker.code) >= 0;
    });
  }

  /** 차단된 계약 — 판정이 걸린 귀속 계약을 계산근거·예외함 링크의 검색조건으로 쓴다. */
  function blockedContractNo(result) {
    var blocked = (result.capPreview || []).filter(function (preview) {
      return preview.resultStatus === "VIOLATION" || preview.resultStatus === "REVIEW_REQUIRED";
    });
    return blocked.length ? blocked[0].contractNo : null;
  }

  function prepareConfirmation(result) {
    clear(confirmSummary);
    confirmSummary.appendChild(summaryLine("업무키", bizKey.value || "-"));
    confirmSummary.appendChild(summaryLine("지급액", format.won(amount.value)));
    confirmSummary.appendChild(summaryLine("흐름", cashflow.options[cashflow.selectedIndex].textContent));
    confirmSummary.appendChild(summaryLine("서버 판정", result.confirmable ? "확정 가능" : "확정 차단"));

    clear(confirmBlockers);
    var blockers = result.blockers || [];
    blockers.forEach(function (blocker) {
      confirmBlockers.appendChild(textElement("p", (blocker.code ? blocker.code + " · " : "") + (blocker.message || "확정 차단 사유를 확인하세요.")));
    });
    confirmBlockers.hidden = blockers.length === 0;

    /*
     * 확정 가능하면 그대로 확정한다. 확정은 불가하지만 서버가 기록해야 하는 규제 판정이면
     * "확정 시도" 로 보낸다 — 확정되지 않고 DRAFT 로 남으며, 서버가 위반을 기록하고 예외를 만든다.
     * 규제 위반을 승인으로 넘기는 버튼이 아니다(화면정의서 TRAN-W02 "관리자 권한으로 강제 통과 —
     * 그런 버튼을 만들지 않습니다"). 입력 오류는 화면에서 고쳐야 하므로 여전히 숨긴다.
     */
    var recordable = result.confirmable !== true && hasRecordableBlocker(result);
    confirmSubmitButton.hidden = result.confirmable !== true && !recordable;
    confirmSubmitButton.disabled = confirmSubmitButton.hidden;
    setButtonLabel(confirmSubmitButton, recordable ? "확정 시도 · 예외 등록" : "확정 실행");

    clear(confirmFollowUp);
    if (recordable) {
      var notice = textElement("p", "확정되지 않습니다. 서버가 이 판정을 기록하고 예외함에 등록한 뒤 확정을 거부합니다. 지급 건은 작성중으로 남습니다.");
      notice.className = "transaction-confirm-notice";
      confirmFollowUp.appendChild(notice);
    }
    confirmFollowUp.hidden = !confirmFollowUp.childElementCount;
  }

  /**
   * 확정이 거부되어 판정이 기록된 뒤의 후속 동선 — 예외함(EXCP-W01).
   * 확정 실패 응답에는 cap_check·exception_case ID 가 없으므로(응답 계약상 문구 치환값만 온다)
   * EXCP-W01 이 이미 지원하는 검색조건(type·contractNo)으로 그 건까지 좁혀 보낸다.
   *
   * CAP-W01(계산근거) 링크는 두지 않는다 — 확정 거절된 DRAFT 후보의 cap_check 는
   * CapCheckMapper.latestScopedCapChecks 가 candidate_transaction_id 조건으로 목록에서
   * 제외하므로(계약의 현재 판정을 덮어쓰지 않게 하려는 의도) 링크를 걸면 빈 목록으로 보내게 된다.
   * 같은 행을 DashboardMapper 는 세고 CAP-W01 은 세지 않아 위반 KPI 와 CAP-W01 건수가
   * 어긋나는 문제(화면정의서 :402 "클릭하면 CAP-W01 위반 필터")가 별도로 있으며,
   * 그 판단이 정리된 뒤에 이 링크를 추가한다.
   */
  function renderFollowUpLinks(contractNo, reviewRequired) {
    if (!contractNo) return;
    var excParams = new URLSearchParams({
      type: reviewRequired ? "CAP_REVIEW_REQUIRED" : "CAP_VIOLATION",
      contractNo: contractNo
    });

    var actions = document.createElement("div");
    actions.className = "transaction-confirm-followup-actions";
    actions.appendChild(followUpLink("예외함에서 처리하기", "/exceptions?" + excParams.toString()));
    confirmFollowUp.appendChild(actions);
    confirmFollowUp.hidden = false;
  }

  function followUpLink(label, href) {
    var link = document.createElement("a");
    link.className = "button button-secondary";
    link.href = href;
    link.textContent = label;
    return link;
  }

  /*
   * 계산근거·예외함 링크는 ID 대신 검색조건으로 연다(#257).
   * IF-API-24 미리보기는 아무것도 저장하지 않아 capCheckId 가 항상 null 이고 확정 실패 응답에도
   * exceptionCaseId 가 없다 — 그래서 ID 링크는 여전히 만들 수 없다. 대신 CAP-W01 은
   * contractNo·status·month 를, EXCP-W01 은 type·contractNo 를 이미 검색조건으로 받으므로
   * (cap-list.js queryFromLocation · ExceptionCaseSearchDTO) 그 건까지 좁혀 보낸다.
   * 없는 API 를 있는 척하는 게 아니라 있는 API 로 여는 것이라 #283 원칙과 어긋나지 않는다.
   * 링크는 서버가 실제로 판정을 기록한 뒤(확정 거부 응답)에만 노출한다.
   */

  function confirmPayment() {
    if (!paymentId || !lastPrecheckResult) return;
    if (lastPrecheckResult.confirmable !== true && !hasRecordableBlocker(lastPrecheckResult)) return;
    var attemptedContractNo = blockedContractNo(lastPrecheckResult);
    confirmSubmitButton.disabled = true;
    confirmButton.disabled = true;
    main.setAttribute("aria-busy", "true");
    setBusy(confirmSubmitButton, true, "확정 중");
    apiClient.request("/api/v1/transactions/" + encodeURIComponent(paymentId) + "/confirm", {
      method: "POST",
      idempotencyKey: "TRAN-CONFIRM-" + paymentId
    }).then(function (envelope) {
      setTransactionStatus("확정 · #" + paymentId, "status-badge-success");
      toast("수수료 지급 건을 확정했습니다.", "success", 3000);
      modal.close("transaction-confirm");
      window.setTimeout(function () { window.location.assign("/transactions"); }, 900);
    }).catch(function (error) {
      showError(error, "수수료 지급 건을 확정하지 못했습니다.");
      var failedResult = {
        confirmable: false,
        blockers: [{ code: error.code, message: format.errorText(error, "수수료 지급 건을 확정하지 못했습니다. 입력값과 예외함을 확인하세요.") }],
        capCheckId: error.params && error.params.capCheckId,
        exceptionCaseId: error.params && error.params.exceptionCaseId,
        capPreview: []
      };
      prepareConfirmation(failedResult);
      /*
       * 서버가 판정을 기록하고 거부한 경우에만 후속 동선을 연다 — 이 시점에는
       * cap_check 과 exception_case 가 실제로 존재하므로 빈 화면으로 보내지 않는다.
       * 재시도는 의미가 없다(같은 판정 + 미해결 위반 게이트 FGC-CAP-003 에 걸린다).
       */
      if (RECORDABLE_BLOCKER_CODES.indexOf(error.code) >= 0) {
        renderFollowUpLinks(attemptedContractNo, error.code === "FGC-CAP-002");
        confirmSubmitButton.hidden = true;
        confirmSubmitButton.disabled = true;
      }
      precheckButton.disabled = !canProcess;
    }).finally(function () {
      main.setAttribute("aria-busy", "false");
      setBusy(confirmSubmitButton, false, "확정 실행");
    });
  }

  function lockDraftInputs() {
    main.querySelectorAll("input, select").forEach(function (control) { control.disabled = true; });
    attrBody.querySelectorAll("button").forEach(function (button) { button.disabled = true; });
    addButton.disabled = true;
    saveButton.disabled = true;
    lockNotice.hidden = false;
  }

  function renderCapMessage(kind, message) {
    var capBody = document.getElementById("cap-body");
    clear(capBody);
    var text = textElement("p", message);
    text.className = "transaction-panel-placeholder transaction-cap-state transaction-cap-state-" + kind;
    text.setAttribute("role", kind === "error" ? "alert" : "status");
    capBody.appendChild(text);
    capBody.setAttribute("aria-busy", String(kind === "loading"));
  }

  function statusSuffix(status) { return status === "VIOLATION" ? "(100% 초과)" : ""; }
  function capStatusBadge(status) { return { NORMAL: "status-badge-success", WARNING: "status-badge-warning", VIOLATION: "status-badge-error", REVIEW_REQUIRED: "status-badge-review" }[status] || "status-badge-neutral"; }
  function capBarClass(status) { return { NORMAL: "is-normal", WARNING: "is-warning", VIOLATION: "is-violation" }[status] || "is-review"; }
  function textElement(tag, text) { var element = document.createElement(tag); element.textContent = text; return element; }
  function toast(message, tone, duration) { if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, tone, duration); }

  function buildPayload() {
    requireValue(sourceType, "원천유형"); requireValue(bizKey, "업무키");
    requireValue(settlementMonth, "정산월");
    if (!isInsurerToGa()) requireValue(recipient, "수령 설계사");
    requireValue(item, "수수료 항목"); requireValue(amount, "금액");
    var attributions = Array.from(attrBody.querySelectorAll("[data-attr-row]")).map(attributionPayload);
    if (attributions.length === 0) throw validationError("귀속행을 하나 이상 추가하세요.", null, "err-attributions");
    return {
      sourceType: sourceType.value,
      sourceBusinessKey: bizKey.value.trim(),
      contractId: attributions[0].contractId,
      agentId: isInsurerToGa() ? null : Number(recipient.value),
      commissionItemId: Number(item.value),
      amount: Number(amount.value),
      settlementMonth: monthFirstDay(),
      cashflowType: cashflow.value,
      scheduledPaymentDate: monthFirstDay(),
      paymentStage: stage.value,
      allocationPolicyVersion: null,
      attributions: attributions,
      evidenceRef: blankToNull(evidence.value),
      note: null
    };
  }

  function attributionPayload(row) {
    var contract = row.querySelector("[data-contract-id]");
    var date = row.querySelector("[data-attr-date]");
    var attrAmount = row.querySelector("[data-attr-amount]");
    var newcomerSupport = isNewcomerSupportSelected();
    if (!newcomerSupport) requireValue(contract, "귀속 계약");
    requireValue(date, "귀속일"); requireValue(attrAmount, "귀속금액");
    var decision = parseInclusionDecision(row.querySelector("[data-inclusion]").value);
    var rowEvidence = blankToNull(row.querySelector("[data-attr-evidence]").value);
    var attributionMethod = row.querySelector("[data-attr-method]").value;
    var allocationBasis = blankToNull(row.querySelector("[data-allocation-basis]").value);
    if ((decision.inclusionStatus === "EXCLUDED" || newcomerSupport) && !rowEvidence) {
      throw validationError("제외 귀속행은 제외유형과 증빙을 입력해야 합니다.", row.querySelector("[data-attr-evidence]"), "err-attributions");
    }
    if (attributionMethod === "APPROVED_ALLOCATION" && !allocationBasis) {
      throw validationError("승인 배부에는 배부정책을 입력해야 합니다.", row.querySelector("[data-allocation-basis]"), "err-attributions");
    }
    return {
      contractId: newcomerSupport ? null : Number(contract.value), attributionDate: date.value,
      amount: Number(attrAmount.value), inclusionDecisionStatus: decision.inclusionStatus,
      exclusionType: decision.exclusionType, inclusionDecisionReason: decisionReason(decision),
      allocationBasis: allocationBasis,
      evidenceRef: rowEvidence, attributionMethod: attributionMethod
    };
  }

  function updateAttributionSummary() {
    var sum = Array.from(attrBody.querySelectorAll("[data-attr-amount]"))
      .reduce(function (total, input) { return total + number(input.value); }, 0);
    var difference = number(amount.value) - sum;
    document.getElementById("attr-sum").textContent = format.won(sum);
    var diff = document.getElementById("attr-diff");
    diff.textContent = difference === 0 ? "일치" : "불일치 · 차액 " + format.won(difference);
    diff.className = "status-badge " + (difference === 0 ? "status-badge-success" : "status-badge-error");
  }

  function suggestedAmount() {
    return attrBody.querySelector("[data-attr-row]") ? 0 : number(amount.value);
  }
  function selectedAgentLabel() {
    var option = recipient.options[recipient.selectedIndex];
    return option && option.value ? option.textContent : "—";
  }
  function appendAgentCell(row) {
    var cell = document.createElement("td");
    cell.dataset.attrAgent = "";
    cell.textContent = selectedAgentLabel();
    row.appendChild(cell);
  }
  function updateAttributionAgentLabels() {
    attrBody.querySelectorAll("[data-attr-agent]").forEach(function (cell) {
      cell.textContent = selectedAgentLabel();
    });
  }

  // 계약일 이전 귀속은 1,200% 산입 기간에 포함되지 않으므로, 계약 선택 시에만
  // 기본 귀속일을 계약일로 보정한다. 사용자가 이미 계약일 이후 날짜를 입력한 경우는 유지한다.
  function syncAttributionDateWithContract(row) {
    var contractSelect = row.querySelector("[data-contract-id]");
    var dateInput = row.querySelector("[data-attr-date]");
    var selectedId = contractSelect.value;
    var selectedContract = contracts.find(function (contract) {
      return String(contract.contractId) === selectedId;
    });
    if (!selectedContract || !selectedContract.contractDate) {
      dateInput.removeAttribute("min");
      return;
    }

    var contractDate = String(selectedContract.contractDate).slice(0, 10);
    dateInput.min = contractDate;
    if (!dateInput.value || dateInput.value < contractDate) {
      dateInput.value = contractDate;
      row.querySelector("[data-attr-month]").value = contractDate.slice(0, 7) + "-01";
    }
  }
  function monthFirstDay() { return settlementMonth.value ? settlementMonth.value + "-01" : ""; }

  function initializeSettlementMonth() {
    if (settlementMonth.value) return;
    var globalMonthSelector = document.querySelector("[data-month-selector]");
    var globalMonth = globalMonthSelector && globalMonthSelector.dataset.value;
    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(globalMonth || "")) {
      settlementMonth.value = globalMonth;
      return;
    }
    settlementMonth.value = format.today().slice(0, 7);
  }
  function decisionReason(decision) {
    var labels = {
      VOICE_RECORDING: "녹취 관련 비용",
      BROADCAST: "방송 관련 비용",
      NEW_AGENT_SUPPORT: "신인활동지원비",
      COMPLIANCE_3PCT: "준법경영비"
    };
    if (decision.inclusionStatus === "EXCLUDED") return "한도 제외: " + labels[decision.exclusionType];
    return { INCLUDED: "화면에서 산입으로 지정", REVIEW_REQUIRED: "화면에서 검토필요로 지정" }[decision.inclusionStatus];
  }

  function generateBusinessKey() {
    var now = new Date();
    function two(value) { return String(value).padStart(2, "0"); }
    return "GA-MANUAL-" + now.getFullYear()
      + two(now.getMonth() + 1)
      + two(now.getDate()) + "-"
      + two(now.getHours())
      + two(now.getMinutes())
      + two(now.getSeconds()) + "-"
      + String(now.getMilliseconds()).padStart(3, "0");
  }

  function summaryLine(label, value) {
    var line = document.createElement("div");
    line.className = "transaction-cap-amount-line";
    line.appendChild(textElement("span", label));
    line.appendChild(textElement("strong", value));
    return line;
  }

  function setTransactionStatus(label, tone) {
    var status = document.getElementById("tx-status");
    status.textContent = label;
    status.className = "status-badge " + tone;
  }

  function setBusy(button, busy, label) {
    button.classList.toggle("is-loading", busy);
    button.setAttribute("aria-busy", String(busy));
    setButtonLabel(button, label);
  }

  function setButtonLabel(button, label) {
    var slot = button.querySelector("[data-button-label]");
    if (slot) slot.textContent = label;
  }

  function validationError(message, control, slotId) {
    var error = new Error(message);
    error.control = control;
    error.slotId = slotId;
    return error;
  }

  function clearFieldErrors() {
    main.querySelectorAll("[id^='err-']").forEach(function (slot) {
      slot.hidden = true;
      slot.textContent = "";
    });
    main.querySelectorAll("[aria-invalid='true']").forEach(function (control) {
      control.removeAttribute("aria-invalid");
      var field = control.closest(".field");
      if (field) field.classList.remove("is-error");
    });
  }

  function showValidationError(error) {
    var slot = document.getElementById(error.slotId || "err-attributions");
    if (slot) {
      slot.textContent = error.message;
      slot.hidden = false;
    }
    if (error.control) {
      error.control.setAttribute("aria-invalid", "true");
      var field = error.control.closest(".field");
      if (field) field.classList.add("is-error");
      error.control.focus();
    } else if (slot) {
      slot.scrollIntoView({ block: "nearest" });
    }
  }

  function showServerFieldError(fieldName, message) {
    var controls = {
      sourceType: sourceType,
      sourceBusinessKey: bizKey,
      settlementMonth: settlementMonth,
      agentId: recipient,
      commissionItemId: item,
      amount: amount,
      evidenceRef: evidence
    };
    var control = controls[fieldName];
    if (!control) return;
    showValidationError(validationError(message, control, "err-" + control.id));
  }

  function markReferenceError(error, fallback) {
    initializationError = true;
    formStatus.hidden = false;
    formStatus.classList.add("is-error");
    formStatus.textContent = format.errorText(error, fallback);
  }

  function fillOptions(select, entries, placeholder) {
    clear(select);
    if (placeholder) addOption(select, "", placeholder);
    entries.forEach(function (entry) { addOption(select, entry[0], entry[1]); });
  }
  function addOption(select, value, text) { var option = document.createElement("option"); option.value = value; option.textContent = text; select.appendChild(option); }
  function input(type, value, marker) { var control = document.createElement("input"); control.type = type; control.value = value; if (marker) control.setAttribute(marker, ""); if (type === "number") { control.min = "0"; control.step = "1"; } return control; }
  function readonlyInput(value) { var control = input("text", value, "data-attr-month"); control.readOnly = true; return control; }
  function controlCell(control) { var cell = document.createElement("td"); cell.appendChild(control); return cell; }
  function appendTextCell(row, value) { var cell = document.createElement("td"); cell.textContent = value; row.appendChild(cell); return cell; }
  function renumberRows() { attrBody.querySelectorAll("[data-attr-row]").forEach(function (row, index) { row.firstElementChild.textContent = index + 1; }); }
  function renderEmptyAttributions() { var row = document.createElement("tr"); var cell = document.createElement("td"); var empty = document.createElement("div"); cell.colSpan = 12; empty.className = "empty-state"; empty.textContent = "귀속행을 추가하세요."; cell.appendChild(empty); row.appendChild(cell); attrBody.appendChild(row); }
  function requireValue(control, label) {
    if (!control.value || (control.type === "number" && number(control.value) < 0)) {
      throw validationError("필수값을 확인하세요: " + label, control, control.closest("[data-attr-row]") ? "err-attributions" : "err-" + control.id);
    }
  }
  function showError(error, fallback) {
    var message = format.errorText(error, fallback);
    if (error && error.field) showServerFieldError(error.field, message);
    toast(message, "error", 8000);
  }
  function blankToNull(value) { var trimmed = String(value || "").trim(); return trimmed || null; }
  function number(value) { var parsed = Number(value); return Number.isFinite(parsed) ? parsed : 0; }
  function clear(element) { while (element.firstChild) element.removeChild(element.firstChild); }
})();
