package com.collectorstools.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Wytwórnia danych zastępczych dla anonimizacji.
 *
 * <p>Podstawiane wartości są <b>deterministyczne względem oryginału</b>: ta sama osoba
 * dostaje wszędzie tę samą tożsamość zastępczą. Bez tego 16 osób, które w portfelu miały
 * po kilka spraw, rozpadłoby się na 16 osobnych dłużników i plik demonstracyjny przestałby
 * pokazywać to, co w danych najciekawsze — relację jeden dłużnik : wiele spraw.
 *
 * <p>Determinizm nie oznacza odwracalności. Wartość zastępcza powstaje z ziarna
 * wyliczonego z oryginału, ale nie niesie żadnego jego fragmentu: z numeru zastępczego
 * nie da się odtworzyć numeru źródłowego inaczej niż mając plik źródłowy — a ten
 * nigdy nie trafia do repozytorium.
 */
public class FakeIdentities {

    /** Zmiana wartości unieważnia wcześniejsze pliki demo — celowo stała. */
    private static final long SALT = 20170208L;

    private static final List<String> MALE_NAMES = List.of(
            "Andrzej", "Piotr", "Tomasz", "Marcin", "Paweł", "Krzysztof", "Michał",
            "Grzegorz", "Adam", "Rafał", "Jacek", "Wojciech", "Damian", "Sławomir");

    private static final List<String> FEMALE_NAMES = List.of(
            "Anna", "Katarzyna", "Magdalena", "Agnieszka", "Ewa", "Joanna", "Barbara",
            "Monika", "Beata", "Dorota", "Renata", "Iwona", "Sylwia", "Halina");

    private static final List<String> SURNAMES = List.of(
            "Kowalczyk", "Wiśniewski", "Dąbrowski", "Lewandowski", "Zieliński", "Szymański",
            "Woźniak", "Kozłowski", "Jankowski", "Mazur", "Krawczyk", "Piotrowski",
            "Grabowski", "Nowicki", "Pawłowski", "Michalski", "Adamczyk", "Dudek",
            "Zając", "Wieczorek", "Jabłoński", "Król", "Majewski", "Olszewski");

    private static final List<String> STREETS = List.of(
            "Polna", "Ogrodowa", "Lipowa", "Słoneczna", "Krótka", "Leśna", "Kwiatowa",
            "Brzozowa", "Kalinowa", "Wrzosowa", "Jaśminowa", "Akacjowa", "Cicha",
            "Rolna", "Sadowa", "Zaciszna", "Wesoła", "Jesienna");

    /**
     * Ulice adresów pracodawców — pula rozłączna z {@link #STREETS}. Wspólna pula
     * sprawiała, że adres zakładu pracy trafiał czasem w prawdziwą ulicę dłużnika
     * z tego samego wiersza; zbieżność przypadkowa, ale nieodróżnialna od przecieku.
     */
    private static final List<String> BUSINESS_STREETS = List.of(
            "Przemysłowa", "Fabryczna", "Handlowa", "Składowa", "Rzemieślnicza",
            "Magazynowa", "Spółdzielcza", "Metalowa", "Budowlana", "Hutnicza");

    private static final List<String> EMPLOYERS = List.of(
            "ZAKŁAD PRODUKCYJNY MERITUM", "PIEKARNIA POD LIPĄ", "TRANSPORT KRAJOWY LOGIS",
            "SPÓŁDZIELNIA OGRODNICZA PLON", "HURTOWNIA BUDOWLANA WEGA", "ZAKŁAD STOLARSKI DĄB",
            "PRZETWÓRNIA OWOCÓW SADEX", "SERWIS SAMOCHODOWY MOTO-TECH", "SKLEP SPOŻYWCZY GROSZEK",
            "FIRMA SPRZĄTAJĄCA CZYSTOŚĆ", "ZAKŁAD MIĘSNY POLANA", "DRUKARNIA OFFSET-PRESS");

    /** Prefiksy polskich numerów komórkowych — numer ma wyglądać wiarygodnie. */
    private static final List<String> PHONE_PREFIXES =
            List.of("50", "51", "53", "57", "60", "66", "69", "72", "78", "79", "88");

