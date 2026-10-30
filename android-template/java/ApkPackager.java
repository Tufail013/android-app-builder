package __PKG__;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.security.auth.x500.X500Principal;

/**
 * Shell Packager, v1 — original code, no external signing library.
 *
 * What it does, in plain steps:
 *  1. Copies the running app's own APK (its shell).
 *  2. Replaces assets/www/index.html inside that copy with the user's HTML.
 *  3. Drops the old JAR (v1) signature files, because they signed the old HTML.
 *  4. Signs the new copy with APK Signature Scheme v2, using a self-signed
 *     RSA key that lives in AndroidKeyStore under alias "shellpackager_signer".
 *     The same key is reused for every output, so outputs can update each
 *     other. Clearing the packager app's data deletes the key, and later
 *     outputs will then carry a different signer.
 *  5. Saves the result to Downloads through MediaStore, and remembers its
 *     Uri so the UI can offer to open (install) it.
 *
 * Signing: v2 ONLY, algorithm 0x0103 (RSASSA-PKCS1-v1_5, SHA2-256).
 * This is deliberate. A v1-only APK is NOT installable for this project's
 * targetSdk 36: Android 11+ requires a v2 (or newer) signature for apps
 * targeting API 30+. A v2 signature is checked on Android 7.0+ (API 24+),
 * which covers this project's minSdk 24, so no v1 signature is needed here.
 * The v2 digest follows the public AOSP v2 description: every section
 * (ZIP entries, Central Directory, End of Central Directory) is split into
 * 1 MB chunks, each chunk digest is SHA-256(0xa5 || uint32 length || chunk),
 * and the stored digest is SHA-256(0x5a || uint32 chunk count || all chunk
 * digests in order). For the End of Central Directory section, the Central
 * Directory offset field is treated as the signing-block offset when the
 * digest is computed, as the spec requires.
 *
 * Honest v1 limits (do not hide these from the user):
 *  - The output keeps the packager's package name and launcher name, because
 *    renaming those means rewriting the binary AndroidManifest and the
 *    compiled string resources, which v1 does not do. So the output cannot
 *    be installed on a phone that still has the packager installed (same
 *    package, different signer). It installs cleanly on a phone without the
 *    packager, which is the normal sharing case. The output file name comes
 *    from the app-name field, but the launcher name shown on the phone is
 *    still the packager's name in v1.
 *  - ZIP alignment extra fields are not preserved. The template has no
 *    native (.so) libraries, so this does not affect installation.
 *  - This code has NOT been run on a phone yet. The generate step only
 *    proves the project generates; a real phone install test is still
 *    required before anyone is told it works.
 */
public class ApkPackager {
  static final String KEY_ALIAS = "shellpackager_signer";
  static final int SIG_ALG_RSA_PKCS1_SHA256 = 0x0103;
  static final int V2_BLOCK_ID = 0x7109871a;
  static final int CHUNK = 1024 * 1024;
  static final String TARGET_ENTRY = "assets/www/index.html";

