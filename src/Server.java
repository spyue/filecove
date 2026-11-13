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
    if (path.equals("~") || path.startsWith("~/")) {
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
    Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

    if (kind.equals("files") && method.equals("GET") && path.isEmpty()) {listFiles(ex); return;}
    if (kind.equals("files") && method.equals("PUT")) { receiveFile(ex, path); return; }
    if (kind.equals("files") && method.equals("GET")) {sendFile(ex, path, query.get("hash")); return;}
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

//upload handling
  static void saveIndex(Map<String, Entry> index) throws IOException {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Entry> item : index.entrySet()) {
      out.append(indexLine(item.getKey(), item.getValue())).append('\n');
    }

    Path tmp = DATA_DIR.resolve("index.tsv.temp");
    Files.writeString(tmp, out, StandardCharsets.UTF_8);
    Files.move(tmp, INDEX, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  static void appendLog(String action, String hash, long size, String device, String path) throws IOException {
    String line = System.currentTimeMillis() + "\t" + action + "\t" + hash + "\t" + size + "\t" + device + "\t" + path + "\n";
    Files.writeString(LOG, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  //ensures paths like ../../.config dont slip through when they are converted from the index.tsv for download
  static boolean isValidPath(String path) {
    if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("\t") || path.contains("\n") || path.contains("\r")) {
      return false;
    }
    for (String part : path.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        return false;
      }
    }
    return true;
  }

  //hash handling sha256
  static MessageDigest newsha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
  }

  //get device that sent the request
  static String deviceOf(HttpExchange ex) {
    String d = ex.getRequestHeaders().getFirst("X-Device");
    return (d != null && DEVICE.matcher(d).matches()) ? d : "unknown";
  }

  //gettime of request
  static long mtimeOf(HttpExchange ex) {
    String m = ex.getRequestHeaders().getFirst("X-MTime");
    return (m != null && m.matches("[0-9]{1,15}")) ? Long.parseLong(m) : System.currentTimeMillis();
  }

  //handles the receieving of the file
  static void receiveFile(HttpExchange ex, String path) throws IOException {
    if (!isValidPath(path)) {
      sendError(ex, 400, "bad path: " + path);
      return;
    }

    String lengthHeader = ex.getRequestHeaders().getFirst("Content-Length");
    long expected = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);

    MessageDigest sha256 = newsha256();
    Path tmp = Files.createTempFile(OBJECTS, ".upload-", "");
    long size;
    try (InputStream in = new DigestInputStream(ex.getRequestBody(), sha256)) {
      size = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw e;
    }
    
    if (expected >= 0 && size != expected) {
      Files.deleteIfExists(tmp);
      sendError(ex, 400, "upload was cut off");
      return;
    }

    String hash = HexFormat.of().formatHex(sha256.digest());
    Files.move(tmp, OBJECTS.resolve(hash), StandardCopyOption.REPLACE_EXISTING);

    Entry entry = new Entry(hash, size, mtimeOf(ex), deviceOf(ex));
    synchronized (LOCK) {
      Map<String, Entry> index = loadIndex();
      index.put(path, entry);
      saveIndex(index);
      appendLog("put", hash, size, entry.device(), path);
    }
    sendText(ex, 200, indexLine(path, entry) + "\n");
  }

//download handling
  static Map<String, String> parseQuery(String raw) {
    Map<String, String> query = new HashMap<>();
    if (raw == null) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return query;
  }

  static void sendFile(HttpExchange ex, String path, String wantedHash) throws IOException {
    Entry current;
    synchronized (LOCK) {
      current = loadIndex().get(path);
    }
    if (wantedHash == null) {
      if (current == null) {
        sendError(ex, 404, "no file named " + path);
        return;
      }
      wantedHash = current.hash();
    }

    if (!HASH.matcher(wantedHash).matches()) {
      sendError(ex, 400, "no such version");
      return;
    }
    Path obj = OBJECTS.resolve(wantedHash);
    if (!Files.exists(obj)) {
      sendError(ex, 404, "no such version");
      return;
    }

    long size = Files.size(obj);
    ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
    if (current != null && current.hash().equals(wantedHash)) {
      ex.getResponseHeaders().set("X-MTime", Long.toString(current.mtime()));
    }
    ex.sendResponseHeaders(200, size == 0 ? -1 : size);
    if (size > 0) {
      try (OutputStream out = ex.getResponseBody()) {
        Files.copy(obj, out);
      }
    }
  }
}
