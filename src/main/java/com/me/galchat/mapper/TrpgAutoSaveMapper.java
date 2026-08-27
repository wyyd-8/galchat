package com.me.galchat.mapper;

import com.me.galchat.domain.po.TrpgAutoSave;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TrpgAutoSaveMapper {

    @Insert("""
            INSERT INTO trpg_auto_save (
                conversation_id, checkpoint_type, saved_at, format_version, snapshot
            ) VALUES (
                #{autoSave.conversationId}, #{autoSave.checkpointType},
                #{autoSave.savedAt},
                #{autoSave.formatVersion},
                #{autoSave.snapshot,typeHandler=com.me.galchat.typehandler.JsonbTypeHandler}
            )
            ON CONFLICT (conversation_id, checkpoint_type) DO UPDATE SET
                saved_at = EXCLUDED.saved_at,
                format_version = EXCLUDED.format_version,
                snapshot = EXCLUDED.snapshot
            """)
    int upsert(@Param("autoSave") TrpgAutoSave autoSave);

    @Results(id = "trpgAutoSaveResultMap", value = {
            @Result(column = "snapshot", property = "snapshot",
                    typeHandler = com.me.galchat.typehandler.JsonbTypeHandler.class)
    })
    @Select("""
            SELECT * FROM trpg_auto_save
            WHERE conversation_id = #{conversationId}
              AND checkpoint_type = #{checkpointType}
            LIMIT 1
            """)
    TrpgAutoSave selectByConversationAndType(
            @Param("conversationId") Long conversationId,
            @Param("checkpointType") String checkpointType);

    @ResultMap("trpgAutoSaveResultMap")
    @Select("""
            SELECT * FROM trpg_auto_save
            WHERE conversation_id = #{conversationId}
            ORDER BY saved_at DESC, checkpoint_type
            """)
    List<TrpgAutoSave> selectByConversationId(
            @Param("conversationId") Long conversationId);

    @Delete("""
            DELETE FROM trpg_auto_save
            WHERE conversation_id = #{conversationId}
              AND saved_at > #{savedAt}
            """)
    int deleteAfter(
            @Param("conversationId") Long conversationId,
            @Param("savedAt") java.time.LocalDateTime savedAt);
}
