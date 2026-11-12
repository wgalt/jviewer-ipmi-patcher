package com.ami.iusb;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * Pure-Java replacement for AMI's native vmedia wrapper libraries (libjava{cdrom,floppy,harddisk}wrapper),
 * covering image-file redirection. Emulates a SCSI block device backed by an image file.
 *
 * Packet layout (IUSB_SCSI_PACKET, as used by the native code):
 *   0..31  IUSB header        32..35 ReadLen      36..39 TagNo      40 DataDir
 *   41..52 CDB (12 bytes)     53 OverallStatus 54 SenseKey 55 ASC 56 ASCQ
 *   57..60 DataLen (little-endian)                61..   Data
 * The response starts as a copy of the first 62 request bytes; execute() returns 61 + DataLen.
 * Multi-byte CDB fields and SCSI response data are big-endian.
 */
public final class ImageDisk {
   public static final int CDROM = 0;
   public static final int FLOPPY = 1;
   public static final int HARDDISK = 2;

   public static final int OPEN_RW = 0;
   public static final int OPEN_RO = 2;
   public static final int OPEN_IN_USE = -3;
   public static final int OPEN_FAILED = -1;

   private static final int CDB = 41;
   private static final int STATUS = 53;
   private static final int DATALEN = 57;
   private static final int DATA = 61;
   private static final int REQ_COPY = 62;
   private static final int MAX_WRITE_SECTORS = 256;

   // error codes mirror the native SetErrorStatus table
   private static final int OK = 0;
   private static final int E_LBA_RANGE = -1;
   private static final int E_MEDIUM_CHANGED = -3;
   private static final int E_READ = -4;
   private static final int E_NO_MEDIUM = -5;
   private static final int E_INVALID_FIELD = -6;
   private static final int E_INVALID_OPCODE = -9;
   private static final int E_WRITE_PROTECT = -10;

   private final int type;
   private final int blockSize;
   private RandomAccessFile file;
   private FileChannel channel;
   private FileLock lock;
   private boolean readOnly;
   private boolean mediumChanged;
   private long blocks;

   public ImageDisk(int type) {
      this.type = type;
      this.blockSize = type == CDROM ? 2048 : 512;
   }

   /** @return OPEN_RW, OPEN_RO (writable open failed, opened read-only), OPEN_IN_USE or OPEN_FAILED */
   public synchronized int open(byte[] pathUtf8) {
      close();
      String path;
      try {
         path = new String(pathUtf8, "UTF-8").trim();
      } catch (IOException e) {
         return OPEN_FAILED;
      }
      File f = new File(path);
      if (!f.isFile()) {
         return OPEN_FAILED;
      }
      int result = OPEN_RO;
      try {
         if (this.type != CDROM && f.canWrite()) {
            this.file = new RandomAccessFile(f, "rw");
            this.channel = this.file.getChannel();
            try {
               this.lock = this.channel.tryLock();
            } catch (OverlappingFileLockException e) {
               this.lock = null;
            }
            if (this.lock == null) {
               close();
               return OPEN_IN_USE;
            }
            result = OPEN_RW;
         } else {
            this.file = new RandomAccessFile(f, "r");
            this.channel = this.file.getChannel();
         }
         this.readOnly = result != OPEN_RW;
         this.blocks = this.channel.size() / this.blockSize;
         if (this.blocks == 0) {
            close();
            return OPEN_FAILED;
         }
         this.mediumChanged = true;
         return result;
      } catch (IOException e) {
         close();
         return OPEN_FAILED;
      }
   }

   public synchronized void close() {
      try {
         if (this.lock != null) {
            this.lock.release();
         }
      } catch (IOException ignored) {
      }
      try {
         if (this.file != null) {
            this.file.close();
         }
      } catch (IOException ignored) {
      }
      this.lock = null;
      this.channel = null;
      this.file = null;
      this.blocks = 0;
   }

