package com.xxsx.earthlore.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.xxsx.earthlore.LoreConfig;
import com.xxsx.earthlore.core.FailCodes;
import com.xxsx.earthlore.core.Prereq;
import com.xxsx.earthlore.data.Discipline;
import com.xxsx.earthlore.data.ItemReq;
import com.xxsx.earthlore.data.ResearchCost;
import com.xxsx.earthlore.data.UnlockRef;
import com.xxsx.earthlore.graph.GraphBuilder;
import com.xxsx.earthlore.graph.LoreGraph;
import com.xxsx.earthlore.knowledge.KnowledgeIndex;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import com.xxsx.earthlore.knowledge.LoreSync;
import com.xxsx.earthlore.knowledge.Migrations;
import com.xxsx.earthlore.team.TeamSource;
import com.xxsx.earthlore.team.TeamTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** /lore (10 册 A-016). Player arguments are names so offline players can be looked up in the index. */
public final class LoreCommands {
    private static final SuggestionProvider<CommandSourceStack> NODES = (context, builder) ->
            SharedSuggestionProvider.suggest(KnowledgeService.graph().nodes().stream().map(node -> node.id().toString()), builder);
    private static final SuggestionProvider<CommandSourceStack> DISCIPLINES = (context, builder) ->
            SharedSuggestionProvider.suggest(KnowledgeService.graph().disciplines().values().stream().map(Discipline::code), builder);
    private static final SuggestionProvider<CommandSourceStack> TARGETS = (context, builder) ->
            SharedSuggestionProvider.suggest(Stream.concat(Stream.concat(Stream.of("all"),
                            KnowledgeService.graph().disciplines().values().stream()
                                    .map(d -> "discipline:" + d.code().toLowerCase(Locale.ROOT))),
                    KnowledgeService.graph().nodes().stream().map(node -> node.id().toString())), builder);
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    private record Target(UUID id, String name, ServerPlayer online) {
    }

