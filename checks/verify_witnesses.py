"""Verify every bundled demo witness with the independent JSON Schema validator.

jsonschema is also installed by the official MCP Python client's dependencies.
This check targets the supplied OpenAPI 3.1 demo, not all supported OAS dialects.
"""
import json
from pathlib import Path
import sys
from jsonschema import Draft202012Validator

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
documents = {side: json.loads(Path(path).read_text(encoding="utf-8"))
             for side, path in zip(("baseline", "candidate"), sys.argv[2:4])}
assert set(documents) == {"baseline", "candidate"}

def locate(document, pointer):
    node = document
    for token in pointer.lstrip("/").split("/") if pointer else []:
        token = token.replace("~1", "/").replace("~0", "~")
        node = node[int(token)] if isinstance(node, list) else node[token]
    return node

verified = 0
for finding in report["findings"]:
    witness = finding.get("witness")
    if witness is None:
        continue
    assert witness["scope"] == "schema-fragment"
    validators = {
        "baseline": Draft202012Validator(locate(documents["baseline"], finding["beforePointer"])),
        "candidate": Draft202012Validator(locate(documents["candidate"], finding["afterPointer"])),
    }
    assert validators[witness["acceptedBy"]].is_valid(witness["value"]), finding["id"]
    assert not validators[witness["rejectedBy"]].is_valid(witness["value"]), finding["id"]
    verified += 1
assert verified > 0, "The fixture must exercise real witnesses"
print(f"Independent JSON Schema validator verified {verified} schema-fragment witnesses.")
