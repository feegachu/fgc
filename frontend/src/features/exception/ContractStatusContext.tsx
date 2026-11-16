import { useQuery } from '@tanstack/react-query'
import { ContractStatusHistory } from '../contract/ContractStatusHistory'
import '../contract/detail.css'
import { contractEventsQuery } from '../contract/api'

/** 계약 상세와 같은 상태 타임라인을 예외 상세·대사 모달에서도 사용한다. */
export function ContractStatusContext({ contractId }: { contractId: number }) {
  const events = useQuery({ ...contractEventsQuery(String(contractId)), staleTime: 30_000 })
  return <div data-guide-feature="features.exception.contract-status" className="exception-contract-status">
    {events.isPending ? <p role="status">상태 이력을 불러오는 중입니다.</p>
      : events.isError || !Array.isArray(events.data) ? <p className="text-secondary">계약 상태 이력을 불러오지 못했습니다.</p>
        : <ContractStatusHistory id={String(contractId)} compact />}
  </div>
}
