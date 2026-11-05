package io.github.almeida96.contractlens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerTest {
  private static final String INITIALIZED =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

  @Test
  void negotiatesBothSupportedVersionsAndOffersLatestForUnknownVersion() throws Exception {
    for (String version : List.of("2025-11-25", "2025-06-18", "1999-01-01")) {
      JsonNode result = run(initialize(version)).getFirst().get("result");
      assertEquals(
          version.equals("2025-06-18") ? version : "2025-11-25",
          result.path("protocolVersion").asText());
      assertFalse(result.path("capabilities").path("tools").path("listChanged").asBoolean());
      assertEquals("contractlens", result.path("serverInfo").path("name").asText());
    }
  }

  @Test
  void requiresInitializeAndReadyNotificationBeforeTools() throws Exception {
    List<JsonNode> responses =
        run(
            request(1, "tools/list", Map.of()),
            initialize("2025-11-25"),
            request(2, "tools/list", Map.of()),
            INITIALIZED,
            request(3, "tools/list", Map.of()));
    assertCode(responses.get(0), -32002);
    assertCode(responses.get(2), -32002);
    assertEquals(3, responses.get(3).path("result").path("tools").size());
  }

  @Test
  void pingWorksBeforeDuringAndAfterInitialization() throws Exception {
    List<JsonNode> responses =
        run(
            request("early", "ping", Map.of()),
            initialize("2025-11-25"),
            request(2, "ping", Map.of()),
            INITIALIZED,
            request(3, "ping", Map.of()));
    for (int index : List.of(0, 2, 3)) assertTrue(responses.get(index).path("result").isEmpty());
    assertEquals("early", responses.getFirst().path("id").asText());
  }

  @Test
  void rejectsRepeatedInitialization() throws Exception {
    List<JsonNode> responses = run(initialize("2025-11-25"), initialize("2025-06-18"));
    assertCode(responses.getLast(), -32600);
  }

  @Test
  void rejectsMalformedInitializationWithoutAdvancingState() throws Exception {
    List<Object> invalid =
        List.of(
            Map.of(),
            Map.of("protocolVersion", "2025-11-25"),
            Map.of(
                "protocolVersion",
                123,
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                List.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test")),
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1"),
                "path",
                "/tmp"));
    for (Object params : invalid) {
      List<JsonNode> responses = run(request(1, "initialize", params), initialize("2025-11-25"));
      assertCode(responses.getFirst(), -32602);
      assertTrue(responses.getLast().has("result"));
    }
  }

  @Test
  void listsOnlyThreeReadOnlyToolsWithClosedArgumentSchemas() throws Exception {
    JsonNode tools =
        ready(request(1, "tools/list", Map.of())).getFirst().path("result").path("tools");
    assertEquals(
        List.of("review_summary", "list_findings", "finding_evidence"),
        asList(tools).stream().map(tool -> tool.path("name").asText()).toList());
    for (JsonNode tool : tools) {
      assertEquals("object", tool.path("inputSchema").path("type").asText());
      assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
      JsonNode hints = tool.path("annotations");
      assertTrue(hints.path("readOnlyHint").asBoolean());
      assertFalse(hints.path("destructiveHint").asBoolean());
      assertTrue(hints.path("idempotentHint").asBoolean());
      assertFalse(hints.path("openWorldHint").asBoolean());
    }
    assertEquals(
        50,
        tools.get(1).path("inputSchema").path("properties").path("limit").path("maximum").asInt());
    assertEquals("id", tools.get(2).path("inputSchema").path("required").get(0).asText());
  }

  @Test
  void summaryContainsSourcesCountsOperationsConsumersAndLimitationsWithoutFindings()
      throws Exception {
    JsonNode summary = structured(ready(call("review_summary", Map.of())).getFirst());
    assertEquals(24, summary.path("counts").path("total").asInt());
    assertEquals(2, summary.path("operationsCompared").asInt());
    assertEquals("baseline.json", summary.path("baseline").path("name").asText());
    assertEquals("candidate.json", summary.path("candidate").path("name").asText());
    assertEquals(
        List.of("batch", "web"),
        asList(summary.path("consumers")).stream().map(JsonNode::asText).toList());
    assertEquals("Bounded comparison", summary.path("limitations").get(0).asText());
    assertFalse(summary.has("findings"));
  }

  @Test
  void defaultsToTwentyAndPaginatesDeterministically() throws Exception {
    List<JsonNode> responses =
        ready(
            call("list_findings", Map.of()),
            call("list_findings", Map.of("offset", 20)),
            call("list_findings", Map.of("offset", 9999)),
            call("list_findings", Map.of()));
    JsonNode first = structured(responses.get(0));
    JsonNode last = structured(responses.get(1));
    assertEquals(24, first.path("total").asInt());
    assertEquals(20, first.path("findings").size());
    assertEquals(20, first.path("nextOffset").asInt());
    assertEquals("f000", first.path("findings").get(0).path("id").asText());
    assertEquals("f020", last.path("findings").get(0).path("id").asText());
    assertEquals(4, last.path("findings").size());
    assertTrue(last.path("nextOffset").isNull());
    assertTrue(structured(responses.get(2)).path("findings").isEmpty());
    assertEquals(first, structured(responses.get(3)));
    assertFalse(first.path("findings").get(0).has("before"));
  }

  @Test
  void combinesExactFiltersAndSupportsEmptyResults() throws Exception {
    JsonNode result =
        structured(
            ready(
                    call(
                        "list_findings",
                        Map.of(
                            "severity",
                            "BREAKING",
                            "operation",
                            "GET /items",
                            "consumer",
                            "web",
                            "limit",
                            50)))
                .getFirst());
    assertEquals(8, result.path("total").asInt());
    for (JsonNode finding : result.path("findings")) {
      assertEquals("BREAKING", finding.path("severity").asText());
      assertEquals("GET /items", finding.path("operation").asText());
      assertEquals("web", finding.path("consumers").get(0).asText());
    }
    assertEquals(
        0,
        structured(ready(call("list_findings", Map.of("consumer", "unknown"))).getFirst())
            .path("total")
            .asInt());
  }

  @Test
  void evidenceContainsExactValuesPointersWitnessConsumersAndAction() throws Exception {
    JsonNode evidence =
        structured(ready(call("finding_evidence", Map.of("id", "f002"))).getFirst());
    assertEquals("/paths/~1items/get/parameters/0/schema", evidence.path("beforePointer").asText());
    assertEquals(evidence.get("beforePointer"), evidence.get("afterPointer"));
    assertEquals(0, evidence.path("before").path("minimum").asInt());
    assertEquals(1, evidence.path("after").path("minimum").asInt());
    assertEquals(0, evidence.path("witness").path("value").asInt());
    assertEquals("baseline", evidence.path("witness").path("acceptedBy").asText());
    assertEquals("candidate", evidence.path("witness").path("rejectedBy").asText());
    assertEquals("schema-fragment", evidence.path("witness").path("scope").asText());
    assertEquals("Keep the previous minimum", evidence.path("action").asText());
    assertEquals("web", evidence.path("consumers").get(0).asText());
  }

  @Test
  void unknownFindingIsAnExplicitToolErrorWithMatchingStructuredAndTextContent() throws Exception {
    JsonNode response = ready(call("finding_evidence", Map.of("id", "does-not-exist"))).getFirst();
    assertTrue(response.path("result").path("isError").asBoolean());
    assertEquals("FINDING_NOT_FOUND", structured(response).path("error").path("code").asText());
    assertFalse(response.has("error"));
  }

  @Test
  void rejectsUnknownToolsAndMethods() throws Exception {
    List<JsonNode> responses =
        ready(call("write_file", Map.of()), request(2, "resources/list", Map.of()));
    assertCode(responses.get(0), -32602);
    assertCode(responses.get(1), -32601);
  }

  @Test
  void rejectsUnknownFieldsIncludingPathArguments() throws Exception {
    for (String tool : List.of("review_summary", "list_findings", "finding_evidence")) {
      assertCode(ready(call(tool, Map.of("path", "C:/secret.json"))).getFirst(), -32602);
    }
    assertCode(
        ready(request(1, "tools/call", Map.of("name", "review_summary", "extra", true))).getFirst(),
        -32602);
    assertCode(ready(request(1, "tools/list", Map.of("cursor", "bogus"))).getFirst(), -32602);
    assertCode(ready(request(1, "ping", Map.of("extra", true))).getFirst(), -32602);
  }

  @Test
  void rejectsWrongTypesAndOutOfRangeArguments() throws Exception {
    List<Map<String, Object>> bad =
        List.of(
            Map.of("severity", "breaking"),
            Map.of("severity", 2),
            Map.of("operation", " "),
            Map.of("consumer", List.of("web")),
            Map.of("limit", 0),
            Map.of("limit", 51),
            Map.of("limit", 1.5),
            Map.of("limit", "20"),
            Map.of("limit", true),
            Map.of("offset", -1),
            Map.of("offset", 10_001),
            Map.of("offset", 999999999999L));
    for (Map<String, Object> args : bad)
      assertCode(ready(call("list_findings", args)).getFirst(), -32602);
    for (String tool : List.of("list_findings", "finding_evidence")) {
      assertCode(
          ready(request(1, "tools/call", Map.of("name", tool, "arguments", List.of()))).getFirst(),
          -32602);
    }
    assertCode(ready(call("finding_evidence", Map.of())).getFirst(), -32602);
    assertCode(ready(call("finding_evidence", Map.of("id", 42))).getFirst(), -32602);
    assertCode(
        ready(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_findings\",\"arguments\":{\"limit\":null}}}")
            .getFirst(),
        -32602);
    assertCode(ready(request(1, "tools/call", List.of())).getFirst(), -32602);
  }

  @Test
  void acceptsOmittedArgumentsForParameterlessToolsAndMetadataForProtocolRequests()
      throws Exception {
    JsonNode response =
        ready(
                request(
                    1,
                    "tools/call",
                    Map.of("name", "review_summary", "_meta", Map.of("progressToken", 7))))
            .getFirst();
    assertTrue(structured(response).has("counts"));
  }

  @Test
  void notificationsAreSilentAndCannotInitializeOrInvokeTools() throws Exception {
    List<JsonNode> responses =
        run(
            INITIALIZED,
            "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"review_summary\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"unknown\",\"params\":null}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"unknown\",\"extra\":true}",
            request(1, "tools/list", Map.of()));
    assertEquals(1, responses.size());
    assertCode(responses.getFirst(), -32002);
  }

  @Test
  void malformedReadyNotificationDoesNotAdvanceState() throws Exception {
    List<JsonNode> responses =
        run(
            initialize("2025-11-25"),
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":[]}",
            request(1, "tools/list", Map.of()),
            INITIALIZED,
            request(2, "tools/list", Map.of()));
    assertEquals(3, responses.size());
    assertCode(responses.get(1), -32002);
    assertTrue(responses.get(2).has("result"));
  }

  @Test
  void acceptsOnlyStringAndIntegerRequestIdsAndPreservesThem() throws Exception {
    for (String id : List.of("null", "true", "1.25", "[]", "{}")) {
      JsonNode response =
          run("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}").getFirst();
      assertCode(response, -32600);
      assertTrue(response.path("id").isNull());
    }
    for (Object id : List.of("request-α", 0, -3, Long.MAX_VALUE)) {
      JsonNode response = run(request(id, "ping", Map.of())).getFirst();
      assertEquals(Json.MAPPER.valueToTree(id), response.path("id"));
    }
  }

  @Test
  void malformedJsonDuplicateFieldsTrailingDocumentsAndBatchProduceProtocolErrors()
      throws Exception {
    for (String input :
        List.of("{", "", "{}{}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}")) {
      assertCode(run(input).getFirst(), -32700);
    }
    for (String input :
        List.of(
            "[]",
            "[{}]",
            "null",
            "42",
            "{}",
            "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}",
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")) {
      assertCode(run(input).getFirst(), -32600);
    }
  }

  @Test
  void invalidUtf8AndOversizedInputRecoverOnTheNextLine() throws Exception {
    byte[] ping = (request(2, "ping", Map.of()) + "\n").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
    input.write(ping);
    List<JsonNode> utf8Responses = runBytes(new McpServer(report(24)), input.toByteArray());
    assertCode(utf8Responses.getFirst(), -32700);
    assertTrue(utf8Responses.getLast().has("result"));
    input.reset();
    input.write(" ".repeat(McpServer.MAX_LINE_BYTES + 1).getBytes(StandardCharsets.UTF_8));
    input.write('\n');
    input.write(ping);
    List<JsonNode> oversizedResponses = runBytes(new McpServer(report(24)), input.toByteArray());
    assertCode(oversizedResponses.getFirst(), -32700);
    assertTrue(oversizedResponses.getLast().has("result"));
  }

  @Test
  void acceptsExactByteBoundaryCrLfAndFinalLineWithoutNewline() throws Exception {
    String ping = request(1, "ping", Map.of());
    String exact =
        " ".repeat(McpServer.MAX_LINE_BYTES - ping.getBytes(StandardCharsets.UTF_8).length)
            + ping
            + "\n";
    assertTrue(
        runBytes(new McpServer(report(24)), exact.getBytes(StandardCharsets.UTF_8))
            .getFirst()
            .has("result"));
    assertEquals(
        2,
        runBytes(new McpServer(report(24)), (ping + "\r\n" + ping).getBytes(StandardCharsets.UTF_8))
            .size());
  }

  @Test
  void snapshotsMutableReportDataAndBoundsNumberOfFindings() throws Exception {
    Model.Report report = report(24);
    McpServer server = new McpServer(report);
    ((ObjectNode) report.findings().getLast().before()).put("minimum", 999);
    report.findings().clear();
    List<JsonNode> responses =
        runBytes(
            server,
            lines(
                    initialize("2025-11-25"),
                    INITIALIZED,
                    call("finding_evidence", Map.of("id", "f000")))
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(0, structured(responses.getLast()).path("before").path("minimum").asInt());
    assertThrows(IllegalArgumentException.class, () -> new McpServer(report(10_001)));
    Model.Report duplicate = report(2);
    duplicate.findings().set(1, duplicate.findings().getFirst());
    assertThrows(IllegalArgumentException.class, () -> new McpServer(duplicate));
  }

  private static Model.Report report(int count) {
    List<Model.Finding> findings = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      findings.add(
          new Model.Finding(
              "f%03d".formatted(i),
              "minimum-raised",
              i % 3 == 0 ? Model.Severity.REVIEW : Model.Severity.BREAKING,
              i % 2 == 0 ? "GET /items" : "POST /items",
              Model.Direction.REQUEST,
              "/paths/~1items/get/parameters/0/schema",
              "/paths/~1items/get/parameters/0/schema",
              Json.MAPPER.createObjectNode().put("type", "integer").put("minimum", 0),
              Json.MAPPER.createObjectNode().put("type", "integer").put("minimum", 1),
              "Minimum increased",
              "Keep the previous minimum",
              new Model.Witness(
                  Json.MAPPER.getNodeFactory().numberNode(0),
                  "baseline",
                  "candidate",
                  "schema-fragment"),
              List.of(i % 2 == 0 ? "web" : "batch")));
    }
    Collections.reverse(findings);
    return new Model.Report(
        1,
        "0.1.0",
        "BREAKING",
        new Model.Source("baseline.json", "a".repeat(64), "3.1.0"),
        new Model.Source("candidate.json", "b".repeat(64), "3.1.0"),
        2,
        Map.of("total", count, "BREAKING", count - count / 3, "REVIEW", count / 3, "INFO", 0),
        findings,
        List.of(
            new Model.Consumer("web", List.of("GET /items")),
            new Model.Consumer("batch", List.of("POST /items"))),
        List.of("Bounded comparison"));
  }

  private static String initialize(String protocol) {
    return request(
        "init",
        "initialize",
        Map.of(
            "protocolVersion",
            protocol,
            "capabilities",
            Map.of(),
            "clientInfo",
            Map.of("name", "unit-test", "version", "1")));
  }

  private static String request(Object id, String method, Object params) {
    return Json.compact(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
  }

  private static String call(String name, Map<String, ?> args) {
    return request(7, "tools/call", Map.of("name", name, "arguments", args));
  }

  private static List<JsonNode> ready(String... requests) throws Exception {
    List<String> messages = new ArrayList<>(List.of(initialize("2025-11-25"), INITIALIZED));
    messages.addAll(Arrays.asList(requests));
    return run(messages.toArray(String[]::new)).subList(1, requests.length + 1);
  }

  private static List<JsonNode> run(String... messages) throws Exception {
    return runBytes(new McpServer(report(24)), lines(messages).getBytes(StandardCharsets.UTF_8));
  }

  private static String lines(String... messages) {
    return String.join("\n", messages) + "\n";
  }

  private static List<JsonNode> runBytes(McpServer server, byte[] bytes) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.run(new ByteArrayInputStream(bytes), output);
    List<JsonNode> results = new ArrayList<>();
    for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
      JsonNode response = Json.read(line);
      assertEquals("2.0", response.path("jsonrpc").asText());
      results.add(response);
    }
    return results;
  }

  private static JsonNode structured(JsonNode response) throws Exception {
    assertFalse(response.has("error"), response.toString());
    JsonNode result = response.path("result");
    assertEquals(1, result.path("content").size());
    assertEquals("text", result.path("content").get(0).path("type").asText());
    JsonNode structured = result.path("structuredContent");
    assertEquals(structured, Json.read(result.path("content").get(0).path("text").asText()));
    return structured;
  }

  private static void assertCode(JsonNode response, int expected) {
    assertEquals(expected, response.path("error").path("code").asInt(), response.toString());
    assertFalse(response.has("result"));
  }

  private static List<JsonNode> asList(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    array.forEach(values::add);
    return values;
  }
}
