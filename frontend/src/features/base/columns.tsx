import { present } from '../reference/utils'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { date } from '../../lib/format'
import type { Agent, CommissionItem, Insurer, Organization, Product } from './api'
const active = (value?: boolean) => (
  <StatusBadge tone={value ? 'success' : 'neutral'}>{value ? '사용' : '사용중지'}</StatusBadge>
)
export const organizationColumns: TableColumn<Organization>[] = [
  { key: 'code', label: '조직코드', render: (r) => present(r.organizationCode) },
  { key: 'name', label: '조직명', render: (r) => present(r.organizationName) },
  {
    key: 'type',
    label: '조직유형',
    render: (r) => (
      <>
        {present(r.organizationTypeLabel)} <small>{r.organizationType}</small>
      </>
    ),
  },
  { key: 'parent', label: '상위조직', render: (r) => present(r.parentName) },
  { key: 'from', label: '적용시작일', render: (r) => date(r.effectiveFrom) },
  { key: 'to', label: '적용종료일', render: (r) => date(r.effectiveTo) },
  { key: 'active', label: '사용여부', render: (r) => active(r.activeYn) },
]
export const insurerColumns: TableColumn<Insurer>[] = [
  { key: 'code', label: '보험회사코드', render: (r) => present(r.insurerCode) },
  { key: 'name', label: '보험회사명', render: (r) => present(r.insurerName) },
  {
    key: 'type',
    label: '보험유형',
    render: (r) => (
      <>
        {present(r.insurerTypeLabel)} <small>{r.insurerType}</small>
      </>
    ),
  },
  { key: 'active', label: '사용여부', render: (r) => active(r.activeYn) },
]
export const productColumns: TableColumn<Product>[] = [
  { key: 'code', label: '보험회사 상품코드', render: (r) => present(r.insurerProductCode) },
  { key: 'name', label: '상품명', render: (r) => present(r.productName) },
  { key: 'standard', label: '표준상품코드', render: (r) => present(r.standardProductCode) },
  { key: 'group', label: '상품군', render: (r) => present(r.productGroupCode) },
  { key: 'version', label: '판매버전', render: (r) => present(r.offeringVersion) },
  {
    key: 'period',
    label: '판매기간',
    render: (r) => `${date(r.salesStartDate)} ~ ${r.salesEndDate ? date(r.salesEndDate) : '계속'}`,
  },
  {
    key: 'document',
    label: '기초서류',
    render: (r) => (
      <>
        {present(r.basicDocumentVersion)}
        <br />
        {date(r.basicDocumentDate)}
      </>
    ),
  },
  {
    key: 'channel',
    label: '판매채널',
    render: (r) => (
      <>
        {present(r.channelCode)} {r.channelSpecialRuleYn && <StatusBadge tone="warning">채널특례</StatusBadge>}
      </>
    ),
  },
  { key: 'fee', label: '수수료체계', render: (r) => present(r.feeRegimeCode) },
  {
    key: 'deduction',
    label: '표준해약공제 80%',
    render: (r) => (
      <StatusBadge tone={r.standardDeduction80Yn ? 'warning' : 'neutral'}>
        {r.standardDeduction80Yn ? '예' : '아니오'}
      </StatusBadge>
    ),
  },
]
export const agentColumns: TableColumn<Agent>[] = [
  { key: 'code', label: '설계사코드', render: (r) => present(r.agentCode) },
  { key: 'name', label: '설계사명', render: (r) => present(r.agentName) },
  {
    key: 'rank',
    label: '직급',
    render: (r) => (
      <>
        {present(r.rankLabel)} <small>{r.rankCode}</small>
      </>
    ),
  },
  {
    key: 'org',
    label: '소속조직',
    render: (r) => (
      <>
        {present(r.organizationName)}
        <br />
        <small>{r.organizationCode}</small>
      </>
    ),
  },
  { key: 'from', label: '위촉일', render: (r) => date(r.appointmentDate) },
  { key: 'to', label: '해촉일', render: (r) => date(r.terminationDate) },
  {
    key: 'status',
    label: '상태',
    render: (r) => (
      <>
        <StatusBadge tone={r.agentStatus === 'ACTIVE' ? 'success' : 'neutral'}>
          {present(r.agentStatusLabel)}
        </StatusBadge>
        {!r.activeYn && <StatusBadge>사용중지</StatusBadge>}
      </>
    ),
  },
  { key: 'registration', label: '최근 등록일', render: (r) => date(r.latestRegistrationDate) },
  {
    key: 'experience',
    label: '최근 3년 경력',
    render: (r) => (r.priorThreeYearExperienceYn == null ? '확인 전' : r.priorThreeYearExperienceYn ? '예' : '아니오'),
  },
  {
    key: 'support',
    label: '신인 지원 대상',
    render: (r) => (
      <>
        <StatusBadge tone={r.newcomerSupportEligibleYn ? 'warning' : 'neutral'}>
          {r.newcomerSupportEligibleYn ? '대상' : '비대상'}
        </StatusBadge>
        {r.newcomerSupportEndDate && (
          <>
            <br />
            {date(r.newcomerSupportEndDate)}까지
          </>
        )}
      </>
    ),
  },
]
// Display labels carried forward from base-list.js; these are not calculation rules.
const categories: Record<string, string> = {
  SALES: '모집수수료',
  MAINTENANCE: '유지관리',
  INCENTIVE: '판매촉진',
  MANAGEMENT: '관리자수수료',
  SUPPORT: '지원',
  COST: '공통비',
  ADJUSTMENT: '조정',
  CLAWBACK: '환수',
  RECOVERY: '회수',
}
export const commissionColumns: TableColumn<CommissionItem>[] = [
  { key: 'code', label: '항목코드', render: (r) => present(r.itemCode) },
  { key: 'name', label: '항목명', render: (r) => present(r.itemName) },
  {
    key: 'cashflow',
    label: '지급/차감',
    render: (r) => (
      <StatusBadge tone={r.cashflowType === 'PAYMENT' ? 'success' : 'warning'}>
        {r.cashflowType === 'PAYMENT' ? '지급' : r.cashflowType === 'DEDUCTION' ? '차감' : '-'}
      </StatusBadge>
    ),
  },
  {
    key: 'category',
    label: '분류',
    render: (r) =>
      r.itemCode === 'SETTLEMENT_SUPPORT'
        ? '정착지원'
        : r.itemCode === 'NEWCOMER_SUPPORT'
          ? '신인지원'
          : (categories[r.itemCategory ?? ''] ?? present(r.itemCategory)),
  },
  { key: 'from', label: '사용시작일', render: (r) => date(r.effectiveFrom) },
  { key: 'to', label: '사용종료일', render: (r) => date(r.effectiveTo) },
]
