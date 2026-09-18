import { memo, useEffect, useMemo } from 'react'
import {
  ReactFlow, ReactFlowProvider, Background, MarkerType, Position, useNodesState, useEdgesState, useReactFlow,
  type Node, type Edge,
} from '@xyflow/react'
import type { StepStatus } from '../lib/types'
import { stepTone } from '../lib/format'

export interface FlowStep {
  id: string
  label: string
  module?: string | null
  role?: string | null
  dependsOn: string[]
  status?: StepStatus
  /** competing candidate number (0/undefined = not a candidate) */
  candidate?: number
  /** 'chosen' = the verifier adopted this candidate, 'rejected' = another one was adopted */
  outcome?: 'chosen' | 'rejected'
}

const NODE_W = 150
const NODE_H = 48

/**
 * Draws the job's step graph. Layout is layered by dependency depth, so
 * parallel agents of one stage stack vertically and stages run left to right.
 * Nodes carry explicit sizes and the view is re-fitted whenever the steps
 * change, so live status updates never push the graph out of view.
 */
const shapeKey = (steps: FlowStep[]) => steps.map((s) => `${s.id}:${s.label}:${s.module ?? ''}:${s.status ?? ''}:${s.outcome ?? ''}`).join('|')

/**
 * Memoised on the graph's shape (ids, labels, modules, statuses) so the
 * hundreds of log events a running job emits never re-render the diagram.
 */
export const FlowDiagram = memo(FlowDiagramImpl, (a, b) => a.height === b.height && shapeKey(a.steps) === shapeKey(b.steps))

function FlowDiagramImpl({ steps, height = 220 }: { steps: FlowStep[]; height?: number }) {
  if (!steps.length) {
    return <div className="flex h-24 items-center justify-center text-sm text-slate-400">단계 정보가 아직 없습니다</div>
  }
  return (
    <div style={{ height }} className="rounded-lg border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <ReactFlowProvider>
        <Diagram steps={steps} />
      </ReactFlowProvider>
    </div>
  )
}

function Diagram({ steps }: { steps: FlowStep[] }) {
  const { nodes: laidOut, edges: laidOutEdges } = useMemo(() => layout(steps), [steps])
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>(laidOut)
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(laidOutEdges)
  const { fitView } = useReactFlow()

  // Re-fit only when the graph actually changes.
  const shape = shapeKey(steps)
  useEffect(() => {
    setNodes(laidOut)
    setEdges(laidOutEdges)
    const handle = window.requestAnimationFrame(() => fitView({ padding: 0.2, duration: 150 }))
    return () => window.cancelAnimationFrame(handle)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shape])

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      fitView
      fitViewOptions={{ padding: 0.2 }}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      panOnDrag
      zoomOnScroll={false}
      minZoom={0.2}
      proOptions={{ hideAttribution: true }}
    >
      <Background gap={16} />
    </ReactFlow>
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
  const xGap = 210
  const yGap = 84

  const nodes: Node[] = []
  for (const [d, column] of columns) {
    const offset = ((maxRows - column.length) * yGap) / 2
    column.forEach((step, i) => {
      const status = step.status ?? 'PENDING'
      const tone = stepTone[status]
      const sub = step.role ? `${step.role} · ${step.module ?? ''}` : step.module ?? ''
      const chosen = step.outcome === 'chosen'
      const rejected = step.outcome === 'rejected'
      nodes.push({
        id: step.id,
        position: { x: d * xGap, y: offset + i * yGap },
        width: NODE_W,
        height: NODE_H,
        data: {
          label: (
            <div className="relative text-center leading-tight">
              {chosen && <span className="absolute -top-4 left-1/2 -translate-x-1/2 rounded-full bg-blue-600 px-1.5 text-[10px] font-semibold text-white shadow">✓ 채택</span>}
              <div className="text-xs font-semibold">{step.label}</div>
              {sub && <div className="mt-0.5 text-[10px] opacity-75">{sub}</div>}
            </div>
          ),
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        style: {
          background: tone.bg,
          borderColor: chosen ? '#2563eb' : tone.border,   // blue: calmer than green next to the DONE tone
          color: tone.text,
          borderWidth: chosen ? 3 : status === 'RUNNING' ? 2 : 1,
          borderRadius: 10,
          width: NODE_W,
          height: NODE_H,
          padding: 6,
          fontSize: 12,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: rejected ? 0.45 : 1,
          overflow: 'visible',
          boxShadow: chosen ? '0 0 0 4px rgba(37,99,235,0.22)' : status === 'RUNNING' ? `0 0 0 4px ${tone.bg}` : undefined,
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
      style: { stroke: step.outcome === 'chosen' || byId.get(from)?.outcome === 'chosen' ? '#2563eb' : '#94a3b8', strokeWidth: step.outcome === 'chosen' || byId.get(from)?.outcome === 'chosen' ? 2 : 1, opacity: step.outcome === 'rejected' || byId.get(from)?.outcome === 'rejected' ? 0.4 : 1 },
    })),
  )
  return { nodes, edges }
}
