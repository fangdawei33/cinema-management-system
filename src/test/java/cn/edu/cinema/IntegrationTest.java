package cn.edu.cinema;

import cn.edu.cinema.service.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs against real SQL Server; creates a uniquely named fixture and removes only that fixture. */
public final class IntegrationTest {
  private static int passed;
  private static int movieId, hallId;
  private static final String tag = "IT-" + UUID.randomUUID().toString().substring(0, 8);
  private static CinemaService admin, cashier;

  @FunctionalInterface
  interface Work {
    void run() throws Exception;
  }

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    passed++;
    System.out.println("PASS " + name);
  }

  private static void rejects(String name, Work work) throws Exception {
    try {
      work.run();
    } catch (java.sql.SQLException | IllegalArgumentException | SecurityException expected) {
      check(true, name);
      return;
    }
    throw new AssertionError("Expected rejection: " + name);
  }

  private static Object scalar(String sql, Object... args) throws Exception {
    try (Connection c = Database.open();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1);
      }
    }
  }

  private static void execute(String sql, Object... args) throws Exception {
    try (Connection c = Database.open();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
  }

  private static int showAt(LocalDateTime time) throws Exception {
    admin.saveShow(null, movieId, hallId, time, new BigDecimal("40.00"), 15);
    return number(
        scalar(
            "SELECT Id FROM dbo.Shows WHERE HallId=? AND StartTime=?",
            hallId,
            Timestamp.valueOf(time)));
  }

  public static void main(String[] args) throws Exception {
    try {
      admin = CinemaService.login("admin", "Cinema@2026".toCharArray());
      cashier = CinemaService.login("cashier", "Cinema@2026".toCharArray());
      check(admin.user().isAdmin() && !cashier.user().isAdmin(), "role login");
      rejects("wrong password", () -> CinemaService.login("admin", "wrong".toCharArray()));
      rejects(
          "parameterized login injection",
          () -> CinemaService.login("' OR 1=1 --", "Cinema@2026".toCharArray()));
      check(
          Passwords.verify(
              "long-test-pass".toCharArray(), Passwords.hash("long-test-pass".toCharArray())),
          "PBKDF2 roundtrip");
      rejects("cashier cannot create movies", () -> cashier.saveMovie(null, tag, 90, true));
      rejects("cashier cannot read audit logs", () -> cashier.table("logs"));
      admin.saveMovie(null, tag, 90, true);
      movieId = number(scalar("SELECT Id FROM dbo.Movies WHERE Title=?", tag));
      admin.saveMovie(movieId, tag, 100, true);
      check(
          number(scalar("SELECT DurationMinutes FROM dbo.Movies WHERE Id=?", movieId)) == 100,
          "movie edit");
      admin.saveHall(null, tag, 2, 3);
      hallId = number(scalar("SELECT Id FROM dbo.Halls WHERE Name=?", tag));
      check(admin.table("seats", hallId).size() == 6, "hall generates seats");
      List<Map<String, Object>> seats = admin.table("seats", hallId);
      int seat1 = number(seats.get(0).get("id")),
          seat2 = number(seats.get(1).get("id")),
          seat3 = number(seats.get(2).get("id")),
          seat4 = number(seats.get(3).get("id"));
      int disabled = number(seats.get(5).get("id"));
      admin.setSeatActive(disabled, false);
      LocalDateTime tomorrow =
          ((Timestamp)
                  scalar("SELECT DATEADD(day,2,CAST(CAST(SYSDATETIME() AS date) AS datetime2))"))
              .toLocalDateTime();
      int show1 = showAt(tomorrow.withHour(10));
      rejects("overlap including cleanup", () -> showAt(tomorrow.withHour(11).withMinute(50)));
      int show2 = showAt(tomorrow.withHour(11).withMinute(55));
      check(show2 > 0, "exact cleanup boundary allowed");
      rejects("past schedule", () -> showAt(tomorrow.minusDays(4)));
      rejects("layout frozen after scheduling", () -> admin.saveHall(hallId, tag, 3, 3));
      rejects("seat capacity frozen after scheduling", () -> admin.setSeatActive(disabled, true));
      rejects(
          "cashier schedule procedure role",
          () -> execute("EXEC dbo.usp_DeleteShow ?,?", cashier.user().id(), show2));
      rejects("disabled seat", () -> cashier.sell(show1, List.of(disabled)));
      int foreign = number(scalar("SELECT TOP(1) Id FROM dbo.Seats WHERE HallId<>?", hallId));
      rejects("seat from other hall", () -> cashier.sell(show1, List.of(foreign)));
      rejects("empty seat list", () -> cashier.sell(show1, List.of()));
      rejects("duplicate seat list", () -> cashier.sell(show1, List.of(seat1, seat1)));
      rejects(
          "SQL duplicate seat list",
          () ->
              execute(
                  "EXEC dbo.usp_SellTickets ?,?,?",
                  cashier.user().id(),
                  show1,
                  seat1 + "," + seat1));
      // Two independent JDBC connections contend for one seat using the same start barrier.
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      List<Future<Long>> attempts = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        attempts.add(
            pool.submit(() -> {
              start.await();
              try {
                return cashier.sell(show1, List.of(seat1));
              } catch (SQLException e) {
                if (e.getErrorCode() != 51027) throw e;
                return -1L;
              }
            }));
      start.countDown();
      int wins = 0;
      long winningOrder = 0;
      try {
        for (Future<Long> f : attempts) {
          long result = f.get(30, TimeUnit.SECONDS);
          if (result > 0) {
            wins++;
            winningOrder = result;
          }
        }
      } finally {
        pool.shutdownNow();
      }
      check(wins == 1, "two concurrent buyers exactly one winner");
      check(
          number(
                  scalar(
                      "SELECT COUNT(*) FROM dbo.Tickets WHERE ShowId=? AND SeatId=? AND Active=1",
                      show1,
                      seat1))
              == 1,
          "unique active seat invariant");
      rejects("multi-seat failure is atomic", () -> cashier.sell(show1, List.of(seat1, seat2)));
      check(
          number(
                  scalar(
                      "SELECT COUNT(*) FROM dbo.Tickets WHERE ShowId=? AND SeatId=?", show1, seat2))
              == 0,
          "failed order leaves no partial tickets");
      rejects(
          "sold show cannot be edited",
          () ->
              admin.saveShow(
                  show1, movieId, hallId, tomorrow.withHour(10), new BigDecimal("50"), 15));
      rejects("sold show cannot be deleted", () -> admin.deleteShow(show1));
      cashier.refund(winningOrder);
      long oldOrder = winningOrder;
      rejects("repeat refund rejected", () -> cashier.refund(oldOrder));
      check(
          number(scalar("SELECT COUNT(*) FROM dbo.Tickets WHERE OrderId=? AND Active=1", oldOrder))
              == 0,
          "refund frees every seat");
      long resale = cashier.sell(show1, List.of(seat1, seat2));
      check(
          new BigDecimal(scalar("SELECT TotalAmount FROM dbo.Orders WHERE Id=?", resale).toString())
                  .compareTo(new BigDecimal("80"))
              == 0,
          "multi-seat total exact");
      long other = cashier.sell(show2, List.of(seat1));
      check(other > 0, "same physical seat can sell in another show");
      List<Map<String, Object>> stats =
          admin.table("stats", tomorrow.toLocalDate(), tomorrow.toLocalDate());
      Map<String, Object> item =
          stats.stream().filter(r -> tag.equals(r.get("movieTitle"))).findFirst().orElseThrow();
      check(
          number(item.get("soldCount")) == 4
              && number(item.get("refundCount")) == 1
              && number(item.get("netTickets")) == 3,
          "stats ticket and refund counts");
      check(
          new BigDecimal(item.get("netRevenue").toString()).compareTo(new BigDecimal("120")) == 0,
          "net revenue subtracts refunds");
      check(
          number(item.get("capacity")) == 10
              && new BigDecimal(item.get("occupancy").toString()).compareTo(new BigDecimal("30"))
                  == 0,
          "weighted occupancy excludes disabled seats");
      check(
          number(scalar("SELECT COUNT(*) FROM dbo.OrderLogs WHERE OrderId=?", oldOrder)) == 2,
          "insert and refund trigger logs");
      check(
          admin.table("orders", LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)).stream()
              .anyMatch(r -> ((Number) r.get("id")).longValue() == resale),
          "order query and seat names");
      check(
          admin.table("showSeats", show1).stream()
                  .filter(r -> Boolean.TRUE.equals(r.get("sold")))
                  .count()
              == 2,
          "seat map reflects current sales");
      // Fixture-only clock transition simulates a show having started without waiting for real
      // time.
      execute(
          "UPDATE dbo.Shows SET"
              + " StartTime=DATEADD(minute,-5,SYSDATETIME()),EndTime=DATEADD(minute,95,SYSDATETIME())"
              + " WHERE Id=?",
          show2);
      rejects("no sales after start", () -> cashier.sell(show2, List.of(seat3)));
      rejects("no refunds after start", () -> cashier.refund(other));
      // Simultaneous refunds: exactly one must succeed, with one transition log.
      long refundOrder = cashier.sell(show1, List.of(seat3, seat4));
      ExecutorService refunds = Executors.newFixedThreadPool(2);
      CountDownLatch refundGate = new CountDownLatch(1);
      Callable<Boolean> refundCall =
          () -> {
            refundGate.await();
            try {
              cashier.refund(refundOrder);
              return true;
            } catch (SQLException e) {
              if (e.getErrorCode() != 51029) throw e;
              return false;
            }
          };
      Future<Boolean> a = refunds.submit(refundCall), b = refunds.submit(refundCall);
      refundGate.countDown();
      try {
        check(
            a.get(30, TimeUnit.SECONDS) ^ b.get(30, TimeUnit.SECONDS),
            "concurrent refunds exactly one winner");
      } finally {
        refunds.shutdownNow();
      }
      check(
          number(scalar("SELECT COUNT(*) FROM dbo.OrderLogs WHERE OrderId=?", refundOrder)) == 2,
          "concurrent refunds log once");
      int disposable = showAt(tomorrow.withHour(18));
      admin.deleteShow(disposable);
      check(
          number(scalar("SELECT COUNT(*) FROM dbo.Shows WHERE Id=?", disposable)) == 0,
          "delete unused future show");
      check(
          admin.table("movies").size() >= 4
              && admin.table("halls").size() >= 3
              && !admin.table("logs").isEmpty(),
          "all admin list queries");
      if (java.nio.file.Files.exists(java.nio.file.Path.of(".tools/test.properties"))) {
        String testConfig = System.getProperty("cinema.config");
        try {
          System.clearProperty("cinema.config");
          CinemaService appAdmin = CinemaService.login("admin", "Cinema@2026".toCharArray());
          CinemaService appCashier = CinemaService.login("cashier", "Cinema@2026".toCharArray());
          rejects(
              "app SQL account cannot update orders directly",
              () -> execute("UPDATE dbo.Orders SET TotalAmount=1 WHERE 1=0"));
          rejects(
              "app SQL account cannot update roles directly",
              () -> execute("UPDATE dbo.AppUsers SET Role='ADMIN' WHERE 1=0"));
          appAdmin.saveShow(null, movieId, hallId, tomorrow.withHour(20), new BigDecimal("40"), 15);
          int appShow = number(scalar("SELECT MAX(Id) FROM dbo.Shows WHERE HallId=?", hallId));
          check(appShow > 0, "restricted SQL account can schedule via procedure");
          long appOrder = appCashier.sell(appShow, List.of(seat1, seat2));
          check(appOrder > 0, "restricted SQL account can sell via procedure");
          appCashier.refund(appOrder);
          check(
              number(
                      scalar(
                          "SELECT COUNT(*) FROM dbo.Tickets WHERE OrderId=? AND Active=1",
                          appOrder))
                  == 0,
              "restricted SQL account can refund via procedure");
          check(
              number(scalar("SELECT COUNT(*) FROM dbo.OrderLogs WHERE OrderId=?", appOrder)) == 2,
              "restricted SQL account trigger writes through ownership chain");
        } finally {
          if (testConfig == null) System.clearProperty("cinema.config");
          else System.setProperty("cinema.config", testConfig);
        }
      }
      System.out.println("RESULT: " + passed + " checks passed against SQL Server.");
    } finally {
      cleanup();
    }
  }

  private static void cleanup() throws Exception {
    if (hallId != 0) {
      try (Connection c = Database.open()) {
        c.setAutoCommit(false);
        String[] commands = {
          "DELETE l FROM dbo.OrderLogs l JOIN dbo.Orders o ON o.Id=l.OrderId JOIN dbo.Shows s ON"
              + " s.Id=o.ShowId WHERE s.HallId=?",
          "DELETE t FROM dbo.Tickets t WHERE t.HallId=?",
          "DELETE o FROM dbo.Orders o JOIN dbo.Shows s ON s.Id=o.ShowId WHERE s.HallId=?",
          "DELETE FROM dbo.Shows WHERE HallId=?",
          "DELETE FROM dbo.Seats WHERE HallId=?",
          "DELETE FROM dbo.Halls WHERE Id=?"
        };
        try {
          for (String sql : commands)
            try (PreparedStatement ps = c.prepareStatement(sql)) {
              ps.setInt(1, hallId);
              ps.executeUpdate();
            }
          c.commit();
        } catch (Exception e) {
          c.rollback();
          throw e;
        }
      }
    }
    if (movieId != 0) execute("DELETE FROM dbo.Movies WHERE Id=? AND Title=?", movieId, tag);
    System.out.println("Fixture removed: " + tag);
  }
}