   public synchronized int execute(ByteBuffer request, ByteBuffer response) {
      // The caller leaves arbitrary position/limit on these buffers (CDROMRedir.run() sets the
      // response limit to the previous packet's length), and absolute get/put are bounds-checked
      // against the limit -- the native code just used the raw address. Work on full-capacity views.
      ByteBuffer req = request.duplicate();
      req.clear();
      ByteBuffer resp = response.duplicate();
      resp.clear();
      for (int i = 0; i < REQ_COPY; i++) {
         resp.put(i, req.get(i));
      }
      int[] len = new int[1];
      int err;
      try {
         err = dispatch(req, resp, len);
      } catch (IOException | RuntimeException e) {
         // never let an exception kill the redirection thread; report a medium error instead
         err = E_READ;
      }
      if (err != OK) {
         len[0] = 0;
      }
      setStatus(resp, err);
      putLE32(resp, DATALEN, len[0]);
      return DATA + len[0];
   }

   private int dispatch(ByteBuffer req, ByteBuffer resp, int[] len) throws IOException {
      int op = u8(req, CDB);
      if (this.channel == null) {
         return op == 0x1B || op == 0x1E ? OK : E_NO_MEDIUM;
      }
      switch (op) {
         case 0x00: // TEST UNIT READY
            return unitAttention();
         case 0x25: { // READ CAPACITY(10)
            int ua = unitAttention();
            if (ua != OK) {
               return ua;
            }
            putBE32(resp, DATA, (int) Math.min(this.blocks - 1, 0xFFFFFFFFL));
            putBE32(resp, DATA + 4, this.blockSize);
            len[0] = 8;
            return OK;
         }
         case 0x28: // READ(10)
         case 0xA8: { // READ(12)
            long lba = be32(req, CDB + 2) & 0xFFFFFFFFL;
            long count = op == 0x28 ? be16(req, CDB + 7) : be32(req, CDB + 6) & 0xFFFFFFFFL;
            return read(lba, count, resp, len);
         }
         case 0x1B: // START STOP UNIT (eject handled by the Java caller)
         case 0x1E: // PREVENT ALLOW MEDIUM REMOVAL
            return OK;
         case 0x43: // READ TOC
            return this.type == CDROM ? readToc(req, resp, len) : E_INVALID_OPCODE;
         default:
            break;
      }
      if (this.type == CDROM) {
         return E_INVALID_OPCODE;
      }
      switch (op) {
         case 0x04: // FORMAT UNIT
            return this.readOnly ? E_WRITE_PROTECT : OK;
         case 0x23: { // READ FORMAT CAPACITIES
            int alloc = be16(req, CDB + 7);
            putBE32(resp, DATA, 8);
            putBE32(resp, DATA + 4, (int) Math.min(this.blocks, 0xFFFFFFFFL));
            putBE32(resp, DATA + 8, 0x02000000 | this.blockSize);
            len[0] = Math.min(12, alloc == 0 ? 12 : alloc);
            return OK;
         }
         case 0x2A: { // WRITE(10)
            long lba = be32(req, CDB + 2) & 0xFFFFFFFFL;
            int count = be16(req, CDB + 7);
            return write(lba, count, req);
         }
         case 0x1A: // MODE SENSE(6)
            return modeSense(req, resp, len, false);
         case 0x5A: // MODE SENSE(10)
            return modeSense(req, resp, len, true);
         default:
            return E_INVALID_OPCODE;
      }
   }

   private int unitAttention() {
      if (this.mediumChanged) {
         this.mediumChanged = false;
         return E_MEDIUM_CHANGED;
      }
      return OK;
   }

