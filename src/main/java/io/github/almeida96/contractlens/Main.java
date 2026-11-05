package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static final String VERSION = "0.1.0";

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
        out.println(
            """
            ContractLens 0.1.0 — API migration evidence for humans and agents

            compare --baseline FILE --candidate FILE [--consumers FILE] [--out NEW_DIR] [--gate]
            mcp     --baseline FILE --candidate FILE [--consumers FILE]
            demo    --out NEW_DIR
            --version

            Inputs: local OpenAPI 3.0/3.1 JSON or YAML. No network requests.
            compare writes JSON to stdout; --out also writes HTML and Markdown.
            --gate: 0 compatible within analyzed scope, 2 breaking, 3 needs review.
            Input/execution errors always return 1. Existing output directories are refused.
            """);
        return 0;
      }
      if (args.length == 1 && args[0].equals("--version")) {
        out.println(VERSION);
        return 0;
      }
      String command = args[0];
      Set<String> allowed =
          switch (command) {
            case "compare" -> Set.of("baseline", "candidate", "consumers", "out", "gate");
            case "mcp" -> Set.of("baseline", "candidate", "consumers");
            case "demo" -> Set.of("out");
            default -> throw new IllegalArgumentException("Unknown command: " + command);
          };
      Map<String, String> options = parse(args, allowed);
      if (command.equals("demo")) {
        demo(Path.of(required(options, "out")), out);
        return 0;
      }
      Spec baseline = Spec.load(Path.of(required(options, "baseline")));
      Spec candidate = Spec.load(Path.of(required(options, "candidate")));
      List<Consumer> consumers =
          options.containsKey("consumers")
              ? consumers(Path.of(options.get("consumers")))
              : List.of();
      Report report = Analyzer.compare(baseline, candidate, consumers);
      if (command.equals("mcp")) {
        new McpServer(report).run(System.in, out);
        return 0;
      }
      if (options.containsKey("out"))
        ReportWriter.write(report, newDirectory(Path.of(options.get("out"))));
      out.print(Json.pretty(report));
      return options.containsKey("gate") ? gateCode(report.verdict()) : 0;
    } catch (IOException | IllegalArgumentException e) {
      err.println("contractlens: " + e.getMessage());
      return 1;
    }
  }

  static int gateCode(String verdict) {
    return switch (verdict) {
      case "COMPATIBLE" -> 0;
      case "BREAKING" -> 2;
      case "REVIEW" -> 3;
      default -> throw new IllegalArgumentException("Unknown verdict: " + verdict);
    };
  }

  private static Map<String, String> parse(String[] args, Set<String> allowed) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)))
        throw new IllegalArgumentException("Unknown option: " + args[i]);
      String key = args[i].substring(2);
      if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate option: --" + key);
      if (key.equals("gate")) {
        result.put(key, "true");
        continue;
      }
      if (++i == args.length || args[i].startsWith("--"))
        throw new IllegalArgumentException("Missing value for --" + key);
      result.put(key, args[i]);
    }
    return result;
  }

  private static String required(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Required option: --" + key);
    return value;
  }

  static Path newDirectory(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Output already exists: " + path);
    if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
    return Files.createDirectory(absolute);
  }

  static List<Consumer> consumers(Path path) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) > 262144)
      throw new IOException("Consumer manifest must be a file up to 256 KiB");
    JsonNode root = Json.read(Files.readString(path, StandardCharsets.UTF_8));
    if (!root.isObject()
        || root.size() != 2
        || root.path("schemaVersion").asInt(-1) != 1
        || !root.path("schemaVersion").isIntegralNumber()
        || !root.path("consumers").isArray())
      throw new IllegalArgumentException(
          "Expected consumer manifest {schemaVersion: 1, consumers: [...]}");
    List<Consumer> result = new ArrayList<>();
    Set<String> names = new HashSet<>();
    if (root.path("consumers").size() > 100)
      throw new IllegalArgumentException("At most 100 consumers are supported");
    for (JsonNode node : root.path("consumers")) {
      if (!node.isObject()
          || node.size() != 2
          || !node.path("name").isTextual()
          || !node.path("operations").isArray()
          || node.path("operations").isEmpty())
        throw new IllegalArgumentException("Each consumer needs name and nonempty operations");
      String name = node.path("name").textValue();
      if (name.isBlank()
          || name.length() > 120
          || name.chars().anyMatch(Character::isISOControl)
          || !names.add(name))
        throw new IllegalArgumentException(
            "Consumer names must be unique, printable, and 1–120 characters");
      SortedSet<String> operations = new TreeSet<>();
      for (JsonNode operation : node.path("operations")) {
        if (!operation.isTextual()
            || operation.textValue().length() > 2048
            || !operation
                .textValue()
                .matches("(GET|PUT|POST|DELETE|OPTIONS|HEAD|PATCH|TRACE) /[^\\s]*")
            || !operations.add(operation.textValue()))
          throw new IllegalArgumentException(
              "Consumer operations must be unique METHOD /path strings");
      }
      if (operations.size() > 1000)
        throw new IllegalArgumentException("At most 1000 operations per consumer");
      result.add(new Consumer(name, List.copyOf(operations)));
    }
    return result.stream().sorted(Comparator.comparing(Consumer::name)).toList();
  }

  private static void demo(Path output, PrintStream out) throws IOException {
    Path directory = newDirectory(output);
    for (String file :
        List.of(
            "baseline.json", "breaking.json", "compatible.json", "review.json", "consumers.json")) {
      try (var input = Main.class.getResourceAsStream("/orders/" + file)) {
        if (input == null) throw new IOException("Missing packaged demo: " + file);
        Files.copy(input, directory.resolve(file));
      }
    }
    Spec baseline = Spec.load(directory.resolve("baseline.json"));
    List<Consumer> consumers = consumers(directory.resolve("consumers.json"));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("demo", "synthetic-orders-contracts");
    for (String name : List.of("breaking", "compatible", "review")) {
      Report report =
          Analyzer.compare(baseline, Spec.load(directory.resolve(name + ".json")), consumers);
      String expected =
          Map.of("breaking", "BREAKING", "compatible", "COMPATIBLE", "review", "REVIEW").get(name);
      if (!report.verdict().equals(expected))
        throw new IllegalArgumentException(
            "Demo expected " + expected + ", received " + report.verdict());
      ReportWriter.write(report, Files.createDirectory(directory.resolve(name)));
      summary.put(
          name,
          Map.of(
              "verdict",
              report.verdict(),
              "gateExit",
              gateCode(report.verdict()),
              "counts",
              report.counts()));
    }
    Files.writeString(
        directory.resolve("summary.json"),
        Json.pretty(summary),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    out.print(Json.pretty(summary));
  }
}
