// First letters of the first two words of a name, e.g. "Aarav Mehta" → "AM".
export function initials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map(w => w[0]?.toUpperCase() ?? '').join('') || '?'
}
