# Contributing

Use JDK 21 and the Gradle wrapper. Run `./gradlew test installDist distZip`,
`python checks/cli_smoke.py build/install/contractlens`, and the MCP smoke test
shown in the README. Do not commit generated build output.

For a new compatibility rule, include both request and response cases, a safe
change that must not be flagged, original-pointer assertions, and an unsupported
case when the rule cannot prove inclusion. Document the rule's scope in
`docs/rules.md`. A witness must be validated against both compared fragments;
an illustrative value must not be labeled verified.

Keep report changes backward compatible within schema version 1. Changes to
finding identity, verdict precedence, pagination or exit codes need explicit
documentation and regression tests. Explain the trigger and resulting behavior
in the commit or pull request.

Use fictional contracts in fixtures. Do not include private APIs, credentials,
customer examples or copied production traffic. Report security issues privately
through GitHub's repository security reporting when available; otherwise open
a minimal issue requesting a private contact without disclosing exploit details.
