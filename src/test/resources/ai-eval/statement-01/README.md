# TER-011 최소 정답셋 — 보험사 정산데이터 1종 (#638)

10/27 PoC 게이트에서 "한 번 돌았다"가 아니라 오수락·누락·합계·수정 부담을 숫자로 보기 위한 고정 샘플과 측정 절차다.
근거: TER-011(요구사항명세서 r19), SRC-057(스프레드시트는 시트당 앞 1,000행), SRC-060(gpt-4o-mini 단가·예산 10달러).

## 파일

| 파일 | 내용 |
|---|---|
| `statement-01.xlsx` | 합성 정산 명세. 시트 `안내`(데이터 없음) · `수수료명세`(원, 1,001행) · `환수차감(천원)`(천원, 60행) |
| `statement-01.gold.json` | `meta` · `totals`(시트별 원본 합계표: 행 수·지급·차감·순액, 원) · `rows`(필드 단위 정답 1,061행, 한 줄에 한 행) |

생성기는 `src/test/java/.../ai/eval/StatementGoldSample.java`(seed 638). 정답 행은 코드가 만든다 — LLM 이 쓰지 않는다.
계약자 개인정보는 없다(SRC-027 D-05). 고치려면 `GENERATOR_VERSION` 을 올리고 `main` 으로 다시 쓴 뒤 PR 에 이유를 적는다.
`StatementGoldSampleTest` 가 고정 파일과 생성기 결과가 다르면 실패한다.

### 들어 있는 함정

- `수수료명세` 는 데이터 1,001행에 반복 헤더·소계를 더해 물리 행이 1,045행이다 — 앞 1,000행만 읽으면 뒤 행이 **누락**으로 잡힌다
- 50행마다 반복 헤더와 `소계`, 시트 끝에 `합계` 행 — 데이터로 읽어 정상 통과시키면 **오수락(없는 행)**·합계 차이
- 차감·환수는 음수 금액. `수수료명세` 는 `구분`(지급/차감) 열이 있고, `환수차감(천원)` 은 구분 열 없이 부호로만 나타낸다
- `환수차감(천원)` 은 천원 단위·소수(예: 1526.5 → 1,526,500원)·정산월 표기 `2026.09`, 헤더 이름도 다르다
- 모집자코드(`000451`)·상품코드(`0032`)·증권번호(`0026…`)는 선행 0 이 있는 텍스트 셀

## 정답 필드 (정규화값)

`settlementMonth`(`2026-09`) · `policyNo` · `agentCode` · `productCode`(글자 그대로) · `installment`(정수) ·
`itemName`(명세에 찍힌 항목명) · `cashflowType`(`PAYMENT`/`DEDUCTION`) · `amount`(원 단위 양수)

## 지표 정의

| 지표 | 정의 |
|---|---|
| 행 맞대기 | 추출 행의 `sourceLocation`(시트·엑셀 행 번호)로 정답 행과 맞댄다. 위치가 없거나 다르면 누락+초과 |
| 필드 정확도 | 필드별 맞은 수 / 정답 행 수 (누락 행은 틀림) |
| **오수락** | 시스템이 정상(`READY`·`CONFIRMED`)으로 둔 행에서 필수 식별값·금액(`settlementMonth`·`policyNo`·`agentCode`·`cashflowType`·`amount`)이 틀림, 또는 정답에 없는 행을 정상으로 둠. **1건이라도 있으면 시연 차단 결함** |
| 누락 / 초과 행 | 정답에 있는데 추출 안 됨 / 정답에 없거나 같은 위치에 두 번 |
| 합계 차이 | 시트별 (추출 지급 − 차감) − 원본 순액. `EXCLUDED` 행은 빼고 센다 |
| 확인 필요 비율 | (`REVIEW_REQUIRED` + `ERROR`) / 추출 행 |
| **무수정 처리율** | 정상 상태·사람 수정 없음·8필드 모두 맞음 / 정답 행 — 전부 '확인 필요'로 보내면 0% 가 된다 |
| 사람 수정률 | 사람이 고친 행 / 추출 행 |
| 업무 소요시간 | 업로드 시작부터 검토 완료(확정 직전)까지 사람이 잰 시간(초) |
| 비용 | (입력 토큰 × 입력 단가 + 출력 토큰 × 출력 단가) / 1,000,000 달러 |
| 경로 | 이 문서의 **행 추출**(`INTAKE_EXTRACT`) 성공 호출이 0회면 **"대체 경로 동작"** 으로 표시하고 AI 추출 합격으로 보지 않는다. 서식 분류(`INTAKE_CLASSIFY`)만 LLM 을 썼어도 대체 경로다 |

