package uk.rtm.worldqueue;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Kit {
    private final String name;
    private final List<ItemStack> contents;
    private final ItemStack helmet;
    private final ItemStack chestplate;
    private final ItemStack leggings;
    private final ItemStack boots;
    private final ItemStack offhand;
    private final String arenaWorld;

    public Kit(String name, List<ItemStack> contents, ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack offhand, String arenaWorld) {
        this.name = name;
        this.contents = contents;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.offhand = offhand;
        this.arenaWorld = arenaWorld;
    }

    public String getName() {
        return name;
    }

    public void applyTo(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setContents(contents.toArray(new ItemStack[0]));
        inv.setHelmet(cloneItemStatic(helmet));
        inv.setChestplate(cloneItemStatic(chestplate));
        inv.setLeggings(cloneItemStatic(leggings));
        inv.setBoots(cloneItemStatic(boots));
        inv.setItemInOffHand(cloneItemStatic(offhand));
        player.updateInventory();
    }

    public void save(FileConfiguration config, String path) {
        ConfigurationSection section = config.createSection(path);
        section.set("name", name);
        section.set("contents", contents);
        section.set("helmet", helmet);
        section.set("chestplate", chestplate);
        section.set("leggings", leggings);
        section.set("boots", boots);
        section.set("offhand", offhand);
        if (arenaWorld != null) section.set("arena", arenaWorld);
    }

    public static Kit fromInventory(String name, PlayerInventory inventory) {
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            contents.add(item == null ? null : item.clone());
        }
        return new Kit(
            name,
            contents,
            cloneItemStatic(inventory.getHelmet()),
            cloneItemStatic(inventory.getChestplate()),
            cloneItemStatic(inventory.getLeggings()),
            cloneItemStatic(inventory.getBoots()),
            cloneItemStatic(inventory.getItemInOffHand()),
            null
        );
    }

    private static ItemStack cloneItemStatic(ItemStack item) {
        return item == null ? null : item.clone();
    }

    public ItemStack createDisplayItem(int queuedCount) {
        ItemStack display = findBestDisplayItem();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§d§l" + name);
            List<String> lore = new ArrayList<>();
            lore.add("§7Click to join the queue for this kit");
            lore.add(" ");
            lore.add("§eQueued players: §f" + queuedCount);
            if (arenaWorld != null && !arenaWorld.isEmpty()) {
                lore.add("§7Arena: §f" + arenaWorld);
            }
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack findBestDisplayItem() {
        if (!contents.isEmpty()) {
            for (ItemStack item : contents) {
                if (item != null && item.getType() != Material.AIR) {
                    return item.clone();
                }
            }
        }
        if (helmet != null && helmet.getType() != Material.AIR) return helmet.clone();
        if (chestplate != null && chestplate.getType() != Material.AIR) return chestplate.clone();
        if (leggings != null && leggings.getType() != Material.AIR) return leggings.clone();
        if (boots != null && boots.getType() != Material.AIR) return boots.clone();
        if (offhand != null && offhand.getType() != Material.AIR) return offhand.clone();
        return new ItemStack(Material.DIAMOND_SWORD);
    }

    public static Kit fromConfig(String name, FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return new Kit(name, new ArrayList<>(), null, null, null, null, null, null);
        }
        List<ItemStack> contents = new ArrayList<>();
        if (section.isSet("contents")) {
            for (Object obj : section.getList("contents")) {
                if (obj instanceof ItemStack) {
                    contents.add(cloneItemStatic((ItemStack) obj));
                } else {
                    contents.add(null);
                }
            }
        }
        return new Kit(
                name,
                contents,
                section.getItemStack("helmet"),
                section.getItemStack("chestplate"),
                section.getItemStack("leggings"),
                section.getItemStack("boots"),
                section.getItemStack("offhand"),
                section.getString("arena", null)
        );
    }

    public String getArenaWorld() {
        return arenaWorld;
    }

    public Kit withArena(String arena) {
        return new Kit(this.name, this.contents, this.helmet, this.chestplate, this.leggings, this.boots, this.offhand, arena);
    }
}
