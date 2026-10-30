import instances from './feature-instances.v1.json'

/** Registered finite UI instances. Business-record rows are selected explicitly, never guessed. */
export function guideFeatureId(group: keyof typeof instances, value: string): string {
  const instance = value.toLowerCase()
  if (!(instances[group] as readonly string[]).includes(instance)) throw new Error(`Unregistered guide instance: ${group}`)
  return `${group}.${instance}`
}
