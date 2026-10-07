package ch.so.agi.rowvalidator.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Streaming report. I/O failures are fatal; a missing run_end means incomplete. */
public final class JsonlReport implements AutoCloseable {
  private final BufferedWriter writer;
  private final Path file;

  public JsonlReport(Path directory, int copy) throws IOException {
    Files.createDirectories(directory);
    file = Files.createTempFile(directory, "row-validator-copy-" + copy + "-", ".jsonl");
    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
  }

  public Path file() {
    return file;
  }

  public void record(String type, Map<String, ?> values) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("record_type", type);
    record.putAll(values);
    try {
      writer.write(json(record));
      writer.newLine();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write report " + file, e);
    }
  }

  public void issue(Issue issue) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("severity", issue.severity());
    data.put("phase", issue.phase());
    data.put("message", issue.message());
    data.put("object_id", issue.objectId());
    data.put("model_element", issue.modelElement());
    if (issue.row() != null) {
      data.put("copy", issue.row().copy());
      data.put("input_number", issue.row().number());
      data.put("source_row", issue.row().source());
    }
    record("issue", data);
  }

  public void flush() {
    try {
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot flush report " + file, e);
    }
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }

  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map<?, ?> map) {
      StringJoiner join = new StringJoiner(",", "{", "}");
      map.forEach((k, v) -> join.add(json(k.toString()) + ":" + json(v)));
      return join.toString();
    }
    if (value instanceof Collection<?> list) {
      StringJoiner join = new StringJoiner(",", "[", "]");
      list.forEach(v -> join.add(json(v)));
      return join.toString();
    }
    StringBuilder s = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      switch (c) {
        case '"' -> s.append("\\\"");
        case '\\' -> s.append("\\\\");
        case '\n' -> s.append("\\n");
        case '\r' -> s.append("\\r");
        case '\t' -> s.append("\\t");
        default -> {
          if (c < 32 || Character.isSurrogate(c))
            s.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else s.append(c);
        }
      }
    }
    return s.append('"').toString();
  }
}
