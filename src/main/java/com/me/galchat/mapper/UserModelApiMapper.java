package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.me.galchat.domain.po.UserModelApi;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserModelApiMapper extends BaseMapper<UserModelApi> {
    @Select("""
            SELECT * FROM user_model_api
            WHERE id = #{id} AND user_id = #{userId}
            LIMIT 1
            """)
    UserModelApi selectOwned(
            @Param("id") Long id,
            @Param("userId") Long userId);

    @Select("""
            SELECT * FROM user_model_api
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<UserModelApi> selectByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE user_model_api SET
                name = #{name},
                base_url = #{baseUrl},
                model_name = #{modelName},
                api_key_encrypted = #{apiKeyEncrypted},
                api_key_hint = #{apiKeyHint},
                status = #{status},
                chat_capability = #{chatCapability},
                streaming_capability = #{streamingCapability},
                tool_calling_capability = #{toolCallingCapability},
                reasoning_output_status = #{reasoningOutputStatus},
                last_test_code = #{lastTestCode},
                last_test_message = #{lastTestMessage},
                last_test_at = #{lastTestAt},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateOwned(UserModelApi modelApi);

    @Update("""
            UPDATE user_model_api SET
                status = #{status},
                chat_capability = #{chatCapability},
                streaming_capability = #{streamingCapability},
                tool_calling_capability = #{toolCallingCapability},
                reasoning_output_status = #{reasoningOutputStatus},
                last_test_code = #{lastTestCode},
                last_test_message = #{lastTestMessage},
                last_test_at = #{lastTestAt},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateTestResult(UserModelApi modelApi);

    @Delete("""
            DELETE FROM user_model_api
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int deleteOwned(
            @Param("id") Long id,
            @Param("userId") Long userId);
}
