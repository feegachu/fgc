import type { ReactNode } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { contractErrorText } from './detailState'

export function ContractLoadState({ message, error, retry }: { message: string; error?: boolean; retry?: () => void }) {
  return (
    <div className={`contract-detail-state ${error ? 'is-error' : ''}`} role={error ? 'alert' : 'status'}>
      <p>{message}</p>
      {retry && (
        <Button data-guide-feature="features.contract.contract-tabs.button" variant="secondary" onClick={retry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}

export function ContractFacts({ items, className = '' }: { items: [string, ReactNode][]; className?: string }) {
  return (
    <dl className={className || 'contract-detail-facts'}>
      {items.map(([label, value]) => (
        <div
          key={label}
          className={
            className === 'contract-information-grid'
              ? 'contract-information-item'
              : className === 'contract-summary-grid'
                ? 'contract-summary-item'
                : undefined
          }
        >
          <dt>{label}</dt>
          <dd>{value ?? '-'}</dd>
        </div>
      ))}
    </dl>
  )
}

export function QueryContent<T>({
  query,
  children,
}: {
  query: UseQueryResult<T, Error>
  children: (data: T) => ReactNode
}) {
  if (query.isError)
    return (
      <ContractLoadState
        message={contractErrorText(query.error, '자료를 불러오지 못했습니다.')}
        error
        retry={() => void query.refetch()}
      />
    )
  if (!query.isSuccess) return <ContractLoadState message="불러오는 중입니다." />
  return children(query.data)
}
