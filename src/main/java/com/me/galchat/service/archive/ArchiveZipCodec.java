package com.me.galchat.service.archive;

import com.me.galchat.constant.ImageConstant;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.ImageSecurityUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.zip.*;

/** Portable archives contain only explicit image fields, never arbitrary server paths. */
@org.springframework.stereotype.Component
public class ArchiveZipCodec {
    public static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGES = 256;
    private static final Set<String> IMAGE_FIELDS = Set.of("image", "imageUrl", "coverUrl", "avatarUrl");
    private final ObjectMapper mapper;
    private final Path directory;

    @org.springframework.beans.factory.annotation.Autowired
    public ArchiveZipCodec(ObjectMapper mapper) {
        this(mapper, Path.of(ImageConstant.LOCAL_UPLOAD_DIR));
    }

    public ArchiveZipCodec(ObjectMapper mapper, Path directory) {
        this.mapper = mapper;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public byte[] exportZip(String type, JsonNode archive) {
        JsonNode copy = archive.deepCopy();
        Map<String, byte[]> images = new LinkedHashMap<>();
        Map<String, String> references = new HashMap<>();
        long[] total = {0};
        rewriteArchive(type, copy, value -> references.computeIfAbsent(value, url -> {
            String local = ImageSecurityUtils.normalizeLocalImageUrl(url);
            String name = local.substring(ImageConstant.LOCAL_UPLOAD_URL_PREFIX.length());
            Path source = directory.resolve(name);
            try {
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UserRequestException("归档图片不存在: " + local);
                }
                byte[] bytes;
                try (var in = Files.newInputStream(source)) { bytes = readLimited(in, (int) ImageConstant.MAX_IMAGE_SIZE); }
                ImageSecurityUtils.validateImageBytes(name, bytes);
                total[0] += bytes.length;
                if (images.size() >= MAX_IMAGES || total[0] > MAX_PACKAGE_BYTES - MAX_JSON_BYTES) {
                    throw new UserRequestException("归档图片数量或总大小超限");
                }
                String relative = "images/" + name;
                images.put(relative, bytes);
                return relative;
            } catch (IOException e) { throw new UserRequestException("无法读取归档图片: " + local, e); }
        }));
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("packageVersion", 1);
        manifest.put("type", type);
        manifest.set("archive", copy);
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        if (json.length > MAX_JSON_BYTES) throw new UserRequestException("归档 JSON 超过4MB");
        try {
            var out = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(out)) {
                writeEntry(zip, "manifest.json", json);
                for (var entry : images.entrySet()) writeEntry(zip, entry.getKey(), entry.getValue());
            }
            if (out.size() > MAX_PACKAGE_BYTES) throw new UserRequestException("ZIP 超过64MB");
            return out.toByteArray();
        } catch (IOException e) { throw new UserRequestException("无法生成 ZIP", e); }
    }

    public Imported importZip(String type, InputStream input) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // Never extract caller-supplied paths to disk. Both compressed and expanded sizes are bounded.
        try (var zip = new ZipInputStream(new LimitedInputStream(input, MAX_PACKAGE_BYTES))) {
            long total = 0;
            int count = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_IMAGES + 2) throw new UserRequestException("ZIP 条目数量超限");
                String name = entry.getName();
                if (entry.isDirectory() && name.equals("images/")) continue;
                if (!name.equals("manifest.json") && !name.matches("images/[a-zA-Z0-9-]+\\.(jpg|jpeg|png|gif|webp|bmp)")) {
                    throw new UserRequestException("ZIP 包含非法路径或不支持的文件: " + name);
                }
                if (entries.containsKey(name)) throw new UserRequestException("ZIP 包含重复文件: " + name);
                byte[] bytes = readLimited(zip, name.equals("manifest.json") ? MAX_JSON_BYTES : (int) ImageConstant.MAX_IMAGE_SIZE);
                total += bytes.length;
                if (total > MAX_PACKAGE_BYTES) throw new UserRequestException("ZIP 解压后超过64MB");
                entries.put(name, bytes);
            }
        } catch (IOException e) { throw new UserRequestException("ZIP 文件无效或读取失败", e); }
        byte[] json = entries.remove("manifest.json");
        if (entries.size() > MAX_IMAGES) throw new UserRequestException("归档图片数量超限");
        if (json == null) throw new UserRequestException("ZIP 缺少 manifest.json");
        JsonNode manifest;
        try { manifest = mapper.readTree(json); }
        catch (RuntimeException e) { throw new UserRequestException("manifest.json 无效", e); }
        if (manifest == null || !manifest.path("packageVersion").isIntegralNumber()
                || manifest.path("packageVersion").asInt() != 1 || !type.equals(manifest.path("type").asString())
                || !manifest.path("archive").isObject()) throw new UserRequestException("不支持的归档版本或类型");
        JsonNode archive = manifest.get("archive");
        for (var entry : entries.entrySet()) ImageSecurityUtils.validateImageBytes(entry.getKey(), entry.getValue());
        Map<String, String> urls = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        rewriteArchive(type, archive, relative -> {
            if (!entries.containsKey(relative)) { missing.add(relative); return relative; }
            return urls.computeIfAbsent(relative, name -> ImageConstant.LOCAL_UPLOAD_URL_PREFIX
                    + UUID.randomUUID() + ImageSecurityUtils.getLowercaseExtension(name));
        });
        if (!missing.isEmpty()) throw new UserRequestException("归档缺少图片: " + String.join(", ", missing));
        if (urls.size() != entries.size()) throw new UserRequestException("ZIP 包含未引用的图片");
        Imported imported = new Imported(archive);
        try {
            Files.createDirectories(directory);
            for (var entry : urls.entrySet()) {
                Path target = directory.resolve(entry.getValue().substring(ImageConstant.LOCAL_UPLOAD_URL_PREFIX.length()));
                // CREATE_NEW avoids overwriting any existing upload, including symlinks.
                try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    imported.created.add(target);
                    out.write(entries.get(entry.getKey()));
                }
            }
            return imported;
        } catch (IOException | RuntimeException e) {
            try { imported.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw new UserRequestException("保存归档图片失败", e);
        }
    }

    private void rewriteArchive(String type, JsonNode archive, UnaryOperator<String> transform) {
        if ("world".equals(type)) {
            rewriteField(archive.path("world"), "image", transform);
            for (JsonNode character : archive.path("characters")) rewriteField(character, "image", transform);
        } else if ("module".equals(type)) {
            JsonNode module = archive.path("module");
            rewriteField(module, "coverUrl", transform);
            for (JsonNode material : module.path("materials")) rewriteField(material, "imageUrl", transform);
            rewrite(module.path("characters"), transform);
        } else throw new UserRequestException("不支持的归档类型");
    }

    private void rewriteField(JsonNode node, String key, UnaryOperator<String> transform) {
        if (!node.isObject()) return; // Existing archive services validate required business fields.
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return;
        if (!value.isString()) throw new UserRequestException("图片字段必须是字符串: " + key);
        if (!value.asString().isBlank()) ((ObjectNode) node).put(key, transform.apply(value.asString().trim()));
    }

    private void rewrite(JsonNode node, UnaryOperator<String> transform) {
        if (node.isObject()) {
            var object = (ObjectNode) node;
            for (String key : new ArrayList<>(object.propertyNames())) {
                JsonNode value = object.get(key);
                if (IMAGE_FIELDS.contains(key) && !value.isNull()) {
                    rewriteField(object, key, transform);
                } else rewrite(value, transform);
            }
        } else if (node.isArray()) for (JsonNode child : node) rewrite(child, transform);
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new UserRequestException("归档文件大小超限");
        return bytes;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long limit) { super(in); remaining = limit; }
        @Override public int read() throws IOException {
            int result = super.read();
            if (result >= 0 && --remaining < 0) throw new UserRequestException("ZIP 超过64MB");
            return result;
        }
        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            int count = in.read(bytes, off, (int) Math.min(len, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new UserRequestException("ZIP 超过64MB");
            return count;
        }
    }

    public static class Imported implements AutoCloseable {
        private final JsonNode archive;
        private final List<Path> created = new ArrayList<>();
        private boolean retained;
        Imported(JsonNode archive) { this.archive = archive; }
        public JsonNode archive() { return archive; }
        public void retain() { retained = true; }
        @Override public void close() {
            if (retained) return;
            RuntimeException failure = null;
            for (Path path : created) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) {
                    if (failure == null) failure = new IllegalStateException("清理导入图片失败", e);
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
