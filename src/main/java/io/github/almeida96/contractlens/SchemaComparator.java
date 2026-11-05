package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Predicate;

/** A deliberately bounded comparison of the documented OpenAPI schema subset. */
public final class SchemaComparator {
  private SchemaComparator() {}

  private static final Set<String> ANNOTATIONS =
      Set.of(
          "title",
          "description",
          "default",
          "example",
          "examples",
          "deprecated",
          "externalDocs",
          "xml",
          "$comment",
          "readOnly",
          "writeOnly");
  private static final Set<String> SUPPORTED =
      Set.of(
          "type",
          "nullable",
          "enum",
          "properties",
          "required",
          "additionalProperties",
          "items",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "minLength",
          "maxLength",
          "minItems",
          "maxItems",
          "$ref",
          "$defs",
          "definitions");
  private static final Set<String> SCALAR =
      Set.of(
          "type",
          "nullable",
          "enum",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "minLength",
          "maxLength");
  // "number" here denotes non-integral numbers; JSON Schema number includes both atoms.
  private static final Set<String> ALL_TYPES =
      Set.of("null", "boolean", "string", "integer", "number", "object", "array");
  private static final ObjectNode ANY = Json.MAPPER.createObjectNode();
  private static final int MAX_DEPTH = 48;
  private static final int MAX_PAIRS = 1024;

  public static List<Finding> compare(
      Spec baseline,
      Spec candidate,
      NodeRef before,
      NodeRef after,
      String operation,
      Direction direction) {
    if (direction != Direction.REQUEST && direction != Direction.RESPONSE)
      throw new IllegalArgumentException("Schemas require REQUEST or RESPONSE direction");
    Context context = new Context(baseline, candidate, operation, direction);
    context.visit(before, after, 0);
    return List.copyOf(context.findings.values());
  }

  private record Bound(BigDecimal value, boolean exclusive) {}

  private record Pair(String before, String after) {}

  private record Property(NodeRef ref, boolean forbidden) {}

  private static final class Context {
    final Spec baseline;
    final Spec candidate;
    final String operation;
    final Direction direction;
    final LinkedHashMap<String, Finding> findings = new LinkedHashMap<>();
    final Set<Pair> active = new HashSet<>();
    final Set<Pair> done = new HashSet<>();
    int pairs;
    int uncertainty;
    boolean limitReported;

    Context(Spec baseline, Spec candidate, String operation, Direction direction) {
      this.baseline = baseline;
      this.candidate = candidate;
      this.operation = operation;
      this.direction = direction;
    }

    void add(
        String rule,
        Severity severity,
        NodeRef before,
        NodeRef after,
        String message,
        Witness witness) {
      if (severity == Severity.BREAKING && uncertainty > 0) {
        severity = Severity.REVIEW;
        witness = null;
      }
      String action =
          severity == Severity.BREAKING
              ? "Preserve the previous wire contract or introduce a versioned operation."
              : "Review this schema manually; compatibility is not established for this feature.";
      Finding finding =
          Findings.make(
              rule, severity, operation, direction, before, after, message, action, witness);
      findings.putIfAbsent(finding.id(), finding);
    }

    NodeRef resolve(Spec spec, NodeRef ref, NodeRef before, NodeRef after) {
      if (ref == null || ref.value() == null || ref.value().isMissingNode()) return null;
      return spec.resolve(ref);
    }

