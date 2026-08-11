package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.TrpgAutoSave;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface TrpgAutoSaveMapper extends BaseMapper<TrpgAutoSave> {

    @Insert("""
            INSERT INTO trpg_auto_save (
                conversation_id, saved_at, format_version, snapshot
            ) VALUES (
                #{autoSave.conversationId}, #{autoSave.savedAt},
                #{autoSave.formatVersion},
                #{autoSave.snapshot,typeHandler=com.me.galchat.typehandler.JsonbTypeHandler}
            )
            ON CONFLICT (conversation_id) DO UPDATE SET
                saved_at = EXCLUDED.saved_at,
                format_version = EXCLUDED.format_version,
                snapshot = EXCLUDED.snapshot
            """)
    int upsert(@Param("autoSave") TrpgAutoSave autoSave);
}
