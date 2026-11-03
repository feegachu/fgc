import { screenMessage } from '../../lib/screenText'

export const present = (value: unknown) => screenMessage(value == null ? null : String(value))
