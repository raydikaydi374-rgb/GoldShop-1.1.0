package com.goldshop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class GoldShop extends JavaPlugin implements Listener {

    private YamlConfiguration balances, playerData, messages;
    private File balancesFile, playerDataFile, messagesFile;

    private final Map<UUID,String> language = new HashMap<>();
    private final Map<UUID,String> menuMode = new HashMap<>();
    private final Map<UUID,String> menuCategory = new HashMap<>();
    private final Map<String,Long> commandCooldowns = new HashMap<>();
    private final Map<UUID,Map<Integer,String>> productSlots = new HashMap<>();

    @Override
    public void onEnable() {

        saveDefaultConfig();

        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("categories.yml");
        saveResourceIfMissing("shops.yml");

        balancesFile = dataFile("balances.yml");
        playerDataFile = dataFile("players.yml");
        messagesFile = dataFile("messages.yml");

        balances = YamlConfiguration.loadConfiguration(balancesFile);
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        if (getCommand("goldshop") == null ||
            getCommand("gold") == null ||
            getCommand("goldmissions") == null) {

            getLogger().severe(
                "A required command is missing from plugin.yml. Disabling GoldShop."
            );

            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("goldshop").setExecutor(this);
        getCommand("gold").setExecutor(this);
        getCommand("goldmissions").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this,this);

        startPlaytimeCounter();
        registerVoteHook();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GoldExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        getLogger().info("GoldShop 1.1.0 enabled.");
    }

    @Override
    public void onDisable() {
        saveAll();
    }

    private File dataFile(String n) {
        return new File(getDataFolder(),n);
    }

    private void saveResourceIfMissing(String n) {

        File file = new File(getDataFolder(), n);

        if (file.exists()) return;

        try {
            saveResource(n, false);
        } catch (IllegalArgumentException ex) {
            getLogger().warning(
                "Optional resource " + n + " was not found in the plugin JAR."
            );
        }
    }

    private void saveAll() {
        save(balances,balancesFile);
        save(playerData,playerDataFile);
    }

    private void save(YamlConfiguration y,File f) {
        try {
            y.save(f);
        } catch (IOException e) {
            getLogger().severe("Save failed: "+e.getMessage());
        }
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&',s==null?"":s);
    }

    private void msg(CommandSender s,String key) {

        String prefix = messages.getString(
            "messages.prefix","&6GoldShop &8» "
        );

        String message = messages.getString(
            "messages."+key,"&c"+key
        );

        s.sendMessage(c(prefix+message));
    }

    private String cur() {
        return getConfig().getString("currency.name","Gold");
    }

    private String fmt(double n) {
        return n==Math.floor(n)
            ? Long.toString((long)n)
            : String.format(Locale.US,"%.2f",n);
    }

    public double getGold(UUID id) {
        return balances.getDouble(id.toString(),0);
    }

    private void setGold(UUID id,double v) {
        balances.set(id.toString(),Math.max(0,v));
        save(balances,balancesFile);
    }

    private ItemStack item(String mat,String name,List<String> lore) {

        Material m;

        try {
            m = Material.valueOf(mat.toUpperCase(Locale.ROOT));
        } catch(Exception e) {
            m = Material.PAPER;
        }

        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();

        if(im!=null) {
            im.setDisplayName(c(name));
            im.setLore(lore.stream().map(this::c).toList());
            it.setItemMeta(im);
        }

        return it;
    }

    private Inventory menu(String title,int size,String mode,Player p) {

        Inventory inv = Bukkit.createInventory(null,size,c(title));

        menuMode.put(p.getUniqueId(),mode);

        return inv;
    }

    private void openShop(Player p) {

        int size = getConfig().getInt("menu.size",27);

        if(size<9 || size>54 || size%9!=0) size=27;

        Inventory inv = menu(
            getConfig().getString("menu.title","&6&lGoldShop"),
            size,
            "shop",
            p
        );

        if(getConfig().getBoolean("menu.filler.enabled",false)) {

            ItemStack fill = item(
                getConfig().getString(
                    "menu.filler.material",
                    "BLACK_STAINED_GLASS_PANE"
                ),
                getConfig().getString("menu.filler.name"," "),
                List.of()
            );

            for(int i=0;i<size;i++) {
                inv.setItem(i,fill);
            }
        }

        ConfigurationSection cats =
            getConfig().getConfigurationSection("categories");

        if(cats!=null) {

            for(String id:cats.getKeys(false)) {

                ConfigurationSection s = cats.getConfigurationSection(id);

                if(s==null) continue;

                int slot = s.getInt("slot",-1);

                if(slot>=0 && slot<size) {

                    inv.setItem(
                        slot,
                        item(
                            s.getString("material","CHEST"),
                            s.getString("name",id),
                            List.of("&eClick to open")
                        )
                    );
                }
            }
        }

        int balanceSlot = getConfig().getInt("menu.balance-slot",22);

        if(balanceSlot>=0 && balanceSlot<size) {

            inv.setItem(
                balanceSlot,
                item(
                    "GOLD_INGOT",
                    "&6"+cur()+": &f"+fmt(getGold(p.getUniqueId())),
                    List.of("&7Your balance")
                )
            );
        }

        int missionSlot = getConfig().getInt("menu.missions-slot",-1);

        if(missionSlot>=0 && missionSlot<size) {

            inv.setItem(
                missionSlot,
                item(
                    "BOOK",
                    "&eMissions",
                    List.of("&7Click to view missions")
                )
            );
        }

        ConfigurationSection cmdItems =
            getConfig().getConfigurationSection("command-items");

        if(cmdItems!=null) {

            for(String id:cmdItems.getKeys(false)) {

                ConfigurationSection s =
                    cmdItems.getConfigurationSection(id);

                if(s==null) continue;

                int slot = s.getInt("slot",-1);

                if(slot>=0 && slot<size) {

                    inv.setItem(
                        slot,
                        item(
                            s.getString("material","PAPER"),
                            s.getString("name",id),
                            s.getStringList("lore")
                        )
                    );
                }
            }
        }

        p.openInventory(inv);
    }

    private void openProducts(Player p,String cat) {

        ConfigurationSection sec =
            getConfig().getConfigurationSection("products."+cat);

        if(sec==null) {
            p.sendMessage(c("&cNo products configured."));
            return;
        }

        Inventory inv = menu(
            "&8GoldShop &7» "+cat,
            54,
            "products",
            p
        );

        menuCategory.put(p.getUniqueId(),cat);

        Map<Integer,String> slots = new HashMap<>();

        for(String id:sec.getKeys(false)) {

            ConfigurationSection pr = sec.getConfigurationSection(id);

            if(pr==null) continue;

            int slot = pr.getInt("slot",-1);

            if(slot<0) {
                slot=0;

                while(slot<45 && inv.getItem(slot)!=null) {
                    slot++;
                }
            }

            if(slot>=0 && slot<45 && !slots.containsKey(slot)) {

                inv.setItem(
                    slot,
                    item(
                        pr.getString("material","CHEST"),
                        pr.getString("name",id),
                        combine(
                            pr.getStringList("lore"),
                            List.of(
                                "&7Price: &6"+
                                fmt(pr.getDouble("price"))+" "+cur(),
                                "&eClick to purchase"
                            )
                        )
                    )
                );

                slots.put(slot,id);
            }
        }

        productSlots.put(p.getUniqueId(),slots);

        inv.setItem(
            49,
            item("ARROW","&cBack",List.of())
        );

        p.openInventory(inv);
    }

    private List<String> combine(List<String> a,List<String> b) {

        List<String> r = new ArrayList<>(a);

        r.addAll(b);

        return r;
    }

    private void openMissions(Player p) {

        if(!getConfig().getBoolean("missions.enabled",true)) {
            p.sendMessage(c("&cMissions are disabled."));
            return;
        }

        ConfigurationSection all =
            getConfig().getConfigurationSection("missions.entries");

        Inventory inv = menu(
            getConfig().getString(
                "missions.menu-title",
                "&6&lGold Missions"
            ),
            54,
            "missions",
            p
        );

        if(all!=null) {

            int slot=0;

            for(String id:all.getKeys(false)) {

                ConfigurationSection m = all.getConfigurationSection(id);

                if(m==null ||
                   !m.getBoolean("enabled",true) ||
                   slot>=45) continue;

                int target = Math.max(1,m.getInt("target",1));

                int progress = playerData.getInt(
                    p.getUniqueId()+".missions."+id+".progress",
                    0
                );

                boolean claimed = playerData.getBoolean(
                    p.getUniqueId()+".missions."+id+".claimed",
                    false
                );

                String status =
                    (claimed && !m.getBoolean("repeatable",false))
                    ? "&aClaimed"
                    : progress>=target
                    ? "&eComplete - click to claim"
                    : "&7Progress: &f"+Math.min(progress,target)+"/"+target;

                inv.setItem(
                    slot++,
                    item(
                        progress>=target?"EMERALD":"BOOK",
                        m.getString("name",id),
                        List.of(
                            m.getString("description",""),
                            "&6Reward: "+m.getDouble("reward")+" "+cur(),
                            status
                        )
                    )
                );
            }
        }

        p.openInventory(inv);
    }

    private void progress(Player p,String type,int amount) {

        ConfigurationSection all =
            getConfig().getConfigurationSection("missions.entries");

        if(all==null) return;

        for(String id:all.getKeys(false)) {

            ConfigurationSection m = all.getConfigurationSection(id);

            if(m==null ||
               !m.getBoolean("enabled",true) ||
               !m.getString("type","").equalsIgnoreCase(type)) continue;

            String path = p.getUniqueId()+".missions."+id;

            if(playerData.getBoolean(path+".claimed",false) &&
               !m.getBoolean("repeatable",false)) continue;

            int target = Math.max(1,m.getInt("target",1));

            int current = playerData.getInt(path+".progress",0);

            playerData.set(
                path+".progress",
                Math.min(target,current+Math.max(0,amount))
            );
        }

        save(playerData,playerDataFile);
    }

    private void registerVoteHook() {

        if(!getConfig().getBoolean("voting.enabled",true) ||
           Bukkit.getPluginManager().getPlugin("NuVotifier")==null) return;

        try {

            Class<?> eventClass = Class.forName(
                "com.vexsoftware.votifier.model.VotifierEvent"
            );

            Bukkit.getPluginManager().registerEvent(
                (Class)eventClass,
                this,
                EventPriority.NORMAL,
                (listener,event)-> {

                    try {

                        Object vote = eventClass
                            .getMethod("getVote")
                            .invoke(event);

                        String username = String.valueOf(
                            vote.getClass()
                                .getMethod("getUsername")
                                .invoke(vote)
                        );

                        Player target = Bukkit.getPlayerExact(username);

                        if(target==null) return;

                        long hours = getConfig().getLong(
                            "voting.cooldown-hours",
                            24
                        );

                        String path =
                            target.getUniqueId()+".last-vote";

                        long now = System.currentTimeMillis();

                        if(now-playerData.getLong(path,0)
                           <hours*3600000L) return;

                        double reward = getConfig().getDouble(
                            "voting.reward",
                            5
                        );

                        setGold(
                            target.getUniqueId(),
                            getGold(target.getUniqueId())+reward
                        );

                        playerData.set(path,now);

                        save(playerData,playerDataFile);

                        progress(target,"VOTE",1);

                        target.sendMessage(
                            c("&aVote reward: &6+"+
                              fmt(reward)+" "+cur())
                        );

                    } catch(Exception ex) {

                        getLogger().warning(
                            "Could not process vote event: "+ex.getMessage()
                        );
                    }

                },
                this
            );

            getLogger().info("NuVotifier hook enabled.");

        } catch(Exception ex) {

            getLogger().warning(
                "NuVotifier API not found; vote rewards disabled."
            );
        }
    }

    private void startPlaytimeCounter() {

        new BukkitRunnable() {

            public void run() {

                for(Player p:Bukkit.getOnlinePlayers()) {

                    progress(p,"PLAYTIME",1);
                }
            }

        }.runTaskTimer(this,1200L,1200L);
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        progress(e.getPlayer(),"JOIN",1);
    }

    @EventHandler
    public void kill(EntityDeathEvent e) {

        Player p = e.getEntity().getKiller();

        if(p!=null) {
            progress(p,"KILL",1);
        }
    }

    @EventHandler
    public void closeMenu(InventoryCloseEvent e) {

        UUID id = e.getPlayer().getUniqueId();

        if(!(e.getPlayer() instanceof Player player)) return;

        Bukkit.getScheduler().runTask(this,()-> {

            if(!player.isOnline()) return;

            if(player.getOpenInventory()
               .getTopInventory()
               .equals(e.getInventory())) {

                menuMode.remove(id);
                menuCategory.remove(id);
                productSlots.remove(id);
            }
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {

        UUID id = e.getPlayer().getUniqueId();

        menuMode.remove(id);
        menuCategory.remove(id);
        productSlots.remove(id);

        commandCooldowns.keySet().removeIf(
            key->key.startsWith(id.toString()+":")
        );
    }

    @EventHandler
    public void click(InventoryClickEvent e) {

        if(!(e.getWhoClicked() instanceof Player p) ||
           !menuMode.containsKey(p.getUniqueId())) return;

        if(e.getClickedInventory()==null ||
           e.getClickedInventory()!=e.getView().getTopInventory()) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();

        String mode = menuMode.get(p.getUniqueId());

        if(mode.equals("shop")) {

            if(slot>=0 && slot<e.getInventory().getSize()) {

                ConfigurationSection cats =
                    getConfig().getConfigurationSection("categories");

                if(cats!=null) {

                    for(String id:cats.getKeys(false)) {

                        ConfigurationSection s =
                            cats.getConfigurationSection(id);

                        if(s!=null && s.getInt("slot",-1)==slot) {
                            openProducts(p,id);
                            return;
                        }
                    }
                }

                ConfigurationSection ci =
                    getConfig().getConfigurationSection("command-items");

                if(ci!=null) {

                    for(String id:ci.getKeys(false)) {

                        ConfigurationSection s =
                            ci.getConfigurationSection(id);

                        if(s!=null && s.getInt("slot",-1)==slot) {
                            runCommandItem(p,s);
                            return;
                        }
                    }
                }

                if(slot==getConfig().getInt("menu.missions-slot",-1)) {
                    openMissions(p);
                    return;
                }
            }

        } else if(mode.equals("products")) {

            if(slot==49) {
                openShop(p);
                return;
            }

            String cat = menuCategory.get(p.getUniqueId());

            if(cat==null) return;

            ConfigurationSection sec =
                getConfig().getConfigurationSection("products."+cat);

            if(sec==null) return;

            Map<Integer,String> slots =
                productSlots.get(p.getUniqueId());

            if(slots==null) return;

            String productId = slots.get(slot);

            if(productId==null) return;

            ConfigurationSection product =
                sec.getConfigurationSection(productId);

            if(product!=null) purchase(p,product);

        } else if(mode.equals
