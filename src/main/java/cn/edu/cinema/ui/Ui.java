package cn.edu.cinema.ui;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

final class Ui {
  static final Color NAVY = new Color(19, 34, 57),
      BG = new Color(244, 247, 251),
      BLUE = new Color(37, 99, 235),
      INK = new Color(30, 41, 59),
      MUTED = new Color(100, 116, 139),
      GREEN = new Color(13, 148, 136),
      LINE = new Color(222, 229, 238);
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  static void setup() {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception ignored) {
    }
    Font f = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    for (Object k : Collections.list(UIManager.getDefaults().keys()))
      if (UIManager.get(k) instanceof javax.swing.plaf.FontUIResource) UIManager.put(k, f);
    UIManager.put("Table.rowHeight", 34);
    UIManager.put("OptionPane.messageFont", f);
  }

  static JLabel label(String text, int size, Color color) {
    JLabel l = new JLabel(text);
    l.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, size));
    l.setForeground(color);
    return l;
  }

  static JLabel heading(String text) {
    JLabel l = label(text, 24, INK);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }

  static JPanel panel(LayoutManager layout) {
    JPanel p = new JPanel(layout);
    p.setOpaque(false);
    return p;
  }

  static JPanel card(LayoutManager layout) {
    JPanel p = new JPanel(layout);
    p.setBackground(Color.WHITE);
    p.setBorder(new EmptyBorder(18, 18, 18, 18));
    return p;
  }

  static JButton button(String text, Runnable action) {
    JButton b = new JButton(text);
    b.setFocusPainted(false);
    b.setMargin(new Insets(8, 14, 8, 14));
    b.addActionListener(e -> action.run());
    return b;
  }

  static JButton primary(String text, Runnable action) {
    JButton b = button(text, action);
    b.setBackground(BLUE);
    b.setForeground(Color.WHITE);
    b.setOpaque(true);
    b.setBorderPainted(false);
    return b;
  }

  static JTextField field(String value, int columns) {
    JTextField f = new JTextField(value, columns);
    f.setMargin(new Insets(6, 8, 6, 8));
    return f;
  }

  static void error(Component owner, Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && !(cause instanceof java.sql.SQLException))
      cause = cause.getCause();
    String s = cause.getMessage();
    if (s == null || s.isBlank()) s = cause.getClass().getSimpleName();
    JTextArea a = new JTextArea(s, Math.min(9, 2 + s.length() / 55), 55);
    a.setLineWrap(true);
    a.setWrapStyleWord(true);
    a.setEditable(false);
    a.setOpaque(false);
    JOptionPane.showMessageDialog(owner, a, "操作未完成", JOptionPane.WARNING_MESSAGE);
  }

  static boolean confirm(Component owner, String text) {
    return JOptionPane.showConfirmDialog(
            owner, text, "请确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
        == JOptionPane.OK_OPTION;
  }

  static LocalDateTime time(Object value) {
    if (value instanceof LocalDateTime t) return t;
    if (value instanceof Timestamp t) return t.toLocalDateTime();
    return LocalDateTime.parse(value.toString().replace(' ', 'T'));
  }

  static boolean bool(Object v) {
    return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0);
  }

  static int number(Object v) {
    return ((Number) v).intValue();
  }

  static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  static String money(Object v) {
    return v == null
        ? "0.00"
        : new BigDecimal(v.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  static String display(Object v) {
    if (v == null) return "—";
    if (v instanceof Timestamp || v instanceof LocalDateTime) return TIME.format(time(v));
    if (v instanceof Boolean b) return b ? "启用" : "停用";
    if (v instanceof BigDecimal) return money(v);
    return v.toString();
  }

  static final class Grid {
    final JTable table;
    final DefaultTableModel model;
    List<Map<String, Object>> rows = List.of();
    final String[] keys;

    Grid(String[] titles, String... keys) {
      this.keys = keys;
      model =
          new DefaultTableModel(titles, 0) {
            public boolean isCellEditable(int r, int c) {
              return false;
            }
          };
      table = new JTable(model);
      table.setRowHeight(36);
      table.setShowVerticalLines(false);
      table.setGridColor(LINE);
      table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      table.setSelectionBackground(new Color(222, 235, 255));
      table.setSelectionForeground(INK);
      table.setAutoCreateRowSorter(true);
      table.getTableHeader().setReorderingAllowed(false);
      table.getTableHeader().setFont(table.getFont().deriveFont(Font.BOLD));
      table.setFillsViewportHeight(true);
    }

    void set(List<Map<String, Object>> data) {
      rows = data;
      model.setRowCount(0);
      for (Map<String, Object> row : rows) {
        Object[] cells = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
          Object v = row.get(keys[i]);
          cells[i] =
              switch (Objects.toString(v, "")) {
                case "PAID" -> "已售票";
                case "REFUNDED" -> "已退票";
                default -> display(v);
              };
        }
        model.addRow(cells);
      }
    }

    Map<String, Object> selected() {
      int r = table.getSelectedRow();
      return r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
    }

    JScrollPane scroll() {
      JScrollPane s = new JScrollPane(table);
      s.setBorder(BorderFactory.createLineBorder(LINE));
      return s;
    }
  }

  static JPanel form(Object... fields) {
    JPanel p = panel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(7, 7, 7, 7);
    c.anchor = GridBagConstraints.WEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    for (int i = 0; i < fields.length; i += 2) {
      c.gridy = i / 2;
      c.gridx = 0;
      c.weightx = 0;
      p.add(new JLabel(fields[i].toString()), c);
      c.gridx = 1;
      c.weightx = 1;
      p.add((Component) fields[i + 1], c);
    }
    return p;
  }

  static <T> void worker(RootPaneContainer owner, Callable<T> job, Consumer<T> done) {
    JComponent glass = (JComponent) owner.getGlassPane();
    glass.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    glass.addMouseListener(new java.awt.event.MouseAdapter() {});
    glass.setVisible(true);
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return job.call();
      }

      protected void done() {
        glass.setVisible(false);
        try {
          done.accept(get());
        } catch (Exception ex) {
          error((Component) owner, ex);
        }
      }
    }.execute();
  }
}
