package mage.player.ai;

import com.j256.ormlite.stmt.query.In;
import mage.MageObject;
import mage.abilities.*;
import mage.abilities.costs.Cost;
import mage.abilities.costs.Costs;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.effects.Effect;
import mage.abilities.mana.ManaOptions;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.Graveyard;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.game.stack.SpellStack;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;


import java.io.IOException;
import java.util.*;

/**
 * Deck specific state encoder for reinforcement learning.
 * Before vectors can be made, the encoder must learn all game features of
 * the given 60 card decks through a first pass of 1,000 simulated mcst games
 */
public class StateEncoder {
    public static int indexCount;
    public static int reducedIndexCount;
    private Features features;
    public static Set<Integer> featureVector = new HashSet<>();
    public UUID opponentID;
    public UUID myPlayerID;
    public List<Set<Integer>> macroStateVectors = new ArrayList<>();
    public List<Set<Integer>> microStateVectors = new ArrayList<>();
    public Map<Integer, Integer> rawToReduced = new HashMap<>();
    public List<Boolean> activeStates = new ArrayList<>();

    public List<Double> stateScores = new ArrayList<>();
    public int initialRawSize = 0;//original max index
    public int mappingVersion = 0;

    public Set<Integer> ignoreList;

    public StateEncoder() {
        //using statics for convenience for now
        indexCount = 0;
        reducedIndexCount = 1;
        features = new Features();
        ignoreList = new HashSet<>();
    }
    public void setAgent(UUID me) {
        myPlayerID = me;
    }
    public void setOpponent(UUID op) {
        opponentID = op;
    }

