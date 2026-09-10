import { reactive } from 'vue'
import type { TrpgParticipantHistory, TrpgParticipantRun, TrpgParticipantRunPage } from '../api/types.ts'

interface HistoryApi {
  participantHistory: (worldId: number) => Promise<TrpgParticipantHistory[]>
  participantRuns: (worldId: number, characterId: number, cursor?: string) => Promise<TrpgParticipantRunPage>
}

export function createParticipantHistory(api: HistoryApi) {
  const state = reactive({
    summaries: [] as TrpgParticipantHistory[], summaryLoading: true, summaryError: '',
    runs: [] as TrpgParticipantRun[], runsLoading: false, runsError: '', nextCursor: null as string | null,
  })
  let worldId: number | null = null
  let characterId: number | null = null
  let summaryVersion = 0
  let runsVersion = 0

  async function reloadSummaries() {
    if (worldId == null) return
    const version = ++summaryVersion
    state.summaryLoading = true
    state.summaryError = ''
    try {
      const summaries = await api.participantHistory(worldId)
      if (version === summaryVersion) state.summaries = summaries
    } catch (error) {
      if (version === summaryVersion) state.summaryError = error instanceof Error ? error.message : '同行记录加载失败'
    } finally {
      if (version === summaryVersion) state.summaryLoading = false
    }
  }

  function loadWorld(id: number | null) {
    worldId = id
    characterId = null
    ++summaryVersion
    ++runsVersion
    Object.assign(state, { summaries: [], summaryError: '', summaryLoading: id != null,
      runs: [], runsError: '', runsLoading: false, nextCursor: null })
    return reloadSummaries()
  }

  async function loadRuns(append: boolean) {
    if (worldId == null || characterId == null || state.runsLoading) return
    if (append && !state.nextCursor) return
    const version = ++runsVersion
    state.runsLoading = true
    state.runsError = ''
    try {
      const page = await api.participantRuns(worldId, characterId, append ? state.nextCursor! : undefined)
      if (version !== runsVersion) return
      const existing = append ? state.runs : []
      state.runs = [...existing, ...page.items.filter(run => !existing.some(item => item.conversationId === run.conversationId))]
      state.nextCursor = page.nextCursor
    } catch (error) {
      if (version === runsVersion) state.runsError = error instanceof Error ? error.message : '跑团记录加载失败'
    } finally {
      if (version === runsVersion) state.runsLoading = false
    }
  }

  function viewCharacter(id: number | null) {
    characterId = id
    ++runsVersion
    Object.assign(state, { runs: [], runsError: '', runsLoading: false, nextCursor: null })
    return loadRuns(false)
  }

  function dispose() { ++summaryVersion; ++runsVersion; worldId = null; characterId = null }
  return { state, loadWorld, reloadSummaries, viewCharacter, loadMore: () => loadRuns(true),
    retryRuns: () => loadRuns(state.runs.length > 0), dispose }
}

export function formatCompanionDate(value: string | null): string {
  if (!value) return '日期未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '日期未知'
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}
