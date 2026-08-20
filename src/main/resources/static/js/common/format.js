/*
 * 공통 표시 형식 유틸 — FGC-SIR-008 (인터페이스정의서 2-4절, 화면정의서 4장 규칙 1~3)
 *
 * 규칙 2 가 "화면에서 반올림하지 않는다" 이므로 여기서는 값을 반올림하지 않는다.
 * 서버가 금액은 원 단위 정수로, 요율·사용률은 문자열로 이미 확정해서 보낸다. 화면은 끊어 읽기
 * 좋게 묶고 정해진 자리에서 자를 뿐이다. Number 로 바꾸면 1,200% 판정에 쓰는 소수 6자리가
 * 흔들리므로 문자열 그대로 다룬다.
 *
 * 요율과 사용률은 자리수가 다르다 — rate() 4자리, usageRate() 6자리. 섞어 쓰면 안 된다.
 *
 * 시각은 Asia/Seoul 로 고정한다. new Date().getMonth() 처럼 브라우저 로컬 시각을 쓰면
 * 월말·월초에 정산월이 한 달 어긋난다.
 */
(function () {
  "use strict";

  var EMPTY = "-";
  var SEOUL = "Asia/Seoul";

  function isBlank(value) {
    return value == null || value === "" || (typeof value === "string" && value.trim() === "");
  }

  /* 천 단위 콤마. 건수·회차처럼 금액이 아닌 수치에 쓴다. */
  function int(value) {
    if (isBlank(value)) return EMPTY;
    var parsed = Number(value);
    if (!isFinite(parsed)) return EMPTY;
    return parsed.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
  }

  /*
   * 금액. 음수는 괄호로 감싼다 (화면정의서 4장 규칙 1).
   * 빨강은 색만으로 뜻을 전달하지 않도록 괄호와 함께 쓰는 보조 수단이므로,
   * 호출부가 isNegative() 로 판별해 .is-negative-amount 클래스를 붙인다.
   *
   * 괄호는 숫자만 감싸고 "원" 은 밖에 둔다 — (1,517,944)원.
   * 산출물의 리터럴 예시를 그대로 따른 것이다.
   *   화면정의서 4장 규칙 1 (:209) — "음수는 빨강 + 괄호. 예: (1,517,944)"
   *   인터페이스정의서 2-4 (:176) — "1,517,944 / 음수 (1,517,944) 빨강"
   *
   * features/cap/cap-list.js:67 은 (1,200,000원) 으로 괄호 안에 원을 넣고 있다.
   * CAP-W01 전환(#289) 때 이 함수로 옮기면서 함께 맞춰야 한다 — 그 화면만 표기가 달라진다.
   */
  function won(value) {
    if (isBlank(value)) return EMPTY;
    var parsed = Number(value);
    if (!isFinite(parsed)) return EMPTY;
    var digits = Math.abs(parsed).toLocaleString("ko-KR", { maximumFractionDigits: 0 });
    return parsed < 0 ? "(" + digits + ")원" : digits + "원";
  }

  function isNegative(value) {
    if (isBlank(value)) return false;
    var parsed = Number(value);
    return isFinite(parsed) && parsed < 0;
  }

  /*
   * 소수 자리를 "자르기만" 한다 — 반올림하지 않는다 (화면정의서 4장 규칙 2·3).
   * 서버가 문자열("104.166667")로 주는 이유가 부동소수점 오차를 없애기 위해서이므로
   * Number 로 바꾸지 않고 문자열을 다룬다.
   */
  function truncateDecimal(value, digits) {
    if (isBlank(value)) return EMPTY;

    var normalized = String(value).trim();
    if (!/^[+-]?\d+(?:\.\d+)?$/.test(normalized)) return EMPTY;

    var sign = "";
    if (normalized.charAt(0) === "-" || normalized.charAt(0) === "+") {
      sign = normalized.charAt(0) === "-" ? "-" : "";
      normalized = normalized.slice(1);
    }

    var parts = normalized.split(".");
    var integerPart = parts[0].replace(/^0+(?=\d)/, "");
    var fractionPart = ((parts[1] || "") + new Array(digits + 1).join("0")).slice(0, digits);
    return sign + integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + fractionPart;
  }

  /*
   * 요율 — 소수 넷째 자리. 예: 650.0000%
   * 인터페이스정의서 2-4 "요율 소수점 4자리".
   */
  function rate(value) {
    return truncateDecimal(value, 4);
  }

  /*
   * 사용률 — 소수 여섯째 자리. 예: 104.166667%
   *
   * 요율과 자리수가 다르다. 1,200% 한도 판정이 소수 6자리까지 쓰기 때문에 4자리로 자르면
   * 화면 숫자와 판정 근거가 어긋난다.
   *   인터페이스정의서 2-4 (:177) — "요율 소수점 4자리, 사용률 6자리"
   *   화면정의서 CAP-W01 (:934) — "사용률 | 게이지 막대 + 숫자 (소수점 6자리)"
   *   화면정의서 CAP-W02 (:1005) — "사용률 74.166667%"
   */
  function usageRate(value) {
    return truncateDecimal(value, 6);
  }

  function seoulParts(value) {
    var date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return null;

    var parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: SEOUL,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    }).formatToParts(date);

    var found = {};
    parts.forEach(function (part) {
      found[part.type] = part.value;
    });
    /* Intl 은 자정을 24 로 주는 구현이 있다 (hour12:false + en-CA). */
    if (found.hour === "24") found.hour = "00";
    return found;
  }

  /*
   * YYYY-MM-DD.
   * 날짜만 있는 값(LocalDate)은 시간대가 없으므로 그대로 통과시킨다. 시각이 붙어 있으면
   * 앞 10자를 자르지 말고 Asia/Seoul 로 환산한다 — UTC 로 온 값은 자정 근처에서 날짜가 하루 어긋난다.
   */
  function date(value) {
    if (isBlank(value)) return EMPTY;

    var text = String(value).trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;

    var parts = seoulParts(text);
    return parts ? parts.year + "-" + parts.month + "-" + parts.day : EMPTY;
  }

  /* YYYY-MM-DD HH:mm, Asia/Seoul 고정. */
  function dateTime(value) {
    if (isBlank(value)) return EMPTY;

    var parts = seoulParts(String(value).trim());
    if (!parts) return EMPTY;
    return parts.year + "-" + parts.month + "-" + parts.day + " " + parts.hour + ":" + parts.minute;
  }

  /* YYYY-MM. 정산월·귀속월은 서버가 항상 그 달 1일로 준다. */
  function month(value) {
    var normalized = date(value);
    return normalized === EMPTY ? EMPTY : normalized.slice(0, 7);
  }

  /* Asia/Seoul 기준 오늘. 계약일 max 같은 입력 제한에 쓴다. */
  function today() {
    var parts = seoulParts(new Date());
    return parts.year + "-" + parts.month + "-" + parts.day;
  }

  /*
   * 오류 Toast 문구 — 메시지 뒤에 오류코드와 요청 ID 를 괄호로 덧붙인다.
   *
   * 근거와 한계
   *   문구 본문은 서버 messages_ko.properties 값을 그대로 쓴다. 화면에서 문구를 새로 만들지
   *   않는다는 규칙은 인터페이스정의서 3-3(:262-270) 규칙 4 에 있다. 그 문구 자체가 화면정의서
   *   4장 규칙 10(:218) 의 "무엇이 · 왜 · 어떻게" 3요소를 이미 담고 있으므로 여기서 덧붙이지 않는다.
   *
   *   반면 "오류코드와 추적ID 를 화면에 함께 제공한다" 는 요구는 비기능 요구사항 FGC-SIR-007 의
   *   인수조건에서 왔는데, SIR 시트가 저장소에 체크인되어 있지 않아 코드에서 대조할 수 없다
   *   (인터페이스정의서 3-3 은 백엔드 처리 아키텍처 5개 규칙만 정한다). 근거 미확인 상태다.
   *
   * 기존 관행과의 차이
   *   features/contract/contract-form.js:114-118 은 요청 ID 를 별도 DOM 에 "요청 ID: xxx" 로만
   *   두고 오류코드는 노출하지 않는다. Toast 는 한 줄이라 별도 슬롯을 둘 수 없어 괄호로 묶었다.
   *   화면마다 형식이 갈리지 않도록 오류 표시는 이 함수 하나만 쓴다.
   */
  function errorText(error, fallbackMessage) {
    var message = (error && error.message) || fallbackMessage || "요청을 처리하지 못했습니다.";
    var trace = [];
    if (error && error.code) trace.push(error.code);
    if (error && error.requestId) trace.push("요청 ID: " + error.requestId);
    return trace.length ? message + " (" + trace.join(" · ") + ")" : message;
  }

  var format = {
    int: int,
    won: won,
    isNegative: isNegative,
    rate: rate,
    usageRate: usageRate,
    date: date,
    dateTime: dateTime,
    month: month,
    today: today,
    errorText: errorText
  };

  if (typeof module === "object" && module.exports) {
    module.exports = format;
    return;
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.format = format;
})();
