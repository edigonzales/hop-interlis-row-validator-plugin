package ch.so.agi.rowvalidator.transform;

import static org.junit.jupiter.api.Assertions.*;

import ch.so.agi.rowvalidator.core.*;
import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.*;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RuntimeTest {
  @TempDir Path temp;

  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  static class Harness extends RowValidator {
    final RowValidatorData state;
    final List<Object[]> out = new ArrayList<>();
    final Iterator<Object[]> rows;

    Harness(RowValidatorMeta meta, RowValidatorData data, List<Object[]> rows) {
      super(
          new TransformMeta("validate", meta),
          meta,
          data,
          0,
          null,
          new org.apache.hop.pipeline.engines.local.LocalPipelineEngine());
      this.rows = rows.iterator();
      this.state = data;
    }

    @Override
    public Object[] getRow() {
      return rows.hasNext() ? rows.next() : null;
    }

    @Override
    public void putRow(IRowMeta meta, Object[] row) {
      out.add(row);
    }
  }

  Harness harness(boolean single, List<Object[]> rows) throws Exception {
    var m = HopMappingTest.model();
    var meta = HopMappingTest.config();
    meta.setSinglePassOnly(single);
    var d = new RowValidatorData();
    d.resolved = meta;
    d.model = m;
    d.inputMeta = HopMappingTest.input();
    d.mapping = new HopMapping(m, meta, d.inputMeta);
    d.report = new JsonlReport(temp, 0);
    d.report.record("run_start", Map.of("schema_version", 1));
    d.session =
        new ValidationSession(
            m,
            ValidationSession.configuration(m, null),
            single,
            d.report::issue,
            () -> d.cancelled);
    if (!single) d.spool = new RowSpool(d.inputMeta, 1, temp, false);
    return new Harness(meta, d, rows);
  }

  @Test
  void lateUniqueReleasesNothingAndReportIsComplete() throws Exception {
    var t =
        harness(
            false, List.of(new Object[] {"a", 1L}, new Object[] {"b", 2L}, new Object[] {"a", 3L}));
    while (t.processRow()) assertTrue(t.out.isEmpty());
    assertTrue(t.getErrors() > 0);
    assertTrue(t.out.isEmpty());
    Path report;
    try (var files = Files.list(temp)) {
      report = files.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
    }
    String text = Files.readString(report);
    assertTrue(text.contains("validation_failed"));
    assertTrue(text.contains("\"output_rows\":0"));
    assertTrue(text.contains("\"release\":false"));
  }

  @Test
  void fullSuccessWaitsForEofThenPreservesOriginalRows() throws Exception {
    var rows = List.of(new Object[] {"a", 1L}, new Object[] {"b", 2L});
    var t = harness(false, rows);
    assertTrue(t.processRow());
    assertTrue(t.out.isEmpty());
    assertTrue(t.processRow());
    assertTrue(t.out.isEmpty());
    assertTrue(t.processRow());
    assertTrue(t.out.isEmpty());
    while (t.processRow()) {}
    assertEquals(2, t.out.size());
    assertArrayEquals(rows.get(0), t.out.get(0));
    assertArrayEquals(rows.get(1), t.out.get(1));
  }

  @Test
  void singlePassEmitsBeforeEofButWithholdsFirstBadRow() throws Exception {
    var t =
        harness(
            true,
            List.of(new Object[] {"a", 1L}, new Object[] {"b", 9999L}, new Object[] {"c", 2L}));
    assertTrue(t.processRow());
    assertEquals(1, t.out.size());
    assertFalse(t.processRow());
    assertEquals(1, t.out.size());
    assertTrue(t.getErrors() > 0);
  }

  @Test
  void emptyInputAndCancellation() throws Exception {
    var t = harness(false, List.of());
    while (t.processRow()) {}
    assertEquals(0, t.getErrors());
    assertTrue(t.out.isEmpty());
    var stopped = harness(false, Collections.singletonList(new Object[] {"a", 1L}));
    stopped.stopRunning();
    assertFalse(stopped.processRow());
    assertTrue(stopped.out.isEmpty());
  }

  @Test
  void rebindsLazyMetadataFromActualFirstRowset() throws Exception {
    var t = harness(false, List.of());
    var actual = HopMappingTest.input();
    actual
        .getValueMeta(0)
        .setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    actual
        .getValueMeta(0)
        .setStorageMetadata(new org.apache.hop.core.row.value.ValueMetaString("key"));
    var lazy =
        new Harness(
            t.state.resolved,
            t.state,
            Collections.singletonList(
                new Object[] {"a".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1L})) {
          @Override
          public IRowMeta getInputRowMeta() {
            return actual;
          }
        };
    while (lazy.processRow()) {}
    assertEquals(0, lazy.getErrors());
    assertEquals(1, lazy.out.size());
    assertArrayEquals(
        "a".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) lazy.out.getFirst()[0]);
  }

  @Test
  void mappingFailureContinuesFullInputButNeverReleases() throws Exception {
    var t = harness(false, List.of(new Object[] {"a", new Object()}, new Object[] {"b", 2L}));
    // No storage spill is needed here: the malformed Java value itself is not serializable.
    t.state.spool.close();
    t.state.spool = new RowSpool(t.state.inputMeta, 10, temp, false);
    while (t.processRow()) {}
    assertEquals(2, t.state.received);
    assertTrue(t.out.isEmpty());
    assertTrue(t.getErrors() > 0);
  }

  @Test
  void reportFailureAndSpoolFailurePreventReleaseAndCleanUp() throws Exception {
    var t = harness(false, Collections.singletonList(new Object[] {"a", 1L}));
    assertTrue(t.processRow());
    t.state.report.close();
    assertThrows(org.apache.hop.core.exception.HopException.class, t::processRow);
    assertTrue(t.out.isEmpty());
    assertTrue(t.getErrors() > 0);
    var other = harness(false, List.of(new Object[] {"b", 1L}, new Object[] {"c", 1L}));
    assertTrue(other.processRow());
    // Remove the spool directory after creation: the next spill must fail closed.
    Path spoolDir = Files.createDirectory(temp.resolve("spool"));
    other.state.spool.close();
    other.state.spool = new RowSpool(other.state.inputMeta, 1, spoolDir, false);
    other.state.spool.add(new Object[] {"b", 1L});
    Files.delete(spoolDir);
    assertThrows(org.apache.hop.core.exception.HopException.class, other::processRow);
    assertTrue(other.out.isEmpty());
    assertTrue(other.getErrors() > 0);
  }

  @Test
  void cancellationDuringReleaseReportsPartialOutputWithoutClaimingSuccess() throws Exception {
    var t = harness(false, List.of(new Object[] {"a", 1L}, new Object[] {"b", 2L}));
    t.processRow();
    t.processRow();
    t.processRow();
    t.processRow();
    assertEquals(1, t.out.size());
    t.stopRunning();
    assertFalse(t.processRow());
    assertEquals(1, t.out.size());
    assertTrue(t.state.terminal);
    assertTrue(Files.readString(t.state.report.file()).contains("\"status\":\"aborted\""));
    try (var paths = Files.list(temp)) {
      assertTrue(paths.noneMatch(p -> p.toString().endsWith(".spool")));
    }
  }

  @Test
  void fullModeRejectsCopiesAndPartitioning() {
    var m = HopMappingTest.config();
    var t = new TransformMeta("validate", m);
    t.setCopies(2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigurationCheck.environment(m, t, new org.apache.hop.core.variables.Variables()));
    t.setCopies(1);
    var partition = new TransformPartitioningMeta();
    partition.setMethodType(TransformPartitioningMeta.PARTITIONING_METHOD_MIRROR);
    t.setTransformPartitioningMeta(partition);
    assertThrows(
        IllegalArgumentException.class,
        () -> ConfigurationCheck.environment(m, t, new org.apache.hop.core.variables.Variables()));
    m.setSinglePassOnly(true);
    assertDoesNotThrow(
        () -> ConfigurationCheck.environment(m, t, new org.apache.hop.core.variables.Variables()));
  }
}
