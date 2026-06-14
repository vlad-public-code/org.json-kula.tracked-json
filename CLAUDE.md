# CLAUDE.md — TrackedJSON

## Project purpose

`TrackedJsonNode` is a Jackson `JsonNode` decorator that carries the node's `JsonPointer` (absolute location in the document). Navigation propagates the pointer automatically. It is a foundational type for the broader `json_kula` ecosystem.

## Package and build

- Root package: `org.json_kula.tracked_json`
- Java 21, Maven
- `mvn test` to verify

## Package layout

| Package | Key files |
|---|---|
| `org.json_kula.tracked_json.json_node` | `TrackedJsonNode.java` — core wrapper |
| `org.json_kula.tracked_json.json_pointer` | `JsonPointerStep.java` — last-segment descriptor |
| `org.json_kula.tracked_json.json_path` | `JsonPathSearch`, `JsonPathExpression`, `InvalidPathException` (public); `JsonPathParser`, `Step`, `StepVisitor`, `StepEvaluator`, `FilterExpr`, `FilterValue` (package-private) |
| `org.json_kula.tracked_json.json_patch` | `JsonPatch`, `JsonPatchException` (public); `JsonPatchOperation` (package-private) |

## Source files

| File | Role |
|---|---|
| `json_node/TrackedJsonNode.java` | Core wrapper — navigation, iteration, delegation |
| `json_pointer/JsonPointerStep.java` | Sealed interface: `Name(String)` / `Index(int)` representing the last navigation step |
| `json_path/JsonPathSearch.java` | Public API — evaluates JSONPath expressions against a `TrackedJsonNode` |
| `json_path/JsonPathExpression.java` | Compiled, immutable JSONPath expression (thread-safe) |
| `json_path/InvalidPathException.java` | Public exception for syntactically invalid expressions |
| `json_path/JsonPathParser.java` | Recursive-descent parser; produces a `List<Step>` |
| `json_path/Step.java` | Sealed AST interface + 7 record types; each implements `accept(StepVisitor<R>)` |
| `json_path/StepVisitor.java` | Visitor interface — one `visitXxx` method per step type |
| `json_path/StepEvaluator.java` | Visitor implementation — evaluation logic, `collectAll` for recursive descent |
| `json_path/FilterExpr.java` | Sealed filter-expression hierarchy + `ComparisonOp` enum |
| `json_path/FilterValue.java` | Sealed filter-value hierarchy + `FunctionResultType` enum |
| `json_patch/JsonPatch.java` | Public API — compiled, immutable patch; `compile(JsonNode)` + `apply(JsonNode/TrackedJsonNode)` |
| `json_patch/JsonPatchException.java` | Public checked exception for invalid patch documents and failed operations |
| `json_patch/JsonPatchOperation.java` | Package-private sealed interface with 6 record types (Add, Remove, Replace, Move, Copy, Test) |

## Key design decisions

**`TrackedJsonNode` is final and not a `JsonNode` subclass.** Callers that need the raw Jackson API must unwrap with `node()`. This avoids the complexity of subclassing Jackson internals.

**`step` field is lazy.** The three-arg private constructor sets `step` eagerly when it is already known (navigation via `get`/`path`/`values`). Nodes created via `of()` or `at()` set `step = null` and compute it on first call to `step()` by inspecting the tail of the pointer.

**RFC 6901 escaping is handled by Jackson.** `pointer.appendProperty(name)` escapes `~` → `~0` and `/` → `~1` automatically. Never escape manually.

**`node.properties()` not `node.fields()`.** `fields()` is deprecated since Jackson 2.18. Use `properties()` everywhere.

## JSONPath search (`JsonPathSearch`)

