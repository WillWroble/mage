package mage.player.ai.encoder;

import mage.ConditionalMana;
import mage.Mana;
import mage.abilities.*;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.Cost;
import mage.abilities.costs.Costs;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.effects.Effect;
import mage.abilities.keyword.KickerAbility;
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
import mage.game.command.CommandObject;
import mage.game.command.Commander;
import mage.game.command.Emblem;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.game.stack.SpellStack;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.target.Target;
import mage.target.Targets;
import mage.util.CardUtil;
import mage.watchers.Watcher;
import mage.watchers.common.CastSpellLastTurnWatcher;
import mage.watchers.common.CreatedTokenWatcher;
import mage.watchers.common.PlayerGainedLifeWatcher;
import mage.watchers.common.PlayerLostLifeWatcher;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static mage.player.ai.encoder.FeatureGraph.*;
import static mage.target.TargetImpl.STOP_CHOOSING;

/**
 * Global sparse state hasher for deep learning and MCTS validation
 * @author WillWroble
 *
 */

public class StateEncoder {
    public FeatureMap featureMap = new FeatureMap();
    protected static Logger logger = Logger.getLogger(StateEncoder.class);
    public boolean perfectInfo = true;
    public FeatureGraph featureGraph = new FeatureGraph();
    private UUID opponentId;
    private UUID myPlayerId;

    public List<LabeledState>  labeledStates = new ArrayList<>();




    private static final int  TABLE_SIZE        = Integer.MAX_VALUE;                // hash bins
    private static final long GLOBAL_SEED       = 0x9E3779B185EBCA87L;      // fixed reproducible seed



    public void setAgent(UUID me) {
        myPlayerId = me;
    }
    public void setOpponent(UUID op) {
        opponentId = op;
    }
    public synchronized UUID getMyPlayerId() {return myPlayerId;}


    private void processManaCosts(ManaCosts<ManaCost> manaCost, UUID parentId) {
        addNumericFeature("ManaValue", manaCost.manaValue(), parentId);
        for(ManaCost mc : manaCost) {
            addFeature(mc.getText(), parentId);
        }
    }
    private void processCosts(Costs<Cost> costs, ManaCosts<ManaCost> manaCosts, UUID parentId) {

        if(manaCosts != null && !manaCosts.isEmpty()) processManaCosts(manaCosts, parentId);
        if(costs == null || costs.isEmpty()) return;
        for(Cost cc : costs) {
            addFeature(cc.getText(), parentId);
        }
    }
    private void processAbility(Ability a, Game game, UUID parentId) {

        Costs<Cost> c = a.getCosts();
        ManaCosts<ManaCost> mcs = a.getManaCostsToPay();
        if(!c.isEmpty() || !mcs.isEmpty()) {
            processCosts(c, mcs, parentId);
        }
        for(Mode m : a.getModes().getAvailableModes(a, game)) {
            for(Effect e : m.getEffects()) {
                addFeature(cleanString(e.getText(m)), parentId);
            }
        }
        //process watchers
        for (Watcher w : a.getWatchers()) {
            if(w.conditionMet()) addFeature(w.getKey(), parentId);
        }
    }
    private void processActivatedAbility(ActivatedAbility aa, Game game, UUID parentId) {

        processAbility(aa, game, parentId);
        if(aa.isManaAbility()) addFeature("ManaAbility", parentId);
        try {
            UUID controllerId = aa.getControllerId();
            if (controllerId != null && aa.copy().canActivate(controllerId, game).canActivate()) {
                addFeature("CanActivate", parentId);
            }
        } catch (Exception e) {
            logger.warn("failed activation check in encoder: " + aa);
        }
    }
    private void processTriggeredAbility(TriggeredAbility ta, Game game, UUID parentId) {

        processAbility(ta, game, parentId);

        if(!ta.checkTriggeredLimit(game)) addFeature("ReachedTriggerLimit", parentId);
        if(ta.checkUsedAlready(game)) addFeature("UsedAlready", parentId);
        if(ta.getTriggerEvent() != null) addFeature(ta.getTriggerEvent().getType().name(), parentId);

    }
    //encodes only static features of card, see permanents for dynamic feature encoding
    private void processCard(Card c, Game game, UUID parentId) {

        //process counters (suspend is the only non-permanent dynamic feature I can think of)
        Counters counters = c.getCounters(game);
        for (String counterName : counters.keySet()) {
            addNumericFeature(counterName, counters.get(counterName).getCount(), parentId);
        }
        //static attribute of card, TYPE.PERM is for actual permanents on the battlefield
        if(c.isPermanent()) {
            addFeature("PermanentType", parentId, "static");
        }
        //add types
        for (CardType ct : c.getCardType()) {
            addFeature(ct.name(), parentId, "static");
        }
        //add color
        if(c.getColor().isRed()) addFeature("RedCard", parentId);
        if(c.getColor().isWhite()) addFeature("WhiteCard", parentId);
        if(c.getColor().isBlack()) addFeature("BlackCard", parentId);
        if(c.getColor().isGreen()) addFeature("GreenCard", parentId);
        if(c.getColor().isBlue()) addFeature("BlueCard", parentId);
        if(c.getColor().isColorless()) addFeature("ColorlessCard", parentId);
        if(c.getColor().isMulticolored()) addFeature("MultiColored", parentId);

        //add subtypes
        for (SubType st : c.getSubtype()) {
            if(!st.name().isEmpty()) addFeature(st.name(), parentId,  "static");
        }
        ManaCosts<ManaCost> mc = c.getManaCost();
        processManaCosts(mc, parentId);

    }

