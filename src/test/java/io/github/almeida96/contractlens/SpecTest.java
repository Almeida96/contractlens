package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecTest {
  @TempDir Path directory;
  private static final String BASE =
      "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Example\",\"version\":\"1\"},\"paths\":{}}";

  @Test
  void loadsJsonAndYamlWithSourceHashes() throws Exception {
    Path json = directory.resolve("api.json"), yaml = directory.resolve("api.yaml");
    Files.writeString(json, BASE);
    Files.writeString(yaml, "openapi: 3.0.3\ninfo:\n  title: Example\n  version: '1'\npaths: {}\n");
    Spec first = Spec.load(json), second = Spec.load(yaml);
    assertTrue(first.is31());
    assertFalse(second.is31());
    assertEquals("api.json", first.source().name());
    assertEquals(Json.hash(Files.readAllBytes(json)), first.source().sha256());
    assertEquals("3.0.3", second.source().openapi());
  }

  @Test
  void rejectsDuplicateKeysAndMultipleDocuments() throws Exception {
    for (String content :
        new String[] {
          BASE.replace("\"paths\":{}", "\"paths\":{},\"paths\":{}"),
          "openapi: 3.1.0\nopenapi: 3.0.3\ninfo: {title: Example, version: '1'}\npaths: {}\n",
          BASE + "\n" + BASE,
          "openapi: 3.1.0\ninfo: {title: Example, version: '1'}\npaths: {}\n---\nhello: world\n"
        }) {
      Path file = directory.resolve("invalid.yaml");
      Files.writeString(file, content);
      assertThrows(Exception.class, () -> Spec.load(file), content);
    }
  }

  @Test
  void utf8BomDoesNotSwitchJsonToTheYamlParser() throws Exception {
    Path file = directory.resolve("bom.json");
    Files.writeString(file, "\ufeff" + BASE);
    Spec valid = Spec.load(file);
    assertEquals("3.1.0", valid.source().openapi());
    assertEquals(Json.hash(Files.readAllBytes(file)), valid.source().sha256());

    for (String invalid : new String[] {
      BASE.replace("\"paths\":{}", "\"paths\":{},"),
      BASE.replace("\"paths\":{}", "paths: {}")
    }) {
      Files.writeString(file, "\ufeff \n" + invalid);
      assertThrows(Exception.class, () -> Spec.load(file), invalid);
    }
  }

  @Test
  void rejectsYamlAliasExpansionAndOversizedInput() throws Exception {
    Path file = directory.resolve("aliases.yaml");
    Files.writeString(
        file,
        "openapi: 3.1.0\ninfo: &info {title: Example, version: '1'}\npaths: {}\nx-copy: *info\n");
    assertThrows(Exception.class, () -> Spec.load(file));
    Files.write(file, new byte[Spec.MAX_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> Spec.load(file));
  }

  @Test
  void validatesVersionAndStructuralRoot() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.of("wrong", Json.read(BASE.replace("3.1.0", "2.0"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.of("wrong", Json.read(BASE.replace("\"paths\":{}", "\"paths\":[]"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.of("wrong", Json.read(BASE.replace("\"version\":\"1\"", "\"version\":1"))));
    ObjectNode root = (ObjectNode) Json.read(BASE);
    for (int i = 0; i <= Spec.MAX_OPERATIONS; i++)
      ((ObjectNode) root.path("paths")).putObject("/p" + i).putObject("get");
    assertThrows(IllegalArgumentException.class, () -> Spec.of("many", root));
  }

  @Test
  void resolvesEscapedPointersAndPreservesActualTarget() throws Exception {
    ObjectNode root = (ObjectNode) Json.read(BASE);
    root.putObject("components").putObject("schemas").putObject("A/B~C").put("type", "string");
    Spec spec = Spec.of("api", root);
    NodeRef result =
        spec.resolve(
            new NodeRef(Json.read("{\"$ref\":\"#/components/schemas/A~1B~0C\"}"), "/schema"));
    assertEquals("/components/schemas/A~1B~0C", result.pointer());
    assertEquals("string", result.value().path("type").asText());
    assertEquals(result, spec.resolve(result));
  }

  @Test
  void rejectsExternalDanglingAndCircularReferenceChains() throws Exception {
    ObjectNode root = (ObjectNode) Json.read(BASE);
    var schemas = root.putObject("components").putObject("schemas");
    schemas.putObject("A").put("$ref", "#/components/schemas/B");
    schemas.putObject("B").put("$ref", "#/components/schemas/A");
    Spec spec = Spec.of("api", root);
    for (String reference :
        new String[] {
          "https://example.com/api.yaml#/schema",
          "#/missing",
          "#/components/schemas/A",
          "#/bad~2token"
        }) {
      ObjectNode ref = Json.MAPPER.createObjectNode().put("$ref", reference);
      assertThrows(IllegalArgumentException.class, () -> spec.resolve(new NodeRef(ref, "/schema")));
    }
  }

  @Test
  void ofCopiesInputAndHashesContentWithoutFilename() throws Exception {
    ObjectNode root = (ObjectNode) Json.read(BASE);
    Spec first = Spec.of("first.json", root), second = Spec.of("renamed.yaml", root);
    root.put("openapi", "bad");
    assertEquals(first.source().sha256(), second.source().sha256());
    assertEquals("3.1.0", first.root().path("openapi").asText());
  }

  @Test
  void rejectsBehavioralSiblingsAtEveryReferenceHop() throws Exception {
    ObjectNode root = (ObjectNode) Json.read(BASE);
    var schemas = root.putObject("components").putObject("schemas");
    schemas.putObject("Value").put("type", "string");
    schemas
        .putObject("Alias")
        .put("$ref", "#/components/schemas/Value")
        .putArray("enum")
        .add("booked");
    NodeRef entry =
        new NodeRef(
            Json.MAPPER.createObjectNode().put("$ref", "#/components/schemas/Alias"), "/schema");
    assertThrows(IllegalArgumentException.class, () -> Spec.of("api", root).resolve(entry));
    ((ObjectNode) schemas.get("Alias")).remove("enum");
    ((ObjectNode) schemas.get("Alias"))
        .put("description", "A description does not constrain values");
    assertEquals("/components/schemas/Value", Spec.of("api", root).resolve(entry).pointer());
    ((ObjectNode) schemas.get("Alias")).put("readOnly", true);
    assertThrows(IllegalArgumentException.class, () -> Spec.of("api", root).resolve(entry));
  }

  @Test
  void rejectsUnboundedNumericExponentsBeforeComparisonOrSerialization() throws Exception {
    for (String value : new String[] {"1e2147483647", "1e-2147483647", "1e1001", "1e-1001"}) {
      Path file = directory.resolve("large-number.json");
      Files.writeString(file, BASE.replace("\"paths\":{}", "\"paths\":{},\"x-number\":" + value));
      assertThrows(Exception.class, () -> Spec.load(file), value);
    }
    ObjectNode root = (ObjectNode) Json.read(BASE);
    root.put("x-number", new java.math.BigDecimal("1e2147483647"));
    assertThrows(IllegalArgumentException.class, () -> Spec.of("number", root));
    Path yaml = directory.resolve("large-number.yaml");
    Files.writeString(
        yaml,
        "openapi: 3.1.0\ninfo: {title: Example, version: '1'}\npaths: {}\nx-number: 1.0e+1001\n");
    assertThrows(Exception.class, () -> Spec.load(yaml));
  }

  @Test
  void preservesDecimalBoundsBeyondDoublePrecision() throws Exception {
    String exact = "0.100000000000000001";
    Path json = directory.resolve("decimal.json"), yaml = directory.resolve("decimal.yaml");
    Files.writeString(json, BASE.replace("\"paths\":{}", "\"paths\":{},\"x-number\":" + exact));
    Files.writeString(
        yaml,
        "openapi: 3.1.0\ninfo: {title: Example, version: '1'}\npaths: {}\nx-number: "
            + exact
            + "\n");
    assertEquals(
        new java.math.BigDecimal(exact), Spec.load(json).root().get("x-number").decimalValue());
    assertEquals(
        new java.math.BigDecimal(exact), Spec.load(yaml).root().get("x-number").decimalValue());
  }
}
