package com.apkrepacker.apk.axml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surgical editor for the <em>global value string pool</em> of a compiled resource table
 * ({@code resources.arsc}).
 *
 * <p>Like {@link AxmlFile}, this is not a resource decompiler. Every string-typed resource
 * value is an index into the global pool, so replacing the contents of a pooled string
 * (never its index, never the count or order) changes that value everywhere it is used
 * while leaving every resource ID, package, type spec, and config chunk byte-identical.
 * Styled-string spans reference strings by index too, so the style section is carried over
 * verbatim. Only the pool chunk changes size; we rebuild it and patch the table size.
 *
 * <p>Format reference: ResTable_header / ResStringPool_header in the AOSP
 * {@code ResourceTypes.h}.
 */
public final class ArscFile {

    private static final int TYPE_TABLE = 0x0002;
    private static final int TYPE_STRING_POOL = 0x0001;
    private static final int FLAG_UTF8 = 0x00000100;
    private static final int POOL_HEADER_SIZE = 28;

    private final byte[] data;
    private int poolOffset;
    private int poolSize;
    private int poolFlags;
    private boolean utf8;
    private int styleCount;
    private byte[] styleOffsets;  // uint32[styleCount], relative to the style data, verbatim
    private byte[] styleData;     // span data, verbatim
    private List<String> strings;

    private ArscFile(byte[] data) {
        this.data = data;
    }

    public static ArscFile parse(byte[] data) {
        ArscFile f = new ArscFile(data);
        f.doParse();
        return f;
    }

    public List<String> strings() {
        return strings;
    }

    /**
     * Replaces pooled strings that exactly equal a key of {@code renames}.
     *
     * @return number of strings changed.
     */
    public int renameExact(Map<String, String> renames) {
        int changed = 0;
        for (int i = 0; i < strings.size(); i++) {
            String replacement = renames.get(strings.get(i));
            if (replacement != null) {
                strings.set(i, replacement);
                changed++;
            }
        }
        return changed;
    }

    private int u16(int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private int u32(int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private void doParse() {
        if (data.length < 12 || u16(0) != TYPE_TABLE) {
            throw new AxmlException("Not a resource table");
        }
        if (u32(4) != data.length) {
            throw new AxmlException("Resource table size mismatch");
        }
        poolOffset = u16(2); // table header size; the global pool chunk follows it
        if (poolOffset + POOL_HEADER_SIZE > data.length || u16(poolOffset) != TYPE_STRING_POOL
                || u16(poolOffset + 2) != POOL_HEADER_SIZE) {
            throw new AxmlException("Resource table does not start with a string pool");
        }
        int base = poolOffset;
        poolSize = u32(base + 4);
        int count = u32(base + 8);
        styleCount = u32(base + 12);
        poolFlags = u32(base + 16);
        utf8 = (poolFlags & FLAG_UTF8) != 0;
        int stringsStart = u32(base + 20);
        int stylesStart = u32(base + 24);
        if (base + poolSize > data.length) {
            throw new AxmlException("String pool extends past end of table");
        }

        strings = new ArrayList<>(count);
        int offsets = base + POOL_HEADER_SIZE;
        for (int i = 0; i < count; i++) {
            int pos = base + stringsStart + u32(offsets + i * 4);
            strings.add(utf8 ? decodeUtf8(pos) : decodeUtf16(pos));
        }

        int styleOffsetsPos = offsets + count * 4;
        styleOffsets = new byte[styleCount * 4];
        System.arraycopy(data, styleOffsetsPos, styleOffsets, 0, styleOffsets.length);
        if (styleCount > 0) {
            int from = base + stylesStart;
            styleData = new byte[base + poolSize - from];
            System.arraycopy(data, from, styleData, 0, styleData.length);
        } else {
            styleData = new byte[0];
        }
    }

    private String decodeUtf16(int pos) {
        int len = u16(pos);
        pos += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | u16(pos);
            pos += 2;
        }
        return new String(data, pos, len * 2, StandardCharsets.UTF_16LE);
    }

    private String decodeUtf8(int pos) {
        int n = data[pos++] & 0xFF; // UTF-16 length; skipped
        if ((n & 0x80) != 0) {
            pos++;
        }
        int byteLen = data[pos++] & 0xFF;
        if ((byteLen & 0x80) != 0) {
            byteLen = ((byteLen & 0x7F) << 8) | (data[pos++] & 0xFF);
        }
        return new String(data, pos, byteLen, StandardCharsets.UTF_8);
    }

    /** Serializes the table with the rebuilt pool; everything after the pool is unchanged. */
    public byte[] toByteArray() {
        ByteArrayOutputStream stringData = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = stringData.size();
            byte[] e = utf8 ? AxmlFile.encodeUtf8(strings.get(i))
                    : AxmlFile.encodeUtf16(strings.get(i));
            stringData.write(e, 0, e.length);
        }
        while (stringData.size() % 4 != 0) {
            stringData.write(0); // style data (if any) must start 4-byte aligned
        }
        byte[] sd = stringData.toByteArray();

        int stringsStart = POOL_HEADER_SIZE + offsets.length * 4 + styleOffsets.length;
        int stylesStart = styleCount > 0 ? stringsStart + sd.length : 0;
        int newPoolSize = stringsStart + sd.length + styleData.length;
        int afterOld = poolOffset + poolSize;
        byte[] out = new byte[poolOffset + newPoolSize + (data.length - afterOld)];

        System.arraycopy(data, 0, out, 0, poolOffset);
        int p = poolOffset;
        writeU16(out, p, TYPE_STRING_POOL);
        writeU16(out, p + 2, POOL_HEADER_SIZE);
        writeU32(out, p + 4, newPoolSize);
        writeU32(out, p + 8, offsets.length);
        writeU32(out, p + 12, styleCount);
        writeU32(out, p + 16, poolFlags);
        writeU32(out, p + 20, stringsStart);
        writeU32(out, p + 24, stylesStart);
        for (int i = 0; i < offsets.length; i++) {
            writeU32(out, p + POOL_HEADER_SIZE + i * 4, offsets[i]);
        }
        System.arraycopy(styleOffsets, 0, out, p + POOL_HEADER_SIZE + offsets.length * 4,
                styleOffsets.length);
        System.arraycopy(sd, 0, out, p + stringsStart, sd.length);
        System.arraycopy(styleData, 0, out, p + stringsStart + sd.length, styleData.length);
        System.arraycopy(data, afterOld, out, poolOffset + newPoolSize, data.length - afterOld);

        writeU32(out, 4, out.length); // table chunk size
        return out;
    }

    private static void writeU16(byte[] a, int off, int v) {
        a[off] = (byte) (v & 0xFF);
        a[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void writeU32(byte[] a, int off, int v) {
        a[off] = (byte) (v & 0xFF);
        a[off + 1] = (byte) ((v >> 8) & 0xFF);
        a[off + 2] = (byte) ((v >> 16) & 0xFF);
        a[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