    private void processPermBattlefield(Permanent p, Game game, UUID playerId, UUID parentId) {
        //process as card
        processCardInZone(p, Zone.BATTLEFIELD, game, parentId);

        addFeature(p.getMainCard().getName(), parentId,  "static");
        //is tapped?
        if(p.isTapped()) addFeature("Tapped", parentId);

        //dynamic effects
        for (CardType ct : p.getCardType(game)) {
            addFeature(ct.name(), parentId);
        }
        for (SubType st : p.getSubtype(game)) {
            addFeature(st.name(),  parentId);
        }
        if(p.getMainCard().getColor().isRed()) addFeature("RedCard", parentId, "static");
        if(p.getMainCard().getColor().isWhite()) addFeature("WhiteCard", parentId, "static");
        if(p.getMainCard().getColor().isBlack()) addFeature("BlackCard", parentId, "static");
        if(p.getMainCard().getColor().isGreen()) addFeature("GreenCard", parentId, "static");
        if(p.getMainCard().getColor().isBlue()) addFeature("BlueCard", parentId, "static");
        if(p.getMainCard().getColor().isColorless()) addFeature("ColorlessCard", parentId, "static");
        if(p.getMainCard().getColor().isMulticolored()) addFeature("MultiColored", parentId, "static");

        //dynamic abilities
        List<Ability> abilities = p.getAbilities(game);
        if(!abilities.isEmpty()) {
            for(Ability a : abilities) {
                if(addNode(FeatureGraph.Node.Type.ABILITY, a.getRule(), a.getId(), parentId)) {
                    if (a instanceof TriggeredAbility) {
                        processTriggeredAbility((TriggeredAbility) a, game, a.getId());
                    } else if (a instanceof ActivatedAbility) {
                        processActivatedAbility((ActivatedAbility) a, game, a.getId());
                    } else {
                        processAbility(a, game, a.getId());
                    }
                }
            }
        }

        //process attachments
        List<UUID> attachments = p.getAttachments();
        if(attachments != null && !attachments.isEmpty()) {
            for (UUID id : attachments) {
                Permanent attachment = game.getPermanent(id);
                if(attachment == null) continue;
                //perms will always be filled in normal iteration
                addNode(FeatureGraph.Node.Type.PERMANENT, attachment.getName(), id, parentId, "attachment");
            }
        }
        //process imprinted
        List<UUID> imprinted = p.getImprinted();
        if(imprinted != null && !imprinted.isEmpty()) {
            for (UUID id : imprinted) {
                Card imprintedCard = game.getCard(id);
                if(imprintedCard == null) continue;
                addNode(FeatureGraph.Node.Type.CARD, imprintedCard.getName(), id, parentId, "imprinted");
            }
        }
        //paired
        Card pairedCard = (Card) p.getPairedCard();
        if(pairedCard != null) {
            addNode(FeatureGraph.Node.Type.CARD, pairedCard.getName(), pairedCard.getId(), parentId, "paired");
        }
        //process special exile zone (oblivion ring effect)
        UUID exileId = CardUtil.getExileZoneId(game, p.getId(), p.getZoneChangeCounter(game));
        ExileZone exileZone = game.getExile().getExileZone(exileId);

        if (exileZone != null) {
            if (addNode(FeatureGraph.Node.Type.ZONE, cleanString(exileZone.getName()), exileZone.getId(), parentId, "exiled")) {
                processExileZone(exileZone, game, exileZone.getId());
            }
        }


        //TODO soulbond, banding


        //unique flags
        if(p.isFlipped()) addFeature("flipped", parentId);
        if(p.isHarnessed()) addFeature("harnessed", parentId);
        if(p.isSolved()) addFeature("solved", parentId);
        if(p.isSuspected()) addFeature("suspected", parentId);
        if(p.isRingBearer()) addFeature("RingBearer", parentId);
        if(p.isRenowned()) addFeature("Renowned", parentId);
        if(p.isMonstrous()) addFeature("Monstrous", parentId);
        if(p.isCloaked()) addFeature("Cloaked", parentId);
        if(p.isDisguised()) addFeature("disguised", parentId);
        if(p.isMorphed()) addFeature("morphed", parentId);
        if(p.isLeftDoorUnlocked()) addFeature("Room-LeftDoor", parentId);
        if(p.isRightDoorUnlocked()) addFeature("Room-RightDoor",parentId);


        if(p.isCreature(game)) {
            if(p.hasSummoningSickness()) addFeature("SummoningSick", parentId);
            if(p.canAttack(game.getOpponent(playerId).getId(), game)) addFeature("CanAttack", parentId); //use p.canAttack()
            if(p.canBlockAny(game)) addFeature("CanBlock", parentId);
            //if(p.hasSummoningSickness()) addFeature("SummoningSickness");
            if(p.isAttacking()) {
                addFeature("Attacking", parentId);
                UUID defender = game.getCombat().getDefenderId(p.getId());
                processTarget(defender, game, playerId, parentId, "defender");
                for(UUID blockerId : game.getCombat().findGroup(p.getId()).getBlockers()) {
                    Permanent blocker  = game.getPermanent(blockerId);
                    addNode(FeatureGraph.Node.Type.PERMANENT, blocker.getName(), blocker.getId(), parentId, "blocker");
                }

            }
            addNumericFeature("Damage", p.getDamage(), parentId);
            addNumericFeature("Power", p.getPower().getValue(), parentId);
            addNumericFeature("Toughness", p.getToughness().getValue(), parentId);
        }
    }
    private void processCardInZone(Card c, Zone z, Game game, UUID parentId) {

        //process as card (static features)
        processCard(c, game, parentId);


        Abilities<Ability> allAbilities = c.getAbilities(game);
        //static abilities
        for (StaticAbility sa : allAbilities.getStaticAbilities(z)) {
            addNode(FeatureGraph.Node.Type.ABILITY, sa.getRule(), sa.getId(), parentId);
            processAbility(sa, game, sa.getId());
        }
        //activated abilities
        for(ActivatedAbility aa : allAbilities.getActivatedAbilities(z)) {
            addNode(FeatureGraph.Node.Type.ABILITY, aa.getRule(), aa.getId(), parentId);
            processActivatedAbility(aa, game, aa.getId());
        }
        //triggered abilities
        for(TriggeredAbility ta : allAbilities.getTriggeredAbilities(z)) {
            addNode(FeatureGraph.Node.Type.ABILITY, ta.getRule(), ta.getId(), parentId);
            processTriggeredAbility(ta, game, ta.getId());

        }
    }
    private void processBattlefield(Battlefield bf, Game game, UUID playerId, UUID parentId) {
        List<Permanent> permanents = bf.getAllActivePermanents(playerId);
        addNumericFeature("BattlefieldSize", permanents.size(), parentId);
        for (Permanent p : permanents) {
            addNode(FeatureGraph.Node.Type.PERMANENT, p.getName(), p.getId(), parentId);
            processPermBattlefield(p, game, playerId, p.getId());
        }
    }
    private void processGraveyard(Graveyard gy, Game game, UUID parentId) {
        addNumericFeature("GraveyardSize", gy.getCards(game).size(), parentId);
        for (Card c : gy.getCardsSorted(game)) {
            addNode(FeatureGraph.Node.Type.CARD, c.getName(), c.getId(), parentId);
            processCardInZone(c, Zone.GRAVEYARD, game, c.getId());
        }
    }
    private void processHand(Cards hand, Game game, UUID parentId) {
        for (Card c : hand.getCardsSorted(game)) {
            addNode(FeatureGraph.Node.Type.CARD, c.getName(), c.getId(), parentId);
            processCardInZone(c, Zone.HAND, game, c.getId());
        }
    }
    private void processTarget(UUID target, Game game, UUID playerId, UUID parentId, String edge) {
        if(game.getPermanent(target) != null) {
            addNode(FeatureGraph.Node.Type.PERMANENT, game.getEntityName(target, playerId), target, parentId, edge);
        } else if (game.getPlayer(target) != null) {
            addNode(FeatureGraph.Node.Type.PLAYER, game.getEntityName(target, playerId), target, parentId, edge);
        } else if (game.getStack().getStackObject(target) != null) {
            addNode(FeatureGraph.Node.Type.STACK_OBJECT, cleanString(game.getStack().getStackObject(target).toString()), target, parentId, edge);
        } else if (game.getCard(target) != null) {
            addNode(FeatureGraph.Node.Type.CARD, game.getEntityName(target, playerId), target, parentId, edge);
        } else {
            logger.warn("unknown target type");
        }
    }
    private void processStackObject(StackObject so, Game game, UUID playerId, UUID parentId) {
        if(so.getControllerId().equals(playerId)) addFeature("isController", parentId);
        Ability sa = so.getStackAbility();
        Card sourceCard = game.getCard(sa.getSourceId());
        if(sourceCard != null) {
            //process as card
            if (addNode(Node.Type.CARD, sourceCard.getName(), sourceCard.getId(), parentId)) {
                processCardInZone(sourceCard, Zone.STACK, game, sourceCard.getId());
            }
        }
        addFeature(sa.getRule(), parentId);

        Targets myTargets = sa.getTargets();
        if(!myTargets.isEmpty()) {
            int i = 0;
            for (Target target : myTargets) {
                for (UUID id : target.getTargets()) {
                    processTarget(id, game, playerId, parentId, "TARGET@"+ i);
                }
                i++;
            }
        }
        //kicker
        int totalKicks = KickerAbility.getKickedCounter(game, sa);
        addNumericFeature("Kicks", totalKicks, parentId);
        //cost tags
        Map<String, Object> tags = CardUtil.getSourceCostsTagsMap(game, sa);
        if (tags != null && !tags.isEmpty()) {
            for(String tag : tags.keySet()) {
                Object v = tags.get(tag);
                addNumericFeature(tag + "_CostTag", (v instanceof Integer) ? (Integer) v : 1, parentId);
            }
        }
        //modes
        List<UUID> selectedModes = sa.getModes().getSelectedModes();
        if (selectedModes != null && !selectedModes.isEmpty() && sa.getModes().size()>1) {
            //selected modes
            for(UUID id : selectedModes) {
                Mode m = sa.getModes().get(id);
                for(Effect e : m.getEffects()) {
                    addFeature(cleanString(e.getText(m)), parentId);
                }
            }
        }
        //variable cost
        int xValue = CardUtil.getSourceCostsTag(game, sa, "X", 0);
        addNumericFeature("XValue", xValue, parentId);

    }
    private void processStack(SpellStack stack, Game game, UUID playerId, UUID parentId) {
        addNumericFeature("StackSize", stack.size(), parentId);
        Iterator<StackObject> itr = stack.iterator();
        StackObject so;
        int depth=0;
        while(itr.hasNext()) {
            depth++;
            so = itr.next();
            addNode(FeatureGraph.Node.Type.STACK_OBJECT, cleanString(so.toString()), so.getId(), parentId);
            addNumericFeature("Depth", depth, so.getId());
            processStackObject(so, game, playerId, so.getId());
        }
    }
    private void processExileZone(ExileZone exileZone, Game game, UUID parentId) {
        for (Card c : exileZone.getCardsSorted(game)) {
            addNode(FeatureGraph.Node.Type.CARD, c.getName(), c.getId(), parentId);
            processCardInZone(c, Zone.EXILED, game, c.getId());
        }
    }
    private void processExile(Exile exile, Game game, UUID parentId) {

        for (ExileZone ez : exile.getExileZones()) {
            addNode(FeatureGraph.Node.Type.ZONE, cleanString(ez.getName()),  ez.getId(), parentId);
            processExileZone(ez, game, ez.getId());
        }
    }
    private void processMana(Mana mana, Game game, String condition, UUID parentId) {
        addNumericFeature("GreenMana"+condition, mana.getGreen(), parentId);
        addNumericFeature("RedMana"+condition, mana.getRed(), parentId);
        addNumericFeature("BlueMana"+condition, mana.getBlue(), parentId);
        addNumericFeature("WhiteMana"+condition, mana.getWhite(), parentId);
        addNumericFeature("BlackMana"+condition, mana.getBlack(), parentId);
        addNumericFeature("ColorlessMana"+condition, mana.getColorless(), parentId);
    }
    private void processManaPool(ManaPool mp, Game game, UUID parentId) {
        processMana(mp.getMana(), game, "", parentId);

        List<ConditionalMana> conditionalMana = mp.getConditionalMana();
        if(conditionalMana != null && !conditionalMana.isEmpty()) {
            for(ConditionalMana condMana : conditionalMana) {
                processMana(condMana, game, "_" + condMana.getConditionString(), parentId);
            }
        }
    }
    private void processCommandZone(Game game, UUID playerId, UUID parentId) {
        // Command zone
        for (CommandObject co : game.getState().getCommand()) {
            if (co instanceof Emblem) {
                Emblem emblem = (Emblem) co;
                if (playerId.equals(emblem.getControllerId())) {
                    // Emblems mainly have continuous/static abilities
                    for (Ability a : emblem.getAbilities()) {
                        addNode(FeatureGraph.Node.Type.ABILITY, a.getRule(), a.getId(), parentId);
                        processAbility(a, game, a.getId());
                    }
                }
            }
            if(co instanceof Commander) {
                Commander commander = (Commander) co;
                if (playerId.equals(commander.getControllerId())) {
                    addNode(FeatureGraph.Node.Type.CARD, commander.getName(), commander.getId(), parentId);
                    processCard(commander.getSourceObject(), game, commander.getId());
                    for (Ability a : commander.getAbilities()) {
                        addNode(FeatureGraph.Node.Type.ABILITY, a.getRule(), a.getId(), parentId);
                        processAbility(a, game, a.getId());
                    }
                }
            }
        }

        // Helper emblems (some emblems can be mirrored here)
        for (Emblem emblem : game.getState().getHelperEmblems()) {
            if (playerId.equals(emblem.getControllerId())) {
                for (Ability a : emblem.getAbilities()) {
                    if(addNode(FeatureGraph.Node.Type.ABILITY, a.getRule(), a.getId(), parentId)) {
                        processAbility(a, game, a.getId());
                    }
                }
            }
        }
        //TODO: companions
    }
    private void processWatchers(Game game, UUID playerId, UUID parentId) {
        // Storm / spells cast counts
        CastSpellLastTurnWatcher stormW = game.getState().getWatcher(CastSpellLastTurnWatcher.class);
        if (stormW != null) {
            addNumericFeature("SpellsCastThisTurn", stormW.getAmountOfSpellsPlayerCastOnCurrentTurn(playerId), parentId);
        }
        // Life gained this turn
        PlayerGainedLifeWatcher lifeW = game.getState().getWatcher(PlayerGainedLifeWatcher.class);
        if (lifeW != null) {
            addNumericFeature("LifeGainedThisTurn", lifeW.getLifeGained(playerId), parentId);
        }
        // Life lost this turn
        PlayerLostLifeWatcher lossW = game.getState().getWatcher(PlayerLostLifeWatcher.class);
        if (lossW != null) {
            addNumericFeature("LifeLostThisTurn", lossW.getLifeLost(playerId), parentId);
        }
        // Tokens created this turn
        CreatedTokenWatcher tokenW = game.getState().getWatcher(CreatedTokenWatcher.class);
        if (tokenW != null) {
            addNumericFeature("TokensCreatedThisTurn", CreatedTokenWatcher.getPlayerCount(playerId, game), parentId);
        }
    }
    private void processMicroDecisions(Game game, UUID playerId, UUID parentId) {
        Player myPlayer = game.getPlayer(playerId);
        int i = 0;
        //current targets selected for when it's in the middle selecting multiple targets
        for(UUID targetID : myPlayer.getPlayerHistory().targetSequence) {
            processTarget(targetID, game, playerId, parentId, "TARGET@"+i);
            i++;
        }
        //current choices selected for when it's in the middle selecting multiple choices
        i = 0;
        for(String choice : myPlayer.getPlayerHistory().choiceSequence) {
            addNumericFeature(choice, i++, parentId);
        }
        //current choices selected for when it's in the middle selecting multiple choices
        i = 0;
        for(Boolean use : myPlayer.getPlayerHistory().useSequence) {
            addNumericFeature(use.toString(), i++, parentId);
        }
        //current choices selected for when it's in the middle selecting multiple choices
        i = 0;
        for(Integer num : myPlayer.getPlayerHistory().numSequence) {
            addNumericFeature(num.toString(),  i++, parentId);
        }
    }
    private void processPlayer(Game game, UUID playerId, UUID decisionPlayerId, UUID parentId) {
        Player myPlayer = game.getPlayer(playerId);

        if(myPlayer.isInPayManaMode()) addFeature("InPayManaMode", parentId);
        if(((PlayerImpl)myPlayer).isActivating) addFeature("Activating", parentId);

        //micro decision state
        processMicroDecisions(game, playerId, parentId);

        //pass ability (for policy net)
        addNode(FeatureGraph.Node.Type.ABILITY, "PassAbility", PASS_ABILITY_ID, parentId);


        if(game.isActivePlayer(playerId)) addFeature("IsActivePlayer", parentId);
        if(decisionPlayerId.equals(playerId)) addFeature("IsDecisionPlayer", parentId);
        addNumericFeature("LifeTotal", myPlayer.getLife(), parentId);
        if(myPlayer.canPlayLand()) addFeature("CanPlayLand", parentId);


        //library
        addNumericFeature("LibrarySize", myPlayer.getLibrary().size(), parentId);
        //TODO: revealed cards

        //attachments
        List<UUID> attachments = myPlayer.getAttachments();
        if(attachments != null && !attachments.isEmpty()) {
            for(UUID id : attachments) {
                if(game.getPermanent(id) != null) {
                    addNode(FeatureGraph.Node.Type.PERMANENT, game.getPermanent(id).getName(), id, parentId, "attachment");
                }
            }
        }
        //counters
        Counters counters = myPlayer.getCountersAsCopy();
        for (String counterName : counters.keySet()) {
            addNumericFeature(counterName, counters.get(counterName).getCount(), parentId);
        }

        //mana pool
        processManaPool(myPlayer.getManaPool(), game, parentId);

        //graveyard
        Graveyard gy = myPlayer.getGraveyard();
        UUID gyId = stringToUUID(GRAVEYARD_ID + parentId.toString());
        addNode(FeatureGraph.Node.Type.ZONE, "Graveyard", gyId, parentId);
        processGraveyard(gy, game, gyId);

        //hand
        Cards hand = myPlayer.getHand();
        UUID handId = stringToUUID(HAND_ID + parentId.toString());
        addNode(FeatureGraph.Node.Type.ZONE, "Hand", handId, parentId);
        addNumericFeature("HandSize", hand.size(), parentId);
        if(playerId.equals(decisionPlayerId) || perfectInfo) { //keep perspective
            processHand(hand, game, handId);
        }

        //command zone
        UUID czId = stringToUUID(COMMAND_ZONE_ID + parentId.toString());
        addNode(FeatureGraph.Node.Type.ZONE, "CommandZone", czId, parentId);
        processCommandZone(game, playerId, czId);

        //global watchers
        processWatchers(game, playerId, parentId);

        //battlefield
        Battlefield bf = game.getBattlefield();
        UUID bfId = stringToUUID(BATTLEFIELD_ID + parentId.toString());
        addNode(FeatureGraph.Node.Type.ZONE, "Battlefield", bfId, parentId);
        processBattlefield(bf, game, playerId, bfId);


        //TODO dungeons
    }
    /**
     * vectorizes (hashes) the entire game state in a neural network-learnable way. These vectors are massive and sparse -
     * they are designed to have redundant features masked before training and used with a massive embedding bag in pytorch
     * @param game S
     * @param decisionPlayerId the player who is making the decision at this state
     * @param decisionType type of decision being made at this state (choose_target, choose_use, choose etc.)
     * @param decisionsText informative context about the micro decision being made to be hashed as its own feature for the network
     * @return set of active indices in the sparse binary vector
     */
    public synchronized FeatureGraph processState(Game game, UUID decisionPlayerId, ActionEncoder.ActionType decisionType, String decisionsText, Cards options, UUID decisionSource) {
        featureGraph.clear();
        //globals
        if(game.getPhase() != null) {
            addFeature(game.getTurnStepType().toString(), GAME_ROOT_ID); //phases
        }
        if(game.hasDayNight()) {
            if(game.checkDayNight(true)) {
                addFeature("DayTime", GAME_ROOT_ID);
            }
            if(game.checkDayNight(false)) {
                addFeature("NightTime", GAME_ROOT_ID);
            }
        }

        //decision type
        addFeature(decisionType.toString(), GAME_ROOT_ID);
        //decision state
        addFeature(cleanString(decisionsText), GAME_ROOT_ID);
        //empty target flag
        addNode(FeatureGraph.Node.Type.CARD, "StopChoosing", STOP_CHOOSING, GAME_ROOT_ID);

        if(decisionSource != null && game.getCard(decisionSource) != null) {
            Card card = game.getCard(decisionSource);
            addNode(FeatureGraph.Node.Type.CARD, card.getName(), card.getId(), GAME_ROOT_ID, "DecisionSource");
        }


        //exiled
        addNode(FeatureGraph.Node.Type.ZONE, "Exile", EXILE_ID, GAME_ROOT_ID);
        processExile(game.getExile(), game, EXILE_ID);

        //each player

        //PlayerA
        addNode(FeatureGraph.Node.Type.PLAYER, "PlayerA", myPlayerId, GAME_ROOT_ID);
        processPlayer(game, myPlayerId, decisionPlayerId, myPlayerId);
        //PlayerB
        addNode(FeatureGraph.Node.Type.PLAYER, "PlayerB", opponentId, GAME_ROOT_ID);
        processPlayer(game, opponentId, decisionPlayerId, opponentId);

        //stack
        addNode(FeatureGraph.Node.Type.ZONE, "Stack", STACK_ID, GAME_ROOT_ID);
        processStack(game.getStack(), game, myPlayerId, STACK_ID);

        //additional options
        if(options != null) {
            for (UUID c : options) {
                Card card = game.getCard(c);
                if (addNode(FeatureGraph.Node.Type.CARD, card.getName(), c, GAME_ROOT_ID, "OptionPile")) {
                    processCard(card, game, c);
                }
            }
        }



        return new FeatureGraph(featureGraph);

    }
    public synchronized FeatureGraph processState(Game game, UUID decisionPlayerId, ActionEncoder.ActionType decisionType, String decisionsText) {
        return processState(game, decisionPlayerId, decisionType, decisionsText, null, null);
    }
    public synchronized FeatureGraph processState(Game game, UUID actingPlayerID) {
        return processState(game, actingPlayerID, ActionEncoder.ActionType.PRIORITY,"priority", null, null);
    }

