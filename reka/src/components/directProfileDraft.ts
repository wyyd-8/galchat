export interface DirectProfileFields { userInfoPrompt: string; modelApiId: string }

/** Refresh arriving server values without overwriting fields the user has edited. */
export function mergeDirectProfileDraft(draft: DirectProfileFields, previous: DirectProfileFields, next: DirectProfileFields): DirectProfileFields {
  return {
    userInfoPrompt: draft.userInfoPrompt === previous.userInfoPrompt ? next.userInfoPrompt : draft.userInfoPrompt,
    modelApiId: draft.modelApiId === previous.modelApiId ? next.modelApiId : draft.modelApiId,
  }
}
