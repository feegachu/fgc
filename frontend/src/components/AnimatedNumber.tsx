import { int } from '../lib/format'
import { useTween } from '../lib/motion'

export function AnimatedNumber({ value, format = int }: { value: number; format?: (value: number) => string }) {
  const [current] = useTween([value], 700, true)
  const displayed = format(current)
  const final = format(value)
  if (displayed === final) return final
  return (
    <>
      <span aria-hidden="true">{displayed}</span>
      <span className="visually-hidden">{final}</span>
    </>
  )
}
