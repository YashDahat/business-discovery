/**
 * Animated backdrop for the login screen — pure CSS + one static SVG, no runtime deps:
 * a dotted world map (edge-faded, gently pulsing) under a slow-panning grid and three
 * drifting glow blobs, so the blobs read as light sweeping across the globe.
 * Purely decorative (aria-hidden) and non-interactive.
 */
export function LoginBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {/* slow-panning tech grid, faded toward the edges */}
      <div className="login-grid absolute inset-0 opacity-70" />

      {/* dotted world map, centered and spanning the width */}
      <div className="absolute inset-0 flex items-center justify-center">
        <img
          src="/world-dotmap.svg"
          alt=""
          aria-hidden="true"
          draggable={false}
          className="login-map w-[130%] max-w-none select-none"
        />
      </div>

      {/* drifting glow blobs (staggered durations/delays for organic motion) */}
      <div className="login-blob absolute -left-24 -top-32 h-96 w-96 rounded-full bg-[#00ff88]/20 blur-3xl" />
      <div
        className="login-blob absolute -bottom-40 -right-20 h-[28rem] w-[28rem] rounded-full bg-[#00c2ff]/10 blur-3xl"
        style={{ animationDelay: '-7s', animationDuration: '24s' }}
      />
      <div
        className="login-blob absolute right-1/4 top-1/3 h-72 w-72 rounded-full bg-[#00ff88]/10 blur-3xl"
        style={{ animationDelay: '-13s', animationDuration: '28s' }}
      />

      {/* vignette to keep the card readable and edges dark */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_35%,#0a0a0a_100%)]" />
    </div>
  )
}
