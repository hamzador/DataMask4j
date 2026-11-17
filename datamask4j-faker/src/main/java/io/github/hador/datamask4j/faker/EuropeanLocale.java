package io.github.hador.datamask4j.faker;

import java.util.List;

/** Small curated v1 fixture vocabularies; not representative population samples. */
public enum EuropeanLocale {
    FR_FR("fr-FR",
            List.of("Camille", "Louise", "Gabriel", "Jules", "Inès", "Léa", "Louis", "Nora"),
            List.of("Martin", "Bernard", "Petit", "Robert", "Richard", "Durand", "Moreau", "Laurent")),
    DE_DE("de-DE",
            List.of("Anna", "Lukas", "Mia", "Felix", "Clara", "Emil", "Lea", "Jonas"),
            List.of("Müller", "Schmidt", "Weber", "Fischer", "Meyer", "Wagner", "Schulz", "Koch")),
    ES_ES("es-ES",
            List.of("Lucía", "Hugo", "Sofía", "Pablo", "Alba", "Mateo", "Elena", "Diego"),
            List.of("García", "López", "Martín", "Ruiz", "Torres", "Romero", "Navarro", "Vega")),
    IT_IT("it-IT",
            List.of("Giulia", "Luca", "Sofia", "Marco", "Elena", "Matteo", "Chiara", "Leo"),
            List.of("Rossi", "Russo", "Ferrari", "Bianchi", "Romano", "Ricci", "Marino", "Gallo")),
    FR_BE("fr-BE",
            List.of("Alice", "Arthur", "Emma", "Noah", "Juliette", "Victor", "Lina", "Hugo"),
            List.of("Dubois", "Lambert", "Simon", "Leroy", "Leclercq", "Dumont", "Michel", "Dupont")),
    NL_BE("nl-BE",
            List.of("Emma", "Louis", "Mila", "Noah", "Lotte", "Finn", "Nina", "Jules"),
            List.of("Peeters", "Janssens", "Maes", "Jacobs", "Willems", "Claes", "Goossens", "Wouters")),
    NL_NL("nl-NL",
            List.of("Julia", "Daan", "Saar", "Lucas", "Tess", "Milan", "Liv", "Sem"),
            List.of("De Jong", "Jansen", "De Vries", "Bakker", "Visser", "Smit", "Meijer", "Bos"));

    private final String languageTag;
    private final List<String> firstNames;
    private final List<String> lastNames;

    EuropeanLocale(String languageTag, List<String> firstNames, List<String> lastNames) {
        this.languageTag = languageTag;
        this.firstNames = firstNames;
        this.lastNames = lastNames;
    }

    public String languageTag() {
        return languageTag;
    }

    String firstName(int index) {
        return firstNames.get(index % firstNames.size());
    }

    String lastName(int index) {
        return lastNames.get(index % lastNames.size());
    }
}
