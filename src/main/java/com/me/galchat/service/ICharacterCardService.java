package com.me.galchat.service;

import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.dto.KpWeaponStateDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollResultVO;

import java.util.List;

public interface ICharacterCardService {
    CharacterCardVO create(CharacterCardCreateDTO createDTO);
    void delete(Long id);
    CharacterCardVO getById(Long id);
    List<CocDiceCharacterVO> listInvestigatorCards(Long runId);
    DiceRollResultVO rollLuck(Long id);
    CocDiceCharacterVO requireDiceCharacter(Long runId, String characterName);
    void updateQuickNotes(Long runId, String characterName, String quickNotes);
    KpWeaponStateDTOs.Result updateWeaponState(
            Long runId, String characterName, String weaponName,
            KpWeaponStateDTOs.Update update);
    CocCharacterWeapon requireWeaponForUpdate(
            Long runId, String characterName, String weaponName);
    void updateWeapon(CocCharacterWeapon weapon);
    KpCharacterAttributeDTOs.Result adjustBasicAttributes(
            Long runId, String characterName,
            KpCharacterAttributeDTOs.Adjustments adjustments);
    void rollbackBasicAttributeAdjustment(
            Long runId, KpCharacterAttributeDTOs.Result executedResult);
    List<CocDiceCharacterVO> listDiceCharacters(Long runId);
    CocCharacter lockDiceCharacter(Long runId, Long cardId);
    void updateDiceCharacter(CocCharacter character);
}
