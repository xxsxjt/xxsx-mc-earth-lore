package com.xxsx.earthlore.registry;

import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.data.LoreText;
import com.xxsx.earthlore.gate.GatedRecipes;
import com.xxsx.earthlore.gate.MachineLore;
import com.xxsx.earthlore.knowledge.PlayerKnowledge;
import com.xxsx.earthlore.research.ResearchTableBlock;
import com.xxsx.earthlore.research.ResearchTableBlockEntity;
import com.xxsx.earthlore.research.ResearchTableMenu;
import com.xxsx.earthlore.study.LiteratureItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Static content of 10 册 A-003..A-008, A-012 and A-013: the research table, literature items, the lore_text
 * component, the knowledge and machine attachments and the gated recipe wrapper.
 */
public final class LoreContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EarthLore.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EarthLore.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EarthLore.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, EarthLore.MODID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EarthLore.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, EarthLore.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, EarthLore.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EarthLore.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LoreText>> LORE_TEXT =
            COMPONENTS.<LoreText>registerComponentType("lore_text", builder -> builder.persistent(LoreText.CODEC)
                    .networkSynchronized(LoreText.STREAM_CODEC));

    /** A-003: per-player record, kept on death and across dimensions. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerKnowledge>> KNOWLEDGE =
            ATTACHMENTS.register("knowledge", () -> AttachmentType.builder(() -> new PlayerKnowledge())
                    .serialize(PlayerKnowledge.CODEC).copyOnDeath().build());

    /** A-013: machine owner, snapshot and fault; only the fault reaches clients. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MachineLore>> MACHINE =
            ATTACHMENTS.register("machine", () -> AttachmentType.builder(() -> new MachineLore())
                    .serialize(MachineLore.CODEC).sync(MachineLore.SYNC_CODEC).build());

    /** A-012: decoding returns the wrapped recipe, so the stream codec of the wrapped recipe is used on the wire. */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<Recipe<?>>> GATED =
            RECIPE_SERIALIZERS.register("gated", () -> new RecipeSerializer<Recipe<?>>(GatedRecipes.CODEC, Recipe.STREAM_CODEC));

    public static final DeferredBlock<ResearchTableBlock> RESEARCH_TABLE = BLOCKS.registerBlock("research_table",
            ResearchTableBlock::new, props -> props.mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD).ignitedByLava());
    public static final DeferredItem<BlockItem> RESEARCH_TABLE_ITEM = ITEMS.registerSimpleBlockItem(RESEARCH_TABLE);
    public static final DeferredItem<Item> BLANK_NOTES = ITEMS.registerSimpleItem("blank_notes", props -> props.stacksTo(16));
    public static final DeferredItem<LiteratureItem> RESEARCH_NOTES = ITEMS.registerItem("research_notes",
            props -> new LiteratureItem(props, false), props -> props.stacksTo(1));
    public static final DeferredItem<LiteratureItem> TREATISE = ITEMS.registerItem("treatise",
            props -> new LiteratureItem(props, true), props -> props.stacksTo(1));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchTableBlockEntity>> RESEARCH_TABLE_BE =
            BLOCK_ENTITY_TYPES.register("research_table",
                    () -> new BlockEntityType<>(ResearchTableBlockEntity::new, RESEARCH_TABLE.get()));
    public static final DeferredHolder<MenuType<?>, MenuType<ResearchTableMenu>> RESEARCH_TABLE_MENU =
            MENUS.register("research_table", () -> IMenuTypeExtension.create(ResearchTableMenu::new));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("earth_lore",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.earth_lore"))
                    .icon(() -> new ItemStack(RESEARCH_NOTES.get()))
                    .displayItems((params, output) -> {
                        output.accept(RESEARCH_TABLE_ITEM.get());
                        output.accept(BLANK_NOTES.get());
                        LiteratureItem.creativeNotes(params.holders(), output::accept);
                    })
                    .build());

    private LoreContent() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        TABS.register(modBus);
    }
}