    void visit(NodeRef originalBefore, NodeRef originalAfter, int depth) {
      if (depth > MAX_DEPTH || ++pairs > MAX_PAIRS) {
        if (!limitReported) {
          limitReported = true;
          add(
              "schema.analysis_limit",
              Severity.REVIEW,
              originalBefore,
              originalAfter,
              "Schema comparison reached its bounded depth or node limit.",
              null);
        }
        return;
      }
      NodeRef before;
      NodeRef after;
      try {
        before = resolve(baseline, originalBefore, originalBefore, originalAfter);
        after = resolve(candidate, originalAfter, originalBefore, originalAfter);
      } catch (IllegalArgumentException ex) {
        add(
            "schema.reference",
            Severity.REVIEW,
            originalBefore,
            originalAfter,
            "Reference could not be compared: " + ex.getMessage(),
            null);
        return;
      }
      Pair pair = new Pair(pointer(before), pointer(after));
      if (done.contains(pair)) return;
      if (!active.add(pair)) {
        add(
            "schema.recursion",
            Severity.REVIEW,
            before,
            after,
            "A recursive schema pair requires manual review beyond this bounded comparison.",
            null);
        return;
      }
      boolean uncertain =
          unmodeled(value(before), baseline.is31()) || unmodeled(value(after), candidate.is31());
      if (uncertain) uncertainty++;
      try {
        JsonNode b = value(before), a = value(after);
        if (!inspect(b, baseline.is31(), before, after)
            | !inspect(a, candidate.is31(), before, after)) return;
        JsonNode source = direction == Direction.REQUEST ? b : a;
        JsonNode target = direction == Direction.REQUEST ? a : b;
        boolean source31 = direction == Direction.REQUEST ? baseline.is31() : candidate.is31();
        boolean target31 = direction == Direction.REQUEST ? candidate.is31() : baseline.is31();

        if (source.isBoolean() && !source.booleanValue()) return;
        if (target.isBoolean()) {
          if (target.booleanValue()) {
            // Still walk the source to report unsupported nested assertions.
            walkUnpaired(before, after, depth);
            return;
          }
          Witness witness = witness(b, a, ignored -> true);
          add(
              "schema.false_schema",
              witness != null || inhabited(source, source31) ? Severity.BREAKING : Severity.REVIEW,
              before,
              after,
              "The receiving schema rejects every value previously allowed in this direction.",
              witness);
          walkUnpaired(before, after, depth);
          return;
        }

        Set<String> sourceTypes = possibleTypes(source, source31),
            targetTypes = types(target, target31);
        Set<String> excluded = new TreeSet<>(sourceTypes);
        excluded.removeAll(targetTypes);
        if (!excluded.isEmpty()
            && !enumProvesNoDifference(
                source, source31, node -> !targetTypes.contains(atom(node)))) {
          Witness witness = witness(b, a, node -> excluded.contains(atom(node)));
          boolean structural =
              excluded.stream()
                  .anyMatch(
                      type ->
                          (type.equals("object") || type.equals("array"))
                              && inhabitedAs(source, source31, type));
          add(
              "schema.type",
              witness != null || structural ? Severity.BREAKING : Severity.REVIEW,
              before,
              after,
              "Values of type "
                  + String.join(", ", excluded)
                  + " are no longer accepted in the "
                  + wireDirection()
                  + " direction.",
              witness);
        }

        compareEnum(b, a, source, target, source31, before, after);
        if (sourceTypes.contains("integer") || sourceTypes.contains("number")) {
          compareNumeric(b, a, source, target, source31, target31, before, after);
        }
        if (sourceTypes.contains("string") && targetTypes.contains("string")) {
          compareLengths(b, a, source, target, "Length", "string", before, after);
        }
        if (sourceTypes.contains("object") && targetTypes.contains("object"))
          compareObject(before, after, depth);
        else scanChildren(before, after, "properties", depth);
        if (sourceTypes.contains("array") && targetTypes.contains("array")) {
          compareLengths(b, a, source, target, "Items", "array", before, after);
          if ((b.has("items") || a.has("items"))
              && count(source, "maxItems", BigDecimal.ONE).signum() > 0)
            visit(child(before, "items"), child(after, "items"), depth + 1);
          else {
            if (b.has("items")) scanOnly(child(before, "items"), baseline, false, depth + 1);
            if (a.has("items")) scanOnly(child(after, "items"), candidate, true, depth + 1);
          }
        } else {
          if (b.has("items")) scanOnly(child(before, "items"), baseline, false, depth + 1);
          if (a.has("items")) scanOnly(child(after, "items"), candidate, true, depth + 1);
        }
      } finally {
        if (uncertain) uncertainty--;
        active.remove(pair);
        done.add(pair);
      }
    }

    boolean inspect(JsonNode node, boolean is31, NodeRef before, NodeRef after) {
      if (node.isBoolean()) {
        if (!is31)
          add(
              "schema.dialect",
              Severity.REVIEW,
              before,
              after,
              "Boolean schemas require OpenAPI 3.1 and cannot be interpreted as OpenAPI 3.0"
                  + " schemas.",
              null);
        return is31;
      }
      if (!node.isObject()) {
        add(
            "schema.invalid",
            Severity.REVIEW,
            before,
            after,
            "Expected a schema object or an OpenAPI 3.1 boolean schema.",
            null);
        return false;
      }
      List<String> unsupported = new ArrayList<>();
      node.fieldNames()
          .forEachRemaining(
              key -> {
                if (!SUPPORTED.contains(key) && !ANNOTATIONS.contains(key) && !key.startsWith("x-"))
                  unsupported.add(key);
              });
      if (!unsupported.isEmpty()) {
        Collections.sort(unsupported);
        add(
            "schema.unsupported",
            Severity.REVIEW,
            before,
            after,
            "Unsupported schema keywords prevent a complete inclusion proof: "
                + String.join(", ", unsupported)
                + ".",
            null);
      }
      List<String> invalid = invalidShape(node, is31);
      if (!invalid.isEmpty()) {
        add(
            "schema.invalid",
            Severity.REVIEW,
            before,
            after,
            "Invalid or unsupported keyword values: " + String.join(", ", invalid) + ".",
            null);
        return false;
      }
      if (is31 && node.has("nullable"))
        add(
            "schema.dialect",
            Severity.REVIEW,
            before,
            after,
            "OpenAPI 3.1 nullable is not a null type; use a type union containing null.",
            null);
      if (node.has("enum") && hasStructuredEnum(node))
        add(
            "schema.enum_complex",
            Severity.REVIEW,
            before,
            after,
            "Object and array enum members require manual review.",
            null);
      if (contradictory(node, is31))
        add(
            "schema.unsatisfiable",
            Severity.REVIEW,
            before,
            after,
            "The schema contains contradictory constraints; reachability requires manual review.",
            null);
      return true;
    }

