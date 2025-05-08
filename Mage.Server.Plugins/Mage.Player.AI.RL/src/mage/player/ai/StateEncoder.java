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
    private int originalVectorSize;
    private Features features;
    public static BitSet featureVector;
    private UUID opponentID;
    private UUID myPlayerID;
    public List<BitSet> macroStateVectors = new ArrayList<>();
    public List<BitSet> microStateVectors = new ArrayList<>();

    public List<Double> stateScores = new ArrayList<>();
    public static final int COMPRESSED_VECTOR_SIZE = 4000;
    public int initialSize = 4000;


    public Set<Integer> ignoreList;

    public StateEncoder() {
        //using statics for convenience for now
        indexCount = 0;
        reducedIndexCount = 1; //pending features map to zero
        originalVectorSize = 0;
        features = new Features();
        featureVector = new BitSet(30000);
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
            if(p.canBlock(opponentID, game)) f.addFeature("CanBlock"); //use p.canBlock()
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
        Cards hand = myPlayer.getHand();
        features.addNumericFeature("OpponentCardsInHand", hand.size());

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
    public void processState(Game game) {
        features.stateRefresh();
        featureVector.clear();

        Player myPlayer = game.getPlayer(myPlayerID);

        //game metadata
        features.addFeature(game.getPhase().getType().name());
        if(game.isActivePlayer(myPlayerID)) features.addFeature("IsActivePlayer");
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
        Cards hand = myPlayer.getHand();
        Features handFeatures = features.getSubFeatures("Hand");
        processHand(hand, game, handFeatures);

        //TODO: add exile

        //lastly do opponent
        processOpponentState(game);

        //update reduced vector
        //updateReducedVector();
        //stateVectors.add(Arrays.copyOf(featureVector, 30000));
    }
    public void processMicroState(Game game) {
        processState(game);
        microStateVectors.add((BitSet) featureVector.clone());
    }
    public void processMacroState(Game game) {
        processState(game);
        macroStateVectors.add((BitSet) featureVector.clone());
    }

    /*
    public void updateReducedVector() {
        //map normal features
        for(int i = 0; i < originalVectorSize; i++) {
            if(!featureVector[i]) continue;
            int reducedIndex = rawToReduced[i]; //pending features map to zero
            reducedFeatureVector[reducedIndex] = true;
        }
        //update matrix for each pending feature
        for (int f : pendingFeatures.keySet()) {
            boolean[][] m = pendingFeatures.get(f);
            boolean allIndependent = true;
            for(int i = 1; i < m.length; i++) {
                if(rawToReduced[i+f] != 0) continue;//only care to check un finalized features
                boolean isIndependent = true;
                for(int j = 0; j < m.length; j++) {
                    m[i][j] = (featureVector[i+f] != featureVector[j+f]) || m[i][j]; //don't change if true
                    if(rawToReduced[j+f] != 0 && !m[i][j]) {//compared to feature is finalized and they aren't independent
                        isIndependent = false;
                    }
                }
                if(isIndependent) {
                    //finalize feature if independent (and hasn't been finalized)
                    System.out.printf("pending feature at raw index %d has been finalized at reduced index %d\n", i+f, reducedIndexCount);
                    rawToReduced[i+f] = reducedIndexCount;
                    reducedFeatureVector[reducedIndexCount++] = featureVector[i+f]; //can be added silently (feature itself didn't fire)
                } else {
                    allIndependent = false;
                }
            }
            if(allIndependent) {
                pendingFeatures.remove(f);
                System.out.printf("entire pending batch from %d has been successfully finalized\n", f);
            }
        }
        //matrix for batch of new features
        int batchSize = indexCount - originalVectorSize;
        boolean[][] occurrenceMatrix = new boolean[batchSize][batchSize]; //maps if 2 features occurred independently for each pair
        if(batchSize > 0) {
            //add first new feature to reduced vector
            System.out.printf("new reduced feature at raw index %d has been finalized at reduced index %d representing a batch of %d new features\n", originalVectorSize, reducedIndexCount, batchSize);
            rawToReduced[originalVectorSize] = reducedIndexCount;
            reducedFeatureVector[reducedIndexCount++] = true;
            pendingFeatures.put(originalVectorSize, occurrenceMatrix); //pending features are stored in by their first feature in batch

        }
        //lastly update original size
        originalVectorSize = indexCount;
    }
    */
    public BitSet getCompressedVector(BitSet rawState) {
        BitSet state = new BitSet(4000);
        for(int k = 0, j = 0; j < indexCount && k < initialSize && k < 4000; j++) {
            if(!ignoreList.contains(j)) {
                state.set(k++, rawState.get(j));
            }
        }
        return state;
    }
    // Persist the persistent feature mapping
    public void persistMapping(String filename) throws IOException {
        features.globalIndexCount = indexCount;
        features.ignoreList = new HashSet<>(ignoreList);
        features.saveMapping(filename);
    }

    // Load the feature mapping from file
    public void loadMapping(String filename) throws IOException, ClassNotFoundException {
        features = Features.loadMapping(filename);
        indexCount = features.globalIndexCount;
        ignoreList = new HashSet<>(features.ignoreList);
        initialSize = indexCount - ignoreList.size();
    }
}
