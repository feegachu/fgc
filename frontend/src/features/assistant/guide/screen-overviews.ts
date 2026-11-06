export interface ScreenArea {
  selector: string
  title: string
  description: string
}

const target = (id: string, title: string, description: string): ScreenArea => ({
  selector: `[data-guide-target="${id}"]`, title, description,
})
const region = (selector: string, title: string, description: string): ScreenArea => ({ selector, title, description })
const filters = (description: string) => region('.filter-bar', '조회 조건', description)
const heading = (id: string, title: string, description: string) => region(`[aria-labelledby="${id}"]`, title, description)

export const screenOverviews: Record<string, ScreenArea[]> = {
  'CONT-W01': [
    target('contract.filters', '계약 조회 조건', '계약번호·보험회사·기간 등으로 조회 범위를 좁힙니다. 검색을 누르면 조건에 맞는 계약이 표시됩니다.'),
    heading('contract-results-title', '계약 목록', '계약번호를 선택하면 기본정보, 상태이력, 스케줄 등 계약 상세를 확인할 수 있습니다.'),
    target('contract.new', '신규 등록', '보험계약 등록 화면을 엽니다. 처리 권한이 있는 사용자에게 표시됩니다.'),
  ],
  'CONT-W03': [
    heading('contract-basic-heading', '계약 기본정보', '보험회사·상품·계약일과 계약번호를 입력합니다. 필수 항목과 오류 문구를 확인하세요.'),
    heading('contract-agent-heading', '설계사 정보', '계약에 연결할 설계사를 선택하고 소속과 상태를 확인합니다.'),
    heading('contract-premium-heading', '보험료 정보', '보험료와 납입 조건을 입력합니다. 한도 참고 금액은 입력한 조건에 따라 표시됩니다.'),
    target('contract.save', '계약 저장', '등록 또는 수정 내용을 저장합니다. 성공 후 계약 상세에서 저장 결과를 확인할 수 있습니다.'),
  ],
  'CONT-W02': [
    heading('contract-summary-title', '계약 요약', '선택한 계약의 번호와 주요 조건을 확인합니다.'),
    region('[role="tablist"]', '상세 탭', '기본정보·스케줄·검증 등 필요한 정보를 탭으로 바꿔 봅니다.'),
    target('contract.statusHistory', '상태이력', '계약 상태의 변화와 적용일·수신일을 확인합니다.'),
    target('contract.schedules', '분급 예정', '계약에 연결된 예정 회차와 버전을 확인합니다.'),
  ],
  'CONT-W02.agent': [
    heading('contract-summary-title', '본인 계약 요약', '본인에게 연결된 계약의 주요 정보를 확인합니다.'),
    target('contract.statusHistory', '상태이력', '계약 상태와 효력일·접수일, 변경 처리 결과를 확인합니다.'),
  ],
  'SCHE-W01': [
    target('schedule.filters', '조회 조건', '정산월·지급단계·체계로 분급 예정의 조회 범위를 정합니다.'),
    target('schedule.results', '스케줄 목록', '계약별 버전과 활성 상태를 확인합니다. 회차 보기를 누르면 상세로 이동합니다.'),
  ],
  'SCHE-W02': [
    heading('schedule-header-title', '스케줄 기본정보', '선택한 계약과 지급단계, 적용 정책, 스케줄 버전을 확인합니다.'),
    target('schedule.lines', '예정 회차', '회차별 귀속일·수수료 항목·예상 금액과 합계를 확인합니다.'),
    heading('schedule-versions-title', '버전 비교', '기존 버전과 새 버전의 회차 및 금액 변화를 비교합니다.'),
    target('schedule.generate', '새 버전 만들기', '권한과 상태가 허용되면 사유를 입력하고 새 버전을 생성할 수 있습니다.'),
  ],
  'TRAN-W01': [
    target('payment.filters', '지급 조회 조건', '정산월·계약·설계사·상태로 지급 건을 조회합니다.'),
    target('payment.results', '지급 목록', '작성중·확정·취소 상태와 금액을 확인합니다. 작성중 건은 수정으로 이어갈 수 있습니다.'),
  ],
  'TRAN-W02': [
    target('payment.scope', '지급 건', '정산월·지급단계·수취인·수수료 항목과 지급액을 입력합니다.'),
    target('payment.attributions', '계약 귀속', '지급액을 계약별로 나누고 귀속일과 증빙을 확인합니다.'),
    target('payment.save', '초안 저장', '입력한 지급 건을 초안으로 저장합니다. 이후 사전검증을 진행할 수 있습니다.'),
    target('payment.precheck', '사전검증', '저장된 지급 건의 조건을 검증하고 차단 사유와 확인 항목을 보여 줍니다.'),
    target('payment.confirm', '확정', '검증 결과와 권한을 충족한 지급 건을 확정합니다.'),
  ],
  'RPT-W02': [
    target('statement.filters', '명세 조회 조건', '기준월과 설계사를 선택해 수수료 명세를 조회합니다.'),
    region('.ast-summary', '유형별 요약', '지급·보류·조정·환수별 금액을 비교합니다.'),
    target('statement.results', '명세 내역', '귀속 계약과 항목별 금액을 확인합니다. 근거 보기에서 계산 상세를 엽니다.'),
    heading('ast-detail-title', '계산 근거', '선택한 항목의 원천 정보와 계산 내역을 확인합니다.'),
  ],
  'VRUN-W01': [
    target('validation.create', '실행 생성', '검증월과 실행 유형을 선택해 월간 통합검증을 생성합니다.'),
    heading('vrun-list-title', '실행 목록', '회차별 상태와 실행자를 확인하고, 회차를 선택해 진행 상황과 상세 결과를 봅니다.'),
  ],
  'VRUN-W02': [
    target('validation.progress', '검증 진행', '10단계의 진행 상태와 오류를 확인합니다. 실행 버튼은 현재 상태와 권한에 따라 활성화됩니다.'),
    heading('vrun-summary-title', '결과 요약', '계산 결과와 검출된 항목을 확인합니다.'),
    heading('vrun-exception-title', '발견된 예외', '검증에서 발견된 문제와 관련 원천을 확인합니다.'),
    target('validation.checklist', '확정 전 점검', '최종 확정에 필요한 8개 조건과 미충족 항목을 확인합니다.'),
    target('validation.finalize', '최종 확정', '조건과 권한을 충족하면 검증 결과를 확정하고 잠급니다.'),
    target('validation.restart', '신규 실행', '새로운 검증 실행을 만들 수 있는 목록 화면으로 이동합니다.'),
  ],
  'RECO-W01': [
    target('reconciliation.filters', '대사 실행 조건', '정산월·보험회사·대사 범위를 선택합니다. 실행 시 선택한 조건으로 원천 자료를 비교합니다.'),
    target('reconciliation.results', '대사 결과', '일치 여부, 불일치 유형과 계약을 확인합니다. 비교 보기에서 원천별 차이를 볼 수 있습니다.'),
    target('reconciliation.history', '실행 이력', '이전 실행과 현재 보고 있는 실행 번호·상태를 확인합니다.'),
  ],
  'EXCP-W01': [
    region('[aria-label="유형별 미처리 요약"]', '미처리 요약', '유형별로 남아 있는 예외 건수를 확인합니다.'),
    target('exception.filters', '조회 조건', '유형·상태·계약번호로 조회하고 확인할 예외를 선택합니다.'),
    region('.data-table-viewport', '예외 목록', '예외의 심각도와 처리 상태를 확인하고 한 건을 선택합니다.'),
    target('exception.detail', '검출 이력', '선택한 예외의 실행별 검출 근거와 원천을 확인합니다.'),
    target('exception.action', '처리 조치', '허용된 조치와 처리 이력을 확인합니다. 원천 정정 후에는 해당 업무에서 재검증 결과를 확인하세요.'),
  ],
  'POL-W01': [
    target('policy.filters', '기준일과 정책 선택', '조회 기준일을 정하고 적용할 정책 버전을 확인합니다.'),
    target('policy.overview', '정책 종류별 탭', '수수료·환급률 등 정책별 목록과 상세를 조회합니다. 관리 탭에서 정책 상태와 허용 조치를 확인합니다.'),
  ],
  'BASE-W01': [
    region('[role="tablist"]', '기준정보 종류', '보험회사·상품·조직·설계사 등 조회할 정보 종류를 고릅니다.'),
    region('.filter-bar', '조회 조건', '기준일과 검색 조건으로 해당 시점의 유효 정보를 찾습니다.'),
    target('base.overview', '기준정보 내역', '선택한 종류의 목록과 세부 정보를 확인합니다.'),
  ],
  'CAP-W01': [
    region('[aria-label="한도 검증 조회 조건"]', '조회 조건', '정산월·지급단계·상태·보험회사·조직으로 조회 범위를 정합니다.'),
    target('cap.overview', '한도 게이지', '선택한 계약의 지급단계별 한도 사용률과 판정을 확인합니다.'),
    region('[data-guide-feature="features.cap.cap-list-page.section-2"]', '계약별 판정', '정상·주의·위반·검토필요 상태를 확인하고 계약별 근거를 살펴봅니다.'),
    region('[data-guide-feature="features.cap.cap-list-page.section-3"]', '설계사별 합계', '설계사별 집계 금액과 검증 현황을 확인합니다.'),
  ],
  'ARB-W01': [
    filters('기준월·지급단계 등으로 차익거래 검증 결과를 조회합니다.'),
    target('arbitrage.overview', '판정 요약', '조건에 맞는 검증 건의 판정별 건수를 확인합니다.'),
    heading('arb-list-title', '검증 목록', '검토할 계약을 선택합니다. 검토대상 건은 추가 확인이 필요합니다.'),
    heading('arb-detail-title', '계산 근거', '선택한 결과의 수수료와 해약환급금 등 판단 근거를 확인합니다.'),
  ],
  'ACQC-W01': [
    filters('계약·지급단계·판정 등으로 계약체결비용 검증 결과를 조회합니다.'),
    target('acquisition.overview', '검증 목록', '계약별 선지급과 유지관리 결과를 확인합니다.'),
    heading('acq-detail-title', '한도와 계산 근거', '선택한 계약의 한도, 비용, 초과 여부와 검토가 필요한 사유를 확인합니다.'),
  ],
  'LEDG-W01': [
    filters('분개일·계약·상태 등으로 검증원장을 조회합니다.'),
    target('ledger.overview', '분개 목록', '분개 번호와 금액을 확인하고 상세를 볼 건을 선택합니다.'),
    region('[aria-label="분개 상세"]', '차변·대변과 원천', '계정별 금액과 연결 원천을 확인합니다. 정정은 권한과 상태에 따라 제공됩니다.'),
  ],
  'AUDT-W01': [
    filters('기간·사용자·대상 조건으로 감사 이력을 조회합니다.'),
    target('audit.overview', '감사 이력', '작업 시각과 사용자, 대상과 처리 결과를 확인합니다.'),
    heading('audit-diff-title', '변경 내역', '선택한 이력의 변경 전후 값을 비교합니다.'),
  ],
  'CLAW-W01': [
    target('clawback.filters', '환수 조회 조건', '기간·계약 등으로 환수 검토 대상을 조회합니다.'),
    heading('claw-list-title', '검토 대상', '계약 상태와 환수 검토 사유를 확인하고 대상을 선택합니다.'),
    heading('claw-detail-title', '계산과 근거', '선택한 대상의 계산 내역과 근거를 확인합니다. 계산과 PDF 생성은 권한에 따라 제공됩니다.'),
  ],
  'POL-W01.editor': [
    target('policy.editor', '정책 기본정보와 규칙', '정책 종류·기간·출처와 수수료 규칙을 입력합니다. 필수값과 오류 문구를 확인하세요.'),
    target('policy.save', '초안 저장', '입력 내용을 정책 초안으로 저장합니다. 저장 후 관리 화면에서 검토 요청을 진행할 수 있습니다.'),
  ],
  'POL-W01.management': [
    target('policy.actions', '정책 관리', '선택한 버전의 상태와 이력을 확인합니다. 검토 요청·승인·활성화는 현재 권한과 상태에 따라 제공됩니다.'),
  ],
  'POL-W01.sources': [
    target('policy.sources', '정책 근거', '선택한 정책의 출처·버전·자료 상태를 확인합니다. 누락된 근거도 함께 살펴보세요.'),
  ],
  'RECO-W02.detail': [
    target('reconciliation.detail', '원천별 비교', '예상 스케줄과 실제 지급의 필드를 비교하고 차액·불일치 유형·계산 근거를 확인합니다.'),
  ],
  'SCHE-W02.regenerate': [
    target('schedule.regenerate.reason', '생성 사유', '기존 스케줄에서 새 버전을 만들 사유를 1~40자로 입력합니다.'),
    target('schedule.regenerate.submit', '새 버전 만들기', '입력한 사유와 현재 조건으로 새 스케줄 버전을 생성합니다.'),
  ],
  'EXCP-W01.action': [
    target('exception.actionInput', '조치와 처리 사유', '선택한 예외에 허용된 조치를 고르고 사유와 증빙을 입력합니다.'),
    target('exception.actionSave', '처리 저장', '조치 내용을 저장합니다. 처리 이력과 변경된 상태에서 결과를 확인할 수 있습니다.'),
  ],
  'VRUN-W02.finalize': [
    target('validation.finalizeWarning', '확정 대상', '검증월·회차와 결과 잠금 안내를 확인합니다.'),
    target('validation.finalizeSubmit', '최종 확정', '권한과 8개 점검 조건을 충족하면 선택한 검증 결과를 확정하고 잠급니다.'),
  ],
  'EXCP-W01.correction': [
    target('exception.correctionInput', '정정 분개 입력', '원분개를 확인하고 새 분개일·계정·금액·사유를 입력합니다. 차변과 대변 합계가 같아야 합니다.'),
    target('exception.correctionSave', '정정 실행', '원분개를 역분개하고 새 분개를 기표합니다. 성공 후 정정 이력을 확인할 수 있습니다.'),
  ],
  'CONT-W02.preview': [
    target('schedule.preview.input', '분급 예정 조건', '지급단계·적용 정책·예정 회차를 확인하고 생성 사유를 입력합니다.'),
    target('schedule.preview.submit', '예정 생성', '확인한 조건으로 분급 예정을 생성합니다. 성공 후 스케줄에서 회차와 버전을 확인할 수 있습니다.'),
  ],
  'SCHE-W02.preview': [
    target('schedule.preview.input', '분급 예정 조건', '지급단계·적용 정책·예정 회차를 확인하고 생성 사유를 입력합니다.'),
    target('schedule.preview.submit', '예정 생성', '확인한 조건으로 분급 예정을 생성합니다. 성공 후 스케줄에서 회차와 버전을 확인할 수 있습니다.'),
  ],
}

export const targetTitles: Record<string, string> = {
  'policy.editor': '정책 기본정보와 규칙', 'policy.save': '초안 저장', 'policy.actions': '상태별 관리 조치',
  'policy.sources': '정책 근거 자료', 'reconciliation.detail': '원천별 비교',
  'schedule.regenerate.reason': '생성 사유', 'schedule.regenerate.submit': '새 버전 생성',
  'exception.actionInput': '조치와 처리 사유', 'exception.actionSave': '처리 저장',
  'validation.finalizeWarning': '확정 대상과 잠금 안내', 'validation.finalizeSubmit': '최종 확정',
  'exception.correctionInput': '정정 분개 입력', 'exception.correctionSave': '정정 실행',
  'schedule.preview.input': '지급단계와 예정 회차', 'schedule.preview.submit': '예정 생성',
}