    void compareEnum(
        JsonNode b,
        JsonNode a,
        JsonNode source,
        JsonNode target,
        boolean source31,
        NodeRef before,
        NodeRef after) {
      if (!target.has("enum") || Objects.equals(source.get("enum"), target.get("enum"))) return;
      Predicate<JsonNode> violation = node -> !enumContains(target.get("enum"), node);
      if (enumProvesNoDifference(source, source31, violation)) return;
      Witness witness = witness(b, a, violation);
      add(
          "schema.enum",
          witness != null ? Severity.BREAKING : Severity.REVIEW,
          before,
          after,
          "The receiving enum may reject values allowed by the producing schema in the "
              + wireDirection()
              + " direction.",
          witness);
    }

    void compareNumeric(
        JsonNode b,
        JsonNode a,
        JsonNode source,
        JsonNode target,
        boolean source31,
        boolean target31,
        NodeRef before,
        NodeRef after) {
      Bound sourceMin = lower(source, source31), targetMin = lower(target, target31);
      Bound sourceMax = upper(source, source31), targetMax = upper(target, target31);
      boolean minTighter = tighterLower(targetMin, sourceMin);
      boolean maxTighter = tighterUpper(targetMax, sourceMax);
      if (!minTighter && !maxTighter) return;
      if (numericIntervalIncluded(source, target, source31, target31)) return;
      Predicate<JsonNode> violation =
          node -> node.isNumber() && !numericAccepts(target, target31, node.decimalValue());
      if (enumProvesNoDifference(source, source31, violation)) return;
      Witness witness = witness(b, a, violation);
      // Candidate generation includes exact endpoints and their midpoint. No unsupported assertion
      // is guessed.
      add(
          "schema.numeric_bounds",
          witness != null ? Severity.BREAKING : Severity.REVIEW,
          before,
          after,
          "Numeric bounds narrow the values accepted in the " + wireDirection() + " direction.",
          witness);
    }

    void compareLengths(
        JsonNode b,
        JsonNode a,
        JsonNode source,
        JsonNode target,
        String suffix,
        String kind,
        NodeRef before,
        NodeRef after) {
      BigDecimal sourceMin = count(source, "min" + suffix, BigDecimal.ZERO);
      BigDecimal sourceMax = count(source, "max" + suffix, null);
      BigDecimal targetMin = count(target, "min" + suffix, BigDecimal.ZERO);
      BigDecimal targetMax = count(target, "max" + suffix, null);
      boolean narrowed =
          targetMin.compareTo(sourceMin) > 0
              || (targetMax != null && (sourceMax == null || targetMax.compareTo(sourceMax) < 0));
      if (!narrowed) return;
      boolean isString = kind.equals("string");
      boolean source31 = direction == Direction.REQUEST ? baseline.is31() : candidate.is31();
      Predicate<JsonNode> violation =
          node ->
              (isString ? node.isTextual() : node.isArray())
                  && !lengthAccepts(
                      target,
                      suffix,
                      isString
                          ? node.textValue().codePointCount(0, node.textValue().length())
                          : node.size());
      if (enumProvesNoDifference(source, source31, violation)) return;
      Witness witness = isString ? witness(b, a, violation) : null;
      boolean inhabited =
          isString
              ? scalarSchema(source, source31) && !contradictory(source, source31)
              : inhabitedAs(source, source31, "array");
      add(
          "schema." + kind + "_length",
          witness != null || inhabited ? Severity.BREAKING : Severity.REVIEW,
          before,
          after,
          "The allowed " + kind + " length narrows in the " + wireDirection() + " direction.",
          witness);
    }

