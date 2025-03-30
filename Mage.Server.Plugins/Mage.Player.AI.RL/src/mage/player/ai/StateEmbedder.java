package mage.player.ai;

import mage.Mana;
import mage.ObjectColor;
import mage.abilities.*;
import mage.abilities.costs.CompositeCost;
import mage.abilities.costs.Cost;
import mage.abilities.costs.Costs;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.effects.Effect;
import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.Graveyard;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.players.Player;


import java.util.*;

/**
 * Deck specific state embedder for reinforcement learning.
 * Before embeddings can be made, the embedder must learn all game features of
 * the given 60 card deck through a first pass of 1,000 simulated mcst games
 */
public class StateEmbedder {
    public static int indexCount;
    private Features features;
    public static boolean[] featureVector;
    private UUID opponentID;
    private UUID myPlayerID;

    public StateEmbedder() {
        //using statics for convenience for now
        indexCount = 0;
        features = new Features();
        featureVector = new boolean[10000];
    }
    public void setAgent(UUID me) {
        myPlayerID = me;
    }
    public void setOpponent(UUID op) {
        opponentID = op;
    }
    //this uses special flag to not lump casting cost in with abilities during aggregation
    public void processManaCosts(ManaCosts<ManaCost> manaCost, Game game, Features f, String flag) {
        //f.addFeature(manaCost.getText());
        f.addNumericFeature("ManaValue_"+flag, manaCost.manaValue());
        for(ManaCost mc : manaCost) {
            f.addFeature(mc.getText()+"_"+flag);
        }
    }
    public void processCosts(Costs<Cost> costs, ManaCosts<ManaCost> manaCosts, Game game, Features f, String flag) {
        //if(c.c) f.addFeature("CanPay"); //use c.canPay()
        if(manaCosts != null && !manaCosts.isEmpty()) processManaCosts(manaCosts, game, f, flag);
        if(costs == null || costs.isEmpty()) return;
        for(Cost cc : costs) {
            f.addFeature(cc.getText());
        }
    }
    public void processAbility(Ability a, Game game, Features f) {
        Costs<Cost> c = a.getCosts();
        ManaCosts<ManaCost> mcs = a.getManaCosts();
        Features costFeature = f.getSubFeatures(mcs.getText() + ", " + c.getText());
        processCosts(c, mcs, game, costFeature, "Ability");

        for(Effect e : a.getAllEffects()) {
            for(Mode m : a.getModes().getAvailableModes(a, game)) {
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

        if(ta.checkTriggeredLimit(game)) f.addFeature("ReachedTriggerLimit"); //use ta.checkTriggeredLimit()
        if(ta.checkUsedAlready(game)) f.addFeature("UsedAlready");//use ta.checkUsedAlready(game)
        if(ta.getTriggerEvent() != null) f.addFeature(ta.getTriggerEvent().getType().name());

    }
    public void processCard(Card c, Game game, Features f) {

        f.addFeature("Card");//raw universal type of card added as feature to pass up to parents for counting purposes

        if(c.isPermanent()) f.addFeature("Permanent");
        //add types
        for (CardType ct : c.getCardType()) {
            f.addFeature(ct.name());
        }
        //add subtypes
        for (SubType st : c.getSubtype(game)) {
            f.addFeature(st.name());
        }
        //add cost
        ManaCosts<ManaCost> mc = c.getManaCost();
        if(!mc.isEmpty()) {
            Features costFeature = f.getSubFeatures("ManaCost");
            processManaCosts(mc, game, costFeature, "CastingCost");
        }
        //add color
        if(c.getColor(game).isRed()) f.addFeature("RedCard");
        if(c.getColor(game).isWhite()) f.addFeature("WhiteCard");
        if(c.getColor(game).isBlack()) f.addFeature("BlackCard");
        if(c.getColor(game).isGreen()) f.addFeature("GreenCard");
        if(c.getColor(game).isBlue()) f.addFeature("BlueCard");
        if(c.getColor(game).isColorless()) f.addFeature("ColorlessCard");
        if(c.getColor(game).isMulticolored()) f.addFeature("isMultiColored");

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


        //process attachments
        for (UUID id : p.getAttachments()) {
            Permanent attachment = game.getPermanent(id);
            //modify name to not count auras/equipment twice
            Features attachmentFeatures = f.getSubFeatures(attachment.getName() + "Attachment");
            processPermBattlefield(attachment, game, attachmentFeatures);
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
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
        }
        //triggered abilities
        for(TriggeredAbility ta : c.getAbilities(game).getTriggeredAbilities(Zone.HAND)) {
            Features taFeatures = f.getSubFeatures(ta.getRule());
            processTriggeredAbility(ta, game, taFeatures);

        }

    }
    public void processBattlefield(Battlefield bf, Game game, Features f) {
        for (Permanent p : bf.getAllActivePermanents(myPlayerID)) {
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
    public void processState(Game game) {
        features.resetOccurrences();
        Arrays.fill(featureVector, false);

        Player myPlayer = game.getPlayer(myPlayerID);

        //game metadata
        features.addNumericFeature("LifeTotal", myPlayer.getLife());
        if(myPlayer.canPlayLand()) features.addFeature("CanPlayLand"); //use features.addFeature(myPlayer.canPlayLand())
        features.addFeature(game.getPhase().getType().name());

        //start with battlefield
        Battlefield bf = game.getBattlefield();
        Features bfFeatures = features.getSubFeatures("Battlefield");
        processBattlefield(bf, game, features);

        //now do graveyard
        Graveyard gy = myPlayer.getGraveyard();
        Features gyFeatures = features.getSubFeatures("Graveyard");
        processGraveyard(gy, game, gyFeatures);

        //now do hand
        Cards hand = myPlayer.getHand();
        Features handFeatures = features.getSubFeatures("Hand");
        processHand(hand, game, handFeatures);


        System.out.println(Arrays.toString(featureVector));

    }
}