행 상태(오수락·확인 필요 비율)는 **추출 직후** 내보내기로, 사람 수정 여부(수정률·무수정 처리율)는 **검토 완료 뒤** 내보내기로 본다.
검토 중 '제외'는 `row_status` 를 `EXCLUDED` 로 덮어쓰므로(V58 `ck_intake_draft_row_excluded`), 검토 뒤 상태만 보면 시스템이 정상으로 통과시킨 소계 행이 오수락에서 빠진다.

필수 식별값 5개를 고른 것은 **프로젝트 가정**이다(FUN-068 의 계약·설계사 매칭과 대사가 기대는 값). 상품·회차·항목은 정확도에는 들어가지만 오수락으로는 세지 않는다.
합격 기준 수치는 정하지 않았다 — 10/27 실측 뒤 TER-011 검증기준 칸에 기록한다(실측 전 업계 기준을 쓰지 않는다).

## 10/27 PoC 실행 절차

1. #640 업로드 화면으로 `statement-01.xlsx` 를 올리고, 스톱워치로 검토 완료까지 시간을 잰다
2. 추출 초안을 **두 번** 내보낸다 — 문서가 REVIEW 가 된 직후(아무도 손대기 전) `before-review.json`, 검토를 마친 뒤(확정 전) `after-review.json`

   ```bash
   psql -At -c "SELECT json_agg(json_build_object('sourceLocation', source_location, 'rowStatus', row_status,
     'edited', edited_fields IS NOT NULL, 'fields', normalized_fields) ORDER BY row_no)
     FROM fgc.intake_draft_row WHERE intake_document_id = <문서ID>" > build/ai-eval/poc-1027/before-review.json
   # 검토를 마친 뒤 같은 명령으로 > build/ai-eval/poc-1027/after-review.json
   ```

3. 같은 문서의 토큰을 `ai_call_log` 에서 읽어 `run.json` 을 쓴다 — 토큰은 비용이라 분류 호출까지 모두 더하고, `aiSuccessCalls` 는 행 추출 성공만 센다

   ```sql
   SELECT model_name, sum(input_tokens), sum(output_tokens),
          count(*) FILTER (WHERE call_status = 'SUCCESS' AND purpose = 'INTAKE_EXTRACT')
   FROM fgc.ai_call_log WHERE related_entity_type = '<#640이 정한 값>' AND related_entity_id = <문서ID> GROUP BY model_name;
   ```

   ```json
   {"model": "gpt-4o-mini", "inputTokens": 0, "outputTokens": 0, "aiSuccessCalls": 0,
    "inputUsdPerMTok": 0.15, "outputUsdPerMTok": 0.60, "elapsedSeconds": 0}
   ```

   단가는 gpt-4o-mini 기준(SRC-060). 모델을 바꾸면 단가도 바꾼다(OI-023: 모델 ID 는 설정값). `elapsedSeconds` 를 안 쟀으면 `null`.

4. 측정한다 — 실제 API 를 부르지 않는다

   ```bash
   FGC_EVAL_RUN=build/ai-eval/poc-1027 ./gradlew test --tests '*StatementExtractionEvalRunTest' --rerun
   ```

   `--rerun` 이 없으면 Gradle 이 환경변수 변화를 모르고 `test` 를 UP-TO-DATE 로 건너뛰어 새 결과가 안 남는다.

   같은 폴더에 `result-{시각}.md`·`.json` 이 실행마다 남는다. md 를 #638 에 코멘트로 붙이고, 합격 기준 수치를 TER-011 에 기록한다.

### #640 이 맞춰야 할 것

- `intake_draft_row.source_location` 에 `{"sheet": "<시트 이름>", "row": <엑셀 행 번호>}` 를 담는다
- `normalized_fields` 키는 위 정답 필드 이름을 쓴다(다르면 내보내기 SQL 에서 바꿔 담는다)
- `normalized_fields` 값 형식도 정답에 맞춘다 — `settlementMonth` 는 `YYYY-MM`(`2026.09` 를 `2026-09` 로), `amount` 는 원 단위 양수(차감·환수의 부호는 `cashflowType` 에만), 코드(`policyNo`·`agentCode`·`productCode`)는 선행 0 을 지킨 문자열(JSON 숫자 `451` 은 `000451` 과 다르다고 센다)
- 사람 수정은 `edited_fields` 에 남긴다(수정률은 검토 뒤 내보내기의 `edited_fields IS NOT NULL` 로 센다)
- ai_call_log 에 서식 분류는 `INTAKE_CLASSIFY`, 행 추출(열 의미 매핑 포함)은 `INTAKE_EXTRACT` 로 나눠 남긴다

초과 행 수가 추출 행 수와 거의 같으면 추출이 틀렸다기보다 `source_location` 행 번호가 어긋난 것이다 — 엑셀 행 번호(1부터, 헤더 행 포함)가 아니라 0부터 세거나 데이터 행 순번을 넣었는지부터 의심한다.
