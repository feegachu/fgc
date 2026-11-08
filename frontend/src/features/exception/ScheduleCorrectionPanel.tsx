import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { date, errorText, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useAuth } from '../auth/useAuth'
import { contractDetailQuery, regenerateContractSchedules } from '../contract/api'
import { ContractStatusBadge } from '../contract/badges'
import { ContractSchedulesView } from '../contract/ContractTabs'
import { ContractFacts, QueryContent } from '../contract/detailParts'
import '../contract/detail.css'
import '../contract/legacy.css'

export function ScheduleCorrectionPanel({ contractId, exceptionId, onRegenerated, readOnly = false }: {
  contractId: number; exceptionId: number; onRegenerated: () => Promise<void>; readOnly?: boolean
}) {
  const id = String(contractId)
  const client = useQueryClient()
  const { user } = useAuth()
  const detail = useQuery(contractDetailQuery(id))
  const regenerate = useMutation({
    mutationKey: ['exception', 'write', exceptionId],
    mutationFn: () => regenerateContractSchedules(id, `EXCEPTION_${exceptionId}_SCHEDULE_CORRECTION`),
    meta: { errorToast: false },
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['contracts', 'detail', id] }),
        client.invalidateQueries({ queryKey: ['schedules'] }),
        client.invalidateQueries({ queryKey: ['cap-checks'] }),
      ])
      toast('예상 스케줄을 재생성했습니다. 예외 원인을 다시 검증합니다.', 'success')
      await onRegenerated()
    },
  })
  return <div className="contract-react-detail exception-contract-schedules" data-guide-feature="features.exception.schedule-correction.contract-preview">
    <QueryContent query={detail}>{contract => <>
      <section data-guide-feature="features.exception.schedule-correction.contract-summary" className="surface contract-summary-card">
        <header className="surface-header">
          <div className="contract-summary-title-row">
            <h3 className="contract-summary-number">{contract.contractNo ?? '-'}</h3>
            <ContractStatusBadge code={contract.contractStatus} label={contract.contractStatusLabel} />
          </div>
          {!readOnly && <Button data-guide-feature="features.exception.schedule-correction.regenerate" variant="secondary"
            loading={regenerate.isPending} disabled={!user?.canProcess || regenerate.isPending}
            title={!user?.canProcess ? '스케줄 재생성은 정산담당자와 시스템관리자만 할 수 있습니다.' : undefined}
            onClick={() => { if (!regenerate.isPending) regenerate.mutate() }}>
            <span className="material-symbols-rounded" aria-hidden="true">restart_alt</span>스케줄 재생성
          </Button>}
        </header>
        <div className="surface-body"><ContractFacts className="contract-summary-grid" items={[
          ['보험회사', contract.insurerName], ['상품', contract.productName], ['계약일', date(contract.contractDate)],
          ['월납환산 초회보험료', won(contract.monthlyEquivalentFirstPremium)],
          ['모집 설계사', contract.agentName], ['소속 조직', contract.organizationName],
          ['납입기간', contract.paymentTermMonths == null ? '-' : `${contract.paymentTermMonths}개월`],
        ]} /></div>
      </section>
      {regenerate.error && <p role="alert" className="field-error">{errorText(regenerate.error, '스케줄 재생성 또는 재검증 결과를 확인하지 못했습니다.')}</p>}
      <section data-guide-feature="features.exception.schedule-correction.schedule-list" className="surface exception-contract-schedule-list"><ContractSchedulesView id={id} /></section>
    </>}</QueryContent>
  </div>
}
