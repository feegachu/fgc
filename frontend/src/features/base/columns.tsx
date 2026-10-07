import { ReferenceText } from '../reference/ReferenceUI'
import { present } from '../reference/utils'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { date } from '../../lib/format'
import type { Agent, CommissionItem, Insurer, Organization, Product } from './api'
const active = (value?: boolean) => (
  <StatusBadge tone={value ? 'success' : 'neutral'}>{value ? '사용' : '사용중지'}</StatusBadge>
)
export const organizationColumns: TableColumn<Organization>[] = [
  {
    key: 'code',
    label: '조직코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.organizationCode} limit={20} singleLine />,
  },
  {
    key: 'name',
    label: '조직명',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.organizationName} limit={24} singleLine />,
  },
  {
    key: 'type',
    label: '유형',
    render: (r) => (
      <>
        {present(r.organizationTypeLabel)} <span className="base-code-label">{r.organizationType}</span>
      </>
    ),
  },
  {
    key: 'parent',
    label: '상위 조직',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.parentName} limit={24} singleLine />,
  },
  { key: 'from', label: '적용 시작일', render: (r) => date(r.effectiveFrom) },
  { key: 'to', label: '적용 종료일', render: (r) => date(r.effectiveTo) },
  { key: 'active', label: '상태', render: (r) => active(r.activeYn) },
]
export const insurerColumns: TableColumn<Insurer>[] = [
  {
    key: 'code',
    label: '보험회사 코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.insurerCode} limit={20} singleLine />,
  },
  {
    key: 'name',
    label: '보험회사명',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.insurerName} limit={24} singleLine />,
  },
  {
    key: 'type',
    label: '구분',
    render: (r) => (
      <>
        {present(r.insurerTypeLabel)} <span className="base-code-label">{r.insurerType}</span>
      </>
    ),
  },
  { key: 'active', label: '상태', render: (r) => active(r.activeYn) },
]
export const productColumns: TableColumn<Product>[] = [
  {
    key: 'code',
    label: '상품코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.insurerProductCode} limit={20} singleLine />,
  },
  {
    key: 'name',
    label: '상품명',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.productName} limit={28} />,
  },
  {
    key: 'standard',
    label: '표준상품코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.standardProductCode} limit={20} singleLine />,
  },
  { key: 'group', label: '상품군', render: (r) => present(r.productGroupCode) },
  { key: 'version', label: '판매버전', render: (r) => present(r.offeringVersion) },
  {
    key: 'period',
    label: '판매기간',
    render: (r) => `${date(r.salesStartDate)} ~ ${r.salesEndDate ? date(r.salesEndDate) : '현재'}`,
  },
  {
    key: 'document',
    label: '기초서류',
    render: (r) => (
      <>
        {present(r.basicDocumentVersion)}
        <span className="base-secondary-line">{date(r.basicDocumentDate)}</span>
      </>
    ),
  },
  {
    key: 'channel',
    label: '채널',
    render: (r) => (
      <>
        {present(r.channelCode)} {r.channelSpecialRuleYn && <span className="base-secondary-line">채널 특례</span>}
      </>
    ),
  },
  { key: 'fee', label: '수수료체계', render: (r) => present(r.feeRegimeCode) },
  {
    key: 'deduction',
    label: '80% 공제',
    render: (r) => (
      <StatusBadge tone={r.standardDeduction80Yn ? 'warning' : 'neutral'}>
        {r.standardDeduction80Yn ? '예' : '아니오'}
      </StatusBadge>
    ),
  },
]
export const agentColumns: TableColumn<Agent>[] = [
  {
    key: 'code',
    label: '설계사 코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.agentCode} limit={20} singleLine />,
  },
  { key: 'name', label: '설계사명', render: (r) => present(r.agentName) },
  {
    key: 'rank',
    label: '직급',
    render: (r) => (
      <>
        {present(r.rankLabel)} <span className="base-code-label">{r.rankCode}</span>
      </>
    ),
  },
  {
    key: 'org',
    label: '소속 조직',
    cellClassName: 'base-disclosure-cell',
    render: (r) => (
      <>
        <ReferenceText value={r.organizationName} limit={24} singleLine />
        <span className="base-secondary-line">{r.organizationCode}</span>
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
        {!r.activeYn && <span className="base-secondary-line">기준정보 사용중지</span>}
      </>
    ),
  },
  { key: 'registration', label: '최근 등록일', render: (r) => date(r.latestRegistrationDate) },
  {
    key: 'experience',
    label: '직전 3년 경력',
    render: (r) => (r.priorThreeYearExperienceYn == null ? '확인 전' : r.priorThreeYearExperienceYn ? '예' : '아니오'),
  },
  {
    key: 'support',
    label: '신인지원',
    render: (r) => (
      <>
        <StatusBadge tone={r.newcomerSupportEligibleYn ? 'warning' : 'neutral'}>
          {r.newcomerSupportEligibleYn ? '대상' : '비대상'}
        </StatusBadge>
        {r.newcomerSupportEndDate && <span className="base-secondary-line">{date(r.newcomerSupportEndDate)}까지</span>}
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
  {
    key: 'code',
    label: '항목코드',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.itemCode} limit={18} singleLine />,
  },
  {
    key: 'name',
    label: '항목명',
    cellClassName: 'base-disclosure-cell',
    render: (r) => <ReferenceText value={r.itemName} limit={28} />,
  },
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
  { key: 'from', label: '적용 시작일', render: (r) => date(r.effectiveFrom) },
  { key: 'to', label: '적용 종료일', render: (r) => date(r.effectiveTo) },
]
