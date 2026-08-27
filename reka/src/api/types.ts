export interface ApiResult<T> { code: number; msg: string; data?: T }
export interface Session { token: string; id: number | null; username: string }
export interface UserToken { token: string; id: number; username: string }
export interface UserInfo { id: number; username: string; email?: string; birthday?: string; diceSkin?: string; createTime?: string }

export interface WorldTemplate {
  id?: number; name: string; image?: string; author?: string; background?: string; authorId?: number; visible?: boolean
}
export interface UserWorld {
  id: number; userId?: number; worldId?: number; name: string; image?: string; acitvePushStatus?: boolean
  dailyCompanionMode?: boolean; favorSystemStatus?: string; eotDetectionStatus?: boolean; thinkStatus?: boolean
  addSpecialPrompt?: boolean; myWorld?: boolean
}
export interface WorldDetail { id?: number; worldId?: number; about?: string; details?: string }
export interface WorldSave {
  userWorldId: number; savedAt?: string; remark?: string
  characterFavors?: Array<{ characterId: number; characterName: string; favorValue?: number }>
}
export interface WorldArchive {
  formatVersion: number
  world: { name: string; image?: string; author?: string; background: string; visible?: boolean }
  details?: Array<{ about?: string; details?: string }>
  characters?: Array<CharacterTemplate>
}
export interface WorldArchiveResult { worldId: number; name: string; detailCount: number; characterCount: number }
export interface WorldTemplateUsage { associatedWorldCount: number; deletable: boolean }
export interface WorldArchiveReplaceResult {
  worldId: number; name: string; detailCount: number; characterCount: number
  matchedCharacterCount: number; addedCharacterCount: number; unchangedCharacterCount: number
  matchRate: number; confirmationRequired: boolean; replaced: boolean
  matchedCharacterNames: string[]; addedCharacterNames: string[]; unchangedCharacterNames: string[]
}

export interface Character {
  userWorldId: number; characterId: number; characterName: string; characterImage?: string
  lastChatTime?: string; lastChatContent?: string; favorValue?: number; userInfoPrompt?: string
}
export interface CharacterTemplate {
  id?: number; worldId?: number; name: string; image?: string; background?: string; personality?: string
  cocPlayStyle?: string; favorability?: Record<string, string>; initFavor?: number
}

export interface CocModule {
  id: number; name: string; author?: string; era?: string; introduction: string; investigatorCreation?: string
  coverUrl?: string; playerCount?: string; estimatedDuration?: string; visible: boolean; createdAt?: string; updatedAt?: string
}

export interface ChatHistory {
  id?: number; userWorldId?: number; characterId?: number; content?: string; type?: string
  userMessageId?: number; stepNo?: number; timestamp?: string
}
export interface ChatMessagePayload {
  type?: string; worldId: number; userWorldId: number; characterId: number; message: string
  isTyping?: boolean; length?: number; revision?: number; triggerType?: string
}
export interface ChatFlux { type: string; content?: string }
export interface DirectMessage {
  id: string; historyId?: number; role: 'user' | 'assistant' | 'thinking' | 'tool'; content: string; time?: string; complete?: boolean
}

