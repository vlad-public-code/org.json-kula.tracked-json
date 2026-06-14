package org.json_kula.tracked_json.json_patch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Each JSON file provides two sections:
 *  "ops"    – patches that must apply successfully; result is compared to "expected"
 *  "errors" – patches that must throw JsonPatchException
 *
 * invalid-patches.json uses a flat array format (with // comments) and contains
 * only compile-time invalid patches; each is applied to an empty document.
 */
class JsonPatchComplianceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mapper that accepts // and /* comments (needed for invalid-patches.json). */
    @SuppressWarnings("deprecation")
    private static final ObjectMapper MAPPER_COMMENTS = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    private static final JsonNode EMPTY_DOC = MAPPER.createObjectNode();

    /** Files that follow the {ops:[…], errors:[…]} schema. */
    private static final String[] STANDARD_FILES = {
            "add.json", "remove.json", "replace.json",
            "move.json", "copy.json", "test.json",
            "rfc6902-samples.json", "js-libs-samples.json"
    };

    // ── Success cases ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("successCases")
    void apply_succeeds(String description, JsonNode patch, JsonNode doc, JsonNode expected)
            throws Exception {
        JsonNode result = JsonPatch.compile(patch).apply(doc);
        assertEquals(expected, result, description);
    }

    static Stream<Arguments> successCases() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        for (String file : STANDARD_FILES) {
            JsonNode root = load(file, MAPPER);
            JsonNode ops = root.path("ops");
            for (int i = 0; i < ops.size(); i++) {
                JsonNode tc = ops.get(i);
                if (tc.path("disabled").asBoolean(false)) continue;
                if (!tc.has("expected"))                  continue;
                cases.add(Arguments.of(
                        label(file, "ops", i, tc),
                        tc.get("op"),
                        tc.get("node"),
                        tc.get("expected")));
            }
        }
        return cases.stream();
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCases")
    void apply_throws(String description, JsonNode patch, JsonNode doc) {
        assertThrows(JsonPatchException.class,
                () -> JsonPatch.compile(patch).apply(doc),
                description);
    }

    static Stream<Arguments> errorCases() throws Exception {
        List<Arguments> cases = new ArrayList<>();

        // Standard {ops, errors} files
        for (String file : STANDARD_FILES) {
            JsonNode root = load(file, MAPPER);
            JsonNode errors = root.path("errors");
            for (int i = 0; i < errors.size(); i++) {
                JsonNode tc = errors.get(i);
                if (tc.path("disabled").asBoolean(false)) continue;
                JsonNode doc = tc.has("node") ? tc.get("node") : EMPTY_DOC;
                cases.add(Arguments.of(
                        label(file, "errors", i, tc),
                        tc.get("op"),
                        doc));
            }
        }

        // invalid-patches.json: flat array of patch-arrays, all invalid at compile time
        JsonNode invalidPatches = load("invalid-patches.json", MAPPER_COMMENTS);
        for (int i = 0; i < invalidPatches.size(); i++) {
            cases.add(Arguments.of(
                    "invalid-patches.json[" + i + "]",
                    invalidPatches.get(i),
                    EMPTY_DOC));
        }

        return cases.stream();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonNode load(String filename, ObjectMapper mapper) throws Exception {
        try (InputStream is = JsonPatchComplianceTest.class
                .getResourceAsStream("/json_patch/" + filename)) {
            if (is == null) throw new IllegalStateException("Test resource not found: " + filename);
            return mapper.readTree(is);
        }
    }

    private static String label(String file, String section, int index, JsonNode tc) {
        String msg = tc.path("message").asText("").trim();
        String base = file + " " + section + "[" + index + "]";
        return msg.isEmpty() ? base : base + ": " + msg;
    }
}
