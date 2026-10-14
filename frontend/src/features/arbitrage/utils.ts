export const present = (value: unknown) => (value == null || value === '' ? '—' : String(value))
