import { ref, watch, type Ref } from 'vue'

export function useActorOverview(blocked: Readonly<Ref<boolean>>) {
  const overviewOpen = ref(false)
  let interactionAllowed = false

  watch(blocked, () => {
    overviewOpen.value = false
    // TooltipRoot may still emit a delayed open from a hover before the modal.
    interactionAllowed = false
  }, { flush: 'sync' })

  function allowOverview() {
    interactionAllowed = !blocked.value
  }

  function updateOverview(open: boolean) {
    overviewOpen.value = open && !blocked.value && interactionAllowed
  }

  return { overviewOpen, allowOverview, updateOverview }
}
