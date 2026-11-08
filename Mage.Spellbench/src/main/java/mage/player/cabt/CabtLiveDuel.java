package mage.player.cabt;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.GameImpl;
import mage.game.match.MatchType;
import mage.game.mulligan.MulliganType;
import mage.game.turn.TurnMod;
import mage.players.Player;

import java.util.UUID;

final class CabtLiveDuel extends GameImpl {

    CabtLiveDuel() {
        super(MultiplayerAttackOption.LEFT, RangeOfInfluence.ALL,
                MulliganType.GAME_DEFAULT.getMulligan(0), 0, 20, 7);
    }

    private CabtLiveDuel(final CabtLiveDuel game) {
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
        return new CabtLiveDuelType();
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
    public CabtLiveDuel copy() {
        return new CabtLiveDuel(this);
    }

    static final class CabtLiveDuelType extends MatchType {

        CabtLiveDuelType() {
            this.name = "CABT Live Duel";
            this.maxPlayers = 2;
            this.minPlayers = 2;
            this.numTeams = 0;
            this.useAttackOption = false;
            this.useRange = false;
            this.sideboardingAllowed = false;
        }

        private CabtLiveDuelType(final CabtLiveDuelType matchType) {
            super(matchType);
        }

        @Override
        public CabtLiveDuelType copy() {
            return new CabtLiveDuelType(this);
        }
    }
}
