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

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> LAST_CONFIRMED_BY_CHANNEL = new ConcurrentHashMap<>();

    private static final String BTN_YES = "att:yes";
    private static final String BTN_NO  = "att:no";

    // =========================
    // Bot entry point
    // =========================

    public static void main(String[] args) throws Exception {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing environment variable: DISCORD_TOKEN");
        }

        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(new BotMain())
                .build();

        jda.awaitReady();

        jda.updateCommands().addCommands(
                Commands.slash("futebol", "Start attendance check (fixed panel with buttons)"),
                Commands.slash("teams", "Best possible optimal 5v5 (requires exactly 10 confirmed)"),
                Commands.slash("nextbest", "Second-best possible optimal 5v5 (requires exactly 10 confirmed)")
        ).queue();

        System.out.println("✅ Bot is online and commands were submitted!");
    }

    // =========================
    // Slash commands
    // =========================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "futebol"  -> startAttendance(event);
            case "teams"    -> generateBestTeams(event);
            case "nextbest" -> generateNextBestTeams(event);
            default -> { /* ignore */ }
        }
    }

    private void startAttendance(SlashCommandInteractionEvent event) {
        String key = sessionKey(event.getUser().getId(), event.getChannel().getId());

        if (SESSIONS.containsKey(key)) {
            event.reply("There is already an attendance check running in this channel for you.")
                    .setEphemeral(true).queue();
            return;
        }

        Session s = new Session(event.getUser().getId(), event.getChannel().getId(), PLAYERS);
        SESSIONS.put(key, s);

        event.reply(attendancePrompt(s))
                .addActionRow(
                        Button.success(BTN_YES, "Yes"),
                        Button.danger(BTN_NO, "No")
                )
                .queue(hook -> hook.retrieveOriginal().queue(msg -> s.panelMessageId = msg.getIdLong()));
    }

    /**
     * /teams: BEST optimal fair 5v5 (EXACT).
     * Requires exactly 10 confirmed players.
     */
    private void generateBestTeams(SlashCommandInteractionEvent event) {
        List<String> confirmed = getConfirmed10OrReply(event);
        if (confirmed == null) return;

        RankedTeams best = getRankedTeams(confirmed, 0);
        if (best == null) {
            event.reply("Could not compute teams (unexpected).").setEphemeral(true).queue();
            return;
        }

        event.reply(formatTeamsMessage("🎯 **Best Teams (Optimal 5v5)**", best.teams, best.diff)).queue();
    }

    /**
     * /nextbest: SECOND-BEST optimal fair 5v5 (EXACT).
     * Requires exactly 10 confirmed players.
     */
    private void generateNextBestTeams(SlashCommandInteractionEvent event) {
        List<String> confirmed = getConfirmed10OrReply(event);
        if (confirmed == null) return;

        RankedTeams second = getRankedTeams(confirmed, 1);
        if (second == null) {
            event.reply("There is no 2nd best split available (very unlikely, but possible if input is invalid).")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply(formatTeamsMessage("🥈 **Next Best Teams (2nd Optimal 5v5)**", second.teams, second.diff)).queue();
    }

    /** Helper: fetch confirmed list and validate exactly 10, otherwise reply and return null. */
    private List<String> getConfirmed10OrReply(SlashCommandInteractionEvent event) {
        String channelId = event.getChannel().getId();
        List<String> confirmed = LAST_CONFIRMED_BY_CHANNEL.get(channelId);

        if (confirmed == null || confirmed.isEmpty()) {
            event.reply("No confirmed list found for this channel yet. Run **/futebol** first.")
                    .setEphemeral(true).queue();
            return null;
        }

        if (confirmed.size() != 10) {
            event.reply("⚠️ You have **" + confirmed.size() + "** confirmed players. You need **exactly 10** for 5v5.")
                    .queue();
            return null;
        }

        return new ArrayList<>(confirmed);
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

        if (!event.getUser().getId().equals(s.userId)) {
            event.reply("Only the person who started the attendance check can answer here.")
                    .setEphemeral(true).queue();
            return;
        }

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
            SESSIONS.remove(key);
            LAST_CONFIRMED_BY_CHANNEL.put(s.channelId, new ArrayList<>(s.confirmed));

            event.editMessage(attendanceSummary(s))
                    .setComponents()
                    .queue();
        } else {
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

        sb.append("\nRun **/teams** for best teams or **/nextbest** for the second-best split.");
        return sb.toString();
    }

    // =========================
    // Team generation logic (Optimal + NextBest)
    // =========================

    private static class Teams {
        List<String> teamA = new ArrayList<>();
        List<String> teamB = new ArrayList<>();
        double sumA = 0.0;
        double sumB = 0.0;
    }

    private static class RankedTeams {
        Teams teams;
        double diff;
        int mask; // for stable tie-breaking/debug

        RankedTeams(Teams teams, double diff, int mask) {
            this.teams = teams;
            this.diff = diff;
            this.mask = mask;
        }
    }

    /**
     * Returns the k-th best split (k=0 best, k=1 second-best, ...).
     * - Enumerates all unique 5v5 splits (removes mirror duplicates A/B swap)
     * - Sorts by diff asc, then by mask asc for stability
     */
    private static RankedTeams getRankedTeams(List<String> tenPlayers, int k) {
        if (tenPlayers.size() != 10) throw new IllegalArgumentException("Expected exactly 10 players");

        List<String> list = new ArrayList<>(tenPlayers);

        double total = 0.0;
        for (String p : list) total += rankOf(p);

        int n = 10;
        int limit = 1 << n;

        List<RankedTeams> all = new ArrayList<>();

        for (int mask = 0; mask < limit; mask++) {
            if (Integer.bitCount(mask) != 5) continue;

            int complement = (~mask) & (limit - 1);

            // Remove mirrored duplicates: only keep canonical representation
            // (e.g., keep the smaller mask of the pair)
            if (mask > complement) continue;

            Teams t = teamsFromMask(list, mask, total);
            double diff = Math.abs(t.sumA - t.sumB);

            all.add(new RankedTeams(t, diff, mask));
        }

        all.sort((a, b) -> {
            int c = Double.compare(a.diff, b.diff);
            if (c != 0) return c;
            return Integer.compare(a.mask, b.mask);
        });

        if (k < 0 || k >= all.size()) return null;
        return all.get(k);
    }

    private static Teams teamsFromMask(List<String> list, int mask, double total) {
        Teams t = new Teams();
        double sumA = 0.0;

        for (int i = 0; i < 10; i++) {
            String name = list.get(i);
            if ((mask & (1 << i)) != 0) {
                t.teamA.add(name);
                sumA += rankOf(name);
            } else {
                t.teamB.add(name);
            }
        }

        t.sumA = sumA;
        t.sumB = total - sumA;
        return t;
    }

    private static double rankOf(String name) {
        return RANK.getOrDefault(name, 5.0);
    }

    private static String formatTeamsMessage(String title, Teams t, double diff) {
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
                .append(String.format(Locale.US, "%.2f", diff));

        return sb.toString();
    }

    // =========================
    // Helpers / Session model
    // =========================

    private static String sessionKey(String userId, String channelId) {
        return userId + ":" + channelId;
    }

    private static class Session {
        final String userId;
        final String channelId;
        final List<String> players;

        int index = 0;
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
