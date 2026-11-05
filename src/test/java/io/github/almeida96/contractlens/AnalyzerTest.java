package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerTest {
  @TempDir Path directory;

  private static ObjectNode document() {
    ObjectNode root = Json.MAPPER.createObjectNode().put("openapi", "3.1.0");
    root.putObject("info").put("title", "Appointments").put("version", "1");
    root.putObject("paths")
        .putObject("/appointments")
        .putObject("get")
        .putObject("responses")
        .putObject("200")
        .put("description", "OK");
    return root;
  }

  private static ObjectNode operation(ObjectNode root) {
    return (ObjectNode) root.at("/paths/~1appointments/get");
  }

  private static Report compare(ObjectNode before, ObjectNode after) {
    return Analyzer.compare(
        Spec.of("before.json", before), Spec.of("after.json", after), List.of());
  }

  private static ObjectNode responseSchema(ObjectNode root) {
    return ((ObjectNode) root.at("/paths/~1appointments/get/responses/200"))
        .putObject("content")
        .putObject("application/json")
        .putObject("schema");
  }

  @Test
  void requiredParameterAndBodyBreakExistingRequests() {
    ObjectNode before = document(), after = before.deepCopy();
    operation(after)
        .putArray("parameters")
        .addObject()
        .put("name", "tenant")
        .put("in", "header")
        .put("required", true)
        .putObject("schema")
        .put("type", "string");
    operation(after)
        .putObject("requestBody")
        .put("required", true)
        .putObject("content")
        .putObject("application/json")
        .putObject("schema")
        .put("type", "object");
    Report report = compare(before, after);
    assertEquals("BREAKING", report.verdict());
    assertEquals(2, report.counts().get("BREAKING"));
    assertEquals(
        List.of("request-body-required", "request-parameter-required"),
        report.findings().stream().map(Finding::rule).toList());
  }

  @Test
  void operationOverridesInheritedHeaderCaseInsensitivelyAndDefaultsNormalize() {
    ObjectNode before = document(), after = before.deepCopy();
    ((ObjectNode) before.at("/paths/~1appointments"))
        .putArray("parameters")
        .addObject()
        .put("name", "X-Tenant")
        .put("in", "header")
        .put("required", true)
        .putObject("schema")
        .put("type", "string");
    operation(before)
        .putArray("parameters")
        .addObject()
        .put("name", "x-tenant")
        .put("in", "header")
        .put("required", false)
        .putObject("schema")
        .put("type", "string");
    operation(after)
        .putArray("parameters")
        .addObject()
        .put("name", "X-TENANT")
        .put("in", "header")
        .put("style", "simple")
        .put("explode", false)
        .putObject("schema")
        .put("type", "string");
    assertEquals("COMPATIBLE", compare(before, after).verdict());
  }

  @Test
  void responseEnumExpansionBreaksButNarrowingDoesNot() {
    ObjectNode before = document();
    responseSchema(before).put("type", "string").putArray("enum").add("booked").add("complete");
    ObjectNode after = before.deepCopy();
    ((ObjectNode)
            after.at("/paths/~1appointments/get/responses/200/content/application~1json/schema"))
        .putArray("enum")
        .add("booked")
        .add("complete")
        .add("cancelled");
    assertEquals("BREAKING", compare(before, after).verdict());
    assertEquals("COMPATIBLE", compare(after, before).verdict());
  }

  @Test
  void comparesReachableSchemasAndDoesNotClaimUnsupportedSchemaCompatible() {
    ObjectNode before = document();
    before.putObject("components").putObject("schemas").putObject("Unused").put("type", "string");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/components/schemas/Unused")).put("type", "integer");
    assertEquals("COMPATIBLE", compare(before, after).verdict());
    responseSchema(before).putArray("oneOf").addObject().put("type", "string");
    assertEquals("REVIEW", compare(before, before.deepCopy()).verdict());
  }

  @Test
  void responseStatusesAreReviewAndOperationRemovalIsBreaking() {
    ObjectNode before = document(), after = before.deepCopy();
    ((ObjectNode) operation(after).get("responses"))
        .putObject("429")
        .put("description", "Rate limited");
    Report report = compare(before, after);
    assertEquals("REVIEW", report.verdict());
    assertEquals("response-status-changed", report.findings().getFirst().rule());
    ((ObjectNode) after.get("paths")).removeAll();
    assertEquals("operation-removed", compare(before, after).findings().getFirst().rule());
  }

  @Test
  void effectiveAuthenticationDefinitionsAndServerChangesRequireReview() {
    ObjectNode before = document();
    before.putArray("security").addObject().putArray("apiKey");
    before
        .putObject("components")
        .putObject("securitySchemes")
        .putObject("apiKey")
        .put("type", "apiKey")
        .put("in", "header")
        .put("name", "X-Key");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/components/securitySchemes/apiKey")).put("name", "X-New-Key");
    after.putArray("servers").addObject().put("url", "https://example.invalid/v2");
    Report report = compare(before, after);
    assertEquals("REVIEW", report.verdict());
    assertEquals(2, report.counts().get("REVIEW"));
  }

  @Test
  void unresolvedReferencesProduceReviewWithoutNetworkAccess() {
    ObjectNode before = document();
    responseSchema(before).put("$ref", "https://example.invalid/schema.json");
    Report report = compare(before, before.deepCopy());
    assertEquals("REVIEW", report.verdict());
    assertFalse(report.findings().isEmpty());
  }

  @Test
  void consumerMappingIsExactAndReportOrderingIsRepeatable() {
    ObjectNode before = document(), after = before.deepCopy();
    ((ObjectNode) after.get("paths")).removeAll();
    List<Consumer> consumers =
        List.of(
            new Consumer("mobile", List.of("GET /appointments")), new Consumer("other", List.of()));
    Report a = Analyzer.compare(Spec.of("old", before), Spec.of("new", after), consumers);
    Report b = Analyzer.compare(Spec.of("old", before), Spec.of("new", after), consumers);
    assertEquals(a, b);
    assertEquals(List.of("mobile"), a.findings().getFirst().consumers());
    assertEquals(0, a.operationsCompared());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Analyzer.compare(
                Spec.of("old", before),
                Spec.of("new", after),
                List.of(new Consumer("typo", List.of("GET /appointment")))));
  }

  @Test
  void candidateUnresolvedPathReferenceDoesNotBecomeAProvedRemoval() {
    ObjectNode before = document(), after = before.deepCopy();
    ((ObjectNode) after.get("paths"))
        .putObject("/appointments")
        .put("$ref", "external.yaml#/paths/~1appointments");
    Report report = compare(before, after);
    assertEquals("REVIEW", report.verdict());
    assertTrue(report.findings().stream().noneMatch(f -> f.rule().equals("operation-removed")));
    assertTrue(report.findings().stream().allMatch(f -> f.beforePointer() == null));
  }

  @Test
  void serializationEvidenceUsesOnlyPresentSourceValues() {
    ObjectNode before = document();
    operation(before)
        .putArray("parameters")
        .addObject()
        .put("name", "ids")
        .put("in", "query")
        .putObject("schema")
        .put("type", "string");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/paths/~1appointments/get/parameters/0")).put("explode", false);
    Finding finding = compare(before, after).findings().getFirst();
    assertEquals("parameter-serialization-changed", finding.rule());
    assertNull(finding.beforePointer());
    assertEquals(after.at(finding.afterPointer()), finding.after());
  }

  @Test
  void unchangedCustomDialectRequiresReview() {
    ObjectNode before =
        document().put("jsonSchemaDialect", "https://example.invalid/custom-dialect");
    assertEquals("REVIEW", compare(before, before.deepCopy()).verdict());
  }

  @Test
  void requestMediaCoveredByWildcardRequiresReviewRatherThanProvedBreak() {
    ObjectNode before = document();
    operation(before)
        .putObject("requestBody")
        .putObject("content")
        .putObject("application/json")
        .putObject("schema")
        .put("type", "string");
    ObjectNode after = before.deepCopy();
    var content = (ObjectNode) after.at("/paths/~1appointments/get/requestBody/content");
    content.set("application/*", content.remove("application/json"));
    assertEquals("REVIEW", compare(before, after).verdict());
    content.removeAll();
    content.putObject("text/plain").putObject("schema").put("type", "string");
    assertEquals("BREAKING", compare(before, after).verdict());
  }

  @Test
  void responseHeaderContentReferencesAreCompared() {
    ObjectNode before = document();
    before
        .putObject("components")
        .putObject("schemas")
        .putObject("Mode")
        .put("type", "string")
        .putArray("enum")
        .add("booked");
    ((ObjectNode) before.at("/paths/~1appointments/get/responses/200"))
        .putObject("headers")
        .putObject("X-Mode")
        .putObject("content")
        .putObject("application/json")
        .putObject("schema")
        .put("$ref", "#/components/schemas/Mode");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/components/schemas/Mode"))
        .putArray("enum")
        .add("booked")
        .add("cancelled");
    Report report = compare(before, after);
    assertEquals("BREAKING", report.verdict());
    assertTrue(
        report.findings().stream()
            .anyMatch(f -> "/components/schemas/Mode".equals(f.afterPointer())));
  }

  @Test
  void encodingWithIndirectHeaderChangesCannotPassAsCompatible() {
    ObjectNode before = document();
    before
        .putObject("components")
        .putObject("schemas")
        .putObject("Mode")
        .put("type", "string")
        .putArray("enum")
        .add("booked")
        .add("cancelled");
    operation(before)
        .putObject("requestBody")
        .putObject("content")
        .putObject("multipart/form-data")
        .putObject("encoding")
        .putObject("file")
        .putObject("headers")
        .putObject("X-Mode")
        .putObject("schema")
        .put("$ref", "#/components/schemas/Mode");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/components/schemas/Mode")).putArray("enum").add("booked");
    assertEquals("REVIEW", compare(before, after).verdict());
  }

  @Test
  void intermediatePathReferenceSiblingsCannotHideServerChanges() {
    ObjectNode before = document();
    var items = before.putObject("components").putObject("pathItems");
    items.set("Actual", before.at("/paths/~1appointments").deepCopy());
    items.putObject("Alias").put("$ref", "#/components/pathItems/Actual");
    ((ObjectNode) before.get("paths"))
        .putObject("/appointments")
        .put("$ref", "#/components/pathItems/Alias");
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/components/pathItems/Alias"))
        .putArray("servers")
        .addObject()
        .put("url", "https://example.invalid/v2");
    assertEquals("REVIEW", compare(before, after).verdict());
  }

  @Test
  void sharedEvidenceAmplificationIsBoundedBeforeReportRendering() {
    ObjectNode before = document();
    before
        .putObject("components")
        .putObject("schemas")
        .putObject("Large")
        .put("type", "string")
        .put("pattern", ".*")
        .put("description", "x".repeat(200_000));
    var paths = (ObjectNode) before.get("paths");
    paths.removeAll();
    for (int i = 0; i < 40; i++)
      paths
          .putObject("/p" + i)
          .putObject("get")
          .putObject("responses")
          .putObject("200")
          .put("description", "OK")
          .putObject("content")
          .putObject("application/json")
          .putObject("schema")
          .put("$ref", "#/components/schemas/Large");
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> compare(before, before.deepCopy()));
    assertTrue(failure.getMessage().contains("8 MiB"));
  }

  @Test
  void repeatedIdenticalFindingIsBudgetedOnce() {
    ObjectNode before = document();
    before
        .putObject("components")
        .putObject("schemas")
        .putObject("Large")
        .put("type", "string")
        .put("pattern", ".*")
        .put("description", "x".repeat(200_000));
    var responses = (ObjectNode) operation(before).get("responses");
    responses.removeAll();
    for (int i = 200; i < 240; i++)
      responses
          .putObject(Integer.toString(i))
          .put("description", "OK")
          .putObject("content")
          .putObject("application/json")
          .putObject("schema")
          .put("$ref", "#/components/schemas/Large");
    Report report = compare(before, before.deepCopy());
    assertEquals("REVIEW", report.verdict());
    assertEquals(1, report.findings().size());
  }

  @Test
  void loadedDecimalConstraintChangesDoNotDisappearThroughDoubleRounding() throws Exception {
    ObjectNode before = document();
    operation(before)
        .putArray("parameters")
        .addObject()
        .put("name", "ratio")
        .put("in", "query")
        .putObject("schema")
        .put("type", "number")
        .put("minimum", new java.math.BigDecimal("0.1"));
    ObjectNode after = before.deepCopy();
    ((ObjectNode) after.at("/paths/~1appointments/get/parameters/0/schema"))
        .put("minimum", new java.math.BigDecimal("0.100000000000000001"));
    Path baselineFile = directory.resolve("before.json"),
        candidateFile = directory.resolve("after.json");
    Files.writeString(baselineFile, Json.pretty(before));
    Files.writeString(candidateFile, Json.pretty(after));
    Report report = Analyzer.compare(Spec.load(baselineFile), Spec.load(candidateFile), List.of());
    assertEquals("BREAKING", report.verdict());
    assertTrue(
        report.findings().stream()
            .anyMatch(f -> f.rule().equals("schema.numeric_bounds") && f.witness() != null));
  }

  @Test
  void rejectsDuplicateParametersAndInvalidResponseStatus() {
    ObjectNode before = document(), after = before.deepCopy();
    var parameters = operation(after).putArray("parameters");
    parameters.addObject().put("name", "X-Key").put("in", "header");
    parameters.addObject().put("name", "x-key").put("in", "header");
    assertThrows(IllegalArgumentException.class, () -> compare(before, after));
    operation(after).remove("parameters");
    ((ObjectNode) operation(after).get("responses")).putObject("999").put("description", "Invalid");
    assertThrows(IllegalArgumentException.class, () -> compare(before, after));
  }

  @Test
  void findingIdsIgnoreObjectFieldOrderButIncludeContentAndPointers() throws Exception {
    var a = new NodeRef(Json.read("{\"type\":\"string\",\"minLength\":1}"), "/schema");
    var b = new NodeRef(Json.read("{\"minLength\":1,\"type\":\"string\"}"), "/schema");
    Finding first =
        Findings.make(
            "example",
            Severity.REVIEW,
            "GET /x",
            Direction.RESPONSE,
            a,
            null,
            "message",
            "action",
            null);
    Finding second =
        Findings.make(
            "example",
            Severity.REVIEW,
            "GET /x",
            Direction.RESPONSE,
            b,
            null,
            "message",
            "action",
            null);
    assertEquals(first.id(), second.id());
    assertNotEquals(
        first.id(),
        Findings.make(
                "example",
                Severity.REVIEW,
                "GET /x",
                Direction.RESPONSE,
                new NodeRef(a.value(), "/other"),
                null,
                "message",
                "action",
                null)
            .id());
  }
}
