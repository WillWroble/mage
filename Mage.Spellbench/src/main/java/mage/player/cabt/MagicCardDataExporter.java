package mage.player.cabt;

import mage.MageObject;
import mage.ObjectColor;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.costs.Cost;
import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.FilterMana;
import mage.target.Target;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Exports static card metadata (the CABT all_card_data() equivalent) from
 * XMage {@link Card} instances.
 * <p>
 * Reads only the card's printed characteristics via {@link MageObject}/
 * {@link Card} getters and each {@link Ability}'s definition (type, rule,
 * zone, costs, targets, modes) — never live game state. Type strings use the
 * XMage display text (CardType/SubType/SuperType toString), matching the
 * protocol response shape; everywhere else in the bridge enum {@code .name()}
 * is used because those enums' toString is UI display text.
 */
public final class MagicCardDataExporter {

    public MagicCardData export(Card card) {
        if (card == null) {
            throw new IllegalArgumentException("card must not be null");
        }
        List<String> cardTypes = new ArrayList<String>();
        for (CardType type : card.getCardType()) {
            cardTypes.add(type.toString());
        }
        List<String> subtypes = new ArrayList<String>();
        for (SubType subType : card.getSubtype()) {
            subtypes.add(subType.toString());
        }
        List<String> supertypes = new ArrayList<String>();
        for (SuperType superType : card.getSuperType()) {
            supertypes.add(superType.toString());
        }
        boolean creature = card.getCardType().contains(CardType.CREATURE);
        boolean planeswalker = card.getCardType().contains(CardType.PLANESWALKER);
        boolean battle = card.getCardType().contains(CardType.BATTLE);
        List<MagicAbilityData> abilities = new ArrayList<MagicAbilityData>();
        for (Ability ability : card.getAbilities()) {
            abilities.add(exportAbility(ability));
        }
        return new MagicCardData(
                card.getId().toString(),
                card.getName(),
                card.getManaCost().getText(),
                card.getManaValue(),
                colorSymbols(card.getColor()),
                colorIdentitySymbols(card.getColorIdentity()),
                supertypes,
                cardTypes,
                subtypes,
                joinRules(card.getRules()),
                creature ? card.getPower().toString() : null,
                creature ? card.getToughness().toString() : null,
                planeswalker ? String.valueOf(card.getStartingLoyalty()) : null,
                battle ? String.valueOf(card.getStartingDefense()) : null,
                abilities);
    }

    public List<MagicCardData> export(Collection<? extends Card> cards) {
        if (cards == null) {
            throw new IllegalArgumentException("cards must not be null");
        }
        List<MagicCardData> exported = new ArrayList<MagicCardData>();
        for (Card card : cards) {
            exported.add(export(card));
        }
        return exported;
    }

    private MagicAbilityData exportAbility(Ability ability) {
        List<String> costs = new ArrayList<String>();
        for (Cost cost : ability.getCosts()) {
            String text = cost.getText();
            if (text != null && !text.isEmpty()) {
                costs.add(text);
            }
        }
        List<String> targets = new ArrayList<String>();
        for (Target target : ability.getTargets()) {
            targets.add(target.getTargetName());
        }
        // a plain ability's Modes always contains its single default mode;
        // only genuinely modal abilities list mode texts here
        List<String> modes = new ArrayList<String>();
        if (ability.getModes().size() > 1) {
            for (Mode mode : ability.getModes().values()) {
                modes.add(mode.getEffects() == null ? "" : mode.getEffects().getText(mode));
            }
        }
        String manaCostText = ability.getManaCosts().getText();
        String rule = ability.getRule();
        return new MagicAbilityData(
                ability.getAbilityType().name(),
                rule == null ? "" : rule,
                ability.getZone().name(),
                manaCostText == null || manaCostText.isEmpty() ? null : manaCostText,
                costs,
                targets,
                modes);
    }

    private static List<String> colorSymbols(ObjectColor color) {
        List<String> symbols = new ArrayList<String>();
        for (ObjectColor mono : color.getColors()) {
            symbols.add(mono.toString());
        }
        return symbols;
    }

    private static List<String> colorIdentitySymbols(FilterMana identity) {
        List<String> symbols = new ArrayList<String>();
        if (identity == null) {
            return symbols;
        }
        if (identity.isWhite()) {
            symbols.add("W");
        }
        if (identity.isBlue()) {
            symbols.add("U");
        }
        if (identity.isBlack()) {
            symbols.add("B");
        }
        if (identity.isRed()) {
            symbols.add("R");
        }
        if (identity.isGreen()) {
            symbols.add("G");
        }
        return symbols;
    }

    private static String joinRules(List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String rule : rules) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(rule);
        }
        return joined.toString();
    }
}
