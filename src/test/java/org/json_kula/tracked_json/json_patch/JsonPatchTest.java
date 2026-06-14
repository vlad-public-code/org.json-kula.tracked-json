package org.json_kula.tracked_json.json_patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base document shared by most tests — never mutated, each apply() deep-copies it. */
    private static JsonNode DOC;

    @BeforeAll
    static void parseDocument() throws Exception {
        DOC = MAPPER.readTree("""
                { "a": 1, "b": [1, 2, 3], "c": {"d": 4} }
                """);
    }

    // ── compile ────────────────────────────────────────────────────────────────

    @Test
    void compile_notAnArrayThrows() {
        assertThrows(JsonPatchException.class,
                () -> JsonPatch.compile(MAPPER.readTree("{}")));
    }

    @Test
    void compile_unknownOpThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"op\":\"invalid\",\"path\":\"/a\"}]")));
    }

    @Test
    void compile_missingOpFieldThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"path\":\"/a\",\"value\":1}]")));
    }

    @Test
    void compile_missingPathThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"op\":\"add\",\"value\":1}]")));
    }

    @Test
    void compile_missingValueForAddThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"op\":\"add\",\"path\":\"/a\"}]")));
    }

    @Test
    void compile_missingFromForMoveThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"op\":\"move\",\"path\":\"/a\"}]")));
    }

    @Test
    void compile_pathNotStartingWithSlashThrows() {
        assertThrows(JsonPatchException.class, () ->
                JsonPatch.compile(MAPPER.readTree("[{\"op\":\"remove\",\"path\":\"a\"}]")));
    }

    @Test
    void compile_emptyPatchIsNoop() throws Exception {
        JsonPatch patch = JsonPatch.compile(MAPPER.readTree("[]"));
        assertEquals(DOC, patch.apply(DOC));
    }

    @Test
    void compile_extraFieldsInOperationAreIgnored() throws Exception {
        // RFC 6902 A.11: unrecognised members MUST be ignored
        JsonPatch patch = JsonPatch.compile(MAPPER.readTree(
                "[{\"op\":\"add\",\"path\":\"/x\",\"value\":9,\"xyz\":123}]"));
        assertEquals(9, patch.apply(DOC).get("x").asInt());
    }

    // ── add ────────────────────────────────────────────────────────────────────

    @Test
    void add_newObjectField() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/e\",\"value\":5}]");
        JsonNode result = patch.apply(DOC);
        assertEquals(5, result.get("e").asInt());
        assertEquals(1, result.get("a").asInt());   // existing field untouched
    }

    @Test
    void add_existingObjectFieldReplaces() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/a\",\"value\":99}]");
        assertEquals(99, patch.apply(DOC).get("a").asInt());
    }

    @Test
    void add_appendToArrayWithDash() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/b/-\",\"value\":4}]");
        JsonNode result = patch.apply(DOC);
        assertEquals(4, result.get("b").size());
        assertEquals(4, result.get("b").get(3).asInt());
    }

    @Test
    void add_insertIntoArrayShiftsElements() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/b/1\",\"value\":99}]");
        JsonNode result = patch.apply(DOC);
        assertEquals(4, result.get("b").size());
        assertEquals(99, result.get("b").get(1).asInt());
        assertEquals(2, result.get("b").get(2).asInt());   // was at index 1, shifted right
    }

    @Test
    void add_insertAtIndexZeroPrependsToArray() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/b/0\",\"value\":0}]");
        JsonNode result = patch.apply(DOC);
        assertEquals(0, result.get("b").get(0).asInt());
        assertEquals(1, result.get("b").get(1).asInt());
    }

    @Test
    void add_insertAtIndexEqualToSizeAppends() throws Exception {
        // inserting at index == size is same as appending
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/b/3\",\"value\":99}]");
        JsonNode result = patch.apply(DOC);
        assertEquals(4, result.get("b").size());
        assertEquals(99, result.get("b").get(3).asInt());
    }

    @Test
    void add_nestedField() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/c/e\",\"value\":10}]");
        assertEquals(10, patch.apply(DOC).get("c").get("e").asInt());
    }

    @Test
    void add_atRootReplacesEntireDocument() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"\",\"value\":42}]");
        assertEquals(42, patch.apply(DOC).asInt());
    }

    @Test
    void add_objectValue() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/x\",\"value\":{\"p\":1}}]");
        assertEquals(1, patch.apply(DOC).get("x").get("p").asInt());
    }

    @Test
    void add_nullValue() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/x\",\"value\":null}]");
        assertTrue(patch.apply(DOC).get("x").isNull());
    }

    @Test
    void add_missingParentThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"add\",\"path\":\"/x/y\",\"value\":1}]").apply(DOC));
    }

    @Test
    void add_arrayIndexOutOfRangeThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"add\",\"path\":\"/b/99\",\"value\":1}]").apply(DOC));
    }

    @Test
    void add_leadingZeroIndexThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"add\",\"path\":\"/b/01\",\"value\":1}]").apply(DOC));
    }

    @Test
    void add_doesNotMutateOriginalDocument() throws Exception {
        JsonNode original = MAPPER.readTree("{\"a\":1}");
        compile("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]").apply(original);
        assertFalse(original.has("b"));
    }

    // ── remove ─────────────────────────────────────────────────────────────────

    @Test
    void remove_objectField() throws Exception {
        JsonNode result = compile("[{\"op\":\"remove\",\"path\":\"/a\"}]").apply(DOC);
        assertFalse(result.has("a"));
        assertTrue(result.has("b"));    // siblings unaffected
    }

    @Test
    void remove_arrayElementShiftsSubsequent() throws Exception {
        JsonNode result = compile("[{\"op\":\"remove\",\"path\":\"/b/1\"}]").apply(DOC);
        assertEquals(2, result.get("b").size());
        assertEquals(1, result.get("b").get(0).asInt());
        assertEquals(3, result.get("b").get(1).asInt());   // was at index 2
    }

    @Test
    void remove_firstArrayElement() throws Exception {
        JsonNode result = compile("[{\"op\":\"remove\",\"path\":\"/b/0\"}]").apply(DOC);
        assertEquals(2, result.get("b").size());
        assertEquals(2, result.get("b").get(0).asInt());
    }

    @Test
    void remove_nonExistentFieldThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"remove\",\"path\":\"/z\"}]").apply(DOC));
    }

    @Test
    void remove_rootThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"remove\",\"path\":\"\"}]").apply(DOC));
    }

    @Test
    void remove_dashTokenThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"remove\",\"path\":\"/b/-\"}]").apply(DOC));
    }

    @Test
    void remove_arrayIndexOutOfRangeThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"remove\",\"path\":\"/b/99\"}]").apply(DOC));
    }

    // ── replace ────────────────────────────────────────────────────────────────

    @Test
    void replace_objectField() throws Exception {
        assertEquals(99, compile("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":99}]")
                .apply(DOC).get("a").asInt());
    }

    @Test
    void replace_arrayElement() throws Exception {
        JsonNode result = compile("[{\"op\":\"replace\",\"path\":\"/b/0\",\"value\":99}]").apply(DOC);
        assertEquals(3, result.get("b").size());    // same size, element replaced not inserted
        assertEquals(99, result.get("b").get(0).asInt());
    }

    @Test
    void replace_atRootReplacesEntireDocument() throws Exception {
        JsonNode result = compile("[{\"op\":\"replace\",\"path\":\"\",\"value\":{\"x\":1}}]").apply(DOC);
        assertEquals(1, result.get("x").asInt());
        assertFalse(result.has("a"));
    }

    @Test
    void replace_nonExistentFieldThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"replace\",\"path\":\"/z\",\"value\":1}]").apply(DOC));
    }

    @Test
    void replace_nonExistentArrayIndexThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"replace\",\"path\":\"/b/99\",\"value\":1}]").apply(DOC));
    }

    @Test
    void replace_dashTokenThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"replace\",\"path\":\"/b/-\",\"value\":1}]").apply(DOC));
    }

    // ── move ───────────────────────────────────────────────────────────────────

    @Test
    void move_renamedObjectField() throws Exception {
        JsonNode result = compile("[{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/z\"}]").apply(DOC);
        assertFalse(result.has("a"));
        assertEquals(1, result.get("z").asInt());
    }

    @Test
    void move_betweenObjects() throws Exception {
        JsonNode result = compile("[{\"op\":\"move\",\"from\":\"/c/d\",\"path\":\"/d\"}]").apply(DOC);
        assertFalse(result.get("c").has("d"));
        assertEquals(4, result.get("d").asInt());
    }

    @Test
    void move_arrayElementToEnd() throws Exception {
        // RFC 6902 A.7: move /foo/1 to /foo/3 in ["all","grass","cows","eat"]
        JsonNode doc = MAPPER.readTree("{\"foo\":[\"all\",\"grass\",\"cows\",\"eat\"]}");
        JsonNode result = compile("[{\"op\":\"move\",\"from\":\"/foo/1\",\"path\":\"/foo/3\"}]").apply(doc);
        assertEquals("all",   result.get("foo").get(0).asText());
        assertEquals("cows",  result.get("foo").get(1).asText());
        assertEquals("eat",   result.get("foo").get(2).asText());
        assertEquals("grass", result.get("foo").get(3).asText());
    }

    @Test
    void move_samePathIsNoop() throws Exception {
        JsonNode result = compile("[{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/a\"}]").apply(DOC);
        assertEquals(1, result.get("a").asInt());
    }

    @Test
    void move_intoOwnDescendantThrows() {
        // After removing /c the parent /c no longer exists, so the add at /c/x fails
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"move\",\"from\":\"/c\",\"path\":\"/c/x\"}]").apply(DOC));
    }

    @Test
    void move_nonExistentFromThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"move\",\"from\":\"/z\",\"path\":\"/w\"}]").apply(DOC));
    }

    // ── copy ───────────────────────────────────────────────────────────────────

    @Test
    void copy_objectField() throws Exception {
        JsonNode result = compile("[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/z\"}]").apply(DOC);
        assertTrue(result.has("a"));        // original preserved
        assertEquals(1, result.get("z").asInt());
    }

    @Test
    void copy_nestedObject() throws Exception {
        JsonNode result = compile("[{\"op\":\"copy\",\"from\":\"/c\",\"path\":\"/copy\"}]").apply(DOC);
        assertEquals(4, result.get("copy").get("d").asInt());
        // Verify it's an independent copy: mutating source should not affect copy in later ops
        JsonNode resultAfterMutate = compile("""
                [
                  {"op":"copy","from":"/c","path":"/copy"},
                  {"op":"replace","path":"/c/d","value":999}
                ]
                """).apply(DOC);
        assertEquals(4,   resultAfterMutate.get("copy").get("d").asInt());
        assertEquals(999, resultAfterMutate.get("c").get("d").asInt());
    }

    @Test
    void copy_nonExistentFromThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"copy\",\"from\":\"/z\",\"path\":\"/w\"}]").apply(DOC));
    }

    // ── test ───────────────────────────────────────────────────────────────────

    @Test
    void test_scalarValueMatches() throws Exception {
        JsonNode result = compile("[{\"op\":\"test\",\"path\":\"/a\",\"value\":1}]").apply(DOC);
        assertEquals(DOC, result);
    }

    @Test
    void test_scalarValueMismatchThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"test\",\"path\":\"/a\",\"value\":999}]").apply(DOC));
    }

    @Test
    void test_arrayValueMatches() throws Exception {
        assertDoesNotThrow(() ->
                compile("[{\"op\":\"test\",\"path\":\"/b\",\"value\":[1,2,3]}]").apply(DOC));
    }

    @Test
    void test_objectValueMatches() throws Exception {
        assertDoesNotThrow(() ->
                compile("[{\"op\":\"test\",\"path\":\"/c\",\"value\":{\"d\":4}}]").apply(DOC));
    }

    @Test
    void test_nullValue() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"a\":null}");
        assertDoesNotThrow(() ->
                compile("[{\"op\":\"test\",\"path\":\"/a\",\"value\":null}]").apply(doc));
    }

    @Test
    void test_nonExistentPathThrows() {
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"test\",\"path\":\"/z\",\"value\":1}]").apply(DOC));
    }

    // ── multi-operation sequences ──────────────────────────────────────────────

    @Test
    void multipleOperationsAppliedSequentially() throws Exception {
        JsonNode result = compile("""
                [
                  {"op":"add","path":"/e","value":5},
                  {"op":"replace","path":"/a","value":99},
                  {"op":"remove","path":"/b"}
                ]
                """).apply(DOC);
        assertEquals(5,  result.get("e").asInt());
        assertEquals(99, result.get("a").asInt());
        assertFalse(result.has("b"));
    }

    @Test
    void failedOperationHaltsSequenceAndOriginalIsUntouched() {
        assertThrows(JsonPatchException.class, () -> compile("""
                [
                  {"op":"add","path":"/x","value":1},
                  {"op":"test","path":"/a","value":999},
                  {"op":"remove","path":"/a"}
                ]
                """).apply(DOC));
        // Original must still have "a" — deep-copy means the patch was rolled back entirely
        assertTrue(DOC.has("a"));
    }

    @Test
    void patchIsReusable() throws Exception {
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/x\",\"value\":9}]");
        JsonNode r1 = patch.apply(DOC);
        JsonNode r2 = patch.apply(DOC);
        assertEquals(r1, r2);
    }

    // ── TrackedJsonNode overload ───────────────────────────────────────────────

    @Test
    void applyToTrackedJsonNodeReturnsTrackedResult() throws Exception {
        TrackedJsonNode tracked = TrackedJsonNode.ofRoot(DOC);
        JsonPatch patch = compile("[{\"op\":\"add\",\"path\":\"/x\",\"value\":7}]");
        TrackedJsonNode result = patch.apply(tracked);
        assertEquals("", result.pointer().toString());
        assertEquals(7, result.get("x").asInt());
    }

    // ── RFC 6902 Appendix A examples ──────────────────────────────────────────

    @Test
    void rfc6902_A1_addObjectMember() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"foo\":\"bar\"}");
        JsonNode result = compile("[{\"op\":\"add\",\"path\":\"/baz\",\"value\":\"qux\"}]").apply(doc);
        assertEquals("qux", result.get("baz").asText());
        assertEquals("bar", result.get("foo").asText());
    }

    @Test
    void rfc6902_A2_addArrayElement() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"foo\":[\"bar\",\"baz\"]}");
        JsonNode result = compile("[{\"op\":\"add\",\"path\":\"/foo/1\",\"value\":\"qux\"}]").apply(doc);
        assertEquals(3, result.get("foo").size());
        assertEquals("qux", result.get("foo").get(1).asText());
        assertEquals("baz", result.get("foo").get(2).asText());
    }

    @Test
    void rfc6902_A3_removeObjectMember() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"foo\":\"bar\",\"baz\":\"qux\"}");
        JsonNode result = compile("[{\"op\":\"remove\",\"path\":\"/baz\"}]").apply(doc);
        assertFalse(result.has("baz"));
        assertEquals("bar", result.get("foo").asText());
    }

    @Test
    void rfc6902_A4_removeArrayElement() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"foo\":[\"bar\",\"qux\",\"baz\"]}");
        JsonNode result = compile("[{\"op\":\"remove\",\"path\":\"/foo/1\"}]").apply(doc);
        assertEquals(2, result.get("foo").size());
        assertEquals("bar", result.get("foo").get(0).asText());
        assertEquals("baz", result.get("foo").get(1).asText());
    }

    @Test
    void rfc6902_A5_replaceValue() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"baz\":\"qux\",\"foo\":\"bar\"}");
        JsonNode result = compile("[{\"op\":\"replace\",\"path\":\"/baz\",\"value\":\"boo\"}]").apply(doc);
        assertEquals("boo", result.get("baz").asText());
    }

    @Test
    void rfc6902_A6_moveValue() throws Exception {
        JsonNode doc = MAPPER.readTree(
                "{\"foo\":{\"bar\":\"baz\",\"waldo\":\"fred\"},\"qux\":{\"corge\":\"grault\"}}");
        JsonNode result = compile("[{\"op\":\"move\",\"from\":\"/foo/waldo\",\"path\":\"/qux/thud\"}]")
                .apply(doc);
        assertFalse(result.get("foo").has("waldo"));
        assertEquals("fred", result.get("qux").get("thud").asText());
    }

    @Test
    void rfc6902_A8_testOperationSuccess() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"baz\":\"qux\",\"foo\":[\"a\",2,\"c\"]}");
        assertDoesNotThrow(() -> compile("""
                [
                  {"op":"test","path":"/baz","value":"qux"},
                  {"op":"test","path":"/foo/1","value":2}
                ]
                """).apply(doc));
    }

    @Test
    void rfc6902_A9_testOperationFailure() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"baz\":\"qux\"}");
        assertThrows(JsonPatchException.class, () ->
                compile("[{\"op\":\"test\",\"path\":\"/baz\",\"value\":\"bar\"}]").apply(doc));
    }

    @Test
    void rfc6902_A10_addNestedMemberObject() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"foo\":\"bar\"}");
        JsonNode result = compile("[{\"op\":\"add\",\"path\":\"/child\",\"value\":{\"grandchild\":{}}}]")
                .apply(doc);
        assertTrue(result.get("child").get("grandchild").isObject());
    }

    @Test
    void rfc6902_rfc6901EscapingInPath() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"a/b\":1,\"c~d\":2}");
        assertDoesNotThrow(() -> compile("""
                [
                  {"op":"test","path":"/a~1b","value":1},
                  {"op":"test","path":"/c~0d","value":2}
                ]
                """).apply(doc));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonPatch compile(String json) throws Exception {
        return JsonPatch.compile(MAPPER.readTree(json));
    }
}
