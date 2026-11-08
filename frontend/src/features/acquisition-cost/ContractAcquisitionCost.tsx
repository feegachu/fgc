import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { QueryState } from '../reference/ReferenceUI'
import { usePermission } from '../auth/useAuth'
import { contractAcquisitionQuery, useRequestAcquisitionCheck } from './api'
import type { AcquisitionRequest } from './api'
import { AcquisitionCostResult, AcquisitionFailure } from './AcquisitionCostResult'
import { ContractAcquisitionTable } from './ContractAcquisitionTable'

/** 계약 상세의 작은 영역(스크럼 4차). 이 계약의 최근 결과만 보이고, 목록과 이력은 ACQC-W01에서 본다. */
export function ContractAcquisitionCost({ contractId, contractNo, concise = false }: { contractId: string; contractNo?: string; concise?: boolean }) {
  return concise ? <>
    <Link data-guide-feature="features.acquisition-cost.contract-acquisition-cost.results" to={`/acquisition-cost-checks?contractNo=${encodeURIComponent(contractNo ?? '')}`}>전체 결과 보기</Link>
    <ContractAcquisitionTable contractId={contractId} />
  </> : <LegacyContractAcquisitionCost contractId={contractId} contractNo={contractNo} />
}

function LegacyContractAcquisitionCost({ contractId, contractNo }: { contractId: string; contractNo?: string }) {
  const canProcess = usePermission('canProcess')
  const [stage, setStage] = useState<AcquisitionRequest['paymentStage']>('GA_TO_FC')
  const query = useQuery(contractAcquisitionQuery(contractId, stage))
  const mutation = useRequestAcquisitionCheck()
  const latest = query.data?.content?.find((row) => row.paymentStage === stage)
  const requestCheck = () => mutation.mutate({ contractId: Number(contractId), paymentStage: stage })
  return (
    <div className="acq-contract">
      <div className="acq-contract-actions">
        <Field variant="filter" label="지급단계">
          <select
            data-guide-feature="features.acquisition-cost.contract-acquisition-cost.select"
            className="select-control"
            value={stage}
            onChange={(event) => {
              setStage(event.target.value as AcquisitionRequest['paymentStage'])
              mutation.reset()
            }}
          >
            <option value="GA_TO_FC">GA→설계사</option>
            <option value="INSURER_TO_GA">보험회사→GA</option>
          </select>
        </Field>
        {canProcess ? (
          <Button
            data-guide-feature="features.acquisition-cost.contract-acquisition-cost.button"
            loading={mutation.isPending}
            disabled={mutation.isPending}
            onClick={requestCheck}
          >
            검증 요청
          </Button>
        ) : (
          <p className="field-helper">검증 요청은 처리 권한이 있는 계정만 할 수 있습니다.</p>
        )}
        <Link
          data-guide-feature="features.acquisition-cost.contract-acquisition-cost.link"
          to={`/acquisition-cost-checks?contractNo=${encodeURIComponent(contractNo ?? '')}`}
        >
          전체 결과 보기
        </Link>
      </div>
      {mutation.error && (
        <AcquisitionFailure error={mutation.error} retry={requestCheck} reload={() => void query.refetch()} />
      )}
      {query.error ? (
        <AcquisitionFailure error={query.error} retry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <QueryState loading />
      ) : latest ? (
        <AcquisitionCostResult check={latest} />
      ) : (
        <p className="empty-state">이 지급단계의 검증 결과가 없습니다.</p>
      )}
    </div>
  )
}
