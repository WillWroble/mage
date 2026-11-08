package spellbench.kit.xmage;

import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.GameImpl;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.match.MatchType;
import mage.game.mulligan.LondonMulligan;
import mage.players.Player;

/**
 * The world's game: a two-player duel with London mulligan, 20 life and 7 cards, as the engine's
 * {@code decide.Duel} (itself copied from CABT's {@code CabtLiveDuel}, MIT). The world builder places every card
 * itself, so libraries are not filled here, and no turn modification is added at init (the builder decides whether
 * the starting player still skips its first draw).
 */
public final class KitDuel extends GameImpl {

    public KitDuel() {
        super(MultiplayerAttackOption.LEFT, RangeOfInfluence.ALL, new LondonMulligan(0), 0, 20, 7);
    }

    private KitDuel(final KitDuel game) {
        super(game);
    }

    @Override
    public MatchType getGameType() {
        return new KitDuelType();
    }

    @Override
    public int getNumPlayers() {
        return 2;
    }

    @Override
    public KitDuel copy() {
        return new KitDuel(this);
    }

    static final class KitDuelType extends MatchType {
        private static final long serialVersionUID = 1L;

        KitDuelType() {
            this.name = "Spellbench Kit Duel";
            this.maxPlayers = 2;
            this.minPlayers = 2;
            this.numTeams = 0;
            this.useAttackOption = false;
            this.useRange = false;
            this.sideboardingAllowed = false;
        }

        private KitDuelType(final KitDuelType matchType) {
            super(matchType);
        }

        @Override
        public KitDuelType copy() {
            return new KitDuelType(this);
        }
    }

    /** The world's match: the reconstruction's own, so references to match players resolve to world objects. */
    public static final class KitMatch extends MatchImpl {

        public KitMatch() {
            super(new MatchOptions("spellbench-kit", "Spellbench Kit Duel", false));
        }

        @Override
        public void startGame() {
            // worlds are built by the kit, never started through the match
        }

        public void add(Player player, Deck deck) {
            addPlayer(player, deck);
        }
    }
}
