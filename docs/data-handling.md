# Data handling and limits

- Specifications: up to 2 MiB each; nesting up to 80 levels; string values up to
  262,144 characters. At most 1,000 operations per document.
- Numeric values retain decimal precision; precision and absolute scale are
  each bounded to 1,000 to prevent enormous exponents from expanding during analysis.
- Consumer manifest: up to 256 KiB, 100 consumers and 1,000 operation declarations
  per consumer. Names and operation identities must be unique within their scope.
- Findings: at most 10,000. Exceeding a bound is an error, not a partial success.
- Stored finding evidence: at most 8 MiB in formatted UTF-8 representation,
  including repeated use sites. This prevents shared schemas from multiplying
  into an unbounded report. Exceeding the budget fails the comparison.
- MCP: UTF-8 frames up to 256 KiB; finding pages contain at most 50 entries.
- References: same-document JSON Pointers only. External files, HTTP URLs,
  URI anchors and percent-encoded reference fragments are not resolved.

The engine makes no network requests. The first Gradle build downloads its
dependencies; the installed application and synthetic demo can run offline.
The MCP client may send tool results to a model provider according to its own
configuration. ContractLens cannot control that client's behavior.

JSON/YAML parsing rejects duplicate mapping keys and multiple documents. A UTF-8
byte order mark is accepted without relaxing JSON syntax: JSON objects and arrays
still reject trailing commas and unquoted keys. Source hashes include the original
bytes, including any byte order mark. YAML
collection aliases are disabled. HTML rendering escapes all supplied contract
text and does not load remote resources. Neither parser nor MCP executes code
from a contract, example or description.

Reports include schema fragments, source basenames, consumer names, and hashes.
They do not copy a contract's whole contents automatically, but a finding about
a removed operation can include that operation's object. Remove secrets and
sensitive examples from inputs before sharing reports or sending them to an agent.

The tool reads paths explicitly supplied at startup. It does not sandbox your
filesystem or impose an organizational permission model. Run it with ordinary
least-privilege access. The MCP process cannot be instructed to open new paths.

Outputs use a new directory and refuse existing files. An interrupted run may
leave a partial directory; choose another path after investigating the error.
No background uploads, analytics, dependency-update bot or automatic PR creation
is configured.
