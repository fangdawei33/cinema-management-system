package cn.edu.cinema;

import cn.edu.cinema.ui.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Reader;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Exercises the actual register button and dialog, including automatic customer navigation. */
public final class CustomerUiSmoke {
  private static final String username = "ui_" + UUID.randomUUID().toString().substring(0, 8);

  private static Object field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(object);
  }

  private static <T extends Window> T find(Class<T> type) throws Exception {
    Object[] found = {null};
    SwingUtilities.invokeAndWait(() -> {
      for (Window w : Window.getWindows()) if (type.isInstance(w) && w.isShowing()) found[0] = w;
    });
    return type.cast(found[0]);
  }

  private static <T extends Window> T awaitWindow(Class<T> type) throws Exception {
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      T w = find(type);
      if (w != null) return w;
      Thread.sleep(100);
    }
    throw new AssertionError("Expected visible window " + type.getSimpleName());
  }

  private static void idle(MainFrame frame) throws Exception {
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      boolean[] busy = {true};
      SwingUtilities.invokeAndWait(() -> {
        try {
          busy[0] = (boolean) field(frame, "busy");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      if (!busy[0]) {
        Thread.sleep(200);
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Database loading did not finish");
  }

  private static void capture(Window window, String file) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        BufferedImage image =
            new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        window.paintAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", Path.of("build/screenshots/" + file + ".png").toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  private static void cleanup() throws Exception {
    Properties config = new Properties();
    try (Reader reader =
        Files.newBufferedReader(Path.of(".tools/test.properties"), StandardCharsets.UTF_8)) {
      config.load(reader);
    }
    try (Connection c =
            DriverManager.getConnection(
                config.getProperty("db.url"),
                config.getProperty("db.user"),
                config.getProperty("db.password"));
        PreparedStatement ps =
            c.prepareStatement(
                "DELETE FROM dbo.AppUsers WHERE Username=? AND Role='CUSTOMER' AND NOT"
                    + " EXISTS(SELECT 1 FROM dbo.Orders WHERE UserId=AppUsers.Id)")) {
      ps.setString(1, username);
      ps.executeUpdate();
    }
  }

  public static void main(String[] args) throws Exception {
    Files.createDirectories(Path.of("build/screenshots"));
    try {
      LoginFrame[] login = {null};
      SwingUtilities.invokeAndWait(() -> {
        login[0] = new LoginFrame();
        login[0].setVisible(true);
      });
      capture(login[0], "customer-login");
      // invokeLater allows the modal dialog to run its nested event loop.
      SwingUtilities.invokeLater(() -> {
        try {
          ((JButton) field(login[0], "register")).doClick();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      RegisterDialog dialog = awaitWindow(RegisterDialog.class);
      capture(dialog, "customer-register");
      SwingUtilities.invokeAndWait(() -> {
        try {
          ((JTextField) field(dialog, "username")).setText(username);
          ((JTextField) field(dialog, "displayName")).setText("观影体验用户");
          ((JPasswordField) field(dialog, "password")).setText("UiCustomer@2026");
          ((JPasswordField) field(dialog, "confirmation")).setText("UiCustomer@2026");
          ((JButton) field(dialog, "submit")).doClick();
          if (((JButton) field(dialog, "submit")).isEnabled())
            throw new AssertionError("Register submit must disable while busy");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      MainFrame frame = awaitWindow(MainFrame.class);
      idle(frame);
      SwingUtilities.invokeAndWait(() -> {
        try {
          if (login[0].isDisplayable() || dialog.isDisplayable())
            throw new AssertionError("Registration did not close login and dialog");
          Map<?, ?> nav = (Map<?, ?>) field(frame, "navButtons");
          if (!nav.keySet().equals(Set.of("ticket", "orders")))
            throw new AssertionError("Customer has staff navigation");
          if (((JPanel) field(frame, "content")).getComponentCount() != 2)
            throw new AssertionError("Customer has staff content panels");
          Object grid = field(frame, "showGrid");
          JTable table = (JTable) field(grid, "table");
          if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      idle(frame);
      capture(frame, "customer-ticketing");
      SwingUtilities.invokeAndWait(() -> {
        try {
          Method nav = MainFrame.class.getDeclaredMethod("navigate", String.class);
          nav.setAccessible(true);
          nav.invoke(frame, "orders");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      idle(frame);
      capture(frame, "customer-orders");
      SwingUtilities.invokeAndWait(() -> {
        try {
          Object grid = field(frame, "orderGrid");
          JTable table = (JTable) field(grid, "table");
          if (table.getRowCount() != 0)
            throw new AssertionError("New customer sees someone else's orders");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      System.out.println(
          "PASS actual registration dialog, duplicate-submit prevention, automatic login,"
              + " customer-only navigation, and private empty orders");
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        for (Window w : Window.getWindows()) w.dispose();
      });
      cleanup();
    }
  }
}
