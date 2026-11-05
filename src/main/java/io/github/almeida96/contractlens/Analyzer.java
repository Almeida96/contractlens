package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Direction-aware operation checks. Unproved changes remain visible as REVIEW. */
public final class Analyzer {
  public static final int MAX_FINDINGS = 10000;
  public static final long MAX_EVIDENCE_BYTES = 8L * 1024 * 1024;
  private static final JsonNode EMPTY_ARRAY = Json.MAPPER.createArrayNode();
  private static final List<String> LIMITATIONS =
      List.of(
          "Structural OpenAPI 3.0/3.1 checks and a bounded schema subset; this is not a complete"
              + " OpenAPI validator or proof of runtime compatibility.",
          "Same-document JSON Pointer references only; unsupported references and schema assertions"
              + " require review.",
          "Runtime behavior, generated SDK source compatibility, auth semantics, callbacks and"
              + " webhook behavior are not proven.",
          "Witnesses describe individual schema fragments, not complete HTTP requests or"
              + " responses.");

  private record Operation(String identity, NodeRef node, NodeRef pathItem) {}

  private record Operations(Map<String, Operation> values, Set<String> unknownPaths) {}

  private record Parameters(Map<String, NodeRef> values, boolean complete) {}

  private final List<Finding> findings = new ArrayList<>();
  private final Set<String> findingIds = new TreeSet<>();
  private final Map<JsonNode, Integer> nodeSizes = new IdentityHashMap<>();
  private long evidenceBytes;
  private Spec baseline;

  public static Report compare(Spec baseline, Spec candidate, List<Consumer> consumers) {
    return new Analyzer().run(baseline, candidate, consumers == null ? List.of() : consumers);
  }

  private Report run(Spec baseline, Spec candidate, List<Consumer> consumers) {
    this.baseline = baseline;
    Operations oldOperations = operations(baseline), newOperations = operations(candidate);
    Map<String, Operation> before = oldOperations.values(), after = newOperations.values();
    Set<String> knownOperations = union(before, after);
    Map<String, TreeSet<String>> consumerNames = new TreeMap<>();
    for (Consumer consumer : consumers)
      for (String operation : consumer.operations()) {
        if (!knownOperations.contains(operation))
          throw new IllegalArgumentException(
              "Unknown operation in consumer '" + consumer.name() + "': " + operation);
        consumerNames.computeIfAbsent(operation, ignored -> new TreeSet<>()).add(consumer.name());
      }
    reserve(encodedSize(consumers));
    int compared = 0;
    for (String key : union(before, after)) {
      Operation old = before.get(key), next = after.get(key);
      String path = key.substring(key.indexOf(' ') + 1);
      if ((old == null && oldOperations.unknownPaths().contains(path))
          || (next == null && newOperations.unknownPaths().contains(path))) continue;
      if (old == null) {
        add(
            "operation-added",
            Severity.INFO,
            key,
            Direction.OPERATION,
            null,
            next.node(),
            "A new operation is available.",
            "Existing clients do not need to use the new operation.");
      } else if (next == null) {
        add(
            "operation-removed",
            Severity.BREAKING,
            key,
            Direction.OPERATION,
            old.node(),
            null,
            "An operation used by existing clients was removed.",
            "Keep the operation available or provide a versioned migration.");
      } else {
        compared++;
        compareOperation(baseline, candidate, old, next);
      }
      limit();
    }
    compareGlobal(baseline, candidate, "webhooks", "webhooks-changed");
    compareGlobal(baseline, candidate, "jsonSchemaDialect", "schema-dialect-changed");
    for (Spec spec : List.of(baseline, candidate)) {
      NodeRef dialect = at(spec, "/jsonSchemaDialect");
      if (dialect != null
          && !dialect.value().asText().equals("https://spec.openapis.org/oas/3.1/dialect/base"))
        add(
            "schema-dialect-unsupported",
            Severity.REVIEW,
            "*",
            Direction.OPERATION,
            spec == baseline ? dialect : null,
            spec == candidate ? dialect : null,
            "The document declares a JSON Schema dialect outside the supported subset.",
            "Use the default OpenAPI dialect or review schema assertions with a dialect-aware"
                + " validator.");
      NodeRef webhooks = at(spec, "/webhooks");
      if (webhooks != null && !webhooks.value().isEmpty())
        add(
            "webhooks-unsupported",
            Severity.REVIEW,
            "*",
            Direction.OPERATION,
            spec == baseline ? webhooks : null,
            spec == candidate ? webhooks : null,
            "Webhook contracts are outside the supported comparison subset.",
            "Review webhook changes separately.");
    }
    var ordered = new TreeMap<String, Finding>();
    for (Finding finding : findings) ordered.putIfAbsent(finding.id(), finding);
    List<Finding> result =
        ordered.values().stream()
            .sorted(
                Comparator.comparing(Finding::operation)
                    .thenComparing(Finding::rule)
                    .thenComparing(f -> Objects.toString(f.beforePointer(), ""))
                    .thenComparing(f -> Objects.toString(f.afterPointer(), ""))
                    .thenComparing(Finding::id))
            .map(
                f -> {
                  List<String> names =
                      consumerNames.containsKey(f.operation())
                          ? List.copyOf(consumerNames.get(f.operation()))
                          : List.of();
                  reserve(encodedSize(names));
                  return new Finding(
                      f.id(),
                      f.rule(),
                      f.severity(),
                      f.operation(),
                      f.direction(),
                      f.beforePointer(),
                      f.afterPointer(),
                      f.before(),
                      f.after(),
                      f.message(),
                      f.action(),
                      f.witness(),
                      names);
                })
            .toList();
    var counts = new LinkedHashMap<String, Integer>();
    for (Severity severity : Severity.values())
      counts.put(
          severity.name(), (int) result.stream().filter(f -> f.severity() == severity).count());
    String verdict =
        counts.get("BREAKING") > 0
            ? "BREAKING"
            : counts.get("REVIEW") > 0 ? "REVIEW" : "COMPATIBLE";
    return new Report(
        1,
        "0.1.0",
        verdict,
        baseline.source(),
        candidate.source(),
        compared,
        counts,
        result,
        List.copyOf(consumers),
        LIMITATIONS);
  }

