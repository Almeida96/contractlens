package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Content-derived identities remain stable when files move or object keys are reordered. */
public final class Findings {
  private Findings() {}

  public static Finding make(
      String rule,
      Severity severity,
      String operation,
      Direction direction,
      NodeRef before,
      NodeRef after,
      String message,
      String action,
      Witness witness) {
    String beforePointer = before == null ? null : before.pointer();
    String afterPointer = after == null ? null : after.pointer();
    JsonNode oldValue = before == null ? null : before.value();
    JsonNode newValue = after == null ? null : after.value();
    var identity = new ArrayList<Object>();
    identity.add(rule);
    identity.add(severity.name());
    identity.add(operation);
    identity.add(direction.name());
    identity.add(beforePointer);
    identity.add(afterPointer);
    identity.add(canonical(oldValue));
    identity.add(canonical(newValue));
    return new Finding(
        "cl-" + Json.hash(Json.compact(identity)).substring(0, 20),
        rule,
        severity,
        operation,
        direction,
        beforePointer,
        afterPointer,
        oldValue,
        newValue,
        message,
        action,
        witness,
        List.of());
  }

  static JsonNode canonical(JsonNode node) {
    if (node == null) return null;
    if (node.isObject()) {
      ObjectNode result = Json.MAPPER.createObjectNode();
      var keys = new TreeSet<String>();
      node.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) result.set(key, canonical(node.get(key)));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = Json.MAPPER.createArrayNode();
      node.forEach(value -> result.add(canonical(value)));
      return result;
    }
    return node.deepCopy();
  }
}
