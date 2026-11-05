# Architecture

```text
before.yaml + after.yaml + optional consumers.json
                 |
         bounded offline reader
                 |
    operation comparison + directional schema checks
                 |
       deterministic evidence report
         /          |           \
 JSON/Markdown   offline HTML   read-only stdio MCP
                                  |
                          client / review skill
```

`Spec` reads one local document, enforces resource limits and resolves supported
same-document pointers. It records a SHA-256 hash of the exact input bytes.
`Analyzer` matches operations by method/path, merges inherited parameters,
applies operation checks and associates declared consumers. `SchemaComparator`
checks a bounded structural subset using the appropriate inclusion direction.

`Findings` assigns IDs from canonical finding content. Operation, direction,
rule, pointers and values distinguish findings. Input filenames and object-key
ordering do not define finding identity. File byte hashes still change if the
input bytes change. Reports contain no generation timestamp and are repeatable
for the same inputs and tool version.

The engine has no model, database, background worker or external API dependency.
`McpServer` receives one completed report and owns a private serialized snapshot.
Each client process sees that snapshot until restarted. Filtering and pagination
do not recalculate the comparison or weaken the overall verdict.

The CLI builds output in a new directory. JSON is the complete machine-readable
result; Markdown is a review summary and HTML presents the source values.
The HTML contains no JavaScript, fonts, images or third-party requests, escapes
contract text, and declares a restrictive Content Security Policy.

Limits prevent unbounded documents and protocol frames, but this is still a
local developer tool. Run it with access only to the contracts intended for
review. It is not a multi-tenant parsing service.
