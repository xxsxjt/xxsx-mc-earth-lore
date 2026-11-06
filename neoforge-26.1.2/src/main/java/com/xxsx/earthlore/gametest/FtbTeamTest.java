package com.xxsx.earthlore.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xxsx.earthlore.knowledge.KnowledgeService;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * earth_lore_team_ftb (10 册 A-023). Registered only when FTB Teams is installed, so this class, the only one in
 * the GameTests that touches FTB types, is never loaded otherwise. FTB's API can create a party but not add
 * members, so joining and leaving go through its /ftbteams party commands.
 */
final class FtbTeamTest {
    private FtbTeamTest() {
    }

    static void run(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer a = LoreGameTests.player(helper);
        ServerPlayer b = LoreGameTests.player(helper);
        ServerPlayer loner = LoreGameTests.player(helper);
        TeamManager manager = FTBTeamsAPI.api().getManager();
        Team party;
        try {
            party = manager.createPartyTeam(a, "lore_party", null, null);
        } catch (CommandSyntaxException e) {
            helper.fail("FTB Teams refused to create the party: " + e.getMessage());
            return;
        }
        party.setProperty(TeamProperties.FREE_TO_JOIN, true);
        server.getCommands().performPrefixedCommand(b.createCommandSourceStack(), "ftbteams party join " + party.getShortName());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(manager.arePlayersInSameTeam(a.getUUID(), b.getUUID()), "B joined A's party"))
                .thenExecute(() -> KnowledgeService.grantAll(a, List.of(LoreFixture.BASIC)))
                .thenWaitUntil(() -> helper.assertTrue(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC), "a party member's node counts"))
                .thenExecute(() -> {
                    helper.assertFalse(KnowledgeService.data(b).learned().contains(LoreFixture.BASIC), "B's own record is unchanged");
                    helper.assertFalse(KnowledgeService.knows(loner.getUUID(), LoreFixture.BASIC),
                            "a player outside any party behaves like player scope");
                    server.getCommands().performPrefixedCommand(b.createCommandSourceStack(), "ftbteams party leave");
                })
                .thenWaitUntil(() -> helper.assertFalse(manager.arePlayersInSameTeam(a.getUUID(), b.getUUID()), "B left the party"))
                .thenWaitUntil(() -> helper.assertFalse(KnowledgeService.knows(b.getUUID(), LoreFixture.BASIC),
                        "the effective set is rebuilt after the member change"))
                .thenExecute(() -> {
                    LoreGameTests.logout(a);
                    LoreGameTests.logout(b);
                    LoreGameTests.logout(loner);
                })
                .thenSucceed();
    }
}

