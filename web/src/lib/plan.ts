import type { AgentDto, FlowConfig, FlowDto } from './types'
import type { FlowStep } from '../components/FlowDiagram'

/** Agents per stage, e.g. 1-1-2-1. */
export const signatureOf = (f: FlowDto) => f.stages.map((s) => Math.max(1, s.models.length)).join('-')

export const agent = (module: string): AgentDto => ({ module, model: null, effort: null })

/** {@code claude}, {@code claude opus}, {@code claude opus/high}. */
export const describeAgent = (a: AgentDto) => a.model || a.effort ? `${a.module} ${a.model ?? '기본'}${a.effort ? '/' + a.effort : ''}` : a.module

/**
 * Client-side copy of ExecutionManager.plan(): one node per agent, every agent
 * of stage N depending on every agent of stage N-1. Candidate numbers are set
 * for a coder stage with 2+ agents (competition mode).
 */
export function buildPlan(flow: FlowDto, cfg: FlowConfig): FlowStep[] {
  const steps: FlowStep[] = []
  let prev: string[] = []
  flow.stages.forEach((stage, si) => {
    const ids: string[] = []
    const models = stage.models.length ? stage.models : [agent(flow.defaultModule ?? 'claude')]
    const competing = stage.role === 'coder' && models.length > 1
    models.forEach((a, k) => {
      const dup = models.slice(0, k).filter((x) => x.module === a.module).length
      const id = `s${si + 1}/${stage.role}@${a.module}${dup ? `#${dup}` : ''}`
      steps.push({ id, label: (cfg.roles[stage.role]?.label ?? stage.role) + (competing ? ` · 후보 ${k + 1}` : ''), module: describeAgent(a), role: stage.role, candidate: competing ? k + 1 : 0, dependsOn: prev })
      ids.push(id)
    })
    prev = ids
  })
  return steps
}