  private Operations operations(Spec spec) {
    var result = new TreeMap<String, Operation>();
    var unknownPaths = new TreeSet<String>();
    var paths = spec.root().path("paths").fields();
    while (paths.hasNext()) {
      var entry = paths.next();
      if (entry.getKey().startsWith("x-")) continue;
      NodeRef original = new NodeRef(entry.getValue(), "/paths/" + Json.escape(entry.getKey()));
      NodeRef item = resolve(spec, original, "*", Direction.OPERATION);
      if (item == null) {
        unknownPaths.add(entry.getKey());
        continue;
      }
      Spec.object(item.value(), "Path item " + entry.getKey());
      if (original.value().has("$ref") && original.value().size() > 1)
        add(
            "path-reference-siblings",
            Severity.REVIEW,
            "*",
            Direction.OPERATION,
            spec == baseline ? original : null,
            spec != baseline ? original : null,
            "Path item reference siblings are not merged by this analyzer.",
            "Inline the path item before comparing.");
      for (String method : new TreeSet<>(Spec.METHODS)) {
        if (!item.value().has(method)) continue;
        String identity = method.toUpperCase(Locale.ROOT) + " " + entry.getKey();
        NodeRef operation = child(item, method);
        Spec.object(operation.value(), identity);
        Spec.object(operation.value().get("responses"), identity + " responses");
        if (operation.value().get("responses").isEmpty())
          throw new IllegalArgumentException(identity + " responses must not be empty");
        result.put(identity, new Operation(identity, operation, item));
        if (result.size() > Spec.MAX_OPERATIONS)
          throw new IllegalArgumentException("Specification exceeds 1000 operations");
      }
    }
    return new Operations(result, unknownPaths);
  }

