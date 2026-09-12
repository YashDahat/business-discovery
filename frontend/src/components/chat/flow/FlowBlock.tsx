import { useMemo, useRef, useState } from 'react'
import {
  Background, Controls, Panel, ReactFlow, getNodesBounds, getViewportForBounds,
  type Edge, type Node,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { toPng } from 'html-to-image'
import { jsPDF } from 'jspdf'
import { FileDown, Image as ImageIcon, Maximize2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { BlockProps } from '../blocks/types'
import type { FlowBlock as FlowBlockSpec } from '../blocks/schema'
import { layout } from './autoLayout'
import { nodeTypes } from './nodeTypes'

const PAD = 60 // export margin around the diagram, in px

function slug(s: string): string {
  return (s || 'flow').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'flow'
}

// Render the diagram to a PNG data URL that frames all nodes (independent of the on-screen pan/zoom).
// Uses React Flow's bounds helpers + html-to-image on the viewport element.
async function renderPng(nodes: Node[], viewportEl: HTMLElement): Promise<{ dataUrl: string; width: number; height: number }> {
  const bounds = getNodesBounds(nodes)
  const width = Math.max(Math.ceil(bounds.width) + PAD * 2, 400)
  const height = Math.max(Math.ceil(bounds.height) + PAD * 2, 300)
  const { x, y, zoom } = getViewportForBounds(bounds, width, height, 0.2, 2, 0.1)

  const dataUrl = await toPng(viewportEl, {
    backgroundColor: '#0a0a0a',
    width,
    height,
    style: {
      width: `${width}px`,
      height: `${height}px`,
      transform: `translate(${x}px, ${y}px) scale(${zoom})`,
    },
  })
  return { dataUrl, width, height }
}

async function exportPng(nodes: Node[], viewportEl: HTMLElement, title: string) {
  const { dataUrl } = await renderPng(nodes, viewportEl)
  const a = document.createElement('a')
  a.download = `${slug(title)}-flow.png`
  a.href = dataUrl
  a.click()
}

// Embed the same fitted PNG into a single-page PDF sized exactly to the diagram.
async function exportPdf(nodes: Node[], viewportEl: HTMLElement, title: string) {
  const { dataUrl, width, height } = await renderPng(nodes, viewportEl)
  const doc = new jsPDF({
    orientation: width >= height ? 'landscape' : 'portrait',
    unit: 'px',
    format: [width, height],
  })
  doc.addImage(dataUrl, 'PNG', 0, 0, width, height)
  doc.save(`${slug(title)}-flow.pdf`)
}

// Read-only, auto-laid-out flow diagram (pan/zoom/fit only). The model emits topology; positions
// come from dagre. Renders larger when the panel is wide, with an expand-to-dialog for close reading.
export function FlowBlock({ block, wide }: BlockProps<FlowBlockSpec>) {
  const [expanded, setExpanded] = useState(false)
  const { nodes, edges } = useMemo(() => layout(block.nodes, block.edges), [block.nodes, block.edges])
  const title = block.title ?? 'Flow'

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#0d0d0d]">
      <div className="flex items-center gap-2 border-b border-[#1e1e1e] px-3 py-1.5">
        <span className="text-[11px] font-semibold uppercase tracking-widest text-[#666]">{title}</span>
        <button
          onClick={() => setExpanded(true)}
          title="Expand"
          className="ml-auto text-[#555] hover:text-white transition-colors"
        >
          <Maximize2 className="h-3.5 w-3.5" />
        </button>
      </div>

      <FlowCanvas nodes={nodes} edges={edges} title={title} className={cn(wide ? 'h-[420px]' : 'h-[300px]')} />

      <Dialog open={expanded} onOpenChange={setExpanded}>
        <DialogContent className="max-w-[92vw] w-[92vw] h-[86vh] p-0 gap-0 flex flex-col">
          <DialogHeader className="shrink-0 px-4 py-2 border-b border-[#1e1e1e]">
            <DialogTitle className="text-sm">{title}</DialogTitle>
          </DialogHeader>
          <FlowCanvas nodes={nodes} edges={edges} title={title} className="flex-1 min-h-0 w-full" />
        </DialogContent>
      </Dialog>
    </div>
  )
}

function FlowCanvas({ nodes, edges, title, className }: {
  nodes: Node[]
  edges: Edge[]
  title: string
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [busy, setBusy] = useState<null | 'png' | 'pdf'>(null)

  const download = async (fmt: 'png' | 'pdf') => {
    const viewportEl = ref.current?.querySelector<HTMLElement>('.react-flow__viewport')
    if (!viewportEl || busy) return
    setBusy(fmt)
    try {
      if (fmt === 'png') await exportPng(nodes, viewportEl, title)
      else await exportPdf(nodes, viewportEl, title)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div ref={ref} className={className}>
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
        <Panel position="top-right" className="flex gap-1">
          <button
            onClick={() => download('png')}
            disabled={!!busy}
            title="Download as PNG"
            className="flex items-center gap-1 rounded border border-[#2a2a2a] bg-[#141414]/90 px-2 py-1 text-[11px] text-[#aaa] hover:text-white hover:border-[#3a3a3a] disabled:opacity-50 transition-colors"
          >
            <ImageIcon className="h-3.5 w-3.5" />
            {busy === 'png' ? 'Saving…' : 'PNG'}
          </button>
          <button
            onClick={() => download('pdf')}
            disabled={!!busy}
            title="Download as PDF"
            className="flex items-center gap-1 rounded border border-[#2a2a2a] bg-[#141414]/90 px-2 py-1 text-[11px] text-[#aaa] hover:text-white hover:border-[#3a3a3a] disabled:opacity-50 transition-colors"
          >
            <FileDown className="h-3.5 w-3.5" />
            {busy === 'pdf' ? 'Saving…' : 'PDF'}
          </button>
        </Panel>
      </ReactFlow>
    </div>
  )
}
