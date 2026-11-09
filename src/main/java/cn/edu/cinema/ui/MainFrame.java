package cn.edu.cinema.ui;

import cn.edu.cinema.service.CinemaService;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Chinese desktop workstation. All database work is executed in SwingWorker. */
public final class MainFrame extends JFrame {
  private final CinemaService service;
  private final CardLayout cards = new CardLayout();
  private final JPanel content = new JPanel(cards);
  private final JLabel status = Ui.label("就绪", 12, Ui.MUTED);
  private final Map<String, JButton> navButtons = new LinkedHashMap<>();
  private String activePage = "ticket";
  private boolean busy;
  private final Ui.Grid showGrid =
      new Ui.Grid(
          new String[] {"影片", "影厅", "开场时间", "票价", "余座"},
          "movieTitle",
          "hallName",
          "startTime",
          "price",
          "availableSeats");
  private final JPanel seatGrid = Ui.panel(new GridBagLayout());
  private final JLabel chosenShow = Ui.label("选择左侧场次，开始选座", 16, Ui.INK);
  private final JLabel selection = Ui.label("尚未选座", 14, Ui.MUTED);
  private final JTextField movieSearch = Ui.field("", 16);
  private final JButton sellButton = Ui.primary("确认售票 · 模拟付款", this::sell);
  private final Set<Integer> selectedSeats = new LinkedHashSet<>();
  private Map<String, Object> currentShow;
  private List<Map<String, Object>> currentSeats = List.of();
  private final JTextField orderFrom = Ui.field(LocalDate.now().minusDays(7).toString(), 10),
      orderTo = Ui.field(LocalDate.now().toString(), 10);
  private final Ui.Grid orderGrid =
      new Ui.Grid(
          new String[] {"订单号", "影片", "影厅", "场次时间", "座位", "金额", "状态", "创建时间", "下单人"},
          "id",
          "movieTitle",
          "hallName",
          "startTime",
          "seats",
          "totalAmount",
          "status",
          "createdAt",
          "cashier");
  private final JTextField statsFrom = Ui.field(LocalDate.now().toString(), 10),
      statsTo = Ui.field(LocalDate.now().plusDays(7).toString(), 10);
  private final Ui.Grid statsGrid =
      new Ui.Grid(
          new String[] {"影片", "场次数", "累计售票", "退票数", "有效票数", "净票房", "可售座位", "上座率 %"},
          "movieTitle",
          "showCount",
          "soldCount",
          "refundCount",
          "netTickets",
          "netRevenue",
          "capacity",
          "occupancy");
  private final JLabel revenue = Ui.heading("¥ 0.00"),
      tickets = Ui.heading("0"),
      occupancy = Ui.heading("0.00 %");
  private final Ui.Grid movieGrid =
      new Ui.Grid(
          new String[] {"编号", "影片名称", "片长 / 分钟", "状态"}, "id", "title", "durationMinutes", "active");
  private final Ui.Grid hallGrid =
      new Ui.Grid(new String[] {"编号", "影厅名称", "排数", "每排座位"}, "id", "name", "rowCount", "colCount");
  private final Ui.Grid adminShowGrid =
      new Ui.Grid(
          new String[] {"编号", "影片", "影厅", "开场时间", "结束时间", "票价", "散场间隔 / 分钟", "余座"},
          "id",
          "movieTitle",
          "hallName",
          "startTime",
          "endTime",
          "price",
          "cleanupMinutes",
          "availableSeats");
  private final Ui.Grid logGrid =
      new Ui.Grid(
          new String[] {"日志编号", "订单号", "原状态", "新状态", "操作时间", "操作人"},
          "id",
          "orderId",
          "oldStatus",
          "newStatus",
          "changedAt",
          "actor");

