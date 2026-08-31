package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CocCharacterCreationDraftMapper
        extends BaseMapper<CocCharacterCreationDraft> {

    @Select("SELECT * FROM coc_character_creation_draft WHERE id = #{id} FOR UPDATE")
    CocCharacterCreationDraft selectByIdForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE coc_character_creation_draft
            SET status = #{draft.status},
                current_step = #{draft.currentStep},
                next_action = #{draft.nextAction},
                operation_status = #{draft.operationStatus},
                version = #{draft.version},
                rules_version = #{draft.rulesVersion},
                state = #{draft.state,typeHandler=com.me.galchat.typehandler.JsonbTypeHandler},
                last_request_id = #{draft.lastRequestId},
                last_action = #{draft.lastAction},
                last_error_code = #{draft.lastErrorCode},
                result_character_id = #{draft.resultCharacterId},
                updated_at = #{draft.updatedAt}
            WHERE id = #{draft.id} AND version = #{expectedVersion}
            """)
    int updateWithExpectedVersion(
            @Param("draft") CocCharacterCreationDraft draft,
            @Param("expectedVersion") Integer expectedVersion);
}
