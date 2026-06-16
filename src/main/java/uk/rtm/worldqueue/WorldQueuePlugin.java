package uk.rtm.worldqueue;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.GameMode;
import org.bukkit.WorldCreator;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WorldQueuePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Kit> kitMap = new LinkedHashMap<>();
    private final Map<UUID, DuelRequest> pendingRequests = new HashMap<>();
    private final Map<UUID, DuelSession> activeDuels = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> savedStates = new HashMap<>();
    private final Set<UUID> awaitingRespawn = new HashSet<>();
    private File kitFile;
    private FileConfiguration kitConfig;
    private static final String QUEUE_COMMAND = "queue";
    private static final String KITS_COMMAND = "kits";
    private static final String FIGHTS_COMMAND = "fights";
    private static final String SPECTATE_COMMAND = "spectate";
    private static final String REPLAY_COMMAND = "replay";
    private static final String GUI_TITLE = ChatColor.DARK_PURPLE + "WorldQueue Kits";
    private static final String SPECTATE_GUI_TITLE = ChatColor.AQUA + "WorldQueue Spectate";
    private static final String CONFIRM_REMOVE_TITLE = ChatColor.RED + "Confirm Kit Remove";
    private static final Material FILLER_MATERIAL = Material.PURPLE_STAINED_GLASS_PANE;
    private static final Material CONFIRM_MATERIAL = Material.GREEN_WOOL;
    private static final Material CANCEL_MATERIAL = Material.RED_WOOL;
    private final Map<UUID, String> pendingKitRemovals = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> spectatorSavedStates = new HashMap<>();
    private final Map<UUID, List<DuelSession>> spectateSelections = new HashMap<>();
    private final Map<String, DuelReplay> duelReplays = new HashMap<>();
    private final Map<UUID, Long> lastMoveRecord = new HashMap<>();

    @Override
    public void onEnable() {
        registerConfig();
        loadKits();
        registerMessages();
        getLogger().info("WorldQueue enabled");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand(QUEUE_COMMAND).setExecutor(this);
        getCommand(QUEUE_COMMAND).setTabCompleter(this);
        getCommand(KITS_COMMAND).setExecutor(this);
        getCommand(KITS_COMMAND).setTabCompleter(this);
        if (getCommand(FIGHTS_COMMAND) != null) {
            getCommand(FIGHTS_COMMAND).setExecutor(this);
            getCommand(FIGHTS_COMMAND).setTabCompleter(this);
        }
        if (getCommand(SPECTATE_COMMAND) != null) {
            getCommand(SPECTATE_COMMAND).setExecutor(this);
            getCommand(SPECTATE_COMMAND).setTabCompleter(this);
        }
        if (getCommand(REPLAY_COMMAND) != null) {
            getCommand(REPLAY_COMMAND).setExecutor(this);
            getCommand(REPLAY_COMMAND).setTabCompleter(this);
        }
    }

    private File messagesFile;
    private FileConfiguration messagesConfig;

    private void registerMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            getDataFolder().mkdirs();
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        if (getResource("messages.yml") != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(getResource("messages.yml"), StandardCharsets.UTF_8)
            );
            messagesConfig.setDefaults(defaults);
        }
    }

    private String t(String key) {
        return t(key, Collections.emptyMap());
    }

    private String t(String key, Map<String, String> vars) {
        if (messagesConfig == null) return formatMessage(key);
        String template = messagesConfig.getString("messages." + key);
        if (template == null || template.equals(key)) {
            template = messagesConfig.getDefaults() != null
                    ? messagesConfig.getDefaults().getString("messages." + key, key)
                    : key;
        }
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                template = template.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        String prefix = messagesConfig.getString("prefix", "");
        return ChatColor.translateAlternateColorCodes('&', prefix + template);
    }

    private String tNoPrefix(String key) {
        return tNoPrefix(key, Collections.emptyMap());
    }

    private String tNoPrefix(String key, Map<String, String> vars) {
        if (messagesConfig == null) return formatMessage(key);
        String template = messagesConfig.getString("messages." + key);
        if (template == null || template.equals(key)) {
            template = messagesConfig.getDefaults() != null
                    ? messagesConfig.getDefaults().getString("messages." + key, key)
                    : key;
        }
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                template = template.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return ChatColor.translateAlternateColorCodes('&', template);
    }

    @Override
    public void onDisable() {
        saveKits();
    }

    private void registerConfig() {
        kitFile = new File(getDataFolder(), "kits.yml");
        if (!kitFile.exists()) {
            getDataFolder().mkdirs();
            saveResource("kits.yml", false);
        }
        kitConfig = YamlConfiguration.loadConfiguration(kitFile);
    }

    private void loadKits() {
        kitMap.clear();
        if (kitConfig.contains("kits")) {
            for (String key : kitConfig.getConfigurationSection("kits").getKeys(false)) {
                String path = "kits." + key;
                Kit kit = Kit.fromConfig(key, kitConfig, path);
                kitMap.put(kit.getName().toLowerCase(Locale.ROOT), kit);
            }
        }
    }

    private void saveKits() {
        kitConfig.set("kits", null);
        for (Kit kit : kitMap.values()) {
            kit.save(kitConfig, "kits." + kit.getName().toLowerCase(Locale.ROOT));
        }
        try {
            kitConfig.save(kitFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save kits.yml", e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingRequests.remove(uuid);
        activeDuels.remove(uuid);
        spectatorSavedStates.remove(uuid);
        spectateSelections.remove(uuid);
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getView().getTitle() == null) return;
        if (event.getView().getTitle().equals(SPECTATE_GUI_TITLE)) {
            spectateSelections.remove(event.getPlayer().getUniqueId());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase(QUEUE_COMMAND)) {
            handleQueueCommand(player, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase(KITS_COMMAND)) {
            handleKitsCommand(player, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase(FIGHTS_COMMAND)) {
            handleFightsCommand(player, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase(SPECTATE_COMMAND)) {
            handleSpectateCommand(player, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase(REPLAY_COMMAND)) {
            handleReplayCommand(player, args);
            return true;
        }
        return false;
    }

    private void handleQueueCommand(Player player, String[] args) {
        if (args.length == 0) {
            openQueueGui(player);
            return;
        }
        if (args.length == 1) {
            queueForKit(player, args[0]);
            return;
        }
        player.sendMessage(t("usage.queue"));
    }

    private void queueForKit(Player player, String kitNameRaw) {
        String kitName = kitNameRaw.toLowerCase(Locale.ROOT);
        Kit kit = kitMap.get(kitName);
        if (kit == null) {
            player.sendMessage(t("kit.notfound", Collections.singletonMap("kit", kitNameRaw)));
            return;
        }
        DuelRequest request = pendingRequests.get(player.getUniqueId());
        if (request != null) {
            player.sendMessage(t("already.queued"));
            return;
        }
        DuelRequest existing = pendingRequests.values().stream()
                .filter(r -> r.getKit().getName().equalsIgnoreCase(kitName) && !r.getRequester().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            Player opponent = Bukkit.getPlayer(existing.getRequester());
            if (opponent != null && opponent.isOnline()) {
                startDuel(player, opponent, kit);
                pendingRequests.remove(existing.getRequester());
                return;
            }
        }
        pendingRequests.put(player.getUniqueId(), new DuelRequest(player.getUniqueId(), kit));
        player.sendMessage(t("queued", Collections.singletonMap("kit", kit.getName())));
    }

    private void openQueueGui(Player player) {
        if (kitMap.isEmpty()) {
            player.sendMessage(t("no.kits"));
            return;
        }
        int kitCount = kitMap.size();
        int rows = Math.min(6, (kitCount + 8) / 9);
        Inventory gui = Bukkit.createInventory(null, rows * 9, GUI_TITLE);

        int index = 0;
        for (Kit kit : kitMap.values()) {
            ItemStack item = kit.createDisplayItem(countQueuedForKit(kit));
            gui.setItem(index++, item);
        }

        for (int slot = index; slot < gui.getSize(); slot++) {
            ItemStack filler = new ItemStack(FILLER_MATERIAL);
            ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(" ");
                filler.setItemMeta(meta);
            }
            gui.setItem(slot, filler);
        }

        player.openInventory(gui);
    }

    private int countQueuedForKit(Kit kit) {
        return (int) pendingRequests.values().stream()
                .filter(r -> r.getKit().getName().equalsIgnoreCase(kit.getName()))
                .count();
    }

    private void startDuel(Player a, Player b, Kit kit) {
        if (activeDuels.containsKey(a.getUniqueId()) || activeDuels.containsKey(b.getUniqueId())) {
            a.sendMessage(t("already.in.duel"));
            b.sendMessage(t("already.in.duel"));
            return;
        }
        String arenaName = kit.getArenaWorld() != null && !kit.getArenaWorld().isEmpty()
            ? kit.getArenaWorld()
            : getConfig().getString("arena-world", "");
        if (arenaName.isEmpty()) {
            a.sendMessage(t("no.arena"));
            b.sendMessage(t("no.arena"));
            return;
        }
        World arena = getServer().getWorld(arenaName);
            if (arena == null) {
            // create world if missing
            getLogger().info("Arena world not found, creating: " + arenaName);
            arena = Bukkit.createWorld(new WorldCreator(arenaName));
            if (arena == null) {
                a.sendMessage(t("arena.create.failed", Collections.singletonMap("world", arenaName)));
                b.sendMessage(t("arena.create.failed", Collections.singletonMap("world", arenaName)));
                return;
            }
        }

        savePlayerState(a);
        savePlayerState(b);
        kit.applyTo(a);
        kit.applyTo(b);

        Location center = arena.getSpawnLocation().clone();
        Location locA = getSafeLocation(arena, center, -8);
        Location locB = getSafeLocation(arena, center, 7);
        a.teleport(locA);
        b.teleport(locB);

        Date duelStart = new Date();
        DuelReplay replay = new DuelReplay(
                a.getName(),
                b.getName(),
                duelStart.getTime(),
                locA,
                locB,
                arenaName,
                a.getUniqueId(),
                b.getUniqueId(),
                cloneItemStatic(a.getInventory().getItemInMainHand()),
                cloneItemStatic(b.getInventory().getItemInMainHand()),
                cloneItemStatic(a.getInventory().getHelmet()),
                cloneItemStatic(b.getInventory().getHelmet()),
                cloneItemStatic(a.getInventory().getChestplate()),
                cloneItemStatic(b.getInventory().getChestplate()),
                cloneItemStatic(a.getInventory().getLeggings()),
                cloneItemStatic(b.getInventory().getLeggings()),
                cloneItemStatic(a.getInventory().getBoots()),
                cloneItemStatic(b.getInventory().getBoots())
        );
        DuelSession session = new DuelSession(a.getUniqueId(), b.getUniqueId(), kit, replay, duelStart);
        activeDuels.put(a.getUniqueId(), session);
        activeDuels.put(b.getUniqueId(), session);
        Map<String,String> varsA = new HashMap<>(); varsA.put("opponent", b.getName()); varsA.put("kit", kit.getName());
        Map<String,String> varsB = new HashMap<>(); varsB.put("opponent", a.getName()); varsB.put("kit", kit.getName());
        a.sendMessage(t("duel.started", varsA));
        b.sendMessage(t("duel.started", varsB));
    }

    private void savePlayerState(Player player) {
        PlayerSnapshot snap = new PlayerSnapshot();
        snap.location = player.getLocation().clone();
        snap.contents = cloneContents(player.getInventory().getContents());
        snap.helmet = cloneItemStatic(player.getInventory().getHelmet());
        snap.chestplate = cloneItemStatic(player.getInventory().getChestplate());
        snap.leggings = cloneItemStatic(player.getInventory().getLeggings());
        snap.boots = cloneItemStatic(player.getInventory().getBoots());
        snap.offhand = cloneItemStatic(player.getInventory().getItemInOffHand());
        snap.health = player.getHealth();
        snap.food = player.getFoodLevel();
        snap.gameMode = player.getGameMode();
        savedStates.put(player.getUniqueId(), snap);
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            clone[i] = it == null ? null : it.clone();
        }
        return clone;
    }

    private static ItemStack cloneItemStatic(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private void restorePlayerState(Player player) {
        PlayerSnapshot snap = savedStates.remove(player.getUniqueId());
        if (snap == null) return;
        restoreSnapshot(player, snap);
    }

    private void restoreSnapshot(final Player player, final PlayerSnapshot snap) {
        Bukkit.getScheduler().runTask(this, () -> {
            player.getInventory().clear();
            player.getInventory().setContents(cloneContents(snap.contents));
            player.getInventory().setHelmet(cloneItemStatic(snap.helmet));
            player.getInventory().setChestplate(cloneItemStatic(snap.chestplate));
            player.getInventory().setLeggings(cloneItemStatic(snap.leggings));
            player.getInventory().setBoots(cloneItemStatic(snap.boots));
            player.getInventory().setItemInOffHand(cloneItemStatic(snap.offhand));
            player.setHealth(Math.min(snap.health, player.getMaxHealth()));
            player.setFoodLevel(snap.food);
            player.setGameMode(snap.gameMode);
            if (snap.location != null) {
                player.teleport(snap.location);
            }
            player.updateInventory();
        });
    }

    private Location getSafeLocation(World world, Location base, double offsetX) {
        Location loc = base.clone().add(offsetX, 0, 0);
        int groundY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        loc.setY(groundY + 1);
        // face towards center
        double dx = base.getX() - loc.getX();
        double dz = base.getZ() - loc.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        loc.setYaw(yaw);
        loc.setPitch(0);
        return loc;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        UUID deadId = dead.getUniqueId();
        if (!activeDuels.containsKey(deadId)) return;
        DuelSession session = activeDuels.get(deadId);
        UUID otherId = session.getPlayerA().equals(deadId) ? session.getPlayerB() : session.getPlayerA();
        session.getReplay().recordDeath(deadId, System.currentTimeMillis() - session.getReplay().getStartTime());
        Player winner = Bukkit.getPlayer(otherId);
        if (winner != null && winner.isOnline()) {
            winner.sendTitle(tNoPrefix("winner.title"), tNoPrefix("winner.subtitle", Collections.singletonMap("player", dead.getName())), 10, 70, 20);
            winner.sendMessage(t("duel.won", Collections.singletonMap("player", dead.getName())));
            Bukkit.getScheduler().runTaskLater(this, () -> restorePlayerState(winner), 80L);
        }
        dead.sendTitle(tNoPrefix("loser.title"), tNoPrefix("loser.subtitle", Collections.singletonMap("player", winner != null ? winner.getName() : "")), 10, 70, 20);
        dead.sendMessage(t("duel.lost", Collections.singletonMap("player", winner != null ? winner.getName() : "")));
        // restore spectators after delay
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (UUID specId : session.getSpectators()) {
                Player spec = Bukkit.getPlayer(specId);
                if (spec != null && spec.isOnline()) {
                    spec.sendMessage(t("duel.ended_return"));
                    restoreSpectatorSnapshot(spec);
                }
            }
        }, 80L);
        duelReplays.put(session.getReplay().getReplayKey(), session.getReplay());
        awaitingRespawn.add(deadId);
        activeDuels.remove(deadId);
        activeDuels.remove(otherId);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!awaitingRespawn.remove(id)) return;
        PlayerSnapshot snap = savedStates.remove(id);
        if (snap != null) {
            if (snap.location != null) {
                event.setRespawnLocation(snap.location);
            }
            Bukkit.getScheduler().runTaskLater(this, () -> restoreSnapshot(player, snap), 1L);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        DuelSession session = activeDuels.get(player.getUniqueId());
        if (session == null) return;
        long now = System.currentTimeMillis();
        long last = lastMoveRecord.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 200L) return;
        lastMoveRecord.put(player.getUniqueId(), now);
        session.getReplay().recordMovement(player.getUniqueId(), player.getLocation(), now - session.getReplay().getStartTime());
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getDamager() instanceof Player)) return;
        Player damaged = (Player) event.getEntity();
        Player damager = (Player) event.getDamager();
        DuelSession session = activeDuels.get(damaged.getUniqueId());
        if (session == null) return;
        if (!session.isParticipant(damager.getUniqueId())) return;
        long now = System.currentTimeMillis();
        session.getReplay().recordHit(
                damager.getUniqueId(),
                damaged.getUniqueId(),
                event.getFinalDamage(),
                now - session.getReplay().getStartTime(),
                getItemName(damager.getInventory().getItemInMainHand())
        );
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle() == null) return;
        String title = event.getView().getTitle();
        if (!title.equals(GUI_TITLE) && !title.equals(CONFIRM_REMOVE_TITLE) && !title.equals(SPECTATE_GUI_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == FILLER_MATERIAL) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (title.equals(GUI_TITLE)) {
            String name = ChatColor.stripColor(meta.getDisplayName()).toLowerCase(Locale.ROOT);
            queueForKit(player, name);
            player.closeInventory();
            return;
        }
        if (title.equals(SPECTATE_GUI_TITLE)) {
            UUID uid = player.getUniqueId();
            List<DuelSession> sessions = spectateSelections.get(uid);
            if (sessions == null) {
                player.closeInventory();
                return;
            }
            int slot = event.getSlot();
            if (slot < 0 || slot >= sessions.size()) {
                player.closeInventory();
                return;
            }
            DuelSession selected = sessions.get(slot);
            if (selected == null) {
                player.closeInventory();
                return;
            }
            spectatePlayer(player, selected);
            player.closeInventory();
            return;
        }
        // confirm remove GUI handling
        if (title.equals(CONFIRM_REMOVE_TITLE)) {
            UUID uid = player.getUniqueId();
            String key = pendingKitRemovals.get(uid);
            if (key == null) {
                player.closeInventory();
                return;
            }
            if (clicked.getType() == CONFIRM_MATERIAL) {
                kitMap.remove(key.toLowerCase(Locale.ROOT));
                saveKits();
                player.sendMessage(t("kit.removed", Collections.singletonMap("kit", key)));
            } else if (clicked.getType() == CANCEL_MATERIAL) {
                player.sendMessage(t("kit.remove.cancel"));
            }
            pendingKitRemovals.remove(uid);
            player.closeInventory();
            return;
        }
    }

    private String formatMessage(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void handleKitsCommand(Player player, String[] args) {
        if (!player.hasPermission("worldqueue.admin")) {
            player.sendMessage(t("no.permission"));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(t("usage.kits"));
            return;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
                handleKitAdd(player, args[2]);
                return;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
                handleKitList(player);
                return;
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                handleKitRemove(player, args[2]);
                return;
            }
            if (args.length >= 4 && args[1].equalsIgnoreCase("setarena")) {
                handleKitSetArena(player, args[2], args[3]);
                return;
            }
            player.sendMessage(t("usage.kits.admin"));
            return;
        }
        player.sendMessage(t("unknown.subcommand"));
    }

    private void handleKitList(Player player) {
        if (kitMap.isEmpty()) {
            player.sendMessage(t("no.kits"));
            return;
        }
        player.sendMessage(t("kits.header"));
        for (Kit kit : kitMap.values()) {
            int queued = countQueuedForKit(kit);
            String arena = kit.getArenaWorld();
            Map<String,String> vars = new HashMap<>();
            vars.put("kit", kit.getName());
            vars.put("queued", String.valueOf(queued));
            vars.put("arena", arena != null ? " &7arena: &f" + arena : "");
            player.sendMessage(t("kits.line", vars));
        }
    }

    private void handleKitRemove(Player player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!kitMap.containsKey(key)) {
            player.sendMessage(formatMessage("&cKit not found: &f" + name));
            return;
        }
        openKitRemoveConfirm(player, name);
    }

    private void openKitRemoveConfirm(Player player, String kitName) {
        Inventory inv = Bukkit.createInventory(null, 9, CONFIRM_REMOVE_TITLE);
        ItemStack confirm = new ItemStack(CONFIRM_MATERIAL);
        ItemMeta cm = confirm.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("§aConfirm removal");
            cm.setLore(Collections.singletonList("§7Remove kit: §f" + kitName));
            confirm.setItemMeta(cm);
        }
        ItemStack cancel = new ItemStack(CANCEL_MATERIAL);
        ItemMeta xm = cancel.getItemMeta();
        if (xm != null) {
            xm.setDisplayName("§cCancel");
            cancel.setItemMeta(xm);
        }
        inv.setItem(3, confirm);
        inv.setItem(5, cancel);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                ItemStack filler = new ItemStack(FILLER_MATERIAL);
                ItemMeta meta = filler.getItemMeta();
                if (meta != null) { meta.setDisplayName(" "); filler.setItemMeta(meta); }
                inv.setItem(i, filler);
            }
        }
        pendingKitRemovals.put(player.getUniqueId(), kitName);
        player.openInventory(inv);
    }

    private void handleKitSetArena(Player player, String name, String worldName) {
        String key = name.toLowerCase(Locale.ROOT);
        Kit kit = kitMap.get(key);
        if (kit == null) {
            player.sendMessage(t("kit.notfound", Collections.singletonMap("kit", name)));
            return;
        }
        World w = getServer().getWorld(worldName);
        if (w == null) {
            player.sendMessage(t("world.creating", Collections.singletonMap("world", worldName)));
            w = Bukkit.createWorld(new WorldCreator(worldName));
            if (w == null) {
                player.sendMessage(t("arena.create.failed", Collections.singletonMap("world", worldName)));
                return;
            }
        }
        // replace kit with new instance carrying arena
        Kit newKit = kit.withArena(worldName);
        kitMap.put(key, newKit);
        saveKits();
        player.sendMessage(t("arena.set", Collections.singletonMap("world", worldName)));
    }

    private void handleKitAdd(Player player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (kitMap.containsKey(key)) {
            player.sendMessage(t("kit.already.exists", Collections.singletonMap("kit", name)));
            return;
        }
        Kit kit = Kit.fromInventory(name, player.getInventory());
        kitMap.put(key, kit);
        saveKits();
        player.sendMessage(t("kit.saved", Collections.singletonMap("kit", name)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase(QUEUE_COMMAND)) {
            if (args.length == 1) {
                return kitMap.keySet().stream()
                        .filter(k -> k.startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        if (command.getName().equalsIgnoreCase(KITS_COMMAND)) {
            if (args.length == 1) {
                return Arrays.asList("admin");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
                return Arrays.asList("add", "list", "remove", "setarena");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("setarena"))) {
                return kitMap.keySet().stream().sorted().collect(Collectors.toList());
            }
        }
        if (command.getName().equalsIgnoreCase(REPLAY_COMMAND)) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .sorted()
                        .collect(Collectors.toList());
            }
            if (args.length == 2) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private static class DuelRequest {
        private final UUID requester;
        private final Kit kit;

        public DuelRequest(UUID requester, Kit kit) {
            this.requester = requester;
            this.kit = kit;
        }

        public UUID getRequester() {
            return requester;
        }

        public Kit getKit() {
            return kit;
        }
    }

    private static class PlayerSnapshot {
        private Location location;
        private ItemStack[] contents;
        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;
        private ItemStack offhand;
        private double health;
        private int food;
        private GameMode gameMode;
    }

    private void handleFightsCommand(Player player, String[] args) {
        if (!player.hasPermission("worldqueue.admin")) {
            player.sendMessage(t("no.permission"));
            return;
        }
        if (args.length == 0) {
            player.sendMessage(t("usage.fights"));
            return;
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length < 2) {
                player.sendMessage(t("usage.fights"));
                return;
            }
            String worldName = args[1];
            World w = getServer().getWorld(worldName);
            if (w == null) {
                player.sendMessage(t("world.creating", Collections.singletonMap("world", worldName)));
                w = Bukkit.createWorld(new WorldCreator(worldName));
                if (w == null) {
                    player.sendMessage(t("arena.create.failed", Collections.singletonMap("world", worldName)));
                    return;
                }
            }
            getConfig().set("arena-world", worldName);
            saveConfig();
            player.sendMessage(t("arena.set", Collections.singletonMap("world", worldName)));
            return;
        }
        player.sendMessage(t("unknown.subcommand"));
    }

    private void handleSpectateCommand(Player player, String[] args) {
        if (args.length == 0) {
            openSpectateGui(player);
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(t("player.notfound", Collections.singletonMap("player", args[0])));
            return;
        }
        DuelSession session = activeDuels.get(target.getUniqueId());
        if (session == null) {
            player.sendMessage(t("not.in.duel"));
            return;
        }
        spectatePlayer(player, session);
    }

    private void handleReplayCommand(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(t("usage.replay"));
            return;
        }
        String player1 = args[0];
        String player2 = args[1];
        DuelReplay replay = findReplay(player1, player2);
        if (replay == null) {
            player.sendMessage(t("replay.notfound", Map.of("player1", player1, "player2", player2)));
            return;
        }
        World arena = Bukkit.getWorld(replay.getArenaName());
        if (arena == null) {
            player.sendMessage(t("arena.notfound", Collections.singletonMap("world", replay.getArenaName())));
            return;
        }
        Location spectatorSpot = arena.getSpawnLocation().clone();
        spectatorSpot.add(0, 2, 0);
        player.teleport(spectatorSpot);
        player.setGameMode(GameMode.CREATIVE);
        giveReplayBook(player, replay);
        player.sendMessage(t("replay.teleported", Map.of("world", replay.getArenaName())));
        player.sendMessage(t("replay.starting", Map.of("player1", player1, "player2", player2)));
        playReplay(player, replay);
    }

    private DuelReplay findReplay(String player1, String player2) {
        for (DuelReplay replay : duelReplays.values()) {
            if (replay.matchesNames(player1, player2)) {
                return replay;
            }
        }
        return null;
    }

    private void openSpectateGui(Player player) {
        List<DuelSession> sessions = activeDuels.values().stream()
                .distinct()
                .collect(Collectors.toList());
        int rows = Math.min(6, Math.max(1, (sessions.size() + 8) / 9));
        Inventory gui = Bukkit.createInventory(null, rows * 9, SPECTATE_GUI_TITLE);
        int index = 0;
        for (DuelSession session : sessions) {
            Player a = Bukkit.getPlayer(session.getPlayerA());
            Player b = Bukkit.getPlayer(session.getPlayerB());
            if (a == null || b == null) continue;
            ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + a.getName() + " vs " + b.getName());
                meta.setLore(Arrays.asList(ChatColor.GRAY + "Kit: " + session.getKit().getName()));
                item.setItemMeta(meta);
            }
            gui.setItem(index++, item);
        }
        for (int slot = index; slot < gui.getSize(); slot++) {
            ItemStack filler = new ItemStack(FILLER_MATERIAL);
            ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(" ");
                filler.setItemMeta(meta);
            }
            gui.setItem(slot, filler);
        }
        spectateSelections.put(player.getUniqueId(), sessions);
        player.openInventory(gui);
    }

    private void spectatePlayer(Player player, DuelSession session) {
        if (session == null) return;
        saveSpectatorState(player);
        String arenaName = session.getKit().getArenaWorld() != null && !session.getKit().getArenaWorld().isEmpty()
                ? session.getKit().getArenaWorld()
                : getConfig().getString("arena-world", "");
        World arena = getServer().getWorld(arenaName);
        if (arena == null) {
            arena = Bukkit.createWorld(new WorldCreator(arenaName));
        }
        if (arena == null) {
            player.sendMessage(t("arena.create.failed", Collections.singletonMap("world", arenaName)));
            return;
        }
        Location center = arena.getSpawnLocation().clone();
        player.teleport(center);
        player.setGameMode(GameMode.SPECTATOR);
        session.addSpectator(player.getUniqueId());
        player.sendMessage(t("spectate.now"));
    }

    private void saveSpectatorState(Player player) {
        PlayerSnapshot snap = new PlayerSnapshot();
        snap.location = player.getLocation().clone();
        snap.contents = cloneContents(player.getInventory().getContents());
        snap.helmet = cloneItemStatic(player.getInventory().getHelmet());
        snap.chestplate = cloneItemStatic(player.getInventory().getChestplate());
        snap.leggings = cloneItemStatic(player.getInventory().getLeggings());
        snap.boots = cloneItemStatic(player.getInventory().getBoots());
        snap.offhand = cloneItemStatic(player.getInventory().getItemInOffHand());
        snap.health = player.getHealth();
        snap.food = player.getFoodLevel();
        snap.gameMode = player.getGameMode();
        spectatorSavedStates.put(player.getUniqueId(), snap);
    }

    private void restoreSpectatorSnapshot(Player player) {
        PlayerSnapshot snap = spectatorSavedStates.remove(player.getUniqueId());
        if (snap == null) return;
        restoreSnapshot(player, snap);
    }

    private String getItemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "fists";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void playReplay(Player viewer, DuelReplay replay) {
        World arena = Bukkit.getWorld(replay.getArenaName());
        if (arena == null) {
            viewer.sendMessage(t("arena.notfound", Collections.singletonMap("world", replay.getArenaName())));
            return;
        }
        viewer.sendMessage(t("replay.ready"));
        final long playStart = System.currentTimeMillis();
        new BukkitRunnable() {
            private ArmorStand[] fighters = new ArmorStand[2];
            @Override
            public void run() {
                if (fighters[0] == null || fighters[1] == null) {
                    fighters[0] = spawnReplayStand(arena, replay, 0);
                    fighters[1] = spawnReplayStand(arena, replay, 1);
                }
                long elapsed = System.currentTimeMillis() - playStart;
                replay.applyFrame(fighters, elapsed);
                if (replay.isComplete(elapsed)) {
                    for (ArmorStand stand : fighters) {
                        if (stand != null && !stand.isDead()) {
                            stand.remove();
                        }
                    }
                    viewer.sendMessage(t("replay.finished"));
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 2L);
    }

    private ArmorStand spawnReplayStand(World world, DuelReplay replay, int index) {
        Location location = replay.getStartLocation(index);
        String name = replay.getPlayerName(index);
        ArmorStand stand = (ArmorStand) world.spawnEntity(location, EntityType.ARMOR_STAND);
        stand.setCustomNameVisible(true);
        stand.setCustomName(ChatColor.YELLOW + name);
        stand.setVisible(true);
        stand.setMarker(false);
        stand.setGravity(false);
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setSmall(false);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.getEquipment().setHelmet(getPlayerHead(name));
        stand.getEquipment().setChestplate(replay.getChestplate(index));
        stand.getEquipment().setLeggings(replay.getLeggings(index));
        stand.getEquipment().setBoots(replay.getBoots(index));
        stand.getEquipment().setItemInMainHand(replay.getMainHand(index));
        return stand;
    }

    private ItemStack getPlayerHead(String playerName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
            meta.setOwningPlayer(offline);
            meta.setDisplayName(ChatColor.RESET + playerName);
            head.setItemMeta(meta);
        }
        return head;
    }

    private void giveReplayBook(Player player, DuelReplay replay) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return;
        meta.setTitle("Replay Damage Log");
        meta.setAuthor("WorldQueue");
        StringBuilder page = new StringBuilder();
        page.append("Damage log for ").append(replay.getPlayerName(0)).append(" vs ").append(replay.getPlayerName(1)).append("\n\n");
        int count = 0;
        for (String line : replay.buildDamageLog()) {
            if (count > 0 && count % 12 == 0) {
                meta.addPage(page.toString());
                page = new StringBuilder();
            }
            page.append(line).append("\n");
            count++;
        }
        meta.addPage(page.toString());
        book.setItemMeta(meta);
        int slot = player.getInventory().firstEmpty();
        if (slot >= 0) {
            player.getInventory().setItem(slot, book);
        } else {
            player.getInventory().setItem(0, book);
        }
    }

    private static class DuelSession {
        private final UUID playerA;
        private final UUID playerB;
        private final Kit kit;
        private final DuelReplay replay;
        private final Date startTime;
        private final List<UUID> spectators = new ArrayList<>();

        public DuelSession(UUID playerA, UUID playerB, Kit kit, DuelReplay replay, Date startTime) {
            this.playerA = playerA;
            this.playerB = playerB;
            this.kit = kit;
            this.replay = replay;
            this.startTime = startTime;
        }

        public DuelReplay getReplay() { return replay; }

        public UUID getPlayerA() {
            return playerA;
        }

        public UUID getPlayerB() {
            return playerB;
        }

        public Kit getKit() { return kit; }

        public List<UUID> getSpectators() { return spectators; }

        public void addSpectator(UUID id) { if (!spectators.contains(id)) spectators.add(id); }

        public boolean isParticipant(UUID id) {
            return playerA.equals(id) || playerB.equals(id);
        }
    }
}
