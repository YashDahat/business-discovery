import type { BlockAction, UIBlock } from './schema'

/** Props every block component receives from the renderer. */
export interface BlockProps<B extends UIBlock = UIBlock> {
  block: B
  /** Feed an interaction back into the chat as the user's next turn. */
  onAction: (action: BlockAction) => void
  /** True while a turn is in flight (blocks lock) or once this block has already been answered. */
  disabled?: boolean
  /** Whether the panel is wide/full-screen, so wide-capable blocks (form/flow) can expand. */
  wide?: boolean
}
