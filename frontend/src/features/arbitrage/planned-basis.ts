export const UPPER_BOUND_NOTE = '지급예정액 상한은 연결이 불명확한 지급을 차감하지 않은 금액입니다. 자료 확인 전에는 확정된 잔여액으로 해석하지 마세요.'

/** 저장된 기준만 설명한다. 금액·판정이나 과거 검증 결과를 재계산하지 않는다. */
export function plannedBasisPresentation(basis: string | null | undefined) {
  switch (basis) {
    case 'REMAINING_V1':
      return {
        label: '잔여 지급예정액',
        description: '같은 지급 의무의 유효 확정 지급액을 차감한 잔여 예정액입니다.',
      }
    case 'UNRESOLVED_UPPER_BOUND_V1':
      return {
        label: '지급예정액 상한',
        description: '연결이 불명확한 지급은 차감하지 않은 상한입니다. 자료 확인 전에는 확정된 잔여액으로 해석하지 마세요.',
      }
    case null:
    case undefined:
      return {
        label: '지급예정액 (기준 미기록)',
        description: '계산 기준이 기록되지 않은 과거 결과입니다. 현재 잔여액으로 해석하지 말고 당시 검증 근거를 확인하세요.',
      }
    default:
      return {
        label: '지급예정액 (기준 확인 필요)',
        description: '이 화면에서 해석할 수 없는 계산 기준입니다. 원 검증 근거를 확인하세요.',
      }
  }
}
