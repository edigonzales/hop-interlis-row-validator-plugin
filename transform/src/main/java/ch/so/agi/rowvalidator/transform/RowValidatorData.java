package ch.so.agi.rowvalidator.transform;

import ch.so.agi.rowvalidator.core.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.pipeline.transform.BaseTransformData;

public class RowValidatorData extends BaseTransformData {
  ModelDescriptor model;
  ValidationSession session;
  JsonlReport report;
  RowSpool spool;
  HopMapping mapping;
  IRowMeta inputMeta;
  RowValidatorMeta resolved;
  long received, emitted;
  boolean releasing, terminal, validationReported;
  volatile boolean cancelled;
}
