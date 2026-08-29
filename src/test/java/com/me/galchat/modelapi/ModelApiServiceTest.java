package com.me.galchat.modelapi;

import com.me.galchat.domain.dto.ModelApiSaveDTO;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.vo.ModelApiVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserModelApiMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ModelApiServiceTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    private UserModelApiMapper mapper;
    private StubProbeService probeService;
    private MapperState mapperState;

    private ModelApiKeyCipher cipher;
    private ModelApiService service;
    private AtomicReference<String> resolvedAddress;

    @BeforeEach
    void setUp() throws Exception {
        mapperState = new MapperState();
        mapper = mapperState.mapper();
        probeService = new StubProbeService();
        cipher = new ModelApiKeyCipher(MASTER_KEY);
        resolvedAddress = new AtomicReference<>("8.8.8.8");
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(InetAddress.getByName(resolvedAddress.get())));
        service = new ModelApiService(mapper, cipher, validator, probeService);
    }

    @Test
    void createStoresEncryptedKeyAndReturnsOnlyItsHint() {
        ModelApiSaveDTO dto = new ModelApiSaveDTO()
                .setName("主模型")
                .setBaseUrl("https://models.example.com/v1/")
                .setModelName("model-a")
                .setApiKey("sk-12345678");

        var result = service.create(7L, dto);

        UserModelApi saved = mapperState.inserted;
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getBaseUrl())
                .isEqualTo("https://models.example.com/v1");
        assertThat(saved.getApiKeyEncrypted())
                .doesNotContain("sk-12345678");
        assertThat(cipher.decrypt(saved.getApiKeyEncrypted()))
                .isEqualTo("sk-12345678");
        assertThat(result.getId()).isEqualTo(41L);
        assertThat(result.getApiKeyHint()).isEqualTo("…5678");
        assertThat(result.getHasApiKey()).isTrue();
    }

    @Test
    void updatePreservesKeyAndTestResultWhenOnlyDisplayNameChanges() {
        UserModelApi existing = testedRecord();
        mapperState.existing = existing;
        ModelApiSaveDTO dto = new ModelApiSaveDTO()
                .setName("备用模型")
                .setBaseUrl(existing.getBaseUrl())
                .setModelName(existing.getModelName());

        service.update(7L, 41L, dto);

        UserModelApi updated = mapperState.updated;
        assertThat(updated.getApiKeyEncrypted())
                .isEqualTo(existing.getApiKeyEncrypted());
        assertThat(updated.getStatus())
                .isEqualTo(ModelApiTestStatus.SUCCESS.name());
        assertThat(updated.getLastTestCode()).isEqualTo("OK");
    }

    @Test
    void updateResetsOldCapabilitiesWhenModelChanges() {
        UserModelApi existing = testedRecord();
        mapperState.existing = existing;
        ModelApiSaveDTO dto = new ModelApiSaveDTO()
                .setName(existing.getName())
                .setBaseUrl(existing.getBaseUrl())
                .setModelName("model-b");

        service.update(7L, 41L, dto);

        UserModelApi updated = mapperState.updated;
        assertThat(updated.getStatus())
                .isEqualTo(ModelApiTestStatus.UNTESTED.name());
        assertThat(updated.getChatCapability())
                .isEqualTo(ModelApiCapability.UNKNOWN.name());
        assertThat(updated.getLastTestCode()).isNull();
        assertThat(updated.getLastTestAt()).isNull();
    }

    @Test
    void updateStoresTheFirstApiKeyForADefaultConfiguration() {
        UserModelApi existing = testedRecord()
                .setApiKeyEncrypted(null)
                .setApiKeyHint(null)
                .setStatus("UNTESTED");
        mapperState.existing = existing;
        ModelApiSaveDTO dto = new ModelApiSaveDTO()
                .setName(existing.getName())
                .setBaseUrl(existing.getBaseUrl())
                .setModelName(existing.getModelName())
                .setApiKey("sk-first-key");

        AtomicReference<ModelApiVO> result = new AtomicReference<>();

        assertThatCode(() -> result.set(service.update(7L, 41L, dto)))
                .doesNotThrowAnyException();

        UserModelApi updated = mapperState.updated;
        assertThat(cipher.decrypt(updated.getApiKeyEncrypted()))
                .isEqualTo("sk-first-key");
        assertThat(updated.getApiKeyHint()).isEqualTo("…-key");
        assertThat(updated.getStatus()).isEqualTo("UNTESTED");
        assertThat(result.get().getHasApiKey()).isTrue();
    }

    @Test
    void deleteRejectsARecordOwnedBySomeoneElse() {
        mapperState.deleteResult = 0;

        assertThatThrownBy(() -> service.delete(7L, 41L))
                .isInstanceOf(UserRequestException.class);
    }

    @Test
    void testDecryptsKeyAndOverwritesLatestStructuredResult() {
        UserModelApi existing = testedRecord();
        mapperState.existing = existing;
        ModelApiProbeResult probe = new ModelApiProbeResult(
                ModelApiTestStatus.PARTIAL,
                ModelApiCapability.SUPPORTED,
                ModelApiCapability.SUPPORTED,
                ModelApiCapability.INCONCLUSIVE,
                ReasoningOutputStatus.NOT_DETECTED,
                "CAPABILITY_PARTIAL", "基础聊天可用，部分能力未确认");
        probeService.result = probe;

        var result = service.test(7L, 41L);

        UserModelApi updated = mapperState.testUpdated;
        assertThat(updated.getStatus()).isEqualTo("PARTIAL");
        assertThat(updated.getToolCallingCapability())
                .isEqualTo("INCONCLUSIVE");
        assertThat(updated.getLastTestAt()).isNotNull();
        assertThat(probeService.baseUrl).isEqualTo(URI.create(existing.getBaseUrl()));
        assertThat(probeService.apiKey).isEqualTo("sk-existing");
        assertThat(probeService.model).isEqualTo(existing.getModelName());
        assertThat(result.getStatus()).isEqualTo(ModelApiTestStatus.PARTIAL);
    }

    @Test
    void testRejectsADefaultConfigurationUntilItsApiKeyIsSet() {
        mapperState.existing = testedRecord()
                .setApiKeyEncrypted(null)
                .setApiKeyHint(null)
                .setStatus("UNTESTED");

        assertThatThrownBy(() -> service.test(7L, 41L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("请先配置 API Key");
        assertThat(probeService.baseUrl).isNull();
        assertThat(mapperState.testUpdated).isNull();
    }

    @Test
    void testRevalidatesDnsAndNeverContactsAHostThatNowResolvesPrivately() {
        mapperState.existing = testedRecord();
        resolvedAddress.set("127.0.0.1");

        assertThatThrownBy(() -> service.test(7L, 41L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("公网");
        assertThat(probeService.baseUrl).isNull();
        assertThat(mapperState.testUpdated).isNull();
    }

    @Test
    void updateDoesNotWriteAnythingWithoutOwnership() {
        assertThatThrownBy(() -> service.update(7L, 41L,
                new ModelApiSaveDTO().setName("x")
                        .setBaseUrl("https://models.example.com/v1")
                        .setModelName("model-a")))
                .isInstanceOf(UserRequestException.class);
        assertThat(mapperState.updated).isNull();
    }

    private UserModelApi testedRecord() {
        return new UserModelApi()
                .setId(41L)
                .setUserId(7L)
                .setName("主模型")
                .setBaseUrl("https://models.example.com/v1")
                .setModelName("model-a")
                .setApiKeyEncrypted(cipher.encrypt("sk-existing"))
                .setApiKeyHint("…ting")
                .setStatus("SUCCESS")
                .setChatCapability("SUPPORTED")
                .setStreamingCapability("SUPPORTED")
                .setToolCallingCapability("SUPPORTED")
                .setReasoningOutputStatus("NOT_DETECTED")
                .setLastTestCode("OK");
    }

    private static class StubProbeService extends ModelApiProbeService {
        private ModelApiProbeResult result;
        private URI baseUrl;
        private String apiKey;
        private String model;

        private StubProbeService() {
            super(request -> null,
                    new ModelApiProbeResponseInterpreter(new ObjectMapper()),
                    new ObjectMapper());
        }

        @Override
        public ModelApiProbeResult probe(URI baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            return result;
        }
    }

    private static class MapperState {
        private UserModelApi existing;
        private UserModelApi inserted;
        private UserModelApi updated;
        private UserModelApi testUpdated;
        private int deleteResult;

        private UserModelApiMapper mapper() {
            return (UserModelApiMapper) Proxy.newProxyInstance(
                    UserModelApiMapper.class.getClassLoader(),
                    new Class<?>[]{UserModelApiMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            if (args[0] instanceof UserModelApi value) {
                                inserted = value.setId(41L);
                                yield 1;
                            }
                            yield 0;
                        }
                        case "selectOwned" -> existing;
                        case "updateOwned" -> {
                            updated = (UserModelApi) args[0];
                            yield 1;
                        }
                        case "updateTestResult" -> {
                            testUpdated = (UserModelApi) args[0];
                            yield 1;
                        }
                        case "deleteOwned" -> deleteResult;
                        case "toString" -> "UserModelApiMapperFake";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
