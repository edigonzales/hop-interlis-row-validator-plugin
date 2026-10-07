package ch.so.agi.rowvalidator.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.ui.core.widget.TextVar;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.*;

class DialogTest {
  static Display display;

  @BeforeAll
  static void start() throws Exception {
    HopClientEnvironment.init();
    display = new Display();
  }

  @AfterAll
  static void stop() {
    display.dispose();
  }

  private static List<Control> controls(Composite parent) {
    var result = new ArrayList<Control>();
    for (Control c : parent.getChildren()) {
      result.add(c);
      if (c instanceof Composite p) result.addAll(controls(p));
    }
    return result;
  }

  private static Control control(Shell shell, String key) {
    return controls(shell).stream()
        .filter(c -> key.equals(c.getData("key")))
        .findFirst()
        .orElseThrow();
  }

  private static void click(Shell shell, String key) {
    control(shell, key).notifyListeners(SWT.Selection, new Event());
  }

  private static void text(Shell shell, String key, String value) {
    ((TextVar) control(shell, key)).setText(value);
  }

  private static void whenOpen(Shell parent, Runnable action) {
    display.timerExec(
        30,
        () -> {
          boolean opening =
              Arrays.stream(Thread.currentThread().getStackTrace())
                  .anyMatch(
                      f ->
                          f.getClassName().equals(Shell.class.getName())
                              && f.getMethodName().equals("open"));
          if (opening
              || Arrays.stream(display.getShells())
                  .noneMatch(s -> s != parent && !s.isDisposed() && s.getVisible()))
            whenOpen(parent, action);
          else action.run();
        });
  }

  @Test
  void okCommitsDraftAndCancelEscapeOrCloseDiscardIt() throws Exception {
    for (String exit : List.of("OK", "Cancel", "Escape", "Close")) {
      var meta = HopMappingTest.config();
      meta.setModelNames("original");
      meta.setChanged(false);
      var pipeline = new PipelineMeta();
      var transform = new TransformMeta("validate", meta);
      pipeline.addTransform(transform);
      Shell parent = new Shell(display);
      parent.open();
      var failure = new AtomicReference<Throwable>();
      Runnable timeout =
          () -> {
            failure.set(new AssertionError("Dialog did not complete within 15 seconds"));
            for (Shell child : parent.getShells()) child.dispose();
          };
      display.timerExec(15000, timeout);
      whenOpen(
          parent,
          () -> {
            Shell shell =
                Arrays.stream(display.getShells())
                    .filter(
                        s ->
                            s != parent
                                && !s.isDisposed()
                                && controls(s).stream()
                                    .anyMatch(c -> "Models".equals(c.getData("key"))))
                    .findFirst()
                    .orElseThrow();
            try {
              text(shell, "Models", "edited");
              text(shell, "Cache", "37");
              var single = (Button) control(shell, "Single");
              var full = (Button) control(shell, "Full");
              single.setSelection(true);
              full.setSelection(false);
              click(shell, "Single");
              assertFalse(control(shell, "Cache").isEnabled());
              full.setSelection(true);
              single.setSelection(false);
              click(shell, "Full");
              assertTrue(control(shell, "Cache").isEnabled());
              assertEquals("37", ((TextVar) control(shell, "Cache")).getText());
              click(shell, "Refresh");
              click(shell, "Auto"); // unavailable upstream must preserve manual entries
              Table table =
                  (Table)
                      controls(shell).stream()
                          .filter(c -> c instanceof Table)
                          .findFirst()
                          .orElseThrow();
              assertEquals("key", table.getItem(0).getText(2));
              table.setSelection(0);
              table.notifyListeners(SWT.Selection, new Event());
              Combo editor =
                  (Combo)
                      Arrays.stream(table.getChildren())
                          .filter(c -> c instanceof Combo)
                          .findFirst()
                          .orElseThrow();
              editor.setText("manual_edit");
              assertEquals("key", meta.getMappings().getFirst().getField()); // still private draft
              System.out.println("Dialog test closing via " + exit);
              switch (exit) {
                case "Escape" -> {
                  Event event = new Event();
                  event.detail = SWT.TRAVERSE_ESCAPE;
                  shell.notifyListeners(SWT.Traverse, event);
                }
                case "Close" -> shell.close();
                default -> click(shell, exit);
              }
            } catch (Throwable ex) {
              failure.set(ex);
              shell.dispose();
            }
          });
      try {
        String name = new RowValidatorDialog(parent, new Variables(), meta, pipeline).open();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(exit.equals("OK") ? "validate" : null, name);
        assertEquals(exit.equals("OK") ? "edited" : "original", meta.getModelNames());
        assertEquals(
            exit.equals("OK") ? "manual_edit" : "key", meta.getMappings().getFirst().getField());
        assertEquals(exit.equals("OK"), meta.hasChanged());
      } finally {
        display.timerExec(-1, timeout);
        parent.dispose();
      }
    }
  }

