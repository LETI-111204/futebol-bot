package com.caria.futebol;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BotMain extends ListenerAdapter {

    // =========================
    // CONFIG: Players & Ranks
    // =========================

    /** Base player list (the order used during attendance check). */
    private static final List<String> PLAYERS = List.of(
            "Caria",
            "Tiago",
            "Filipe",
            "Francisco",
            "Gui",
            "João",
            "Miguel",
            "Pedro",
            "Rodrigo",
            "Salvador",
            "Pipa",
            "André",
            "Fontes",
            "Vasco"
    );

    /**
     * Player ranks.
     * Any missing player defaults to 5.0.
     */
    private static final Map<String, Double> RANK = Map.ofEntries(
            Map.entry("Caria", 71.9),
            Map.entry("Tiago", 51.9),
            Map.entry("Filipe", 58.1),
            Map.entry("Francisco", 73.8),
            Map.entry("Gui", 66.9),
            Map.entry("João", 50.1),
            Map.entry("Miguel", 67.3),
            Map.entry("Pedro", 69.3),
            Map.entry("Rodrigo", 56.4),
            Map.entry("Salvador", 80.0),
            Map.entry("Pipa", 49.3),
            Map.entry("André", 55.1),
            Map.entry("Fontes", 41.6),
            Map.entry("Vasco", 73.9)
    );

    // =========================
    // Runtime State
    // =========================

    /**
     * Active attendance sessions:
     * key = "userId:channelId" (so a user can run one session per channel).
     */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Last confirmed list per channel.
     * Used by /teams and /remake.
     * key = channelId
     */
    private static final Map<String, List<String>> LAST_CONFIRMED_BY_CHANNEL = new ConcurrentHashMap<>();

    // Fixed button IDs
    private static final String BTN_YES = "att:yes";
    private static final String BTN_NO  = "att:no";

    // =========================
    // Bot entry point
    // =========================

    public static void main(String[] args) throws Exception {
        // IMPORTANT: set your bot token as environment variable DISCORD_TOKEN
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing environment variable: DISCORD_TOKEN");
        }

        // Build JDA and attach this listener
        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(new BotMain())
                .build();

        // Wait until Discord connection is ready
        jda.awaitReady();

        // Register slash commands (GLOBAL). It can take a few minutes to appear.
        jda.updateCommands().addCommands(
                Commands.slash("futebol", "Start attendance check (fixed panel with buttons)"),
                Commands.slash("teams", "Generate optimal fair 5v5 teams using ranks"),
                Commands.slash("remake", "Remake teams (random, may be less fair)")
        ).queue();

        System.out.println("✅ Bot is online and commands were submitted!");
    }

    // =========================
    // Slash commands
    // =========================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "futebol" -> startAttendance(event);
            case "teams"   -> generateFairTeams(event);
            case "remake"  -> generateRandomTeams(event);
            default -> { /* ignore */ }
        }
    }

    /**
     * /futebol: creates ONE message panel. Buttons stay in the same place.
     * Each click edits that same message to the next player.
     */
    private void startAttendance(SlashCommandInteractionEvent event) {
        String key = sessionKey(event.getUser().getId(), event.getChannel().getId());

        if (SESSIONS.containsKey(key)) {
            event.reply("There is already an attendance check running in this channel for you.")
                    .setEphemeral(true).queue();
            return;
        }

        Session s = new Session(event.getUser().getId(), event.getChannel().getId(), PLAYERS);
        SESSIONS.put(key, s);

        // Send ONE fixed panel message and store its messageId.
        event.reply(attendancePrompt(s))
                .addActionRow(
                        Button.success(BTN_YES, "Yes"),
                        Button.danger(BTN_NO, "No")
                )
                .queue(hook -> hook.retrieveOriginal().queue(msg -> s.panelMessageId = msg.getIdLong()));
    }

    /**
     * /teams: OPTIMAL fixed 5v5 (10 players).
     * - If <10 confirmed: warns
     * - If =10: optimal balance and output
     * - If >10: pick best 10 (EXACT), list substitutes
     */
    private void generateFairTeams(SlashCommandInteractionEvent event) {
        String channelId = event.getChannel().getId();
        List<String> confirmed = LAST_CONFIRMED_BY_CHANNEL.get(channelId);

        if (confirmed == null || confirmed.isEmpty()) {
            event.reply("No confirmed list found for this channel yet. Run **/futebol** first.")
                    .setEphemeral(true).queue();
            return;
        }

        if (confirmed.size() < 10) {
            event.reply("⚠️ You have **" + confirmed.size() + "** confirmed players. You need **10** for fixed 5v5.")
                    .queue();
            return;
        }

        Pick10Result pick = pickBest10ForBalanceExact(confirmed);
        Teams teams = splitIntoOptimal5v5(pick.tenChosen);

        event.reply(formatTeamsMessage(
                "🎯 **Optimal Fair Teams (Fixed 5v5)**",
                teams,
                pick.substitutes,
                pick.bestDiffFound,
                false
        )).queue();
    }

    /**
     * /remake: random fixed 5v5 (10 players).
     * - If <10 confirmed: warns
     * - If =10: random split
     * - If >10: random pick 10 + random split, list substitutes
     */
    private void generateRandomTeams(SlashCommandInteractionEvent event) {
        String channelId = event.getChannel().getId();
        List<String> confirmed = LAST_CONFIRMED_BY_CHANNEL.get(channelId);

        if (confirmed == null || confirmed.isEmpty()) {
            event.reply("No confirmed list found for this channel yet. Run **/futebol** first.")
                    .setEphemeral(true).queue();
            return;
        }

        if (confirmed.size() < 10) {
            event.reply("⚠️ You have **" + confirmed.size() + "** confirmed players. You need **10** for fixed 5v5.")
                    .queue();
            return;
        }

        // Randomly pick 10 if more than 10 confirmed
        List<String> pool = new ArrayList<>(confirmed);
        Collections.shuffle(pool);

        List<String> ten = new ArrayList<>(pool.subList(0, 10));

        // Remaining become substitutes
        Set<String> tenSet = new HashSet<>(ten);
        List<String> substitutes = new ArrayList<>();
        for (String n : confirmed) {
            if (!tenSet.contains(n)) substitutes.add(n);
        }

        // Random split
        Teams teams = splitRandom5v5(ten);

        event.reply(formatTeamsMessage(
                "🔁 **Remake Teams (Random 5v5)**",
                teams,
                substitutes,
                -1.0,
                true
        )).queue();
    }

    // =========================
    // Button interactions
    // =========================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals(BTN_YES) && !id.equals(BTN_NO)) return;

        String key = sessionKey(event.getUser().getId(), event.getChannel().getId());
        Session s = SESSIONS.get(key);

        if (s == null) {
            event.reply("No active attendance check here. Run **/futebol** to start one.")
                    .setEphemeral(true).queue();
            return;
        }

        // Only the user who started the session can click
        if (!event.getUser().getId().equals(s.userId)) {
            event.reply("Only the person who started the attendance check can answer here.")
                    .setEphemeral(true).queue();
            return;
        }

        // Ensure clicks happen on the correct panel message
        if (s.panelMessageId == null || event.getMessageIdLong() != s.panelMessageId) {
            event.reply("This panel is not the active one. Run **/futebol** again if needed.")
                    .setEphemeral(true).queue();
            return;
        }

        String current = s.currentPlayer();
        if (current == null) {
            event.reply("This attendance check is already finished.")
                    .setEphemeral(true).queue();
            return;
        }

        boolean yes = id.equals(BTN_YES);
        if (yes) s.confirmed.add(current);
        else s.notGoing.add(current);

        s.advance();

        if (s.isFinished()) {
            // Store confirmed list for /teams and /remake
            SESSIONS.remove(key);
            LAST_CONFIRMED_BY_CHANNEL.put(s.channelId, new ArrayList<>(s.confirmed));

            // Edit same message to summary and remove buttons
            event.editMessage(attendanceSummary(s))
                    .setComponents()
                    .queue();
        } else {
            // Edit same message to next player and keep buttons fixed
            event.editMessage(attendancePrompt(s))
                    .setActionRow(
                            Button.success(BTN_YES, "Yes"),
                            Button.danger(BTN_NO, "No")
                    )
                    .queue();
        }
    }

    // =========================
    // Attendance UI text
    // =========================

    private static String attendancePrompt(Session s) {
        return "⚽ **Futebol Attendance**\n\n" +
                "**" + s.currentPlayer() + "** — are you in?\n" +
                "_(Click a button. This panel will move to the next player.)_";
    }

    private static String attendanceSummary(Session s) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ **Attendance Finished**\n\n");

        sb.append("**Confirmed (").append(s.confirmed.size()).append("):**\n");
        for (String n : s.confirmed) sb.append("- ").append(n).append("\n");

        sb.append("\n**Not going (").append(s.notGoing.size()).append("):**\n");
        for (String n : s.notGoing) sb.append("- ").append(n).append("\n");

        sb.append("\nRun **/teams** for optimal fair teams or **/remake** to reshuffle randomly.");
        return sb.toString();
    }

    // =========================
    // Team generation logic
    // =========================

    /** Container for two teams and their rank sums. */
    private static class Teams {
        List<String> teamA = new ArrayList<>();
        List<String> teamB = new ArrayList<>();
        double sumA = 0.0;
        double sumB = 0.0;
    }

    /** Pick result: chosen 10 + substitutes + best diff found while searching. */
    private static class Pick10Result {
        List<String> tenChosen;
        List<String> substitutes;
        double bestDiffFound;

        Pick10Result(List<String> tenChosen, List<String> substitutes, double bestDiffFound) {
            this.tenChosen = tenChosen;
            this.substitutes = substitutes;
            this.bestDiffFound = bestDiffFound;
        }
    }

    /**
     * EXACT best-10 selection:
     * - if exactly 10: just split optimal
     * - if 11..20: enumerate ALL subsets of size 10 (bitmask), for each do optimal 5v5, pick best diff
     * - if >20: fallback to random search (still uses optimal split for each random 10)
     */
    private static Pick10Result pickBest10ForBalanceExact(List<String> confirmed) {
        List<String> list = new ArrayList<>(confirmed);

        if (list.size() == 10) {
            Teams t = splitIntoOptimal5v5(list);
            double diff = Math.abs(t.sumA - t.sumB);
            return new Pick10Result(list, List.of(), diff);
        }

        int n = list.size();
        if (n < 10) throw new IllegalArgumentException("Need at least 10 players");

        double bestDiff = Double.MAX_VALUE;
        List<String> bestTen = null;

        if (n <= 20) {
            int limit = 1 << n;

            for (int mask = 0; mask < limit; mask++) {
                if (Integer.bitCount(mask) != 10) continue;

                List<String> ten = new ArrayList<>(10);
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) ten.add(list.get(i));
                }

                Teams t = splitIntoOptimal5v5(ten);
                double diff = Math.abs(t.sumA - t.sumB);

                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestTen = ten;
                    if (bestDiff == 0.0) break;
                }
            }
        } else {
            // Fallback random search for very large groups
            Random rnd = new Random();
            for (int i = 0; i < 8000; i++) {
                Collections.shuffle(list, rnd);
                List<String> ten = new ArrayList<>(list.subList(0, 10));

                Teams t = splitIntoOptimal5v5(ten);
                double diff = Math.abs(t.sumA - t.sumB);

                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestTen = ten;
                    if (bestDiff < 0.01) break;
                }
            }
        }

        if (bestTen == null) bestTen = new ArrayList<>(list.subList(0, 10));

        Set<String> setTen = new HashSet<>(bestTen);
        List<String> subs = new ArrayList<>();
        for (String p : confirmed) {
            if (!setTen.contains(p)) subs.add(p);
        }

        return new Pick10Result(bestTen, subs, bestDiff);
    }

    /**
     * OPTIMAL 5v5 split for exactly 10 players.
     * Enumerates all C(10,5)=252 combinations and picks the minimum |sumA - sumB|.
     */
    private static Teams splitIntoOptimal5v5(List<String> tenPlayers) {
        if (tenPlayers.size() != 10) throw new IllegalArgumentException("Expected exactly 10 players");

        List<String> list = new ArrayList<>(tenPlayers);

        double total = 0.0;
        for (String p : list) total += rankOf(p);

        Teams best = null;
        double bestDiff = Double.MAX_VALUE;

        int n = 10;
        int limit = 1 << n;

        for (int mask = 0; mask < limit; mask++) {
            if (Integer.bitCount(mask) != 5) continue;

            double sumA = 0.0;
            List<String> teamA = new ArrayList<>(5);
            List<String> teamB = new ArrayList<>(5);

            for (int i = 0; i < n; i++) {
                String name = list.get(i);
                if ((mask & (1 << i)) != 0) {
                    teamA.add(name);
                    sumA += rankOf(name);
                } else {
                    teamB.add(name);
                }
            }

            double sumB = total - sumA;
            double diff = Math.abs(sumA - sumB);

            if (diff < bestDiff) {
                bestDiff = diff;
                Teams t = new Teams();
                t.teamA = teamA;
                t.teamB = teamB;
                t.sumA = sumA;
                t.sumB = sumB;
                best = t;

                if (bestDiff == 0.0) break;
            }
        }

        // Pequeno "baralhar" opcional: se quiseres variar ordem interna sem alterar equipas,
        // podes baralhar teamA/teamB aqui.
        return best;
    }

    /** Random split (fixed 5v5). Not optimized for fairness. */
    private static Teams splitRandom5v5(List<String> tenPlayers) {
        List<String> list = new ArrayList<>(tenPlayers);
        Collections.shuffle(list);

        Teams t = new Teams();
        for (int i = 0; i < list.size(); i++) {
            String name = list.get(i);
            double r = rankOf(name);

            if (i < 5) {
                t.teamA.add(name);
                t.sumA += r;
            } else {
                t.teamB.add(name);
                t.sumB += r;
            }
        }
        return t;
    }

    private static double rankOf(String name) {
        return RANK.getOrDefault(name, 5.0);
    }

    private static String formatTeamsMessage(String title, Teams t, List<String> substitutes, double bestDiffFound, boolean isRemake) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        sb.append("**Team A (").append(String.format(Locale.US, "%.1f", t.sumA)).append("):**\n");
        for (String n : t.teamA) {
            sb.append("- ").append(n)
                    .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
        }

        sb.append("\n**Team B (").append(String.format(Locale.US, "%.1f", t.sumB)).append("):**\n");
        for (String n : t.teamB) {
            sb.append("- ").append(n)
                    .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
        }

        sb.append("\n**Difference (A vs B):** ")
                .append(String.format(Locale.US, "%.2f", Math.abs(t.sumA - t.sumB)));

        if (!substitutes.isEmpty()) {
            sb.append("\n\n**Substitutes (").append(substitutes.size()).append("):**\n");
            for (String n : substitutes) {
                sb.append("- ").append(n)
                        .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
            }

            if (!isRemake && bestDiffFound >= 0) {
                sb.append("\n_(From more than 10 confirmed, I picked the 10 that produced the best balance. Best diff found: ")
                        .append(String.format(Locale.US, "%.2f", bestDiffFound)).append(")_");
            } else {
                sb.append("\n_(Remake mode: random pick & random split — may be less fair.)_");
            }
        } else if (isRemake) {
            sb.append("\n_(Remake mode: random split — may be less fair.)_");
        }

        return sb.toString();
    }

    // =========================
    // Helpers / Session model
    // =========================

    private static String sessionKey(String userId, String channelId) {
        return userId + ":" + channelId;
    }

    /** Represents a running attendance check session. */
    private static class Session {
        final String userId;
        final String channelId;
        final List<String> players;

        int index = 0;

        // The fixed panel message ID (the one we keep editing)
        Long panelMessageId = null;

        final List<String> confirmed = new ArrayList<>();
        final List<String> notGoing = new ArrayList<>();

        Session(String userId, String channelId, List<String> players) {
            this.userId = userId;
            this.channelId = channelId;
            this.players = players;
        }

        String currentPlayer() {
            if (index < 0 || index >= players.size()) return null;
            return players.get(index);
        }

        void advance() {
            index++;
        }

        boolean isFinished() {
            return index >= players.size();
        }
    }
}
