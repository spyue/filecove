// filecove.Server: stores files and histories, communicates over HTTP

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class Server {
  static final String TOKEN = env("FILECOVE_TOKEN", "");
  static final Path DATA_DIR = Path.of(expandHome(env("FILECOVE_DATA", "~/filecove-data")));
  static final String HOST = env("FILECOVE_HOST", "0.0.0.0");
  static final int PORT = Integer.parseInt(env("FILECOVE_PORT", "8800"));

  static final Path OBJECTS = DATA_DIR.resolve("objects");
  static final Path INDEX = DATA_DIR.resolve("index.tsv");
  static final Path LOG = DATA_DIR.resolve("log.tsv");

  static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  static final Pattern DEVICE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  static final Object LOCK = new Object();

  static String env(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isEmpty() ? fallback : value);
  }

  //help with ~ for java
  static String expandHome(String path) {
    if (path.equals("~") || path.equals("~/")) {
      return System.getProperty("user.home") + path.substring(1);
    }
    return path;
  }

  static void handle(HttpExchange ex) {
    try {
      route(ex);
    } catch (Exception e) {
        //ensures errors dont just drop the connection with no msg
        e.printStackTrace();
        try {
          sendError(ex, 500, "server bug: " + e);
        } catch (IOException ignored) {
      }
    } finally {
        System.out.println(ex.getRequestMethod() + " " + ex.getRequestURI() + " -> " + ex.getResponseCode());
        ex.close();
    }
  }

  static boolean authorized(HttpExchange ex) {
    String sent = ex.getRequestHeaders().getFirst("Authorization");
    if (sent == null) {
      sent = "";
    }
    // MessageDigest.isEqual takes the same time no matter where
    // the difference is thus nobody can guess the token by timing
    // how fast the server says no
    return MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), ("Bearer " + TOKEN).getBytes(StandardCharsets.UTF_8));
  }

  static void sendText(HttpExchange ex, int code, String text) throws IOException {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");

    //body.length -> 0 means lenght unknown send in chunks and -1 means no body
    ex.sendResponseHeaders(code, body.length == 0 ? -1: body.length);
    if (body.length > 0) {
      try (OutputStream out = ex.getResponseBody()) {
        out.write(body);
      }
    }
  }

  static void sendError(HttpExchange ex, int code, String message) throws IOException {
    sendText(ex, code, message + "\n");
  }

  //landing spot for every request (add new features below)
  static void route(HttpExchange ex) throws IOException {
    if (!authorized(ex)) {
      sendError(ex, 401, "missing or wrong token");
      return;
    }

    // "/files/notes/a.txt?hash=ab12" -> kind: "files", path: "notes/a.txt"
    String full = ex.getRequestURI().getPath();
    String rest = full.startsWith("/") ? full.substring(1) : full;
    int slash = rest.indexOf('/');
    String kind = slash < 0 ? rest : rest.substring(0, slash);
    String path = slash < 0 ? "" : rest.substring(slash + 1);
    String method = ex.getRequestMethod();

    sendError(ex, 404, "unknown endpoint: " + method + " /" + kind);
     }

  public static void main(String[] args) throws IOException {
    if (TOKEN.isEmpty()) {
      System.err.println("FILECOVE_TOKEN is not set, Make one with:");
      System.err.println(" tr -dc 'a-f0-9' < /dev/urandom | head -c 64; echo");
      System.exit(1);
    }

    Files.createDirectories(OBJECTS);
    HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
    server.createContext("/", Server::handle);
    //requests wait in line for a thread, a pool of 8 threads lets 8 requests
    //run at once
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.println("filecove server on " + HOST + ";" + PORT + ", data in " + DATA_DIR);
  }

  //record handling
  record Entry(String hash, long size, long mtime, String device) {}

  static Map<String, Entry> loadIndex() throws IOException {
    Map<String, Entry> index = new TreeMap<>();
    if (!Files.exists(INDEX)) {
      return index;
    }
    for (String line : Files.readAllLines(INDEX, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }

      String[] f = line.split("\t", -1);
      index.put(f[4], new Entry(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
    }
    return index;
  }

  static String indexLine(String path, Entry e) {
    return e.hash() + "\t" + e.size() + "\t" + e.mtime() + "\t" + e.device() + "\t" + path;
  }

  static void listFiles(HttpExchange ex) throws IOException {
    Map<String, Entry> index;
    synchronized (LOCK) {
      index = loadIndex();
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Entry> item : index.entrySet()) {
      out.append(indexLine(item.getKey(), item.getValue())).append('\n');
    }
    sendText(ex, 200, out.toString());
  }
}