    void compareObject(NodeRef before, NodeRef after, int depth) {
      JsonNode b = value(before), a = value(after);
      NodeRef sourceRef = direction == Direction.REQUEST ? before : after;
      NodeRef targetRef = direction == Direction.REQUEST ? after : before;
      JsonNode source = value(sourceRef), target = value(targetRef);
      boolean[] projectionUnknown = {false};
      Map<String, NodeRef> sourceProperties =
          properties(
              sourceRef, direction == Direction.REQUEST ? baseline : candidate, projectionUnknown);
      Map<String, NodeRef> targetProperties =
          properties(
              targetRef, direction == Direction.REQUEST ? candidate : baseline, projectionUnknown);
      Set<String> sourceRequired = required(source, sourceProperties);
      Set<String> targetRequired = required(target, targetProperties);
      Set<String> newRequired = new TreeSet<>(targetRequired);
      newRequired.removeAll(sourceRequired);
      boolean source31 = direction == Direction.REQUEST ? baseline.is31() : candidate.is31();
      boolean objectUncertain = projectionUnknown[0] || !inhabitedAs(source, source31, "object");
      if (objectUncertain) uncertainty++;
      if (!newRequired.isEmpty())
        add(
            "schema.required",
            inhabitedAs(source, source31, "object") ? Severity.BREAKING : Severity.REVIEW,
            before,
            after,
            "The receiving object requires properties that the producing schema does not guarantee:"
                + " "
                + String.join(", ", newRequired)
                + ".",
            null);

      Set<String> names = new TreeSet<>(sourceProperties.keySet());
      names.addAll(targetProperties.keySet());
      for (String name : names) {
        Property from = property(sourceRef, sourceProperties, name);
        Property to = property(targetRef, targetProperties, name);
        if (from.forbidden()) {
          // Inspect a newly permitted schema too, without manufacturing a rejected value.
          if (to.ref() != null)
            scanOnly(
                to.ref(),
                direction == Direction.REQUEST ? candidate : baseline,
                direction == Direction.REQUEST,
                depth + 1);
          continue;
        }
        if (to.forbidden()) {
          Severity severity =
              inhabitedAs(source, source31, "object") && inhabited(value(from.ref()), source31)
                  ? Severity.BREAKING
                  : Severity.REVIEW;
          add(
              "schema.property_rejected",
              severity,
              direction == Direction.REQUEST ? from.ref() : to.ref(),
              direction == Direction.REQUEST ? to.ref() : from.ref(),
              "Property '"
                  + name
                  + "' can be produced but is forbidden by the receiving object's"
                  + " additionalProperties constraint.",
              null);
          scanOnly(
              from.ref(),
              direction == Direction.REQUEST ? baseline : candidate,
              direction != Direction.REQUEST,
              depth + 1);
          continue;
        }
        visit(
            direction == Direction.REQUEST ? from.ref() : to.ref(),
            direction == Direction.REQUEST ? to.ref() : from.ref(),
            depth + 1);
      }
      Property fromAdditional = additional(sourceRef), toAdditional = additional(targetRef);
      if (!fromAdditional.forbidden() && toAdditional.forbidden()) {
        add(
            "schema.additional_properties",
            inhabitedAs(source, source31, "object")
                    && inhabited(value(fromAdditional.ref()), source31)
                ? Severity.BREAKING
                : Severity.REVIEW,
            before,
            after,
            "Additional object properties may be produced but are forbidden by the receiving"
                + " schema.",
            null);
        scanOnly(
            fromAdditional.ref(),
            direction == Direction.REQUEST ? baseline : candidate,
            direction != Direction.REQUEST,
            depth + 1);
      } else if (!fromAdditional.forbidden()
          && !toAdditional.forbidden()
          && (fromAdditional.ref() != null || toAdditional.ref() != null)) {
        visit(
            direction == Direction.REQUEST ? fromAdditional.ref() : toAdditional.ref(),
            direction == Direction.REQUEST ? toAdditional.ref() : fromAdditional.ref(),
            depth + 1);
      }
      // Schemas attached to properties not visible in this direction intentionally do not constrain
      // its wire values.
      if (objectUncertain) uncertainty--;
    }

    Map<String, NodeRef> properties(NodeRef ref, Spec spec, boolean[] projectionUnknown) {
      Map<String, NodeRef> result = new TreeMap<>();
      JsonNode properties = value(ref).path("properties");
      if (!properties.isObject()) return result;
      properties
          .fieldNames()
          .forEachRemaining(
              name -> {
                NodeRef property = child(child(ref, "properties"), name);
                try {
                  NodeRef resolved = spec.resolve(property);
                  String excluded = direction == Direction.REQUEST ? "readOnly" : "writeOnly";
                  if (!value(resolved).path(excluded).asBoolean(false)) result.put(name, property);
                } catch (IllegalArgumentException ex) {
                  projectionUnknown[0] = true;
                  result.put(
                      name,
                      property); // visit emits the reference finding with the property pointer.
                }
              });
      return result;
    }

    Set<String> required(JsonNode node, Map<String, NodeRef> visible) {
      Set<String> names = new TreeSet<>();
      JsonNode list = node.path("required");
      if (list.isArray())
        list.forEach(
            name -> {
              // A required name need not have an entry in properties. Only explicitly hidden
              // entries are removed.
              if (visible.containsKey(name.asText()) || !node.path("properties").has(name.asText()))
                names.add(name.asText());
            });
      return names;
    }

    void walkUnpaired(NodeRef before, NodeRef after, int depth) {
      scanChildren(before, after, "properties", depth);
      if (value(before).has("items") || value(after).has("items"))
        visit(child(before, "items"), child(after, "items"), depth + 1);
      if (value(before).path("additionalProperties").isObject()
          || value(after).path("additionalProperties").isObject())
        visit(
            child(before, "additionalProperties"), child(after, "additionalProperties"), depth + 1);
    }

