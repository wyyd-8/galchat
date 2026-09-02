package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleCharacter;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleContext;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class CocModuleDetailVO {

    private CocModule module;
    private CocModuleContext context;
    private List<CocModuleLocation> locations;
    private List<CocModuleClue> clues;
    private List<CocModuleMaterial> materials;
    private List<CocModuleCharacter> characters;
}
