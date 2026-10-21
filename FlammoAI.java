package de.flamosmp.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FlammoAI extends JavaPlugin implements Listener {

    private OllamaClient ollamaClient;
    private AiService aiService;
    private AiGui aiGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.ollamaClient = new OllamaClient(this);
        this.aiService = new AiService(this, ollamaClient);
        this.aiGui = new AiGui(this, aiService);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(aiGui, this);

        getCommand("ai").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Nur Spieler können /ai nutzen.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("flamoai.use")) {
                player.sendMessage(ChatColor.RED + "Dazu hast du keine Berechtigung.");
                return true;
            }

            if (args.length > 0) {
                String prompt = String.join(" ", args);
                aiService.ask(player, prompt);
            } else {
                aiGui.open(player);
            }
            return true;
        });

        getLogger().info("FlammoAI aktiv.");
    }
}

class AiGui implements Listener {
    private final FlammoAI plugin;
    private final AiService service;
    private static final String TITLE = ChatColor.DARK_GRAY + "Flamo SMP" + ChatColor.GRAY + " | " + ChatColor.LIGHT_PURPLE + "KI-Assistent";
    private final Map<UUID, Inventory> openGuis = new ConcurrentHashMap<>();

    private static final int SLOT_CLEAR = 45;
    private static final int SLOT_ASK = 49;
    private static final int SLOT_INFO = 53;

    public AiGui(FlammoAI plugin, AiService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(player, 54, TITLE);
        fill(inv, player);
        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    public void refreshIfOpen(Player player) {
        Inventory inv = openGuis.get(player.getUniqueId());
        if (inv != null) {
            fill(inv, player);
        }
    }

    private void fill(Inventory inv, Player player) {
        inv.clear();

        ItemStack grayGlass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = grayGlass.getItemMeta();
        if (gm != null) {
            gm.setDisplayName(" ");
            grayGlass.setItemMeta(gm);
        }
        for (int i = 36; i < 54; i++) {
            inv.setItem(i, grayGlass);
        }

        List<AiService.Entry> history = service.getHistory(player);
        int maxSlots = 36;
        int startIndex = Math.max(0, history.size() - maxSlots);

        int slot = 0;
        for (int i = startIndex; i < history.size() && slot < maxSlots; i++) {
            AiService.Entry entry = history.get(i);
            
            ItemStack qItem = new ItemStack(Material.WRITABLE_BOOK);
            ItemMeta qm = qItem.getItemMeta();
            if (qm != null) {
                qm.setDisplayName(ChatColor.YELLOW + "Frage: " + ChatColor.WHITE + cut(entry.question, 30));
                qm.setLore(wrap(entry.question, 40));
                qItem.setItemMeta(qm);
            }
            inv.setItem(slot++, qItem);

            ItemStack aItem = new ItemStack(Material.BOOK);
            ItemMeta am = aItem.getItemMeta();
            if (am != null) {
                am.setDisplayName(ChatColor.GREEN + "Antwort:");
                am.setLore(wrap(entry.answer, 40));
                aItem.setItemMeta(am);
            }
            inv.setItem(slot++, aItem);
        }

        if (history.isEmpty()) {
            ItemStack info = new ItemStack(Material.PAPER);
            ItemMeta im = info.getItemMeta();
            if (im != null) {
                im.setDisplayName(ChatColor.GRAY + "Noch kein Verlauf");
                im.setLore(List.of(ChatColor.LIGHT_PURPLE + "Klicke unten auf das Namensschild,", ChatColor.LIGHT_PURPLE + "um deine erste Frage zu stellen."));
                info.setItemMeta(im);
            }
            inv.setItem(13, info);
        }

        inv.setItem(SLOT_CLEAR, createItem(Material.LAVA_BUCKET, ChatColor.RED + "Verlauf löschen", List.of(ChatColor.GRAY + "Klicken zum Leeren.")));
        inv.setItem(SLOT_ASK, createItem(Material.NAME_TAG, ChatColor.LIGHT_PURPLE + ChatColor.BOLD.toString() + "Frage stellen", List.of(ChatColor.GRAY + "Klicken, dann deine Frage in den", ChatColor.GRAY + "Chat schreiben.", ChatColor.YELLOW + "(\"abbrechen\" bricht ab)")));
        inv.setItem(SLOT_INFO, createItem(Material.BOOK, ChatColor.GOLD + "Haftungsausschluss", List.of(ChatColor.GRAY + "Hinweis: Die KI-Antworten werden automatisiert generiert.")));
    }

    private ItemStack createItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private List<String> wrap(String text, int lineSize) {
        List<String> list = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() + word.length() > lineSize) {
                list.add(ChatColor.WHITE + sb.toString().trim());
                sb = new StringBuilder();
            }
            sb.append(word).append(" ");
        }
        if (sb.length() > 0) {
            list.add(ChatColor.WHITE + sb.toString().trim());
        }
        return list;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals(TITLE)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        int raw = e.getRawSlot();
        if (raw == SLOT_ASK) {
            player.closeInventory();
            AiService.awaitingInput.add(player.getUniqueId());
            player.sendMessage(ChatColor.LIGHT_PURPLE + "[Flamo KI] " + ChatColor.WHITE + "Schreibe jetzt deine Frage in den Chat. (\"abbrechen\" zum Abbrechen)");
        } else if (raw == SLOT_CLEAR) {
            service.clearHistory(player);
            refreshIfOpen(player);
            player.sendMessage(ChatColor.GREEN + "Verlauf gelöscht.");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        if (AiService.awaitingInput.contains(player.getUniqueId())) {
            e.setCancelled(true);
            AiService.awaitingInput.remove(player.getUniqueId());
            String msg = e.getMessage().trim();

            if (msg.equalsIgnoreCase("abbrechen")) {
                player.sendMessage(ChatColor.RED + "Abgebrochen.");
                Bukkit.getScheduler().runTask(plugin, () -> open(player));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                service.ask(player, msg);
            });
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player) {
            openGuis.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        AiService.awaitingInput.remove(e.getPlayer().getUniqueId());
        openGuis.remove(e.getPlayer().getUniqueId());
    }
}

