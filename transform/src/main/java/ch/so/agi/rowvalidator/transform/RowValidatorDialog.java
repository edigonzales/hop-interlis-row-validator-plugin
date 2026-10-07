package ch.so.agi.rowvalidator.transform;

import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.rowvalidator.core.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.widget.TextVar;
import org.apache.hop.ui.pipeline.transform.BaseTransformDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Dialog edits a private draft; only OK commits. Slow model work never runs on the UI thread. */
public class RowValidatorDialog extends BaseTransformDialog {
  private final RowValidatorMeta input;
  private final RowValidatorMeta draft;
  private final Map<String, TextVar> texts = new LinkedHashMap<>();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "row-validator-model");
            t.setDaemon(true);
            return t;
          });
  private Future<?> pending;
  private long revision;
  private boolean filling;
  private TransferDescription loaded;
  private ModelDescriptor descriptor;
  private IRowMeta incoming;
  private Button full, single, compress;
  private Combo classes;
  private Label hint, status, outputHint, details;
  private Table table;
  private TableEditor editor;
  private Composite buffer;
  private final List<FieldMapping> mapping;

  public RowValidatorDialog(
      Shell parent, IVariables variables, RowValidatorMeta meta, PipelineMeta pipelineMeta) {
    super(parent, variables, meta, pipelineMeta);
    input = meta;
    draft = meta.clone();
    mapping = draft.getMappings();
  }

  private static String msg(String key) {
    return BaseMessages.getString(RowValidatorMeta.class, key);
  }

  @Override
  public String open() {
    shell = new Shell(getParent(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX);
    shell.setText(msg("Plugin.Name"));
    PropsUi.setLook(shell);
    setShellImage(shell, input);
    // Hop adds its help control with FormData. This dialog uses GridLayout.
    for (Control control : shell.getChildren())
      control.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    shell.setLayout(new GridLayout(1, false));
    shell.setMinimumSize(820, 620);
    Composite top = new Composite(shell, SWT.NONE);
    top.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    top.setLayout(new GridLayout(2, false));
    newLabel(top, "Name");
    wTransformName = new Text(top, SWT.BORDER);
    wTransformName.setLayoutData(fill());
    wTransformName.setText(transformName == null ? "" : transformName);
    newLabel(top, "Mode");
    Composite modes = new Composite(top, SWT.NONE);
    modes.setLayout(new RowLayout());
    full = button(modes, "Full", SWT.RADIO, () -> modeChanged());
    single = button(modes, "Single", SWT.RADIO, () -> modeChanged());
    full.setSelection(!draft.isSinglePassOnly());
    single.setSelection(draft.isSinglePassOnly());
    hint = new Label(shell, SWT.WRAP);
    hint.setLayoutData(fill());
    TabFolder tabs = new TabFolder(shell, SWT.NONE);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite validation = tab(tabs, "Validation");
    text(validation, "Models", draft.getModelNames());
    text(validation, "Sources", draft.getModelSources());
    button(validation, "Load", SWT.PUSH, this::loadModel);
    button(validation, "CancelLoad", SWT.PUSH, this::cancelLoad);
    newLabel(validation, "Class");
    classes = new Combo(validation, SWT.DROP_DOWN);
    classes.setLayoutData(fill());
    classes.setText(draft.getClassName());
    classes.addModifyListener(
        e -> {
          if (!filling) {
            revision++;
            chooseClass();
          }
        });
    pathField(validation, "Config", draft.getConfigFile(), false);
    Label scope = new Label(validation, SWT.WRAP);
    scope.setText(msg("Scope"));
    scope.setLayoutData(span());
    Composite fields = tab(tabs, "Mapping");
    Composite actions = new Composite(fields, SWT.NONE);
    actions.setLayoutData(span());
    actions.setLayout(new RowLayout());
    button(actions, "Refresh", SWT.PUSH, this::refreshFields);
    button(actions, "Auto", SWT.PUSH, this::autoMap);
    table = new Table(fields, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData grid = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    grid.heightHint = 210;
    table.setLayoutData(grid);
    String[] headers = {"Attribute", "Type", "Field", "Status"};
    int[] widths = {170, 180, 190, 230};
    for (int i = 0; i < headers.length; i++) {
      TableColumn c = new TableColumn(table, SWT.NONE);
      c.setText(msg(headers[i]));
      c.setWidth(widths[i]);
    }
    editor = new TableEditor(table);
    editor.grabHorizontal = true;
    table.addListener(SWT.Selection, e -> editField());
    text(fields, "Zone", draft.getTimeZone());
    details = new Label(fields, SWT.WRAP);
    details.setLayoutData(span());
    details.setText(msg("ZoneHelp"));
    Composite output = tab(tabs, "Output");
    outputHint = new Label(output, SWT.WRAP);
    outputHint.setLayoutData(span());
    pathField(output, "Report", draft.getReportDirectory(), true);
    text(output, "SourceRow", draft.getSourceRowField());
    Label reportHelp = new Label(output, SWT.WRAP);
    reportHelp.setText(msg("ReportHelp"));
    reportHelp.setLayoutData(span());
    Composite advanced = tab(tabs, "Advanced");
    text(advanced, "ObjectId", draft.getObjectIdField());
    Label oidHelp = new Label(advanced, SWT.WRAP);
    oidHelp.setText(msg("ObjectIdHelp"));
    oidHelp.setLayoutData(span());
    buffer = new Composite(advanced, SWT.NONE);
    buffer.setLayoutData(span());
    buffer.setLayout(new GridLayout(2, false));
    text(buffer, "Cache", Integer.toString(draft.getCacheRows()));
    pathField(buffer, "Temp", draft.getTempDirectory(), true);
    compress = button(buffer, "Compress", SWT.CHECK, () -> {});
    compress.setLayoutData(span());
    compress.setSelection(draft.isCompress());
    Label cacheHelp = new Label(buffer, SWT.WRAP);
    cacheHelp.setText(msg("CacheHelp"));
    cacheHelp.setLayoutData(span());
    status = new Label(shell, SWT.WRAP);
    status.setLayoutData(fill());
    status.setText(msg("NotChecked"));
    Composite footer = new Composite(shell, SWT.NONE);
    footer.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
    footer.setLayout(new RowLayout());
    button(footer, "Check", SWT.PUSH, this::check);
    button(footer, "Cancel", SWT.PUSH, this::cancel);
    Button ok = button(footer, "OK", SWT.PUSH, this::ok);
    shell.setDefaultButton(ok);
    shell.addListener(
        SWT.Close,
        e -> {
          e.doit = false;
          cancel();
        });
    shell.addListener(
        SWT.Traverse,
        e -> {
          if (e.detail == SWT.TRAVERSE_ESCAPE) {
            e.doit = false;
            cancel();
          }
        });
    shell.addDisposeListener(
        e -> {
          revision++;
          worker.shutdownNow();
        });
    for (TextVar field : texts.values()) field.addModifyListener(e -> invalidateCheck());
    texts.get("Zone").addModifyListener(e -> render());
    compress.addListener(SWT.Selection, e -> invalidateCheck());
    for (String key : List.of("Models", "Sources"))
      texts
          .get(key)
          .addModifyListener(
              e -> {
                revision++;
                loaded = null;
                descriptor = null;
                status.setText(msg("Reload"));
                render();
              });
    refreshFields();
    render();
    modeChanged();
    Display display = shell.getDisplay();
    shell.setSize(980, 760);
    shell.open();
    while (!shell.isDisposed()) if (!display.readAndDispatch()) display.sleep();
    return transformName;
  }

  private GridData fill() {
    return new GridData(SWT.FILL, SWT.CENTER, true, false);
  }

  private GridData span() {
    return new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
  }

  private void newLabel(Composite parent, String key) {
    Label label = new Label(parent, SWT.NONE);
    label.setText(msg(key));
  }

  private Composite tab(TabFolder folder, String title) {
    TabItem item = new TabItem(folder, SWT.NONE);
    item.setText(msg(title));
    Composite content = new Composite(folder, SWT.NONE);
    content.setLayout(new GridLayout(2, false));
    item.setControl(content);
    return content;
  }

  private TextVar text(Composite parent, String key, String value) {
    newLabel(parent, key);
    TextVar field = new TextVar(variables, parent, SWT.BORDER);
    field.setLayoutData(fill());
    field.setText(value == null ? "" : value);
    field.setData("key", key);
    texts.put(key, field);
    return field;
  }

  private void pathField(Composite parent, String key, String value, boolean directory) {
    text(parent, key, value);
    new Label(parent, SWT.NONE);
    button(
        parent,
        "Browse",
        SWT.PUSH,
        () -> {
          String selected =
              directory
                  ? new DirectoryDialog(shell).open()
                  : new FileDialog(shell, SWT.OPEN).open();
          if (selected != null) texts.get(key).setText(selected);
        });
  }

  private Button button(Composite parent, String key, int style, Runnable action) {
    Button button = new Button(parent, style);
    button.setText(msg(key));
    button.setData("key", key);
    button.addListener(SWT.Selection, e -> action.run());
    return button;
  }

  private void invalidateCheck() {
    revision++;
    if (status != null && !status.isDisposed()) status.setText(msg("NotChecked"));
  }

  private void modeChanged() {
    invalidateCheck();
    if (hint == null) return;
    String message = msg(single.getSelection() ? "SingleHelp" : "FullHelp");
    hint.setText(message);
    if (outputHint != null)
      outputHint.setText(
          message + "\n" + msg(single.getSelection() ? "SingleFailure" : "FullFailure"));
    if (buffer != null) enable(buffer, !single.getSelection());
    shell.layout(true, true);
  }

  private void enable(Composite parent, boolean enabled) {
    parent.setEnabled(enabled);
    for (Control c : parent.getChildren()) {
      c.setEnabled(enabled);
      if (c instanceof Composite composite) enable(composite, enabled);
    }
  }

  private String value(String key) {
    return texts.get(key).getText();
  }

  private RowValidatorMeta snapshot() {
    RowValidatorMeta r = draft.clone();
    r.setModelNames(value("Models"));
    r.setModelSources(value("Sources"));
    r.setClassName(classes.getText());
    r.setConfigFile(value("Config"));
    r.setTimeZone(value("Zone"));
    r.setReportDirectory(value("Report"));
    r.setSourceRowField(value("SourceRow"));
    r.setObjectIdField(value("ObjectId"));
    r.setTempDirectory(value("Temp"));
    r.setSinglePassOnly(single.getSelection());
    r.setCompress(compress.getSelection());
    try {
      r.setCacheRows(Integer.parseInt(value("Cache")));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(msg("CacheInvalid"));
    }
    r.setMappings(
        mapping.stream()
            .map(m -> new FieldMapping(m.getAttribute(), m.getField()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    return r;
  }

  private void loadModel() {
    try {
      RowValidatorMeta requested = new RowValidatorMeta();
      requested.setModelNames(value("Models"));
      requested.setModelSources(value("Sources"));
      RowValidatorMeta r = ConfigurationCheck.resolve(requested, variables);
      long token = ++revision;
      status.setText(msg("Loading"));
      Display display = shell.getDisplay();
      if (pending != null) pending.cancel(true);
      pending =
          worker.submit(
              () -> {
                try {
                  TransferDescription td =
                      ModelService.compile(r.getModelNames(), r.getModelSources());
                  List<String> names = ModelService.classes(td);
                  post(
                      display,
                      token,
                      () -> {
                        loaded = td;
                        filling = true;
                        String previous = classes.getText();
                        classes.setItems(names.toArray(String[]::new));
                        classes.setText(
                            previous.isBlank() && names.size() == 1 ? names.getFirst() : previous);
                        filling = false;
                        chooseClass();
                      });
                } catch (Exception ex) {
                  post(
                      display,
                      token,
                      () -> status.setText(msg("LoadFailed") + " " + ex.getMessage()));
                }
              });
    } catch (Exception ex) {
      status.setText(ex.getMessage());
    }
  }

  private void post(Display display, long token, Runnable result) {
    if (display.isDisposed()) return;
    display.asyncExec(
        () -> {
          if (!shell.isDisposed() && token == revision) {
            result.run();
            shell.layout(true, true);
          }
        });
  }

  private void cancelLoad() {
    revision++;
    if (pending != null) pending.cancel(true);
    status.setText(msg("Cancelled"));
  }

  private void chooseClass() {
    descriptor = null;
    if (loaded != null)
      try {
        descriptor = ModelDescriptor.of(loaded, classes.getText());
        status.setText(msg("Loaded"));
      } catch (Exception ex) {
        status.setText(ex.getMessage());
      }
    render();
  }

  private void refreshFields() {
    invalidateCheck();
    try {
      var transform = pipelineMeta.findTransform(transformName);
      incoming =
          transform == null ? null : pipelineMeta.getPrevTransformFields(variables, transform);
      if (incoming == null) status.setText(msg("MetadataUnavailable"));
    } catch (Exception ex) {
      incoming = null;
      status.setText(msg("MetadataUnavailable") + " " + ex.getMessage());
    }
    render();
  }

  static List<String> matching(String attribute, String[] fields) {
    List<String> exact = Arrays.stream(fields).filter(attribute::equals).toList();
    return exact.isEmpty()
        ? Arrays.stream(fields).filter(attribute::equalsIgnoreCase).toList()
        : exact;
  }

  private FieldMapping mapping(String attribute) {
    return mapping.stream()
        .filter(m -> attribute.equals(m.getAttribute()))
        .findFirst()
        .orElseGet(
            () -> {
              FieldMapping m = new FieldMapping(attribute, "");
              mapping.add(m);
              return m;
            });
  }

  private void autoMap() {
    invalidateCheck();
    if (descriptor == null || incoming == null) {
      status.setText(msg("LoadFirst"));
      return;
    }
    boolean ambiguous = false;
    for (var attr : descriptor.attributes()) {
      FieldMapping m = mapping(attr.name());
      if (m.getField() != null && !m.getField().isBlank()) continue;
      List<String> hits = matching(attr.name(), incoming.getFieldNames());
      if (hits.size() == 1) m.setField(hits.getFirst());
      if (hits.size() > 1) ambiguous = true;
    }
    status.setText(msg(ambiguous ? "Ambiguous" : "Assigned"));
    render();
  }

  private void disposeEditor() {
    if (editor != null && editor.getEditor() != null) {
      editor.getEditor().dispose();
      editor.setEditor(null);
    }
  }

  private void render() {
    if (table == null || table.isDisposed()) return;
    disposeEditor();
    table.removeAll();
    Set<String> visible = new HashSet<>();
    if (descriptor != null)
      for (var attr : descriptor.attributes()) {
        FieldMapping m = mapping(attr.name());
        visible.add(attr.name());
        String field = m.getField() == null ? "" : m.getField();
        String state =
            field.isBlank()
                ? msg(attr.required() ? "RequiredMissing" : "Undefined")
                : incoming == null
                    ? msg("Unchecked")
                    : incoming.indexOfValue(field) < 0 ? msg("FieldMissing") : msg("Assigned");
        if (!field.isBlank() && incoming != null && incoming.indexOfValue(field) >= 0)
          try {
            var zone =
                value("Zone").isBlank()
                    ? null
                    : java.time.ZoneId.of(variables.resolve(value("Zone")));
            ScalarConverter.check(
                HopMapping.inputKind(incoming.getValueMeta(incoming.indexOfValue(field))),
                attr.kind(),
                zone);
          } catch (Exception ex) {
            state = msg("TypeCheck") + ": " + ex.getMessage();
          }
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(
            new String[] {attr.name(), attr.kind() + (attr.required() ? " *" : ""), field, state});
        item.setData(m);
        item.setData("details", attr.details());
      }
    for (FieldMapping m : mapping)
      if (!visible.contains(m.getAttribute())) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(
            new String[] {
              m.getAttribute(),
              "",
              m.getField() == null ? "" : m.getField(),
              msg(descriptor == null ? "Unchecked" : "UnknownAttribute")
            });
        item.setData(m);
      }
  }

  private void editField() {
    disposeEditor();
    if (table.getSelectionCount() == 0) return;
    TableItem item = table.getSelection()[0];
    FieldMapping m = (FieldMapping) item.getData();
    Combo field = new Combo(table, SWT.DROP_DOWN);
    if (incoming != null) field.setItems(incoming.getFieldNames());
    field.setText(item.getText(2));
    field.addModifyListener(
        e -> {
          m.setField(field.getText());
          item.setText(2, field.getText());
          item.setText(3, msg("Unchecked"));
          revision++;
        });
    editor.setEditor(field, item, 2);
    field.setFocus();
    details.setText(Objects.toString(item.getData("details"), msg("ZoneHelp")));
    shell.layout(true, true);
  }

  private void check() {
    try {
      RowValidatorMeta r = snapshot();
      var in = incoming;
      var transform = pipelineMeta.findTransform(transformName);
      // Resolve variables on the UI thread; the worker receives a stable snapshot.
      r = ConfigurationCheck.resolve(r, variables);
      if (transform != null) ConfigurationCheck.environment(r, transform, variables);
      RowValidatorMeta resolved = r;
      long token = ++revision;
      status.setText(msg("Checking"));
      Display display = shell.getDisplay();
      pending =
          worker.submit(
              () -> {
                try {
                  ModelDescriptor m = ConfigurationCheck.model(resolved);
                  ValidationSession.configuration(
                      m,
                      resolved.getConfigFile().isBlank()
                          ? null
                          : java.nio.file.Path.of(resolved.getConfigFile()));
                  if (in == null) throw new IllegalArgumentException(msg("MetadataUnavailable"));
                  new HopMapping(m, resolved, in);
                  post(display, token, () -> status.setText(msg("Checked")));
                } catch (Exception ex) {
                  post(display, token, () -> status.setText(ex.getMessage()));
                }
              });
    } catch (Exception ex) {
      status.setText(ex.getMessage());
    }
  }

  private void ok() {
    try {
      RowValidatorMeta r = snapshot();
      input.setModelNames(r.getModelNames());
      input.setModelSources(r.getModelSources());
      input.setClassName(r.getClassName());
      input.setConfigFile(r.getConfigFile());
      input.setSinglePassOnly(r.isSinglePassOnly());
      input.setTimeZone(r.getTimeZone());
      input.setObjectIdField(r.getObjectIdField());
      input.setSourceRowField(r.getSourceRowField());
      input.setReportDirectory(r.getReportDirectory());
      input.setTempDirectory(r.getTempDirectory());
      input.setCacheRows(r.getCacheRows());
      input.setCompress(r.isCompress());
      input.setMappings(r.getMappings());
      input.setChanged();
      transformName = wTransformName.getText();
      shell.dispose();
    } catch (Exception ex) {
      status.setText(ex.getMessage());
    }
  }

  private void cancel() {
    transformName = null;
    shell.dispose();
  }
}
