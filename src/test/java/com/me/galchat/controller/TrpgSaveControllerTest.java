package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.vo.TrpgSaveOverviewVO;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgSaveControllerTest {

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void saveUsesCurrentUserAndConversationId() {
        ITrpgSaveService service = mock(ITrpgSaveService.class);
        TrpgSaveController controller = new TrpgSaveController(service);
        TrpgSaveCreateDTO dto = new TrpgSaveCreateDTO().setRemark("门后");
        TrpgSaveOverviewVO overview = new TrpgSaveOverviewVO()
                .setConversationId(51L);
        CurrentHolder.setCurrentId(7);
        when(service.save(7L, 51L, dto)).thenReturn(overview);

        Result result = controller.save(51L, dto);

        assertThat(result.getCode()).isEqualTo(1);
        assertThat(result.getData()).isSameAs(overview);
        verify(service).save(7L, 51L, dto);
    }
}