    private final Map<String, FakePerson> people = new HashMap<>();
    private final Map<String, String> phones = new HashMap<>();
    private final Map<String, String> streets = new HashMap<>();
    private final Map<String, String> accounts = new HashMap<>();
    private final Map<String, String> employers = new HashMap<>();
    private final Map<String, String> caseNumbers = new HashMap<>();
    private final Set<String> usedPesels = new HashSet<>();

    /** Osoba zastępcza: imię, nazwisko i zgodny z płcią numer PESEL. */
    public record FakePerson(String firstName, String lastName, String pesel) {
        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    /**
     * Tożsamość zastępcza dla podanego klucza.
     *
     * @param key wartość identyfikująca osobę w pliku źródłowym — PESEL, jeśli jest,
     *            w przeciwnym razie nazwisko; decyduje o tym wywołujący
     */
    public FakePerson personFor(String key) {
        return personFor(key, null);
    }

    /**
     * @param originalName imię i nazwisko z pliku źródłowego; żaden jego człon nie zostanie
     *                     użyty w tożsamości zastępczej. Trafienie w prawdziwe nazwisko jest
     *                     przy puli kilkudziesięciu nazwisk kwestią czasu, a w wyniku wygląda
     *                     dokładnie tak samo jak niezanonimizowany wiersz
     */
    public FakePerson personFor(String key, String originalName) {
        return people.computeIfAbsent(key, id -> buildPerson(id, originalName));
    }

    private FakePerson buildPerson(String key, String originalName) {
        Random random = randomFor("person", key);
        boolean female = random.nextBoolean();

        String firstName;
        String lastName;
        int attempts = 0;
        do {
            firstName = pick(female ? FEMALE_NAMES : MALE_NAMES, random);
            lastName = female ? feminine(pick(SURNAMES, random)) : pick(SURNAMES, random);
        } while (clashesWith(originalName, firstName, lastName) && ++attempts < 50);

        return new FakePerson(firstName, lastName, buildPesel(random, female));
    }

    /** Czy któryś człon tożsamości zastępczej pokrywa się z oryginałem. */
    private static boolean clashesWith(String originalName, String... parts) {
        if (originalName == null || originalName.isBlank()) {
            return false;
        }
        String upper = originalName.toUpperCase(Locale.ROOT);
        for (String part : parts) {
            // Porównanie po rdzeniu: „Kowalska" i „Kowalski" to dla tej reguły to samo
            String stem = stem(part.toUpperCase(Locale.ROOT));
            if (upper.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    private static String stem(String word) {
        return word.length() > 3 ? word.substring(0, word.length() - 1) : word;
    }

    /**
     * Nazwisko w formie żeńskiej. Dotyczy wyłącznie nazwisk przymiotnikowych —
     * „Mazur" czy „Dudek" kobieta nosi w tej samej postaci co mężczyzna.
     */
    private static String feminine(String surname) {
        return surname.endsWith("ski") || surname.endsWith("cki") || surname.endsWith("dzki")
                ? surname.substring(0, surname.length() - 1) + "a"
                : surname;
    }

    /**
     * Numer PESEL poprawny formalnie: istniejąca data urodzenia, cyfra płci zgodna
     * z imieniem i prawidłowa suma kontrolna.
     *
     * <p>Poprawność jest tu wymagana, a nie ozdobna: import odrzuca numery błędne,
     * więc plik demo z byle jakimi cyframi pokazywałby 171 odrzuceń zamiast danych.
     */
    private String buildPesel(Random random, boolean female) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int year = 1950 + random.nextInt(46);
            int month = 1 + random.nextInt(12);
            int day = 1 + random.nextInt(28);

            // Rocznik XXI wieku koduje się przez dodanie 20 do miesiąca
            int encodedMonth = year >= 2000 ? month + 20 : month;

            int serial = random.nextInt(500) * 2 + (female ? 0 : 1);
            String base = "%02d%02d%02d%04d".formatted(year % 100, encodedMonth, day, serial);
            String pesel = base + checksum(base);
            if (usedPesels.add(pesel)) {
                return pesel;
            }
        }
        throw new IllegalStateException("Nie udało się wygenerować unikalnego numeru PESEL.");
    }

    private static char checksum(String tenDigits) {
        int[] weights = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (tenDigits.charAt(i) - '0') * weights[i];
        }
        return (char) ('0' + (10 - (sum % 10)) % 10);
    }

