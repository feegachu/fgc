import type { PaymentGuideInput } from '../../transaction/payment-guide'
import { isWonAmount } from '../guards'
import { getScreenWalkthrough } from '../guide/page-guides'

export interface PaymentWalkthroughStep {
  id: string
  selector: string
  title: string
  instruction: string
  ready: boolean
  interaction?: string
  concise?: boolean
}
// Input checks only unlock explanation steps. Saving/completion still requires server evidence.
const validAmount = (value: string) => isWonAmount(value) && BigInt(value) >= 0n && BigInt(value) <= BigInt(Number.MAX_SAFE_INTEGER)
export function paymentWalkthrough(input: PaymentGuideInput | null, contractId?: number): PaymentWalkthroughStep[] {
  if (!input) return []
  const { form, rows } = input
  const steps: PaymentWalkthroughStep[] = []
  const page = getScreenWalkthrough('TRAN-W02')
  const pageTargets: Record<string, string> = {
    stage: 'features.transaction.transaction-form-page.stage', month: 'features.transaction.transaction-form-page.settlement-month',
    recipient: 'features.transaction.transaction-form-page.recipient', item: 'features.transaction.transaction-form-page.item',
    amount: 'payment.amount', key: 'features.transaction.transaction-form-page.biz-key', save: 'payment.save',
  }
  const add = (id: string, selector: string, title: string, instruction: string, ready = true) => {
    const shared = page.find(step => step.targetId === pageTargets[id])
    steps.push({ ...(shared ?? {}), id, selector, title: shared?.title ?? title,
      instruction: shared?.instruction ?? instruction, ready })
  }
  add('stage', '#stage', '지급단계를 확인하세요', 'GA → 설계사인지 보험사 → GA인지 확인하세요. 전체 가이드에서 선택한 지급단계와 같아야 합니다.')
  add('month', '#settlementMonth', '정산월을 확인하세요', '이번 지급이 속하는 정산월을 확인하세요.', !!form.settlementMonth)
  if (form.paymentStage === 'GA_TO_FC')
    add('recipient', '#recipient', '수령 설계사를 선택하세요', '이름이나 코드로 검색한 뒤 검색 결과에서 실제 수령인을 선택하세요.', !!form.agentId)
  add('item', '#item', '수수료 항목을 선택하세요', '검색 결과에서 이번 지급의 수수료 종류를 선택하세요.', !!form.commissionItemId)
  add('amount', '#amount', '전체 지급금액을 입력하세요', '여러 계약에 나눌 금액을 포함한 지급 건 전체 금액입니다. 0원 이상 원 단위로 입력하세요.', validAmount(form.amount))
  add('key', '#bizKey', '지급 관리번호를 확인하세요', '같은 지급 건을 두 번 저장하지 않게 구분하는 번호입니다.', !!form.sourceBusinessKey.trim())
  for (const [index, row] of rows.entries()) {
    const prefix = `귀속 ${index + 1} · `
    if (row.attributionMethod !== 'NEWCOMER_NON_CONTRACT')
      add(`contract-${row.key}`, `#attr-${row.key}-contractId`, `${prefix}계약을 선택하세요`, '검색 결과에서 전체 업무 가이드를 시작할 때 선택한 계약을 선택하세요.', !!row.contractId && (!contractId || row.contractId === String(contractId)))
    add(`date-${row.key}`, `#attr-${row.key}-attributionDate`, `${prefix}귀속일을 입력하세요`, '이 계약에 금액이 귀속되는 날짜를 입력하세요.', !!row.attributionDate)
    add(`amount-${row.key}`, `#attr-${row.key}-amount`, `${prefix}귀속금액을 입력하세요`, '이 계약에 배분할 금액입니다. 모든 귀속금액의 합계는 전체 지급금액과 같아야 합니다.', validAmount(row.amount))
    add(`decision-${row.key}`, `#attr-${row.key}-decision`, `${prefix}산입 여부를 확인하세요`, '산입 여부는 실제 지급 근거에 따라 선택하세요. 제외를 선택하면 증빙이 필요합니다.')
    if (row.decision.startsWith('EXCLUDED'))
      add(`evidence-${row.key}`, `#attr-${row.key}-evidenceRef`, `${prefix}제외 증빙을 입력하세요`, '제외 근거를 확인할 수 있는 문서번호나 링크를 입력하세요.', !!row.evidenceRef.trim() || !!form.evidenceRef.trim())
  }
  add('add', '#btn-add-attr', '귀속 내역을 추가하나요?', '필요하면 선택한 계약의 귀속 내역을 추가하세요. 추가한 행도 하나씩 안내합니다. 현재 전체 업무 가이드는 계약 한 건을 기준으로 진행합니다.')
  add('review', '[data-guide-target="payment.attributions"]', '입력 결과를 확인하세요', input.validationMessage || '계약, 귀속일, 산입 여부와 지급금액 합계를 확인하세요.', input.valid)
  add('save', '#btn-save', '직접 초안을 저장하세요', '화면의 임시저장 버튼을 누르세요. 저장 성공을 확인한 뒤 아래 버튼으로 다음 업무를 안내합니다.')
  return steps
}
