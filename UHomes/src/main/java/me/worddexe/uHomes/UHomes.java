package me.worddexe.uHomes;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UHomes extends JavaPlugin implements Listener {
    private final Map<UUID, Map<Integer, Location>> homes = new HashMap<>();
    private final Map<UUID, Integer> teleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingConfirmations = new ConcurrentHashMap<>();

    private FileConfiguration config;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        config = getConfig();
        loadHomes();
    }

    @Override
    public void onDisable() {
        saveHomes();
    }

    private int getMaxHomes(Player player) {
        for (int i = 7; i >= 1; i--) {
            if (player.hasPermission("uHomes.maxhomes." + i)) {
                return i;
            }
        }
        return config.getInt("default-max-homes", 3);
    }

    private void openHomeGUI(Player player) {
        new HomeGUI(player).open();
    }

    private class HomeGUI {
        private final Player player;
        private final Inventory inv;
        private final int maxHomes;

        HomeGUI(Player player) {
            this.player = player;
            this.maxHomes = getMaxHomes(player);
            this.inv = Bukkit.createInventory(null, 36, ChatColor.GRAY + "" + ChatColor.BOLD + "Homes Menu");
            Map<Integer, Location> playerHomes = homes.getOrDefault(player.getUniqueId(), new HashMap<>());

            for (int i = 0; i < 7; i++) {
                ItemStack bed = new ItemStack(Material.GREEN_BED);
                ItemMeta meta = bed.getItemMeta();

                if (playerHomes.containsKey(i)) {
                    meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Home " + (i + 1));
                } else {
                    meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Home " + (i + 1));
                }
                bed.setItemMeta(meta);
                inv.setItem(i + 10, bed);
            }

            for (int i = 0; i < 7; i++) {
                ItemStack dye = new ItemStack(Material.LIME_DYE);
                ItemMeta dyeMeta = dye.getItemMeta();

                if (i < maxHomes) {
                    if (homes.getOrDefault(player.getUniqueId(), new HashMap<>()).containsKey(i)) {
                        dye.setType(Material.RED_DYE);
                        dyeMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Remove Home");
                    } else {
                        dyeMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Set Home");
                    }
                } else {
                    dye.setType(Material.GRAY_DYE);
                    dyeMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Limit Reached");
                }
                dye.setItemMeta(dyeMeta);
                inv.setItem(i + 19, dye);
            }
        }

        void open() {
            player.openInventory(inv);
        }
    }

    private class ConfirmationGUI {
        private final Player player;
        private final int homeIndex;
        private final Inventory inv;

        ConfirmationGUI(Player player, int homeIndex) {
            this.player = player;
            this.homeIndex = homeIndex;
            this.inv = Bukkit.createInventory(null, 27, ChatColor.RED + "Confirm Deletion");

            ItemStack confirm = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            ItemMeta confirmMeta = confirm.getItemMeta();
            confirmMeta.setDisplayName(ChatColor.GREEN + "Confirm");
            confirm.setItemMeta(confirmMeta);

            ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta cancelMeta = cancel.getItemMeta();
            cancelMeta.setDisplayName(ChatColor.RED + "Cancel");
            cancel.setItemMeta(cancelMeta);

            inv.setItem(11, confirm);
            inv.setItem(15, cancel);
        }

        void open() {
            pendingConfirmations.put(player.getUniqueId(), homeIndex);
            player.openInventory(inv);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (event.getView().getTitle().startsWith(ChatColor.GRAY + "" + ChatColor.BOLD + "Homes Menu")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot >= 10 && slot <= 16) {
                handleHomeTeleport(player, slot - 10);
            } else if (slot >= 19 && slot <= 25) {
                handleHomeManagement(player, slot - 19);
            }
        } else if (event.getView().getTitle().startsWith(ChatColor.RED + "Confirm Deletion")) {
            event.setCancelled(true);
            UUID uuid = player.getUniqueId();
            int clickedSlot = event.getRawSlot();

            if (clickedSlot == 11) {
                Integer homeIndex = pendingConfirmations.get(uuid);
                if (homeIndex != null) {
                    removeHome(player, homeIndex);
                }
            }
            pendingConfirmations.remove(uuid);
            player.closeInventory();
        }
    }

    private void handleHomeTeleport(Player player, int index) {
        Location home = homes.getOrDefault(player.getUniqueId(), new HashMap<>()).get(index);
        if (home == null) {
            player.sendActionBar(ChatColor.RED + "Home not set!");
            return;
        }

        if (teleportTasks.containsKey(player.getUniqueId())) {
            Bukkit.getScheduler().cancelTask(teleportTasks.get(player.getUniqueId()));
        }

        startTeleportCountdown(player, home);
    }

    private void handleHomeManagement(Player player, int index) {
        int maxHomes = getMaxHomes(player);
        if (index >= maxHomes) {
            player.sendMessage(ChatColor.RED + "You don't have permission for more homes!");
            return;
        }

        Map<Integer, Location> playerHomes = homes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (playerHomes.containsKey(index)) {
            new ConfirmationGUI(player, index).open();
        } else {
            playerHomes.put(index, player.getLocation());
            saveHomes();
            player.sendActionBar(ChatColor.GREEN + "Home " + (index + 1) + " set!");
            openHomeGUI(player);
        }
    }

    private void removeHome(Player player, int index) {
        Map<Integer, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes != null) {
            playerHomes.remove(index);
            saveHomes();
        }
        player.sendActionBar(ChatColor.GREEN + "Home " + (index + 1) + " removed!");
    }

    private void startTeleportCountdown(Player player, Location destination) {
        final UUID uuid = player.getUniqueId();
        teleportLocations.put(uuid, player.getLocation());

        int taskId = new BukkitRunnable() {
            int countdown = 5;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (countdown <= 0) {
                    player.teleport(destination);
                    player.sendActionBar(ChatColor.GREEN + "Teleported!");
                    teleportTasks.remove(uuid);
                    teleportLocations.remove(uuid);
                    cancel();
                } else {
                    player.sendActionBar(ChatColor.GREEN + "Teleporting in " + countdown + " seconds. Do not move");
                    countdown--;
                }
            }
        }.runTaskTimer(this, 0, 20).getTaskId();

        teleportTasks.put(uuid, taskId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (teleportTasks.containsKey(uuid)) {
            if (event.getFrom().distanceSquared(event.getTo()) > 0.1) {
                Bukkit.getScheduler().cancelTask(teleportTasks.get(uuid));
                player.sendActionBar(ChatColor.RED + "Teleportation cancelled due to movement");
                teleportTasks.remove(uuid);
                teleportLocations.remove(uuid);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (cmd.getName().equalsIgnoreCase("homes")) {
            openHomeGUI(player);
            return true;
        } else if (cmd.getName().equalsIgnoreCase("home")) {
            Location home = homes.getOrDefault(player.getUniqueId(), new HashMap<>()).get(0);
            if (home == null) {
                player.sendActionBar(ChatColor.RED + "Home 1 not set!");
            } else {
                startTeleportCountdown(player, home);
            }
            return true;
        }
        return false;
    }

    private void saveHomes() {
        homes.forEach((uuid, homeMap) -> {
            homeMap.forEach((index, loc) -> {
                config.set("homes." + uuid + "." + index + ".world", loc.getWorld().getName());
                config.set("homes." + uuid + "." + index + ".x", loc.getX());
                config.set("homes." + uuid + "." + index + ".y", loc.getY());
                config.set("homes." + uuid + "." + index + ".z", loc.getZ());
            });
        });
        saveConfig();
    }

    private void loadHomes() {
        if (!config.contains("homes")) return;

        config.getConfigurationSection("homes").getKeys(false).forEach(uuidStr -> {
            UUID uuid = UUID.fromString(uuidStr);
            Map<Integer, Location> homeMap = new HashMap<>();

            config.getConfigurationSection("homes." + uuidStr).getKeys(false).forEach(indexStr -> {
                int index = Integer.parseInt(indexStr);
                String path = "homes." + uuidStr + "." + indexStr + ".";
                World world = Bukkit.getWorld(config.getString(path + "world"));
                double x = config.getDouble(path + "x");
                double y = config.getDouble(path + "y");
                double z = config.getDouble(path + "z");
                homeMap.put(index, new Location(world, x, y, z));
            });

            homes.put(uuid, homeMap);
        });
    }
}