  private void compareOperation(Spec oldSpec, Spec newSpec, Operation before, Operation after) {
    String operation = before.identity();
    compareParameters(oldSpec, newSpec, before, after);
    compareBody(oldSpec, newSpec, before, after);
    compareResponses(oldSpec, newSpec, before, after);
    compareSecurity(oldSpec, newSpec, before, after);
    NodeRef oldServers = effective(oldSpec, before, "servers");
    NodeRef newServers = effective(newSpec, after, "servers");
    changedReview(
        "servers-changed",
        operation,
        Direction.OPERATION,
        oldServers,
        newServers,
        "Effective server URLs changed.",
        "Check client base URLs, variables and deployment routing.");
    NodeRef oldCallbacks = child(before.node(), "callbacks"),
        newCallbacks = child(after.node(), "callbacks");
    if ((oldCallbacks != null && !oldCallbacks.value().isEmpty())
        || (newCallbacks != null && !newCallbacks.value().isEmpty()))
      add(
          "callbacks-unsupported",
          Severity.REVIEW,
          operation,
          Direction.OPERATION,
          oldCallbacks,
          newCallbacks,
          "Callback contracts are outside the supported comparison subset.",
          "Review callbacks separately.");
    if (!equal(child(before.node(), "operationId"), child(after.node(), "operationId")))
      add(
          "operation-id-changed",
          Severity.INFO,
          operation,
          Direction.OPERATION,
          child(before.node(), "operationId"),
          child(after.node(), "operationId"),
          "The operation identifier changed; generated SDK method names may change.",
          "Check generated SDK source compatibility separately.");
  }

  private void compareParameters(Spec oldSpec, Spec newSpec, Operation before, Operation after) {
    Parameters old = parameters(oldSpec, before), next = parameters(newSpec, after);
    String operation = before.identity();
    for (String key : union(old.values(), next.values())) {
      NodeRef a = old.values().get(key), b = next.values().get(key);
      if (a == null) {
        if (old.complete() && next.complete() && required(b))
          add(
              "request-parameter-required",
              Severity.BREAKING,
              operation,
              Direction.REQUEST,
              null,
              b,
              "A new required request parameter was added.",
              "Make the parameter optional or introduce a versioned operation.");
        continue;
      }
      if (b == null) {
        if (old.complete() && next.complete())
          add(
              "request-parameter-removed",
              Severity.REVIEW,
              operation,
              Direction.REQUEST,
              a,
              null,
              "A documented request parameter was removed.",
              "Confirm that old clients can continue sending this parameter.");
        continue;
      }
      if (!required(a) && required(b))
        add(
            "request-parameter-required",
            Severity.BREAKING,
            operation,
            Direction.REQUEST,
            child(a, "required"),
            child(b, "required"),
            "An optional request parameter became required.",
            "Keep the parameter optional until clients have migrated.");
      for (String field : List.of("style", "explode", "allowReserved", "allowEmptyValue")) {
        JsonNode oldValue = parameterSetting(a.value(), field),
            newValue = parameterSetting(b.value(), field);
        if (!oldValue.equals(newValue))
          add(
              "parameter-serialization-changed",
              Severity.REVIEW,
              operation,
              Direction.REQUEST,
              child(a, field),
              child(b, field),
              "Effective parameter serialization setting '"
                  + field
                  + "' changed from "
                  + oldValue
                  + " to "
                  + newValue
                  + "; absent values use the OpenAPI default.",
              "Verify encoded requests from existing clients.");
      }
      schema(
          oldSpec, newSpec, child(a, "schema"), child(b, "schema"), operation, Direction.REQUEST);
      if (a.value().has("content") || b.value().has("content"))
        compareContent(
            oldSpec,
            newSpec,
            child(a, "content"),
            child(b, "content"),
            operation,
            Direction.REQUEST);
    }
  }

