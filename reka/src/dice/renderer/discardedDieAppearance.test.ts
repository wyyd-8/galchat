import assert from 'node:assert/strict'
import test from 'node:test'
import * as THREE from 'three'

test('dims only the discarded die materials without adding screen-space geometry', async () => {
  const appearance = await import('./discardedDieAppearance.ts').catch(() => undefined)
  assert.ok(appearance, 'discarded dice need a renderer-level appearance')

  const bodyMaterial = new THREE.MeshStandardMaterial({ opacity: 0.8 })
  const effectMaterial = new THREE.SpriteMaterial({ opacity: 0.5 })
  const model = new THREE.Group()
  model.add(
    new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), bodyMaterial),
    new THREE.Sprite(effectMaterial),
  )
  const childCount = model.children.length

  appearance.applyDiscardedDieAppearance(model)

  assert.equal(model.children.length, childCount)
  assert.equal(bodyMaterial.transparent, true)
  assert.equal(effectMaterial.transparent, true)
  assert.ok(Math.abs(bodyMaterial.opacity - 0.24) < 1e-9)
  assert.ok(Math.abs(effectMaterial.opacity - 0.15) < 1e-9)

  const shader = {
    fragmentShader: 'void main() {\n#include <colorspace_fragment>\n}',
  } as Parameters<typeof bodyMaterial.onBeforeCompile>[0]
  bodyMaterial.onBeforeCompile(
    shader,
    {} as Parameters<typeof bodyMaterial.onBeforeCompile>[1],
  )
  assert.match(shader.fragmentShader, /0\.105/)
  assert.match(shader.fragmentShader, /0\.85/)

  appearance.applyDiscardedDieAppearance(model)
  assert.ok(Math.abs(bodyMaterial.opacity - 0.24) < 1e-9)
  assert.ok(Math.abs(effectMaterial.opacity - 0.15) < 1e-9)

  model.traverse((object) => {
    if (object instanceof THREE.Mesh) object.geometry.dispose()
  })
  bodyMaterial.dispose()
  effectMaterial.dispose()
})