    public void processManaCosts(ManaCosts<ManaCost> manaCost, Game game, Features f, Boolean callParent) {
        //f.addFeature(manaCost.getText());
        f.addNumericFeature("ManaValue", manaCost.manaValue(), callParent);
        for(ManaCost mc : manaCost) {
            f.addFeature(mc.getText());
        }
    }
    public void processCosts(Costs<Cost> costs, ManaCosts<ManaCost> manaCosts, Game game, Features f, Boolean callParent) {
        //if(c.c) f.addFeature("CanPay"); //use c.canPay()
        if(manaCosts != null && !manaCosts.isEmpty()) processManaCosts(manaCosts, game, f, callParent);
        if(costs == null || costs.isEmpty()) return;
        for(Cost cc : costs) {
            f.addFeature(cc.getText());
        }
    }
    public void processAbility(Ability a, Game game, Features f) {
        Costs<Cost> c = a.getCosts();
        //for now lets not worry about encoding costs per abilities
        /*ManaCosts<ManaCost> mcs = a.getManaCostsToPay();
        if(!c.isEmpty() || !mcs.isEmpty()) {
            Features costFeature = f.getSubFeatures("AbilityCost");
            processCosts(c, mcs, game, costFeature, false); //dont propagate mana cost up for abilities
        }*/
        for(Mode m : a.getModes().getAvailableModes(a, game)) {
            for(Effect e : m.getEffects()) {
                f.addFeature(e.getText(m));
            }
        }
    }
    public void processActivatedAbility(ActivatedAbility aa, Game game, Features f) {
        processAbility(aa, game, f);

        if(aa.canActivate(myPlayerID, game).canActivate()) f.addFeature("CanActivate"); //use aa.canActivate()
    }
    public void processTriggeredAbility(TriggeredAbility ta, Game game, Features f) {
        processAbility(ta, game, f);

        if(!ta.checkTriggeredLimit(game)) f.addFeature("ReachedTriggerLimit"); //use ta.checkTriggeredLimit()
        if(ta.checkUsedAlready(game)) f.addFeature("UsedAlready");//use ta.checkUsedAlready(game)
        if(ta.getTriggerEvent() != null) f.addFeature(ta.getTriggerEvent().getType().name());

    }
    public void processCard(Card c, Game game, Features f) {

        f.parent.addFeature("Card");//raw universal type of card added for counting purposes

        if(c.isPermanent()) f.addCategory("Permanent");
        //add types
        for (CardType ct : c.getCardType()) {
            f.addCategory(ct.name());
        }
        //add subtypes
        for (SubType st : c.getSubtype(game)) {
            f.addCategory(st.name());
        }
        //add color
        if(c.getColor(game).isRed()) f.addCategory("RedCard");
        if(c.getColor(game).isWhite()) f.addCategory("WhiteCard");
        if(c.getColor(game).isBlack()) f.addCategory("BlackCard");
        if(c.getColor(game).isGreen()) f.addCategory("GreenCard");
        if(c.getColor(game).isBlue()) f.addCategory("BlueCard");
        if(c.getColor(game).isColorless()) f.addCategory("ColorlessCard");
        if(c.getColor(game).isMulticolored()) f.addCategory("MultiColored");

        //add cost
        ManaCosts<ManaCost> mc = c.getManaCost();
        processManaCosts(mc, game, f, true);


        //process counters
        Counters counters = c.getCounters(game);
        for (String counterName : counters.keySet()) {
            f.addNumericFeature(counterName, counters.get(counterName).getCount());
        }
        //is creature
        if(c.isCreature(game)) {

            f.addNumericFeature("Power", c.getPower().getValue());
            f.addNumericFeature("Toughness", c.getToughness().getValue());
        }

    }
    public void processPermBattlefield(Permanent p, Game game, Features f) {
        processCard(p, game, f);
        //is tapped?
        if(p.isTapped()) f.addFeature("Tapped");

        //process attachments
        for (UUID id : p.getAttachments()) {
            Permanent attachment = game.getPermanent(id);
            if(attachment == null) continue;
            //modify name to not count auras/equipment twice
            f.passToParent = false; //don't pass pooled attachment features up, or they will be counted twice
            Features attachmentFeatures = f.getSubFeatures(attachment.getName());
            processPermBattlefield(attachment, game, attachmentFeatures);
            f.passToParent = true;
        }


        //process abilities on battlefield
        //static abilities
        for (StaticAbility sa : p.getAbilities(game).getStaticAbilities(Zone.BATTLEFIELD)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : p.getAbilities(game).getActivatedAbilities(Zone.BATTLEFIELD)) {
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
        }
        //triggered abilities
        for(TriggeredAbility ta : p.getAbilities(game).getTriggeredAbilities(Zone.BATTLEFIELD)) {
            Features taFeatures = f.getSubFeatures(ta.getRule());
            processTriggeredAbility(ta, game, taFeatures);

        }
        if(p.isCreature(game)) {
            if(p.canAttack(opponentID, game)) f.addFeature("CanAttack"); //use p.canAttack()
            if(p.canBlock(opponentID, game)) f.addFeature("CanBlock");
            f.addNumericFeature("Damage", p.getDamage());
        }
    }
    public void processCardInGraveyard(Card c, Game game, Features f) {
        //process as card
        processCard(c, game, f);
        //process abilities in gy
        //static abilities
        for (StaticAbility sa : c.getAbilities(game).getStaticAbilities(Zone.GRAVEYARD)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : c.getAbilities(game).getActivatedAbilities(Zone.GRAVEYARD)) {
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
        }
        //triggered abilities
        for(TriggeredAbility ta : c.getAbilities(game).getTriggeredAbilities(Zone.GRAVEYARD)) {
            Features taFeatures = f.getSubFeatures(ta.getRule());
            processTriggeredAbility(ta, game, taFeatures);

        }

    }
    public void processCardInHand(Card c, Game game, Features f) {
        //process as card
        processCard(c, game, f);
        //process abilities in hand
        //static abilities
        for (StaticAbility sa : c.getAbilities(game).getStaticAbilities(Zone.HAND)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : c.getAbilities(game).getActivatedAbilities(Zone.HAND)) {
            //if(!(aa instanceof SpellAbility)) {
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
            //}
        }
        //triggered abilities
        for(TriggeredAbility ta : c.getAbilities(game).getTriggeredAbilities(Zone.HAND)) {
            Features taFeatures = f.getSubFeatures(ta.getRule());
            processTriggeredAbility(ta, game, taFeatures);

        }
    }
    public void processBattlefield(Battlefield bf, Game game, Features f, UUID playerID) {
        for (Permanent p : bf.getAllActivePermanents(playerID)) {
            Features permFeatures = f.getSubFeatures(p.getName());
            processPermBattlefield(p, game, permFeatures);
        }
    }
    public void processGraveyard(Graveyard gy, Game game, Features f) {
        for (Card c : gy.getCards(game)) {
            Features graveCardFeatures = f.getSubFeatures(c.getName());
            processCardInGraveyard(c, game, graveCardFeatures);
        }
    }
    public void processHand(Cards hand, Game game, Features f) {
        for (Card c : hand.getCards(game)) {
            Features handCardFeatures = f.getSubFeatures(c.getName());
            processCardInHand(c, game, handCardFeatures);
        }
    }
    public void processStackObject(StackObject so, int stackPosition, Game game, Features f) {
        f.addNumericFeature("StackPosition", stackPosition, false);
        if(so.getControllerId()==myPlayerID) f.addFeature("isController");
        Ability sa = so.getStackAbility();
        if(sa instanceof TriggeredAbility) {
            processTriggeredAbility((TriggeredAbility) sa, game, f);
        } else {
            processActivatedAbility((ActivatedAbility)sa, game, f);
            if(sa instanceof SpellAbility) {
                MageObject source = game.getObject(so.getSourceId());
                for (Ability a : source.getAbilities().getStaticAbilities(Zone.STACK)) {
                    f.addFeature(a.toString());
                }
            }
        }
    }
    public void processStack(SpellStack stack, Game game, Features f) {
        Iterator<StackObject> itr = stack.descendingIterator();
        StackObject so;
        f.addNumericFeature("StackSize", stack.size());
        int i = 0;
        while(itr.hasNext()) {
            so = itr.next();
            i++;
            Features soFeatures = f.getSubFeatures(so.toString());
            processStackObject(so, i, game, soFeatures);
        }
    }
    public void processOpponentState(Game game) {
        Player myPlayer = game.getPlayer(opponentID);
        //game metadata
        features.addNumericFeature("OpponentLifeTotal", myPlayer.getLife());
        if(myPlayer.canPlayLand()) features.addFeature("OpponentCanPlayLand"); //use features.addFeature(myPlayer.canPlayLand())
        //mana pool
        Features mpFeatures = features.getSubFeatures("OpponentManaPool");
        processManaPool(myPlayer.getManaPool(), game, mpFeatures);

        //start with battlefield
        Battlefield bf = game.getBattlefield();
        Features bfFeatures = features.getSubFeatures("OpponentBattlefield");
        processBattlefield(bf, game, bfFeatures, opponentID);

        //now do graveyard
        Graveyard gy = myPlayer.getGraveyard();
        Features gyFeatures = features.getSubFeatures("OpponentGraveyard");
        processGraveyard(gy, game, gyFeatures);

        //now do hand (cards are face down so only keep count of number of cards
        // TODO: keep track of face up cards and exile
        if(opponentID==game.getPriorityPlayerId()) { //invert perspective
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("Hand");
            processHand(hand, game, handFeatures);
        } else {
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("OpponentCardsInHand", hand.size());
        }

    }
    public void processManaPool(ManaPool mp, Game game,  Features f) {
        f.addNumericFeature("GreenMana", mp.getGreen());
        f.addNumericFeature("RedMana", mp.getRed());
        f.addNumericFeature("BlueMana", mp.getBlue());
        f.addNumericFeature("WhiteMana", mp.getWhite());
        f.addNumericFeature("BlackMana", mp.getBlack());
        f.addNumericFeature("ColorlessMana", mp.getColorless());
        //TODO: deal with conditional mana
    }
    public void processPhase(Game game) {
        switch (game.getTurnStepType()) {
            case UPKEEP:
                features.addFeature("Upkeep");
            case DRAW:
                features.addFeature("Draw");
            case PRECOMBAT_MAIN:
                features.addFeature("PreCombatMain");
            case BEGIN_COMBAT:
                features.addFeature("BeginCombat");
            case DECLARE_ATTACKERS:
                features.addFeature("DeclareAttackers");
            case DECLARE_BLOCKERS:
                features.addFeature("DeclareBlockers");
            case FIRST_COMBAT_DAMAGE:
                features.addFeature("FirstCombatDamage");
            case COMBAT_DAMAGE:
                features.addFeature("CombatDamage");
            case END_COMBAT:
                features.addFeature("EndCombat");
            case POSTCOMBAT_MAIN:
                features.addFeature("PostCombatMain");
            case END_TURN:
                features.addFeature("EndTurn");
            case CLEANUP:
               features.addFeature("Cleanup");
        }
    }
    public synchronized void processState(Game game) {
        features.stateRefresh();
        featureVector.clear();

        Player myPlayer = game.getPlayer(myPlayerID);

        //game metadata
        features.addFeature(game.getPhase().getType().name());
        if(game.isActivePlayer(myPlayerID)) features.addFeature("IsActivePlayer");
        if(game.getPriorityPlayerId()==myPlayerID) features.addFeature("IsPriorityPlayer");
        //TODO: *IMPORTANT* ADD PHASE INFO
        processPhase(game);
        features.addNumericFeature("LifeTotal", myPlayer.getLife());
        if(myPlayer.canPlayLand()) features.addFeature("CanPlayLand"); //use features.addFeature(myPlayer.canPlayLand())

        //stack
        Features stackFeatures = features.getSubFeatures("Stack");
        processStack(game.getStack(), game, stackFeatures);

        //mana pool
        Features mpFeatures = features.getSubFeatures("ManaPool");
        processManaPool(myPlayer.getManaPool(), game, mpFeatures);

        //start with battlefield
        Battlefield bf = game.getBattlefield();
        Features bfFeatures = features.getSubFeatures("Battlefield");
        processBattlefield(bf, game, bfFeatures, myPlayerID);

        //now do graveyard
        Graveyard gy = myPlayer.getGraveyard();
        Features gyFeatures = features.getSubFeatures("Graveyard");
        processGraveyard(gy, game, gyFeatures);

        //now do hand
        if(opponentID==game.getPriorityPlayerId()) { //invert perspective
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("OpponentCardsInHand", hand.size());
        } else {
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("Hand");
            processHand(hand, game, handFeatures);
        }
        //TODO: add exile

        //lastly do opponent
        processOpponentState(game);

        //mapping version
        features.addNumericFeature("Mapping Version", mappingVersion);

        //update reduced vector
        //updateReducedVector();
        //stateVectors.add(Arrays.copyOf(featureVector, 30000));
    }
    public void processMicroState(Game game) {
        processState(game);
        microStateVectors.add(new HashSet<>(featureVector));
    }
    public void processMacroState(Game game) {
        processState(game);
        macroStateVectors.add(new HashSet<>(featureVector));
        //activeStates.add(game.getActivePlayerId() == myPlayerID);
    }
    /**
     * Takes an array of raw indices, filters out those present in the ignoreList,
     * and returns a new array of the remaining indices.
     *
     * @param rawIndices The input array of feature indices.
     * @return A new int[] containing only the indices not in the ignoreList.
     */
    public int[] getCompressedVectorArray(int[] rawIndices) {
        Set<Integer> filteredIndicesSet = new HashSet<>();

        for (int index : rawIndices) {
            if (!this.ignoreList.contains(index)) { // Assuming ignoreList is a member
                filteredIndicesSet.add(index);
            }
        }

        // Convert the Set<Integer> to an int[]

        return filteredIndicesSet.stream()
                .mapToInt(Integer::intValue)
                .toArray();
    }
    public Set<Integer> getCompressedVector(Set<Integer> rawIndices) {
        Set<Integer> filteredIndicesSet = new HashSet<>();

        for (int index : rawIndices) {
            if (!this.ignoreList.contains(index)) { // Assuming ignoreList is a member
                filteredIndicesSet.add(index);
            }
        }
        return filteredIndicesSet;
    }
    public synchronized int[] getFinalActiveGlobalIndicesArray() {
        Set<Integer> out1 = getCompressedVector(featureVector);
        return out1.stream().mapToInt(Integer::intValue).toArray();

    }
    // Persist the persistent feature mapping
    public void persistMapping(String filename) throws IOException {
        features.globalIndexCount = indexCount;
        features.ignoreList = new HashSet<>(ignoreList);
        features.rawToReduced = new HashMap<>(rawToReduced);
        features.version = mappingVersion;
        features.saveMapping(filename);
    }

    // Load the feature mapping from file
    public void loadMapping(String filename) throws IOException, ClassNotFoundException {
        features = Features.loadMapping(filename);
        indexCount = features.globalIndexCount;
        ignoreList = new HashSet<>(features.ignoreList);
        rawToReduced = new HashMap<>(features.rawToReduced);
        mappingVersion = features.version+1;
        initialRawSize = indexCount;
    }
}
