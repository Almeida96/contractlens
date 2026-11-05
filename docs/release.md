ContractLens 0.1.0 compares OpenAPI contracts and exposes API migration evidence
through CLI reports and a read-only MCP server.

- Direction-aware checks for a documented OpenAPI 3.0/3.1 subset.
- Exact source pointers, file hashes, declared consumer impact and scalar witnesses.
- JSON, Markdown and standalone HTML outputs with distinct gate exit codes.
- An offline Orders API demo with breaking, compatible and manual-review cases.
- A starter migration-review skill and official-client MCP smoke test.

Requires Java 21. Extract the ZIP and run `bin/contractlens demo --out orders-demo`
(on Windows, `bin\contractlens.bat`). The attached HTML report uses synthetic data.

Unsupported schema features require manual review. This release does not prove
runtime behavior, generated SDK compatibility or the safety of a deployment.
