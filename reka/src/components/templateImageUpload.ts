import { ref } from 'vue'

export function createTemplateImageUpload(options: {
  upload: (file: File) => Promise<string>
  update: (image: string) => void
  busy: (value: boolean) => void
}) {
  const uploading = ref(false)
  const error = ref('')
  const filename = ref('')
  let revision = 0

  async function select(file: File) {
    if (uploading.value) return
    const request = ++revision
    const previousFilename = filename.value
    error.value = ''
    filename.value = file.name
    uploading.value = true
    options.busy(true)
    try {
      const image = await options.upload(file)
      if (request !== revision) return
      if (!image) throw new Error('上传未返回图片地址，请重试')
      options.update(image)
    } catch (cause) {
      if (request !== revision) return
      filename.value = previousFilename
      error.value = cause instanceof Error ? cause.message : '图片上传失败，请重新选择'
    } finally {
      if (request === revision) {
        uploading.value = false
        options.busy(false)
      }
    }
  }

  function remove() {
    if (uploading.value) return
    filename.value = ''
    error.value = ''
    options.update('')
  }

  function reset() {
    revision += 1
    if (uploading.value) options.busy(false)
    uploading.value = false
    filename.value = ''
    error.value = ''
  }

  return { uploading, error, filename, select, remove, reset }
}
