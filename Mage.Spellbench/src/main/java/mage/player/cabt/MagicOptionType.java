package mage.player.cabt;

/**
 * CABT bridge: the kind of game action or answer an option represents.
 * PLAY_LAND/CAST_SPELL/ACTIVATE_ABILITY/SPECIAL_ACTION are the root priority
 * actions enumerated from Player.getPlayable; PROMPT_* options answer an
 * engine prompt by referencing a player, game object, card, choice value,
 * pile, mode, or number; there is deliberately no catch-all/unknown value —
 * an unrecognized action must fail, not be bucketed.
 */
public enum MagicOptionType {
    PASS_PRIORITY,
    PLAY_LAND,
    CAST_SPELL,
    ACTIVATE_ABILITY,
    SPECIAL_ACTION,
    PROMPT_OBJECT,
    PROMPT_PLAYER,
    PROMPT_CARD,
    PROMPT_YES,
    PROMPT_NO,
    PROMPT_CHOICE,
    PROMPT_PILE,
    PROMPT_MODE,
    PROMPT_NUMBER,
    PROMPT_AMOUNT_ASSIGNMENT,
    PROMPT_TRIGGERED_ABILITY,
    PROMPT_REPLACEMENT_EFFECT,
    PROMPT_MANA_SOURCE,
    PROMPT_MANA_POOL,
    PROMPT_SPECIAL_MANA,
    PROMPT_CANCEL_PAYMENT,
    PROMPT_ATTACKER,
    PROMPT_BLOCKER,
    PROMPT_KEEP,
    PROMPT_MULLIGAN
}