   private int read(long lba, long count, ByteBuffer resp, int[] len) throws IOException {
      if (lba + count > this.blocks) {
         return E_LBA_RANGE;
      }
      long bytes = count * this.blockSize;
      if (bytes > resp.capacity() - DATA) {
         return E_INVALID_FIELD;
      }
      ByteBuffer dst = resp.duplicate();
      dst.clear();
      dst.position(DATA);
      dst.limit(DATA + (int) bytes);
      long pos = lba * this.blockSize;
      while (dst.hasRemaining()) {
         int n = this.channel.read(dst, pos);
         if (n < 0) {
            return E_READ;
         }
         pos += n;
      }
      len[0] = (int) bytes;
      return OK;
   }

   private int write(long lba, int count, ByteBuffer req) throws IOException {
      if (this.readOnly) {
         return E_WRITE_PROTECT;
      }
      if (count > MAX_WRITE_SECTORS) {
         return E_INVALID_FIELD;
      }
      if (lba + count > this.blocks) {
         return E_LBA_RANGE;
      }
      int bytes = count * this.blockSize;
      if (DATA + bytes > req.capacity()) {
         return E_INVALID_FIELD;
      }
      ByteBuffer src = req.duplicate();
      src.clear();
      src.position(DATA);
      src.limit(DATA + bytes);
      long pos = lba * this.blockSize;
      while (src.hasRemaining()) {
         pos += this.channel.write(src, pos);
      }
      return OK;
   }

   /** Single data-track TOC, formats 0 (TOC) and 1 (session info). */
   private int readToc(ByteBuffer req, ByteBuffer resp, int[] len) {
      boolean msf = (u8(req, CDB + 1) & 0x02) != 0;
      int format = u8(req, CDB + 2) & 0x0F;
      if (format == 0) {
         format = (u8(req, CDB + 9) >> 6) & 0x03; // legacy (SFF-8020) format field
      }
      int track = u8(req, CDB + 6);
      int alloc = be16(req, CDB + 7);
      int n = 4;
      if (format == 1) {
         resp.put(DATA + n++, (byte) 0);
         resp.put(DATA + n++, (byte) 0x14);
         resp.put(DATA + n++, (byte) 1);
         resp.put(DATA + n++, (byte) 0);
         n = putAddr(resp, n, 0, msf);
      } else if (format == 0) {
         if (track > 1 && track != 0xAA) {
            return E_INVALID_FIELD;
         }
         if (track <= 1) {
            resp.put(DATA + n++, (byte) 0);
            resp.put(DATA + n++, (byte) 0x14); // ADR=1, data track
            resp.put(DATA + n++, (byte) 1);
            resp.put(DATA + n++, (byte) 0);
            n = putAddr(resp, n, 0, msf);
         }
         resp.put(DATA + n++, (byte) 0);
         resp.put(DATA + n++, (byte) 0x14);
         resp.put(DATA + n++, (byte) 0xAA); // lead-out
         resp.put(DATA + n++, (byte) 0);
         n = putAddr(resp, n, this.blocks, msf);
      } else {
         return E_INVALID_FIELD;
      }
      resp.put(DATA, (byte) ((n - 2) >> 8));
      resp.put(DATA + 1, (byte) (n - 2));
      resp.put(DATA + 2, (byte) 1); // first track / session
      resp.put(DATA + 3, (byte) 1); // last track / session
      len[0] = Math.min(n, alloc);
      return OK;
   }

   private static int putAddr(ByteBuffer resp, int n, long lba, boolean msf) {
      if (msf) {
         long f = lba + 150;
         resp.put(DATA + n, (byte) 0);
         resp.put(DATA + n + 1, (byte) (f / 4500));
         resp.put(DATA + n + 2, (byte) (f / 75 % 60));
         resp.put(DATA + n + 3, (byte) (f % 75));
      } else {
         putBE32(resp, DATA + n, (int) lba);
      }
      return n + 4;
   }

