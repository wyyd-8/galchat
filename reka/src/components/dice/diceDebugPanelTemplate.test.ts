import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode } from '@vue/compiler-dom'

test('offers every personalized window style in the dice debug toolbar', async () => {
  const source = await readFile(new URL('./DiceDebugPanel.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DiceDebugPanel should contain a template')

  const root = baseParse(template)
  const labels = root.children.filter((node): node is ElementNode => (
    node.type === NodeTypes.ELEMENT && node.tag === 'div'
  )).flatMap((node) => node.children)
    .filter((node): node is ElementNode => (
      node.type === NodeTypes.ELEMENT && node.tag === 'div'
    ))
    .flatMap((node) => node.children)
    .filter((node): node is ElementNode => (
      node.type === NodeTypes.ELEMENT && node.tag === 'label'
    ))
  const styleLabel = labels.find((label) => label.children.some((child) => (
    child.type === NodeTypes.ELEMENT
      && child.tag === 'span'
      && child.children.some((text) => (
        text.type === NodeTypes.TEXT && text.content.trim() === '窗口样式'
      ))
  )))

  assert.ok(styleLabel, 'dice debug toolbar should render a window style selector')
  const select = styleLabel.children.find((child): child is ElementNode => (
    child.type === NodeTypes.ELEMENT && child.tag === 'select'
  ))
  const optionValues = select?.children
    .filter((child): child is ElementNode => (
      child.type === NodeTypes.ELEMENT && child.tag === 'option'
    ))
    .map((option) => option.props.find((prop) => (
      prop.type === NodeTypes.ATTRIBUTE && prop.name === 'value'
    )))
    .map((attribute) => attribute?.type === NodeTypes.ATTRIBUTE
      ? attribute.value?.content
      : undefined)

  assert.deepEqual(optionValues, [
    'default',
    'rollDamage',
    'requestSanCheck',
    'rollHealing',
    'requestPushedCheck',
  ])
})
