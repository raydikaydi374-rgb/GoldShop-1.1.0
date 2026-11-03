package com.goldshop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class GoldShop extends JavaPlugin implements Listener {
    private YamlConfiguration balances, playerData, messages;
    private File balancesFile, playerDataFile, messagesFile;
    private final Map<UUID,String> language = new HashMap<>();
    private final Map<UUID,String> menuMode = new HashMap<>();
    private final Map<UUID,String> menuCategory = new HashMap<>();
    private final Map<UUID,Long> commandCooldowns = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("categories.yml");
        saveResourceIfMissing("shops.yml");
        balancesFile = dataFile("balances.yml"); playerDataFile = dataFile("players.yml"); messagesFile = dataFile("messages.yml");
        balances = YamlConfiguration.loadConfiguration(balancesFile);
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        Objects.requireNonNull(getCommand("goldshop")).setExecutor(this);
        Objects.requireNonNull(getCommand("gold")).setExecutor(this);
        Objects.requireNonNull(getCommand("goldmissions")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this,this);
        startPlaytimeCounter();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GoldExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
        getLogger().info("GoldShop 1.1.0 enabled.");
    }
    @Override public void onDisable(){ saveAll(); }
    private File dataFile(String n){ return new File(getDataFolder(),n); }
    private void saveResourceIfMissing(String n){ if(!new File(getDataFolder(),n).exists()) saveResource(n,false); }
    private void saveAll(){ save(balances,balancesFile); save(playerData,playerDataFile); }
    private void save(YamlConfiguration y,File f){ try{ y.save(f); }catch(IOException e){getLogger().severe("Save failed: "+e.getMessage());} }
    private String c(String s){ return ChatColor.translateAlternateColorCodes('&',s==null?"":s); }
    private void msg(CommandSender s,String key){ s.sendMessage(c(messages.getString("messages.prefix","&6GoldShop &8» ")+messages.getString("messages."+key,"&c"+key))); }
    private String cur(){return getConfig().getString("currency.name","Gold");}
    private String fmt(double n){return n==Math.floor(n)?Long.toString((long)n):String.format(Locale.US,"%.2f",n);}
    public double getGold(UUID id){return balances.getDouble(id.toString(),0);}
    private void setGold(UUID id,double v){balances.set(id.toString(),Math.max(0,v));save(balances,balancesFile);}
    private ItemStack item(String mat,String name,List<String> lore){
        Material m; try{m=Material.valueOf(mat.toUpperCase(Locale.ROOT));}catch(Exception e){m=Material.PAPER;}
        ItemStack it=new ItemStack(m); ItemMeta im=it.getItemMeta();
        if(im!=null){im.setDisplayName(c(name));im.setLore(lore.stream().map(this::c).toList());it.setItemMeta(im);} return it;
    }
    private Inventory menu(String title,int size,String mode,Player p){
        Inventory inv=Bukkit.createInventory(null,size,c(title)); menuMode.put(p.getUniqueId(),mode); return inv;
    }
    private void openShop(Player p){
        int size=getConfig().getInt("menu.size",27); if(size<9||size>54||size%9!=0)size=27;
        Inventory inv=menu(getConfig().getString("menu.title","&6&lGoldShop"),size,"shop",p);
        if(getConfig().getBoolean("menu.filler.enabled",false)){
            ItemStack fill=item(getConfig().getString("menu.filler.material","BLACK_STAINED_GLASS_PANE"),getConfig().getString("menu.filler.name"," "),List.of());
            for(int i=0;i<size;i++)inv.setItem(i,fill);
        }
        ConfigurationSection cats=getConfig().getConfigurationSection("categories");
        if(cats!=null) for(String id:cats.getKeys(false)){
            ConfigurationSection s=cats.getConfigurationSection(id); if(s==null)continue;
            int slot=s.getInt("slot",-1); if(slot>=0&&slot<size)inv.setItem(slot,item(s.getString("material","CHEST"),s.getString("name",id),List.of("&eClick to open")));
        }
        int balanceSlot=getConfig().getInt("menu.balance-slot",22);
        if(balanceSlot>=0&&balanceSlot<size)inv.setItem(balanceSlot,item("GOLD_INGOT","&6"+cur()+": &f"+fmt(getGold(p.getUniqueId())),List.of("&7Your balance")));
        int missionSlot=getConfig().getInt("menu.missions-slot",-1);
        if(missionSlot>=0&&missionSlot<size)inv.setItem(missionSlot,item("BOOK","&eMissions",List.of("&7Click to view missions")));
        ConfigurationSection cmdItems=getConfig().getConfigurationSection("command-items");
        if(cmdItems!=null)for(String id:cmdItems.getKeys(false)){
            ConfigurationSection s=cmdItems.getConfigurationSection(id);if(s==null)continue;
            int slot=s.getInt("slot",-1);if(slot>=0&&slot<size)inv.setItem(slot,item(s.getString("material","PAPER"),s.getString("name",id),s.getStringList("lore")));
        }
        p.openInventory(inv);
    }
    private void openProducts(Player p,String cat){
        ConfigurationSection sec=getConfig().getConfigurationSection("products."+cat);
        if(sec==null){p.sendMessage(c("&cNo products configured."));return;}
        Inventory inv=menu("&8GoldShop &7» "+cat,54,"products",p);menuCategory.put(p.getUniqueId(),cat);
        for(String id:sec.getKeys(false)){
            ConfigurationSection pr=sec.getConfigurationSection(id);if(pr==null)continue;
            int slot=pr.getInt("slot",-1); if(slot<0){slot=0;while(slot<45&&inv.getItem(slot)!=null)slot++;}
            if(slot>=0&&slot<45)inv.setItem(slot,item(pr.getString("material","CHEST"),pr.getString("name",id),
                combine(pr.getStringList("lore"),List.of("&7Price: &6"+fmt(pr.getDouble("price"))+" "+cur(),"&eClick to purchase"))));
        }
        inv.setItem(49,item("ARROW","&cBack",List.of()));p.openInventory(inv);
    }
    private List<String> combine(List<String>a,List<String>b){List<String>r=new ArrayList<>(a);r.addAll(b);return r;}
    private void openMissions(Player p){
        if(!getConfig().getBoolean("missions.enabled",true)){p.sendMessage(c("&cMissions are disabled."));return;}
        ConfigurationSection all=getConfig().getConfigurationSection("missions.entries");
        Inventory inv=menu(getConfig().getString("missions.menu-title","&6&lGold Missions"),54,"missions",p);
        if(all!=null){int slot=0;for(String id:all.getKeys(false)){ConfigurationSection m=all.getConfigurationSection(id);if(m==null||!m.getBoolean("enabled",true)||slot>=45)continue;
            int target=Math.max(1,m.getInt("target",1));int progress=playerData.getInt(p.getUniqueId()+".missions."+id+".progress",0);
            boolean claimed=playerData.getBoolean(p.getUniqueId()+".missions."+id+".claimed",false);
            String status=claimed?"&aClaimed":progress>=target?"&eComplete - click to claim":"&7Progress: &f"+Math.min(progress,target)+"/"+target;
            inv.setItem(slot++,item(progress>=target?"EMERALD":"BOOK",m.getString("name",id),
                List.of(m.getString("description",""),"&6Reward: "+m.getDouble("reward")+" "+cur(),status)));
        }}
        p.openInventory(inv);
    }
    private void progress(Player p,String type,int amount){
        ConfigurationSection all=getConfig().getConfigurationSection("missions.entries");if(all==null)return;
        for(String id:all.getKeys(false)){ConfigurationSection m=all.getConfigurationSection(id);if(m==null||!m.getBoolean("enabled",true)||!m.getString("type","").equalsIgnoreCase(type))continue;
            String path=p.getUniqueId()+".missions."+id;
            if(playerData.getBoolean(path+".claimed",false)&&!m.getBoolean("repeatable",false))continue;
            playerData.set(path+".progress",Math.min(m.getInt("target",1),playerData.getInt(path+".progress",0)+amount));
        } save(playerData,playerDataFile);
    }

    private void registerVoteHook(){
        if(!getConfig().getBoolean("voting.enabled",true) || Bukkit.getPluginManager().getPlugin("NuVotifier")==null)return;
        try{
            Class<?> eventClass=Class.forName("com.vexsoftware.votifier.model.VotifierEvent");
            Bukkit.getPluginManager().registerEvent((Class)eventClass,this,EventPriority.NORMAL,
                (listener,event)->{
                    try{
                        Object vote=eventClass.getMethod("getVote").invoke(event);
                        String username=String.valueOf(vote.getClass().getMethod("getUsername").invoke(vote));
                        Player target=Bukkit.getPlayerExact(username);
                        if(target==null)return;
                        long hours=getConfig().getLong("voting.cooldown-hours",24);
                        String path=target.getUniqueId()+".last-vote";
                        long now=System.currentTimeMillis();
                        if(now-playerData.getLong(path,0)<hours*3600000L)return;
                        double reward=getConfig().getDouble("voting.reward",5);
                        setGold(target.getUniqueId(),getGold(target.getUniqueId())+reward);
                        playerData.set(path,now);save(playerData,playerDataFile);
                        progress(target,"VOTE",1);
                        target.sendMessage(c("&aVote reward: &6+"+fmt(reward)+" "+cur()));
                    }catch(Exception ex){getLogger().warning("Could not process vote event: "+ex.getMessage());}
                },this);
            getLogger().info("NuVotifier hook enabled.");
        }catch(Exception ex){getLogger().warning("NuVotifier API not found; vote rewards disabled.");}
    }
    private void startPlaytimeCounter(){new BukkitRunnable(){public void run(){for(Player p:Bukkit.getOnlinePlayers())progress(p,"PLAYTIME",1);}}.runTaskTimer(this,1200L,1200L);}
    @EventHandler public void join(PlayerJoinEvent e){progress(e.getPlayer(),"JOIN",1);}
    @EventHandler public void kill(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p!=null)progress(p,"KILL",1);}
    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!menuMode.containsKey(p.getUniqueId()))return;
        if(e.getClickedInventory()==null||e.getClickedInventory()!=e.getView().getTopInventory())return;
        e.setCancelled(true);int slot=e.getRawSlot();String mode=menuMode.get(p.getUniqueId());
        if(mode.equals("shop")){
            if(slot>=0&&slot<e.getInventory().getSize()){
                ConfigurationSection cats=getConfig().getConfigurationSection("categories");
                if(cats!=null)for(String id:cats.getKeys(false)){ConfigurationSection s=cats.getConfigurationSection(id);if(s!=null&&s.getInt("slot",-1)==slot){openProducts(p,id);return;}}
                ConfigurationSection ci=getConfig().getConfigurationSection("command-items");
                if(ci!=null)for(String id:ci.getKeys(false)){ConfigurationSection s=ci.getConfigurationSection(id);if(s!=null&&s.getInt("slot",-1)==slot){runCommandItem(p,s);return;}}
                if(slot==getConfig().getInt("menu.missions-slot",-1)){openMissions(p);return;}
            }
        }else if(mode.equals("products")){
            if(slot==49){openShop(p);return;}
            String cat=menuCategory.get(p.getUniqueId());ConfigurationSection sec=getConfig().getConfigurationSection("products."+cat);if(sec==null)return;
            for(String id:sec.getKeys(false)){ConfigurationSection pr=sec.getConfigurationSection(id);if(pr==null)continue;
                int configured=pr.getInt("slot",-1);if(configured<0){configured=0;for(String prev:sec.getKeys(false)){if(prev.equals(id))break;ConfigurationSection x=sec.getConfigurationSection(prev);if(x!=null)configured++;}}
                if(configured==slot){purchase(p,pr);return;}
            }
        }else if(mode.equals("missions")){
            ConfigurationSection all=getConfig().getConfigurationSection("missions.entries");if(all==null)return;
            int i=0;for(String id:all.getKeys(false)){ConfigurationSection m=all.getConfigurationSection(id);if(m==null||!m.getBoolean("enabled",true))continue;if(i++!=slot)continue;claim(p,id,m);return;}
        }
    }
    private void purchase(Player p,ConfigurationSection pr){
        double price=pr.getDouble("price",0),bal=getGold(p.getUniqueId());List<String> cmds=pr.getStringList("commands");
        if(price<=0||bal<price||cmds.isEmpty()){p.sendMessage(c(bal<price?"&cNot enough "+cur()+"!":"&cProduct is not configured."));return;}
        setGold(p.getUniqueId(),bal-price);for(String cmd:cmds)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("{player}",p.getName()));
        p.sendMessage(c("&aPurchase successful! &6-"+fmt(price)+" "+cur()));p.closeInventory();
    }
    private void runCommandItem(Player p,ConfigurationSection s){
        String perm=s.getString("permission","");if(!perm.isEmpty()&&!p.hasPermission(perm)){p.sendMessage(c("&cNo permission."));return;}
        long cd=s.getLong("cooldown-seconds",0)*1000L;String key=p.getUniqueId()+":"+s.getName();long now=System.currentTimeMillis();
        if(commandCooldowns.getOrDefault(p.getUniqueId(),0L)>now){p.sendMessage(c("&cPlease wait before using another command item."));return;}
        commandCooldowns.put(p.getUniqueId(),now+cd);
        for(String cmd:s.getStringList("commands")){String ready=cmd.replace("{player}",p.getName());if(s.getString("executor","CONSOLE").equalsIgnoreCase("PLAYER"))p.performCommand(ready);else Bukkit.dispatchCommand(Bukkit.getConsoleSender(),ready);}
        p.closeInventory();
    }
    private void claim(Player p,String id,ConfigurationSection m){
        String path=p.getUniqueId()+".missions."+id;int progress=playerData.getInt(path+".progress",0),target=m.getInt("target",1);
        if(progress<target||playerData.getBoolean(path+".claimed",false)&&!m.getBoolean("repeatable",false)){p.sendMessage(c("&cMission not ready or already claimed."));return;}
        setGold(p.getUniqueId(),getGold(p.getUniqueId())+m.getDouble("reward",0));playerData.set(path+".claimed",true);
        if(m.getBoolean("repeatable",false))playerData.set(path+".progress",0);
        save(playerData,playerDataFile);p.sendMessage(c("&aMission reward claimed: &6"+fmt(m.getDouble("reward"))+" "+cur()));openMissions(p);
    }
    private void help(CommandSender s,String lang){
        boolean ar=lang.equals("ar");s.sendMessage(c("&6&lGoldShop Help"));
        String[] lines=ar?new String[]{"/gold - رصيدك","/gold give <player> <amount> - إعطاء ذهب","/gold take <player> <amount> - سحب ذهب","/gold set <player> <amount> - تحديد الرصيد","/gold rename <name> - تغيير اسم العملة","/goldshop - فتح المتجر","/goldmissions - فتح المهام","/goldshop reload - إعادة تحميل الإعدادات"}:
        new String[]{"/gold - View your balance","/gold give <player> <amount> - Give Gold","/gold take <player> <amount> - Take Gold","/gold set <player> <amount> - Set balance","/gold rename <name> - Rename currency","/goldshop - Open shop","/goldmissions - Open missions","/goldshop reload - Reload configuration"};
        for(String line:lines)s.sendMessage(c("&e» &f"+line));
    }
    @Override public boolean onCommand(CommandSender s,Command cmd,String label,String[] a){
        String name=cmd.getName().toLowerCase(Locale.ROOT);
        if(name.equals("goldmissions")){if(s instanceof Player p)openMissions(p);else s.sendMessage("Players only.");return true;}
        if(name.equals("goldshop")){
            if(a.length>0&&a[0].equalsIgnoreCase("help")){
                if(a.length>1){help(s,a[1].equalsIgnoreCase("ar")?"ar":"en");return true;}
                if(s instanceof Player p){p.sendMessage(c("&eChoose language: &a[العربية] &b[English]"));p.sendMessage(c("&7Type: &f/goldshop help ar &7or &f/goldshop help en"));}else help(s,"en");return true;
            }
            if(a.length>0&&a[0].equalsIgnoreCase("mission")){
                if(!s.hasPermission("goldshop.admin")){msg(s,"no-permission");return true;}
                if(a.length<3){s.sendMessage("/goldshop mission <create|delete|toggle|reward|target> <id> [value]");return true;}
                String action=a[1].toLowerCase(Locale.ROOT),id=a[2];String path="missions.entries."+id;
                switch(action){
                    case "create"->{getConfig().set(path+".name","&e"+id);getConfig().set(path+".description","&7New mission");getConfig().set(path+".type","JOIN");getConfig().set(path+".target",1);getConfig().set(path+".reward",10);getConfig().set(path+".enabled",true);getConfig().set(path+".repeatable",false);}
                    case "delete"->getConfig().set(path,null);
                    case "toggle"->getConfig().set(path+".enabled",!getConfig().getBoolean(path+".enabled",true));
                    case "reward","target"->{if(a.length<4){s.sendMessage("Missing value.");return true;}try{double v=Double.parseDouble(a[3]);getConfig().set(path+"."+action,v);}catch(Exception ex){s.sendMessage("Invalid number.");return true;}}
                    default->{s.sendMessage("Unknown mission action.");return true;}
                }
                saveConfig();s.sendMessage(c("&aMission updated. Edit its name, description and type in config.yml."));return true;
            }
            if(a.length>0&&a[0].equalsIgnoreCase("reload")){if(!s.hasPermission("goldshop.admin")){msg(s,"no-permission");return true;}reloadConfig();messages=YamlConfiguration.loadConfiguration(messagesFile);s.sendMessage(c("&aReloaded."));return true;}
            if(s instanceof Player p)openShop(p);else s.sendMessage("Players only.");return true;
        }
        if(a.length==0){if(s instanceof Player p)s.sendMessage(c("&6"+cur()+": &f"+fmt(getGold(p.getUniqueId()))));else s.sendMessage("Usage: /gold <give|take|set|rename>");return true;}
        if(a[0].equalsIgnoreCase("rename")){if(!s.hasPermission("goldshop.admin")){msg(s,"no-permission");return true;}if(a.length<2){s.sendMessage("/gold rename <name>");return true;}getConfig().set("currency.name",String.join(" ",Arrays.copyOfRange(a,1,a.length)));saveConfig();s.sendMessage(c("&aCurrency renamed."));return true;}
        if(!s.hasPermission("goldshop.admin")||a.length!=3){s.sendMessage(c("&cUsage: /gold <give|take|set> <player> <amount>"));return true;}
        Player target=Bukkit.getPlayerExact(a[1]);if(target==null){s.sendMessage(c("&cPlayer must be online."));return true;}
        double amount;try{amount=Double.parseDouble(a[2]);if(!Double.isFinite(amount)||amount<0)throw new NumberFormatException();}catch(Exception ex){s.sendMessage(c("&cInvalid amount."));return true;}
        double old=getGold(target.getUniqueId());switch(a[0].toLowerCase(Locale.ROOT)){case "give"->setGold(target.getUniqueId(),old+amount);case "take"->setGold(target.getUniqueId(),Math.max(0,old-amount));case "set"->setGold(target.getUniqueId(),amount);default->{s.sendMessage("/gold <give|take|set> <player> <amount>");return true;}}
        s.sendMessage(c("&aBalance updated."));target.sendMessage(c("&6Your "+cur()+": &f"+fmt(getGold(target.getUniqueId()))));return true;
    }
    private static final class GoldExpansion extends PlaceholderExpansion{
        private final GoldShop plugin;GoldExpansion(GoldShop p){plugin=p;}
        public String getIdentifier(){return "goldshop";}public String getAuthor(){return "GoldShop";}public String getVersion(){return "1.1.0";}public boolean persist(){return true;}
        public String onRequest(org.bukkit.OfflinePlayer p,String params){if(p==null)return "";if(params.equalsIgnoreCase("balance"))return plugin.fmt(plugin.getGold(p.getUniqueId()));if(params.equalsIgnoreCase("currency"))return plugin.cur();return null;}
    }
}
