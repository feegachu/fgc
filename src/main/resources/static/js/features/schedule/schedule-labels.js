/*
 * SCHE-W01·W02 공용 라벨 사전 · 상태 톤 · 표 셀 유틸 (#285)
 *
 * 왜 파일을 나눴나
 *   schedule-list.js:63-88 과 schedule-detail.js:15-35 가 같은 라벨 4세트를 각자 들고 있었다.
 *   톤 매핑(STATUS_TONES)은 W01 에만 있어서 W02 는 상태를 plain 텍스트로 찍었다.
 *   화면정의서 4장 규칙 4 는 상태를 "색 + 글자"로 쓰라고 하는데 한쪽만 지키던 상태다.
 *   한쪽만 고치면 즉시 라벨이 갈리므로 사전과 톤을 여기 한 벌만 둔다.
 *
 * 이중 모드
 *   src/test/js/schedule-list.test.cjs 가 schedule-list.js 를 require 하고 responseLabel 을
 *   계약으로 쓴다. 그 계약을 유지하면서 구현을 한 벌로 두려고 이 파일도 module.exports 를 연다.
 *   브라우저에서는 window.FgcUi.scheduleLabels 로 읽는다.
 *
 * 로드 순서
 *   layout/default.html 이 이 파일을 schedule-list.js · schedule-detail.js 보다 먼저 defer 로
 *   건다. 순서가 바뀌면 두 화면이 라벨 없이 코드값을 그대로 노출한다.
 */
(function () {
  "use strict";

  var EMPTY = "-";

  var PAYMENT_STAGE = {
    INSURER_TO_GA: "원수사→GA",
    GA_TO_FC: "GA→설계사"
  };

  var SCHEDULE_REGIME = {
    CURRENT: "현행",
    FOUR_YEAR_2027: "4년 분급(2027)",
    SEVEN_YEAR_2029: "7년 분급(2029)",
    TM_SPECIAL: "TM 특례"
  };

  var SCHEDULE_PURPOSE = {
    OPERATIONAL: "운영",
    COMPARISON: "비교",
    SIMULATION: "시뮬레이션"
  };

  /* 서버 enum 과 1:1 — common/code/ScheduleHeaderStatus.java:12-18 */
  var SCHEDULE_STATUS = {
    PLANNED: "예정",
    CONFIRMED: "확정",
    MATCHED: "대사일치",
    ADJUSTED: "조정",
    HOLD: "보류",
    CANCELLED: "취소",
    RESTARTED: "재개"
  };

  /* 회차(line) 상태 — common/code/ScheduleLineStatus.java:12-18 과 같은 코드계 */
  var LINE_STATUS = SCHEDULE_STATUS;

  var CALCULATION_TYPE = {
    RATE: "요율",
    FIXED: "정액"
  };

  /*
   * 상태 배지 톤 — 화면정의서 4장 규칙 4 (:212)
   *   "정상=초록, 주의=주황, 위반·차단=빨강, 검토필요=회색, 진행중=파랑"
   * 판단 근거는 서버 enum 의 주석에 적힌 뜻을 그대로 따랐다
   * (common/code/ScheduleLineStatus.java:12-18 이 각 상태의 업무 의미를 적어 둔 유일한 출처다).
   *
   *   PLANNED   생성 직후            → 진행중  info
   *   CONFIRMED 담당자가 확정        → 아래 주석 참조. review 유지 + 자물쇠
   *   MATCHED   실제 지급과 일치     → 정상    success
   *   ADJUSTED  실제 지급과 다름     → 주의    warning
   *   HOLD      계약 미납            → 검토필요 review   (기존 risk 에서 변경)
   *   CANCELLED 계약 해지            → 종료    neutral  (기존 error 에서 변경)
   *   RESTARTED 계약 부활            → 진행중  info
   *
   * CONFIRMED 를 success 로 바꾸지 않은 이유
   *   VRUN-W01 이 같은 뜻의 확정(FINALIZED)을 vrun/list.html:111-118 에서 review + lock 으로
   *   표시한다. 규칙 8 (:216) 이 "확정 후 잠금"으로 두 화면을 같은 개념으로 묶으므로
   *   여기만 초록으로 바꾸면 확정 색이 화면마다 갈린다. 대신 규칙 8 을 지키도록
   *   자물쇠 아이콘을 배지 안에 넣어 색이 아니라 아이콘·글자로 구분되게 했다.
   *
   * HOLD·CANCELLED 는 바꿔도 안전하다
   *   저장소 전체에서 이 두 코드에 배지 톤을 매기는 곳이 schedule-list.js 하나뿐이라
   *   (features/*.js 전수 확인) 다른 화면과 어긋날 여지가 없다.
   */
  var STATUS_TONES = {
    PLANNED: "status-badge-info",
    CONFIRMED: "status-badge-review",
    MATCHED: "status-badge-success",
    ADJUSTED: "status-badge-warning",
    HOLD: "status-badge-review",
    CANCELLED: "status-badge-neutral",
    RESTARTED: "status-badge-info"
  };

  /* 확정은 규칙 8 의 잠금 상태다 — 색에 더해 자물쇠로도 알린다 (VRUN-W01 과 같은 표현). */
  var LOCKED_STATUSES = ["CONFIRMED"];

  /*
   * 적용 체계·용도는 상태가 아니라 분류값이다.
   * 규칙 4 의 5색은 상태 전용이므로 분류값에 info/success 를 쓰면 색의 뜻이 흐려진다.
   * 배지 형태는 유지하되 톤은 neutral 하나로 통일한다.
   */
  var CLASSIFICATION_TONE = "status-badge-neutral";

  /*
   * REG-19 근거 — 적용 체계 값 옆 근거 툴팁에 쓴다 (규칙 5 :213).
   * 원래 SCHE-W01 의 schedule-guidance 배너와 SCHE-W02 의 fgc-banner--info 에 각각
   * 같은 문구가 중복으로 있었다. 배너는 화면 설명문이라 지우되 근거 자체는 잃지 않는다.
   */
  var REGIME_EVIDENCE = {
    title: "REG-19 · 적용 체계 판정",
    body: "적용 체계는 계약 체결연도만으로 정하지 않습니다. "
      + "상품 판매개시일 · 기초서류 버전 · 판매채널을 함께 보고 정합니다."
  };

  function responseLabel(serverLabel, labels, code) {
    if (typeof serverLabel === "string" && serverLabel.trim()) return serverLabel;
    return labels[code] || code || EMPTY;
  }

  function statusTone(code) {
    return STATUS_TONES[code] || CLASSIFICATION_TONE;
  }

  function isLocked(code) {
    return LOCKED_STATUSES.indexOf(code) >= 0;
  }

  var api = {
    EMPTY: EMPTY,
    PAYMENT_STAGE: PAYMENT_STAGE,
    SCHEDULE_REGIME: SCHEDULE_REGIME,
    SCHEDULE_PURPOSE: SCHEDULE_PURPOSE,
    SCHEDULE_STATUS: SCHEDULE_STATUS,
    LINE_STATUS: LINE_STATUS,
    CALCULATION_TYPE: CALCULATION_TYPE,
    STATUS_TONES: STATUS_TONES,
    CLASSIFICATION_TONE: CLASSIFICATION_TONE,
    REGIME_EVIDENCE: REGIME_EVIDENCE,
    responseLabel: responseLabel,
    statusTone: statusTone,
    isLocked: isLocked
  };

  if (typeof module === "object" && module.exports) {
    module.exports = api;
    return;
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.scheduleLabels = api;
})();