    /** Numer telefonu zastępczy — ten sam oryginał zawsze daje ten sam numer. */
    public String phoneFor(String original) {
        return phones.computeIfAbsent(original, key -> {
            Random random = randomFor("phone", key);
            return pick(PHONE_PREFIXES, random) + "%07d".formatted(random.nextInt(10_000_000));
        });
    }

    /** Ulica zastępcza — nigdy ta sama, co w oryginale. */
    public String streetFor(String original) {
        return streets.computeIfAbsent(original, key -> {
            Random random = randomFor("street", key);
            String upper = key.toUpperCase(Locale.ROOT);
            String candidate;
            int attempts = 0;
            do {
                candidate = pick(STREETS, random);
            } while (upper.contains(candidate.toUpperCase(Locale.ROOT)) && ++attempts < 50);
            return candidate;
        });
    }

    public String houseNumberFor(String original) {
        return String.valueOf(1 + randomFor("house", original).nextInt(120));
    }

    public String flatNumberFor(String original) {
        return String.valueOf(1 + randomFor("flat", original).nextInt(60));
    }

    /**
     * Kod pocztowy z zachowanym okręgiem. Pierwsze dwie cyfry wskazują region,
     * a nie osobę — bez nich Lublin w danych demo miałby kody z drugiego końca kraju.
     */
    public String postalCodeFor(String original) {
        if (original == null || !original.matches("\\d{2}-\\d{3}")) {
            return "20-%03d".formatted(randomFor("postal", String.valueOf(original)).nextInt(1000));
        }
        return original.substring(0, 3) + "%03d".formatted(randomFor("postal", original).nextInt(1000));
    }

    /** Numer rachunku: 26 cyfr, bez związku z jakimkolwiek istniejącym kontem. */
    public String accountFor(String original) {
        return accounts.computeIfAbsent(original, key -> {
            Random random = randomFor("account", key);
            StringBuilder digits = new StringBuilder(26);
            while (digits.length() < 26) {
                digits.append(random.nextInt(10));
            }
            return digits.toString();
        });
    }

    public String employerFor(String original) {
        return employers.computeIfAbsent(original, key -> pick(EMPLOYERS, randomFor("employer", key)));
    }

    /**
     * Adresy wymyślone wskazują miejscowość, której nie ma — dzięki temu żadna nazwa
     * z pliku źródłowego nie może się w nich przypadkiem powtórzyć, a kontrola szczelności
     * nie potrzebuje wyjątków na miasta.
     */
    public static final String FICTIONAL_TOWN = "Wólka Przykładowa";

    public String employerAddressFor(String original) {
        Random random = randomFor("employerAddress", original);
        return "%s %d, 20-%03d %s".formatted(pick(BUSINESS_STREETS, random),
                1 + random.nextInt(90), random.nextInt(1000), FICTIONAL_TOWN);
    }

    /** Numer sprawy nadawany kolejno — numeracja wierzyciela nie wychodzi poza jego system. */
    public String caseNumberFor(String original) {
        return caseNumbers.computeIfAbsent(original,
                key -> "DEMO/%04d".formatted(caseNumbers.size() + 1));
    }

    public String courtSignatureFor(String original) {
        Random random = randomFor("signature", original);
        return "VI Nc-e %d/16".formatted(100_000 + random.nextInt(900_000));
    }

    /** Ile różnych osób zostało dotąd wytworzonych. */
    public int peopleCount() {
        return people.size();
    }

    private static <T> T pick(List<T> options, Random random) {
        return options.get(random.nextInt(options.size()));
    }

    private static Random randomFor(String scope, String key) {
        return new Random(SALT * 31 + (scope + '|' + key).hashCode());
    }

    /** Wszystkie wytworzone osoby — do sprawdzenia, czy nic się nie powtórzyło. */
    public List<FakePerson> allPeople() {
        return new ArrayList<>(people.values());
    }
}
