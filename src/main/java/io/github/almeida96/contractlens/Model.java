package io.github.almeida96.contractlens;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public final class Model {
  private Model() {}

  public enum Severity {
    BREAKING,
    REVIEW,
    INFO
  }

  public enum Direction {
    REQUEST,
    RESPONSE,
    OPERATION
  }

  public record NodeRef(JsonNode value, String pointer) {}

  /** Witnesses are schema-fragment values, never complete HTTP requests. */
  public record Witness(JsonNode value, String acceptedBy, String rejectedBy, String scope) {}

  public record Finding(
      String id,
      String rule,
      Severity severity,
      String operation,
      Direction direction,
      String beforePointer,
      String afterPointer,
      JsonNode before,
      JsonNode after,
      String message,
      String action,
      Witness witness,
      List<String> consumers) {}

  public record Source(String name, String sha256, String openapi) {}

  public record Consumer(String name, List<String> operations) {}

  public record Report(
      int schemaVersion,
      String toolVersion,
      String verdict,
      Source baseline,
      Source candidate,
      int operationsCompared,
      Map<String, Integer> counts,
      List<Finding> findings,
      List<Consumer> consumers,
      List<String> limitations) {}
}
