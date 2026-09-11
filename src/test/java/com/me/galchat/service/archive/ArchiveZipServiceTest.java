package com.me.galchat.service.archive;

import com.me.galchat.domain.dto.*;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.impl.trpg.CocModuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveZipServiceTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();
    IWorldArchiveService worlds = mock(IWorldArchiveService.class);
    CocModuleService modules = mock(CocModuleService.class);
    MockMultipartFile file() throws Exception {
        Files.write(dir.resolve("abc.png"), Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="));
        byte[] zip = new ArchiveZipCodec(mapper, dir).exportZip("world", mapper.readTree("{\"formatVersion\":1,\"world\":{\"name\":\"test\",\"image\":\"/uploads/abc.png\"}}"));
        Files.delete(dir.resolve("abc.png"));
        return new MockMultipartFile("file", "world.zip", "application/zip", zip);
    }
    long files() throws Exception { try(var files = Files.list(dir)) { return files.count(); } }
    ArchiveZipService service(boolean commitFails) {
        var tx = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) { if(commitFails) throw new IllegalStateException("commit failed"); }
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        return new ArchiveZipService(new ArchiveZipCodec(mapper, dir), mapper, worlds, modules, tx);
    }
    @Test void retainsImagesOnlyAfterSuccessfulCommit() throws Exception {
        var input = file();
        when(worlds.importWorld(eq(1L), any())).thenAnswer(call -> {
            WorldArchiveDTO archive = call.getArgument(1);
            assertTrue(Files.exists(dir.resolve(archive.getWorld().getImage().substring(9))));
            return new WorldArchiveImportResultDTO().setWorldId(99L);
        });
        assertEquals(99L, service(false).importWorld(1L, input).getWorldId());
        assertEquals(1, files());
    }
    @Test void databaseFailureRemovesImages() throws Exception {
        var input = file();
        when(worlds.importWorld(eq(1L), any())).thenThrow(new IllegalArgumentException("invalid world"));
        assertThrows(IllegalArgumentException.class, () -> service(false).importWorld(1L, input));
        assertEquals(0, files());
    }
    @Test void commitFailureRemovesImages() throws Exception {
        var input = file();
        when(worlds.importWorld(eq(1L), any())).thenReturn(new WorldArchiveImportResultDTO());
        assertThrows(IllegalStateException.class, () -> service(true).importWorld(1L, input));
        assertEquals(0, files());
    }
    @Test void confirmationResponseRemovesImages() throws Exception {
        var input = file();
        when(worlds.replaceWorldTemplate(eq(1L), eq(2L), any(), eq(false)))
                .thenReturn(new WorldArchiveReplaceResultDTO().setConfirmationRequired(true).setReplaced(false));
        assertTrue(service(false).replaceWorld(1L, 2L, input, false).isConfirmationRequired());
        assertEquals(0, files());
    }
    @Test void confirmedReplacementRetainsImages() throws Exception {
        var input = file();
        when(worlds.replaceWorldTemplate(eq(1L), eq(2L), any(), eq(true)))
                .thenReturn(new WorldArchiveReplaceResultDTO().setReplaced(true));
        assertTrue(service(false).replaceWorld(1L, 2L, input, true).isReplaced());
        assertEquals(1, files());
    }

    @Test void moduleImportRewritesCoverAndRetainsImages() throws Exception {
        Files.write(dir.resolve("abc.png"), Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="));
        byte[] zip = new ArchiveZipCodec(mapper, dir).exportZip("module", mapper.readTree("{\"formatVersion\":1,\"module\":{\"name\":\"test\",\"coverUrl\":\"/uploads/abc.png\"}}"));
        Files.delete(dir.resolve("abc.png"));
        when(modules.importOwned(eq(1L), any())).thenAnswer(call -> {
            CocModuleArchiveDTO archive = call.getArgument(1);
            assertNotEquals("/uploads/abc.png", archive.getModule().getCoverUrl());
            assertTrue(Files.exists(dir.resolve(archive.getModule().getCoverUrl().substring(9))));
            return new com.me.galchat.domain.po.CocModule().setId(5L);
        });
        assertEquals(5L, service(false).importModule(1L, new MockMultipartFile("file", zip)).getId());
        assertEquals(1, files());
    }
}