  public MainFrame(CinemaService service) {
    super("影院排片与售票管理系统");
    this.service = service;
    if (service.user().isCustomer()) {
      setTitle("影院购票 · " + service.user().displayName());
      sellButton.setText("确认购票 · 模拟付款");
    }
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setSize(1360, 880);
    setMinimumSize(new Dimension(1120, 740));
    setLocationRelativeTo(null);
    JPanel shell = new JPanel(new BorderLayout());
    shell.setBackground(Ui.BG);
    shell.add(sidebar(), BorderLayout.WEST);
    JPanel workspace = new JPanel(new BorderLayout(0, 16));
    workspace.setBackground(Ui.BG);
    workspace.setBorder(new EmptyBorder(22, 24, 14, 24));
    JPanel top = Ui.panel(new BorderLayout());
    top.add(
        Ui.label(service.user().isCustomer() ? "影院购票中心" : "影院运营工作台", 14, Ui.MUTED),
        BorderLayout.WEST);
    top.add(
        Ui.label(
            service.user().displayName()
                + "（"
                + service.user().username()
                + "） · "
                + service.user().roleName(),
            14,
            Ui.INK),
        BorderLayout.EAST);
    workspace.add(top, BorderLayout.NORTH);
    content.setOpaque(false);
    content.add(ticketPage(), "ticket");
    content.add(orderPage(), "orders");
    if (service.user().isStaff()) content.add(statsPage(), "stats");
    if (service.user().isAdmin()) {
      content.add(adminPage(), "admin");
      content.add(logPage(), "logs");
    }
    workspace.add(content, BorderLayout.CENTER);
    workspace.add(status, BorderLayout.SOUTH);
    shell.add(workspace, BorderLayout.CENTER);
    setContentPane(shell);
    int[] showWidths = {100, 95, 150, 60, 45};
    for (int i = 0; i < showWidths.length; i++)
      showGrid.table.getColumnModel().getColumn(i).setPreferredWidth(showWidths[i]);
    showGrid
        .table
        .getSelectionModel()
        .addListSelectionListener(e -> {
          if (!e.getValueIsAdjusting() && !busy) {
            Map<String, Object> row = showGrid.selected();
            if (row != null) loadSeats(row);
          }
        });
    SwingUtilities.invokeLater(() -> navigate("ticket"));
  }

  private JPanel sidebar() {
    JPanel side = new JPanel();
    side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
    side.setBackground(Ui.NAVY);
    side.setPreferredSize(new Dimension(210, 0));
    side.setBorder(new EmptyBorder(30, 18, 24, 18));
    JLabel brand = Ui.label("CINEMA", 25, Color.WHITE);
    brand.setFont(brand.getFont().deriveFont(Font.BOLD));
    brand.setAlignmentX(Component.LEFT_ALIGNMENT);
    side.add(brand);
    side.add(Box.createVerticalStrut(7));
    side.add(
        Ui.label(
            service.user().isCustomer() ? "电影与我的观影" : "排片与售票管理", 13, new Color(157, 179, 210)));
    side.add(Box.createVerticalStrut(38));
    nav(side, "ticket", service.user().isCustomer() ? "选座购票" : "选座售票");
    nav(side, "orders", service.user().isCustomer() ? "我的订单" : "订单与退票");
    if (service.user().isStaff()) nav(side, "stats", "经营统计");
    if (service.user().isAdmin()) {
      side.add(Box.createVerticalStrut(18));
      nav(side, "admin", "基础资料与排片");
      nav(side, "logs", "订单状态日志");
    }
    side.add(Box.createVerticalGlue());
    side.add(Ui.label("单影院 · 模拟付款", 12, new Color(157, 179, 210)));
    side.add(Box.createVerticalStrut(14));
    JButton passwordButton = Ui.button("修改密码", this::passwordForm);
    passwordButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    side.add(passwordButton);
    side.add(Box.createVerticalStrut(8));
    JButton out =
        Ui.button(
            "退出登录",
            () -> {
              if (!busy && Ui.confirm(this, "退出当前账号？")) {
                new LoginFrame().setVisible(true);
                dispose();
              }
            });
    out.setAlignmentX(Component.LEFT_ALIGNMENT);
    side.add(out);
    return side;
  }

