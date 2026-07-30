package com.collectorstools.demo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Co anonimizacja zrobiła z plikiem.
 *
 * <p>Raport jest częścią zabezpieczenia, nie ozdobą: kolumna i sekcja komentarza,
 * których narzędzie nie rozpoznało, są wycinane, a zliczenie ich tutaj jest jedynym
 * sposobem, żeby ktoś to zauważył. Cicha anonimizacja albo przepuszcza dane, których
 * nie miała przepuścić, albo kasuje dane, które miały zostać — i w obu przypadkach
 * wygląda tak samo.
 */
public class AnonymizationReport {

    private int rows;
    private int people;
    private int commentsRewritten;
    private int contactNotesReplaced;
    private int promisesKept;
    private final Set<String> blankedColumns = new LinkedHashSet<>();
    private final List<String> droppedSections = new ArrayList<>();

    void countRow() {
        rows++;
    }

    void setPeople(int people) {
        this.people = people;
    }

    void countCommentRewritten() {
        commentsRewritten++;
    }

    void countContactNoteReplaced() {
        contactNotesReplaced++;
    }

    void countPromiseKept() {
        promisesKept++;
    }

    void blankedColumn(String header) {
        blankedColumns.add(header);
    }

    void droppedSection(String label) {
        droppedSections.add(label);
    }

    public int getRows() {
        return rows;
    }

    public int getPeople() {
        return people;
    }

    public int getCommentsRewritten() {
        return commentsRewritten;
    }

    public int getContactNotesReplaced() {
        return contactNotesReplaced;
    }

    public int getPromisesKept() {
        return promisesKept;
    }

    public Set<String> getBlankedColumns() {
        return blankedColumns;
    }

    public List<String> getDroppedSections() {
        return droppedSections;
    }

    public String describe() {
        StringBuilder text = new StringBuilder()
                .append("Wierszy: ").append(rows).append('\n')
                .append("Osób zastępczych: ").append(people).append('\n')
                .append("Przepisanych komentarzy: ").append(commentsRewritten).append('\n')
                .append("Notatek z kontaktu zastąpionych tekstem zastępczym: ")
                .append(contactNotesReplaced).append('\n')
                .append("Zachowanych obietnic spłaty: ").append(promisesKept).append('\n');

        text.append("Wyczyszczone kolumny: ")
                .append(blankedColumns.isEmpty() ? "brak" : String.join(", ", blankedColumns))
                .append('\n');

        if (droppedSections.isEmpty()) {
            text.append("Nierozpoznanych sekcji komentarza: brak\n");
        } else {
            text.append("Usuniętych nierozpoznanych sekcji komentarza: ")
                    .append(droppedSections.size()).append('\n');
            droppedSections.stream().distinct().limit(10)
                    .forEach(section -> text.append("  - ").append(section).append('\n'));
        }
        return text.toString();
    }
}
