package mage.player.ai;

import mage.cards.decks.DeckCardLists;
import org.apache.log4j.Logger;

/**
 * Generates State graph for given deck and handles training cycles to tune RL parameters
 *
 * @author willwroble
 */
public class DeckLearner {
    private DeckCardLists deckList;
    private ComputerPlayer player;
    private GameStateGraphNode stateTreeRoot;
    private static Logger logger;
    public DeckLearner(ComputerPlayer p, DeckCardLists d) {
        player = p;
        deckList = d;
        logger = org.apache.log4j.Logger.getLogger(DeckLearner.class);
    }
    //generates skeleton state tree with one of each one card state
    public void GenerateInitialStateTree() {


    }
    public void AddGameStateNode(GameStateGraphNode n) {

    }
}
