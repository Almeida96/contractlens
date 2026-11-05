# Rules and supported scope

The compatibility question is directional:

- **Requests:** values accepted by the baseline must remain accepted by the candidate.
- **Responses:** values the candidate can return must remain acceptable under the baseline.

This models the documented wire contract. It does not model generated SDK source
compatibility, undocumented server behavior or client business logic.

## Operation checks

| Change | Classification |
| --- | --- |
| Removed operation | Breaking when the operation sets can be resolved |
| New operation | Informational |
| New required parameter, or optional parameter becoming required | Breaking |
| Request body becoming required | Breaking |
| Removed request media type | Breaking |
| Removed documented request parameter | Review; server handling is unknown |
| Response status/media changes | Review; status ranges/defaults and negotiation need inspection |
| Parameter serialization changes | Review |
| Effective security requirements, scheme definitions or servers change | Review |
| Response header contract changes | Review |
| Operation ID changes | Informational; check generated SDKs separately |
| Unresolved/unsupported references or behavioral extensions | Review |

Operations are matched by uppercase method and exact path template. Path-level
parameters are merged with operation overrides by `(name, in)`; header names
are case-insensitive. A path-template rename is not normalized automatically.
Path-item reference siblings are not merged silently.

## Schema checks

| Constraint | Request risk | Response risk |
| --- | --- | --- |
| Type / nullability | Narrowing accepted values | Widening possible values |
| Scalar enum | Removing accepted values | Adding possible values |
| Required object field | Adding a requirement | Removing an existing guarantee |
| Numeric bounds | Tightening | Relaxing |
| String / array length bounds | Tightening | Relaxing |
| Property schemas | Recursively narrowed input | Recursively widened output |
| Additional properties | Newly closed/restricted input | New values an old closed schema rejects |
| Array items | Narrowed element contract | Widened element contract |

`integer` is treated as a subset of `number`. OpenAPI 3.0 `nullable` requires a
local type; OpenAPI 3.1 nullability uses a type union. Read-only properties are
excluded from request validation and write-only properties from response
validation. Requiredness is evaluated in that direction.

Optional response fields may be absent already, so simply removing one is not
automatically a structural incompatibility. Additional properties are allowed
by default unless restricted by the schema. An added response field can therefore
be incompatible with an old closed object, even when optional.

The analyzer includes scalar witnesses when it can generate a value and validate
it against both supported fragments. Otherwise, a finding may have no witness.
This is not general JSON Schema satisfiability solving. Contradictory constraints
and uncertain reachability are kept visible as review findings.

## Features requiring manual review

- Composition such as `allOf`, `oneOf`, `anyOf` and `not`.
- Conditional, dependent, unevaluated and pattern-based constraints.
- Formats, custom dialects, validation siblings of references and unsupported keywords.
- External references, URI anchors and percent-encoded fragments.
- Recursive comparisons and schemas beyond the bounded traversal.
- Structured object/array enum members and unsupported keyword value shapes.

Unsupported reachable schema assertions are reported even when unchanged. An
unsupported parent prevents a nested check from being advertised as a proved
break when the parent may make that value unreachable. Unused components do
not trigger findings merely by existing in the document.

Each schema comparison is bounded to 48 recursive levels and 1,024 visited
pairs. Reaching these limits creates a review finding. Operation changes and
schema assertions outside this matrix may need separate validation; this is
not a complete implementation of either OpenAPI or JSON Schema.

Annotations such as descriptions and examples do not define structural
compatibility. Extensions beginning with `x-` have no general semantics here.
Behavior represented only in those fields is outside the analyzed contract.

References: [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.1.html),
[OpenAPI 3.0](https://spec.openapis.org/oas/v3.0.4.html), and
[JSON Schema validation](https://json-schema.org/draft/2020-12/json-schema-validation).
