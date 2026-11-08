package mage.player.cabt;

import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds the NUMBER prompt for announceX/getAmount — one option
 * per integer in [min, max], so no value outside the engine's bounds can be
 * selected.
 * <p>
 * Ranges larger than {@link #MAX_ENUMERATED_NUMBER_OPTIONS} fail closed:
 * Selection carries option indices only (no metadata), so an un-enumerable
 * range cannot be answered yet. A compact single-option encoding can be
 * added once Selection supports carrying a value.
 */
public final class CabtNumberPromptBuilder {

    public static final int MAX_ENUMERATED_NUMBER_OPTIONS = 100;

    public PendingDecision build(Player player, int min, int max, String message) {
        if (max < min) {
            throw new IllegalArgumentException("number prompt with max " + max + " < min " + min);
        }
        int rangeSize = max - min + 1;
        if (rangeSize > MAX_ENUMERATED_NUMBER_OPTIONS) {
            throw new CabtUnhandledDecisionException(
                    "number range " + min + ".." + max + " exceeds the enumeration cap ("
                            + MAX_ENUMERATED_NUMBER_OPTIONS + "); failing closed");
        }
        PendingDecision decision = new PendingDecision(
                MagicSelectType.NUMBER, player.getId(), 1, 1);
        for (int value = min; value <= max; value++) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("value", value);
            payload.put("min", min);
            payload.put("max", max);
            payload.put("message", message);
            decision.addOption(new MagicOption(
                    MagicOptionType.PROMPT_NUMBER, String.valueOf(value), payload));
        }
        return decision;
    }
}
