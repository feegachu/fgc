import { useContext, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { DisabledButtonHint } from '../../components/DisabledButtonHint'
import { ClawbackActionPlacement } from './actionPlacement'
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import { ListPagination } from '../../components/ListPagination'
import { ApiError } from '../../lib/api/client'
import { dateTime, int } from '../../lib/format'
import { QueryState } from '../reference/ReferenceUI'
import { Amount } from './badges'
import { downloadNotice, issueNotice, noticeHistoryKey, noticeHistoryQuery } from './api'
import type { CandidateDetail, NoticeDocument } from './api'

const writers = ['SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN']
const fieldLabels: Record<string, string> = {
  calculationVersion: '대상 계산판',
  expectedSourceVersion: '현재 원천 버전',
  policyVersionId: '계산 정책',
}
type Props = {
  detail: CandidateDetail
  roleCode?: string
  refreshing: boolean
  onRefresh: () => Promise<unknown>
}
const isStatus = (error: unknown, status: number) => error instanceof ApiError && error.status === status

/** IF84의 최신 계산판을 발행 대상으로 표시하고 과거 발행본은 IF86에서 조회한다. */
export function ClawbackDocuments(props: Props) {
  if (!props.roleCode || ![...writers, 'COMPLIANCE'].includes(props.roleCode)) return null
  return <NoticePanel {...props} />
}
function NoticePanel({ detail, roleCode, refreshing, onRefresh }: Props) {
  const placement = useContext(ClawbackActionPlacement)
  const candidate = detail.candidate
  const calculation = detail.latestCalculation
  const caseId = candidate?.clawbackCaseId ?? calculation?.clawbackCaseId
  const [page, setPage] = useState(1)
  const client = useQueryClient()
  const history = useQuery({ ...noticeHistoryQuery(caseId ?? 0, page), enabled: caseId != null })
  const mutationKey = ['clawback', 'issue-notice', caseId]
  const issuing = useIsMutating({ mutationKey }) > 0
  const actionLock = useRef(false)
  const request = {
    calculationVersion: calculation?.calculationVersion ?? 0,
    expectedSourceVersion: candidate?.sourceVersion ?? '',
  }
  // 로그아웃 시 비워지는 공통 캐시에 응답 유실 시도의 키를 보존한다. 선택·필터 변경에도 같은 시도를 재사용한다.
  const attemptKey = ['clawback', 'notice-attempt', caseId, request.calculationVersion, request.expectedSourceVersion]
  const issue = useMutation({
    mutationKey,
    retry: false,
    mutationFn: async () => {
      const key = client.getQueryData<string>(attemptKey) ?? crypto.randomUUID()
      client.setQueryDefaults(['clawback', 'notice-attempt'], { gcTime: Infinity })
      client.setQueryData(attemptKey, key)
      const result = await issueNotice(caseId!, request, key)
      client.removeQueries({ queryKey: attemptKey, exact: true })
      return result
    },
    onSuccess: () => {
      setPage(1)
      void client.invalidateQueries({ queryKey: noticeHistoryKey(caseId!) })
    },
    meta: { errorToast: false },
  })
  const [downloadError, setDownloadError] = useState<Error | null>(null)
  const [downloading, setDownloading] = useState<string | null>(null)
  const downloadLock = useRef(false)
  const denied = [history.error, issue.error, downloadError].some((error) => isStatus(error, 403))
  const conflict = isStatus(issue.error, 409) && (issue.error as ApiError).code !== 'FGC-IMPL-REQUEST_IN_PROGRESS'
  const current = Boolean(
    caseId &&
    calculation?.calculationVersion &&
    candidate?.sourceVersion &&
    calculation.sourceVersion === candidate.sourceVersion &&
    candidate.calculationStatus === 'CALCULATED',
  )
  // canProcess는 SETTLEMENT·SYSTEM_ADMIN용 플래그다. IF73은 GA_ADMIN도 발행할 수 있다.
  const canIssue = writers.includes(roleCode ?? '') && !denied
  const hasAttempt = Boolean(client.getQueryData(attemptKey))
  const blocked = !current || conflict || refreshing || history.isFetching || Boolean(history.error) || issuing
  async function generate() {
    if (actionLock.current || blocked || !canIssue) return
    actionLock.current = true
    try {
      const issued = await issue.mutateAsync()
      if (placement.revised) await download(issued)
    } catch {
      /* 화면에 실패를 표시하고 같은 시도를 재시도하도록 멱등키를 보존한다. */
    } finally {
      actionLock.current = false
    }
  }
  async function download(row: NoticeDocument) {
    if (downloadLock.current || denied || refreshing) return
    downloadLock.current = true
    setDownloading(`${row.documentNo}:${row.version}`)
    setDownloadError(null)
    try {
      await downloadNotice(row)
    } catch (error) {
      setDownloadError(error instanceof Error ? error : new Error('다운로드에 실패했습니다.'))
    } finally {
      downloadLock.current = false
      setDownloading(null)
    }
  }
  async function refresh() {
    await Promise.all([onRefresh(), caseId != null ? history.refetch() : Promise.resolve()])
    issue.reset()
    setDownloadError(null)
  }
  const issueReason = !canIssue ? 'PDF 발행 권한이 없습니다.' : issuing || downloading ? 'PDF를 준비하고 있습니다.'
    : !calculation ? '환수액을 먼저 계산하세요.' : !current ? '현재 원천으로 환수액을 다시 계산하세요.'
      : conflict ? '최신 근거를 다시 조회한 뒤 발행하세요.' : history.error ? '발행 이력을 다시 조회하세요.'
        : '최신 발행 조건을 확인하고 있습니다.'
  const issueButton = canIssue && <DisabledButtonHint disabled={blocked || !!downloading} reason={issueReason}>
    <Button data-guide-feature="clawback.documents.issue" loading={issuing || !!downloading} disabled={blocked || !!downloading} onClick={() => void generate()}>
      {placement.revised ? issuing || downloading ? 'PDF 준비 중…' : 'PDF 출력' : issuing ? 'PDF 생성 중…'
        : hasAttempt ? '발행 재시도' : history.data?.totalElements || issue.data ? '재발행' : 'PDF 생성'}
    </Button>
  </DisabledButtonHint>
  return (
    <section
      data-guide-feature="clawback.documents"
      className="claw-evidence claw-documents"
      aria-label="환수 PDF·발행 이력"
    >
      <h3 className="claw-evidence-title">환수 PDF·발행 이력</h3>
      <dl className="claw-detail-list">
        <div>
          <dt>발행 대상</dt>
          <dd>
            {candidate?.contractNo ?? '자료 없음'} · {candidate?.agentName ?? '자료 없음'}
          </dd>
        </div>
        <div>
          <dt>대상 계산판</dt>
          <dd>
            {calculation ? `v${int(calculation.calculationVersion)}` : '미계산'}
            {calculation && !current ? ' (현재 원천과 다른 계산판)' : ''}
          </dd>
        </div>
        <div>
          <dt>계산 원천 버전</dt>
          <dd>{calculation?.sourceVersion ?? '자료 없음'}</dd>
        </div>
        <div>
          <dt>현재 원천 버전</dt>
          <dd>{candidate?.sourceVersion ?? '자료 없음'}</dd>
        </div>
        <div>
          <dt>계산액 · 계산일</dt>
          <dd>
            {calculation?.amount == null ? '자료 없음' : <Amount value={calculation.amount} />} ·{' '}
            {dateTime(calculation?.calculatedAt)}
          </dd>
        </div>
      </dl>
      <p className="claw-evidence-empty">
        내부 검토용 가상 양식입니다. PDF 발행·다운로드는 실제 통지 완료를 뜻하지 않으며 환수 상태를 변경하지 않습니다.
      </p>
      {!calculation ? (
        <p className="claw-evidence-empty">환수액 계산 후 PDF를 생성할 수 있습니다.</p>
      ) : !current ? (
        <p role="status" className="claw-evidence-empty">
          원천이 변경되었거나 현재 발행 조건을 충족하지 않습니다. 다시 조회하고 최신 근거로 계산해 주세요.
        </p>
      ) : null}
      <div className="claw-calculation-actions">
        {placement.revised ? placement.document && createPortal(issueButton, placement.document) : issueButton}
        <Button
          data-guide-feature="clawback.documents.refresh"
          variant="secondary"
          disabled={refreshing || history.isFetching || issuing}
          onClick={() => void refresh()}
        >
          다시 조회
        </Button>
        {roleCode === 'COMPLIANCE' && <p className="field-helper">발행 이력 조회·다운로드 권한입니다.</p>}
      </div>
      {issue.isError && (
        <div className="claw-document-message" role="alert">
          <p>
            {isStatus(issue.error, 403)
              ? 'PDF 발행 권한이 없습니다.'
              : conflict
                ? '현재 계산판·원천과 발행 요청이 다릅니다. 다시 조회해 주세요.'
                : isStatus(issue.error, 422)
                  ? '발행에 필요한 계산·정책 근거를 확인해 주세요.'
                  : 'PDF 발행에 실패했습니다. 응답 유실 시 같은 요청으로 재시도합니다.'}
          </p>
          <QueryState error={issue.error} />
          {issue.error instanceof ApiError && issue.error.field && (
            <p>확인 항목: {fieldLabels[issue.error.field] ?? issue.error.field}</p>
          )}
        </div>
      )}
      {issue.data && (
        <p role="status" className="claw-evidence-empty">
          PDF 발행 완료: {issue.data.documentNo} · 발행판 v{issue.data.version} · 계산판 v
          {issue.data.calculationVersion}
        </p>
      )}
      <h4 className="claw-evidence-title">발행 이력</h4>
      {history.isFetching && (
        <p role="status" className="claw-evidence-empty">
          발행 이력 불러오는 중…
        </p>
      )}
      {history.error ? (
        <div className="claw-document-message">
          <QueryState error={history.error} />
        </div>
      ) : denied ? (
        <p role="alert" className="claw-evidence-empty">
          문서를 조회·다운로드할 권한이 없습니다.
        </p>
      ) : history.data?.content?.length ? (
        <DataTable
          caption="환수 PDF 발행 이력"
          viewportClassName="claw-evidence-table"
          rows={history.data.content}
          rowKey={(row) => `${row.documentNo}:${row.version}`}
          columns={[
            { key: 'no', label: '문서번호', render: (row) => row.documentNo ?? '자료 없음' },
            { key: 'edition', label: '발행판', render: (row) => `v${int(row.version)}` },
            { key: 'calculation', label: '계산판', render: (row) => `v${int(row.calculationVersion)}` },
            { key: 'by', label: '발행자 번호', render: (row) => row.createdBy ?? '자료 없음' },
            { key: 'at', label: '발행일시', render: (row) => dateTime(row.createdAt) },
            {
              key: 'download',
              label: '다운로드',
              render: (row) => (
                <Button
                  data-guide-feature="clawback.documents.download"
                  variant="secondary"
                  aria-label={`발행판 v${row.version} 다운로드`}
                  disabled={refreshing || Boolean(downloading)}
                  loading={downloading === `${row.documentNo}:${row.version}`}
                  onClick={() => void download(row)}
                >
                  다운로드
                </Button>
              ),
            },
          ]}
        />
      ) : (
        !history.isFetching && <p className="claw-evidence-empty">발행된 PDF가 없습니다.</p>
      )}
      {!history.error && !denied && (
        <ListPagination
          label="PDF 발행 이력 페이지"
          page={history.data?.page ?? page}
          totalPages={history.data?.totalPages ?? 0}
          onPageChange={setPage}
        />
      )}
      {downloadError && (
        <div className="claw-document-message" role="alert">
          <p>
            {isStatus(downloadError, 404)
              ? '요청한 발행판의 파일이 없습니다. 발행 이력을 다시 조회해 주세요.'
              : isStatus(downloadError, 403)
                ? 'PDF 다운로드 권한이 없습니다.'
                : 'PDF 다운로드에 실패했습니다. 파일은 저장하지 않았습니다.'}
          </p>
          <QueryState error={downloadError} />
        </div>
      )}
    </section>
  )
}
