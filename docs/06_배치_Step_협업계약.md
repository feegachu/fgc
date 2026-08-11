# MonthlyValidationJob Step 협업 계약

## 공통 규칙

- Step 1은 `ValidationJobContext`를 받고 `validation_run_id`를 반환한다. Step 2~8은 이 ID와 모든 JobParameter를 포함한 `ValidationStepContext`를 받는다.
- 모든 포트 구현체는 `requestId`, `triggeredBy`를 감사 로그·업무 저장에 그대로 전달한다.
- Writer는 UPSERT 또는 `ON CONFLICT DO NOTHING`을 사용한다. 기존 결과를 삭제하고 다시 쓰지 않는다.
- 치명적 오류는 예외로 전파해 Spring Batch Step을 실패시킨다. 계약 단위로 허용된 오류만 `StepProcessingResult.skips`로 반환한다.
- Step 9(담당자 검토)와 Step 10(확정)은 사람이 수행하므로 이 계약에 포함하지 않는다.

| Step | 포트와 입력 | 결과·저장 대상 | 멱등성 키 | 오류 정책 |
| --- | --- | --- | --- | --- |
| 1 실행 생성 | `ValidationRunCreationPort.create(ValidationJobContext)` | `ValidationRunCreationResult`, `validation_run` | `(validation_month, run_no)` | 생성 불가 오류는 Job 실패 |
| 2 대상 선별 | `TargetSelectionPort.selectTargets(ValidationStepContext)` | `StepProcessingResult`, `validation_target` 및 선별 사유·상품/환급률표 스냅샷 | `(validation_run_id, contract_id)` | 치명 오류는 Step 실패 |
| 3 스케줄 재생성 | `ScheduleRegenerationPort.regenerateSchedules(ValidationStepContext)` | `StepProcessingResult`, `schedule_header`, `schedule_line` | 활성 OPERATIONAL 및 `uq_schedule_line_business` | 계약 오류는 `DATA_QUALITY` 예외 생성 후 skip 가능 |
| 4 1,200% 검증 | `CapCheckBatchPort.check(context, paymentStage)` | `StepProcessingResult`, `cap_check`, `cap_check_detail` | `(validation_run_id, contract_id, payment_stage)` | 지급단계별 처리. 설정된 skip 한도 초과 시 Step 실패 |
| 5 차익거래 | `ArbitrageCheckBatchPort.check(ValidationStepContext)` | `StepProcessingResult`, `arbitrage_check` | `(validation_run_id, contract_id, payment_stage, as_of_date)` | 개별 대상 skip 가능 |
| 6a 원장 기표 | `JournalPostingPort.post(ValidationStepContext)` | `JournalPostingResult`, 검증 원장 | 원장 원천 업무키 | 기표 오류는 Step 실패 |
| 6b 균형검사 | `LedgerImbalanceCheckPort.check(ValidationStepContext)` | `LedgerImbalanceResult`, 불균형 검증 결과 | 원장 원천 업무키 | `imbalanceCount > 0`이면 즉시 Step 실패 |
| 7 양방향 대사 | `ReconciliationBatchPort.reconcile(context, paymentStage)` | `StepProcessingResult`, `reconciliation_run/result/match` | `(reconciliation_run_id, match_group_key)` | 두 지급단계를 명시적으로 호출하며, 개별 대상 skip 가능 |
| 8 예외 생성 | `ExceptionGenerationPort.generate(ValidationStepContext)` | `StepProcessingResult`, `exception_case`, `exception_action` | `exception_key` | 중복은 생성하지 않음 |

## 상태·감사 계약

- Step 1 성공 직후 `validation_run`은 `CREATED → RUNNING`, `current_step = 1`로 전이한다.
- Step 2~8이 성공하면 `current_step`을 해당 업무 단계로 갱신한다. Step 6a·6b는 모두 `current_step = 6`을 사용한다.
- Step 실패는 실패 단계와 사유를 `validation_run`에 기록한다. 자동 재시작은 제공하지 않는다.
- 시작·완료·실패 감사 로그의 `user_id`는 `triggeredBy`, `request_id`는 JobParameter `requestId`다.
