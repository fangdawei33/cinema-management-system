package cn.edu.cinema.ui;

import cn.edu.cinema.service.CinemaService;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Self registration creates a customer account only; roles are never chosen in the client. */
public final class RegisterDialog extends JDialog {
  private final JTextField username = Ui.field("", 24);
  private final JTextField displayName = Ui.field("", 24);
  private final JPasswordField password = new JPasswordField(24);
  private final JPasswordField confirmation = new JPasswordField(24);
  private final JButton submit = Ui.primary("注册并进入购票", this::submit);
  private final JButton cancel = Ui.button("返回登录", this::dispose);
  private final Consumer<CinemaService> onSuccess;
  private boolean busy;

  public RegisterDialog(JFrame owner, Consumer<CinemaService> onSuccess) {
    super(owner, "注册普通用户", true);
    this.onSuccess = onSuccess;
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (!busy) dispose();
          }
        });
    JPanel panel = Ui.card(new BorderLayout(0, 18));
    panel.setBorder(new EmptyBorder(28, 32, 26, 32));
    JPanel header = Ui.panel(new GridLayout(2, 1, 0, 10));
    header.add(Ui.heading("创建你的购票账号"));
    header.add(Ui.label("注册后即可选座购票，查看与退还自己的订单。", 13, Ui.MUTED));
    panel.add(header, BorderLayout.NORTH);
    password.setMargin(new Insets(6, 8, 6, 8));
    confirmation.setMargin(new Insets(6, 8, 6, 8));
    panel.add(
        Ui.form(
            "登录账号", username,
            "账号规则", Ui.label("3–30 位英文字母、数字或下划线", 12, Ui.MUTED),
            "显示姓名", displayName,
            "姓名规则", Ui.label("1–50 个字符，用于订单展示", 12, Ui.MUTED),
            "设置密码", password,
            "密码长度", Ui.label("10–128 位，请勿使用他处重要账号的密码", 12, Ui.MUTED),
            "确认密码", confirmation),
        BorderLayout.CENTER);
    JPanel actions = Ui.panel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actions.add(cancel);
    actions.add(submit);
    panel.add(actions, BorderLayout.SOUTH);
    setContentPane(panel);
    getRootPane().setDefaultButton(submit);
    pack();
    setMinimumSize(getSize());
    setLocationRelativeTo(owner);
  }

  private void submit() {
    if (busy) return;
    String name = username.getText().trim();
    String display = displayName.getText().trim();
    char[] secret = password.getPassword();
    char[] repeated = confirmation.getPassword();
    String problem = null;
    if (!name.matches("[A-Za-z0-9_]{3,30}")) problem = "账号应为 3–30 位英文字母、数字或下划线。";
    else if (display.isEmpty() || display.length() > 50) problem = "显示姓名应为 1–50 个字符。";
    else if (secret.length < 10 || secret.length > 128) problem = "密码长度应为 10–128 位。";
    else if (!Arrays.equals(secret, repeated)) problem = "两次输入的密码不一致。";
    Arrays.fill(repeated, '\0');
    if (problem != null) {
      Arrays.fill(secret, '\0');
      JOptionPane.showMessageDialog(this, problem, "请检查注册信息", JOptionPane.WARNING_MESSAGE);
      return;
    }
    setBusy(true);
    new SwingWorker<CinemaService, Void>() {
      @Override
      protected CinemaService doInBackground() throws Exception {
        try {
          return CinemaService.register(name, display, secret);
        } finally {
          Arrays.fill(secret, '\0');
        }
      }

      @Override
      protected void done() {
        setBusy(false);
        password.setText("");
        confirmation.setText("");
        try {
          CinemaService session = get();
          dispose();
          onSuccess.accept(session);
        } catch (Exception ex) {
          Ui.error(RegisterDialog.this, ex);
        }
      }
    }.execute();
  }

  private void setBusy(boolean value) {
    busy = value;
    username.setEnabled(!value);
    displayName.setEnabled(!value);
    password.setEnabled(!value);
    confirmation.setEnabled(!value);
    submit.setEnabled(!value);
    cancel.setEnabled(!value);
    submit.setText(value ? "正在创建账号…" : "注册并进入购票");
  }
}
