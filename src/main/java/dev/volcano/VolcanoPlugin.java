package dev.volcano;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VolcanoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    public interface Job { boolean run(long deadlineNanos); }

    Schem schem;
    World world;
    int ox, oy, oz;
    boolean pasted = false;   // a volcano (or part of one) is in the world and a restore snapshot exists
    boolean ready = false;    // the paste finished, /volcano start is allowed
    VolcanoEvent event;
    BlockStore snapshot;      // original blocks under the volcano, taken on /volcano paste
    BlockStore pendingChanges; // event changes saved by a restart, restored on /volcano remove|stop
    final Deque<Job> jobs = new ArrayDeque<>();
    private File dataFile, snapshotFile, changesFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        snapshotFile = new File(getDataFolder(), "snapshot.dat");
        changesFile = new File(getDataFolder(), "changes.dat");
        loadSchem();
        loadData();
        try { if (changesFile.exists()) pendingChanges = BlockStore.load(changesFile); }
        catch (Exception e) { getLogger().warning("Could not read changes.dat: " + e); }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("volcano").setExecutor(this);
        getCommand("volcano").setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::tickJobs, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (event != null) {
            try { event.persist(changesFile); } catch (IOException e) { getLogger().warning("save failed: " + e); }
            event.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- setup
    private void loadSchem() {
        try {
            File custom = new File(getDataFolder(), "vesuvius.schem");
            InputStream in = custom.exists() ? new java.io.FileInputStream(custom) : getResource("vesuvius.schem");
            schem = Schem.load(in);
            getLogger().info("Loaded volcano schematic " + schem.width + "x" + schem.height + "x" + schem.length);
        } catch (Exception e) {
            getLogger().severe("Could not load schematic: " + e);
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) return;
        world = w; ox = y.getInt("x"); oy = y.getInt("y"); oz = y.getInt("z");
        pasted = y.getBoolean("pasted");
        ready = y.getBoolean("ready");
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        if (world != null) y.set("world", world.getName());
        y.set("x", ox); y.set("y", oy); y.set("z", oz);
        y.set("pasted", pasted); y.set("ready", ready);
        try { y.save(dataFile); } catch (IOException e) { getLogger().warning("save failed: " + e); }
    }

    String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    String msg(String path) { return color(getConfig().getString(path, path)); }

    // ------------------------------------------------------------ job queue
    private void tickJobs() {
        if (jobs.isEmpty()) return;
        long deadline = System.nanoTime() + getConfig().getLong("settings.paste-budget-ms", 40) * 1_000_000L;
        while (!jobs.isEmpty() && System.nanoTime() < deadline) {
            if (jobs.peek().run(deadline)) jobs.poll();
        }
    }

    private int worldX(int i) { return ox + (i % schem.width) + schem.offX; }
    private int worldZ(int i) { return oz + ((i / schem.width) % schem.length) + schem.offZ; }
    private int worldY(int i) { return oy + (i / (schem.width * schem.length)) + schem.offY; }

    /** Step 1 of /volcano paste: remember what the land looked like under every block the volcano will replace. */
    private final class SnapshotJob implements Job {
        final long[] keys; final int[] idx; final Map<BlockData, Integer> pal = new HashMap<>();
        final List<BlockData> list = new ArrayList<>();
        final Runnable done; int i = 0, n = 0;
        SnapshotJob(Runnable done) {
            this.done = done;
            int c = 0;
            for (int b : schem.blocks) if (b != schem.airIndex) c++;
            keys = new long[c]; idx = new int[c];
        }
        public boolean run(long deadline) {
            int total = schem.blocks.length, ticks = 0;
            while (i < total) {
                if (schem.blocks[i] != schem.airIndex) {
                    int x = worldX(i), y = worldY(i), z = worldZ(i);
                    BlockData bd = world.getBlockAt(x, y, z).getBlockData();
                    Integer p = pal.get(bd);
                    if (p == null) { p = list.size(); pal.put(bd, p); list.add(bd); }
                    keys[n] = VolcanoEvent.pack(x, y, z);
                    idx[n++] = p;
                }
                i++;
                if ((++ticks & 255) == 0 && System.nanoTime() > deadline) return false;
            }
            snapshot = new BlockStore(keys, idx, list.toArray(new BlockData[0]));
            final BlockStore s = snapshot;
            Bukkit.getScheduler().runTaskAsynchronously(VolcanoPlugin.this, () -> {
                try { s.save(snapshotFile); } catch (IOException e) { getLogger().warning("snapshot save failed: " + e); }
            });
            done.run();
            return true;
        }
    }

    /** Step 2 of /volcano paste: place the volcano's blocks in time-sliced batches (FAWE-style). */
    private final class PasteJob implements Job {
        final Runnable done; int i = 0;
        PasteJob(Runnable done) { this.done = done; }
        public boolean run(long deadline) {
            int total = schem.blocks.length, n = 0;
            while (i < total) {
                int idx = schem.blocks[i];
                if (idx != schem.airIndex)
                    world.getBlockAt(worldX(i), worldY(i), worldZ(i)).setBlockData(schem.palette[idx], false);
                i++;
                if ((++n & 255) == 0 && System.nanoTime() > deadline) return false;
            }
            done.run();
            return true;
        }
    }

    /** Fallback if the snapshot is missing: just clear the volcano's blocks. */
    private final class ClearSchemJob implements Job {
        final Runnable done; int i = 0;
        ClearSchemJob(Runnable done) { this.done = done; }
        public boolean run(long deadline) {
            int total = schem.blocks.length, n = 0;
            while (i < total) {
                if (schem.blocks[i] != schem.airIndex) world.getBlockAt(worldX(i), worldY(i), worldZ(i)).setType(Material.AIR, false);
                i++;
                if ((++n & 255) == 0 && System.nanoTime() > deadline) return false;
            }
            done.run();
            return true;
        }
    }

    private void restorePendingChanges() {
        if (pendingChanges != null && world != null) {
            jobs.add(new BlockStore.RestoreJob(world, pendingChanges, null));
        }
        pendingChanges = null;
        if (changesFile.exists()) changesFile.delete();
    }

    // ------------------------------------------------------------- commands
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { s.sendMessage(ChatColor.YELLOW + "/volcano <paste|remove|start <time>|stop|reload>"); return true; }
        switch (a[0].toLowerCase()) {
            case "paste": {
                if (!(s instanceof Player)) { s.sendMessage("Players only."); return true; }
                if (schem == null) { s.sendMessage(ChatColor.RED + "Schematic failed to load, see console."); return true; }
                if (!jobs.isEmpty()) { s.sendMessage(ChatColor.RED + "Another paste/remove is still running."); return true; }
                if (pasted) { s.sendMessage(ChatColor.RED + "A volcano is already pasted. Run /volcano remove first (it restores the original land)."); return true; }
                Player p = (Player) s;
                Location loc = p.getLocation();
                if (loc.getBlockY() + schem.height > loc.getWorld().getMaxHeight()) {
                    s.sendMessage(ChatColor.RED + "Not enough build height here (need " + schem.height + " blocks above you).");
                    return true;
                }
                if (event != null) event.cleanupAll();
                world = loc.getWorld(); ox = loc.getBlockX(); oy = loc.getBlockY(); oz = loc.getBlockZ();
                pasted = false; ready = false; saveData();
                s.sendMessage(msg("messages.pasting"));
                jobs.add(new SnapshotJob(() -> {
                    pasted = true; saveData();      // from here on /volcano remove can restore the land
                    jobs.add(new PasteJob(() -> { ready = true; saveData(); s.sendMessage(msg("messages.pasted")); }));
                }));
                return true;
            }
            case "remove": {
                if (!pasted || world == null) { s.sendMessage(ChatColor.RED + "No volcano is pasted."); return true; }
                if (!jobs.isEmpty()) { s.sendMessage(ChatColor.RED + "Another paste/remove is still running."); return true; }
                if (event != null) event.cleanupAll();   // puts back lava, magma, glass, destroyed wood
                restorePendingChanges();
                BlockStore snap = snapshot;
                if (snap == null && snapshotFile.exists()) {
                    try { snap = BlockStore.load(snapshotFile); } catch (Exception e) { getLogger().warning("snapshot load failed: " + e); }
                }
                Runnable done = () -> {
                    pasted = false; ready = false; snapshot = null;
                    if (snapshotFile.exists()) snapshotFile.delete();
                    saveData();
                    s.sendMessage(msg("messages.removed"));
                };
                if (snap != null) jobs.add(new BlockStore.RestoreJob(world, snap, done));
                else {
                    s.sendMessage(ChatColor.YELLOW + "No restore snapshot found - clearing the volcano blocks instead.");
                    jobs.add(new ClearSchemJob(done));
                }
                s.sendMessage(ChatColor.YELLOW + "Restoring the original land...");
                return true;
            }
            case "start": {
                if (!ready) { s.sendMessage(ChatColor.RED + "Paste the volcano first (/volcano paste) and wait for it to finish."); return true; }
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /volcano start <time>  (e.g. 30, 45s, 2m, 1m30s)"); return true; }
                int secs = parseSeconds(a[1]);
                if (secs < 1) { s.sendMessage(ChatColor.RED + "Invalid time. Examples: 30, 45s, 2m, 1m30s"); return true; }
                if (event != null) event.cleanupAll();
                restorePendingChanges();
                if (!jobs.isEmpty()) { s.sendMessage(ChatColor.YELLOW + "Cleaning up the previous eruption - run the command again in a few seconds."); return true; }
                event = new VolcanoEvent(this, secs);
                event.begin();
                s.sendMessage(ChatColor.GREEN + "Volcano countdown started (" + secs + "s).");
                return true;
            }
            case "stop": {
                if (event == null && pendingChanges == null) { s.sendMessage(ChatColor.RED + "Nothing is running."); return true; }
                if (event != null) event.cleanupAll();
                restorePendingChanges();
                s.sendMessage(ChatColor.GREEN + "Event stopped. Lava, plume and other changes are being put back.");
                return true;
            }
            case "reload": {
                reloadConfig(); loadSchem();
                s.sendMessage(ChatColor.GREEN + "Reloaded.");
                return true;
            }
            default:
                s.sendMessage(ChatColor.YELLOW + "/volcano <paste|remove|start <time>|stop|reload>");
                return true;
        }
    }

    static int parseSeconds(String s) {
        s = s.toLowerCase();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        Matcher m = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matcher(s);
        if (s.isEmpty() || !m.matches()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int se = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return h * 3600 + mi * 60 + se;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return Arrays.asList("paste", "remove", "start", "stop", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("start")) return Arrays.asList("30s", "1m", "2m", "5m");
        return Collections.emptyList();
    }

    // ------------------------------------------------------------ listeners
    private boolean tracking(World w) { return event != null && event.tracking(w); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!tracking(p.getWorld())) return;
        String cause = event.consumeCause(p);
        if (cause == null) {
            EntityDamageEvent d = p.getLastDamageCause();
            if (d != null) {
                switch (d.getCause()) {
                    case LAVA: case FIRE: case FIRE_TICK: case HOT_FLOOR: cause = "burned"; break;
                    default: break;
                }
            }
        }
        if (cause != null)
            e.setDeathMessage(msg("messages.death." + cause).replace("%player%", p.getName()));
    }

    /** Debris hits ignore armor (the hurt sound, totem pops and everything else stay vanilla). */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (event == null || !event.bypassArmor(e.getEntity())) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.ARMOR))
            e.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallingLand(EntityChangeBlockEvent e) {
        if (event == null || !(e.getEntity() instanceof FallingBlock)) return;
        FallingBlock fb = (FallingBlock) e.getEntity();
        if (event.isDebris(fb)) {              // debris never becomes a block; it damages players and throws the block below
            e.setCancelled(true);
            event.onDebrisLand(e.getBlock(), fb);
        } else if (event.isEjecta(fb)) {       // thrown blocks do land (the dislocated look); the change is logged
            event.onEjectaLand(e.getBlock(), fb);
        }
    }

    /**
     * Lava uses normal vanilla physics but never goes past the stop line (those flows are cancelled). Each allowed
     * lava flow is handed to the event, which turns it into source lava and deletes whatever it touches. Every block a
     * flow overwrites is recorded for /volcano remove.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!tracking(e.getBlock().getWorld())) return;
        if (e.getBlock().getType() == Material.LAVA) {
            if (!event.flowAllowed(e.getToBlock())) { e.setCancelled(true); return; }
            event.capture(e.getToBlock());
            event.onLavaFlow(e.getBlock(), e.getToBlock());
            return;
        }
        event.capture(e.getToBlock());
    }

    /** Lava meeting water makes magma blocks instead of cobblestone/stone/obsidian. */
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!tracking(e.getBlock().getWorld())) return;
        Material m = e.getNewState().getType();
        if (m == Material.COBBLESTONE || m == Material.STONE || m == Material.OBSIDIAN || m == Material.BASALT) {
            event.capture(e.getBlock());
            e.getNewState().setType(Material.MAGMA_BLOCK);
        }
    }

    /** Only wood is destroyed by lava - no stray fires. */
    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getCause() == BlockIgniteEvent.IgniteCause.LAVA && tracking(e.getBlock().getWorld()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (event != null) event.dropBar(e.getPlayer());
    }
}
