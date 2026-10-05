package com.apkrepacker.apk.axml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, surgical editor for Android binary XML (AXML), as used by a compiled
 * AndroidManifest.xml inside an APK.
 *
 * <p>We deliberately do NOT re-implement a full resource decompiler. Instead we parse
 * the documented chunk layout far enough to:
 * <ul>
 *   <li>read the string pool,</li>
 *   <li>read the {@code package} attribute on the {@code <manifest>} element,</li>
 *   <li>rewrite the <em>contents</em> of selected pooled strings while keeping the
 *       string <em>count and ordering</em> identical.</li>
 * </ul>
 *
 * <p>Because every name/attribute reference in the XML body is an index into the
 * string pool, mutating only string contents (not indices) keeps the rest of the
 * file structurally valid. Only the string-pool chunk changes size, so we rebuild it
 * and patch the two affected size fields (pool chunk size, file chunk size).
 *
 * <p>Format reference: ResChunk_header / ResStringPool_header in the AOSP
 * {@code ResourceTypes.h}.
 */
public final class AxmlFile {

    private static final int TYPE_XML = 0x0003;
    private static final int TYPE_STRING_POOL = 0x0001;
    private static final int TYPE_RESOURCE_MAP = 0x0180;
    private static final int TYPE_START_ELEMENT = 0x0102;

    private static final int FLAG_UTF8 = 0x00000100;

    // Framework attribute resource IDs (stable AOSP public ids).
    public static final int ATTR_LABEL = 0x01010001;
    public static final int ATTR_DRAWABLE = 0x01010199;

    private static final int TYPE_REFERENCE = 0x01; // ResValue dataType for a resource ref
    private static final int TYPE_STRING_VALUE = 0x03; // ResValue dataType for an inline string

    private final byte[] data;

    // String pool.
    private int poolChunkOffset;      // offset of the string pool ResChunk_header
    private int poolChunkSize;        // original byte size of the pool chunk
    private int poolFlags;
    private boolean utf8;
    private List<String> strings = new ArrayList<>();

    private AxmlFile(byte[] data) {
        this.data = data;
    }

    public static AxmlFile parse(byte[] data) {
        AxmlFile f = new AxmlFile(data);
        f.doParse();
        return f;
    }

    public List<String> strings() {
        return strings;
    }

