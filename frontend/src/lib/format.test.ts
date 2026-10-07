import { test, assert, vi } from "vitest";
import * as format from "./format";
test("금액은 천 단위로 묶고 음수는 숫자만 괄호로 감싼다 (화면정의서 4장 규칙 1)", () => {
  assert.strictEqual(format.won(1517944), "1,517,944원");
  // 산출물 리터럴 예시가 (1,517,944) 이므로 "원" 은 괄호 밖이다.
  assert.strictEqual(format.won(-1517944), "(1,517,944)원");
  assert.strictEqual(format.won(0), "0원");
  assert.strictEqual(format.isNegative(-1), true);
  assert.strictEqual(format.isNegative(0), false);
  assert.strictEqual(format.isNegative(null), false);
});

test("요율은 소수 넷째 자리까지 자르기만 하고 반올림하지 않는다 (규칙 3)", () => {
  assert.strictEqual(format.rate("650.000000"), "650.0000");
  assert.strictEqual(format.rate("1.234567"), "1.2345");
  assert.strictEqual(format.rate("104.166667"), "104.1666");
  assert.strictEqual(format.rate("1200"), "1,200.0000");
});

test("사용률은 소수 여섯째 자리다 — 요율과 자리수가 다르다 (인터페이스정의서 2-4)", () => {
  // 화면정의서 CAP-W02(:1005) 예시 그대로.
  assert.strictEqual(format.usageRate("74.166667"), "74.166667");
  assert.strictEqual(format.usageRate("104.166667"), "104.166667");
  // 4자리로 자르면 판정 근거와 어긋난다 — 두 함수가 실제로 달라야 한다.
  assert.notEqual(format.usageRate("104.166667"), format.rate("104.166667"));
  // 모자란 자리는 0으로 채우고, 넘치는 자리는 반올림 없이 버린다.
  assert.strictEqual(format.usageRate("1200"), "1,200.000000");
  assert.strictEqual(format.usageRate("1.23456789"), "1.234567");
  assert.strictEqual(format.usageRate("not-a-rate"), "-");
});

test("서버가 이미 YYYY-MM-DD 로 준 날짜는 그대로 통과시킨다", () => {
  assert.strictEqual(format.date("2026-07-01"), "2026-07-01");
  assert.strictEqual(format.month("2026-07-01"), "2026-07");
});

test("일시는 Asia/Seoul 로 고정해 표시한다 (인터페이스정의서 2-4)", () => {
  // 오프셋이 다르게 들어와도 같은 순간이면 같은 KST 로 나와야 한다.
  assert.strictEqual(format.dateTime("2026-08-03T14:05:22+09:00"), "2026-08-03 14:05");
  assert.strictEqual(format.dateTime("2026-08-03T05:05:22Z"), "2026-08-03 14:05");
  // UTC 기준으로는 전날이지만 KST 로는 당일이다.
  assert.strictEqual(format.dateTime("2026-08-02T15:30:00Z"), "2026-08-03 00:30");
  assert.strictEqual(format.date("2026-08-02T15:30:00Z"), "2026-08-03");
  assert.strictEqual(format.dateTimeSeconds("2026-08-02T15:30:07Z"), "2026-08-03 00:30:07");
  assert.strictEqual(format.dateTimeSeconds("not-a-date"), "-");
});

test("빈 값과 잘못된 값은 대시로 통일한다", () => {
  ["", "   ", null, undefined, "not-a-number"].forEach((value) => {
    assert.strictEqual(format.won(value), "-");
    assert.strictEqual(format.int(value), "-");
  });
  assert.strictEqual(format.rate("not-a-rate"), "-");
  assert.strictEqual(format.date(""), "-");
  assert.strictEqual(format.dateTime("not-a-date"), "-");
});

test("오류 문구에 오류코드와 요청 ID 를 병기한다 (FGC-SIR-007)", () => {
  assert.strictEqual(
    format.errorText({ code: "FGC-CONT-001", message: "저장 불가 — 이미 등록된 계약번호입니다.", requestId: "20260820-1a2b3c" }),
    "저장 불가 — 이미 등록된 계약번호입니다. (FGC-CONT-001 · 요청 ID: 20260820-1a2b3c)"
  );
  assert.strictEqual(format.errorText({ message: "저장 불가" }), "저장 불가");
  assert.strictEqual(format.errorText(null, "요청을 처리하지 못했습니다."), "요청을 처리하지 못했습니다.");
});

test("본문에 이미 요청 ID 가 있으면 뒤에 다시 붙이지 않는다 (FGC-COMMON-500)", () => {
  const requestId = "20260803-7f3a1c";
  // messages_ko.properties:50 의 {requestId} 가 서버에서 치환되어 내려오는 형태.
  const text = format.errorText({
    code: "FGC-COMMON-500",
    message: `처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요.`,
    requestId
  });

  assert.strictEqual(text.match(new RegExp(requestId, "g"))!.length, 1);
  assert.strictEqual(
    text,
    `처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요. (FGC-COMMON-500)`
  );
});

test('today도 브라우저 시간대 대신 KST 월 경계를 따른다', () => {
  vi.useFakeTimers();
  try {
    vi.setSystemTime(new Date('2026-08-31T15:00:00Z'));
    assert.strictEqual(format.today(), '2026-09-01');
    assert.strictEqual(format.month(format.today()), '2026-09');
  } finally { vi.useRealTimers(); }
});
