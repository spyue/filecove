//filecove.Client

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public class Client {
  static final Path HOME = Path.of(System.getProperty("user.home"));
  static final Path CONFIG = HOME.resolve(".config/filecove/config.properties");
  static final Path INBOX_DIR = HOME.resolve("Inbox");

  //Force HTTP 1.1
  static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

  static String server;
  static String token;
  static String device;

  static void die(String message) {
    System.err.println(message);
    System.exit(1);
  }

  static void need(List<String> args, int min, int max, String usage) {
    if (args.size() < min || args.size() > max) {
      die("usage: fs " + usage);
    }
  }

  static String hostname() {
    try {
        return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
        return "unknown";
    }
  }

  static void loadConfig() {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(CONFIG)) {
        p.load(r);
    } catch (NoSuchFileException e) {
        die("no config file at " + CONFIG);
    } catch (IOException e) {
        die("cannot read " + CONFIG + " | " + e.getMessage());
    }
    server = p.getProperty("server");
    token = p.getProperty("token");
    device = p.getProperty("device", hostname());
    if (server == null || token == null) {
      die(CONFIG + " needs a server and/or a token line");
    }
    server = server.replaceAll("/+$", "");
  }

  //begin building the request
  static HttpRequest.Builder newRequest(String urlPath) {
    return HttpRequest.newBuilder(URI.create(server + urlPath)).header("Authorization", "Bearer " + token).header("X-Device", device);
  }

  static HttpResponse<InputStream> send(HttpRequest request) {
    try {
        HttpResponse<InputStream> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
          String message = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
          die("server error " + resp.statusCode() + ": " + message.strip());
        }
        return resp;
    } catch (ConnectException e) {
        die("can't reach " + server + ": Connection refused");
    } catch (IOException | InterruptedException e) {
        die("can't reach " + server + ": " + e);
    }
    return null;
  }

  static String call(HttpRequest request) {
    try (InputStream in = send(request).body()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
        die("lost the connection: " + e.getMessage());
        return null;
    }
  }

  static String getText(String urlPath) {
    return call(newRequest(urlPath).GET().build());
  }

  static void usage() {
    System.out.println("""
                usage: fs COMMAND [ARGS]
                  ls [PREFIX]                           list files on the server
                  push FILE [REMOTE]                    upload a file
                  pull REMOTE [LOCAL] [--version HASH]  download a file
                  log [REMOTE]                          show history
                  rm REMOTE                             remove a file from the server
                  send FILE... DEVICE                   send files to DEVICE's inbox
                  inbox [--get]                         files other devices sent you""");

  }

  public static void main(String[] argv) {
    List<String> args = new ArrayList<>();
    boolean get = false;
    String version = null;

    for(int i = 0; i < argv.length; i++) {
      switch (argv[i]) {
        case "--get" -> get = true;
        case "--version" -> {
            if (i + 1 >= argv.length) {
              die("--version needs a hash after it");
          }
          i++;
          version = argv[i];
        }
        case "-h", "--help" -> {
          usage();
          return;
        }
        default -> args.add(argv[i]);
      }
    }

    if (args.isEmpty()) {
        usage();
        System.exit(1);
    }
    String command = args.remove(0);
    loadConfig();
    //add new commands here
    switch(command) {
      case "ls" -> cmdLs(args);
      case "push" -> cmdPush(args);
      default -> die("unknown command: " + command + " (try --help)");
    }
  }

  static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  record Entry(String hash, long size, long mtime,String device, String path) {
    static Entry parse(String line) {
      String[] f = line.split("\t", -1);
      return new Entry(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3], f[4]);
    }
  }

  //ls handling
  static String human(long n) {
    if (n < 1024) {
      return n + "B";
    }
    String[] units = {"KB", "MB", "GB", "TB"};
    double x = n;
    int i = -1;
    while (x>=1024 && i < units.length - 1) {
      x /= 1024;
      i++;
    }
    return String.format("%.1f%s", x, units[i]);
  }

  static String when(long millis) {
    return WHEN.format(Instant.ofEpochMilli(millis));
  }

  static List<String> lines(String text) {
    return text.lines().filter(line -> !line.isBlank()).toList();
  }

  static List<Entry> fileList() {
    List<Entry> entries = new ArrayList<>();
    for (String line : lines(getText("/files/"))) {
      entries.add(Entry.parse(line));
    }
    return entries;
  }
  
  static void cmdLs(List<String> args) {
    need(args, 0, 1, "ls [PREFIX]");
    String prefix = args.isEmpty() ? "" : args.get(0);
    List<Entry> rows = new ArrayList<>();
    for (Entry e : fileList()) {
      if (e.path().startsWith(prefix)) {
        rows.add(e);
      }
    }
    if (rows.isEmpty()) {
      System.out.println("no files"); 
    }
    for (Entry e : rows) {
      System.out.printf("%8s %s %-14s %s%n", human(e.size()), when(e.mtime()), e.device(), e.path());
    }
  }

  //push handling
  static String encode(String s) {
    StringBuilder out = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      //-128-127 -> 0-255
      int v = b & 0xff;
      char c = (char) v;
      if ((c >= 'a' && c <= 'z') ||(c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~/".indexOf(c) >= 0) {
        out.append(c);
      } else { 
        out.append('%').append(String.format("%02x", v));
      }
    }
    return out.toString();
  }

  static void upload(Path local, String remote) {
    try {
      long size = Files.size(local);
      long mtime = Files.getLastModifiedTime(local).toMillis();
      HttpRequest request = newRequest("/files/" + encode(remote)).header("X-MTime", Long.toString(mtime)).PUT(HttpRequest.BodyPublishers.ofFile(local)).build();
      String hash = call(request).split("\t")[0];
      System.out.println("pushed " + local + " -> " + remote + "  (" + human(size) + ", version " + hash.substring(0, 8) + ")");
    } catch (IOException e) {
      die("can't read " + local + ": " + e.getMessage());
    }
  }

  static void cmdPush(List<String> args) {
    need(args, 1, 2, "push FILE [REMOTE]");
    Path file = Path.of(args.get(0));
    if (!Files.isRegularFile(file)) {
      die(file + " is not a file");
    }
    String name = file.getFileName().toString();
    String remote = args.size() > 1 ? args.get(1) : name;
    if (remote.endsWith("/")) {
      remote += name;
    }
    upload(file, remote);
  }
}
