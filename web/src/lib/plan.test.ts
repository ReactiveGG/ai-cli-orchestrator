import { describe, expect, it } from 'vitest'
import { buildPlan, describeAgent, signatureOf } from './plan'
import type { FlowConfig, FlowDto } from './types'

const roles: FlowConfig['roles'] = {
  planner: { label: '플래너', instructions: 'p', builtIn: true },
  coder: { label: '코더', instructions: 'c', builtIn: true },
  reviewer: { label: '리뷰어', instructions: 'r', builtIn: true },
  verifier: { label: '검증자', instructions: 'v', builtIn: true },
}
const cfg: FlowConfig = { flows: {}, roles, fallback: {} }
const preset = (counts: number[], module = 'claude'): FlowDto => ({
  label: 'p', task: 'custom', defaultModule: module,
  stages: ['planner', 'coder', 'reviewer', 'verifier'].map((role, i) => ({ name: null, role, models: Array.from({ length: counts[i] }, () => ({ module, model: null, effort: null, command: null })) })),
})

describe('signatureOf', () => {
  it('counts agents per stage, empty stage counts as one', () => {
    expect(signatureOf(preset([1, 3, 3, 1]))).toBe('1-3-3-1')
    expect(signatureOf(preset([0, 0, 2, 0]))).toBe('1-1-2-1')
  })
})

describe('describeAgent', () => {
  it('shows module, then model and effort when set', () => {
    expect(describeAgent({ module: 'claude', model: null, effort: null, command: null })).toBe('claude')
    expect(describeAgent({ module: 'claude', model: 'sonnet', effort: null, command: '/plan' })).toBe('claude sonnet /plan')
    expect(describeAgent({ module: 'claude', model: 'opus', effort: null, command: null })).toBe('claude opus')
    expect(describeAgent({ module: 'claude', model: null, effort: 'high', command: null })).toBe('claude 기본/high')
  })
})

describe('buildPlan', () => {
  it('chains stages and fans out parallel agents with unique ids', () => {
    const plan = buildPlan(preset([1, 1, 2, 1]), cfg)
    expect(plan.map((s) => s.id)).toEqual(['s1/planner@claude', 's2/coder@claude', 's3/reviewer@claude', 's3/reviewer@claude#1', 's4/verifier@claude'])
    expect(plan[0].dependsOn).toEqual([])
    expect(plan[2].dependsOn).toEqual(['s2/coder@claude'])
    expect(plan[4].dependsOn).toEqual(['s3/reviewer@claude', 's3/reviewer@claude#1'])
    expect(plan.every((s) => s.candidate === 0)).toBe(true)
  })

  it('marks competing coders as candidates', () => {
    const plan = buildPlan(preset([1, 3, 3, 1]), cfg)
    const coders = plan.filter((s) => s.role === 'coder')
    expect(coders.map((s) => s.candidate)).toEqual([1, 2, 3])
    expect(coders[1].label).toBe('코더 · 후보 2')
    expect(plan.filter((s) => s.role === 'reviewer').every((s) => s.candidate === 0)).toBe(true)
  })

  it('uses the default model for an empty stage', () => {
    const plan = buildPlan(preset([0, 0, 0, 0], 'codex'), cfg)
    expect(plan).toHaveLength(4)
    expect(plan.every((s) => s.module === 'codex')).toBe(true)
  })
})
