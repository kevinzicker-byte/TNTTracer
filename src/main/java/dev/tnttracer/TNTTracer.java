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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TNTTracer extends JavaPlugin {

    private final Set<UUID> enabled = new HashSet<>();

    // Performance + visibility knobs
    private final int radius = 120;
    private final int maxEntitiesPerTick = 250;

    // Dense "line" settings
    private final double pointSpacing = 0.16;   // denser line for 1.8 clients
    private final int maxPointsPerEntity = 140; // cap per entity per tick

    // 1.8.9-safe particles (ViaVersion friendly)
    private final Particle tntParticle = Particle.FLAME;
    private final Particle fallingParticle = Particle.REDSTONE; // very visible

    // Track last positions by entity UUID
    private final Map<UUID, Vector> lastPos = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (enabled.isEmpty()) return;

            for (UUID id : enabled) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) continue;

                World w = p.getWorld();
                int shownEntities = 0;

                for (Entity e : w.getNearbyEntities(p.getLocation(), radius, radius, radius)) {
                    if (shownEntities >= maxEntitiesPerTick) break;

                    boolean isTnt = e instanceof TNTPrimed;
                    boolean isFalling = e instanceof FallingBlock;
                    if (!isTnt && !isFalling) continue;

                    UUID eid = e.getUniqueId();
                    Vector cur = e.getLocation().toVector();

                    Vector prev = lastPos.get(eid);
                    lastPos.put(eid, cur);

                    Particle particle = isTnt ? tntParticle : fallingParticle;

                    // First time seeing it: burst at current location
                    if (prev == null) {
                        p.spawnParticle(particle, e.getLocation(), 12, 0.06, 0.06, 0.06, 0.0);
                        shownEntities++;
                        continue;
                    }

                    // Draw segment prev -> cur
                    drawSegment(p, prev, cur, particle);
                    shownEntities++;
                }

                // safety cleanup
                if (lastPos.size() > 6000) lastPos.clear();
            }
        }, 1L, 1L);
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
        lastPos.clear();
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

        String mode = (args.length == 0) ? "toggle" : args[0].toLowerCase();

        if (mode.equals("on") || mode.equals("enable")) {
            enabled.add(p.getUniqueId());
            p.sendMessage("§aTNT tracer ENABLED§7 (line mode).");
            return true;
        }
        if (mode.equals("off") || mode.equals("disable")) {
            enabled.remove(p.getUniqueId());
            p.sendMessage("§cTNT tracer DISABLED§7.");
            return true;
        }

        if (enabled.contains(p.getUniqueId())) {
            enabled.remove(p.getUniqueId());
            p.sendMessage("§cTNT tracer DISABLED§7.");
        } else {
            enabled.add(p.getUniqueId());
            p.sendMessage("§aTNT tracer ENABLED§7 (line mode).");
        }
        return true;
    }
}
