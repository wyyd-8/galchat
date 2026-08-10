import assert from 'node:assert/strict'
import { access, readFile, readdir } from 'node:fs/promises'
import test from 'node:test'

const files = {
  player: new URL('../src/dice/components/DicePlayerDialog.vue', import.meta.url),
  message: new URL('../src/dice/components/DiceRollMessage.vue', import.meta.url),
  playback: new URL('../src/dice/domain/dicePlayback.ts', import.meta.url),
  renderer: new URL('../src/dice/renderer/ThreeDice.ts', import.meta.url),
  classicModels: new URL('../src/dice/assets/models/classic/', import.meta.url),
  galaxyModels: new URL('../src/dice/assets/models/galaxy/', import.meta.url),
  moonwhiteModels: new URL('../src/dice/assets/models/moonwhite/', import.meta.url),
}

test('keeps production dice code inside the frontend dice feature', async () => {
  await Promise.all(Object.values(files).map((file) => access(file)))

  const playerSource = await readFile(files.player, 'utf8')
  assert.doesNotMatch(playerSource, /dice-lab/)
  assert.match(playerSource, /@\/dice\/renderer\/ThreeDice/)
})

test('keeps exactly the runtime models used by each dice skin', async () => {
  for (const directory of [files.classicModels, files.galaxyModels, files.moonwhiteModels]) {
    const modelFiles = (await readdir(directory)).filter((file) => file.endsWith('.glb'))
    assert.equal(modelFiles.length, 7)
  }
})

test('removes the standalone and embedded dice demos', async () => {
  await assert.rejects(access(new URL('../../dice-lab/', import.meta.url)))
  await assert.rejects(access(new URL('../src/dice/components/DiceDebugPanel.vue', import.meta.url)))

  const toolsSource = await readFile(new URL('../src/components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(toolsSource, /DiceDebugPanel|dice-debug|骰子调试/)
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
