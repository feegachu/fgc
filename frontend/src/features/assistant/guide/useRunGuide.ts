import { useEffect } from 'react'
import type { SaveGuide } from './save-guide'

/** Observe existing server queries; accepting a request is never completion. */
export function useRunGuide(guide: SaveGuide, context: string, valid: boolean, id: string, status?: string, error = '') {
  useEffect(() => {
    // Status/error are part of the revision: a failed refresh must invalidate old success.
    guide.update(JSON.stringify([context, id, status, error]), valid)
    guide.fail('')
    if (error || status === 'FAILED' || status === 'STOPPED') {
      guide.fail(error || '실행이 실패했습니다. 오류를 확인하고 신규 실행 또는 재시도 절차를 따르세요.')
    } else if (status === 'RUNNING') guide.begin()
    else if (valid && id && (status === 'COMPLETED' || status === 'FINALIZED')) {
      guide.begin()
      void guide.saved(id).catch(error => guide.fail(String(error)))
    }
  }, [guide, context, valid, id, status, error])
}
