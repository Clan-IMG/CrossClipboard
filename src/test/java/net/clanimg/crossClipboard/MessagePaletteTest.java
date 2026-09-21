package net.clanimg.crossClipboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The messages are meant to be gray with aqua highlights. This renders every message as the plugin does and
 * checks that all visible text ends up in that palette, so an untagged or stray-coloured message is caught.
 */
class MessagePaletteTest {

    private static final Set<TextColor> PALETTE = Set.of(NamedTextColor.GRAY, NamedTextColor.AQUA, NamedTextColor.DARK_GRAY);
    private static final String[] PLACEHOLDERS = {"server", "blocks", "size", "bytes", "limit", "age", "expires", "reason", "command"};

    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void everyMessageUsesOnlyGrayAndAqua(String language) throws IOException {
        YamlConfiguration lang = load(language);
        TagResolver[] resolvers = new TagResolver[PLACEHOLDERS.length];
        for (int i = 0; i < PLACEHOLDERS.length; i++) {
            resolvers[i] = Placeholder.unparsed(PLACEHOLDERS[i], "X");
        }

        for (String key : lang.getKeys(false)) {
            Component rendered = MiniMessage.miniMessage().deserialize(lang.getString("prefix") + lang.getString(key), resolvers);
            List<String> problems = new ArrayList<>();
            collectProblems(rendered, null, problems);
            assertTrue(problems.isEmpty(), language + "/" + key + ": " + problems);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void everyPlaceholderIsFilledIn(String language) throws IOException {
        YamlConfiguration lang = load(language);
        for (String key : lang.getKeys(false)) {
            String text = lang.getString(key);
            for (String placeholder : PLACEHOLDERS) {
                if (text.contains("<" + placeholder + ">")) {
                    Component rendered = MiniMessage.miniMessage().deserialize(text,
                            Placeholder.unparsed(placeholder, "FILLED"));
                    assertEquals(-1, plain(rendered).indexOf("<" + placeholder + ">"), language + "/" + key);
                    assertTrue(plain(rendered).contains("FILLED"), language + "/" + key);
                }
            }
        }
    }

    /** Text with no colour, or a colour outside the palette, after inheriting from its parents. */
    private static void collectProblems(Component component, TextColor inherited, List<String> problems) {
        TextColor effective = component.color() != null ? component.color() : inherited;
        if (component instanceof TextComponent text && !text.content().isBlank()
                && (effective == null || !PALETTE.contains(effective))) {
            problems.add("'" + text.content() + "' is " + effective);
        }
        for (Component child : component.children()) {
            collectProblems(child, effective, problems);
        }
    }

    private static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        append(component, out);
        return out.toString();
    }

    private static void append(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> append(child, out));
    }

    private static YamlConfiguration load(String language) throws IOException {
        try (Reader reader = new InputStreamReader(
                MessagePaletteTest.class.getResourceAsStream("/lang/" + language + ".yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
