package com.business.discovery.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Gemini-native pass-through proxy for Cline (Cline_Integration_Documentation.md §5.2, revised).
 *
 * Cline (in the sidecar) uses its NATIVE {@code gemini} provider with the base URL pointed here, so it
 * speaks Gemini's own API — no OpenAI-compatibility shim (which mishandles function/tool calling, breaking
 * MCP). This app is the sole holder of the Gemini key: it validates the sidecar's internal token, swaps in
 * the real key, and forwards {@code /v1beta/**} (incl. {@code :streamGenerateContent} SSE) to Google.
 *
 * The route lives outside {@code /api/**} so the operator-only POST rule in
 * {@link com.business.discovery.configuration.SecurityConfig} doesn't apply; access is gated by the
 * internal token (sent by Cline's provider as {@code x-goog-api-key}) instead.
 */
@Slf4j
@RestController
public class ClineLlmProxyController {

    @Value("${cline.proxy.internal-token:}")
    private String internalToken;

    // Gemini native API host root; Cline's gemini provider adds the /v1beta/... path itself.
    @Value("${cline.proxy.gemini-base-url}")
    private String geminiBaseUrl;

    @Value("${langchain4j.google-ai-gemini.chat-model.api-key}")
    private String geminiApiKey;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @RequestMapping(value = "/v1beta/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<StreamingResponseBody> forward(
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) throws Exception {

        if (!authorized(request)) {
            log.warn("[ClineProxy] Rejected {} {} — missing/invalid internal token",
                    request.getMethod(), request.getRequestURI());
            return ResponseEntity.status(401)
                    .body(out -> out.write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8)));
        }

        // Rebuild the upstream URL: host root + the original /v1beta/... path + query (with the fake key
        // stripped — the real key goes in the x-goog-api-key header).
        String cleanedQuery = stripKeyParam(request.getQueryString());
        String target = geminiBaseUrl + request.getRequestURI()
                + (StringUtils.hasText(cleanedQuery) ? "?" + cleanedQuery : "");

        boolean isPost = "POST".equalsIgnoreCase(request.getMethod());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(Duration.ofMinutes(10))
                .header("x-goog-api-key", geminiApiKey)
                .header("Content-Type", "application/json");

        String accept = request.getHeader("Accept");
        builder.header("Accept", accept != null ? accept : "application/json");

        builder.method(request.getMethod(),
                isPost && body != null
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        String contentType = resp.headers().firstValue("content-type").orElse("application/json");

        if (status != 200) {
            String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            log.warn("[ClineProxy] Upstream Gemini {} for {} — {}", status, request.getRequestURI(), truncate(errBody));
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(out -> out.write(errBody.getBytes(StandardCharsets.UTF_8)));
        }

        InputStream upstreamStream = resp.body();
        StreamingResponseBody relay = out -> pump(upstreamStream, out);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(MediaType.parseMediaType(contentType))
                .body(relay);
    }

    // Copy the upstream body to the client as it arrives, flushing so SSE chunks aren't buffered.
    private void pump(InputStream in, OutputStream out) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception e) {
            log.warn("[ClineProxy] Stream relay interrupted: {}", e.getMessage());
        }
    }

    // Cline's gemini provider (@google/genai) sends the key as the x-goog-api-key header; some paths use
    // a ?key= query param. Accept either as the internal token.
    private boolean authorized(HttpServletRequest request) {
        if (!StringUtils.hasText(internalToken)) {
            return true; // dev convenience — no token configured
        }
        String headerKey = request.getHeader("x-goog-api-key");
        if (internalToken.equals(headerKey)) {
            return true;
        }
        return internalToken.equals(queryParam(request.getQueryString(), "key"));
    }

    private String stripKeyParam(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        return Arrays.stream(query.split("&"))
                .filter(p -> !p.startsWith("key="))
                .collect(Collectors.joining("&"));
    }

    private String queryParam(String query, String name) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
