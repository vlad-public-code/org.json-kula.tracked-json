# TrackedJSON

A thin wrapper around Jackson's `JsonNode` that carries its `JsonPointer` — the node's absolute location within the document. Every navigation call propagates tracking automatically, so you always know where a value came from.

## Why

When you traverse a JSON document with plain `JsonNode`, you lose location information. Error messages become vague ("invalid value") and diagnostics require re-traversal. `TrackedJsonNode` keeps pointer and value together at all times.

## Requirements

- Java 21
- Jackson Databind 2.18+
- Jayway JsonPath 2.9+ (only if you use `findByJsonPath`)

## Quick start

```java
ObjectMapper mapper = new ObjectMapper();
JsonNode doc = mapper.readTree(json);
TrackedJsonNode root = TrackedJsonNode.ofRoot(doc);

TrackedJsonNode value = root.get("order").get("items").get(0).get("price");
System.out.println(value.asDouble());          // 9.99
System.out.println(value.pointer().toString()); // /order/items/0/price
```

## API

### Factory

```java
TrackedJsonNode.ofRoot(JsonNode root)        // pointer = ""
TrackedJsonNode.of(JsonNode node, JsonPointer pointer) // arbitrary position
```

### Navigation — pointer extends automatically

| Method | Missing behaviour |
|---|---|
| `get(String field)` | returns `null` |
| `get(int index)` | returns `null` |
| `path(String field)` | returns MissingNode-wrapped node |
| `path(int index)` | returns MissingNode-wrapped node |
| `at(JsonPointer rel)` | returns MissingNode-wrapped node |
| `at(String jsonPtrExpr)` | returns MissingNode-wrapped node |

Field names containing `/` or `~` are RFC 6901-escaped in the pointer automatically.

### Iteration

```java
// Array elements or object values — each wrapped with its pointer
Iterator<TrackedJsonNode>  values()
Stream<TrackedJsonNode>    valueStream()

// Object properties
Stream<Map.Entry<String, TrackedJsonNode>>   propertyStream()
Set<Map.Entry<String, TrackedJsonNode>>      properties()
void forEachEntry(BiConsumer<String, TrackedJsonNode> action)
```

### JSONPath search

```java
List<TrackedJsonNode> results = root.findByJsonPath("$.store.book[*].author");
// each result carries its absolute pointer
results.forEach(n -> System.out.println(n.pointer() + " = " + n.asText()));
```

The search is evaluated relative to the node it is called on, not the document root. Throws `InvalidPathException` for malformed expressions; returns an empty list when nothing matches.

### Step — last navigation segment

```java
JsonPointerStep step = node.step();
switch (step) {
    case JsonPointerStep.Name(var name) -> System.out.println("field: " + name);
    case JsonPointerStep.Index(var idx) -> System.out.println("index: " + idx);
}
```

`JsonPointerStep.ROOT` (`Name("")`) is returned for the root node.

### Accessors and delegation

```java
JsonNode    node()      // raw Jackson node
JsonPointer pointer()   // absolute path from document root

// Type checks: isObject, isArray, isValueNode, isMissingNode, isNull,
//              isTextual, isNumber, isBoolean, has(String), has(int), size()
// Value reads:  asText, asInt, asLong, asDouble, asBoolean
```

## Build

```
mvn test
```
