package com.me.galchat.service;

import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollResultVO;

import java.util.List;

public interface ICharacterCardService {
    CharacterCardVO create(CharacterCardCreateDTO createDTO);
    void delete(Long id);
    CharacterCardVO getById(Long id);
    CharacterCardVO getByRunIdAndParticipantId(Long runId, Long participantId);
    DiceRollResultVO rollLuck(Long id);
    CocDiceCharacterVO requireDiceCharacter(Long runId, String characterName);
    void updateQuickNotes(Long runId, String characterName, String quickNotes);
    List<CocDiceCharacterVO> listDiceCharacters(Long runId);
    CocCharacter lockDiceCharacter(Long runId, Long cardId);
    void updateDiceCharacter(CocCharacter character);
}
