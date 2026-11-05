# MCP integration

ContractLens implements newline-delimited JSON-RPC over standard input/output.
It supports protocol revisions `2025-11-25` and `2025-06-18`. Startup parses and
compares the chosen contracts; an invalid input terminates the process with
an error on stderr. Stdout is reserved for protocol messages.

Configure a client using absolute paths. Replace every example path below:

```json
{
  "mcpServers": {
    "contractlens": {
      "command": "/opt/contractlens/bin/contractlens",
      "args": [
        "mcp", "--baseline", "/work/api-before.yaml",
        "--candidate", "/work/api-after.yaml",
        "--consumers", "/work/consumers.json"
      ]
    }
  }
}
```

On Windows, a client that cannot launch `.bat` files directly can run Java:

```json
{
  "mcpServers": {
    "contractlens": {
      "command": "C:/Program Files/Java/jdk-21/bin/java.exe",
      "args": [
        "-cp", "C:/Tools/contractlens/lib/*",
        "io.github.almeida96.contractlens.Main", "mcp",
        "--baseline", "C:/Work/api-before.yaml",
        "--candidate", "C:/Work/api-after.yaml"
      ]
    }
  }
}
```

Client configuration locations and installation flows vary. The server's
transport does not require HTTP, a port, an account or a token. Restart it to
review edited files; tools cannot re-read arbitrary paths.

## Review sequence

1. Call `review_summary` with `{}`. Record source hashes, verdict and limitations.
2. Call `list_findings` with `{"offset":0,"limit":20}`. Continue using `nextOffset`
   until it is null. Optional exact filters are `severity`, `operation`, `consumer`.
3. Call `finding_evidence` with `{"id":"ID_FROM_LIST"}` for a complete finding.

Tools return JSON in `structuredContent` and equivalent JSON in a text content
block. They declare read-only, non-destructive, idempotent, closed-world hints.
The server validates arguments rather than relying on those hints. Unknown
arguments, invalid filters and nonexistent IDs produce errors, not empty success.

The official-client [smoke test](../checks/mcp_smoke.py) performs initialization,
tool discovery, filtering, pagination and evidence retrieval against the actual
packaged Java process. It is a protocol test, not an LLM evaluation.

## Skill example

The repository includes [review-api-migration](../skills/review-api-migration/SKILL.md).
Copy that folder into your client's supported skill directory. It can interpret
a supplied JSON report without Java; running comparisons additionally needs
ContractLens and Java 21. MCP is optional.

The skill instructs the agent to preserve uncertainty, distinguish declared
consumer impact from observed usage, and avoid presenting fragment witnesses as
full requests. It does not grant permission to edit applications or publish
changes. Adapt the workflow to the authorization in the actual user request.

Protocol references: [tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
and [stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
