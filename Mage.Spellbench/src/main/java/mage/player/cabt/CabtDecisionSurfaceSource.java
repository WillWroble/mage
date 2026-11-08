package mage.player.cabt;

/**
 * Where a decision surface comes from in XMage. There is no single complete
 * legal-decision enum in the engine; the adapter surface is the union of
 * these sources.
 */
public enum CabtDecisionSurfaceSource {

    /**
     * Prompt callback declared on the mage.players.Player interface — the
     * authoritative engine prompt surface.
     */
    PLAYER_INTERFACE,

    /**
     * Prompt method on GameSessionPlayer that maps to a client/UI callback.
     */
    CLIENT_CALLBACK,

    /**
     * Priority playable-object query API (getPlayable and friends). These
     * enumerate playable actions while a player holds priority; they are not
     * the full decision space.
     */
    PLAYABLE_OBJECTS,

    /**
     * Comparison note against Arena/17Lands-style replay data; informs the
     * design but is not an XMage API.
     */
    ARENA_LOG_COMPARISON
}
