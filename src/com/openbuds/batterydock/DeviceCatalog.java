package com.openbuds.batterydock;

import java.util.Locale;

/** Names advertised by supported earbuds and a few friendly aliases. */
public final class DeviceCatalog {
    private static final String[] TOKENS = {
            "CERAMICS", "EARFREE", "EARFEEL", "OPENFEEL", "OPENFREE", "OPENFUN",
            "OPENLINK", "BUDSFEEL", "BUDSFREE", "SPORTFEEL", "SPORTFREE",
            "SPEAKFEEL", "SPEAKFREE", "HYACINTH", "CORAL", "OPAL", "ZIRCON",
            "JASMINE", "AGATE", "SWIFT", "MIRROR", "EXPLORER", "MAGICIAN",
            "MOKA", "AURAMIC", "HEADFREE", "FURINA", "LUMINOUS", "ASTRO X",
            "ENDLESS SOLO",
            "FIRST ANGEL", "ROSESELSA", "ROSE TECHNICS", "ROSETECHNIC",
            "RUOSHUI", "Buds", "琉璃", "静海"
    };

    private DeviceCatalog() {
    }

    public static boolean looksLikeRose(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String upper = name.toUpperCase(Locale.US);
        for (String token : TOKENS) {
            if (upper.contains(token)) return true;
        }
        return false;
    }

    public static String displayName(String advertised) {
        if (advertised == null) advertised = "";
        String n = normalize(advertised);
        if (n.isEmpty()) return "耳机";

        if (n.contains("静海")) return "静海";
        if (n.contains("FURINA") || n.contains("ENDLESSSOLO")) {
            return "芙宁娜 · 不休独舞";
        }
        if (n.contains("CERAMICSX7034")) return "CERAMICS X 7034 · 琉璃 X";
        if (n.contains("CERAMICSX")) return "CERAMICS X · 琉璃 X";
        if (n.contains("CERAMICSUHSC2710")) return "CERAMICS U HSC 2710";
        if (n.contains("CERAMICSU")) return "CERAMICS U";
        if (n.contains("CERAMICSMK28912F")) return "CERAMICS MK2 8912F · 琉璃 MK2";
        if (n.contains("CERAMICSMK25736") || n.contains("CERAMICSMK25736")) {
            return "CERAMICS MK2 5736 · 琉璃 MK2";
        }
        if (n.contains("CERAMICSMK2")) return "CERAMICS MK2 · 琉璃 MK2";
        if (n.contains("CERAMICS")) return "CERAMICS · 琉璃";
        if (n.contains("EARFREEI5")) return "EARFREE i5";
        if (n.contains("EARFREEI3")) return "EARFREE i3";
        if (n.contains("EARFEELI7")) return "EarFeel i7";
        if (n.contains("EARFREE")) return "EARFREE";
        if (n.contains("OPENFEEL")) return "OpenFeel";
        if (n.contains("OPENFREEL")) return "OpenFeel L";
        if (n.contains("OPENFREE")) return "OpenFree";
        if (n.contains("OPENFUN")) return "OpenFun";
        if (n.contains("OPENLINK")) return "OpenLink";
        if (n.contains("BUDSFEELMK2")) return "BudsFeel MK2";
        if (n.contains("BUDSFEEL")) return "BudsFeel";
        if (n.contains("BUDSFREE")) return "BudsFree";
        if (n.contains("SPORTFEEL8912F")) return "SportFeel 8912F";
        if (n.contains("SPORTFEEL")) return "SportFeel";
        if (n.contains("SPORTFREE")) return "SportFree";
        if (n.contains("SPEAKFEEL2")) return "SpeakFeel 2";
        if (n.contains("SPEAKFEEL")) return "SpeakFeel";
        if (n.contains("SPEAKFREE")) return "SpeakFree";
        if (n.contains("ASTROX")) return "Astro X";
        if (n.contains("FURINA")) return "Furina";
        if (n.contains("HYACINTH")) return "Hyacinth";
        if (n.contains("JASMINE")) return "Jasmine";
        if (n.contains("ZIRCON")) return "Zircon";
        if (n.contains("CORAL")) return "Coral";
        if (n.contains("OPAL")) return "Opal";
        if (n.contains("AGATEX")) return "Agate X";
        if (n.contains("AGATE")) return "Agate";
        if (n.contains("SWIFT2")) return "Swift 2";
        if (n.contains("SWIFT")) return "Swift";
        if (n.contains("MIRROR")) return "Mirror";
        if (n.contains("EXPLORER")) return "Explorer";
        if (n.contains("MAGICIAN")) return "Magician";
        if (n.contains("MOKA")) return "Moka";
        if (n.contains("AURAMIC")) return "Auramic";
        if (n.contains("HEADFREE")) return "HeadFree";
        if (n.contains("FIRSTANGEL")) return "First Angel";
        if (n.contains("LUMINOUS")) return "Luminous";

        String cleaned = advertised.trim();
        if (cleaned.toUpperCase(Locale.US).startsWith("ROSE")) {
            return "Buds · " + cleaned;
        }
        return looksLikeRose(cleaned) ? "Buds · " + cleaned : cleaned;
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .replace("·", "")
                .replace("（", "")
                .replace("）", "");
    }

    public static String shortModel(String model) {
        if (model == null) return "Buds";
        int dot = model.indexOf(" · ");
        return dot > 0 ? model.substring(0, dot) : model;
    }
}
