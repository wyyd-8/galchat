import assert from 'node:assert/strict'
import test from 'node:test'
import { createTemplateImageUpload } from './templateImageUpload.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const file = new File(['image'], '新封面.png', { type: 'image/png' })

test('keeps the current image until upload succeeds and reports busy state', async () => {
  const pending = deferred<string>()
  let image = 'old.png'
  const busy: boolean[] = []
  const upload = createTemplateImageUpload({
    upload: () => pending.promise, update: value => { image = value }, busy: value => busy.push(value),
  })
  const operation = upload.select(file)
  assert.equal(upload.uploading.value, true)
  assert.equal(image, 'old.png')
  pending.resolve('new.png')
  await operation
  assert.equal(image, 'new.png')
  assert.equal(upload.filename.value, '新封面.png')
  assert.deepEqual(busy, [true, false])
})

test('failed replacement keeps the previous image and allows retry', async () => {
  let image = 'old.png'
  let fail = true
  const upload = createTemplateImageUpload({
    upload: async () => { if (fail) throw new Error('图片不能超过 4MB'); return 'new.png' },
    update: value => { image = value }, busy() {},
  })
  await upload.select(file)
  assert.equal(image, 'old.png')
  assert.equal(upload.error.value, '图片不能超过 4MB')
  assert.equal(upload.uploading.value, false)
  fail = false
  await upload.select(file)
  assert.equal(image, 'new.png')
  assert.equal(upload.error.value, '')
})

test('closing the editor ignores a late upload result and releases busy state', async () => {
  const pending = deferred<string>()
  let image = 'old.png'
  const busy: boolean[] = []
  const upload = createTemplateImageUpload({
    upload: () => pending.promise, update: value => { image = value }, busy: value => busy.push(value),
  })
  const operation = upload.select(file)
  upload.reset()
  pending.resolve('late.png')
  await operation
  assert.equal(image, 'old.png')
  assert.equal(upload.uploading.value, false)
  assert.deepEqual(busy, [true, false])
})

test('blocks duplicate uploads and removal while an upload is pending', async () => {
  const pending = deferred<string>()
  const uploaded: File[] = []
  let image = 'old.png'
  const upload = createTemplateImageUpload({
    upload: selected => { uploaded.push(selected); return pending.promise },
    update: value => { image = value }, busy() {},
  })
  const operation = upload.select(file)
  await upload.select(file)
  upload.remove()
  assert.deepEqual(uploaded, [file])
  assert.equal(image, 'old.png')
  pending.resolve('new.png')
  await operation
  upload.remove()
  assert.equal(image, '')
  assert.equal(upload.filename.value, '')
})

test('an empty server response does not remove the current image', async () => {
  let image = 'old.png'
  const upload = createTemplateImageUpload({ upload: async () => '', update: value => { image = value }, busy() {} })
  await upload.select(file)
  assert.equal(image, 'old.png')
  assert.ok(upload.error.value)
})
