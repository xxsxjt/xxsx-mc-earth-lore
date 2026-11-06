package com.xxsx.earthlore.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xxsx.earthlore.EarthLore;
import com.xxsx.earthlore.core.CarrierForms;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;

/**
 * data/<ns>/earth_lore/carrier_forms.json (10 册 A-014): which blank carrier becomes which written item,
 * optionally filtered by discipline or nodes, plus an optional study_factor for that written form.
 */
public final class CarrierFormRegistry extends SimplePreparableReloadListener<List<CarrierFormRegistry.Form>> {
    public record Form(CarrierForms.Decl decl, double studyFactor) {
    }

    public static final Identifier ID = EarthLore.id("carrier_forms");
    private static final Identifier FILE = Identifier.fromNamespaceAndPath("earth_lore", "earth_lore/carrier_forms.json");

    private static volatile List<Form> forms = List.of();
    private static volatile Map<Item, Double> factors = Map.of();
    private static volatile Set<Item> carriers = Set.of();

    @Override
    protected List<Form> prepare(ResourceManager manager, ProfilerFiller profiler) {
        List<Form> out = new ArrayList<>();
        for (String namespace : new TreeSet<>(manager.getNamespaces())) {
            Identifier file = Identifier.fromNamespaceAndPath(namespace, FILE.getPath());
            for (Resource resource : manager.getResourceStack(file)) {
                try (Reader reader = resource.openAsReader()) {
                    JsonElement root = JsonParser.parseReader(reader);
                    JsonArray entries = root.getAsJsonObject().getAsJsonArray("forms");
                    if (entries == null) {
                        continue;
                    }
                    for (JsonElement element : entries) {
                        JsonObject obj = element.getAsJsonObject();
                        String carrier = obj.get("carrier").getAsString();
                        String result = obj.get("result").getAsString();
                        String discipline = obj.has("discipline") ? obj.get("discipline").getAsString() : null;
                        Set<String> nodes = new LinkedHashSet<>();
                        if (obj.has("nodes")) {
                            obj.getAsJsonArray("nodes").forEach(node -> nodes.add(node.getAsString()));
                        }
                        double factor = obj.has("study_factor") ? obj.get("study_factor").getAsDouble() : 1.0;
                        out.add(new Form(new CarrierForms.Decl(carrier, result, discipline, nodes), factor));
                    }
                } catch (Exception e) {
                    EarthLore.LOGGER.error("[earth_lore] bad carrier_forms.json in {}: {}", resource.sourcePackId(), e.toString());
                }
            }
        }
        return out;
    }

    @Override
    protected void apply(List<Form> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<Item, Double> nextFactors = new HashMap<>();
        Set<Item> nextCarriers = new LinkedHashSet<>();
        for (Form form : prepared) {
            item(form.decl().result()).ifPresent(item -> nextFactors.putIfAbsent(item, form.studyFactor()));
            item(form.decl().carrier()).ifPresent(nextCarriers::add);
        }
        forms = List.copyOf(prepared);
        factors = Map.copyOf(nextFactors);
        carriers = Set.copyOf(nextCarriers);
        EarthLore.LOGGER.info("[earth_lore] carrier forms loaded: {}", prepared.size());
    }

    private static Optional<Item> item(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(parsed);
    }

    /** Written item for a carrier, or empty when the carrier is not allowed (lore_carrier_invalid). */
    public static Optional<Item> resolve(Item carrier, Identifier discipline, Collection<Identifier> nodes) {
        List<CarrierForms.Decl> decls = forms.stream().map(Form::decl).toList();
        List<String> ids = nodes.stream().map(Identifier::toString).toList();
        return CarrierForms.resolve(decls, BuiltInRegistries.ITEM.getKey(carrier).toString(), discipline.toString(), ids)
                .flatMap(CarrierFormRegistry::item);
    }

    public static double studyFactor(Item written) {
        return factors.getOrDefault(written, 1.0);
    }

    public static boolean isCarrier(Item item) {
        return carriers.contains(item);
    }

    public static List<Form> forms() {
        return forms;
    }

    /** GameTest only: swaps the loaded declarations and returns the previous ones so the test can restore them. */
    public static List<Form> replaceForTest(List<Form> next) {
        List<Form> previous = forms;
        forms = List.copyOf(next);
        return previous;
    }
}

