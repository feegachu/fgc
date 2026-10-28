export const RADIUS = 42
export const CIRCUMFERENCE = 2 * Math.PI * RADIUS
const SEGMENT_GAP = 1.5

/** 조각 길이와 시작 위치(원주 기준). 조각이 하나뿐이면 틈을 두지 않는다. */
export function donutSegments(items: { key: string; value: number }[]) {
  const positive = items.filter((item) => item.value > 0)
  const total = positive.reduce((sum, item) => sum + item.value, 0)
  const gap = positive.length > 1 ? SEGMENT_GAP : 0
  let offset = 0
  return positive.map((item) => {
    const share = item.value / total
    const length = Math.max(0, CIRCUMFERENCE * share - gap)
    const segment = { key: item.key, share, length, offset }
    offset += CIRCUMFERENCE * share
    return segment
  })
}
