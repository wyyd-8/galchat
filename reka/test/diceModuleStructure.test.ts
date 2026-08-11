import assert from 'node:assert/strict'
import { access, readFile, readdir } from 'node:fs/promises'
import test from 'node:test'
import { inflateSync } from 'node:zlib'

const files = {
  player: new URL('../src/dice/components/DicePlayerDialog.vue', import.meta.url),
  message: new URL('../src/dice/components/DiceRollMessage.vue', import.meta.url),
  playback: new URL('../src/dice/domain/dicePlayback.ts', import.meta.url),
  renderer: new URL('../src/dice/renderer/ThreeDice.ts', import.meta.url),
  styles: new URL('../src/styles/index.css', import.meta.url),
  classicModels: new URL('../src/dice/assets/models/classic/', import.meta.url),
  galaxyModels: new URL('../src/dice/assets/models/galaxy/', import.meta.url),
  moonwhiteModels: new URL('../src/dice/assets/models/moonwhite/', import.meta.url),
  cinnabarModels: new URL('../src/dice/assets/models/cinnabar/', import.meta.url),
}

async function readGlb(file: URL) {
  const buffer = await readFile(file)
  assert.equal(buffer.toString('utf8', 0, 4), 'glTF')
  const jsonLength = buffer.readUInt32LE(12)
  const chunkType = buffer.toString('utf8', 16, 20)
  assert.equal(chunkType, 'JSON')
  const binStart = 20 + jsonLength + 8
  const binLength = buffer.readUInt32LE(20 + jsonLength)
  const binType = buffer.toString('utf8', 24 + jsonLength, 28 + jsonLength)
  assert.equal(binType, 'BIN\0')
  return {
    json: JSON.parse(buffer.toString('utf8', 20, 20 + jsonLength)),
    bin: buffer.subarray(binStart, binStart + binLength),
  }
}

async function readGlbJson(file: URL) {
  return (await readGlb(file)).json
}

function decodePng(buffer: Buffer) {
  assert.equal(buffer.toString('hex', 0, 8), '89504e470d0a1a0a')
  let offset = 8
  let width = 0
  let height = 0
  let bitDepth = 0
  let colorType = 0
  const idatChunks: Buffer[] = []
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset)
    const type = buffer.toString('utf8', offset + 4, offset + 8)
    const data = buffer.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      bitDepth = data[8]!
      colorType = data[9]!
    } else if (type === 'IDAT') {
      idatChunks.push(data)
    } else if (type === 'IEND') {
      break
    }
    offset += length + 12
  }
  assert.equal(bitDepth, 8)
  assert.ok(colorType === 2 || colorType === 6)
  const bytesPerPixel = colorType === 6 ? 4 : 3
  const rowLength = width * bytesPerPixel
  const inflated = inflateSync(Buffer.concat(idatChunks))
  const pixels = Buffer.alloc(height * rowLength)
  let readOffset = 0
  for (let y = 0; y < height; y += 1) {
    const filter = inflated[readOffset]!
    readOffset += 1
    const rowStart = y * rowLength
    const previousRowStart = rowStart - rowLength
    for (let x = 0; x < rowLength; x += 1) {
      const raw = inflated[readOffset + x]!
      const left = x >= bytesPerPixel ? pixels[rowStart + x - bytesPerPixel]! : 0
      const up = y > 0 ? pixels[previousRowStart + x]! : 0
      const upLeft = y > 0 && x >= bytesPerPixel ? pixels[previousRowStart + x - bytesPerPixel]! : 0
      const predictor = (() => {
        if (filter === 0) return 0
        if (filter === 1) return left
        if (filter === 2) return up
        if (filter === 3) return Math.floor((left + up) / 2)
        const p = left + up - upLeft
        const pa = Math.abs(p - left)
        const pb = Math.abs(p - up)
        const pc = Math.abs(p - upLeft)
        if (pa <= pb && pa <= pc) return left
        return pb <= pc ? up : upLeft
      })()
      pixels[rowStart + x] = (raw + predictor) & 0xff
    }
    readOffset += rowLength
  }
  return { width, height, bytesPerPixel, pixels }
}

function baseColorTextureBuffer(
  glb: { json: any; bin: Buffer },
  materialNamePattern: RegExp,
) {
  const material = glb.json.materials.find((item: { name?: string }) => materialNamePattern.test(item.name || ''))
  assert.ok(material, `Missing material matching ${materialNamePattern}`)
  const textureIndex = material.pbrMetallicRoughness?.baseColorTexture?.index
  assert.equal(typeof textureIndex, 'number')
  const imageIndex = glb.json.textures[textureIndex].source
  const view = glb.json.bufferViews[glb.json.images[imageIndex].bufferView]
  return glb.bin.subarray(view.byteOffset || 0, (view.byteOffset || 0) + view.byteLength)
}

