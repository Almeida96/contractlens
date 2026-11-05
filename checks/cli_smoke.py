"""Exercise the packaged CLI, including exit codes and evidence provenance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

install = Path(sys.argv[1]).resolve()
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
command = [java, "-cp", str(install / "lib" / "*"), "io.github.almeida96.contractlens.Main"]

def run(*args, expected=0):
    result = subprocess.run(command + list(map(str, args)), capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert result.returncode == expected, (args, result.returncode, result.stderr)
    return result

with tempfile.TemporaryDirectory(prefix="contractlens-check-") as temporary:
    root = Path(temporary)
    demo = root / "demo"
    summary = json.loads(run("demo", "--out", demo).stdout)
    assert summary["breaking"]["verdict"] == "BREAKING"
    original = (demo / "breaking" / "report.json").read_bytes()
    run("demo", "--out", demo, expected=1)
    assert original == (demo / "breaking" / "report.json").read_bytes()
    for name, code in [("breaking", 2), ("compatible", 0), ("review", 3)]:
        args = ["compare", "--baseline", demo / "baseline.json", "--candidate", demo / f"{name}.json", "--consumers", demo / "consumers.json"]
        result = json.loads(run(*args, "--gate", expected=code).stdout)
        again = json.loads(run(*args).stdout)
        assert result == again, "Report must be deterministic"
        assert result["baseline"]["sha256"] == hashlib.sha256((demo / "baseline.json").read_bytes()).hexdigest()
        assert result["candidate"]["sha256"] == hashlib.sha256((demo / f"{name}.json").read_bytes()).hexdigest()
        assert len({f["id"] for f in result["findings"]}) == len(result["findings"])
        if name == "breaking":
            assert any(f["consumers"] for f in result["findings"])
            assert any(f.get("witness") is not None for f in result["findings"])
            for finding in result["findings"]:
                for side, filename in [("before", "baseline.json"), ("after", f"{name}.json")]:
                    pointer = finding[side + "Pointer"]
                    if pointer is None:
                        continue
                    node = json.loads((demo / filename).read_text(encoding="utf-8"))
                    for token in pointer.lstrip("/").split("/") if pointer else []:
                        token = token.replace("~1", "/").replace("~0", "~")
                        node = node[int(token)] if isinstance(node, list) else node[token]
                    assert node == finding[side], (finding["id"], side, pointer)
    run("compare", "--baseline", demo / "missing.json", "--candidate", demo / "breaking.json", expected=1)
    invalid = root / "invalid.json"
    invalid.write_text('{"openapi":"3.1.0","openapi":"3.0.0"}', encoding="utf-8")
    run("compare", "--baseline", invalid, "--candidate", demo / "breaking.json", expected=1)
    run("compare", "--baseline", demo / "baseline.json", "--candidate", demo / "breaking.json", "--out", demo, expected=1)
print("Packaged CLI: three gate decisions, deterministic reports, exact evidence pointers, input failures and overwrite protection passed.")