Implements [RFC 9535 — JSONPath: Query Expressions for JSON](https://www.rfc-editor.org/rfc/rfc9535) with a bespoke recursive-descent parser and evaluator. No third-party JSONPath library is used.

**Architecture — Visitor pattern:**

The `Step` sealed hierarchy is split into two roles:

- `Step` records (`FieldStep`, `WildcardStep`, `IndexStep`, `SliceStep`, `FilterStep`, `RecursiveStep`, `UnionStep`) are pure AST nodes. They carry only parsed data and dispatch via `accept(StepVisitor<R>)`.
- `StepEvaluator` is the single concrete `StepVisitor<List<TrackedJsonNode>>`. It holds evaluation context (`inputs`, `root`) and contains all traversal logic.

**Filter expressions (`FilterExpr`):**

Sealed hierarchy: `OrExpr`, `AndExpr`, `NotExpr`, `ExistenceExpr`, `ComparisonExpr`, `FunctionBoolExpr`. `ComparisonExpr` contains `nodesEqual` / `orderedLess` as private statics (numbers compared by `double` value, strings by lexicographic order).

**Filter values (`FilterValue`):**

Sealed hierarchy: `SingularFilterPath`, `NonSingularFilterPath`, `LiteralValue`, `FunctionCallValue`. `NonSingularFilterPath.evaluateAll()` re-enters the visitor via `step.accept(new StepEvaluator(...))`.

**Function extensions (RFC 9535 §2.4):**

`length()`, `count()`, `value()`, `match()`, `search()`. Pattern matching uses I-Regexp (RFC 9485): bare `.` in a pattern is rewritten to `[^\r\n]` before compilation so it excludes only CR/LF — not U+2028/U+2029 as Java's default `.` would.

**Pointer tracking:**

`JsonPathSearch.find()` threads `List<TrackedJsonNode>` (node + pointer pairs) through every step. No identity-map lookup at the end; pointers are built incrementally via `appendProperty` / `appendIndex`.

## JSON Patch (`JsonPatch`)

Implements [RFC 6902 — JSON Patch](https://datatracker.ietf.org/doc/html/rfc6902). All six operations are supported: `add`, `remove`, `replace`, `move`, `copy`, `test`.

**Public API:**

- `JsonPatch.compile(JsonNode patch)` — parses and validates the patch array; throws `JsonPatchException` for unknown ops, missing required fields, or malformed pointers.
- `patch.apply(JsonNode document)` — applies all operations to a deep copy; throws `JsonPatchException` if any operation fails. The original document is never mutated.
- `patch.apply(TrackedJsonNode document)` — convenience overload; returns `TrackedJsonNode.ofRoot(result)`.

**Key design decisions:**

- `JsonPatch` is final, immutable, and thread-safe. Compile once, apply many times.
- `apply` always deep-copies the input document before mutating; if any operation fails the partial result is discarded and the original is unchanged.
- The RFC 6902 §4.4 "proper prefix" constraint for `move` is enforced naturally: after removing `from`, if `path` is inside the removed subtree its parent will be missing and `navigateToParent` throws. This correctly allows array-shift cases (e.g. `move /arr/0 → /arr/0/x` shifts indices after remove).
- Array index `-` is accepted only by `add` (append to end); all other operations reject it.
- Array indices with leading zeros (e.g. `"01"`) and non-integer tokens (e.g. `"1e0"`) are rejected per RFC 6901.
- Root path `""` is valid for `add` and `replace` (replaces the entire document); `remove` on root throws.

**Internal structure:**

`JsonPatchOperation` is a package-private sealed interface with six record types (one per RFC 6902 operation). `JsonPatch.compile` produces a `List<JsonPatchOperation>`; `apply` dispatches via a `switch` expression using Java 21 record patterns.

## Testing conventions

- All tests use JUnit 5 (`@BeforeAll` + `@Test`).
- Test document is parsed once in `@BeforeAll` and stored in a `static` field.
- `TrackedJsonNodeTest` — navigation, iteration, step, RFC 6901 escaping, edge cases, plus a small subset of `findByJsonPath` smoke tests.
- `FindByJsonPathTest` — dedicated coverage for `findByJsonPath`: root selection, empty-key property, wildcard, recursive descent, predicates, non-root origin, no-match, invalid expression.
- `JsonPathSearchComplianceTest` — parameterized against all 703 valid+invalid cases from the [RFC 9535 Compliance Test Suite](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite). Also validates `TrackedJsonNode::pointer` against `result_paths` for the 447 tests that supply them.
- `JsonPatchTest` — unit tests for all six patch operations, edge cases, compile-time validation, and RFC 6902 Appendix A examples (65 tests).
- `JsonPatchComplianceTest` — parameterized against 9 test data files, stored in `src/test/resources/json_patch/`. Two `@ParameterizedTest` methods: `apply_succeeds` checks result equality; `apply_throws` asserts `JsonPatchException` (126 tests).
- Run with `mvn test`.
