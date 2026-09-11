import assert from 'node:assert/strict'
import test from 'node:test'
import * as THREE from 'three'

test('dims discarded die materials without changing their transparency', async () => {
  const appearance = await import('./discardedDieAppearance.ts').catch(() => undefined)
  assert.ok(appearance, 'discarded dice need a renderer-level appearance')

  const bodyMaterial = new THREE.MeshStandardMaterial({ opacity: 0.8 })
  const effectMaterial = new THREE.SpriteMaterial({ opacity: 0.5 })
  const bodyTransparent = bodyMaterial.transparent
  const effectTransparent = effectMaterial.transparent
  const model = new THREE.Group()
  model.add(
    new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), bodyMaterial),
    new THREE.Sprite(effectMaterial),
  )
  const childCount = model.children.length

  appearance.applyDiscardedDieAppearance(model)

  assert.equal(model.children.length, childCount)
  assert.equal(bodyMaterial.transparent, bodyTransparent)
  assert.equal(effectMaterial.transparent, effectTransparent)
  assert.equal(bodyMaterial.opacity, 0.8)
  assert.equal(effectMaterial.opacity, 0.5)

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
  assert.equal(bodyMaterial.opacity, 0.8)
  assert.equal(effectMaterial.opacity, 0.5)

  model.traverse((object) => {
    if (object instanceof THREE.Mesh) object.geometry.dispose()
  })
  bodyMaterial.dispose()
  effectMaterial.dispose()
})

test('lifts dark discarded colors toward a neutral gray', async () => {
  const appearance = await import('./discardedDieAppearance.ts').catch(() => undefined)
  assert.ok(appearance, 'discarded dice need a renderer-level appearance')

  const material = new THREE.MeshStandardMaterial()
  const model = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), material)
  appearance.applyDiscardedDieAppearance(model)

  const shader = {
    fragmentShader: 'void main() {\n#include <colorspace_fragment>\n}',
  } as Parameters<typeof material.onBeforeCompile>[0]
  material.onBeforeCompile(
    shader,
    {} as Parameters<typeof material.onBeforeCompile>[1],
  )

  assert.match(shader.fragmentShader, /vec3 discardedColor =/)
  assert.match(shader.fragmentShader, /mix\(vec3\(0\.88\), discardedColor, 0\.3\)/)
  assert.doesNotMatch(shader.fragmentShader, /gl_FragColor\.a\s*=/)

  model.geometry.dispose()
  material.dispose()
})
