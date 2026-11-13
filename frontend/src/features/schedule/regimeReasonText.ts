// 서버 AcquisitionCostCheckService.regimeReason 과 같은 문장이다.
export const REGIME_REASON_TEXT: Record<string, string> = {
  UNKNOWN_REGIME: '판매버전의 수수료 체계 코드를 확인할 수 없습니다.',
  CONTRACT_OUTSIDE_SALES_PERIOD: '계약일이 판매기간 밖입니다.',
  OFFERING_NOT_UNIQUE: '계약일을 포함하는 판매버전이 하나가 아닙니다.',
  NOT_APPLICABLE: '판매개시일과 기초서류가 분급 적용례를 충족하지 않아 적용 대상이 아닙니다.',
  CURRENT_BUT_ELIGIBLE: '적용례를 충족하는데 현행 체계로 등록돼 있습니다.',
  REGIME_NOT_ELIGIBLE: '4년, 7년 체계로 등록됐지만 적용례를 충족하지 않습니다.',
  FOUR_YEAR_AFTER_SPECIAL_PERIOD: '4년 특례 종료 뒤의 판매개시 또는 계약이라 적용 체계를 확정할 수 없습니다.',
  SEVEN_YEAR_IN_SPECIAL_PERIOD: '4년 특례 기간에 7년 체계로 등록돼 있습니다.',
  RULESET_MISSING: '적용할 분급 규칙이 없습니다.',
  RULESET_AMBIGUOUS: '적용할 분급 규칙이 둘 이상입니다.',
  RULESET_FLAGGED: '경계사례로 표시된 규칙이라 자동으로 확정하지 않습니다.',
  RULESET_LIMIT_MISSING: '규칙에 필수 한도율이 없습니다.',
  COMMISSION_RULE_INVALID: '지급 규칙이 분급 규칙 조건과 맞지 않습니다.',
}
