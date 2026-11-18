import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Fetches, patches and launches AMI MegaRAC JViewer on a modern JDK. Run from source by run.sh / run.cmd
 * (and build.sh / build.cmd), which pass {@code -Djviewer.home=<repository directory>}; needs JDK 24+.
 *
 * <pre>
 *   run &lt;jviewer.jnlp | https://.../x.jnlp&gt;   fetch this BMC's jars, patch them (cached) and launch
 *   run [bmc-host] [username] [password]       standalone login, with the jars cached for that host
 *   run -- &lt;raw JViewer args...&gt;
 *   build [-o OUT_DIR] JAR|DIR ...             patch any set of JViewer jars into OUT_DIR/JViewer.jar
 * </pre>
 *
 * Environment: JVIEWER_JAR, PORT, TLS_PROTOCOLS, JVIEWER_JAVA_OPTS, JVIEWER_DEBUG=1, JVIEWER_SKIP_CHECKS=1,
 * JVIEWER_DRY_RUN=1
 * (see README.md).
 */
public class JViewerPatcher {
   static Path home;

   public static void main(String[] args) throws Exception {
      String h = System.getProperty("jviewer.home");
      home = (h != null ? Path.of(h) : Path.of("")).toAbsolutePath().normalize();
      if (args.length == 0) usage();
      List<String> rest = List.of(args).subList(1, args.length);
      try {
         switch (args[0]) {
            case "run" -> Run.main(rest);
            case "build" -> Build.main(rest);
            default -> usage();
         }
      } catch (Fail f) {
         System.err.println(f.getMessage());
         System.exit(1);
      } catch (IOException e) {
         // network and file errors are expected (BMC unreachable, bad path): no stack trace
         System.err.println("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
         System.exit(1);
      }
   }

   static void usage() {
      System.err.println("usage: run.sh <jviewer.jnlp | url | bmc-host [user [password]] | -- args...>\n" +
            "       build.sh [-o OUT_DIR] JAR|DIR ...");
      System.exit(2);
   }

   /** A fatal error with a message meant for the user (no stack trace). */
   static class Fail extends RuntimeException {
      private static final long serialVersionUID = 1L;

      Fail(String m) { super(m); }
   }

   static String env(String name, String def) {
      String v = System.getenv(name);
      return v == null || v.isEmpty() ? def : v;
   }

   static boolean isWindows() {
      return System.getProperty("os.name", "").startsWith("Windows");
   }

   static void warn(String msg) {
      System.err.println("warning: " + msg);
   }

   static String sha(MessageDigest md) {
      return HexFormat.of().formatHex(md.digest()).substring(0, 16);
   }

   static void rmrf(Path p) throws IOException {
      if (!Files.exists(p)) return;
      try (Stream<Path> s = Files.walk(p)) {
         for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(x);
      }
   }

   static String hostKey(String host) {
      return host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
   }

   // ------------------------------------------------------------------------------------------------
   /** Environment checks: they only warn, since an environment we haven't considered may still work. */
   static class Checks {
      static void run() {
         if ("1".equals(System.getenv("JVIEWER_SKIP_CHECKS"))) return;
         Path jh = Path.of(System.getProperty("java.home"));
         if (ModuleLayer.boot().findModule("java.desktop").isEmpty()) {
            warn("this Java runtime has no java.desktop module, so JViewer can't open windows; " + jdkHint());
            return;
         }
         String os = System.getProperty("os.name", "");
         if (os.startsWith("Linux")) {
            if (!Files.exists(jh.resolve("lib/libawt_xawt.so"))) {
               warn(jh + " looks like a headless Java (no lib/libawt_xawt.so), which can't open windows; " + jdkHint());
            }
            if (System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) {
               warn("no DISPLAY or WAYLAND_DISPLAY set: JViewer needs an X11/XWayland session (or 'ssh -X')");
            }
         } else if (GraphicsEnvironment.isHeadless()) {
            warn("Java reports no graphical display, so JViewer can't open windows");
         }
      }

      static String jdkHint() {
         String os = System.getProperty("os.name", "");
         if (os.startsWith("Mac")) return "install a JDK, e.g. 'brew install openjdk' or from https://adoptium.net";
         if (os.startsWith("Windows")) return "install a JDK, e.g. 'winget install EclipseAdoptium.Temurin.25.JDK' or from https://adoptium.net";
         if (Files.exists(Path.of("/usr/bin/apt-get"))) return "e.g. 'sudo apt install openjdk-25-jdk' (not -jre or -headless)";
         if (Files.exists(Path.of("/usr/bin/dnf"))) return "e.g. 'sudo dnf install java-25-openjdk-devel' (not -headless)";
         return "install a full (non-headless) JDK from your distribution or https://adoptium.net";
      }
   }

   // ------------------------------------------------------------------------------------------------
   static class Run {
      static Path cache() { return home.resolve("cache"); }

      static void main(List<String> a) throws Exception {
         Checks.run();
         String first = a.isEmpty() ? "" : a.get(0);
         if (first.toLowerCase(Locale.ROOT).endsWith(".jnlp") || first.matches("(?i)https?://.*")) {
            jnlp(first);
         } else if (first.equals("--")) {
            List<String> raw = a.subList(1, a.size());
            int i = raw.indexOf("-hostname");
            Path jar = jarFor(i >= 0 && i + 1 < raw.size() ? raw.get(i + 1) : "");
            launch(jar, mainClass(jar), List.of(), raw);
         } else {
            Path jar = jarFor(first);
            List<String> args = new ArrayList<>(List.of("-apptype", "StandAlone", "-webport", env("PORT", "443")));
            String[] names = {"-hostname", "-u", "-p"};
            for (int i = 0; i < Math.min(a.size(), 3); i++) {
               if (!a.get(i).isEmpty()) { args.add(names[i]); args.add(a.get(i)); }
            }
            launch(jar, mainClass(jar), List.of(), args);
         }
      }

      static void jnlp(String src) throws Exception {
         Net.legacyTls();
         Jnlp j = Jnlp.load(src, cache().resolve("jars"));
         // rebuild only when the BMC's jars, the patcher or the debug setting change
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         try (Stream<Path> s = Files.list(j.jarDir)) {
            for (Path p : s.sorted().toList()) md.update(Files.readAllBytes(p));
         }
         Build.digestSources(md);
         md.update(("debug=" + env("JVIEWER_DEBUG", "0")).getBytes(StandardCharsets.UTF_8));
         String key = sha(md);
         Path out = cache().resolve("build").resolve(key);
         if (!Files.exists(out.resolve("JViewer.jar"))) Build.build(out, List.of(j.jarDir));
         // remember it for standalone launches of this host
         Path hosts = cache().resolve("hosts");
         Files.createDirectories(hosts);
         Files.writeString(hosts.resolve(hostKey(j.host)), key + "\n");
         Files.writeString(hosts.resolve(".last"), key + "\n");
         Path jar = out.resolve("JViewer.jar");
         launch(jar, j.mainClass != null ? j.mainClass : mainClass(jar), j.props, j.args);
      }

      static Path jarFor(String host) throws IOException {
         String override = System.getenv("JVIEWER_JAR");
         if (override != null && !override.isEmpty()) {
            Path p = Path.of(override).toAbsolutePath();
            if (!Files.isRegularFile(p)) throw new Fail("run: JVIEWER_JAR: no such file: " + p);
            return p;
         }
         Path hosts = cache().resolve("hosts");
         Path f = hosts.resolve(host.isEmpty() ? ".last" : hostKey(host));
         if (Files.exists(f)) {
            Path jar = cache().resolve("build").resolve(Files.readString(f).trim()).resolve("JViewer.jar");
            if (Files.exists(jar)) return jar;
         }
         StringBuilder m = new StringBuilder("run: no patched JViewer cached for " + (host.isEmpty() ? "any host" : host) + " yet.\n" +
               "     Open the remote console once from the BMC web UI, save the jviewer.jnlp it offers and run\n" +
               "     run.sh path/to/jviewer.jnlp (run.cmd on Windows); later standalone launches of that host reuse its jars.");
         if (!host.isEmpty() && Files.isDirectory(hosts)) {
            try (Stream<Path> s = Files.list(hosts)) {
               String known = s.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted()
                     .collect(Collectors.joining(" "));
               if (!known.isEmpty()) m.append("\n     Cached hosts: ").append(known);
            }
         }
         throw new Fail(m.toString());
      }

      static String mainClass(Path jar) throws IOException {
         try (var jf = new java.util.jar.JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            String m = mf == null ? null : mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            return m != null ? m : Build.DEFAULT_MAIN;
         }
      }

      /** Runs JViewer in its own JVM (it needs JVM flags) and exits with its exit code. */
      static void launch(Path jar, String main, List<String> props, List<String> args) throws Exception {
         String tls = env("TLS_PROTOCOLS", "TLSv1");
         Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
         List<String> cmd = new ArrayList<>(List.of(java.toString(),
               "-Djava.security.properties=" + home.resolve("legacy.java.security"),
               "-Dhttps.protocols=" + tls, "-Djdk.tls.client.protocols=" + tls,
               "-Djava.security.disableSystemPropertiesFile=true",
               "-Dsun.java2d.metal=true", "--add-opens", "java.desktop/java.awt=ALL-UNNAMED"));
         for (String o : env("JVIEWER_JAVA_OPTS", "").trim().split("\\s+")) if (!o.isEmpty()) cmd.add(o);
         for (String p : props) cmd.add("-D" + p);
         cmd.addAll(List.of("-cp", jar.toString(), main));
         cmd.addAll(args);
         if ("1".equals(System.getenv("JVIEWER_DRY_RUN"))) {
            // arguments may contain a session token or password, so only the fixed part is shown
            System.out.println(String.join(" ", cmd.subList(0, cmd.size() - args.size())) + "  [+" + args.size() + " arguments]");
            return;
         }
         Process p = new ProcessBuilder(cmd).inheritIO().start();
         System.exit(p.waitFor());
      }
   }

   // ------------------------------------------------------------------------------------------------
   static class Net {
      /** Same relaxations as the JViewer JVM gets (legacy.java.security, TLS versions), for fetching. */
      static void legacyTls() throws Exception {
         Path f = home.resolve("legacy.java.security");
         if (Files.exists(f)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
            for (String k : p.stringPropertyNames()) Security.setProperty(k, p.getProperty(k));
         }
         String tls = env("TLS_PROTOCOLS", "TLSv1");
         System.setProperty("https.protocols", tls);
         System.setProperty("jdk.tls.client.protocols", tls);
         // the BMC's certificate is self-signed, so it is not verified (the same as JViewer itself)
         TrustManager[] tm = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String t) {}
            public void checkServerTrusted(X509Certificate[] c, String t) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
         }};
         SSLContext ctx = SSLContext.getInstance("TLS");
         ctx.init(null, tm, null);
         HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
         HttpsURLConnection.setDefaultHostnameVerifier((hn, s) -> true);
      }

      static byte[] fetch(URL u) throws IOException {
         HttpURLConnection c = (HttpURLConnection) u.openConnection();
         c.setConnectTimeout(15000);
         c.setReadTimeout(60000);
         int rc = c.getResponseCode();
         if (rc != 200) throw new Fail("jnlp: " + u + ": HTTP " + rc);
         try (InputStream in = c.getInputStream()) {
            return in.readAllBytes();
         }
      }
   }

   // ------------------------------------------------------------------------------------------------
   /** A parsed jviewer.jnlp whose jars have been downloaded. Arguments (with the session token) stay in memory. */
   static class Jnlp {
      Path jarDir;
      String host, mainClass;
      List<String> props = new ArrayList<>(), args = new ArrayList<>();

      static Jnlp load(String src, Path cache) throws Exception {
         URI srcUri = src.matches("(?i)https?://.*") ? new URI(src) : null;
         byte[] xml;
         try {
            xml = srcUri != null ? Net.fetch(srcUri.toURL()) : Files.readAllBytes(Path.of(src));
         } catch (java.nio.file.NoSuchFileException e) {
            throw new Fail("jnlp: no such file: " + src);
         }
         DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
         f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
         f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
         f.setExpandEntityReferences(false);
         Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
         Element root = doc.getDocumentElement();
         if (!root.getTagName().equals("jnlp")) throw new Fail("jnlp: not a JNLP file: <" + root.getTagName() + ">");

         String cb = root.getAttribute("codebase").trim();
         if (cb.isEmpty() || cb.contains("<%")) {
            throw new Fail("jnlp: no usable codebase (" + (cb.isEmpty() ? "missing" : "unexpanded template") +
                  "); use the jviewer.jnlp the BMC web UI downloads when you launch the console");
         }
         if (!cb.endsWith("/")) cb += "/";
         URI base = srcUri != null ? srcUri.resolve(cb) : new URI(cb);
         if (!base.isAbsolute()) throw new Fail("jnlp: relative codebase in a local JNLP file: " + cb);

         // main="true" jar first, then the rest in document order; <nativelib> is never needed
         List<String> jars = new ArrayList<>();
         NodeList nl = root.getElementsByTagName("jar");
         for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            String href = e.getAttribute("href").trim();
            if (href.isEmpty() || jars.contains(href)) continue;
            if ("true".equalsIgnoreCase(e.getAttribute("main"))) jars.add(0, href); else jars.add(href);
         }
         if (jars.isEmpty()) throw new Fail("jnlp: no <jar> resources listed");

         Jnlp j = new Jnlp();
         j.host = base.getHost();
         String hp = base.getHost() + "_" + (base.getPort() < 0 ? base.getScheme() : String.valueOf(base.getPort()));
         j.jarDir = cache.resolve(hp.replaceAll("[^A-Za-z0-9._-]", "_"));
         Files.createDirectories(cache);
         Path tmp = Files.createTempDirectory(cache, ".fetch");
         try {
            int n = 0;
            for (String href : jars) {
               URI u = base.resolve(href);
               // keep the jnlp order in the file names: jars are merged in name order, first wins
               String name = String.format("%02d-%s", n++, new File(u.getPath()).getName().replaceAll("[^A-Za-z0-9._-]", "_"));
               System.err.println("jnlp: fetching " + u);
               Files.write(tmp.resolve(name), Net.fetch(u.toURL()));
            }
            rmrf(j.jarDir);
            Files.move(tmp, j.jarDir, StandardCopyOption.ATOMIC_MOVE);
         } finally {
            rmrf(tmp);
         }

         NodeList app = root.getElementsByTagName("application-desc");
         Element ad = app.getLength() > 0 ? (Element) app.item(0) : null;
         if (ad != null && !ad.getAttribute("main-class").trim().isEmpty()) j.mainClass = ad.getAttribute("main-class").trim();
         NodeList props = root.getElementsByTagName("property");
         for (int i = 0; i < props.getLength(); i++) {
            Element p = (Element) props.item(i);
            if (!p.getAttribute("name").isEmpty()) j.props.add(p.getAttribute("name") + "=" + p.getAttribute("value"));
         }
         if (ad != null) {
            NodeList as = ad.getElementsByTagName("argument");
            for (int i = 0; i < as.getLength(); i++) j.args.add(as.item(i).getTextContent().trim());
         }
         return j;
      }
   }

   // ------------------------------------------------------------------------------------------------
   /** Merges jars, compiles patch-src in, patches the bytecode and writes OUT/JViewer.jar. */
   static class Build {
      static final String DEFAULT_MAIN = "com.ami.kvm.jviewer.JViewer";

      static void main(List<String> a) throws Exception {
         Path out = Path.of("dist");
         List<String> in = new ArrayList<>(a);
         if (in.size() >= 2 && in.get(0).equals("-o")) { out = Path.of(in.get(1)); in = in.subList(2, in.size()); }
         if (in.isEmpty()) {
            throw new Fail("usage: build.sh [-o OUT_DIR] JAR|DIR ...   (JViewer.jar, JViewer-SOC.jar, ... from the BMC)");
         }
         build(out.toAbsolutePath(), in.stream().map(Path::of).toList());
      }

      /** Everything that affects the build output besides the input jars, for the cache key. */
      static void digestSources(MessageDigest md) throws IOException {
         md.update(Files.readAllBytes(home.resolve("tools/JViewerPatcher.java")));
         for (Path p : patchSources()) md.update(Files.readAllBytes(p));
      }

      static List<Path> patchSources() throws IOException {
         try (Stream<Path> s = Files.walk(home.resolve("patch-src"))) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
         }
      }

      static void build(Path out, List<Path> inputs) throws Exception {
         List<Path> jars = new ArrayList<>();
         for (Path a : inputs) {
            if (Files.isDirectory(a)) {
               // main jar first so its manifest and classes take precedence
               Path main = a.resolve("JViewer.jar");
               if (Files.isRegularFile(main)) jars.add(main);
               try (Stream<Path> s = Files.list(a)) {
                  s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().forEach(jars::add);
               }
            } else if (Files.isRegularFile(a)) {
               jars.add(a);
            } else {
               throw new Fail("build: no such file or directory: " + a);
            }
         }
         // merge (first wins on duplicates); jars without classes are the per-OS native libraries
         Map<String, byte[]> files = new TreeMap<>();
         String main = null;
         Set<Path> seen = new java.util.HashSet<>();
         for (Path j : jars) {
            if (!seen.add(j.toAbsolutePath().normalize())) continue;
            Map<String, byte[]> entries = readJar(j);
            if (entries.keySet().stream().noneMatch(n -> n.endsWith(".class"))) continue;
            System.err.println("build: + " + j.getFileName());
            if (main == null && entries.containsKey("META-INF/MANIFEST.MF")) {
               main = new Manifest(new ByteArrayInputStream(entries.get("META-INF/MANIFEST.MF")))
                     .getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            }
            // signatures would be invalid after patching, so META-INF is dropped
            entries.forEach((n, b) -> { if (!n.startsWith("META-INF/")) files.putIfAbsent(n, b); });
         }
         if (files.keySet().stream().noneMatch(n -> n.endsWith(".class"))) {
            throw new Fail("build: no classes found in: " + jars);
         }

         // pure-Java vmedia (ImageDisk + VMediaNatives) compiled in, then the bytecode patches
         files.putAll(compilePatchSources());
         new Patcher(files, "1".equals(System.getenv("JVIEWER_DEBUG"))).run();

         Manifest mf = new Manifest();
         mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
         mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main != null ? main : DEFAULT_MAIN);
         Files.createDirectories(out);
         rmrf(out.resolve("Jar"));   // JViewer's native-lib cache
         Path tmp = Files.createTempFile(out, ".JViewer", ".jar");
         try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(tmp), mf)) {
            for (var e : files.entrySet()) {
               jo.putNextEntry(new JarEntry(e.getKey()));
               jo.write(e.getValue());
               jo.closeEntry();
            }
         }
         Files.move(tmp, out.resolve("JViewer.jar"), StandardCopyOption.REPLACE_EXISTING);
         System.err.println("Built " + out.resolve("JViewer.jar"));
      }

      static Map<String, byte[]> readJar(Path jar) throws IOException {
         Map<String, byte[]> m = new LinkedHashMap<>();
         try (ZipInputStream zi = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
               if (!e.isDirectory()) m.putIfAbsent(e.getName(), zi.readAllBytes());
            }
         }
         return m;
      }

      static Map<String, byte[]> compilePatchSources() throws IOException {
         JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
         if (jc == null) throw new Fail("build: no Java compiler in this runtime; " + Checks.jdkHint());
         Path tmp = Files.createTempDirectory("jviewer-patch-src");
         try {
            List<String> opts = new ArrayList<>(List.of("--release", "8", "-nowarn", "-Xlint:-options",
                  "-encoding", "UTF-8", "-d", tmp.toString()));
            for (Path p : patchSources()) opts.add(p.toString());
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            if (jc.run(null, null, err, opts.toArray(String[]::new)) != 0) {
               throw new Fail("build: compiling patch-src failed:\n" + err.toString(StandardCharsets.UTF_8));
            }
            Map<String, byte[]> m = new TreeMap<>();
            try (Stream<Path> s = Files.walk(tmp)) {
               for (Path p : s.filter(Files::isRegularFile).toList()) {
                  m.put(tmp.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
               }
            }
            return m;
         } finally {
            rmrf(tmp);
         }
      }
   }

   // ------------------------------------------------------------------------------------------------
   /**
    * Bytecode patches, found by what needs fixing rather than at fixed offsets, so they work across versions:
    * <ul>
    * <li>tls: String constants like "SSLv3" / "SSLv3,TLSv1" (https.protocols values) lose their SSL entries
    * <li>catch: {@code catch (ArrayIndexOutOfBoundsException)} widened to {@code IndexOutOfBoundsException}
    *     (modern {@code ArrayList.get(-1)} throws the latter)
    * <li>natives: every {@code native} method gets a body calling the static method of the same name in
    *     com.ami.iusb.VMediaNatives, with {@code this} (as Object) prepended (pure-Java vmedia, see patch-src)
    * <li>libload: in those classes, System.load/loadLibrary and *.getWrapperLibrary go to VMediaNatives no-ops
    * <li>debug (JVIEWER_DEBUG=1): com.ami.kvm.jviewer.Debug.MODE set to 1 (console logging) where it is initialised
    * </ul>
    */
   static class Patcher {
      static final String HELPER = "com/ami/iusb/VMediaNatives";
      static final ClassDesc HELPER_DESC = ClassDesc.ofInternalName(HELPER);
      static final ClassDesc IOOBE = ClassDesc.of("java.lang.IndexOutOfBoundsException");
      static final String AIOOBE = "java/lang/ArrayIndexOutOfBoundsException";
      static final Pattern PROTO = Pattern.compile(
            "(SSLv2Hello|SSLv3|SSLv2|TLSv1(\\.[0-3])?)( *, *(SSLv2Hello|SSLv3|SSLv2|TLSv1(\\.[0-3])?))*");

      final Map<String, byte[]> files;
      final boolean debug;
      final Set<String> helper;
      final ClassFile cf;

      Patcher(Map<String, byte[]> files, boolean debug) {
         this.files = files;
         this.debug = debug;
         byte[] h = files.get(HELPER + ".class");
         if (h == null) throw new Fail("build: " + HELPER + ".class missing (patch-src not compiled?)");
         ClassModel hm = ClassFile.of().parse(h);
         this.helper = hm.methods().stream().filter(m -> m.flags().has(AccessFlag.STATIC))
               .map(m -> m.methodName().stringValue() + m.methodType().stringValue()).collect(Collectors.toSet());
         // stack maps (class files 50+) are recomputed for changed methods, which needs the JViewer class hierarchy
         ClassLoader treeLoader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
               byte[] b = files.get(name);
               return b != null ? new ByteArrayInputStream(b) : ClassLoader.getSystemResourceAsStream(name);
            }
         };
         this.cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
               ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(treeLoader))));
      }

      /** Per-class counters, also used to decide whether a class changed. */
      static class Count {
         int tls, catches, natives, libload, debug;

         boolean any() { return tls + catches + natives + libload + debug > 0; }

         public String toString() {
            List<String> s = new ArrayList<>();
            if (tls > 0) s.add("tls " + tls);
            if (catches > 0) s.add("catch " + catches);
            if (natives > 0) s.add("natives " + natives);
            if (libload > 0) s.add("libload " + libload);
            if (debug > 0) s.add("debug " + debug);
            return String.join(", ", s);
         }
      }

      void run() {
         Count total = new Count();
         for (String name : List.copyOf(files.keySet())) {
            if (!name.endsWith(".class") || name.equals(HELPER + ".class")) continue;
            ClassModel cm = cf.parse(files.get(name));
            Count c = new Count();
            byte[] out = patch(cm, c);
            if (c.any()) {
               files.put(name, out);
               System.err.println("patch: " + cm.thisClass().asInternalName() + ": " + c);
            }
            total.tls += c.tls; total.natives += c.natives; total.debug += c.debug;
         }
         if (total.tls == 0) warn("patch: no SSLv3 protocol strings found (fine if this version never forces SSLv3)");
         if (total.natives == 0) warn("patch: no native vmedia methods found; virtual media classes may have moved");
         if (debug && total.debug == 0) warn("patch: JVIEWER_DEBUG: could not find the Debug.MODE initialiser");
      }

      byte[] patch(ClassModel cm, Count c) {
         String cls = cm.thisClass().asInternalName();
         boolean hasNatives = false;
         for (MethodModel m : cm.methods()) {
            if (!m.flags().has(AccessFlag.NATIVE)) continue;
            String key = m.methodName().stringValue() + helperType(m).descriptorString();
            if (helper.contains(key)) {
               hasNatives = true;
            } else {
               warn("patch: " + cls + "." + m.methodName().stringValue() + m.methodType().stringValue() +
                     " is native and VMediaNatives has no replacement; it will throw UnsatisfiedLinkError");
            }
         }
         boolean libload = hasNatives;
         boolean isDebug = debug && cls.equals("com/ami/kvm/jviewer/Debug");

         CodeTransform code = CodeTransform.ofStateful(() -> new CodeFixes(c, libload, isDebug));
         ClassTransform t = (cb, ce) -> {
            if (ce instanceof MethodModel m && m.flags().has(AccessFlag.NATIVE)
                  && helper.contains(m.methodName().stringValue() + helperType(m).descriptorString())) {
               c.natives++;
               cb.transformMethod(m, stub(m));
            } else {
               cb.with(ce);
            }
         };
         return cf.transformClass(cm, t.andThen(ClassTransform.transformingMethodBodies(code)));
      }

      /** VMediaNatives signature for native method m: (Object this, args...) -> same return type. */
      static MethodTypeDesc helperType(MethodModel m) {
         MethodTypeDesc t = m.methodTypeSymbol();
         return m.flags().has(AccessFlag.STATIC) ? t : t.insertParameterTypes(0, ClassDesc.of("java.lang.Object"));
      }

      /** Clears ACC_NATIVE and adds the body {@code return VMediaNatives.name([this,] args...)}. */
      static MethodTransform stub(MethodModel m) {
         return new MethodTransform() {
            @Override
            public void accept(MethodBuilder mb, MethodElement e) {
               if (e instanceof AccessFlags af) mb.withFlags(af.flagsMask() & ~ClassFile.ACC_NATIVE);
               else mb.with(e);
            }

            @Override
            public void atEnd(MethodBuilder mb) {
               mb.withCode(cb -> {
                  int slot = 0;
                  if (!m.flags().has(AccessFlag.STATIC)) cb.aload(slot++);
                  for (ClassDesc p : m.methodTypeSymbol().parameterList()) {
                     TypeKind k = TypeKind.from(p);
                     cb.loadLocal(k, slot);
                     slot += k.slotSize();
                  }
                  cb.invokestatic(HELPER_DESC, m.methodName().stringValue(), helperType(m));
                  cb.return_(TypeKind.from(m.methodTypeSymbol().returnType()));
               });
            }
         };
      }

      /** Instruction-level patches; one instance per method body. */
      class CodeFixes implements CodeTransform {
         final Count c;
         final boolean libload, isDebug;
         ConstantInstruction pending;   // debug: an int constant that may be stored into Debug.MODE next

         CodeFixes(Count c, boolean libload, boolean isDebug) {
            this.c = c;
            this.libload = libload;
            this.isDebug = isDebug;
         }

         @Override
         public void accept(CodeBuilder cb, CodeElement e) {
            if (isDebug) {
               if (pending != null) {
                  ConstantInstruction p = pending;
                  pending = null;
                  if (e instanceof FieldInstruction f && f.opcode() == Opcode.PUTSTATIC
                        && f.owner().asInternalName().equals("com/ami/kvm/jviewer/Debug")
                        && f.name().stringValue().equals("MODE") && f.typeSymbol().descriptorString().equals("I")) {
                     cb.iconst_1();
                     cb.with(e);
                     c.debug++;
                     return;
                  }
                  cb.with(p);
               }
               if (e instanceof ConstantInstruction ci && ci.constantValue() instanceof Integer) {
                  pending = ci;
                  return;
               }
            }
            if (e instanceof ConstantInstruction ci && ci.constantValue() instanceof String s && PROTO.matcher(s).matches()
                  && s.contains("SSL")) {
               String keep = Stream.of(s.split(",")).map(String::trim).filter(x -> !x.startsWith("SSL"))
                     .collect(Collectors.joining(","));
               cb.ldc(keep.isEmpty() ? "TLSv1" : keep);
               c.tls++;
            } else if (e instanceof ExceptionCatch ec && ec.catchType().isPresent()
                  && ec.catchType().get().asInternalName().equals(AIOOBE)) {
               cb.exceptionCatch(ec.tryStart(), ec.tryEnd(), ec.handler(), IOOBE);
               c.catches++;
            } else if (libload && e instanceof InvokeInstruction inv && inv.opcode() == Opcode.INVOKESTATIC
                  && ((inv.owner().asInternalName().equals("java/lang/System")
                        && (inv.name().stringValue().equals("load") || inv.name().stringValue().equals("loadLibrary")))
                      || inv.name().stringValue().equals("getWrapperLibrary"))) {
               String n = inv.name().stringValue();
               if (helper.contains(n + inv.typeSymbol().descriptorString())) {
                  cb.invokestatic(HELPER_DESC, n, inv.typeSymbol());
                  c.libload++;
               } else {
                  warn("patch: no VMediaNatives." + n + inv.typeSymbol().descriptorString() + " to redirect "
                        + inv.owner().asInternalName() + "." + n + " to");
                  cb.with(e);
               }
            } else {
               cb.with(e);
            }
         }

         @Override
         public void atEnd(CodeBuilder cb) {
            if (pending != null) cb.with(pending);
         }
      }
   }
}
