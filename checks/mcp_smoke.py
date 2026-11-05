#!/usr/bin/env python3
"""Exercise the packaged CLI with the official MCP Python SDK (no model involved).

Install the pinned client: python -m pip install mcp==1.26.0
Example after Gradle installDist:
  python checks/mcp_smoke.py --install-dir build/install/contractlens \
      --baseline examples/orders/baseline.json --candidate examples/orders/breaking.json \
      --consumers examples/orders/consumers.json

Invoking the installed distribution's lib/* with Java works on Windows and POSIX,
without shell or batch-file interpretation. Supply --java for a portable JDK.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
from datetime import timedelta
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--install-dir", required=True, type=Path, help="Unpacked distribution or Gradle installDist directory")
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--consumers", required=True, type=Path)
    parser.add_argument("--java", help="Java 21+ executable; defaults to JAVA_HOME/bin/java or PATH")
    return parser.parse_args()


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def structured(result: Any, *, error: bool = False) -> dict[str, Any]:
    check(result.isError is error, f"Unexpected tool status: {result}")
    check(isinstance(result.structuredContent, dict), "Tool must return a structured object")
    check(len(result.content) == 1 and result.content[0].type == "text", "Expected one JSON text content block")
    check(json.loads(result.content[0].text) == result.structuredContent, "Text and structuredContent differ")
    return result.structuredContent


async def smoke(options: argparse.Namespace) -> dict[str, Any]:
    from jsonschema import Draft202012Validator
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    from mcp.shared.exceptions import McpError

    distribution = options.install_dir.resolve(strict=True)
    check((distribution / "lib").is_dir(), f"No lib directory in {distribution}; run installDist first")
    check(any((distribution / "lib").glob("*.jar")), "Installed lib directory contains no JARs")
    java_arg = options.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java")
    java = str(Path(java_arg).resolve()) if Path(java_arg).is_file() else shutil.which(java_arg)
    check(java is not None, "Java executable not found; supply --java /path/to/java")
    command = StdioServerParameters(
        command=java,
        args=[
            "-cp", str(distribution / "lib" / "*"), "io.github.almeida96.contractlens.Main", "mcp",
            "--baseline", str(options.baseline.resolve(strict=True)),
            "--candidate", str(options.candidate.resolve(strict=True)),
            "--consumers", str(options.consumers.resolve(strict=True)),
        ],
    )
    async with stdio_client(command) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=20)) as session:
            initialized = await session.initialize()
            check(initialized.protocolVersion in {"2025-11-25", "2025-06-18"}, "Unexpected protocol version")
            check(initialized.serverInfo.name == "contractlens", "Unexpected server name")
            check(initialized.capabilities.tools is not None, "Server did not advertise tools")
            await session.send_ping()
            listed = await session.list_tools()
            tools = {tool.name: tool for tool in listed.tools}
            check(set(tools) == {"review_summary", "list_findings", "finding_evidence"}, "Unexpected tool inventory")
            for tool in listed.tools:
                Draft202012Validator.check_schema(tool.inputSchema)
                check(tool.inputSchema.get("type") == "object", "Tool schema must describe an object")
                check(tool.inputSchema.get("additionalProperties") is False, "Tool arguments must reject unknown fields")
                hints = tool.annotations
                check(hints is not None and hints.readOnlyHint is True, "Missing read-only hint")
                check(hints.destructiveHint is False and hints.idempotentHint is True and hints.openWorldHint is False,
                      "Tool behavior annotations do not describe a closed, read-only report")
            check(tools["finding_evidence"].inputSchema.get("required") == ["id"], "Evidence id must be required")
            check(tools["list_findings"].inputSchema["properties"]["limit"]["maximum"] == 50,
                  "Page size must be bounded at 50")
            summary = structured(await session.call_tool("review_summary", {}))
            check("findings" not in summary, "Summary leaked full findings")
            check(all(key in summary for key in ("counts", "baseline", "candidate", "operationsCompared", "consumers", "limitations")),
                  "Summary is missing review context")
            check(all(isinstance(name, str) for name in summary["consumers"]), "Summary consumers must be names")
            for source in (summary["baseline"], summary["candidate"]):
                check(len(source["sha256"]) == 64, "Source must contain a SHA-256 fingerprint")

            first = structured(await session.call_tool("list_findings", {"limit": 1}))
            check(first["total"] >= 1, "Use a sample with at least one finding to verify evidence")
            check(len(first["findings"]) == 1, "Requested one finding")
            check(first["nextOffset"] == (1 if first["total"] > 1 else None), "Incorrect first nextOffset")
            check(first == structured(await session.call_tool("list_findings", {"limit": 1})), "Pagination is not repeatable")
            findings = list(first["findings"])
            next_offset = first["nextOffset"]
            while next_offset is not None:
                page = structured(await session.call_tool("list_findings", {"offset": next_offset, "limit": 17}))
                check(page["total"] == first["total"] and page["offset"] == next_offset, "Inconsistent page metadata")
                check(0 < len(page["findings"]) <= 17, "Unexpected page length")
                expected_next = next_offset + len(page["findings"])
                check(page["nextOffset"] == (expected_next if expected_next < page["total"] else None), "Incorrect nextOffset")
                findings.extend(page["findings"])
                next_offset = page["nextOffset"]
            identifiers = [finding["id"] for finding in findings]
            check(len(identifiers) == first["total"] == len(set(identifiers)), "Pagination lost or repeated findings")
            check(identifiers == sorted(identifiers), "Findings must use stable id order")

            selected = findings[0]
            filters = {"severity": selected["severity"], "operation": selected["operation"], "limit": 50}
            if selected["consumers"]:
                filters["consumer"] = selected["consumers"][0]
            filtered = structured(await session.call_tool("list_findings", filters))
            check(any(item["id"] == selected["id"] for item in filtered["findings"]), "Exact filters lost the selected finding")
            check(all(item["severity"] == selected["severity"] and item["operation"] == selected["operation"]
                      and ("consumer" not in filters or filters["consumer"] in item["consumers"])
                      for item in filtered["findings"]), "Filtered page contains a nonmatching finding")
            evidence = structured(await session.call_tool("finding_evidence", {"id": selected["id"]}))
            check(evidence["id"] == selected["id"], "Wrong evidence returned")
            check(all(key in evidence for key in ("beforePointer", "afterPointer", "before", "after", "witness", "consumers", "action")),
                  "Evidence does not contain complete provenance and remediation")
            check(isinstance(evidence["action"], str) and bool(evidence["action"]), "Evidence requires a remediation action")
            for key in ("beforePointer", "afterPointer"):
                check(evidence[key] is None or evidence[key] == "" or evidence[key].startswith("/"), "Evidence pointer is not a JSON Pointer")
            if evidence["witness"] is not None:
                check(all(key in evidence["witness"] for key in ("value", "acceptedBy", "rejectedBy", "scope")), "Incomplete witness")
            missing = structured(await session.call_tool("finding_evidence", {"id": "smoke-test-missing-id"}), error=True)
            check(missing["error"]["code"] == "FINDING_NOT_FOUND", "Unknown id must produce an explicit tool error")
            for invalid in ({"path": "/arbitrary/file"}, {"limit": 51}, {"offset": -1}):
                try:
                    await session.call_tool("list_findings", invalid)
                except McpError as failure:
                    check(failure.error.code == -32602, "Invalid arguments must produce Invalid params")
                else:
                    raise AssertionError(f"Server accepted invalid arguments: {invalid}")
            return {"status": "passed", "client": "official MCP Python SDK", "clientVersion": "1.26.0",
                    "protocolVersion": initialized.protocolVersion, "tools": sorted(tools),
                    "findingsPaged": len(findings), "evidenceId": selected["id"], "modelInvoked": False}


def main() -> None:
    options = arguments()
    try:
        installed = version("mcp")
    except PackageNotFoundError:
        raise SystemExit("Install the official client first: python -m pip install mcp==1.26.0") from None
    if installed != "1.26.0":
        raise SystemExit(f"Expected mcp==1.26.0, found {installed}; use a dedicated virtual environment")
    print(json.dumps(asyncio.run(smoke(options)), indent=2))


if __name__ == "__main__":
    main()
