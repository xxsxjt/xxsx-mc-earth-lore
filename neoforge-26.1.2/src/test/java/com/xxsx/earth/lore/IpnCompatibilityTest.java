package com.xxsx.earth.lore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpnCompatibilityTest {
    @Test
    void researchTableMenuAndScreenCarryIpnPlayerSideOnly() throws Exception {
        Path classesRoot = Path.of("build/classes/java/main");
        for (String rel : new String[] {
                "com/xxsx/earth/lore/research/ResearchTableMenu.class",
                "com/xxsx/earth/lore/client/ResearchTableScreen.class"
        }) {
            String raw = new String(Files.readAllBytes(classesRoot.resolve(rel)), StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("RuntimeVisibleAnnotations"));
            assertTrue(raw.contains("Lorg/anti_ad/mc/ipn/api/IPNPlayerSideOnly;"));
        }
        assertFalse(Files.exists(classesRoot.resolve("org/anti_ad/mc/ipn/api/IPNPlayerSideOnly.class")));
    }
}
