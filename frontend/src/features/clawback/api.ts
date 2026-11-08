import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { formatSort, parseSort } from '../../lib/tableSort'
type Schemas = components['schemas']
export type CandidateRow = Schemas['ClawbackCandidateResponse']
export type CandidateDetail = Schemas['ClawbackCandidateDetailResponse']
export type CandidatePage = Schemas['PageResponseClawbackCandidateResponse']
export type Calculation = Schemas['Calculation']
export type CalculationRequest = Required<Schemas['ClawbackCalculationRequest']>
export type NoticeDocument = Schemas['ClawbackNoticeDocumentResponse']
export type NoticeRequest = Schemas['ClawbackNoticeDocumentRequest']
export const noticeHistoryKey = (caseId: number) => ['clawback', 'notice-documents', caseId] as const
export const noticeHistoryQuery = (caseId: number, page: number) =>
  queryOptions({
    queryKey: [...noticeHistoryKey(caseId), page],
    queryFn: async ({ signal }) => {
      const { data } = await apiClient.request<Schemas['PageResponseClawbackNoticeDocumentResponse']>(
        `/api/v1/clawbacks/${caseId}/notice-documents?page=${page}&size=20`,
        { signal },
      )
      if (
        !data ||
        !Array.isArray(data.content) ||
        data.totalElements == null ||
        data.totalPages == null ||
        data.page == null
      ) {
        throw new Error('발행 이력 응답을 확인할 수 없습니다. 다시 조회해 주세요.')
      }
      return data
    },
    meta: { errorToast: false },
  })
export async function issueNotice(caseId: number, body: NoticeRequest, idempotencyKey: string) {
  const { data } = await apiClient.request<NoticeDocument>(`/api/v1/clawbacks/${caseId}/notice-documents`, {
    method: 'POST',
    body,
    idempotencyKey,
  })
  if (
    !data?.documentNo ||
    !Number.isSafeInteger(data.version) ||
    data.version! < 1 ||
    data.calculationVersion !== body.calculationVersion ||
    !data.downloadPath
  ) {
    throw new Error('PDF 발행 응답을 확인할 수 없습니다. 같은 요청으로 재시도해 주세요.')
  }
  return data
}
export async function readNoticePdf(edition: NoticeDocument) {
  const { documentNo, version, downloadPath } = edition
  if (!documentNo || !/^[A-Za-z0-9_-]+$/.test(documentNo) || !Number.isSafeInteger(version) || version! < 1) {
    throw new Error('다운로드할 문서번호와 발행본을 확인할 수 없습니다.')
  }
  const expectedPath = `/api/v1/documents/${encodeURIComponent(documentNo)}.pdf?version=${version}`
  if (downloadPath !== expectedPath) throw new Error('문서의 다운로드 경로와 발행본이 일치하지 않습니다.')
  const { blob } = await apiClient.requestPdf(expectedPath)
  return { blob, filename: `${documentNo}-v${version}.pdf` }
}
export async function downloadNotice(edition: NoticeDocument) {
  const { blob, filename } = await readNoticePdf(edition)
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  try {
    anchor.href = url
    anchor.download = filename
    document.body.append(anchor)
    anchor.click()
  } finally {
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}
/** 서버(ClawbackCandidateQueryRepository.SORTABLE)와 같은 정렬 key. */
export const CLAWBACK_SORT_KEYS = [
  'candidateId',
  'contractNo',
  'eventDate',
  'contractDate',
  'paidAmount',
  'unpaidAmount',
] as const

export interface Criteria {
  dateBasis: 'EVENT_DATE' | 'CONTRACT_DATE'
  dateFrom: string
  dateTo: string
  contractNo: string
  insurerId: string
  productOfferingId: string
  agentId: string
  triggerType: string
  calculationStatus: string
  /** 비어 있으면 서버 기본(후보 ID 역순) */
  sort: string
  page: number
}
/** 기간 기본값은 작업월의 첫날~마지막날(양 끝 포함)이다. */
export function monthRange(month: string) {
  if (!/^\d{4}-\d{2}$/.test(month)) return { dateFrom: '', dateTo: '' }
  const [year, monthNo] = month.split('-').map(Number)
  const last = new Date(Date.UTC(year, monthNo, 0)).getUTCDate()
  return { dateFrom: `${month}-01`, dateTo: `${month}-${String(last).padStart(2, '0')}` }
}
export function defaultCriteria(month: string): Criteria {
  return {
    dateBasis: 'EVENT_DATE',
    ...monthRange(month),
    contractNo: '',
    insurerId: '',
    productOfferingId: '',
    agentId: '',
    triggerType: '',
    calculationStatus: '',
    sort: '',
    page: 1,
  }
}
export function criteriaFromParams(params: URLSearchParams, month: string): Criteria {
  const base = defaultCriteria(month)
  const raw = Number(params.get('page') ?? 1)
  // URL에 키가 없으면 기본값(작업월)이고, `dateFrom=`처럼 빈 값으로 있으면 사용자가 비운 것(제한 없음)이다.
  const text = (key: keyof Criteria) => params.get(key)?.trim() ?? (base[key] as string)
  return {
    dateBasis: params.get('dateBasis') === 'CONTRACT_DATE' ? 'CONTRACT_DATE' : 'EVENT_DATE',
    dateFrom: text('dateFrom'),
    dateTo: text('dateTo'),
    contractNo: text('contractNo'),
    insurerId: text('insurerId'),
    productOfferingId: text('productOfferingId'),
    agentId: text('agentId'),
    triggerType: text('triggerType'),
    calculationStatus: text('calculationStatus'),
    sort: formatSort(parseSort(params.get('sort'), CLAWBACK_SORT_KEYS)),
    page: Number.isSafeInteger(raw) && raw > 0 ? raw : 1,
  }
}
async function search(criteria: Criteria, signal?: AbortSignal) {
  const query = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== '') query.set(key, String(value))
  })
  query.set('size', '20')
  return (await apiClient.request<CandidatePage>(`/api/v1/clawbacks/candidates?${query}`, { signal })).data!
}
export const candidateListQuery = (criteria: Criteria) =>
  queryOptions({
    queryKey: ['clawback', 'candidates', criteria],
    queryFn: ({ signal }) => search(criteria, signal),
    meta: { errorToast: false },
  })
export const candidateDetailQuery = (candidateId?: number) =>
  queryOptions({
    queryKey: ['clawback', 'candidate', candidateId],
    enabled: candidateId != null,
    queryFn: async ({ signal }) =>
      (await apiClient.request<CandidateDetail>(`/api/v1/clawbacks/candidates/${candidateId}`, { signal })).data!,
    meta: { errorToast: false },
  })
/** IF-API-85. 금액과 환수율은 보내지 않고 서버가 잠근 원천, 선택한 정책판, 사유로만 계산한다. */
export async function calculateCandidate(candidateId: number, body: CalculationRequest, idempotencyKey: string) {
  return (
    await apiClient.request<Calculation>(`/api/v1/clawbacks/candidates/${candidateId}/calculations`, {
      method: 'POST',
      body,
      idempotencyKey,
    })
  ).data!
}
