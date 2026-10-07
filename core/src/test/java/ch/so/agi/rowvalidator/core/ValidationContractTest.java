package ch.so.agi.rowvalidator.core;

import static org.junit.jupiter.api.Assertions.*;

import ch.ehi.basics.settings.Settings;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iox.IoxLogEvent;
import ch.interlis.iox_j.*;
import ch.interlis.iox_j.logging.LogEventFactory;
import ch.interlis.iox_j.validator.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ValidationContractTest {
  @Test
  void sessionMatchesDirectNativeValidatorForTemporalLexemes() throws Exception {
    var model = CoreTest.model("Times24");
    for (boolean single : List.of(false, true)) {
      for (String date : List.of("2024-02-29", "2024-02-30", "1900-01-01", "2024-02-29Z", "")) {
        var object = new Iom_jObject(model.className(), "1");
        object.setattrvalue("day", date);
        object.setattrvalue("clock", "23:59:59.123");
        object.setattrvalue("moment", "2024-02-29T12:00:00.123");
        var direct = new ArrayList<String>();
        var factory = new LogEventFactory();
        ch.interlis.iox.IoxLogging logger =
            event -> {
              if (event.getEventKind() == IoxLogEvent.ERROR) direct.add(event.getEventMsg());
            };
        factory.setLogger(logger);
        var settings = new Settings();
        if (single)
          settings.setValue(Validator.CONFIG_DO_SINGLE_PASS, Validator.CONFIG_DO_SINGLE_PASS_DO);
        var nativeValidator =
            new Validator(
                model.model(),
                ValidationSession.configuration(model, null),
                logger,
                factory,
                new PipelinePool(),
                settings);
        try {
          nativeValidator.setAutoSecondPass(false);
          nativeValidator.validate(new StartTransferEvent("test"));
          nativeValidator.validate(new StartBasketEvent(model.topicName(), model.basketId()));
          nativeValidator.validate(new ObjectEvent(object));
          nativeValidator.validate(new EndBasketEvent());
          nativeValidator.validate(new EndTransferEvent());
          if (!single) nativeValidator.doSecondPass();
        } finally {
          nativeValidator.close();
        }
        var actual = new ArrayList<String>();
        try (var session =
            new ValidationSession(
                model,
                ValidationSession.configuration(model, null),
                single,
                i -> {
                  if (i.severity().equals("ERROR")) actual.add(i.message());
                },
                () -> false)) {
          session.accept(object, new Issue.RowReference(0, 1, "source"));
          session.finish();
        }
        assertEquals(direct, actual, "lexeme=" + date + ", single=" + single);
      }
    }
  }

  @Test
  void rejectsUnsupportedTransferContentAndRequiresExplicitNumericOid() throws Exception {
    var td =
        ModelService.compile(
            "Unsupported", Path.of("src/test/resources/models").toAbsolutePath().toString());
    for (String name : List.of("Nested", "Place", "LeftSide", "RightSide")) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> ModelDescriptor.of(td, "Unsupported.Data." + name));
      assertTrue(ex.getMessage().contains("unsupported"));
    }
    var model = ModelDescriptor.of(td, "Unsupported.Data.NumericId");
    assertThrows(IllegalArgumentException.class, () -> model.checkAutomaticOid(true));
    assertDoesNotThrow(() -> model.checkAutomaticOid(false));
  }

  @Test
  void secondPassCancellationNeverCompletesValidation() throws Exception {
    var model = CoreTest.model("Rows");
    var stop = new AtomicBoolean();
    try (var session =
        new ValidationSession(
            model,
            ValidationSession.configuration(model, null),
            false,
            i -> {
              if (i.phase().equals("second_pass")) stop.set(true);
            },
            stop::get)) {
      for (int n = 0; n < 10; n++)
        session.accept(CoreTest.row("" + n, "k" + n, "1", "2"), new Issue.RowReference(0, n, null));
      assertThrows(CancellationException.class, session::finish);
      assertFalse(session.finished());
    }
  }

  @Test
  void independentConcurrentSessionsKeepTheirSourceReferences() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<Future<List<Issue>>>();
      for (int copy = 0; copy < 2; copy++) {
        int c = copy;
        tasks.add(
            executor.submit(
                () -> {
                  var model = CoreTest.model("Rows");
                  var issues = new ArrayList<Issue>();
                  try (var session =
                      new ValidationSession(
                          model,
                          ValidationSession.configuration(model, null),
                          true,
                          issues::add,
                          () -> false)) {
                    var row = CoreTest.row("1", "k", "1", "2");
                    row.setattrvalue("amount", "9999");
                    session.accept(row, new Issue.RowReference(c, 1, "source-" + c));
                    assertFalse(session.finish());
                  }
                  return issues;
                }));
      }
      for (int copy = 0; copy < 2; copy++) {
        var errors =
            tasks.get(copy).get().stream().filter(i -> i.severity().equals("ERROR")).toList();
        assertFalse(errors.isEmpty());
        for (var error : errors) {
          assertNotNull(error.row());
          assertEquals(copy, error.row().copy());
          assertEquals("source-" + copy, error.row().source());
        }
      }
    }
  }

  @Test
  void duplicateObjectIdMakesSecondPassSourceAmbiguous() throws Exception {
    var model = CoreTest.model("Rows");
    var issues = new ArrayList<Issue>();
    try (var session =
        new ValidationSession(
            model, ValidationSession.configuration(model, null), false, issues::add, () -> false)) {
      session.accept(CoreTest.row("1", "a", "9", "2"), new Issue.RowReference(0, 1, "first"));
      session.accept(CoreTest.row("1", "b", "9", "2"), new Issue.RowReference(0, 2, "second"));
      session.finish();
      assertTrue(session.errors() > 0);
    }
    for (var issue : issues)
      if (issue.phase().equals("second_pass") && "1".equals(issue.objectId()))
        assertNull(issue.row());
  }

  @Test
  void warningConfigurationAllowsSuccessfulCompletion() throws Exception {
    var model = CoreTest.model("Rows");
    var config = ValidationSession.configuration(model, null);
    config.setConfigValue("Rows.Data.Row.amount", ValidationConfig.TYPE, ValidationConfig.WARNING);
    try (var session = new ValidationSession(model, config, false, i -> {}, () -> false)) {
      var row = CoreTest.row("1", "a", "1", "2");
      row.setattrvalue("amount", "9999");
      session.accept(row, new Issue.RowReference(0, 1, null));
      assertTrue(session.finish());
      assertEquals(0, session.errors());
      assertTrue(session.warnings() > 0);
    }
  }
}
