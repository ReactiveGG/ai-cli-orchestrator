import { describe, expect, it } from 'vitest'
import { formatCost, formatDuration, formatTokens, progress } from './format'

describe('format helpers', () => {
  it('abbreviates token counts', () => {
    expect(formatTokens(999)).toBe('999')
    expect(formatTokens(1500)).toBe('1.5k')
    expect(formatTokens(1_360_000)).toBe('1.36M')
  })
  it('formats cost with more precision under a dollar', () => {
    expect(formatCost(0)).toBe('$0')
    expect(formatCost(0.0579)).toBe('$0.058')
    expect(formatCost(1.5323)).toBe('$1.53')
  })
  it('formats durations from ms to minutes', () => {
    const t0 = '2026-09-17T00:00:00.000Z'
    expect(formatDuration(null, null)).toBe('-')
    expect(formatDuration(t0, '2026-09-17T00:00:00.500Z')).toBe('500ms')
    expect(formatDuration(t0, '2026-09-17T00:00:42.000Z')).toBe('42s')
    expect(formatDuration(t0, '2026-09-17T00:02:13.000Z')).toBe('2m 13s')
  })
  it('computes progress from done steps', () => {
    expect(progress({ status: 'RUNNING', steps: [{ status: 'DONE' }, { status: 'RUNNING' }, { status: 'PENDING' }, { status: 'PENDING' }] })).toBe(25)
    expect(progress({ status: 'SUCCEEDED', steps: [] })).toBe(100)
  })
})