  private void passwordForm() {
    JPasswordField oldPass = new JPasswordField(20),
        newPass = new JPasswordField(20),
        confirm = new JPasswordField(20);
    JPanel form = Ui.form("当前密码", oldPass, "新密码（至少10位）", newPass, "确认新密码", confirm);
    if (JOptionPane.showConfirmDialog(
            this, form, "修改登录密码", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        != JOptionPane.OK_OPTION) return;
    char[] oldValue = oldPass.getPassword(),
        newValue = newPass.getPassword(),
        confirmValue = confirm.getPassword();
    boolean matches = Arrays.equals(newValue, confirmValue);
    Arrays.fill(confirmValue, '\0');
    if (!matches) {
      Arrays.fill(oldValue, '\0');
      Arrays.fill(newValue, '\0');
      JOptionPane.showMessageDialog(this, "两次新密码不一致。");
      return;
    }
    change(
        "正在修改密码…",
        () -> service.changePassword(oldValue, newValue),
        () -> JOptionPane.showMessageDialog(this, "密码已修改，下次登录请使用新密码。"));
  }

  private void nav(JPanel side, String key, String title) {
    JButton b = Ui.button(title, () -> navigate(key));
    b.setAlignmentX(Component.LEFT_ALIGNMENT);
    b.setMaximumSize(new Dimension(174, 46));
    b.setPreferredSize(new Dimension(174, 46));
    b.setHorizontalAlignment(SwingConstants.LEFT);
    b.setBackground(Ui.NAVY);
    b.setForeground(Color.WHITE);
    b.setOpaque(true);
    b.setBorderPainted(false);
    navButtons.put(key, b);
    side.add(b);
    side.add(Box.createVerticalStrut(8));
  }

  private JPanel page(String title, String subtitle) {
    JPanel p = Ui.panel(new BorderLayout(0, 18));
    JPanel h = Ui.panel(new GridLayout(2, 1, 0, 8));
    h.add(Ui.heading(title));
    h.add(Ui.label(subtitle, 13, Ui.MUTED));
    p.add(h, BorderLayout.NORTH);
    return p;
  }

  private void navigate(String key) {
    if (busy || !navButtons.containsKey(key)) return;
    activePage = key;
    cards.show(content, key);
    navButtons.forEach((k, b) -> b.setBackground(k.equals(key) ? new Color(42, 66, 102) : Ui.NAVY));
    switch (key) {
      case "ticket" -> refreshShows();
      case "orders" -> refreshOrders();
      case "stats" -> refreshStats();
      case "admin" -> refreshAdmin();
      case "logs" -> refreshLogs();
    }
  }

  private <T> void run(String message, Callable<T> work, Consumer<T> done) {
    if (busy) return;
    busy = true;
    status.setText(message);
    JComponent glass = (JComponent) getGlassPane();
    glass.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    if (glass.getMouseListeners().length == 0)
      glass.addMouseListener(new java.awt.event.MouseAdapter() {});
    glass.setVisible(true);
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return work.call();
      }

      protected void done() {
        busy = false;
        glass.setVisible(false);
        status.setText("就绪 · " + Ui.TIME.format(LocalDateTime.now()));
        try {
          done.accept(get());
        } catch (Exception ex) {
          status.setText("操作未完成，请检查提示");
          Ui.error(MainFrame.this, ex);
        }
      }
    }.execute();
  }

  private void change(String message, Checked change, Runnable refresh) {
    run(
        message,
        () -> {
          change.execute();
          return true;
        },
        x -> refresh.run());
  }

  @FunctionalInterface
  private interface Checked {
    void execute() throws Exception;
  }

  private JPanel toolbar(Component... children) {
    JPanel bar = Ui.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    for (Component c : children) bar.add(c);
    return bar;
  }

  private JPanel ticketPage() {
    JPanel p = page(service.user().isCustomer() ? "选座购票" : "选座售票", "选择未开场场次和座位，确认金额后完成模拟付款。");
    JPanel body = Ui.panel(new BorderLayout(0, 12));
    body.add(
        toolbar(
            new JLabel("影片名称"),
            movieSearch,
            Ui.button("查询 / 刷新", this::refreshShows),
            Ui.label("仅显示未开场场次", 12, Ui.MUTED)),
        BorderLayout.NORTH);
    movieSearch.addActionListener(e -> {
      if (!busy) refreshShows();
    });
    JPanel left = Ui.card(new BorderLayout(0, 12));
    left.add(Ui.label("可售场次", 17, Ui.INK), BorderLayout.NORTH);
    left.add(showGrid.scroll(), BorderLayout.CENTER);
    left.setMinimumSize(new Dimension(350, 200));
    JPanel right = Ui.card(new BorderLayout(0, 14));
    JPanel header = Ui.panel(new GridLayout(2, 1, 0, 8));
    header.add(chosenShow);
    JLabel screen = Ui.label("银 幕 方 向", 14, Ui.MUTED);
    screen.setHorizontalAlignment(SwingConstants.CENTER);
    screen.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, Ui.LINE));
    header.add(screen);
    right.add(header, BorderLayout.NORTH);
    JScrollPane seatScroll = new JScrollPane(seatGrid);
    seatScroll.setBorder(null);
    seatScroll.getViewport().setBackground(Color.WHITE);
    right.add(seatScroll, BorderLayout.CENTER);
    JPanel footer = Ui.panel(new BorderLayout(0, 14));
    footer.add(
        toolbar(
            Ui.label("□ 可售", 12, Ui.MUTED),
            Ui.label("■ 已选", 12, Ui.BLUE),
            Ui.label("■ 已售 / 停用", 12, Ui.MUTED)),
        BorderLayout.NORTH);
    footer.add(selection, BorderLayout.CENTER);
    sellButton.setEnabled(false);
    footer.add(sellButton, BorderLayout.SOUTH);
    right.add(footer, BorderLayout.SOUTH);
    right.setMinimumSize(new Dimension(350, 200));
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    split.setBorder(null);
    split.setResizeWeight(.49);
    split.setDividerLocation(490);
    body.add(split, BorderLayout.CENTER);
    p.add(body, BorderLayout.CENTER);
    return p;
  }

  private void refreshShows() {
    if (busy) return;
    String keyword = movieSearch.getText().trim().toLowerCase(Locale.ROOT);
    currentShow = null;
    currentSeats = List.of();
    selectedSeats.clear();
    seatGrid.removeAll();
    seatGrid.revalidate();
    seatGrid.repaint();
    chosenShow.setText("选择左侧场次，开始选座");
    updateSelection();
    run(
        "正在读取可售场次…",
        () -> service.table("shows"),
        rows -> {
          showGrid.set(
              rows.stream()
                  .filter(r -> Ui.time(r.get("startTime")).isAfter(LocalDateTime.now()))
                  .filter(r -> Ui.str(r, "movieTitle").toLowerCase(Locale.ROOT).contains(keyword))
                  .toList());
          status.setText("共 " + showGrid.rows.size() + " 个未开场场次");
        });
  }

  private void loadSeats(Map<String, Object> row) {
    run(
        "正在读取座位…",
        () -> service.table("showSeats", Ui.number(row.get("id"))),
        rows -> {
          currentShow = row;
          currentSeats = rows;
          selectedSeats.clear();
          chosenShow.setText(
              Ui.str(row, "movieTitle")
                  + " · "
                  + Ui.str(row, "hallName")
                  + " · "
                  + Ui.TIME.format(Ui.time(row.get("startTime"))));
          renderSeats();
        });
  }

  private void renderSeats() {
    seatGrid.removeAll();
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 3, 3, 3);
    for (Map<String, Object> seat : currentSeats) {
      int id = Ui.number(seat.get("id")),
          r = Ui.number(seat.get("rowNo")),
          col = Ui.number(seat.get("colNo"));
      boolean usable = Ui.bool(seat.get("active")) && !Ui.bool(seat.get("sold"));
      JToggleButton b = new JToggleButton(r + "-" + col, selectedSeats.contains(id));
      b.setFont(b.getFont().deriveFont(12f));
      b.setPreferredSize(new Dimension(43, 38));
      b.setMargin(new Insets(0, 0, 0, 0));
      b.setFocusPainted(false);
      b.setEnabled(usable);
      b.setOpaque(true);
      b.setBorderPainted(false);
      b.setBackground(
          !usable ? new Color(223, 228, 235) : b.isSelected() ? Ui.BLUE : new Color(224, 242, 239));
      b.setForeground(b.isSelected() ? Color.WHITE : Ui.INK);
      b.setToolTipText(
          r
              + " 排 "
              + col
              + " 座"
              + (!Ui.bool(seat.get("active")) ? "（停用）" : Ui.bool(seat.get("sold")) ? "（已售）" : ""));
      b.addActionListener(e -> {
        if (busy) {
          b.setSelected(selectedSeats.contains(id));
          return;
        }
        if (b.isSelected()) selectedSeats.add(id);
        else selectedSeats.remove(id);
        b.setBackground(b.isSelected() ? Ui.BLUE : new Color(224, 242, 239));
        b.setForeground(b.isSelected() ? Color.WHITE : Ui.INK);
        updateSelection();
      });
      c.gridx = col - 1;
      c.gridy = r - 1;
      seatGrid.add(b, c);
    }
    seatGrid.revalidate();
    seatGrid.repaint();
    updateSelection();
  }

  private void updateSelection() {
    int count = selectedSeats.size();
    BigDecimal price =
        currentShow == null ? BigDecimal.ZERO : new BigDecimal(currentShow.get("price").toString());
    selection.setText(
        "已选 " + count + " 座    合计 ¥ " + Ui.money(price.multiply(BigDecimal.valueOf(count))));
    sellButton.setEnabled(count > 0 && currentShow != null);
  }

  private void sell() {
    if (busy || currentShow == null || selectedSeats.isEmpty()) return;
    int showId = Ui.number(currentShow.get("id"));
    List<Integer> seats = new ArrayList<>(selectedSeats);
    String seatNames =
        currentSeats.stream()
            .filter(r -> selectedSeats.contains(Ui.number(r.get("id"))))
            .map(r -> r.get("rowNo") + "排" + r.get("colNo") + "座")
            .reduce((a, b) -> a + "、" + b)
            .orElse("");
    if (!Ui.confirm(
        this,
        "影片："
            + currentShow.get("movieTitle")
            + "\n场次："
            + Ui.TIME.format(Ui.time(currentShow.get("startTime")))
            + "\n座位："
            + seatNames
            + "\n"
            + selection.getText()
            + "\n\n确认模拟付款并生成订单？")) return;
    run(
        "正在提交售票事务…",
        () -> service.sell(showId, seats),
        id -> {
          JOptionPane.showMessageDialog(
              this,
              (service.user().isCustomer() ? "购票成功，订单号：" : "售票成功，订单号：") + id + "\n本次为模拟付款。",
              service.user().isCustomer() ? "购票完成" : "售票完成",
              JOptionPane.INFORMATION_MESSAGE);
          refreshShows();
        });
  }

  private JPanel orderPage() {
    JPanel p =
        page(
            service.user().isCustomer() ? "我的订单" : "订单与退票",
            service.user().isCustomer()
                ? "仅展示当前账号的订单；开场前可整单退票，退款为模拟操作。"
                : "按订单创建日期查询；整单退票仅限开场前，退票后座位重新可售。");
    JPanel body = Ui.card(new BorderLayout(0, 16));
    body.add(
        toolbar(
            new JLabel("创建日期"),
            orderFrom,
            new JLabel("至"),
            orderTo,
            Ui.button("查询订单", this::refreshOrders),
            Ui.button("退还所选订单", this::refund)),
        BorderLayout.NORTH);
    body.add(orderGrid.scroll(), BorderLayout.CENTER);
    p.add(body, BorderLayout.CENTER);
    return p;
  }

  private LocalDate[] dates(JTextField from, JTextField to) {
    LocalDate a = LocalDate.parse(from.getText().trim()), b = LocalDate.parse(to.getText().trim());
    if (b.isBefore(a)) throw new IllegalArgumentException("结束日期不能早于开始日期。");
    return new LocalDate[] {a, b};
  }

  private void refreshOrders() {
    try {
      LocalDate[] d = dates(orderFrom, orderTo);
      run(
          "正在查询订单…",
          () -> service.table("orders", d[0], d[1]),
          rows -> {
            orderGrid.set(rows);
            status.setText("共 " + rows.size() + " 笔订单");
          });
    } catch (Exception ex) {
      Ui.error(this, new IllegalArgumentException("请输入有效的日期，格式为 yyyy-MM-dd；结束日期不能早于开始日期。"));
    }
  }

  private Map<String, Object> require(Ui.Grid g, String label) {
    Map<String, Object> r = g.selected();
    if (r == null) JOptionPane.showMessageDialog(this, "请先选择一条" + label + "。");
    return r;
  }

  private void refund() {
    Map<String, Object> row = require(orderGrid, "订单");
    if (row == null) return;
    if (!"PAID".equals(row.get("status"))) {
      JOptionPane.showMessageDialog(this, "该订单已退票，不能重复操作。");
      return;
    }
    if (!Ui.confirm(
        this,
        "订单号："
            + row.get("id")
            + "\n影片："
            + row.get("movieTitle")
            + "\n退款金额：¥ "
            + Ui.money(row.get("totalAmount"))
            + "\n\n确认整单退票并释放全部座位？")) return;
    change(
        "正在提交退票事务…",
        () -> service.refund(((Number) row.get("id")).longValue()),
        () -> {
          JOptionPane.showMessageDialog(this, "退票成功，座位已释放。");
          refreshOrders();
        });
  }

  private JPanel statsPage() {
    JPanel p = page("经营统计", "按场次开场日期统计；净票房扣除整单退款，上座率按有效售出票数计算。");
    JPanel body = Ui.panel(new BorderLayout(0, 18));
    JPanel upper = Ui.panel(new BorderLayout(0, 16));
    upper.add(
        toolbar(
            new JLabel("场次日期"),
            statsFrom,
            new JLabel("至"),
            statsTo,
            Ui.button("查询统计", this::refreshStats)),
        BorderLayout.NORTH);
    JPanel metrics = Ui.panel(new GridLayout(1, 3, 16, 0));
    metrics.add(metric("净票房", revenue));
    metrics.add(metric("有效售出票数", tickets));
    metrics.add(metric("上座率", occupancy));
    upper.add(metrics, BorderLayout.CENTER);
    body.add(upper, BorderLayout.NORTH);
    JPanel table = Ui.card(new BorderLayout(0, 12));
    table.add(Ui.label("影片票房排行", 17, Ui.INK), BorderLayout.NORTH);
    table.add(statsGrid.scroll(), BorderLayout.CENTER);
    body.add(table, BorderLayout.CENTER);
    p.add(body, BorderLayout.CENTER);
    return p;
  }

  private JPanel metric(String title, JLabel value) {
    JPanel p = Ui.card(new GridLayout(2, 1, 0, 10));
    p.add(Ui.label(title, 13, Ui.MUTED));
    p.add(value);
    return p;
  }

  private void refreshStats() {
    try {
      LocalDate[] d = dates(statsFrom, statsTo);
      run(
          "正在汇总经营数据…",
          () -> service.table("stats", d[0], d[1]),
          rows -> {
            statsGrid.set(rows);
            BigDecimal amount = BigDecimal.ZERO;
            long count = 0, capacity = 0;
            for (Map<String, Object> r : rows) {
              amount = amount.add(new BigDecimal(r.get("netRevenue").toString()));
              count += ((Number) r.get("netTickets")).longValue();
              capacity += ((Number) r.get("capacity")).longValue();
            }
            revenue.setText("¥ " + Ui.money(amount));
            tickets.setText(Long.toString(count));
            occupancy.setText(
                String.format(
                    Locale.ROOT, "%.2f %%", capacity == 0 ? 0 : 100.0 * count / capacity));
          });
    } catch (Exception ex) {
      Ui.error(this, new IllegalArgumentException("请输入有效的日期，格式为 yyyy-MM-dd；结束日期不能早于开始日期。"));
    }
  }

  private JPanel adminPage() {
    JPanel p = page("基础资料与排片", "维护影片、影厅及座位，安排场次；已产生订单的场次受到保护。");
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab(
        "影片管理",
        catalog(
            movieGrid,
            Ui.button("新增影片", () -> movieForm(null)),
            Ui.button(
                "编辑影片",
                () -> {
                  Map<String, Object> r = require(movieGrid, "影片");
                  if (r != null) movieForm(r);
                }),
            Ui.button(
                "删除影片",
                () -> delete(movieGrid, "影片", r -> service.deleteMovie(Ui.number(r.get("id")))))));
    tabs.addTab(
        "影厅与座位",
        catalog(
            hallGrid,
            Ui.button("新增影厅", () -> hallForm(null)),
            Ui.button(
                "编辑影厅",
                () -> {
                  Map<String, Object> r = require(hallGrid, "影厅");
                  if (r != null) hallForm(r);
                }),
            Ui.button("座位管理", this::seatManager),
            Ui.button(
                "删除影厅",
                () -> delete(hallGrid, "影厅", r -> service.deleteHall(Ui.number(r.get("id")))))));
    tabs.addTab(
        "场次排片",
        catalog(
            adminShowGrid,
            Ui.button("新增场次", () -> showForm(null)),
            Ui.button(
                "编辑场次",
                () -> {
                  Map<String, Object> r = require(adminShowGrid, "场次");
                  if (r != null) showForm(r);
                }),
            Ui.button(
                "删除场次",
                () ->
                    delete(adminShowGrid, "场次", r -> service.deleteShow(Ui.number(r.get("id")))))));
    p.add(tabs, BorderLayout.CENTER);
    return p;
  }

  private JPanel catalog(Ui.Grid grid, JButton... buttons) {
    JPanel p = Ui.card(new BorderLayout(0, 16));
    JPanel bar = toolbar();
    for (JButton b : buttons) bar.add(b);
    bar.add(Ui.button("刷新", this::refreshAdmin));
    p.add(bar, BorderLayout.NORTH);
    p.add(grid.scroll(), BorderLayout.CENTER);
    return p;
  }

  private void refreshAdmin() {
    run(
        "正在读取基础资料…",
        () -> List.of(service.table("movies"), service.table("halls"), service.table("shows")),
        data -> {
          movieGrid.set(data.get(0));
          hallGrid.set(data.get(1));
          adminShowGrid.set(data.get(2));
        });
  }

  @FunctionalInterface
  private interface RowAction {
    void apply(Map<String, Object> row) throws Exception;
  }

  private void delete(Ui.Grid grid, String kind, RowAction action) {
    Map<String, Object> row = require(grid, kind);
    if (row != null
        && Ui.confirm(this, "确定删除所选" + kind + "（编号 " + row.get("id") + "）？\n存在关联业务记录时，系统将拒绝删除。"))
      change("正在删除…", () -> action.apply(row), this::refreshAdmin);
  }

  private Integer id(Map<String, Object> r) {
    return r == null ? null : Ui.number(r.get("id"));
  }

  private void movieForm(Map<String, Object> row) {
    JTextField title = Ui.field(row == null ? "" : Ui.str(row, "title"), 24);
    JSpinner duration =
        new JSpinner(
            new SpinnerNumberModel(
                row == null ? 120 : Ui.number(row.get("durationMinutes")), 1, 600, 1));
    JCheckBox active = new JCheckBox("允许安排新场次", row == null || Ui.bool(row.get("active")));
    JPanel fields = Ui.form("影片名称", title, "片长（分钟）", duration, "状态", active);
    while (JOptionPane.showConfirmDialog(
            this,
            fields,
            row == null ? "新增影片" : "编辑影片",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      String name = title.getText().trim();
      if (name.isEmpty()) {
        JOptionPane.showMessageDialog(this, "影片名称不能为空。");
        continue;
      }
      change(
          "正在保存影片…",
          () ->
              service.saveMovie(
                  id(row), name, ((Number) duration.getValue()).intValue(), active.isSelected()),
          this::refreshAdmin);
      return;
    }
  }

  private void hallForm(Map<String, Object> row) {
    JTextField name = Ui.field(row == null ? "" : Ui.str(row, "name"), 24);
    JSpinner
        rows =
            new JSpinner(
                new SpinnerNumberModel(row == null ? 6 : Ui.number(row.get("rowCount")), 1, 20, 1)),
        cols =
            new JSpinner(
                new SpinnerNumberModel(row == null ? 8 : Ui.number(row.get("colCount")), 1, 30, 1));
    JPanel fields =
        Ui.form(
            "影厅名称",
            name,
            "排数",
            rows,
            "每排座位数",
            cols,
            "说明",
            Ui.label("已有场次的影厅不能变更座位布局", 12, Ui.MUTED));
    while (JOptionPane.showConfirmDialog(
            this,
            fields,
            row == null ? "新增影厅" : "编辑影厅",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      String value = name.getText().trim();
      if (value.isEmpty()) {
        JOptionPane.showMessageDialog(this, "影厅名称不能为空。");
        continue;
      }
      change(
          "正在保存影厅与座位…",
          () ->
              service.saveHall(
                  id(row),
                  value,
                  ((Number) rows.getValue()).intValue(),
                  ((Number) cols.getValue()).intValue()),
          this::refreshAdmin);
      return;
    }
  }

  private record Choice(int id, String label) {
    public String toString() {
      return label;
    }
  }

  private void showForm(Map<String, Object> row) {
    if (movieGrid.rows.isEmpty() || hallGrid.rows.isEmpty()) {
      JOptionPane.showMessageDialog(this, "请先创建影片和影厅。");
      return;
    }
    JComboBox<Choice> movie = new JComboBox<>(), hall = new JComboBox<>();
    for (Map<String, Object> r : movieGrid.rows)
      movie.addItem(
          new Choice(
              Ui.number(r.get("id")),
              Ui.str(r, "title") + (Ui.bool(r.get("active")) ? "" : "（停用）")));
    for (Map<String, Object> r : hallGrid.rows)
      hall.addItem(new Choice(Ui.number(r.get("id")), Ui.str(r, "name")));
    if (row != null) {
      select(movie, Ui.number(row.get("movieId")));
      select(hall, Ui.number(row.get("hallId")));
    }
    JTextField
        time =
            Ui.field(
                row == null
                    ? Ui.TIME.format(LocalDateTime.now().plusDays(1).withHour(14).withMinute(0))
                    : Ui.TIME.format(Ui.time(row.get("startTime"))),
                24),
        price = Ui.field(row == null ? "39.00" : Ui.money(row.get("price")), 12);
    JSpinner cleanup =
        new JSpinner(
            new SpinnerNumberModel(
                row == null ? 15 : Ui.number(row.get("cleanupMinutes")), 0, 120, 5));
    JPanel fields =
        Ui.form(
            "影片",
            movie,
            "影厅",
            hall,
            "开场时间",
            time,
            "时间格式",
            Ui.label("yyyy-MM-dd HH:mm", 12, Ui.MUTED),
            "票价（元）",
            price,
            "散场间隔（分钟）",
            cleanup);
    while (JOptionPane.showConfirmDialog(
            this,
            fields,
            row == null ? "新增场次" : "编辑场次",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        LocalDateTime start = LocalDateTime.parse(time.getText().trim(), Ui.TIME);
        BigDecimal amount = new BigDecimal(price.getText().trim());
        if (amount.signum() <= 0 || amount.scale() > 2)
          throw new IllegalArgumentException("票价必须大于 0，且最多两位小数。");
        if (!start.isAfter(LocalDateTime.now()))
          throw new IllegalArgumentException("开场时间必须晚于当前时间。");
        Choice m = (Choice) movie.getSelectedItem(), h = (Choice) hall.getSelectedItem();
        change(
            "正在检查排片冲突并保存…",
            () ->
                service.saveShow(
                    id(row),
                    m.id(),
                    h.id(),
                    start,
                    amount,
                    ((Number) cleanup.getValue()).intValue()),
            this::refreshAdmin);
        return;
      } catch (Exception ex) {
        Ui.error(this, ex);
      }
    }
  }

  private void select(JComboBox<Choice> box, int id) {
    for (int i = 0; i < box.getItemCount(); i++)
      if (box.getItemAt(i).id() == id) {
        box.setSelectedIndex(i);
        return;
      }
  }

  private void seatManager() {
    Map<String, Object> hall = require(hallGrid, "影厅");
    if (hall == null) return;
    int hallId = Ui.number(hall.get("id"));
    run(
        "正在读取影厅座位…",
        () -> service.table("seats", hallId),
        rows -> {
          JDialog dialog = new JDialog(this, Ui.str(hall, "name") + " · 座位管理", true);
          Ui.Grid grid =
              new Ui.Grid(new String[] {"编号", "排", "座", "状态"}, "id", "rowNo", "colNo", "active");
          grid.set(rows);
          JPanel p = Ui.card(new BorderLayout(0, 14));
          JButton enable = Ui.button("启用所选座位", () -> setSeat(dialog, grid, true, hallId)),
              disable = Ui.button("停用所选座位", () -> setSeat(dialog, grid, false, hallId));
          p.add(toolbar(enable, disable), BorderLayout.NORTH);
          p.add(grid.scroll(), BorderLayout.CENTER);
          p.add(Ui.label("已有场次时，系统限制座位变更以保护售票与统计。", 12, Ui.MUTED), BorderLayout.SOUTH);
          dialog.setContentPane(p);
          dialog.setSize(620, 560);
          dialog.setLocationRelativeTo(this);
          dialog.setVisible(true);
        });
  }

  private void setSeat(JDialog dialog, Ui.Grid grid, boolean active, int hallId) {
    Map<String, Object> seat = grid.selected();
    if (seat == null) {
      JOptionPane.showMessageDialog(dialog, "请先选择一个座位。");
      return;
    }
    Ui.worker(
        dialog,
        () -> {
          service.setSeatActive(Ui.number(seat.get("id")), active);
          return service.table("seats", hallId);
        },
        grid::set);
  }

  private JPanel logPage() {
    JPanel p = page("订单状态日志", "查看订单创建及退票的状态变更记录，便于追踪业务操作。");
    JPanel body = Ui.card(new BorderLayout(0, 16));
    body.add(toolbar(Ui.button("刷新日志", this::refreshLogs)), BorderLayout.NORTH);
    body.add(logGrid.scroll(), BorderLayout.CENTER);
    p.add(body, BorderLayout.CENTER);
    return p;
  }

  private void refreshLogs() {
    run("正在读取状态日志…", () -> service.table("logs"), logGrid::set);
  }
}
