package mage.player.cabt;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static metadata for one Magic card, the CABT all_card_data() equivalent.
 * <p>
 * Type/subtype strings use XMage's display text ("Creature", "Bear") to match
 * the documented protocol response shape; the JSON keys for the type lists
 * are {@code types}/{@code subtypes}/{@code supertypes} via
 * {@link SerializedName}.
 * <p>
 * Audit note: this is reference data for joining observation object IDs and
 * card names to printed characteristics. Legal choices still come from live
 * XMage state (prompt callbacks and playable queries), not from this class.
 */
public final class MagicCardData {

    private final String cardId;
    private final String name;
    private final String manaCost;
    private final Integer manaValue;
    private final List<String> colors;
    private final List<String> colorIdentity;
    @SerializedName("supertypes")
    private final List<String> supertypes;
    @SerializedName("types")
    private final List<String> cardTypes;
    @SerializedName("subtypes")
    private final List<String> subtypes;
    private final String rulesText;
    private final String power;
    private final String toughness;
    private final String loyalty;
    private final String defense;
    private final List<MagicAbilityData> abilities;

    public MagicCardData(String cardId,
                         String name,
                         String manaCost,
                         Integer manaValue,
                         List<String> colors,
                         List<String> colorIdentity,
                         List<String> supertypes,
                         List<String> cardTypes,
                         List<String> subtypes,
                         String rulesText,
                         String power,
                         String toughness,
                         String loyalty,
                         String defense,
                         List<MagicAbilityData> abilities) {
        if (cardId == null || name == null || colors == null || colorIdentity == null
                || supertypes == null || cardTypes == null || subtypes == null
                || rulesText == null || abilities == null) {
            throw new IllegalArgumentException(
                    "card data identity/type/rules fields must not be null"
                            + " (manaCost, manaValue, power, toughness, loyalty, defense may be null)");
        }
        this.cardId = cardId;
        this.name = name;
        this.manaCost = manaCost;
        this.manaValue = manaValue;
        this.colors = Collections.unmodifiableList(new ArrayList<String>(colors));
        this.colorIdentity = Collections.unmodifiableList(new ArrayList<String>(colorIdentity));
        this.supertypes = Collections.unmodifiableList(new ArrayList<String>(supertypes));
        this.cardTypes = Collections.unmodifiableList(new ArrayList<String>(cardTypes));
        this.subtypes = Collections.unmodifiableList(new ArrayList<String>(subtypes));
        this.rulesText = rulesText;
        this.power = power;
        this.toughness = toughness;
        this.loyalty = loyalty;
        this.defense = defense;
        this.abilities = Collections.unmodifiableList(new ArrayList<MagicAbilityData>(abilities));
    }

    public String getCardId() {
        return cardId;
    }

    public String getName() {
        return name;
    }

    public String getManaCost() {
        return manaCost;
    }

    public Integer getManaValue() {
        return manaValue;
    }

    public List<String> getColors() {
        return colors;
    }

    public List<String> getColorIdentity() {
        return colorIdentity;
    }

    public List<String> getSupertypes() {
        return supertypes;
    }

    public List<String> getCardTypes() {
        return cardTypes;
    }

    public List<String> getSubtypes() {
        return subtypes;
    }

    public String getRulesText() {
        return rulesText;
    }

    public String getPower() {
        return power;
    }

    public String getToughness() {
        return toughness;
    }

    public String getLoyalty() {
        return loyalty;
    }

    public String getDefense() {
        return defense;
    }

    public List<MagicAbilityData> getAbilities() {
        return abilities;
    }
}
