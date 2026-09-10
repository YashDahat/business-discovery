import { useMemo, useState } from 'react'
import {
  Background, Controls, ReactFlow, type Edge, type Node,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Maximize2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { BlockProps } from '../blocks/types'
import type { FlowBlock as FlowBlockSpec } from '../blocks/schema'
import { layout } from './autoLayout'
import { nodeTypes } from './nodeTypes'

// Read-only, auto-laid-out flow diagram (pan/zoom/fit only). The model emits topology; positions
// come from dagre. Renders larger when the panel is wide, with an expand-to-dialog for close reading.
export function FlowBlock({ block, wide }: BlockProps<FlowBlockSpec>) {
  const [expanded, setExpanded] = useState(false)
  const { nodes, edges } = useMemo(() => layout(block.nodes, block.edges), [block.nodes, block.edges])

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#0d0d0d]">
      <div className="flex items-center gap-2 border-b border-[#1e1e1e] px-3 py-1.5">
        <span className="text-[11px] font-semibold uppercase tracking-widest text-[#666]">
          {block.title ?? 'Flow'}
        </span>
        <button
          onClick={() => setExpanded(true)}
          title="Expand"
          className="ml-auto text-[#555] hover:text-white transition-colors"
        >
          <Maximize2 className="h-3.5 w-3.5" />
        </button>
      </div>

      <FlowCanvas nodes={nodes} edges={edges} className={cn(wide ? 'h-[420px]' : 'h-[300px]')} />

      <Dialog open={expanded} onOpenChange={setExpanded}>
        <DialogContent className="max-w-[92vw] w-[92vw] h-[86vh] p-0 gap-0 flex flex-col">
          <DialogHeader className="shrink-0 px-4 py-2 border-b border-[#1e1e1e]">
            <DialogTitle className="text-sm">{block.title ?? 'Flow'}</DialogTitle>
          </DialogHeader>
          <FlowCanvas nodes={nodes} edges={edges} className="flex-1 min-h-0 w-full" />
        </DialogContent>
      </Dialog>
    </div>
  )
}

function FlowCanvas({ nodes, edges, className }: { nodes: Node[]; edges: Edge[]; className?: string }) {
  return (
    <div className={className}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        proOptions={{ hideAttribution: true }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnScroll
        zoomOnScroll
        minZoom={0.2}
        maxZoom={2}
      >
        <Background color="#1e1e1e" gap={18} />
        <Controls showInteractive={false} className="!bg-[#141414] !border-[#2a2a2a]" />
      </ReactFlow>
    </div>
  )
}