   private int modeSense(ByteBuffer req, ByteBuffer resp, int[] len, boolean ten) {
      int page = u8(req, CDB + 2) & 0x3F;
      int alloc = ten ? be16(req, CDB + 7) : u8(req, CDB + 4);
      int hdr = ten ? 8 : 4;
      boolean floppy144 = this.type == FLOPPY && this.blocks == 2880;
      int n = hdr;
      if (this.type == FLOPPY && (page == 0x05 || page == 0x3F)) {
         // Flexible disk page
         int heads = 2;
         int spt = 18;
         if (!floppy144) {
            heads = 255;
            spt = 63;
         }
         for (int i = 0; i < 32; i++) {
            resp.put(DATA + n + i, (byte) 0);
         }
         resp.put(DATA + n, (byte) 0x05);
         resp.put(DATA + n + 1, (byte) 0x1E);
         putBE16(resp, DATA + n + 2, 500); // transfer rate kbit/s
         resp.put(DATA + n + 4, (byte) heads);
         resp.put(DATA + n + 5, (byte) spt);
         putBE16(resp, DATA + n + 6, this.blockSize);
         putBE16(resp, DATA + n + 8, (int) Math.min(this.blocks / (heads * spt), 0xFFFF));
         putBE16(resp, DATA + n + 28, 300); // rotation rate
         n += 32;
      }
      int mediumType = floppy144 ? 0x94 : 0;
      int wp = this.readOnly ? 0x80 : 0;
      if (ten) {
         putBE16(resp, DATA, n - 2);
         resp.put(DATA + 2, (byte) mediumType);
         resp.put(DATA + 3, (byte) wp);
         for (int i = 4; i < 8; i++) {
            resp.put(DATA + i, (byte) 0);
         }
      } else {
         resp.put(DATA, (byte) (n - 1));
         resp.put(DATA + 1, (byte) mediumType);
         resp.put(DATA + 2, (byte) wp);
         resp.put(DATA + 3, (byte) 0);
      }
      len[0] = Math.min(n, alloc);
      return OK;
   }

   private static void setStatus(ByteBuffer resp, int err) {
      int s;
      int k;
      int asc;
      int ascq = 0;
      switch (err) {
         case OK: s = 0; k = 0; asc = 0; break;
         case E_LBA_RANGE: s = 1; k = 0x05; asc = 0x21; break;
         case E_MEDIUM_CHANGED: s = 1; k = 0x06; asc = 0x28; break;
         case E_READ: s = 1; k = 0x03; asc = 0x11; break;
         case E_NO_MEDIUM: s = 1; k = 0x02; asc = 0x3A; break;
         case E_INVALID_FIELD: s = 1; k = 0x05; asc = 0x24; break;
         case E_WRITE_PROTECT: s = 1; k = 0x07; asc = 0x27; break;
         default: s = 1; k = 0x05; asc = 0x20; break; // invalid command opcode
      }
      resp.put(STATUS, (byte) s);
      resp.put(STATUS + 1, (byte) k);
      resp.put(STATUS + 2, (byte) asc);
      resp.put(STATUS + 3, (byte) ascq);
   }

   private static int u8(ByteBuffer b, int i) {
      return b.get(i) & 0xFF;
   }

   private static int be16(ByteBuffer b, int i) {
      return (u8(b, i) << 8) | u8(b, i + 1);
   }

   private static int be32(ByteBuffer b, int i) {
      return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
   }

   private static void putBE16(ByteBuffer b, int i, int v) {
      b.put(i, (byte) (v >> 8));
      b.put(i + 1, (byte) v);
   }

   private static void putBE32(ByteBuffer b, int i, int v) {
      b.put(i, (byte) (v >>> 24));
      b.put(i + 1, (byte) (v >>> 16));
      b.put(i + 2, (byte) (v >>> 8));
      b.put(i + 3, (byte) v);
   }

   private static void putLE32(ByteBuffer b, int i, int v) {
      b.put(i, (byte) v);
      b.put(i + 1, (byte) (v >>> 8));
      b.put(i + 2, (byte) (v >>> 16));
      b.put(i + 3, (byte) (v >>> 24));
   }
}
