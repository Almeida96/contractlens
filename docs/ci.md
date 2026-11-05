# Use the comparison as a CI gate

Keep a reviewed baseline contract and generate the candidate from the same
application revision being tested. ContractLens does not fetch a baseline,
generate an OpenAPI file, infer deployed versions, or modify a release itself.

After installing a pinned release, use this Bash step:

```bash
set +e
bin/contractlens compare \
  --baseline contracts/baseline.yaml \
  --candidate build/openapi.yaml \
  --consumers contracts/consumers.json \
  --out contract-review --gate
result=$?
set -e

case "$result" in
  0) echo "Supported contract checks passed" ;;
  2) echo "Breaking contract changes found" ;;
  3) echo "Contract changes require manual review" ;;
  *) echo "Contract comparison did not complete" ;;
esac
exit "$result"
```

Use an artifact-upload step with `if: always()` to retain `contract-review/` on
GitHub Actions. In GitLab CI, use `artifacts: when: always`. Protect reports as
appropriate for the source contracts; they may contain internal schema examples.

Do not append `|| true` to the comparison or map exit 3 to success. An empty or
failed report is not evidence of compatibility. If a team explicitly accepts a
breaking change or unsupported feature, record that release decision separately
instead of editing the report's verdict.

The repository's own [workflow](../.github/workflows/ci.yml) tests the installed
distribution on Linux and Windows. It uses synthetic fixtures and does not
deploy anything. Release publication only runs after the matrix succeeds on
the release commit.
