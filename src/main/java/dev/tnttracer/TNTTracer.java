package dev.tnttracer;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TNTTracer extends JavaPlugin {

    // Per-player enable toggle
    private final Set<UUID> enabled = new HashSet<>();

    // Barrel gate location (single barrel)
    private Location barrelCenter = null;

    // Barrel direction (saved when you run /tnttracer setbarrel)
    private Vector barrelDir = null;

    // Gate size (adjust if your barrel mouth is wider)
    private final double gateX = 2.5;
    private final double gateY = 2.5;
    private final double gateZ = 2.5;

    // Direction filter tuning
    private final double minSpeed = 0.15;   // ignore barely-moving entities
    private final double minDot = 0.55;     // higher = stricter forward-only (0.35 looser, 0.75 very strict)
    private final boolean ignoreYForDirection = true; // usually best for cannons

    // Once an entity crosses the gate (and is moving out), we track it
    private final Set<UUID> tracked = new HashSet<>();
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    // Performance knobs
    private final int trackRadiusAroundBarrel = 10; // only scan near barrel gate
    private final double pointSpacing = 0.22;       // smaller = denser line (more load)
    private final int maxPointsPerEntity = 70;      // safety cap per entity per tick
    private final int maxTrackedPerTick = 180;      // safety cap total tracked drawn per tick run

    // 1.8.9-safe particles (ViaVersion friendly)
    private final Particle tntParticle = Particle.FLAME; // TNT
    private final Particle fallingParticle = Particle.CRIT; // sand/falling blocks

    @Override
    public void onEnable() {
        // Every 2 ticks for performance (still looks like a solid line)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (enabled.isEmpty()) return;
            if (barrelCenter == null) return;

            World w = barrelCenter.getWorld();
            if (w == null) return;

            // 1) Find new entities crossing the barrel gate (tiny scan near barrel)
            for (Entity e : w.getNearbyEntities(barrelCenter, trackRadiusAroundBarrel, trackRadiusAroundBarrel, trackRadiusAroundBarrel)) {
                if (!(e instanceof TNTPrimed) && !(e instanceof FallingBlock)) continue;

                if (insideGate(e.getLocation()) && movingOutOfBarrel(e)) {
                    UUID id = e.getUniqueId();
                    tracked.add(id);
                    lastSeen.put(id, System.currentTimeMillis());
                }
            }

            // 2) Draw lines for tracked entities (only shown to enabled players)
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

                boolean isTnt = e instanceof TNTPrimed;
                boolean isFall = e instanceof FallingBlock;

                // If it turned into a normal block / disappeared
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
                    if (!p.getWorld().equals(e.getWorld())) continue;

                    if (prev == null) {
                        // burst so you instantly see it
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

            // 4) Hard cap safety
            if (tracked.size() > 5000) {
                tracked.clear();
                lastPos.clear();
                lastSeen.clear();
            }

        }, 2L, 2L);
    }

    private boolean insideGate(Location loc) {
        if (barrelCenter == null) return false;
        if (loc.getWorld() == null || barrelCenter.getWorld() == null) return false;
        if (!loc.getWorld().equals(barrelCenter.getWorld())) return false;

        return Math.abs(loc.getX() - barrelCenter.getX()) <= gateX
                && Math.abs(loc.getY() - barrelCenter.getY()) <= gateY
                && Math.abs(loc.getZ() - barrelCenter.getZ()) <= gateZ;
    }

    private boolean movingOutOfBarrel(Entity e) {
        if (barrelDir == null) return true; // if not set, don't filter

        Vector v = e.getVelocity();
        if (ignoreYForDirection) v = v.clone().setY(0);

        double speed = v.length();
        if (speed < minSpeed) return false;

        Vector vn = v.clone().normalize();
        Vector dn = barrelDir.clone().normalize();

        // dot product: 1 = same direction, 0 = perpendicular, -1 = opposite
        double dot = vn.dot(dn);
        return dot >= minDot;
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
        barrelCenter = null;
        barrelDir = null;
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

            Vector dir = p.getLocation().getDirection().clone();
            if (ignoreYForDirection) dir.setY(0);
            if (dir.length() == 0) dir = new Vector(1, 0, 0);
            barrelDir = dir.normalize();

            tracked.clear();
            lastPos.clear();
            lastSeen.clear();

            p.sendMessage("§aBarrel gate set at your location.");
            p.sendMessage("§aBarrel direction saved. Face OUT of the barrel when setting.");
            return true;
        }

        if (sub.equals("clearbarrel")) {
            barrelCenter = null;
            barrelDir = null;
            tracked.clear();
            lastPos.clear();
            lastSeen.clear();
            p.sendMessage("§cBarrel gate cleared.");
            return true;
        }

        if (sub.equals("on") || sub.equals("enable")) {
            enabled.add(p.getUniqueId());
            p.sendMessage("§aTNT tracer ENABLED§7 (barrel + direction filtered).");
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
            p.sendMessage("§aTNT tracer ENABLED§7 (barrel + direction filtered).");
            if (barrelCenter == null) p.sendMessage("§eRun: /tnttracer setbarrel");
        }
        return true;
    }
}
