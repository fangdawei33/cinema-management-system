package cn.edu.cinema.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Application authorization plus parameterized SQL. The DB procedures validate roles again. */
public final class CinemaService {
  public record User(int id, String username, String displayName, String role) {
    public boolean isAdmin() {
      return role.equals("ADMIN");
    }

    public boolean isCustomer() {
      return role.equals("CUSTOMER");
    }

    public boolean isStaff() {
      return role.equals("ADMIN") || role.equals("CASHIER");
    }

    public String roleName() {
      return switch (role) {
        case "ADMIN" -> "管理员";
        case "CASHIER" -> "售票员";
        case "CUSTOMER" -> "普通用户";
        default -> "未知角色";
      };
    }
  }

  private final User user;

  private CinemaService(User user) {
    this.user = user;
  }

  public User user() {
    return user;
  }

  /** Public registration has no role parameter: every new account is a customer. */
  public static CinemaService register(String username, String displayName, char[] password)
      throws Exception {
    try {
      String name = username == null ? "" : username.trim();
      String display = displayName == null ? "" : displayName.trim();
      if (!name.matches("[A-Za-z0-9_]{3,30}"))
        throw new IllegalArgumentException("账号须为 3–30 位字母、数字或下划线。");
      if (display.isEmpty() || display.length() > 50)
        throw new IllegalArgumentException("昵称须为 1–50 个字符。");
      if (password == null || password.length < 10 || password.length > 128)
        throw new IllegalArgumentException("密码须为 10–128 个字符。");
      String hash = Passwords.hash(password);
      try (Connection c = Database.open();
          PreparedStatement ps = c.prepareStatement("EXEC dbo.usp_RegisterCustomer ?,?,?")) {
        bind(ps, new Object[] {name, display, hash});
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) throw new SQLException("注册未返回账号信息。");
          return new CinemaService(new User(rs.getInt("UserId"), name, display, "CUSTOMER"));
        }
      }
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }
  }

  public static CinemaService login(String username, char[] password) throws Exception {
    try (Connection c = Database.open();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT Id,Username,DisplayName,Role,PasswordHash FROM dbo.AppUsers WHERE"
                    + " Username=? AND Active=1")) {
      ps.setString(1, username.trim());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && Passwords.verify(password, rs.getString("PasswordHash")))
          return new CinemaService(
              new User(
                  rs.getInt("Id"),
                  rs.getString("Username"),
                  rs.getString("DisplayName"),
                  rs.getString("Role")));
      }
      throw new IllegalArgumentException("用户名或密码错误，或账号已停用。");
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private void authorize(Connection c, boolean adminOnly) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT Role FROM dbo.AppUsers WHERE Id=? AND Active=1")) {
      ps.setInt(1, user.id());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new SecurityException("账号已停用，请重新登录。");
        if (!rs.getString(1).equals(user.role())) throw new SecurityException("账号权限已变更，请重新登录。");
        if (adminOnly && !rs.getString(1).equals("ADMIN"))
          throw new SecurityException("仅管理员可以执行此操作。");
      }
    }
  }

  public List<Map<String, Object>> table(String name, Object... args) throws Exception {
    if (user.isCustomer() && Set.of("halls", "seats", "stats", "logs").contains(name))
      throw new SecurityException("普通用户无权查看内部管理数据。");
    String sql;
    Object[] params = args;
    boolean admin = false;
    switch (name) {
      case "movies" ->
          sql =
              "SELECT Id id,Title title,DurationMinutes durationMinutes,Active active FROM"
                  + " dbo.Movies"
                  + (user.isCustomer() ? " WHERE Active=1" : "")
                  + " ORDER BY Id";
      case "halls" ->
          sql =
              "SELECT Id id,Name name,[RowCount] [rowCount],ColCount colCount FROM dbo.Halls ORDER"
                  + " BY Id";
      case "seats" ->
          sql =
              "SELECT Id id,RowNo rowNo,ColNo colNo,Active active FROM dbo.Seats WHERE HallId=?"
                  + " ORDER BY RowNo,ColNo";
      case "shows" ->
          sql =
              """
              SELECT sh.Id id,sh.MovieId movieId,sh.HallId hallId,m.Title movieTitle,h.Name hallName,
                sh.StartTime startTime,sh.EndTime endTime,sh.Price price,sh.CleanupMinutes cleanupMinutes,
                (SELECT COUNT(*) FROM dbo.Seats s WHERE s.HallId=sh.HallId AND s.Active=1) totalSeats,
                (SELECT COUNT(*) FROM dbo.Seats s WHERE s.HallId=sh.HallId AND s.Active=1)
                -(SELECT COUNT(*) FROM dbo.Tickets t WHERE t.ShowId=sh.Id AND t.Active=1) availableSeats
              FROM dbo.Shows sh JOIN dbo.Movies m ON sh.MovieId=m.Id JOIN dbo.Halls h ON sh.HallId=h.Id
              """
                  + (user.isCustomer() ? " WHERE sh.StartTime>SYSDATETIME()" : "")
                  + " ORDER BY sh.StartTime,sh.Id";
      case "showSeats" ->
          sql =
              """
              SELECT s.Id id,s.RowNo rowNo,s.ColNo colNo,s.Active active,
                CAST(CASE WHEN EXISTS(SELECT 1 FROM dbo.Tickets t WHERE t.SeatId=s.Id AND t.ShowId=sh.Id AND t.Active=1)
                  THEN 1 ELSE 0 END AS bit) sold
              FROM dbo.Shows sh JOIN dbo.Seats s ON s.HallId=sh.HallId WHERE sh.Id=?
              """
                  + (user.isCustomer() ? " AND sh.StartTime>SYSDATETIME()" : "")
                  + " ORDER BY s.RowNo,s.ColNo";
      case "orders" -> {
        Object[] dates = range(args);
        params = new Object[] {dates[0], dates[1], user.isCustomer() ? 1 : 0, user.id()};
        sql =
            """
            SELECT o.Id id,m.Title movieTitle,h.Name hallName,sh.StartTime startTime,
              (SELECT STRING_AGG(CONCAT(s.RowNo,N'排',s.ColNo,N'座'),N'、') WITHIN GROUP(ORDER BY s.RowNo,s.ColNo)
               FROM dbo.Tickets t JOIN dbo.Seats s ON s.Id=t.SeatId WHERE t.OrderId=o.Id) seats,
              o.TotalAmount totalAmount,o.Status status,o.CreatedAt createdAt,u.DisplayName cashier
            FROM dbo.Orders o JOIN dbo.Shows sh ON sh.Id=o.ShowId JOIN dbo.Movies m ON m.Id=sh.MovieId
              JOIN dbo.Halls h ON h.Id=sh.HallId JOIN dbo.AppUsers u ON u.Id=o.UserId
            WHERE o.CreatedAt>=? AND o.CreatedAt<? AND (?=0 OR o.UserId=?) ORDER BY o.Id DESC
            """;
      }
      case "stats" -> {
        params = range(args);
        sql =
            """
            WITH per_show AS (
              SELECT sh.Id,sh.MovieId,
                (SELECT COUNT(*) FROM dbo.Seats s WHERE s.HallId=sh.HallId AND s.Active=1) capacity,
                (SELECT COUNT(*) FROM dbo.Tickets t WHERE t.ShowId=sh.Id) soldCount,
                (SELECT COUNT(*) FROM dbo.Tickets t WHERE t.ShowId=sh.Id AND t.Active=0) refundCount,
                (SELECT COUNT(*) FROM dbo.Tickets t WHERE t.ShowId=sh.Id AND t.Active=1) netTickets,
                COALESCE((SELECT SUM(t.Price) FROM dbo.Tickets t WHERE t.ShowId=sh.Id AND t.Active=1),0) netRevenue
              FROM dbo.Shows sh WHERE sh.StartTime>=? AND sh.StartTime<?
            )
            SELECT m.Title movieTitle,COUNT(*) showCount,SUM(p.soldCount) soldCount,SUM(p.refundCount) refundCount,
              SUM(p.netTickets) netTickets,SUM(p.netRevenue) netRevenue,SUM(p.capacity) capacity,
              CAST(100.0*SUM(p.netTickets)/NULLIF(SUM(p.capacity),0) AS decimal(6,2)) occupancy
            FROM per_show p JOIN dbo.Movies m ON m.Id=p.MovieId GROUP BY m.Id,m.Title ORDER BY netRevenue DESC,m.Title
            """;
      }
      case "logs" -> {
        admin = true;
        sql =
            """
            SELECT TOP(500) l.Id id,l.OrderId orderId,l.OldStatus oldStatus,l.NewStatus newStatus,
              l.ChangedAt changedAt,u.DisplayName actor FROM dbo.OrderLogs l
            JOIN dbo.AppUsers u ON u.Id=l.ActorId ORDER BY l.Id DESC
            """;
      }
      default -> throw new IllegalArgumentException("未知查询。");
    }
    try (Connection c = Database.open()) {
      authorize(c, admin);
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, params);
        try (ResultSet rs = ps.executeQuery()) {
          return rows(rs);
        }
      }
    }
  }

  private static Object[] range(Object[] args) {
    if (args.length != 2
        || !(args[0] instanceof LocalDate start)
        || !(args[1] instanceof LocalDate end)
        || start.isAfter(end)) throw new IllegalArgumentException("起止日期无效。");
    return new Object[] {
      ((LocalDate) args[0]).atStartOfDay(), ((LocalDate) args[1]).plusDays(1).atStartOfDay()
    };
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    ps.setQueryTimeout(20);
    for (int i = 0; i < args.length; i++) {
      Object a = args[i];
      if (a instanceof LocalDateTime dt) ps.setTimestamp(i + 1, Timestamp.valueOf(dt));
      else if (a instanceof LocalDate d) ps.setDate(i + 1, java.sql.Date.valueOf(d));
      else ps.setObject(i + 1, a);
    }
  }

  private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
    List<Map<String, Object>> result = new ArrayList<>();
    ResultSetMetaData meta = rs.getMetaData();
    while (rs.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= meta.getColumnCount(); i++)
        row.put(meta.getColumnLabel(i), rs.getObject(i));
      result.add(row);
    }
    return result;
  }

  private long call(String procedure, boolean admin, Object... values) throws Exception {
    try (Connection c = Database.open()) {
      authorize(c, admin);
      String placeholders = String.join(",", Collections.nCopies(values.length + 1, "?"));
      try (PreparedStatement ps =
          c.prepareStatement("EXEC dbo." + procedure + " " + placeholders)) {
        Object[] parameters = new Object[values.length + 1];
        parameters[0] = user.id();
        System.arraycopy(values, 0, parameters, 1, values.length);
        bind(ps, parameters);
        boolean result = ps.execute();
        while (!result && ps.getUpdateCount() != -1) result = ps.getMoreResults();
        if (result)
          try (ResultSet rs = ps.getResultSet()) {
            if (rs.next()) return rs.getLong(1);
          }
        return 0;
      }
    }
  }

  public long sell(int showId, List<Integer> seatIds) throws Exception {
    if (seatIds == null
        || seatIds.isEmpty()
        || seatIds.size() > 50
        || new HashSet<>(seatIds).size() != seatIds.size())
      throw new IllegalArgumentException("请选择 1–50 个不同座位。");
    return call(
        "usp_SellTickets",
        false,
        showId,
        seatIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
  }

  public void refund(long orderId) throws Exception {
    call("usp_RefundOrder", false, orderId);
  }

  public void saveMovie(Integer id, String title, int duration, boolean active) throws Exception {
    call("usp_SaveMovie", true, id, title.trim(), duration, active);
  }

  public void deleteMovie(int id) throws Exception {
    call("usp_DeleteMovie", true, id);
  }

  public void saveHall(Integer id, String name, int rows, int cols) throws Exception {
    call("usp_SaveHall", true, id, name.trim(), rows, cols);
  }

  public void deleteHall(int id) throws Exception {
    call("usp_DeleteHall", true, id);
  }

  public void setSeatActive(int id, boolean active) throws Exception {
    call("usp_SetSeatActive", true, id, active);
  }

  public void saveShow(
      Integer id, int movieId, int hallId, LocalDateTime time, BigDecimal price, int cleanup)
      throws Exception {
    call("usp_SaveShow", true, id, movieId, hallId, time, price, cleanup);
  }

  public void deleteShow(int id) throws Exception {
    call("usp_DeleteShow", true, id);
  }

  public void changePassword(char[] oldPassword, char[] newPassword) throws Exception {
    try {
      if (newPassword.length < 10 || newPassword.length > 128)
        throw new IllegalArgumentException("新密码须为 10–128 位。");
      try (Connection c = Database.open()) {
        authorize(c, false);
        try (PreparedStatement ps =
            c.prepareStatement("SELECT PasswordHash FROM dbo.AppUsers WHERE Id=?")) {
          ps.setInt(1, user.id());
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !Passwords.verify(oldPassword, rs.getString(1)))
              throw new IllegalArgumentException("当前密码错误。");
          }
        }
        try (PreparedStatement ps =
            c.prepareStatement("UPDATE dbo.AppUsers SET PasswordHash=? WHERE Id=?")) {
          ps.setString(1, Passwords.hash(newPassword));
          ps.setInt(2, user.id());
          ps.executeUpdate();
        }
      }
    } finally {
      Arrays.fill(oldPassword, '\0');
      Arrays.fill(newPassword, '\0');
    }
  }
}
