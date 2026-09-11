package com.me.galchat.service.impl.character;

import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import java.util.List;
import java.util.Map;

public interface ImportedWeaponAuditModel {

    ImportedWeaponAuditModels.Response review(
            CocCharacter character,
            List<CocCharacterWeapon> weapons,
            List<CocWeaponCatalogConstant.WeaponDefinition> catalog,
            Map<String, String> genericTypeDefaults);
}
