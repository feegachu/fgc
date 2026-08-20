const test = require("node:test");
const assert = require("node:assert/strict");

const format = require("../../main/resources/static/js/common/format.js");

test("금액은 천 단위로 묶고 음수는 숫자만 괄호로 감싼다 (화면정의서 4장 규칙 1)", () => {
  assert.equal(format.won(1517944), "1,517,944원");
  // 산출물 리터럴 예시가 (1,517,944) 이므로 "원" 은 괄호 밖이다.
  assert.equal(format.won(-1517944), "(1,517,944)원");
  assert.equal(format.won(0), "0원");
  assert.equal(format.isNegative(-1), true);
  assert.equal(format.isNegative(0), false);
  assert.equal(format.isNegative(null), false);
});

test("요율은 소수 넷째 자리까지 자르기만 하고 반올림하지 않는다 (규칙 3)", () => {
  assert.equal(format.rate("650.000000"), "650.0000");
  assert.equal(format.rate("1.234567"), "1.2345");
  assert.equal(format.rate("104.166667"), "104.1666");
  assert.equal(format.rate("1200"), "1,200.0000");
});

test("사용률은 소수 여섯째 자리다 — 요율과 자리수가 다르다 (인터페이스정의서 2-4)", () => {
  // 화면정의서 CAP-W02(:1005) 예시 그대로.
  assert.equal(format.usageRate("74.166667"), "74.166667");
  assert.equal(format.usageRate("104.166667"), "104.166667");
  // 4자리로 자르면 판정 근거와 어긋난다 — 두 함수가 실제로 달라야 한다.
  assert.notEqual(format.usageRate("104.166667"), format.rate("104.166667"));
  // 모자란 자리는 0으로 채우고, 넘치는 자리는 반올림 없이 버린다.
  assert.equal(format.usageRate("1200"), "1,200.000000");
  assert.equal(format.usageRate("1.23456789"), "1.234567");
  assert.equal(format.usageRate("not-a-rate"), "-");
});

test("서버가 이미 YYYY-MM-DD 로 준 날짜는 그대로 통과시킨다", () => {
  assert.equal(format.date("2026-07-01"), "2026-07-01");
  assert.equal(format.month("2026-07-01"), "2026-07");
});

test("일시는 Asia/Seoul 로 고정해 표시한다 (인터페이스정의서 2-4)", () => {
  // 오프셋이 다르게 들어와도 같은 순간이면 같은 KST 로 나와야 한다.
  assert.equal(format.dateTime("2026-08-03T14:05:22+09:00"), "2026-08-03 14:05");
  assert.equal(format.dateTime("2026-08-03T05:05:22Z"), "2026-08-03 14:05");
  // UTC 기준으로는 전날이지만 KST 로는 당일이다.
  assert.equal(format.dateTime("2026-08-02T15:30:00Z"), "2026-08-03 00:30");
  assert.equal(format.date("2026-08-02T15:30:00Z"), "2026-08-03");
});

test("빈 값과 잘못된 값은 대시로 통일한다", () => {
  ["", "   ", null, undefined, "not-a-number"].forEach((value) => {
    assert.equal(format.won(value), "-");
    assert.equal(format.int(value), "-");
  });
  assert.equal(format.rate("not-a-rate"), "-");
  assert.equal(format.date(""), "-");
  assert.equal(format.dateTime("not-a-date"), "-");
});

test("오류 문구에 오류코드와 요청 ID 를 병기한다 (FGC-SIR-007)", () => {
  assert.equal(
    format.errorText({ code: "FGC-CONT-001", message: "저장 불가 — 이미 등록된 계약번호입니다.", requestId: "20260820-1a2b3c" }),
    "저장 불가 — 이미 등록된 계약번호입니다. (FGC-CONT-001 · 요청 ID: 20260820-1a2b3c)"
  );
  assert.equal(format.errorText({ message: "저장 불가" }), "저장 불가");
  assert.equal(format.errorText(null, "요청을 처리하지 못했습니다."), "요청을 처리하지 못했습니다.");
});

test("본문에 이미 요청 ID 가 있으면 뒤에 다시 붙이지 않는다 (FGC-COMMON-500)", () => {
  const requestId = "20260803-7f3a1c";
  // messages_ko.properties:50 의 {requestId} 가 서버에서 치환되어 내려오는 형태.
  const text = format.errorText({
    code: "FGC-COMMON-500",
    message: `처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요.`,
    requestId
  });

  assert.equal(text.match(new RegExp(requestId, "g")).length, 1);
  assert.equal(
    text,
    `처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요. (FGC-COMMON-500)`
  );
});
