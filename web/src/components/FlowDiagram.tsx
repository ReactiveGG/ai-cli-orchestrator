import { useMemo } from 'react'
import { ReactFlow, Background, type Node, type Edge, MarkerType, Position } from '@xyflow/react'
import type { StepStatus } from '../lib/types'
import { stepTone } from '../lib/format'

export interface FlowStep {
  id: string
  label: string
  module?: string | null
  role?: string | null
  dependsOn: string[]
  status?: StepStatus
}

/**
 * Draws the job's step graph. Layout is layered by dependency depth, so a
 * fan-out (verify: codex + claude) shows as parallel branches and a pipeline
 * (planner -> coder -> reviewer) shows as one chain.
 */
export function FlowDiagram({ steps, height = 220 }: { steps: FlowStep[]; height?: number }) {
  const { nodes, edges } = useMemo(() => layout(steps), [steps])
  if (!steps.length) {
    return <div className="flex h-24 items-center justify-center text-sm text-slate-400">단계 정보가 아직 없습니다</div>
  }
  return (
    <div style={{ height }} className="rounded-lg border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnDrag
        zoomOnScroll={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={16} />
      </ReactFlow>
    </div>
  )
}

function layout(steps: FlowStep[]): { nodes: Node[]; edges: Edge[] } {
  const depth = new Map<string, number>()
  const byId = new Map(steps.map((s) => [s.id, s]))
  const depthOf = (id: string, seen = new Set<string>()): number => {
    if (depth.has(id)) return depth.get(id)!
    if (seen.has(id)) return 0
    seen.add(id)
    const step = byId.get(id)
    const d = step && step.dependsOn.length ? Math.max(...step.dependsOn.map((p) => depthOf(p, seen))) + 1 : 0
    depth.set(id, d)
    return d
  }
  steps.forEach((s) => depthOf(s.id))

  const columns = new Map<number, FlowStep[]>()
  for (const step of steps) {
    const d = depth.get(step.id) ?? 0
    if (!columns.has(d)) columns.set(d, [])
    columns.get(d)!.push(step)
  }
  const maxRows = Math.max(...[...columns.values()].map((c) => c.length))
  const xGap = 200
  const yGap = 80

  const nodes: Node[] = []
  for (const [d, column] of columns) {
    const offset = ((maxRows - column.length) * yGap) / 2
    column.forEach((step, i) => {
      const status = step.status ?? 'PENDING'
      const tone = stepTone[status]
      const sub = step.role ? `${step.role} · ${step.module ?? ''}` : step.module ?? ''
      nodes.push({
        id: step.id,
        position: { x: d * xGap, y: offset + i * yGap },
        data: {
          label: (
            <div className="text-center leading-tight">
              <div className="text-xs font-semibold">{step.label}</div>
              {sub && <div className="mt-0.5 text-[10px] opacity-75">{sub}</div>}
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: {
          background: tone.bg,
          borderColor: tone.border,
          color: tone.text,
          borderWidth: status === 'RUNNING' ? 2 : 1,
          borderRadius: 10,
          width: 150,
          padding: 6,
          fontSize: 12,
          boxShadow: status === 'RUNNING' ? `0 0 0 4px ${tone.bg}` : undefined,
        },
      })
    })
  }
  const edges: Edge[] = steps.flatMap((step) =>
    step.dependsOn.map((from) => ({
      id: `${from}->${step.id}`,
      source: from,
      target: step.id,
      animated: step.status === 'RUNNING',
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: '#94a3b8' },
    })),
  )
  return { nodes, edges }
}
