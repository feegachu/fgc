import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { date, int, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useAuth, usePermission } from '../auth/useAuth'
import { contractDetailQuery, recheckContractCap, regenerateContractSchedules } from './api'
import { ContractStatusBadge, DataOriginBadge } from './badges'
import { ContractFacts, ContractLoadState, ContractTabs } from './ContractTabs'
import { contractErrorText, contractTab } from './detailState'
import type { ContractTab } from './detailState'
import './legacy.css'
import './detail.css'

export default function ContractDetailPage() {
  const { id = '' } = useParams()
  return <ContractDetailView key={id} id={id} />
}

function ContractDetailView({ id }: { id: string }) {
  const validId = /^[1-9]\d*$/.test(id)
  const detail = useQuery({ ...contractDetailQuery(id), enabled: validId })
  const client = useQueryClient()
  const canProcess = usePermission('canProcess')
  const isAgent = useAuth().user?.roleCode === 'AGENT'
  const navigate = useNavigate()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const active = isAgent ? 'basic' : contractTab(params.get('tab'))
  const mounted = useRef(true)
  const displayedSave = useRef<string | null>(null)
  const saveMessage: unknown = location.state?.message

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  // 저장 성공은 이동한 화면에서 한 번만 표시하고, 뒤로가기로 다시 나타나지 않게 소비한다.
  useEffect(() => {
    if (typeof saveMessage !== 'string' || !saveMessage || displayedSave.current === saveMessage) return
    displayedSave.current = saveMessage
    toast(saveMessage, 'success')
    void navigate(`${location.pathname}${location.search}${location.hash}`, { replace: true, state: null })
  }, [saveMessage, location.pathname, location.search, location.hash, navigate])

  function selectTab(tab: ContractTab) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      next.set('tab', tab)
      next.delete('capCheckId')
      return next
    })
  }

  // 두 작업 모두 계약별 스냅샷 및 목록 요약을 바꾸므로 다른 화면의 캐시도 만료한다.
  async function invalidateContract() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ['contracts'] }),
      client.invalidateQueries({ queryKey: ['schedules'] }),
      client.invalidateQueries({ queryKey: ['cap-checks'] }),
    ])
  }
  const recheck = useMutation({
    mutationFn: () => recheckContractCap(id),
    meta: { errorToast: false },
    onSuccess: async () => {
      await invalidateContract()
      if (!mounted.current) return
      toast('1,200% 한도 재검증을 완료했습니다.', 'success')
      selectTab('cap')
    },
    onError: (error) => {
      if (mounted.current) toast(contractErrorText(error, '한도 재검증에 실패했습니다.'), 'error')
    },
  })
  const regenerate = useMutation({
    mutationFn: () => regenerateContractSchedules(id, 'CONTRACT_DETAIL_MANUAL'),
    meta: { errorToast: false },
    onSuccess: async () => {
      await invalidateContract()
      if (!mounted.current) return
      toast('예상 스케줄을 새 버전으로 재생성했습니다.', 'success')
      selectTab('schedules')
    },
    onError: (error) => {
      if (mounted.current) toast(contractErrorText(error, '스케줄 재생성에 실패했습니다.'), 'error')
    },
  })
  const busy = recheck.isPending || regenerate.isPending
  const actionDisabled = !canProcess || !detail.isSuccess || busy
  const contract = detail.data

  return (
    <div className="contract-react-detail contract-detail-page">
      <header className="page-header contract-detail-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">보험계약 상세</h1>
        </div>
      </header>
      {!validId ? (
        <ContractLoadState message="계약 식별자를 확인할 수 없습니다." error />
      ) : detail.isError ? (
        <ContractLoadState
          message={contractErrorText(detail.error, '계약 정보를 불러오지 못했습니다.')}
          error
          retry={() => void detail.refetch()}
        />
      ) : !contract ? (
        <ContractLoadState message="계약 정보를 불러오는 중입니다." />
      ) : (
        <>
          <section className="surface contract-summary-card" aria-labelledby="contract-summary-title">
            <div className="contract-summary-primary">
              <div className="contract-summary-identity">
                <p className="contract-summary-label">계약번호</p>
                <div className="contract-summary-title-row">
                  <h2 id="contract-summary-title" className="contract-summary-number">
                    {contract.contractNo || '—'}
                  </h2>
                  <ContractStatusBadge code={contract.contractStatus} label={contract.contractStatusLabel} />
                  <DataOriginBadge code={contract.dataOrigin} label={contract.dataOriginLabel} />
                </div>
              </div>
              <div className="contract-summary-actions" aria-label="계약 작업">
                <Button
                  variant="secondary"
                  disabled={actionDisabled}
                  aria-describedby={!canProcess ? 'contract-action-note' : undefined}
                  onClick={() => void navigate(`/contracts/${id}/edit`)}
                >
                  <span className="material-symbols-rounded" aria-hidden="true">
                    edit
                  </span>
                  수정
                </Button>
                <Button
                  variant="secondary"
                  disabled={actionDisabled}
                  loading={regenerate.isPending}
                  aria-describedby={!canProcess ? 'contract-action-note' : undefined}
                  onClick={() => {
                    if (!actionDisabled) regenerate.mutate()
                  }}
                >
                  {!regenerate.isPending && (
                    <span className="material-symbols-rounded" aria-hidden="true">
                      restart_alt
                    </span>
                  )}
                  스케줄 재생성
                </Button>
                <Button
                  disabled={actionDisabled}
                  loading={recheck.isPending}
                  aria-describedby={!canProcess ? 'contract-action-note' : undefined}
                  onClick={() => {
                    if (!actionDisabled) recheck.mutate()
                  }}
                >
                  {!recheck.isPending && (
                    <span className="material-symbols-rounded" aria-hidden="true">
                      speed
                    </span>
                  )}
                  한도 재검증
                </Button>
              </div>
            </div>
            {!canProcess && (
              <p id="contract-action-note" className="contract-permission-note">
                수정·스케줄 재생성·한도 재검증은 SETTLEMENT 권한이 필요합니다.
              </p>
            )}
            <ContractFacts
              className="contract-summary-grid"
              items={[
                ['보험회사', contract.insurerName],
                ['상품', contract.productName],
                ['계약일', date(contract.contractDate)],
                ['월납환산 초회보험료', won(contract.monthlyEquivalentFirstPremium)],
                ['모집 설계사', contract.agentName],
                ['소속 조직', contract.organizationName],
                ['납입기간', contract.paymentTermMonths == null ? '—' : `${int(contract.paymentTermMonths)}개월`],
              ]}
            />
          </section>
          <ContractTabs id={id} contract={contract} active={active} onSelect={selectTab} agentOnly={isAgent} />
        </>
      )}
    </div>
  )
}
