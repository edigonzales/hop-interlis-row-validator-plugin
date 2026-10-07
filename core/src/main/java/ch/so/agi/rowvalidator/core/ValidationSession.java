package ch.so.agi.rowvalidator.core;

import ch.ehi.basics.settings.Settings;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import ch.interlis.iox_j.*;
import ch.interlis.iox_j.logging.LogEventFactory;
import ch.interlis.iox_j.validator.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** A single, finite validation unit. Never shared between transform copies. */
public final class ValidationSession implements AutoCloseable {
  private final Validator validator;
  private final boolean singlePass;
  private final BooleanSupplier cancelled;
  private final Consumer<Issue> sink;
  private final Map<String, Issue.RowReference> rows = new HashMap<>();
  private final Map<String, Map<String, String>> effectiveConfig;
  private Issue.RowReference current;
  private String currentId;
  private String phase = "initialization";
  private long errors, warnings;
  private boolean finished, closed;

  public static ValidationConfig configuration(ModelDescriptor model, Path file) throws Exception {
    var config = new ValidationConfig();
    config.mergeIliMetaAttrs(model.model());
    if (file != null) config.mergeConfigFile(file.toFile());
    for (String name : config.getIliQnames())
      for (String key : config.getConfigParams(name)) {
        if (key.toLowerCase(Locale.ROOT).contains("singlepass"))
          throw new IllegalArgumentException(
              "Select Single Pass in the transform, not in validation configuration: " + key);
      }
    return config;
  }

  public ValidationSession(
      ModelDescriptor model,
      ValidationConfig config,
      boolean singlePass,
      Consumer<Issue> sink,
      BooleanSupplier cancelled)
      throws Exception {
    this.singlePass = singlePass;
    this.cancelled = cancelled;
    this.sink = sink;
    effectiveConfig = new TreeMap<>();
    for (String name : config.getIliQnames()) {
      Map<String, String> params = new TreeMap<>();
      for (String key : config.getConfigParams(name))
        params.put(key, config.getConfigValue(name, key));
      effectiveConfig.put(name, params);
    }
    var factory = new SessionLogger();
    factory.setLogger(factory);
    Settings settings = new Settings();
    if (singlePass)
      settings.setValue(Validator.CONFIG_DO_SINGLE_PASS, Validator.CONFIG_DO_SINGLE_PASS_DO);
    validator =
        new Validator(model.model(), config, factory, factory, new PipelinePool(), settings);
    validator.setAutoSecondPass(false);
    try {
      checkCancelled();
      validator.validate(new ch.interlis.iox_j.StartTransferEvent("Hop Row Stream Validator"));
      validator.validate(
          new ch.interlis.iox_j.StartBasketEvent(model.topicName(), model.basketId()));
      phase = "input";
    } catch (Exception ex) {
      validator.close();
      throw ex;
    }
  }

  public Map<String, Map<String, String>> effectiveConfiguration() {
    return Collections.unmodifiableMap(effectiveConfig);
  }

  public long errors() {
    return errors;
  }

  public long warnings() {
    return warnings;
  }

  public boolean finished() {
    return finished;
  }

  public void accept(IomObject object, Issue.RowReference row) throws IoxException {
    if (finished || closed) throw new IllegalStateException("Validation already ended");
    checkCancelled();
    current = row;
    currentId = object.getobjectoid();
    if (!singlePass) {
      // A null entry deliberately means ambiguous: never attribute a later issue arbitrarily.
      if (rows.containsKey(currentId)) rows.put(currentId, null);
      else rows.put(currentId, row);
    }
    try {
      validator.validate(new ch.interlis.iox_j.ObjectEvent(object));
    } finally {
      current = null;
      currentId = null;
    }
    checkCancelled();
  }

  public void mappingError(String message, String oid, Issue.RowReference row, String attribute) {
    emit(new Issue("ERROR", "mapping", message, oid, row, attribute));
  }

  public boolean finish() throws IoxException {
    if (finished || closed) throw new IllegalStateException("Validation already ended");
    checkCancelled();
    phase = "end_transfer";
    validator.validate(new ch.interlis.iox_j.EndBasketEvent());
    validator.validate(new ch.interlis.iox_j.EndTransferEvent());
    checkCancelled();
    if (!singlePass) {
      phase = "second_pass";
      validator.doSecondPass();
      checkCancelled();
    }
    finished = true;
    return errors == 0;
  }

  private void emit(Issue issue) {
    if ("ERROR".equals(issue.severity())) errors++;
    if ("WARNING".equals(issue.severity())) warnings++;
    sink.accept(issue);
  }

  private void checkCancelled() {
    if (cancelled.getAsBoolean()) throw new CancellationException("Validation cancelled");
  }

  private final class SessionLogger extends LogEventFactory implements IoxLogging {
    @Override
    public void setDataObj(IomObject object) {
      checkCancelled();
      super.setDataObj(object);
    }

    @Override
    public void addEvent(IoxLogEvent event) {
      checkCancelled();
      String id = event.getSourceObjectXtfId();
      if (id == null) id = event.getSourceObjectTechId();
      Issue.RowReference row =
          id != null && Objects.equals(id, currentId) && current != null ? current : rows.get(id);
      String severity =
          switch (event.getEventKind()) {
            case IoxLogEvent.ERROR -> "ERROR";
            case IoxLogEvent.WARNING -> "WARNING";
            default -> "INFO";
          };
      emit(new Issue(severity, phase, event.getEventMsg(), id, row, event.getModelEleQName()));
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      validator.close();
      rows.clear();
    }
  }
}
