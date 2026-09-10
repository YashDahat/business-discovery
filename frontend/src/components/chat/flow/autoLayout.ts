import dagre from 'dagre'
import type { Edge, Node } from '@xyflow/react'
import type { FlowEdgeSpec, FlowNodeSpec } from '../blocks/schema'

const NODE_W = 180
const NODE_H = 52

/**
 * Turn the model's topology (nodes + edges, no coordinates) into positioned React Flow nodes/edges
 * using a dagre top-to-bottom layout. Deterministic — same input always lays out the same way.
 */
export function layout(nodeSpecs: FlowNodeSpec[], edgeSpecs: FlowEdgeSpec[]): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 40, ranksep: 60, marginx: 12, marginy: 12 })

  const ids = new Set(nodeSpecs.map(n => n.id))
  for (const n of nodeSpecs) g.setNode(n.id, { width: NODE_W, height: NODE_H })
  // Only lay out edges whose endpoints both exist, so a bad edge can't crash dagre.
  const validEdges = edgeSpecs.filter(e => ids.has(e.from) && ids.has(e.to))
  for (const e of validEdges) g.setEdge(e.from, e.to)

  dagre.layout(g)

  const nodes: Node[] = nodeSpecs.map(spec => {
    const pos = g.node(spec.id)
    return {
      id: spec.id,
      type: 'ops',
      position: { x: (pos?.x ?? 0) - NODE_W / 2, y: (pos?.y ?? 0) - NODE_H / 2 },
      data: { label: spec.label, kind: spec.kind ?? 'process', detail: spec.detail },
      width: NODE_W,
      height: NODE_H,
    }
  })

  const edges: Edge[] = validEdges.map((e, i) => ({
    id: `e${i}-${e.from}-${e.to}`,
    source: e.from,
    target: e.to,
    label: e.label,
    animated: false,
    style: { stroke: '#3a3a3a' },
    labelStyle: { fill: '#999', fontSize: 11 },
    labelBgStyle: { fill: '#0a0a0a' },
  }))

  return { nodes, edges }
}
