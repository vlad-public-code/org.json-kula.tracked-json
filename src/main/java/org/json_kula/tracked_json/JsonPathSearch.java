package org.json_kula.tracked_json;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.util.ArrayList;
import java.util.List;

public final class JsonPathSearch {

    private static final Configuration CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS, Option.ALWAYS_RETURN_LIST)
            .build();

    private JsonPathSearch() {}

    /**
     * Evaluates {@code jsonPath} relative to {@code origin} and returns all matching nodes,
     * each wrapped with its absolute {@link com.fasterxml.jackson.core.JsonPointer}.
     * Returns an empty list when nothing matches or the expression is invalid.
     */
    public static List<TrackedJsonNode> find(TrackedJsonNode origin, String jsonPath) {
        // JacksonJsonNodeJsonProvider causes AS_PATH_LIST to produce an ArrayNode of TextNodes.
        // InvalidPathException (unparseable expression) is intentionally left to propagate.
        JsonNode pathArray = JsonPath.using(CONFIG).parse(origin.node()).read(jsonPath);
        if (pathArray == null || !pathArray.isArray() || pathArray.isEmpty()) return List.of();
        List<TrackedJsonNode> result = new ArrayList<>(pathArray.size());
        for (JsonNode pathNode : pathArray) {
            TrackedJsonNode match = navigate(origin, pathNode.asText());
            if (match != null) result.add(match);
        }
        return result;
    }

    /** Navigates from {@code origin} by converting the normalized path to a {@link JsonPointer} and resolving it in one step. */
    private static TrackedJsonNode navigate(TrackedJsonNode origin, String normalizedPath) {
        JsonPointer rel = toRelativePointer(normalizedPath);
        if (rel == null) return null;
        TrackedJsonNode result = origin.at(rel);
        return result.isMissingNode() ? null : result;
    }

    /** Converts a JsonPath normalized path ({@code $['key'][idx]}) to a relative {@link JsonPointer}. */
    private static JsonPointer toRelativePointer(String normalizedPath) {
        JsonPointer pointer = JsonPointer.empty();
        int i = 1; // skip leading '$'
        while (i < normalizedPath.length()) {
            if (normalizedPath.charAt(i) != '[') { i++; continue; }
            int close = normalizedPath.indexOf(']', i + 1);
            if (close < 0) return null;
            String token = normalizedPath.substring(i + 1, close);
            if (token.length() >= 2 && token.charAt(0) == '\'' && token.charAt(token.length() - 1) == '\'') {
                pointer = pointer.appendProperty(token.substring(1, token.length() - 1).replace("\\'", "'"));
            } else {
                try {
                    pointer = pointer.appendIndex(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Can't parse normalized JSON Path", e);
                }
            }
            i = close + 1;
        }
        return pointer;
    }
}
