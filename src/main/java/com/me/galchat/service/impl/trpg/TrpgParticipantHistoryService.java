package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.vo.TrpgParticipantHistoryVO;
import com.me.galchat.domain.vo.TrpgParticipantRunVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.TrpgParticipantHistoryMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TrpgParticipantHistoryService {
    private final IUserWorldPrefixService worlds;
    private final TrpgParticipantHistoryMapper mapper;

    public List<TrpgParticipantHistoryVO> summaries(Long userWorldId) {
        worlds.checkUserWorldAuth(userWorldId, false);
        return mapper.selectSummaries(userWorldId);
    }

    public RunPage runs(Long userWorldId, Long characterId, String cursor, int limit) {
        worlds.checkUserWorldAuth(userWorldId, false);
        if (!mapper.characterExists(userWorldId, characterId)) throw new UserRequestException("角色不属于当前世界");
        if (limit < 1 || limit > 50) throw new UserRequestException("每页记录数须为 1 到 50");
        LocalDateTime beforeAt = null;
        Long beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 128) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                beforeAt = LocalDateTime.parse(parts[0]);
                beforeId = Long.parseLong(parts[1]);
                if (beforeId <= 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                throw new UserRequestException("跑团记录分页标记无效，请刷新后重试");
            }
        }
        List<TrpgParticipantRunVO> rows = mapper.selectCompletedRuns(userWorldId, characterId, beforeAt, beforeId, limit + 1);
        List<TrpgParticipantRunVO> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String nextCursor = null;
        if (rows.size() > limit) {
            var last = items.getLast();
            nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.getCompletedAt() + "|" + last.getConversationId()).getBytes(StandardCharsets.UTF_8));
        }
        return new RunPage(items, nextCursor);
    }

    public record RunPage(List<TrpgParticipantRunVO> items, String nextCursor) {}
}
