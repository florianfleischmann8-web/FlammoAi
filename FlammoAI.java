package de.flamosmp.ai;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FlamoAI extends JavaPlugin implements Listener {

    private static final String GUI_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "KI-Assistent";
    private static final Map<UUID, Boolean> AWAITING_INPUT = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        
        // Command /ai registrieren
        getCommand("ai").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Nur für Spieler!");
                return true;
            }
            Player player = (Player) sender;
            
            if (args.length > 0) {
                // Direkt per Befehl gefragt: /ai Frage...
                String prompt = String.join(" ", args);
                AiService.ask(player, prompt);
            } else {
                // GUI öffnen
                openGui(player);
            }
            return true;
        });

        getLogger().info("FlamoAI wurde erfolgreich mit GUI-Support aktiviert!");
    }

    public static void openGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TITLE);

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
        }
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, glass);
        }

        ItemStack askItem = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta askMeta = askItem.getItemMeta();
        if (askMeta != null) {
            askMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Frage stellen");
            askMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Klicke hier, um deine Frage",
                    ChatColor.GRAY + "im Chat (privat) einzugeben."
            ));
            askItem.setItemMeta(askMeta);
        }
        gui.setItem(13, askItem);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;

        Player player = (Player) event.getWhoClicked();

        if (event.getSlot() == 13) {
            player.closeInventory();
            AWAITING_INPUT.put(player.getUniqueId(), true);

            player.sendMessage(ChatColor.GOLD + "========================================");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Bitte tippe deine KI-Frage in den Chat.");
            player.sendMessage(ChatColor.GRAY + "(Deine Nachricht wird abgefangen und nicht öffentlich angezeigt)");
            player.sendMessage(ChatColor.GOLD + "========================================");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (AWAITING_INPUT.getOrDefault(player.getUniqueId(), false)) {
            event.setCancelled(true);
            AWAITING_INPUT.remove(player.getUniqueId());

            String prompt = event.getMessage();

            getServer().getScheduler().runTask(this, () -> {
                AiService.ask(player, prompt);
            });
        }
    }
}
