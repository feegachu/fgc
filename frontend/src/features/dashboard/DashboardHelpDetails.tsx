import overview from './assets/dashboard-help-overview.png'

const areas = [
  {
    title: '주요 검증 지표',
    description: '기준 정산월의 한도·차익거래·대사 결과를 보여 줍니다. 미처리 예외와 원장 불균형은 전체 기간 기준입니다. 카드를 누르면 관련 목록이 열립니다.',
    x: '1.8%', y: '6.9%',
  },
  {
    title: '월별 수수료',
    description: '확정 지급 기준의 GA 수령액과 설계사 지급액을 확인합니다. 최근 6개월 추이와 전월 대비 증감을 함께 보여 줍니다.',
    x: '1.8%', y: '22.2%',
  },
  {
    title: '검증 항목별 건수 비중',
    description: '검증 항목별 건수의 비중을 보여 줍니다. 다른 항목과 겹칠 수 있는 미처리 예외는 비중 계산에서 제외합니다.',
    x: '1.8%', y: '62.7%',
  },
  {
    title: '지점별 현황',
    description: '수수료·설계사·계약·예외 중 지표를 골라 지점을 비교합니다. 지점의 막대를 누르면 상세 내역을 확인할 수 있습니다.',
    x: '39.1%', y: '62.7%',
  },
  {
    title: '최근 예외',
    description: '최근 접수된 예외의 심각도와 상태를 확인합니다. 제목을 누르면 예외함이 열립니다.',
    x: '76.4%', y: '22.2%',
  },
  {
    title: '최근 통합검증 실행',
    description: '최근 월간 검증의 회차, 진행 단계, 상태를 확인합니다. 실행을 누르면 해당 회차의 상세로 이동합니다.',
    x: '76.4%', y: '45.0%',
  },
  {
    title: '전체 업무 가이드',
    description: '계약부터 정산 최종 확정까지 실제 업무를 단계별로 따라가는 가이드입니다.',
    x: '76.4%', y: '63.3%',
  },
  {
    title: '보험GA협회 바로가기',
    description: '보험GA협회 홈페이지를 새 탭에서 열어 업계 소식과 제도 안내를 확인합니다.',
    x: '87.5%', y: '63.3%',
  },
]

export function DashboardHelpDetails() {
  return (
    <div className="screen-help-overview" role="region" aria-label="화면 예시와 설명" tabIndex={0}>
      <figure>
        <div className="screen-help-figure">
          <img src={overview} alt="업무 대시보드 화면 예시" width="1608" height="834" />
          {areas.map((area, index) => (
            <span key={area.title} aria-hidden="true" className="screen-help-number" style={{ left: area.x, top: area.y }}>
              {index + 1}
            </span>
          ))}
        </div>
        <figcaption>화면 예시입니다. 집계 수치는 조회 시점에 따라 달라집니다.</figcaption>
      </figure>
      <ol className="screen-help-legend" aria-label="번호별 화면 설명">
        {areas.map((area, index) => (
          <li key={area.title}>
            <span aria-hidden="true" className="screen-help-number">{index + 1}</span>
            <div>
              <h3>{area.title}</h3>
              <p>{area.description}</p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  )
}
