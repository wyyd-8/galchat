package com.me.galchat.modelapi;

import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicHttpsUrlValidatorTest {

    @Test
    void normalizesPublicHttpsBaseUrlWithoutChangingItsPath() throws Exception {
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(InetAddress.getByName("8.8.8.8")));

        assertThat(validator.validateAndNormalize(
                " https://models.example.com/openai/v1/ ").toString())
                .isEqualTo("https://models.example.com/openai/v1");
    }

    @Test
    void rejectsUnsafeUrlShapesBeforeAnyRequestCanBeSent() throws Exception {
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(InetAddress.getByName("8.8.8.8")));

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "http://models.example.com/v1"))
                .isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize(
                "https://user@models.example.com/v1"))
                .isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize(
                "https://models.example.com/v1?target=internal"))
                .isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize(
                "https://models.example.com/v1#fragment"))
                .isInstanceOf(UserRequestException.class);
    }

    @Test
    void rejectsHostWhenAnyResolvedAddressIsNotPublic() throws Exception {
        PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                host -> List.of(
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("10.0.0.7")));

        assertThatThrownBy(() -> validator.validateAndNormalize(
                "https://models.example.com/v1"))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("公网");
    }

    @Test
    void rejectsLoopbackLinkLocalAndIpv6UniqueLocalAddresses() throws Exception {
        for (String address : List.of("127.0.0.1", "169.254.169.254", "::1", "fc00::1")) {
            PublicHttpsUrlValidator validator = new PublicHttpsUrlValidator(
                    host -> List.of(InetAddress.getByName(address)));

            assertThatThrownBy(() -> validator.validateAndNormalize(
                    "https://models.example.com/v1"))
                    .isInstanceOf(UserRequestException.class);
        }
    }
}
