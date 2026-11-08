package mage.player.cabt;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.game.Game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds one option per available mode, using the same mode
 * text HumanPlayer shows — effect text with {this} replaced by the source
 * object's name — plus how many times the mode was already selected.
 */
public final class CabtModeOptionFactory {

    private CabtModeOptionFactory() {
    }

    public static MagicOption modeOption(Game game, Modes modes, Mode mode, Ability source) {
        String modeText = modeText(game, mode, source);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("modeId", mode.getId().toString());
        payload.put("modeText", modeText);
        payload.put("selectedCount", modes.getSelectedStats(mode.getId()));
        payload.put("sourceId", source == null || source.getSourceId() == null
                ? null : source.getSourceId().toString());
        payload.put("sourceName", sourceName(game, source));
        String label = modeText.isEmpty()
                ? "Choose mode: " + mode.getId()
                : "Choose mode: " + modeText;
        return new MagicOption(MagicOptionType.PROMPT_MODE, label, payload);
    }

    private static String modeText(Game game, Mode mode, Ability source) {
        String text = mode.getEffects() == null ? "" : mode.getEffects().getText(mode);
        if (text == null) {
            return "";
        }
        MageObject sourceObject = source == null || source.getSourceId() == null
                ? null : game.getObject(source.getSourceId());
        if (sourceObject != null) {
            text = text.replace("{this}", sourceObject.getName());
        }
        if (!text.isEmpty()) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return text;
    }

    private static String sourceName(Game game, Ability source) {
        if (source == null || source.getSourceId() == null) {
            return null;
        }
        MageObject object = game.getObject(source.getSourceId());
        return object == null ? null : object.getName();
    }
}
