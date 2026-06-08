package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the JSONPath Compliance Test Suite (CTS) against {@link JsonPathSearch}.
 *
 * Test data: src/test/resources/cts.json
 * Source:    https://github.com/jsonpath-standard/jsonpath-compliance-test-suite
 *
 * Skipped: tests tagged "function" (length, match, search, count, value, keys, …
 *           — function extensions are not implemented).
 */
class JsonPathSearchComplianceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("testCases")
    void compliance(String name,
                    String selector,
                    JsonNode document,
                    boolean invalidSelector,
                    boolean multipleOrderings,
                    JsonNode expected,
                    JsonNode resultPaths) {

        if (invalidSelector) {
            TrackedJsonNode dummy = TrackedJsonNode.ofRoot(JsonNodeFactory.instance.objectNode());
            assertThrows(InvalidPathException.class,
                    () -> JsonPathSearch.find(dummy, selector),
                    "Expected InvalidPathException for: " + selector);
            return;
        }

        TrackedJsonNode root = TrackedJsonNode.ofRoot(document);
        List<TrackedJsonNode> results = JsonPathSearch.find(root, selector);
        List<JsonNode> actual = results.stream().map(TrackedJsonNode::node).toList();

        if (!multipleOrderings) {
            // CTS guarantees a single valid ordering — compare exactly.
            assertEquals(toList(expected), actual,
                    "Value mismatch for selector: " + selector);
        } else {
            // Object-property iteration order is implementation-defined;
            // CTS supplies every valid permutation in "results".
            boolean matched = false;
            for (JsonNode ordering : expected) {
                if (toList(ordering).equals(actual)) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched,
                    "Result " + actual + " did not match any expected ordering for: " + selector);
        }

        // Verify JsonPointer of each result matches the CTS-supplied normalized path.
        // result_paths is absent on invalid-selector tests, multi-ordering tests, and some others.
        if (resultPaths != null && !multipleOrderings) {
            List<JsonPointer> actualPointers = results.stream()
                    .map(TrackedJsonNode::pointer).toList();
            List<JsonPointer> expectedPointers = StreamSupport.stream(resultPaths.spliterator(), false)
                    .map(n -> toRelativePointer(n.asText()))
                    .toList();
            assertEquals(expectedPointers, actualPointers,
                    "Pointer mismatch for selector: " + selector);
        }
    }

    static Stream<Arguments> testCases() throws Exception {
        JsonNode cts = MAPPER.readTree(
                JsonPathSearchComplianceTest.class.getResourceAsStream("/cts.json"));

        return StreamSupport.stream(cts.get("tests").spliterator(), false)
                .filter(t -> {
                    // A few CTS entries omit "document" and "result" for non-invalid-selector
                    // cases that require unsupported extensions; skip them safely.
                    boolean invalid = t.has("invalid_selector") && t.get("invalid_selector").asBoolean();
                    return invalid || t.has("document");
                })
                .map(t -> {
                    String name     = t.get("name").asText();
                    String selector = t.get("selector").asText();
                    boolean invalid = t.has("invalid_selector") && t.get("invalid_selector").asBoolean();
                    JsonNode document = t.get("document");
                    // "results" (plural) = multiple valid orderings allowed (object wildcard, etc.)
                    boolean multi   = t.has("results");
                    JsonNode expected    = multi ? t.get("results") : t.get("result");
                    JsonNode resultPaths = t.get("result_paths"); // null when absent
                    return Arguments.of(name, selector, document, invalid, multi, expected, resultPaths);
                });
    }

    private static boolean hasTag(JsonNode test, String tag) {
        JsonNode tags = test.get("tags");
        if (tags == null || !tags.isArray()) return false;
        for (JsonNode t : tags) if (tag.equals(t.asText())) return true;
        return false;
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(list::add);
        return list;
    }

    /** Converts a JSONPath normalized path ({@code $['key'][idx]}) to a {@link JsonPointer}. */
    private static JsonPointer toRelativePointer(String normalizedPath) {
        JsonPointer pointer = JsonPointer.empty();
        int i = 1; // skip leading '$'
        while (i < normalizedPath.length()) {
            if (normalizedPath.charAt(i) != '[') { i++; continue; }
            i++; // skip '['
            if (i < normalizedPath.length() && normalizedPath.charAt(i) == '\'') {
                // Single-quoted property name: scan to the closing unescaped quote
                i++; // skip opening quote
                StringBuilder name = new StringBuilder();
                while (i < normalizedPath.length() && normalizedPath.charAt(i) != '\'') {
                    char c = normalizedPath.charAt(i++);
                    if (c == '\\' && i < normalizedPath.length()) {
                        char esc = normalizedPath.charAt(i++);
                        switch (esc) {
                            case '\'' -> name.append('\'');
                            case '\\' -> name.append('\\');
                            case 'b'  -> name.append('\b');
                            case 'f'  -> name.append('\f');
                            case 'n'  -> name.append('\n');
                            case 'r'  -> name.append('\r');
                            case 't'  -> name.append('\t');
                            case 'u'  -> {
                                name.append((char) Integer.parseInt(
                                        normalizedPath.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default   -> name.append(esc);
                        }
                    } else {
                        name.append(c);
                    }
                }
                i++; // skip closing quote
                i++; // skip ']'
                pointer = pointer.appendProperty(name.toString());
            } else {
                // Array index
                int close = normalizedPath.indexOf(']', i);
                pointer = pointer.appendIndex(Integer.parseInt(normalizedPath.substring(i, close)));
                i = close + 1;
            }
        }
        return pointer;
    }
}