function colorStats(image: ReturnType<typeof decodePng>) {
  let darkPixels = 0
  let warmPixels = 0
  for (let offset = 0; offset < image.pixels.length; offset += image.bytesPerPixel) {
    const red = image.pixels[offset]!
    const green = image.pixels[offset + 1]!
    const blue = image.pixels[offset + 2]!
    if (red < 80 && green < 80 && blue < 80) darkPixels += 1
    if (red > 120 && green > 70 && blue < 100) warmPixels += 1
  }
  const totalPixels = image.width * image.height
  return {
    darkRatio: darkPixels / totalPixels,
    warmRatio: warmPixels / totalPixels,
  }
}

test('keeps production dice code inside the frontend dice feature', async () => {
  await Promise.all(Object.values(files).map((file) => access(file)))

  const playerSource = await readFile(files.player, 'utf8')
  assert.doesNotMatch(playerSource, /dice-lab/)
  assert.match(playerSource, /@\/dice\/renderer\/ThreeDice/)
})

test('keeps exactly the runtime models used by each dice skin', async () => {
  for (const directory of [files.classicModels, files.galaxyModels, files.moonwhiteModels, files.cinnabarModels]) {
    const modelFiles = (await readdir(directory)).filter((file) => file.endsWith('.glb'))
    assert.equal(modelFiles.length, 7)
  }
})

test('keeps the cinnabar dice player center light enough for the dialog', async () => {
  const source = await readFile(files.styles, 'utf8')
  const rule = source.match(/\.dice-player-surface\[data-skin="cinnabar"\]\s*\{(?<body>[^}]+)\}/)?.groups?.body
  assert.ok(rule)

  const warmOverlayOpacity = Number(rule.match(/rgba\(176,116,43,\.([0-9]+)\)/)?.[1])
  assert.ok(warmOverlayOpacity <= 14)
  assert.match(rule, /#f2e7da 54%/)
})

test('bakes every cinnabar dice material into a base color texture', async () => {
  const modelFiles = (await readdir(files.cinnabarModels)).filter((file) => file.endsWith('.glb'))

  for (const file of modelFiles) {
    const gltf = await readGlbJson(new URL(file, files.cinnabarModels))
    const unbakedMaterials = (gltf.materials || [])
      .filter((material: { name?: string; pbrMetallicRoughness?: { baseColorTexture?: unknown } }) => (
        !material.pbrMetallicRoughness?.baseColorTexture
      ))
      .map((material: { name?: string }) => material.name || '<unnamed>')
    assert.deepEqual(unbakedMaterials, [], `${file} has unbaked materials`)
  }
})

test('keeps baked cinnabar gold details warm instead of black', async () => {
  const glb = await readGlb(new URL('D10_个位骰_0-9_朱砂鎏金_baked.glb', files.cinnabarModels))

  for (const materialPattern of [/Aged_Bronze_Edges/, /Vertex_Studs/]) {
    const image = decodePng(baseColorTextureBuffer(glb, materialPattern))
    const stats = colorStats(image)
    assert.ok(stats.warmRatio > 0.05, `${materialPattern} has too few warm gold pixels`)
  }
})

test('keeps baked cinnabar gold detail atlases free of black bleed', async () => {
  const glb = await readGlb(new URL('D10_个位骰_0-9_朱砂鎏金_baked.glb', files.cinnabarModels))

  for (const materialPattern of [/Aged_Bronze_Edges/, /Vertex_Studs/]) {
    const image = decodePng(baseColorTextureBuffer(glb, materialPattern))
    const stats = colorStats(image)
    assert.ok(stats.darkRatio < 0.2, `${materialPattern} has a black atlas background that can bleed into gold edges`)
  }
})

test('keeps dice debugging inside the TRPG tools instead of a standalone demo', async () => {
  await assert.rejects(access(new URL('../../dice-lab/', import.meta.url)))
  await access(new URL('../src/dice/components/DiceDebugPanel.vue', import.meta.url))

  const toolsSource = await readFile(new URL('../src/components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  assert.match(toolsSource, /DiceDebugPanel/)
  assert.match(toolsSource, /value="dice-debug"/)
  assert.match(toolsSource, /骰子调试/)
})

test('routes frontend consumers through the dice feature hierarchy', async () => {
  const consumers = await Promise.all([
    readFile(new URL('../src/App.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/GroupChatStage.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/TrpgToolsDialog.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/composables/useWorkspace.ts', import.meta.url), 'utf8'),
  ])

  for (const source of consumers) {
    assert.doesNotMatch(source, /@\/components\/dice\//)
  }
})
