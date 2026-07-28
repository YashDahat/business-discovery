import { cn } from '@/lib/utils'

export function Section({ title, children, className }: { title: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn('rounded-lg border border-[#1e1e1e] bg-[#111] p-5', className)}>
      <h3 className="text-xs font-semibold uppercase tracking-widest text-[#555] mb-4">{title}</h3>
      {children}
    </div>
  )
}
