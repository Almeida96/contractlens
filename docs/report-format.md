# Report format, schema version 1

`report.json` and `compare` stdout contain the same object. Fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion`, `toolVersion` | Format revision and producing engine version |
| `verdict` | `BREAKING`, `REVIEW`, or `COMPATIBLE` |
| `baseline`, `candidate` | Input basename, exact-byte SHA-256 hash and OpenAPI version |
| `operationsCompared` | Operations present on both sides and compared |
| `counts` | Exact finding counts for `BREAKING`, `REVIEW`, `INFO` |
| `findings` | Complete ordered list; no silent truncation |
| `consumers` | Supplied declarations, not discovered usage |
| `limitations` | Analysis scope that must accompany interpretation |

Each finding contains `id`, `rule`, `severity`, `operation`, `direction`,
`beforePointer`, `afterPointer`, `before`, `after`, `message`, `action`,
optional/null `witness`, and `consumers`. `operation` is `METHOD /path`, or `*`
for document-level findings. `direction` is `REQUEST`, `RESPONSE` or `OPERATION`.

Pointers use RFC 6901 escaping and point into the original documents. Shared
schemas can point into `components`; the operation field retains the use site.
A null pointer/value represents an absent side. Explicit JSON null is
distinguished by a non-null pointer. A witness contains `value`, `acceptedBy`,
`rejectedBy` and `scope`; it is checked only against the stated schema fragments.

The overall verdict takes the strongest finding: breaking first, then review.
`INFO` alone leaves it compatible within the supported checks. Always inspect
review findings even when the overall verdict is breaking. There are no
suppression files or overrides that silently convert an uncertain report to green.

Finding IDs support stable references within the documented tool version.
They are not signatures or authenticity guarantees. Source hashes establish
byte identity when independently checked; they do not prove who supplied a file.

Consumer names and schema examples may contain internal information. Treat
reports as having the same confidentiality as their source contracts.
