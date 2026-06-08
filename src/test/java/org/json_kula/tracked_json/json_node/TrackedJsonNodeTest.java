package org.json_kula.tracked_json.json_node;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.tracked_json.json_path.InvalidPathException;
import org.json_kula.tracked_json.json_pointer.JsonPointerStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrackedJsonNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode DOC;

    @BeforeAll
    static void parseDocument() throws Exception {
        DOC = MAPPER.readTree("""
                {
                  "a": {
                    "b": 42,
                    "c": [10, 20, 30]
                  },
                  "missing_parent": null,
                  "arr": [{"x": 1}, {"x": 2}],
                  "special/key": "slash",
                  "~tilde": "tilde"
                }
                """);
    }

    @Test
    void rootHasEmptyPointer() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        assertEquals("", root.pointer().toString());
    }

    @Test
    void stepIsEmptyForRoot() {
        assertEquals(new JsonPointerStep.Name(""), TrackedJsonNode.ofRoot(DOC).step());
    }

    @Test
    void stepIsNameForObjectProperty() {
        TrackedJsonNode node = TrackedJsonNode.ofRoot(DOC).get("a").get("b");
        JsonPointerStep step = node.step();
        assertInstanceOf(JsonPointerStep.Name.class, step);
        assertEquals("b", ((JsonPointerStep.Name) step).value());
        assertEquals("b", step.toString());
    }

    @Test
    void stepIsIndexForArrayElement() {
        TrackedJsonNode node = TrackedJsonNode.ofRoot(DOC).get("a").get("c").get(1);
        JsonPointerStep step = node.step();
        assertInstanceOf(JsonPointerStep.Index.class, step);
        assertEquals(1, ((JsonPointerStep.Index) step).value());
        assertEquals("1", step.toString());
    }

    @Test
    void singleLevelGet() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode a = root.get("a");
        assertNotNull(a);
        assertEquals("/a", a.pointer().toString());
        assertTrue(a.isObject());
    }

    @Test
    void chainedGet() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode b = root.get("a").get("b");
        assertNotNull(b);
        assertEquals("/a/b", b.pointer().toString());
        assertEquals(42, b.asInt());
    }

    @Test
    void getByIndex() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode c = root.get("a").get("c");
        assertNotNull(c);
        TrackedJsonNode c0 = c.get(0);
        TrackedJsonNode c2 = c.get(2);
        assertEquals("/a/c/0", c0.pointer().toString());
        assertEquals("/a/c/2", c2.pointer().toString());
        assertEquals(10, c0.asInt());
        assertEquals(30, c2.asInt());
    }

    @Test
    void pathOnMissingFieldReturnsMissingNode() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode missing = root.path("no_such_field");
        assertTrue(missing.isMissingNode());
        assertEquals("/no_such_field", missing.pointer().toString());
    }

    @Test
    void atResolvesNodeAndExtractsPointer() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        JsonPointer ptr = JsonPointer.compile("/a/b");
        TrackedJsonNode node = root.at(ptr);
        assertFalse(node.isMissingNode());
        assertEquals("/a/b", node.pointer().toString());
        assertEquals(42, node.asInt());
    }

    @Test
    void atStringExprResolvesNodeAndExtractsPointer() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode node = root.at("/a/b");
        assertFalse(node.isMissingNode());
        assertEquals("/a/b", node.pointer().toString());
        assertEquals(42, node.asInt());
    }

    @Test
    void valuesIteratorGivesIndexedPointers() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode c = root.get("a").get("c");
        Iterator<TrackedJsonNode> it = c.values();
        List<TrackedJsonNode> elems = new ArrayList<>();
        it.forEachRemaining(elems::add);
        assertEquals(3, elems.size());
        assertEquals("/a/c/0", elems.get(0).pointer().toString());
        assertEquals("/a/c/1", elems.get(1).pointer().toString());
        assertEquals("/a/c/2", elems.get(2).pointer().toString());
    }

    @Test
    void valueStreamGivesIndexedPointers() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode c = root.get("a").get("c");
        List<TrackedJsonNode> elems = c.valueStream().toList();
        assertEquals(3, elems.size());
        assertEquals("/a/c/0", elems.get(0).pointer().toString());
        assertEquals("/a/c/1", elems.get(1).pointer().toString());
        assertEquals("/a/c/2", elems.get(2).pointer().toString());
    }

    @Test
    void propertyStreamGivesNamedPointers() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode a = root.get("a");
        Map<String, String> pointers = a.propertyStream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().pointer().toString()));
        assertEquals("/a/b", pointers.get("b"));
        assertEquals("/a/c", pointers.get("c"));
    }

    @Test
    void forEachEntryVisitsAllPropertiesWithPointers() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode a = root.get("a");
        Map<String, String> pointers = new java.util.HashMap<>();
        a.forEachEntry((key, value) -> pointers.put(key, value.pointer().toString()));
        assertEquals("/a/b", pointers.get("b"));
        assertEquals("/a/c", pointers.get("c"));
    }

    @Test
    void propertiesIterationGivesNamedPointers() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode a = root.get("a");
        Set<Map.Entry<String, TrackedJsonNode>> entries = a.properties();
        assertEquals(2, entries.size());
        Map<String, String> pointers = new java.util.HashMap<>();
        for (Map.Entry<String, TrackedJsonNode> e : entries) {
            pointers.put(e.getKey(), e.getValue().pointer().toString());
        }
        assertEquals("/a/b", pointers.get("b"));
        assertEquals("/a/c", pointers.get("c"));
    }

    @Test
    void fieldNameWithSlashIsRfc6901Escaped() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode node = root.get("special/key");
        assertNotNull(node);
        assertEquals("/special~1key", node.pointer().toString());
        assertEquals("slash", node.asText());
    }

    @Test
    void fieldNameWithTildeIsRfc6901Escaped() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode node = root.get("~tilde");
        assertNotNull(node);
        assertEquals("/~0tilde", node.pointer().toString());
        assertEquals("tilde", node.asText());
    }

    @Test
    void getOnNonExistentFieldReturnsNull() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        assertNull(root.get("nonexistent"));
    }

    @Test
    void factoryOfPreservesSuppliedPointer() {
        JsonPointer ptr = JsonPointer.compile("/x/y");
        TrackedJsonNode node = TrackedJsonNode.of(DOC.get("a"), ptr);
        assertEquals("/x/y", node.pointer().toString());
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void valuesNextBeyondEndThrowsNoSuchElementException() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        TrackedJsonNode c = root.get("a").get("c");
        Iterator<TrackedJsonNode> it = c.values();
        it.forEachRemaining(ignored -> {});
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void valuesOnEmptyArrayReturnsEmptyIterator() throws Exception {
        JsonNode emptyArr = MAPPER.readTree("[]");
        assertFalse(TrackedJsonNode.ofRoot(emptyArr).values().hasNext());
    }

    @Test
    void valuesOnScalarReturnsEmptyIterator() {
        TrackedJsonNode scalar = TrackedJsonNode.ofRoot(DOC).get("a").get("b");
        assertFalse(scalar.values().hasNext());
    }

    @Test
    void stepDecodesRfc6901FieldName() {
        TrackedJsonNode node = TrackedJsonNode.ofRoot(DOC).get("special/key");
        assertNotNull(node);
        JsonPointerStep step = node.step();
        assertInstanceOf(JsonPointerStep.Name.class, step);
        assertEquals("special/key", ((JsonPointerStep.Name) step).value());
    }

    @Test
    void atEmptyPointerFromNonRootReturnsSamePointer() {
        TrackedJsonNode a = TrackedJsonNode.ofRoot(DOC).get("a");
        TrackedJsonNode same = a.at(JsonPointer.empty());
        assertEquals("/a", same.pointer().toString());
        assertEquals(a.node(), same.node());
    }

    @Test
    void pathIntOnNonArrayReturnsMissingNodeWithPointer() {
        TrackedJsonNode obj = TrackedJsonNode.ofRoot(DOC).get("a");
        TrackedJsonNode result = obj.path(0);
        assertTrue(result.isMissingNode());
        assertEquals("/a/0", result.pointer().toString());
    }

    @Test
    void getIntOutOfBoundsReturnsNull() {
        TrackedJsonNode c = TrackedJsonNode.ofRoot(DOC).get("a").get("c");
        assertNull(c.get(99));
    }

    @Test
    void propertyStreamOnEmptyObjectIsEmpty() throws Exception {
        JsonNode emptyObj = MAPPER.readTree("{}");
        assertEquals(0, TrackedJsonNode.ofRoot(emptyObj).propertyStream().count());
    }

    @Test
    void chainedAtFromNonRootExtendsPointer() {
        TrackedJsonNode a = TrackedJsonNode.ofRoot(DOC).get("a");
        TrackedJsonNode b = a.at("/b");
        assertEquals("/a/b", b.pointer().toString());
        assertEquals(42, b.asInt());
    }

    // ── find() ────────────────────────────────────────────────────────────────

    @Test
    void findExactPath_returnsNodeWithCorrectPointer() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        List<TrackedJsonNode> result = root.findByJsonPath("$.a.b");
        assertEquals(1, result.size());
        assertEquals("/a/b", result.get(0).pointer().toString());
        assertEquals(42, result.get(0).asInt());
    }

    @Test
    void findRecursiveDescent_returnsAllMatches() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        List<TrackedJsonNode> result = root.findByJsonPath("$..x");
        assertEquals(2, result.size());
        assertEquals("/arr/0/x", result.get(0).pointer().toString());
        assertEquals("/arr/1/x", result.get(1).pointer().toString());
    }

    @Test
    void findFromNonRoot_pointerIncludesParentPath() {
        TrackedJsonNode a = TrackedJsonNode.ofRoot(DOC).get("a");
        List<TrackedJsonNode> result = a.findByJsonPath("$.b");
        assertEquals(1, result.size());
        assertEquals("/a/b", result.get(0).pointer().toString());
        assertEquals(42, result.get(0).asInt());
    }

    @Test
    void findArrayElement_returnsCorrectIndexedPointer() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        List<TrackedJsonNode> result = root.findByJsonPath("$.a.c[1]");
        assertEquals(1, result.size());
        assertEquals("/a/c/1", result.get(0).pointer().toString());
        assertEquals(20, result.get(0).asInt());
    }

    @Test
    void findWithPredicate_returnsMatchingNode() {
        TrackedJsonNode root = TrackedJsonNode.ofRoot(DOC);
        List<TrackedJsonNode> result = root.findByJsonPath("$.arr[?(@.x == 1)]");
        assertEquals(1, result.size());
        assertEquals("/arr/0", result.get(0).pointer().toString());
    }

    @Test
    void findNoMatch_returnsEmptyList() {
        assertTrue(TrackedJsonNode.ofRoot(DOC).findByJsonPath("$.no_such_field").isEmpty());
    }

    @Test
    void findInvalidExpression_throwsInvalidPathException() {
        assertThrows(InvalidPathException.class,
                () -> TrackedJsonNode.ofRoot(DOC).findByJsonPath("not a jsonpath!!!"));
    }
}
