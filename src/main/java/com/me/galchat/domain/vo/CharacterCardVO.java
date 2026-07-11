package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CharacterCardVO {
    private CocCharacter character;
    private List<CocCharacterSkill> skills;
    private List<CocCharacterWeapon> weapons;
    private CocCharacterProfile profile;
}
