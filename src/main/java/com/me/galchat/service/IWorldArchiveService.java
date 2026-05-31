package com.me.galchat.service;

import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;

public interface IWorldArchiveService {

    WorldArchiveDTO exportMyWorld(Long userId, Long userWorldId);

    WorldArchiveImportResultDTO importWorld(Long userId, WorldArchiveDTO archive);
}
