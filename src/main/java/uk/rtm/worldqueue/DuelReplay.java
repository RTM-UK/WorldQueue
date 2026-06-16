package uk.rtm.worldqueue;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DuelReplay {
    private final String playerA;
    private final String playerB;
    private final long startTime;
    private final String arenaName;
    private final Map<UUID, List<MovementEvent>> movements = new HashMap<>();
    private final List<HitEvent> hits = new ArrayList<>();
    private final Map<UUID, Long> deathTimes = new HashMap<>();
    private final Map<Integer, UUID> indexToPlayer = new HashMap<>();
    private final Map<UUID, Integer> playerIndex = new HashMap<>();
    private final Map<Integer, Location> startLocations = new HashMap<>();
    private final ItemStack[] mainHands = new ItemStack[2];
    private final ItemStack[] helmets = new ItemStack[2];
    private final ItemStack[] chestplates = new ItemStack[2];
    private final ItemStack[] leggings = new ItemStack[2];
    private final ItemStack[] boots = new ItemStack[2];

    public DuelReplay(String playerA, String playerB, long startTime, Location startA, Location startB, String arenaName, UUID uuidA, UUID uuidB,
                      ItemStack mainHandA, ItemStack mainHandB,
                      ItemStack helmetA, ItemStack helmetB,
                      ItemStack chestplateA, ItemStack chestplateB,
                      ItemStack leggingsA, ItemStack leggingsB,
                      ItemStack bootsA, ItemStack bootsB) {
        this.playerA = playerA;
        this.playerB = playerB;
        this.startTime = startTime;
        this.arenaName = arenaName;
        this.indexToPlayer.put(0, uuidA);
        this.indexToPlayer.put(1, uuidB);
        this.playerIndex.put(uuidA, 0);
        this.playerIndex.put(uuidB, 1);
        this.startLocations.put(0, startA.clone());
        this.startLocations.put(1, startB.clone());
        this.mainHands[0] = mainHandA == null ? null : mainHandA.clone();
        this.mainHands[1] = mainHandB == null ? null : mainHandB.clone();
        this.helmets[0] = helmetA == null ? null : helmetA.clone();
        this.helmets[1] = helmetB == null ? null : helmetB.clone();
        this.chestplates[0] = chestplateA == null ? null : chestplateA.clone();
        this.chestplates[1] = chestplateB == null ? null : chestplateB.clone();
        this.leggings[0] = leggingsA == null ? null : leggingsA.clone();
        this.leggings[1] = leggingsB == null ? null : leggingsB.clone();
        this.boots[0] = bootsA == null ? null : bootsA.clone();
        this.boots[1] = bootsB == null ? null : bootsB.clone();
        recordMovement(uuidA, startA.clone(), 0);
        recordMovement(uuidB, startB.clone(), 0);
    }

    public void recordMovement(UUID player, Location location, long timeOffset) {
        movements.computeIfAbsent(player, k -> new ArrayList<>())
                .add(new MovementEvent(timeOffset, location.clone()));
    }

    public void recordHit(UUID attacker, UUID target, double damage, long timeOffset, String weaponName) {
        hits.add(new HitEvent(timeOffset, attacker, target, damage, weaponName));
    }

    public void recordDeath(UUID player, long timeOffset) {
        deathTimes.put(player, timeOffset);
    }

    public long getStartTime() {
        return startTime;
    }

    public String getArenaName() {
        return arenaName;
    }

    public String getPlayerName(int index) {
        return index == 0 ? playerA : playerB;
    }

    public Location getStartLocation(int index) {
        return startLocations.getOrDefault(index, null);
    }

    public String getReplayKey() {
        return playerA.toLowerCase() + ":" + playerB.toLowerCase() + ":" + startTime;
    }

    public boolean matchesNames(String name1, String name2) {
        String a = name1.toLowerCase();
        String b = name2.toLowerCase();
        return (a.equals(playerA.toLowerCase()) && b.equals(playerB.toLowerCase()))
                || (a.equals(playerB.toLowerCase()) && b.equals(playerA.toLowerCase()));
    }

    public void applyFrame(ArmorStand[] fighters, long time) {
        for (int i = 0; i < fighters.length; i++) {
            if (fighters[i] == null) continue;
            UUID player = indexToPlayer.get(i);
            Location loc = getLocationAtTime(player, time);
            if (loc != null) {
                fighters[i].teleport(loc);
            }
        }
        for (HitEvent hit : hits) {
            if (hit.getTimeOffset() >= time - 40 && hit.getTimeOffset() <= time) {
                spawnHitEffect(fighters, hit);
            }
        }
    }

    private Location getLocationAtTime(UUID player, long time) {
        List<MovementEvent> history = movements.get(player);
        if (history == null || history.isEmpty()) return null;
        MovementEvent previous = null;
        MovementEvent next = null;
        for (MovementEvent event : history) {
            if (event.timeOffset <= time) {
                previous = event;
            } else {
                next = event;
                break;
            }
        }
        if (previous == null) {
            return next != null ? next.location.clone() : history.get(0).location.clone();
        }
        if (next == null) {
            return previous.location.clone();
        }
        if (next.timeOffset == previous.timeOffset) {
            return previous.location.clone();
        }
        double progress = (double) (time - previous.timeOffset) / (double) (next.timeOffset - previous.timeOffset);
        return interpolateLocation(previous.location, next.location, progress);
    }

    private Location interpolateLocation(Location from, Location to, double t) {
        Location result = from.clone();
        result.setX(from.getX() + (to.getX() - from.getX()) * t);
        result.setY(from.getY() + (to.getY() - from.getY()) * t);
        result.setZ(from.getZ() + (to.getZ() - from.getZ()) * t);
        result.setPitch((float) (from.getPitch() + (to.getPitch() - from.getPitch()) * t));
        double yawFrom = from.getYaw();
        double yawTo = to.getYaw();
        double deltaYaw = ((yawTo - yawFrom + 540) % 360) - 180;
        result.setYaw((float) (yawFrom + deltaYaw * t));
        return result;
    }

    private void spawnHitEffect(ArmorStand[] fighters, HitEvent hit) {
        int attackerIndex = playerIndex.getOrDefault(hit.attacker, -1);
        int targetIndex = playerIndex.getOrDefault(hit.target, -1);
        if (attackerIndex < 0 || targetIndex < 0) return;
        ArmorStand source = fighters[attackerIndex];
        ArmorStand target = fighters[targetIndex];
        if (source == null || target == null) return;
        source.getWorld().spawnParticle(org.bukkit.Particle.DAMAGE_INDICATOR, target.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0.05);
    }

    public boolean isComplete(long time) {
        long maxDeath = deathTimes.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        return time > maxDeath + 2000L;
    }

    public ItemStack getMainHand(int index) {
        return index >= 0 && index < mainHands.length ? mainHands[index] : null;
    }

    public ItemStack getHelmet(int index) {
        return index >= 0 && index < helmets.length ? helmets[index] : null;
    }

    public ItemStack getChestplate(int index) {
        return index >= 0 && index < chestplates.length ? chestplates[index] : null;
    }

    public ItemStack getLeggings(int index) {
        return index >= 0 && index < leggings.length ? leggings[index] : null;
    }

    public ItemStack getBoots(int index) {
        return index >= 0 && index < boots.length ? boots[index] : null;
    }

    public List<String> buildDamageLog() {
        List<String> lines = new ArrayList<>();
        hits.sort(Comparator.comparingLong(HitEvent::getTimeOffset));
        for (HitEvent hit : hits) {
            String attacker = getPlayerName(playerIndex.getOrDefault(hit.attacker, 0));
            String target = getPlayerName(playerIndex.getOrDefault(hit.target, 1));
            double seconds = hit.timeOffset / 1000.0;
            double hearts = hit.damage / 2.0;
            String weapon = hit.weaponName != null && !hit.weaponName.isEmpty() ? hit.weaponName : "fists";
            lines.add(String.format("[%.2fs] %s did %.1f hearts to %s with %s", seconds, attacker, hearts, target, weapon));
        }
        List<Map.Entry<UUID, Long>> deaths = new ArrayList<>(deathTimes.entrySet());
        deaths.sort(Map.Entry.comparingByValue());
        for (Map.Entry<UUID, Long> death : deaths) {
            String victim = getPlayerName(playerIndex.getOrDefault(death.getKey(), 0));
            double seconds = death.getValue() / 1000.0;
            lines.add(String.format("[%.2fs] %s died", seconds, victim));
        }
        if (lines.isEmpty()) {
            lines.add("No damage events recorded.");
        }
        return lines;
    }

    private static class MovementEvent {
        private final long timeOffset;
        private final Location location;

        private MovementEvent(long timeOffset, Location location) {
            this.timeOffset = timeOffset;
            this.location = location;
        }
    }

    private static class HitEvent {
        private final long timeOffset;
        private final UUID attacker;
        private final UUID target;
        private final double damage;
        private final String weaponName;

        private HitEvent(long timeOffset, UUID attacker, UUID target, double damage, String weaponName) {
            this.timeOffset = timeOffset;
            this.attacker = attacker;
            this.target = target;
            this.damage = damage;
            this.weaponName = weaponName;
        }

        public long getTimeOffset() {
            return timeOffset;
        }
    }
}
