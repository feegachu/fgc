import { useId } from 'react'
import './screen-help.css'

export type HelpVersion = 'before' | 'after'

const versions: { value: HelpVersion; label: string }[] = [
  { value: 'before', label: '수정 전' },
  { value: 'after', label: '수정 후' },
]

export function HelpComparison({ version, onChange, label = '도움말 비교' }: { version: HelpVersion; onChange: (version: HelpVersion) => void; label?: string }) {
  const name = useId()

  return (
    <div className="help-comparison" role="radiogroup" aria-label={label} data-version={version}>
      {versions.map(item => (
        <label key={item.value}>
          <input className="visually-hidden" type="radio" name={name} checked={version === item.value} onChange={() => onChange(item.value)} />
          <span>{item.label}</span>
        </label>
      ))}
    </div>
  )
}