  private Parameters parameters(Spec spec, Operation operation) {
    var values = new TreeMap<String, NodeRef>();
    boolean complete = true;
    for (NodeRef owner : List.of(operation.pathItem(), operation.node())) {
      NodeRef parameters = child(owner, "parameters");
      if (parameters == null) continue;
      if (!parameters.value().isArray())
        throw new IllegalArgumentException(parameters.pointer() + " must be an array");
      var seen = new TreeSet<String>();
      for (int i = 0; i < parameters.value().size(); i++) {
        NodeRef original = new NodeRef(parameters.value().get(i), parameters.pointer() + "/" + i);
        NodeRef parameter = resolve(spec, original, operation.identity(), Direction.REQUEST);
        if (parameter == null) {
          complete = false;
          continue;
        }
        Spec.object(parameter.value(), parameter.pointer());
        Spec.text(parameter.value().get("name"), parameter.pointer() + "/name");
        String location = parameter.value().path("in").asText();
        if (!List.of("query", "header", "path", "cookie").contains(location))
          throw new IllegalArgumentException(
              "Invalid parameter location at " + parameter.pointer());
        String name = parameter.value().path("name").textValue();
        if (location.equals("header")) name = name.toLowerCase(Locale.ROOT);
        String key = location + ":" + name;
        if (!seen.add(key))
          throw new IllegalArgumentException(
              "Duplicate parameter " + key + " at " + parameters.pointer());
        if (location.equals("path") && !required(parameter))
          throw new IllegalArgumentException(
              "Path parameters must be required: " + parameter.pointer());
        required(parameter);
        if (parameter.value().has("schema") && parameter.value().has("content"))
          throw new IllegalArgumentException(
              "Parameter cannot contain both schema and content: " + parameter.pointer());
        values.put(key, parameter);
      }
    }
    return new Parameters(values, complete);
  }

  private void compareBody(Spec oldSpec, Spec newSpec, Operation before, Operation after) {
    NodeRef oldOriginal = child(before.node(), "requestBody"),
        newOriginal = child(after.node(), "requestBody");
    NodeRef old = resolve(oldSpec, oldOriginal, before.identity(), Direction.REQUEST);
    NodeRef next = resolve(newSpec, newOriginal, before.identity(), Direction.REQUEST);
    if ((oldOriginal != null && old == null) || (newOriginal != null && next == null)) return;
    if (old != null) {
      Spec.object(old.value(), old.pointer());
      Spec.object(old.value().get("content"), old.pointer() + "/content");
      required(old);
    }
    if (next != null) {
      Spec.object(next.value(), next.pointer());
      Spec.object(next.value().get("content"), next.pointer() + "/content");
      required(next);
    }
    if (!required(old) && required(next))
      add(
          "request-body-required",
          Severity.BREAKING,
          before.identity(),
          Direction.REQUEST,
          old == null ? null : child(old, "required"),
          child(next, "required"),
          "A request body became mandatory.",
          "Continue accepting requests without a body during migration.");
    if (old != null && next == null) {
      add(
          "request-body-removed",
          Severity.REVIEW,
          before.identity(),
          Direction.REQUEST,
          old,
          null,
          "The documented request body was removed.",
          "Confirm existing clients may continue sending their request body.");
      return;
    }
    if (old != null && next != null)
      compareContent(
          oldSpec,
          newSpec,
          child(old, "content"),
          child(next, "content"),
          before.identity(),
          Direction.REQUEST);
  }

