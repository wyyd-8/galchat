package com.me.galchat.service.archive;

import com.me.galchat.domain.dto.*;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.impl.trpg.CocModuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class ArchiveZipService {
    private final ArchiveZipCodec codec;
    private final ObjectMapper mapper;
    private final IWorldArchiveService worlds;
    private final CocModuleService modules;
    private final TransactionTemplate transaction;

    public ArchiveZipService(ArchiveZipCodec codec, ObjectMapper mapper, IWorldArchiveService worlds,
                             CocModuleService modules, PlatformTransactionManager manager) {
        this.codec = codec;
        this.mapper = mapper;
        this.worlds = worlds;
        this.modules = modules;
        transaction = new TransactionTemplate(manager);
        // Return only after a real commit: a joined outer transaction could otherwise orphan files.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public byte[] exportWorld(Long user, Long id) {
        return codec.exportZip("world", mapper.valueToTree(worlds.exportMyWorld(user, id)));
    }

    public byte[] exportModule(Long user, Long id) {
        return codec.exportZip("module", mapper.valueToTree(modules.exportReadable(user, id)));
    }

    public WorldArchiveImportResultDTO importWorld(Long user, MultipartFile file) {
        return importArchive("world", file, WorldArchiveDTO.class,
                archive -> worlds.importWorld(user, archive), result -> true);
    }

    public CocModule importModule(Long user, MultipartFile file) {
        return importArchive("module", file, CocModuleArchiveDTO.class,
                archive -> modules.importOwned(user, archive), result -> true);
    }

    public WorldArchiveReplaceResultDTO replaceWorld(Long user, Long id, MultipartFile file, boolean confirm) {
        return importArchive("world", file, WorldArchiveDTO.class,
                archive -> worlds.replaceWorldTemplate(user, id, archive, confirm),
                WorldArchiveReplaceResultDTO::isReplaced);
    }

    private <A, R> R importArchive(String type, MultipartFile file, Class<A> archiveType,
                                   Function<A, R> action, Predicate<R> retain) {
        if (file == null || file.isEmpty()) throw new UserRequestException("请选择 ZIP 文件");
        if (file.getSize() > ArchiveZipCodec.MAX_PACKAGE_BYTES) throw new UserRequestException("ZIP 超过64MB");
        try (var input = file.getInputStream(); var imported = codec.importZip(type, input)) {
            A archive;
            try { archive = mapper.treeToValue(imported.archive(), archiveType); }
            catch (RuntimeException e) { throw new UserRequestException("归档 JSON 结构无效", e); }
            R result = transaction.execute(status -> action.apply(archive));
            if (result != null && retain.test(result)) imported.retain();
            return result;
        } catch (IOException e) { throw new UserRequestException("读取 ZIP 失败", e); }
    }
}
