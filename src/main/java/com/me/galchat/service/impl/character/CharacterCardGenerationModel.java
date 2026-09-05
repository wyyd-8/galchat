package com.me.galchat.service.impl.character;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.vo.CharacterCardVO;
import java.util.List;

public interface CharacterCardGenerationModel {

    CharacterCardGenerationModels.BuildPlan generateBuild(
            CharacterTemplate template,
            CocModule module,
            List<String> availableSkills);

    CharacterCardGenerationModels.BackgroundPlan generateBackground(
            CharacterTemplate template,
            CocModule module,
            CharacterCardVO card,
            CharacterCardGenerationModels.BackgroundRolls rolls,
            List<CharacterCardGenerationModels.AvailableWeapon> weapons);
}
