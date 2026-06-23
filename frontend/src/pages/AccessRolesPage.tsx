import { Shield } from 'lucide-react'

export default function AccessRolesPage() {
  return (
    <div className="flex flex-col items-center justify-center h-[60vh] gap-4 text-center">
      <Shield className="h-12 w-12 text-[#2a2a2a]" />
      <h2 className="text-lg font-semibold text-white">Access &amp; Roles</h2>
      <p className="text-sm text-[#555] max-w-md">
        Role-based access control, team member management, and audit logs will be available
        once the platform enters the client-facing phase.
      </p>
      <span className="text-xs text-[#444] font-mono bg-[#111] border border-[#1e1e1e] rounded-full px-3 py-1">
        Coming in Phase 4
      </span>
    </div>
  )
}
