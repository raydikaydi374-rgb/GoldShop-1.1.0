package com.goldshop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class GoldShop extends JavaPlugin implements Listener {

    private YamlConfiguration balances;
    private YamlConfiguration playerData;
    private File balancesFile;
    private File playerDataFile;

    private final Map<UUID, String> menuModes = new HashMap<>();
    private final Map<UUID, String> menuCategories = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        balancesFile = new File(getDataFolder(), "balances.yml");
        playerDataFile = new File(getDataFolder(), "players.yml");

        balances = YamlConfiguration.loadConfiguration(balancesFile);
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);

        Objects.requireNonNull(getCommand("goldshop")).setExecutor(this);
        Objects.requireNonNull(getCommand("gold")).setExecutor(this);
        Objects.requireNonNull(getCommand("goldmissions")).setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        startPlaytimeCounter();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GoldExpansion(this).register();
        }

        getLogger().info("GoldShop 1.1.0 enabled!");
    }

    @Override
    public void onDisable() {
        saveAll();
    }

    private void saveAll() {
        saveYaml(balances, balancesFile);
        saveYaml(playerData, playerDataFile);
    }

    private void saveYaml(YamlConfiguration yaml, File file) {
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().severe("Could not save " + file.getName());
            e.printStackTrace();
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String currency() {
        return getConfig().getString("currency.name", "Gold");
    }

    private String format(double amount) {
        if (amount == Math.floor(amount)) {
            return Long.toString((long) amount);
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    public double getGold(UUID uuid) {
        return balances.getDouble(uuid.toString(), 0);
    }

    private void setGold(UUID uuid, double amount) {
        balances.set(uuid.toString(), Math.max(0, amount));
        saveYaml(balances, balancesFile);
    }

    private ItemStack createItem(String material, String name, List<String> lore) {
        Material type;
        try {
            type = Material.valueOf(material.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = Material.PAPER;
        }

        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(color(line));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private Inventory createMenu(Player player, String title, int size, String mode) {
        menuModes.put(player.getUniqueId(), mode);
        return Bukkit.createInventory(null, size, color(title));
    }

    private void openShop(Player player) {
        int size = getConfig().getInt("menu.size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            size = 27;
        }

        Inventory inventory = createMenu(
                player,
                getConfig().getString("menu.title", "&6&lGoldShop"),
                size,
                "shop"
        );

        ConfigurationSection categories =
                getConfig().getConfigurationSection("categories");

        if (categories != null) {
            for (String id : categories.getKeys(false)) {
                ConfigurationSection section =
                        categories.getConfigurationSection(id);

                if (section == null) continue;

                int slot = section.getInt("slot", -1);

                if (slot >= 0 && slot < size) {
                    inventory.setItem(slot, createItem(
                            section.getString("material", "CHEST"),
                            section.getString("name", id),
                            List.of("&eClick to open")
                    ));
                }
            }
        }

        int balanceSlot = getConfig().getInt("menu.balance-slot", 22);

        if (balanceSlot >= 0 && balanceSlot < size) {
            inventory.setItem(balanceSlot, createItem(
                    "GOLD_INGOT",
                    "&6" + currency() + ": &f" +
                            format(getGold(player.getUniqueId())),
                    List.of("&7Your balance")
            ));
        }

        int missionSlot = getConfig().getInt("menu.missions-slot", -1);

        if (missionSlot >= 0 && missionSlot < size) {
            inventory.setItem(missionSlot, createItem(
                    "BOOK",
                    "&eMissions",
                    List.of("&7Click to view missions")
            ));
        }

        player.openInventory(inventory);
    }

    private void openProducts(Player player, String category) {
        ConfigurationSection products =
                getConfig().getConfigurationSection("products." + category);

        if (products == null) {
            player.sendMessage(color("&cNo products configured."));
            return;
        }

        Inventory inventory = createMenu(
                player,
                "&8GoldShop &7» " + category,
                54,
                "products"
        );

        menuCategories.put(player.getUniqueId(), category);

        int slot = 0;

        for (String id : products.getKeys(false)) {
            ConfigurationSection product =
                    products.getConfigurationSection(id);

            if (product == null) continue;

            int configuredSlot = product.getInt("slot", -1);

            if (configuredSlot < 0) {
                while (slot < 45 && inventory.getItem(slot) != null) {
                    slot++;
                }
                configuredSlot = slot;
            }

            if (configuredSlot >= 0 && configuredSlot < 45) {
                double price = product.getDouble("price", 0);

                List<String> lore = new ArrayList<>(
                        product.getStringList("lore")
                );

                lore.add("&7Price: &6" + format(price) + " " + currency());
                lore.add("&eClick to purchase");

                inventory.setItem(configuredSlot, createItem(
                        product.getString("material", "CHEST"),
                        product.getString("name", id),
                        lore
                ));
            }

            slot++;
        }

        inventory.setItem(49, createItem(
                "ARROW",
                "&cBack",
                List.of("&7Return to shop")
        ));

        player.openInventory(inventory);
    }

    private void openMissions(Player player) {
        if (!getConfig().getBoolean("missions.enabled", true)) {
            player.sendMessage(color("&cMissions are disabled."));
            return;
        }

        Inventory inventory = createMenu(
                player,
                getConfig().getString("missions.menu-title", "&6Gold Missions"),
                54,
                "missions"
        );

        ConfigurationSection missions =
                getConfig().getConfigurationSection("missions.entries");

        if (missions != null) {
            int slot = 0;

            for (String id : missions.getKeys(false)) {
                if (slot >= 45) break;

                ConfigurationSection mission =
                        missions.getConfigurationSection(id);

                if (mission == null ||
                        !mission.getBoolean("enabled", true)) {
                    continue;
                }

                String path = player.getUniqueId() + ".missions." + id;

                int progress = playerData.getInt(path + ".progress", 0);
                int target = Math.max(1, mission.getInt("target", 1));
                boolean claimed = playerData.getBoolean(
                        path + ".claimed", false
                );

                String status;

                if (claimed && !mission.getBoolean("repeatable", false)) {
                    status = "&aClaimed";
                } else if (progress >= target) {
                    status = "&eComplete - click to claim";
                } else {
                    status = "&7Progress: &f" +
                            Math.min(progress, target) + "/" + target;
                }

                inventory.setItem(slot++, createItem(
                        progress >= target ? "EMERALD" : "BOOK",
                        mission.getString("name", id),
                        List.of(
                                mission.getString("description", ""),
                                "&6Reward: " +
                                        format(mission.getDouble("reward", 0))
                                        + " " + currency(),
                                status
                        )
                ));
            }
        }

        player.openInventory(inventory);
    }

    private void addProgress(Player player, String type, int amount) {
        ConfigurationSection missions =
                getConfig().getConfigurationSection("missions.entries");

        if (missions == null) return;

        for (String id : missions.getKeys(false)) {
            ConfigurationSection mission =
                    missions.getConfigurationSection(id);

            if (mission == null ||
                    !mission.getBoolean("enabled", true) ||
                    !mission.getString("type", "")
                            .equalsIgnoreCase(type)) {
                continue;
            }

            String path = player.getUniqueId() + ".missions." + id;

            if (playerData.getBoolean(path + ".claimed", false) &&
                    !mission.getBoolean("repeatable", false)) {
                continue;
            }

            int current = playerData.getInt(path + ".progress", 0);
            int target = Math.max(1, mission.getInt("target", 1));

            playerData.set(
                    path + ".progress",
                    Math.min(target, current + amount)
            );
        }

        saveYaml(playerData, playerDataFile);
    }

    private void startPlaytimeCounter() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    addProgress(player, "PLAYTIME", 1);
                }
            }
        }.runTaskTimer(this, 1200L, 1200L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        addProgress(event.getPlayer(), "JOIN", 1);
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();

        if (killer != null) {
            addProgress(killer, "KILL", 1);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();

        if (!menuModes.containsKey(uuid)) return;

        if (event.getClickedInventory() == null ||
                event.getClickedInventory() !=
                        event.getView().getTopInventory()) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        String mode = menuModes.get(uuid);

        if ("shop".equals(mode)) {
            ConfigurationSection categories =
                    getConfig().getConfigurationSection("categories");

            if (categories != null) {
                for (String id : categories.getKeys(false)) {
                    ConfigurationSection section =
                            categories.getConfigurationSection(id);

                    if (section != null &&
                            section.getInt("slot", -1) == slot) {
                        openProducts(player, id);
                        return;
                    }
                }
            }

            if (slot == getConfig().getInt("menu.missions-slot", -1)) {
                openMissions(player);
            }

        } else if ("products".equals(mode)) {
            if (slot == 49) {
                openShop(player);
                return;
            }

            String category = menuCategories.get(uuid);

            if (category == null) return;

            ConfigurationSection products =
                    getConfig().getConfigurationSection("products." + category);

            if (products == null) return;

            int autoSlot = 0;

            for (String id : products.getKeys(false)) {
                ConfigurationSection product =
                        products.getConfigurationSection(id);

                if (product == null) continue;

                int configuredSlot = product.getInt("slot", -1);

                if (configuredSlot < 0) {
                    while (autoSlot < 45 &&
                            productsSlotOccupied(products, id, autoSlot)) {
                        autoSlot++;
                    }
                    configuredSlot = autoSlot++;
                }

                if (configuredSlot == slot) {
                    purchase(player, product);
                    return;
                }
            }

        } else if ("missions".equals(mode)) {
            ConfigurationSection missions =
                    getConfig().getConfigurationSection("missions.entries");

            if (missions == null) return;

            int currentSlot = 0;

            for (String id : missions.getKeys(false)) {
                ConfigurationSection mission =
                        missions.getConfigurationSection(id);

                if (mission == null ||
                        !mission.getBoolean("enabled", true)) {
                    continue;
                }

                if (currentSlot++ == slot) {
                    claimMission(player, id, mission);
                    return;
                }
            }
        }
    }

    private boolean productsSlotOccupied(
            ConfigurationSection products, String currentId, int slot) {

        for (String id : products.getKeys(false)) {
            if (id.equals(currentId)) break;

            ConfigurationSection product =
                    products.getConfigurationSection(id);

            if (product != null && product.getInt("slot", -1) == slot) {
                return true;
            }
        }

        return false;
    }

    private void purchase(Player player, ConfigurationSection product) {
        double price = product.getDouble("price", 0);
        double balance = getGold(player.getUniqueId());

        List<String> commands = product.getStringList("commands");

        if (!Double.isFinite(price) || price < 0 ||
                balance < price || commands.isEmpty()) {
            player.sendMessage(color(
                    balance < price
                            ? "&cNot enough " + currency() + "!"
                            : "&cProduct is not configured."
            ));
            return;
        }

        setGold(player.getUniqueId(), balance - price);

        for (String command : commands) {
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    command.replace("{player}", player.getName())
            );
        }

        player.sendMessage(color(
                "&aPurchase successful! &6-" +
                        format(price) + " " + currency()
        ));

        player.closeInventory();
    }

    private void claimMission(
            Player player, String id, ConfigurationSection mission) {

        String path = player.getUniqueId() + ".missions." + id;

        int progress = playerData.getInt(path + ".progress", 0);
        int target = Math.max(1, mission.getInt("target", 1));

        boolean repeatable = mission.getBoolean("repeatable", false);
        boolean claimed = playerData.getBoolean(path + ".claimed", false);

        if (progress < target || (claimed && !repeatable)) {
            player.sendMessage(color("&cMission is not ready."));
            return;
        }

        double reward = mission.getDouble("reward", 0);

        setGold(
                player.getUniqueId(),
                getGold(player.getUniqueId()) + reward
        );

        if (repeatable) {
            playerData.set(path + ".progress", 0);
            playerData.set(path + ".claimed", false);
        } else {
            playerData.set(path + ".claimed", true);
        }

        saveYaml(playerData, playerDataFile);

        player.sendMessage(color(
                "&aMission reward claimed: &6+" +
                        format(reward) + " " + currency()
        ));

        openMissions(player);
    }

    private void help(CommandSender sender, boolean arabic) {
        sender.sendMessage(color("&6&lGoldShop Help"));

        String[] lines = arabic
                ? new String[]{
                "/gold - رصيدك",
                "/gold give <player> <amount> - إعطاء ذهب",
                "/gold take <player> <amount> - سحب ذهب",
                "/gold set <player> <amount> - تحديد الرصيد",
                "/gold rename <name> - تغيير اسم العملة",
                "/goldshop - فتح المتجر",
                "/goldmissions - فتح المهام",
                "/goldshop reload - إعادة تحميل الإعدادات"
        }
                : new String[]{
                "/gold - View your balance",
                "/gold give <player> <amount> - Give Gold",
                "/gold take <player> <amount> - Take Gold",
                "/gold set <player> <amount> - Set balance",
                "/gold rename <name> - Rename currency",
                "/goldshop - Open shop",
                "/goldmissions - Open missions",
                "/goldshop reload - Reload configuration"
        };

        for (String line : lines) {
            sender.sendMessage(color("&e» &f" + line));
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("goldmissions")) {
            if (sender instanceof Player player) {
                openMissions(player);
            } else {
                sender.sendMess
