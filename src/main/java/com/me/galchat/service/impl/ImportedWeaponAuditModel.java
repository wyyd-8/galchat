package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;

import java.util.List;

public interface ImportedWeaponAuditModel {

    ImportedWeaponAuditModels.Response review(
            CocCharacter character,
            List<CocCharacterWeapon> weapons);
}
