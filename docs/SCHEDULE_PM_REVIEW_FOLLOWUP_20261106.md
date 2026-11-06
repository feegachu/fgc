# 예정 스케줄 PM 검토 후속 보완 (2026-11-06)

대상: PR #822의 [PM 검토](https://github.com/feegachu/fgc/pull/822#issuecomment-6657566128), FUN-037/038, 이슈 #649/650.
기준: develop `c27bb64850e15a3266baca308e79629dcb12b624` + 이 문서와 함께 제출하는 회귀 검증 보완. 기존 병합 구현과 이번 추가 검증을 구분한다.

## 보완 사항

- `PolicyWorkflowIntegrationTest.assertPreviewPolicyEvidence`: API·DB의 정책 ID, API `ruleRef`와 DB `source_commission_rule_id`의 항목/회차/정책 정합성, 생성 감사 로그의 정책·헤더·`sourceRefs`를 직접 대조한다. 같은 멱등키로 재시도한 뒤 생성 감사 기록이 정확히 한 건인지 검사한다.
- 4년, 7년(장기유지 67~84), 7년(61~84)의 세 정책에 동일 검사를 적용한다. 새 검사는 이번 후속 변경이며 기존 develop에서 이미 검증됐다고 소급하지 않는다.
- 4년·7년 직접 확정 409 검사는 기존 `assertPreviewVersionLifecycle`이 세 정책 모두에서 실행하므로 중복 테스트를 추가하지 않았다.
- 인터페이스 §14-1에 `SCHEDULE_SOURCE_CHANGED`(409), `SCHEDULE_REVIEW_REQUIRED`(422) 및 #822·#828 반영 이력을 추가했다.
- §4-4-3의 목록 CSV 회차 수는 CURRENT 포함 `distinct installmentCount`임을 명시했다. 12회차에 항목 2개이면 회차 수 12, 행 수 24다. 상세 CSV는 항목 행을 유지한다. 이는 기존 구현의 의미를 명확히 한 것으로 기존 lineCount 표기를 되돌리는 변경이 아니다.

## CT별 검증 근거

인터페이스 §14-3의 **CT-S01은 4년·7년 생성/조회**, **CT-S02는 예정 확정 금지·CURRENT 확정 유지**다. 기존 E2E 제목의 `CT-S02: 84회차`는 7년 테스트를 구분하는 이름일 뿐, 공식 CT-S02 전체를 검증했다는 뜻이 아니다. 아래 표로 공식 기준에 대응한다.

| 인수 시험 | 경로 | 실행 근거와 판정 |
| --- | --- | --- |
| CT-S01 정상 | 실제 승인 정책 생성·저장·화면 조회 | 기존 `schedule-dod.spec.ts`의 48/84회차 테스트: 금액·마지막 예정일·회차/행 구분·CSV·v2 생성·v1 이력·목록 복귀 통과. 실서버 82개 실행에 포함 |
| CT-S01 정책 근거 | API → DB → 감사 로그 | 이번 `previewPersistsPolicyInstallmentsAndPreservesVersionsWithIdempotency` 3개 정책 사례에서 정책 ID·원천 규칙·sourceRefs 직접 대조 통과 |
| CT-S01 예외 | stale·비활성 원본·동시 생성·실패 롤백 | 같은 통합 테스트의 `assertPreviewVersionLifecycle`·`assertPreviewRollbackAndConcurrency`: 실제 DB/API 상태·버전 정합성 검증 |
| CT-S02 거절 | 4년·7년 IF-API-29A 직접 확정 | 같은 통합 테스트에서 HTTP 409 및 `FGC-IMPL-SCHEDULE_PREVIEW_ONLY` 검증. UI에서도 확정 불가 확인 |
| CT-S02 정상 | CURRENT 재생성 후 확정 | 기존 `journey.spec.ts` 실서버 실행에서 확정 후 CONFIRMED 확인. ScheduleService/Controller 회귀 검사 병행 |
| UI 실패 표시 | 목록·상세 오류/지연 응답 | `schedule-dod.spec.ts` 오류 응답 주입 시험은 UI 표시·재시도 검증이다. 서버의 실제 오류 발생 검증은 위 DB 통합 테스트와 구분한다 |

## 실행 기록과 한계

- 이번 정책 근거 추가 검사: 3개 사례 통과. 관련 서버 5개 클래스 회귀 테스트 **130개 통과, 실패·오류·건너뜀 0**. 실행: `./gradlew test --tests '*ScheduleServiceTest' --tests '*ScheduleControllerTest' --tests '*PolicyWorkflowIntegrationTest' --tests '*PaymentJournalCorrectionIntegrationTest' --tests '*JournalCorrectionControllerTest'`.
- 기존 전체 실서버 E2E: 2026-11-06 실행, **82/82 통과, 실패·건너뜀·재시도 0**, 약 14.2분. 기준은 위 develop + PR #905의 화면 문구 검사 수정이다. 이번 테스트/문서 변경 후 브라우저 전체를 새로 실행한 결과로 표시하지 않는다.
- 기존 로컬 E2E 증적: `output/playwright/screen-language-final-20261106/`의 `demo.xml`, `full-e2e.log`, `html-report/`. 원래 작업본에 보관된 로컬 산출물이며 이 PR에 첨부된 파일은 아니다. 운영 배포본 인수 검증은 아니다.

## 채택한 저장 방식과 완료 경계

기존 `schedule_header`·`schedule_line`·`commission_rule`·`fee_regime_ruleset`·`audit_log`를 재사용한다. 정책은 헤더의 `policy_version_id`, 원천 규칙은 회차의 `source_commission_rule_id`, 생성 근거는 감사 로그의 `sourceRefs`에 남는다. API의 원천 규칙 이름은 `ruleRef`다. 같은 계약/지급단계/용도/시나리오의 잠금과 버전 증가·이전 판 inactive 처리, 멱등키 및 트랜잭션 롤백은 기존 구현을 유지한다.

이번 보완은 테스트·문서 변경이며 신규 DDL/마이그레이션은 **해당 없음**이다. 새 snapshot 테이블이나 큐를 도입하지 않는다. OpenAPI nullable·요청 조건 정합성은 별도 PR #904 범위다. #649의 마지막 문서 정합성 체크는 관련 후속 변경이 병합된 뒤 완료하며, 이슈 CLOSED 상태를 새 병합 완료의 근거로 사용하지 않는다.
