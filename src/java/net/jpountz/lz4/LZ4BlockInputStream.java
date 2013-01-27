package net.jpountz.lz4;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static net.jpountz.lz4.LZ4BlockOutputStream.COMPRESSION_LEVEL_BASE;
import static net.jpountz.lz4.LZ4BlockOutputStream.COMPRESSION_METHOD_LZ4;
import static net.jpountz.lz4.LZ4BlockOutputStream.COMPRESSION_METHOD_RAW;
import static net.jpountz.lz4.LZ4BlockOutputStream.DEFAULT_SEED;
import static net.jpountz.lz4.LZ4BlockOutputStream.HEADER_LENGTH;
import static net.jpountz.lz4.LZ4BlockOutputStream.MAGIC;
import static net.jpountz.lz4.LZ4BlockOutputStream.MAGIC_LENGTH;
import static net.jpountz.lz4.LZ4BlockOutputStream.MIN_BLOCK_SIZE;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Checksum;

import net.jpountz.util.Utils;
import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

/**
 * {@link InputStream} implementation to decode data written with
 * {@link LZ4BlockOutputStream}. This class is not thread-safe.
 * @see LZ4BlockOutputStream
 */
public final class LZ4BlockInputStream extends FilterInputStream {

  private static final byte[] EMPTY = new byte[0];

  private final LZ4Decompressor decompressor;
  private final Checksum checksum;
  private byte[] buffer;
  private byte[] compressedBuffer;
  private int originalLen;
  private int o;
  private boolean finished;

  // for mark / reset
  private byte[] markBuffer;
  private int markOriginalLen, markO;
  private boolean markFinished;

  /**
   * Create a new {@link InputStream}.
   *
   * @param in            the {@link InputStream} to poll
   * @param decompressor  the {@link LZ4Decompressor decompressor} instance to
   *                      use
   * @param checksum      the {@link Checksum} instance to use, must be
   *                      equivalent to the instance which has been used to
   *                      write the stream
   */
  public LZ4BlockInputStream(InputStream in, LZ4Decompressor decompressor, Checksum checksum) {
    super(in);
    this.decompressor = decompressor;
    this.checksum = checksum;
    this.buffer = new byte[0];
    this.compressedBuffer = new byte[HEADER_LENGTH];
    o = originalLen = 0;
    finished = false;
  }

  /**
   * Create a new instance using {@link XXHash32} for checksuming.
   * @see #LZ4BlockInputStream(InputStream, LZ4Decompressor, Checksum)
   * @see StreamingXXHash32#asChecksum()
   */
  public LZ4BlockInputStream(InputStream in, LZ4Decompressor decompressor) {
    this(in, decompressor, XXHashFactory.fastestInstance().newStreamingHash32(DEFAULT_SEED).asChecksum());
  }

  /**
   * Create a new instance which uses the fastest {@link LZ4Decompressor} available.
   * @see LZ4Factory#fastestInstance()
   * @see #LZ4BlockInputStream(InputStream, LZ4Decompressor)
   */
  public LZ4BlockInputStream(InputStream in) {
    this(in, LZ4Factory.fastestInstance().decompressor());
  }

  @Override
  public int available() throws IOException {
    return originalLen - o;
  }

