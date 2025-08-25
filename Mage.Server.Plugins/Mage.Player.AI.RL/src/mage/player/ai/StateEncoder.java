package mage.player.ai;

import mage.MageObject;
import mage.abilities.*;
import mage.abilities.costs.Cost;
import mage.abilities.costs.Costs;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.effects.Effect;
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
 * the given 60 card decks through a first pass of 1,000 simulated mcst and minimax games
 */
public class StateEncoder {
    public static int indexCount;
    private Features features;
    public Set<Integer> featureVector = new HashSet<>();
    private UUID opponentID;
    private UUID myPlayerID;
    public List<Set<Integer>> stateVectors = new ArrayList<>();
    public List<Set<Integer>> microStateVectors = new ArrayList<>();

    public List<Double> stateScores = new ArrayList<>();
    public List<double[]> actionVectors = new ArrayList<>();
    public int initialRawSize = 0;//original max index
    public int mappingVersion = 0;

    public Set<Integer> ignoreList;

    public StateEncoder() {
        //using statics for convenience for now
        //indexCount = 0;
        features = new Features();
        features.setEncoder(this);
        ignoreList = new HashSet<>();
    }
    public void setAgent(UUID me) {
        myPlayerID = me;
    }
    public void setOpponent(UUID op) {
        opponentID = op;
    }
    public Features getFeatures() {return features;}
    public synchronized UUID getMyPlayerID() {return myPlayerID;}
    public synchronized void addAction(double[] actionVec) { actionVectors.add(actionVec); }

