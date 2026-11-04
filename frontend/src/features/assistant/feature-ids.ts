import instances from './feature-instances.v1.json'

/** Registered finite UI instances. Business-record rows are selected explicitly, never guessed. */
export function guideFeatureId(group: keyof typeof instances, value: string): string | undefined {
  const instance = value.toLowerCase()
  // Unknown optional guidance must not take down the surrounding business screen.
  if (!(instances[group] as readonly string[] | undefined)?.includes(instance)) return undefined
  return `${group}.${instance}`
}
