package ch.so.agi.rowvalidator.core;

public record Issue(
    String severity,
    String phase,
    String message,
    String objectId,
    RowReference row,
    String modelElement) {
  public record RowReference(int copy, long number, String source) {}
}
