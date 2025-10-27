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
import mage.game.Exile;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.Graveyard;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.game.stack.SpellStack;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.util.*;

/**
 * Deck specific state encoder for reinforcement learning.
 * Before vectors can be made, the encoder must learn all game features of
 * the given 60 card decks through a first pass of 1,000 simulated mcst and minimax games
 */
public class StateEncoder {
    public static int indexCount;
    public static volatile ImmutableRoaringBitmap globalIgnore;
    public volatile RoaringBitmap seenFeatures;
    public static boolean perfectInfo = true;
    private final Features features;
    public Set<Integer> featureVector = new HashSet<>();
    private UUID opponentID;
    private UUID myPlayerID;

    public List<LabeledState>  labeledStates = new ArrayList<>();

    public Set<Integer> ignoreList;

    public StateEncoder() {
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

        //removing static cost because this never changes, see getManaCostsToPay in abilities (maybe add back later for abstraction for the network)
        //ManaCosts<ManaCost> mc = c.getManaCost();
        //processManaCosts(mc, game, f, true);


        //process counters
        Counters counters = c.getCounters(game);
        for (String counterName : counters.keySet()) {
            f.addNumericFeature(counterName, counters.get(counterName).getCount());
        }

    }
    public void processPermBattlefield(Permanent p, Game game, Features f) {

        processCardInZone(p, Zone.BATTLEFIELD, game, f);
        //is tapped?
        if(p.isTapped()) f.addFeature("Tapped");

        //process attachments
        for (UUID id : p.getAttachments()) {
            Permanent attachment = game.getPermanent(id);
            if(attachment == null) continue;
            //modify name to not count auras/equipment twice
            //don't pass pooled attachment features up, or they will be counted twice
            Features attachmentFeatures = f.getSubFeatures(attachment.getName(), false);
            processPermBattlefield(attachment, game, attachmentFeatures);

        }
        if(p.isCreature(game)) {
            if(p.canAttack(opponentID, game)) f.addFeature("CanAttack"); //use p.canAttack()
            if(p.canBlockAny(game)) f.addFeature("CanBlock");
            if(p.isAttacking()) {
                f.addFeature("Attacking");
                for(UUID blockerId : game.getCombat().findGroup(p.getId()).getBlockers()) {
                    Permanent blocker  = game.getPermanent(blockerId);
                    f.addFeature(blocker.getName() + " Blocking");
                }
            }
            f.addNumericFeature("Damage", p.getDamage());
            f.addNumericFeature("Power", p.getPower().getValue());
            f.addNumericFeature("Toughness", p.getToughness().getValue());
        }
    }
    public void processCardInZone(Card c, Zone z, Game game, Features f) {

        //process as card
        processCard(c, game, f);
        //process abilities in gy
        //static abilities
        for (StaticAbility sa : c.getAbilities(game).getStaticAbilities(z)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : c.getAbilities(game).getActivatedAbilities(z)) {
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
        }
        //triggered abilities
        for(TriggeredAbility ta : c.getAbilities(game).getTriggeredAbilities(z)) {
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
            processCardInZone(c, Zone.GRAVEYARD, game, graveCardFeatures);
        }
    }
    public void processHand(Cards hand, Game game, Features f) {
        for (Card c : hand.getCards(game)) {
            Features handCardFeatures = f.getSubFeatures(c.getName());
            processCardInZone(c, Zone.HAND, game, handCardFeatures);
        }
    }
    public void processStackObject(StackObject so, int stackPosition, Game game, Features f) {

        f.addNumericFeature("StackPosition", stackPosition, false);
        if(so.getControllerId().equals(myPlayerID)) f.addFeature("isController");
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
    public void processExileZone(ExileZone exileZone, Game game, Features f) {
        for (Card c : exileZone.getCards(game)) {
            Features graveCardFeatures = f.getSubFeatures(c.getName());
            processCardInZone(c, Zone.EXILED, game, graveCardFeatures);
        }
    }
    public void processExile(Exile exile, Game game, Features f) {

        for (ExileZone ez : exile.getExileZones()) {
            Features exileZoneFeatures = f.getSubFeatures(ez.getName());
            processExileZone(ez, game, exileZoneFeatures);
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
        //TODO: special handling of revealed cards
        if(myPlayerID==activePlayerID || perfectInfo) { //invert perspective
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("OpponentHand");
            processHand(hand, game, handFeatures);
        } else {
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("CardsInHand", hand.size());
        }
        Features exileFeatures = features.getSubFeatures("Exile");
        processExile(game.getExile(), game, exileFeatures);
        //switch back
        opponentID = myPlayerID;
        myPlayerID = temp;

    }

    /**
     * vectorizes (hashes) the entire game state in a neural network-learnable way. These vectors are massive and sparse -
     * they are designed to have redundant features masked before training and used with a massive embedding bag in pytorch
     * @param game S
     * @param actingPlayerID the player who is making the decision at this state
     * @param decisionType type of decision being made at this state (choose_target, choose_use, choose etc.)
     * @param decisionsText informative context about the micro decision being made to be hashed as its own feature for the network
     * @return set of active indices in the sparse binary vector
     */
    public synchronized Set<Integer> processState(Game game, UUID actingPlayerID, MCTSPlayer.NextAction decisionType, String decisionsText) {
        features.stateRefresh();
        featureVector.clear();

        Player myPlayer = game.getPlayer(myPlayerID);
        //decision type
        features.addFeature(decisionType.toString());
        //decision state
        features.addFeature(decisionsText);

        //game metadata
        features.addFeature(game.getTurnStepType().toString()); //phases
        if(game.isActivePlayer(myPlayerID)) features.addFeature("IsActivePlayer");
        if(actingPlayerID.equals(myPlayerID)) features.addFeature("IsDecisionPlayer");
        features.addNumericFeature("LifeTotal", myPlayer.getLife());
        if(myPlayer.canPlayLand()) features.addFeature("CanPlayLand");

        //stack
        Features stackFeatures = features.getSubFeatures("Stack", false);
        processStack(game.getStack(), game, stackFeatures);

        //mana pool
        Features mpFeatures = features.getSubFeatures("ManaPool", false);
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
        if(myPlayerID==actingPlayerID || perfectInfo) { //keep perspective
            Cards hand = myPlayer.getHand();
            Features handFeatures = features.getSubFeatures("Hand");
            processHand(hand, game, handFeatures);
        } else {
            Cards hand = myPlayer.getHand();
            features.addNumericFeature("OpponentCardsInHand", hand.size());
        }
        Features exileFeatures = features.getSubFeatures("Exile");
        processExile(game.getExile(), game, exileFeatures);

        //lastly do opponent
        processOpponentState(game, actingPlayerID);

        return new HashSet<>(featureVector);

    }

    public synchronized Set<Integer> processState(Game game, UUID actingPlayerID) {
        return processState(game, actingPlayerID, MCTSPlayer.NextAction.PRIORITY,"priority");
    }

    public void addLabeledState(Set<Integer> stateVector, int[] actionVector, double score, MCTSPlayer.NextAction actionType, boolean isPlayer) {
        LabeledState newState = new LabeledState(stateVector, actionVector, score, actionType, isPlayer);
        labeledStates.add(newState);
    }
}
