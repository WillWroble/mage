package mage.game;

import mage.MageItem;
import mage.MageObject;
import mage.MageObjectReference;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.delayed.ReflexiveTriggeredAbility;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.ContinuousEffects;
import mage.abilities.effects.PreventionEffectData;
import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.MeldCard;
import mage.cards.decks.Deck;
import mage.choices.Choice;
import mage.constants.*;
import mage.counters.Counters;
import mage.game.combat.Combat;
import mage.game.command.*;
import mage.game.events.GameEvent;
import mage.game.events.Listener;
import mage.game.events.PlayerQueryEvent;
import mage.game.events.TableEvent;
import mage.game.match.MatchType;
import mage.game.mulligan.Mulligan;
import mage.game.permanent.Battlefield;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.SpellStack;
import mage.game.turn.Phase;
import mage.game.turn.Step;
import mage.game.turn.Turn;
import mage.players.Player;
import mage.players.PlayerList;
import mage.players.Players;
import mage.util.Copyable;
import mage.util.MessageToClient;
import mage.util.MultiAmountMessage;
import mage.util.functions.CopyApplier;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public interface Game extends MageItem, Serializable, Copyable<Game> {
//    void setMacroState(Game game);
//    void setMacroPlayerId(UUID id);
//    void setLastAction(Ability ability);
    Game getLastPriority();
    UUID getLastPriorityPlayerId();
    Ability getLastPriorityAction();

    void setLastPriority(Game game);

    MatchType getGameType();

    int getNumPlayers();

    int getStartingLife();

    RangeOfInfluence getRangeOfInfluence();

    MultiplayerAttackOption getAttackOption();

    //game data methods
    void loadCards(Set<Card> cards, UUID ownerId);

    Collection<Card> getCards();

    MeldCard getMeldCard(UUID meldId);

    void addMeldCard(UUID meldId, MeldCard meldCard);

    Object getCustomData();

    void setCustomData(Object data);

    GameOptions getOptions();

    /**
     * Return object or LKI from battlefield
     *
     * @param objectId
     * @return
     */
    MageObject getObject(UUID objectId);

    MageObject getObject(Ability source);

    MageObject getBaseObject(UUID objectId);

    MageObject getEmblem(UUID objectId);

    Dungeon getDungeon(UUID objectId);

    Dungeon getPlayerDungeon(UUID playerId);

    UUID getControllerId(UUID objectId);

    UUID getOwnerId(UUID objectId);

    UUID getOwnerId(MageObject object);

    Spell getSpell(UUID spellId);

    Spell getSpellOrLKIStack(UUID spellId);

    /**
     * Find permanent on the battlefield by id. If you works with cards and want to check it on battlefield then
     * use game.getState().getZone() instead. Card's id and permanent's id can be different (example: mdf card
     * puts half card to battlefield, not the main card).
     *
     * @param permanentId
     * @return
     */
    Permanent getPermanent(UUID permanentId);

    /**
     * Given the UUID of a permanent, this method returns the permanent. If the current game state does not contain
     * a permanent with the given UUID, this method checks the last known information on the battlefield to look for it.
     * <br>
     * Warning: if the permanent has left the battlefield and then returned, this information might be wrong.
     * Prefer usage of a MageObjectReference instead of only the UUID.
     *
     * @param permanentId - The UUID of the permanent
     * @return permanent or permanent's LKI
     */
    Permanent getPermanentOrLKIBattlefield(UUID permanentId);

    /**
     * Given a MageObjectReference to a permanent, this method returns the permanent. If the current game state does not
     * contain that permanent, this method checks the last known information on the battlefield.
     *
     * @param permanentRef - A MOR to the permanent
     * @return permanent or permanent's LKI
     */
    Permanent getPermanentOrLKIBattlefield(MageObjectReference permanentRef);

    Permanent getPermanentEntering(UUID permanentId);

    Map<UUID, Permanent> getPermanentsEntering();

    Map<Zone, Map<UUID, MageObject>> getLKI();
    Map<MageObjectReference, Map<String, Object>> getPermanentCostsTags();

    /**
     * Take the source's Costs Tags and store it for later access through the MOR.
     */
    void storePermanentCostsTags(MageObjectReference permanentMOR, Ability source);

    // Result must be checked for null. Possible errors search pattern: (\S*) = game.getCard.+\n(?!.+\1 != null)
    Card getCard(UUID cardId);

    Optional<Ability> getAbility(UUID abilityId, UUID sourceId);

    void setZone(UUID objectId, Zone zone);

    void addPlayer(Player player, Deck deck);

    // Result must be checked for null. Possible errors search pattern: (\S*) = game.getPlayer.+\n(?!.+\1 != null)
    Player getPlayer(UUID playerId);

    Player getPlayerOrPlaneswalkerController(UUID playerId);

    /**
     * Static players list from start of the game. Use it to find player by ID or in game engine.
     */
    Players getPlayers();

    /**
     * Static players list from start of the game. Use it to interate by starting turn order.
     * WARNING, it's ignore range and leaved players, so use it by game engine only
     */
    // TODO: check usage of getPlayerList in cards and replace by game.getState().getPlayersInRange
    PlayerList getPlayerList();

    /**
     *  Returns opponents list in range for the given playerId. Use it to interate by starting turn order.
     *
     *  Warning, it will return leaved players until end of turn. For dialogs and one shot effects use excludeLeavedPlayers
     */
    // TODO: check usage of getOpponents in cards and replace with correct call of excludeLeavedPlayers, see #13289
    default Set<UUID> getOpponents(UUID playerId) {
        return getOpponents(playerId, false);
    }

    /**
     *  Returns opponents list in range for the given playerId. Use it to interate by starting turn order.
     *  Warning, it will return dead players until end of turn.
     *
     * @param excludeLeavedPlayers exclude dead player immediately without waiting range update on next turn
     */
    default Set<UUID> getOpponents(UUID playerId, boolean excludeLeavedPlayers) {
        Player player = getPlayer(playerId);
        if (player == null) {
            return new LinkedHashSet<>();
        }

        return this.getPlayerList().stream()
                .filter(opponentId -> !opponentId.equals(playerId))
                .filter(player::hasPlayerInRange)
                .filter(opponentId -> !excludeLeavedPlayers || getPlayer(opponentId).isInGame())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    default boolean isActivePlayer(UUID playerId) {
        return getActivePlayerId() != null && getActivePlayerId().equals(playerId);
    }

    /**
     * Checks if the given playerToCheckId is an opponent of player As long as
     * no team formats are implemented, this method returns always true for each
     * playerId not equal to the player it is checked for. Also if this player
     * is out of range. This method can't handle that only players in range are
     * processed because it can only return TRUE or FALSE.
     *
     * @param player
     * @param playerToCheckId
     * @return
     */
    default boolean isOpponent(Player player, UUID playerToCheckId) {
        return !player.getId().equals(playerToCheckId);
    }

    Turn getTurn();

    /**
     * @return can return null in non started games
     */
    PhaseStep getTurnStepType();

    /**
     * @return can return null in non started games
     */
    TurnPhase getTurnPhaseType();

    /**
     * @return can return null in non started games
     */
    Phase getPhase();

    Step getStep();

    int getTurnNum();

    boolean isMainPhase();

    boolean canPlaySorcery(UUID playerId);

    /**
     * Id of the player the current turn it is.
     *
     * Player can be under control of another player, so search a real GUI's controller by Player->getTurnControlledBy
     *
     * @return
     */
    UUID getActivePlayerId();

    UUID getPriorityPlayerId();

    boolean checkIfGameIsOver();

    boolean hasEnded();

    Battlefield getBattlefield();

    SpellStack getStack();

    Exile getExile();

    Combat getCombat();

    GameState getState();

    String getWinner();

    void setDraw(UUID playerId);

    boolean isADraw();

    ContinuousEffects getContinuousEffects();

    GameStates getGameStates();

    void loadGameStates(GameStates states);

    boolean isSimulation();

    /**
     * Prepare game for any simulations like AI or effects calc
     */
    Game createSimulationForAI();

    /**
     * Prepare game for any playable calc (available mana/abilities)
     */
    Game createSimulationForPlayableCalc();

    boolean inCheckPlayableState();

    MageObject getLastKnownInformation(UUID objectId, Zone zone);

    CardState getLastKnownInformationCard(UUID objectId, Zone zone);

    MageObject getLastKnownInformation(UUID objectId, Zone zone, int zoneChangeCounter);

    /**
     * For checking if an object was in a zone during the resolution of an effect
     */
    boolean checkShortLivingLKI(UUID objectId, Zone zone);

    void rememberLKI(Zone zone, MageObject object);

    void resetLKI();

    void resetShortLivingLKI();

    void setLosingPlayer(Player player);

    Player getLosingPlayer();

    int getTotalErrorsCount(); // debug only

    int getTotalEffectsCount(); // debug only

    //client event methods
    void addTableEventListener(Listener<TableEvent> listener);

    void addPlayerQueryEventListener(Listener<PlayerQueryEvent> listener);

    void fireAskPlayerEvent(UUID playerId, MessageToClient message, Ability source);

    void fireAskPlayerEvent(UUID playerId, MessageToClient message, Ability source, Map<String, Serializable> options);

    void fireChooseChoiceEvent(UUID playerId, Choice choice);

    void fireSelectTargetEvent(UUID playerId, MessageToClient message, Set<UUID> targets, boolean required, Map<String, Serializable> options);

    void fireSelectTargetEvent(UUID playerId, MessageToClient message, Cards cards, boolean required, Map<String, Serializable> options);

    void fireSelectTargetTriggeredAbilityEvent(UUID playerId, String message, List<TriggeredAbility> abilities);

    void fireSelectTargetEvent(UUID playerId, String message, List<Permanent> perms, boolean required);

    void fireSelectEvent(UUID playerId, String message);

    void fireSelectEvent(UUID playerId, String message, Map<String, Serializable> options);

    void firePriorityEvent(UUID playerId);

    void firePlayManaEvent(UUID playerId, String message, Map<String, Serializable> options);

    void firePlayXManaEvent(UUID playerId, String message);

    void fireGetChoiceEvent(UUID playerId, String message, MageObject object, List<? extends ActivatedAbility> choices);

    void fireGetModeEvent(UUID playerId, String message, Map<UUID, String> modes);

    void fireGetAmountEvent(UUID playerId, String message, int min, int max);

    void fireGetMultiAmountEvent(UUID playerId, List<MultiAmountMessage> messages, int min, int max, Map<String, Serializable> options);

    void fireChoosePileEvent(UUID playerId, String message, List<? extends Card> pile1, List<? extends Card> pile2);

    void fireInformEvent(String message);

    void fireStatusEvent(String message, boolean withTime, boolean withTurnInfo);

    void fireUpdatePlayersEvent();

    void informPlayers(String message);

    void informPlayer(Player player, String message);

    void debugMessage(String message);

    void fireErrorEvent(String message, Exception ex);

    void fireGameEndInfo();

    //game event methods
    void fireEvent(GameEvent event);

    /**
     * The events are stored until the resolution of the current effect ends and
     * fired then all together (e.g. X lands enter the battlefield from
     * Scapeshift)
     *
     * @param event
     */
    void addSimultaneousEvent(GameEvent event);

    boolean replaceEvent(GameEvent event);

    boolean replaceEvent(GameEvent event, Ability targetAbility);

    /**
     * Creates and fires a damage prevention event
     *
     * @param damageEvent     damage event that will be replaced (instanceof
     *                        check will be done)
     * @param source          ability that's the source of the prevention effect
     * @param game
     * @param amountToPrevent max preventable amount
     * @return true prevention was successful / false prevention was replaced
     */
    PreventionEffectData preventDamage(GameEvent damageEvent, Ability source, Game game, int amountToPrevent);

    void start(UUID choosingPlayerId);

    void resume();

    void pause();

    boolean isPaused();

    void end();

    void cleanUp();

    /*
     * Gives back the number of cards the player has after the next mulligan
     */
    int mulliganDownTo(UUID playerId);

    void mulligan(UUID playerId);

    void endMulligan(UUID playerId);

    // void quit(UUID playerId);
    void timerTimeout(UUID playerId);

    void idleTimeout(UUID playerId);

    void concede(UUID playerId);

    void setConcedingPlayer(UUID playerId);

    void setManaPaymentMode(UUID playerId, boolean autoPayment);

    void setManaPaymentModeRestricted(UUID playerId, boolean autoPaymentRestricted);

    void setUseFirstManaAbility(UUID playerId, boolean useFirstManaAbility);

    void undo(UUID playerId);

    /**
     * Empty mana pool with mana burn and life lose checks
     *
     * @param source must be null for default game events
     */
    void emptyManaPools(Ability source);

    void addEffect(ContinuousEffect continuousEffect, Ability source);

    void addEmblem(Emblem emblem, MageObject sourceObject, Ability source);

    void addEmblem(Emblem emblem, MageObject sourceObject, UUID toPlayerId);

    boolean addPlane(Plane plane, UUID toPlayerId);

    void addCommander(Commander commander);

    Dungeon addDungeon(Dungeon dungeon, UUID playerId);

    /**
     * Enter to dungeon or go to next room
     *
     * @param isEnterToUndercity - enter to Undercity instead choose a new dungeon
     */
    void ventureIntoDungeon(UUID playerId, boolean isEnterToUndercity);

    void temptWithTheRing(UUID playerId);

    /**
     * Tells whether the current game has day or night, defaults to false
     */
    boolean hasDayNight();

    /**
     * Sets game to day or night, sets hasDayNight to true
     *
     * @param daytime day is true, night is false
     */
    void setDaytime(boolean daytime);

    /**
     * Returns true if hasDayNight is true and parameter matches current day/night value
     * Returns false if hasDayNight is false
     *
     * @param daytime day is true, night is false
     */
    boolean checkDayNight(boolean daytime);

    /**
     * Adds a permanent to the battlefield
     *
     * @param permanent
     * @param createOrder upcounting number from state about the create order of
     *                    all permanents. Can equal for multiple permanents, if
     *                    they go to battlefield at the same time. If the value
     *                    is set to 0, a next number will be set automatically.
     */
    void addPermanent(Permanent permanent, int createOrder);

    // priority method
    void sendPlayerAction(PlayerAction playerAction, UUID playerId, Object data);

    /**
     * This version supports copying of copies of any depth.
     *
     * @param copyFromPermanent
     * @param copyToPermanentId
     * @param source
     * @param applier
     * @return
     */
    Permanent copyPermanent(Permanent copyFromPermanent, UUID copyToPermanentId, Ability source, CopyApplier applier);

    Permanent copyPermanent(Duration duration, Permanent copyFromPermanent, UUID copyToPermanentId, Ability source, CopyApplier applier);

    Card copyCard(Card cardToCopy, Ability source, UUID newController);

    void addTriggeredAbility(TriggeredAbility ability, GameEvent triggeringEvent);

    UUID addDelayedTriggeredAbility(DelayedTriggeredAbility delayedAbility, Ability source);

    UUID fireReflexiveTriggeredAbility(ReflexiveTriggeredAbility reflexiveAbility, Ability source);

    UUID fireReflexiveTriggeredAbility(ReflexiveTriggeredAbility reflexiveAbility, Ability source, boolean fireAsSimultaneousEvent);

    /**
     * Inner engine call to reset all game objects and re-apply all layered continuous effects.
     * Do NOT use indiscriminately. See processAction() instead.
     */
    @Deprecated
    void applyEffects();

    /**
     * Handles simultaneous events for triggers and then re-applies all layered continuous effects.
     * Must be called between sequential steps of a resolving one-shot effect.
     * <p>
     * 608.2e. Some spells and abilities have multiple steps or actions, denoted by separate sentences or clauses,
     * that involve multiple players. In these cases, the choices for the first action are made in APNAP order,
     * and then the first action is processed simultaneously. Then the choices for the second action are made in
     * APNAP order, and then that action is processed simultaneously, and so on. See rule 101.4.
     * <p>
     * 608.2f. Some spells and abilities include actions taken on multiple players and/or objects. In most cases,
     * each such action is processed simultaneously. If the action can't be processed simultaneously, it's instead
     * processed considering each affected player or object individually. APNAP order is used to make the primary
     * determination of the order of those actions. Secondarily, if the action is to be taken on both a player
     * and an object they control or on multiple objects controlled by the same player, the player who controls
     * the resolving spell or ability chooses the relative order of those actions.
     */
    void processAction();

    @Deprecated // TODO: must research usage and remove it from all non engine code (example: Bestow ability, ProcessActions must be used instead)
    boolean checkStateAndTriggered();

    /**
     * Play priority by all players
     *
     * @param activePlayerId starting priority player
     * @param resuming false to reset passed priority and ask it again
     */
    void playPriority(UUID activePlayerId, boolean resuming);

    void resetControlAfterSpellResolve(UUID topId);

    boolean endTurn(Ability source);

    //game transaction methods
    void saveState(boolean bookmark);

    /**
     * Save current game state and return bookmark to it
     *
     * @return
     */
    int bookmarkState();

    GameState restoreState(int bookmark, String context);

    /**
     * Remove selected bookmark and all newer bookmarks and game states
     * Part of restore/rollback lifecycle
     *
     * @param bookmark
     */
    void removeBookmark(int bookmark);

    /**
     * TODO: remove logic changed, must research each usage of removeBookmark and replace it with new code
     * @param bookmark
     */
    void removeBookmark_v2(int bookmark);

    int getSavedStateSize();

    boolean isSaveGame();

    void setSaveGame(boolean saveGame);

    // game options
    void setGameOptions(GameOptions options);

    // game times
    Date getStartTime();

    Date getEndTime();

    // game cheats (for tests only)
    void cheat(UUID ownerId, Map<Zone, String> commands);
    void cheat(UUID ownerId, List<Card> library, List<Card> hand, List<PutToBattlefieldInfo> battlefield, List<Card> graveyard, List<Card> command, List<Card> exiled);

    // controlling the behaviour of replacement effects while permanents entering the battlefield
    void setScopeRelevant(boolean scopeRelevant);

    boolean getScopeRelevant();

    // players' timers
    void initTimer(UUID playerId);

    void resumeTimer(UUID playerId);

    void pauseTimer(UUID playerId);

    int getPriorityTime();

    void setPriorityTime(int priorityTime);

    int getBufferTime();

    void setBufferTime(int bufferTime);

    UUID getStartingPlayerId();

    void setStartingPlayerId(UUID startingPlayerId);

    void saveRollBackGameState();

    boolean canRollbackTurns(int turnsToRollback);

    void rollbackTurns(int turnsToRollback);

    boolean executingRollback();

    void addCard(UUID cardId, Card card);


    /**
     * Add counters to permanent before ETB. Use it before put real permanent to battlefield.
     */
    void setEnterWithCounters(UUID sourceId, Counters counters);

    Counters getEnterWithCounters(UUID sourceId);

    /**
     * Get the UUID of the current player who is the Monarch, or null if nobody has it.
     *
     * @return UUID of the Monarch (null if nobody has it).
     */
    UUID getMonarchId();

    void setMonarchId(Ability source, UUID monarchId);

    /**
     * Get the UUID of the current player who has the initiative, or null if nobody has it.
     *
     * @return UUID of the player who currently has the Initiative (null if nobody has it).
     */
    UUID getInitiativeId();

    /**
     * Function to call for a player to take the initiative.
     *
     * @param source       The ability granting initiative.
     * @param initiativeId UUID of the player taking the initiative
     */
    void takeInitiative(Ability source, UUID initiativeId);

    int damagePlayerOrPermanent(UUID playerOrPermanent, int damage, UUID attackerId, Ability source, Game game, boolean combatDamage, boolean preventable);

    int damagePlayerOrPermanent(UUID playerOrPermanent, int damage, UUID attackerId, Ability source, Game game, boolean combatDamage, boolean preventable, List<UUID> appliedEffects);

    Mulligan getMulligan();

    Set<UUID> getCommandersIds(Player player, CommanderCardType commanderCardType, boolean returnAllCardParts);

    /**
     * Return not played commander cards from command zone
     * Read comments for CommanderCardType for more info on commanderCardType usage
     *
     * @param player
     * @return
     */
    default Set<Card> getCommanderCardsFromCommandZone(Player player, CommanderCardType commanderCardType) {
        // commanders in command zone aren't cards so you must call getCard instead getObject
        return getCommandersIds(player, commanderCardType, false).stream()
                .map(this::getCard)
                .filter(Objects::nonNull)
                .filter(card -> Zone.COMMAND.equals(this.getState().getZone(card.getId())))
                .collect(Collectors.toSet());
    }

    /**
     * Return commander cards from any zones (main card from command and permanent card from battlefield)
     * Read comments for CommanderCardType for more info on commanderCardType usage
     *
     * @param player
     * @param commanderCardType commander or signature spell
     * @return
     */
    default Set<Card> getCommanderCardsFromAnyZones(Player player, CommanderCardType commanderCardType, Zone... searchZones) {
        Set<Zone> needZones = Arrays.stream(searchZones).collect(Collectors.toSet());
        if (needZones.isEmpty()) {
            throw new IllegalArgumentException("Empty zones list in searching commanders");
        }
        Set<UUID> needCommandersIds = this.getCommandersIds(player, commanderCardType, true);
        Set<Card> needCommandersCards = needCommandersIds.stream()
                .map(this::getCard)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Card> res = new HashSet<>();

        // hand
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.HAND)) {
            needCommandersCards.stream()
                    .filter(card -> Zone.HAND.equals(this.getState().getZone(card.getId())))
                    .forEach(res::add);
        }

        // graveyard
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.GRAVEYARD)) {
            needCommandersCards.stream()
                    .filter(card -> Zone.GRAVEYARD.equals(this.getState().getZone(card.getId())))
                    .forEach(res::add);
        }

        // library
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.LIBRARY)) {
            needCommandersCards.stream()
                    .filter(card -> Zone.LIBRARY.equals(this.getState().getZone(card.getId())))
                    .forEach(res::add);
        }

        // battlefield (need permanent card)
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.BATTLEFIELD)) {
            needCommandersIds.stream()
                    .map(this::getPermanent)
                    .filter(Objects::nonNull)
                    .forEach(res::add);
        }

        // stack
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.STACK)) {
            needCommandersCards.stream()
                    .filter(card -> Zone.STACK.equals(this.getState().getZone(card.getId())))
                    .forEach(res::add);
        }

        // exiled
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.EXILED)) {
            needCommandersCards.stream()
                    .filter(card -> Zone.EXILED.equals(this.getState().getZone(card.getId())))
                    .forEach(res::add);
        }

        // command
        if (needZones.contains(Zone.ALL) || needZones.contains(Zone.COMMAND)) {
            res.addAll(getCommanderCardsFromCommandZone(player, commanderCardType));
        }

        // outside must be ignored (example: second side of MDFC commander after cast)
        if (needZones.contains(Zone.OUTSIDE)) {
            throw new IllegalArgumentException("Outside zone doesn't supported in searching commanders");
        }

        return res;
    }

    /**
     * Finds is it a commander card/object (use it in conditional and other things)
     *
     * @param player
     * @param object
     * @return
     */
    default boolean isCommanderObject(Player player, MageObject object) {
        UUID idToCheck = null;
        if (object instanceof Spell) {
            idToCheck = ((Spell) object).getCard().getId();
        }
        if (object instanceof CommandObject) {
            idToCheck = object.getId();
        }
        if (object instanceof Card) {
            idToCheck = ((Card) object).getMainCard().getId();
        }
        return idToCheck != null && this.getCommandersIds(player, CommanderCardType.COMMANDER_OR_OATHBREAKER, false).contains(idToCheck);
    }

    void setGameStopped(boolean gameStopped);

    boolean isGameStopped();

    boolean isTurnOrderReversed();
}
