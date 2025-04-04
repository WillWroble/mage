package mage.sets;

import mage.cards.ExpansionSet;
import mage.collation.BoosterCollator;
import mage.collation.BoosterStructure;
import mage.collation.CardRun;
import mage.collation.RarityConfiguration;
import mage.constants.Rarity;
import mage.constants.SetType;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public final class Conflux extends ExpansionSet {

    private static final Conflux instance = new Conflux();

    public static Conflux getInstance() {
        return instance;
    }

    private Conflux() {
        super("Conflux", "CON", ExpansionSet.buildDate(2009, 0, 31), SetType.EXPANSION);
        this.blockName = "Shards of Alara";
        this.parentSet = ShardsOfAlara.getInstance();
        this.hasBasicLands = false;
        this.hasBoosters = true;
        this.numBoosterLands = 1;
        this.numBoosterCommon = 10;
        this.numBoosterUncommon = 3;
        this.numBoosterRare = 1;
        this.ratioBoosterMythic = 8;
        cards.add(new SetCardInfo("Absorb Vis", 40, Rarity.COMMON, mage.cards.a.AbsorbVis.class));
        cards.add(new SetCardInfo("Aerie Mystics", 1, Rarity.UNCOMMON, mage.cards.a.AerieMystics.class));
        cards.add(new SetCardInfo("Ancient Ziggurat", 141, Rarity.UNCOMMON, mage.cards.a.AncientZiggurat.class));
        cards.add(new SetCardInfo("Apocalypse Hydra", 98, Rarity.MYTHIC, mage.cards.a.ApocalypseHydra.class));
        cards.add(new SetCardInfo("Armillary Sphere", 134, Rarity.COMMON, mage.cards.a.ArmillarySphere.class));
        cards.add(new SetCardInfo("Asha's Favor", 2, Rarity.COMMON, mage.cards.a.AshasFavor.class));
        cards.add(new SetCardInfo("Aven Squire", 3, Rarity.COMMON, mage.cards.a.AvenSquire.class));
        cards.add(new SetCardInfo("Aven Trailblazer", 4, Rarity.COMMON, mage.cards.a.AvenTrailblazer.class));
        cards.add(new SetCardInfo("Banefire", 58, Rarity.RARE, mage.cards.b.Banefire.class));
        cards.add(new SetCardInfo("Beacon Behemoth", 78, Rarity.COMMON, mage.cards.b.BeaconBehemoth.class));
        cards.add(new SetCardInfo("Bloodhall Ooze", 59, Rarity.RARE, mage.cards.b.BloodhallOoze.class));
        cards.add(new SetCardInfo("Blood Tyrant", 99, Rarity.RARE, mage.cards.b.BloodTyrant.class));
        cards.add(new SetCardInfo("Bone Saw", 135, Rarity.COMMON, mage.cards.b.BoneSaw.class));
        cards.add(new SetCardInfo("Brackwater Elemental", 21, Rarity.COMMON, mage.cards.b.BrackwaterElemental.class));
        cards.add(new SetCardInfo("Canyon Minotaur", 60, Rarity.COMMON, mage.cards.c.CanyonMinotaur.class));
        cards.add(new SetCardInfo("Celestial Purge", 5, Rarity.UNCOMMON, mage.cards.c.CelestialPurge.class));
        cards.add(new SetCardInfo("Charnelhoard Wurm", 100, Rarity.RARE, mage.cards.c.CharnelhoardWurm.class));
        cards.add(new SetCardInfo("Child of Alara", 101, Rarity.MYTHIC, mage.cards.c.ChildOfAlara.class));
        cards.add(new SetCardInfo("Cliffrunner Behemoth", 79, Rarity.RARE, mage.cards.c.CliffrunnerBehemoth.class));
        cards.add(new SetCardInfo("Conflux", 102, Rarity.MYTHIC, mage.cards.c.Conflux.class));
        cards.add(new SetCardInfo("Constricting Tendrils", 22, Rarity.COMMON, mage.cards.c.ConstrictingTendrils.class));
        cards.add(new SetCardInfo("Controlled Instincts", 23, Rarity.UNCOMMON, mage.cards.c.ControlledInstincts.class));
        cards.add(new SetCardInfo("Corrupted Roots", 41, Rarity.UNCOMMON, mage.cards.c.CorruptedRoots.class));
        cards.add(new SetCardInfo("Countersquall", 103, Rarity.UNCOMMON, mage.cards.c.Countersquall.class));
        cards.add(new SetCardInfo("Court Homunculus", 6, Rarity.COMMON, mage.cards.c.CourtHomunculus.class));
        cards.add(new SetCardInfo("Cumber Stone", 24, Rarity.UNCOMMON, mage.cards.c.CumberStone.class));
        cards.add(new SetCardInfo("Cylian Sunsinger", 80, Rarity.RARE, mage.cards.c.CylianSunsinger.class));
        cards.add(new SetCardInfo("Darklit Gargoyle", 7, Rarity.COMMON, mage.cards.d.DarklitGargoyle.class));
        cards.add(new SetCardInfo("Dark Temper", 61, Rarity.COMMON, mage.cards.d.DarkTemper.class));
        cards.add(new SetCardInfo("Drag Down", 42, Rarity.COMMON, mage.cards.d.DragDown.class));
        cards.add(new SetCardInfo("Dragonsoul Knight", 62, Rarity.UNCOMMON, mage.cards.d.DragonsoulKnight.class));
        cards.add(new SetCardInfo("Dreadwing", 43, Rarity.UNCOMMON, mage.cards.d.Dreadwing.class));
        cards.add(new SetCardInfo("Elder Mastery", 104, Rarity.UNCOMMON, mage.cards.e.ElderMastery.class));
        cards.add(new SetCardInfo("Ember Weaver", 81, Rarity.COMMON, mage.cards.e.EmberWeaver.class));
        cards.add(new SetCardInfo("Esper Cormorants", 105, Rarity.COMMON, mage.cards.e.EsperCormorants.class));
        cards.add(new SetCardInfo("Esperzoa", 25, Rarity.UNCOMMON, mage.cards.e.Esperzoa.class));
        cards.add(new SetCardInfo("Ethersworn Adjudicator", 26, Rarity.MYTHIC, mage.cards.e.EtherswornAdjudicator.class));
        cards.add(new SetCardInfo("Exotic Orchard", 142, Rarity.RARE, mage.cards.e.ExoticOrchard.class));
        cards.add(new SetCardInfo("Exploding Borders", 106, Rarity.COMMON, mage.cards.e.ExplodingBorders.class));
        cards.add(new SetCardInfo("Extractor Demon", 44, Rarity.RARE, mage.cards.e.ExtractorDemon.class));
        cards.add(new SetCardInfo("Faerie Mechanist", 27, Rarity.COMMON, mage.cards.f.FaerieMechanist.class));
        cards.add(new SetCardInfo("Fiery Fall", 63, Rarity.COMMON, mage.cards.f.FieryFall.class));
        cards.add(new SetCardInfo("Filigree Fracture", 82, Rarity.UNCOMMON, mage.cards.f.FiligreeFracture.class));
        cards.add(new SetCardInfo("Fleshformer", 45, Rarity.UNCOMMON, mage.cards.f.Fleshformer.class));
        cards.add(new SetCardInfo("Font of Mythos", 136, Rarity.RARE, mage.cards.f.FontOfMythos.class));
        cards.add(new SetCardInfo("Frontline Sage", 28, Rarity.COMMON, mage.cards.f.FrontlineSage.class));
        cards.add(new SetCardInfo("Fusion Elemental", 107, Rarity.UNCOMMON, mage.cards.f.FusionElemental.class));
        cards.add(new SetCardInfo("Giltspire Avenger", 108, Rarity.RARE, mage.cards.g.GiltspireAvenger.class));
        cards.add(new SetCardInfo("Gleam of Resistance", 8, Rarity.COMMON, mage.cards.g.GleamOfResistance.class));
        cards.add(new SetCardInfo("Gluttonous Slime", 83, Rarity.UNCOMMON, mage.cards.g.GluttonousSlime.class));
        cards.add(new SetCardInfo("Goblin Outlander", 109, Rarity.COMMON, mage.cards.g.GoblinOutlander.class));
        cards.add(new SetCardInfo("Goblin Razerunners", 64, Rarity.RARE, mage.cards.g.GoblinRazerunners.class));
        cards.add(new SetCardInfo("Grixis Illusionist", 29, Rarity.COMMON, mage.cards.g.GrixisIllusionist.class));
        cards.add(new SetCardInfo("Grixis Slavedriver", 46, Rarity.UNCOMMON, mage.cards.g.GrixisSlavedriver.class));
        cards.add(new SetCardInfo("Gwafa Hazid, Profiteer", 110, Rarity.RARE, mage.cards.g.GwafaHazidProfiteer.class));
        cards.add(new SetCardInfo("Hellkite Hatchling", 111, Rarity.UNCOMMON, mage.cards.h.HellkiteHatchling.class));
        cards.add(new SetCardInfo("Hellspark Elemental", 65, Rarity.UNCOMMON, mage.cards.h.HellsparkElemental.class));
        cards.add(new SetCardInfo("Ignite Disorder", 66, Rarity.UNCOMMON, mage.cards.i.IgniteDisorder.class));
        cards.add(new SetCardInfo("Infectious Horror", 47, Rarity.COMMON, mage.cards.i.InfectiousHorror.class));
        cards.add(new SetCardInfo("Inkwell Leviathan", 30, Rarity.RARE, mage.cards.i.InkwellLeviathan.class));
        cards.add(new SetCardInfo("Jhessian Balmgiver", 112, Rarity.UNCOMMON, mage.cards.j.JhessianBalmgiver.class));
        cards.add(new SetCardInfo("Kaleidostone", 137, Rarity.COMMON, mage.cards.k.Kaleidostone.class));
        cards.add(new SetCardInfo("Kederekt Parasite", 48, Rarity.RARE, mage.cards.k.KederektParasite.class));
        cards.add(new SetCardInfo("Knight of the Reliquary", 113, Rarity.RARE, mage.cards.k.KnightOfTheReliquary.class));
        cards.add(new SetCardInfo("Knotvine Mystic", 114, Rarity.UNCOMMON, mage.cards.k.KnotvineMystic.class));
        cards.add(new SetCardInfo("Kranioceros", 67, Rarity.COMMON, mage.cards.k.Kranioceros.class));
        cards.add(new SetCardInfo("Lapse of Certainty", 9, Rarity.COMMON, mage.cards.l.LapseOfCertainty.class));
        cards.add(new SetCardInfo("Maelstrom Archangel", 115, Rarity.MYTHIC, mage.cards.m.MaelstromArchangel.class));
        cards.add(new SetCardInfo("Magister Sphinx", 116, Rarity.RARE, mage.cards.m.MagisterSphinx.class));
        cards.add(new SetCardInfo("Malfegor", 117, Rarity.MYTHIC, mage.cards.m.Malfegor.class));
        cards.add(new SetCardInfo("Mana Cylix", 138, Rarity.COMMON, mage.cards.m.ManaCylix.class));
        cards.add(new SetCardInfo("Manaforce Mace", 139, Rarity.UNCOMMON, mage.cards.m.ManaforceMace.class));
        cards.add(new SetCardInfo("Maniacal Rage", 68, Rarity.COMMON, mage.cards.m.ManiacalRage.class));
        cards.add(new SetCardInfo("Mark of Asylum", 10, Rarity.RARE, mage.cards.m.MarkOfAsylum.class));
        cards.add(new SetCardInfo("Martial Coup", 11, Rarity.RARE, mage.cards.m.MartialCoup.class));
        cards.add(new SetCardInfo("Master Transmuter", 31, Rarity.RARE, mage.cards.m.MasterTransmuter.class));
        cards.add(new SetCardInfo("Matca Rioters", 84, Rarity.COMMON, mage.cards.m.MatcaRioters.class));
        cards.add(new SetCardInfo("Meglonoth", 118, Rarity.RARE, mage.cards.m.Meglonoth.class));
        cards.add(new SetCardInfo("Might of Alara", 85, Rarity.COMMON, mage.cards.m.MightOfAlara.class));
        cards.add(new SetCardInfo("Mirror-Sigil Sergeant", 12, Rarity.MYTHIC, mage.cards.m.MirrorSigilSergeant.class));
        cards.add(new SetCardInfo("Molten Frame", 69, Rarity.COMMON, mage.cards.m.MoltenFrame.class));
        cards.add(new SetCardInfo("Nacatl Hunt-Pride", 13, Rarity.UNCOMMON, mage.cards.n.NacatlHuntPride.class));
        cards.add(new SetCardInfo("Nacatl Outlander", 119, Rarity.COMMON, mage.cards.n.NacatlOutlander.class));
        cards.add(new SetCardInfo("Nacatl Savage", 86, Rarity.COMMON, mage.cards.n.NacatlSavage.class));
        cards.add(new SetCardInfo("Nicol Bolas, Planeswalker", 120, Rarity.MYTHIC, mage.cards.n.NicolBolasPlaneswalker.class));
        cards.add(new SetCardInfo("Noble Hierarch", 87, Rarity.RARE, mage.cards.n.NobleHierarch.class));
        cards.add(new SetCardInfo("Nyxathid", 49, Rarity.RARE, mage.cards.n.Nyxathid.class));
        cards.add(new SetCardInfo("Obelisk of Alara", 140, Rarity.RARE, mage.cards.o.ObeliskOfAlara.class));
        cards.add(new SetCardInfo("Paleoloth", 88, Rarity.RARE, mage.cards.p.Paleoloth.class));
        cards.add(new SetCardInfo("Paragon of the Amesha", 14, Rarity.UNCOMMON, mage.cards.p.ParagonOfTheAmesha.class));
        cards.add(new SetCardInfo("Parasitic Strix", 32, Rarity.COMMON, mage.cards.p.ParasiticStrix.class));
        cards.add(new SetCardInfo("Path to Exile", 15, Rarity.UNCOMMON, mage.cards.p.PathToExile.class));
        cards.add(new SetCardInfo("Pestilent Kathari", 50, Rarity.COMMON, mage.cards.p.PestilentKathari.class));
        cards.add(new SetCardInfo("Progenitus", 121, Rarity.MYTHIC, mage.cards.p.Progenitus.class));
        cards.add(new SetCardInfo("Quenchable Fire", 70, Rarity.COMMON, mage.cards.q.QuenchableFire.class));
        cards.add(new SetCardInfo("Rakka Mar", 71, Rarity.RARE, mage.cards.r.RakkaMar.class));
        cards.add(new SetCardInfo("Reliquary Tower", 143, Rarity.UNCOMMON, mage.cards.r.ReliquaryTower.class));
        cards.add(new SetCardInfo("Rhox Bodyguard", 122, Rarity.COMMON, mage.cards.r.RhoxBodyguard.class));
        cards.add(new SetCardInfo("Rhox Meditant", 16, Rarity.COMMON, mage.cards.r.RhoxMeditant.class));
        cards.add(new SetCardInfo("Rotting Rats", 51, Rarity.COMMON, mage.cards.r.RottingRats.class));
        cards.add(new SetCardInfo("Rupture Spire", 144, Rarity.COMMON, mage.cards.r.RuptureSpire.class));
        cards.add(new SetCardInfo("Sacellum Archers", 89, Rarity.UNCOMMON, mage.cards.s.SacellumArchers.class));
        cards.add(new SetCardInfo("Salvage Slasher", 52, Rarity.COMMON, mage.cards.s.SalvageSlasher.class));
        cards.add(new SetCardInfo("Scarland Thrinax", 123, Rarity.UNCOMMON, mage.cards.s.ScarlandThrinax.class));
        cards.add(new SetCardInfo("Scattershot Archer", 90, Rarity.COMMON, mage.cards.s.ScattershotArcher.class));
        cards.add(new SetCardInfo("Scepter of Dominance", 17, Rarity.RARE, mage.cards.s.ScepterOfDominance.class));
        cards.add(new SetCardInfo("Scepter of Fugue", 53, Rarity.RARE, mage.cards.s.ScepterOfFugue.class));
        cards.add(new SetCardInfo("Scepter of Insight", 33, Rarity.RARE, mage.cards.s.ScepterOfInsight.class));
        cards.add(new SetCardInfo("Scornful Aether-Lich", 34, Rarity.UNCOMMON, mage.cards.s.ScornfulAetherLich.class));
        cards.add(new SetCardInfo("Sedraxis Alchemist", 54, Rarity.COMMON, mage.cards.s.SedraxisAlchemist.class));
        cards.add(new SetCardInfo("Shambling Remains", 124, Rarity.UNCOMMON, mage.cards.s.ShamblingRemains.class));
        cards.add(new SetCardInfo("Shard Convergence", 91, Rarity.UNCOMMON, mage.cards.s.ShardConvergence.class));
        cards.add(new SetCardInfo("Sigil of the Empty Throne", 18, Rarity.RARE, mage.cards.s.SigilOfTheEmptyThrone.class));
        cards.add(new SetCardInfo("Skyward Eye Prophets", 125, Rarity.UNCOMMON, mage.cards.s.SkywardEyeProphets.class));
        cards.add(new SetCardInfo("Sludge Strider", 126, Rarity.UNCOMMON, mage.cards.s.SludgeStrider.class));
        cards.add(new SetCardInfo("Soul's Majesty", 92, Rarity.RARE, mage.cards.s.SoulsMajesty.class));
        cards.add(new SetCardInfo("Sphinx Summoner", 127, Rarity.RARE, mage.cards.s.SphinxSummoner.class));
        cards.add(new SetCardInfo("Spore Burst", 93, Rarity.UNCOMMON, mage.cards.s.SporeBurst.class));
        cards.add(new SetCardInfo("Suicidal Charge", 128, Rarity.COMMON, mage.cards.s.SuicidalCharge.class));
        cards.add(new SetCardInfo("Sylvan Bounty", 94, Rarity.COMMON, mage.cards.s.SylvanBounty.class));
        cards.add(new SetCardInfo("Telemin Performance", 35, Rarity.RARE, mage.cards.t.TeleminPerformance.class));
        cards.add(new SetCardInfo("Thornling", 95, Rarity.MYTHIC, mage.cards.t.Thornling.class));
        cards.add(new SetCardInfo("Toxic Iguanar", 72, Rarity.COMMON, mage.cards.t.ToxicIguanar.class));
        cards.add(new SetCardInfo("Traumatic Visions", 36, Rarity.COMMON, mage.cards.t.TraumaticVisions.class));
        cards.add(new SetCardInfo("Tukatongue Thallid", 96, Rarity.COMMON, mage.cards.t.TukatongueThallid.class));
        cards.add(new SetCardInfo("Unstable Frontier", 145, Rarity.UNCOMMON, mage.cards.u.UnstableFrontier.class));
        cards.add(new SetCardInfo("Unsummon", 37, Rarity.COMMON, mage.cards.u.Unsummon.class));
        cards.add(new SetCardInfo("Vagrant Plowbeasts", 129, Rarity.UNCOMMON, mage.cards.v.VagrantPlowbeasts.class));
        cards.add(new SetCardInfo("Valeron Outlander", 130, Rarity.COMMON, mage.cards.v.ValeronOutlander.class));
        cards.add(new SetCardInfo("Valiant Guard", 19, Rarity.COMMON, mage.cards.v.ValiantGuard.class));
        cards.add(new SetCardInfo("Vectis Agents", 131, Rarity.COMMON, mage.cards.v.VectisAgents.class));
        cards.add(new SetCardInfo("Vedalken Outlander", 132, Rarity.COMMON, mage.cards.v.VedalkenOutlander.class));
        cards.add(new SetCardInfo("Viashino Slaughtermaster", 73, Rarity.UNCOMMON, mage.cards.v.ViashinoSlaughtermaster.class));
        cards.add(new SetCardInfo("View from Above", 38, Rarity.UNCOMMON, mage.cards.v.ViewFromAbove.class));
        cards.add(new SetCardInfo("Voices from the Void", 55, Rarity.UNCOMMON, mage.cards.v.VoicesFromTheVoid.class));
        cards.add(new SetCardInfo("Volcanic Fallout", 74, Rarity.UNCOMMON, mage.cards.v.VolcanicFallout.class));
        cards.add(new SetCardInfo("Voracious Dragon", 75, Rarity.RARE, mage.cards.v.VoraciousDragon.class));
        cards.add(new SetCardInfo("Wall of Reverence", 20, Rarity.RARE, mage.cards.w.WallOfReverence.class));
        cards.add(new SetCardInfo("Wandering Goblins", 76, Rarity.COMMON, mage.cards.w.WanderingGoblins.class));
        cards.add(new SetCardInfo("Wild Leotau", 97, Rarity.COMMON, mage.cards.w.WildLeotau.class));
        cards.add(new SetCardInfo("Worldheart Phoenix", 77, Rarity.RARE, mage.cards.w.WorldheartPhoenix.class));
        cards.add(new SetCardInfo("Worldly Counsel", 39, Rarity.COMMON, mage.cards.w.WorldlyCounsel.class));
        cards.add(new SetCardInfo("Wretched Banquet", 56, Rarity.COMMON, mage.cards.w.WretchedBanquet.class));
        cards.add(new SetCardInfo("Yoke of the Damned", 57, Rarity.COMMON, mage.cards.y.YokeOfTheDamned.class));
        cards.add(new SetCardInfo("Zombie Outlander", 133, Rarity.COMMON, mage.cards.z.ZombieOutlander.class));
    }

    @Override
    public BoosterCollator createCollator() {
        return new ConfluxCollator();
    }
}