  @Override
  public int read() throws IOException {
    if (finished) {
      return -1;
    }
    if (o == originalLen) {
      refill();
    }
    if (finished) {
      return -1;
    }
    return buffer[o++] & 0xFF;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    Utils.checkRange(b, off, len);
    if (finished) {
      return -1;
    }
    if (o == originalLen) {
      refill();
    }
    if (finished) {
      return -1;
    }
    len = Math.min(len, originalLen - o);
    System.arraycopy(buffer, o, b, off, len);
    o += len;
    return len;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public long skip(long n) throws IOException {
    if (finished) {
      return -1;
    }
    if (o == originalLen) {
      refill();
    }
    if (finished) {
      return -1;
    }
    final int skipped = (int) Math.min(n, originalLen - o);
    o += skipped;
    return skipped;
  }

  private void refill() throws IOException {
    in.read(compressedBuffer, 0, HEADER_LENGTH);
    for (int i = 0; i < MAGIC_LENGTH; ++i) {
      if (compressedBuffer[i] != MAGIC[i]) {
        throw new IOException("Stream is corrupted");
      }
    }
    final int token = compressedBuffer[MAGIC_LENGTH] & 0xFF;
    final int compressionMethod = token & 0xF0;
    final int compressionLevel = COMPRESSION_LEVEL_BASE + (token & 0x0F);
    if (compressionMethod != COMPRESSION_METHOD_RAW && compressionMethod != COMPRESSION_METHOD_LZ4) {
      throw new IOException("Stream is corrupted");
    }
    final int compressedLen = Utils.readIntLE(compressedBuffer, MAGIC_LENGTH + 1);
    originalLen = Utils.readIntLE(compressedBuffer, MAGIC_LENGTH + 5);
    final int check = Utils.readIntLE(compressedBuffer, MAGIC_LENGTH + 9);
    assert HEADER_LENGTH == MAGIC_LENGTH + 13;
    if (originalLen > 1 << compressionLevel
        || originalLen < 0
        || compressedLen < 0
        || (originalLen == 0 && compressedLen != 0)
        || (originalLen != 0 && compressedLen == 0)
        || (compressionMethod == COMPRESSION_METHOD_RAW && originalLen != compressedLen)) {
      throw new IOException("Stream is corrupted");
    }
    if (originalLen == 0 && compressedLen == 0) {
      if (check != 0) {
        throw new IOException("Stream is corrupted");
      }
      finished = true;
      return;
    }
    if (buffer.length < originalLen) {
      buffer = new byte[Math.max(originalLen, buffer.length * 3 / 2)];
    }
    switch (compressionMethod) {
    case COMPRESSION_METHOD_RAW:
      readFully(buffer, originalLen);
      break;
    case COMPRESSION_METHOD_LZ4:
      if (compressedBuffer.length < originalLen) {
        compressedBuffer = new byte[Math.max(compressedLen, compressedBuffer.length * 3 / 2)];
      }
      readFully(compressedBuffer, compressedLen);
      try {
        final int compressedLen2 = decompressor.decompress(compressedBuffer, 0, buffer, 0, originalLen);
        if (compressedLen != compressedLen2) {
          throw new IOException("Stream is corrupted");
        }
      } catch (LZ4Exception e) {
        throw new IOException("Stream is corrupted", e);
      }
      break;
    default:
      throw new AssertionError();
    }
    checksum.reset();
    checksum.update(buffer, 0, originalLen);
    if ((int) checksum.getValue() != check) {
      throw new IOException("Stream is corrupted");
    }
    o = 0;
  }

  private void readFully(byte[] b, int len) throws IOException {
    int read = 0;
    while (read < len) {
      final int r = in.read(b, read, len - read);
      if (r < 0) {
        throw new EOFException("Stream ended prematurely");
      }
      read += r;
    }
    assert len == read;
  }

  @Override
  public boolean markSupported() {
    return in.markSupported();
  }

  @Override
  public void mark(int readlimit) {
    readlimit = Math.max(readlimit, 0);
    // worst-case compression ratio is when all blocks are MIN_BLOCK_SIZE bytes
    // and incompressible
    long compressedReadLimit =
        (long) (MIN_BLOCK_SIZE + HEADER_LENGTH) // max compressed size of a single block
        * (readlimit + MIN_BLOCK_SIZE - 1) / MIN_BLOCK_SIZE; // number of blocks
    if (compressedReadLimit > Integer.MAX_VALUE) {
      // overflow: try to do our best, this might not work if the input is incompressible
      // and if the underlying InputStream actually buffers data when mark is called (if
      // it does not support seeking)
      compressedReadLimit = Integer.MAX_VALUE;
    }
    in.mark((int) compressedReadLimit);
    markFinished = finished;
    markO = o;
    markOriginalLen = originalLen;
    if (o < originalLen) {
      if (markBuffer == null) {
        markBuffer = new byte[originalLen];
      } else if (markBuffer.length < originalLen - o) {
        markBuffer = new byte[Math.max(markBuffer.length + markBuffer.length >>> 1, originalLen)];
      }
      System.arraycopy(buffer, o, markBuffer, 0, originalLen - o);
    } else if (markBuffer == null) {
      markBuffer = EMPTY;
    }
    assert markBuffer != null;
  }

  @Override
  public void reset() throws IOException {
    if (markBuffer == null) {
      throw new IOException("Call mark first");
    }
    in.reset();
    finished = markFinished;
    o = markO;
    originalLen = markOriginalLen;
    assert o <= originalLen;
    if (o < originalLen) {
      assert buffer.length >= originalLen; // block has alread been decompressed
      System.arraycopy(markBuffer, 0, buffer, o, originalLen - o);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(in=" + in
        + ", decompressor=" + decompressor + ", checksum=" + checksum + ")";
  }

}
