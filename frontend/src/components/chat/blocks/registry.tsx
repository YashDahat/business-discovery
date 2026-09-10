import { lazy } from 'react'
import type { ComponentType } from 'react'
import type { BlockProps } from './types'
import type { UIBlock } from './schema'
import { TextBlock } from './TextBlock'
import { CalloutBlock } from './CalloutBlock'
import { ChoicesBlock } from './ChoicesBlock'
import { DateBlock } from './DateBlock'
import { FormBlock } from './FormBlock'

// React Flow is heavy — load it only when a flow block actually renders.
const FlowBlock = lazy(() => import('../flow/FlowBlock').then(m => ({ default: m.FlowBlock })))

type AnyBlockComponent = ComponentType<BlockProps>

/**
 * Maps a block `type` to its renderer. Adding a new block type = add a component + one entry here;
 * no edits to BlockRenderer or ChatBubble (Open/Closed).
 */
export const BLOCK_REGISTRY: Record<UIBlock['type'], AnyBlockComponent> = {
  text: TextBlock as unknown as AnyBlockComponent,
  callout: CalloutBlock as unknown as AnyBlockComponent,
  choices: ChoicesBlock as unknown as AnyBlockComponent,
  date: DateBlock as unknown as AnyBlockComponent,
  form: FormBlock as unknown as AnyBlockComponent,
  flow: FlowBlock as unknown as AnyBlockComponent,
}
