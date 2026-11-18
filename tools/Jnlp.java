import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads a JViewer .jnlp (file or URL), downloads its jars from the codebase and prints how to launch it.
 *
 * <pre>java tools/Jnlp.java JNLP CACHE_DIR</pre>
 *
 * Jars go to CACHE_DIR/&lt;host_port&gt;/ (replaced on every run; native-library jars are skipped).
 * stdout is a list of NUL-terminated records: {@code D:<jar dir>}, {@code M:<main class>},
 * {@code P:<name>=<value>} (JNLP properties) and {@code A:<argument>} (in order).
 * Arguments usually include a session token, so they are only ever printed, never written to disk.
 * The BMC's certificate is self-signed, so it is not verified (the same as JViewer itself).
 */
public class Jnlp {
   public static void main(String[] a) throws Exception {
      if (a.length != 2) {
         System.err.println("usage: java tools/Jnlp.java JNLP_FILE_OR_URL CACHE_DIR");
         System.exit(2);
      }
      trustAll();
      URI src = a[0].matches("(?i)https?://.*") ? new URI(a[0]) : null;
      byte[] xml = src != null ? fetch(src.toURL()) : Files.readAllBytes(Path.of(a[0]));

      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setExpandEntityReferences(false);
      Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
      Element root = doc.getDocumentElement();
      if (!root.getTagName().equals("jnlp")) throw new IOException("not a JNLP file: <" + root.getTagName() + ">");

      String cb = root.getAttribute("codebase").trim();
      if (cb.isEmpty() || cb.contains("<%")) {
         throw new IOException("JNLP has no usable codebase (" + (cb.isEmpty() ? "missing" : "unexpanded template") +
               "); use the jviewer.jnlp the BMC web UI downloads when you launch the console");
      }
      if (!cb.endsWith("/")) cb += "/";
      URI base = src != null ? src.resolve(cb) : new URI(cb);
      if (!base.isAbsolute()) throw new IOException("relative codebase in a local JNLP file: " + cb);

      // main="true" jar first, then the rest in document order; <nativelib> is never needed
      List<String> jars = new ArrayList<>();
      NodeList nl = root.getElementsByTagName("jar");
      for (int i = 0; i < nl.getLength(); i++) {
         Element e = (Element) nl.item(i);
         String href = e.getAttribute("href").trim();
         if (href.isEmpty() || jars.contains(href)) continue;
         if ("true".equalsIgnoreCase(e.getAttribute("main"))) jars.add(0, href); else jars.add(href);
      }
      if (jars.isEmpty()) throw new IOException("JNLP lists no <jar> resources");

      Path cache = Path.of(a[1]).toAbsolutePath();
      String host = base.getHost() + "_" + (base.getPort() < 0 ? base.getScheme() : String.valueOf(base.getPort()));
      Path dir = cache.resolve(host.replaceAll("[^A-Za-z0-9._-]", "_"));
      Files.createDirectories(cache);
      Path tmp = Files.createTempDirectory(cache, ".fetch");
      try {
         int n = 0;
         for (String href : jars) {
            URI u = base.resolve(href);
            // keep the jnlp order in the file names: build.sh merges jars in name order, first wins
            String name = String.format("%02d-%s", n++, new File(u.getPath()).getName().replaceAll("[^A-Za-z0-9._-]", "_"));
            System.err.println("jnlp: fetching " + u);
            Files.write(tmp.resolve(name), fetch(u.toURL()));
         }
         rmrf(dir);
         Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
      } finally {
         rmrf(tmp);
      }

      PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
      rec(out, "D:" + dir);
      NodeList app = root.getElementsByTagName("application-desc");
      Element ad = app.getLength() > 0 ? (Element) app.item(0) : null;
      if (ad != null && !ad.getAttribute("main-class").trim().isEmpty()) rec(out, "M:" + ad.getAttribute("main-class").trim());
      NodeList props = root.getElementsByTagName("property");
      for (int i = 0; i < props.getLength(); i++) {
         Element p = (Element) props.item(i);
         if (!p.getAttribute("name").isEmpty()) rec(out, "P:" + p.getAttribute("name") + "=" + p.getAttribute("value"));
      }
      if (ad != null) {
         NodeList args = ad.getElementsByTagName("argument");
         for (int i = 0; i < args.getLength(); i++) rec(out, "A:" + args.item(i).getTextContent().trim());
      }
      out.flush();
   }

   private static void rec(PrintStream out, String s) {
      if (s.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in JNLP value");
      out.print(s);
      out.print('\0');
   }

   private static byte[] fetch(URL u) throws IOException {
      HttpURLConnection c = (HttpURLConnection) u.openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(60000);
      int rc = c.getResponseCode();
      if (rc != 200) throw new IOException(u + ": HTTP " + rc);
      try (InputStream in = c.getInputStream()) {
         return in.readAllBytes();
      }
   }

   private static void rmrf(Path p) throws IOException {
      if (!Files.exists(p)) return;
      try (Stream<Path> s = Files.walk(p)) {
         for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(x);
      }
   }

   private static void trustAll() throws Exception {
      TrustManager[] tm = {new X509TrustManager() {
         public void checkClientTrusted(X509Certificate[] c, String t) {}
         public void checkServerTrusted(X509Certificate[] c, String t) {}
         public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }};
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tm, null);
      HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
      HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
   }
}
