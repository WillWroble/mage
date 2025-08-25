package mage.player.ai;

import mage.ConditionalMana;
import mage.MageObject;
import mage.Mana;
import mage.ObjectColor;
import mage.abilities.*;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.Costs;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.costs.mana.VariableManaCost;
import mage.abilities.effects.Effect;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.abilities.mana.ManaOptions;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.choices.ChoiceColor;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.command.CommandObject;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetCard;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * AI: server side bot with monte carlo logic (experimental, the latest version)
 * <p>
 * Simple implementation for random play, outdate and do not support,
 * see <a href="https://github.com/magefree/mage/issues/7075">more details here</a>
 *
 * @author BetaSteward_at_googlemail.com
 */
public class MCTSPlayer extends ComputerPlayer {

    public boolean lastToAct = false;
    private NextAction nextAction;
    public long dirichletSeed = 0;
    private static final Logger logger = Logger.getLogger(MCTSPlayer.class);

    public int chooseTargetCount = 0;
    public int makeChoiceCount = 0;
    public static boolean PRINT_CHOOSE_DIALOGUES = false;


    public enum NextAction {
        PRIORITY, SELECT_ATTACKERS, SELECT_BLOCKERS, CHOOSE_TARGET, MAKE_CHOICE
    }

    public MCTSPlayer(UUID id) {
        super(id);
    }

    public MCTSPlayer(final MCTSPlayer player) {
        super(player);
        this.nextAction = player.nextAction;
    }

    @Override
    public MCTSPlayer copy() {
        return new MCTSPlayer(this);
    }

    protected List<ActivatedAbility> getPlayableAbilities(Game game) {
        List<ActivatedAbility> playables = getPlayable(game, true);
        List<ActivatedAbility> out = new ArrayList<>();
        for (ActivatedAbility aa : playables) {
            if (!aa.isManaAbility()) {
                out.add(aa);
            }
        }
        //playables.add(new PassAbility());
        out.add(new PassAbility());
        return out;
    }

    public List<Ability> getPlayableOptions(Game game) {
        //if(true) return simulatePriority(game);
        List<Ability> all = new ArrayList<>();
        List<ActivatedAbility> playables = getPlayableAbilities(game);
        for (ActivatedAbility ability : playables) {
            List<Ability> options = game.getPlayer(playerId).getPlayableOptions(ability, game);
            if (options.isEmpty()) {
                if (!ability.getManaCosts().getVariableCosts().isEmpty()) {
                    simulateVariableCosts(ability, all, game);
                } else {
                    all.add(ability);
                }
            } else {
                for (Ability option : options) {
                    if (!ability.getManaCosts().getVariableCosts().isEmpty()) {
                        simulateVariableCosts(option, all, game);
                    } else {
                        all.add(option);
                    }
                }
            }
        }
        return all;
    }

    protected void simulateVariableCosts(Ability ability, List<Ability> options, Game game) {
        int numAvailable = getAvailableManaProducers(game).size() - ability.getManaCosts().manaValue();
        int start = 0;
        if (!(ability instanceof SpellAbility)) {
            //only use x=0 on spell abilities
            if (numAvailable == 0)
                return;
            else
                start = 1;
        }
        for (int i = start; i < numAvailable; i++) {
            Ability newAbility = ability.copy();
            newAbility.addManaCostsToPay(new GenericManaCost(i));
            options.add(newAbility);
        }
    }

    public List<List<UUID>> getAttacks(Game game) {
        List<List<UUID>> engagements = new ArrayList<>();
        List<Permanent> attackersList = super.getAvailableAttackers(game);
        //use binary digits to calculate powerset of attackers
        int powerElements = (int) Math.pow(2, attackersList.size());
        StringBuilder binary = new StringBuilder();
        for (int i = powerElements - 1; i >= 0; i--) {
            binary.setLength(0);
            binary.append(Integer.toBinaryString(i));
            while (binary.length() < attackersList.size()) {
                binary.insert(0, '0');
            }
            List<UUID> engagement = new ArrayList<>();
            for (int j = 0; j < attackersList.size(); j++) {
                if (binary.charAt(j) == '1') {
                    engagement.add(attackersList.get(j).getId());
                }
            }
            engagements.add(engagement);
        }
        return engagements;
    }

