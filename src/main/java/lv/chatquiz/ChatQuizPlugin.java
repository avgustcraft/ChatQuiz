package lv.chatquiz;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatQuizPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Question(String text, List<String> answers, List<String> normalizedAnswers) {}

    private final List<Question> questions = new ArrayList<>();
    private final List<Material> rewardMaterials = new ArrayList<>();
    private final AtomicReference<Question> active = new AtomicReference<>();

    private Question lastQuestion;
    private BukkitTask nextTask;
    private BukkitTask timeoutTask;
    private BukkitTask hintTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        scheduleNext();
        getLogger().info("ChatQuiz ieslēgts. Jautājumu skaits: " + questions.size());
    }

    @Override
    public void onDisable() {
        cancelTasks();
        active.set(null);
    }

    // ---------------------------------------------------------------- config

    private void loadAll() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        questions.clear();
        for (Map<?, ?> map : cfg.getMapList("questions")) {
            Object q = map.get("q");
            Object a = map.get("a");
            if (q == null || a == null) continue;

            List<String> answers = new ArrayList<>();
            if (a instanceof List<?> list) {
                for (Object o : list) answers.add(String.valueOf(o));
            } else {
                answers.add(String.valueOf(a));
            }
            List<String> normalized = answers.stream().map(ChatQuizPlugin::normalize).toList();
            questions.add(new Question(String.valueOf(q), answers, normalized));
        }

        rewardMaterials.clear();
        if (cfg.getBoolean("settings.reward-any-item", false)) {
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir() && !m.isLegacy()) rewardMaterials.add(m);
            }
        } else {
            for (String name : cfg.getStringList("reward-materials")) {
                Material m = Material.matchMaterial(name);
                if (m != null && m.isItem() && !m.isAir()) {
                    rewardMaterials.add(m);
                } else {
                    getLogger().warning("Nezināms materiāls konfigurācijā: " + name);
                }
            }
        }
        if (rewardMaterials.isEmpty()) rewardMaterials.add(Material.DIAMOND);
    }

    private Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        String body = getConfig().getString("messages." + key, key);
        return MM.deserialize(prefix + body, resolvers);
    }

    private Component msgNoPrefix(String key, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString("messages." + key, key), resolvers);
    }

    // ------------------------------------------------------------ scheduling

    private void cancelTasks() {
        if (nextTask != null) nextTask.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
        cancelHint();
        nextTask = null;
        timeoutTask = null;
    }

    private void cancelHint() {
        if (hintTask != null) hintTask.cancel();
        hintTask = null;
    }

    private void scheduleNext() {
        if (nextTask != null) nextTask.cancel();
        int min = Math.max(1, getConfig().getInt("settings.min-interval-minutes", 5));
        int max = Math.max(min, getConfig().getInt("settings.max-interval-minutes", 10));
        long seconds = ThreadLocalRandom.current().nextLong(min * 60L, max * 60L + 1);
        nextTask = Bukkit.getScheduler().runTaskLater(this, () -> askQuestion(true), seconds * 20L);
    }

    private boolean askQuestion(boolean timed) {
        if (active.get() != null || questions.isEmpty()) {
            scheduleNext();
            return false;
        }
        if (Bukkit.getOnlinePlayers().size() < getConfig().getInt("settings.min-players", 1)) {
            scheduleNext();
            return false;
        }

        Question q;
        do {
            q = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
        } while (questions.size() > 1 && q == lastQuestion);
        lastQuestion = q;

        int seconds = Math.max(5, getConfig().getInt("settings.answer-time-seconds", 60));
        active.set(q);
        scheduleHint(q);

        if (!timed) {
            // jautājums bez taimera: gaida, līdz kāds atbild vai admins izlaiž
            Bukkit.broadcast(msg("question-no-timer",
                    Placeholder.unparsed("question", q.text())));
            return true;
        }

        Bukkit.broadcast(msg("question",
                Placeholder.unparsed("question", q.text()),
                Placeholder.unparsed("seconds", String.valueOf(seconds))));

        final Question asked = q;
        timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (active.compareAndSet(asked, null)) {
                Bukkit.broadcast(msg("timeout",
                        Placeholder.unparsed("answer", asked.answers().get(0))));
                scheduleNext();
            }
        }, seconds * 20L);
        return true;
    }

    private void scheduleHint(Question q) {
        cancelHint();
        int delay = getConfig().getInt("settings.hint-delay-seconds", 10);
        if (delay <= 0) return;
        hintTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (active.get() == q) {
                Bukkit.broadcast(msg("hint",
                        Placeholder.unparsed("hint", buildHint(q.answers().get(0)))));
            }
        }, delay * 20L);
    }

    /** Parāda katra vārda pirmo burtu, pārējie burti kļūst par _ */
    static String buildHint(String answer) {
        StringBuilder sb = new StringBuilder();
        boolean startOfWord = true;
        for (char c : answer.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(startOfWord ? c : '_');
                startOfWord = false;
            } else {
                sb.append(c);
                startOfWord = (c == ' ');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Question q = active.get();
        if (q == null) return;

        String text = normalize(PlainTextComponentSerializer.plainText().serialize(event.message()));
        if (!q.normalizedAnswers().contains(text)) return;

        // tikai pirmais pareizais atbildētājs uzvar
        if (!active.compareAndSet(q, null)) return;

        if (getConfig().getBoolean("settings.hide-correct-answer", true)) {
            event.setCancelled(true);
        }

        Player player = event.getPlayer();
        // balvas izsniegšana jādara galvenajā pavedienā
        Bukkit.getScheduler().runTask(this, () -> win(player, q));
    }

    private void win(Player player, Question q) {
        if (timeoutTask != null) timeoutTask.cancel();
        cancelHint();

        int minA = Math.max(1, getConfig().getInt("settings.min-amount", 1));
        int maxA = Math.max(minA, getConfig().getInt("settings.max-amount", 6));
        int amount = ThreadLocalRandom.current().nextInt(minA, maxA + 1);
        Material material = rewardMaterials.get(ThreadLocalRandom.current().nextInt(rewardMaterials.size()));

        Component itemName = Component.translatable(material.translationKey());

        if (player.isOnline()) {
            var leftovers = player.getInventory().addItem(new ItemStack(material, amount));
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(it ->
                        player.getWorld().dropItemNaturally(player.getLocation(), it));
                player.sendMessage(msg("inventory-full"));
            }
            player.sendMessage(msg("reward-received",
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.component("item", itemName)));
        }

        Bukkit.broadcast(msg("winner",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.component("item", itemName),
                Placeholder.unparsed("answer", q.answers().get(0))));

        scheduleNext();
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chatquiz.admin")) {
            sender.sendMessage(msgNoPrefix("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(msgNoPrefix("usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ask" -> {
                if (active.get() != null) {
                    sender.sendMessage(msgNoPrefix("already-active"));
                } else {
                    if (nextTask != null) nextTask.cancel();
                    if (askQuestion(true)) sender.sendMessage(msgNoPrefix("asked"));
                }
            }
            case "start" -> {
                if (active.get() != null) {
                    sender.sendMessage(msgNoPrefix("already-active"));
                } else {
                    if (nextTask != null) nextTask.cancel();
                    if (askQuestion(false)) sender.sendMessage(msgNoPrefix("asked"));
                }
            }
            case "skip" -> {
                Question q = active.get();
                if (q != null && active.compareAndSet(q, null)) {
                    if (timeoutTask != null) timeoutTask.cancel();
                    cancelHint();
                    Bukkit.broadcast(msg("timeout", Placeholder.unparsed("answer", q.answers().get(0))));
                    scheduleNext();
                    sender.sendMessage(msgNoPrefix("skipped"));
                } else {
                    sender.sendMessage(msgNoPrefix("no-active"));
                }
            }
            case "reload" -> {
                cancelTasks();
                active.set(null);
                loadAll();
                scheduleNext();
                sender.sendMessage(msgNoPrefix("reloaded",
                        Placeholder.unparsed("count", String.valueOf(questions.size()))));
            }
            default -> sender.sendMessage(msgNoPrefix("usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("chatquiz.admin")) {
            return List.of("ask", "start", "skip", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    // ---------------------------------------------------------------- helper

    /** Mazie burti, bez garumzīmēm/mīkstinājuma zīmēm, bez liekiem simboliem. */
    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}\\s.]", "")
                .replaceAll("\\s+", " ")
                .trim();
        // "18.novembris" un "18. novembris" jāuzskata par vienādiem
        return n.replaceAll("\\.\\s*", ". ").replaceAll("\\.+$", "").trim();
    }
}