  private void compareResponses(Spec oldSpec, Spec newSpec, Operation before, Operation after) {
    NodeRef old = child(before.node(), "responses"), next = child(after.node(), "responses");
    for (String status : union(fields(old), fields(next))) {
      if (status.startsWith("x-")) continue;
      if (!status.equals("default") && !status.matches("[1-5](?:[0-9]{2}|XX)"))
        throw new IllegalArgumentException(
            "Invalid response status " + status + " in " + before.identity());
      NodeRef a = child(old, status), b = child(next, status);
      if (a == null || b == null) {
        add(
            "response-status-changed",
            Severity.REVIEW,
            before.identity(),
            Direction.RESPONSE,
            a,
            b,
            "A response status was " + (a == null ? "added" : "removed") + ": " + status + ".",
            "Check status ranges, default responses and existing client handling.");
        continue;
      }
      a = resolve(oldSpec, a, before.identity(), Direction.RESPONSE);
      b = resolve(newSpec, b, before.identity(), Direction.RESPONSE);
      if (a == null || b == null) continue;
      Spec.object(a.value(), a.pointer());
      Spec.object(b.value(), b.pointer());
      compareContent(
          oldSpec,
          newSpec,
          child(a, "content"),
          child(b, "content"),
          before.identity(),
          Direction.RESPONSE);
      for (String field : List.of("headers", "links")) {
        NodeRef av = child(a, field), bv = child(b, field);
        if (av != null) Spec.object(av.value(), av.pointer());
        if (bv != null) Spec.object(bv.value(), bv.pointer());
        changedReview(
            "response-" + field + "-changed",
            before.identity(),
            Direction.RESPONSE,
            av,
            bv,
            "Response " + field + " changed.",
            "Check consumer expectations for response " + field + ".");
        // Changes behind equal local references are still observable.
        for (String name : union(fields(av), fields(bv))) {
          NodeRef ar = child(av, name), br = child(bv, name);
          if (ar != null && br != null && (ar.value().has("$ref") || br.value().has("$ref"))) {
            NodeRef resolvedA = resolve(oldSpec, ar, before.identity(), Direction.RESPONSE);
            NodeRef resolvedB = resolve(newSpec, br, before.identity(), Direction.RESPONSE);
            if (resolvedA != null && resolvedB != null)
              changedReview(
                  "response-" + field + "-changed",
                  before.identity(),
                  Direction.RESPONSE,
                  resolvedA,
                  resolvedB,
                  "Referenced response " + field + " changed.",
                  "Check consumer expectations for response " + field + ".");
          }
          if (field.equals("headers") && ar != null && br != null) {
            NodeRef headerA = resolve(oldSpec, ar, before.identity(), Direction.RESPONSE);
            NodeRef headerB = resolve(newSpec, br, before.identity(), Direction.RESPONSE);
            if (headerA != null && headerB != null)
              schema(
                  oldSpec,
                  newSpec,
                  child(headerA, "schema"),
                  child(headerB, "schema"),
                  before.identity(),
                  Direction.RESPONSE);
            if (headerA != null
                && headerB != null
                && (headerA.value().has("content") || headerB.value().has("content")))
              compareContent(
                  oldSpec,
                  newSpec,
                  child(headerA, "content"),
                  child(headerB, "content"),
                  before.identity(),
                  Direction.RESPONSE);
          }
        }
      }
    }
  }

  private void compareContent(
      Spec oldSpec,
      Spec newSpec,
      NodeRef before,
      NodeRef after,
      String operation,
      Direction direction) {
    if (before != null) Spec.object(before.value(), before.pointer());
    if (after != null) Spec.object(after.value(), after.pointer());
    for (String media : union(fields(before), fields(after))) {
      NodeRef a = child(before, media), b = child(after, media);
      if (a == null || b == null) {
        if (direction == Direction.REQUEST && a == null) continue;
        boolean uncertainMedia =
            direction == Direction.REQUEST
                && a != null
                && mediaOverlap(media, fields(after).keySet());
        add(
            direction == Direction.REQUEST ? "request-media-removed" : "response-media-changed",
            direction == Direction.REQUEST && !uncertainMedia ? Severity.BREAKING : Severity.REVIEW,
            operation,
            direction,
            a,
            b,
            "Media type '" + media + "' was " + (a == null ? "added." : "removed."),
            "Check content negotiation and preserve media types used by existing clients.");
        continue;
      }
      Spec.object(a.value(), a.pointer());
      Spec.object(b.value(), b.pointer());
      schema(oldSpec, newSpec, child(a, "schema"), child(b, "schema"), operation, direction);
      NodeRef oldEncoding = child(a, "encoding"), newEncoding = child(b, "encoding");
      if (oldEncoding != null || newEncoding != null)
        add(
            "media-encoding-unsupported",
            Severity.REVIEW,
            operation,
            direction,
            oldEncoding,
            newEncoding,
            "Multipart and form encoding behavior is outside the supported comparison subset.",
            "Verify encoding, per-part headers and referenced schemas for existing payloads.");
    }
  }

