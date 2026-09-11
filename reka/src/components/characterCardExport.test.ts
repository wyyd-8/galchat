import assert from 'node:assert/strict'
import test from 'node:test'
import { createCharacterCardExporter, suggestedCardBackground, cardPdfFilename, prepareCardPortrait } from './characterCardExport.ts'

test('optional service stays hidden when unconfigured, offline, or not the PDF service', async (t) => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => { throw new Error('offline') })
  assert.equal(await createCharacterCardExporter('').available(), false)
  assert.equal(fetchMock.mock.callCount(), 0)
  assert.equal(await createCharacterCardExporter('http://localhost:8083').available(), false)
  fetchMock.mock.mockImplementation(async () => new Response(JSON.stringify({ available: true })))
  assert.equal(await createCharacterCardExporter('/card-pdf').available(), false)
  fetchMock.mock.mockImplementation(async () => new Response(JSON.stringify({ service: 'character-card-pdf', available: true })))
  assert.equal(await createCharacterCardExporter('/card-pdf/').available(), true)
  assert.equal(fetchMock.mock.calls.at(-1)?.arguments[0], '/card-pdf/health')
})

test('sends the selected card and options directly without application credentials', async (t) => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => new Response('%PDF-test', { headers: { 'Content-Type': 'application/pdf' } }))
  const card = { character: { name: '林默', image: null }, skills: [], weapons: [] }
  const blob = await createCharacterCardExporter('http://localhost:8083').exportPdf(card as never, '1920s', 2)
  const [url, options] = fetchMock.mock.calls[0]!.arguments as unknown as [string, RequestInit]
  assert.equal(url, 'http://localhost:8083/export')
  assert.equal(options.credentials, 'omit')
  assert.deepEqual(options.headers, { 'Content-Type': 'application/json' })
  assert.deepEqual(JSON.parse(options.body as string), { card, background: '1920s', fontIndex: 2 })
  assert.equal(await blob.text(), '%PDF-test')
})

test('does not download an error response or HTML page as a PDF', async (t) => {
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ detail: '模板资源不可用' }), { status: 503 }))
  const exporter = createCharacterCardExporter('/card-pdf')
  const card = { character: {}, skills: [], weapons: [] } as never
  await assert.rejects(exporter.exportPdf(card, 'modern', 0), /模板资源不可用/)
  fetchMock.mock.mockImplementation(async () => new Response('<html>fallback</html>', { headers: { 'Content-Type': 'text/html' } }))
  await assert.rejects(exporter.exportPdf(card, 'modern', 0), /未返回 PDF/)
})

test('recognizes supported eras and sanitizes downloaded filenames', () => {
  assert.equal(suggestedCardBackground('1920年代'), '1920s')
  assert.equal(suggestedCardBackground('1920s'), '1920s')
  assert.equal(suggestedCardBackground('现代'), 'modern')
  assert.equal(suggestedCardBackground('Modern'), 'modern')
  assert.equal(suggestedCardBackground('维多利亚'), '')
  assert.equal(suggestedCardBackground(undefined), '')
  assert.equal(cardPdfFilename('林/默:调查员'), '林_默_调查员-人物卡.pdf')
})

test('portrait fallback preserves the original card and cancellation stops export preparation', async (t) => {
  const previousWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { location: { href: 'http://localhost:5173/' } } })
  t.after(() => {
    if (previousWindow) Object.defineProperty(globalThis, 'window', previousWindow)
    else Reflect.deleteProperty(globalThis, 'window')
  })
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('Image blocked by CORS') })
  const card = { character: { name: '林默', image: '/unavailable.png' }, skills: [], weapons: [] } as never
  const result = await prepareCardPortrait(card, new AbortController().signal)
  assert.equal(result.portraitOmitted, true)
  assert.equal(result.card.character.image, undefined)
  assert.equal(fetchMock.mock.callCount(), 1)
  assert.equal((card as { character: { image: string } }).character.image, '/unavailable.png')
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(prepareCardPortrait(card, controller.signal))
})
