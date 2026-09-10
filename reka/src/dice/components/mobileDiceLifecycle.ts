import { watch, type Ref } from 'vue'

export function watchMobileDicePreparation(
  isOpen: () => boolean,
  requestId: () => number | undefined,
  tray: Ref<HTMLElement | null>,
  renderLayer: Ref<HTMLElement | null>,
  prepare: () => void,
) {
  // Portal children can mount after the open transition; the actual hosts are the readiness signal.
  return watch([isOpen, requestId, tray, renderLayer], () => {
    if (isOpen() && requestId() != null && tray.value && renderLayer.value) prepare()
  }, { immediate: true, flush: 'post' })
}