  private void compareSecurity(Spec before, Spec after, Operation old, Operation next) {
    NodeRef a = effective(before, old, "security"), b = effective(after, next, "security");
    JsonNode av = a == null ? EMPTY_ARRAY : a.value(), bv = b == null ? EMPTY_ARRAY : b.value();
    if (!av.isArray() || !bv.isArray())
      throw new IllegalArgumentException("security must be an array");
    if (!av.equals(bv))
      add(
          "security-changed",
          Severity.REVIEW,
          old.identity(),
          Direction.OPERATION,
          a,
          b,
          "Effective authentication or authorization requirements changed.",
          "Check security alternatives, scopes and client credentials.");
    var names = new TreeSet<String>();
    for (JsonNode list : List.of(av, bv))
      for (JsonNode requirement : list) {
        Spec.object(requirement, "security requirement");
        requirement.fieldNames().forEachRemaining(names::add);
      }
    for (String name : names) {
      String pointer = "/components/securitySchemes/" + Json.escape(name);
      NodeRef ar = at(before, pointer), br = at(after, pointer);
      if (ar != null) ar = resolve(before, ar, old.identity(), Direction.OPERATION);
      if (br != null) br = resolve(after, br, old.identity(), Direction.OPERATION);
      if (ar == null || br == null)
        add(
            "security-scheme-unresolved",
            Severity.REVIEW,
            old.identity(),
            Direction.OPERATION,
            ar,
            br,
            "A referenced security scheme is unavailable.",
            "Define the security scheme and review its requirements.");
      else
        changedReview(
            "security-scheme-changed",
            old.identity(),
            Direction.OPERATION,
            ar,
            br,
            "A referenced security scheme changed.",
            "Verify credential, scope and authentication handling.");
    }
  }

  private void schema(
      Spec oldSpec,
      Spec newSpec,
      NodeRef before,
      NodeRef after,
      String operation,
      Direction direction) {
    if (before == null && after == null) return;
    for (Finding finding :
        SchemaComparator.compare(oldSpec, newSpec, before, after, operation, direction))
      record(finding);
    limit();
  }

  private NodeRef resolve(Spec spec, NodeRef ref, String operation, Direction direction) {
    try {
      return spec.resolve(ref);
    } catch (IllegalArgumentException ex) {
      add(
          "reference-unsupported",
          Severity.REVIEW,
          operation,
          direction,
          spec == baseline ? ref : null,
          spec != baseline ? ref : null,
          ex.getMessage(),
          "Inline or resolve the reference locally before comparing.");
      return null;
    }
  }

  private void compareGlobal(Spec before, Spec after, String field, String rule) {
    changedReview(
        rule,
        "*",
        Direction.OPERATION,
        at(before, "/" + field),
        at(after, "/" + field),
        "Document field '" + field + "' changed outside the supported subset.",
        "Review this contract change manually.");
  }

  private void changedReview(
      String rule,
      String operation,
      Direction direction,
      NodeRef before,
      NodeRef after,
      String message,
      String action) {
    if (!equal(before, after))
      add(rule, Severity.REVIEW, operation, direction, before, after, message, action);
  }

  private void add(
      String rule,
      Severity severity,
      String operation,
      Direction direction,
      NodeRef before,
      NodeRef after,
      String message,
      String action) {
    record(
        Findings.make(rule, severity, operation, direction, before, after, message, action, null));
    limit();
  }

  private void record(Finding finding) {
    if (!findingIds.add(finding.id())) return;
    long size = nodeSize(finding.before()) + (long) nodeSize(finding.after());
    if (finding.witness() != null) size += encodedSize(finding.witness());
    Finding metadata =
        new Finding(
            finding.id(),
            finding.rule(),
            finding.severity(),
            finding.operation(),
            finding.direction(),
            finding.beforePointer(),
            finding.afterPointer(),
            null,
            null,
            finding.message(),
            finding.action(),
            null,
            List.of());
    size += encodedSize(metadata);
    reserve(size);
    findings.add(finding);
    limit();
  }