export type ConversationMode = 'chat' | 'trpg'
export type ConversationStatus = 'active' | 'closed'
export type TrpgComposerIntent = 'action' | 'inquiry'
export type TrpgGameTimePeriod = 'DAWN' | 'MORNING' | 'NOON' | 'AFTERNOON' | 'EVENING' | 'LATE_NIGHT'
export interface TrpgGameTime {
  dayNo: number; period: TrpgGameTimePeriod; periodLabel: string; displayText: string; revision: number; updatedAt?: string
}
export interface Conversation {
  id: number; userWorldId: number; worldId: number; moduleId?: number; activeReplyPlanId?: number; mode: ConversationMode
  title: string; summary?: string; status: ConversationStatus; version?: number
  gameTime?: TrpgGameTime
  characterIds?: number[]
  createdAt?: string; updatedAt?: string; closedAt?: string; lastChatContent?: string; lastChatTime?: string
}
export interface GroupMessage {
  id: number; conversationId: number; turnId?: number; replyStepId?: number
  speakerType: 'user' | 'character' | 'kp' | 'narrator'; speakerId?: number; speakerName?: string
  messageKind: 'dialogue' | 'narration' | 'system_event' | 'dice_roll' | 'material' | 'combat_result' | 'epilogue'; content: string; sequenceNo: number
  diceRoll?: DiceRollAggregate
  diceRoundNos?: number[]
  decisionContent?: string; status: string; createdAt?: string
}
export interface GroupSpeaker { type: string; id?: number; name?: string; avatar?: string }
export interface GroupRouteContext {
  ownerCharacterId: number
  targetCharacterId: number
}
export interface GenerationErrorDetail {
  errorId: string
  code: string
  category: string
  message: string
  retryable: boolean
  occurredAt: string
  operation: string
  request: Record<string, unknown>
  response: Record<string, unknown>
  stack: string
}
export interface GroupChatEvent {
  eventType: 'stream.caught_up' | 'turn.accepted' | 'turn.waiting_input' | 'turn.paused' | 'reply.started' | 'reasoning.delta' | 'decision.delta' | 'decision.completed' | 'message.delta' | 'dice_roll.created' | 'material.created' | 'game_time.changed' | 'message.completed' | 'reply.failed' | 'generation.failed' | 'turn.completed' | 'scene_selection.options' | 'scene_selection.choice' | 'combat.started' | 'combat.completed'
  conversationId?: number; turnId?: number; replyStepId?: number; messageId?: number; sequence?: number
  actionType?: string; groupName?: string; itemOrder?: number; messageKind?: string; speaker?: GroupSpeaker; delta?: string; content?: string; error?: string; toolName?: string
  promptMessageId?: number; interactionType?: string; interactionSeq?: number
  diceRoll?: DiceRollAggregate
  gameTime?: TrpgGameTime
  routeContext?: GroupRouteContext
  sceneOptions?: Record<string, string>; autoSelected?: boolean
  sceneChoice?: { optionNo?: string; controllerName?: string; investigatorName?: string; locationName?: string; randomized?: boolean }
  errorDetail?: GenerationErrorDetail
}
export interface GenerationFailureState {
  conversationId: number
  turnId?: number
  replyStepId?: number
  messageId?: number
  message: string
  detail: GenerationErrorDetail
}
export interface CurrentTurn {
  turnId: number; planId?: number; planSource?: string; planContextId?: number; status: string
  stepId?: number; actionType?: string; itemOrder?: number; inputType?: 'message' | 'selection' | 'clarification' | 'continue' | 'dice'; sceneName?: string
  promptMessageId?: number; interactionType?: string; interactionSeq?: number
  waitingForUser: boolean; canAskKp?: boolean; sceneOptions: Record<string, string>; routeContext?: GroupRouteContext | null; steps: CurrentTurnStep[]
}
export interface CurrentTurnStep {
  stepId: number; itemOrder: number; actorType: string; actorId?: number; subjectCharacterId?: number
  status: string; error?: string
}
export type ReplyPlanParticipantStatus = 'ACTIVE' | 'WAITING' | 'READY'
export interface ReplyPlanItem {
  id?: number; order: number; actorType: string; actorId?: number; subjectCharacterId?: number; subjectCharacterName?: string
  participantStatus?: ReplyPlanParticipantStatus
}
export interface ReplyPlan {
  id?: number; source: 'USER' | 'SCENE' | 'COMBAT'; displayName: string
  nextPlanId?: number; resumePlanId?: number; parentPlanId?: number; items: ReplyPlanItem[]
}
export interface ReplyPlanRequest {
  source: 'USER' | 'SCENE' | 'COMBAT'; contextId?: number; executionKey: string; displayName: string; items: ReplyPlanItem[]
}

export interface ContextWindowUsage {
  characterCount: number; softLimit: number; ratio: number; updatedAt: string
}