    public void processManaCosts(ManaCosts<ManaCost> manaCost, Game game, Features f, Boolean callParent) {
        if(f == null) return;
        //f.addFeature(manaCost.getText());
        f.addNumericFeature("ManaValue", manaCost.manaValue(), callParent);
        for(ManaCost mc : manaCost) {
            f.addFeature(mc.getText());
        }
    }
    public void processCosts(Costs<Cost> costs, ManaCosts<ManaCost> manaCosts, Game game, Features f, Boolean callParent) {
        if(f == null) return;
        //if(c.c) f.addFeature("CanPay"); //use c.canPay()
        if(manaCosts != null && !manaCosts.isEmpty()) processManaCosts(manaCosts, game, f, callParent);
        if(costs == null || costs.isEmpty()) return;
        for(Cost cc : costs) {
            f.addFeature(cc.getText());
        }
    }
    public void processAbility(Ability a, Game game, Features f) {
        if(f == null) return;
        Costs<Cost> c = a.getCosts();
        //for now lets not worry about encoding costs per abilities
        ManaCosts<ManaCost> mcs = a.getManaCostsToPay();
        if(!c.isEmpty() || !mcs.isEmpty()) {
            Features costFeature = f.getSubFeatures("AbilityCost");
            processCosts(c, mcs, game, costFeature, false); //dont propagate mana cost up for abilities
        }
        for(Mode m : a.getModes().getAvailableModes(a, game)) {
            for(Effect e : m.getEffects()) {
                f.parent.addFeature(e.getText(m));//only add feature for abstraction (isn't dynamic)
            }
        }
    }
    public void processActivatedAbility(ActivatedAbility aa, Game game, Features f) {
        if(f == null) return;
        processAbility(aa, game, f);

        if(aa.canActivate(myPlayerID, game).canActivate()) f.addFeature("CanActivate"); //use aa.canActivate()
    }
    public void processTriggeredAbility(TriggeredAbility ta, Game game, Features f) {
        if(f == null) return;
        processAbility(ta, game, f);

        if(!ta.checkTriggeredLimit(game)) f.addFeature("ReachedTriggerLimit"); //use ta.checkTriggeredLimit()
        if(ta.checkUsedAlready(game)) f.addFeature("UsedAlready");//use ta.checkUsedAlready(game)
        if(ta.getTriggerEvent() != null) f.addFeature(ta.getTriggerEvent().getType().name());

    }
    public void processCard(Card c, Game game, Features f) {
        if(f == null) return;
        f.parent.addFeature("Card");//raw universal type of card added for counting purposes

        if(c.isPermanent()) f.addCategory("Permanent");
        //add types
        for (CardType ct : c.getCardType()) {
            f.addCategory(ct.name());
        }
        //add color
        if(c.getColor(game).isRed()) f.addFeature("RedCard");
        if(c.getColor(game).isWhite()) f.addFeature("WhiteCard");
        if(c.getColor(game).isBlack()) f.addFeature("BlackCard");
        if(c.getColor(game).isGreen()) f.addFeature("GreenCard");
        if(c.getColor(game).isBlue()) f.addFeature("BlueCard");
        if(c.getColor(game).isColorless()) f.addFeature("ColorlessCard");
        if(c.getColor(game).isMulticolored()) f.addFeature("MultiColored");

        //add subtypes
        for (SubType st : c.getSubtype(game)) {
            if(!st.name().isEmpty()) f.addFeature(st.name());
        }

        //add cost removing because this never changes, see getManaCoststoPay in abilities
        //ManaCosts<ManaCost> mc = c.getManaCost();
        //processManaCosts(mc, game, f, true);


        //process counters
        Counters counters = c.getCounters(game);
        for (String counterName : counters.keySet()) {
            f.addNumericFeature(counterName, counters.get(counterName).getCount());
        }

    }
    public void processPermBattlefield(Permanent p, Game game, Features f) {
        if(f == null) return;
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
            if(p.canBlockAny(game)) f.addFeature("CanBlock");
            f.addNumericFeature("Damage", p.getDamage());
            f.addNumericFeature("Power", p.getPower().getValue());
            f.addNumericFeature("Toughness", p.getToughness().getValue());
        }
    }
    public void processCardInGraveyard(Card c, Game game, Features f) {
        if(f == null) return;
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
        if(f == null) return;
        //process as card
        processCard(c, game, f);
        //process abilities in hand
        //static abilities
        for (StaticAbility sa : c.getAbilities(game).getStaticAbilities(Zone.HAND)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : c.getAbilities(game).getActivatedAbilities(Zone.HAND)) {
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
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
        if(f == null) return;
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
    public void processManaPool(ManaPool mp, Game game,  Features f) {
        if(f == null) return;
        f.addNumericFeature("GreenMana", mp.getGreen());
        f.addNumericFeature("RedMana", mp.getRed());
        f.addNumericFeature("BlueMana", mp.getBlue());
        f.addNumericFeature("WhiteMana", mp.getWhite());
        f.addNumericFeature("BlackMana", mp.getBlack());
        f.addNumericFeature("ColorlessMana", mp.getColorless());
        //TODO: deal with conditional mana
    }
    public void processOpponentState(Game game, UUID activePlayerID) {
        //switch for perspective reasons
        UUID temp = myPlayerID;
        myPlayerID = opponentID;
        opponentID = temp;

        Player myPlayer = game.getPlayer(myPlayerID);
        //game metadata
        features.addNumericFeature("OpponentLifeTotal", myPlayer.getLife());
        if(myPlayer.canPlayLand()) features.addFeature("OpponentCanPlayLand"); //use features.addFeature(myPlayer.canPlayLand())
        //mana pool
        Features mpFeatures = features.getSubFeatures("OpponentManaPool");
        processManaPool(myPlayer.getManaPool(), game, mpFeatures);

        //start with battlefield
        Battlefield bf = game.getBattlefield();
        Features bfFeatures = features.getSubFeatures("OpponentBattlefield");
        processBattlefield(bf, game, bfFeatures, myPlayerID);

        //now do graveyard
        Graveyard gy = myPlayer.getGraveyard();
        Features gyFeatures = features.getSubFeatures("OpponentGraveyard");
        processGraveyard(gy, game, gyFeatures);

        //now do hand (cards are face down so only keep count of number of cards
        // TODO: keep track of face up cards and exile
        if(myPlayerID==activePlayerID) { //invert perspective
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("Hand");
            processHand(hand, game, handFeatures);
        } else {
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("OpponentCardsInHand", hand.size());
        }
        //switch back
        opponentID = myPlayerID;
        myPlayerID = temp;

    }
    public synchronized void processState(Game game, UUID actingPlayerID, int microCounter) {
        features.stateRefresh();
        featureVector.clear();

        Player myPlayer = game.getPlayer(myPlayerID);

        //game metadata
        features.addFeature(game.getTurnStepType().toString()); //phases
        if(game.isActivePlayer(myPlayerID)) features.addFeature("IsActivePlayer");
        if(actingPlayerID==myPlayerID) features.addFeature("IsActingPlayer");
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
        if(myPlayerID==actingPlayerID) { //keep perspective
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("Hand");
            processHand(hand, game, handFeatures);
        } else {
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("OpponentCardsInHand", hand.size());
        }
        //TODO: add exile

        //lastly do opponent
        processOpponentState(game, actingPlayerID);

        //micro counter
        features.addNumericFeature("Micro Counter", microCounter);

        //mapping version
        features.addNumericFeature("Mapping Version", features.version);

    }
    public void processState(Game game, UUID actingPlayerID) {
        processState(game, actingPlayerID, 0);
    }
    public void processMicroState(Game game, UUID actingPlayerID, int microCounter) {
        processState(game, actingPlayerID, microCounter);
        stateVectors.add(new HashSet<>(featureVector));
    }
    public synchronized void processMacroState(Game game, UUID actingPlayerID) {
        processState(game, actingPlayerID);
        stateVectors.add(new HashSet<>(featureVector));
    }
    // Persist the persistent feature mapping
    public void persistMapping(String filename) throws IOException {

        features.version = mappingVersion;
        features.saveMapping(filename);
    }

    // Load the feature mapping from file
    public void loadMapping(String filename) throws IOException, ClassNotFoundException {
        features = Features.loadMapping(filename);
        features.setEncoder(this);
    }
    // Load the feature mapping from object
    public void loadMapping(Features f) {
        features = f.createDeepCopy();
        features.setEncoder(this);

    }
}
