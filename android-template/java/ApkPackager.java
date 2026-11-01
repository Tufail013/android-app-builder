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
 * Shell Packager, v2 — original code, no external signing library.
 *
 * v2 adds manifest renaming (see renameManifest): the output APK's
 * manifest package, its custom permission name and its provider
 * authority are changed to the user's package, and the application
 * label is changed from a resource reference to the user's app name
 * as a literal string. Component names in the manifest are already
 * fully qualified in this template (for example
 * com.example.shellpackager.MainActivity), so they are left alone and
 * still resolve to the classes in the dex, even though the manifest
 * package has changed. The dex class names are NOT renamed, and the
 * compiled resources (resources.arsc) are NOT rebuilt: the label
 * override in the manifest is what the home screen shows, and all
 * resource lookups in this template use compiled R constants, not
 * getIdentifier() with a package name. If a future template adds a
 * FileProvider, a custom permission used by another app, or a
 * getIdentifier() lookup, this section must be revisited.
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
 * Honest v2 limits (do not hide these from the user):
 *  - The output keeps the packager's dex class names and its icon.
 *    Only the manifest package, the custom permission name, the
 *    provider authority, the home-screen label and the file name
 *    change. The icon shown on the home screen is still the
 *    packager's icon in v2, because icons live in compiled resources.
 *  - The output's versionCode stays at the packager's versionCode.
 *    A later output with the same package and the same signer can
 *    replace the first one on a phone, but it is not a Play-style
 *    version upgrade.
 *  - If the user asks for the packager's own package, v2 refuses:
 *    that output could never be installed alongside the packager.
 *  - ZIP alignment extra fields are not preserved. The template has no
 *    native (.so) libraries, so this does not affect installation.
 *  - This code has NOT been compiled on this machine (no Java here)
 *    and NOT been run on a phone yet. A real phone install test is
 *    still required before anyone is told v2 renaming works.
 *
 * App Cloner v1 (added on top of v2, same file, same signing code):
 * cloneApk() clones a template-built APK file the user already has
 * in Downloads. It does NOT copy a logged-in account, it does NOT
 * rename dex class names, and it does NOT rebuild resources.arsc,
 * so the clone keeps the original app's icon in v1. It only renames
 * the manifest package / label / permission / authority with the
 * same renameManifest() logic as v2, replaces assets/config.json
 * package/name only if that file exists, drops old v1 signature
 * files, and re-signs v2-only with a separate AndroidKeyStore key
 * (alias "appcloner_signer", CN=App Cloner). It is honest about
 * its scope: it works best for the user's own template-built apps
 * (fully-qualified components, like Shell Packager outputs). Big
 * apps that check their own signature (Gmail, WhatsApp, banking)
 * will refuse or crash after any re-signing, and a clone always
 * starts fresh — it never carries over a login. Do not use this
 * to clone Muse accounts or any signature-checking app.
 */
