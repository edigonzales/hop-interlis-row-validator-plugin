package ch.so.agi.rowvalidator.transform;

import ch.so.agi.rowvalidator.core.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;

public class RowValidator extends BaseTransform<RowValidatorMeta, RowValidatorData> {
  public RowValidator(
      TransformMeta transformMeta,
      RowValidatorMeta meta,
      RowValidatorData data,
      int copyNr,
      PipelineMeta pipelineMeta,
      Pipeline pipeline) {
    super(transformMeta, meta, data, copyNr, pipelineMeta, pipeline);
  }

  @Override
  public boolean init() {
    if (!super.init()) return false;
    try {
      data.resolved = ConfigurationCheck.resolve(meta, this);
      ConfigurationCheck.environment(data.resolved, getTransformMeta(), this);
      if (getPipelineMeta().findPreviousTransforms(getTransformMeta()).isEmpty())
        throw new IllegalArgumentException("An incoming row stream is required");
      data.inputMeta = getPipelineMeta().getPrevTransformFields(this, getTransformMeta());
      ModelDescriptor model = ConfigurationCheck.model(data.resolved);
      data.model = model;
      data.mapping = new HopMapping(model, data.resolved, data.inputMeta);
      var config = ValidationSession.configuration(model, path(data.resolved.getConfigFile()));
      if (!data.resolved.getReportDirectory().isBlank()) {
        data.report = new JsonlReport(path(data.resolved.getReportDirectory()), getCopy());
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("schema_version", 1);
        start.put("run_id", UUID.randomUUID().toString());
        start.put("copy", getCopy());
        start.put("models", data.resolved.getModelNames());
        start.put("model_sources", data.resolved.getModelSources());
        start.put("class", model.className());
        start.put("mode", data.resolved.isSinglePassOnly() ? "single_pass" : "full");
        start.put("time_zone", data.resolved.getTimeZone());
        start.put("config_file", data.resolved.getConfigFile());
        Map<String, Object> effective = new TreeMap<>();
        for (String name : config.getIliQnames()) {
          Map<String, String> params = new TreeMap<>();
          for (String key : config.getConfigParams(name))
            params.put(key, config.getConfigValue(name, key));
          effective.put(name, params);
        }
        start.put("effective_configuration", effective);
        data.report.record("run_start", start);
        data.report.flush();
        logBasic("Validation report: " + data.report.file());
      }
      data.session =
          new ValidationSession(
              model, config, data.resolved.isSinglePassOnly(), this::issue, this::cancelled);
      if (data.session.errors() > 0)
        throw new IllegalArgumentException("Validator initialization reported errors");
      if (!data.resolved.isSinglePassOnly())
        data.spool =
            new RowSpool(
                data.inputMeta,
                data.resolved.getCacheRows(),
                path(data.resolved.getTempDirectory()),
                data.resolved.isCompress());
      logBasic(
          data.resolved.isSinglePassOnly()
              ? "Single Pass: reading input (limited validation scope)"
              : "Full validation: reading and retaining input");
      return true;
    } catch (Exception ex) {
      logError("Cannot initialize Row Stream Validator", ex);
      setErrors(1);
      terminate("technical_error", ex);
      return false;
    }
  }

  private static Path path(String value) {
    return value == null || value.isBlank() ? null : Path.of(value);
  }

  private boolean cancelled() {
    return data.cancelled || isStopped() || Thread.currentThread().isInterrupted();
  }

  private void checkCancelled() {
    if (cancelled()) throw new CancellationException("Pipeline stopped");
  }

  private void issue(Issue issue) {
    if (data.report != null) data.report.issue(issue);
    String message =
        issue.phase()
            + ": "
            + issue.message()
            + (issue.row() == null
                ? ""
                : " [copy "
                    + issue.row().copy()
                    + ", input "
                    + issue.row().number()
                    + ", source "
                    + issue.row().source()
                    + "]");
    if ("ERROR".equals(issue.severity())) logError(message);
    else if ("WARNING".equals(issue.severity())) logBasic("WARNING: " + message);
    else logDetailed(message);
  }

