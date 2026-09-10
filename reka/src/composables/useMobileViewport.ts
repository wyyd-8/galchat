import { onMounted, onUnmounted, ref } from 'vue'

export const MOBILE_QUERY = '(max-width: 767px)'

export function useMobileViewport() {
  const isMobile = ref(typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches)
  let media: MediaQueryList | undefined
  const update = () => { isMobile.value = media?.matches ?? false }
  onMounted(() => {
    media = window.matchMedia(MOBILE_QUERY)
    update()
    media.addEventListener('change', update)
  })
  onUnmounted(() => media?.removeEventListener('change', update))
  return { isMobile }
}

/** Follow the visible viewport when a mobile keyboard covers part of the page. */
export function useVisualViewport() {
  const online = ref(typeof navigator === 'undefined' || navigator.onLine)
  const updateOnline = () => { online.value = navigator.onLine }
  const update = () => {
    const viewport = window.visualViewport
    // Pinch zoom must not resize or move the application's layout.
    if (viewport && viewport.scale !== 1) return
    document.documentElement.style.setProperty('--app-viewport-height', `${viewport?.height ?? window.innerHeight}px`)
    document.documentElement.style.setProperty('--app-viewport-top', `${viewport?.offsetTop ?? 0}px`)
  }
  onMounted(() => {
    update()
    window.addEventListener('resize', update)
    window.visualViewport?.addEventListener('resize', update)
    window.visualViewport?.addEventListener('scroll', update)
    window.addEventListener('online', updateOnline)
    window.addEventListener('offline', updateOnline)
  })
  onUnmounted(() => {
    window.removeEventListener('resize', update)
    window.visualViewport?.removeEventListener('resize', update)
    window.visualViewport?.removeEventListener('scroll', update)
    window.removeEventListener('online', updateOnline)
    window.removeEventListener('offline', updateOnline)
    document.documentElement.style.removeProperty('--app-viewport-height')
    document.documentElement.style.removeProperty('--app-viewport-top')
  })
  return { online }
}