    public void addLabeledState(FeatureGraph state, Map<UUID, Integer> actionMap, double score, ActionEncoder.ActionType actionType, boolean isPlayer, Game game, UUID playerId) {
        for (UUID id : actionMap.keySet()) {
            if(!state.containsKey(id)) {
                logger.error("missing node: " + game.getEntityName(id, playerId) + ", cannot add state to buffer");
            }
        }
        LabeledState newState = new LabeledState(state, actionMap, score, actionType, isPlayer);
        labeledStates.add(newState);
    }
    //private String featureName;
    public static boolean useFeatureMap = false;
    public boolean addNode(FeatureGraph.Node.Type type, String name, UUID id, UUID parentId) {
        return addNode(type, name, id, parentId, "NONE");
    }
    public boolean addNode(FeatureGraph.Node.Type type, String name, UUID id, UUID parentId, String edge) {
        //name becomes attribute of base-type.
        if(!addFeature(type.name(), id, parentId, edge)) {
            return false;
        }
        addFeature(name, id, "name");
        return true;
    }
    public void addFeature(String name, UUID parentId) {
        addFeature(name, stringToUUID(name), parentId, "NONE");
    }
    public void addFeature(String name, UUID parentId, String edge) {
        addFeature(name, stringToUUID(name), parentId, edge);
    }
        public boolean addFeature(String name, UUID id, UUID parentId, String  edge) {
            boolean result = false;
            int hash = indexFor(hash64(name));
            if(!featureGraph.containsKey(id)) {
                FeatureGraph.Node newNode = new FeatureGraph.Node(name, hash);
                featureGraph.put(id, newNode);
                result = true;
            }
            if(parentId != null) {
                featureGraph.get(parentId).children.put(id, edge);
            }
            if(useFeatureMap) {
                featureMap.addFeature(name, hash);
            }
            return result;
        }
        public void addNumericFeature(String name, int num, UUID parentId) {
            addNumericFeature(name, num, parentId, "NONE");
        }
        public void addNumericFeature(String name, int num, UUID parentId, String edge) {
            int hash = indexFor(hash64(name));
            UUID key = stringToUUID(name+"@"+num);
            if(!featureGraph.containsKey(key)) {
                FeatureGraph.Node newNode = new FeatureGraph.Node(name, hash, num);
                featureGraph.put(key, newNode);
            }
            if(parentId != null) {
                featureGraph.get(parentId).children.put(key, edge);
            }
        }

