package mage.player.ai;

import mage.abilities.*;
import mage.abilities.effects.Effect;
import mage.cards.Card;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.players.Player;


import java.util.*;

/**
 * Deck specific state embedder for reinforcement learning.
 * Before embeddings can be made, the embedder must learn all game features of
 * the given 60 card deck through a first pass of 1,000 simulated mcst games
 */
public class StateEmbedder {
    public static int indexCount;
    private DeckCardLists deckList;

    private CardRepository cardRepo;
    //layered feature structure
    private Features features;
    private UUID opponentID;
    private UUID myPlayerID;
    public StateEmbedder(DeckCardLists dList) {
        deckList = dList;
        cardRepo = CardRepository.instance;
        indexCount = 0;
    }
    public StateEmbedder() {
        //using statics for convenience for now
        indexCount = 0;
        features = new Features();
    }
    public void setAgent(UUID me) {
        myPlayerID = me;
    }
    public void setOpponent(UUID op) {
        opponentID = op;
    }
    public void processActivatedAbility(ActivatedAbility aa, Game game, Features f) {
        f.addFeature("CanActivate"); //use aa.canActivate()
        f.addFeature(aa.getCosts().getText());
        //f.addNumericFeature("MaxActivationsPerTurn", aa.getMaxActivationsPerTurn(game));
        for(Effect e : aa.getAllEffects()) {
            for(Mode m : aa.getModes().getAvailableModes(aa, game)) {
                f.addFeature(e.getText(m));
            }
        }
    }
    public void processTriggeredAbility(TriggeredAbility ta, Game game, Features f) {
        f.addFeature("ReachedTriggerLimit"); //use ta.checkTriggeredLimit()
        f.addFeature("UsedAlready");//use ta.checkUsedAlready(game)
        if(ta.getTriggerEvent() != null) f.addFeature(ta.getTriggerEvent().getType().name());
        for(Effect e : ta.getAllEffects()) {
            for(Mode m : ta.getModes().getAvailableModes(ta, game)) {
                f.addFeature(e.getText(m));
            }
        }
    }
    public void processPerm(Permanent p, Game game, Features f) {
        //add types
        for (CardType ct : p.getCardType()) {
            f.addFeature(ct.name());
        }
        //add subtypes
        for (SubType st : p.getSubtype(game)) {
            f.addFeature(st.name());
        }
        //process attachments
        for (UUID id : p.getAttachments()) {
            Permanent attachment = game.getPermanent(id);
            //modify name to not count auras/equipment twice
            f.addFeature(attachment.getName() + "Attachment");
            Features attachmentFeatures = f.getSubFeatures(attachment.getName() + "Attachment");
            processPerm(attachment, game, attachmentFeatures);
        }

        //process counters
        Counters counters = p.getCounters(game);
        for (String c : counters.keySet()) {
            f.addNumericFeature(c, counters.get(c).getCount());
        }

        //process abilities
        //static abilities
        for (StaticAbility sa : p.getAbilities(game).getStaticAbilities(Zone.BATTLEFIELD)) {
            f.addFeature(sa.getRule());
        }
        //activated abilities
        for(ActivatedAbility aa : p.getAbilities(game).getActivatedAbilities(Zone.BATTLEFIELD)) {
            f.addFeature(aa.getRule());
            Features aaFeatures = f.getSubFeatures(aa.getRule());
            processActivatedAbility(aa, game, aaFeatures);
        }
        //triggered abilities
        for(TriggeredAbility ta : p.getAbilities(game).getTriggeredAbilities(Zone.BATTLEFIELD)) {
            f.addFeature(ta.getRule());
            Features taFeatures = f.getSubFeatures(ta.getRule());
            processTriggeredAbility(ta, game, taFeatures);

        }

        //is creature
        if(p.isCreature(game)) {

            f.addNumericFeature("Power", p.getPower().getValue());
            f.addNumericFeature("Toughness", p.getToughness().getValue());
            f.addFeature("CanAttack"); //use p.canAttack()
            f.addFeature("CanBlock"); //use p.canBlock()
        }
        //is artifact


    }
    //for first pass
    public void processState(Game game) {
        features.resetOccurrences();
        Player myPlayer = game.getPlayer(myPlayerID);

        //game metadata
        features.addNumericFeature("LifeTotal", myPlayer.getLife());
        features.addFeature("CanPlayLand"); //use features.addFeature(myPlayer.canPlayLand())
        features.addFeature(game.getPhase().getType().name());

        //start with battlefield
        Battlefield bf = game.getBattlefield();

        for (Permanent p : bf.getAllActivePermanents(myPlayerID)) {
            features.addFeature("Permanent");//keep count of permanents
            features.addFeature(p.getName());
            Features permFeatures = features.getSubFeatures(p.getName());
            processPerm(p, game, permFeatures);

        }

    }

//    public int[] gameToVec(GameState game) {
//
//        int[] out = new int[256];
//        int outIndex = 0;
//        //FecundGreenshell turtle;
//        //PermanentCard pc;
//        //pc.getAbilities(state).getActivatedAbilities().get(0).ge
//        Map<String, Long> cardNumToAmount = deckList.getCards().stream().collect(groupingBy(DeckCardInfo::getCardNumber, counting()));
//        Map<String, String> cardNumToName = deckList.getCards().stream().collect(toMap(DeckCardInfo::getCardNumber, DeckCardInfo::getCardName, BinaryOperator.maxBy(Comparator.naturalOrder())));
//        for(String deckCardNum : cardNumToAmount.keySet()) {
//            Long amount = cardNumToAmount.get(deckCardNum);
//            //DeckCardInfo deckCard = deckList.getCards().get(i);
//            //CardInfo cardInfo = cardRepo.findCard(deckCard.getCardNumber(), deckCard.getCardNumber());
//            //List<CardType> permTypes = Arrays.asList(CardType.ARTIFACT, CardType.LAND, CardType.CREATURE, CardType.ENCHANTMENT, CardType.PLANESWALKER);
//            //if(!disjoint(cardInfo.getTypes(), permTypes)) { //is permanent
//                int copiesFound = (int) game.getBattlefield().getAllActivePermanents()
//                        .stream()
//                        .filter(perm -> perm.getCardNumber().equals(deckCardNum)
//                        && perm.isControlledBy(state.getActivePlayerId()))
//                        .count();
//                System.out.print(cardNumToName.get(deckCardNum) + ", ");
//                System.out.println(copiesFound);
//                assert (copiesFound <= amount);
//                for(int j = 0; j < amount; j++) {
//                    out[outIndex] = (j < copiesFound) ? 1 : 0;
//                    outIndex++;
//                }
//            //}
//        }
//        return out;
//    }
}