    private LoreCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lore")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource(), self(context.getSource())))
                        .then(player().requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> list(context.getSource(), named(context.getSource(),
                                        StringArgumentType.getString(context, "player"), false)))))
                .then(Commands.literal("why")
                        .then(Commands.argument("node", IdentifierArgument.id()).suggests(NODES)
                                .executes(context -> why(context.getSource(), IdentifierArgument.getId(context, "node")))))
                .then(Commands.literal("tree")
                        .then(Commands.argument("discipline", StringArgumentType.word()).suggests(DISCIPLINES)
                                .executes(context -> tree(context.getSource(), StringArgumentType.getString(context, "discipline")))))
                .then(Commands.literal("grant").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(player().then(Commands.argument("target", StringArgumentType.greedyString()).suggests(TARGETS)
                                .executes(context -> change(context.getSource(), StringArgumentType.getString(context, "player"),
                                        StringArgumentType.getString(context, "target"), true)))))
                .then(Commands.literal("revoke").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(player().then(Commands.argument("target", StringArgumentType.greedyString()).suggests(TARGETS)
                                .executes(context -> change(context.getSource(), StringArgumentType.getString(context, "player"),
                                        StringArgumentType.getString(context, "target"), false)))))
                .then(Commands.literal("migrate").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(player().executes(context -> migrate(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("validate").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> validate(context.getSource())))
                .then(Commands.literal("export").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> export(context.getSource())))
                .then(Commands.literal("team")
                        .executes(context -> team(context.getSource(), self(context.getSource())))
                        .then(player().requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> team(context.getSource(), named(context.getSource(),
                                        StringArgumentType.getString(context, "player"), false))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS);
    }

    private static Target self(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return new Target(player.getUUID(), player.getScoreboardName(), player);
    }

    /** Online first, then the knowledge index unless onlineOnly; null when unknown. */
    private static Target named(CommandSourceStack source, String name, boolean onlineOnly) {
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new Target(online.getUUID(), online.getScoreboardName(), online);
        }
        if (onlineOnly) {
            return null;
        }
        KnowledgeIndex index = KnowledgeIndex.get(server);
        return index.byName(name).map(id -> new Target(id, index.name(id).orElse(name), null)).orElse(null);
    }

    private static int fail(CommandSourceStack source, String code) {
        source.sendFailure(Component.translatable("lore.earth_lore.fail." + code));
        return 0;
    }

    private static Component nodeName(LoreGraph graph, Identifier id) {
        return graph.node(id).map(node -> (Component) Component.translatable(node.nameKey()))
                .orElse(Component.literal(id.toString()));
    }

    private static Component disciplineName(Discipline discipline) {
        return Component.translatable(discipline.nameKey());
    }

    private static MutableComponent joined(LoreGraph graph, Collection<Identifier> ids) {
        MutableComponent out = Component.empty();
        int i = 0;
        for (Identifier id : ids) {
            if (i++ > 0) {
                out.append(", ");
            }
            out.append(nodeName(graph, id));
        }
        return out;
    }

    private static int list(CommandSourceStack source, Target target) {
        if (target == null) {
            return fail(source, FailCodes.NO_PLAYER);
        }
        LoreGraph graph = KnowledgeService.graph();
        Set<Identifier> learned = KnowledgeService.personal(target.id());
        source.sendSuccess(() -> Component.translatable("message.earth_lore.list.header", target.name()), false);
        int shown = 0;
        for (Identifier disciplineId : graph.disciplineOrder()) {
            List<Identifier> ids = graph.inDiscipline(disciplineId).stream().map(LoreGraph.Node::id)
                    .filter(learned::contains).toList();
            if (ids.isEmpty()) {
                continue;
            }
            shown += ids.size();
            Discipline discipline = graph.disciplines().get(disciplineId);
            MutableComponent line = Component.empty().append(disciplineName(discipline).copy().withStyle(ChatFormatting.GOLD))
                    .append(" (" + ids.size() + "): ").append(joined(graph, ids));
            source.sendSuccess(() -> line, false);
        }
        if (shown == 0) {
            source.sendSuccess(() -> Component.translatable("message.earth_lore.list.empty"), false);
        }
        return shown;
    }

    private static int why(CommandSourceStack source, Identifier id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        LoreGraph graph = KnowledgeService.graph();
        Optional<LoreGraph.Node> found = graph.node(id);
        if (found.isEmpty()) {
            return fail(source, FailCodes.UNKNOWN_NODE);
        }
        LoreGraph.Node node = found.get();
        source.sendSuccess(() -> Component.translatable("message.earth_lore.why.header", nodeName(graph, id), node.tier()), false);
        if (KnowledgeService.knowsSelf(player, id)) {
            source.sendSuccess(() -> Component.translatable("lore.earth_lore.fail." + FailCodes.ALREADY_KNOWN), false);
            return 1;
        }
        List<Identifier> missing = new ArrayList<>();
        KnowledgeService.prerequisitesMet(player.getUUID(), node, missing);
        if (!missing.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.earth_lore.why.knowledge", joined(graph, missing)), false);
        }
        ServerLevel level = player.level();
        for (ItemReq req : node.data().itemRequires()) {
            req.hold().ifPresent(ingredient -> source.sendSuccess(() -> Component.translatable("message.earth_lore.why.hold",
                    ingredient.toString()), false));
            req.made().ifPresent(item -> source.sendSuccess(() -> Component.translatable("message.earth_lore.why.made",
                    BuiltInRegistries.ITEM.getOptional(item).map(i -> i.getName(i.getDefaultInstance()))
                            .orElse(Component.literal(item.toString()))), false));
            req.cond().ifPresent(cond -> {
                boolean ok = com.xxsx.earthlore.condition.Conditions.test(level, player.blockPosition(), cond);
                source.sendSuccess(() -> Component.translatable("message.earth_lore.why.cond", cond.raw(),
                        Component.translatable("lore.earth_lore.fail." + cond.failCode()))
                        .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            });
        }
        ResearchCost research = node.data().research();
        if (!research.items().isEmpty()) {
            MutableComponent cost = Component.empty();
            for (int i = 0; i < research.items().size(); i++) {
                ResearchCost.Material material = research.items().get(i);
                if (i > 0) {
                    cost.append(" + ");
                }
                cost.append(material.ingredient().toString() + " x" + material.count());
            }
            source.sendSuccess(() -> Component.translatable("message.earth_lore.why.cost", cost, research.ticks()), false);
        }
        if (missing.isEmpty() && node.data().itemRequires().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.earth_lore.why.ready"), false);
        }
        return 1;
    }

    private static Optional<Map.Entry<Identifier, Discipline>> discipline(LoreGraph graph, String raw) {
        String text = raw.startsWith("discipline:") ? raw.substring("discipline:".length()) : raw;
        for (Map.Entry<Identifier, Discipline> entry : graph.disciplines().entrySet()) {
            if (entry.getValue().code().equalsIgnoreCase(text) || entry.getKey().toString().equals(text)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static int tree(CommandSourceStack source, String raw) {
        LoreGraph graph = KnowledgeService.graph();
        Optional<Map.Entry<Identifier, Discipline>> found = discipline(graph, raw);
        if (found.isEmpty()) {
            return fail(source, FailCodes.BAD_DISCIPLINE);
        }
        Identifier disciplineId = found.get().getKey();
        UUID viewer = source.getPlayer() == null ? null : source.getPlayer().getUUID();
        List<LoreGraph.Node> nodes = graph.inDiscipline(disciplineId);
        source.sendSuccess(() -> Component.empty().append(disciplineName(found.get().getValue()).copy().withStyle(ChatFormatting.GOLD))
                .append(" (" + nodes.size() + ")"), false);
        int tier = -1;
        MutableComponent line = null;
        for (LoreGraph.Node node : nodes) {
            if (node.tier() != tier) {
                if (line != null) {
                    MutableComponent done = line;
                    source.sendSuccess(() -> done, false);
                }
                tier = node.tier();
                line = Component.translatable("message.earth_lore.tree.tier", tier).append(" ");
            } else {
                line.append(", ");
            }
            boolean known = viewer != null && KnowledgeService.knows(viewer, node.id());
            line.append(Component.translatable(node.nameKey()).withStyle(known ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (line != null) {
            MutableComponent done = line;
            source.sendSuccess(() -> done, false);
        }
        return nodes.size();
    }

    private static int change(CommandSourceStack source, String playerName, String raw, boolean grant) {
        Target target = named(source, playerName, true);
        if (target == null) {
            return fail(source, FailCodes.NO_PLAYER);
        }
        LoreGraph graph = KnowledgeService.graph();
        List<Identifier> ids;
        if (raw.equals("all")) {
            ids = graph.nodes().stream().filter(node -> !graph.roots().contains(node.id())).sorted(LoreGraph.STUDY_ORDER)
                    .map(LoreGraph.Node::id).toList();
        } else if (raw.startsWith("discipline:")) {
            Optional<Map.Entry<Identifier, Discipline>> found = discipline(graph, raw);
            if (found.isEmpty()) {
                return fail(source, FailCodes.BAD_DISCIPLINE);
            }
            ids = graph.inDiscipline(found.get().getKey()).stream().filter(node -> !graph.roots().contains(node.id()))
                    .map(LoreGraph.Node::id).toList();
        } else {
            Identifier id = Identifier.tryParse(raw);
            if (id == null || !graph.loaded(id)) {
                return fail(source, FailCodes.UNKNOWN_NODE);
            }
            ids = List.of(id);
        }
        ServerPlayer player = target.online();
        int count;
        if (grant) {
            count = KnowledgeService.grantAll(player, ids).size();
        } else {
            count = 0;
            for (Identifier id : ids) {
                count += KnowledgeService.revoke(player, id).size();
            }
            LoreSync.sendFull(player);
        }
        int changed = count;
        source.sendSuccess(() -> Component.translatable(grant ? "message.earth_lore.granted" : "message.earth_lore.revoked",
                changed, target.name()), true);
        return changed;
    }

    private static int migrate(CommandSourceStack source, String playerName) {
        Target target = named(source, playerName, true);
        if (target == null) {
            return fail(source, FailCodes.NO_PLAYER);
        }
        int added = Migrations.rerun(target.online()).size();
        LoreSync.sendFull(target.online());
        source.sendSuccess(() -> Component.translatable("message.earth_lore.migrated", added, target.name(),
                Migrations.ids().size()), true);
        return added;
    }

    private static int validate(CommandSourceStack source) {
        GraphBuilder.Report report = KnowledgeService.revalidate();
        LoreSync.fullAll(source.getServer());
        source.sendSuccess(() -> Component.literal(report.summaryLine()), false);
        if (!report.rejectedByCode().isEmpty()) {
            source.sendSuccess(() -> Component.literal("rejected by code: " + report.rejectedByCode()), false);
        }
        return report.graph().nodes().size();
    }

    private static int export(CommandSourceStack source) {
        LoreGraph graph = KnowledgeService.graph();
        JsonObject root = new JsonObject();
        root.addProperty("hash", graph.hash());
        JsonArray disciplines = new JsonArray();
        for (Identifier id : graph.disciplineOrder()) {
            Discipline discipline = graph.disciplines().get(id);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id.toString());
            entry.addProperty("code", discipline.code());
            entry.addProperty("order", discipline.order());
            entry.addProperty("home_mod", discipline.homeMod());
            entry.addProperty("has_root", discipline.hasRoot());
            disciplines.add(entry);
        }
        root.add("disciplines", disciplines);
        JsonArray nodes = new JsonArray();
        graph.nodes().stream().sorted(LoreGraph.STUDY_ORDER).forEach(node -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", node.id().toString());
            entry.addProperty("discipline", node.discipline().toString());
            entry.addProperty("tier", node.tier());
            entry.addProperty("gate", node.gate().id());
            JsonArray requires = new JsonArray();
            for (Prereq prereq : node.def().requires()) {
                JsonObject req = new JsonObject();
                if (prereq instanceof Prereq.All all) {
                    req.addProperty("all", all.node());
                } else {
                    JsonArray any = new JsonArray();
                    prereq.members().forEach(any::add);
                    req.add("any", any);
                }
                requires.add(req);
            }
            entry.add("requires", requires);
            JsonArray unlocks = new JsonArray();
            for (UnlockRef unlock : node.data().unlocks()) {
                unlocks.add(unlock.kind().id() + ":" + unlock.value());
            }
            entry.add("unlocks", unlocks);
            entry.addProperty("research_ticks", node.data().research().ticks());
            nodes.add(entry);
        });
        root.add("nodes", nodes);
        JsonObject rejected = new JsonObject();
        graph.rejected().forEach((id, code) -> rejected.addProperty(id.toString(), code));
        root.add("rejected", rejected);
        Path path = source.getServer().getServerDirectory().resolve("logs").resolve("earth_lore-graph.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return fail(source, FailCodes.IO_ERROR);
        }
        source.sendSuccess(() -> Component.translatable("message.earth_lore.exported", graph.nodes().size(),
                "logs/earth_lore-graph.json"), false);
        return graph.nodes().size();
    }

    private static int team(CommandSourceStack source, Target target) {
        if (target == null) {
            return fail(source, FailCodes.NO_PLAYER);
        }
        MinecraftServer server = source.getServer();
        TeamSource teams = TeamTracker.source();
        LoreConfig.Scope scope = LoreConfig.scope();
        String teamName = teams.teamName(server, target.id());
        Set<UUID> members = teams.members(server, target.id());
        KnowledgeIndex index = KnowledgeIndex.get(server);
        List<String> names = new ArrayList<>();
        for (UUID member : members) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            names.add(online != null ? online.getScoreboardName() : index.name(member).orElse(member.toString()));
        }
        LoreGraph graph = KnowledgeService.graph();
        long effective = KnowledgeService.effective(target.id()).stream().filter(id -> !graph.roots().contains(id)).count();
        long personal = KnowledgeService.personal(target.id()).stream().filter(graph::loaded).count();
        boolean viewerIsOp = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        source.sendSuccess(() -> Component.translatable("message.earth_lore.team.scope", target.name(),
                scope.name().toLowerCase(Locale.ROOT), teams.id()), false);
        source.sendSuccess(() -> Component.translatable("message.earth_lore.team.members",
                teamName.isEmpty() ? "-" : teamName, String.join(", ", names)), false);
        source.sendSuccess(() -> Component.translatable("message.earth_lore.team.count", personal, effective), false);
        return viewerIsOp ? (int) effective : 1;
    }
}
