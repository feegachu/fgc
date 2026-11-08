import type { Run } from './api'

export function RunHistoryCard({ run }: { run: Run }) {
  return <span className="reco-history-run"><strong>{run.insurerName ?? '전체 보험회사'}</strong></span>
}