// Booster collation info from https://vm1.substation33.com/tiera/t/lethe/cfx.html
// Using USA collation
class ConfluxCollator implements BoosterCollator {
    private final CardRun commonA = new CardRun(true, "8", "97", "28", "63", "50", "130", "134", "42", "4", "72", "36", "78", "138", "133", "84", "7", "63", "56", "105", "32", "4", "137", "81", "72", "27", "135", "8", "54", "132", "97", "61", "28", "3", "56", "94", "60", "36", "54", "137", "16", "133", "78", "7", "37", "134", "67", "27", "40", "130", "94", "3", "50", "61", "32", "84", "135", "42", "60", "105", "37", "16", "132", "40", "81", "67", "138");
    private final CardRun commonB = new CardRun(true, "90", "47", "39", "70", "51", "2", "119", "29", "106", "9", "90", "57", "86", "76", "21", "128", "68", "51", "109", "6", "39", "85", "52", "21", "106", "68", "119", "86", "19", "29", "57", "131", "70", "96", "9", "144", "128", "22", "6", "69", "47", "122", "76", "85", "19", "52", "109", "22", "96", "2", "144", "131", "69", "122");
    private final CardRun uncommonA = new CardRun(false, "141", "5", "23", "41", "103", "24", "62", "82", "107", "83", "65", "66", "112", "114", "139", "13", "14", "143", "91", "129", "38", "55");
    private final CardRun uncommonB = new CardRun(false, "1", "43", "104", "25", "45", "46", "111", "15", "89", "123", "34", "124", "125", "126", "93", "145", "73", "74");
    private final CardRun rare = new CardRun(false, "58", "58", "59", "59", "99", "99", "100", "100", "79", "79", "80", "80", "142", "142", "44", "44", "136", "136", "108", "108", "64", "64", "110", "110", "30", "30", "48", "48", "113", "113", "116", "116", "10", "10", "11", "11", "31", "31", "118", "118", "87", "87", "49", "49", "140", "140", "88", "88", "71", "71", "17", "17", "53", "53", "33", "33", "18", "18", "92", "92", "127", "127", "35", "35", "75", "75", "20", "20", "77", "77", "98", "101", "102", "26", "115", "117", "12", "120", "121", "95");
    private final CardRun land = new CardRun(false, "ALA_230", "ALA_231", "ALA_232", "ALA_233", "ALA_234", "ALA_235", "ALA_236", "ALA_237", "ALA_238", "ALA_239", "ALA_240", "ALA_241", "ALA_242", "ALA_243", "ALA_244", "ALA_245", "ALA_246", "ALA_247", "ALA_248", "ALA_249");