  private int nodeSize(JsonNode node) {
    return node == null ? 0 : nodeSizes.computeIfAbsent(node, Analyzer::encodedSize);
  }

  private static int encodedSize(Object value) {
    class Counter extends OutputStream {
      int count;

      @Override
      public void write(int ignored) throws IOException {
        add(1);
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        add(length);
      }

      void add(int bytes) throws IOException {
        if ((long) count + bytes > MAX_EVIDENCE_BYTES)
          throw new IOException("Evidence exceeds budget");
        count += bytes;
      }
    }
    Counter counter = new Counter();
    try {
      Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(counter, value);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "Comparison exceeds 8 MiB of aggregate finding evidence and metadata", ex);
    }
    return counter.count;
  }

  private void reserve(long bytes) {
    evidenceBytes += bytes;
    if (evidenceBytes > MAX_EVIDENCE_BYTES)
      throw new IllegalArgumentException(
          "Comparison exceeds 8 MiB of aggregate finding evidence and metadata");
  }

  private static boolean mediaOverlap(String removed, Set<String> remaining) {
    String[] oldParts = removed.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().split("/", -1);
    if (oldParts.length != 2) return true;
    for (String other : remaining) {
      String[] newParts = other.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().split("/", -1);
      if (newParts.length != 2) return true;
      boolean typeMatches =
          oldParts[0].equals(newParts[0]) || oldParts[0].equals("*") || newParts[0].equals("*");
      boolean subtypeMatches =
          oldParts[1].equals(newParts[1]) || oldParts[1].equals("*") || newParts[1].equals("*");
      if (typeMatches && subtypeMatches) return true;
    }
    return false;
  }

  private void limit() {
    if (findings.size() > MAX_FINDINGS)
      throw new IllegalArgumentException("Comparison exceeds 10000 findings");
  }

  private static boolean equal(NodeRef a, NodeRef b) {
    return Objects.equals(a == null ? null : a.value(), b == null ? null : b.value());
  }

  private static NodeRef child(NodeRef parent, String field) {
    if (parent == null || !parent.value().has(field)) return null;
    return new NodeRef(parent.value().get(field), parent.pointer() + "/" + Json.escape(field));
  }

  private static NodeRef at(Spec spec, String pointer) {
    JsonNode value = spec.root().at(pointer);
    return value.isMissingNode() ? null : new NodeRef(value, pointer);
  }

  private static Map<String, JsonNode> fields(NodeRef ref) {
    var result = new TreeMap<String, JsonNode>();
    if (ref != null)
      ref.value().fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
    return result;
  }

  private static TreeSet<String> union(Map<String, ?> a, Map<String, ?> b) {
    var result = new TreeSet<>(a.keySet());
    result.addAll(b.keySet());
    return result;
  }

  private static boolean required(NodeRef ref) {
    if (ref == null || !ref.value().has("required")) return false;
    if (!ref.value().get("required").isBoolean())
      throw new IllegalArgumentException(ref.pointer() + "/required must be boolean");
    return ref.value().get("required").booleanValue();
  }

  private static NodeRef effective(Spec spec, Operation operation, String field) {
    NodeRef value = child(operation.node(), field);
    if (value == null && field.equals("servers")) value = child(operation.pathItem(), field);
    return value == null ? at(spec, "/" + field) : value;
  }

  private static JsonNode parameterSetting(JsonNode parameter, String field) {
    if (parameter.has(field)) return parameter.get(field);
    String style =
        parameter
            .path("style")
            .asText(
                parameter.path("in").asText().equals("query")
                        || parameter.path("in").asText().equals("cookie")
                    ? "form"
                    : "simple");
    return field.equals("style")
        ? Json.MAPPER.getNodeFactory().textNode(style)
        : Json.MAPPER.getNodeFactory().booleanNode(field.equals("explode") && style.equals("form"));
  }
}
