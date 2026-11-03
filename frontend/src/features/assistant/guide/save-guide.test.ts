import { expect, it } from 'vitest'
import { SaveGuide } from './save-guide'
it('failure, retry and changed inputs never retain old server success', async () => {
  const guide = new SaveGuide()
  guide.update({}, true); guide.begin(); guide.fail('conflict')
  expect(guide.getSnapshot()).toMatchObject({ error: 'conflict', savedId: null, pending: false })
  guide.begin()
  await expect(guide.saved(0)).rejects.toThrow()
  await guide.saved(91)
  expect(guide.getSnapshot().savedId).toBe(91)
  guide.update({}, true)
  expect(guide.getSnapshot().savedId).toBeNull()
})
it('holds navigation until acknowledgement, cancels navigation on unmount', async () => {
  const guide = new SaveGuide()
  guide.activate(); guide.begin()
  const result = guide.saved(92)
  guide.deactivate(false)
  await expect(result).resolves.toBe(false)
  guide.activate(); guide.begin()
  const next = guide.saved(93)
  guide.deactivate()
  await expect(next).resolves.toBe(true)
})

it('does not apply a late successful response to changed input or entity context', async () => {
  const guide = new SaveGuide()
  guide.update('contract1:policy1', true); guide.begin()
  guide.update('contract2:policy2', true)
  await expect(guide.saved(94)).rejects.toThrow('선택 대상')
  expect(guide.getSnapshot().savedId).toBeNull()
})

it('rejects a late response even when changed inputs are restored to their original values', async () => {
  const guide = new SaveGuide()
  guide.update('first', true); guide.begin()
  guide.update('other', true); guide.update('first', true)
  await expect(guide.saved(95)).rejects.toThrow('선택 대상')
  expect(guide.getSnapshot().savedId).toBeNull()
  guide.begin(); await guide.saved(96)
  expect(guide.getSnapshot().savedId).toBe(96)
})
