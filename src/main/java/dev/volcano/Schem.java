package dev.volcano;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Minimal Sponge Schematic v2 (.schem) reader - no WorldEdit/FAWE required. */
public final class Schem {
    public final int width, height, length, offX, offY, offZ;
    public final BlockData[] palette;
    public final int[] blocks;
    public final int airIndex;

    private Schem(int w, int h, int l, int ox, int oy, int oz, BlockData[] pal, int[] blocks, int air) {
        width = w; height = h; length = l; offX = ox; offY = oy; offZ = oz;
        palette = pal; this.blocks = blocks; airIndex = air;
    }

    public int get(int x, int y, int z) { return blocks[x + z * width + y * width * length]; }

    @SuppressWarnings("unchecked")
    public static Schem load(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in)));
        d.readByte();
        d.readUTF();
        Map<String, Object> root = (Map<String, Object>) read(d, 10);
        if (root.containsKey("Schematic")) root = (Map<String, Object>) root.get("Schematic");

        int w = ((Number) root.get("Width")).intValue();
        int h = ((Number) root.get("Height")).intValue();
        int l = ((Number) root.get("Length")).intValue();
        int[] off = root.containsKey("Offset") ? (int[]) root.get("Offset") : new int[]{0, 0, 0};

        Map<String, Object> pal = (Map<String, Object>) root.get("Palette");
        BlockData[] palette = new BlockData[pal.size()];
        int air = -1;
        for (Map.Entry<String, Object> e : pal.entrySet()) {
            int idx = ((Number) e.getValue()).intValue();
            palette[idx] = Bukkit.createBlockData(e.getKey());
            if (e.getKey().equals("minecraft:air")) air = idx;
        }

        byte[] data = (byte[]) root.get("BlockData");
        int[] blocks = new int[w * h * l];
        int pos = 0;
        for (int i = 0; i < blocks.length; i++) {
            int value = 0, shift = 0;
            while (true) {
                int b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            blocks[i] = value;
        }
        return new Schem(w, h, l, off[0], off[1], off[2], palette, blocks, air);
    }

    private static Object read(DataInputStream d, int t) throws IOException {
        switch (t) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { int n = d.readInt(); byte[] a = new byte[n]; d.readFully(a); return a; }
            case 8: return d.readUTF();
            case 9: {
                int tt = d.readByte(); int n = d.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) list.add(read(d, tt));
                return list;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int tt = d.readByte();
                    if (tt == 0) break;
                    String name = d.readUTF();
                    m.put(name, read(d, tt));
                }
                return m;
            }
            case 11: { int n = d.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = d.readInt(); return a; }
            case 12: { int n = d.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("Unknown NBT tag " + t);
        }
    }
}
