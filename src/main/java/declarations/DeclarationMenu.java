package declarations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import static declarations.DeclarationMenuUtils.*;

public class DeclarationMenu {
    private final Scanner sc = new Scanner(System.in);
    private static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static Map<LocalDate, Declaration> DB = new HashMap<>();
    DeclarationManagement declarationManagement = new DeclarationManagement();

//    static {
//        COMMANDS.put(1, "Stwórz deklarację");
//        COMMANDS.put(2, "Sprawdź najbliższe (od dziś do podanej liczby dni)/zakres dat");
//        COMMANDS.put(3, "Wszystkie deklaracje");
//        COMMANDS.put(4, "Edycja deklaracji (edytuj/usuń)");
//        COMMANDS.put(5, "Suma deklaracji (od dzisiaj do...)/(zakres dat)/(wszystkie)");
//        COMMANDS.put(6, "Plik deklaracji (podgląd)/(edycja)");
//        COMMANDS.put(9, "Wyjście");
//    }
    public void readAndExecute(){
        showMenu();
        int input = sc.nextInt();
        switch (input){
            case 1:
                addDeclaration();
                showMenu();
            case 2:
                declarationChecker();
                showMenu();
        }
    }

    private void declarationChecker() {
     showDeclarationsHandlerMenu();
     int menuInput = sc.nextInt();
     switch (menuInput){
         case 1:

     }
    }

    private void addDeclaration() {
        System.out.println("");
        System.out.println("Podaj deklarację w formacie dd/MM");
        String declarationDateIn = sc.next().concat("/2020");
        LocalDate declarationDate = LocalDate.parse(declarationDateIn, FORMATTER);
        System.out.println("Podaj kwotę");
        int declarationValue = sc.nextInt();
        System.out.println("Podaj numer sprawy");
        int declarationCaseNumber = sc.nextInt();

        Declaration declaration = new Declaration(declarationDate, declarationValue, declarationCaseNumber);
        declarationManagement.createDeclaration(declarationDate, declaration, DB);
    }


}
