import { int } from '../lib/format'
import { useCountUp } from '../lib/motion'

/** 숫자 카운트업. 값이 바뀌면 이전 값에서 이어서 올라간다. 접근 가능한 이름은 부모(링크 aria-label 등)가 최종값을 갖는다. */
export function AnimatedNumber({ value, format = int }: { value: number; format?: (n: number) => string }) {
  return <>{format(useCountUp(value))}</>
}