  @Test
  void backgroundModelLoadingAndAutoMappingPreserveManualAssignments() throws Exception {
    var meta = HopMappingTest.config();
    meta.setModelNames("Rows");
    meta.setClassName("Rows.Data.Row");
    meta.setModelSources(
        java.nio.file.Path.of("../core/src/test/resources/models").toAbsolutePath().toString());
    meta.getMappings().getFirst().setField("manual_key");
    var incoming = HopMappingTest.input();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("manual_key"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("START"));
    var pipeline =
        new PipelineMeta() {
          @Override
          public org.apache.hop.core.row.IRowMeta getPrevTransformFields(
              org.apache.hop.core.variables.IVariables variables, TransformMeta transform) {
            return incoming;
          }
        };
    pipeline.addTransform(new TransformMeta("validate", meta));
    Shell parent = new Shell(display);
    parent.open();
    var failure = new AtomicReference<Throwable>();
    Runnable timeout =
        () -> {
          failure.set(new AssertionError("Model load timed out"));
          for (Shell child : parent.getShells()) child.dispose();
        };
    display.timerExec(15000, timeout);
    whenOpen(
        parent,
        () -> {
          Shell shell =
              Arrays.stream(display.getShells())
                  .filter(
                      s ->
                          s != parent
                              && !s.isDisposed()
                              && controls(s).stream()
                                  .anyMatch(c -> "Models".equals(c.getData("key"))))
                  .findFirst()
                  .orElseThrow();
          click(shell, "Load");
          display.timerExec(
              25,
              new Runnable() {
                public void run() {
                  if (shell.isDisposed()) return;
                  try {
                    Table table =
                        (Table)
                            controls(shell).stream()
                                .filter(c -> c instanceof Table)
                                .findFirst()
                                .orElseThrow();
                    if (table.getItemCount() < 10) {
                      display.timerExec(25, this);
                      return;
                    }
                    click(shell, "Auto");
                    assertEquals(
                        "manual_key",
                        Arrays.stream(table.getItems())
                            .filter(i -> i.getText(0).equals("key"))
                            .findFirst()
                            .orElseThrow()
                            .getText(2));
                    assertEquals(
                        "START",
                        Arrays.stream(table.getItems())
                            .filter(i -> i.getText(0).equals("start"))
                            .findFirst()
                            .orElseThrow()
                            .getText(2));
                    if (Boolean.getBoolean("rowValidator.captureDialog")) {
                      TabFolder tabs =
                          (TabFolder)
                              controls(shell).stream()
                                  .filter(c -> c instanceof TabFolder)
                                  .findFirst()
                                  .orElseThrow();
                      tabs.setSelection(1);
                      shell.layout(true, true);
                      shell.update();
                      var screenshot =
                          new org.eclipse.swt.graphics.Image(
                              display, shell.getSize().x, shell.getSize().y);
                      var gc = new org.eclipse.swt.graphics.GC(shell);
                      try {
                        gc.copyArea(screenshot, 0, 0);
                        var loader = new org.eclipse.swt.graphics.ImageLoader();
                        loader.data =
                            new org.eclipse.swt.graphics.ImageData[] {screenshot.getImageData()};
                        loader.save("target/dialog-mapping.png", SWT.IMAGE_PNG);
                      } finally {
                        gc.dispose();
                        screenshot.dispose();
                      }
                    }
                    click(shell, "OK");
                  } catch (Throwable ex) {
                    failure.set(ex);
                    shell.dispose();
                  }
                }
              });
        });
    try {
      assertEquals(
          "validate", new RowValidatorDialog(parent, new Variables(), meta, pipeline).open());
      if (failure.get() != null) throw new AssertionError(failure.get());
      assertEquals("manual_key", meta.getMappings().getFirst().getField());
      assertTrue(
          meta.getMappings().stream()
              .anyMatch(m -> m.getAttribute().equals("start") && m.getField().equals("START")));
    } finally {
      display.timerExec(-1, timeout);
      parent.dispose();
    }
  }
}
