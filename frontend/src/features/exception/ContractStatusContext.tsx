import { ContractStatusHistory } from '../contract/ContractStatusHistory'
import '../contract/detail.css'

/** 계약 상세와 같은 상태 타임라인을 예외 상세·대사 모달에서도 사용한다. */
export function ContractStatusContext({ contractId }: { contractId: number }) {
  return <div data-guide-feature="features.exception.contract-status" className="exception-contract-status">
    <ContractStatusHistory id={String(contractId)} compact />
  </div>
}
