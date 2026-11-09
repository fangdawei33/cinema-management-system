package cn.edu.cinema.service;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
  private Passwords() {}

  public static String hash(char[] password) throws Exception {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return "pbkdf2:210000:"
        + Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt, 210000));
  }

  public static boolean verify(char[] password, String encoded) throws Exception {
    if (encoded == null) return false;
    String[] parts = encoded.split(":");
    if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
    int rounds = Integer.parseInt(parts[1]);
    if (rounds < 100000 || rounds > 1000000) return false;
    return MessageDigest.isEqual(
        Base64.getDecoder().decode(parts[3]),
        derive(password, Base64.getDecoder().decode(parts[2]), rounds));
  }

  private static byte[] derive(char[] password, byte[] salt, int rounds) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password, salt, rounds, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }
}
