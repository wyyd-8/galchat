package com.me.galchat.service;

import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;
import com.me.galchat.domain.dto.WorldArchiveReplaceResultDTO;
import com.me.galchat.domain.dto.WorldTemplateUsageDTO;

public interface IWorldArchiveService {

    WorldArchiveDTO exportMyWorld(Long userId, Long userWorldId);

    WorldArchiveImportResultDTO importWorld(Long userId, WorldArchiveDTO archive);

    WorldTemplateUsageDTO getWorldTemplateUsage(Long userId, Long worldId);

    void deleteWorldTemplate(Long userId, Long worldId);

    WorldArchiveReplaceResultDTO replaceWorldTemplate(Long userId, Long worldId,
                                                      WorldArchiveDTO archive, boolean confirmLowMatch);
}
