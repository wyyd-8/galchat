import { computed, shallowRef, watch, type Ref } from 'vue'
import type { DiceRollAggregate } from '../../api/types.ts'
import { splitDiceAggregateByRound } from '../domain/dicePlayback.ts'

export function useDiceRoundNavigation(options: {
  aggregate: Readonly<Ref<DiceRollAggregate | null>>
  open: Readonly<Ref<boolean>>
  context: () => string
  load: (summaryId: number) => Promise<DiceRollAggregate>
  onError: (error: unknown) => void
}) {
  const rounds = shallowRef<DiceRollAggregate[]>([])
  watch([options.open, options.aggregate, options.context], async ([open, aggregate, context], previous, onCleanup) => {
    rounds.value = []
    let active = true
    onCleanup(() => { active = false })
    if (!open || !aggregate || (previous[2] != null && previous[2] !== context)) return
    try {
      const full = await options.load(aggregate.summary.id)
      if (!active || full.summary.id !== aggregate.summary.id
        || full.summary.conversationId !== aggregate.summary.conversationId) return
      rounds.value = splitDiceAggregateByRound(full)
    } catch (error) {
      if (active) options.onError(error)
    }
  }, { immediate: true, flush: 'sync' })

  const index = computed(() => {
    const current = options.aggregate.value
    if (!current) return -1
    const roundNo = Math.max(1, ...current.results.map(detail => detail.roundNo || 1))
    return rounds.value.findIndex(round => (round.results[0]?.roundNo || 1) === roundNo)
  })
  return {
    roundPosition: computed(() => index.value + 1),
    roundCount: computed(() => rounds.value.length),
    previous: computed(() => index.value > 0 ? rounds.value[index.value - 1]! : null),
    next: computed(() => index.value >= 0 ? rounds.value[index.value + 1] ?? null : null),
  }
}