  @Override
  public boolean processRow() throws HopException {
    if (data.terminal) return false;
    try {
      checkCancelled();
      if (data.releasing) {
        Object[] row = data.spool.next();
        if (row == null) {
          complete();
          return false;
        }
        checkCancelled();
        putRow(data.inputMeta, row);
        data.emitted++;
        return true;
      }
      Object[] row = getRow();
      checkCancelled(); // getRow() also returns null on stop; that is not regular EOF.
      if (row == null) {
        logBasic(
            data.resolved.isSinglePassOnly()
                ? "Completing Single Pass"
                : "Running second validation pass");
        boolean valid = data.session.finish();
        checkCancelled();
        validationEnd(true, valid);
        if (!valid) {
          failValidation();
          return false;
        }
        if (data.spool != null) {
          data.spool.seal();
          data.releasing = true;
          logBasic("Validation completed; releasing retained rows");
          return true;
        }
        complete();
        return false;
      }
      if (data.received == 0 && getInputRowMeta() != null) {
        // Storage representation is supplied by the first rowset, not necessarily by
        // design-time getFields(). Bind before retaining or decoding any actual row.
        data.inputMeta = getInputRowMeta().clone();
        data.mapping = new HopMapping(data.model, data.resolved, data.inputMeta);
        if (data.spool != null) {
          data.spool.close();
          data.spool =
              new RowSpool(
                  data.inputMeta,
                  data.resolved.getCacheRows(),
                  path(data.resolved.getTempDirectory()),
                  data.resolved.isCompress());
        }
      }
      data.received++;
      if (data.spool != null) data.spool.add(row);
      String oid = null;
      Issue.RowReference reference = new Issue.RowReference(getCopy(), data.received, null);
      ch.interlis.iom.IomObject object = null;
      try {
        reference = new Issue.RowReference(getCopy(), data.received, data.mapping.sourceId(row));
        oid = data.mapping.objectId(row);
        object = data.mapping.object(row, oid);
      } catch (Exception ex) {
        String attr =
            ex instanceof HopMapping.MappingException mapping ? mapping.attribute() : null;
        data.session.mappingError(ex.getMessage(), oid, reference, attr);
      }
      // Validator / diagnostic failures are technical failures, never mapping errors.
      if (object != null) data.session.accept(object, reference);
      if (data.resolved.isSinglePassOnly()) {
        if (data.session.errors() > 0) {
          validationEnd(false, false);
          failValidation();
          return false;
        }
        checkCancelled();
        putRow(data.inputMeta, row);
        data.emitted++;
      }
      return true;
    } catch (Exception ex) {
      terminate(ex instanceof CancellationException ? "aborted" : "technical_error", ex);
      if (ex instanceof CancellationException) {
        setOutputDone();
        return false;
      }
      throw new HopException("Row Stream Validator failed: " + ex.getMessage(), ex);
    }
  }

  private void validationEnd(boolean complete, boolean release) {
    if (data.validationReported) return;
    if (data.report != null) {
      data.report.record(
          "validation_end",
          Map.of(
              "complete",
              complete,
              "errors",
              data.session.errors(),
              "warnings",
              data.session.warnings(),
              "input_rows",
              data.received,
              "release",
              release));
      data.report.flush();
    }
    data.validationReported = true;
  }

  private void failValidation() throws Exception {
    finishResources("validation_failed");
    setErrors(Math.max(1, data.session.errors()));
    setOutputDone();
    stopAll();
  }

  private void complete() throws Exception {
    finishResources("success");
    setOutputDone();
  }

  private void finishResources(String status) throws Exception {
    if (data.spool != null) data.spool.close();
    if (data.session != null) data.session.close();
    if (data.report != null) {
      data.report.record(
          "run_end",
          Map.of("status", status, "input_rows", data.received, "output_rows", data.emitted));
      data.report.flush();
      data.report.close();
    }
    data.terminal = true;
    logBasic(
        "Row Stream Validator: "
            + status
            + "; input="
            + data.received
            + ", output="
            + data.emitted);
  }

  private void terminate(String status, Exception cause) {
    if (data.terminal) return;
    try {
      if ("technical_error".equals(status))
        issue(
            new Issue(
                "ERROR",
                "execution",
                Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()),
                null,
                null,
                null));
      if (data.session != null) validationEnd(data.session.finished(), false);
      finishResources(status);
    } catch (Exception cleanup) {
      cause.addSuppressed(cleanup);
      logError("Cannot finalize validator resources", cleanup);
      if (data.session != null)
        try {
          data.session.close();
        } catch (Exception ex) {
          cause.addSuppressed(ex);
        }
      if (data.spool != null)
        try {
          data.spool.close();
        } catch (Exception ex) {
          cause.addSuppressed(ex);
        }
      if (data.report != null)
        try {
          data.report.close();
        } catch (Exception ex) {
          cause.addSuppressed(ex);
        }
      data.terminal = true;
    }
    if (!"aborted".equals(status)) setErrors(Math.max(1, getErrors()));
  }

  @Override
  public void stopRunning() throws HopException {
    data.cancelled = true;
    super.stopRunning();
  }

  @Override
  public void dispose() {
    if (!data.terminal)
      terminate("aborted", new CancellationException("Disposed before completion"));
    super.dispose();
  }
}
