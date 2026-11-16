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
import java.nio.file.attribute.PosixFilePermissions;
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
      die("usage: cove " + usage);
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
                usage: cove COMMAND [ARGS]
                  ls [PREFIX]                           list files on the server
                  push FILE [REMOTE]                    upload a file
                  pull REMOTE [LOCAL] [--version HASH]  download a file
                  log [REMOTE]                          show history
                  rm REMOTE                             remove a file from the server
                  send FILE... DEVICE                   send files to DEVICE's inbox
                  inbox [--get]                         files other devices sent you
                  setup SERVER TOKEN [DEVICE] [--force] create this device's config file""");
  }

  public static void main(String[] argv) {
    List<String> args = new ArrayList<>();
    boolean get = false;
    String version = null;
    boolean force = false;

    for(int i = 0; i < argv.length; i++) {
      switch (argv[i]) {
        case "--get" -> get = true;
        case "--force" -> force = true;
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
    if (command.equals("setup")) {
      cmdSetup(args, force);
      return;
    }

    loadConfig();
    //add new commands here
    switch(command) {
      case "ls" -> cmdLs(args);
      case "push" -> cmdPush(args);
      case "pull" -> cmdPull(args, version);
      case "log" -> cmdLog(args);
      case "rm" -> cmdRm(args);
      case "send" -> cmdSend(args);
      case "inbox" -> cmdInbox(args, get);
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
    need(args, 0, 1, "ls [FOLDER] [--all]");
    String prefix = args.isEmpty() ? "" : args.get(0);
    List<Entry> entries = fileList();

    if (all) {
      List<Entry> rows = new ArrayList<>();
      for (Entry e : entries) {
        if (e.path().startsWith(prefix)) {
          rows.add(e);
        }
      }
      if (rows.isEmpty()) {
        System.out.println("(no files)");
      }
      for (Entry e : rows) {
        printFile(e);
      }
      return;
    }

    String folder = prefix;
    if (!folder.isEmpty() && !folder.endsWith("/")) {
      folder += "/";
    }

    Map<String, Long[]> subfolders = new TreeMap<>();
    List<Entry> files = new ArrayList<>();
    for (Entry e : entries) {
      if (!e.path().startsWith(folder)) {
        continue;
      }
      String rest = e.path().substring(folder.length());
      int slash = rest.indexOf('/');
      if (slash < 0) {
        files.add(e);
      } else {
          String sub = folder + rest.substring(0, slash + 1);
          long[] totals = subfolders.computeIfAbsent(sub, k -> new long[2]);
          totals[0]++;
          totals[1] += e.size();
      }
    }

    if (subfolders.isEmpty() && files.isEmpty()) {
      System.out.println(folder.isEmpty() ? "(no files)" : "(no files in " + folder + ")");
      return;
    }
    for (Map.Entry<String, long[]> sub : subfolders.entrySet()) {
      long count = sub.getValue()[0];
      System.out.printf("  %-28s %d %s, %s%n", sub.getKey(), count, count == 1 ? "file" : "files", human(sub.getValue()[1]));
    }
    for (Entry e : files) {
      printFile(e);
    }
  }

  static void printFile(Entry e) {
    System.out.println(System.out.printf("%8s  %s  %-14s %s%n", human(e.size()), when(e.mtime()), e.device(), e.path());)
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

//pull handling
  static void download(String remote, Path local, String version) {
    String url = "/files/" + encode(remote) + (version == null ? "" : "?hash=" + version);
    Path tmp = local.resolveSibling(local.getFileName() + ".part");
    HttpResponse<InputStream> resp = send(newRequest(url).GET().build());
    try (InputStream in = resp.body()) {
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
      Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
      Optional<String> mtime = resp.headers().firstValue("X-MTime");
      if (mtime.isPresent()) {
        Files.setLastModifiedTime(local, FileTime.fromMillis(Long.parseLong(mtime.get())));
      }
    } catch (IOException e) {
        die("couldn't save " + local + ": " + e.getMessage());
    }
    System.out.println("pulled " + remote + " -> " + local);
  }

  static void cmdPull(List<String> args, String version) {
    need(args, 1, 2, "pull REMOTE [LOCAL] [--version HASH]");
    String remote = args.get(0);
    String name = Path.of(remote).getFileName().toString();
    Path local = Path.of(args.size() > 1 ? args.get(1) : name);
    if (Files.isDirectory(local)) {
      local = local.resolve(name);
    }
    String fullHash = null;
    if (version != null) {
      fullHash = resolveVersion(remote, version);
    }
    download(remote, local, fullHash);
  }

//log / pulling old versions
  static String resolveVersion(String remote, String shortHash) {
    Set<String> matches = new HashSet<>();
    for (String line : lines(getText("/log?path=" + encode(remote)))) {
      String hash = line.split("\t")[2];
      if (hash.startsWith(shortHash)) {
        matches.add(hash);
      }
    }
    if (matches.size() != 1) {
      die(matches.size() + " versions of " + remote + " match '" + shortHash + "'; check 'log " + remote + "'");
    }

    return matches.iterator().next();
  }

  static void cmdLog(List<String> args) {
    need(args, 0, 1, "log [REMOTE]");
    String url = args.isEmpty() ? "/log" : "/log?path=" + encode(args.get(0));
    List<String> history = lines(getText(url));
    int start = Math.max(0, history.size() - 30);
    for (String line : history.subList(start, history.size())) {
      String[] f = line.split("\t", -1);
      System.out.printf("%s %-6s %s %-14s %s%n", when(Long.parseLong(f[0])), f[1], f[2].substring(0, 8), f[4], f[5]);
    }
  }

//removal (rm) handling
  static void cmdRm(List<String> args) {
    need(args, 1, 1, "rm REMOTE");
    call(newRequest("/files/" + encode(args.get(0))).DELETE().build());
    System.out.println("removed " + args.get(0) + " (old versions still in history)");
  }

//send to device
  static void cmdSend(List<String> args) {
    need(args, 2, Integer.MAX_VALUE, "send FILE... DEVICE");
    String target = args.get(args.size() - 1);
    List<String> files = args.subList(0, args.size() - 1);
    for (String f : files) {
      if (!Files.isRegularFile(Path.of(f))) {
        die(f + " is not a file");
      }
    }
    for (String f : files) {
      Path p = Path.of(f);
      upload(p, "inbox/" + target + "/" + p.getFileName());
    }
  }

//device inbox
  static void cmdInbox(List<String> args, boolean get) {
    need(args, 0, 0, "inbox [--get]");
    String prefix = "inbox/" + device + "/";
    List<Entry> waiting = new ArrayList<>();
    for (Entry e : fileList()) {
      if (e.path().startsWith(prefix)) {
        waiting.add(e);
      }
    }
    if (waiting.isEmpty()) {
      System.out.println("inbox is empty");
      return;
    }
    if (!get) {
      for (Entry e : waiting) {
        System.out.printf("%8s from %-14s %s%n", human(e.size()), e.device(), e.path().substring(prefix.length()));
      }
      System.out.println("run 'inbox --get' to download these to ~/inbox");
      return;
    }
    try {
      Files.createDirectories(INBOX_DIR);
    } catch (IOException e) {
      die("can't create " + INBOX_DIR + ": " + e.getMessage());
    }
    for (Entry e : waiting) {
      download(e.path(), INBOX_DIR.resolve(Path.of(e.path()).getFileName()), null);
      call(newRequest("/files/" + encode(e.path())).DELETE().build());
    }
  }

//config setup handling (~/.config/filecove/config.properties)
  static void cmdSetup(List<String> args, boolean force) {
    need(args, 2, 3, "setup SERVER TOKEN [DEVICE] [--force]");
    String newServer = args.get(0).replaceAll("/+$", "");
    String newToken = args.get(1);
    String newDevice = args.size() > 2 ? args.get(2) : hostname();

    //confirm everything fits the requirements
    if (!newServer.startsWith("http://")) {
      die("the server address must start with http://");
    }

    if (!newDevice.matches("[A-Za-z0-9._-]{1,64}")) {
      die("device name '" + newDevice + "' may only use numbers (1-64), letters, ._ and -");
    }

    if (Files.exists(CONFIG) && !force) {
      die(CONFIG + " already exists; add --force to replace");
    }

    server = newServer;
    token = newToken;
    device = newDevice;
    int count = lines(getText("/files/")).size();

    String text = "server=" + newServer + "\n" + "token=" + newToken + "\n" + "device=" + newDevice + "\n";
    try {
      Files.createDirectories(CONFIG.getParent());
      Files.writeString(CONFIG, text, StandardCharsets.UTF_8);

      try {
        Files.setPosixFilePermissions(CONFIG, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {}
    } catch (IOException e) {
        die("can't write " + CONFIG + ": " + e.getMessage());
    }

    System.out.println("saved " + CONFIG + " for device " + newDevice + "; the server answered with " + count + " files");
  }
}
