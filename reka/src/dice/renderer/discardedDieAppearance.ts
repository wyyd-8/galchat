import * as THREE from 'three'

const DISCARDED_OPACITY = 0.3
const DISCARDED_SATURATION = 0.105
const DISCARDED_BRIGHTNESS = 0.85
const COLORSPACE_FRAGMENT = '#include <colorspace_fragment>'
const discardedMaterials = new WeakSet<THREE.Material>()

function applyDiscardedMaterialAppearance(material: THREE.Material): void {
  if (discardedMaterials.has(material)) return
  discardedMaterials.add(material)

  const compile = material.onBeforeCompile.bind(material)
  material.onBeforeCompile = (parameters, renderer) => {
    compile(parameters, renderer)
    parameters.fragmentShader = parameters.fragmentShader.replace(
      COLORSPACE_FRAGMENT,
      `${COLORSPACE_FRAGMENT}
gl_FragColor.rgb = mix(
  vec3(dot(gl_FragColor.rgb, vec3(0.2126, 0.7152, 0.0722))),
  gl_FragColor.rgb,
  ${DISCARDED_SATURATION}
) * ${DISCARDED_BRIGHTNESS};`,
    )
  }
  material.opacity *= DISCARDED_OPACITY
  material.transparent = true
  material.needsUpdate = true
}

export function applyDiscardedDieAppearance(model: THREE.Object3D): void {
  model.traverse((object) => {
    if (!(object instanceof THREE.Mesh || object instanceof THREE.Sprite)) return
    const materials = Array.isArray(object.material) ? object.material : [object.material]
    materials.forEach(applyDiscardedMaterialAppearance)
  })
}
