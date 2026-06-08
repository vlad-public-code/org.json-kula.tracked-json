package org.json_kula.tracked_json.json_pointer;

public sealed interface JsonPointerStep permits JsonPointerStep.Name, JsonPointerStep.Index {
    JsonPointerStep ROOT = new Name("");

    record Name(String value) implements JsonPointerStep {
        @Override
        public String toString() { return value; }
    }

    record Index(int value) implements JsonPointerStep {
        @Override
        public String toString() { return String.valueOf(value); }
    }
}
