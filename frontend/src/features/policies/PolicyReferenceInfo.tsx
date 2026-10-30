import { StatusBadge } from '../../components/StatusBadge'
import { QueryState } from '../reference/ReferenceUI'
import { usePolicyDetail } from './api'
import { safeSourceUrl } from './management'

/**
 * FUN-016 근거 info 패널 본문. 팝오버가 열릴 때만 렌더링되므로 그때 정책 상세를 조회한다.
 * 저장된 상세 설명이 없으면 공통 샘플 문구로 채우지 않고 없다고 표시한다.
 */
export function PolicyReferenceInfo({ policyVersionId, refText }: { policyVersionId: number; refText: string }) {
  const detail = usePolicyDetail(policyVersionId, true)
  if (detail.isLoading || detail.error) {
    return <QueryState loading={detail.isLoading} error={detail.error} />
  }
  const reference = detail.data?.references?.find((row) => row.ref === refText)
  if (!reference) return <p>이 근거의 정보를 찾을 수 없습니다. 다시 조회하세요.</p>
  return (
    <div className="policy-reference-info">
      {reference.registryTitle ? (
        <p className="policy-reference-registry">
          규제조문표 · <strong>{reference.registryTitle}</strong>
        </p>
      ) : null}
      {reference.statusTags?.length ? (
        <p className="policy-reference-tags" aria-label="근거 상태">
          {reference.statusTags.map((tag) => (
            <StatusBadge key={tag} tone="info">
              {tag}
            </StatusBadge>
          ))}
        </p>
      ) : null}
      {reference.detailTitle ? (
        <>
          <p className="policy-reference-title">{reference.detailTitle}</p>
          {reference.explanation ? (
            <p className="policy-reference-explanation">{reference.explanation}</p>
          ) : (
            <p className="policy-reference-empty">설명 자료 없음</p>
          )}
          {safeSourceUrl(reference.url) ? (
            // 이전에 저장된 링크도 관리 상세와 같은 기준(http(s)·사용자 정보 없음)으로만 연다.
            <a href={safeSourceUrl(reference.url)!} target="_blank" rel="noopener noreferrer">
              근거 원문 열기
            </a>
          ) : null}
        </>
      ) : (
        <p className="policy-reference-empty">이 정책버전에 저장된 상세 설명이 없습니다.</p>
      )}
    </div>
  )
}
