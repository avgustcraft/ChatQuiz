package lv.chatquiz;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
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

    private record Reward(Material material, int min, int max) {}

    private final List<Question> questions = new ArrayList<>();
    private final List<Reward> rewards = new ArrayList<>();
    private final AtomicReference<Question> active = new AtomicReference<>();
    private final List<BukkitTask> hintTasks = new ArrayList<>();

    private Question lastQuestion;
    private BukkitTask nextTask;
    private BukkitTask timeoutTask;

    /** 0 = parastais nejaušais intervāls no config; citādi fiksēts intervāls minūtēs. */
    private int autoMinutes = 0;

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

        rewards.clear();
        int defMin = Math.max(1, cfg.getInt("settings.min-amount", 1));
        int defMax = Math.max(defMin, cfg.getInt("settings.max-amount", 6));
        if (cfg.getBoolean("settings.reward-any-item", false)) {
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir() && !m.isLegacy()) rewards.add(new Reward(m, defMin, defMax));
            }
        } else {
            // formāts: MATERIAL  vai  MATERIAL:min-max  vai  MATERIAL:daudzums
            for (String entry : cfg.getStringList("reward-materials")) {
                String name = entry.trim();
                int min = defMin;
                int max = defMax;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    String range = name.substring(colon + 1).trim();
                    name = name.substring(0, colon).trim();
                    try {
                        int dash = range.indexOf('-');
                        if (dash > 0) {
                            min = Integer.parseInt(range.substring(0, dash).trim());
                            max = Integer.parseInt(range.substring(dash + 1).trim());
                        } else {
                            min = Integer.parseInt(range);
                            max = min;
                        }
                    } catch (NumberFormatException e) {
                        getLogger().warning("Nepareizs daudzums konfigurācijā: " + entry);
                        min = defMin;
                        max = defMax;
                    }
                }
                min = Math.max(1, min);
                max = Math.max(min, max);

                Material m = Material.matchMaterial(name);
                if (m != null && m.isItem() && !m.isAir()) {
                    rewards.add(new Reward(m, min, max));
                } else {
                    getLogger().warning("Nezināms materiāls konfigurācijā: " + name);
                }
            }
        }
        if (rewards.isEmpty()) rewards.add(new Reward(Material.DIAMOND, defMin, defMax));
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

    private void cancelHint() {
        for (BukkitTask t : hintTasks) t.cancel();
        hintTasks.clear();
    }

    private void cancelTasks() {
        if (nextTask != null) nextTask.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
        cancelHint();
        nextTask = null;
        timeoutTask = null;
    }

    private void scheduleNext() {
        if (nextTask != null) nextTask.cancel();
        long seconds;
        if (autoMinutes > 0) {
            seconds = autoMinutes * 60L;
        } else {
            int min = Math.max(1, getConfig().getInt("settings.min-interval-minutes", 5));
            int max = Math.max(min, getConfig().getInt("settings.max-interval-minutes", 10));
            seconds = ThreadLocalRandom.current().nextLong(min * 60L, max * 60L + 1);
        }
        nextTask = Bukkit.getScheduler().runTaskLater(this, () -> askQuestion(true), seconds * 20L);
    }

    /**
     * @param timed true = mājieni un beigās pareizā atbilde; false = bez beigām (tikai kamēr kāds atbild vai admins izlaiž)
     */
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

        int hint1 = Math.max(1, getConfig().getInt("settings.hint-delay-seconds", 10));
        int hint2 = Math.max(1, getConfig().getInt("settings.second-hint-delay-seconds", 20));
        int end = Math.max(1, getConfig().getInt("settings.end-delay-seconds", 20));

        active.set(q);
        scheduleHints(q, hint1, hint2);

        if (!timed) {
            Bukkit.broadcast(msg("question-no-timer",
                    Placeholder.unparsed("question", q.text())));
            playQuestionSound();
            return true;
        }

        int total = hint1 + hint2 + end;
        Bukkit.broadcast(msg("question",
                Placeholder.unparsed("question", q.text()),
                Placeholder.unparsed("seconds", String.valueOf(total))));
        playQuestionSound();

        final Question asked = q;
        timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (active.compareAndSet(asked, null)) {
                cancelHint();
                Bukkit.broadcast(msg("timeout",
                        Placeholder.unparsed("answer", asked.answers().get(0))));
                scheduleNext();
            }
        }, total * 20L);
        return true;
    }

    /** Nošu bloka skaņa visiem spēlētājiem, kad parādās jautājums. */
    private void playQuestionSound() {
        String name = getConfig().getString("settings.question-sound", "block.note_block.pling");
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return;
        try {
            float volume = (float) getConfig().getDouble("settings.question-sound-volume", 1.0);
            float pitch = (float) getConfig().getDouble("settings.question-sound-pitch", 2.0);
            Bukkit.getServer().playSound(Sound.sound(
                    Key.key(name.trim().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, volume, pitch));
        } catch (RuntimeException e) {
            getLogger().warning("Nepareizs skaņas nosaukums konfigurācijā: " + name);
        }
    }

    private void scheduleHints(Question q, int hint1Seconds, int hint2Seconds) {
        cancelHint();
        hintTasks.add(Bukkit.getScheduler().runTaskLater(this, () -> {
            if (active.get() == q) {
                Bukkit.broadcast(msg("hint",
                        Placeholder.unparsed("hint", buildHint(q.answers().get(0), 1))));
            }
        }, hint1Seconds * 20L));
        hintTasks.add(Bukkit.getScheduler().runTaskLater(this, () -> {
            if (active.get() == q) {
                Bukkit.broadcast(msg("hint2",
                        Placeholder.unparsed("hint", buildHint(q.answers().get(0), 2))));
            }
        }, (hint1Seconds + hint2Seconds) * 20L));
    }

    /**
     * 1. mājiens: katra vārda pirmais burts.
     * 2. mājiens: pirmais burts + katrs otrais burts.
     */
    static String buildHint(String answer, int level) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (char c : answer.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                boolean show = pos == 0 || (level >= 2 && pos % 2 == 0);
                sb.append(show ? c : '_');
                pos++;
            } else {
                sb.append(c);
                if (c == ' ') pos = 0;
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

        Reward reward = rewards.get(ThreadLocalRandom.current().nextInt(rewards.size()));
        Material material = reward.material();
        int amount = ThreadLocalRandom.current().nextInt(reward.min(), reward.max() + 1);

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
            case "auto" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
                    autoMinutes = 0;
                    if (active.get() == null) scheduleNext();
                    sender.sendMessage(msgNoPrefix("auto-off"));
                } else {
                    int minutes = 5;
                    if (args.length >= 2) {
                        try {
                            minutes = Integer.parseInt(args[1]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(msgNoPrefix("usage"));
                            return true;
                        }
                    }
                    if (minutes < 1 || minutes > 1440) {
                        sender.sendMessage(msgNoPrefix("usage"));
                        return true;
                    }
                    autoMinutes = minutes;
                    sender.sendMessage(msgNoPrefix("auto-on",
                            Placeholder.unparsed("minutes", String.valueOf(minutes))));
                    if (active.get() == null) {
                        if (nextTask != null) nextTask.cancel();
                        askQuestion(true);
                    }
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
        if (!sender.hasPermission("chatquiz.admin")) return List.of();
        if (args.length == 1) {
            return List.of("ask", "start", "auto", "skip", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
            return List.of("5", "10", "off");
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
