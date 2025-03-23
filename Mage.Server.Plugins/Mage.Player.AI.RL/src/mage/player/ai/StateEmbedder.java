package mage.player.ai;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardRepository;
import mage.game.GameState;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;


import java.util.*;
import java.util.function.BinaryOperator;

import static java.util.stream.Collectors.*;

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
    public StateEmbedder(DeckCardLists dList) {
        deckList = dList;
        cardRepo = CardRepository.instance;
        indexCount = 0;
    }
    public void processPerm(Permanent p, GameState state, Features f) {
        //process attachments
        for (UUID id : p.getAttachments()) {
            Permanent attachment = state.getPermanent(id);
            Class<? extends Permanent> attachmentClass = attachment.getClass();
            f.addFeature(attachmentClass);
            Features attachmentFeatures = f.getSubFeatures(attachmentClass);
            processPerm(attachment, state, attachmentFeatures);
        }

        //process counters

        //process abilities

    }
    //for first pass
    public void processState(GameState state) {
        features.resetOccurances();
        Battlefield bf = state.getBattlefield();


        Map<Class<? extends Permanent>, Integer> permCounts = new HashMap<>();
        for (Permanent p : bf.getAllActivePermanents(state.getActivePlayerId())) {
            Class<? extends Permanent> permClass = p.getClass();
            features.addFeature(permClass);
            Features permFeatures = features.getSubFeatures(permClass);
            processPerm(p, state, permFeatures);

        }

    }

    public int[] stateToVec(GameState state) {
        int[] out = new int[256];
        int outIndex = 0;
        //FecundGreenshell turtle;
        //PermanentCard pc;
        //pc.getAbilities(state).getActivatedAbilities().get(0).ge
        Map<String, Long> cardNumToAmount = deckList.getCards().stream().collect(groupingBy(DeckCardInfo::getCardNumber, counting()));
        Map<String, String> cardNumToName = deckList.getCards().stream().collect(toMap(DeckCardInfo::getCardNumber, DeckCardInfo::getCardName, BinaryOperator.maxBy(Comparator.naturalOrder())));
        for(String deckCardNum : cardNumToAmount.keySet()) {
            Long amount = cardNumToAmount.get(deckCardNum);
            //DeckCardInfo deckCard = deckList.getCards().get(i);
            //CardInfo cardInfo = cardRepo.findCard(deckCard.getCardNumber(), deckCard.getCardNumber());
            //List<CardType> permTypes = Arrays.asList(CardType.ARTIFACT, CardType.LAND, CardType.CREATURE, CardType.ENCHANTMENT, CardType.PLANESWALKER);
            //if(!disjoint(cardInfo.getTypes(), permTypes)) { //is permanent
                int copiesFound = (int) state.getBattlefield().getAllActivePermanents()
                        .stream()
                        .filter(perm -> perm.getCardNumber().equals(deckCardNum)
                        && perm.isControlledBy(state.getActivePlayerId()))
                        .count();
                System.out.print(cardNumToName.get(deckCardNum) + ", ");
                System.out.println(copiesFound);
                assert (copiesFound <= amount);
                for(int j = 0; j < amount; j++) {
                    out[outIndex] = (j < copiesFound) ? 1 : 0;
                    outIndex++;
                }
            //}
        }
        return out;
    }
}
