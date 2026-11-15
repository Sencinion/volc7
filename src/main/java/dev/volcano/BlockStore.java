package dev.volcano;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A compact list of (position -> original block data), used for the paste snapshot and the event change-log. */
public final class BlockStore {
    final long[] keys;
    final int[] idx;
    final BlockData[] palette;

    BlockStore(long[] keys, int[] idx, BlockData[] palette) { this.keys = keys; this.idx = idx; this.palette = palette; }

    int size() { return keys.length; }

    static BlockStore fromMap(Map<Long, BlockData> m) {
        long[] k = new long[m.size()];
        int[] ix = new int[m.size()];
        Map<BlockData, Integer> pal = new HashMap<>();
        List<BlockData> list = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Long, BlockData> e : m.entrySet()) {
            k[i] = e.getKey();
            Integer p = pal.get(e.getValue());
            if (p == null) { p = list.size(); pal.put(e.getValue(), p); list.add(e.getValue()); }
            ix[i++] = p;
        }
        return new BlockStore(k, ix, list.toArray(new BlockData[0]));
    }

    void save(File f) throws IOException {
        f.getParentFile().mkdirs();
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f))))) {
            o.writeInt(palette.length);
            for (BlockData b : palette) o.writeUTF(b.getAsString());
            o.writeInt(keys.length);
            for (int i = 0; i < keys.length; i++) { o.writeLong(keys[i]); o.writeInt(idx[i]); }
        }
    }

    static BlockStore load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            BlockData[] pal = new BlockData[in.readInt()];
            for (int i = 0; i < pal.length; i++) pal[i] = Bukkit.createBlockData(in.readUTF());
            int n = in.readInt();
            long[] k = new long[n]; int[] ix = new int[n];
            for (int i = 0; i < n; i++) { k[i] = in.readLong(); ix[i] = in.readInt(); }
            return new BlockStore(k, ix, pal);
        }
    }

    /** Time-sliced job that writes every stored block back into the world. */
    static final class RestoreJob implements VolcanoPlugin.Job {
        final World w; final BlockStore s; final Runnable done; int i = 0;
        RestoreJob(World w, BlockStore s, Runnable done) { this.w = w; this.s = s; this.done = done; }
        public boolean run(long deadline) {
            int n = 0;
            while (i < s.keys.length) {
                long k = s.keys[i];
                Block b = w.getBlockAt(VolcanoEvent.ux(k), VolcanoEvent.uy(k), VolcanoEvent.uz(k));
                b.setBlockData(s.palette[s.idx[i]], false);
                i++;
                if ((++n & 255) == 0 && System.nanoTime() > deadline) return false;
            }
            if (done != null) done.run();
            return true;
        }
    }
}
