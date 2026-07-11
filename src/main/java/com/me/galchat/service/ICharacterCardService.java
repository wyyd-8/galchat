package com.me.galchat.service;

import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.DiceRollResultVO;

public interface ICharacterCardService {
    CharacterCardVO create(CharacterCardCreateDTO createDTO);
    void delete(Long id);
    CharacterCardVO getById(Long id);
    CharacterCardVO getByRunIdAndParticipantId(Long runId, Long participantId);
    DiceRollResultVO rollLuck(Long id);
}
