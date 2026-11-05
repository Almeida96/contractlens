package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;

/** Offline, bounded reader for a documented structural subset of OpenAPI. */
public final class Spec {
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  public static final int MAX_OPERATIONS = 1000;
  static final Set<String> METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
  private static final ObjectMapper YAML;

  static {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(80);
    options.setCodePointLimit(MAX_BYTES);
    var factory =
        YAMLFactory.builder()
            .loaderOptions(options)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    factory.setStreamReadConstraints(
        StreamReadConstraints.builder()
            .maxNestingDepth(80)
            .maxStringLength(262144)
            .maxNumberLength(1000)
            .build());
    YAML = new ObjectMapper(factory).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  private final JsonNode root;
  private final Source source;

  private Spec(String name, JsonNode root, String hash) {
    validate(root);
    this.root = root.deepCopy();
    this.source = new Source(name, hash, root.path("openapi").textValue());
  }

  public static Spec load(Path path) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Specification exceeds 2 MiB: " + path);
    if (bytes.length == 0) throw new IllegalArgumentException("Specification is empty: " + path);
    // JSON is a YAML subset. Selecting JSON by its leading token retains strict JSON syntax.
    String text = new String(bytes, StandardCharsets.UTF_8);
    String leading = text.stripLeading();
    ObjectMapper mapper = leading.startsWith("{") || leading.startsWith("[") ? Json.MAPPER : YAML;
    if (mapper == YAML) {
      // Jackson exposes aliases as scalar tokens instead of expanding them. Reject them
      // explicitly so a contract never changes meaning during parsing.
      try (YAMLParser tokens = (YAMLParser) YAML.createParser(bytes)) {
        while (tokens.nextToken() != null) {
          if (tokens.isCurrentAlias())
            throw new IllegalArgumentException(
                "YAML aliases are unsupported; inline the referenced value");
          String tag = tokens.getTypeId();
          if (tag != null && !tag.startsWith("tag:yaml.org,2002:"))
            throw new IllegalArgumentException("Custom YAML tags are unsupported");
        }
      }
    }
    JsonNode root;
    try (var parser = mapper.createParser(bytes)) {
      root = mapper.readTree(parser);
      if (root == null || parser.nextToken() != null)
        throw new IllegalArgumentException("Expected exactly one specification document");
    }
    return new Spec(path.getFileName().toString(), root, Json.hash(bytes));
  }

  public static Spec of(String name, JsonNode root) {
    if (root == null) throw new IllegalArgumentException("Specification root is missing");
    validate(root);
    String encoded = Json.compact(Findings.canonical(root));
    if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Specification exceeds 2 MiB");
    return new Spec(name, root, Json.hash(encoded));
  }

  public JsonNode root() {
    return root;
  }

  public Source source() {
    return source;
  }

  public boolean is31() {
    return source.openapi().startsWith("3.1.");
  }

  public NodeRef resolve(NodeRef input) {
    if (input == null) return null;
    NodeRef current = input;
    var seen = new HashSet<String>();
    for (int depth = 0; depth <= 80; depth++) {
      JsonNode value = current.value();
      if (value == null || !value.isObject() || !value.has("$ref")) return current;
      var siblings = new TreeSet<String>();
      value
          .fieldNames()
          .forEachRemaining(
              key -> {
                if (!Set.of("$ref", "description", "summary").contains(key)) siblings.add(key);
              });
      if (!siblings.isEmpty())
        throw new IllegalArgumentException(
            "Unsupported $ref siblings at "
                + current.pointer()
                + ": "
                + String.join(", ", siblings));
      JsonNode reference = value.get("$ref");
      if (!reference.isTextual())
        throw new IllegalArgumentException("$ref must be a string at " + current.pointer());
      String ref = reference.textValue();
      if (!ref.startsWith("#/"))
        throw new IllegalArgumentException(
            "Only same-document JSON Pointer $ref is supported: " + ref);
      // URI-fragment percent decoding is deliberately unsupported rather than resolved incorrectly.
      if (ref.contains("%"))
        throw new IllegalArgumentException(
            "Percent-encoded $ref fragments are unsupported: " + ref);
      String pointer = ref.substring(1);
      if (!seen.add(pointer)) throw new IllegalArgumentException("Cyclic $ref chain at " + ref);
      for (String token : pointer.substring(1).split("/", -1)) {
        for (int i = 0; i < token.length(); i++) {
          if (token.charAt(i) == '~'
              && (i + 1 == token.length() || "01".indexOf(token.charAt(++i)) < 0))
            throw new IllegalArgumentException("Invalid JSON Pointer escape in " + ref);
        }
      }
      JsonNode target = root.at(pointer);
      if (target.isMissingNode()) throw new IllegalArgumentException("Unresolved $ref: " + ref);
      current = new NodeRef(target, pointer);
    }
    throw new IllegalArgumentException("$ref chain exceeds depth limit");
  }

  private static void validate(JsonNode root) {
    object(root, "Specification");
    String version = root.path("openapi").asText("");
    if (!version.matches("3\\.[01]\\.\\d+"))
      throw new IllegalArgumentException("Only OpenAPI 3.0.x and 3.1.x are supported");
    object(root.get("info"), "info");
    text(root.path("info").get("title"), "info.title");
    text(root.path("info").get("version"), "info.version");
    object(root.get("paths"), "paths");
    if (root.has("components")) object(root.get("components"), "components");
    if (root.has("servers") && !root.get("servers").isArray())
      throw new IllegalArgumentException("servers must be an array");
    if (root.has("security") && !root.get("security").isArray())
      throw new IllegalArgumentException("security must be an array");
    int operations = 0;
    var paths = root.path("paths").fields();
    while (paths.hasNext()) {
      var path = paths.next();
      if (path.getKey().startsWith("x-")) continue;
      if (!path.getKey().startsWith("/"))
        throw new IllegalArgumentException("Path keys must begin with '/': " + path.getKey());
      object(path.getValue(), "Path item " + path.getKey());
      for (String method : METHODS) {
        if (path.getValue().has(method)) {
          object(path.getValue().get(method), method.toUpperCase() + " " + path.getKey());
          if (++operations > MAX_OPERATIONS)
            throw new IllegalArgumentException("Specification exceeds 1000 operations");
        }
      }
    }
    checkTree(root, 0);
  }

  private static void checkTree(JsonNode node, int depth) {
    if (depth > 80) throw new IllegalArgumentException("Specification nesting exceeds 80 levels");
    if (node.isNumber()) {
      try {
        var number = node.decimalValue();
        if (number.precision() > 1000 || Math.abs((long) number.scale()) > 1000)
          throw new IllegalArgumentException(
              "Specification number precision or exponent exceeds supported limits");
      } catch (ArithmeticException | NumberFormatException ex) {
        throw new IllegalArgumentException(
            "Specification contains a non-finite or unsupported number", ex);
      }
    }
    if (node.isTextual() && node.textValue().length() > 262144)
      throw new IllegalArgumentException("Specification scalar exceeds length limit");
    if (node.isContainerNode()) node.forEach(child -> checkTree(child, depth + 1));
  }

  static void object(JsonNode node, String location) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException(location + " must be an object");
  }

  static void text(JsonNode node, String location) {
    if (node == null || !node.isTextual() || node.textValue().isEmpty())
      throw new IllegalArgumentException(location + " must be a nonempty string");
  }
}