    private final BoosterStructure AAAAAABBBB = new BoosterStructure(
            commonA, commonA, commonA, commonA, commonA, commonA,
            commonB, commonB, commonB, commonB
    );
    private final BoosterStructure AAAAABBBBB = new BoosterStructure(
            commonA, commonA, commonA, commonA, commonA,
            commonB, commonB, commonB, commonB, commonB
    );
    private final BoosterStructure AAB = new BoosterStructure(uncommonA, uncommonA, uncommonB);
    private final BoosterStructure ABB = new BoosterStructure(uncommonA, uncommonB, uncommonB);
    private final BoosterStructure R1 = new BoosterStructure(rare);
    private final BoosterStructure L1 = new BoosterStructure(land);

    // In order for equal numbers of each common to exist, the average booster must contain:
    // 5.5 A commons (11 / 2)
    // 4.5 B commons ( 9 / 2)
    private final RarityConfiguration commonRuns = new RarityConfiguration(
            AAAAAABBBB,
            AAAAABBBBB
    );
    // In order for equal numbers of each uncommon to exist, the average booster must contain:
    // 1.65 A uncommons (33 / 20)
    // 1.35 B uncommons (27 / 20)
    // These numbers are the same for all sets with 60 uncommons in asymmetrical A/B print runs
    private final RarityConfiguration uncommonRuns = new RarityConfiguration(
            AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB, AAB,
            ABB, ABB, ABB, ABB, ABB, ABB, ABB
    );
    private final RarityConfiguration rareRuns = new RarityConfiguration(R1);
    private final RarityConfiguration landRuns = new RarityConfiguration(L1);

    @Override
    public List<String> makeBooster() {
        List<String> booster = new ArrayList<>();
        booster.addAll(commonRuns.getNext().makeRun());
        booster.addAll(uncommonRuns.getNext().makeRun());
        booster.addAll(rareRuns.getNext().makeRun());
        booster.addAll(landRuns.getNext().makeRun());
        return booster;
    }
}
