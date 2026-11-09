package cn.edu.cinema.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/** JDBC configuration is local to the installation; never commit real credentials. */
public final class Database {
  private Database() {}

  public static Connection open() throws Exception {
    Properties config = new Properties();
    Path path = Path.of(System.getProperty("cinema.config", "config/application.properties"));
    if (!Files.isRegularFile(path))
      throw new IOException(
          "缺少数据库配置，请复制 config/application.example.properties 为 application.properties。");
    try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      config.load(in);
    }
    String url = System.getenv().getOrDefault("CINEMA_DB_URL", config.getProperty("db.url", ""));
    if (!url.startsWith("jdbc:sqlserver:")) throw new IOException("请配置 SQL Server JDBC 地址。");
    Properties props = new Properties();
    String user = System.getenv().getOrDefault("CINEMA_DB_USER", config.getProperty("db.user", ""));
    String password =
        System.getenv().getOrDefault("CINEMA_DB_PASSWORD", config.getProperty("db.password", ""));
    if (!user.isBlank()) {
      props.setProperty("user", user);
      props.setProperty("password", password);
    }
    props.setProperty("loginTimeout", "8");
    props.setProperty("socketTimeout", "20000");
    return DriverManager.getConnection(url, props);
  }
}
