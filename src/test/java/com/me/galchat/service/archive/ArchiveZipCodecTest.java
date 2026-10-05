package com.me.galchat.service.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveZipCodecTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();
    byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @Test void roundTripRewritesAndDeduplicatesImages() throws Exception {
        Files.write(dir.resolve("abc.png"), png);
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("{\"formatVersion\":1,\"world\":{\"image\":\"/uploads/abc.png\"},\"characters\":[{\"image\":\"/uploads/abc.png\"}]}");
        byte[] zip = codec.exportZip("world", archive);
        Files.delete(dir.resolve("abc.png"));
        try (var imported = codec.importZip("world", new ByteArrayInputStream(zip))) {
            String url = imported.archive().at("/world/image").asString();
            assertTrue(url.startsWith("/uploads/"));
            assertNotEquals("/uploads/abc.png", url);
            assertEquals(url, imported.archive().at("/characters/0/image").asString());
            assertArrayEquals(png, Files.readAllBytes(dir.resolve(url.substring(9))));
            imported.retain();
        }
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test void abandonedImportRemovesNewFiles() throws Exception {
        Files.write(dir.resolve("abc.png"), png);
        var codec = new ArchiveZipCodec(mapper, dir);
        byte[] zip = codec.exportZip("world", mapper.readTree("{\"world\":{\"image\":\"/uploads/abc.png\"}}"));
        try (var imported = codec.importZip("world", new ByteArrayInputStream(zip))) { assertNotNull(imported.archive()); }
        try (var files = Files.list(dir)) { assertEquals(List.of("abc.png"), files.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test void rejectsTraversalMissingImagesWrongTypeAndInvalidImageWithoutWriting() throws Exception {
        var codec = new ArchiveZipCodec(mapper, dir);
        for (var entries : List.of(
                Map.of("../escape.png", png),
                Map.of("manifest.json", manifest("world", "images/a.png")),
                Map.of("manifest.json", manifest("module", "")),
                Map.of("manifest.json", manifest("world", "images/a.png"), "images/a.png", new byte[]{1,2,3}),
                Map.of("manifest.json", manifest("world", "/uploads/abc.png")),
                Map.of("manifest.json", manifest("world", "images/a.png"), "images/a.png", new byte[4 * 1024 * 1024 + 1]))) {
            assertThrows(com.me.galchat.exception.UserRequestException.class, () -> codec.importZip("world", new ByteArrayInputStream(zip(entries))));
            try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
        }
    }
    @Test void worldFavorabilityKeysAreNotImageFields() throws Exception {
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("{\"world\":{},\"characters\":[{\"favorability\":{\"image\":5}}]}");
        byte[] zip = codec.exportZip("world", archive);
        try (var imported = codec.importZip("world", new ByteArrayInputStream(zip))) {
            assertEquals(5, imported.archive().at("/characters/0/favorability/image").asInt());
        }
    }

    @Test void moduleCoverMaterialsAndCharacterImagesRoundTrip() throws Exception {
        Files.write(dir.resolve("abc.png"), png);
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("{\"module\":{\"coverUrl\":\"/uploads/abc.png\",\"materials\":[{\"imageUrl\":\"/uploads/abc.png\"}],\"characters\":[{\"character\":{\"avatarUrl\":\"/uploads/abc.png\"}}]}}");
        byte[] zip = codec.exportZip("module", archive);
        try (var imported = codec.importZip("module", new ByteArrayInputStream(zip))) {
            String url = imported.archive().at("/module/coverUrl").asString();
            assertNotEquals("/uploads/abc.png", url);
            assertEquals(url, imported.archive().at("/module/materials/0/imageUrl").asString());
            assertEquals(url, imported.archive().at("/module/characters/0/character/avatarUrl").asString());
        }
    }

    @Test void moduleExportClearsNonLocalImagesButKeepsLocalImagesAndOriginalData() throws Exception {
        Files.write(dir.resolve("abc.png"), png);
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("""
                {"module":{"coverUrl":"https://example.com/cover.png",
                  "introduction":"![正文图片](https://example.com/story.png)",
                  "materials":[{"imageUrl":"/uploads/abc.png"},{"imageUrl":"https://example.com/material.png"}],
                  "characters":[{"character":{"avatarUrl":"//example.com/avatar.png"}}]}}
                """);
        var original = archive.deepCopy();
        byte[] exported = codec.exportZip("module", archive);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exported))) {
            assertEquals("manifest.json", zip.getNextEntry().getName());
            var packed = mapper.readTree(zip.readAllBytes()).path("archive");
            assertEquals("", packed.at("/module/coverUrl").asString());
            assertEquals("", packed.at("/module/materials/1/imageUrl").asString());
            assertEquals("", packed.at("/module/characters/0/character/avatarUrl").asString());
            assertEquals("images/abc.png", packed.at("/module/materials/0/imageUrl").asString());
            assertEquals("![正文图片](https://example.com/story.png)", packed.at("/module/introduction").asString());
            assertEquals("images/abc.png", zip.getNextEntry().getName());
            assertArrayEquals(png, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
        try (var imported = codec.importZip("module", new ByteArrayInputStream(exported))) {
            assertEquals("", imported.archive().at("/module/coverUrl").asString());
            String local = imported.archive().at("/module/materials/0/imageUrl").asString();
            assertArrayEquals(png, Files.readAllBytes(dir.resolve(local.substring(9))));
        }
        assertEquals(original, archive);
    }

    @Test void worldExportClearsNonLocalImagesWithoutChangingOriginalData() throws Exception {
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("""
                {"world":{"image":"https://example.com/world.png"},
                 "characters":[{"image":"https://example.com/avatar.png"}]}
                """);
        var original = archive.deepCopy();
        byte[] exported = codec.exportZip("world", archive);
        try (var imported = codec.importZip("world", new ByteArrayInputStream(exported))) {
            assertEquals("", imported.archive().at("/world/image").asString());
            assertEquals("", imported.archive().at("/characters/0/image").asString());
        }
        assertEquals(original, archive);
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test void exportRejectsMissingImagesAndSymlinks() throws Exception {
        var codec = new ArchiveZipCodec(mapper, dir);
        var archive = mapper.readTree("{\"world\":{\"image\":\"/uploads/abc.png\"}}");
        assertThrows(com.me.galchat.exception.UserRequestException.class, () -> codec.exportZip("world", archive));
        Files.write(dir.resolve("def.png"), png);
        Files.createSymbolicLink(dir.resolve("abc.png"), dir.resolve("def.png"));
        assertThrows(com.me.galchat.exception.UserRequestException.class, () -> codec.exportZip("world", archive));
    }

    byte[] manifest(String type, String image) { return ("{\"packageVersion\":1,\"type\":\""+type+"\",\"archive\":{\"world\":{\"image\":\""+image+"\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    byte[] zip(Map<String, byte[]> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try(var zip = new ZipOutputStream(out)) { for(var e : entries.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); } }
        return out.toByteArray();
    }
}
