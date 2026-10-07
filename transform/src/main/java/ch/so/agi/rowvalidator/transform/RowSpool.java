package ch.so.agi.rowvalidator.transform;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.hop.core.row.IRowMeta;

/** Ordered, bounded row cache with counted disk blocks. Owned by the processing thread. */
public final class RowSpool implements AutoCloseable {
  private static final int MAGIC = 0x52535631;
  private final IRowMeta meta;
  private final int capacity;
  private final Path directory;
  private final boolean compress;
  private final List<Object[]> memory = new ArrayList<>();
  private final List<Path> files = new ArrayList<>();
  private DataInputStream reader;
  private int blockIndex, remaining, memoryIndex;
  private boolean sealed, closed;

  public RowSpool(IRowMeta meta, int capacity, Path directory, boolean compress)
      throws IOException {
    if (capacity < 1) throw new IllegalArgumentException("Row cache must be at least 1");
    this.meta = meta.clone();
    this.capacity = capacity;
    this.directory = directory;
    this.compress = compress;
    if (directory != null && (!Files.isDirectory(directory) || !Files.isWritable(directory)))
      throw new IOException("Temporary directory is not writable: " + directory);
  }

  public void add(Object[] row) throws Exception {
    if (sealed || closed) throw new IllegalStateException("Spool no longer accepts rows");
    if (memory.size() == capacity) spill();
    Object[] copy = meta.cloneRow(row);
    // Hop's lazy-storage clone returns the same byte array. Preserve ownership explicitly.
    for (int i = 0; i < copy.length; i++)
      if (copy[i] instanceof byte[] bytes) copy[i] = bytes.clone();
    memory.add(copy);
  }

  private void spill() throws Exception {
    if (memory.isEmpty()) return;
    Path file =
        directory == null
            ? Files.createTempFile("hop-row-validator-", ".spool")
            : Files.createTempFile(directory, "hop-row-validator-", ".spool");
    files.add(file); // Track before opening, including failed writes.
    try (OutputStream raw = Files.newOutputStream(file);
        DataOutputStream out =
            new DataOutputStream(
                new BufferedOutputStream(compress ? new GZIPOutputStream(raw) : raw))) {
      out.writeInt(MAGIC);
      out.writeInt(memory.size());
      for (Object[] row : memory) meta.writeData(out, row);
      out.writeInt(MAGIC);
    }
    memory.clear();
  }

  public void seal() throws Exception {
    if (sealed) throw new IllegalStateException("Spool already sealed");
    if (!files.isEmpty()) spill();
    sealed = true;
  }

  public Object[] next() throws Exception {
    if (!sealed || closed) throw new IllegalStateException("Spool not readable");
    if (files.isEmpty()) return memoryIndex < memory.size() ? memory.get(memoryIndex++) : null;
    if (reader != null && remaining == 0) {
      if (reader.readInt() != MAGIC || reader.read() != -1)
        throw new IOException("Invalid spool block trailer");
      reader.close();
      reader = null;
    }
    if (reader == null) {
      if (blockIndex == files.size()) return null;
      InputStream raw = Files.newInputStream(files.get(blockIndex++));
      try {
        reader =
            new DataInputStream(new BufferedInputStream(compress ? new GZIPInputStream(raw) : raw));
      } catch (Exception e) {
        raw.close();
        throw e;
      }
      if (reader.readInt() != MAGIC) throw new IOException("Invalid spool header");
      remaining = reader.readInt();
      if (remaining < 1 || remaining > capacity)
        throw new IOException("Invalid spool row count: " + remaining);
    }
    Object[] row = meta.readData(reader); // EOF is a failure, never normal stream completion.
    remaining--;
    return row;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException problem = null;
    if (reader != null)
      try {
        reader.close();
      } catch (IOException ex) {
        problem = ex;
      }
    for (Path path : files)
      try {
        Files.deleteIfExists(path);
      } catch (IOException ex) {
        if (problem == null) problem = ex;
        else problem.addSuppressed(ex);
      }
    memory.clear();
    if (problem != null) throw problem;
  }
}
