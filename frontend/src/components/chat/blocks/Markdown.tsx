import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { cn } from '@/lib/utils'

// Minimal, theme-matched markdown for text/callout blocks. Dark ops palette, compact spacing.
export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={cn('text-sm leading-relaxed text-[#ccc] [&>*:first-child]:mt-0 [&>*:last-child]:mb-0', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ node: _node, ...props }) => <p className="my-2 whitespace-pre-wrap" {...props} />,
          a: ({ node: _node, ...props }) => (
            <a className="text-[#4aa8ff] underline underline-offset-2 hover:text-[#7bc0ff]" target="_blank" rel="noreferrer" {...props} />
          ),
          ul: ({ node: _node, ...props }) => <ul className="my-2 list-disc pl-5 space-y-1" {...props} />,
          ol: ({ node: _node, ...props }) => <ol className="my-2 list-decimal pl-5 space-y-1" {...props} />,
          li: ({ node: _node, ...props }) => <li className="marker:text-[#555]" {...props} />,
          h1: ({ node: _node, ...props }) => <h1 className="mt-3 mb-2 text-base font-semibold text-white" {...props} />,
          h2: ({ node: _node, ...props }) => <h2 className="mt-3 mb-1.5 text-sm font-semibold text-white" {...props} />,
          h3: ({ node: _node, ...props }) => <h3 className="mt-2 mb-1 text-sm font-semibold text-[#ddd]" {...props} />,
          code: ({ node: _node, ...props }) => (
            <code className="rounded bg-[#1a1a1a] border border-[#2a2a2a] px-1 py-0.5 text-[0.8em] text-[#e0e0e0]" {...props} />
          ),
          pre: ({ node: _node, ...props }) => (
            <pre className="my-2 overflow-x-auto rounded bg-[#0d0d0d] border border-[#2a2a2a] p-3 text-xs text-[#ddd]" {...props} />
          ),
          blockquote: ({ node: _node, ...props }) => (
            <blockquote className="my-2 border-l-2 border-[#333] pl-3 text-[#aaa] italic" {...props} />
          ),
          table: ({ node: _node, ...props }) => (
            <div className="my-2 overflow-x-auto"><table className="w-full text-xs border-collapse" {...props} /></div>
          ),
          th: ({ node: _node, ...props }) => <th className="border border-[#2a2a2a] px-2 py-1 text-left font-semibold text-[#ddd]" {...props} />,
          td: ({ node: _node, ...props }) => <td className="border border-[#2a2a2a] px-2 py-1 text-[#bbb]" {...props} />,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  )
}
