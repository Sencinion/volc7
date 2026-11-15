package dev.volcano;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class VolcanoEvent {

    enum State { PRE, ACTIVE, DONE, STOPPED }

    // ---- packed positions (26-bit x, 26-bit z, 12-bit y)
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) ((y + 2048) & 0xFFF);
    }
    static int ux(long k) { return (int) (k >> 38); }
    static int uz(long k) { return (int) ((k << 26) >> 38); }
    static int uy(long k) { return (int) (k & 0xFFF) - 2048; }

    private static final int FOOT = 75;                 // radius of the mountain base

    private final VolcanoPlugin plugin;
    private final FileConfiguration cfg;
    private final World world;
    private final Schem schem;
    private final int cx, cz, rimY;
    private final Random rnd = new Random();

    private State state = State.PRE;
    private BukkitTask task;
    private final int preTotal, phaseTicks, totalTicks;
    private int preElapsed = 0, activeElapsed = 0, doneTicks = 0;

    // everything this event ever changed, keyed by position -> the block that was there before
    private final Map<Long, BlockData> changes = new HashMap<>();

    // lava
    private final List<int[]> cells = new ArrayList<>();   // timed source blocks: {tick,x,z,ringDist}
    private int cellPtr = 0;
    private int iMax, limitR;
    private final Set<Long> touchQueue = new HashSet<>();   // lava that just moved: delete what it touches
    private final Set<Long> sourceQueue = new HashSet<>();  // flowing lava that should become source lava
    private Particle leaf, ash;
    private int promoted = 0;                 // how many flowing blocks have been turned into sources
    private final int maxPromoted;
    private final double corridorGrowth;
    private final Set<Long> lavaSet = new HashSet<>();       // every spot lava has been (re)checked at, for the periodic sweep
    private final List<Long> lavaList = new ArrayList<>();
    private int sweepPtr = 0;
    private Team debrisTeam;                                  // gives the glowing debris its orange outline
    private static final Material[] DEBRIS = {Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.BLACKSTONE};

    // glass
    private final List<Long> glass = new ArrayList<>();
    private final List<int[]> paneCols = new ArrayList<>();
    private final List<long[]> plume = new ArrayList<>();
    private int plumeIdx = 0;

    // debris / bars / death causes
    private final Set<FallingBlock> debris = new HashSet<>();
    private final Set<FallingBlock> ejecta = new HashSet<>();   // blocks thrown up by landing debris
    private Particle puff, crack;
    private UUID bypassArmor;                 // the player currently being hit by debris (armor is ignored for that hit)
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, String> cause = new HashMap<>();
    private final Map<UUID, Long> causeTime = new HashMap<>();

    VolcanoEvent(VolcanoPlugin plugin, int preSeconds) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        this.world = plugin.world;
        this.schem = plugin.schem;
        this.cx = plugin.ox;
        this.cz = plugin.oz;
        this.preTotal = preSeconds * 20;
        this.phaseTicks = cfg.getInt("settings.phase-seconds", 60) * 20;
        this.totalTicks = phaseTicks * 5;

        int lx = -schem.offX, lz = -schem.offZ;
        this.rimY = plugin.oy + highest(lx, lz) + schem.offY + 1;

        this.maxPromoted = cfg.getInt("settings.lava.max-promoted-sources", 40000);
        this.corridorGrowth = cfg.getDouble("settings.lava.corridor-growth", 0.3);
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team old = sb.getTeam("volcano_debris");
            if (old != null) old.unregister();
            debrisTeam = sb.registerNewTeam("volcano_debris");
            debrisTeam.setColor(ChatColor.GOLD);
        } catch (Exception ignored) { debrisTeam = null; }
        planFlow(cfg.getInt("settings.lava.flow-length", 100));
        for (String n : cfg.getStringList("settings.fumes.leaf-particles")) {
            try { leaf = Particle.valueOf(n.toUpperCase()); break; } catch (IllegalArgumentException ignored) { }
        }
        try { ash = Particle.valueOf("ASH"); } catch (IllegalArgumentException ignored) { }
        puff = pick("EXPLOSION_NORMAL", "POOF");
        crack = pick("BLOCK_CRACK", "BLOCK");

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                if (dx * dx + dz * dz <= 7 && rnd.nextDouble() < 0.75)
                    paneCols.add(new int[]{dx, dz, 0, 40 + rnd.nextInt(61)});
    }

    private int highest(int lx, int lz) {
        for (int y = schem.height - 1; y >= 0; y--) if (schem.get(lx, y, lz) != schem.airIndex) return y;
        return -1;
    }

    // =========================================================== change log
    void capture(Block b) { changes.putIfAbsent(pack(b.getX(), b.getY(), b.getZ()), b.getBlockData()); }

    private void set(Block b, Material m, boolean physics) {
        capture(b);
        b.setType(m, physics);
    }

    void persist(File f) throws IOException {
        if (!changes.isEmpty()) BlockStore.fromMap(changes).save(f);
    }

    private void restoreSubset(List<Long> keys) {
        Map<Long, BlockData> sub = new HashMap<>();
        for (long k : keys) {
            BlockData d = changes.remove(k);
            if (d != null) sub.put(k, d);
        }
        if (!sub.isEmpty()) plugin.jobs.add(new BlockStore.RestoreJob(world, BlockStore.fromMap(sub), null));
    }

    // ================================================================ lifecycle
    void begin() {
        broadcast(plugin.msg("messages.start").replace("%time%", fmt(preTotal)));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean tracking(World w) { return state != State.STOPPED && w.equals(world); }
    boolean isActiveIn(World w) { return (state == State.PRE || state == State.ACTIVE) && w.equals(world); }
    boolean isDebris(Entity e) { return e instanceof FallingBlock && debris.contains(e); }

    String consumeCause(Player p) {
        Long t = causeTime.get(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 1500) return cause.get(p.getUniqueId());
        return null;
    }

    void dropBar(Player p) {
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) b.removeAll();
    }

    void shutdownNow() {
        if (task != null) task.cancel();
        clearBars();
        for (FallingBlock f : debris) f.remove();
        debris.clear();
        for (FallingBlock f : ejecta) f.remove();     // the blocks they came from are restored by the change log
        ejecta.clear();
        dropTeam();
    }

    private void dropTeam() {
        if (debrisTeam == null) return;
        try { debrisTeam.unregister(); } catch (Exception ignored) { }
        debrisTeam = null;
    }

    /** Stops the event and puts back every block it changed (lava, magma, glass, destroyed wood...). */
    void cleanupAll() {
        state = State.STOPPED;
        shutdownNow();
        if (!changes.isEmpty())
            plugin.jobs.add(new BlockStore.RestoreJob(world, BlockStore.fromMap(changes), null));
        changes.clear(); glass.clear(); cells.clear();
    }

    private void tick() {
        if (state == State.PRE) tickPre();
        else if (state == State.ACTIVE) tickActive();
        else if (state == State.DONE && ++doneTicks % 2 == 0) processLava();   // lava keeps flowing after the event
    }

    // ================================================================== PRE
    private void tickPre() {
        int remaining = preTotal - preElapsed;
        if (preElapsed % 20 == 0) {
            int secs = remaining / 20;
            if (secs > 0 && cfg.getIntegerList("countdown-announce").contains(secs))
                broadcast(plugin.msg("messages.countdown").replace("%time%", fmt(remaining)));
        }
        if (preElapsed % 10 == 0) risePanes(preElapsed / (double) preTotal);
        if (preElapsed % 5 == 0) updateBars("pre", remaining, preTotal);
        preElapsed++;
        if (preElapsed >= preTotal) {
            state = State.ACTIVE;
            buildPlume();
        }
    }

    private void risePanes(double progress) {
        int max = cfg.getInt("settings.pane-rise-height", 40);
        for (int[] c : paneCols) {
            double wobble = 0.85 + 0.3 * Math.sin(preElapsed * 0.05 + c[0] * 1.7 + c[1]);
            int target = (int) Math.round(progress * max * (c[3] / 100.0) * wobble);
            while (c[2] < target) {
                Block b = world.getBlockAt(cx + c[0], rimY + c[2], cz + c[1]);
                if (b.getType().isAir()) {
                    set(b, Material.GRAY_STAINED_GLASS_PANE, false);
                    glass.add(pack(b.getX(), b.getY(), b.getZ()));
                }
                c[2]++;
            }
        }
    }

    // ============================================================== ACTIVE
    private void tickActive() {
        int t = activeElapsed;
        int section = t / phaseTicks;

        if (t % phaseTicks == 0) {
            if (section == 0) broadcast(plugin.msg("messages.eruption"));
            else if (section == 1) broadcast(plugin.msg("messages.flowing"));
            else if (section == 3) broadcast(plugin.msg("messages.fumes"));
        }

        if (t < phaseTicks * 3 + 40) placeCells(t);   // the lava front advances through minutes 1-3
        if (t % 2 == 0) processLava();

        placePlume();

        if (t % 20 == 0) spawnDebris();
        tickDebris();

        if (t % 5 == 0) {
            killMobsInLava();
            updateBars(section == 0 ? "eruption" : section <= 2 ? "flowing" : "fumes", totalTicks - t, totalTicks);
        }
        if (t % 10 == 0) heat();
        if (section >= 3 && t % (cfg.getInt("settings.fumes.interval-seconds", 2) * 20) == 0) fumes();
        if (section >= 3 && t % 2 == 0) ashParticles(t);

        activeElapsed++;
        if (activeElapsed >= totalTicks) finish();
    }

    private void finish() {
        state = State.DONE;
        broadcast(plugin.msg("messages.end"));
        clearBars();
        for (FallingBlock f : debris) f.remove();
        debris.clear();
        for (FallingBlock f : ejecta) f.remove();
        ejecta.clear();
        dropTeam();
        if (cfg.getBoolean("settings.cleanup-plume-on-end", true)) { restoreSubset(glass); glass.clear(); }
    }

    // ------------------------------------------------------------- plume
    /**
     * Organic plume: a wind-leaning, wobbling stem plus a cap of many random overlapping puffs.
     * Everything is a hollow, ragged shell of glass; colour (white -> gray -> black) is noise-jittered.
     */
    private void buildPlume() {
        final int PH = cfg.getInt("settings.plume-height", 70);
        final int R = 44, GX = 2 * R + 1, GH = PH + 14;
        float[] dens = new float[GX * GH * GX];

        double wind = rnd.nextDouble() * Math.PI * 2;
        double lean = 5 + rnd.nextDouble() * 11;
        double p1 = rnd.nextDouble() * 6, p2 = rnd.nextDouble() * 6, p3 = rnd.nextDouble() * 6;
        int stemEnd = (int) (PH * (0.55 + rnd.nextDouble() * 0.12));

        List<double[]> shapes = new ArrayList<>();   // {x, y, z, radius, vertical squash}
        for (int h = 0; h <= stemEnd; h += 2) {
            double f = h / (double) PH;
            double ax = Math.cos(wind) * lean * Math.pow(f, 1.6) + Math.sin(h * 0.21 + p1) * 1.8;
            double az = Math.sin(wind) * lean * Math.pow(f, 1.6) + Math.cos(h * 0.17 + p2) * 1.8;
            double r = 3.0 + 3.2 * (h / (double) stemEnd) + Math.sin(h * 0.45 + p3) * 1.0 + rnd.nextDouble() * 0.9;
            shapes.add(new double[]{ax, h, az, r, 1.0});
        }
        double fe = stemEnd / (double) PH;
        double ax0 = Math.cos(wind) * lean * Math.pow(fe, 1.6), az0 = Math.sin(wind) * lean * Math.pow(fe, 1.6);
        shapes.add(new double[]{ax0, stemEnd + 6, az0, 8 + rnd.nextDouble() * 4, 1.0});
        int puffs = 18 + rnd.nextInt(10);
        for (int i = 0; i < puffs; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, rad = Math.sqrt(rnd.nextDouble()) * 16;
            double y = stemEnd - 2 + rnd.nextDouble() * (PH - stemEnd + 4);
            shapes.add(new double[]{ax0 + Math.cos(ang) * rad, y, az0 + Math.sin(ang) * rad, 4 + rnd.nextDouble() * 5.5, 1.15});
        }
        for (int i = 0; i < 5; i++) {       // lumps budding off the stem
            double h = 6 + rnd.nextDouble() * (stemEnd - 8), ang = rnd.nextDouble() * Math.PI * 2;
            double f = h / PH;
            shapes.add(new double[]{Math.cos(wind) * lean * Math.pow(f, 1.6) + Math.cos(ang) * 5, h,
                    Math.sin(wind) * lean * Math.pow(f, 1.6) + Math.sin(ang) * 5, 2.5 + rnd.nextDouble() * 2.5, 1.0});
        }

        for (double[] s : shapes) {
            double r = s[3];
            int x0 = (int) Math.floor(s[0] - r), x1 = (int) Math.ceil(s[0] + r);
            int y0 = (int) Math.floor(s[1] - r * s[4]), y1 = (int) Math.ceil(s[1] + r * s[4]);
            int z0 = (int) Math.floor(s[2] - r), z1 = (int) Math.ceil(s[2] + r);
            for (int x = Math.max(-R, x0); x <= Math.min(R, x1); x++)
                for (int y = Math.max(0, y0); y <= Math.min(GH - 1, y1); y++)
                    for (int z = Math.max(-R, z0); z <= Math.min(R, z1); z++) {
                        double dx = x - s[0], dy = (y - s[1]) * s[4], dz = z - s[2];
                        float v = (float) (1.0 - Math.sqrt(dx * dx + dy * dy + dz * dz) / r);
                        int gi = ((x + R) * GH + y) * GX + (z + R);
                        if (v > dens[gi]) dens[gi] = v;
                    }
        }

        long seed = rnd.nextLong();
        List<double[]> out = new ArrayList<>();   // {order, packedPos, material ordinal}
        Material[] mats = Material.values();
        for (int x = -R; x <= R; x++)
            for (int y = 0; y < GH; y++)
                for (int z = -R; z <= R; z++) {
                    float v = dens[((x + R) * GH + y) * GX + (z + R)];
                    if (v <= 0) continue;
                    double j1 = hash(x, y, z, seed), j2 = hash(x, y, z, seed ^ 0x5DEECE66DL);
                    if (v > 0.28 + 0.2 * j1) continue;          // hollow, ragged shell
                    if (v < 0.1 && j2 < 0.4) continue;           // wispy edges
                    if (rimY + y >= world.getMaxHeight()) continue;
                    double f = y / (double) PH + (hash(x / 3, y / 3, z / 3, seed + 7) - 0.5) * 0.3 + (j2 - 0.5) * 0.08;
                    Material m = f < 0.33 ? Material.WHITE_STAINED_GLASS
                            : f < 0.66 ? Material.GRAY_STAINED_GLASS : Material.BLACK_STAINED_GLASS;
                    out.add(new double[]{y + rnd.nextDouble() * 3, pack(cx + x, rimY + y, cz + z), m.ordinal()});
                }
        out.sort(Comparator.comparingDouble(a -> a[0]));
        for (double[] e : out) plume.add(new long[]{(long) e[1], (long) e[2]});
    }

    private static double hash(int x, int y, int z, long seed) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ seed;
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        h *= 0xBF58476D1CE4E5B9L;
        return ((h >>> 40) & 0xFFFFFF) / (double) 0x1000000;
    }

    private void placePlume() {
        if (plumeIdx >= plume.size()) return;
        int buildTicks = Math.max(1, cfg.getInt("settings.plume-build-seconds", 30) * 20);
        int perTick = (int) Math.ceil(plume.size() / (double) buildTicks);
        Material[] mats = Material.values();
        for (int n = 0; n < perTick && plumeIdx < plume.size(); n++, plumeIdx++) {
            long[] e = plume.get(plumeIdx);
            Block b = world.getBlockAt(ux(e[0]), uy(e[0]), uz(e[0]));
            Material cur = b.getType();
            if (cur.isAir() || cur == Material.GRAY_STAINED_GLASS_PANE) {
                set(b, mats[(int) e[1]], false);
                glass.add(e[0]);
            }
        }
    }

    // -------------------------------------------------------------- lava
    /**
     * How the lava moves:
     *  - A few timed rows of source blocks are laid down each engraved channel and across the plain. They only set the
     *    pace (fast down the mountain, slower on flat ground); the real work is vanilla lava physics.
     *  - Whenever lava flows into a spot inside the river corridor, that flowing lava is turned into a SOURCE block, so
     *    it keeps spreading instead of dying after 3 blocks. That chain carries it the whole `flow-length`.
     *  - Any block that lava touches from the side (outside the mountain itself) is deleted together with everything
     *    stacked above it, and the lava flows into the gap.
     *  - Any flow past `flow-length` blocks from the crater edge is cancelled (the stop line).
     */
    private void planFlow(int flowLength) {
        limitR = 8 + Math.max(10, flowLength);               // stop line, measured from the crater centre
        iMax = Math.max(24, limitR - 12);                    // last pre-placed row: the final stretch is pure lava physics
        int pace = Math.max(1, cfg.getInt("settings.lava.feeder-spacing", 4));
        for (int dir = 0; dir < 4; dir++)
            for (int i = 8; i <= iMax; i += pace)
                for (int k = -2; k <= 2; k++) {
                    int[] w = rayPos(dir, i, k);
                    cells.add(new int[]{rowTime(i) + rnd.nextInt(10) + Math.abs(k) * 2, w[0], w[1], i});
                }
        cells.sort(Comparator.comparingInt(a -> a[0]));
    }

    private int[] rayPos(int dir, int i, int kk) {
        switch (dir) {
            case 0: return new int[]{cx + kk, cz - i};
            case 1: return new int[]{cx + kk, cz + i};
            case 2: return new int[]{cx - i, cz + kk};
            default: return new int[]{cx + i, cz + kk};
        }
    }

    /** Fast down the mountain (about one minute), then slowing as it spreads over the plain. */
    private int rowTime(int i) {
        if (i < FOOT) return (int) ((i - 8) / (double) (FOOT - 1 - 8) * phaseTicks);
        double u = Math.min(1, (i - (FOOT - 1)) / (double) Math.max(1, iMax - (FOOT - 1)));
        double e = 1 - Math.pow(1 - u, 1.7);
        return phaseTicks + (int) (e * 2 * phaseTicks * 0.85);
    }

    private void placeCells(int t) {
        int n = 0;
        while (cellPtr < cells.size() && cells.get(cellPtr)[0] <= t && n++ < 600) placeCell(cells.get(cellPtr++));
    }

    private void placeCell(int[] c) {
        int x = c[1], z = c[2];
        int y = surfaceY(x, z, c[3]);
        if (y == Integer.MIN_VALUE) return;
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m == Material.WATER) {                    // lava meeting water makes magma
            set(b, Material.MAGMA_BLOCK, false);
            return;
        }
        if (!m.isAir() && m != Material.LAVA) destroyColumn(b);    // something is in the way: it just goes away
        setLava(b);
        queueTouch(b);
    }

    /** Source lava with physics on: it ticks and spreads like normal lava. */
    private void setLava(Block b) {
        capture(b);
        b.setType(Material.LAVA, true);
    }

    private int surfaceY(int wx, int wz, int i) {
        if (i < FOOT) {
            int lx = wx - plugin.ox - schem.offX, lz = wz - plugin.oz - schem.offZ;
            if (lx >= 0 && lz >= 0 && lx < schem.width && lz < schem.length) {
                int f = highest(lx, lz);
                if (f >= 0) return plugin.oy + schem.offY + f + 1;
            }
        }
        for (int y = plugin.oy + 60; y >= world.getMinHeight(); y--) {
            Material m = world.getBlockAt(wx, y, wz).getType();
            if (m.isAir() || !m.isSolid() || isWood(m)) continue;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    // ---- stop line and river corridor
    boolean flowAllowed(Block to) {
        return Math.hypot(to.getX() - cx, to.getZ() - cz) <= limitR;
    }

    /** The widening strip around each of the four channels where flowing lava is promoted to source lava. */
    private boolean inCorridor(Block b) {
        int dx = Math.abs(b.getX() - cx), dz = Math.abs(b.getZ() - cz);
        int i = Math.max(dx, dz), k = Math.min(dx, dz);
        if (i < 8) return false;
        double w = i < FOOT ? 5 : 5 + (i - (FOOT - 1)) * corridorGrowth;
        return k <= w;
    }

    /** Called for every allowed lava flow (from the BlockFromToEvent listener). */
    void onLavaFlow(Block from, Block to) {
        queueTouch(to);
        if (to.getY() < from.getY()) return;                         // falling lava is never promoted
        if (!to.getRelative(0, -1, 0).getType().isSolid()) return;  // only lava resting on solid ground: a source in mid-air spreads sideways forever
        double dFrom = Math.hypot(from.getX() - cx, from.getZ() - cz), dTo = Math.hypot(to.getX() - cx, to.getZ() - cz);
        if (dTo >= dFrom - 0.5 && inCorridor(to) && sourceQueue.size() < 8000)
            sourceQueue.add(pack(to.getX(), to.getY(), to.getZ()));
    }

    private void queueTouch(Block b) {
        long k = pack(b.getX(), b.getY(), b.getZ());
        if (touchQueue.size() < 40000) touchQueue.add(k);
        if (lavaList.size() < 200000 && lavaSet.add(k)) lavaList.add(k);
    }

    private void processLava() {
        // flowing lava -> source lava, so it keeps moving
        if (!sourceQueue.isEmpty()) {
            long[] src = new long[sourceQueue.size()];
            int n = 0;
            for (long k : sourceQueue) src[n++] = k;
            sourceQueue.clear();
            for (long k : src) {
                Block b = world.getBlockAt(ux(k), uy(k), uz(k));
                if (b.getType() != Material.LAVA || promoted >= maxPromoted) continue;
                if (!b.getRelative(0, -1, 0).getType().isSolid()) continue;
                if (((org.bukkit.block.data.Levelled) b.getBlockData()).getLevel() != 0) { setLava(b); promoted++; queueTouch(b); }
            }
        }

        // lava deletes what it touches from the side and whatever is above it
        if (!touchQueue.isEmpty()) {
            long[] tch = new long[Math.min(touchQueue.size(), 6000)];
            int n = 0;
            Iterator<Long> it = touchQueue.iterator();
            while (it.hasNext() && n < tch.length) { tch[n++] = it.next(); it.remove(); }
            for (long k : tch) {
                Block b = world.getBlockAt(ux(k), uy(k), uz(k));
                if (b.getType() == Material.LAVA && touchLava(b)) queueTouch(b);
            }
        }

        sweep();
    }

    /**
     * Lava removes blocks beside it and above it (never below), plus everything stacked on top of what it removes.
     * Returns true if it removed anything.
     */
    private boolean touchLava(Block b) {
        boolean any = false;
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : side) {
            Block nb = b.getRelative(o[0], 0, o[1]);
            if (destroyable(nb)) { destroyColumn(nb); any = true; }
        }
        Block up = b.getRelative(0, 1, 0);          // roofs, bridges and overhangs above lava collapse
        if (destroyable(up)) { destroyColumn(up); any = true; }
        return any;
    }

    /** Re-checks lava we already know about, so anything that changed (or was missed) next to it still goes. */
    private void sweep() {
        int n = Math.min(400, lavaList.size());
        for (int c = 0; c < n; c++) {
            if (sweepPtr >= lavaList.size()) sweepPtr = 0;
            long k = lavaList.get(sweepPtr++);
            Block b = world.getBlockAt(ux(k), uy(k), uz(k));
            if (b.getType() == Material.LAVA) touchLava(b);
        }
    }

    private boolean destroyable(Block b) {
        Material m = b.getType();
        if (m.isAir() || m == Material.LAVA || m == Material.WATER || m == Material.MAGMA_BLOCK) return false;
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME || m == Material.END_PORTAL
                || m == Material.NETHER_PORTAL || m == Material.COMMAND_BLOCK || m == Material.STRUCTURE_BLOCK
                || m == Material.JIGSAW || m == Material.STRUCTURE_VOID || m == Material.LIGHT) return false;
        // never remove a block that lava is resting on - lava only eats what it touches from the side
        if (b.getRelative(0, 1, 0).getType() == Material.LAVA) return false;
        // the volcano itself is protected so the engraved channels stay intact
        return Math.hypot(b.getX() - cx, b.getZ() - cz) >= FOOT;
    }

    static boolean isWood(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.WOODEN_STAIRS.isTagged(m)
                || Tag.WOODEN_SLABS.isTagged(m) || Tag.WOODEN_FENCES.isTagged(m) || Tag.WOODEN_DOORS.isTagged(m)
                || Tag.WOODEN_TRAPDOORS.isTagged(m) || Tag.WOODEN_BUTTONS.isTagged(m)
                || Tag.WOODEN_PRESSURE_PLATES.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.LEAVES.isTagged(m) || Tag.SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)
                || m == Material.CRAFTING_TABLE || m == Material.BOOKSHELF || m == Material.LADDER
                || m == Material.CHEST || m == Material.BARREL;
    }

    /** Deletes a block and everything stacked above it (gravity: nothing is left floating). */
    void destroyColumn(Block b) {
        int topLog = Integer.MIN_VALUE;
        if (Tag.LOGS.isTagged(b.getType())) topLog = b.getY();
        set(b, Material.AIR, false);
        for (int y = b.getY() + 1; y < b.getY() + 48 && y < world.getMaxHeight(); y++) {
            Block a = world.getBlockAt(b.getX(), y, b.getZ());
            if (a.getType().isAir() || a.getType() == Material.LAVA) break;
            if (Tag.LOGS.isTagged(a.getType())) topLog = y;
            set(a, Material.AIR, false);
        }
        if (topLog != Integer.MIN_VALUE) {      // a felled tree takes its canopy with it
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++)
                    for (int dy = -3; dy <= 3; dy++) {
                        Block l = world.getBlockAt(b.getX() + dx, topLog + dy, b.getZ() + dz);
                        if (Tag.LEAVES.isTagged(l.getType())) set(l, Material.AIR, false);
                    }
        }
    }

    // ------------------------------------------------------- ash (fumes)
    /** Leaves that spin and drift down around each player, orbiting a little, plus gray ash flecks. */
    private void ashParticles(int t) {
        double base = t * 0.11;
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (leaf != null) {
                for (int n = 0; n < 8; n++) {
                    double a = base + n * (Math.PI * 2 / 8) + rnd.nextDouble() * 0.4;
                    double r = 2.5 + rnd.nextDouble() * 7;
                    Location at = new Location(world, l.getX() + Math.cos(a) * r, l.getY() + 2.5 + rnd.nextDouble() * 5,
                            l.getZ() + Math.sin(a) * r);
                    p.spawnParticle(leaf, at, 1, 0, 0, 0, 0);
                }
            }
            if (ash != null) p.spawnParticle(ash, l.getX(), l.getY() + 3, l.getZ(), 25, 8, 4, 8, 0);
        }
    }

    // ---------------------------------------------------- mobs / players
    private boolean survival(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void killMobsInLava() {
        for (LivingEntity le : world.getLivingEntities()) {
            if (le instanceof Player || le instanceof ArmorStand) continue;
            if (le.getLocation().getBlock().getType() == Material.LAVA
                    || le.getEyeLocation().getBlock().getType() == Material.LAVA)
                le.setHealth(0);
        }
    }

    private void heat() {
        int r = cfg.getInt("settings.heat-radius", 5);
        int fire = cfg.getInt("settings.fire-seconds", 20) * 20;
        for (Player p : world.getPlayers()) {
            if (!survival(p)) continue;
            Location l = p.getLocation();
            int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
            boolean near = false;
            outer:
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++)
                        if (world.getBlockAt(bx + dx, by + dy, bz + dz).getType() == Material.LAVA) { near = true; break outer; }
            if (near && p.getFireTicks() < fire - 10) p.setFireTicks(fire);
        }
    }

    private void fumes() {
        double dmg = cfg.getDouble("settings.fumes.damage", 1.0);
        for (Player p : world.getPlayers()) if (survival(p)) trueDamage(p, dmg, "toxic");
    }

    private void trueDamage(Player p, double amount, String why) {
        cause.put(p.getUniqueId(), why);
        causeTime.put(p.getUniqueId(), System.currentTimeMillis());
        double hp = p.getHealth() - amount;
        if (hp <= 0) p.setHealth(0);
        else { p.setHealth(hp); p.playEffect(EntityEffect.HURT); }
    }

    // ------------------------------------------------------------ debris
    private void spawnDebris() {
        double radius = cfg.getDouble("settings.debris.radius", 150);
        int per = cfg.getInt("settings.debris.per-player-per-second", 3);
        for (Player p : world.getPlayers()) {
            if (!survival(p)) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - cx, pl.getZ() - cz) > radius) continue;
            for (int i = 0; i < per; i++) {
                double spread = i == 0 ? 2 : 14;
                double y = Math.min(world.getMaxHeight() - 2, pl.getY() + 30 + rnd.nextInt(16));
                Location at = new Location(world, pl.getX() + (rnd.nextDouble() * 2 - 1) * spread, y,
                        pl.getZ() + (rnd.nextDouble() * 2 - 1) * spread);
                FallingBlock fb = world.spawnFallingBlock(at, DEBRIS[rnd.nextInt(DEBRIS.length)].createBlockData());
                fb.setGlowing(true);
                if (debrisTeam != null) debrisTeam.addEntry(fb.getUniqueId().toString());
                fb.setDropItem(false);
                fb.setHurtEntities(false);
                fb.setVelocity(new Vector(0, -0.5, 0));
                debris.add(fb);
            }
        }
    }

    private void tickDebris() {
        if (activeElapsed % 20 == 0) ejecta.removeIf(f -> !f.isValid());
        if (debris.isEmpty()) return;
        Iterator<FallingBlock> it = debris.iterator();
        while (it.hasNext()) {
            FallingBlock fb = it.next();
            if (!fb.isValid()) { it.remove(); continue; }
            for (Player p : world.getPlayers()) {
                if (!survival(p)) continue;
                if (p.getBoundingBox().overlaps(fb.getBoundingBox().expand(0.25))) {
                    hitDebris(p, fb);
                    fb.remove();
                    it.remove();
                    break;
                }
            }
        }
    }

    /** Real damage (armor applies, hurt sound plays, totems pop) plus the anvil clang, like a falling anvil. */
    private void hitDebris(Player p, FallingBlock source) {
        cause.put(p.getUniqueId(), "debris");
        causeTime.put(p.getUniqueId(), System.currentTimeMillis());
        bypassArmor = p.getUniqueId();
        try { p.damage(cfg.getDouble("settings.debris.damage", 10.0), source); }
        finally { bypassArmor = null; }
        world.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.9f);
    }

    boolean bypassArmor(Entity e) { return bypassArmor != null && e.getUniqueId().equals(bypassArmor); }

    boolean isEjecta(Entity e) { return e instanceof FallingBlock && ejecta.contains(e); }

    /** A debris block hit the ground: it does anvil damage to anyone under it and throws the block it landed on. */
    void onDebrisLand(Block target, FallingBlock fb) {
        debris.remove(fb);
        fb.remove();
        Location c = target.getLocation().add(0.5, 0.5, 0.5);
        for (Player p : world.getPlayers())
            if (survival(p) && p.getLocation().distanceSquared(c) < 2.5) hitDebris(p, fb);
        world.playSound(c, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.8f);
        ejectBlock(target.getRelative(0, -1, 0));
    }

    /** A thrown block has come back down: remember what it overwrote so /volcano remove can undo it. */
    void onEjectaLand(Block target, FallingBlock fb) {
        capture(target);
        ejecta.remove(fb);
    }

    /** The block under a landing chunk of debris flies up and tumbles away, like sand blown out by an explosion. */
    private void ejectBlock(Block under) {
        if (!ejectable(under)) return;
        BlockData bd = under.getBlockData();
        Location c = under.getLocation().add(0.5, 0.5, 0.5);
        set(under, Material.AIR, false);
        FallingBlock f = world.spawnFallingBlock(c, bd);
        f.setDropItem(false);
        f.setVelocity(new Vector((rnd.nextDouble() - 0.5) * 0.7, 0.55 + rnd.nextDouble() * 0.6, (rnd.nextDouble() - 0.5) * 0.7));
        ejecta.add(f);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        if (puff != null) world.spawnParticle(puff, c.clone().add(0, 0.6, 0), 6, 0.3, 0.3, 0.3, 0.02);
        if (crack != null) world.spawnParticle(crack, c.clone().add(0, 0.6, 0), 20, 0.3, 0.3, 0.3, 0.1, bd);
    }

    private boolean ejectable(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid() || m == Material.LAVA || m == Material.WATER) return false;
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME
                || m == Material.COMMAND_BLOCK || m == Material.STRUCTURE_BLOCK || m == Material.JIGSAW) return false;
        return !(b.getState() instanceof TileState);       // chests, signs, spawners... would lose their data
    }

    private static Particle pick(String... names) {
        for (String n : names) {
            try { return Particle.valueOf(n); } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    // ---------------------------------------------------------- boss bars
    private void updateBars(String kind, int remainingTicks, int total) {
        BarColor color = kind.equals("fumes") ? BarColor.GREEN : kind.equals("flowing") ? BarColor.YELLOW
                : kind.equals("pre") ? BarColor.WHITE : BarColor.RED;
        String pattern = plugin.color(cfg.getString("bars." + kind, kind));
        String time = fmt(remainingTicks);
        double progress = Math.max(0, Math.min(1, remainingTicks / (double) total));
        Set<UUID> present = new HashSet<>();
        for (Player p : world.getPlayers()) {
            present.add(p.getUniqueId());
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = Bukkit.createBossBar("", color, BarStyle.SOLID);
                bar.addPlayer(p);
                bars.put(p.getUniqueId(), bar);
            }
            double dist = Math.hypot(p.getLocation().getX() - (cx + 0.5), p.getLocation().getZ() - (cz + 0.5));
            bar.setColor(color);
            bar.setProgress(progress);
            bar.setTitle(pattern.replace("%time%", time).replace("%distance%", String.valueOf(Math.round(dist))));
        }
        bars.entrySet().removeIf(en -> {
            if (present.contains(en.getKey())) return false;
            en.getValue().removeAll();
            return true;
        });
    }

    private void clearBars() {
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
    }

    // -------------------------------------------------------------- misc
    private static String fmt(int ticks) {
        int s = (int) Math.ceil(ticks / 20.0);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void broadcast(String s) { Bukkit.broadcastMessage(s); }
}
