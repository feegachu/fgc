import { useState, type ReactNode } from 'react'
import './hierarchy-tree.css'

export interface HierarchyNode {
  id: string
  label: string
  meta?: ReactNode
  icon?: string | null
  content?: ReactNode
  className?: string
  children?: HierarchyNode[]
}

export function HierarchyTree({ nodes, selectedId, onSelect, label }: {
  nodes: HierarchyNode[]; selectedId?: string | null; onSelect: (id: string) => void; label: string
}) {
  const [closed, setClosed] = useState<Set<string>>(() => new Set())
  const render = (items: HierarchyNode[], depth: number): ReactNode => <ul role={depth ? 'group' : 'tree'} aria-label={depth ? undefined : label}>
    {items.map(node => {
      const branch = !!node.children?.length
      const expanded = branch && !closed.has(node.id)
      return <li key={node.id} role="treeitem" aria-selected={selectedId === node.id} aria-expanded={branch ? expanded : undefined}>
        <div className={`hierarchy-row ${node.className ?? ''}${selectedId === node.id ? ' is-active' : ''}`} style={{ paddingLeft: 8 + depth * 16 }}>
          {branch ? <button data-guide-feature="hierarchy.button-1" type="button" className="hierarchy-toggle" aria-label={`${node.label} ${expanded ? '접기' : '펼치기'}`}
            onClick={() => setClosed(previous => { const next = new Set(previous); if (next.has(node.id)) next.delete(node.id); else next.add(node.id); return next })}>
            <span className="material-symbols-rounded" aria-hidden="true">{expanded ? 'expand_more' : 'chevron_right'}</span>
          </button> : <span className="hierarchy-toggle" />}
          <button data-guide-feature="hierarchy.button-2" type="button" className="hierarchy-select" aria-current={selectedId === node.id ? 'true' : undefined} onClick={() => onSelect(node.id)} title={node.label}>
            {node.icon !== null && <span className="material-symbols-rounded" aria-hidden="true">{node.icon ?? (branch ? 'folder' : 'description')}</span>}
            {node.content ?? <><span className="hierarchy-label">{node.label}</span>{node.meta != null && <small>{node.meta}</small>}</>}
          </button>
        </div>
        {expanded && render(node.children!, depth + 1)}
      </li>
    })}
  </ul>
  return <nav className="hierarchy-tree" aria-label={label}>{render(nodes, 0)}</nav>
}

export function ViewModeSwitch({ value, onChange, label = '보기 방식' }: {
  value: 'tree' | 'table'; onChange: (value: 'tree' | 'table') => void; label?: string
}) {
  return <div className="hierarchy-view-switch" role="group" aria-label={label} data-view={value}>
    <span className="hierarchy-view-slider" aria-hidden="true" />
    {(['tree', 'table'] as const).map(mode => <button data-guide-feature="hierarchy.button-3" type="button" key={mode} aria-label={mode === 'tree' ? '트리 보기' : '표 보기'}
      aria-pressed={value === mode} onClick={() => onChange(mode)} title={mode === 'tree' ? '트리 보기' : '표 보기'}>
      <span className="material-symbols-rounded" aria-hidden="true">{mode === 'tree' ? 'folder' : 'table_chart'}</span>
    </button>)}
  </div>
}