    public static int indexFor(long h) {
        if (h < 0) h = -h;
        return (int) (h % TABLE_SIZE);
    }
    public static long hash64(String s) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long h = mix64(GLOBAL_SEED ^ (data.length * 0x9E3779B185EBCA87L));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.remaining() >= 8) {
            long k = bb.getLong();
            h ^= mix64(k);
            h = Long.rotateLeft(h, 27) * 0x9E3779B185EBCA87L + 0x165667B19E3779F9L;
        }
        long k = 0;
        int rem = bb.remaining();
        for (int i = 0; i < rem; i++) {
            k ^= ((long) bb.get() & 0xFFL) << (8 * i);
        }
        h ^= mix64(k);
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }


    /**
     * removes all instances of UUIDs for consistent hashing
     * @param input nondeterministic string with UUIDs
     * @return deterministic cleaned strings
     */
    public static String cleanString (String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String cleaned = input.replaceAll(" \\[[0-9a-f]+]", "");
        cleaned = cleaned.replaceAll("<[^>]*>", "");
        return cleaned;
    }

    /**
     * returns stack objects targeting given permanent. and populates a list of stackIndices that correspond to
     * those object's stack positions
     * @param permanent
     * @param game
     * @param stackIndices is populated
     * @return
     */
    public static List<StackObject> getSpellsTargetingPermanent(Permanent permanent, Game game, List<Integer> stackIndices) {
        List<StackObject> result = new ArrayList<>();
        int index = 1;
        if (permanent == null) return result;

        for (StackObject so : game.getStack()) {
            Ability sa = so.getStackAbility();
            if (sa == null) continue;

            Targets targets = sa.getTargets();
            if (targets == null || targets.isEmpty()) continue;

            boolean targetsThisPerm = targets
                    .stream()
                    .anyMatch(t -> t.getTargets().contains(permanent.getId()));

            if (targetsThisPerm) {
                result.add(so);
                stackIndices.add(index);
            }
            index++;
        }
        return result;
    }
    public static UUID stringToUUID(String s) {
        long h = hash64(s);
        return new UUID(h, mix64(h));
    }
}
