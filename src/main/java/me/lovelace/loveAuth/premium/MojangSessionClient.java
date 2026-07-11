package me.lovelace.loveAuth.premium;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal client for Mojang's session-server "hasJoined" endpoint, used to
 * cryptographically verify that a connecting client owns a genuine licensed
 * Minecraft account. No JSON library is available in this project, so
 * responses are parsed with a small embedded recursive-descent JSON parser
 * sufficient for this fixed response shape.
 */
public final class MojangSessionClient {
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record TextureProperty(String name, String value, String signature) {}

    public record MojangProfile(UUID id, String name, List<TextureProperty> properties) {}

    public CompletableFuture<MojangProfile> hasJoined(String username, String serverIdHash) {
        String url = HAS_JOINED_URL.formatted(
                URLEncoder.encode(username, StandardCharsets.UTF_8),
                serverIdHash);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200 ? parseProfile(response.body()) : null);
    }

    public void close() {
        httpClient.close();
    }

    private MojangProfile parseProfile(String json) {
        Object parsed = new JsonParser(json).parseValue();
        if (!(parsed instanceof Map<?, ?> map)) return null;
        if (!(map.get("id") instanceof String idStr) || !(map.get("name") instanceof String name)) return null;

        List<TextureProperty> props = new ArrayList<>();
        if (map.get("properties") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> propMap
                        && propMap.get("name") instanceof String pName
                        && propMap.get("value") instanceof String pValue) {
                    String pSig = propMap.get("signature") instanceof String s ? s : "";
                    props.add(new TextureProperty(pName, pValue, pSig));
                }
            }
        }
        return new MojangProfile(parseUndashedUuid(idStr), name, props);
    }

    private static UUID parseUndashedUuid(String raw) {
        String dashed = raw.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    /** Tiny JSON parser, just enough to read the hasJoined response shape. */
    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++; // ':'
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == '}') break;
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == ']') break;
            }
            return result;
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            pos += 5;
            return Boolean.FALSE;
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < s.length() && "-+.eE0123456789".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            return s.charAt(pos);
        }
    }
}
