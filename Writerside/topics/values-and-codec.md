# Values and codec

`ConvexValue` (in `convex-core`) is the closed hierarchy of everything a
Convex function accepts or returns. Every consumer — the JSON codec, the
protocol layer, the Compose bindings — switches over it exhaustively, so a
new Convex type breaks compilation instead of silently dropping data.

## Variants

| Variant | Kotlin type | Wire form |
| --- | --- | --- |
| `Null` | `ConvexValue.Null` | JSON `null` |
| `Int64` | `ConvexValue.Int64(Long)` | tagged base64 little-endian (JSON numbers cannot hold 64 bits) |
| `Float64` | `ConvexValue.Float64(Double)` | JSON number; tagged `NaN`/`Infinity`/`-Infinity`/`-0.0` |
| `Boolean` | `ConvexValue.Boolean(Boolean)` | JSON boolean |
| `String` | `ConvexValue.String(String)` | JSON string |
| `Bytes` | `ConvexValue.Bytes(ByteArray)` | tagged base64 |
| `Array` | `ConvexValue.Array(List<ConvexValue>)` | JSON array |
| `Object` | `ConvexValue.Object(Map<String, ConvexValue>)` | JSON object, keys sorted |

`Bytes` copies on construction and on every read, and compares by content —
it is safe as a map key. Decoding is strict: ambiguous or retired shapes
(a float that would have fit in a number, old `$set`/`$map` tags) throw
`ConvexJsonException` instead of guessing.

```kotlin
import eu.wynq.convex.core.value.ConvexJson
import eu.wynq.convex.core.value.ConvexValue

val text: String = ConvexJson.encode(value)   // compact JSON text
val back: ConvexValue = ConvexJson.decode(text) // throws ConvexJsonException
```

When you already hold a parsed element — one field of a larger document —
skip the text round trip with the element-level codec:
`ConvexJson.toJsonElement(value)` / `ConvexJson.fromJsonElement(element)`.

## export(): the human-facing projection

`ConvexValue.export()` is deliberately **not** the wire codec: it projects
to the plain-JSON "database types" format (integers become decimal
*strings* because JSON numbers cannot hold 64 bits, non-finite floats
become `"NaN"`/`"Infinity"`/`"-Infinity"`, bytes become base64 text).
Export is lossy by design — re-importing needs the original validator as
well as the JSON.

## Identifiers

Table and field names are validated ASCII, mirroring the backend:

- **Identifiers** (table names): start `a-zA-Z_`, continue `a-zA-Z0-9_`,
  at least one non-underscore, at most 64 UTF-8 bytes.
- **Field names**: any non-control ASCII (including spaces and `$`
  anywhere but first), at most 1024 UTF-8 bytes; the empty name is valid.

```kotlin
import eu.wynq.convex.core.value.Identifier
import eu.wynq.convex.core.value.checkValidFieldName
import eu.wynq.convex.core.value.isValidIdentifier

isValidIdentifier("messages") // true
checkValidFieldName("body")   // returns normally
Identifier("messages")        // throws ConvexIdentifierException when invalid
Identifier.min()              // "A", sorts before every generated name
```

The fast boolean checks and the throwing checks agree on every input; the
throwing variants exist to name the offending character Rust-`{:?}`-style
(`'9'`, `'@'`, `'\n'`, `'\u{10}'`, `'😀'`). Lengths are UTF-8 bytes, so a
non-ASCII name exhausts its budget faster than its `length` suggests.
