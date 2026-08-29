package com.me.galchat.modelapi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkModelApiHttpTransportTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBearerJsonRequestAndReadsRegularResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        JdkModelApiHttpTransport transport = transport(1024);

        ModelApiHttpTransport.Response response = transport.post(request(
                "/v1/chat/completions", "{\"model\":\"a\"}", false));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"ok\":true}");
        assertThat(authorization.get()).isEqualTo("Bearer sk-secret");
        assertThat(requestBody.get()).isEqualTo("{\"model\":\"a\"}");
    }

    @Test
    void readsSseLinesAndDoesNotFollowRedirects() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stream", exchange -> {
            byte[] body = "data: {\"chunk\":1}\n\ndata: [DONE]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/stream");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        JdkModelApiHttpTransport transport = transport(1024);

        ModelApiHttpTransport.Response stream = transport.post(
                request("/stream", "{}", true));
        ModelApiHttpTransport.Response redirect = transport.post(
                request("/redirect", "{}", false));

        assertThat(stream.lines()).containsExactly(
                "data: {\"chunk\":1}", "", "data: [DONE]");
        assertThat(redirect.statusCode()).isEqualTo(302);
    }

    @Test
    void rejectsResponseLargerThanConfiguredLimit() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = "x".repeat(65).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> transport(64).post(
                request("/large", "{}", false)))
                .isInstanceOf(ModelApiTransportException.class)
                .extracting(error -> ((ModelApiTransportException) error).getCode())
                .isEqualTo("RESPONSE_TOO_LARGE");
    }

    private JdkModelApiHttpTransport transport(int maxBytes) {
        return new JdkModelApiHttpTransport(
                Duration.ofSeconds(2), Duration.ofSeconds(2), maxBytes);
    }

    private ModelApiHttpTransport.Request request(
            String path, String body, boolean streaming) {
        return new ModelApiHttpTransport.Request(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
                "sk-secret", body, streaming);
    }
}