export interface CocCharacter {
  id: number; runId: number; actorType: 'PLAYER' | 'BOT'; participantId?: number; name: string; playerName?: string
  image?: string; occupation?: string; sex?: string; age?: number; era?: string; birthplace?: string; residence?: string
  str: number; con: number; siz: number; dex: number; app: number; intValue: number; pow: number; edu: number
  damageBonus?: string; build?: number; mov?: number; hpCurrent?: number; hpMax?: number; sanCurrent?: number; sanMax?: number
  mpCurrent?: number; mpMax?: number; luckCurrent?: number; armor?: number; majorWound?: boolean; unconscious?: boolean
  dying?: boolean; dead?: boolean; temporaryInsanity?: boolean; temporaryInsanityPhase?: string
}
export interface CocSkill { id: number; characterId: number; displayName: string; category?: string; specialization?: string; baseValue?: number; value: number; isCustom?: boolean }
export interface CocWeapon { id: number; characterId: number; name: string; skillName?: string; damage?: string; range?: string; attacksPerRound?: string; ammoCapacity?: number; remainingAmmo?: number; malfunction?: string; isBroken?: boolean; abnormal?: boolean; riskTags?: string[]; notes?: string }
export interface CocProfile {
  appearance?: string; ideology?: string; significantPeople?: string; meaningfulLocations?: string; treasuredPossessions?: string
  traits?: string; keyConnectionCategory?: string; keyConnectionText?: string
  injuriesAndScars?: string; phobiasAndManias?: string; equipmentText?: string; assetsText?: string
  spendingLevel?: string; cash?: string; notes?: string
}
export interface CharacterCard { character: CocCharacter; skills: CocSkill[]; weapons: CocWeapon[]; profile?: CocProfile }
export interface DraftCharacterCard {
  character: Omit<CocCharacter, 'id' | 'runId'> & { id?: number; runId?: number }
  skills: Array<Omit<CocSkill, 'id' | 'characterId'> & { id?: number; characterId?: number }>
  weapons: Array<Omit<CocWeapon, 'id' | 'characterId'> & { id?: number; characterId?: number }>
  profile?: CocProfile
}
export interface CharacterCardBuildPlan {
  name: string; age: number; sex?: string; birthplace?: string; residence?: string; occupation?: string
  attributeOrder: string[]; occupationSkillOrder: string[]; interestSkillOrder: string[]; explanations?: string[]
}
export interface CharacterCardBackgroundRolls {
  ideology: number; significantPersonWho: number; significantPersonReason: number
  meaningfulLocation: number; treasuredPossession: number; trait: number; directions: Record<string, string>
}
export interface CharacterCardBackgroundPlan {
  appearance?: string; ideology?: string; significantPeople?: string; meaningfulLocations?: string
  treasuredPossessions?: string; traits?: string; keyConnectionCategory?: string; keyConnectionText?: string
  weaponCode?: string; equipment?: string[]
}
export interface CharacterCardCreationDraft {
  draftId: number; creationMode: string; status: string; currentStep: string; nextAction?: string; version: number
  state: {
    formatVersion: number
    buildPlan: CharacterCardBuildPlan
    buildRolls?: { luck?: number; educationChecks?: number[]; educationIncreases?: number[] }
    backgroundRolls?: CharacterCardBackgroundRolls
    backgroundPlan?: CharacterCardBackgroundPlan
    preview: DraftCharacterCard
  }
}
export interface InvestigatorCardSummary {
  cardId: number; actorType: 'PLAYER' | 'BOT'; participantId?: number; name: string; checkValues: Record<string, number>
  hpCurrent?: number; hpMax?: number; sanCurrent?: number; sanMax?: number; con?: number; armor?: number
  majorWound?: boolean; unconscious?: boolean; dying?: boolean; dead?: boolean; temporaryInsanity?: boolean
  temporaryInsanityPhase?: string; temporaryInsanityRemainingHours?: number
}

export interface TrpgCombatParticipantOverview {
  characterId: number; name: string; investigator: boolean; statuses: string[]
  hpCurrent?: number; hpMax?: number; armor?: number; dex?: number; build?: number; mov?: number; damageBonus?: string
}

export interface DiceValue { sides: number; value?: number; role?: string; selected: boolean }
export interface DiceModule { expression: string; diceCount: number; diceSides: number; modifier?: string; dice: DiceValue[]; result?: number; placeholder?: boolean }
export interface DiceResult { formula: string; modules: DiceModule[]; result?: number }
export interface DiceResolution { type?: string; sourceResultId?: number; groupRule?: 'ANY_SUCCESS' | 'ALL_SUCCESS' | 'SEPARATE'; characterName?: string; checkName?: string; difficulty?: 'REGULAR' | 'HARD' | 'EXTREME'; rule?: Record<string, unknown>; outcome?: Record<string, unknown>; effect?: Record<string, unknown> }
export interface DiceRollSummary { id: number; conversationId: number; reason?: string; totalResult?: string; roundCount?: number; status: string; toolName?: string; createdAt?: string; updatedAt?: string }
export interface DiceRollDetail { id: number; summaryId: number; characterId?: number; roundNo?: number; displayOrder?: number; displayType?: string; reason?: string; resultData?: DiceResult; resolution?: DiceResolution; resolvedAt?: string; createdAt?: string; updatedAt?: string }
export interface DiceRollProgress { summary: DiceRollSummary; rolledResult: DiceRollDetail; createdResults: DiceRollDetail[] }
export interface DiceRollAggregate { summary: DiceRollSummary; results: DiceRollDetail[]; semanticResult?: string }

export interface TrpgSaveInvestigator {
  characterId: number; name: string; hpCurrent?: number; hpMax?: number; sanCurrent?: number; sanMax?: number
  mpCurrent?: number; mpMax?: number; unconscious?: boolean; dying?: boolean; dead?: boolean
}
export interface TrpgSave {
  id: number; conversationId: number; conversationTitle?: string; remark?: string; savedAt?: string; formatVersion?: number
  activePlanSource?: string; activeSceneId?: number; investigators: TrpgSaveInvestigator[]
}