    void scanChildren(NodeRef before, NodeRef after, String keyword, int depth) {
      Set<String> names = new TreeSet<>();
      value(before).path(keyword).fieldNames().forEachRemaining(names::add);
      value(after).path(keyword).fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        NodeRef b = child(child(before, keyword), name), a = child(child(after, keyword), name);
        if (b != null) scanOnly(b, baseline, false, depth + 1);
        if (a != null) scanOnly(a, candidate, true, depth + 1);
      }
    }

    void scanOnly(NodeRef ref, Spec spec, boolean candidateSide, int depth) {
      if (ref == null) return;
      // Comparing a schema with itself exposes unsupported coverage without generating artificial
      // differences.
      Context scan = new Context(spec, spec, operation, direction);
      scan.pairs = pairs;
      scan.limitReported = limitReported;
      scan.visit(ref, ref, depth);
      pairs = scan.pairs;
      limitReported = scan.limitReported;
      for (Finding f : scan.findings.values())
        if (f.severity() == Severity.REVIEW)
          add(
              f.rule(),
              Severity.REVIEW,
              candidateSide ? null : new NodeRef(f.before(), f.beforePointer()),
              candidateSide ? new NodeRef(f.after(), f.afterPointer()) : null,
              f.message(),
              null);
    }

    Witness witness(JsonNode before, JsonNode after, Predicate<JsonNode> relevant) {
      if (!scalarSchema(before, baseline.is31()) || !scalarSchema(after, candidate.is31()))
        return null;
      JsonNode source = direction == Direction.REQUEST ? before : after;
      JsonNode target = direction == Direction.REQUEST ? after : before;
      boolean source31 = direction == Direction.REQUEST ? baseline.is31() : candidate.is31();
      boolean target31 = direction == Direction.REQUEST ? candidate.is31() : baseline.is31();
      for (JsonNode node : candidates(source, target)) {
        if (relevant.test(node)
            && acceptsScalar(source, source31, node)
            && !acceptsScalar(target, target31, node))
          return new Witness(
              node,
              direction == Direction.REQUEST ? "baseline" : "candidate",
              direction == Direction.REQUEST ? "candidate" : "baseline",
              "schema-fragment");
      }
      return null;
    }