class AiService {
    private final FlammoAI plugin;
    private final OllamaClient client;
    private final Map<UUID, List<Entry>> histories = new ConcurrentHashMap<>();
    public static final Set<UUID> awaitingInput = ConcurrentHashMap.newKeySet();

    public AiService(FlammoAI plugin, OllamaClient client) {
        this.plugin = plugin;
        this.client = client;
    }

    public static class Entry {
        public String question;
        public String answer;
        public Entry(String q, String a) {
            this.question = q;
            this.answer = a;
        }
    }

    public List<Entry> getHistory(Player player) {
        return histories.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
    }

    public void clearHistory(Player player) {
        histories.remove(player.getUniqueId());
    }

    public void ask(Player player, String prompt) {
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[Flamo KI] " + ChatColor.YELLOW + "Denke nach...");
        
        CompletableFuture.runAsync(() -> {
            String response = client.chat(prompt);
            Bukkit.getScheduler().runTask(plugin, () -> {
                getHistory(player).add(new Entry(prompt, response));
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Flamo KI] " + ChatColor.GREEN + "Antwort erhalten! Öffne GUI...");
                if (plugin.getServer().getPluginManager().getPlugin("FlamoAI") != null) {
                    // GUI erneut öffnen
                    AiGui gui = new AiGui(plugin, this);
                    gui.open(player);
                }
            });
        });
    }
}

class OllamaClient {
    private final FlammoAI plugin;
    private final Gson gson = new Gson();

    public OllamaClient(FlammoAI plugin) {
        this.plugin = plugin;
    }

    public String chat(String prompt) {
        try {
            String urlStr = plugin.getConfig().getString("ollama-url", "http://localhost:11434/api/chat");
            String model = plugin.getConfig().getString("model", "qwen2.5:7b");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            JsonObject json = new JsonObject();
            json.addProperty("model", model);
            json.addProperty("stream", false);

            com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "user");
            msg.addProperty("content", prompt);
            messages.add(msg);
            json.add("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                return "Fehler: HTTP " + code;
            }

            try (var reader = new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject res = gson.fromJson(reader, JsonObject.class);
                if (res.has("message")) {
                    return res.getAsJsonObject("message").get("content").getAsString();
                }
            }
        } catch (Exception e) {
            return "Verbindungsfehler: " + e.getMessage();
        }
        return "Keine Antwort erhalten.";
    }
}
