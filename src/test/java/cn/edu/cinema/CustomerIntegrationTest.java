package cn.edu.cinema;

import cn.edu.cinema.service.CinemaService;
import cn.edu.cinema.service.Database;
import cn.edu.cinema.service.Passwords;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Customer registration, ownership and role regression against the real application SQL account.
 * Fixture inspection and cleanup use a separate owner connection, never a global config switch
 * during concurrent work. Only uniquely named fixtures created by this run are removed.
 */
public final class CustomerIntegrationTest {
  private static final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private static final String tag = "IT-CUSTOMER-" + suffix;
  private static final String usernameA = "ct_" + suffix + "_a";
  private static final String usernameB = "ct_" + suffix + "_b";
  private static final String usernameRace = "ct_" + suffix + "_race";
  private static final String password = "Customer@2026";
  private static final String newPassword = "CustomerChanged@2026";
  private static final Set<String> fixtureUsernames = new HashSet<>();
  private static final List<Integer> fixtureMovieIds = new ArrayList<>();
  private static int hallId;
  private static int passed;

  @FunctionalInterface
  private interface Work {
    void run() throws Exception;
  }

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    passed++;
    System.out.println("PASS " + name);
  }

  private static void rejects(String name, Predicate<Exception> expected, Work work)
      throws Exception {
    try {
      work.run();
    } catch (Exception exception) {
      if (!expected.test(exception)) {
        throw new AssertionError("Unexpected failure for " + name, exception);
      }
      check(true, name);
      return;
    }
    throw new AssertionError("Expected rejection: " + name);
  }

  private static boolean duplicateUsername(Exception exception) {
    return exception instanceof SQLException sql && sql.getErrorCode() == 51102;
  }

  private static boolean deniedOrder(Exception exception) {
    return exception instanceof SQLException sql && sql.getErrorCode() == 51031;
  }

  private static Connection ownerConnection() throws Exception {
    Properties properties = new Properties();
    try (Reader reader =
        Files.newBufferedReader(Path.of(".tools/test.properties"), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    Properties credentials = new Properties();
    credentials.setProperty("user", properties.getProperty("db.user"));
    credentials.setProperty("password", properties.getProperty("db.password"));
    credentials.setProperty("loginTimeout", "8");
    credentials.setProperty("socketTimeout", "20000");
    return DriverManager.getConnection(properties.getProperty("db.url"), credentials);
  }

  private static Object scalar(String sql, Object... values) throws Exception {
    try (Connection connection = ownerConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new AssertionError("Expected a scalar result");
        return result.getObject(1);
      }
    }
  }

  private static void executeOwner(String sql, Object... values) throws Exception {
    try (Connection connection = ownerConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      statement.executeUpdate();
    }
  }

  private static void executeApp(String sql, Object... values) throws Exception {
    try (Connection connection = Database.open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, values);
      statement.execute();
    }
  }

  private static void bind(PreparedStatement statement, Object... values) throws SQLException {
    statement.setQueryTimeout(20);
    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
  }

  private static Set<Long> orderIds(CinemaService service, LocalDate today) throws Exception {
    Set<Long> ids = new HashSet<>();
    for (Map<String, Object> row : service.table("orders", today.minusDays(1), today.plusDays(1))) {
      ids.add(((Number) row.get("id")).longValue());
    }
    return ids;
  }

  public static void main(String[] args) throws Exception {
    String originalConfig = System.getProperty("cinema.config");
    // Always exercise registration and purchases with the restricted runtime SQL credentials.
    System.setProperty("cinema.config", "config/application.properties");
    fixtureUsernames.add(usernameA);
    fixtureUsernames.add(usernameB);
    fixtureUsernames.add(usernameRace);
    try {
      CinemaService admin = CinemaService.login("admin", "Cinema@2026".toCharArray());
      CinemaService cashier = CinemaService.login("cashier", "Cinema@2026".toCharArray());
      rejects(
          "reject invalid registration username",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.register("x!", "测试用户", password.toCharArray()));
      rejects(
          "reject short registration password",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.register(usernameA, "测试用户", "short".toCharArray()));
      rejects(
          "reject overlong registration password",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.register(usernameA, "测试用户", "x".repeat(129).toCharArray()));
      rejects(
          "reject blank registration display name",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.register(usernameA, "  ", password.toCharArray()));
      rejects(
          "reject overlong registration display name",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.register(usernameA, "人".repeat(51), password.toCharArray()));

      CinemaService customerA = CinemaService.register(usernameA, "测试观众甲", password.toCharArray());
      CinemaService customerB = CinemaService.register(usernameB, "测试观众乙", password.toCharArray());
      check(
          customerA.user().isCustomer()
              && !customerA.user().isStaff()
              && !customerA.user().isAdmin(),
          "registration only creates CUSTOMER role");
      check(
          customerB.user().id() != customerA.user().id(),
          "registration creates distinct identities");
      check(
          "CUSTOMER"
              .equals(scalar("SELECT Role FROM dbo.AppUsers WHERE Id=?", customerA.user().id())),
          "registered customer role persists in database");
      rejects(
          "duplicate username rejected case insensitively",
          CustomerIntegrationTest::duplicateUsername,
          () ->
              CinemaService.register(
                  usernameA.toUpperCase(Locale.ROOT), "重复用户", password.toCharArray()));
      String hashA =
          scalar("SELECT PasswordHash FROM dbo.AppUsers WHERE Id=?", customerA.user().id())
              .toString();
      String hashB =
          scalar("SELECT PasswordHash FROM dbo.AppUsers WHERE Id=?", customerB.user().id())
              .toString();
      check(
          !hashA.equals(password)
              && hashA.startsWith("pbkdf2:")
              && Passwords.verify(password.toCharArray(), hashA),
          "registration stores PBKDF2 hash rather than plaintext");
      check(!hashA.equals(hashB), "same registration password gets different salts");
      check(
          CinemaService.login(usernameA, password.toCharArray()).user().id()
              == customerA.user().id(),
          "new customer can log in");
      customerA.changePassword(password.toCharArray(), newPassword.toCharArray());
      rejects(
          "old customer password rejected after change",
          e -> e instanceof IllegalArgumentException,
          () -> CinemaService.login(usernameA, password.toCharArray()));
      check(
          CinemaService.login(usernameA, newPassword.toCharArray()).user().isCustomer(),
          "changed customer password permits login");

      admin.saveMovie(null, tag, 90, true);
      int movieId = number(scalar("SELECT Id FROM dbo.Movies WHERE Title=?", tag));
      fixtureMovieIds.add(movieId);
      admin.saveMovie(null, tag + "-inactive", 90, false);
      int inactiveMovie =
          number(scalar("SELECT Id FROM dbo.Movies WHERE Title=?", tag + "-inactive"));
      fixtureMovieIds.add(inactiveMovie);
      admin.saveHall(null, tag, 2, 3);
      hallId = number(scalar("SELECT Id FROM dbo.Halls WHERE Name=?", tag));
      LocalDateTime future =
          ((Timestamp)
                  scalar("SELECT DATEADD(day,3,CAST(CAST(SYSDATETIME() AS date) AS datetime2))"))
              .toLocalDateTime()
              .withHour(10);
      LocalDate today =
          ((Timestamp) scalar("SELECT SYSDATETIME()")).toLocalDateTime().toLocalDate();
      admin.saveShow(null, movieId, hallId, future, new BigDecimal("30.00"), 15);
      int showId =
          number(
              scalar(
                  "SELECT Id FROM dbo.Shows WHERE HallId=? AND StartTime=?",
                  hallId,
                  Timestamp.valueOf(future)));
      admin.saveShow(null, movieId, hallId, future.plusHours(4), new BigDecimal("30.00"), 15);
      int pastShow =
          number(
              scalar(
                  "SELECT Id FROM dbo.Shows WHERE HallId=? AND StartTime=?",
                  hallId,
                  Timestamp.valueOf(future.plusHours(4))));
      executeOwner(
          "UPDATE dbo.Shows SET"
              + " StartTime=DATEADD(hour,-2,SYSDATETIME()),EndTime=DATEADD(minute,-30,SYSDATETIME())"
              + " WHERE Id=?",
          pastShow);
      List<Map<String, Object>> seats = admin.table("seats", hallId);
      int seat1 = number(seats.get(0).get("id"));
      int seat2 = number(seats.get(1).get("id"));
      int seat3 = number(seats.get(2).get("id"));
      int seat4 = number(seats.get(3).get("id"));

      check(
          customerA.table("movies").stream().anyMatch(row -> number(row.get("id")) == movieId)
              && customerA.table("movies").stream()
                  .noneMatch(row -> number(row.get("id")) == inactiveMovie),
          "customer movie browsing omits inactive movies");
      check(
          customerA.table("shows").stream().anyMatch(row -> number(row.get("id")) == showId)
              && customerA.table("shows").stream()
                  .noneMatch(row -> number(row.get("id")) == pastShow),
          "customer show browsing omits past shows");
      check(customerA.table("showSeats", showId).size() == 6, "customer can load seat map");
      rejects(
          "customer cannot view business statistics",
          e -> e instanceof SecurityException,
          () -> customerA.table("stats", today, today.plusDays(7)));
      rejects(
          "customer cannot view audit logs",
          e -> e instanceof SecurityException,
          () -> customerA.table("logs"));
      rejects(
          "customer cannot view hall administration",
          e -> e instanceof SecurityException,
          () -> customerA.table("halls"));
      rejects(
          "customer cannot view seat administration",
          e -> e instanceof SecurityException,
          () -> customerA.table("seats", hallId));
      rejects(
          "customer cannot create movies",
          e -> e instanceof SecurityException,
          () -> customerA.saveMovie(null, tag + "-forbidden", 90, true));
      rejects(
          "customer cannot create shows",
          e -> e instanceof SecurityException,
          () ->
              customerA.saveShow(
                  null, movieId, hallId, future.plusDays(1), new BigDecimal("30"), 15));

      long orderA = customerA.sell(showId, List.of(seat1, seat2));
      long orderB = customerB.sell(showId, List.of(seat3));
      long staffOrder = cashier.sell(showId, List.of(seat4));
      check(
          number(scalar("SELECT UserId FROM dbo.Orders WHERE Id=?", orderA))
              == customerA.user().id(),
          "customer purchase is owned by authenticated customer");
      check(
          new BigDecimal(scalar("SELECT TotalAmount FROM dbo.Orders WHERE Id=?", orderA).toString())
                  .compareTo(new BigDecimal("60"))
              == 0,
          "customer multi-seat payment total is accurate");
      check(orderIds(customerA, today).equals(Set.of(orderA)), "customer A sees only own order");
      check(
          orderIds(customerB, today).equals(Set.of(orderB)),
          "customer B cannot see customer A or staff orders");
      check(
          orderIds(cashier, today).containsAll(Set.of(orderA, orderB, staffOrder)),
          "staff still sees customer and counter orders");
      rejects(
          "customer cannot refund another customer's order",
          CustomerIntegrationTest::deniedOrder,
          () -> customerB.refund(orderA));
      rejects(
          "database procedure independently blocks cross-customer refund",
          CustomerIntegrationTest::deniedOrder,
          () -> executeApp("EXEC dbo.usp_RefundOrder ?,?", customerB.user().id(), orderA));
      rejects(
          "customer cannot refund counter staff order",
          CustomerIntegrationTest::deniedOrder,
          () -> customerA.refund(staffOrder));
      check(
          "PAID".equals(scalar("SELECT Status FROM dbo.Orders WHERE Id=?", orderA))
              && number(scalar("SELECT COUNT(*) FROM dbo.OrderLogs WHERE OrderId=?", orderA)) == 1,
          "failed ownership checks leave order and logs unchanged");

      customerA.refund(orderA);
      check(
          "REFUNDED".equals(scalar("SELECT Status FROM dbo.Orders WHERE Id=?", orderA))
              && number(
                      scalar(
                          "SELECT COUNT(*) FROM dbo.Tickets WHERE OrderId=? AND Active=1", orderA))
                  == 0,
          "customer can refund own order and release every seat");
      rejects(
          "customer cannot refund own order twice",
          e -> e instanceof SQLException sql && sql.getErrorCode() == 51029,
          () -> customerA.refund(orderA));
      long resale = customerB.sell(showId, List.of(seat1));
      check(
          orderIds(customerB, today).equals(Set.of(orderB, resale)),
          "released seat can be repurchased with new customer ownership");
      cashier.refund(orderB);
      check(
          "REFUNDED".equals(scalar("SELECT Status FROM dbo.Orders WHERE Id=?", orderB)),
          "cashier can process a customer's refund");
      check(
          number(
                  scalar(
                      "SELECT ActorId FROM dbo.OrderLogs WHERE OrderId=? AND NewStatus='REFUNDED'",
                      orderB))
              == cashier.user().id(),
          "staff-assisted customer refund records actual actor");

      CountDownLatch gate = new CountDownLatch(1);
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          attempts.add(
              workers.submit(() -> {
                gate.await();
                try {
                  CinemaService.register(usernameRace, "并发注册测试", password.toCharArray());
                  return true;
                } catch (Exception exception) {
                  if (!duplicateUsername(exception)) throw exception;
                  return false;
                }
              }));
        }
        gate.countDown();
        boolean a = attempts.get(0).get(30, TimeUnit.SECONDS);
        boolean b = attempts.get(1).get(30, TimeUnit.SECONDS);
        check(a ^ b, "concurrent duplicate registration has exactly one winner");
      } finally {
        workers.shutdownNow();
      }
      check(
          number(
                  scalar(
                      "SELECT COUNT(*) FROM dbo.AppUsers WHERE Username=? AND Role='CUSTOMER'",
                      usernameRace))
              == 1,
          "concurrent registration leaves one customer identity");
      System.out.println("RESULT: " + passed + " customer checks passed against SQL Server.");
    } finally {
      try {
        cleanup();
      } finally {
        if (originalConfig == null) System.clearProperty("cinema.config");
        else System.setProperty("cinema.config", originalConfig);
      }
    }
  }

  private static void cleanup() throws Exception {
    try (Connection connection = ownerConnection()) {
      connection.setAutoCommit(false);
      try {
        if (hallId != 0) {
          String[] commands = {
            "DELETE l FROM dbo.OrderLogs l JOIN dbo.Orders o ON o.Id=l.OrderId JOIN dbo.Shows s ON"
                + " s.Id=o.ShowId WHERE s.HallId=?",
            "DELETE FROM dbo.Tickets WHERE HallId=?",
            "DELETE o FROM dbo.Orders o JOIN dbo.Shows s ON s.Id=o.ShowId WHERE s.HallId=?",
            "DELETE FROM dbo.Shows WHERE HallId=?",
            "DELETE FROM dbo.Seats WHERE HallId=?",
            "DELETE FROM dbo.Halls WHERE Id=?"
          };
          for (String sql : commands) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              statement.setInt(1, hallId);
              statement.executeUpdate();
            }
          }
        }
        for (int id : fixtureMovieIds) {
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM dbo.Movies WHERE Id=? AND Title IN (?,?)")) {
            bind(statement, id, tag, tag + "-inactive");
            statement.executeUpdate();
          }
        }
        for (String username : fixtureUsernames) {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM dbo.AppUsers WHERE Username=? AND Role='CUSTOMER'")) {
            statement.setString(1, username);
            statement.executeUpdate();
          }
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      }
    }
    System.out.println("Customer fixture removed: " + tag);
  }
}
