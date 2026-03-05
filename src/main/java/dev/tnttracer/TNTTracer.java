package dev.tnttracer;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class TNTTracer extends JavaPlugin {

    // Per-player enable toggle
    private final Set<UUID> enabled = new HashSet<>();

    // Barrel gate location (single barrel for now)
    private Location barrelCenter = null;

    // Gate size (adjust to your barrel mouth)
    private final double gateX = 2.5;
    private final double gateY = 2.5;
    private final double gateZ = 2.5;

    // Once an entity crosses the gate, we track it
    private final Set<UUID> tracked = new HashSet<>();
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    // Performance knobs
    private final int trackRadiusAroundBarrel = 10; // only scan near the barrel gate
    private final double pointSpacing = 0.22;
    private final int maxPointsPerEntity = 70;
    private final int maxTrackedPerTick = 180;

    // 1.8-safe particles
    private final Particle tntParticle = Particle.FLAME;
    private final Particle fallingParticle = Particle.REDSTONE;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (enabled.isEmpty()) return;
            if (barrelCenter == null) return;

            // 1) Find new entities crossing the barrel gate (tiny scan near barrel)
            World w = barrelCenter.getWorld();
            if (w == null) return;

            for (Entity e : w.getNearbyEntities(barrelCenter, trackRadiusAroundBarrel, trackRadiusAroundBarrel, trackRadiusAroundBarrel)) {
                if (!(e instanceof TNTPrimed) && !(e instanceof FallingBlock)) continue;

                // If it's inside the gate box, mark it tracked
                if (insideGate(e.getLocation())) {
                    UUID id = e.getUniqueId();
                    tracked.add(id);
                    lastSeen.put(id, System.currentTimeMillis());
                }
            }

            // 2) Draw lines for tracked entities, but only for enabled players
            // Also cleanup dead/old ones
            Iterator<UUID> it = tracked.iterator();
            int drawn = 0;
            while (it.hasNext()) {
                UUID eid = it.next();

                Entity e = Bukkit.getEntity(eid);
                if (e == null || e.isDead()) {
                    it.remove();
                    lastPos.remove(eid);
                    lastSeen.remove(eid);
                    continue;
                }

                // Stop tracking falling blocks once they land (they cease to be FallingBlock)
                boolean isTnt = e instanceof TNTPrimed;
                boolean isFall = e instanceof FallingBlock;
                if (!isTnt && !isFall) {
                    it.remove();
                    lastPos.remove(eid);
                    lastSeen.remove(eid);
                    continue;
                }

                long now = System.currentTimeMillis();
                lastSeen.put(eid, now);

                Vector cur = e.getLocation().toVector();
                Vector prev = lastPos.get(eid);
                lastPos.put(eid, cur);

                Particle particle = isTnt ? tntParticle : fallingParticle;

                for (UUID pid : enabled) {
                    Player p = Bukkit.getPlayer(pid);
                    if (p == null || !p.isOnline()) continue;
                    // Only show if player is in same world
                    if (!p.getWorld().equals(e.getWorld())) continue;

                    if (prev == null) {
                        p.spawnParticle(particle, e.getLocation(), 10, 0.06, 0.06, 0.06, 0.0);
                    } else {
                        drawSegment(p, prev, cur, particle);
                    }
                }

                drawn++;
                if (drawn >= maxTrackedPerTick) break;
            }

            // 3) Cleanup: remove entities not seen for 3 seconds (safety)
            long cutoff = System.currentTimeMillis() - 3000;
            lastSeen.entrySet().removeIf(en -> en.getValue() < cutoff && tracked.remove(en.getKey()));

        }, 2L, 2L); // every 2 ticks = much lighter
    }

    private boolean insideGate(Location loc) {
        if (barrelCenter == null) return false;
        if (loc.getWorld() == null || barrelCenter.getWorld() == null) return false;
        if (!loc.getWorld().equals(barrelCenter.getWorld())) return false;

        return Math.abs(loc.getX() - barrelCenter.getX()) <= gateX
                && Math.abs(loc.getY() - barrelCenter.getY()) <= gateY
                && Math.abs(loc.getZ() - barrelCenter.getZ()) <= gateZ;
    }

    private void drawSegment(Player p, Vector from, Vector to, Particle particle) {
        Vector delta = to.clone().subtract(from);
        double dist = delta.length();
        if (dist <= 0.0001) {
            p.spawnParticle(particle, to.toLocation(p.getWorld()), 1, 0, 0, 0, 0);
            return;
        }

        Vector step = delta.clone().normalize().multiply(pointSpacing);
        int points = (int) Math.ceil(dist / pointSpacing);
        if (points > maxPointsPerEntity) points = maxPointsPerEntity;

        Vector pos = from.clone();
        for (int i = 0; i < points; i++) {
            pos.add(step);
            p.spawnParticle(particle, pos.toLocation(p.getWorld()), 1, 0, 0, 0, 0);
        }
    }

    @Override
    public void onDisable() {
        enabled.clear();
        tracked.clear();
        lastPos.clear();
        lastSeen.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!p.hasPermission("cannon.tracer")) {
            p.sendMessage("§cNo permission.");
            return true;
        }

        String sub = (args.length == 0) ? "toggle" : args[0].toLowerCase();

        if (sub.equals("setbarrel")) {
            barrelCenter = p.getLocation().clone();
            tracked.clear();
            lastPos.clear();
            lastSeen.clear();
            p.sendMessage("§aBarrel gate set at your location.");
            p.sendMessage("§7Tip: stand at the barrel mouth / where TNT exits.");
            return true;
        }

        if (sub.equals("clearbarrel")) {
            barrelCenter = null;
            tracked.clear();
            lastPos.clear();
            lastSeen.clear();
            p.sendMessage("§cBarrel gate cleared.");
            return true;
        }

        if (sub.equals("on") || sub.equals("enable")) {
            enabled.add(p.getUniqueId());
            p.sendMessage("§aTNT tracer ENABLED§7 (barrel-filtered).");
            if (barrelCenter == null) p.sendMessage("§eRun: /tnttracer setbarrel");
            return true;
        }

        if (sub.equals("off") || sub.equals("disable")) {
            enabled.remove(p.getUniqueId());
            p.sendMessage("§cTNT tracer DISABLED§7.");
            return true;
        }

        // toggle
        if (enabled.contains(p.getUniqueId())) {
            enabled.remove(p.getUniqueId());
            p.sendMessage("§cTNT tracer DISABLED§7.");
        } else {
            enabled.add(p.getUniqueId());
            p.sendMessage("§aTNT tracer ENABLED§7 (barrel-filtered).");
            if (barrelCenter == null) p.sendMessage("§eRun: /tnttracer setbarrel");
        }
        return true;
    }
}