    public List<List<List<UUID>>> getBlocks(Game game) {
        List<List<List<UUID>>> engagements = new ArrayList<>();
        int numGroups = game.getCombat().getGroups().size();
        if (numGroups == 0) {
            return engagements;
        }

        //add a node with no blockers
        List<List<UUID>> engagement = new ArrayList<>();
        for (int i = 0; i < numGroups; i++) {
            engagement.add(new ArrayList<UUID>());
        }
        engagements.add(engagement);

        List<Permanent> blockers = getAvailableBlockers(game);
        addBlocker(game, engagement, blockers, engagements);

        return engagements;
    }

    private List<List<UUID>> copyEngagement(List<List<UUID>> engagement) {
        List<List<UUID>> newEngagement = new ArrayList<>();
        for (List<UUID> group : engagement) {
            newEngagement.add(new ArrayList<>(group));
        }
        return newEngagement;
    }

    protected void addBlocker(Game game, List<List<UUID>> engagement, List<Permanent> blockers, List<List<List<UUID>>> engagements) {
        if (blockers.isEmpty())
            return;
        int numGroups = game.getCombat().getGroups().size();
        //try to block each attacker with each potential blocker
        Permanent blocker = blockers.get(0);
//        if (logger.isDebugEnabled())
//            logger.debug("simulating -- block:" + blocker);
        List<Permanent> remaining = remove(blockers, blocker);
        for (int i = 0; i < numGroups; i++) {
            if (game.getCombat().getGroups().get(i).canBlock(blocker, game)) {
                List<List<UUID>> newEngagement = copyEngagement(engagement);
                newEngagement.get(i).add(blocker.getId());
                engagements.add(newEngagement);
//                    logger.debug("simulating -- found redundant block combination");
                addBlocker(game, newEngagement, remaining, engagements);  // and recurse minus the used blocker
            }
        }
        addBlocker(game, engagement, remaining, engagements);
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public void setNextAction(NextAction action) {
        this.nextAction = action;
    }

    @Override
    public void restore(Player player) {
        // simulated player can be created from any player type
        super.restore(player.getRealPlayer());
    }

    @Override
    public boolean priority(Game game) {
        chooseTargetAction.clear();
        choiceAction.clear();
        assert (game.getState().getValue(true, game).equals(game.getLastPriority().getState().getValue(true, game.getLastPriority())));
        game.pause();
        lastToAct = true;
        nextAction = NextAction.PRIORITY;
        return false;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_ATTACKERS;
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_BLOCKERS;
    }

    public static void getAllPossible(Set<Set<UUID>> out, Set<UUID> possible, Target target, Ability source, Game game, UUID myID) {
        if (target.isChosen(game)) out.add(new HashSet<>(target.getTargets()));
        for (UUID id : possible) {
            if (!target.canTarget(myID, id, source, game)) continue;
            target.add(id, game);
            if (out.contains(new HashSet<>(target.getTargets()))) {
                target.remove(id);
                continue;
            }
            Set<UUID> copy = new HashSet<>(possible);
            copy.remove(id);
            getAllPossible(out, copy, target, source, game, myID);
            target.remove(id);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (game.isPaused())
            return super.chooseTarget(outcome, target, source, game); //if game is already paused don't overwrite last decision
        //for choosing targets of triggered abilities
        if (PRINT_CHOOSE_DIALOGUES)
            logger.info("CALLING CHOOSE TARGET: " + (source == null ? "null" : source.toString()));
        if (chooseTargetCount < chooseTargetAction.size()) {
            StringBuilder sb = PRINT_CHOOSE_DIALOGUES ? new StringBuilder() : null;
            for (UUID id : chooseTargetAction.get(chooseTargetCount)) {
                if (!target.canTarget(getId(), id, source, game)) continue;
                target.addTarget(id, source, game);
                if (sb != null) {
                    sb.append(String.format("tried target: %s ", game.getObject(id).toString()));
                }
            }
            if (sb != null) {
                logger.info(sb.toString());
            }
            chooseTargetCount++;
            return true;
        }
        Set<UUID> possible = target.possibleTargets(getId(), game);
        chooseTargetOptions.clear();
        getAllPossible(chooseTargetOptions, possible, target.copy(), source, game, getId());
        game.pause();
        lastToAct = true;
        //if(chooseTargetCount > 0) logger.info("MICRO DECISION CHAIN");
        nextAction = NextAction.CHOOSE_TARGET;
        return super.chooseTarget(outcome, target, source, game);//continue with default target until able to pause
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        //for discarding
        return chooseTarget(outcome, target, source, game);

    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (outcome == Outcome.PutManaInPool || game.isPaused()) {
            return super.choose(outcome, choice, game);
        }
        //for choosing colors/types etc
        if (PRINT_CHOOSE_DIALOGUES) logger.info("CALLING MAKE CHOICE: " + choice.toString());
        if (makeChoiceCount < choiceAction.size()) {
            String chosen = choiceAction.get(makeChoiceCount);
            choice.setChoice(chosen);
            if (PRINT_CHOOSE_DIALOGUES) logger.info(String.format("tried choice: %s ", chosen));
            makeChoiceCount++;
            return true;
        }
        choiceOptions = new HashSet<>(choice.getChoices());
        game.pause();
        lastToAct = true;
        nextAction = NextAction.MAKE_CHOICE;
        return super.choose(outcome, choice, game);
    }

}


    //overridden for optimization (doesnt need to copy state again since this is called after the fact
//    public List<ActivatedAbility> getPlayable(Game originalGame, boolean hidden, Zone fromZone, boolean hideDuplicatedAbilities) {
//        List<ActivatedAbility> playable = new ArrayList<>();
//
//        ManaOptions availableMana = getManaAvailable(originalGame); // get available mana options (mana pool and conditional mana added (but conditional still lose condition))
//        boolean fromAll = fromZone.equals(Zone.ALL);
//        if (hidden && (fromAll || fromZone == Zone.HAND)) {
//            for (Card card : hand.getCards(originalGame)) {
//                for (Ability ability : card.getAbilities(originalGame)) { // gets this activated ability from hand? (Morph?)
//                    if (ability.getZone().match(Zone.HAND)) {
//                        boolean isPlaySpell = (ability instanceof SpellAbility);
//                        boolean isPlayLand = (ability instanceof PlayLandAbility);
//
//                        // play land restrictions
//                        if (isPlayLand && originalGame.getContinuousEffects().preventedByRuleModification(
//                                GameEvent.getEvent(GameEvent.EventType.PLAY_LAND, ability.getSourceId(),
//                                        ability, this.getId()), ability, originalGame, true)) {
//                            continue;
//                        }
//                        // cast spell restrictions 1
//                        GameEvent castEvent = GameEvent.getEvent(GameEvent.EventType.CAST_SPELL,
//                                ability.getId(), ability, this.getId());
//                        castEvent.setZone(fromZone);
//                        if (isPlaySpell && originalGame.getContinuousEffects().preventedByRuleModification(
//                                castEvent, ability, originalGame, true)) {
//                            continue;
//                        }
//                        // cast spell restrictions 2
//                        GameEvent castLateEvent = GameEvent.getEvent(GameEvent.EventType.CAST_SPELL_LATE,
//                                ability.getId(), ability, this.getId());
//                        castLateEvent.setZone(fromZone);
//                        if (isPlaySpell && originalGame.getContinuousEffects().preventedByRuleModification(
//                                castLateEvent, ability, originalGame, true)) {
//                            continue;
//                        }
//
//                        ActivatedAbility playAbility = findActivatedAbilityFromPlayable(card, availableMana, ability, originalGame);
//                        if (playAbility != null && !playable.contains(playAbility)) {
//                            playable.add(playAbility);
//                        }
//                    }
//                }
//            }
//        }
//
//        if (fromAll || fromZone == Zone.GRAVEYARD) {
//            for (UUID playerId : originalGame.getState().getPlayersInRange(getId(), originalGame)) {
//                Player player = originalGame.getPlayer(playerId);
//                if (player == null) {
//                    continue;
//                }
//                for (Card card : player.getGraveyard().getCards(originalGame)) {
//                    getPlayableFromObjectAll(originalGame, Zone.GRAVEYARD, card, availableMana, playable);
//                }
//            }
//        }
//
//        if (fromAll || fromZone == Zone.EXILED) {
//            for (ExileZone exile : originalGame.getExile().getExileZones()) {
//                for (Card card : exile.getCards(originalGame)) {
//                    getPlayableFromObjectAll(originalGame, Zone.EXILED, card, availableMana, playable);
//                }
//            }
//        }
//
//        // check to play revealed cards
//        if (fromAll) {
//            for (Cards revealedCards : originalGame.getState().getRevealed().values()) {
//                for (Card card : revealedCards.getCards(originalGame)) {
//                    // revealed cards can be from any zones
//                    getPlayableFromObjectAll(originalGame, originalGame.getState().getZone(card.getId()), card, availableMana, playable);
//                }
//            }
//        }
//
//        // outside cards
//        if (fromAll || fromZone == Zone.OUTSIDE) {
//            // companion cards
//            for (Cards companionCards : originalGame.getState().getCompanion().values()) {
//                for (Card card : companionCards.getCards(originalGame)) {
//                    getPlayableFromObjectAll(originalGame, Zone.OUTSIDE, card, availableMana, playable);
//                }
//            }
//
//            // sideboard cards (example: Wish)
//            for (UUID sideboardCardId : this.getSideboard()) {
//                Card sideboardCard = originalGame.getCard(sideboardCardId);
//                if (sideboardCard != null) {
//                    getPlayableFromObjectAll(originalGame, Zone.OUTSIDE, sideboardCard, availableMana, playable);
//                }
//            }
//        }
//
//        // check if it's possible to play the top card of a library
//        if (fromAll || fromZone == Zone.LIBRARY) {
//            for (UUID playerInRangeId : originalGame.getState().getPlayersInRange(getId(), originalGame)) {
//                Player player = originalGame.getPlayer(playerInRangeId);
//                if (player != null && player.getLibrary().hasCards()) {
//                    Card card = player.getLibrary().getFromTop(originalGame);
//                    if (card != null) {
//                        getPlayableFromObjectAll(originalGame, Zone.LIBRARY, card, availableMana, playable);
//                    }
//                }
//            }
//        }
//
//        // check the hand zone (Sen Triplets)
//        // TODO: remove direct hand check (reveal fix in Sen Triplets)?
//        // human games: cards from opponent's hand must be revealed before play
//        // AI games: computer can see and play cards from opponent's hand without reveal
//        if (fromAll || fromZone == Zone.HAND) {
//            for (UUID playerInRangeId : originalGame.getState().getPlayersInRange(getId(), originalGame)) {
//                Player player = originalGame.getPlayer(playerInRangeId);
//                if (player != null && !player.getHand().isEmpty()) {
//                    for (Card card : player.getHand().getCards(originalGame)) {
//                        if (card != null) {
//                            getPlayableFromObjectAll(originalGame, Zone.HAND, card, availableMana, playable);
//                        }
//                    }
//                }
//            }
//        }
//
//        // eliminate duplicate activated abilities (uses for AI plays)
//        Map<String, ActivatedAbility> activatedUnique = new HashMap<>();
//        List<ActivatedAbility> activatedAll = new ArrayList<>();
//
//        // activated abilities from battlefield objects
//        if (fromAll || fromZone == Zone.BATTLEFIELD) {
//            for (Permanent permanent : originalGame.getBattlefield().getAllActivePermanents()) {
//                boolean canUseActivated = permanent.canUseActivatedAbilities(originalGame);
//                List<ActivatedAbility> currentPlayable = new ArrayList<>();
//                getPlayableFromObjectAll(originalGame, Zone.BATTLEFIELD, permanent, availableMana, currentPlayable);
//                for (ActivatedAbility ability : currentPlayable) {
//                    if (ability instanceof SpecialAction || canUseActivated) {
//                        activatedUnique.putIfAbsent(ability.toString(), ability);
//                        activatedAll.add(ability);
//                    }
//                }
//            }
//        }
//
//        // activated abilities from stack objects
//        if (fromAll || fromZone == Zone.STACK) {
//            for (StackObject stackObject : originalGame.getState().getStack()) {
//                List<ActivatedAbility> currentPlayable = new ArrayList<>();
//                getPlayableFromObjectAll(originalGame, Zone.STACK, stackObject, availableMana, currentPlayable);
//                for (ActivatedAbility ability : currentPlayable) {
//                    activatedUnique.put(ability.toString(), ability);
//                    activatedAll.add(ability);
//                }
//            }
//        }
//
//        // activated abilities from objects in the command zone (emblems or commanders)
//        if (fromAll || fromZone == Zone.COMMAND) {
//            for (CommandObject commandObject : originalGame.getState().getCommand()) {
//                List<ActivatedAbility> currentPlayable = new ArrayList<>();
//                getPlayableFromObjectAll(originalGame, Zone.COMMAND, commandObject, availableMana, currentPlayable);
//                for (ActivatedAbility ability : currentPlayable) {
//                    activatedUnique.put(ability.toString(), ability);
//                    activatedAll.add(ability);
//                }
//            }
//        }
//
//        if (hideDuplicatedAbilities) {
//            playable.addAll(activatedUnique.values());
//        } else {
//            playable.addAll(activatedAll);
//        }
//
//        // make sure it independent of sim game
//        return playable.stream()
//                .map(ActivatedAbility::copy)
//                .collect(Collectors.toList());
//    }
//
//}
