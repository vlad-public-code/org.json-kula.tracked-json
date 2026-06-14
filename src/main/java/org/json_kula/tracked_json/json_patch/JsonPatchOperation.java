package org.json_kula.tracked_json.json_patch;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

sealed interface JsonPatchOperation permits
        JsonPatchOperation.Add,
        JsonPatchOperation.Remove,
        JsonPatchOperation.Replace,
        JsonPatchOperation.Move,
        JsonPatchOperation.Copy,
        JsonPatchOperation.Test {

    record Add(JsonPointer path, JsonNode value) implements JsonPatchOperation {}
    record Remove(JsonPointer path) implements JsonPatchOperation {}
    record Replace(JsonPointer path, JsonNode value) implements JsonPatchOperation {}
    record Move(JsonPointer from, JsonPointer path) implements JsonPatchOperation {}
    record Copy(JsonPointer from, JsonPointer path) implements JsonPatchOperation {}
    record Test(JsonPointer path, JsonNode value) implements JsonPatchOperation {}
}