    String wireDirection() {
      return direction == Direction.REQUEST
          ? "request (baseline → candidate)"
          : "response (candidate → baseline)";
    }
  }

  private static List<String> invalidShape(JsonNode node, boolean is31) {
    List<String> invalid = new ArrayList<>();
    if (node.has("type")) {
      JsonNode type = node.get("type");
      if (type.isArray() && is31 && !type.isEmpty()) {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : type)
          if (!item.isTextual()
              || !ALL_TYPES.contains(item.textValue())
              || !seen.add(item.textValue())) invalid.add("type");
      } else if (!type.isTextual()
          || !ALL_TYPES.contains(type.asText())
          || (!is31 && type.asText().equals("null"))) invalid.add("type");
    }
    for (String key : List.of("nullable", "readOnly", "writeOnly"))
      if (node.has(key) && !node.get(key).isBoolean()) invalid.add(key);
    if (node.path("readOnly").asBoolean() && node.path("writeOnly").asBoolean())
      invalid.add("readOnly/writeOnly");
    if (node.has("properties") && !node.get("properties").isObject()) invalid.add("properties");
    if (node.has("required")) {
      JsonNode required = node.get("required");
      if (!required.isArray()) invalid.add("required");
      else {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : required)
          if (!item.isTextual() || !seen.add(item.textValue())) invalid.add("required");
      }
    }
    if (node.has("enum") && (!node.get("enum").isArray() || node.get("enum").isEmpty()))
      invalid.add("enum");
    for (String key : List.of("minLength", "maxLength", "minItems", "maxItems"))
      if (node.has(key)) {
        JsonNode n = node.get(key);
        if (!n.isIntegralNumber() || n.bigIntegerValue().signum() < 0) invalid.add(key);
      }
    for (String key : List.of("minimum", "maximum"))
      if (node.has(key) && !safeNumber(node.get(key))) invalid.add(key);
    for (String key : List.of("exclusiveMinimum", "exclusiveMaximum"))
      if (node.has(key)) {
        if (is31 ? !safeNumber(node.get(key)) : !node.get(key).isBoolean()) invalid.add(key);
        if (!is31
            && node.path(key).asBoolean()
            && !node.has(key.equals("exclusiveMinimum") ? "minimum" : "maximum")) invalid.add(key);
      }
    for (String key : List.of("additionalProperties", "items"))
      if (node.has(key)) {
        JsonNode schema = node.get(key);
        if (!schema.isObject() && !schema.isBoolean()) invalid.add(key);
        if (key.equals("items") && !is31 && schema.isBoolean()) invalid.add(key);
      }
    return invalid.stream().distinct().toList();
  }

  private static boolean safeNumber(JsonNode node) {
    if (!node.isNumber()) return false;
    try {
      BigDecimal value = node.decimalValue();
      return Math.abs((long) value.scale()) <= 10000 && value.precision() <= 1000;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static boolean unmodeled(JsonNode schema, boolean is31) {
    if (schema.isBoolean()) return !is31;
    if (!schema.isObject() || !invalidShape(schema, is31).isEmpty()) return true;
    if (hasStructuredEnum(schema) || contradictory(schema, is31) || is31 && schema.has("nullable"))
      return true;
    Iterator<String> keys = schema.fieldNames();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!SUPPORTED.contains(key) && !ANNOTATIONS.contains(key) && !key.startsWith("x-"))
        return true;
    }
    return false;
  }

  private static Set<String> types(JsonNode node, boolean is31) {
    if (node.isBoolean()) return node.booleanValue() ? ALL_TYPES : Set.of();
    if (!node.has("type")) return ALL_TYPES;
    Set<String> result = new HashSet<>();
    JsonNode type = node.get("type");
    if (type.isArray()) type.forEach(item -> result.add(item.asText()));
    else result.add(type.asText());
    if (result.contains("number")) result.add("integer");
    if (!is31 && node.path("nullable").asBoolean(false)) result.add("null");
    return result;
  }

  private static Set<String> possibleTypes(JsonNode node, boolean is31) {
    Set<String> result = new HashSet<>(types(node, is31));
    if (node.path("enum").isArray()) {
      Set<String> members = new HashSet<>();
      node.get("enum").forEach(value -> members.add(atom(value)));
      result.retainAll(members);
    }
    return result;
  }

  private static String atom(JsonNode node) {
    if (node.isNull()) return "null";
    if (node.isTextual()) return "string";
    if (node.isBoolean()) return "boolean";
    if (node.isNumber())
      return node.decimalValue().stripTrailingZeros().scale() <= 0 ? "integer" : "number";
    return node.isArray() ? "array" : "object";
  }

  private static boolean scalarSchema(JsonNode schema, boolean is31) {
    if (schema.isBoolean()) return is31;
    if (!schema.isObject() || !invalidShape(schema, is31).isEmpty() || hasStructuredEnum(schema))
      return false;
    Iterator<String> fields = schema.fieldNames();
    while (fields.hasNext()) {
      String key = fields.next();
      if (!SCALAR.contains(key) && !ANNOTATIONS.contains(key) && !key.startsWith("x-"))
        return false;
    }
    return !(is31 && schema.has("nullable"));
  }

  private static boolean acceptsScalar(JsonNode schema, boolean is31, JsonNode node) {
    if (!node.isValueNode()) return false;
    if (schema.isBoolean()) return schema.booleanValue();
    if (!types(schema, is31).contains(atom(node))) return false;
    if (schema.has("enum") && !enumContains(schema.get("enum"), node)) return false;
    if (node.isNumber() && !numericAccepts(schema, is31, node.decimalValue())) return false;
    return !node.isTextual()
        || lengthAccepts(
            schema, "Length", node.textValue().codePointCount(0, node.textValue().length()));
  }

  private static boolean enumContains(JsonNode values, JsonNode node) {
    if (values == null || !values.isArray()) return false;
    for (JsonNode value : values) {
      if (value.isNumber() && node.isNumber()) {
        if (value.decimalValue().compareTo(node.decimalValue()) == 0) return true;
      } else if (value.equals(node)) return true;
    }
    return false;
  }

  private static boolean hasStructuredEnum(JsonNode node) {
    if (!node.path("enum").isArray()) return false;
    for (JsonNode item : node.get("enum")) if (!item.isValueNode()) return true;
    return false;
  }

  private static boolean enumProvesNoDifference(
      JsonNode source, boolean is31, Predicate<JsonNode> violation) {
    if (!source.has("enum") || !scalarSchema(source, is31)) return false;
    for (JsonNode value : source.get("enum"))
      if (acceptsScalar(source, is31, value) && violation.test(value)) return false;
    return true;
  }

  private static Bound lower(JsonNode schema, boolean is31) {
    Bound result =
        schema.has("minimum")
            ? new Bound(
                schema.get("minimum").decimalValue(),
                !is31 && schema.path("exclusiveMinimum").asBoolean())
            : null;
    if (is31 && schema.has("exclusiveMinimum")) {
      Bound exclusive = new Bound(schema.get("exclusiveMinimum").decimalValue(), true);
      if (tighterLower(exclusive, result)) result = exclusive;
    }
    return result;
  }

  private static Bound upper(JsonNode schema, boolean is31) {
    Bound result =
        schema.has("maximum")
            ? new Bound(
                schema.get("maximum").decimalValue(),
                !is31 && schema.path("exclusiveMaximum").asBoolean())
            : null;
    if (is31 && schema.has("exclusiveMaximum")) {
      Bound exclusive = new Bound(schema.get("exclusiveMaximum").decimalValue(), true);
      if (tighterUpper(exclusive, result)) result = exclusive;
    }
    return result;
  }

  private static boolean tighterLower(Bound a, Bound b) {
    return a != null
        && (b == null
            || a.value().compareTo(b.value()) > 0
            || a.value().compareTo(b.value()) == 0 && a.exclusive() && !b.exclusive());
  }

  private static boolean tighterUpper(Bound a, Bound b) {
    return a != null
        && (b == null
            || a.value().compareTo(b.value()) < 0
            || a.value().compareTo(b.value()) == 0 && a.exclusive() && !b.exclusive());
  }

  private static boolean numericAccepts(JsonNode schema, boolean is31, BigDecimal number) {
    Bound lo = lower(schema, is31), hi = upper(schema, is31);
    return (lo == null
            || number.compareTo(lo.value()) > 0
            || number.compareTo(lo.value()) == 0 && !lo.exclusive())
        && (hi == null
            || number.compareTo(hi.value()) < 0
            || number.compareTo(hi.value()) == 0 && !hi.exclusive());
  }

  private static boolean numericIntervalIncluded(
      JsonNode source, JsonNode target, boolean source31, boolean target31) {
    Bound lo = lower(source, source31), hi = upper(source, source31);
    Bound targetLo = lower(target, target31), targetHi = upper(target, target31);
    if (!possibleTypes(source, source31).contains("number")) {
      if (lo != null) {
        BigDecimal integer = lo.value().setScale(0, RoundingMode.CEILING);
        if (lo.exclusive() && integer.compareTo(lo.value()) == 0)
          integer = integer.add(BigDecimal.ONE);
        lo = new Bound(integer, false);
      }
      if (hi != null) {
        BigDecimal integer = hi.value().setScale(0, RoundingMode.FLOOR);
        if (hi.exclusive() && integer.compareTo(hi.value()) == 0)
          integer = integer.subtract(BigDecimal.ONE);
        hi = new Bound(integer, false);
      }
    }
    if (lo != null
        && hi != null
        && (lo.value().compareTo(hi.value()) > 0
            || lo.value().compareTo(hi.value()) == 0 && (lo.exclusive() || hi.exclusive())))
      return true;
    return !tighterLower(targetLo, lo) && !tighterUpper(targetHi, hi);
  }

  private static BigDecimal count(JsonNode schema, String key, BigDecimal fallback) {
    return schema.has(key) ? schema.get(key).decimalValue() : fallback;
  }

  private static boolean lengthAccepts(JsonNode schema, String suffix, int length) {
    BigDecimal n = BigDecimal.valueOf(length),
        lo = count(schema, "min" + suffix, BigDecimal.ZERO),
        hi = count(schema, "max" + suffix, null);
    return n.compareTo(lo) >= 0 && (hi == null || n.compareTo(hi) <= 0);
  }

  private static boolean contradictory(JsonNode schema, boolean is31) {
    if (!schema.isObject()) return false;
    Bound lo = lower(schema, is31), hi = upper(schema, is31);
    if (lo != null
        && hi != null
        && (lo.value().compareTo(hi.value()) > 0
            || lo.value().compareTo(hi.value()) == 0 && (lo.exclusive() || hi.exclusive())))
      return true;
    for (String suffix : List.of("Length", "Items")) {
      BigDecimal min = count(schema, "min" + suffix, BigDecimal.ZERO),
          max = count(schema, "max" + suffix, null);
      if (max != null && min.compareTo(max) > 0) return true;
    }
    if (schema.path("additionalProperties").isBoolean()
        && !schema.path("additionalProperties").booleanValue())
      for (JsonNode required : schema.path("required"))
        if (!schema.path("properties").has(required.asText())) return true;
    for (JsonNode required : schema.path("required")) {
      JsonNode property = schema.path("properties").path(required.asText());
      if (property.isBoolean() && !property.booleanValue()) return true;
    }
    if (count(schema, "minItems", BigDecimal.ZERO).signum() > 0
        && schema.path("items").isBoolean()
        && !schema.path("items").booleanValue()) return true;
    return false;
  }

  private static boolean inhabited(JsonNode schema, boolean is31) {
    if (schema.isBoolean()) return schema.booleanValue() && is31;
    if (!schema.isObject() || !invalidShape(schema, is31).isEmpty() || contradictory(schema, is31))
      return false;
    if (scalarSchema(schema, is31))
      for (JsonNode candidate : candidates(schema, ANY))
        if (acceptsScalar(schema, is31, candidate)) return true;
    return inhabitedAs(schema, is31, "object") || inhabitedAs(schema, is31, "array");
  }

  private static boolean inhabitedAs(JsonNode schema, boolean is31, String type) {
    return inhabitedAs(schema, is31, type, 0);
  }

  private static boolean inhabitedAs(JsonNode schema, boolean is31, String type, int depth) {
    if (depth > 12 || !types(schema, is31).contains(type) || schema.has("enum")) return false;
    if (schema.isBoolean()) return schema.booleanValue() && is31;
    if (!invalidShape(schema, is31).isEmpty() || contradictory(schema, is31)) return false;
    Iterator<String> keys = schema.fieldNames();
    while (keys.hasNext()) {
      String key = keys.next();
      if ((!SUPPORTED.contains(key) && !ANNOTATIONS.contains(key) && !key.startsWith("x-"))
          || key.equals("$ref")) return false;
    }
    if (type.equals("object")) {
      for (JsonNode required : schema.path("required")) {
        JsonNode property = schema.path("properties").get(required.asText());
        if (property == null) property = schema.get("additionalProperties");
        if (property != null && !simpleInhabited(property, is31, depth + 1)) return false;
      }
      return true;
    }
    if (type.equals("array"))
      return count(schema, "minItems", BigDecimal.ZERO).signum() == 0
          || simpleInhabited(
              schema.path("items").isMissingNode() ? ANY : schema.get("items"), is31, depth + 1);
    return false;
  }

  private static boolean simpleInhabited(JsonNode schema, boolean is31, int depth) {
    if (depth > 12) return false;
    if (scalarSchema(schema, is31))
      for (JsonNode value : candidates(schema, ANY))
        if (acceptsScalar(schema, is31, value)) return true;
    return inhabitedAs(schema, is31, "object", depth + 1)
        || inhabitedAs(schema, is31, "array", depth + 1);
  }

  private static List<JsonNode> candidates(JsonNode one, JsonNode two) {
    LinkedHashSet<JsonNode> values =
        new LinkedHashSet<>(
            List.of(
                NullNode.instance,
                BooleanNode.FALSE,
                BooleanNode.TRUE,
                TextNode.valueOf(""),
                TextNode.valueOf("a"),
                TextNode.valueOf("0")));
    List<BigDecimal> numbers =
        new ArrayList<>(
            List.of(
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE.negate(), new BigDecimal("0.5")));
    List<BigDecimal> endpoints = new ArrayList<>();
    for (JsonNode schema : List.of(one, two)) {
      if (schema.path("enum").isArray())
        schema
            .get("enum")
            .forEach(
                node -> {
                  if (node.isValueNode()) values.add(node);
                });
      for (String key : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
        if (schema.has(key) && safeNumber(schema.get(key)))
          endpoints.add(schema.get(key).decimalValue());
      }
      for (String key : List.of("minLength", "maxLength"))
        if (schema.has(key) && schema.get(key).canConvertToInt()) {
          int length = schema.get(key).intValue();
          for (long size : new long[] {(long) length - 1, length, (long) length + 1})
            if (size >= 0 && size <= 4096) values.add(TextNode.valueOf("a".repeat((int) size)));
        }
    }
    for (BigDecimal point : endpoints) {
      numbers.add(point);
      numbers.add(point.add(BigDecimal.ONE));
      numbers.add(point.subtract(BigDecimal.ONE));
      numbers.add(point.setScale(0, RoundingMode.FLOOR));
      numbers.add(point.setScale(0, RoundingMode.CEILING));
      BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(-Math.max(1, point.scale() + 1));
      numbers.add(point.add(step));
      numbers.add(point.subtract(step));
    }
    for (int i = 0; i < endpoints.size(); i++)
      for (int j = i + 1; j < endpoints.size(); j++)
        numbers.add(endpoints.get(i).add(endpoints.get(j)).divide(BigDecimal.valueOf(2)));
    for (BigDecimal number : numbers) values.add(DecimalNode.valueOf(number));
    return List.copyOf(values);
  }

  private static Property property(NodeRef parent, Map<String, NodeRef> properties, String name) {
    return properties.containsKey(name)
        ? new Property(properties.get(name), false)
        : additional(parent);
  }

  private static Property additional(NodeRef parent) {
    JsonNode additional = value(parent).get("additionalProperties");
    if (additional == null || additional.isBoolean() && additional.booleanValue())
      return new Property(null, false);
    if (additional.isBoolean()) return new Property(child(parent, "additionalProperties"), true);
    return new Property(child(parent, "additionalProperties"), false);
  }

  private static NodeRef child(NodeRef parent, String key) {
    if (parent == null || parent.value() == null || !parent.value().has(key)) return null;
    return new NodeRef(parent.value().get(key), parent.pointer() + "/" + Json.escape(key));
  }

  private static JsonNode value(NodeRef ref) {
    return ref == null || ref.value() == null || ref.value().isMissingNode() ? ANY : ref.value();
  }

  private static String pointer(NodeRef ref) {
    return ref == null ? "<unconstrained>" : ref.pointer();
  }
}