  // ---- little-endian helpers (v2 uses little-endian everywhere) ----
  static byte[] le32(int v) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
  }
  static byte[] le64(long v) {
    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
  }
  static int readLe32(byte[] b, int off) {
    return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }
  static byte[] lp(byte[] data) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    try { o.write(le32(data.length)); o.write(data); } catch (Exception e) { }
    return o.toByteArray();
  }
  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    try { for (byte[] p : parts) o.write(p); } catch (Exception e) { }
    return o.toByteArray();
  }

  static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    in.close();
    return o.toByteArray();
  }

  static boolean isOldSignatureFile(String name) {
    if (!name.startsWith("META-INF/")) return false;
    String up = name.toUpperCase();
    return up.endsWith(".MF") || up.endsWith(".SF") || up.endsWith(".RSA")
        || up.endsWith(".DSA") || up.endsWith(".EC");
  }

  static String cleanFileName(String raw) {
    if (raw == null) raw = "";
    String name = new File(raw).getName().trim();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.') sb.append(c);
      else if (c == ' ') sb.append('_');
    }
    String out = sb.toString();
    while (out.startsWith(".")) out = out.substring(1);
    if (out.isEmpty()) out = "MyApp";
    if (!out.toLowerCase().endsWith(".apk")) out = out + ".apk";
    if (out.length() > 80) out = out.substring(0, 76) + ".apk";
    return out;
  }

  // ---- AndroidKeyStore key, created once and reused ----
  static class KeyAndCert {
    PrivateKey priv; byte[] cert; byte[] pub;
  }
  static KeyAndCert getKey(Context ctx) throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(KEY_ALIAS)) {
      Calendar start = Calendar.getInstance();
      Calendar end = Calendar.getInstance();
      end.add(Calendar.YEAR, 25);
      KeyPairGenerator gen = KeyPairGenerator.getInstance(
          KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
      KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
          KeyProperties.PURPOSE_SIGN)
          .setKeySize(2048)
          .setDigests(KeyProperties.DIGEST_SHA256)
          .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
          .setCertificateSubject(new X500Principal("CN=Shell Packager"))
          .setCertificateSerialNumber(BigInteger.ONE)
          .setCertificateNotBefore(start.getTime())
          .setCertificateNotAfter(end.getTime())
          .setUserAuthenticationRequired(false)
          .build();
      gen.initialize(spec);
      gen.generateKeyPair();
    }
    KeyAndCert kc = new KeyAndCert();
    kc.priv = (PrivateKey) ks.getKey(KEY_ALIAS, null);
    Certificate cert = ks.getCertificate(KEY_ALIAS);
    if (kc.priv == null || cert == null) throw new Exception("packager signing key not available");
    kc.cert = cert.getEncoded();
    kc.pub = cert.getPublicKey().getEncoded();
    return kc;
  }

  // ---- v2 content digest over sections 1, 3 and 4 ----
  static byte[] contentDigest(byte[] s1, byte[] s3, byte[] s4) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    List<byte[]> chunks = new ArrayList<byte[]>();
    byte[][] sections = new byte[][] { s1, s3, s4 };
    for (byte[] sec : sections) {
      for (int off = 0; off < sec.length || (sec.length == 0 && off == 0); ) {
        int len = Math.min(CHUNK, sec.length - off);
        if (sec.length == 0) len = 0;
        MessageDigest cm = MessageDigest.getInstance("SHA-256");
        cm.update((byte) 0xa5);
        cm.update(le32(len));
        if (len > 0) cm.update(sec, off, len);
        chunks.add(cm.digest());
        if (sec.length == 0) break;
        off += len;
        if (len == 0) break;
      }
    }
    md.update((byte) 0x5a);
    md.update(le32(chunks.size()));
    for (byte[] c : chunks) md.update(c);
    return md.digest();
  }

  // Find the End of Central Directory record (signature 0x06054b50) by
  // scanning backwards from the end of the file. Returns its offset, or -1.
  static int findEocd(byte[] b) {
    int start = Math.max(0, b.length - 65557);
    for (int i = b.length - 22; i >= start; i--) {
      if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06) {
        int commentLen = ((b[i + 21] & 0xff) << 8) | (b[i + 20] & 0xff);
        if (i + 22 + commentLen == b.length) return i;
      }
    }
    return -1;
  }

  public static String build(Context ctx, String html, String rawName) {
    try {
      if (html == null || html.trim().isEmpty()) return "error: Paste or choose HTML first.";
      String fileName = cleanFileName(rawName);
      byte[] newHtml = html.getBytes("UTF-8");
      String source = ctx.getApplicationInfo().sourceDir;
      if (source == null) return "error: Own APK path not found.";

      // 1. Rewrite the ZIP, replacing only assets/www/index.html, and
      //    dropping old v1 signature files. No other entry is changed.
      ByteArrayOutputStream unsignedOut = new ByteArrayOutputStream();
      ZipOutputStream zos = new ZipOutputStream(unsignedOut);
      ZipFile zip = new ZipFile(source);
      try {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
          ZipEntry e = en.nextElement();
          String name = e.getName();
          if (name.endsWith("/")) continue;
          if (isOldSignatureFile(name)) continue;
          byte[] data = TARGET_ENTRY.equals(name) ? newHtml : readAll(zip.getInputStream(e));
          ZipEntry out = new ZipEntry(name);
          if (e.getTime() > 0) out.setTime(e.getTime());
          if (e.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            out.setMethod(ZipEntry.STORED);
            out.setSize(data.length);
            out.setCompressedSize(data.length);
            out.setCrc(crc.getValue());
          } else {
            out.setMethod(ZipEntry.DEFLATED);
          }
          zos.putNextEntry(out);
          zos.write(data);
          zos.closeEntry();
        }
      } finally {
        zos.close();
        zip.close();
      }
      byte[] unsigned = unsignedOut.toByteArray();

      // 2. Split the unsigned ZIP into sections 1, 3 and 4.
      int eocd = findEocd(unsigned);
      if (eocd < 0) return "error: Could not find the ZIP directory in the shell APK.";
      int cdSize = readLe32(unsigned, eocd + 12);
      int cdOffset = readLe32(unsigned, eocd + 16);
      if (cdOffset < 0 || cdOffset + cdSize > unsigned.length) return "error: Bad ZIP directory in the shell APK.";
      byte[] s1 = new byte[cdOffset];
      System.arraycopy(unsigned, 0, s1, 0, cdOffset);
      byte[] s3 = new byte[cdSize];
      System.arraycopy(unsigned, cdOffset, s3, 0, cdSize);
      byte[] s4 = new byte[unsigned.length - (cdOffset + cdSize)];
      System.arraycopy(unsigned, cdOffset + cdSize, s4, 0, s4.length);

      // 3. v2 signing block.
      KeyAndCert kc = getKey(ctx);
      // For the digest, the EOCD's Central Directory offset is treated as
      // the signing-block offset, which is exactly s1.length here.
      byte[] s4ForDigest = s4.clone();
      if (s4ForDigest.length >= 20) System.arraycopy(le32(s1.length), 0, s4ForDigest, 16, 4);
      byte[] digest = contentDigest(s1, s3, s4ForDigest);

      byte[] digestEntry = lp(concat(le32(SIG_ALG_RSA_PKCS1_SHA256), lp(digest)));
      byte[] certEntry = lp(kc.cert);
      byte[] signedData = concat(lp(digestEntry), lp(certEntry), le32(0));

      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(kc.priv);
      sig.update(signedData);
      byte[] sigBytes = sig.sign();
      byte[] sigEntry = lp(concat(le32(SIG_ALG_RSA_PKCS1_SHA256), lp(sigBytes)));
      byte[] signer = concat(lp(signedData), lp(sigEntry), lp(kc.pub));
      byte[] v2Value = lp(lp(signer));
      byte[] pair = concat(le64(4L + v2Value.length), le32(V2_BLOCK_ID), v2Value);
      long blockSize = pair.length + 8L + 16L;
      byte[] signingBlock = concat(le64(blockSize), pair, le64(blockSize),
          "APK Sig Block 42".getBytes("UTF-8"));

      // 4. Assemble: section 1 + signing block + section 3 + EOCD with the
      //    real Central Directory offset (after the signing block).
      int realCdOffset = s1.length + signingBlock.length;
      byte[] s4Final = s4.clone();
      if (s4Final.length >= 20) System.arraycopy(le32(realCdOffset), 0, s4Final, 16, 4);
      byte[] fin = concat(s1, signingBlock, s3, s4Final);

      // 5. Save to Downloads. MediaStore is the normal path on Android 10+;
      //    the app's own Downloads folder is only a fallback, and a file
      //    saved there cannot be opened by the installer without a
      //    FileProvider, which this template does not configure.
      String savedWhere;
      Uri savedUri = null;
      if (Build.VERSION.SDK_INT >= 29) {
        try {
          ContentValues v = new ContentValues();
          v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
          v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
          v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
          savedUri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
          if (savedUri != null) {
            OutputStream os = ctx.getContentResolver().openOutputStream(savedUri);
            os.write(fin);
            os.close();
            savedWhere = "Downloads/" + fileName;
          } else {
            savedUri = null;
            savedWhere = null;
          }
        } catch (Exception e) {
          savedUri = null;
          savedWhere = null;
        }
      } else {
        savedWhere = null;
      }
      if (savedUri == null) {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) dir.mkdirs();
        File outFile = new File(dir, fileName);
        FileOutputStream os = new FileOutputStream(outFile);
        os.write(fin);
        os.close();
        savedWhere = outFile.getPath() + " (app folder, not shared Downloads)";
      }
      SharedPreferences prefs = ctx.getSharedPreferences("packager", 0);
      SharedPreferences.Editor ed = prefs.edit();
      ed.putString("last_name", fileName);
      if (savedUri != null) ed.putString("last_uri", savedUri.toString());
      else ed.remove("last_uri");
      ed.apply();
      return "ok|" + savedWhere + "|" + (savedUri != null ? savedUri.toString() : "");
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  public static String openLast(Context ctx) {
    try {
      SharedPreferences prefs = ctx.getSharedPreferences("packager", 0);
      String uriStr = prefs.getString("last_uri", null);
      if (uriStr == null) return "error: No standalone APK saved yet. Build one first.";
      Uri uri = Uri.parse(uriStr);
      Intent in = new Intent(Intent.ACTION_VIEW);
      in.setDataAndType(uri, "application/vnd.android.package-archive");
      in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(in);
      return "ok";
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }
}
