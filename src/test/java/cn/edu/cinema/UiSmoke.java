package cn.edu.cinema;

import cn.edu.cinema.service.CinemaService;
import cn.edu.cinema.ui.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real Swing views connected using the restricted application database account. */
public final class UiSmoke {
  private static MainFrame frame;

  private static Object field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(object);
  }

  private static void idle() throws Exception {
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
    throw new AssertionError("UI operation timed out");
  }

  private static void snapshot(JFrame window, String name) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        BufferedImage image =
            new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        window.paintAll(g);
        g.dispose();
        ImageIO.write(image, "png", Path.of("build/screenshots/" + name + ".png").toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  public static void main(String[] args) throws Exception {
    Files.createDirectories(Path.of("build/screenshots"));
    try {
      LoginFrame[] login = {null};
      SwingUtilities.invokeAndWait(() -> {
        login[0] = new LoginFrame();
        login[0].setVisible(true);
      });
      snapshot(login[0], "login");
      SwingUtilities.invokeAndWait(() -> login[0].dispose());
      CinemaService admin = CinemaService.login("admin", "Cinema@2026".toCharArray());
      SwingUtilities.invokeAndWait(() -> {
        frame = new MainFrame(admin);
        frame.setVisible(true);
      });
      idle();
      SwingUtilities.invokeAndWait(() -> {
        try {
          Object grid = field(frame, "showGrid");
          JTable table = (JTable) field(grid, "table");
          if (table.getRowCount() == 0) throw new AssertionError("Expected future demo shows");
          table.setRowSelectionInterval(0, 0);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      idle();
      SwingUtilities.invokeAndWait(() -> {
        try {
          JPanel seats = (JPanel) field(frame, "seatGrid");
          int count = 0;
          for (Component c : seats.getComponents())
            if (c instanceof JToggleButton b && b.isEnabled()) {
              b.doClick();
              if (++count == 2) break;
            }
          if (count != 2) throw new AssertionError("Expected two available seats");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      snapshot(frame, "ticketing");
      for (String page : new String[] {"orders", "stats", "admin", "logs"}) {
        SwingUtilities.invokeAndWait(() -> {
          try {
            Method m = MainFrame.class.getDeclaredMethod("navigate", String.class);
            m.setAccessible(true);
            m.invoke(frame, page);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        idle();
        snapshot(frame, page);
      }
      System.out.println("PASS real application-account login and 6 Swing view captures");
    } finally {
      SwingUtilities.invokeAndWait(() -> {
        for (Window w : Window.getWindows()) w.dispose();
      });
    }
  }
}
