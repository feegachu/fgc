// 화면정의서 §4 규칙 3: 소수 표시는 한 자리로 절사하고 원본 계산·판정값은 유지한다.
  const EMPTY = "-";
  const SEOUL = "Asia/Seoul";

  function isBlank(value: unknown) {
    return value == null || value === "" || (typeof value === "string" && value.trim() === "");
  }

  /* 천 단위 콤마. 건수·회차처럼 금액이 아닌 수치에 쓴다. */
  export function int(value: unknown) {
    if (isBlank(value)) return EMPTY;
    const parsed = Number(value);
    if (!isFinite(parsed)) return EMPTY;
    return parsed.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
  }

  /*
   * 금액. 음수는 마이너스 기호를 붙인다 (화면정의서 4장 규칙 1).
   * 예: -1,517,944원. 호출부가 isNegative() 로 판별해
   * .is-negative-amount 클래스로 빨간색을 함께 적용한다.
   */
  export function won(value: unknown) {
    if (isBlank(value)) return EMPTY;
    const parsed = Number(value);
    if (!isFinite(parsed)) return EMPTY;
    const digits = Math.abs(parsed).toLocaleString("ko-KR", { maximumFractionDigits: 0 });
    return parsed < 0 ? "-" + digits + "원" : digits + "원";
  }

  export function isNegative(value: unknown) {
    if (isBlank(value)) return false;
    const parsed = Number(value);
    return isFinite(parsed) && parsed < 0;
  }

  /*
   * 소수 자리를 "자르기만" 한다 — 반올림하지 않는다 (화면정의서 4장 규칙 2·3).
   * 서버가 문자열("104.166667")로 주는 이유가 부동소수점 오차를 없애기 위해서이므로
   * Number 로 바꾸지 않고 문자열을 다룬다.
   */
  function truncateDecimal(value: unknown, digits: number) {
    if (isBlank(value)) return EMPTY;

    let normalized = String(value).trim();
    if (!/^[+-]?\d+(?:\.\d+)?$/.test(normalized)) return EMPTY;

    let sign = "";
    if (normalized.charAt(0) === "-" || normalized.charAt(0) === "+") {
      sign = normalized.charAt(0) === "-" ? "-" : "";
      normalized = normalized.slice(1);
    }

    const parts = normalized.split(".");
    const integerPart = parts[0].replace(/^0+(?=\d)/, "");
    const fractionPart = ((parts[1] || "") + new Array(digits + 1).join("0")).slice(0, digits);
    return sign + integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + fractionPart;
  }

  /*
   * 요율·배수 — 소수 첫째 자리. 예: 650.0%, 12.0배.
   */
  export function rate(value: unknown) {
    return truncateDecimal(value, 1);
  }

  /*
   * 사용률 — 소수 첫째 자리. 예: 104.1%.
   * 저장·API의 소수 6자리와 서버 판정은 보존하고 화면 문자열만 절사한다.
   */
  export function usageRate(value: unknown) {
    return rate(value);
  }

  /* 서버 안내 문구에 포함된 소수 백분율만 표시 규칙을 적용한다. 1,200% 같은 명칭은 유지한다. */
  export function rateText(value: unknown) {
    return String(value == null ? "" : value).replace(
      /(^|[^\w.,+-])([+-]?\d[\d,]*\.\d+)(\s*%)/g,
      (_, prefix: string, decimal: string, suffix: string) => prefix + rate(decimal.replace(/,/g, "")) + suffix,
    );
  }

  function seoulParts(value: string | Date) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return null;

    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: SEOUL,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false
    }).formatToParts(date);

    const found: Record<string, string> = {};
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
  export function date(value: unknown) {
    if (isBlank(value)) return EMPTY;

    const text = String(value).trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;

    const parts = seoulParts(text);
    return parts ? parts.year + "-" + parts.month + "-" + parts.day : EMPTY;
  }

  /* YYYY-MM-DD HH:mm, Asia/Seoul 고정. */
  export function dateTime(value: unknown) {
    if (isBlank(value)) return EMPTY;

    const parts = seoulParts(String(value).trim());
    if (!parts) return EMPTY;
    return parts.year + "-" + parts.month + "-" + parts.day + " " + parts.hour + ":" + parts.minute;
  }

  /* YYYY-MM-DD HH:mm:ss, Asia/Seoul 고정. 감사로그 발생시각처럼 초까지 구분해야 하는 값에 쓴다. */
  export function dateTimeSeconds(value: unknown) {
    if (isBlank(value)) return EMPTY;

    const parts = seoulParts(String(value).trim());
    return parts ? dateTime(value) + ":" + parts.second : EMPTY;
  }

  /* YYYY-MM. 정산월·귀속월은 서버가 항상 그 달 1일로 준다. */
  export function month(value: unknown) {
    const normalized = date(value);
    return normalized === EMPTY ? EMPTY : normalized.slice(0, 7);
  }

  /* Asia/Seoul 기준 오늘. 계약일 max 같은 입력 제한에 쓴다. */
  export function today() {
    const parts = seoulParts(new Date())!;
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
   *
   * 요청 ID 중복 방지
   *   일부 문구는 서버가 이미 요청 ID 를 본문에 치환해 보낸다.
   *     messages_ko.properties:50 error.common.internal
   *       "처리 중 오류가 발생했습니다. 요청번호 {requestId}를 담당자에게 알려주세요."
   *   GlobalExceptionHandler 가 COMMON_500 경로(:227-249, :167-173)에서 params 에 requestId 를
   *   넣고 FgcMessageResolver 가 치환하는데, 그 값은 ApiResponse 봉투의 requestId 와 같은
   *   RequestIdContext.current() 다. 그대로 덧붙이면 가장 흔한 500 토스트에서 같은 번호가 두 번 찍힌다.
   *   그래서 본문에 이미 들어 있으면 뒤에 다시 적지 않는다.
   */
  export function errorText(error?: { message?: string; code?: string; requestId?: string | null } | null, fallbackMessage?: string) {
    const message = rateText((error && error.message) || fallbackMessage || "요청을 처리하지 못했습니다.");
    const requestId = error && error.requestId;
    const trace: string[] = [];

    if (error && error.code) trace.push(error.code);
    if (requestId && message.indexOf(requestId) === -1) {
      trace.push("요청 ID: " + requestId);
    }

    return trace.length ? message + " (" + trace.join(" · ") + ")" : message;
  }