    // ---- little-endian readers ---------------------------------------------

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
        if (data.length < 8) {
            throw new AxmlException("File too small to be AXML");
        }
        int fileType = u16(0);
        if (fileType != TYPE_XML) {
            throw new AxmlException("Not a binary XML file (type=0x"
                    + Integer.toHexString(fileType) + ")");
        }
        // String pool chunk immediately follows the 8-byte file header.
        poolChunkOffset = 8;
        int poolType = u16(poolChunkOffset);
        if (poolType != TYPE_STRING_POOL) {
            throw new AxmlException("Expected string pool chunk, got 0x"
                    + Integer.toHexString(poolType));
        }
        parseStringPool();
    }

    private void parseStringPool() {
        int base = poolChunkOffset;
        poolChunkSize = u32(base + 4);
        int stringCount = u32(base + 8);
        poolFlags = u32(base + 16);
        utf8 = (poolFlags & FLAG_UTF8) != 0;
        int stringsStart = u32(base + 20); // offset from chunk start to string data
        int offsetsArray = base + 28;      // uint32[stringCount] of per-string offsets

        strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int rel = u32(offsetsArray + i * 4);
            int pos = base + stringsStart + rel;
            strings.add(utf8 ? decodeUtf8(pos) : decodeUtf16(pos));
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
        // First: number of UTF-16 chars (may be 1 or 2 bytes). We skip it.
        int n = data[pos++] & 0xFF;
        if ((n & 0x80) != 0) {
            pos++; // two-byte char length
        }
        // Then: number of encoded bytes (may be 1 or 2 bytes).
        int byteLen = data[pos++] & 0xFF;
        if ((byteLen & 0x80) != 0) {
            byteLen = ((byteLen & 0x7F) << 8) | (data[pos++] & 0xFF);
        }
        return new String(data, pos, byteLen, StandardCharsets.UTF_8);
    }

    // ---- manifest package --------------------------------------------------

    /** Returns the {@code package} value declared on the {@code <manifest>} element. */
    public String getManifestPackage() {
        int idx = findManifestPackageStringIndex();
        if (idx < 0) {
            throw new AxmlException("No package attribute found on <manifest>");
        }
        return strings.get(idx);
    }

    /**
     * Walks XML start-element chunks to locate the {@code <manifest>} element and the
     * string-pool index of its {@code package} attribute value.
     */
    private int findManifestPackageStringIndex() {
        int manifestNameIdx = strings.indexOf("manifest");
        int packageNameIdx = strings.indexOf("package");
        if (manifestNameIdx < 0 || packageNameIdx < 0) {
            return -1;
        }
        int off = poolChunkOffset + poolChunkSize;
        while (off + 8 <= data.length) {
            int type = u16(off);
            int size = u32(off + 4);
            if (size <= 0) break;
            if (type == TYPE_START_ELEMENT) {
                // ResXMLTree_attrExt layout follows the 16-byte node header:
                //   +16 ns(u32) +20 name(u32) +24 attributeStart(u16) +26 attributeSize(u16)
                //   +28 attributeCount(u16) ...
                int nameIdx = u32(off + 20);
                if (nameIdx == manifestNameIdx) {
                    int attrStart = u16(off + 24);
                    int attrCount = u16(off + 28);
                    int attrBase = off + 16 + attrStart;
                    for (int a = 0; a < attrCount; a++) {
                        int ab = attrBase + a * 20; // each ResXMLTree_attribute is 20 bytes
                        int aName = u32(ab + 4);
                        int aRawValue = u32(ab + 8); // string index or -1
                        if (aName == packageNameIdx && aRawValue >= 0) {
                            return aRawValue;
                        }
                    }
                }
            }
            off += size;
        }
        return -1;
    }

    // ---- rewriting ---------------------------------------------------------

    /**
     * Rewrites pooled strings for a package rename.
     *
     * <p>For every pooled string {@code s}: if {@code s} equals {@code oldPkg} it becomes
     * {@code newPkg}; if {@code s} starts with {@code oldPkg + "."} its prefix is replaced
     * with {@code newPkg}. Relative names beginning with {@code "."} are left untouched —
     * they resolve against the (now new) package automatically.
     *
     * @return number of strings changed.
     */
    public int renamePackage(String oldPkg, String newPkg) {
        int changed = 0;
        String prefix = oldPkg + ".";
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);
            if (s.equals(oldPkg)) {
                strings.set(i, newPkg);
                changed++;
            } else if (s.startsWith(prefix)) {
                strings.set(i, newPkg + "." + s.substring(prefix.length()));
                changed++;
            }
        }
        return changed;
    }

    /** Appends a new string to the pool (indices of existing strings are preserved). */
    public int addString(String s) {
        strings.add(s);
        return strings.size() - 1;
    }

    /**
     * Sets the {@code <application>}'s {@code android:label} to an inline string (the new
     * display name), overriding any {@code @string/...} reference it held.
     *
     * <p>Android uses the application label as the default for components that don't declare
     * their own, so this renames what most launchers show. Activities with their own explicit
     * label keep it.
     *
     * @return true if the application element had a label attribute that was updated.
     */
    public boolean setApplicationLabel(String newLabel) {
        int off = findFirstElementOffset("application");
        if (off < 0) {
            return false;
        }
        int[] resMap = resourceMap();
        int attrStart = u16(off + 24);
        int attrCount = u16(off + 28);
        int base = off + 16 + attrStart;
        for (int a = 0; a < attrCount; a++) {
            int ab = base + a * 20;
            int nameIdx = u32(ab + 4);
            int resId = (nameIdx >= 0 && nameIdx < resMap.length) ? resMap[nameIdx] : -1;
            if (resId == ATTR_LABEL) {
                int idx = addString(newLabel);
                writeU32(data, ab + 8, idx);              // rawValue -> new string
                data[ab + 15] = (byte) TYPE_STRING_VALUE; // typedValue.dataType = string
                writeU32(data, ab + 16, idx);             // typedValue.data -> new string
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the resource IDs referenced by {@code attrResId} on every element named
     * {@code elementName}. Used to resolve adaptive-icon {@code <foreground>}/{@code <background>}
     * drawables to their underlying resources.
     */
    public java.util.List<Integer> getAttrReferences(String elementName, int attrResId) {
        java.util.List<Integer> out = new ArrayList<>();
        int nameIdx = strings.indexOf(elementName);
        if (nameIdx < 0) {
            return out;
        }
        int[] resMap = resourceMap();
        int off = poolChunkOffset + poolChunkSize;
        while (off + 8 <= data.length) {
            int type = u16(off);
            int size = u32(off + 4);
            if (size <= 0) break;
            if (type == TYPE_START_ELEMENT && u32(off + 20) == nameIdx) {
                int attrStart = u16(off + 24);
                int attrCount = u16(off + 28);
                int base = off + 16 + attrStart;
                for (int a = 0; a < attrCount; a++) {
                    int ab = base + a * 20;
                    int ni = u32(ab + 4);
                    int resId = (ni >= 0 && ni < resMap.length) ? resMap[ni] : -1;
                    if (resId == attrResId && (data[ab + 15] & 0xFF) == TYPE_REFERENCE) {
                        out.add(u32(ab + 16));
                    }
                }
            }
            off += size;
        }
        return out;
    }

    private int findFirstElementOffset(String elementName) {
        int nameIdx = strings.indexOf(elementName);
        if (nameIdx < 0) {
            return -1;
        }
        int off = poolChunkOffset + poolChunkSize;
        while (off + 8 <= data.length) {
            int type = u16(off);
            int size = u32(off + 4);
            if (size <= 0) break;
            if (type == TYPE_START_ELEMENT && u32(off + 20) == nameIdx) {
                return off;
            }
            off += size;
        }
        return -1;
    }

    private int[] resourceMap() {
        int off = poolChunkOffset + poolChunkSize;
        while (off + 8 <= data.length) {
            int type = u16(off);
            int size = u32(off + 4);
            if (size <= 0) break;
            if (type == TYPE_RESOURCE_MAP) {
                int n = (size - 8) / 4;
                int[] m = new int[n];
                for (int i = 0; i < n; i++) {
                    m[i] = u32(off + 8 + i * 4);
                }
                return m;
            }
            if (type == TYPE_START_ELEMENT) {
                break; // the resource map always precedes the XML body
            }
            off += size;
        }
        return new int[0];
    }

    /** Serializes the (possibly modified) AXML back to bytes. */
    public byte[] toByteArray() {
        byte[] newPool = buildStringPoolChunk();

        int before = poolChunkOffset;                    // bytes before the pool chunk (file header)
        int afterOff = poolChunkOffset + poolChunkSize;  // everything after the pool chunk
        int afterLen = data.length - afterOff;

        int newTotal = before + newPool.length + afterLen;
        byte[] out = new byte[newTotal];

        System.arraycopy(data, 0, out, 0, before);
        System.arraycopy(newPool, 0, out, before, newPool.length);
        System.arraycopy(data, afterOff, out, before + newPool.length, afterLen);

        // Patch file-level chunk size (offset 4) and pool chunk size (offset before+4).
        writeU32(out, 4, newTotal);
        writeU32(out, before + 4, newPool.length);
        return out;
    }

    private byte[] buildStringPoolChunk() {
        int count = strings.size();

        // Encode each string.
        List<byte[]> encoded = new ArrayList<>(count);
        for (String s : strings) {
            encoded.add(utf8 ? encodeUtf8(s) : encodeUtf16(s));
        }

        // String data section (with 4-byte alignment of the total section for safety).
        ByteArrayOutputStream dataSection = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        int running = 0;
        for (int i = 0; i < count; i++) {
            offsets[i] = running;
            byte[] e = encoded.get(i);
            dataSection.write(e, 0, e.length);
            running += e.length;
        }
        byte[] stringData = dataSection.toByteArray();

        int headerSize = 28;
        int offsetsSize = count * 4;
        int stringsStart = headerSize + offsetsSize; // no style offsets (styleCount = 0)
        int rawSize = stringsStart + stringData.length;
        int padded = (rawSize + 3) & ~3; // align chunk to 4 bytes

        byte[] chunk = new byte[padded];
        writeU16(chunk, 0, TYPE_STRING_POOL);
        writeU16(chunk, 2, headerSize);
        writeU32(chunk, 4, padded);
        writeU32(chunk, 8, count);
        writeU32(chunk, 12, 0);           // styleCount
        writeU32(chunk, 16, poolFlags);   // preserve UTF8/sorted flags
        writeU32(chunk, 20, stringsStart);
        writeU32(chunk, 24, 0);           // stylesStart
        for (int i = 0; i < count; i++) {
            writeU32(chunk, headerSize + i * 4, offsets[i]);
        }
        System.arraycopy(stringData, 0, chunk, stringsStart, stringData.length);
        return chunk;
    }

    private static byte[] encodeUtf16(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
        int chars = body.length / 2;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (chars > 0x7FFF) {
            int high = (chars >> 16) | 0x8000;
            b.write(high & 0xFF);
            b.write((high >> 8) & 0xFF);
        }
        b.write(chars & 0xFF);
        b.write((chars >> 8) & 0xFF);
        b.write(body, 0, body.length);
        b.write(0); // u16 null terminator
        b.write(0);
        return b.toByteArray();
    }

    private static byte[] encodeUtf8(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_8);
        int chars = s.length(); // UTF-16 char count
        int bytes = body.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeLen8(b, chars);
        writeLen8(b, bytes);
        b.write(body, 0, body.length);
        b.write(0); // null terminator
        return b.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream b, int len) {
        if (len > 0x7F) {
            b.write(((len >> 8) & 0x7F) | 0x80);
            b.write(len & 0xFF);
        } else {
            b.write(len & 0x7F);
        }
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
