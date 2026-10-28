/** 대시보드 로딩 자리표시. 스크린리더에는 상태 문구를 그대로 읽어 주고, 눈에는 최종 레이아웃과 같은 뼈대를 보여 준다. */
export function DashboardSkeleton() {
  return (
    <div role="status" className="dashboard-skeleton">
      <span className="visually-hidden">불러오는 중입니다.</span>
      <div className="kpi-grid" aria-hidden="true">
        {Array.from({ length: 6 }, (_, index) => (
          <div key={index} className="skeleton-card">
            <span className="skeleton-line skeleton-line-short" />
            <span className="skeleton-line skeleton-line-value" />
            <span className="skeleton-line skeleton-line-short" />
          </div>
        ))}
      </div>
      <div className="dashboard-main-grid" aria-hidden="true">
        <div className="surface skeleton-panel">
          {Array.from({ length: 5 }, (_, index) => (
            <span key={index} className="skeleton-line" />
          ))}
        </div>
        <div className="surface skeleton-panel">
          <span className="skeleton-circle" />
          {Array.from({ length: 3 }, (_, index) => (
            <span key={index} className="skeleton-line" />
          ))}
        </div>
      </div>
    </div>
  )
}
