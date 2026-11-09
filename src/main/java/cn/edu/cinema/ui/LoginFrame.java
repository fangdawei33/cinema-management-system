package cn.edu.cinema.ui;

import cn.edu.cinema.service.CinemaService;
import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public final class LoginFrame extends JFrame {
  private final JTextField username = Ui.field("", 22);
  private final JPasswordField password = new JPasswordField(22);
  private final JButton submit = Ui.primary("登录系统", this::login);
  private final JButton register = Ui.button("没有账号？注册普通用户", this::register);
  private boolean busy;

  public LoginFrame() {
    super("影院排片与售票 · 登录");
    Ui.setup();
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setSize(860, 570);
    setMinimumSize(new Dimension(760, 540));
    setLocationRelativeTo(null);
    JPanel base = new JPanel(new GridLayout(1, 2));
    base.setBackground(Color.WHITE);
    JPanel brand = new JPanel();
    brand.setBackground(Ui.NAVY);
    brand.setBorder(new EmptyBorder(55, 36, 45, 30));
    brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
    brand.add(Ui.label("CINEMA  /  影院购票", 17, new Color(142, 179, 240)));
    brand.add(Box.createVerticalStrut(48));
    brand.add(Ui.label("让每一场放映", 29, Color.WHITE));
    brand.add(Box.createVerticalStrut(10));
    brand.add(Ui.label("井然有序", 29, Color.WHITE));
    brand.add(Box.createVerticalGlue());
    brand.add(Ui.label("浏览场次 · 自主选座 · 我的订单", 14, new Color(180, 196, 219)));
    brand.add(Box.createVerticalStrut(12));
    brand.add(Ui.label("数据库原理课程设计", 12, new Color(132, 153, 180)));
    base.add(brand);
    JPanel right = Ui.card(new BorderLayout());
    right.setBorder(new EmptyBorder(48, 30, 36, 30));
    JPanel middle = Ui.panel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(8, 0, 8, 0);
    c.gridy = 0;
    middle.add(Ui.heading("欢迎回来"), c);
    c.gridy++;
    middle.add(Ui.label("普通用户、售票员与管理员均可登录", 13, Ui.MUTED), c);
    c.gridy++;
    middle.add(new JLabel("账号"), c);
    c.gridy++;
    middle.add(username, c);
    c.gridy++;
    middle.add(new JLabel("密码"), c);
    c.gridy++;
    password.setMargin(new Insets(7, 8, 7, 8));
    middle.add(password, c);
    c.gridy++;
    c.insets = new Insets(20, 0, 8, 0);
    middle.add(submit, c);
    c.gridy++;
    c.insets = new Insets(4, 0, 8, 0);
    middle.add(register, c);
    right.add(middle, BorderLayout.CENTER);
    right.add(Ui.label("本系统付款为模拟操作", 12, Ui.MUTED), BorderLayout.SOUTH);
    base.add(right);
    setContentPane(base);
    getRootPane().setDefaultButton(submit);
  }

  private void login() {
    if (busy) return;
    String name = username.getText().trim();
    char[] secret = password.getPassword();
    if (name.isEmpty() || secret.length == 0) {
      Arrays.fill(secret, '\0');
      JOptionPane.showMessageDialog(this, "请输入账号和密码。");
      return;
    }
    setBusy(true);
    new SwingWorker<CinemaService, Void>() {
      protected CinemaService doInBackground() throws Exception {
        try {
          return CinemaService.login(name, secret);
        } finally {
          Arrays.fill(secret, '\0');
        }
      }

      protected void done() {
        setBusy(false);
        password.setText("");
        try {
          openSession(get());
        } catch (Exception ex) {
          Ui.error(LoginFrame.this, ex);
        }
      }
    }.execute();
  }

  private void setBusy(boolean value) {
    busy = value;
    submit.setEnabled(!value);
    register.setEnabled(!value);
    username.setEnabled(!value);
    password.setEnabled(!value);
  }

  private void register() {
    if (!busy) new RegisterDialog(this, this::openSession).setVisible(true);
  }

  private void openSession(CinemaService session) {
    MainFrame frame = new MainFrame(session);
    frame.setVisible(true);
    dispose();
  }
}
