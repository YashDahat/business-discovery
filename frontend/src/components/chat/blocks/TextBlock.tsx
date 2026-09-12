import type { BlockProps } from './types'
import type { TextBlock as TextBlockSpec } from './schema'
import { Markdown } from './Markdown'

export function TextBlock({ block }: BlockProps<TextBlockSpec>) {
  return <Markdown>{block.markdown}</Markdown>
}
