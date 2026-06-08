package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FindByJsonPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static TrackedJsonNode ROOT;

    @BeforeAll
    static void parseDocument() throws Exception {
        JsonNode doc = MAPPER.readTree("""
                {
                  "a": {
                    "b": 42,
                    "c": [10, 20, 30]
                  },
                  "arr": [{"x": 1}, {"x": 2}],
                  "": "empty-key-value",
                  "nested": {
                    "deep": {
                      "value": "found"
                    }
                  }
                }
                """);
        ROOT = TrackedJsonNode.ofRoot(doc);
    }

    @Test
    void selectRoot_returnsSelf() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$");
        assertEquals(1, result.size());
        assertEquals("", result.get(0).pointer().toString());
        assertSame(ROOT.node(), result.get(0).node());
    }

    @Test
    void selectExactPath() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.a.b");
        assertEquals(1, result.size());
        assertEquals("/a/b", result.get(0).pointer().toString());
        assertEquals(42, result.get(0).asInt());
    }

    @Test
    void selectArrayElement() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.a.c[1]");
        assertEquals(1, result.size());
        assertEquals("/a/c/1", result.get(0).pointer().toString());
        assertEquals(20, result.get(0).asInt());
    }

    @Test
    void selectPropertyWithEmptyName() {
        // JSON allows "" as a key; RFC 6901 represents it as the pointer "/"
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$['']");
        assertEquals(1, result.size());
        assertEquals("/", result.get(0).pointer().toString());
        assertEquals("empty-key-value", result.get(0).asText());
    }

    @Test
    void wildcardOnArray_returnsAllElements() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.a.c[*]");
        assertEquals(3, result.size());
        assertEquals("/a/c/0", result.get(0).pointer().toString());
        assertEquals("/a/c/1", result.get(1).pointer().toString());
        assertEquals("/a/c/2", result.get(2).pointer().toString());
    }

    @Test
    void wildcardOnObject_returnsAllDirectChildren() {
        // $.a.* → b and c
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.a.*");
        assertEquals(2, result.size());
        Set<String> pointers = result.stream()
                .map(n -> n.pointer().toString())
                .collect(Collectors.toSet());
        assertTrue(pointers.contains("/a/b"));
        assertTrue(pointers.contains("/a/c"));
    }

    @Test
    void recursiveDescentFindsAllMatches() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$..x");
        assertEquals(2, result.size());
        assertEquals("/arr/0/x", result.get(0).pointer().toString());
        assertEquals(1, result.get(0).asInt());
        assertEquals("/arr/1/x", result.get(1).pointer().toString());
        assertEquals(2, result.get(1).asInt());
    }

    @Test
    void predicateFilter_returnsOnlyMatchingElements() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.arr[?(@.x == 2)]");
        assertEquals(1, result.size());
        assertEquals("/arr/1", result.get(0).pointer().toString());
    }

    @Test
    void deeplyNestedPath() {
        List<TrackedJsonNode> result = ROOT.findByJsonPath("$.nested.deep.value");
        assertEquals(1, result.size());
        assertEquals("/nested/deep/value", result.get(0).pointer().toString());
        assertEquals("found", result.get(0).asText());
    }

    @Test
    void fromNonRoot_pointerIncludesParentSegments() {
        TrackedJsonNode a = ROOT.get("a");
        List<TrackedJsonNode> result = a.findByJsonPath("$.c[0]");
        assertEquals(1, result.size());
        assertEquals("/a/c/0", result.get(0).pointer().toString());
        assertEquals(10, result.get(0).asInt());
    }

    @Test
    void noMatch_returnsEmptyList() {
        assertTrue(ROOT.findByJsonPath("$.no_such_field").isEmpty());
    }

    @Test
    void noMatchOnDeepPath_returnsEmptyList() {
        assertTrue(ROOT.findByJsonPath("$.a.b.c.d").isEmpty());
    }

    @Test
    void invalidExpression_throwsInvalidPathException() {
        assertThrows(InvalidPathException.class,
                () -> ROOT.findByJsonPath("not a jsonpath!!!"));
    }

    @Test
    void emptyBracketExpression_throwsInvalidPathException() {
        assertThrows(InvalidPathException.class,
                () -> ROOT.findByJsonPath("$[]"));
    }
}
