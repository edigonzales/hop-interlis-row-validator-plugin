package ch.so.agi.rowvalidator.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.*;
import org.xml.sax.InputSource;

class MetadataTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void xmlAndClonePreserveSettingsAndMappings() throws Exception {
    var m = HopMappingTest.config();
    m.setModelNames("Rows");
    m.setClassName("Rows.Data.Row");
    m.setTimeZone("Europe/Zurich");
    m.setSinglePassOnly(true);
    m.setCompress(true);
    m.setCacheRows(7);
    m.setReportDirectory("${REPORTS}");
    String xml = new TransformMeta("validate", m).getXml();
    var n =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(xml)))
            .getDocumentElement();
    var restored = new RowValidatorMeta();
    restored.loadXml(n, new MemoryMetadataProvider());
    assertEquals(m.getTimeZone(), restored.getTimeZone());
    assertEquals(2, restored.getMappings().size());
    assertEquals("key", restored.getMappings().getFirst().getAttribute());
    assertTrue(restored.isSinglePassOnly());
    assertTrue(restored.isCompress());
    assertEquals(7, restored.getCacheRows());
    assertEquals("${REPORTS}", restored.getReportDirectory());
    var copy = m.clone();
    copy.getMappings().getFirst().setField("other");
    assertEquals("key", m.getMappings().getFirst().getField());
  }

  @Test
  void matchingNeverGuesses() {
    assertEquals(List.of("foo"), RowValidatorDialog.matching("foo", new String[] {"foo", "FOO"}));
    assertEquals(
        List.of("FOO", "Foo"), RowValidatorDialog.matching("foo", new String[] {"FOO", "Foo"}));
  }

  @Test
  void unknownVariablesAreUncheckedButDefiniteErrorsRemainErrors() {
    var meta = new RowValidatorMeta();
    meta.setModelNames("${LATER}");
    var transform = new TransformMeta("validate", meta);
    var remarks = new ArrayList<org.apache.hop.core.ICheckResult>();
    var variables = new org.apache.hop.core.variables.Variables();
    meta.check(
        remarks,
        new org.apache.hop.pipeline.PipelineMeta(),
        transform,
        null,
        new String[] {"Input"},
        new String[0],
        null,
        variables,
        new MemoryMetadataProvider());
    assertEquals(
        org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING, remarks.getFirst().getType());
    remarks.clear();
    meta.setModelNames("");
    meta.check(
        remarks,
        new org.apache.hop.pipeline.PipelineMeta(),
        transform,
        null,
        new String[] {"Input"},
        new String[0],
        null,
        variables,
        new MemoryMetadataProvider());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.getFirst().getType());
  }
}
