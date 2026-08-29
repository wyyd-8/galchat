import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode } from '@vue/compiler-dom'

function textContent(node: unknown): string {
  if (!node || typeof node !== 'object') return ''
  const candidate = node as { type?: number, content?: unknown, children?: unknown[] }
  if (candidate.type === NodeTypes.TEXT || candidate.type === NodeTypes.SIMPLE_EXPRESSION) {
    return typeof candidate.content === 'string' ? candidate.content : ''
  }
  if (candidate.type === NodeTypes.INTERPOLATION) return textContent(candidate.content)
  return (candidate.children || []).map(textContent).join('')
}

test('places model management below password changes in the account menu', async () => {
  const source = await readFile(new URL('./AppSidebar.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template)
  const root = baseParse(template)
  const items: ElementNode[] = []
  const visit = (node: unknown) => {
    if (!node || typeof node !== 'object') return
    const candidate = node as { type?: number, tag?: string, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT && candidate.tag === 'DropdownMenuItem') items.push(node as ElementNode)
    for (const child of candidate.children || []) visit(child)
  }
  visit(root)

  assert.deepEqual(items.map((item) => textContent(item).trim()), ['账号资料', '修改密码', '模型管理', '退出登录'])
  const modelItem = items[2]
  assert.ok(modelItem.props.some((prop) => prop.type === NodeTypes.DIRECTIVE
    && prop.name === 'on'
    && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.arg.content === 'select'
    && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.exp.content === "emit('models')"))
})
