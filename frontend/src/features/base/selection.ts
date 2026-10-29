import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import type { SearchPage, SearchSource } from '../../components/SearchSelect'
import { queryString } from './api'
type S = components['schemas']
type Entities = {
  agents: S['AgentResponse']
  products: S['ProductResponse']
  items: S['CommissionItemResponse']
  contracts: S['ContractView']
}
export type EntityKind = keyof Entities
const key = {
  agents: 'agentId',
  products: 'productOfferingId',
  items: 'commissionItemId',
  contracts: 'contractId',
} as const
const paths = {
  agents: '/api/v1/base/agents',
  products: '/api/v1/base/products',
  items: '/api/v1/base/commission-items/search',
  contracts: '/api/v1/contracts',
}
export function entityId<K extends EntityKind>(kind: K, option: Entities[K]): string {
  return String((option as Record<string, unknown>)[key[kind]] ?? '')
}
export function entityLabel<K extends EntityKind>(kind: K, option: Entities[K]): string {
  const row = option as Record<string, unknown>
  const coded = (name: unknown, code: unknown) => (code ? `${name ?? ''} (${code})` : String(name ?? ''))
  if (kind === 'agents') return coded(row.agentName, row.agentCode)
  if (kind === 'products')
    return coded(`${row.productName ?? ''} · ${row.offeringVersion ?? ''}`, row.insurerProductCode)
  if (kind === 'contracts') return `${row.contractNo ?? ''} · ${row.productName ?? ''}`
  return coded(row.itemName, row.itemCode)
}

export function useEntityChoices<K extends EntityKind>(
  kind: K,
  criteria: Record<string, string>,
  ids: string[] = [],
  enabled = true,
  accept: (option: Entities[K]) => boolean = () => true,
  variant = '',
) {
  const client = useQueryClient()
  const scope = [kind, criteria, variant] as const
  const selectedKey = (id: string) => ['selection-value', ...scope, id] as const
  const load = async (keyword: string, page: number, signal: AbortSignal): Promise<SearchPage<Entities[K]>> => {
    const response = await apiClient.request<{ content?: Entities[K][]; totalPages?: number }>(
      `${paths[kind]}?${queryString({
        ...criteria,
        [kind === 'contracts' ? 'contractNo' : 'keyword']: keyword,
        page,
        size: 20,
      })}`,
      { signal },
    )
    return { content: (response.data?.content ?? []).filter(accept), page, totalPages: response.data?.totalPages ?? 1 }
  }
  const first = useQuery({
    queryKey: ['selection-first', ...scope],
    queryFn: ({ signal }) => load('', 1, signal),
    enabled,
    retry: false,
    meta: { errorToast: false },
  })
  const selected = useQueries({
    queries: [...new Set(ids.filter(Boolean))].map((id) => ({
      queryKey: selectedKey(id),
      enabled: enabled && !first.isPending && !first.data?.content.some((row) => entityId(kind, row) === id),
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        if (kind === 'contracts')
          return (await apiClient.request<Entities[K]>(`${paths[kind]}/${encodeURIComponent(id)}`, { signal })).data
        const response = await apiClient.request<{ content?: Entities[K][] }>(
          `${paths[kind]}?${queryString({ ...criteria, selectedId: id, page: 1, size: 1 })}`,
          { signal },
        )
        return response.data?.content?.find((row) => entityId(kind, row) === id && accept(row)) ?? null
      },
      retry: false,
      meta: { errorToast: false },
    })),
  })
  const data = [
    ...new Map(
      [...(first.data?.content ?? []), ...selected.flatMap((query) => (query.data ? [query.data] : []))].map((row) => [
        entityId(kind, row),
        row,
      ]),
    ).values(),
  ]
  const source: SearchSource<Entities[K]> = {
    key: scope,
    load,
    id: (row) => entityId(kind, row),
    label: (row) => entityLabel(kind, row),
  }
  return {
    data,
    source,
    error: first.error,
    selectedError: selected.find((query) => query.error)?.error,
    isFetching: first.isFetching || selected.some((query) => query.isFetching),
    remember: (row?: Entities[K]) => {
      if (row) client.setQueryData(selectedKey(entityId(kind, row)), row)
    },
    refetch: () => {
      void first.refetch()
      selected.forEach((query) => void query.refetch())
    },
  }
}