public class ApkPackager {
  static final String KEY_ALIAS = "shellpackager_signer";
  static final String CLONE_KEY_ALIAS = "appcloner_signer";
  static final String CLONE_CERT_CN = "CN=App Cloner";
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
    return getKeyFor(ctx, KEY_ALIAS, "CN=Shell Packager");
  }
  // Reusable key helper: same AndroidKeyStore pattern, different
  // alias / subject for the cloner, so packager and cloner outputs
  // are signed by different keys and cannot update each other.
  static KeyAndCert getKeyFor(Context ctx, String alias, String certCn) throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(alias)) {
      Calendar start = Calendar.getInstance();
      Calendar end = Calendar.getInstance();
      end.add(Calendar.YEAR, 25);
      KeyPairGenerator gen = KeyPairGenerator.getInstance(
          KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
      KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
          KeyProperties.PURPOSE_SIGN)
          .setKeySize(2048)
          .setDigests(KeyProperties.DIGEST_SHA256)
          .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
          .setCertificateSubject(new X500Principal(certCn))
          .setCertificateSerialNumber(BigInteger.ONE)
          .setCertificateNotBefore(start.getTime())
          .setCertificateNotAfter(end.getTime())
          .setUserAuthenticationRequired(false)
          .build();
      gen.initialize(spec);
      gen.generateKeyPair();
    }
    KeyAndCert kc = new KeyAndCert();
    kc.priv = (PrivateKey) ks.getKey(alias, null);
    Certificate cert = ks.getCertificate(alias);
    if (kc.priv == null || cert == null) throw new Exception("signing key not available");
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


  // ---- v2: app-name / package cleaning ----
  static String cleanAppName(String raw) {
    if (raw == null) return "MyApp";
    String t = raw.trim();
    if (t.isEmpty()) return "MyApp";
    if (t.length() > 50) t = t.substring(0, 50).trim();
    if (t.isEmpty()) return "MyApp";
    return t;
  }
  static String cleanPackage(String input, String label) {
    String pkg = input == null ? "" : input.trim().toLowerCase();
    if (pkg.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < label.length(); i++) {
        char c = Character.toLowerCase(label.charAt(i));
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
      }
      if (sb.length() == 0) sb.append("myapp");
      pkg = "com.example." + sb.toString();
    }
    if (pkg.length() > 100) return null;
    String[] parts = pkg.split("\\.");
    if (parts.length < 2) return null;
    for (String part : parts) {
      if (part.isEmpty()) return null;
      char first = part.charAt(0);
      if (first < 'a' || first > 'z') return null;
      for (int i = 0; i < part.length(); i++) {
        char c = part.charAt(i);
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return null;
      }
    }
    return pkg;
  }

  // ---- v2: binary AndroidManifest.xml renaming ----
  static int readU16(byte[] b, int off) {
    return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
  }
  static void writeLe16(ByteArrayOutputStream o, int v) {
    o.write(v & 0xff); o.write((v >> 8) & 0xff);
  }
  static void writeLe32(ByteArrayOutputStream o, int v) {
    o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
  }
  static void putLe32(byte[] b, int off, int v) {
    b[off] = (byte) (v & 0xff); b[off + 1] = (byte) ((v >> 8) & 0xff);
    b[off + 2] = (byte) ((v >> 16) & 0xff); b[off + 3] = (byte) ((v >> 24) & 0xff);
  }
  static void setStringAttr(byte[] b, int attrOff, int newIdx) {
    putLe32(b, attrOff + 8, newIdx); // rawValue
    b[attrOff + 12 + 3] = 3; // dataType = STRING
    putLe32(b, attrOff + 12 + 4, newIdx); // data
  }
  static byte[] renameManifest(byte[] orig, String oldPkg, String newPkg, String newLabel) throws Exception {
    if (orig.length < 8 || orig[0] != 0x03 || orig[1] != 0x00 || orig[2] != 0x08 || orig[3] != 0x00)
      throw new Exception("AndroidManifest.xml is not a binary manifest");
    int poolOff = 8;
    if (readU16(orig, poolOff) != 0x0001) throw new Exception("AndroidManifest.xml has no string pool");
    int poolHeaderSize = readU16(orig, poolOff + 2);
    int poolSize = readLe32(orig, poolOff + 4);
    int count = readLe32(orig, poolOff + 8);
    int styleCount = readLe32(orig, poolOff + 12);
    int flags = readLe32(orig, poolOff + 16);
    int stringsOff = readLe32(orig, poolOff + 20);
    if (poolHeaderSize != 28) throw new Exception("Unexpected manifest string-pool header");
    if (styleCount != 0) throw new Exception("Manifest string pool has styles, v2 renaming does not support it");
    if ((flags & 0x100) != 0) throw new Exception("Manifest string pool is UTF-8, v2 renaming supports UTF-16 only");
    if (poolOff + poolSize > orig.length) throw new Exception("Manifest string pool runs past the file");
    List<String> strings = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      int strOff = readLe32(orig, poolOff + 28 + i * 4);
      int pos = poolOff + stringsOff + strOff;
      int len = readU16(orig, pos); pos += 2;
      if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | readU16(orig, pos); pos += 2; }
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < len; j++) sb.append((char) readU16(orig, pos + j * 2));
      strings.add(sb.toString());
    }
    int oldPermIdx = strings.indexOf(oldPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    int oldAuthIdx = strings.indexOf(oldPkg + ".androidx-startup");
    List<String> nstrings = new ArrayList<String>(strings);
    int newPkgIdx = nstrings.indexOf(newPkg);
    if (newPkgIdx < 0) { nstrings.add(newPkg); newPkgIdx = nstrings.size() - 1; }
    int newLabelIdx = nstrings.indexOf(newLabel);
    if (newLabelIdx < 0) { nstrings.add(newLabel); newLabelIdx = nstrings.size() - 1; }
    int newPermIdx = -1;
    if (oldPermIdx >= 0) {
      String np = newPkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
      newPermIdx = nstrings.indexOf(np);
      if (newPermIdx < 0) { nstrings.add(np); newPermIdx = nstrings.size() - 1; }
    }
    int newAuthIdx = -1;
    if (oldAuthIdx >= 0) {
      String na = newPkg + ".androidx-startup";
      newAuthIdx = nstrings.indexOf(na);
      if (newAuthIdx < 0) { nstrings.add(na); newAuthIdx = nstrings.size() - 1; }
    }
    // Rebuild the string pool with the same UTF-16 encoding.
    ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
    List<Integer> newOffsets = new ArrayList<Integer>();
    for (String st : nstrings) {
      newOffsets.add(dataOut.size());
      int len = st.length();
      if (len < 0x8000) writeLe16(dataOut, len);
      else { writeLe16(dataOut, 0x8000 | (len >> 16)); writeLe16(dataOut, len & 0xffff); }
      for (int j = 0; j < len; j++) writeLe16(dataOut, st.charAt(j));
      writeLe16(dataOut, 0);
    }
    while (dataOut.size() % 4 != 0) dataOut.write(0);
    int newStringsOff = 28 + nstrings.size() * 4;
    int newPoolSize = newStringsOff + dataOut.size();
    ByteArrayOutputStream poolOut = new ByteArrayOutputStream();
    writeLe16(poolOut, 0x0001); writeLe16(poolOut, 28); writeLe32(poolOut, newPoolSize);
    writeLe32(poolOut, nstrings.size()); writeLe32(poolOut, 0); writeLe32(poolOut, 0);
    writeLe32(poolOut, newStringsOff); writeLe32(poolOut, 0);
    for (int off : newOffsets) writeLe32(poolOut, off);
    poolOut.write(dataOut.toByteArray());
    // Rebuild the file: original 8-byte file header, then the new
    // pool, then everything after the old pool. Omitting the header
    // would shift every later chunk by 8 bytes and break the file.
    ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
    fileOut.write(orig, 0, 8);
    fileOut.write(poolOut.toByteArray());
    fileOut.write(orig, poolOff + poolSize, orig.length - (poolOff + poolSize));
    byte[] rebuilt = fileOut.toByteArray();
    putLe32(rebuilt, 4, rebuilt.length);
    // Walk the XML chunks and change the package, label, permission and authority.
    boolean foundPkg = false, foundLabel = false;
    int off = 8 + poolOut.size();
    while (off + 8 <= rebuilt.length) {
      int type = readU16(rebuilt, off);
      int chunkSize = readLe32(rebuilt, off + 4);
      if (chunkSize <= 0 || off + chunkSize > rebuilt.length) break;
      if (type == 0x0102) {
        int nameIdx = readLe32(rebuilt, off + 20);
        String elem = (nameIdx >= 0 && nameIdx < nstrings.size()) ? nstrings.get(nameIdx) : "";
        int attrStart = readU16(rebuilt, off + 24);
        int attrSize = readU16(rebuilt, off + 26);
        int attrCount = readU16(rebuilt, off + 28);
        int attrsBase = off + 16 + attrStart;
        for (int j = 0; j < attrCount; j++) {
          int aOff = attrsBase + j * attrSize;
          if (aOff + 20 > rebuilt.length) break;
          int aNameIdx = readLe32(rebuilt, aOff + 4);
          String aName = (aNameIdx >= 0 && aNameIdx < nstrings.size()) ? nstrings.get(aNameIdx) : "";
          int dataType = rebuilt[aOff + 12 + 3] & 0xff;
          int dataVal = readLe32(rebuilt, aOff + 12 + 4);
          if (elem.equals("manifest") && aName.equals("package")) { setStringAttr(rebuilt, aOff, newPkgIdx); foundPkg = true; }
          else if (elem.equals("application") && aName.equals("label")) { setStringAttr(rebuilt, aOff, newLabelIdx); foundLabel = true; }
          else if ((elem.equals("permission") || elem.equals("uses-permission")) && aName.equals("name") && dataType == 3 && oldPermIdx >= 0 && dataVal == oldPermIdx) setStringAttr(rebuilt, aOff, newPermIdx);
          else if (elem.equals("provider") && aName.equals("authorities") && dataType == 3 && oldAuthIdx >= 0 && dataVal == oldAuthIdx) setStringAttr(rebuilt, aOff, newAuthIdx);
        }
      }
      off += chunkSize;
    }
    if (!foundPkg) throw new Exception("Manifest package attribute not found");
    if (!foundLabel) throw new Exception("Application label attribute not found");
    return rebuilt;
  }
  static String replaceConfig(String json, String oldPkg, String newPkg, String oldName, String newLabel) {
    if (json == null) return json;
    String out = json.replace(oldPkg, newPkg);
    // Only the app-name field, not every occurrence of the words.
    String esc = newLabel.replace("\\", "\\\\").replace("\"", "\\\"");
    out = out.replace("\"name\": \"" + oldName + "\"", "\"name\": \"" + esc + "\"");
    return out;
  }

  // ---- App Cloner v1: parse the original package from a binary manifest ----
  static String parseManifestPackage(byte[] orig) throws Exception {
    if (orig.length < 8 || orig[0] != 0x03 || orig[1] != 0x00 || orig[2] != 0x08 || orig[3] != 0x00)
      throw new Exception("AndroidManifest.xml is not a binary manifest");
    int poolOff = 8;
    if (readU16(orig, poolOff) != 0x0001) throw new Exception("AndroidManifest.xml has no string pool");
    int poolHeaderSize = readU16(orig, poolOff + 2);
    int poolSize = readLe32(orig, poolOff + 4);
    int count = readLe32(orig, poolOff + 8);
    int styleCount = readLe32(orig, poolOff + 12);
    int flags = readLe32(orig, poolOff + 16);
    int stringsOff = readLe32(orig, poolOff + 20);
    if (poolHeaderSize != 28) throw new Exception("Unexpected manifest string-pool header");
    if (styleCount != 0) throw new Exception("Manifest string pool has styles, renaming does not support it");
    if ((flags & 0x100) != 0) throw new Exception("Manifest string pool is UTF-8, renaming supports UTF-16 only");
    if (poolOff + poolSize > orig.length) throw new Exception("Manifest string pool runs past the file");
    List<String> strings = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      int strOff = readLe32(orig, poolOff + 28 + i * 4);
      int pos = poolOff + stringsOff + strOff;
      int len = readU16(orig, pos); pos += 2;
      if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | readU16(orig, pos); pos += 2; }
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < len; j++) sb.append((char) readU16(orig, pos + j * 2));
      strings.add(sb.toString());
    }
    int off = 8 + poolSize;
    while (off + 8 <= orig.length) {
      int type = readU16(orig, off);
      int chunkSize = readLe32(orig, off + 4);
      if (chunkSize <= 0 || off + chunkSize > orig.length) break;
      if (type == 0x0102) {
        int nameIdx = readLe32(orig, off + 20);
        String elem = (nameIdx >= 0 && nameIdx < strings.size()) ? strings.get(nameIdx) : "";
        if (elem.equals("manifest")) {
          int attrStart = readU16(orig, off + 24);
          int attrSize = readU16(orig, off + 26);
          int attrCount = readU16(orig, off + 28);
          int attrsBase = off + 16 + attrStart;
          for (int j = 0; j < attrCount; j++) {
            int aOff = attrsBase + j * attrSize;
            if (aOff + 20 > orig.length) break;
            int aNameIdx = readLe32(orig, aOff + 4);
            String aName = (aNameIdx >= 0 && aNameIdx < strings.size()) ? strings.get(aNameIdx) : "";
            if (!aName.equals("package")) continue;
            int dataType = orig[aOff + 12 + 3] & 0xff;
            int dataVal = readLe32(orig, aOff + 12 + 4);
            int rawVal = readLe32(orig, aOff + 8);
            int idx = (dataType == 3) ? dataVal : rawVal;
            if (idx >= 0 && idx < strings.size()) return strings.get(idx);
            throw new Exception("Manifest package attribute not found");
          }
        }
      }
      off += chunkSize;
    }
    throw new Exception("Manifest package attribute not found");
  }

  // Replace only the "name" field in assets/config.json, if present.
  // Unlike replaceConfig() (which knows the old packager name), the
  // clone does not know the source app's old name, so it finds the
  // first "name": "..." value instead of matching an exact old name.
  static String replaceConfigName(String json, String newLabel) {
    if (json == null) return json;
    int key = json.indexOf("\"name\"");
    if (key < 0) return json;
    int colon = json.indexOf(':', key + 6);
    if (colon < 0) return json;
    int open = json.indexOf('"', colon + 1);
    if (open < 0) return json;
    int close = json.indexOf('"', open + 1);
    if (close < 0) return json;
    String esc = newLabel.replace("\\", "\\\\").replace("\"", "\\\"");
    return json.substring(0, open + 1) + esc + json.substring(close);
  }

  // Find a source APK file by name in Downloads. MediaStore is the
  // normal path on Android 10+; the app's own Downloads folder is
  // only a fallback. Returns a readable File, or null.
  static File findSourceApk(Context ctx, String fileName) {
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        String[] proj = new String[] { MediaStore.Downloads._ID };
        android.database.Cursor cur = ctx.getContentResolver().query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
            MediaStore.Downloads.DISPLAY_NAME + "=?", new String[] { fileName }, null);
        if (cur != null) {
          try {
            if (cur.moveToFirst()) {
              long id = cur.getLong(0);
              Uri uri = android.content.ContentUris.withAppendedId(
                  MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
              InputStream in = ctx.getContentResolver().openInputStream(uri);
              if (in != null) {
                File tmp = new File(ctx.getCacheDir(), "clone_src.apk");
                FileOutputStream os = new FileOutputStream(tmp);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                in.close();
                return tmp;
              }
            }
          } finally { cur.close(); }
        }
      } catch (Exception e) { /* fall through to the folder fallback */ }
    }
    try {
      File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
      if (dir != null) {
        File f = new File(dir, fileName);
        if (f.isFile() && f.canRead()) return f;
      }
    } catch (Exception e) { }
    return null;
  }

  public static String build(Context ctx, String html, String rawName) {
    return build2(ctx, html, rawName, "");
  }

  public static String build2(Context ctx, String html, String appName, String pkgInput) {
    try {
      String label = cleanAppName(appName);
      String origPkg = ctx.getPackageName();
      if (origPkg == null || origPkg.isEmpty()) return "error: Own package not found.";
      String newPkg = cleanPackage(pkgInput, label);
      if (newPkg == null) return "error: Package name must look like com.example.myapp, lower-case letters, digits and _ only, at least two parts.";
      if (newPkg.equals(origPkg)) return "error: Choose a different app name or package. That package is Shell Packager itself, so the output could not be installed next to it.";
      String fileName = cleanFileName(label);
      return buildRenamed(ctx, html, label, origPkg, newPkg, fileName);
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  // ---- Reusable v2 signing + saving (used by both packager and cloner) ----
  // Takes an unsigned ZIP, signs it v2-only, saves it to Downloads,
  // remembers its Uri under prefsName, and returns ok|where|uri.
  static String signAndSave(Context ctx, byte[] unsigned, String fileName,
      String keyAlias, String certCn, String prefsName) throws Exception {
      // 2. Split the unsigned ZIP into sections 1, 3 and 4.
      int eocd = findEocd(unsigned);
      if (eocd < 0) return "error: Could not find the ZIP directory in the APK.";
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
      KeyAndCert kc = getKeyFor(ctx, keyAlias, certCn);
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
      SharedPreferences prefs = ctx.getSharedPreferences(prefsName, 0);
      SharedPreferences.Editor ed = prefs.edit();
      ed.putString("last_name", fileName);
      if (savedUri != null) ed.putString("last_uri", savedUri.toString());
      else ed.remove("last_uri");
      ed.apply();
      return "ok|" + savedWhere + "|" + (savedUri != null ? savedUri.toString() : "");
  }

  static String buildRenamed(Context ctx, String html, String label, String origPkg, String newPkg, String fileName) {
    try {
      if (html == null || html.trim().isEmpty()) return "error: Paste or choose HTML first.";
      byte[] newHtml = html.getBytes("UTF-8");
      String source = ctx.getApplicationInfo().sourceDir;
      if (source == null) return "error: Own APK path not found.";

      // 1. Rewrite the ZIP: replace assets/www/index.html, rewrite
      //    AndroidManifest.xml (v2 renaming) and assets/config.json,
      //    and drop old v1 signature files. No other entry is changed.
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
          byte[] data;
          if (TARGET_ENTRY.equals(name)) data = newHtml;
          else if (name.equals("AndroidManifest.xml")) data = renameManifest(readAll(zip.getInputStream(e)), origPkg, newPkg, label);
          else if (name.equals("assets/config.json")) data = replaceConfig(new String(readAll(zip.getInputStream(e)), "UTF-8"), origPkg, newPkg, "Shell Packager", label).getBytes("UTF-8");
          else data = readAll(zip.getInputStream(e));
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

      return signAndSave(ctx, unsigned, fileName, KEY_ALIAS, "CN=Shell Packager", "packager");
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  // ---- App Cloner v1 ----
  // Clone a template-built APK already in Downloads. The source APK
  // itself is never changed; a new APK is written to Downloads.
  // What changes: manifest package, label, permission, authority
  // (same renameManifest() as v2), and assets/config.json package
  // and name, only if that file exists. What does NOT change:
  // assets/www/index.html (kept from the source), the icon,
  // resources.arsc, dex class names, or versionCode. A clone
  // therefore starts fresh and does NOT copy a logged-in account.
  // Refuses: the cloner's own package, the source app's own
  // package (a clone must differ, or it could not sit next to
  // the original), a missing source file, and any manifest
  // renameManifest() itself refuses (UTF-8 pool, styles).
  public static String cloneApk(Context ctx, String sourceName, String appName, String pkgInput) {
    try {
      String label = cleanAppName(appName);
      String clonerPkg = ctx.getPackageName();
      if (clonerPkg == null || clonerPkg.isEmpty()) return "error: Own package not found.";
      String newPkg = cleanPackage(pkgInput, label);
      if (newPkg == null) return "error: Package name must look like com.example.myapp, lower-case letters, digits and _ only, at least two parts.";
      if (newPkg.equals(clonerPkg)) return "error: Choose a different app name or package. That package is App Cloner itself, so the clone could not be installed next to it.";
      String srcName = cleanFileName(sourceName);
      File srcFile = findSourceApk(ctx, srcName);
      if (srcFile == null) return "error: " + srcName + " not found in Downloads. Put that APK file in Downloads first, then try again.";
      // Read the source manifest first, to learn its original package.
      String oldPkg = null;
      ZipFile probe = new ZipFile(srcFile);
      try {
        ZipEntry man = probe.getEntry("AndroidManifest.xml");
        if (man == null) return "error: That APK has no AndroidManifest.xml, so it cannot be cloned.";
        oldPkg = parseManifestPackage(readAll(probe.getInputStream(man)));
      } finally { probe.close(); }
      if (oldPkg == null || oldPkg.isEmpty()) return "error: Could not read the original package from that APK.";
      if (newPkg.equals(oldPkg)) return "error: Choose a different app name or package. That package is the original app's package, so the clone could not be installed next to the original.";
      String fileName = cleanFileName(label);
      // Rewrite the ZIP: rename the manifest and config.json only.
      // Every other entry, including assets/www/index.html and the
      // icon, is copied unchanged. Old v1 signature files are dropped.
      ByteArrayOutputStream unsignedOut = new ByteArrayOutputStream();
      ZipOutputStream zos = new ZipOutputStream(unsignedOut);
      ZipFile zip = new ZipFile(srcFile);
      try {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
          ZipEntry e = en.nextElement();
          String name = e.getName();
          if (name.endsWith("/")) continue;
          if (isOldSignatureFile(name)) continue;
          byte[] data;
          if (name.equals("AndroidManifest.xml")) data = renameManifest(readAll(zip.getInputStream(e)), oldPkg, newPkg, label);
          else if (name.equals("assets/config.json")) {
            String cfg = new String(readAll(zip.getInputStream(e)), "UTF-8");
            cfg = cfg.replace(oldPkg, newPkg);
            cfg = replaceConfigName(cfg, label);
            data = cfg.getBytes("UTF-8");
          } else data = readAll(zip.getInputStream(e));
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
      // Delete the MediaStore temp copy, if one was made; the app's
      // own Downloads-folder file is the user's file, so leave it.
      try {
        if (srcFile.getParentFile() != null && srcFile.getParentFile().equals(ctx.getCacheDir())) srcFile.delete();
      } catch (Exception e) { }
      return signAndSave(ctx, unsigned, fileName, CLONE_KEY_ALIAS, CLONE_CERT_CN, "cloner");
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  public static String openLast(Context ctx) {
    return openFor(ctx, "packager", "No standalone APK saved yet. Build one first.");
  }

  public static String openClone(Context ctx) {
    return openFor(ctx, "cloner", "No clone saved yet. Build a clone first.");
  }

  static String openFor(Context ctx, String prefsName, String emptyMsg) {
    try {
      SharedPreferences prefs = ctx.getSharedPreferences(prefsName, 0);
      String uriStr = prefs.getString("last_uri", null);
      if (uriStr == null) return "error: " + emptyMsg;
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
