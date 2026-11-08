package mage.player.spellbench.decide;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.GameImpl;
import mage.game.match.MatchType;
import mage.game.turn.TurnMod;
import mage.players.Player;

import java.util.UUID;

/**
 * A two-player duel: London mulligan with the bottom step after the keep ({@link LondonAfterKeep}), 20 life,
 * 7 cards, the starting player skips the first draw, and each library starts in decklist order before the opening
 * shuffle. Copied from CABT's package-private {@code CabtLiveDuel} (MIT, see NOTICE), which this package cannot
 * reach.
 */
final class Duel extends GameImpl {

    Duel() {
        super(MultiplayerAttackOption.LEFT, RangeOfInfluence.ALL, new LondonAfterKeep(), 0, 20, 7);
    }

    private Duel(final Duel game) {
        super(game);
    }

    @Override
    public void addPlayer(Player player, Deck deck) {
        super.addPlayer(player, deck);
        player.getLibrary().clear();
        for (Card card : deck.getCards()) {
            if (!card.isExtraDeckCard()) {
                player.getLibrary().putOnBottom(card, this);
            }
        }
    }

    @Override
    public MatchType getGameType() {
        return new DuelType();
    }

    @Override
    public int getNumPlayers() {
        return 2;
    }

    @Override
    protected void init(UUID choosingPlayerId) {
        super.init(choosingPlayerId);
        state.getTurnMods().add(new TurnMod(startingPlayerId).withSkipStep(PhaseStep.DRAW));
    }

    @Override
    public Duel copy() {
        return new Duel(this);
    }

    static final class DuelType extends MatchType {

        DuelType() {
            this.name = "Spellbench Duel";
            this.maxPlayers = 2;
            this.minPlayers = 2;
            this.numTeams = 0;
            this.useAttackOption = false;
            this.useRange = false;
            this.sideboardingAllowed = false;
        }

        private DuelType(final DuelType matchType) {
            super(matchType);
        }

        @Override
        public DuelType copy() {
            return new DuelType(this);
        }
    }
}
