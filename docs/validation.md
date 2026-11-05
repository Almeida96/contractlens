# Validation for 0.1.0

Local verification used JDK 21 on Windows:

- 87 JUnit tests passed across input handling, operation rules, directional
  schemas, CLI output and MCP protocol behavior.
- The installed CLI exercised all three demo verdicts and gate exits 0/2/3,
  input failures, duplicate keys, output protection and repeatable JSON.
- Evidence pointers were followed back into both original documents and their
  values matched the report.
- The official Python MCP client 1.26.0 initialized the packaged Java process,
  discovered its three tools, paged through eight findings, checked filters and
  fetched complete evidence. No model was invoked by that protocol test.
- Python's independent JSON Schema validator checked all four scalar witnesses
  in the OpenAPI 3.1 demo against their accepted and rejected fragments.
- An independent report-only use of the included skill distinguished seven
  findings mapped to the requested consumer from the eighth finding affecting
  another consumer. It preserved the limits of static evidence and proposed
  checks without claiming they had run.
- Review regressions cover chained reference siblings, response-header content,
  multipart encoding, precise decimals, huge exponents, inherited parameters,
  unknown consumer declarations and evidence amplification.

A separate synthetic capacity check compared 1,000 identical simple operations
under a 128 MiB maximum Java heap in about 1.3 seconds, including JVM startup.
That single local measurement is not a production benchmark or a latency promise.
Large schemas and many findings have different costs; the documented bounds and
evidence budget still apply.

The repository workflow repeats the unit, packaged CLI, MCP and independent
witness checks on Linux and Windows. Consult the actual workflow run for hosted
CI status. No customer API, production traffic, deployment or provider-specific
agent integration was validated by these checks.
