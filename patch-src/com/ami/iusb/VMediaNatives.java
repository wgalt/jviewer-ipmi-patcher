package com.ami.iusb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pure-Java bodies for the native methods of CDROMRedir, FloppyRedir and HarddiskRedir
 * (libjava{cdrom,floppy,harddisk}wrapper). tools/patch.py turns each {@code native} method
 * {@code R name(A...)} into {@code return VMediaNatives.name((Object) this, A...)}, so this class only
 * depends on the native method names and signatures, not on the rest of the (version-specific) classes.
 * Private fields of the Redir classes are reached by reflection for the same reason.
 *
 * <p>Also holds no-op replacements for the native library loading done in those classes' static
 * initialisers. Images only: physical drive lists are always empty.
 */
public final class VMediaNatives {
   private static final Map<Object, ImageDisk> DISKS = Collections.synchronizedMap(new WeakHashMap<Object, ImageDisk>());

   private VMediaNatives() {}

   private static ImageDisk disk(Object redir, int type) {
      synchronized (DISKS) {
         ImageDisk d = DISKS.get(redir);
         if (d == null) DISKS.put(redir, d = new ImageDisk(type));
         return d;
      }
   }

   private static void close(Object redir) {
      ImageDisk d = DISKS.get(redir);
      if (d != null) d.close();
   }

   private static Field field(Object o, String... names) {
      for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
         for (String n : names) {
            try {
               Field f = c.getDeclaredField(n);
               f.setAccessible(true);
               return f;
            } catch (ReflectiveOperationException | RuntimeException e) {
               // try next
            }
         }
      }
      return null;
   }

   /** The natives stored their reader handle in {@code nativeReaderPointer}; Java code tests it against -1. */
   private static void setReader(Object redir, long v) {
      Field f = field(redir, "nativeReaderPointer");
      try {
         if (f != null) f.setLong(redir, v);
      } catch (ReflectiveOperationException e) {
         // field type changed; the Java side then just recreates the reader as needed
      }
   }

   private static boolean physical(Object redir) {
      Field f = field(redir, "physicalDrive", "physicalDevice");
      try {
         return f != null && f.getBoolean(redir);
      } catch (ReflectiveOperationException e) {
         return false;
      }
   }

   private static String keyboardName() {
      return System.getProperty("os.name", "").startsWith("Linux") ? "us" : "00000409";
   }

   // ---- library loading (static initialisers) ----
   public static void loadLibrary(String name) {}

   public static void load(String path) {}

   public static boolean getWrapperLibrary(String name) {
      return true;
   }

   /** getVersion() of all three classes. */
   public static String getVersion(Object r) { return "pure-java"; }

   // ---- CDROMRedir ----
   public static String[] listCDROMDrives(Object r) { return null; }
   public static void newCDROMReader(Object r, boolean physical) { setReader(r, 1L); }
   public static void deleteCDROMReader(Object r) { close(r); setReader(r, -1L); }
   public static boolean openCDROM(Object r, byte[] path) {
      return !physical(r) && disk(r, ImageDisk.CDROM).open(path) >= 0;
   }
   public static void closeCDROM(Object r) { close(r); }
   public static int executeCDROMSCSICmd(Object r, ByteBuffer req, ByteBuffer resp) {
      return disk(r, ImageDisk.CDROM).execute(req, resp);
   }

   // ---- FloppyRedir ----
   public static String[] listFloppyDrives(Object r) { return null; }
   public static void newFloppyReader(Object r, boolean physical) { setReader(r, 1L); }
   public static void deleteFloppyReader(Object r) { close(r); setReader(r, -1L); }
   public static int openFloppy(Object r, byte[] path) {
      return physical(r) ? -1 : disk(r, ImageDisk.FLOPPY).open(path);
   }
   public static void closeFloppy(Object r) { close(r); }
   public static int executeFloppySCSICmd(Object r, ByteBuffer req, ByteBuffer resp) {
      return disk(r, ImageDisk.FLOPPY).execute(req, resp);
   }
   public static String GetKeyboardName(Object r) { return keyboardName(); }
   /** Echo the remote LED state so the client never toggles local lock keys. */
   public static byte GetLEDStatus(Object r) {
      try {
         Class<?> app = Class.forName("com.ami.kvm.jviewer.JViewerApp");
         Object inst = app.getMethod("getInstance").invoke(null);
         Method m = app.getMethod("getLed_status");
         return ((Number) m.invoke(inst)).byteValue();
      } catch (ReflectiveOperationException | RuntimeException e) {
         return 0;
      }
   }

   // ---- HarddiskRedir ----
   public static String[] listHardDrivesFixed(Object r) { return null; }
   public static String[] listHardDrives(Object r) { return null; }
   public static void newHarddiskReader(Object r, boolean physical) { setReader(r, 1L); }
   public static void deleteHarddiskReader(Object r) { close(r); setReader(r, -1L); }
   public static int openHarddisk(Object r, byte[] path, boolean physical) {
      return physical ? -1 : disk(r, ImageDisk.HARDDISK).open(path);
   }
   public static void closeHarddisk(Object r) { close(r); }
   public static int executeHarddiskSCSICmd(Object r, ByteBuffer req, ByteBuffer resp) {
      return disk(r, ImageDisk.HARDDISK).execute(req, resp);
   }
}
