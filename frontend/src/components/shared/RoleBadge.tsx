import { cn } from '@/lib/utils'
import type { Role } from '@/types/access'

// Per-role identity: label, accent color, and the short access descriptor used in the
// legend chips. Colors follow the design mockup (Operator green, Analyst blue,
// Client gold, Reseller tan).
export const ROLE_META: Record<Role, { label: string; color: string; access: string }> = {
  OPERATOR: { label: 'Operator', color: '#00ff88', access: 'all' },
  ANALYST:  { label: 'Analyst',  color: '#4aa3ff', access: 'read' },
  CLIENT:   { label: 'Client',   color: '#d4a24e', access: '1 biz' },
  RESELLER: { label: 'Reseller', color: '#c9b896', access: 'portfolio' },
}

export const ROLE_ORDER: Role[] = ['OPERATOR', 'ANALYST', 'CLIENT', 'RESELLER']

// Friendly job-title shown under a user's name (badge + profile screen).
export const ROLE_TITLE: Record<Role, string> = {
  OPERATOR: 'Lead Operator',
  ANALYST: 'Analyst',
  CLIENT: 'Client',
  RESELLER: 'Reseller Partner',
}

export function RoleBadge({ role, className }: { role: Role; className?: string }) {
  const meta = ROLE_META[role]
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-md border px-3 py-1 font-mono text-xs font-medium',
        className,
      )}
      style={{
        color: meta.color,
        borderColor: `${meta.color}55`,
        backgroundColor: `${meta.color}14`,
      }}
    >
      {meta.label}
    </span>
  )
}
