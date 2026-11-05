package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaComparatorTest {
  private static final String POINTER = "/components/schemas/Test";

  private Spec spec(String schema, String version) throws Exception {
    ObjectNode root =
        (ObjectNode)
            Json.read(
                """
                {"openapi":"3.1.0","info":{"title":"Test","version":"1"},"paths":{},"components":{"schemas":{}}}
                """);
    root.put("openapi", version);
    ((ObjectNode) root.at("/components/schemas")).set("Test", Json.read(schema));
    return Spec.of("test.json", root);
  }

  private List<Finding> compare(String before, String after, Direction direction) throws Exception {
    return compare(before, after, direction, "3.1.0", "3.1.0");
  }

  private List<Finding> compare(
      String before,
      String after,
      Direction direction,
      String baselineVersion,
      String candidateVersion)
      throws Exception {
    Spec baseline = spec(before, baselineVersion), candidate = spec(after, candidateVersion);
    return SchemaComparator.compare(
        baseline,
        candidate,
        new NodeRef(baseline.root().at(POINTER), POINTER),
        new NodeRef(candidate.root().at(POINTER), POINTER),
        "POST /widgets",
        direction);
  }

  private static void breaking(List<Finding> findings, String rule) {
    assertTrue(
        findings.stream().anyMatch(f -> f.severity() == Severity.BREAKING && f.rule().equals(rule)),
        () -> "Expected breaking " + rule + ": " + Json.pretty(findings));
  }

  private static void compatible(List<Finding> findings) {
    assertTrue(findings.isEmpty(), () -> Json.pretty(findings));
  }

  private static void review(List<Finding> findings, String rule) {
    assertTrue(
        findings.stream().anyMatch(f -> f.severity() == Severity.REVIEW && f.rule().equals(rule)),
        () -> "Expected review " + rule + ": " + Json.pretty(findings));
  }

  @Test
  void enumRemovalBreaksRequestsButNotResponses() throws Exception {
    String before = "{\"type\":\"string\",\"enum\":[\"draft\",\"paid\"]}";
    String after = "{\"type\":\"string\",\"enum\":[\"paid\"]}";
    List<Finding> request = compare(before, after, Direction.REQUEST);
    breaking(request, "schema.enum");
    compatible(compare(before, after, Direction.RESPONSE));
    Witness witness =
        request.stream()
            .filter(f -> f.rule().equals("schema.enum"))
            .findFirst()
            .orElseThrow()
            .witness();
    assertEquals("draft", witness.value().asText());
    assertEquals("baseline", witness.acceptedBy());
    assertEquals("candidate", witness.rejectedBy());
    assertEquals("schema-fragment", witness.scope());
  }

  @Test
  void responseEnumExpansionHasTheOppositeWitness() throws Exception {
    List<Finding> findings =
        compare(
            "{\"type\":\"integer\",\"enum\":[1]}",
            "{\"type\":\"integer\",\"enum\":[1,2]}",
            Direction.RESPONSE);
    breaking(findings, "schema.enum");
    Witness witness = findings.getFirst().witness();
    assertEquals(2, witness.value().intValue());
    assertEquals("candidate", witness.acceptedBy());
    assertEquals("baseline", witness.rejectedBy());
  }

  @Test
  void integerIsASubtypeOfNumberIncludingDecimalSpelling() throws Exception {
    compatible(compare("{\"type\":\"integer\"}", "{\"type\":\"number\"}", Direction.REQUEST));
    breaking(
        compare("{\"type\":\"number\"}", "{\"type\":\"integer\"}", Direction.REQUEST),
        "schema.type");
    breaking(
        compare("{\"type\":\"integer\"}", "{\"type\":\"number\"}", Direction.RESPONSE),
        "schema.type");
    compatible(
        compare(
            "{\"type\":\"number\",\"enum\":[1.0]}",
            "{\"type\":\"integer\",\"enum\":[1]}",
            Direction.REQUEST));
  }

  @Test
  void nullabilityWorksAcrossTheTwoDialects() throws Exception {
    String nullable30 = "{\"type\":\"string\",\"nullable\":true}";
    String nullable31 = "{\"type\":[\"string\",\"null\"]}";
    compatible(compare(nullable30, nullable31, Direction.REQUEST, "3.0.3", "3.1.0"));
    List<Finding> findings = compare(nullable31, "{\"type\":\"string\"}", Direction.REQUEST);
    breaking(findings, "schema.type");
    assertTrue(findings.getFirst().witness().value().isNull());
    breaking(compare("{\"type\":\"string\"}", nullable31, Direction.RESPONSE), "schema.type");
    review(compare(nullable30, nullable30, Direction.REQUEST), "schema.dialect");
  }

  @Test
  void requiredPropertiesAreDirectional() throws Exception {
    String optional = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
    String required =
        "{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\"}}}";
    breaking(compare(optional, required, Direction.REQUEST), "schema.required");
    compatible(compare(required, optional, Direction.REQUEST));
    breaking(compare(required, optional, Direction.RESPONSE), "schema.required");
    compatible(compare(optional, required, Direction.RESPONSE));
  }

  @Test
  void removingAnOptionalResponsePropertyIsStructurallySafe() throws Exception {
    String before =
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\"}}}";
    String after = "{\"type\":\"object\",\"additionalProperties\":false}";
    compatible(compare(before, after, Direction.RESPONSE));
    breaking(compare(before, after, Direction.REQUEST), "schema.property_rejected");
  }

  @Test
  void additionalPropertiesFollowSetInclusion() throws Exception {
    String open = "{\"type\":\"object\"}",
        closed = "{\"type\":\"object\",\"additionalProperties\":false}";
    breaking(compare(open, closed, Direction.REQUEST), "schema.additional_properties");
    compatible(compare(closed, open, Direction.REQUEST));
    breaking(compare(closed, open, Direction.RESPONSE), "schema.additional_properties");
    compatible(compare(open, closed, Direction.RESPONSE));
  }

  @Test
  void schemaValuedAdditionalPropertiesConstrainNamedAndUnnamedValues() throws Exception {
    String any = "{\"type\":\"object\"}";
    String strings = "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}";
    breaking(compare(any, strings, Direction.REQUEST), "schema.type");
    compatible(compare(strings, any, Direction.REQUEST));
    String prop = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}";
    breaking(compare(prop, strings, Direction.REQUEST), "schema.type");
  }

  @Test
  void addingOptionalPropertyToOpenRequestStillConstrainsExistingValues() throws Exception {
    breaking(
        compare(
            "{\"type\":\"object\"}",
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}",
            Direction.REQUEST),
        "schema.type");
  }

  @Test
  void readOnlyAndWriteOnlyPropertiesApplyInTheirWireDirection() throws Exception {
    String before = "{\"type\":\"object\",\"additionalProperties\":false}";
    String readonly =
        "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\",\"readOnly\":true}}}";
    compatible(compare(before, readonly, Direction.REQUEST));
    breaking(compare(before, readonly, Direction.RESPONSE), "schema.property_rejected");
    String writeonly = readonly.replace("readOnly", "writeOnly");
    compatible(compare(before, writeonly, Direction.RESPONSE));
    breaking(compare(before, writeonly, Direction.REQUEST), "schema.required");
  }

  @Test
  void itemsAndArrayLengthsAreDirectional() throws Exception {
    String before =
        "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":0,\"maxItems\":10}";
    String after =
        "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"maxItems\":5}";
    breaking(compare(before, after, Direction.REQUEST), "schema.array_length");
    compatible(compare(before, after, Direction.RESPONSE));
    breaking(
        compare(before, before.replace("string", "integer"), Direction.RESPONSE), "schema.type");
    compatible(
        compare(
            "{\"type\":\"array\",\"maxItems\":0,\"items\":{\"type\":\"string\"}}",
            "{\"type\":\"array\",\"maxItems\":0,\"items\":{\"type\":\"integer\"}}",
            Direction.REQUEST));
  }

  @Test
  void numericBoundsHandleExclusivityAndNarrowIntervals() throws Exception {
    breaking(
        compare(
            "{\"type\":\"number\",\"minimum\":0}",
            "{\"type\":\"number\",\"exclusiveMinimum\":0}",
            Direction.REQUEST),
        "schema.numeric_bounds");
    compatible(
        compare(
            "{\"type\":\"integer\",\"minimum\":1.1}",
            "{\"type\":\"integer\",\"minimum\":1.2}",
            Direction.REQUEST));
    List<Finding> findings =
        compare(
            "{\"type\":\"number\",\"exclusiveMinimum\":0.000001,\"maximum\":0.000002}",
            "{\"type\":\"number\",\"minimum\":0.0000015,\"maximum\":0.000002}",
            Direction.REQUEST);
    breaking(findings, "schema.numeric_bounds");
    assertNotNull(findings.getFirst().witness());
    compatible(
        compare(
            "{\"type\":\"number\",\"minimum\":0,\"exclusiveMinimum\":true}",
            "{\"type\":\"number\",\"exclusiveMinimum\":0}",
            Direction.REQUEST,
            "3.0.3",
            "3.1.0"));
  }

  @Test
  void stringBoundsRespectEnumMasksAndUseScalarWitnesses() throws Exception {
    List<Finding> findings =
        compare(
            "{\"type\":\"string\",\"maxLength\":8}",
            "{\"type\":\"string\",\"maxLength\":3}",
            Direction.REQUEST);
    breaking(findings, "schema.string_length");
    assertTrue(findings.getFirst().witness().value().textValue().length() > 3);
    compatible(
        compare(
            "{\"type\":\"string\",\"enum\":[\"ab\"],\"maxLength\":8}",
            "{\"type\":\"string\",\"enum\":[\"ab\"],\"maxLength\":3}",
            Direction.REQUEST));
    compatible(
        compare(
            "{\"type\":\"string\",\"enum\":[\"😀\"],\"minLength\":1}",
            "{\"type\":\"string\",\"enum\":[\"😀\"],\"maxLength\":1}",
            Direction.REQUEST));
  }

  @Test
  void unsupportedAssertionsAreReviewedEvenWhenUnchanged() throws Exception {
    for (String assertion :
        List.of(
            "\"pattern\":\"^a$\"",
            "\"format\":\"email\"",
            "\"multipleOf\":2",
            "\"oneOf\":[{\"type\":\"string\"}]",
            "\"uniqueItems\":true",
            "\"unevaluatedProperties\":false")) {
      String schema = "{" + assertion + "}";
      review(compare(schema, schema, Direction.REQUEST), "schema.unsupported");
    }
  }

  @Test
  void unsupportedParentNeverManufacturesAReachableBreakingWitness() throws Exception {
    String before =
        "{\"type\":\"object\",\"maxProperties\":0,\"properties\":{\"x\":{\"type\":\"string\"}}}";
    List<Finding> findings =
        compare(before, before.replace("string", "integer"), Direction.REQUEST);
    review(findings, "schema.unsupported");
    assertTrue(
        findings.stream().noneMatch(f -> f.severity() == Severity.BREAKING || f.witness() != null));
  }

  @Test
  void pointersEscapePropertyNames() throws Exception {
    List<Finding> findings =
        compare(
            "{\"type\":\"object\",\"properties\":{\"a/b~c\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a/b~c\":{\"type\":\"integer\"}}}",
            Direction.REQUEST);
    Finding finding =
        findings.stream().filter(f -> f.rule().equals("schema.type")).findFirst().orElseThrow();
    assertEquals(POINTER + "/properties/a~1b~0c", finding.beforePointer());
    assertEquals(POINTER + "/properties/a~1b~0c", finding.afterPointer());
  }

  @Test
  void sameDocumentRefsUseResolvedPointers() throws Exception {
    Spec baseline = spec("{\"type\":\"string\"}", "3.1.0"),
        candidate = spec("{\"type\":\"integer\"}", "3.1.0");
    JsonNode ref = Json.read("{\"$ref\":\"#/components/schemas/Test\"}");
    List<Finding> findings =
        SchemaComparator.compare(
            baseline,
            candidate,
            new NodeRef(ref, "/request"),
            new NodeRef(ref, "/request"),
            "GET /ref",
            Direction.REQUEST);
    breaking(findings, "schema.type");
    assertEquals(POINTER, findings.getFirst().beforePointer());
    assertEquals(POINTER, findings.getFirst().afterPointer());
  }

  @Test
  void cyclicExternalAndUnresolvedReferencesAreReviewed() throws Exception {
    for (String ref :
        List.of(
            "#/components/schemas/Test",
            "#/missing",
            "https://example.invalid/schema",
            "#/bad~2escape")) {
      String schema = "{\"$ref\":\"" + ref + "\"}";
      review(compare(schema, schema, Direction.REQUEST), "schema.reference");
    }
    String recursive =
        "{\"type\":\"object\",\"properties\":{\"next\":{\"$ref\":\"#/components/schemas/Test\"}}}";
    review(compare(recursive, recursive, Direction.REQUEST), "schema.recursion");
  }

  @Test
  void refSiblingsCannotSilentlyDisappear() throws Exception {
    Spec spec = spec("{\"type\":\"string\"}", "3.1.0");
    JsonNode ref = Json.read("{\"$ref\":\"#/components/schemas/Test\",\"maxLength\":2}");
    review(
        SchemaComparator.compare(
            spec,
            spec,
            new NodeRef(ref, "/request"),
            new NodeRef(ref, "/request"),
            "GET /ref",
            Direction.REQUEST),
        "schema.reference");
  }

  @Test
  void malformedAndContradictorySchemasDoNotLookCompatible() throws Exception {
    for (String schema :
        List.of(
            "{\"type\":[]}",
            "{\"enum\":[]}",
            "{\"required\":[4]}",
            "{\"minimum\":\"zero\"}",
            "{\"minLength\":-1}",
            "{\"items\":[]}",
            "null")) {
      review(compare(schema, schema, Direction.REQUEST), "schema.invalid");
    }
    review(
        compare(
            "{\"type\":\"string\",\"minLength\":8,\"maxLength\":2}",
            "{\"type\":\"string\",\"maxLength\":1}",
            Direction.REQUEST),
        "schema.unsatisfiable");
  }

  @Test
  void missingSchemasAndBooleansDoNotCreateRecursionFindings() throws Exception {
    compatible(compare("{}", "{}", Direction.REQUEST));
    compatible(compare("false", "true", Direction.REQUEST));
    breaking(compare("true", "false", Direction.REQUEST), "schema.false_schema");
    Spec spec = spec("{\"type\":\"string\"}", "3.1.0");
    List<Finding> findings =
        SchemaComparator.compare(
            spec,
            spec,
            null,
            new NodeRef(spec.root().at(POINTER), POINTER),
            "GET /x",
            Direction.REQUEST);
    breaking(findings, "schema.type");
    assertNull(findings.getFirst().beforePointer());
  }

  @Test
  void findingsAndWitnessesAreDeterministic() throws Exception {
    String before = "{\"type\":\"number\",\"minimum\":0}",
        after = "{\"minimum\":2,\"type\":\"number\"}";
    List<Finding> one = compare(before, after, Direction.REQUEST),
        two = compare(before, after, Direction.REQUEST);
    assertEquals(one, two);
    assertTrue(
        one.stream()
            .filter(f -> f.witness() != null)
            .allMatch(f -> f.witness().value().isValueNode()));
  }

  @Test
  void inapplicableArrayAssertionsDoNotCreateBreakingChanges() throws Exception {
    compatible(
        compare(
            "{\"type\":\"string\",\"items\":{\"type\":\"string\"}}",
            "{\"type\":\"string\",\"items\":{\"type\":\"integer\"}}",
            Direction.REQUEST));
  }

  @Test
  void referenceAnnotationSiblingsAreReviewed() throws Exception {
    Spec baseline = spec("{\"type\":\"string\"}", "3.1.0"),
        candidate = spec("{\"type\":\"string\"}", "3.1.0");
    JsonNode before =
        Json.read(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"x\":{\"$ref\":\"#/components/schemas/Test\"}}}");
    JsonNode after =
        Json.read(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"x\":{\"$ref\":\"#/components/schemas/Test\",\"readOnly\":true}}}");
    review(
        SchemaComparator.compare(
            baseline,
            candidate,
            new NodeRef(before, "/request"),
            new NodeRef(after, "/request"),
            "GET /ref",
            Direction.REQUEST),
        "schema.reference");
  }

  @Test
  void assertionsOnIntermediateRefsAreNotSkipped() throws Exception {
    ObjectNode root = (ObjectNode) spec("{\"type\":\"string\"}", "3.1.0").root().deepCopy();
    ((ObjectNode) root.at("/components/schemas"))
        .set("Middle", Json.read("{\"$ref\":\"#/components/schemas/Test\",\"maxLength\":1}"));
    Spec spec = Spec.of("chain.json", root);
    JsonNode ref = Json.read("{\"$ref\":\"#/components/schemas/Middle\"}");
    review(
        SchemaComparator.compare(
            spec,
            spec,
            new NodeRef(ref, "/request"),
            new NodeRef(ref, "/request"),
            "GET /ref",
            Direction.REQUEST),
        "schema.reference");
  }

  @Test
  void unresolvedPropertyVisibilityCannotProduceDefinitiveRequiredChanges() throws Exception {
    Spec spec = spec("{\"type\":\"string\"}", "3.1.0");
    JsonNode before = Json.read("{\"type\":\"object\",\"additionalProperties\":false}");
    JsonNode after =
        Json.read(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"x\"],\"properties\":{\"x\":{\"$ref\":\"#/components/schemas/Test\",\"readOnly\":true}}}");
    List<Finding> findings =
        SchemaComparator.compare(
            spec,
            spec,
            new NodeRef(before, "/request"),
            new NodeRef(after, "/request"),
            "GET /ref",
            Direction.REQUEST);
    review(findings, "schema.reference");
    assertTrue(
        findings.stream().noneMatch(f -> f.severity() == Severity.BREAKING || f.witness() != null));
  }

  @Test
  void impossibleRequiredValuesDowngradeChildChanges() throws Exception {
    String before =
        "{\"type\":\"object\",\"required\":[\"x\"],\"properties\":{\"x\":false,\"y\":{\"type\":\"string\"}}}";
    List<Finding> findings =
        compare(before, before.replace("string", "integer"), Direction.REQUEST);
    review(findings, "schema.unsatisfiable");
    assertTrue(
        findings.stream().noneMatch(f -> f.severity() == Severity.BREAKING || f.witness() != null));
    String emptyArrays = "{\"type\":\"array\",\"minItems\":1,\"items\":false}";
    review(compare(emptyArrays, emptyArrays, Direction.REQUEST), "schema.unsatisfiable");
  }

  @Test
  void wideSchemaComparisonStopsWithOneExplicitCoverageFinding() throws Exception {
    ObjectNode schema = Json.MAPPER.createObjectNode().put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    for (int i = 0; i < 1100; i++) properties.putObject("property" + i).put("type", "string");
    List<Finding> findings = compare(Json.compact(schema), Json.compact(schema), Direction.REQUEST);
    review(findings, "schema.analysis_limit");
    assertEquals(
        1, findings.stream().filter(f -> f.rule().equals("schema.analysis_limit")).count());
  }

  @Test
  void scalarWitnessesAndCompatibleResultsAgreeWithAnIndependentNumericOracle() throws Exception {
    List<String> schemas =
        List.of(
            "{\"type\":\"integer\"}",
            "{\"type\":\"number\"}",
            "{\"type\":\"number\",\"minimum\":0}",
            "{\"type\":\"integer\",\"enum\":[0,1]}",
            "{\"type\":\"number\",\"exclusiveMinimum\":0,\"maximum\":1}",
            "{\"type\":\"number\",\"minimum\":0.25,\"maximum\":0.75}",
            "{\"type\":\"integer\",\"minimum\":1.1,\"maximum\":4}",
            "{\"type\":\"number\",\"enum\":[0,0.5,1]}");
    List<BigDecimal> samples =
        List.of("-2", "-1", "-0.5", "0", "0.1", "0.25", "0.5", "0.75", "1", "1.1", "2", "4", "5")
            .stream()
            .map(BigDecimal::new)
            .toList();
    for (String oldSchema : schemas)
      for (String newSchema : schemas)
        for (Direction direction : List.of(Direction.REQUEST, Direction.RESPONSE)) {
          JsonNode oldNode = Json.read(oldSchema), newNode = Json.read(newSchema);
          JsonNode source = direction == Direction.REQUEST ? oldNode : newNode;
          JsonNode target = direction == Direction.REQUEST ? newNode : oldNode;
          List<Finding> findings = compare(oldSchema, newSchema, direction);
          for (Finding finding : findings)
            if (finding.witness() != null) {
              BigDecimal value = finding.witness().value().decimalValue();
              assertTrue(numericOracle(source, value), () -> Json.pretty(finding));
              assertFalse(numericOracle(target, value), () -> Json.pretty(finding));
              assertEquals("schema-fragment", finding.witness().scope());
            }
          if (findings.isEmpty())
            for (BigDecimal sample : samples)
              assertFalse(
                  numericOracle(source, sample) && !numericOracle(target, sample),
                  () ->
                      "False compatible for "
                          + direction
                          + ": "
                          + oldSchema
                          + " -> "
                          + newSchema
                          + " at "
                          + sample);
        }
  }

  private static boolean numericOracle(JsonNode schema, BigDecimal value) {
    if (schema.path("type").asText().equals("integer")
        && value.remainder(BigDecimal.ONE).signum() != 0) return false;
    if (schema.has("minimum") && value.compareTo(schema.get("minimum").decimalValue()) < 0)
      return false;
    if (schema.has("maximum") && value.compareTo(schema.get("maximum").decimalValue()) > 0)
      return false;
    if (schema.has("exclusiveMinimum")
        && value.compareTo(schema.get("exclusiveMinimum").decimalValue()) <= 0) return false;
    if (schema.has("enum")) {
      for (JsonNode member : schema.get("enum"))
        if (value.compareTo(member.decimalValue()) == 0) return true;
      return false;
    }
    return true;
  }
}
