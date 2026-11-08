import type { ScreenHelpStep } from './ScreenHelp'
import { screenWalkthroughs } from './screen-walkthroughs'

/** Both entry points call this catalogue; workflow-only result gates stay with its orchestrator. */
export function getScreenWalkthrough(screenId: string, variant?: string): readonly ScreenHelpStep[] {
  return screenWalkthroughs[variant ? `${screenId}.${variant}` : screenId]
    ?? (variant === 'reader' ? screenWalkthroughs[screenId] : undefined) ?? []
}
export function guideSelector(step: ScreenHelpStep) {
  return step.selector ?? `[${step.attribute ?? 'data-guide-target'}="${step.targetId}"]`
}
