package cn.edu.cinema;

import cn.edu.cinema.ui.LoginFrame;
import java.awt.Font;
import javax.swing.*;

public final class App {
  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
      }
      Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
      for (Object key : UIManager.getDefaults().keySet())
        if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
          UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
      new LoginFrame().setVisible(true);
    });
  }
}
